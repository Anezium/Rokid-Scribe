package com.rokidscribe

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptSegmentBuildersTest {
    @Test
    fun keepsCurlyApostropheContractionsTight() {
        val segments = buildSegmentsFromTokens(
            listOf(
                TranscriptToken(text = "I", speakerTag = "Speaker 1", startMs = 0, endMs = 100),
                TranscriptToken(text = "\u2019m", speakerTag = "Speaker 1", startMs = 100, endMs = 180),
                TranscriptToken(text = "ready", speakerTag = "Speaker 1", startMs = 180, endMs = 320),
            ),
        )

        assertEquals(1, segments.size)
        assertEquals("I\u2019m ready", segments.single().text)
    }
}
