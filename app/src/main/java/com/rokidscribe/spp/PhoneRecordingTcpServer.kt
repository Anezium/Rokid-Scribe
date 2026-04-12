package com.rokidscribe.spp

import com.rokidscribe.PhoneRecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException

class PhoneRecordingTcpServer(
    private val store: PhoneRecordingStore,
) {
    private companion object {
        private const val CLIENT_IO_TIMEOUT_MS = 30_000
    }

    private var serverSocket: ServerSocket? = null

    suspend fun start(): Int = withContext(Dispatchers.IO) {
        val server = ServerSocket(0).apply {
            reuseAddress = true
            soTimeout = 120_000
        }
        serverSocket = server
        server.localPort
    }

    suspend fun awaitBatch(
        expectedItems: List<RecordingDescriptor>,
        sourceDeviceName: String?,
        onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): TransferStatistics = withContext(Dispatchers.IO) {
        val server = serverSocket ?: throw IOException("Wi-Fi receiver is not started.")
        val totalExpectedBytes = expectedItems.sumOf { it.sizeBytes }.coerceAtLeast(1L)
        val startedAt = System.currentTimeMillis()
        try {
            server.accept().use { client ->
                client.tcpNoDelay = true
                client.soTimeout = CLIENT_IO_TIMEOUT_MS
                val input = DataInputStream(BufferedInputStream(client.inputStream))
                val output = DataOutputStream(BufferedOutputStream(client.outputStream))

                val itemCount = input.readInt()
                if (itemCount != expectedItems.size) {
                    throw IOException("Expected ${expectedItems.size} recording(s), got $itemCount.")
                }

                val remainingById = expectedItems.associateBy { it.id }.toMutableMap()
                var receivedBytes = 0L
                repeat(itemCount) {
                    val offer = readOffer(input)
                    val expected = remainingById.remove(offer.id)
                        ?: throw IOException("Unexpected or duplicate recording id ${offer.id}.")
                    if (offer.sizeBytes != expected.sizeBytes) {
                        throw IOException("Unexpected size for ${offer.fileName}.")
                    }

                    val targetFile = store.createTargetFile(offer)
                    try {
                        targetFile.outputStream().buffered(SppTransferConstants.CHUNK_SIZE).use { sink ->
                            val buffer = ByteArray(SppTransferConstants.CHUNK_SIZE)
                            var remaining = offer.sizeBytes
                            while (remaining > 0L) {
                                val nextRead = minOf(buffer.size.toLong(), remaining).toInt()
                                val read = input.read(buffer, 0, nextRead)
                                if (read < 0) {
                                    throw EOFException("Wi-Fi batch ended before ${offer.fileName} was fully received.")
                                }
                                sink.write(buffer, 0, read)
                                remaining -= read
                                receivedBytes += read
                                onProgress(receivedBytes, totalExpectedBytes)
                            }
                            sink.flush()
                        }

                        val actualMd5Hex = SppPacketUtils.calculateMd5(targetFile).toHexString()
                        if (!actualMd5Hex.equals(offer.md5Hex, ignoreCase = true)) {
                            throw IOException("Checksum mismatch for ${offer.fileName}.")
                        }
                        store.writeMetadata(targetFile, offer, sourceDeviceName)
                    } catch (error: Exception) {
                        if (targetFile.exists()) {
                            targetFile.delete()
                        }
                        throw error
                    }
                }

                if (remainingById.isNotEmpty()) {
                    throw IOException(
                        "Missing recordings in Wi-Fi batch: ${remainingById.keys.joinToString()}",
                    )
                }

                output.writeBoolean(true)
                output.writeUTF("Batch received.")
                output.flush()

                TransferStatistics(
                    totalBytes = receivedBytes,
                    totalChunks = expectedItems.size,
                    elapsedTimeMs = System.currentTimeMillis() - startedAt,
                    retryCount = 0,
                )
            }
        } catch (_: SocketTimeoutException) {
            throw IOException("Timed out while waiting for the glasses to send the LAN batch.")
        } catch (error: SocketException) {
            if (server.isClosed || error.message?.contains("closed", ignoreCase = true) == true) {
                throw IOException("The phone LAN receiver was closed.")
            }
            throw error
        }
    }

    fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun readOffer(input: DataInputStream): RecordingOffer {
        val headerLength = input.readInt()
        if (headerLength <= 0) {
            throw IOException("Wi-Fi batch header is empty.")
        }
        val body = ByteArray(headerLength)
        input.readFully(body)
        val payload = JSONObject(String(body, Charsets.UTF_8))
        return RecordingOffer(
            id = payload.getString("id"),
            fileName = payload.getString("fileName"),
            sizeBytes = payload.getLong("sizeBytes"),
            durationMs = payload.optLong("durationMs", 0L),
            createdAtEpochMs = payload.optLong("createdAtEpochMs", 0L),
            md5Hex = payload.getString("md5Hex"),
        )
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }
}
