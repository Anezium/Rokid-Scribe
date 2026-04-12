package com.rokidscribe

import java.util.Locale

data class SpeakerSegment(
    val speakerTag: String?,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

fun LocalTranscript.speakerCountOrNull(): Int? {
    val count = segments.distinctSpeakerTags().size
    return count.takeIf { it > 0 }
}

fun LocalTranscript.hasMultipleSpeakers(): Boolean = (speakerCountOrNull() ?: 0) > 1

fun LocalTranscript.speakerBadgeText(): String? =
    speakerCountOrNull()?.takeIf { it > 1 }?.let { "$it SPK" }

fun LocalTranscript.displayText(): String {
    if (!hasMultipleSpeakers()) {
        return text.trim()
    }

    return segments
        .filter { it.text.isNotBlank() }
        .joinToString("\n\n") { segment ->
            buildString {
                append("[")
                append(displaySpeakerLabel(segment.speakerTag).uppercase(Locale.getDefault()))
                append("] ")
                append(formatSegmentClock(segment.startMs))
                append(" - ")
                append(formatSegmentClock(segment.endMs))
                append('\n')
                append(segment.text.trim())
            }
        }
        .ifBlank { text.trim() }
}

fun TranscriptProvider.displayNameFallback(): String = when (this) {
    TranscriptProvider.ELEVENLABS -> "ELEVENLABS"
    TranscriptProvider.ASSEMBLYAI -> "ASSEMBLYAI"
    TranscriptProvider.SPEECHMATICS -> "SPEECHMATICS"
    TranscriptProvider.DEEPGRAM -> "DEEPGRAM"
    TranscriptProvider.GROQ -> "GROQ"
}

fun providerDisplayLabel(providerId: String?): String {
    val provider = TranscriptProvider.entries.firstOrNull { it.name == providerId }
    return provider?.displayNameFallback()
        ?: providerId?.replace('_', ' ')?.uppercase(Locale.getDefault()).orEmpty()
}

fun displaySpeakerLabel(rawSpeakerTag: String?): String {
    val tag = rawSpeakerTag?.trim().orEmpty()
    if (tag.isBlank()) {
        return "Speaker"
    }

    Regex("^speaker_(\\d+)$", RegexOption.IGNORE_CASE)
        .matchEntire(tag)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.let { return "Speaker $it" }

    Regex("^[sS](\\d+)$")
        .matchEntire(tag)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.let { return "Speaker $it" }

    tag.toIntOrNull()?.let { return "Speaker ${it + 1}" }

    if (tag.length == 1 && tag[0].isLetter()) {
        return "Speaker ${tag.uppercase(Locale.getDefault())}"
    }

    return "Speaker ${tag.uppercase(Locale.getDefault())}"
}

private fun List<SpeakerSegment>.distinctSpeakerTags(): Set<String> {
    return mapNotNull { segment ->
        segment.speakerTag
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.toSet()
}

private fun formatSegmentClock(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
