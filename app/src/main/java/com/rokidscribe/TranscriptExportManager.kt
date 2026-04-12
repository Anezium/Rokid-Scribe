package com.rokidscribe

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TranscriptExportManager(
    private val context: Context,
) {
    suspend fun exportTxt(
        recording: LocalRecording,
        transcript: LocalTranscript,
    ): Result<ExportedDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = buildDisplayName(recording, "txt")
            val content = buildTextDocument(recording, transcript)
            writeDocument(fileName, "text/plain") { output ->
                output.writer(Charsets.UTF_8).use { writer ->
                    writer.write(content)
                }
            }
        }
    }

    suspend fun exportPdf(
        recording: LocalRecording,
        transcript: LocalTranscript,
    ): Result<ExportedDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = buildDisplayName(recording, "pdf")
            writeDocument(fileName, "application/pdf") { output ->
                writePdf(output, recording, transcript)
            }
        }
    }

    private fun writeDocument(
        displayName: String,
        mimeType: String,
        writer: (OutputStream) -> Unit,
    ): ExportedDocument {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/Rokid-Scribe",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: error("Unable to create export entry in Downloads.")
            try {
                resolver.openOutputStream(uri)?.use(writer)
                    ?: error("Unable to open output stream for $displayName.")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return ExportedDocument(
                    displayName = displayName,
                    locationLabel = "Download/Rokid-Scribe/$displayName",
                    uriString = uri.toString(),
                )
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        val exportDir = File(baseDir, "Rokid-Scribe")
        exportDir.mkdirs()
        val outputFile = File(exportDir, displayName)
        FileOutputStream(outputFile).use(writer)
        return ExportedDocument(
            displayName = displayName,
            locationLabel = outputFile.absolutePath,
            uriString = null,
        )
    }

    private fun buildTextDocument(
        recording: LocalRecording,
        transcript: LocalTranscript,
    ): String = buildString {
        val displayText = transcript.displayText()
        appendLine("Rokid Scribe Transcript")
        appendLine()
        appendLine("Source file: ${recording.sourceFileName}")
        appendLine("Imported at: ${formatDateTime(recording.importedAtEpochMs)}")
        appendLine("Recorded at: ${formatDateTime(recording.createdAtEpochMs)}")
        appendLine("Duration: ${formatDuration(recording.durationMs)}")
        appendLine("Size: ${formatBytes(recording.sizeBytes)}")
        appendLine("Source device: ${recording.sourceDeviceName ?: "Unknown"}")
        appendLine("Provider: ${providerDisplayLabel(transcript.providerId)}")
        appendLine("Language: ${transcript.languageCode ?: "Auto"}")
        appendLine("Model: ${transcript.modelId}")
        appendLine("Words: ${transcript.wordCount}")
        transcript.speakerCountOrNull()?.let { speakerCount ->
            appendLine("Speakers: $speakerCount")
        }
        appendLine()
        appendLine(displayText)
    }

    private fun writePdf(
        output: OutputStream,
        recording: LocalRecording,
        transcript: LocalTranscript,
    ) {
        val document = PdfDocument()
        try {
            val pageWidth = 595
            val pageHeight = 842
            val margin = 42f
            val contentWidth = pageWidth - (margin * 2f)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 20f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 11f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            }
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 12f
                typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            }
            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = 10f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            }

            val metaLines = listOf(
                "Source file: ${recording.sourceFileName}",
                "Imported at: ${formatDateTime(recording.importedAtEpochMs)}",
                "Recorded at: ${formatDateTime(recording.createdAtEpochMs)}",
                "Duration: ${formatDuration(recording.durationMs)}",
                "Source device: ${recording.sourceDeviceName ?: "Unknown"}",
                "Provider: ${providerDisplayLabel(transcript.providerId)}",
                "Language: ${transcript.languageCode ?: "Auto"}",
                "Model: ${transcript.modelId}",
                "Words: ${transcript.wordCount}",
            ) + listOfNotNull(
                transcript.speakerCountOrNull()?.let { "Speakers: $it" },
            )
            val transcriptLines = wrapText(transcript.displayText(), bodyPaint, contentWidth)

            val bodyLineHeight = bodyPaint.fontSpacing + 2f
            val metaLineHeight = metaPaint.fontSpacing + 2f
            var lineIndex = 0
            var pageNumber = 1

            do {
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create(),
                )
                val canvas = page.canvas
                var y = margin

                if (pageNumber == 1) {
                    canvas.drawText("Rokid Scribe Transcript", margin, y, titlePaint)
                    y += titlePaint.fontSpacing + 10f
                    metaLines.forEach { line ->
                        canvas.drawText(line, margin, y, metaPaint)
                        y += metaLineHeight
                    }
                    y += 10f
                } else {
                    canvas.drawText(recording.sourceFileName, margin, y, metaPaint)
                    y += metaPaint.fontSpacing + 8f
                }

                val bottomLimit = pageHeight - margin - footerPaint.fontSpacing
                while (lineIndex < transcriptLines.size && y + bodyLineHeight <= bottomLimit) {
                    canvas.drawText(transcriptLines[lineIndex], margin, y, bodyPaint)
                    y += bodyLineHeight
                    lineIndex += 1
                }

                val footer = "Page $pageNumber"
                val footerWidth = footerPaint.measureText(footer)
                canvas.drawText(
                    footer,
                    pageWidth - margin - footerWidth,
                    pageHeight - margin / 2f,
                    footerPaint,
                )
                document.finishPage(page)
                pageNumber += 1
            } while (lineIndex < transcriptLines.size)

            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    private fun wrapText(
        text: String,
        paint: Paint,
        maxWidth: Float,
    ): List<String> {
        if (text.isBlank()) {
            return listOf("")
        }

        val lines = mutableListOf<String>()
        text.replace("\r\n", "\n")
            .split('\n')
            .forEach { paragraph ->
                if (paragraph.isBlank()) {
                    lines += ""
                    return@forEach
                }

                var currentLine = ""
                paragraph.trim().split(Regex("\\s+")).forEach { word ->
                    val candidate = if (currentLine.isBlank()) word else "$currentLine $word"
                    if (paint.measureText(candidate) <= maxWidth) {
                        currentLine = candidate
                    } else {
                        if (currentLine.isNotBlank()) {
                            lines += currentLine
                        }
                        if (paint.measureText(word) <= maxWidth) {
                            currentLine = word
                        } else {
                            val brokenWord = breakLongWord(word, paint, maxWidth)
                            if (brokenWord.isNotEmpty()) {
                                lines += brokenWord.dropLast(1)
                                currentLine = brokenWord.last()
                            } else {
                                currentLine = word
                            }
                        }
                    }
                }

                if (currentLine.isNotBlank()) {
                    lines += currentLine
                }
            }

        return lines.ifEmpty { listOf("") }
    }

    private fun breakLongWord(
        word: String,
        paint: Paint,
        maxWidth: Float,
    ): List<String> {
        if (word.isBlank()) {
            return emptyList()
        }

        val parts = mutableListOf<String>()
        val current = StringBuilder()
        word.forEach { character ->
            val candidate = buildString {
                append(current)
                append(character)
            }
            if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                current.append(character)
            } else {
                parts += current.toString()
                current.clear()
                current.append(character)
            }
        }
        if (current.isNotEmpty()) {
            parts += current.toString()
        }
        return parts
    }

    private fun buildDisplayName(
        recording: LocalRecording,
        extension: String,
    ): String {
        val timeStamp = FILE_TIME_FORMAT.format(Date())
        val baseName = sanitize(recording.sourceFileName.substringBeforeLast('.', recording.sourceFileName))
        return "${timeStamp}_${baseName}_transcript.$extension"
    }

    private fun sanitize(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').ifBlank { "recording" }
    }

    private fun formatDateTime(epochMs: Long): String {
        return DATE_TIME_FORMAT.format(Date(epochMs))
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun formatBytes(sizeBytes: Long): String {
        val kilo = 1024.0
        val mega = kilo * kilo
        return when {
            sizeBytes >= mega -> String.format(Locale.US, "%.1f MB", sizeBytes / mega)
            sizeBytes >= kilo -> String.format(Locale.US, "%.1f KB", sizeBytes / kilo)
            else -> "$sizeBytes B"
        }
    }

    companion object {
        private val FILE_TIME_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        private val DATE_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
