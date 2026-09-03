package com.syncpedia.crm.utils

import android.content.Context
import android.provider.ContactsContract
import com.syncpedia.crm.models.ContactItem

object ContactReader {

    fun getAll(ctx: Context): List<ContactItem> {
        if (!PermissionHelper.has(ctx, android.Manifest.permission.READ_CONTACTS)) {
            return emptyList()
        }
        val map = linkedMapOf<String, ContactItem>()
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )
        cursor?.use {
            val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val id = it.getLong(idIdx)
                val name = it.getString(nameIdx) ?: "Unknown"
                val phone = it.getString(numIdx) ?: ""
                val key = "$id-$phone"
                if (!map.containsKey(key)) {
                    map[key] = ContactItem(id = id, name = name, phone = phone)
                }
            }
        }
        return map.values.toList()
    }

    /** Best-effort name match for an incoming number. */
    fun lookupName(ctx: Context, phone: String): String? {
        if (phone.isBlank()) return null
        if (!PermissionHelper.has(ctx, android.Manifest.permission.READ_CONTACTS)) return null
        val want = AppCallTracker.normalize(phone)
        if (want.isEmpty()) return null
        return try {
            getAll(ctx).firstOrNull { AppCallTracker.normalize(it.phone).endsWith(want.takeLast(10))
                || want.endsWith(AppCallTracker.normalize(it.phone).takeLast(10))
            }?.name
        } catch (_: Exception) {
            null
        }
    }
}
