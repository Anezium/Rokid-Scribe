package com.rokidscribe.spp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

class SppFileReceiver(
    private val input: BufferedInputStream,
    private val output: BufferedOutputStream,
    private val targetFile: File,
    private val onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
) {
    companion object {
        private const val TAG = "RokidScribeSpp"
    }

    suspend fun receiveFile(): Result<TransferStatistics> = withContext(Dispatchers.IO) {
        var resultSent = false
        runCatching {
            val startedAt = System.currentTimeMillis()

            val startPacket = readFully(input, SppTransferConstants.START_PACKET_SIZE)
            val startData = SppPacketUtils.parseStartPacket(startPacket)
            Log.d(TAG, "Receiving file over SPP: bytes=${startData.totalSize} chunks=${startData.totalChunks}")

            targetFile.parentFile?.mkdirs()
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val buffer = ByteArray(SppTransferConstants.CHUNK_SIZE)
            var receivedBytes = 0L
            var remaining = startData.totalSize.toLong()
            targetFile.outputStream().buffered(SppTransferConstants.CHUNK_SIZE).use { fileOutput ->
                while (remaining > 0L) {
                    val nextRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, nextRead)
                    if (read < 0) {
                        throw EOFException("Bluetooth stream ended before the full file was received.")
                    }
                    fileOutput.write(buffer, 0, read)
                    receivedBytes += read
                    remaining -= read
                    onProgress(receivedBytes, startData.totalSize.toLong())
                }
                fileOutput.flush()
            }

            val endStatus = SppPacketUtils.parseEndPacket(
                readFully(input, SppTransferConstants.END_PACKET_SIZE),
            )
            if (endStatus != SppTransferConstants.STATUS_SUCCESS) {
                output.write(SppPacketUtils.createResultPacket(endStatus))
                output.flush()
                resultSent = true
                throw IOException("Sender reported end status $endStatus.")
            }

            if (!SppPacketUtils.verifyMd5(targetFile, startData.md5)) {
                output.write(SppPacketUtils.createResultPacket(SppTransferConstants.STATUS_MD5_ERROR))
                output.flush()
                resultSent = true
                throw IOException("Checksum mismatch after the Bluetooth transfer.")
            }

            output.write(SppPacketUtils.createResultPacket(SppTransferConstants.STATUS_SUCCESS))
            output.flush()
            resultSent = true

            TransferStatistics(
                totalBytes = startData.totalSize.toLong(),
                totalChunks = startData.totalChunks,
                elapsedTimeMs = System.currentTimeMillis() - startedAt,
                retryCount = 0,
            )
        }.onFailure {
            Log.e(TAG, "SPP receive failed.", it)
            if (!resultSent) {
                runCatching {
                    output.write(
                        SppPacketUtils.createResultPacket(SppTransferConstants.STATUS_ERROR),
                    )
                    output.flush()
                }
            }
            if (targetFile.exists()) {
                targetFile.delete()
            }
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
