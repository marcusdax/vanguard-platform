package com.odasi.fieldwright.feature.vault

import java.util.Locale

/**
 * Small, non-money formatters for evidence metadata. Money never appears here: it goes through
 * `FwMoneyText` / `Money.format` so the tabular-numeral and sign rules cannot fork.
 */
internal object VaultFormat {

    /** "216 KB" — media size is metadata, not a promise. */
    fun bytes(bytes: Long): String = when {
        bytes < 1_000 -> "$bytes B"
        bytes < 1_000_000 -> "${(bytes / 1_000)} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    }

    fun meters(meters: Double): String = String.format(Locale.US, "%.2f m", meters)

    fun feet(feet: Double): String = String.format(Locale.US, "%.2f ft", feet)

    /** 8-character display hash; chain hashes are copied in full elsewhere. */
    fun shortHash(hash: String): String = hash.take(8)
}
