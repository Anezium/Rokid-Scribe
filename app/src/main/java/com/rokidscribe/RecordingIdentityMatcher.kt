package com.rokidscribe

import com.rokidscribe.spp.RecordingOffer
import kotlin.math.abs

object RecordingIdentityMatcher {
    fun matches(recording: LocalRecording, offer: RecordingOffer): Boolean {
        val existingMd5 = recording.md5Hex?.trim().orEmpty()
        val offeredMd5 = offer.md5Hex.trim()
        if (existingMd5.isNotBlank() && offeredMd5.isNotBlank()) {
            return existingMd5.equals(offeredMd5, ignoreCase = true)
        }

        if (recording.id == offer.id) {
            return true
        }

        return recording.sizeBytes == offer.sizeBytes &&
            recording.sourceFileName.equals(offer.fileName, ignoreCase = true) &&
            abs(recording.durationMs - offer.durationMs) <= FALLBACK_DURATION_TOLERANCE_MS
    }

    private const val FALLBACK_DURATION_TOLERANCE_MS = 1_000L
}
