package com.rokidscribe

import android.bluetooth.BluetoothDevice
import androidx.appcompat.app.AppCompatActivity
import com.rokidscribe.spp.ImportRequest
import com.rokidscribe.spp.RecordingOffer
import com.rokidscribe.spp.SppControlChannel
import com.rokidscribe.spp.SppFileReceiver
import com.rokidscribe.spp.SppTransferConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream

class SppRecordingImportSession(
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

        onBusyChanged(true)
        postStatus("Connecting to ${device.name ?: device.address}...")
        sessionJob = scope.launch {
            try {
                val sourceName = device.name ?: device.address
                bluetoothClient.connect(device).use { socket ->
                    val input = BufferedInputStream(socket.inputStream, SppTransferConstants.CHUNK_SIZE)
                    val output = BufferedOutputStream(socket.outputStream, SppTransferConstants.CHUNK_SIZE)
                    val channel = SppControlChannel(input, output)
                    channel.sendQueueProbe()
                    val queueState = channel.awaitQueueState()
                    val items = queueState.items
                    if (items.isEmpty()) {
                        postStatus("No pending recordings on the glasses.")
                        return@use
                    }

                    val remainingIds = items.map { item -> item.id }.toMutableSet()
                    val totalBytes = items.sumOf { it.sizeBytes }.coerceAtLeast(1L)
                    channel.sendImportRequest(
                        ImportRequest(
                            transportMode = "spp",
                            itemIds = items.map { it.id },
                        ),
                    )

                    var receivedSoFar = 0L
                    var importedCount = 0
                    repeat(items.size) {
                        val offer = channel.awaitRecordingOffer()
                        ensureOffered(offer, remainingIds)
                        val targetFile = recordingStore.createTargetFile(offer)
                        SppFileReceiver(input, output, targetFile) { currentBytes, _ ->
                            val percent = (((receivedSoFar + currentBytes) * 100L) / totalBytes).toInt()
                            postStatus("Importing over Bluetooth: $percent%")
                        }.receiveFile().getOrThrow()
                        val storedRecording = recordingStore.writeMetadata(targetFile, offer, sourceName)
                        receivedSoFar += offer.sizeBytes
                        if (storedRecording.localAudioPath == targetFile.absolutePath) {
                            importedCount += 1
                            postStatus("Saved ${offer.fileName} on the phone.")
                        } else {
                            postStatus("Skipped duplicate ${offer.fileName}.")
                        }
                    }

                    if (remainingIds.isNotEmpty()) {
                        throw IllegalStateException("Missing recordings in the Bluetooth batch: ${remainingIds.joinToString()}")
                    }
                    val result = channel.awaitResult()
                    if (!result.success) {
                        throw IllegalStateException(result.message)
                    }
                    postStatus("Bluetooth import complete. $importedCount recording(s) saved.")
                }
            } catch (error: Exception) {
                postStatus("Bluetooth import failed: ${error.message}")
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

    private fun ensureOffered(
        offer: RecordingOffer,
        remainingIds: MutableSet<String>,
    ) {
        if (!remainingIds.remove(offer.id)) {
            throw IllegalStateException("The glasses offered an unexpected recording: ${offer.id}")
        }
    }

    private fun postStatus(message: String) {
        activity.runOnUiThread {
            onStatus(message)
        }
    }
}
