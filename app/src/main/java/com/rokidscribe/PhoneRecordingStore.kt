package com.rokidscribe

import android.content.Context
import com.rokidscribe.spp.RecordingOffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class PhoneRecordingStore(
    private val context: Context,
) {
    private val audioDir = File(context.filesDir, "imported-recordings")
    private val transcriptSuffix = ".transcript.json"
    private val transcriptIssueSuffix = ".transcript.issue.json"
    private val storeLock = Any()

    fun getStoragePath(): String = audioDir.absolutePath

    fun createTargetFile(offer: RecordingOffer): File {
        audioDir.mkdirs()
        val extension = offer.fileName.substringAfterLast('.', "m4a")
        val baseName = sanitize("${offer.id}_${offer.fileName.substringBeforeLast('.', offer.id)}")
        var index = 0
        while (true) {
            val suffix = if (index == 0) "" else "-$index"
            val candidate = File(audioDir, "$baseName$suffix.$extension")
            if (!candidate.exists()) {
                return candidate
            }
            index += 1
        }
    }

    suspend fun writeMetadata(
        targetFile: File,
        offer: RecordingOffer,
        sourceDeviceName: String?,
    ): LocalRecording = withContext(Dispatchers.IO) {
        audioDir.mkdirs()
        synchronized(storeLock) {
            findRecordingByOffer(offer)?.let { existing ->
                runCatching { targetFile.delete() }
                return@synchronized existing
            }

            val metadataFile = metadataFileFor(targetFile)
            val metadata = JSONObject()
                .put("id", offer.id)
                .put("fileName", offer.fileName)
                .put("sizeBytes", offer.sizeBytes)
                .put("durationMs", offer.durationMs)
                .put("createdAtEpochMs", offer.createdAtEpochMs)
                .put("md5Hex", offer.md5Hex)
                .put("importedAtEpochMs", System.currentTimeMillis())
                .put("sourceDeviceName", sourceDeviceName)
                .put("localAudioPath", targetFile.absolutePath)

            writeTextAtomically(metadataFile, metadata.toString(2))
            readRecording(metadataFile) ?: error("Unable to read back imported recording metadata.")
        }
    }

    suspend fun updateRecordingDisplayName(
        recording: LocalRecording,
        displayName: String?,
    ): LocalRecording = withContext(Dispatchers.IO) {
        synchronized(storeLock) {
            val metadataFile = File(recording.metadataPath)
            val payload = JSONObject(metadataFile.readText())
            val normalizedDisplayName = displayName?.trim()?.takeIf { it.isNotBlank() }
            if (normalizedDisplayName != null) {
                payload.put("displayName", normalizedDisplayName)
            } else {
                payload.remove("displayName")
            }
            writeTextAtomically(metadataFile, payload.toString(2))
            readRecording(metadataFile) ?: error("Unable to read back renamed recording metadata.")
        }
    }

    fun listRecordings(): List<LocalRecording> {
        audioDir.mkdirs()
        val metadataFiles = audioDir.listFiles { file ->
            file.isFile &&
                file.extension.equals("json", ignoreCase = true) &&
                !file.name.endsWith(transcriptSuffix, ignoreCase = true) &&
                !file.name.endsWith(transcriptIssueSuffix, ignoreCase = true)
        }.orEmpty()

        return metadataFiles.mapNotNull(::readRecording)
            .sortedByDescending { it.importedAtEpochMs }
    }

    fun findRecording(id: String): LocalRecording? {
        return listRecordings().firstOrNull { it.id == id }
    }

    fun findRecordingByMetadataPath(metadataPath: String): LocalRecording? {
        return listRecordings().firstOrNull { it.metadataPath == metadataPath }
    }

    fun findRecordingByOffer(offer: RecordingOffer): LocalRecording? {
        return listRecordings().firstOrNull { recording ->
            RecordingIdentityMatcher.matches(recording, offer)
        }
    }

    fun readTranscript(recording: LocalRecording): LocalTranscript? {
        return readTranscriptFile(transcriptFileFor(recording.metadataFile))
    }

    suspend fun writeTranscript(
        recording: LocalRecording,
        response: TranscriptResponse,
    ): LocalTranscript = withContext(Dispatchers.IO) {
        val transcript = LocalTranscript(
            providerId = response.providerId,
            modelId = response.modelId,
            languageCode = response.languageCode,
            text = response.text,
            createdAtEpochMs = System.currentTimeMillis(),
            wordCount = response.wordCount,
            sourceAudioPath = recording.localAudioPath,
            segments = response.segments,
        )

        val payload = JSONObject()
            .put("providerId", transcript.providerId)
            .put("modelId", transcript.modelId)
            .put("languageCode", transcript.languageCode)
            .put("text", transcript.text)
            .put("createdAtEpochMs", transcript.createdAtEpochMs)
            .put("wordCount", transcript.wordCount)
            .put("sourceAudioPath", transcript.sourceAudioPath)
            .put("sourceFileName", recording.sourceFileName)
            .put("segments", transcript.segments.toJson())

        writeTextAtomically(transcriptFileFor(recording.metadataFile), payload.toString(2))
        transcriptIssueFileFor(recording.metadataFile).delete()
        transcript
    }

    suspend fun writeTranscriptIssue(
        recording: LocalRecording,
        issue: TranscriptIssue,
    ): TranscriptIssue = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("providerId", issue.providerId)
            .put("kind", issue.kind.name)
            .put("message", issue.message)
            .put("createdAtEpochMs", issue.createdAtEpochMs)
            .put("requestedLanguageLabel", issue.requestedLanguageLabel)

        writeTextAtomically(transcriptIssueFileFor(recording.metadataFile), payload.toString(2))
        issue
    }

    suspend fun clearTranscriptIssue(recording: LocalRecording) = withContext(Dispatchers.IO) {
        transcriptIssueFileFor(recording.metadataFile).delete()
    }

    suspend fun deleteRecording(recording: LocalRecording) = withContext(Dispatchers.IO) {
        runCatching { File(recording.localAudioPath).delete() }
        runCatching { File(recording.metadataPath).delete() }
        runCatching { transcriptFileFor(recording.metadataFile).delete() }
        runCatching { transcriptIssueFileFor(recording.metadataFile).delete() }
    }

    private fun sanitize(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun readRecording(metadataFile: File): LocalRecording? {
        return runCatching {
            val payload = JSONObject(metadataFile.readText())
            val localAudioPath = payload.optString("localAudioPath")
            if (localAudioPath.isBlank()) {
                return@runCatching null
            }

            val audioFile = File(localAudioPath)
            if (!audioFile.exists()) {
                return@runCatching null
            }

            LocalRecording(
                id = payload.optString("id").ifBlank { metadataFile.nameWithoutExtension },
                sourceFileName = payload.optString("fileName").ifBlank { audioFile.name },
                displayName = payload.optString("displayName").takeIf { it.isNotBlank() },
                sizeBytes = payload.optLong("sizeBytes"),
                durationMs = payload.optLong("durationMs"),
                createdAtEpochMs = payload.optLong("createdAtEpochMs"),
                importedAtEpochMs = payload.optLong("importedAtEpochMs"),
                md5Hex = payload.optString("md5Hex").takeIf { it.isNotBlank() },
                sourceDeviceName = payload.optString("sourceDeviceName").takeIf { it.isNotBlank() },
                localAudioPath = localAudioPath,
                metadataPath = metadataFile.absolutePath,
                transcript = readTranscriptFile(transcriptFileFor(metadataFile)),
                transcriptIssue = readTranscriptIssueFile(transcriptIssueFileFor(metadataFile)),
            )
        }.getOrNull()
    }

    private fun readTranscriptFile(file: File): LocalTranscript? {
        if (!file.exists()) {
            return null
        }

        return runCatching {
            val payload = JSONObject(file.readText())
            LocalTranscript(
                providerId = payload.optString("providerId")
                    .ifBlank { TranscriptProvider.ELEVENLABS.name },
                modelId = payload.optString("modelId").ifBlank { "scribe_v2" },
                languageCode = payload.optString("languageCode").takeIf { it.isNotBlank() },
                text = payload.optString("text"),
                createdAtEpochMs = payload.optLong("createdAtEpochMs"),
                wordCount = payload.optInt("wordCount"),
                sourceAudioPath = payload.optString("sourceAudioPath"),
                segments = payload.optJSONArray("segments").toSpeakerSegments(),
            )
        }.getOrNull()
    }

    private fun transcriptFileFor(metadataFile: File): File {
        return File(metadataFile.parentFile, "${metadataFile.nameWithoutExtension}$transcriptSuffix")
    }

    private fun transcriptIssueFileFor(metadataFile: File): File {
        return File(metadataFile.parentFile, "${metadataFile.nameWithoutExtension}$transcriptIssueSuffix")
    }

    private fun metadataFileFor(targetFile: File): File {
        return File(targetFile.parentFile, "${targetFile.nameWithoutExtension}.json")
    }

    private fun readTranscriptIssueFile(file: File): TranscriptIssue? {
        if (!file.exists()) {
            return null
        }

        return runCatching {
            val payload = JSONObject(file.readText())
            val kind = payload.optString("kind")
                .takeIf { it.isNotBlank() }
                ?.let(TranscriptIssueKind::valueOf)
                ?: return@runCatching null

            TranscriptIssue(
                providerId = payload.optString("providerId")
                    .ifBlank { TranscriptProvider.ELEVENLABS.name },
                kind = kind,
                message = payload.optString("message").ifBlank { "Unknown transcription error." },
                createdAtEpochMs = payload.optLong("createdAtEpochMs"),
                requestedLanguageLabel = payload.optString("requestedLanguageLabel")
                    .takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    private fun writeTextAtomically(targetFile: File, content: String) {
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        FileOutputStream(tempFile).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
            runCatching { output.fd.sync() }
        }
        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
    }
}

private fun List<SpeakerSegment>.toJson(): JSONArray = JSONArray().apply {
    forEach { segment ->
        put(
            JSONObject()
                .put("speakerTag", segment.speakerTag)
                .put("startMs", segment.startMs)
                .put("endMs", segment.endMs)
                .put("text", segment.text),
        )
    }
}

private fun JSONArray?.toSpeakerSegments(): List<SpeakerSegment> {
    if (this == null) {
        return emptyList()
    }

    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val text = item.optString("text").trim()
            if (text.isBlank()) {
                continue
            }
            add(
                SpeakerSegment(
                    speakerTag = item.optString("speakerTag").takeIf { it.isNotBlank() },
                    startMs = item.optLong("startMs"),
                    endMs = item.optLong("endMs"),
                    text = text,
                ),
            )
        }
    }
}
