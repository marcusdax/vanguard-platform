package com.odasi.fieldwright.crypto

/**
 * Wave B C2PA v2.1 + Fieldwatch RF/Sensor Provenance assertion bundle.
 * Mirrors AUCTUM.atlas/backend/src/services/field/provenance.ts so captured
 * Android packets carry identical manifest digests from capture to Vault.
 */
data class ProvenanceManifest(
    val packetCode: String,
    val headHash: String,
    val merkleLeaf: String,
    val pitchDeg: Double,
    val baroHpa: Double,
    val wifiBssidDigest: String,
    val manifestDigest: String,
) {
    companion object {
        fun build(
            packetCode: String,
            headHash: String,
            merkleLeaf: String,
            pitchDeg: Double = 30.3,
            baroHpa: Double = 1013.2,
            wifiBssidDigest: String = "",
        ): ProvenanceManifest {
            val payload = "c2pa-v2.1|$packetCode|$headHash|$merkleLeaf|$pitchDeg|$baroHpa|$wifiBssidDigest"
            val digest = Sha256.hex(payload.toByteArray(Charsets.UTF_8))
            return ProvenanceManifest(
                packetCode = packetCode,
                headHash = headHash,
                merkleLeaf = merkleLeaf,
                pitchDeg = pitchDeg,
                baroHpa = baroHpa,
                wifiBssidDigest = wifiBssidDigest.ifEmpty { digest.take(24) },
                manifestDigest = digest,
            )
        }
    }
}
