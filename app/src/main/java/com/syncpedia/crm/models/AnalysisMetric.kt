package com.syncpedia.crm.models

/**
 * KPI cards on the Analysis page. Each opens a detail screen with live data.
 */
enum class AnalysisMetric {
    // Summary tab
    ALL_CALLS,
    INCOMING,
    OUTGOING,
    UNKNOWN,
    MISSED,
    REJECTED,
    NOT_PICKED_OUTGOING,
    ALL_TALKED,
    INCOMING_TALKED,
    OUTGOING_TALKED,

    // Analysis tab
    TOP_CALLER,
    LONGEST_DURATION,
    TOP_10_CALLERS,
    TOP_10_INCOMING,
    TOP_10_OUTGOING,
    TOP_10_DURATION,
    TOP_10_INCOMING_DURATION,
    TOP_10_OUTGOING_DURATION,
    AVERAGE_DURATION,
    GAP_TIME;

    val title: String
        get() = when (this) {
            ALL_CALLS -> "All Calls"
            INCOMING -> "Incoming Calls"
            OUTGOING -> "Outgoing Calls"
            UNKNOWN -> "Unknown Calls"
            MISSED -> "Missed Calls"
            REJECTED -> "Rejected Calls"
            NOT_PICKED_OUTGOING -> "Not Picked Outgoing Calls"
            ALL_TALKED -> "All Talked Calls"
            INCOMING_TALKED -> "Incoming Talked Calls"
            OUTGOING_TALKED -> "Outgoing Talked Calls"
            TOP_CALLER -> "Top Caller"
            LONGEST_DURATION -> "Longest Duration"
            TOP_10_CALLERS -> "Top 10 Callers"
            TOP_10_INCOMING -> "Top 10 Incoming Calls"
            TOP_10_OUTGOING -> "Top 10 Outgoing Calls"
            TOP_10_DURATION -> "Top 10 Call Duration"
            TOP_10_INCOMING_DURATION -> "Top 10 Incoming Call Duration"
            TOP_10_OUTGOING_DURATION -> "Top 10 Outgoing Call Duration"
            AVERAGE_DURATION -> "Average Call Duration"
            GAP_TIME -> "Gap Time"
        }

    val isSummary: Boolean
        get() = this <= OUTGOING_TALKED
}
