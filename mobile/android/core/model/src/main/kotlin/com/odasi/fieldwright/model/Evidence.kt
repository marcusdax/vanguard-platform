package com.odasi.fieldwright.model

/** Who agreed to what, and when. Consent is a record, not a checkbox. */
data class Consent(
    val name: String,
    val role: String,
    val scopeText: String,
    val method: ConsentMethod,
    val capturedAtEpochMs: Long,
    val signatureHash: String?,
    val verifiedByOperatorId: String,
)

/** Media is referenced by hash; the vault never stores an unhashed frame. */
data class MediaRef(
    val id: String,
    val kind: MediaKind,
    val sha256: String,
    val bytes: Long,
    val capturedAtEpochMs: Long,
    val width: Int,
    val height: Int,
    val redaction: RedactionState,
    val source: MediaSource,
    val note: String = "",
) {
    val isPhoto: Boolean get() = kind == MediaKind.PHOTO || kind == MediaKind.VIDEO_FRAME
}

/**
 * One link in the per-packet custody chain. `payload` is a stable, order-independent
 * map rendered canonically before hashing (see :core:crypto CanonicalJson).
 */
data class ChainRecord(
    val seq: Int,
    val kind: RecordKind,
    val atEpochMs: Long,
    val actorId: String,
    val summary: String,
    val payload: Map<String, String>,
    val prevHash: String,
    val hash: String,
    val signatureBase64: String?,
    val signerKeyId: String?,
)

data class Badge(
    val key: BadgeKey,
    val state: BadgeState,
    val required: Boolean,
    val rationale: String,
    val evidenceRefs: List<String>,
)

data class GateCheck(
    val id: String,
    val label: String,
    val passed: Boolean,
    val detail: String,
    val blocking: Boolean,
)

/** The sealed artefact that gets sold, shared, and defended. */
data class EvidencePacket(
    val id: String,
    val jobId: String,
    val jobType: JobType,
    val operatorId: String,
    val title: String,
    val requesterLabel: String,
    val site: SiteRef,
    val consent: Consent?,
    val badges: List<Badge>,
    val tier: EvidenceTier,
    val seal: SealState,
    val records: List<ChainRecord>,
    val media: List<MediaRef>,
    val checklist: List<ChecklistResult>,
    val gates: List<GateCheck>,
    val measurements: List<Measurement>,
    val notes: String,
    val demo: Boolean,
    val createdAtEpochMs: Long,
    val sealedAtEpochMs: Long?,
    val merkleLeaf: String?,
    val merkleProof: List<String>,
    val asOfEpochMs: Long,
    val signatureBase64: String?,
) {
    val packetHash: String? get() = records.lastOrNull()?.hash
    val shortHash: String get() = (packetHash ?: records.firstOrNull()?.hash ?: "").take(8)
    val photoCount: Int get() = media.count { it.isPhoto }
    val blockedGates: List<GateCheck> get() = gates.filter { it.blocking && !it.passed }
    val verifiedBadges: List<Badge> get() = badges.filter { it.state == BadgeState.VERIFIED }
    val requiredBadges: List<Badge> get() = badges.filter { it.required }
    val isSealed: Boolean get() = seal == SealState.EARNED
    val recordCount: Int get() = records.size
}

data class SealEvaluation(
    val state: SealState,
    val tier: EvidenceTier,
    val asOfEpochMs: Long,
    val gates: List<GateCheck>,
    val reasons: List<String>,
    val packetHash: String?,
) {
    val canSeal: Boolean get() = state == SealState.EARNED
}

/** Result of re-walking a chain from genesis. */
data class ChainVerification(
    val valid: Boolean,
    val entries: Int,
    val brokenAtSeq: Int?,
    val headHash: String,
    val detail: String,
)

data class PacketExport(
    val fileName: String,
    val canonicalJson: String,
    val custodyText: String,
    val chainHead: String,
    val recordCount: Int,
)
