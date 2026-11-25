package com.syncpedia.crm.utils

import android.provider.CallLog
import com.syncpedia.crm.models.AnalysisMetric
import com.syncpedia.crm.models.AnalysisSummary
import com.syncpedia.crm.models.AverageDurationStats
import com.syncpedia.crm.models.CallLogItem
import com.syncpedia.crm.models.ContactCallStats
import com.syncpedia.crm.models.GapDaySummary
import com.syncpedia.crm.models.GapSegment
import com.syncpedia.crm.models.GapSegmentType
import com.syncpedia.crm.models.RankedContact
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object AnalysisCalculator {

    private val dateLabelFmt = SimpleDateFormat("d MMM yyyy", Locale.US)
    private val dowFmt = SimpleDateFormat("EEEE", Locale.US)

    fun filterRange(calls: List<CallLogItem>, from: Long, to: Long): List<CallLogItem> =
        calls.filter { it.date in from..to }.sortedByDescending { it.date }

    fun computeSummary(calls: List<CallLogItem>): AnalysisSummary {
        val incoming = calls.filter { it.callType == CallLog.Calls.INCOMING_TYPE }
        val outgoing = calls.filter { it.callType == CallLog.Calls.OUTGOING_TYPE }
        val missed = calls.filter { it.callType == CallLog.Calls.MISSED_TYPE }
        val rejected = calls.filter { it.callType == CallLog.Calls.REJECTED_TYPE }
        val talked = calls.filter { isTalked(it) }
        val incomingTalked = incoming.filter { it.duration > 0 }
        val outgoingTalked = outgoing.filter { it.duration > 0 }
        val notPicked = outgoing.count { it.duration == 0L }
        val unknown = calls.count { isUnknown(it) }

        return AnalysisSummary(
            totalCalls = calls.size,
            totalDuration = calls.sumOf { it.duration },
            incoming = incoming.size,
            outgoing = outgoing.size,
            missed = missed.size,
            rejected = rejected.size,
            incomingDuration = incoming.sumOf { it.duration },
            outgoingDuration = outgoing.sumOf { it.duration },
            unansweredOutgoing = notPicked,
            unreturnedMissed = 0,
            unknownNumbers = unknown,
            allTalked = talked.size,
            allTalkedDuration = talked.sumOf { it.duration },
            incomingTalked = incomingTalked.size,
            incomingTalkedDuration = incomingTalked.sumOf { it.duration },
            outgoingTalked = outgoingTalked.size,
            outgoingTalkedDuration = outgoingTalked.sumOf { it.duration },
            unknownDuration = calls.filter { isUnknown(it) }.sumOf { it.duration }
        )
    }

    fun filterByMetric(calls: List<CallLogItem>, metric: AnalysisMetric): List<CallLogItem> =
        when (metric) {
            AnalysisMetric.ALL_CALLS -> calls
            AnalysisMetric.INCOMING -> calls.filter { it.callType == CallLog.Calls.INCOMING_TYPE }
            AnalysisMetric.OUTGOING -> calls.filter { it.callType == CallLog.Calls.OUTGOING_TYPE }
            AnalysisMetric.UNKNOWN -> calls.filter { isUnknown(it) }
            AnalysisMetric.MISSED -> calls.filter { it.callType == CallLog.Calls.MISSED_TYPE }
            AnalysisMetric.REJECTED -> calls.filter { it.callType == CallLog.Calls.REJECTED_TYPE }
            AnalysisMetric.NOT_PICKED_OUTGOING ->
                calls.filter { it.callType == CallLog.Calls.OUTGOING_TYPE && it.duration == 0L }
            AnalysisMetric.ALL_TALKED -> calls.filter { isTalked(it) }
            AnalysisMetric.INCOMING_TALKED ->
                calls.filter { it.callType == CallLog.Calls.INCOMING_TYPE && it.duration > 0 }
            AnalysisMetric.OUTGOING_TALKED ->
                calls.filter { it.callType == CallLog.Calls.OUTGOING_TYPE && it.duration > 0 }
            AnalysisMetric.LONGEST_DURATION -> {
                val longest = calls.filter { it.duration > 0 }.maxByOrNull { it.duration }
                if (longest == null) emptyList() else listOf(longest)
            }
            else -> emptyList()
        }

    fun topCaller(calls: List<CallLogItem>): ContactCallStats? =
        contactStats(calls).maxByOrNull { it.totalDuration.takeIf { d -> d > 0 } ?: it.totalCalls.toLong() }

    fun contactStatsForPhone(calls: List<CallLogItem>, phone: String): ContactCallStats? {
        val key = AppCallTracker.normalize(phone)
        return contactStats(calls).find {
            AppCallTracker.phonesMatch(AppCallTracker.normalize(it.phoneNumber), key)
        }
    }

    fun contactStats(calls: List<CallLogItem>): List<ContactCallStats> {
        val grouped = linkedMapOf<String, MutableList<CallLogItem>>()
        for (c in calls) {
            val key = AppCallTracker.normalize(c.phoneNumber).ifBlank { c.phoneNumber.ifBlank { "unknown" } }
            grouped.getOrPut(key) { mutableListOf() }.add(c)
        }
        return grouped.map { (_, list) ->
            val incoming = list.filter { it.callType == CallLog.Calls.INCOMING_TYPE }
            val outgoing = list.filter { it.callType == CallLog.Calls.OUTGOING_TYPE }
            ContactCallStats(
                phoneNumber = list.first().phoneNumber,
                contactName = list.firstOrNull { it.contactName.isNotBlank() && !it.contactName.equals("Unknown", true) }
                    ?.contactName
                    ?: list.first().contactName.ifBlank { "Unknown" },
                totalCalls = list.size,
                totalDuration = list.sumOf { it.duration },
                incoming = incoming.size,
                incomingDuration = incoming.sumOf { it.duration },
                outgoing = outgoing.size,
                outgoingDuration = outgoing.sumOf { it.duration },
                missed = list.count { it.callType == CallLog.Calls.MISSED_TYPE },
                rejected = list.count { it.callType == CallLog.Calls.REJECTED_TYPE },
                notPickedOutgoing = outgoing.count { it.duration == 0L }
            )
        }
    }

    fun topByCount(
        calls: List<CallLogItem>,
        typeFilter: Int? = null,
        limit: Int = 10
    ): List<RankedContact> {
        val filtered = if (typeFilter == null) calls else calls.filter { it.callType == typeFilter }
        return contactStats(filtered)
            .sortedWith(compareByDescending<ContactCallStats> { it.totalCalls }.thenByDescending { it.totalDuration })
            .take(limit)
            .mapIndexed { i, s ->
                RankedContact(i + 1, s.phoneNumber, s.contactName, s.totalCalls, s.totalDuration)
            }
    }

    fun topByDuration(
        calls: List<CallLogItem>,
        typeFilter: Int? = null,
        limit: Int = 10
    ): List<RankedContact> {
        val filtered = if (typeFilter == null) {
            calls.filter { it.duration > 0 }
        } else {
            calls.filter { it.callType == typeFilter && it.duration > 0 }
        }
        return contactStats(filtered)
            .sortedWith(compareByDescending<ContactCallStats> { it.totalDuration }.thenByDescending { it.totalCalls })
            .take(limit)
            .mapIndexed { i, s ->
                RankedContact(i + 1, s.phoneNumber, s.contactName, s.totalCalls, s.totalDuration)
            }
    }

    fun averageDuration(calls: List<CallLogItem>): AverageDurationStats {
        val talkedLike = calls.filter {
            it.callType == CallLog.Calls.INCOMING_TYPE || it.callType == CallLog.Calls.OUTGOING_TYPE
        }
        val incoming = talkedLike.filter { it.callType == CallLog.Calls.INCOMING_TYPE }
        val outgoing = talkedLike.filter { it.callType == CallLog.Calls.OUTGOING_TYPE }
        val dayKeys = talkedLike.map { dayKey(it.date) }.toSet()
        val totalDur = talkedLike.sumOf { it.duration }
        val days = dayKeys.size.coerceAtLeast(0)
        return AverageDurationStats(
            totalDays = days,
            totalDurationSec = totalDur,
            avgPerDaySec = if (days > 0) totalDur / days else 0L,
            incomingCalls = incoming.size,
            incomingDuration = incoming.sumOf { it.duration },
            outgoingCalls = outgoing.size,
            outgoingDuration = outgoing.sumOf { it.duration }
        )
    }

    fun gapDays(calls: List<CallLogItem>): List<GapDaySummary> {
        if (calls.isEmpty()) return emptyList()
        val byDay = calls.groupBy { dayKey(it.date) }
        return byDay.keys.sortedDescending().mapNotNull { key ->
            val dayCalls = byDay[key]!!.sortedBy { it.date }
            if (dayCalls.isEmpty()) return@mapNotNull null
            buildGapDay(dayCalls)
        }
    }

    private fun buildGapDay(dayCalls: List<CallLogItem>): GapDaySummary {
        val segments = mutableListOf<GapSegment>()
        val gaps = mutableListOf<GapSegment>()
        var talkSec = 0L
        var gapSec = 0L

        for (i in dayCalls.indices) {
            val call = dayCalls[i]
            val callStart = call.date
            val callEnd = call.date + call.duration * 1000L
            if (call.duration > 0) {
                talkSec += call.duration
                segments.add(
                    GapSegment(GapSegmentType.TALKED, callStart, callEnd, call.duration * 1000L)
                )
            }
            if (i < dayCalls.lastIndex) {
                val next = dayCalls[i + 1]
                val gapStart = callEnd
                val gapEnd = next.date
                val gapMs = (gapEnd - gapStart).coerceAtLeast(0L)
                if (gapMs > 0) {
                    val type = when {
                        gapMs < 2 * 60_000L -> GapSegmentType.SHORT_GAP
                        gapMs <= 10 * 60_000L -> GapSegmentType.MEDIUM_GAP
                        else -> GapSegmentType.LONG_GAP
                    }
                    val seg = GapSegment(type, gapStart, gapEnd, gapMs)
                    segments.add(seg)
                    gaps.add(seg)
                    gapSec += gapMs / 1000L
                }
            }
        }

        val first = dayCalls.first()
        val last = dayCalls.last()
        val rangeEnd = last.date + last.duration * 1000L

        return GapDaySummary(
            dayStartMs = startOfDay(first.date),
            dateLabel = dateLabelFmt.format(Date(first.date)),
            dayOfWeekLabel = dowFmt.format(Date(first.date)).uppercase(Locale.US),
            totalCalls = dayCalls.size,
            talkTimeSec = talkSec,
            totalGapSec = gapSec,
            rangeStartMs = first.date,
            rangeEndMs = rangeEnd,
            segments = segments,
            gaps = gaps.sortedByDescending { it.durationMs }
        )
    }

    private fun isTalked(c: CallLogItem): Boolean =
        (c.callType == CallLog.Calls.INCOMING_TYPE || c.callType == CallLog.Calls.OUTGOING_TYPE) &&
            c.duration > 0

    private fun isUnknown(c: CallLogItem): Boolean =
        c.contactName.isBlank() || c.contactName.equals("Unknown", true)

    /** Zero-padded day-of-year so string sort order matches chronological order
     *  (gapDays() sorts these as strings — "45" otherwise sorts after "328"). */
    private fun dayKey(ms: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR).toString().padStart(3, '0')}"
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
}
