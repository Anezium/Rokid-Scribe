package com.rokidscribe.glasses

import android.media.MediaRecorder
import com.rokidscribe.glasses.spp.RecordingDescriptor

class GlassesRecorder(
    private val repository: RecordingRepository,
) {
    private var recorder: MediaRecorder? = null
    private var activeDraft: RecordingDraft? = null
    private var startedAtEpochMs: Long = 0L

    fun isRecording(): Boolean = recorder != null

    fun recordingStartedAtEpochMs(): Long? = startedAtEpochMs.takeIf { recorder != null && it > 0L }

    fun start(): RecordingDraft {
        check(recorder == null) { "Recording is already in progress." }
        val draft = repository.createDraft()
        val mediaRecorder = MediaRecorder()
        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioSamplingRate(16_000)
            mediaRecorder.setAudioEncodingBitRate(64_000)
            mediaRecorder.setAudioChannels(1)
            mediaRecorder.setOutputFile(draft.audioFile.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
        } catch (error: Exception) {
            mediaRecorder.release()
            repository.discardDraft(draft)
            throw error
        }

        recorder = mediaRecorder
        activeDraft = draft
        startedAtEpochMs = draft.createdAtEpochMs
        return draft
    }

    fun stop(): RecordingDescriptor {
        val mediaRecorder = recorder ?: error("No active recording.")
        val draft = activeDraft ?: error("No active recording draft.")
        val durationMs = (System.currentTimeMillis() - startedAtEpochMs).coerceAtLeast(0L)

        return runCatching {
            mediaRecorder.stop()
            repository.finalizeDraft(draft, durationMs)
        }.recoverCatching { error ->
            repository.salvageDraft(draft)
                ?: throw IllegalStateException(
                    "Recording stopped unexpectedly and no audio could be recovered.",
                    error,
                )
        }.also {
            runCatching { mediaRecorder.reset() }
            runCatching { mediaRecorder.release() }
            recorder = null
            activeDraft = null
            startedAtEpochMs = 0L
        }.getOrThrow()
    }

    fun cancel() {
        val activeRecorder = recorder
        val draft = activeDraft
        runCatching { activeRecorder?.reset() }
        runCatching { activeRecorder?.release() }
        activeDraft = null
        recorder = null
        startedAtEpochMs = 0L
        if (draft != null) {
            repository.salvageDraft(draft) ?: repository.discardDraft(draft)
        }
    }
}
