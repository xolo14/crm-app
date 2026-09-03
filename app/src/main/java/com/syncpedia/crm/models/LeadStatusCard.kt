package com.syncpedia.crm.models

data class LeadStatusCard(
    val status: String,
    val label: String,
    val count: Int,
    val colorRes: Int
)
