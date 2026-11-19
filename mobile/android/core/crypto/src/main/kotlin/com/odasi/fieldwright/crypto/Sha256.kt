package com.odasi.fieldwright.crypto

import com.odasi.fieldwright.model.HashAlgorithm
import java.security.MessageDigest

/**
 * SHA-256, the single digest used by every Fieldwright evidence path.
 *
 * Implemented with the platform [MessageDigest] rather than a bundled library: the
 * algorithm is specified, the JDK/Android provider is audited, and a home-made digest
 * would be a supply-chain risk in a product whose whole value is that a hash means what
 * it says. This module is deliberately Android-free so the same primitive is used in
 * unit tests, in the app, and by the (future) server verifier.
 *
 * It is a stateless [object]: inject it as `HashAlgorithm = Sha256` rather than
 * constructing a new digest per call. The interface is [HashAlgorithm] from
 * `:core:model`, which keeps the domain model dependency-free.
 */
object Sha256 : HashAlgorithm {
    const val DIGEST_BYTES: Int = 32
    const val ALGORITHM: String = "SHA-256"

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    override fun sha256Hex(bytes: ByteArray): String = toLowerHex(digest(bytes))

    /** Raw 32-byte digest, for callers that need bytes rather than a display hash. */
    fun sha256Bytes(bytes: ByteArray): ByteArray = digest(bytes)

    /** Lowercase hex encoding. Stable across platforms and locales (no String.format). */
    fun toLowerHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            out[index * 2] = HEX_DIGITS[value ushr 4]
            out[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
        }
        return String(out)
    }

    private fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance(ALGORITHM).digest(bytes)
}
