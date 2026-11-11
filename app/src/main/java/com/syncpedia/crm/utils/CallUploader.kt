package com.syncpedia.crm.utils

import android.content.Context
import android.util.Log
import com.syncpedia.crm.models.CallLogItem
import com.syncpedia.crm.models.CallLogUploadRequest
import com.syncpedia.crm.network.RetrofitClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallUploader(private val ctx: Context) {
    @Volatile var lastError: String? = null

    suspend fun upload(item: CallLogItem, lat: Double?, lng: Double?, recordingPath: String? = null): Boolean {
        return uploadToWebsite(
            phoneNumber = item.phoneNumber,
            contactName = item.contactName,
            callType = CallLogReader.typeLabel(item.callType),
            duration = item.duration.toInt(),
            calledAtMs = item.date,
            deviceCallId = item.id.toString(),
            lat = lat,
            lng = lng,
            recordingPath = recordingPath,
            localCallId = item.id
        )
    }

    /** Upload a call directly from InCallService (optional recording file). */
    suspend fun uploadDirect(
        phoneNumber: String,
        contactName: String,
        callType: String,
        duration: Int,
        recordingFile: File?,
        latitude: Double?,
        longitude: Double?,
        deviceCallId: String,
        calledAtMs: Long = 0L
    ): Boolean {
        return uploadToWebsite(
            phoneNumber = phoneNumber,
            contactName = contactName,
            callType = callType,
            duration = duration,
            calledAtMs = if (calledAtMs > 0) calledAtMs else System.currentTimeMillis() - duration * 1000L,
            deviceCallId = deviceCallId,
            lat = latitude,
            lng = longitude,
            recordingFile = recordingFile,
            localCallId = deviceCallId.toLongOrNull()
        )
    }

    private suspend fun uploadToWebsite(
        phoneNumber: String,
        contactName: String,
        callType: String,
        duration: Int,
        calledAtMs: Long,
        deviceCallId: String,
        lat: Double?,
        lng: Double?,
        recordingPath: String? = null,
        recordingFile: File? = null,
        localCallId: Long? = null
    ): Boolean {
        return try {
            val phone = formatPhone(phoneNumber)
            val type = normalizeType(callType, duration)
            val status = mapStatus(type, duration)
            val (callDate, callTime) = formatLocalDateTime(calledAtMs)
            val name = contactName.ifBlank { phone.ifBlank { "Unknown" } }
            val file = recordingFile ?: recordingPath?.let { File(it) }
            val usable = file?.takeIf { it.exists() && it.length() >= RecordingConfig.MIN_FILE_SIZE }

            Log.d(
                TAG,
                "POST call_logs.php add_log $phone $type $status ${duration}s $callDate $callTime " +
                    "recording=${usable?.length()?.let { "${it / 1024}KB" } ?: "none"}"
            )

            val response = if (usable != null) {
                fun String.rb() = toRequestBody("text/plain".toMediaType())
                val mime = when (usable.extension.lowercase()) {
                    "mp3" -> "audio/mpeg"
                    "wav" -> "audio/wav"
                    "m4a", "mp4", "aac" -> "audio/mp4"
                    else -> "audio/mp4"
                }
                val part = MultipartBody.Part.createFormData(
                    "call_recording",
                    usable.name,
                    usable.asRequestBody(mime.toMediaType())
                )
                RetrofitClient.api.addCallLogWithRecording(
                    callType = type.rb(),
                    callStatus = status.rb(),
                    durationSeconds = duration.toString().rb(),
                    callDate = callDate.rb(),
                    callTime = callTime.rb(),
                    clientPhone = phone.rb(),
                    clientName = name.rb(),
                    deviceCallId = deviceCallId.rb(),
                    latitude = lat?.toString()?.rb(),
                    longitude = lng?.toString()?.rb(),
                    recording = part
                )
            } else {
                RetrofitClient.api.addCallLog(
                    body = CallLogUploadRequest(
                        callType = type,
                        callStatus = status,
                        durationSeconds = duration,
                        callDate = callDate,
                        callTime = callTime,
                        clientPhone = phone,
                        clientName = name,
                        deviceCallId = deviceCallId,
                        latitude = lat,
                        longitude = lng,
                        notes = "Synced from Syncpedia app"
                    )
                )
            }

            val raw = if (response.isSuccessful) {
                response.body()?.string().orEmpty()
            } else {
                response.errorBody()?.string().orEmpty()
            }
            val serverError = extractError(raw)
            val confirmed = Regex(""""success"\s*:\s*true""").containsMatchIn(raw)
            val alreadyLogged = response.code() == 409 && serverError?.startsWith("Duplicate call log") == true
            val ok = alreadyLogged || (response.isSuccessful && serverError == null && confirmed)
            if (ok) {
                localCallId?.let { id ->
                    SessionManager.getInstance(ctx).markSynced(id)
                    AppCallStore.markLocalSynced(ctx, id)
                }
                usable?.absolutePath?.let { CallRecorder.markUploaded(ctx, it) }
                lastError = null
                Log.d(TAG, "Website call log saved $raw")
                true
            } else {
                lastError = serverError ?: "HTTP ${response.code()} unconfirmed: $raw".take(240)
                Log.e(TAG, "call_logs upload failed $lastError")
                false
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "call_logs upload exception: $lastError", e)
            false
        }
    }

    private fun extractError(raw: String): String? {
        if (raw.isBlank()) return null
        val match = Regex(""""error"\s*:\s*"([^"]+)"""").find(raw)
        return match?.groupValues?.get(1)
    }

    private fun normalizeType(raw: String, duration: Int): String {
        return when (raw.trim().lowercase()) {
            "incoming" -> "incoming"
            "outgoing" -> "outgoing"
            "missed" -> "missed"
            "rejected" -> "rejected"
            else -> if (duration <= 0) "missed" else "outgoing"
        }
    }

    private fun mapStatus(callType: String, duration: Int): String {
        if (duration > 0 && callType != "missed" && callType != "rejected") return "connected"
        return if (callType == "outgoing") "not_pickup_by_client" else "never_attended"
    }

    private fun formatPhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        val compact = if (digits.isNotEmpty()) digits else raw.filter { !it.isWhitespace() }
        return compact.take(20)
    }

    private fun formatLocalDateTime(ms: Long): Pair<String, String> {
        val whenMs = if (ms > 0) ms else System.currentTimeMillis()
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val date = Date(whenMs)
        return dateFmt.format(date) to timeFmt.format(date)
    }

    companion object {
        private const val TAG = "CallUploader"
    }
}
