package com.rokidscribe

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.rokidscribe.spp.SppTransferConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class PhoneBluetoothClient(
    private val context: Context,
) {
    private val bluetoothAdapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        if (!hasConnectPermission()) {
            return emptyList()
        }
        return bluetoothAdapter?.bondedDevices
            ?.sortedWith(
                compareByDescending<BluetoothDevice> { scoreImportCandidate(it) }
                    .thenBy { normalizedName(it) }
                    .thenBy { it.address },
            )
            ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun getRecommendedImportDevices(): List<BluetoothDevice> {
        val devices = getPairedDevices()
        val recommended = devices.filter { scoreImportCandidate(it) > 0 }
        return if (recommended.isNotEmpty()) recommended else devices
    }

    fun isImportCandidate(device: BluetoothDevice): Boolean {
        return scoreImportCandidate(device) > 0
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): BluetoothSocket = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter ?: throw IOException("Bluetooth adapter unavailable on the phone.")
        val strategies = listOf<(BluetoothDevice) -> BluetoothSocket>(
            { it.createInsecureRfcommSocketToServiceRecord(SppTransferConstants.APP_UUID) },
            { it.createRfcommSocketToServiceRecord(SppTransferConstants.APP_UUID) },
            {
                val method = it.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                method.invoke(it, 1) as BluetoothSocket
            },
        )

        var lastError: Exception? = null
        for (strategy in strategies) {
            var socket: BluetoothSocket? = null
            try {
                runCatching { adapter.cancelDiscovery() }
                socket = strategy(device)
                socket.connect()
                return@withContext socket
            } catch (error: Exception) {
                lastError = error as? Exception ?: IOException(error.message, error)
                runCatching { socket?.close() }
            }
        }
        throw IOException(lastError?.message ?: "Unable to connect to the glasses over Bluetooth.")
    }

    private fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun scoreImportCandidate(device: BluetoothDevice): Int {
        val name = normalizedName(device)
        var score = 0

        when {
            "rokid" in name -> score += 600
            name.startsWith("rg_") || name.startsWith("rg-") || name.startsWith("rg ") -> score += 420
        }

        if ("glasses" in name || "scribe" in name) {
            score += 240
        }
        if ("ar " in "$name " || name.endsWith(" ar") || "_ar" in name) {
            score += 80
        }

        if ("airpods" in name || "buds" in name || "headset" in name || "headphones" in name) {
            score -= 260
        }
        if ("speaker" in name || "watch" in name || "car" in name) {
            score -= 140
        }

        when (device.bluetoothClass?.majorDeviceClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> score -= 120
            BluetoothClass.Device.Major.COMPUTER -> score -= 40
            BluetoothClass.Device.Major.PHONE -> score -= 40
            BluetoothClass.Device.Major.UNCATEGORIZED -> score += 20
        }

        return score
    }

    private fun normalizedName(device: BluetoothDevice): String {
        return (device.name ?: device.address).trim().lowercase()
    }
}
