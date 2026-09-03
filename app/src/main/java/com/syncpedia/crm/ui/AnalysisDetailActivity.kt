package com.syncpedia.crm.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.syncpedia.crm.R
import com.syncpedia.crm.databinding.ActivityAnalysisDetailBinding
import com.syncpedia.crm.databinding.ContentAnalysisListBinding
import com.syncpedia.crm.databinding.ContentAverageDurationBinding
import com.syncpedia.crm.databinding.ContentTopCallerBinding
import com.syncpedia.crm.databinding.ItemAvgDurationRowBinding
import com.syncpedia.crm.databinding.ItemGapDayBinding
import com.syncpedia.crm.databinding.ItemGapRowBinding
import com.syncpedia.crm.databinding.ItemRankedContactBinding
import com.syncpedia.crm.databinding.ItemTopCallerRowBinding
import com.syncpedia.crm.models.AnalysisMetric
import com.syncpedia.crm.models.CallLogItem
import com.syncpedia.crm.models.ContactCallStats
import com.syncpedia.crm.models.GapDaySummary
import com.syncpedia.crm.models.RankedContact
import com.syncpedia.crm.ui.adapters.CallLogAdapter
import com.syncpedia.crm.utils.AnalysisCalculator
import com.syncpedia.crm.utils.AppCallStore
import com.syncpedia.crm.utils.AppDialer
import com.syncpedia.crm.utils.CallLogReader
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AnalysisDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_METRIC = "metric"
        const val EXTRA_FROM = "from"
        const val EXTRA_TO = "to"
        const val EXTRA_PHONE = "phone"

        fun intent(
            ctx: Context,
            metric: AnalysisMetric,
            fromMs: Long,
            toMs: Long,
            phone: String? = null
        ): Intent = Intent(ctx, AnalysisDetailActivity::class.java).apply {
            putExtra(EXTRA_METRIC, metric.name)
            putExtra(EXTRA_FROM, fromMs)
            putExtra(EXTRA_TO, toMs)
            if (!phone.isNullOrBlank()) putExtra(EXTRA_PHONE, phone)
        }
    }

    private lateinit var binding: ActivityAnalysisDetailBinding
    private lateinit var metric: AnalysisMetric
    private var fromMs = 0L
    private var toMs = Long.MAX_VALUE
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnalysisDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        metric = runCatching {
            AnalysisMetric.valueOf(intent.getStringExtra(EXTRA_METRIC) ?: "")
        }.getOrDefault(AnalysisMetric.ALL_CALLS)
        fromMs = intent.getLongExtra(EXTRA_FROM, 0L)
        toMs = intent.getLongExtra(EXTRA_TO, Long.MAX_VALUE)

        binding.tvTitle.text = metric.title
        binding.btnBack.setOnClickListener { finish() }

        lifecycleScope.launch {
            val merged = AppCallStore.fetchMerged(this@AnalysisDetailActivity, appUploadsOnly = false)
            val calls = AnalysisCalculator.filterRange(merged, fromMs, toMs)
            renderMetric(calls)
        }
    }

    private fun renderMetric(calls: List<CallLogItem>) {
        binding.contentHost.removeAllViews()
        when (metric) {
            AnalysisMetric.TOP_CALLER -> showTopCaller(calls, intent.getStringExtra(EXTRA_PHONE))
            AnalysisMetric.AVERAGE_DURATION -> showAverage(calls)
            AnalysisMetric.GAP_TIME -> showGapTime(calls)
            AnalysisMetric.TOP_10_CALLERS,
            AnalysisMetric.TOP_10_INCOMING,
            AnalysisMetric.TOP_10_OUTGOING,
            AnalysisMetric.TOP_10_DURATION,
            AnalysisMetric.TOP_10_INCOMING_DURATION,
            AnalysisMetric.TOP_10_OUTGOING_DURATION -> showRanked(calls)
            AnalysisMetric.LONGEST_DURATION -> {
                val longest = calls.filter { it.duration > 0 }.maxByOrNull { it.duration }
                if (longest != null) {
                    showTopCaller(calls, longest.phoneNumber)
                } else {
                    showCallList(calls)
                }
            }
            else -> showCallList(calls)
        }
    }

    private fun showCallList(all: List<CallLogItem>) {
        val listBinding = ContentAnalysisListBinding.inflate(layoutInflater, binding.contentHost, true)
        val filtered = AnalysisCalculator.filterByMetric(all, metric)
        listBinding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        listBinding.rvList.layoutManager = LinearLayoutManager(this)
        val adapter = CallLogAdapter(
            onCallClick = { AppDialer.dial(this, it.phoneNumber, it.contactName) },
            onItemClick = {},
            onNoteClick = {}
        )
        listBinding.rvList.adapter = adapter
        adapter.submit(filtered)
    }

    private fun showRanked(all: List<CallLogItem>) {
        val listBinding = ContentAnalysisListBinding.inflate(layoutInflater, binding.contentHost, true)
        val ranked = when (metric) {
            AnalysisMetric.TOP_10_CALLERS -> AnalysisCalculator.topByCount(all)
            AnalysisMetric.TOP_10_INCOMING ->
                AnalysisCalculator.topByCount(all, CallLog.Calls.INCOMING_TYPE)
            AnalysisMetric.TOP_10_OUTGOING ->
                AnalysisCalculator.topByCount(all, CallLog.Calls.OUTGOING_TYPE)
            AnalysisMetric.TOP_10_DURATION -> AnalysisCalculator.topByDuration(all)
            AnalysisMetric.TOP_10_INCOMING_DURATION ->
                AnalysisCalculator.topByDuration(all, CallLog.Calls.INCOMING_TYPE)
            AnalysisMetric.TOP_10_OUTGOING_DURATION ->
                AnalysisCalculator.topByDuration(all, CallLog.Calls.OUTGOING_TYPE)
            else -> emptyList()
        }
        listBinding.tvEmpty.visibility = if (ranked.isEmpty()) View.VISIBLE else View.GONE
        listBinding.rvList.layoutManager = LinearLayoutManager(this)
        listBinding.rvList.adapter = RankedAdapter(ranked) { item ->
            startActivity(intent(this, AnalysisMetric.TOP_CALLER, fromMs, toMs, item.phoneNumber))
        }
    }

    private fun showTopCaller(all: List<CallLogItem>, phone: String?) {
        val stats = if (!phone.isNullOrBlank()) {
            AnalysisCalculator.contactStatsForPhone(all, phone)
        } else {
            AnalysisCalculator.topCaller(all)
        }
        if (stats == null) {
            val listBinding = ContentAnalysisListBinding.inflate(layoutInflater, binding.contentHost, true)
            listBinding.tvEmpty.visibility = View.VISIBLE
            listBinding.tvEmpty.text = "No caller data"
            return
        }
        val b = ContentTopCallerBinding.inflate(layoutInflater, binding.contentHost, true)
        bindTopCaller(b, stats)
    }

    private fun bindTopCaller(b: ContentTopCallerBinding, s: ContactCallStats) {
        val initial = s.contactName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        b.tvAvatar.text = initial
        val avatarBg = b.tvAvatar.background
        if (avatarBg is GradientDrawable) {
            avatarBg.setColor(ContextCompat.getColor(this, R.color.incoming_green))
        } else {
            b.tvAvatar.setBackgroundColor(ContextCompat.getColor(this, R.color.incoming_green))
        }
        b.tvName.text = s.contactName
        b.tvPhone.text = s.phoneNumber

        bindCallerRow(b.rowTotal, R.drawable.ic_call, R.color.text_muted, "Total Calls",
            s.totalDuration, s.totalCalls, showIcon = false)
        bindCallerRow(b.rowIncoming, R.drawable.ic_incoming, R.color.outgoing_blue, "Incoming Calls",
            s.incomingDuration, s.incoming)
        bindCallerRow(b.rowOutgoing, R.drawable.ic_outgoing, R.color.incoming_green, "Outgoing Calls",
            s.outgoingDuration, s.outgoing)
        bindCallerRow(b.rowMissed, R.drawable.ic_missed, R.color.missed_red, "Missed Calls",
            null, s.missed)
        bindCallerRow(b.rowNotPicked, R.drawable.ic_outgoing, R.color.syncpedia_orange,
            "Not Picked Outgoing Calls", null, s.notPickedOutgoing)
        bindCallerRow(b.rowRejected, R.drawable.ic_missed, R.color.rejected_red, "Rejected Calls",
            null, s.rejected)

        b.btnCall.setOnClickListener { AppDialer.dial(this, s.phoneNumber, s.contactName) }
        b.btnSms.setOnClickListener {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${s.phoneNumber}")))
        }
        b.btnWhatsapp.setOnClickListener {
            val digits = s.phoneNumber.replace(Regex("[^0-9+]"), "")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")))
            } catch (_: Exception) {
                Toast.makeText(this, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindCallerRow(
        row: ItemTopCallerRowBinding,
        icon: Int,
        color: Int,
        label: String,
        durationSec: Long?,
        calls: Int,
        showIcon: Boolean = true
    ) {
        row.tvRowLabel.text = label
        row.tvRowCalls.text = calls.toString()
        if (durationSec != null) {
            row.tvRowDuration.visibility = View.VISIBLE
            row.tvRowDuration.text = CallLogReader.formatDuration(durationSec)
        } else {
            row.tvRowDuration.visibility = View.GONE
        }
        row.ivRowIcon.visibility = if (showIcon) View.VISIBLE else View.INVISIBLE
        row.ivRowIcon.setImageResource(icon)
        val bg = row.ivRowIcon.background.mutate()
        if (bg is GradientDrawable) {
            bg.setColor(ContextCompat.getColor(this, color))
        }
    }

    private fun showAverage(all: List<CallLogItem>) {
        val stats = AnalysisCalculator.averageDuration(all)
        val b = ContentAverageDurationBinding.inflate(layoutInflater, binding.contentHost, true)
        b.tvTotalDays.text = "${stats.totalDays} Days"
        b.tvTotalDuration.text = CallLogReader.formatDuration(stats.totalDurationSec)
        b.tvAvgPerDay.text = CallLogReader.formatDuration(stats.avgPerDaySec)

        bindAvgRow(b.rowAvgIncoming, "Incoming", stats.incomingCalls, stats.incomingDuration)
        bindAvgRow(b.rowAvgOutgoing, "Outgoing", stats.outgoingCalls, stats.outgoingDuration)
        val totalCalls = stats.incomingCalls + stats.outgoingCalls
        val totalDur = stats.incomingDuration + stats.outgoingDuration
        bindAvgRow(b.rowAvgTotal, "Total Calls", totalCalls, totalDur)
    }

    private fun bindAvgRow(row: ItemAvgDurationRowBinding, type: String, calls: Int, duration: Long) {
        row.tvType.text = type
        row.tvCalls.text = calls.toString()
        row.tvDuration.text = CallLogReader.formatDuration(duration)
        val avg = if (calls > 0) duration / calls else 0L
        row.tvAvg.text = CallLogReader.formatDuration(avg)
    }

    private fun showGapTime(all: List<CallLogItem>) {
        val listBinding = ContentAnalysisListBinding.inflate(layoutInflater, binding.contentHost, true)
        val days = AnalysisCalculator.gapDays(all)
        listBinding.tvEmpty.visibility = if (days.isEmpty()) View.VISIBLE else View.GONE
        listBinding.rvList.layoutManager = LinearLayoutManager(this)
        listBinding.rvList.adapter = GapDayAdapter(days, timeFmt)
    }

    private class RankedAdapter(
        private val items: List<RankedContact>,
        private val onClick: (RankedContact) -> Unit
    ) : RecyclerView.Adapter<RankedAdapter.VH>() {

        class VH(val b: ItemRankedContactBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemRankedContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.b.tvRank.text = item.rank.toString()
            holder.b.tvName.text = item.contactName
            holder.b.tvPhone.text = item.phoneNumber
            holder.b.tvCount.text = "${item.count} calls"
            holder.b.tvDuration.text = CallLogReader.formatDuration(item.durationSec)
            holder.b.root.setOnClickListener { onClick(item) }
        }
    }

    private class GapDayAdapter(
        private val days: List<GapDaySummary>,
        private val timeFmt: SimpleDateFormat
    ) : RecyclerView.Adapter<GapDayAdapter.VH>() {

        class VH(val b: ItemGapDayBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemGapDayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = days.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val day = days[position]
            val b = holder.b
            b.tvDate.text = day.dateLabel
            b.tvDayMeta.text = "${day.dayOfWeekLabel} · DATA SUMMARY"
            b.tvTotalCalls.text = day.totalCalls.toString()
            b.tvTalkTime.text = CallLogReader.formatDuration(day.talkTimeSec)
            b.tvGapTime.text = CallLogReader.formatDuration(day.totalGapSec)
            val start = timeFmt.format(Date(day.rangeStartMs))
            val end = timeFmt.format(Date(day.rangeEndMs))
            b.tvRhythmRange.text = "$start – $end"
            b.tvRhythmStart.text = start
            b.tvRhythmEnd.text = end
            b.rhythmBar.setData(day.segments, day.rangeStartMs, day.rangeEndMs)
            b.tvGapsCount.text = "TOTAL ${day.gaps.size} GAPS"
            b.gapsContainer.removeAllViews()
            val inflater = LayoutInflater.from(b.root.context)
            for (gap in day.gaps) {
                val row = ItemGapRowBinding.inflate(inflater, b.gapsContainer, false)
                row.tvGapRange.text =
                    "${timeFmt.format(Date(gap.startMs))} - ${timeFmt.format(Date(gap.endMs))}"
                row.tvGapDuration.text = CallLogReader.formatDuration(gap.durationMs / 1000L)
                b.gapsContainer.addView(row.root)
            }
        }
    }
}
