package com.rokidscribe.glasses.spp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

data class RecordingBatchItem(
    val descriptor: RecordingDescriptor,
    val md5Hex: String,
    val file: File,
)

class WifiRecordingBatchUploader {
    suspend fun uploadBatch(
        hostIp: String,
        port: Int,
        items: List<RecordingBatchItem>,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<TransferStatistics> = withContext(Dispatchers.IO) {
        runCatching {
            val startedAt = System.currentTimeMillis()
            val totalBytes = items.sumOf { it.descriptor.sizeBytes }.coerceAtLeast(1L)
            var sentBytes = 0L

            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(hostIp, port), 15_000)
                val output = DataOutputStream(BufferedOutputStream(socket.outputStream))
                val input = DataInputStream(BufferedInputStream(socket.inputStream))

                output.writeInt(items.size)
                for (item in items) {
                    val header = JSONObject()
                        .put("id", item.descriptor.id)
                        .put("fileName", item.descriptor.fileName)
                        .put("sizeBytes", item.descriptor.sizeBytes)
                        .put("durationMs", item.descriptor.durationMs)
                        .put("createdAtEpochMs", item.descriptor.createdAtEpochMs)
                        .put("md5Hex", item.md5Hex)
                    val body = header.toString().toByteArray(Charsets.UTF_8)
                    output.writeInt(body.size)
                    output.write(body)

                    item.file.inputStream().buffered(SppTransferConstants.CHUNK_SIZE).use { source ->
                        val buffer = ByteArray(SppTransferConstants.CHUNK_SIZE)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            sentBytes += read
                            onProgress(sentBytes, totalBytes)
                        }
                    }
                }
                output.flush()

                val success = input.readBoolean()
                val message = input.readUTF()
                if (!success) {
                    throw IOException(message)
                }
            }

            TransferStatistics(
                totalBytes = sentBytes,
                totalChunks = items.size,
                elapsedTimeMs = System.currentTimeMillis() - startedAt,
                retryCount = 0,
            )
        }
    }
}
