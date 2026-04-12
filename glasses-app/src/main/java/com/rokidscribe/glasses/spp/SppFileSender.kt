package com.rokidscribe.glasses.spp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

class SppFileSender(
    private val input: BufferedInputStream,
    private val output: BufferedOutputStream,
    private val onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
) {
    companion object {
        private const val TAG = "RokidScribeSpp"
    }

    suspend fun sendFile(file: File): Result<TransferStatistics> = withContext(Dispatchers.IO) {
        runCatching {
            val totalBytes = file.length()
            if (totalBytes <= 0L) {
                throw IOException("Selected file is empty.")
            }

            val totalChunks = SppPacketUtils.getChunkCount(totalBytes)
            val md5 = SppPacketUtils.calculateMd5(file)
            var sentBytes = 0L
            val startedAt = System.currentTimeMillis()

            Log.d(TAG, "Starting SPP send: bytes=$totalBytes chunks=$totalChunks")
            output.write(SppPacketUtils.createStartPacket(totalBytes.toInt(), totalChunks, md5))
            output.flush()

            file.inputStream().buffered(SppTransferConstants.CHUNK_SIZE).use { input ->
                val buffer = ByteArray(SppTransferConstants.CHUNK_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    sentBytes += read
                    onProgress(sentBytes, totalBytes)
                }
            }

            output.write(SppPacketUtils.createEndPacket(SppTransferConstants.STATUS_SUCCESS))
            output.flush()

            when (waitForTransferResult()) {
                SppTransferConstants.STATUS_SUCCESS -> Unit
                SppTransferConstants.STATUS_MD5_ERROR -> throw IOException("The phone reported a checksum mismatch.")
                else -> throw IOException("The phone rejected the file after transfer.")
            }

            TransferStatistics(
                totalBytes = totalBytes,
                totalChunks = totalChunks,
                elapsedTimeMs = System.currentTimeMillis() - startedAt,
                retryCount = 0,
            )
        }
    }

    private suspend fun waitForTransferResult(): Byte = withContext(Dispatchers.IO) {
        try {
            withTimeout(SppTransferConstants.RESULT_TIMEOUT_MS) {
                SppPacketUtils.parseResultPacket(
                    readFully(input, SppTransferConstants.RESULT_PACKET_SIZE),
                )
            }
        } catch (error: TimeoutCancellationException) {
            throw IOException("Timed out waiting for the phone transfer result.", error)
        }
    }

    private fun readFully(input: java.io.InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) {
                throw EOFException("Bluetooth stream ended while waiting for $length bytes.")
            }
            offset += read
        }
        return buffer
    }
}
