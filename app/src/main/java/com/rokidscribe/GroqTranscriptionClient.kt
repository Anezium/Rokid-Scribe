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
import java.util.Locale

class GroqTranscriptionClient : TranscriptionProviderClient {
    override val provider: TranscriptProvider = TranscriptProvider.GROQ

    override suspend fun transcribe(
        apiKey: String,
        audioFile: File,
        languageCodeOverride: String?,
    ): Result<TranscriptResponse> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing Groq API key."))
        }
        if (!audioFile.exists()) {
            return@withContext Result.failure(IllegalArgumentException("Missing audio file: ${audioFile.absolutePath}"))
        }

        var connection: HttpURLConnection? = null
        val boundary = "----RokidScribeGroq${System.currentTimeMillis()}"
        try {
            connection = (URL(TRANSCRIPT_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = 30_000
                readTimeout = 300_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(BufferedOutputStream(connection.outputStream)).use { output ->
                writeTextPart(output, boundary, "model", MODEL_ID)
                writeTextPart(output, boundary, "response_format", "verbose_json")
                writeTextPart(output, boundary, "temperature", "0")
                writeTextPart(output, boundary, "timestamp_granularities[]", "segment")
                writeTextPart(output, boundary, "timestamp_granularities[]", "word")
                languageCodeOverride?.takeIf { it.isNotBlank() }?.let { languageCode ->
                    writeTextPart(output, boundary, "language", languageCode)
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
                    IllegalStateException("Groq HTTP $code: ${summarizeError(responseBody)}"),
                )
            }

            val payload = JSONObject(responseBody)
            val transcriptText = payload.optString("text").trim()
            if (transcriptText.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Groq returned an empty transcript."),
                )
            }

            val wordCount = payload.optJSONArray("words")?.length()
                ?: payload.optJSONArray("segments")?.let(::countSegmentWords)
                ?: transcriptText.split(WHITESPACE).count { it.isNotBlank() }
            val segments = buildSegments(payload.optJSONArray("segments"))

            Result.success(
                TranscriptResponse(
                    providerId = provider.name,
                    modelId = payload.optString("model").ifBlank { MODEL_ID },
                    languageCode = payload.optString("language").takeIf { it.isNotBlank() } ?: languageCodeOverride,
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

    override suspend fun fetchUsage(apiKey: String): Result<ProviderUsage> {
        return Result.failure(
            UnsupportedOperationException(
                "Groq does not expose a simple credits/spend endpoint for standard API keys in the app yet.",
            ),
        )
    }

    private fun countSegmentWords(segments: org.json.JSONArray): Int {
        var count = 0
        for (index in 0 until segments.length()) {
            count += segments.optJSONObject(index)?.optJSONArray("words")?.length() ?: 0
        }
        return count.takeIf { it > 0 } ?: 0
    }

    private fun buildSegments(segments: JSONArray?): List<SpeakerSegment> {
        if (segments == null) {
            return emptyList()
        }

        return buildSegmentsFromUtterances(
            buildList {
                for (index in 0 until segments.length()) {
                    val item = segments.optJSONObject(index) ?: continue
                    val text = item.optString("text").trim()
                    if (text.isBlank()) {
                        continue
                    }
                    add(
                        TranscriptUtterance(
                            speakerTag = null,
                            startMs = secondsToMs(item.optDouble("start", 0.0)),
                            endMs = secondsToMs(item.optDouble("end", 0.0)),
                            text = text,
                        ),
                    )
                }
            },
        )
    }

    private fun secondsToMs(value: Double): Long = (value * 1000.0).toLong()

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
        file.inputStream().use { input -> input.copyTo(output) }
        output.writeBytes("\r\n")
    }

    private fun guessMimeType(file: File): String {
        return when (file.extension.lowercase(Locale.getDefault())) {
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            else -> "application/octet-stream"
        }
    }

    private fun readText(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.bufferedReader().use { it.readText() }
    }

    private fun summarizeError(body: String): String {
        if (body.isBlank()) return "No response body"
        return runCatching {
            val json = JSONObject(body)
            when {
                json.optJSONObject("error")?.optString("message").orEmpty().isNotBlank() ->
                    json.optJSONObject("error")!!.optString("message")
                json.optString("error").isNotBlank() -> json.optString("error")
                json.optString("message").isNotBlank() -> json.optString("message")
                else -> body
            }
        }.getOrDefault(body).replace(WHITESPACE, " ").trim().take(220)
    }

    companion object {
        private const val TRANSCRIPT_ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val MODEL_ID = "whisper-large-v3"
        private val WHITESPACE = Regex("\\s+")
    }
}
