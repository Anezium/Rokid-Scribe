package com.rokidscribe.glasses

import android.content.Context
import android.media.MediaMetadataRetriever
import com.rokidscribe.glasses.spp.RecordingDescriptor
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingDraft(
    val id: String,
    val audioFile: File,
    val createdAtEpochMs: Long,
    val draftFile: File,
)

class RecordingRepository(
    private val context: Context,
) {
    companion object {
        private const val PREFS_NAME = "rokid_scribe_recordings"
        private const val KEY_IMPORTED_IDS = "imported_ids"
        private const val DRAFT_SUFFIX = ".draft.json"
        private val AUDIO_EXTENSIONS = setOf("aac", "m4a")
    }

    private val recordingDir = File(context.filesDir, "recordings")
    private val timeFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    fun createDraft(nowEpochMs: Long = System.currentTimeMillis()): RecordingDraft {
        recordingDir.mkdirs()
        val id = "note_${timeFormat.format(Date(nowEpochMs))}"
        val draft = RecordingDraft(
            id = id,
            audioFile = File(recordingDir, "$id.aac"),
            createdAtEpochMs = nowEpochMs,
            draftFile = draftFileFor(id),
        )
        writeJsonAtomically(
            draft.draftFile,
            JSONObject()
                .put("id", draft.id)
                .put("fileName", draft.audioFile.name)
                .put("createdAtEpochMs", draft.createdAtEpochMs)
                .put("startedAtEpochMs", draft.createdAtEpochMs)
                .put("state", "recording"),
        )
        return draft
    }

    fun discardDraft(draft: RecordingDraft) {
        runCatching { draft.audioFile.delete() }
        runCatching { draft.draftFile.delete() }
        metadataFileFor(draft.id).delete()
    }

    fun finalizeDraft(
        draft: RecordingDraft,
        durationMs: Long,
    ): RecordingDescriptor {
        val descriptor = RecordingDescriptor(
            id = draft.id,
            fileName = draft.audioFile.name,
            sizeBytes = draft.audioFile.length(),
            durationMs = durationMs,
            createdAtEpochMs = draft.createdAtEpochMs,
        )
        writeDescriptor(descriptor)
        runCatching { draft.draftFile.delete() }
        clearImportedMark(descriptor.id)
        return descriptor
    }

    fun salvageDraft(draft: RecordingDraft): RecordingDescriptor? {
        if (!draft.audioFile.exists() || draft.audioFile.length() <= 0L) {
            discardDraft(draft)
            return null
        }

        val durationMs = estimateDurationMs(draft.audioFile, draft.createdAtEpochMs)
        return finalizeDraft(draft, durationMs)
    }

    fun recoverInterruptedRecordings(): List<RecordingDescriptor> {
        recordingDir.mkdirs()
        val recovered = mutableListOf<RecordingDescriptor>()
        val recoveredIds = mutableSetOf<String>()

        draftFiles().forEach { draftFile ->
            val snapshot = readDraftSnapshot(draftFile) ?: run {
                runCatching { draftFile.delete() }
                return@forEach
            }

            recoveredIds += snapshot.id
            if (metadataFileFor(snapshot.id).exists()) {
                runCatching { draftFile.delete() }
                return@forEach
            }

            val audioFile = resolveAudioFile(snapshot.fileName, snapshot.id)
            if (audioFile == null || !audioFile.exists() || audioFile.length() <= 0L) {
                cleanupArtifacts(snapshot.id, audioFile, draftFile)
                return@forEach
            }

            val draft = RecordingDraft(
                id = snapshot.id,
                audioFile = audioFile,
                createdAtEpochMs = snapshot.createdAtEpochMs,
                draftFile = draftFile,
            )
            salvageDraft(draft)?.let(recovered::add)
        }

        listAudioFiles().forEach { audioFile ->
            val id = audioFile.nameWithoutExtension
            if (id in recoveredIds) {
                return@forEach
            }
            if (metadataFileFor(id).exists() || draftFileFor(id).exists()) {
                return@forEach
            }
            if (audioFile.length() <= 0L) {
                runCatching { audioFile.delete() }
                return@forEach
            }

            val recoveredDescriptor = RecordingDescriptor(
                id = id,
                fileName = audioFile.name,
                sizeBytes = audioFile.length(),
                durationMs = estimateDurationMs(audioFile, audioFile.lastModified()),
                createdAtEpochMs = audioFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
            writeDescriptor(recoveredDescriptor)
            clearImportedMark(id)
            recovered += recoveredDescriptor
        }

        return recovered.sortedByDescending { it.createdAtEpochMs }
    }

    fun listPending(): List<RecordingDescriptor> {
        val importedIds = importedIds()
        return listAll()
            .filterNot { it.id in importedIds }
            .sortedByDescending { it.createdAtEpochMs }
    }

    fun listAll(): List<RecordingDescriptor> {
        recordingDir.mkdirs()
        return recordingDir.listFiles { file -> file.extension.equals("json", ignoreCase = true) }
            ?.mapNotNull { file -> readDescriptor(file) }
            ?.filter { descriptor -> audioFileFor(descriptor).exists() }
            ?.sortedByDescending { it.createdAtEpochMs }
            .orEmpty()
    }

    fun resolvePending(itemIds: List<String>): List<RecordingDescriptor> {
        val pendingById = listPending().associateBy { it.id }
        return itemIds.mapNotNull(pendingById::get)
    }

    fun audioFileFor(id: String): File {
        val descriptor = readDescriptor(metadataFileFor(id))
        return if (descriptor != null) {
            audioFileFor(descriptor)
        } else {
            resolveAudioFile(fileName = null, id = id) ?: File(recordingDir, "$id.aac")
        }
    }

    fun audioFileFor(descriptor: RecordingDescriptor): File {
        return resolveAudioFile(descriptor.fileName, descriptor.id) ?: File(recordingDir, descriptor.fileName)
    }

    fun markImported(itemIds: List<String>) {
        val updated = importedIds().toMutableSet()
        updated.addAll(itemIds)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_IMPORTED_IDS, updated)
            .apply()
    }

    private fun clearImportedMark(id: String) {
        val updated = importedIds().toMutableSet()
        updated.remove(id)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_IMPORTED_IDS, updated)
            .apply()
    }

    private fun importedIds(): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_IMPORTED_IDS, emptySet())
            ?.toSet()
            .orEmpty()
    }

    private fun metadataFileFor(id: String): File = File(recordingDir, "$id.json")

    private fun draftFileFor(id: String): File = File(recordingDir, "$id$DRAFT_SUFFIX")

    private fun readDescriptor(metadataFile: File): RecordingDescriptor? {
        if (!metadataFile.exists() || metadataFile.name.endsWith(DRAFT_SUFFIX, ignoreCase = true)) {
            return null
        }
        return runCatching {
            val payload = JSONObject(metadataFile.readText())
            RecordingDescriptor(
                id = payload.getString("id"),
                fileName = payload.getString("fileName"),
                sizeBytes = payload.getLong("sizeBytes"),
                durationMs = payload.optLong("durationMs", 0L),
                createdAtEpochMs = payload.optLong("createdAtEpochMs", 0L),
            )
        }.getOrNull()
    }

    private fun writeDescriptor(descriptor: RecordingDescriptor) {
        writeJsonAtomically(
            metadataFileFor(descriptor.id),
            JSONObject()
                .put("id", descriptor.id)
                .put("fileName", descriptor.fileName)
                .put("sizeBytes", descriptor.sizeBytes)
                .put("durationMs", descriptor.durationMs)
                .put("createdAtEpochMs", descriptor.createdAtEpochMs),
        )
    }

    private fun writeJsonAtomically(target: File, payload: JSONObject) {
        target.parentFile?.mkdirs()
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tempFile).use { output ->
            output.write(payload.toString(2).toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        if (target.exists()) {
            runCatching { target.delete() }
        }
        check(tempFile.renameTo(target)) { "Unable to persist ${target.name}." }
    }

    private fun draftFiles(): List<File> {
        return recordingDir.listFiles { file ->
            file.isFile && (
                file.name.endsWith(DRAFT_SUFFIX, ignoreCase = true) ||
                    file.name.endsWith("$DRAFT_SUFFIX.tmp", ignoreCase = true)
            )
        }?.toList().orEmpty()
    }

    private fun listAudioFiles(): List<File> {
        return recordingDir.listFiles { file ->
            file.isFile && file.extension.lowercase(Locale.US) in AUDIO_EXTENSIONS
        }?.toList().orEmpty()
    }

    private fun resolveAudioFile(fileName: String?, id: String): File? {
        if (!fileName.isNullOrBlank()) {
            val direct = File(recordingDir, fileName)
            if (direct.exists()) {
                return direct
            }
        }

        return listAudioFiles()
            .firstOrNull { it.nameWithoutExtension == id }
    }

    private fun cleanupArtifacts(
        id: String,
        audioFile: File?,
        draftFile: File,
    ) {
        runCatching { audioFile?.delete() }
        runCatching { metadataFileFor(id).delete() }
        runCatching { draftFile.delete() }
    }

    private fun readDraftSnapshot(file: File): DraftSnapshot? {
        return runCatching {
            val payload = JSONObject(file.readText())
            DraftSnapshot(
                id = payload.getString("id"),
                fileName = payload.optString("fileName").takeIf { it.isNotBlank() },
                createdAtEpochMs = payload.optLong("createdAtEpochMs", System.currentTimeMillis()),
            )
        }.getOrNull()
    }

    private fun estimateDurationMs(
        audioFile: File,
        startedAtEpochMs: Long,
    ): Long {
        val metadataDuration = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(audioFile.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()

        if (metadataDuration != null && metadataDuration > 0L) {
            return metadataDuration
        }

        val endedAtEpochMs = audioFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        return (endedAtEpochMs - startedAtEpochMs).coerceAtLeast(0L)
    }

    private data class DraftSnapshot(
        val id: String,
        val fileName: String?,
        val createdAtEpochMs: Long,
    )
}
