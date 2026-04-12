package com.rokidscribe

import android.bluetooth.BluetoothDevice
import androidx.appcompat.app.AppCompatActivity
import com.rokidscribe.spp.QueueState
import com.rokidscribe.spp.SppControlChannel
import com.rokidscribe.spp.SppTransferConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream

class QueueProbeSession(
    private val activity: AppCompatActivity,
    private val onStatus: (String) -> Unit,
    private val onBusyChanged: (Boolean) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bluetoothClient = PhoneBluetoothClient(activity)
    private var sessionJob: Job? = null

    fun probe(
        device: BluetoothDevice,
        onQueueState: (QueueState) -> Unit,
    ) {
        if (sessionJob != null) {
            return
        }

        onBusyChanged(true)
        postStatus("Connecting to ${device.name ?: device.address}...")
        sessionJob = scope.launch {
            try {
                val queueState = withContext(Dispatchers.IO) {
                    bluetoothClient.connect(device).use { socket ->
                        val channel = SppControlChannel(
                            BufferedInputStream(socket.inputStream, SppTransferConstants.CHUNK_SIZE),
                            BufferedOutputStream(socket.outputStream, SppTransferConstants.CHUNK_SIZE),
                        )
                        channel.sendQueueProbe()
                        channel.awaitQueueState()
                    }
                }
                postStatus(
                    if (queueState.pendingCount == 0) {
                        "No pending recordings on the glasses."
                    } else {
                        "${queueState.pendingCount} pending recording(s) ready to import."
                    },
                )
                onQueueState(queueState)
            } catch (error: Exception) {
                postStatus("Queue check failed: ${error.message}")
            } finally {
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
}
