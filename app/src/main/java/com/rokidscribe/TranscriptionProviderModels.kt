package com.rokidscribe

import java.io.File
import java.util.Locale

enum class TranscriptProvider {
    ELEVENLABS,
    ASSEMBLYAI,
    SPEECHMATICS,
    DEEPGRAM,
    GROQ,
}

interface TranscriptionProviderClient {
    val provider: TranscriptProvider

    suspend fun transcribe(
        apiKey: String,
        audioFile: File,
        languageCodeOverride: String? = null,
    ): Result<TranscriptResponse>

    suspend fun fetchUsage(apiKey: String): Result<ProviderUsage> {
        return Result.failure(
            UnsupportedOperationException("${provider.name} usage lookup is not available in the app yet."),
        )
    }
}

data class ProviderUsage(
    val rows: List<Pair<String, String>>,
    val footnote: String,
)

enum class TranscriptIssueKind {
    LANGUAGE_DETECTION,
}

data class TranscriptIssue(
    val providerId: String,
    val kind: TranscriptIssueKind,
    val message: String,
    val createdAtEpochMs: Long,
    val requestedLanguageLabel: String? = null,
)

enum class ManualTranscriptLanguage(
    val label: String,
    private val elevenLabsCode: String,
    private val assemblyAiCode: String,
    private val speechmaticsCode: String,
    private val deepgramCode: String,
    private val groqCode: String,
) {
    ENGLISH("English", "en", "en_us", "en", "en", "en"),
    FRENCH("French", "fr", "fr", "fr", "fr", "fr"),
    SPANISH("Spanish", "es", "es", "es", "es", "es"),
    GERMAN("German", "de", "de", "de", "de", "de"),
    ITALIAN("Italian", "it", "it", "it", "it", "it"),
    PORTUGUESE("Portuguese", "pt", "pt", "pt", "pt", "pt"),
    DUTCH("Dutch", "nl", "nl", "nl", "nl", "nl"),
    JAPANESE("Japanese", "ja", "ja", "ja", "ja", "ja"),
    KOREAN("Korean", "ko", "ko", "ko", "ko", "ko"),
    ARABIC("Arabic", "ar", "ar", "ar", "ar", "ar"),
    CHINESE("Chinese", "zh", "zh", "zh", "zh", "zh");

    fun codeFor(provider: TranscriptProvider): String = when (provider) {
        TranscriptProvider.ELEVENLABS -> elevenLabsCode
        TranscriptProvider.ASSEMBLYAI -> assemblyAiCode
        TranscriptProvider.SPEECHMATICS -> speechmaticsCode
        TranscriptProvider.DEEPGRAM -> deepgramCode
        TranscriptProvider.GROQ -> groqCode
    }
}

object TranscriptIssueDetector {
    fun detectLanguageIssue(
        provider: TranscriptProvider,
        error: Throwable,
        requestedLanguage: ManualTranscriptLanguage? = null,
    ): TranscriptIssue? {
        val message = buildIssueMessage(error)
        val lower = message.lowercase(Locale.ROOT)
        val isLanguageIssue =
            LANGUAGE_ERROR_SNIPPETS.any(lower::contains) ||
                (lower.contains("language") &&
                    (lower.contains("detect") ||
                        lower.contains("confidence") ||
                        lower.contains("identify") ||
                        lower.contains("invalid") ||
                        lower.contains("unsupported")))

        if (!isLanguageIssue) {
            return null
        }

        return TranscriptIssue(
            providerId = provider.name,
            kind = TranscriptIssueKind.LANGUAGE_DETECTION,
            message = message,
            createdAtEpochMs = System.currentTimeMillis(),
            requestedLanguageLabel = requestedLanguage?.label,
        )
    }

    private fun buildIssueMessage(error: Throwable): String {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { throwable -> throwable.message?.trim()?.takeIf { it.isNotBlank() } }
            .firstOrNull()
            ?: "Unknown transcription error."
        return message.replace(Regex("\\s+"), " ").trim().take(220)
    }

    private val LANGUAGE_ERROR_SNIPPETS = listOf(
        "failed to detect language",
        "failed to detect the language",
        "unable to detect language",
        "unable to detect the language",
        "automatic language detection",
        "language detection",
        "low language confidence",
        "language confidence",
        "unable to identify language",
        "unable to identify the language",
        "unsupported language",
        "invalid language",
    )
}
