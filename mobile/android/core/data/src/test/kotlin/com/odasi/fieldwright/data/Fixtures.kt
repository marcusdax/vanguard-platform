package com.odasi.fieldwright.data

import com.odasi.fieldwright.model.Availability
import com.odasi.fieldwright.model.AutoAcceptPolicy
import com.odasi.fieldwright.model.ChecklistResult
import com.odasi.fieldwright.model.Consent
import com.odasi.fieldwright.model.ConsentMethod
import com.odasi.fieldwright.model.Credential
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.HashAlgorithm
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobCatalog
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.JobType
import com.odasi.fieldwright.model.Measurement
import com.odasi.fieldwright.model.MediaKind
import com.odasi.fieldwright.model.MediaRef
import com.odasi.fieldwright.model.MediaSource
import com.odasi.fieldwright.model.Operator
import com.odasi.fieldwright.model.RedactionState
import com.odasi.fieldwright.model.Requester
import com.odasi.fieldwright.model.RequesterKind
import com.odasi.fieldwright.model.SignatureScheme
import com.odasi.fieldwright.model.SiteRef
import com.odasi.fieldwright.model.StepResult
import java.security.MessageDigest
import java.util.Base64

/**
 * Test doubles. Deliberately **not** the production crypto: a deterministic digest and a
 * toy signature scheme prove the orchestration order without spending test time on ECDSA,
 * and they make tamper tests easy to reason about.
 */
class FakeSha256 : HashAlgorithm {
    override fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

class FakeSigner(override val keyId: String = "test-key-0001") : SignatureScheme {
    override fun sign(canonical: String): String =
        Base64.getEncoder().encodeToString("sig|$keyId|$canonical".toByteArray(Charsets.UTF_8))

    override fun verify(canonical: String, signatureBase64: String): Boolean = runCatching {
        String(Base64.getDecoder().decode(signatureBase64), Charsets.UTF_8) == "sig|$keyId|$canonical"
    }.getOrDefault(false)
}

object Fixtures {

    const val NOW: Long = 1_743_000_000_000L

    val hasher: HashAlgorithm = FakeSha256()
    val signer: SignatureScheme = FakeSigner()

    fun operator(): Operator = Operator(
        id = "op-test",
        displayName = "Elena Voss",
        initials = "EV",
        credentialTier = 3,
        ratingAvg = 4.9,
        acceptanceRate = 0.92,
        onTimeRate = 0.97,
        completedJobs = 38,
        streakDays = 6,
        credentials = setOf(
            Credential.ROOF_SAFETY,
            Credential.PROPERTY_INSPECTOR,
            Credential.RPZ_BACKFLOW_TESTER,
        ),
        payoutAccountLast4 = "4417",
        weeklyGoalCents = 120_000,
        memberSinceLabel = "March 2024",
    )

    fun availability(now: Long = NOW): Availability = Availability(
        isOnline = true,
        updatedAtEpochMs = now - 60_000,
        radiusMeters = 15_000,
        autoAccept = AutoAcceptPolicy(enabled = true, minPayoutCents = 3_000),
        areaLabel = "Creekside · MUD 14",
    )

    fun job(
        id: String = "job-test-1",
        type: JobType = JobType.ROOF_HAIL,
        status: JobStatus = JobStatus.ON_SITE,
        now: Long = NOW,
    ): Job = Job(
        id = id,
        type = type,
        title = "Panel T-14 is set",
        requester = Requester(
            org = "Lamar Tilt Partners",
            contactName = "Chris Adeyemi",
            kind = RequesterKind.INSURER,
            billingLabel = "Lamar Tilt Partners · Claim 88-2214",
        ),
        site = SiteRef(
            address = "1412 Creekside Bend",
            unit = null,
            point = GeoPoint(30.2720, -97.7380, now, 8f, GeoPoint.FixSource.SIMULATED),
            geofenceRadiusMeters = 60,
            accessNotes = "Gate code at the call box.",
            planLabel = "Creekside parcel 14 · lot 8",
        ),
        status = status,
        bountyCents = 40_500,
        bonusCents = 1_500,
        estimatedMinutes = type.estimatedMinutes,
        scheduledForEpochMs = now - 60_000,
        slaMinutes = 120,
        distanceMeters = 1_500,
        etaMinutes = 5,
        consentScope = type.consentScope,
        checklist = JobCatalog.checklistFor(type),
        requiredCredentials = type.requiredCredentials,
        demo = false,
        notes = "Hail event 04-11.",
        acceptedAtEpochMs = now - 30_000,
        arrivedAtEpochMs = now - 10_000,
    )

    fun consent(now: Long = NOW): Consent = Consent(
        name = "Chris Adeyemi",
        role = "Facilities lead, Lamar Tilt Partners",
        scopeText = JobType.ROOF_HAIL.consentScope,
        method = ConsentMethod.IN_APP_SIGNATURE,
        capturedAtEpochMs = now - 20_000,
        signatureHash = FakeSha256().sha256Hex("typed-signature:Chris Adeyemi"),
        verifiedByOperatorId = "op-test",
    )

    fun media(now: Long = NOW): List<MediaRef> = listOf(
        MediaRef(
            id = "MD-01",
            kind = MediaKind.PHOTO,
            sha256 = FakeSha256().sha256Hex("frame-1"),
            bytes = 1_800_000,
            capturedAtEpochMs = now - 15_000,
            width = 4_032,
            height = 3_024,
            redaction = RedactionState.NONE,
            source = MediaSource.SIMULATED,
            note = "South slope · establishing",
        ),
        MediaRef(
            id = "MD-02",
            kind = MediaKind.PHOTO,
            sha256 = FakeSha256().sha256Hex("frame-2"),
            bytes = 1_900_000,
            capturedAtEpochMs = now - 14_000,
            width = 4_032,
            height = 3_024,
            redaction = RedactionState.NONE,
            source = MediaSource.SIMULATED,
            note = "South slope · test square",
        ),
    )

    fun measurement(now: Long = NOW): Measurement = Measurement(
        id = "MS-01",
        label = "South slope test square",
        meters = 3.05,
        referenceLabel = "Site card 30 cm",
        referenceMeters = 0.30,
        pixelSpan = 1_240.0,
        calibratedAtEpochMs = now - 60_000,
        source = MediaSource.SIMULATED,
    )

    fun checklist(job: Job): List<ChecklistResult> = job.checklist.map { step ->
        ChecklistResult(key = step.key, result = StepResult.PASS, note = "Confirmed on site.")
    }

    fun passingGates(withOverride: Boolean = false): List<GateCheck> = listOf(
        GateCheck("gate.consent", "Consent on file", true, "Signed in app", true),
        GateCheck("gate.geofence", "Geofence satisfied", true, "Inside the fence", true),
        GateCheck("gate.redaction", "Redaction resolved", true, "No faces or plates", true),
        GateCheck("gate.checklist", "Required checklist", true, "All required steps", true),
        GateCheck("gate.measurement", "Measurement calibrated", true, "Site card 30 cm", true),
        GateCheck(
            "gate.arrival",
            "Arrival review",
            !withOverride,
            if (withOverride) "Override: gate was locked" else "Arrival matched the fence",
            false,
        ),
        GateCheck("gate.chain", "Chain verifies", true, "Materialised at seal", true),
    )
}
