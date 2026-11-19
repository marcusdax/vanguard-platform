package com.odasi.fieldwright.feature.vault

import com.odasi.fieldwright.data.SyncState
import com.odasi.fieldwright.model.SyncPhase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Time and sync copy for the Vault. Kept local because `core:design` deliberately ships no
 * date formatter: one vocabulary per feature is cheaper than a shared utility with a locale
 * decision hidden inside it.
 *
 * Style (field contract): "Oct 3, 9:44 a.m.", "Today, 2:32 p.m.". Absolute times win over
 * relative ones for evidence; a short relative form is used only for the offer countdown.
 * Callers that render these strings use the tabular numeric style so columns line up.
 */
internal object VaultTime {

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

    /** "5 records · Oct 3, 9:44 a.m. · 9f2c1a04" — the fingerprint footer. */
    fun fingerprint(recordCount: Int, lastRecordAtEpochMs: Long?, shortHash: String, nowEpochMs: Long): String {
        val records = if (recordCount == 1) "1 record" else "$recordCount records"
        val last = lastRecordAtEpochMs?.let { clockLabel(it, nowEpochMs) } ?: "no records"
        val hash = shortHash.ifBlank { "no hash" }
        return "$records · $last · $hash"
    }

    /** Top-bar sync phrase; null means "nothing worth saying". */
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
