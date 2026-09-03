package com.syncpedia.crm.utils

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.util.Log
import android.view.LayoutInflater
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.syncpedia.crm.R

/**
 * Places a normal cellular call. Recording runs only when Syncpedia is the
 * default Phone app (SyncpediaInCallService). Calls auto-sync ~10s after hangup.
 */
object AppDialer {

    private const val TAG = "AppDialer"

    data class SimLine(
        val slotLabel: String,
        val number: String,
        val handle: PhoneAccountHandle?,
        val badgeColor: Int = 0,
        val subscriptionId: Int = -1,
        val slotIndex: Int = -1
    ) {
        val titleLine: String get() = slotLabel
        val subtitleLine: String get() = number.ifBlank { "Number unavailable" }
        val simKey: String
            get() = SimRecordingPrefs.keyFor(
                subscriptionId = subscriptionId.takeIf { it >= 0 },
                slotIndex = slotIndex.takeIf { it >= 0 },
                handle = handle
            )
        val slotShort: String
            get() = when {
                slotIndex >= 0 -> "SIM ${slotIndex + 1}"
                else -> slotLabel.take(12)
            }
    }

    private val badgeColors = intArrayOf(
        0xFF26A67D.toInt(),
        0xFF7B61FF.toInt(),
        0xFF378ADD.toInt(),
        0xFFFF6200.toInt()
    )

    fun dial(ctx: Context, phone: String, displayName: String = "") {
        val trimmed = sanitizeTel(phone)
        if (trimmed.isEmpty()) {
            Toast.makeText(ctx, "No phone number", Toast.LENGTH_SHORT).show()
            return
        }

        if (!PermissionHelper.has(ctx, Manifest.permission.CALL_PHONE)) {
            if (ctx is Activity) {
                PermissionHelper.requestAll(ctx)
                Toast.makeText(ctx, "Allow Phone permission, then dial again", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(ctx, "Phone permission required", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val lines = listSimLines(ctx)
        when {
            lines.size <= 1 -> placeCall(ctx, trimmed, lines.firstOrNull(), displayName)
            else -> showSimPicker(ctx, trimmed, displayName, lines)
        }
    }

    private fun showSimPicker(
        ctx: Context,
        phone: String,
        name: String,
        lines: List<SimLine>
    ) {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LayoutInflater.from(ctx).inflate(R.layout.dialog_sim_picker, null)
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.88f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        dialog.setCancelable(true)

        root.findViewById<TextView>(R.id.tvSimDialogSubtitle).text =
            if (name.isNotBlank()) "Calling $name" else "Syncpedia CRM"

        val container = root.findViewById<LinearLayout>(R.id.simOptionsContainer)
        lines.forEachIndexed { index, line ->
            val row = LayoutInflater.from(ctx).inflate(R.layout.item_sim_choice, container, false)
            val badge = row.findViewById<TextView>(R.id.tvSimBadge)
            badge.text = "${index + 1}"
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * ctx.resources.displayMetrics.density
                setColor(
                    if (line.badgeColor != 0) line.badgeColor
                    else badgeColors[index % badgeColors.size]
                )
            }
            badge.background = bg
            row.findViewById<TextView>(R.id.tvSimName).text = line.titleLine
            row.findViewById<TextView>(R.id.tvSimNumber).text = line.subtitleLine
            row.setOnClickListener {
                dialog.dismiss()
                placeCall(ctx, phone, line, name)
            }
            container.addView(row)
        }
        dialog.show()
    }

    private fun placeCall(
        ctx: Context,
        phone: String,
        line: SimLine?,
        displayName: String
    ) {
        val handle = line?.handle
        val simKey = line?.simKey ?: "default"
        AppCallTracker.markPlaced(ctx, phone, displayName, simKey)
        val label = displayName.ifBlank { phone }
        val isDialer = DefaultDialerHelper.isDefaultDialer(ctx)
        val allowRecord = SimRecordingPrefs.isSimAllowed(ctx, simKey)

        val placed = if (isDialer) {
            placeViaTelecom(ctx, phone, handle) || placeViaActionCall(ctx, phone, handle)
        } else {
            placeViaActionCall(ctx, phone, handle) || placeViaTelecom(ctx, phone, handle)
        }

        if (!placed) {
            Toast.makeText(ctx, "Could not place call — check Phone permission", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(
            ctx,
            if (allowRecord) "Calling $label…" else "Calling $label… (recording off for this SIM)",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun sanitizeTel(raw: String): String =
        raw.trim().filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

    private fun placeViaTelecom(
        ctx: Context,
        phone: String,
        handle: PhoneAccountHandle?
    ): Boolean {
        return try {
            val telecom = ctx.getSystemService(TelecomManager::class.java) ?: return false
            val uri = Uri.fromParts("tel", phone, null)
            val extras = Bundle()
            if (handle != null) {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }
            telecom.placeCall(uri, extras)
            Log.d(TAG, "telecom.placeCall ok")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "placeCall security: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "placeCall failed: ${e.message}")
            false
        }
    }

    private fun placeViaActionCall(
        ctx: Context,
        phone: String,
        handle: PhoneAccountHandle?
    ): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", phone, null))
            if (handle != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            Log.d(TAG, "ACTION_CALL ok")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "ACTION_CALL security: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "ACTION_CALL failed: ${e.message}")
            false
        }
    }

    fun listSimLines(ctx: Context): List<SimLine> {
        val result = mutableListOf<SimLine>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val telecom = ctx.getSystemService(TelecomManager::class.java) ?: return emptyList()
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    return listOf(SimLine("Default SIM", "", null, slotIndex = 0))
                }
                val handles = telecom.callCapablePhoneAccounts
                if (handles.isNullOrEmpty()) {
                    return listOf(SimLine("Default SIM", "", null, slotIndex = 0))
                }
                val subMgr = ctx.getSystemService(SubscriptionManager::class.java)
                val subs = try {
                    subMgr?.activeSubscriptionInfoList
                } catch (_: SecurityException) {
                    null
                }

                handles.forEachIndexed { index, handle ->
                    var slotLabel = "SIM ${index + 1}"
                    var number = ""
                    var subId = -1
                    var slotIdx = index
                    try {
                        val account = telecom.getPhoneAccount(handle)
                        val accLabel = account?.label?.toString()?.takeIf { it.isNotBlank() }
                        if (accLabel != null) slotLabel = accLabel

                        val info = matchSubscription(subs, handle, index)
                        if (info != null) {
                            subId = info.subscriptionId
                            slotIdx = info.simSlotIndex
                            val dn = info.displayName?.toString()?.takeIf { it.isNotBlank() }
                            val carrier = info.carrierName?.toString()?.takeIf { it.isNotBlank() }
                            slotLabel = when {
                                dn != null && carrier != null && !dn.equals(carrier, true) ->
                                    "SIM ${info.simSlotIndex + 1} · $dn"
                                dn != null -> "SIM ${info.simSlotIndex + 1} · $dn"
                                carrier != null -> "SIM ${info.simSlotIndex + 1} · $carrier"
                                else -> "SIM ${info.simSlotIndex + 1}"
                            }
                            val rawNumber = @Suppress("DEPRECATION") info.number
                            number = formatSimNumber(rawNumber)
                        }
                    } catch (_: Exception) {
                    }
                    result.add(
                        SimLine(
                            slotLabel = slotLabel,
                            number = number,
                            handle = handle,
                            badgeColor = badgeColors[index % badgeColors.size],
                            subscriptionId = subId,
                            slotIndex = slotIdx
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        if (result.isEmpty()) result.add(SimLine("Default SIM", "", null, slotIndex = 0))
        return result
    }

    fun simKeyForHandle(ctx: Context, handle: PhoneAccountHandle?): String {
        if (handle == null) return "default"
        return listSimLines(ctx).firstOrNull { it.handle == handle }?.simKey
            ?: SimRecordingPrefs.keyFor(handle = handle)
    }

    private fun matchSubscription(
        subs: List<android.telephony.SubscriptionInfo>?,
        handle: PhoneAccountHandle,
        index: Int
    ): android.telephony.SubscriptionInfo? {
        if (subs.isNullOrEmpty()) return null
        val hid = handle.id
        subs.firstOrNull { it.subscriptionId.toString() == hid }?.let { return it }
        subs.firstOrNull { hid.contains(it.iccId.orEmpty()) && it.iccId.isNotBlank() }?.let { return it }
        return subs.getOrNull(index)
    }

    private fun formatSimNumber(raw: String?): String {
        val n = raw?.trim().orEmpty()
        if (n.isEmpty() || n.equals("unknown", true)) return ""
        val digits = n.filter { it.isDigit() || it == '+' }
        if (digits.length < 8) return n
        return if (digits.startsWith("+")) {
            digits
        } else if (digits.length == 12 && digits.startsWith("91")) {
            "91 ${digits.substring(2, 7)} ${digits.substring(7)}"
        } else if (digits.length == 10) {
            "${digits.substring(0, 5)} ${digits.substring(5)}"
        } else {
            digits
        }
    }
}
