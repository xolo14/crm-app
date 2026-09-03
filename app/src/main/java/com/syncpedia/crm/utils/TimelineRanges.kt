package com.syncpedia.crm.utils

import com.syncpedia.crm.models.Lead
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Shared Analysis / Leads timeline date windows (device local time). */
object TimelineRanges {

    enum class Kind {
        ALL, TODAY, YESTERDAY, CURRENT_WEEK, PREV_WEEK,
        CURRENT_MONTH, PREV_MONTH, CURRENT_YEAR, CUSTOM
    }

    data class Window(val fromMs: Long, val toMs: Long)

    fun window(kind: Kind, nowMs: Long = System.currentTimeMillis()): Window {
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        return when (kind) {
            Kind.ALL -> Window(0L, Long.MAX_VALUE)
            Kind.TODAY -> Window(startOfDay(now.timeInMillis), endOfDay(now.timeInMillis))
            Kind.YESTERDAY -> {
                now.add(Calendar.DAY_OF_YEAR, -1)
                Window(startOfDay(now.timeInMillis), endOfDay(now.timeInMillis))
            }
            Kind.CURRENT_WEEK -> {
                val monday = mondayOfWeek(now)
                val from = startOfDay(monday.timeInMillis)
                monday.add(Calendar.DAY_OF_YEAR, 6)
                Window(from, endOfDay(monday.timeInMillis))
            }
            Kind.PREV_WEEK -> {
                val monday = mondayOfWeek(now)
                monday.add(Calendar.DAY_OF_YEAR, -7)
                val from = startOfDay(monday.timeInMillis)
                monday.add(Calendar.DAY_OF_YEAR, 6)
                Window(from, endOfDay(monday.timeInMillis))
            }
            Kind.CURRENT_MONTH -> {
                now.set(Calendar.DAY_OF_MONTH, 1)
                val from = startOfDay(now.timeInMillis)
                now.set(Calendar.DAY_OF_MONTH, now.getActualMaximum(Calendar.DAY_OF_MONTH))
                Window(from, endOfDay(now.timeInMillis))
            }
            Kind.PREV_MONTH -> {
                now.set(Calendar.DAY_OF_MONTH, 1)
                now.add(Calendar.MONTH, -1)
                val from = startOfDay(now.timeInMillis)
                now.set(Calendar.DAY_OF_MONTH, now.getActualMaximum(Calendar.DAY_OF_MONTH))
                Window(from, endOfDay(now.timeInMillis))
            }
            Kind.CURRENT_YEAR -> {
                now.set(Calendar.DAY_OF_YEAR, 1)
                val from = startOfDay(now.timeInMillis)
                now.set(Calendar.DAY_OF_YEAR, now.getActualMaximum(Calendar.DAY_OF_YEAR))
                Window(from, endOfDay(now.timeInMillis))
            }
            Kind.CUSTOM -> Window(0L, Long.MAX_VALUE)
        }
    }

    fun filterLeads(leads: List<Lead>, fromMs: Long, toMs: Long): List<Lead> {
        if (fromMs <= 0L && toMs >= Long.MAX_VALUE / 2) return leads
        return leads.filter { lead ->
            val ms = leadCreatedMs(lead)
            ms >= 0L && ms in fromMs..toMs
        }
    }

    fun leadCreatedMs(lead: Lead): Long {
        val raw = lead.createdAt?.takeIf { it.isNotBlank() }
            ?: lead.updatedAt?.takeIf { it.isNotBlank() }
            ?: return -1L
        return parseDateTime(raw)
    }

    fun parseDateTime(raw: String): Long {
        val patterns = arrayOf(
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US)
                fmt.isLenient = true
                fmt.parse(raw.trim())?.time?.let { return it }
            } catch (_: Exception) {
            }
        }
        return -1L
    }

    fun mondayOfWeek(base: Calendar): Calendar {
        val c = base.clone() as Calendar
        val day = c.get(Calendar.DAY_OF_WEEK)
        val offset = if (day == Calendar.SUNDAY) -6 else Calendar.MONDAY - day
        c.add(Calendar.DAY_OF_YEAR, offset)
        return c
    }

    fun startOfDay(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun endOfDay(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 23)
        c.set(Calendar.MINUTE, 59)
        c.set(Calendar.SECOND, 59)
        c.set(Calendar.MILLISECOND, 999)
        return c.timeInMillis
    }
}
