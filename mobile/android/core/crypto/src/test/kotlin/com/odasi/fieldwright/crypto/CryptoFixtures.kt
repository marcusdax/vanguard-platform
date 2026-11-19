package com.odasi.fieldwright.crypto

import com.odasi.fieldwright.model.ChecklistResult
import com.odasi.fieldwright.model.Consent
import com.odasi.fieldwright.model.ConsentMethod
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobCatalog
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.JobType
import com.odasi.fieldwright.model.MediaKind
import com.odasi.fieldwright.model.MediaRef
import com.odasi.fieldwright.model.MediaSource
import com.odasi.fieldwright.model.Measurement
import com.odasi.fieldwright.model.Operator
import com.odasi.fieldwright.model.PacketAssembler
import com.odasi.fieldwright.model.RedactionState
import com.odasi.fieldwright.model.RecordKind
import com.odasi.fieldwright.model.Requester
import com.odasi.fieldwright.model.RequesterKind
import com.odasi.fieldwright.model.SiteRef
import com.odasi.fieldwright.model.StepResult

/** Fixed instants and ready-made chains so the crypto tests are deterministic. */
internal const val TEST_NOW: Long = 1_768_478_400_000L

internal object CryptoFixtures {
    const val OPERATOR_ID: String = "op-1"

    val hasher = Sha256

    /** One signer for the whole test run; generated once because key generation is slow. */
    val signer: SoftwareEcdsaSigner = SoftwareEcdsaSigner.generate()

    val point = GeoPoint(lat = 30.2672, lng = -97.7431, recordedAtEpochMs = TEST_NOW, accuracyMeters = 4.0f)

    fun operator(id: String = OPERATOR_ID): Operator = Operator(
        id = id,
        displayName = "Mara Voss",
        initials = "MV",
        credentialTier = 3,
        ratingAvg = 4.86,
        acceptanceRate = 0.88,
        onTimeRate = 0.92,
        completedJobs = 214,
        streakDays = 12,
        credentials = com.odasi.fieldwright.model.Credential.entries.toSet(),
        payoutAccountLast4 = "4412",
        weeklyGoalCents = 60_000,
        memberSinceLabel = "Member since 2023",
    )

    fun job(id: String = "job-1", type: JobType = JobType.ROOF_HAIL): Job = Job(
        id = id,
        type = type,
        title = JobCatalog.subjectFor(type, "T-14"),
        requester = Requester("Creekside MUD 14", "Dana Reyes", RequesterKind.UTILITY, "MUD 14 · annual"),
        site = SiteRef(
            address = "14 Creekside Row",
            unit = null,
            point = point,
            geofenceRadiusMeters = 40,
            accessNotes = "Gate code 4412",
            planLabel = "T-14",
        ),
        status = JobStatus.AVAILABLE,
        bountyCents = type.baseBountyCents,
        bonusCents = 0,
        estimatedMinutes = type.estimatedMinutes,
        scheduledForEpochMs = null,
        slaMinutes = 120,
        distanceMeters = 3_200,
        etaMinutes = 10,
        consentScope = type.consentScope,
        checklist = JobCatalog.checklistFor(type),
        requiredCredentials = type.requiredCredentials,
        demo = false,
        notes = "",
    )

    fun consent(): Consent = Consent(
        name = "Dana Reyes",
        role = "property manager",
        scopeText = JobType.ROOF_HAIL.consentScope,
        method = ConsentMethod.IN_APP_SIGNATURE,
        capturedAtEpochMs = TEST_NOW - 5_000,
        signatureHash = "a".repeat(64),
        verifiedByOperatorId = OPERATOR_ID,
    )

    fun photo(): MediaRef = MediaRef(
        id = "media-1",
        kind = MediaKind.PHOTO,
        sha256 = "b".repeat(64),
        bytes = 240_000,
        capturedAtEpochMs = TEST_NOW - 4_000,
        width = 1920,
        height = 1080,
        redaction = RedactionState.NOT_NEEDED,
        source = MediaSource.SIMULATED,
    )

    fun measurement(): Measurement = Measurement(
        id = "meas-1",
        label = "hail strike",
        meters = 0.032,
        referenceLabel = "site card 30 cm",
        referenceMeters = 0.30,
        pixelSpan = 210.0,
        calibratedAtEpochMs = TEST_NOW - 3_000,
        source = MediaSource.SIMULATED,
    )

    /** A packet with every required badge supported, but not yet sealed. */
    fun completePacket(jobId: String = "job-1"): EvidencePacket {
        val base = PacketAssembler.draft(job(id = jobId), operator(), TEST_NOW).copy(
            consent = consent(),
            checklist = JobCatalog.checklistFor(JobType.ROOF_HAIL).map {
                ChecklistResult(it.key, StepResult.PASS, "done", emptyList())
            },
            media = listOf(photo()),
            measurements = listOf(measurement()),
        )
        return PacketAssembler.appendRecord(
            packet = base,
            kind = RecordKind.CAPTURE,
            actorId = OPERATOR_ID,
            summary = "Four slopes captured",
            payload = mapOf("captureStandardMet" to "true"),
            atEpochMs = TEST_NOW + 1_000,
            hasher = hasher,
            signer = signer,
        )
    }

    fun sealedPacket(jobId: String = "job-1"): EvidencePacket = PacketAssembler.seal(
        packet = completePacket(jobId),
        gates = listOf(GateCheck("consent", "Consent on file", true, "Signed in app", true)),
        hasher = hasher,
        signer = signer,
        nowEpochMs = TEST_NOW + 2_000,
    )
}
