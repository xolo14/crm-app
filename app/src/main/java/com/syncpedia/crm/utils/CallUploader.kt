package com.syncpedia.crm.utils

import android.content.Context
import android.util.Log
import com.syncpedia.crm.models.CallLogItem
import com.syncpedia.crm.network.RetrofitClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CallUploader(private val ctx: Context) {

    suspend fun upload(item: CallLogItem, lat: Double?, lng: Double?): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val calledAt = sdf.format(Date(item.date))
            val deviceCallId = item.id.toString()

            fun String.rb() = toRequestBody("text/plain".toMediaType())
            fun Double.rb() = toString().toRequestBody("text/plain".toMediaType())

            val recFile = CallRecorder.findForCall(ctx, item)?.takeIf {
                it.exists() && it.length() >= RecordingConfig.MIN_FILE_SIZE
            }
            val recordingPart = recFile?.let { file ->
                val mime = when (file.extension.lowercase()) {
                    "mp3" -> "audio/mpeg"
                    "wav" -> "audio/wav"
                    "m4a", "mp4", "aac" -> "audio/mp4"
                    else -> "application/octet-stream"
                }
                val body = file.asRequestBody(mime.toMediaType())
                MultipartBody.Part.createFormData("recording", file.name, body)
            }

            val resp = RetrofitClient.api.uploadCall(
                phoneNumber = item.phoneNumber.rb(),
                contactName = item.contactName.rb(),
                callType = CallLogReader.typeLabel(item.callType).rb(),
                duration = item.duration.toString().rb(),
                calledAt = calledAt.rb(),
                deviceCallId = deviceCallId.rb(),
                latitude = lat?.rb(),
                longitude = lng?.rb(),
                leadId = null,
                recording = recordingPart
            )
            val ok = resp.isSuccessful && (resp.body()?.success != false)
            if (ok) {
                SessionManager.getInstance(ctx).markSynced(item.id)
                AppCallStore.markLocalSynced(ctx, item.id)
                recFile?.absolutePath?.let { CallRecorder.markUploaded(ctx, it) }
                true
            } else {
                Log.e("CallUploader", "upload failed: http=${resp.code()}")
                false
            }
        } catch (e: Exception) {
            Log.e("CallUploader", "upload failed: ${e.javaClass.simpleName}")
            false
        }
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
        deviceCallId: String
    ): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val calledAt = sdf.format(Date())

            fun String.rb() = toRequestBody("text/plain".toMediaType())
            fun Double.rb() = toString().toRequestBody("text/plain".toMediaType())

            val usable = recordingFile?.takeIf { it.exists() && it.length() > 1024L }
            val filePart = usable?.let { file ->
                val mime = when (file.extension.lowercase()) {
                    "mp3" -> "audio/mpeg"
                    "wav" -> "audio/wav"
                    "m4a", "mp4", "aac" -> "audio/mp4"
                    else -> "audio/mp4"
                }
                val fileBody = file.asRequestBody(mime.toMediaType())
                MultipartBody.Part.createFormData("recording", file.name, fileBody)
            }

            Log.d(
                "CallUploader",
                "Uploading direct: $phoneNumber, $callType, ${duration}s, " +
                    "recording=${usable?.length()?.let { "${it / 1024}KB" } ?: "none"}"
            )

            val response = RetrofitClient.api.uploadCall(
                phoneNumber = phoneNumber.rb(),
                contactName = contactName.rb(),
                callType = callType.rb(),
                duration = duration.toString().rb(),
                calledAt = calledAt.rb(),
                deviceCallId = deviceCallId.rb(),
                latitude = latitude?.rb(),
                longitude = longitude?.rb(),
                leadId = null,
                recording = filePart
            )

            if (response.isSuccessful && (response.body()?.success != false)) {
                deviceCallId.toLongOrNull()?.let { id ->
                    SessionManager.getInstance(ctx).markSynced(id)
                    AppCallStore.markLocalSynced(ctx, id)
                }
                usable?.absolutePath?.let { CallRecorder.markUploaded(ctx, it) }
                Log.d("CallUploader", "Upload success")
                true
            } else {
                Log.e(
                    "CallUploader",
                    "Upload failed ${response.code()}: ${response.errorBody()?.string()}"
                )
                false
            }
        } catch (e: Exception) {
            Log.e("CallUploader", "Upload exception: ${e.message}")
            false
        }
    }
}
