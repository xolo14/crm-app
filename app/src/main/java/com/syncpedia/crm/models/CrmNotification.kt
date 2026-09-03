package com.syncpedia.crm.models

import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/** Row from GET /api/notifications.php (same as website bell). */
data class CrmNotification(
    val id: String = "",
    val title: String? = null,
    val message: String? = null,
    val type: String? = null,
    @SerializedName("is_read")
    @JsonAdapter(ReadFlagAdapter::class)
    val isRead: Boolean = false,
    val link: String? = null,
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("user_id")
    val userId: String? = null,
    @SerializedName("org_id")
    val orgId: String? = null
)

/** MySQL may send 0/1, "0"/"1", or true/false. */
class ReadFlagAdapter : TypeAdapter<Boolean>() {
    override fun write(out: JsonWriter, value: Boolean?) {
        out.value(value == true)
    }

    override fun read(reader: JsonReader): Boolean {
        return when (reader.peek()) {
            JsonToken.NULL -> {
                reader.nextNull()
                false
            }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NUMBER -> reader.nextInt() != 0
            JsonToken.STRING -> {
                val s = reader.nextString()
                s == "1" || s.equals("true", ignoreCase = true)
            }
            else -> {
                reader.skipValue()
                false
            }
        }
    }
}

data class MarkAllReadRequest(val ids: List<String>)

data class BulkIdsRequest(val ids: List<String>)
