package com.syncpedia.crm.models

data class AnalysisKpiCard(
    val metric: AnalysisMetric,
    val title: String,
    val primaryValue: String,
    val secondaryValue: String? = null,
    val accentColorRes: Int
)

data class ContactCallStats(
    val phoneNumber: String,
    val contactName: String,
    val totalCalls: Int,
    val totalDuration: Long,
    val incoming: Int,
    val incomingDuration: Long,
    val outgoing: Int,
    val outgoingDuration: Long,
    val missed: Int,
    val rejected: Int,
    val notPickedOutgoing: Int
)

data class RankedContact(
    val rank: Int,
    val phoneNumber: String,
    val contactName: String,
    val count: Int,
    val durationSec: Long
)

data class AverageDurationStats(
    val totalDays: Int,
    val totalDurationSec: Long,
    val avgPerDaySec: Long,
    val incomingCalls: Int,
    val incomingDuration: Long,
    val outgoingCalls: Int,
    val outgoingDuration: Long
)

data class GapSegment(
    val type: GapSegmentType,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long
)

enum class GapSegmentType {
    SHORT_GAP,   // < 2m
    MEDIUM_GAP,  // 2m–10m
    LONG_GAP,    // > 10m
    TALKED
}

data class GapDaySummary(
    val dayStartMs: Long,
    val dateLabel: String,
    val dayOfWeekLabel: String,
    val totalCalls: Int,
    val talkTimeSec: Long,
    val totalGapSec: Long,
    val rangeStartMs: Long,
    val rangeEndMs: Long,
    val segments: List<GapSegment>,
    val gaps: List<GapSegment>
)
