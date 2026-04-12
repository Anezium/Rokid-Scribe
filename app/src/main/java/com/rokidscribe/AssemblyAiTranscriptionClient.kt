package com.rokidscribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class AssemblyAiTranscriptionClient : TranscriptionProviderClient {
    override val provider: TranscriptProvider = TranscriptProvider.ASSEMBLYAI

    override suspend fun transcribe(
        apiKey: String,
        audioFile: File,
        languageCodeOverride: String?,
    ): Result<TranscriptResponse> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing AssemblyAI API key."))
        }
        if (!audioFile.exists()) {
            return@withContext Result.failure(IllegalArgumentException("Missing audio file: ${audioFile.absolutePath}"))
        }

        return@withContext runCatching {
            val uploadUrl = uploadAudio(apiKey, audioFile)
            val transcriptId = submitTranscript(apiKey, uploadUrl, languageCodeOverride)
            val payload = pollTranscript(apiKey, transcriptId)
            bestEffortDeleteTranscript(apiKey, transcriptId)

            val transcriptText = payload.optString("text").trim()
            if (transcriptText.isBlank()) {
                error("AssemblyAI returned an empty transcript.")
            }

            val modelId = payload.optString("speech_model_used")
                .takeIf { it.isNotBlank() }
                ?: listOf(
                    payload.optString("acoustic_model"),
                    payload.optString("language_model"),
                ).filter { it.isNotBlank() }.joinToString("+").ifBlank { "assemblyai" }

            TranscriptResponse(
                providerId = provider.name,
                modelId = modelId,
                languageCode = payload.optString("language_code").takeIf { it.isNotBlank() },
                text = transcriptText,
                wordCount = payload.optJSONArray("words")?.length()
                    ?: transcriptText.split(WHITESPACE).count { it.isNotBlank() },
                segments = buildSpeakerSegments(payload),
            )
        }
    }

    private fun uploadAudio(apiKey: String, audioFile: File): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(UPLOAD_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = 30_000
                readTimeout = 300_000
                setChunkedStreamingMode(0)
                setRequestProperty("Authorization", apiKey)
                setRequestProperty("Content-Type", "application/octet-stream")
            }

            DataOutputStream(BufferedOutputStream(connection.outputStream)).use { output ->
                audioFile.inputStream().use { input -> input.copyTo(output) }
                output.flush()
            }

            val code = connection.responseCode
            val responseBody = readText(
                if (code in 200..299) connection.inputStream else connection.errorStream,
            )
            if (code !in 200..299) {
                error("AssemblyAI upload HTTP $code: ${summarizeError(responseBody)}")
            }

            val payload = JSONObject(responseBody)
            return payload.optString("upload_url").ifBlank {
                error("AssemblyAI did not return an upload_url.")
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun submitTranscript(
        apiKey: String,
        uploadUrl: String,
        languageCodeOverride: String?,
    ): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(TRANSCRIPT_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("Authorization", apiKey)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
            }

            val request = JSONObject()
                .put("audio_url", uploadUrl)
                .put(
                    "speech_models",
                    org.json.JSONArray().apply {
                        put(PRIMARY_SPEECH_MODEL)
                        put(FALLBACK_SPEECH_MODEL)
                    },
                )
                .put("speaker_labels", true)

            if (languageCodeOverride.isNullOrBlank()) {
                request.put("language_detection", true)
            } else {
                request.put("language_code", languageCodeOverride)
            }

            val requestBody = request.toString()

            DataOutputStream(connection.outputStream).use { output ->
                output.write(requestBody.toByteArray(Charsets.UTF_8))
                output.flush()
            }

            val code = connection.responseCode
            val responseBody = readText(
                if (code in 200..299) connection.inputStream else connection.errorStream,
            )
            if (code !in 200..299) {
                error("AssemblyAI submit HTTP $code: ${summarizeError(responseBody)}")
            }

            return JSONObject(responseBody).optString("id").ifBlank {
                error("AssemblyAI did not return a transcript id.")
            }
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun pollTranscript(apiKey: String, transcriptId: String): JSONObject {
        repeat(MAX_POLLS) {
            val payload = getTranscript(apiKey, transcriptId)
            when (payload.optString("status").lowercase()) {
                "completed" -> return payload
                "error" -> {
                    error(
                        payload.optString("error")
                            .ifBlank { "AssemblyAI transcription failed." },
                    )
                }
            }
            delay(POLL_DELAY_MS)
        }
        error("AssemblyAI transcription timed out.")
    }

    private fun getTranscript(apiKey: String, transcriptId: String): JSONObject {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$TRANSCRIPT_ENDPOINT/$transcriptId").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                doInput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Authorization", apiKey)
                setRequestProperty("Accept", "application/json")
            }

            val code = connection.responseCode
            val responseBody = readText(
                if (code in 200..299) connection.inputStream else connection.errorStream,
            )
            if (code !in 200..299) {
                error("AssemblyAI poll HTTP $code: ${summarizeError(responseBody)}")
            }

            return JSONObject(responseBody)
        } finally {
            connection?.disconnect()
        }
    }

    private fun bestEffortDeleteTranscript(apiKey: String, transcriptId: String) {
        runCatching {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("$TRANSCRIPT_ENDPOINT/$transcriptId").openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    doInput = true
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Authorization", apiKey)
                }
                connection.responseCode
            } finally {
                connection?.disconnect()
            }
        }
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
                json.optString("error").isNotBlank() -> json.optString("error")
                json.optString("detail").isNotBlank() -> json.optString("detail")
                json.optString("message").isNotBlank() -> json.optString("message")
                else -> body
            }
        }.getOrDefault(body).replace(WHITESPACE, " ").trim().take(220)
    }

    companion object {
        private const val UPLOAD_ENDPOINT = "https://api.assemblyai.com/v2/upload"
        private const val TRANSCRIPT_ENDPOINT = "https://api.assemblyai.com/v2/transcript"
        private const val MAX_POLLS = 240
        private const val POLL_DELAY_MS = 1_500L
        private const val PRIMARY_SPEECH_MODEL = "universal-3-pro"
        private const val FALLBACK_SPEECH_MODEL = "universal-2"
        private val WHITESPACE = Regex("\\s+")
    }

    private fun buildSpeakerSegments(payload: JSONObject): List<SpeakerSegment> {
        val utterances = payload.optJSONArray("utterances") ?: return emptyList()
        return buildSegmentsFromUtterances(
            buildList {
                for (index in 0 until utterances.length()) {
                    val item = utterances.optJSONObject(index) ?: continue
                    val text = item.optString("text").trim()
                    if (text.isBlank()) {
                        continue
                    }
                    add(
                        TranscriptUtterance(
                            speakerTag = item.optString("speaker").takeIf { it.isNotBlank() },
                            startMs = item.optLong("start"),
                            endMs = item.optLong("end"),
                            text = text,
                        ),
                    )
                }
            },
        )
    }
}
