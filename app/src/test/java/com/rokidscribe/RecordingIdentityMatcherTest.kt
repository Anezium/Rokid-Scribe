package com.rokidscribe

import com.rokidscribe.spp.RecordingOffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingIdentityMatcherTest {
    @Test
    fun matchesSameIdAndMd5() {
        val recording = recording(md5Hex = "aabbcc")
        val offer = offer(md5Hex = "AABBCC")

        assertTrue(RecordingIdentityMatcher.matches(recording, offer))
    }

    @Test
    fun doesNotMatchDifferentMd5() {
        val recording = recording(md5Hex = "aabbcc")
        val offer = offer(md5Hex = "ddeeff")

        assertFalse(RecordingIdentityMatcher.matches(recording, offer))
    }

    @Test
    fun fallsBackToSizeAndFileNameWhenMd5Missing() {
        val recording = recording(md5Hex = null)
        val offer = offer(md5Hex = "")

        assertTrue(RecordingIdentityMatcher.matches(recording, offer))
    }

    @Test
    fun matchesSameMd5EvenWhenIdsDiffer() {
        val recording = recording(id = "stored-note", md5Hex = "aabbcc")
        val offer = offer(id = "incoming-note", md5Hex = "AABBCC")

        assertTrue(RecordingIdentityMatcher.matches(recording, offer))
    }

    @Test
    fun doesNotMatchDifferentMd5EvenWhenIdsMatch() {
        val recording = recording(md5Hex = "aabbcc")
        val offer = offer(md5Hex = "ddeeff")

        assertFalse(RecordingIdentityMatcher.matches(recording, offer))
    }

    @Test
    fun fallsBackToStableFileShapeWhenMd5MissingAndIdsDiffer() {
        val recording = recording(id = "stored-note", md5Hex = null)
        val offer = offer(id = "incoming-note", md5Hex = "")

        assertTrue(RecordingIdentityMatcher.matches(recording, offer))
    }

    @Test
    fun doesNotFallbackWhenDurationDriftsTooMuch() {
        val recording = recording(id = "stored-note", md5Hex = null)
        val offer = offer(
            id = "incoming-note",
            md5Hex = "",
            durationMs = recording.durationMs + 5_000,
        )

        assertFalse(RecordingIdentityMatcher.matches(recording, offer))
    }

    private fun recording(
        id: String = "note_1",
        md5Hex: String?,
    ): LocalRecording {
        return LocalRecording(
            id = id,
            sourceFileName = "voice.m4a",
            displayName = null,
            sizeBytes = 1_024,
            durationMs = 1_000,
            createdAtEpochMs = 1_700_000_000_000,
            importedAtEpochMs = 1_700_000_000_100,
            md5Hex = md5Hex,
            sourceDeviceName = "Rokid",
            localAudioPath = "/tmp/voice.m4a",
            metadataPath = "/tmp/voice.json",
            transcript = null,
            transcriptIssue = null,
        )
    }

    private fun offer(
        id: String = "note_1",
        md5Hex: String,
        durationMs: Long = 1_000,
    ): RecordingOffer {
        return RecordingOffer(
            id = id,
            fileName = "voice.m4a",
            sizeBytes = 1_024,
            durationMs = durationMs,
            createdAtEpochMs = 1_700_000_000_000,
            md5Hex = md5Hex,
        )
    }
}
