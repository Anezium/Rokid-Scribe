package com.rokidscribe

internal data class TranscriptToken(
    val text: String,
    val speakerTag: String?,
    val startMs: Long,
    val endMs: Long,
    val type: String? = null,
)

internal data class TranscriptUtterance(
    val speakerTag: String?,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

internal fun buildSegmentsFromTokens(tokens: List<TranscriptToken>): List<SpeakerSegment> {
    if (tokens.isEmpty()) {
        return emptyList()
    }

    val segments = mutableListOf<SpeakerSegment>()
    val buffer = mutableListOf<TranscriptToken>()
    var activeSpeaker: String? = null

    fun flush() {
        if (buffer.isEmpty()) {
            return
        }
        val text = renderTokenText(buffer).trim()
        if (text.isNotBlank()) {
            segments += SpeakerSegment(
                speakerTag = activeSpeaker,
                startMs = buffer.first().startMs,
                endMs = buffer.last().endMs,
                text = text,
            )
        }
        buffer.clear()
    }

    for (token in tokens) {
        val speaker = token.speakerTag?.trim()?.takeIf { it.isNotBlank() }
        if (buffer.isEmpty()) {
            activeSpeaker = speaker
        } else if (speaker != activeSpeaker) {
            flush()
            activeSpeaker = speaker
        }
        buffer += token
    }
    flush()
    return segments
}

internal fun buildSegmentsFromUtterances(utterances: List<TranscriptUtterance>): List<SpeakerSegment> {
    return utterances.mapNotNull { utterance ->
        val text = utterance.text.trim()
        if (text.isBlank()) {
            null
        } else {
            SpeakerSegment(
                speakerTag = utterance.speakerTag?.trim()?.takeIf { it.isNotBlank() },
                startMs = utterance.startMs,
                endMs = utterance.endMs,
                text = text,
            )
        }
    }
}

private fun renderTokenText(tokens: List<TranscriptToken>): String {
    val builder = StringBuilder()
    tokens.forEachIndexed { index, token ->
        val text = token.text.trim()
        if (text.isBlank()) {
            return@forEachIndexed
        }

        val previousChar = builder.lastOrNull()
        val tokenType = token.type?.lowercase()
        val needsLeadingSpace = when {
            index == 0 -> false
            tokenType == "punctuation" -> false
            text.isStandaloneClosingPunctuation() -> false
            text.isContractionLike() -> false
            previousChar == null -> false
            previousChar in setOf('(', '[', '{', '"', '\'', '\n') -> false
            else -> true
        }

        if (needsLeadingSpace) {
            builder.append(' ')
        }
        builder.append(text)
    }
    return builder.toString().trim()
}

private fun String.isStandaloneClosingPunctuation(): Boolean =
    matches(Regex("^[,.;:!?%\\)\\]\\}]+$"))

private fun String.isContractionLike(): Boolean =
    startsWith("'") || startsWith("\u2019")
