package com.syncpedia.crm.ui.fragments

import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.syncpedia.crm.R
import com.syncpedia.crm.databinding.FragmentAnalysisBinding
import com.syncpedia.crm.databinding.ItemStatCardBinding
import com.syncpedia.crm.models.AnalysisKpiCard
import com.syncpedia.crm.models.AnalysisMetric
import com.syncpedia.crm.ui.AnalysisDetailActivity
import com.syncpedia.crm.viewmodels.AnalysisViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AnalysisFragment : Fragment() {

    private var _binding: FragmentAnalysisBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AnalysisViewModel by viewModels()

    private var fromMs: Long = 0L
    private var toMs: Long = Long.MAX_VALUE
    private var showingSummary = true
    private var timelineLabel: String = ""
    private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.US)

    private enum class Timeline {
        ALL, TODAY, YESTERDAY, CURRENT_WEEK, PREV_WEEK,
        CURRENT_MONTH, PREV_MONTH, CURRENT_YEAR, CUSTOM
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnalysisBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        timelineLabel = getString(R.string.filter_all)
        updateDateLabel()
        setupChart()

        binding.dateHeader.setOnClickListener { showFilterMenu() }
        binding.tvDateRange.setOnClickListener { showFilterMenu() }
        binding.tabSummary.setOnClickListener { selectTab(summary = true) }
        binding.tabDetailed.setOnClickListener { selectTab(summary = false) }

        viewModel.summaryCards.observe(viewLifecycleOwner) {
            if (showingSummary) renderCards(it)
        }
        viewModel.analysisCards.observe(viewLifecycleOwner) {
            if (!showingSummary) renderCards(it)
        }
        viewModel.chartData.observe(viewLifecycleOwner) { renderChart() }
        // The first load runs in onResume, right after this.
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) refresh()
    }

    private fun selectTab(summary: Boolean) {
        showingSummary = summary
        val teal = ContextCompat.getColor(requireContext(), R.color.syncpedia_teal)
        val muted = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        binding.tabSummary.setTextColor(if (summary) teal else muted)
        binding.tabDetailed.setTextColor(if (summary) muted else teal)
        binding.underlineSummary.setBackgroundColor(if (summary) teal else Color.TRANSPARENT)
        binding.underlineDetailed.setBackgroundColor(if (summary) Color.TRANSPARENT else teal)
        val cards = if (summary) viewModel.summaryCards.value else viewModel.analysisCards.value
        renderCards(cards.orEmpty())
    }

    private fun setupChart() {
        binding.pieChart.apply {
            setUsePercentValues(true)
            description.isEnabled = false
            setHoleColor(ContextCompat.getColor(requireContext(), R.color.bg_card))
            setTransparentCircleAlpha(0)
            holeRadius = 55f
            transparentCircleRadius = 58f
            setDrawEntryLabels(false)
            legend.textColor = ContextCompat.getColor(requireContext(), R.color.text_primary)
            legend.isWordWrapEnabled = true
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.bg_card))
            setNoDataTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
        }
    }

    private fun refresh() {
        viewModel.loadMerged(requireContext(), fromMs, toMs)
    }

    private fun recomputeLocal() {
        viewModel.recomputeRange(fromMs, toMs)
    }

    private fun renderChart() {
        val data = viewModel.chartData.value ?: return
        val slices = listOf(
            Triple(data.incoming, "Incoming", R.color.outgoing_blue),
            Triple(data.outgoing, "Outgoing", R.color.status_qualified),
            Triple(data.missed, "Missed", R.color.missed_red),
            Triple(data.rejected, "Rejected", R.color.syncpedia_orange)
        ).filter { it.first > 0f }

        val entries = if (slices.isEmpty()) {
            listOf(PieEntry(1f, "No data"))
        } else {
            slices.map { PieEntry(it.first, it.second) }
        }
        val colors = if (slices.isEmpty()) {
            listOf(Color.DKGRAY)
        } else {
            slices.map { ContextCompat.getColor(requireContext(), it.third) }
        }

        val set = PieDataSet(entries, "").apply {
            this.colors = colors
            valueTextColor = Color.WHITE
            valueTextSize = 11f
            sliceSpace = 2f
        }
        binding.pieChart.data = PieData(set).apply {
            setValueFormatter(PercentFormatter(binding.pieChart))
        }
        binding.pieChart.invalidate()
    }

    private fun renderCards(cards: List<AnalysisKpiCard>) {
        binding.statsGrid.removeAllViews()
        cards.forEach { cardData ->
            val card = ItemStatCardBinding.inflate(layoutInflater, binding.statsGrid, false)
            card.tvStatTitle.text = cardData.title
            val showPrimary = cardData.primaryValue.isNotBlank()
            card.tvStatPrimary.visibility = if (showPrimary) View.VISIBLE else View.GONE
            if (showPrimary) {
                val isAnalysisPreview = !cardData.metric.isSummary &&
                    cardData.metric != AnalysisMetric.TOP_CALLER &&
                    cardData.metric != AnalysisMetric.LONGEST_DURATION
                card.tvStatPrimary.text = when {
                    cardData.metric.isSummary ->
                        "${cardData.primaryValue} calls"
                    else -> cardData.primaryValue
                }
                if (isAnalysisPreview) card.tvStatPrimary.visibility = View.GONE
            }
            if (cardData.secondaryValue != null) {
                card.tvStatSecondary.visibility = View.VISIBLE
                card.tvStatSecondary.text = cardData.secondaryValue
            } else {
                card.tvStatSecondary.visibility = View.GONE
            }
            card.ivKpiIcon.setImageResource(iconFor(cardData.metric))
            val bg = card.iconBg.background.mutate()
            if (bg is GradientDrawable) {
                bg.setColor(ContextCompat.getColor(requireContext(), cardData.accentColorRes))
            }
            card.root.setOnClickListener { openDetail(cardData.metric) }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(6, 6, 6, 6)
            }
            binding.statsGrid.addView(card.root, params)
        }
    }

    private fun iconFor(metric: AnalysisMetric): Int = when (metric) {
        AnalysisMetric.ALL_CALLS, AnalysisMetric.TOP_10_CALLERS -> R.drawable.ic_call
        AnalysisMetric.INCOMING, AnalysisMetric.INCOMING_TALKED,
        AnalysisMetric.TOP_10_INCOMING, AnalysisMetric.TOP_10_INCOMING_DURATION -> R.drawable.ic_incoming
        AnalysisMetric.OUTGOING, AnalysisMetric.OUTGOING_TALKED, AnalysisMetric.NOT_PICKED_OUTGOING,
        AnalysisMetric.TOP_10_OUTGOING, AnalysisMetric.TOP_10_OUTGOING_DURATION -> R.drawable.ic_outgoing
        AnalysisMetric.MISSED, AnalysisMetric.REJECTED -> R.drawable.ic_missed
        AnalysisMetric.UNKNOWN, AnalysisMetric.TOP_CALLER -> R.drawable.ic_person
        AnalysisMetric.ALL_TALKED -> R.drawable.ic_people
        AnalysisMetric.LONGEST_DURATION, AnalysisMetric.TOP_10_DURATION,
        AnalysisMetric.AVERAGE_DURATION -> R.drawable.ic_clock
        AnalysisMetric.GAP_TIME -> R.drawable.ic_alarm
    }

    private fun openDetail(metric: AnalysisMetric) {
        startActivity(
            AnalysisDetailActivity.intent(requireContext(), metric, fromMs, toMs)
        )
    }

    private fun showFilterMenu() {
        val popup = PopupMenu(requireContext(), binding.dateHeader)
        popup.menu.add(0, Timeline.ALL.ordinal, 0, R.string.filter_all)
        popup.menu.add(0, Timeline.TODAY.ordinal, 1, R.string.timeline_today)
        popup.menu.add(0, Timeline.YESTERDAY.ordinal, 2, R.string.timeline_yesterday)
        popup.menu.add(0, Timeline.CURRENT_WEEK.ordinal, 3, R.string.timeline_current_week)
        popup.menu.add(0, Timeline.PREV_WEEK.ordinal, 4, R.string.timeline_prev_week)
        popup.menu.add(0, Timeline.CURRENT_MONTH.ordinal, 5, R.string.timeline_current_month)
        popup.menu.add(0, Timeline.PREV_MONTH.ordinal, 6, R.string.timeline_prev_month)
        popup.menu.add(0, Timeline.CURRENT_YEAR.ordinal, 7, R.string.timeline_current_year)
        popup.menu.add(0, Timeline.CUSTOM.ordinal, 8, R.string.timeline_custom)
        popup.setOnMenuItemClickListener { item ->
            val timeline = Timeline.entries.getOrNull(item.itemId) ?: Timeline.ALL
            if (timeline == Timeline.CUSTOM) {
                pickDateRange()
            } else {
                applyTimeline(timeline)
            }
            true
        }
        popup.show()
    }

    private fun applyTimeline(timeline: Timeline) {
        val now = Calendar.getInstance()
        when (timeline) {
            Timeline.ALL -> {
                fromMs = 0L
                toMs = Long.MAX_VALUE
                timelineLabel = getString(R.string.filter_all)
            }
            Timeline.TODAY -> {
                fromMs = startOfDay(now.timeInMillis)
                toMs = endOfDay(now.timeInMillis)
                timelineLabel = getString(R.string.timeline_today)
            }
            Timeline.YESTERDAY -> {
                now.add(Calendar.DAY_OF_YEAR, -1)
                fromMs = startOfDay(now.timeInMillis)
                toMs = endOfDay(now.timeInMillis)
                timelineLabel = getString(R.string.timeline_yesterday)
            }
            Timeline.CURRENT_WEEK -> {
                val monday = mondayOfWeek(now)
                fromMs = startOfDay(monday.timeInMillis)
                monday.add(Calendar.DAY_OF_YEAR, 6)
                toMs = endOfDay(monday.timeInMillis)
                timelineLabel = getString(R.string.timeline_current_week)
            }
            Timeline.PREV_WEEK -> {
                val monday = mondayOfWeek(now)
                monday.add(Calendar.DAY_OF_YEAR, -7)
                fromMs = startOfDay(monday.timeInMillis)
                monday.add(Calendar.DAY_OF_YEAR, 6)
                toMs = endOfDay(monday.timeInMillis)
                timelineLabel = getString(R.string.timeline_prev_week)
            }
            Timeline.CURRENT_MONTH -> {
                now.set(Calendar.DAY_OF_MONTH, 1)
                fromMs = startOfDay(now.timeInMillis)
                now.set(Calendar.DAY_OF_MONTH, now.getActualMaximum(Calendar.DAY_OF_MONTH))
                toMs = endOfDay(now.timeInMillis)
                timelineLabel = getString(R.string.timeline_current_month)
            }
            Timeline.PREV_MONTH -> {
                now.set(Calendar.DAY_OF_MONTH, 1)
                now.add(Calendar.MONTH, -1)
                fromMs = startOfDay(now.timeInMillis)
                now.set(Calendar.DAY_OF_MONTH, now.getActualMaximum(Calendar.DAY_OF_MONTH))
                toMs = endOfDay(now.timeInMillis)
                timelineLabel = getString(R.string.timeline_prev_month)
            }
            Timeline.CURRENT_YEAR -> {
                now.set(Calendar.DAY_OF_YEAR, 1)
                fromMs = startOfDay(now.timeInMillis)
                now.set(Calendar.DAY_OF_YEAR, now.getActualMaximum(Calendar.DAY_OF_YEAR))
                toMs = endOfDay(now.timeInMillis)
                timelineLabel = getString(R.string.timeline_current_year)
            }
            Timeline.CUSTOM -> Unit
        }
        updateDateLabel()
        recomputeLocal()
    }

    private fun pickDateRange() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                cal.set(y, m, d)
                fromMs = startOfDay(cal.timeInMillis)
                DatePickerDialog(
                    requireContext(),
                    { _, y2, m2, d2 ->
                        cal.set(y2, m2, d2)
                        toMs = endOfDay(cal.timeInMillis)
                        timelineLabel = "${dateFmt.format(Date(fromMs))} - ${dateFmt.format(Date(toMs))}"
                        updateDateLabel()
                        recomputeLocal()
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateDateLabel() {
        binding.tvDateRange.text = timelineLabel.ifBlank { getString(R.string.filter_all) }
    }

    private fun mondayOfWeek(base: Calendar): Calendar {
        val c = base.clone() as Calendar
        val day = c.get(Calendar.DAY_OF_WEEK) // Sun=1 … Sat=7
        val offset = if (day == Calendar.SUNDAY) -6 else Calendar.MONDAY - day
        c.add(Calendar.DAY_OF_YEAR, offset)
        return c
    }

    private fun startOfDay(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun endOfDay(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 23)
        c.set(Calendar.MINUTE, 59)
        c.set(Calendar.SECOND, 59)
        c.set(Calendar.MILLISECOND, 999)
        return c.timeInMillis
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
