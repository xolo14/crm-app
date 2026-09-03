package com.syncpedia.crm.viewmodels

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncpedia.crm.R
import com.syncpedia.crm.models.AnalysisKpiCard
import com.syncpedia.crm.models.AnalysisMetric
import com.syncpedia.crm.models.AnalysisSummary
import com.syncpedia.crm.models.CallLogItem
import com.syncpedia.crm.utils.AnalysisCalculator
import com.syncpedia.crm.utils.AppCallStore
import com.syncpedia.crm.utils.CallLogReader
import kotlinx.coroutines.launch

class AnalysisViewModel : ViewModel() {

    data class ChartData(
        val incoming: Float,
        val outgoing: Float,
        val missed: Float,
        val rejected: Float
    )

    private val _summary = MutableLiveData<AnalysisSummary>()
    val summary: LiveData<AnalysisSummary> = _summary

    private val _chartData = MutableLiveData<ChartData>()
    val chartData: LiveData<ChartData> = _chartData

    private val _summaryCards = MutableLiveData<List<AnalysisKpiCard>>()
    val summaryCards: LiveData<List<AnalysisKpiCard>> = _summaryCards

    private val _analysisCards = MutableLiveData<List<AnalysisKpiCard>>()
    val analysisCards: LiveData<List<AnalysisKpiCard>> = _analysisCards

    private val _filteredCalls = MutableLiveData<List<CallLogItem>>()
    val filteredCalls: LiveData<List<CallLogItem>> = _filteredCalls

    private val _allCalls = MutableLiveData<List<CallLogItem>>(emptyList())
    val allCalls: LiveData<List<CallLogItem>> = _allCalls

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private var fromMs: Long = 0L
    private var toMs: Long = Long.MAX_VALUE

    /** Load local + server CRM calls, then recompute KPIs for the active timeline. */
    fun loadMerged(ctx: Context, from: Long = fromMs, to: Long = toMs) {
        fromMs = from
        toMs = to
        viewModelScope.launch {
            _loading.value = true
            try {
                val merged = AppCallStore.fetchMerged(ctx.applicationContext, appUploadsOnly = false)
                _allCalls.value = merged
                computeStats(merged, fromMs, toMs)
            } finally {
                _loading.value = false
            }
        }
    }

    fun recomputeRange(from: Long, to: Long) {
        fromMs = from
        toMs = to
        computeStats(_allCalls.value.orEmpty(), from, to)
    }

    fun computeStats(calls: List<CallLogItem>, from: Long, to: Long): AnalysisSummary {
        val filtered = AnalysisCalculator.filterRange(calls, from, to)
        _filteredCalls.value = filtered

        val summary = AnalysisCalculator.computeSummary(filtered)
        _summary.value = summary

        _chartData.value = ChartData(
            incoming = summary.incoming.toFloat(),
            outgoing = summary.outgoing.toFloat(),
            missed = summary.missed.toFloat(),
            rejected = summary.rejected.toFloat()
        )

        _summaryCards.value = buildSummaryCards(summary)
        _analysisCards.value = buildAnalysisCards(filtered)
        return summary
    }

    private fun buildSummaryCards(s: AnalysisSummary): List<AnalysisKpiCard> = listOf(
        card(AnalysisMetric.ALL_CALLS, s.totalCalls, s.totalDuration, R.color.outgoing_blue),
        card(AnalysisMetric.INCOMING, s.incoming, s.incomingDuration, R.color.outgoing_blue),
        card(AnalysisMetric.OUTGOING, s.outgoing, s.outgoingDuration, R.color.incoming_green),
        card(AnalysisMetric.UNKNOWN, s.unknownNumbers, s.unknownDuration, R.color.syncpedia_orange),
        card(AnalysisMetric.MISSED, s.missed, null, R.color.missed_red),
        card(AnalysisMetric.REJECTED, s.rejected, null, R.color.rejected_red),
        card(AnalysisMetric.NOT_PICKED_OUTGOING, s.unansweredOutgoing, null, R.color.syncpedia_orange),
        card(AnalysisMetric.ALL_TALKED, s.allTalked, s.allTalkedDuration, R.color.incoming_green),
        card(AnalysisMetric.INCOMING_TALKED, s.incomingTalked, s.incomingTalkedDuration, R.color.incoming_green),
        card(AnalysisMetric.OUTGOING_TALKED, s.outgoingTalked, s.outgoingTalkedDuration, R.color.incoming_green)
    )

    private fun buildAnalysisCards(calls: List<CallLogItem>): List<AnalysisKpiCard> {
        val top = AnalysisCalculator.topCaller(calls)
        val longest = calls.filter { it.duration > 0 }.maxByOrNull { it.duration }
        return listOf(
            AnalysisKpiCard(
                AnalysisMetric.TOP_CALLER,
                AnalysisMetric.TOP_CALLER.title,
                top?.contactName ?: "—",
                null,
                R.color.outgoing_blue
            ),
            AnalysisKpiCard(
                AnalysisMetric.LONGEST_DURATION,
                AnalysisMetric.LONGEST_DURATION.title,
                longest?.let { CallLogReader.formatDuration(it.duration) } ?: "—",
                null,
                R.color.syncpedia_orange
            ),
            AnalysisKpiCard(
                AnalysisMetric.TOP_10_CALLERS,
                AnalysisMetric.TOP_10_CALLERS.title,
                "",
                null,
                R.color.outgoing_blue
            ),
            AnalysisKpiCard(
                AnalysisMetric.TOP_10_INCOMING,
                AnalysisMetric.TOP_10_INCOMING.title,
                "",
                null,
                R.color.outgoing_blue
            ),
            AnalysisKpiCard(
                AnalysisMetric.TOP_10_OUTGOING,
                AnalysisMetric.TOP_10_OUTGOING.title,
                "",
                null,
                R.color.incoming_green
            ),
            AnalysisKpiCard(
                AnalysisMetric.TOP_10_DURATION,
                AnalysisMetric.TOP_10_DURATION.title,
                "",
                null,
                R.color.syncpedia_orange
            ),
            AnalysisKpiCard(
                AnalysisMetric.TOP_10_INCOMING_DURATION,
                AnalysisMetric.TOP_10_INCOMING_DURATION.title,
                "",
                null,
                R.color.outgoing_blue
            ),
            AnalysisKpiCard(
                AnalysisMetric.TOP_10_OUTGOING_DURATION,
                AnalysisMetric.TOP_10_OUTGOING_DURATION.title,
                "",
                null,
                R.color.incoming_green
            ),
            AnalysisKpiCard(
                AnalysisMetric.AVERAGE_DURATION,
                AnalysisMetric.AVERAGE_DURATION.title,
                "",
                null,
                R.color.missed_red
            ),
            AnalysisKpiCard(
                AnalysisMetric.GAP_TIME,
                AnalysisMetric.GAP_TIME.title,
                "",
                null,
                R.color.syncpedia_orange
            )
        )
    }

    private fun card(
        metric: AnalysisMetric,
        count: Int,
        durationSec: Long?,
        color: Int
    ): AnalysisKpiCard {
        val primary = count.toString()
        val secondary = durationSec?.let { CallLogReader.formatDuration(it) }
        return AnalysisKpiCard(metric, metric.title, primary, secondary, color)
    }
}
