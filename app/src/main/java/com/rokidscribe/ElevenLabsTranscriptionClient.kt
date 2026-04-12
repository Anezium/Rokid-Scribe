package com.rokidscribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class ElevenLabsTranscriptionClient : TranscriptionProviderClient {
    override val provider: TranscriptProvider = TranscriptProvider.ELEVENLABS

    override suspend fun fetchUsage(apiKey: String): Result<ProviderUsage> = withContext(Dispatchers.IO) {
        fetchSubscriptionUsage(apiKey).map { usage ->
            ProviderUsage(
                rows = listOf(
                    "PLAN" to formatTierLabel(usage.tier),
                    "USED" to "${formatQuotaCount(usage.usedCharacters)} / ${formatQuotaCount(usage.characterLimit)}",
                    "LEFT" to formatQuotaCount(usage.remainingCharacters),
                    "RESET" to formatReset(usage.nextResetUnixSeconds),
                ),
                footnote = if (usage.hasOverageAccess) {
                    "Overage is enabled after the plan limit."
                } else {
                    "Hard cap at the current plan limit."
                },
            )
        }
    }

    private suspend fun fetchSubscriptionUsage(apiKey: String): Result<SubscriptionUsage> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing ElevenLabs API key."))
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(SUBSCRIPTION_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                doInput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("xi-api-key", apiKey)
            }

            val code = connection.responseCode
            val responseBody = readText(
                if (code in 200..299) connection.inputStream else connection.errorStream,
            )

            if (code !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException("ElevenLabs HTTP $code: ${summarizeError(responseBody)}"),
                )
            }

            val payload = JSONObject(responseBody)
            Result.success(
                SubscriptionUsage(
                    tier = payload.optString("tier").takeIf { it.isNotBlank() },
                    usedCharacters = payload.optInt("character_count"),
                    characterLimit = payload.optInt("character_limit"),
                    nextResetUnixSeconds = payload.takeLongOrNull("next_character_count_reset_unix"),
                    canExtendCharacterLimit = payload.optBoolean("can_extend_character_limit"),
                    allowedToExtendCharacterLimit = payload.optBoolean("allowed_to_extend_character_limit"),
                ),
            )
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            connection?.disconnect()
        }
    }

    override suspend fun transcribe(
        apiKey: String,
        audioFile: File,
        languageCodeOverride: String?,
    ): Result<TranscriptResponse> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing ElevenLabs API key."))
        }
        if (!audioFile.exists()) {
            return@withContext Result.failure(IllegalArgumentException("Missing audio file: ${audioFile.absolutePath}"))
        }

        var connection: HttpURLConnection? = null
        try {
            val boundary = "----RokidScribe${System.currentTimeMillis()}"
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = 30_000
                readTimeout = 300_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("xi-api-key", apiKey)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(BufferedOutputStream(connection.outputStream)).use { output ->
                writeTextPart(output, boundary, "model_id", MODEL_ID)
                writeTextPart(output, boundary, "file_format", "other")
                writeTextPart(output, boundary, "timestamps_granularity", "word")
                writeTextPart(output, boundary, "tag_audio_events", "false")
                writeTextPart(output, boundary, "diarize", "true")
                languageCodeOverride?.takeIf { it.isNotBlank() }?.let { languageCode ->
                    writeTextPart(output, boundary, "language_code", languageCode)
                }
                writeFilePart(output, boundary, "file", audioFile)
                output.writeBytes("--$boundary--\r\n")
                output.flush()
            }

            val code = connection.responseCode
            val responseBody = readText(
                if (code in 200..299) connection.inputStream else connection.errorStream,
            )

            if (code !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException("ElevenLabs HTTP $code: ${summarizeError(responseBody)}"),
                )
            }

            val payload = JSONObject(responseBody)
            val transcriptText = payload.optString("text").trim()
            if (transcriptText.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("ElevenLabs returned an empty transcript."),
                )
            }

            val languageCode = payload.optString("language_code").takeIf { it.isNotBlank() }
            val words = payload.optJSONArray("words")
            val wordCount = words?.length()
                ?: transcriptText.split(WHITESPACE).count { it.isNotBlank() }
            val segments = buildSpeakerSegments(words)

            Result.success(
                TranscriptResponse(
                    providerId = provider.name,
                    modelId = MODEL_ID,
                    languageCode = languageCode,
                    text = transcriptText,
                    wordCount = wordCount,
                    segments = segments,
                ),
            )
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            connection?.disconnect()
        }
    }

    private fun writeTextPart(
        output: DataOutputStream,
        boundary: String,
        name: String,
        value: String,
    ) {
        output.writeBytes("--$boundary\r\n")
        output.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        output.writeBytes(value)
        output.writeBytes("\r\n")
    }

    private fun writeFilePart(
        output: DataOutputStream,
        boundary: String,
        name: String,
        file: File,
    ) {
        output.writeBytes("--$boundary\r\n")
        output.writeBytes(
            "Content-Disposition: form-data; name=\"$name\"; filename=\"${file.name}\"\r\n",
        )
        output.writeBytes("Content-Type: ${guessMimeType(file)}\r\n\r\n")
        file.inputStream().use { input ->
            input.copyTo(output)
        }
        output.writeBytes("\r\n")
    }

    private fun readText(stream: InputStream?): String {
        if (stream == null) {
            return ""
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun summarizeError(body: String): String {
        if (body.isBlank()) {
            return "No response body"
        }
        return runCatching {
            val json = JSONObject(body)
            when {
                json.optString("detail").isNotBlank() -> json.optString("detail")
                json.optString("message").isNotBlank() -> json.optString("message")
                else -> body
            }
        }.getOrDefault(body).replace(WHITESPACE, " ").trim().take(220)
    }

    private fun guessMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val ENDPOINT = "https://api.elevenlabs.io/v1/speech-to-text"
        private const val SUBSCRIPTION_ENDPOINT = "https://api.elevenlabs.io/v1/user/subscription"
        private const val MODEL_ID = "scribe_v2"
        private val WHITESPACE = Regex("\\s+")
    }

    private fun buildSpeakerSegments(words: JSONArray?): List<SpeakerSegment> {
        if (words == null) {
            return emptyList()
        }

        val tokens = mutableListOf<TranscriptToken>()
        var currentSpeaker: String? = null
        for (index in 0 until words.length()) {
            val item = words.optJSONObject(index) ?: continue
            val text = item.optString("text").trim()
            if (text.isBlank()) {
                continue
            }
            val type = item.optString("type").takeIf { it.isNotBlank() }
            val speakerTag = item.optString("speaker_id").takeIf { it.isNotBlank() }
                ?: currentSpeaker
            if (!speakerTag.isNullOrBlank()) {
                currentSpeaker = speakerTag
            }
            tokens += TranscriptToken(
                text = text,
                speakerTag = speakerTag,
                startMs = secondsToMs(item.optDouble("start", 0.0)),
                endMs = secondsToMs(item.optDouble("end", 0.0)),
                type = type,
            )
        }
        return buildSegmentsFromTokens(tokens)
    }

    private fun secondsToMs(value: Double): Long = (value * 1000.0).toLong()

    private fun formatQuotaCount(value: Int): String =
        String.format(java.util.Locale.getDefault(), "%,d", value)

    private fun formatTierLabel(rawTier: String?): String {
        if (rawTier.isNullOrBlank()) {
            return "UNKNOWN"
        }
        return rawTier.replace('_', ' ').trim().uppercase(java.util.Locale.getDefault())
    }

    private fun formatReset(resetUnixSeconds: Long?): String {
        val epochSeconds = resetUnixSeconds ?: return "UNKNOWN"
        return java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(epochSeconds * 1000))
            .uppercase(java.util.Locale.getDefault())
    }
}

private data class SubscriptionUsage(
    val tier: String?,
    val usedCharacters: Int,
    val characterLimit: Int,
    val nextResetUnixSeconds: Long?,
    val canExtendCharacterLimit: Boolean,
    val allowedToExtendCharacterLimit: Boolean,
) {
    val remainingCharacters: Int
        get() = (characterLimit - usedCharacters).coerceAtLeast(0)

    val hasOverageAccess: Boolean
        get() = canExtendCharacterLimit && allowedToExtendCharacterLimit
}

private fun JSONObject.takeLongOrNull(name: String): Long? {
    if (isNull(name) || !has(name)) {
        return null
    }
    val value = optLong(name, Long.MIN_VALUE)
    return value.takeUnless { it == Long.MIN_VALUE }
}
