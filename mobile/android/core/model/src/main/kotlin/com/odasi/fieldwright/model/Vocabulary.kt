package com.odasi.fieldwright.model

/**
 * Fieldwright domain core — pure Kotlin, no Android, no I/O.
 *
 * Everything in this package is a value object, an enum, or a pure decision function.
 * The Android layers (data, features, app) translate to and from these types. That
 * boundary is deliberate: the business rules that decide *who gets offered a job*,
 * *whether a packet may be sealed*, and *what a worker is owed* must be testable
 * without an emulator.
 *
 * Naming follows the AUCTUM Trust Engine contract (see docs/01-architecture.md):
 * records are appended, never edited; tiers are derived, never assigned; the seal is
 * earned by evidence and can be withdrawn; money and evidence move independently.
 */

/** Wall-clock time is always injected, never read inside a decision function. */
fun interface Clock {
    fun nowEpochMs(): Long
}

class FixedClock(private var now: Long) : Clock {
    override fun nowEpochMs(): Long = now
    fun advanceBy(ms: Long) {
        now += ms
    }
    fun set(epochMs: Long) {
        now = epochMs
    }
}

/** Credentials gate which work an operator may accept. */
enum class Credential(val label: String, val short: String) {
    RPZ_BACKFLOW_TESTER("RPZ / backflow assembly tester", "RPZ"),
    ROOF_SAFETY("Roof safety certified", "ROOF"),
    PROPERTY_INSPECTOR("Property inspector", "PROP"),
    NOTARY("Commissioned notary", "NOTA"),
    DRONE_PILOT("Part 107 remote pilot", "UAS"),
}

/** A job family. Templates carry the checklist and the badge obligations for the work. */
enum class JobType(
    val code: String,
    val label: String,
    val shortLabel: String,
    val baseBountyCents: Int,
    val estimatedMinutes: Int,
    val cadence: Cadence,
    val requiredCredentials: Set<Credential>,
    val consentScope: String,
    val captureStandard: String,
) {
    ROOF_HAIL(
        code = "ROOF_HAIL",
        label = "Roof hail assessment",
        shortLabel = "Roof",
        baseBountyCents = 42_000,
        estimatedMinutes = 55,
        cadence = Cadence.ONE_OFF,
        requiredCredentials = setOf(Credential.ROOF_SAFETY),
        consentScope = "Roof surfaces, slopes and penetrations. Not the interior of the home. Not the street.",
        captureStandard = "Four slopes plus a test square, one measured paddle, daylight frames.",
    ),
    RPZ_TEST(
        code = "RPZ_TEST",
        label = "Annual RPZ test",
        shortLabel = "RPZ",
        baseBountyCents = 9_500,
        estimatedMinutes = 35,
        cadence = Cadence.RECURRING_YEARLY,
        requiredCredentials = setOf(Credential.RPZ_BACKFLOW_TESTER),
        consentScope = "The assembly inside the service vault. Not the building interior.",
        captureStandard = "Vault polygon, body tag, gauge face, test cocks. No needle transcription.",
    ),
    PROPERTY_WALK(
        code = "PROPERTY_WALK",
        label = "Monthly property walk",
        shortLabel = "Walk",
        baseBountyCents = 6_500,
        estimatedMinutes = 30,
        cadence = Cadence.RECURRING_MONTHLY,
        requiredCredentials = setOf(Credential.PROPERTY_INSPECTOR),
        consentScope = "Common areas and exterior envelopes named in the property brief.",
        captureStandard = "Each finding photographed against a fixed corner reference; no unit interiors.",
    ),
    METER_CLUSTER(
        code = "METER_CLUSTER",
        label = "Meter cluster read",
        shortLabel = "Meter",
        baseBountyCents = 2_800,
        estimatedMinutes = 15,
        cadence = Cadence.RECURRING_MONTHLY,
        requiredCredentials = emptySet(),
        consentScope = "Exterior meter bank only. Serial and read dials.",
        captureStandard = "One frame per meter, serial legible, no residents or vehicles in frame.",
    ),
    PROGRESS_PROOF(
        code = "PROGRESS_PROOF",
        label = "Contractor progress proof",
        shortLabel = "Build",
        baseBountyCents = 12_000,
        estimatedMinutes = 40,
        cadence = Cadence.ONE_OFF,
        requiredCredentials = emptySet(),
        consentScope = "The work in scope, photographed where it stands.",
        captureStandard = "Wide frame per milestone plus one measured reference per change.",
    ),
    CLAIM_SWEEP(
        code = "CLAIM_SWEEP",
        label = "Claim evidence sweep",
        shortLabel = "Claim",
        baseBountyCents = 28_000,
        estimatedMinutes = 45,
        cadence = Cadence.ONE_OFF,
        requiredCredentials = setOf(Credential.PROPERTY_INSPECTOR),
        consentScope = "Damage and context named on the claim. Faces and plates excluded unless consented.",
        captureStandard = "Establishing shot, mid shot, detail shot, and one measurement per claimed item.",
    ),
    ;

    enum class Cadence { ONE_OFF, RECURRING_MONTHLY, RECURRING_YEARLY }
}

/** Which claim a badge makes. States live in [BadgeState]; both are the engine's, not the UI's. */
enum class BadgeKey(val label: String, val claim: String) {
    STORM_ATTRIBUTION("Storm attribution", "This property sits inside a recorded hail event footprint."),
    FIELD_CAPTURED("Field captured", "These frames are of this site, on this date, from these angles."),
    MEASURED("Measured", "The dimensions come from a calibrated measurement, not an estimate."),
    SCOPE_AGREED("Scope agreed", "The subject accepted this scope of capture in this session."),
    CONSENT_ON_FILE("Consent on file", "A named person authorized this capture and its stated limits."),
    SETTLED("Settled", "The requester paid for this packet."),
    ;

    companion object {
        /** Badge obligations by job family — the adjudication rule every adapter reads. */
        fun requiredFor(type: JobType): Set<BadgeKey> = when (type) {
            JobType.ROOF_HAIL -> setOf(STORM_ATTRIBUTION, FIELD_CAPTURED, MEASURED, SCOPE_AGREED, CONSENT_ON_FILE)
            JobType.RPZ_TEST -> setOf(FIELD_CAPTURED, MEASURED, CONSENT_ON_FILE)
            JobType.PROPERTY_WALK -> setOf(FIELD_CAPTURED, SCOPE_AGREED, CONSENT_ON_FILE)
            JobType.METER_CLUSTER -> setOf(FIELD_CAPTURED, CONSENT_ON_FILE)
            JobType.PROGRESS_PROOF -> setOf(FIELD_CAPTURED, MEASURED, SCOPE_AGREED)
            JobType.CLAIM_SWEEP -> setOf(FIELD_CAPTURED, MEASURED, SCOPE_AGREED, CONSENT_ON_FILE)
        }
    }
}

enum class BadgeState(val label: String, val blocks: Boolean) {
    MISSING("Missing", true),
    IN_REVIEW("In review", true),
    VERIFIED("Verified", false),
    FLAGGED("Flagged", true),
    WAIVED("Waived", false),
}

/** Derived, never assigned. Tier is a named state with reasons — never a percentage. */
enum class EvidenceTier(val level: Int, val label: String, val meaning: String) {
    REPORTED(1, "Reported", "At least one record exists."),
    COUNTERPARTY_CONFIRMED(2, "Counterparty confirmed", "An independent second actor has attested."),
    FIELD_CAPTURED(3, "Field captured", "A capture record met the market's capture standard."),
    PAYMENT_VERIFIED(4, "Payment verified", "A settled payment record exists against the subject."),
    VERIFIED_BY_AUCTUM(5, "Verified by AUCTUM", "Every required badge verifies and the seal preconditions hold."),
}

enum class SealState(val label: String) {
    PENDING("Pending"),
    EARNED("Sealed"),
    FLAGGED("Flagged"),
    WITHDRAWN("Withdrawn"),
}

/**
 * What happened. The evidence ledger is append-only: a mistake is a [CORRECTION]
 * record, never an edit or a delete.
 */
enum class RecordKind(val label: String) {
    CAPTURE("Capture"),
    ATTEST("Attest"),
    CUSTODY("Custody"),
    INSPECTION("Inspection"),
    PAYMENT("Payment"),
    CORRECTION("Correction"),
}

enum class StepResult(val label: String) { PASS("Pass"), FAIL("Fail"), NOT_APPLICABLE("N/A"), PENDING("Open") }

enum class ProofKind { PHOTO, MEASUREMENT, OBSERVATION, CONSENT }

enum class MediaKind { PHOTO, VIDEO_FRAME, DEPTH_MAP, SIGNATURE }

enum class MediaSource { CAMERA, SIMULATED, IMPORTED }

enum class RedactionState { NONE, REQUIRED, APPLIED, NOT_NEEDED }

enum class ConsentMethod(val label: String) {
    IN_APP_SIGNATURE("Signed in app"),
    VERBAL_RECORDED("Recorded verbally"),
    PRE_AUTHORIZED("Pre-authorized on file"),
}

/** The operator-facing life of a job. Server-authoritative for money; append-only for evidence. */
enum class JobStatus(val label: String) {
    SCHEDULED("Scheduled"),
    AVAILABLE("Available"),
    OFFERED("Offered"),
    ACCEPTED("Accepted"),
    EN_ROUTE("En route"),
    ON_SITE("On site"),
    IN_PROGRESS("Capturing"),
    PACKET_READY("Packet ready"),
    SEALED("Sealed"),
    SUBMITTED("Submitted"),
    UNDER_REVIEW("In review"),
    APPROVED("Approved"),
    PAID("Paid"),
    DISPUTED("Disputed"),
    CANCELLED("Cancelled"),
    EXPIRED("Expired"),
    ;

    val isTerminal: Boolean get() = this == PAID || this == CANCELLED || this == EXPIRED
    val isActive: Boolean get() = this in setOf(ACCEPTED, EN_ROUTE, ON_SITE, IN_PROGRESS, PACKET_READY, SEALED, SUBMITTED)
    val isOpenForCapture: Boolean get() = this in setOf(ON_SITE, IN_PROGRESS, PACKET_READY)
}

enum class OfferStatus { PENDING, ACCEPTED, DECLINED, EXPIRED, WITHDRAWN }

enum class LedgerKind(val label: String) {
    EARNING("Job earning"),
    BONUS("Busy bonus"),
    TIP("Requester tip"),
    INSTANT_FEE("Instant payout fee"),
    ADJUSTMENT("Adjustment"),
    REVERSAL("Reversal"),
}

enum class LedgerStatus { PENDING, AVAILABLE, PAID, REVERSED, FAILED }

enum class PayoutMode(val label: String) {
    INSTANT("Instant to debit"),
    STANDARD("Standard ACH"),
}

enum class PayoutStatus { REQUESTED, PROCESSING, PAID, FAILED, CANCELLED }

enum class RequesterKind(val label: String) {
    INSURER("Insurer"),
    PROPERTY_MANAGER("Property manager"),
    UTILITY("Utility district"),
    CONTRACTOR("Contractor"),
    OWNER("Owner"),
}

enum class SyncPhase { IDLE, SYNCING, WAITING_FOR_NETWORK, RETRYING, DEAD_LETTER }
