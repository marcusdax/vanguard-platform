package com.odasi.fieldwright.feature.capture.crypto

import com.odasi.fieldwright.model.ConsentMethod
import com.odasi.fieldwright.model.HashAlgorithm
import java.security.MessageDigest

/**
 * SHA-256 over raw bytes for media and signature digests.
 *
 * The primitive is the platform [MessageDigest] — standard, available on every API 26
 * device, and pure JVM so a frame can be hashed (and unit-tested) before the data layer
 * ever sees it. Canonical JSON, chain walking, Merkle inclusion and packet signing stay
 * owned by `:core:crypto` and the seal repository; nothing here re-implements a rule.
 */
object Sha256 : HashAlgorithm {
    override fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()
}

internal fun ByteArray.toLowerHex(): String {
    val digits = "0123456789abcdef"
    val out = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xFF
        out[i * 2] = digits[value ushr 4]
        out[i * 2 + 1] = digits[value and 0x0F]
    }
    return String(out)
}

/**
 * Digest pre-images owned by the capture feature.
 *
 * These are *not* the evidence-record canonical form: chain records, packets and seals are
 * canonicalised and hashed by `:core:crypto`. A signature digest here only binds the typed
 * signature text to the consent scope, method and capture time so the stored hash cannot be
 * reused for a different scope. The unit separator (`\u001F`) cannot appear in normal text,
 * so the concatenation is unambiguous.
 */
object CaptureDigests {

    /** Pre-image version; bump if the consent record shape changes. */
    const val CONSENT_PREIMAGE_VERSION: String = "fieldwright.consent.v1"

    fun consentSignatureHash(
        hasher: HashAlgorithm,
        subjectName: String,
        subjectRole: String,
        scopeText: String,
        method: ConsentMethod,
        typedSignature: String,
        capturedAtEpochMs: Long,
    ): String {
        val preimage = buildString {
            append(CONSENT_PREIMAGE_VERSION)
            append('\u001F').append(subjectName.trim())
            append('\u001F').append(subjectRole.trim())
            append('\u001F').append(scopeText.trim())
            append('\u001F').append(method.name)
            append('\u001F').append(typedSignature.trim())
            append('\u001F').append(capturedAtEpochMs)
        }
        return hasher.sha256Hex(preimage)
    }

    /** Stable media id derived from content: the same frame can only ever get one id. */
    fun mediaId(sha256Hex: String): String = "media-${sha256Hex.take(16)}"

    /**
     * Stable measurement id: the same calibrated result at the same instant is the same
     * measurement, so a retried save cannot duplicate it.
     */
    fun measurementId(
        label: String,
        meters: Double,
        referenceLabel: String,
        referenceMeters: Double,
        pixelSpan: Double,
        calibratedAtEpochMs: Long,
    ): String {
        val preimage = buildString {
            append("fieldwright.measurement.v1")
            append('\u001F').append(label.trim())
            append('\u001F').append(String.format(java.util.Locale.US, "%.6f", meters))
            append('\u001F').append(referenceLabel)
            append('\u001F').append(String.format(java.util.Locale.US, "%.6f", referenceMeters))
            append('\u001F').append(String.format(java.util.Locale.US, "%.3f", pixelSpan))
            append('\u001F').append(calibratedAtEpochMs)
        }
        return "meas-${sha256HexOf(preimage).take(16)}"
    }

    private fun sha256HexOf(text: String): String = Sha256.sha256Hex(text)
}
