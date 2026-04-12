package com.rokidscribe

import android.bluetooth.BluetoothDevice
import androidx.appcompat.app.AppCompatActivity
import com.rokidscribe.spp.ImportRequest
import com.rokidscribe.spp.PhoneRecordingTcpServer
import com.rokidscribe.spp.SppControlChannel
import com.rokidscribe.spp.SppTransferConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface

class WifiLanRecordingImportSession(
    private val activity: AppCompatActivity,
    private val onStatus: (String) -> Unit,
    private val onBusyChanged: (Boolean) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bluetoothClient = PhoneBluetoothClient(activity)
    private val recordingStore = PhoneRecordingStore(activity)
    private var sessionJob: Job? = null

    fun importAllPending(device: BluetoothDevice) {
        if (sessionJob != null) {
            return
        }

        val hostIp = resolveWifiLanIpAddress()
        if (hostIp.isNullOrBlank()) {
            postStatus("Wi-Fi import requires the phone and glasses to share the same hotspot or LAN.")
            return
        }

        onBusyChanged(true)
        postStatus("Connecting to ${device.name ?: device.address}...")
        sessionJob = scope.launch {
            var server: PhoneRecordingTcpServer? = null
            try {
                val sourceName = device.name ?: device.address
                bluetoothClient.connect(device).use { socket ->
                    val channel = SppControlChannel(
                        BufferedInputStream(socket.inputStream, SppTransferConstants.CHUNK_SIZE),
                        BufferedOutputStream(socket.outputStream, SppTransferConstants.CHUNK_SIZE),
                    )
                    channel.sendQueueProbe()
                    val queueState = channel.awaitQueueState()
                    val items = queueState.items
                    if (items.isEmpty()) {
                        postStatus("No pending recordings on the glasses.")
                        return@use
                    }

                    val totalBytes = items.sumOf { it.sizeBytes }.coerceAtLeast(1L)
                    server = PhoneRecordingTcpServer(recordingStore)
                    val port = server.start()
                    postStatus("Using phone LAN receiver $hostIp:$port")
                    channel.sendImportRequest(
                        ImportRequest(
                            transportMode = "wifi_lan",
                            itemIds = items.map { it.id },
                            hostIp = hostIp,
                            port = port,
                        ),
                    )
                    postStatus("Waiting for the glasses to stream ${items.size} recording(s) over LAN...")

                    val receiveDeferred = async {
                        server.awaitBatch(
                            expectedItems = items,
                            sourceDeviceName = sourceName,
                        ) { receivedBytes, totalExpectedBytes ->
                            val denominator = totalExpectedBytes.coerceAtLeast(totalBytes)
                            val percent = ((receivedBytes * 100L) / denominator).toInt()
                            postStatus("Importing over Wi-Fi: $percent%")
                        }
                    }

                    val transferStats = receiveDeferred.await()
                    val result = channel.awaitResult()
                    if (!result.success) {
                        throw IllegalStateException(result.message)
                    }

                    postStatus(
                        "Wi-Fi import complete. ${result.importedCount} recording(s) saved in ${transferStats.elapsedTimeMs} ms.",
                    )
                }
            } catch (error: Exception) {
                postStatus("Wi-Fi import failed: ${error.message}")
            } finally {
                server?.close()
                onBusyChanged(false)
                sessionJob = null
            }
        }
    }

    fun cleanup() {
        sessionJob?.cancel()
        sessionJob = null
        scope.cancel()
    }

    private fun postStatus(message: String) {
        activity.runOnUiThread {
            onStatus(message)
        }
    }

    private fun resolveWifiLanIpAddress(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var bestAddress: String? = null
            var bestScore = Int.MIN_VALUE
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }
                val interfaceName = networkInterface.name.orEmpty()
                if (!isWifiLikeInterface(interfaceName)) {
                    continue
                }
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address !is Inet4Address || address.isLoopbackAddress) {
                        continue
                    }
                    val hostAddress = address.hostAddress ?: continue
                    if (!isPrivateLanAddress(hostAddress)) {
                        continue
                    }
                    val score = scoreInterface(interfaceName, hostAddress)
                    if (score > bestScore) {
                        bestScore = score
                        bestAddress = hostAddress
                    }
                }
            }
            bestAddress
        }.getOrNull()
    }

    private fun isWifiLikeInterface(interfaceName: String): Boolean {
        val lowered = interfaceName.lowercase()
        return lowered == "wlan0" ||
            lowered.startsWith("wlan") ||
            lowered.startsWith("ap") ||
            lowered.contains("wifi") ||
            lowered.contains("softap") ||
            lowered.contains("swlan")
    }

    private fun isPrivateLanAddress(hostAddress: String): Boolean {
        if (hostAddress.startsWith("192.168.") || hostAddress.startsWith("10.")) {
            return true
        }
        val octets = hostAddress.split('.')
        if (octets.size < 2) {
            return false
        }
        val first = octets[0].toIntOrNull() ?: return false
        val second = octets[1].toIntOrNull() ?: return false
        return first == 172 && second in 16..31
    }

    private fun scoreInterface(interfaceName: String, hostAddress: String): Int {
        var score = 0
        val lowered = interfaceName.lowercase()
        when {
            lowered.startsWith("swlan") -> score += 320
            lowered.startsWith("ap") || lowered.contains("softap") -> score += 280
            lowered.contains("hotspot") -> score += 260
            lowered == "wlan0" -> score += 180
            lowered.startsWith("wlan") -> score += 150
            lowered.contains("wifi") -> score += 120
        }
        when {
            hostAddress.startsWith("192.168.") -> score += 80
            hostAddress.startsWith("10.") -> score += 60
            hostAddress.startsWith("172.") -> score += 40
        }
        return score
    }
}
