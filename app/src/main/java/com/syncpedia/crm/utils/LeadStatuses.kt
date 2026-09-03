package com.syncpedia.crm.utils

/**
 * Lead statuses aligned with crm-main website (Leads.tsx).
 */
object LeadStatuses {
    val ALL = listOf(
        "new",
        "contacted",
        "not_answered",
        "messaged",
        "qualified",
        "interested",
        "demo_scheduled",
        "demo_attended",
        "enrolled",
        "lost"
    )

    /** Cards shown on management home (website chip order). */
    val CARD_ORDER = listOf(
        "new",
        "contacted",
        "not_answered",
        "messaged",
        "interested",
        "demo_scheduled",
        "demo_attended",
        "enrolled",
        "lost",
        "qualified"
    )

    fun normalize(raw: String?): String {
        val s = raw?.trim()?.lowercase().orEmpty()
        return when (s) {
            "", "new" -> "new"
            "converted" -> "enrolled"
            "considering" -> "interested"
            "not_interested" -> "lost"
            else -> if (s in ALL) s else "new"
        }
    }

    fun label(raw: String?): String = when (normalize(raw)) {
        "new" -> "New"
        "contacted" -> "Contacted"
        "not_answered" -> "Not answered"
        "messaged" -> "Messaged"
        "qualified" -> "Qualified"
        "interested" -> "Interested"
        "demo_scheduled" -> "Demo scheduled"
        "demo_attended" -> "Demo attended"
        "enrolled" -> "Enrolled"
        "lost" -> "Lost"
        else -> normalize(raw).replace('_', ' ')
    }

    fun colorRes(raw: String?): Int = when (normalize(raw)) {
        "new" -> com.syncpedia.crm.R.color.status_new
        "contacted" -> com.syncpedia.crm.R.color.status_contacted
        "not_answered" -> com.syncpedia.crm.R.color.status_not_answered
        "messaged" -> com.syncpedia.crm.R.color.status_messaged
        "qualified" -> com.syncpedia.crm.R.color.status_qualified
        "interested" -> com.syncpedia.crm.R.color.status_interested
        "demo_scheduled" -> com.syncpedia.crm.R.color.status_demo_scheduled
        "demo_attended" -> com.syncpedia.crm.R.color.status_demo_attended
        "enrolled" -> com.syncpedia.crm.R.color.status_converted
        "lost" -> com.syncpedia.crm.R.color.status_lost
        else -> com.syncpedia.crm.R.color.tab_unselected
    }
}
