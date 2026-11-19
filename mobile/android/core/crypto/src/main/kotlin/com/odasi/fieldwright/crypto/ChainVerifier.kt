package com.odasi.fieldwright.crypto

import com.odasi.fieldwright.model.ChainRecord
import com.odasi.fieldwright.model.ChainVerification
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.HashAlgorithm
import com.odasi.fieldwright.model.PacketAssembler
import com.odasi.fieldwright.model.SignatureScheme
import com.odasi.fieldwright.model.computeHash

/**
 * Re-walks a packet's record chain from genesis and reports exactly where it breaks.
 *
 * What "verifies" means here, in order:
 * 1. the records are in sequence (`seq` is 1-based and must equal position + 1, matching
 *    the wire `Record.seq` and the server's own chain walk);
 * 2. each record links to its predecessor (`prevHash`), and record 0 links to the
 *    64-zero genesis hash;
 * 3. each record's stored hash equals the hash **recomputed from its content** using the
 *    canonical record rule from `:core:model` — so editing a summary, an actor, a
 *    timestamp or one payload entry is detected even if the attacker also rewrites the
 *    hash field, because the next record still commits to the old hash;
 * 4. (optionally, when a verifier key is supplied) signatures made by that key verify
 *    over the record hash.
 *
 * Signature policy: records signed by a *different* key id are skipped, not failed. A
 * packet may be signed on a device keystore and re-verified on a server or a second
 * device that does not hold that key. Records with no signature are tolerated but never
 * counted as signed; a signature that claims our key but does not verify is a hard fail,
 * because that is forgery rather than absence.
 */
object ChainVerifier {

    /**
     * Verify [records] with [hasher]. When [scheme] is supplied, signatures that claim
     * [SignatureScheme.keyId] are checked as well.
     */
    fun verify(
        records: List<ChainRecord>,
        hasher: HashAlgorithm,
        scheme: SignatureScheme? = null,
    ): ChainVerification {
        if (records.isEmpty()) {
            return ChainVerification(
                valid = false,
                entries = 0,
                brokenAtSeq = null,
                headHash = PacketAssembler.GENESIS_HASH,
                detail = "The chain is empty; an empty record set proves nothing.",
            )
        }

        var expectedPrev = PacketAssembler.GENESIS_HASH
        for ((position, record) in records.withIndex()) {
            // Wire-compatible 1-based sequence: the sync server expects records[0].seq == 1,
            // and the domain uses the same numbering so there is no off-by-one at upload.
            val expectedSeq = position + 1
            if (record.seq != expectedSeq) {
                return broken(
                    records = records,
                    seq = record.seq,
                    detail = "Record ${record.seq} is out of order: it sits at position $position " +
                        "where sequence $expectedSeq was expected.",
                )
            }
            if (record.hash.isBlank()) {
                return broken(
                    records = records,
                    seq = record.seq,
                    detail = "Record ${record.seq} is an unmaterialised draft; it was never hashed.",
                )
            }
            if (record.prevHash != expectedPrev) {
                return broken(
                    records = records,
                    seq = record.seq,
                    detail = "Record ${record.seq} does not link to the previous record " +
                        "(prevHash mismatch). The chain was reordered, truncated or spliced.",
                )
            }
            val recomputed = record.computeHash(hasher)
            if (recomputed != record.hash) {
                return broken(
                    records = records,
                    seq = record.seq,
                    detail = "Record ${record.seq} content does not match its hash " +
                        "(stored ${short(record.hash)}, recomputed ${short(recomputed)}). " +
                        "A field was edited after the record was appended.",
                )
            }
            val signature = record.signatureBase64
            if (scheme != null && signature != null && record.signerKeyId == scheme.keyId) {
                if (!scheme.verify(record.hash, signature)) {
                    return broken(
                        records = records,
                        seq = record.seq,
                        detail = "Record ${record.seq} claims signer ${short(scheme.keyId)} " +
                            "but its signature does not verify.",
                    )
                }
            }
            expectedPrev = record.hash
        }

        return ChainVerification(
            valid = true,
            entries = records.size,
            brokenAtSeq = null,
            headHash = records.last().hash,
            detail = "Chain verifies: ${records.size} record(s) from genesis to ${short(records.last().hash)}.",
        )
    }

    /** Convenience: verify the packet's own record list. */
    fun verify(packet: EvidencePacket, hasher: HashAlgorithm): ChainVerification =
        verify(packet.records, hasher)

    /**
     * Check the packet-level signature (the seal credential) against a verifying key.
     *
     * `PacketAssembler.seal` signs the final chain head hash and stores it in
     * [EvidencePacket.signatureBase64]. Because the head hash commits — through
     * `prevHash` links — to every earlier record, one signature covers the whole packet.
     *
     * Returns **false** when there is no signature, no head hash, or the signature does
     * not verify. `EvidencePacket` has no separate signer key id, so a key mismatch is
     * reported by the signature itself failing, never by a string comparison. A caller
     * that must prove *who* signed needs the signer's public key out-of-band (the
     * `signerKeyId` lives on each [ChainRecord]).
     */
    fun verifyPacketSignature(packet: EvidencePacket, scheme: SignatureScheme): Boolean {
        val signature = packet.signatureBase64 ?: return false
        val headHash = packet.packetHash?.takeIf { it.isNotBlank() } ?: return false
        return scheme.verify(headHash, signature)
    }

    private fun broken(records: List<ChainRecord>, seq: Int, detail: String): ChainVerification =
        ChainVerification(
            valid = false,
            entries = records.size,
            brokenAtSeq = seq,
            headHash = records.lastOrNull()?.hash ?: PacketAssembler.GENESIS_HASH,
            detail = detail,
        )

    private fun short(hash: String): String = if (hash.length <= 8) hash else hash.take(8)
}
