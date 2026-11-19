package com.odasi.fieldwright.feature.payouts

import com.odasi.fieldwright.data.SyncState
import com.odasi.fieldwright.model.SyncPhase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Time copy for money surfaces. Absolute, unambiguous timestamps: a worker reading a ledger
 * at a gate needs to know *when* a fee was charged, not approximately how long ago.
 * Style (field contract): "Oct 3, 9:44 a.m.", "Today, 2:32 p.m.".
 */
internal object PayoutsTime {

    fun clockLabel(epochMs: Long, nowEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(epochMs).atZone(zone)
        val now = Instant.ofEpochMilli(nowEpochMs).atZone(zone)
        val day = at.toLocalDate()
        val today = now.toLocalDate()
        val time = timeLabel(at.hour, at.minute)
        return when {
            day == today -> "Today, $time"
            day == today.minusDays(1) -> "Yesterday, $time"
            day.year == today.year -> "${monthDay(day)}, $time"
            else -> "${monthDay(day)}, ${day.year}, $time"
        }
    }

    fun dateLabel(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        return "${monthDay(day)}, ${day.year}"
    }

    fun dayLabel(epochMs: Long, nowEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
        return when {
            day == today -> "Today"
            day == today.minusDays(1) -> "Yesterday"
            day.year == today.year -> monthDay(day)
            else -> "${monthDay(day)}, ${day.year}"
        }
    }

    /** "3 h 20 m" — online time is a duration, never a money figure. */
    fun durationLabel(minutes: Int): String {
        if (minutes <= 0) return "0 m"
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> "$rest m"
            rest == 0 -> "$hours h"
            else -> "$hours h $rest m"
        }
    }

    fun syncLabel(sync: SyncState?): String? {
        if (sync == null) return null
        return when (sync.phase) {
            SyncPhase.IDLE -> if (sync.queued == 0) null else "${sync.queued} queued"
            SyncPhase.SYNCING -> "Syncing…"
            SyncPhase.WAITING_FOR_NETWORK -> "Offline · ${sync.queued} queued"
            SyncPhase.RETRYING -> "Retrying · ${sync.queued} queued"
            SyncPhase.DEAD_LETTER -> "${sync.deadLetters} stuck"
        }
    }

    private fun monthDay(day: LocalDate): String =
        "${day.month.getDisplayName(TextStyle.SHORT, Locale.US)} ${day.dayOfMonth}"

    private fun timeLabel(hour: Int, minute: Int): String {
        val suffix = if (hour < 12) "a.m." else "p.m."
        val hour12 = when (val h = hour % 12) {
            0 -> 12
            else -> h
        }
        return "$hour12:${minute.toString().padStart(2, '0')} $suffix"
    }
}
