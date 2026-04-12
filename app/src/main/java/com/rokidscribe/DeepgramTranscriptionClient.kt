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
import java.net.URLEncoder
import java.net.URL
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class DeepgramTranscriptionClient : TranscriptionProviderClient {
    override val provider: TranscriptProvider = TranscriptProvider.DEEPGRAM

    override suspend fun transcribe(
        apiKey: String,
        audioFile: File,
        languageCodeOverride: String?,
    ): Result<TranscriptResponse> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing Deepgram API key."))
        }
        if (!audioFile.exists()) {
            return@withContext Result.failure(IllegalArgumentException("Missing audio file: ${audioFile.absolutePath}"))
        }

        var connection: HttpURLConnection? = null
        try {
            val query = buildListenQuery(languageCodeOverride)
            connection = (URL("$LISTEN_ENDPOINT?$query").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = 30_000
                readTimeout = 300_000
                setChunkedStreamingMode(0)
                setRequestProperty("Authorization", "Token $apiKey")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", guessMimeType(audioFile))
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
                return@withContext Result.failure(
                    IllegalStateException("Deepgram HTTP $code: ${summarizeError(responseBody)}"),
                )
            }

            val payload = JSONObject(responseBody)
            val channel = payload.optJSONObject("results")
                ?.optJSONArray("channels")
                ?.optJSONObject(0)
            val alternative = channel
                ?.optJSONArray("alternatives")
                ?.optJSONObject(0)

            val transcriptText = alternative?.optString("transcript")?.trim().orEmpty()
            if (transcriptText.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Deepgram returned an empty transcript."),
                )
            }

            val detectedLanguage = channel?.optString("detected_language")
                ?.takeIf { it.isNotBlank() }
                ?: alternative?.optString("language")
                    ?.takeIf { it.isNotBlank() }
                    ?: languageCodeOverride

            val modelInfo = payload.optJSONObject("metadata")
                ?.optJSONArray("models")
                ?.let(::flattenModels)
                .orEmpty()
                .ifBlank { MODEL_ID }

            val utterances = payload.optJSONObject("results")?.optJSONArray("utterances")
            val wordCount = utterances?.let(::countUtteranceWords)
                ?.takeIf { it > 0 }
                ?: alternative?.optJSONArray("words")?.length()
                ?: transcriptText.split(WHITESPACE).count { it.isNotBlank() }
            val segments = buildSpeakerSegments(utterances)

            Result.success(
                TranscriptResponse(
                    providerId = provider.name,
                    modelId = modelInfo,
                    languageCode = detectedLanguage,
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

    override suspend fun fetchUsage(apiKey: String): Result<ProviderUsage> = withContext(Dispatchers.IO) {
        fetchUsage(apiKey, null)
    }

    suspend fun fetchUsage(
        apiKey: String,
        projectIdOverride: String?,
    ): Result<ProviderUsage> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing Deepgram API key."))
        }

        runCatching {
            val project = resolveProject(apiKey, projectIdOverride)
            val usage = fetchUsageBreakdown(apiKey, project.projectId)
            val spend = runCatching { fetchBillingBreakdown(apiKey, project.projectId) }.getOrNull()

            val rows = mutableListOf(
                "PROJECT" to project.label,
                "REQS" to formatInt(usage.requests),
                "HOURS" to formatDecimal(usage.hours),
                "RANGE" to "${usage.start} -> ${usage.end}",
            )
            if (usage.ttsCharacters > 0L) {
                rows += "TTS CHARS" to formatLong(usage.ttsCharacters)
            }
            spend?.let { billing ->
                rows += "SPEND" to formatUsd(billing.dollars)
            }

            ProviderUsage(
                rows = rows,
                footnote = if (project.wasAutoResolved) {
                    if (spend != null) {
                        "Usage and spend loaded from Deepgram project endpoints. You can override the project id below if needed."
                    } else {
                        "Usage loaded from Deepgram /usage/breakdown. You can override the project id below if needed."
                    }
                } else {
                    if (spend != null) {
                        "Usage and spend loaded for the Deepgram project id entered below."
                    } else {
                        "Usage loaded from Deepgram /usage/breakdown for the project id entered below."
                    }
                },
            )
        }
    }

    private fun fetchProjects(apiKey: String): List<DeepgramProject> {
        val payload = authorizedGet(apiKey, PROJECTS_ENDPOINT)
        val projects = payload.optJSONArray("projects") ?: JSONArray()
        return buildList {
            for (index in 0 until projects.length()) {
                val project = projects.optJSONObject(index) ?: continue
                val projectId = project.optString("project_id").takeIf { it.isNotBlank() } ?: continue
                add(
                    DeepgramProject(
                        projectId = projectId,
                        name = project.optString("name").ifBlank { projectId },
                    ),
                )
            }
        }
    }

    private fun fetchUsageBreakdown(apiKey: String, projectId: String): DeepgramUsageBreakdown {
        val calendar = Calendar.getInstance(Locale.getDefault())
        val endDate = isoDate(calendar.time)
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        val startDate = isoDate(calendar.time)
        val url = "$PROJECTS_ENDPOINT/$projectId/usage/breakdown?start=$startDate&end=$endDate"
        val payload = authorizedGet(apiKey, url)
        val results = payload.optJSONArray("results") ?: JSONArray()
        var hours = 0.0
        var requests = 0L
        var ttsCharacters = 0L
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            hours += item.optDouble("hours", 0.0)
            requests += item.optLong("requests", 0L)
            ttsCharacters += item.optLong("tts_characters", 0L)
        }
        return DeepgramUsageBreakdown(
            start = payload.optString("start").ifBlank { startDate },
            end = payload.optString("end").ifBlank { endDate },
            requests = requests,
            hours = hours,
            ttsCharacters = ttsCharacters,
        )
    }

    private fun fetchBillingBreakdown(apiKey: String, projectId: String): DeepgramBillingBreakdown {
        val calendar = Calendar.getInstance(Locale.getDefault())
        val endDate = isoDate(calendar.time)
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        val startDate = isoDate(calendar.time)
        val url = "$PROJECTS_ENDPOINT/$projectId/billing/breakdown?start=$startDate&end=$endDate"
        val payload = authorizedGet(apiKey, url)
        val results = payload.optJSONArray("results") ?: JSONArray()
        var dollars = 0.0
        for (index in 0 until results.length()) {
            dollars += results.optJSONObject(index)?.optDouble("dollars", 0.0) ?: 0.0
        }
        return DeepgramBillingBreakdown(dollars = dollars)
    }

    private fun authorizedGet(apiKey: String, endpoint: String): JSONObject {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                doInput = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Authorization", "Token $apiKey")
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            val responseBody = readText(
                if (code in 200..299) connection.inputStream else connection.errorStream,
            )
            if (code !in 200..299) {
                val message = summarizeError(responseBody)
                if (code == 403 && endpoint.contains("/usage")) {
                    throw DeepgramAccessException(
                        "This Deepgram key cannot read project usage for that project id. Check the project id or use a key with usage:read access.",
                    )
                }
                error("Deepgram HTTP $code: $message")
            }
            return JSONObject(responseBody)
        } finally {
            connection?.disconnect()
        }
    }

    private fun resolveProject(apiKey: String, projectIdOverride: String?): DeepgramProjectTarget {
        val explicitProjectId = projectIdOverride?.trim().orEmpty()
        if (explicitProjectId.isNotBlank()) {
            return DeepgramProjectTarget(
                projectId = explicitProjectId,
                label = explicitProjectId,
                wasAutoResolved = false,
            )
        }

        val project = fetchProjects(apiKey).firstOrNull()
            ?: error("Add a Deepgram project id to load usage for this provider.")
        return DeepgramProjectTarget(
            projectId = project.projectId,
            label = project.name,
            wasAutoResolved = true,
        )
    }

    private fun buildListenQuery(languageCodeOverride: String?): String {
        val params = linkedMapOf(
            "model" to MODEL_ID,
            "smart_format" to "true",
            "punctuate" to "true",
            "paragraphs" to "true",
            "diarize" to "true",
            "utterances" to "true",
        )
        if (languageCodeOverride.isNullOrBlank()) {
            params["detect_language"] = "true"
        } else {
            params["language"] = languageCodeOverride
        }
        return params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
    }

    private fun flattenModels(models: JSONArray): String {
        val values = mutableListOf<String>()
        for (index in 0 until models.length()) {
            when (val item = models.opt(index)) {
                is String -> if (item.isNotBlank()) values += item
                is JSONObject -> {
                    item.optString("name").takeIf { it.isNotBlank() }?.let(values::add)
                    item.optString("id").takeIf { it.isNotBlank() }?.let(values::add)
                }
            }
        }
        return values.distinct().joinToString("+")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun readText(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.bufferedReader().use { it.readText() }
    }

    private fun summarizeError(body: String): String {
        if (body.isBlank()) return "No response body"
        return runCatching {
            val json = JSONObject(body)
            when {
                json.optString("err_msg").isNotBlank() -> json.optString("err_msg")
                json.optString("message").isNotBlank() -> json.optString("message")
                json.optString("error").isNotBlank() -> json.optString("error")
                else -> body
            }
        }.getOrDefault(body).replace(WHITESPACE, " ").trim().take(220)
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

    private fun formatDecimal(value: Double): String =
        String.format(Locale.US, "%.2f", value)

    private fun formatUsd(value: Double): String =
        DecimalFormat(
            "$0.00###",
            DecimalFormatSymbols(Locale.US),
        ).format(value)

    private fun formatInt(value: Long): String =
        String.format(Locale.US, "%,d", value)

    private fun formatLong(value: Long): String =
        String.format(Locale.US, "%,d", value)

    private fun isoDate(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

    private fun buildSpeakerSegments(utterances: JSONArray?): List<SpeakerSegment> {
        if (utterances == null) {
            return emptyList()
        }

        return buildSegmentsFromUtterances(
            buildList {
                for (index in 0 until utterances.length()) {
                    val item = utterances.optJSONObject(index) ?: continue
                    val text = item.optString("transcript").trim()
                    if (text.isBlank()) {
                        continue
                    }
                    add(
                        TranscriptUtterance(
                            speakerTag = item.opt("speaker")?.toString()?.takeIf { it.isNotBlank() },
                            startMs = secondsToMs(item.optDouble("start", 0.0)),
                            endMs = secondsToMs(item.optDouble("end", 0.0)),
                            text = text,
                        ),
                    )
                }
            },
        )
    }

    private fun countUtteranceWords(utterances: JSONArray): Int {
        var count = 0
        for (index in 0 until utterances.length()) {
            count += utterances.optJSONObject(index)?.optJSONArray("words")?.length() ?: 0
        }
        return count
    }

    private fun secondsToMs(value: Double): Long = (value * 1000.0).toLong()

    private data class DeepgramProject(
        val projectId: String,
        val name: String,
    )

    private data class DeepgramProjectTarget(
        val projectId: String,
        val label: String,
        val wasAutoResolved: Boolean,
    )

    private data class DeepgramUsageBreakdown(
        val start: String,
        val end: String,
        val requests: Long,
        val hours: Double,
        val ttsCharacters: Long,
    )

    private data class DeepgramBillingBreakdown(
        val dollars: Double,
    )

    private class DeepgramAccessException(message: String) : IllegalStateException(message)

    companion object {
        private const val MODEL_ID = "nova-3"
        private const val LISTEN_ENDPOINT = "https://api.deepgram.com/v1/listen"
        private const val PROJECTS_ENDPOINT = "https://api.deepgram.com/v1/projects"
        private val WHITESPACE = Regex("\\s+")
    }
}
