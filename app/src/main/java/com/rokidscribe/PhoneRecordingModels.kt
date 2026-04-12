package com.rokidscribe

import java.io.File

data class LocalRecording(
    val id: String,
    val sourceFileName: String,
    val displayName: String?,
    val sizeBytes: Long,
    val durationMs: Long,
    val createdAtEpochMs: Long,
    val importedAtEpochMs: Long,
    val md5Hex: String?,
    val sourceDeviceName: String?,
    val localAudioPath: String,
    val metadataPath: String,
    val transcript: LocalTranscript?,
    val transcriptIssue: TranscriptIssue?,
) {
    val displayTitle: String
        get() = displayName?.trim()?.takeIf { it.isNotBlank() }
            ?: sourceFileName.substringBeforeLast('.', sourceFileName)

    val audioFile: File
        get() = File(localAudioPath)

    val metadataFile: File
        get() = File(metadataPath)
}

data class LocalTranscript(
    val providerId: String,
    val modelId: String,
    val languageCode: String?,
    val text: String,
    val createdAtEpochMs: Long,
    val wordCount: Int,
    val sourceAudioPath: String,
    val segments: List<SpeakerSegment> = emptyList(),
)

data class TranscriptResponse(
    val providerId: String,
    val modelId: String,
    val languageCode: String?,
    val text: String,
    val wordCount: Int,
    val segments: List<SpeakerSegment> = emptyList(),
)

data class ExportedDocument(
    val displayName: String,
    val locationLabel: String,
    val uriString: String? = null,
)
