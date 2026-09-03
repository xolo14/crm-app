package com.syncpedia.crm.ui.adapters

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.CallLog
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.syncpedia.crm.R
import com.syncpedia.crm.databinding.ItemCallLogBinding
import com.syncpedia.crm.databinding.ItemDateHeaderBinding
import com.syncpedia.crm.models.CallLogItem
import com.syncpedia.crm.utils.CallLogReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallLogAdapter(
    private val onCallClick: (CallLogItem) -> Unit,
    private val onItemClick: (CallLogItem) -> Unit,
    private val onNoteClick: (CallLogItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val dateLabel: String) : Row()
        data class Call(val item: CallLogItem) : Row()
    }

    private val rows = mutableListOf<Row>()
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
    private val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.US)

    fun submit(calls: List<CallLogItem>) {
        rows.clear()
        var lastDay = ""
        for (call in calls) {
            val day = dateFmt.format(Date(call.date))
            if (day != lastDay) {
                rows.add(Row.Header(day))
                lastDay = day
            }
            rows.add(Row.Call(call))
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Call -> TYPE_CALL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemDateHeaderBinding.inflate(inflater, parent, false))
        } else {
            CallVH(ItemCallLogBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).bind(row.dateLabel)
            is Row.Call -> (holder as CallVH).bind(row.item)
        }
    }

    inner class HeaderVH(private val b: ItemDateHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(label: String) {
            b.tvDate.text = label
        }
    }

    inner class CallVH(private val b: ItemCallLogBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: CallLogItem) {
            val ctx = b.root.context
            b.tvName.text = item.contactName
            b.tvPhone.text = item.phoneNumber
            b.tvTime.text = timeFmt.format(Date(item.date)).lowercase(Locale.US)
            b.tvBadge.text = "1"

            val (color, icon) = when (item.callType) {
                CallLog.Calls.INCOMING_TYPE ->
                    R.color.incoming_green to R.drawable.ic_incoming
                CallLog.Calls.OUTGOING_TYPE ->
                    R.color.outgoing_blue to R.drawable.ic_outgoing
                CallLog.Calls.MISSED_TYPE ->
                    R.color.missed_red to R.drawable.ic_missed
                CallLog.Calls.REJECTED_TYPE ->
                    R.color.rejected_red to R.drawable.ic_missed
                else ->
                    R.color.tab_unselected to R.drawable.ic_call
            }

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(ctx, color))
            }
            b.avatarBg.background = bg
            b.ivCallType.setImageResource(icon)

            if (item.callType == CallLog.Calls.MISSED_TYPE ||
                item.callType == CallLog.Calls.REJECTED_TYPE
            ) {
                b.tvDuration.text = if (item.callType == CallLog.Calls.REJECTED_TYPE) "rejected" else "missed"
                b.tvDuration.setTextColor(ContextCompat.getColor(ctx, R.color.missed_red))
            } else {
                b.tvDuration.text = CallLogReader.formatDuration(item.duration)
                b.tvDuration.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }

            b.nameBlock.setOnClickListener { onItemClick(item) }
            b.btnCall.setOnClickListener { onCallClick(item) }

            b.btnCopy.setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("phone", item.phoneNumber))
                Toast.makeText(ctx, "Number copied", Toast.LENGTH_SHORT).show()
            }
            b.btnSms.setOnClickListener {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:${item.phoneNumber}")
                }
                ctx.startActivity(intent)
            }
            b.btnWhatsapp.setOnClickListener {
                val phone = item.phoneNumber.replace(Regex("[^0-9+]"), "")
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://wa.me/$phone")
                    }
                    ctx.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(ctx, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
                }
            }
            b.btnNote.setOnClickListener { onNoteClick(item) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CALL = 1
    }
}
