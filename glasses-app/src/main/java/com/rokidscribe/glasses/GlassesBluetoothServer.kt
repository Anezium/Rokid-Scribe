package com.rokidscribe.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.rokidscribe.glasses.spp.ImportRequest
import com.rokidscribe.glasses.spp.ImportResult
import com.rokidscribe.glasses.spp.QueueState
import com.rokidscribe.glasses.spp.RecordingBatchItem
import com.rokidscribe.glasses.spp.RecordingOffer
import com.rokidscribe.glasses.spp.SppControlChannel
import com.rokidscribe.glasses.spp.SppFileSender
import com.rokidscribe.glasses.spp.SppPacketUtils
import com.rokidscribe.glasses.spp.SppTransferConstants
import com.rokidscribe.glasses.spp.WifiRecordingBatchUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.Locale

class GlassesBluetoothServer(
    private val context: Context,
    private val repository: RecordingRepository,
) {
    private var scope = createScope()
    private val bluetoothAdapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    @Volatile
    private var onStatus: ((String) -> Unit)? = null

    @Volatile
    private var onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)? = null

    private var listenJob: Job? = null
    private var serverSocket: BluetoothServerSocket? = null

    fun setCallbacks(
        onStatus: (String) -> Unit,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit,
    ) {
        this.onStatus = onStatus
        this.onProgress = onProgress
    }

    fun clearCallbacks() {
        onStatus = null
        onProgress = null
    }

    fun start() {
        if (listenJob != null) {
            return
        }
        if (!hasConnectPermission()) {
            postStatus("Bluetooth permission is missing on the glasses.")
            return
        }

        scope = createScope()
        listenJob = scope.launch {
            while (isActive) {
                try {
                    val server = openServerSocket()
                    serverSocket = server
                    postStatus("Waiting for the phone import command...")
                    val socket = server.accept()
                    runCatching { server.close() }
                    serverSocket = null
                    if (socket != null) {
                        handleClient(socket)
                    }
                } catch (error: IOException) {
                    if (!isActive) {
                        break
                    }
                    postStatus("Bluetooth listener unavailable: ${error.message ?: "retrying"}")
                    delay(750L)
                }
            }
        }
    }

    fun stop() {
        listenJob?.cancel()
        listenJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
        scope = createScope()
    }

    @SuppressLint("MissingPermission")
    private fun openServerSocket(): BluetoothServerSocket {
        val adapter = bluetoothAdapter ?: throw IOException("Bluetooth adapter unavailable on the glasses.")
        return try {
            adapter.listenUsingInsecureRfcommWithServiceRecord(
                SppTransferConstants.SERVICE_NAME,
                SppTransferConstants.APP_UUID,
            )
        } catch (_: Exception) {
            adapter.listenUsingRfcommWithServiceRecord(
                SppTransferConstants.SERVICE_NAME,
                SppTransferConstants.APP_UUID,
            )
        }
    }

    private suspend fun handleClient(socket: BluetoothSocket) {
        socket.use { activeSocket ->
            val input = BufferedInputStream(activeSocket.inputStream, SppTransferConstants.CHUNK_SIZE)
            val output = BufferedOutputStream(activeSocket.outputStream, SppTransferConstants.CHUNK_SIZE)
            val channel = SppControlChannel(input, output)
            var leaveWaitingStatus = true
            try {
                channel.awaitQueueProbe()
                val pending = repository.listPending()
                channel.sendQueueState(
                    QueueState(
                        deviceName = Build.MODEL,
                        pendingCount = pending.size,
                        items = pending,
                    ),
                )
                val request = channel.awaitImportRequest()
                val requested = if (request.itemIds.isEmpty()) pending else repository.resolvePending(request.itemIds)
                if (requested.isEmpty()) {
                    channel.sendResult(
                        ImportResult(
                            success = false,
                            message = "No pending recordings matched the request.",
                        ),
                    )
                    return
                }

                when (request.transportMode.lowercase(Locale.US)) {
                    "spp" -> handleSppTransfer(input, output, channel, requested.map { it.id })
                    "wifi_lan" -> handleWifiTransfer(channel, request, requested.map { it.id })
                    else -> {
                        channel.sendResult(
                            ImportResult(
                                success = false,
                                message = "Unsupported transport ${request.transportMode}.",
                            ),
                        )
                    }
                }
            } catch (error: Exception) {
                leaveWaitingStatus = false
                runCatching {
                    channel.sendResult(
                        ImportResult(
                            success = false,
                            message = error.message ?: "Transfer failed.",
                        ),
                    )
                }
                postStatus("Transfer failed: ${error.message}")
            } finally {
                reportProgress(0L, 0L)
                if (leaveWaitingStatus) {
                    postStatus("Waiting for the phone import command...")
                }
            }
        }
    }

    private suspend fun handleSppTransfer(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        channel: SppControlChannel,
        itemIds: List<String>,
    ) {
        val recordings = repository.resolvePending(itemIds)
        val totalBytes = recordings.sumOf { it.sizeBytes }.coerceAtLeast(1L)
        var sentSoFar = 0L
        for (recording in recordings) {
            val file = repository.audioFileFor(recording)
            val md5Hex = SppPacketUtils.calculateMd5(file).toHexString()
            channel.sendRecordingOffer(
                RecordingOffer(
                    id = recording.id,
                    fileName = recording.fileName,
                    sizeBytes = recording.sizeBytes,
                    durationMs = recording.durationMs,
                    createdAtEpochMs = recording.createdAtEpochMs,
                    md5Hex = md5Hex,
                ),
            )
            SppFileSender(input, output) { currentBytes, _ ->
                reportProgress(sentSoFar + currentBytes, totalBytes)
            }.sendFile(file).getOrThrow()
            sentSoFar += recording.sizeBytes
            postStatus("Sent ${recording.fileName} over Bluetooth.")
        }
        channel.sendResult(
            ImportResult(
                success = true,
                importedCount = recordings.size,
                failedCount = 0,
                message = "Bluetooth import complete.",
            ),
        )
        repository.markImported(recordings.map { it.id })
    }

    private suspend fun handleWifiTransfer(
        channel: SppControlChannel,
        request: ImportRequest,
        itemIds: List<String>,
    ) {
        val hostIp = request.hostIp ?: throw IOException("The phone did not provide a LAN address.")
        val port = request.port ?: throw IOException("The phone did not provide a LAN port.")
        val recordings = repository.resolvePending(itemIds)
        val totalBytes = recordings.sumOf { it.sizeBytes }.coerceAtLeast(1L)
        val items = recordings.map { recording ->
            val file = repository.audioFileFor(recording)
            RecordingBatchItem(
                descriptor = recording,
                md5Hex = SppPacketUtils.calculateMd5(file).toHexString(),
                file = file,
            )
        }
        WifiRecordingBatchUploader().uploadBatch(hostIp, port, items) { sentBytes, _ ->
            reportProgress(sentBytes, totalBytes)
        }.getOrThrow()
        channel.sendResult(
            ImportResult(
                success = true,
                importedCount = recordings.size,
                failedCount = 0,
                message = "Wi-Fi import complete.",
            ),
        )
        repository.markImported(recordings.map { it.id })
        postStatus("Sent ${recordings.size} recording(s) over Wi-Fi.")
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }

    private fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun postStatus(message: String) {
        onStatus?.invoke(message)
    }

    private fun reportProgress(
        sentBytes: Long,
        totalBytes: Long,
    ) {
        onProgress?.invoke(sentBytes, totalBytes)
    }

    private fun createScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
