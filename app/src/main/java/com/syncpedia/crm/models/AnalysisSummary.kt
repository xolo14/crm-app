package com.syncpedia.crm.models

data class AnalysisSummary(
    val totalCalls: Int,
    val totalDuration: Long,
    val incoming: Int,
    val outgoing: Int,
    val missed: Int,
    val rejected: Int,
    val incomingDuration: Long,
    val outgoingDuration: Long,
    val unansweredOutgoing: Int = 0,
    val unreturnedMissed: Int = 0,
    val unknownNumbers: Int = 0,
    val allTalked: Int = 0,
    val allTalkedDuration: Long = 0,
    val incomingTalked: Int = 0,
    val incomingTalkedDuration: Long = 0,
    val outgoingTalked: Int = 0,
    val outgoingTalkedDuration: Long = 0,
    val unknownDuration: Long = 0
)
