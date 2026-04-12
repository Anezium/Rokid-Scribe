package com.rokidscribe.spp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

data class RecordingDescriptor(
    val id: String,
    val fileName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val createdAtEpochMs: Long,
)

data class QueueState(
    val deviceName: String?,
    val pendingCount: Int,
    val items: List<RecordingDescriptor>,
)

data class ImportRequest(
    val transportMode: String,
    val itemIds: List<String>,
    val hostIp: String? = null,
    val port: Int? = null,
)

data class RecordingOffer(
    val id: String,
    val fileName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val createdAtEpochMs: Long,
    val md5Hex: String,
)

data class ImportResult(
    val success: Boolean,
    val importedCount: Int = 0,
    val failedCount: Int = 0,
    val message: String,
)

class SppControlChannel(
    input: BufferedInputStream,
    output: BufferedOutputStream,
) {
    private val input = DataInputStream(input)
    private val output = DataOutputStream(output)

    suspend fun sendQueueProbe() = withContext(Dispatchers.IO) {
        writeJson(JSONObject().put("type", "queue_probe"))
    }

    suspend fun awaitQueueProbe() = withContext(Dispatchers.IO) {
        val payload = readJson()
        if (payload.optString("type") != "queue_probe") {
            throw IOException("Unexpected control message: ${payload.optString("type")}")
        }
    }

    suspend fun sendQueueState(state: QueueState) = withContext(Dispatchers.IO) {
        val items = JSONArray()
        state.items.forEach { item ->
            items.put(
                JSONObject()
                    .put("id", item.id)
                    .put("fileName", item.fileName)
                    .put("sizeBytes", item.sizeBytes)
                    .put("durationMs", item.durationMs)
                    .put("createdAtEpochMs", item.createdAtEpochMs),
            )
        }
        writeJson(
            JSONObject()
                .put("type", "queue_state")
                .put("deviceName", state.deviceName)
                .put("pendingCount", state.pendingCount)
                .put("items", items),
        )
    }

    suspend fun awaitQueueState(): QueueState = withContext(Dispatchers.IO) {
        val payload = readJson()
        if (payload.optString("type") != "queue_state") {
            throw IOException("Unexpected control message: ${payload.optString("type")}")
        }
        val itemsJson = payload.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (index in 0 until itemsJson.length()) {
                val item = itemsJson.getJSONObject(index)
                add(
                    RecordingDescriptor(
                        id = item.getString("id"),
                        fileName = item.getString("fileName"),
                        sizeBytes = item.getLong("sizeBytes"),
                        durationMs = item.optLong("durationMs", 0L),
                        createdAtEpochMs = item.optLong("createdAtEpochMs", 0L),
                    ),
                )
            }
        }
        QueueState(
            deviceName = payload.optString("deviceName").ifBlank { null },
            pendingCount = payload.optInt("pendingCount", items.size),
            items = items,
        )
    }

    suspend fun sendImportRequest(request: ImportRequest) = withContext(Dispatchers.IO) {
        val itemIds = JSONArray()
        request.itemIds.forEach(itemIds::put)
        writeJson(
            JSONObject()
                .put("type", "import_request")
                .put("transportMode", request.transportMode)
                .put("itemIds", itemIds)
                .put("hostIp", request.hostIp)
                .put("port", request.port),
        )
    }

    suspend fun awaitImportRequest(): ImportRequest = withContext(Dispatchers.IO) {
        val payload = readJson()
        if (payload.optString("type") != "import_request") {
            throw IOException("Unexpected control message: ${payload.optString("type")}")
        }
        val itemIdsJson = payload.optJSONArray("itemIds") ?: JSONArray()
        val itemIds = buildList {
            for (index in 0 until itemIdsJson.length()) {
                add(itemIdsJson.getString(index))
            }
        }
        ImportRequest(
            transportMode = payload.optString("transportMode", "spp"),
            itemIds = itemIds,
            hostIp = payload.optString("hostIp").ifBlank { null },
            port = payload.optInt("port").takeIf { it > 0 },
        )
    }

    suspend fun sendRecordingOffer(offer: RecordingOffer) = withContext(Dispatchers.IO) {
        writeJson(
            JSONObject()
                .put("type", "recording_offer")
                .put("id", offer.id)
                .put("fileName", offer.fileName)
                .put("sizeBytes", offer.sizeBytes)
                .put("durationMs", offer.durationMs)
                .put("createdAtEpochMs", offer.createdAtEpochMs)
                .put("md5Hex", offer.md5Hex),
        )
    }

    suspend fun awaitRecordingOffer(): RecordingOffer = withContext(Dispatchers.IO) {
        val payload = readJson()
        if (payload.optString("type") != "recording_offer") {
            throw IOException("Unexpected control message: ${payload.optString("type")}")
        }
        RecordingOffer(
            id = payload.getString("id"),
            fileName = payload.getString("fileName"),
            sizeBytes = payload.getLong("sizeBytes"),
            durationMs = payload.optLong("durationMs", 0L),
            createdAtEpochMs = payload.optLong("createdAtEpochMs", 0L),
            md5Hex = payload.getString("md5Hex"),
        )
    }

    suspend fun sendResult(result: ImportResult) = withContext(Dispatchers.IO) {
        writeJson(
            JSONObject()
                .put("type", "result")
                .put("success", result.success)
                .put("importedCount", result.importedCount)
                .put("failedCount", result.failedCount)
                .put("message", result.message),
        )
    }

    suspend fun awaitResult(): ImportResult = withContext(Dispatchers.IO) {
        val payload = readJson()
        if (payload.optString("type") != "result") {
            throw IOException("Unexpected control message: ${payload.optString("type")}")
        }
        ImportResult(
            success = payload.optBoolean("success"),
            importedCount = payload.optInt("importedCount", 0),
            failedCount = payload.optInt("failedCount", 0),
            message = payload.optString("message", "No result message."),
        )
    }

    private fun writeJson(payload: JSONObject) {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        output.writeInt(body.size)
        output.write(body)
        output.flush()
    }

    private fun readJson(): JSONObject {
        val length = input.readInt()
        if (length <= 0) {
            throw IOException("SPP control payload is empty.")
        }
        val body = ByteArray(length)
        input.readFully(body)
        return JSONObject(String(body, Charsets.UTF_8))
    }
}
