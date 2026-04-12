package com.rokidscribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class SpeechmaticsTranscriptionClient : TranscriptionProviderClient {
    override val provider: TranscriptProvider = TranscriptProvider.SPEECHMATICS

    override suspend fun transcribe(
        apiKey: String,
        audioFile: File,
        languageCodeOverride: String?,
    ): Result<TranscriptResponse> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing Speechmatics API key."))
        }
        if (!audioFile.exists()) {
            return@withContext Result.failure(IllegalArgumentException("Missing audio file: ${audioFile.absolutePath}"))
        }

        return@withContext runCatching {
            val jobId = submitJob(apiKey, audioFile, languageCodeOverride)
            try {
                val jobPayload = waitForCompletion(apiKey, jobId)
                val transcriptText = fetchTranscript(apiKey, jobId)
                val transcriptJson = fetchTranscriptJson(apiKey, jobId)
                if (transcriptText.isBlank()) {
                    error("Speechmatics returned an empty transcript.")
                }

                val languageCode = jobPayload.optJSONObject("config")
                    ?.optJSONObject("transcription_config")
                    ?.optString("language")
                    ?.takeIf { it.isNotBlank() && it != "auto" }

                TranscriptResponse(
                    providerId = provider.name,
                    modelId = "speechmatics_batch_v2",
                    languageCode = languageCode,
                    text = transcriptText,
                    wordCount = transcriptText.split(WHITESPACE).count { it.isNotBlank() },
                    segments = buildSpeakerSegments(transcriptJson),
                )
            } finally {
                bestEffortDeleteJob(apiKey, jobId)
            }
        }
    }

    private fun submitJob(
        apiKey: String,
        audioFile: File,
        languageCodeOverride: String?,
    ): String {
        val boundary = "----RokidScribeSpeechmatics${System.currentTimeMillis()}"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$BASE_URL/jobs").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = 30_000
                readTimeout = 300_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            val config = JSONObject()
                .put("type", "transcription")
                .put(
                    "transcription_config",
                    JSONObject()
                        .put("language", languageCodeOverride ?: "auto")
                        .put("diarization", "speaker"),
                )

            DataOutputStream(BufferedOutputStream(connection.outputStream)).use { output ->
                writeJsonPart(output, boundary, "config", config.toString())
                writeFilePart(output, boundary, "data_file", audioFile)
                output.writeBytes("--$boundary--\r\n")
                output.flush()
            }

            val code = connection.responseCode
            val responseBody = readText(
                if (code in 200..299) connection.inputStream else connection.errorStream,
            )
            if (code !in 200..299) {
                error("Speechmatics submit HTTP $code: ${summarizeError(responseBody)}")
            }

            val payload = JSONObject(responseBody)
            return payload.optString("id")
                .ifBlank { payload.optJSONObject("job")?.optString("id").orEmpty() }
                .ifBlank { error("Speechmatics did not return a job id.") }
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun waitForCompletion(apiKey: String, jobId: String): JSONObject {
        repeat(MAX_POLLS) {
            val payload = getJobDetails(apiKey, jobId)
            when (resolveJobStatus(payload)) {
                "done", "completed" -> return payload
                "rejected", "failed", "error" -> {
                    error(resolveJobError(payload))
                }
            }
            delay(POLL_DELAY_MS)
        }
        error("Speechmatics transcription timed out.")
    }

    private fun getJobDetails(apiKey: String, jobId: String): JSONObject {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$BASE_URL/jobs/$jobId").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                doInput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }

            val code = connection.responseCode
            val responseBody = readText(
                if (code in 200..299) connection.inputStream else connection.errorStream,
            )
            if (code !in 200..299) {
                error("Speechmatics poll HTTP $code: ${summarizeError(responseBody)}")
            }

            return JSONObject(responseBody).let { payload ->
                payload.optJSONObject("job") ?: payload
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchTranscript(apiKey: String, jobId: String): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$BASE_URL/jobs/$jobId/transcript?format=txt").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                doInput = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Accept", "text/plain")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }

            val code = connection.responseCode
            val responseBody = readText(
                if (code in 200..299) connection.inputStream else connection.errorStream,
            )
            if (code !in 200..299) {
                error("Speechmatics transcript HTTP $code: ${summarizeError(responseBody)}")
            }
            return responseBody.trim()
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchTranscriptJson(apiKey: String, jobId: String): JSONObject {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$BASE_URL/jobs/$jobId/transcript").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                doInput = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }

            val code = connection.responseCode
            val responseBody = readText(
                if (code in 200..299) connection.inputStream else connection.errorStream,
            )
            if (code !in 200..299) {
                error("Speechmatics transcript JSON HTTP $code: ${summarizeError(responseBody)}")
            }
            return JSONObject(responseBody)
        } finally {
            connection?.disconnect()
        }
    }

    private fun bestEffortDeleteJob(apiKey: String, jobId: String) {
        runCatching {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("$BASE_URL/jobs/$jobId").openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    doInput = true
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                connection.responseCode
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun resolveJobStatus(payload: JSONObject): String {
        return payload.optString("status")
            .ifBlank { payload.optString("job_status") }
            .lowercase()
    }

    private fun resolveJobError(payload: JSONObject): String {
        val errors = payload.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val first = errors.opt(0)
            if (first is JSONObject) {
                return first.optString("message").ifBlank { first.toString() }
            }
            if (first != null) {
                return first.toString()
            }
        }
        return payload.optString("error").ifBlank { "Speechmatics transcription failed." }
    }

    private fun writeJsonPart(
        output: DataOutputStream,
        boundary: String,
        name: String,
        value: String,
    ) {
        output.writeBytes("--$boundary\r\n")
        output.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n")
        output.writeBytes("Content-Type: application/json\r\n\r\n")
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
        return when (file.extension.lowercase()) {
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            else -> "application/octet-stream"
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
                json.optString("detail").isNotBlank() -> json.optString("detail")
                json.optString("message").isNotBlank() -> json.optString("message")
                json.optString("error").isNotBlank() -> json.optString("error")
                json.optJSONArray("errors") != null -> flattenErrors(json.optJSONArray("errors"))
                else -> body
            }
        }.getOrDefault(body).replace(WHITESPACE, " ").trim().take(220)
    }

    private fun flattenErrors(errors: JSONArray?): String {
        if (errors == null || errors.length() == 0) {
            return ""
        }
        return buildString {
            for (index in 0 until errors.length()) {
                val item = errors.opt(index)
                val text = when (item) {
                    is JSONObject -> item.optString("message").ifBlank { item.toString() }
                    null -> ""
                    else -> item.toString()
                }
                if (text.isNotBlank()) {
                    if (isNotEmpty()) append("; ")
                    append(text)
                }
            }
        }
    }

    companion object {
        private const val BASE_URL = "https://asr.api.speechmatics.com/v2"
        private const val MAX_POLLS = 240
        private const val POLL_DELAY_MS = 1_500L
        private val WHITESPACE = Regex("\\s+")
    }

    private fun buildSpeakerSegments(payload: JSONObject): List<SpeakerSegment> {
        val results = payload.optJSONArray("results") ?: return emptyList()
        val tokens = mutableListOf<TranscriptToken>()
        var currentSpeaker: String? = null

        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            val type = item.optString("type").lowercase()
            if (type != "word" && type != "punctuation") {
                continue
            }

            val alternatives = item.optJSONArray("alternatives")
            val tokenText = alternatives?.optJSONObject(0)?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: item.optString("content").takeIf { it.isNotBlank() }
                ?: continue

            val speakerTag = item.optString("speaker").takeIf { it.isNotBlank() }
                ?: alternatives?.optJSONObject(0)?.optString("speaker")?.takeIf { it.isNotBlank() }
                ?: if (type == "punctuation") currentSpeaker else null

            if (!speakerTag.isNullOrBlank()) {
                currentSpeaker = speakerTag
            }

            tokens += TranscriptToken(
                text = tokenText,
                speakerTag = speakerTag,
                startMs = secondsToMs(item.optDouble("start_time", item.optDouble("start", 0.0))),
                endMs = secondsToMs(item.optDouble("end_time", item.optDouble("end", 0.0))),
                type = type,
            )
        }

        return buildSegmentsFromTokens(tokens)
    }

    private fun secondsToMs(value: Double): Long = (value * 1000.0).toLong()
}
