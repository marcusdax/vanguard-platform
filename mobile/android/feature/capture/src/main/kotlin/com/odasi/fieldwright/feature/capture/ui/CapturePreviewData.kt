package com.odasi.fieldwright.feature.capture.ui

import com.odasi.fieldwright.data.SealOutcome
import com.odasi.fieldwright.feature.capture.BannerTone
import com.odasi.fieldwright.feature.capture.CameraFailure
import com.odasi.fieldwright.feature.capture.CaptureBanner
import com.odasi.fieldwright.feature.capture.CapturePhase
import com.odasi.fieldwright.feature.capture.CaptureState
import com.odasi.fieldwright.feature.capture.CaptureStepState
import com.odasi.fieldwright.feature.capture.CapturedMediaState
import com.odasi.fieldwright.feature.capture.ConsentForm
import com.odasi.fieldwright.feature.capture.EvidenceState
import com.odasi.fieldwright.feature.capture.GateReview
import com.odasi.fieldwright.feature.capture.MeasurementDraft
import com.odasi.fieldwright.feature.capture.SealProgress
import com.odasi.fieldwright.feature.capture.SealStage
import com.odasi.fieldwright.feature.capture.StepStatus
import com.odasi.fieldwright.feature.capture.measure.MeasurementMath
import com.odasi.fieldwright.feature.capture.measure.MeasurementReference
import com.odasi.fieldwright.feature.capture.measure.PixelPoint
import com.odasi.fieldwright.model.Badge
import com.odasi.fieldwright.model.BadgeKey
import com.odasi.fieldwright.model.BadgeState
import com.odasi.fieldwright.model.ChainRecord
import com.odasi.fieldwright.model.ChainVerification
import com.odasi.fieldwright.model.Consent
import com.odasi.fieldwright.model.ConsentMethod
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.EvidenceTier
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobCatalog
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.JobType
import com.odasi.fieldwright.model.MediaKind
import com.odasi.fieldwright.model.MediaSource
import com.odasi.fieldwright.model.Operator
import com.odasi.fieldwright.model.RecordKind
import com.odasi.fieldwright.model.RedactionState
import com.odasi.fieldwright.model.Requester
import com.odasi.fieldwright.model.RequesterKind
import com.odasi.fieldwright.model.SealEvaluation
import com.odasi.fieldwright.model.SealState
import com.odasi.fieldwright.model.SiteRef
import com.odasi.fieldwright.model.SyncPhase

/**
 * Deterministic sample data for the capture `@Preview`s.
 *
 * Kept next to the UI because previews must render the real state types, not a parallel
 * preview-only model. It is `internal` and never referenced from production code paths.
 */
internal object CaptureSamples {

    const val NOW: Long = 1_763_000_000_000L

    val operator: Operator = Operator(
        id = "op-rae",
        displayName = "Rae Calder",
        initials = "RC",
        credentialTier = 2,
        ratingAvg = 4.9,
        acceptanceRate = 0.94,
        onTimeRate = 0.97,
        completedJobs = 128,
        streakDays = 6,
        credentials = emptySet(),
        payoutAccountLast4 = "4417",
        weeklyGoalCents = 120_000,
        memberSinceLabel = "Mar 2024",
    )

    fun job(status: JobStatus = JobStatus.ON_SITE): Job = Job(
        id = "FW-1048",
        type = JobType.ROOF_HAIL,
        title = "Hail assessment · 1420 Creekside Bend",
        requester = Requester(
            org = "Creekside Mutual",
            contactName = "Dana Whitlock",
            kind = RequesterKind.INSURER,
            billingLabel = "Creekside Mutual · claims",
        ),
        site = SiteRef(
            address = "1420 Creekside Bend",
            unit = "Lot 12",
            point = GeoPoint(30.2672, -97.7431, NOW, accuracyMeters = 4.2f),
            geofenceRadiusMeters = 150,
            accessNotes = "Side gate code 4417. Dog in the back yard until 09:00.",
            planLabel = "MUD 14 · parcel 88-2",
        ),
        status = status,
        bountyCents = 42_000,
        bonusCents = 6_000,
        estimatedMinutes = 55,
        scheduledForEpochMs = NOW - 3_600_000,
        slaMinutes = 90,
        distanceMeters = 2_400,
        etaMinutes = 7,
        consentScope = JobType.ROOF_HAIL.consentScope,
        checklist = JobCatalog.checklistFor(JobType.ROOF_HAIL),
        requiredCredentials = JobType.ROOF_HAIL.requiredCredentials,
        demo = false,
        notes = "Owner will be on site. South slope access from the driveway.",
    )

    fun consent(): Consent = Consent(
        name = "Dana Whitlock",
        role = "Owner",
        scopeText = JobType.ROOF_HAIL.consentScope,
        method = ConsentMethod.IN_APP_SIGNATURE,
        capturedAtEpochMs = NOW - 1_200_000,
        signatureHash = "9d1f4a0c8e75b2d46a0f5c3e7b9182d4a6c0e2f8b1d3957a4c6e0f2b8d1a3c5e7",
        verifiedByOperatorId = operator.id,
    )

    private fun media(stepKey: String, simulated: Boolean): CapturedMediaState = CapturedMediaState(
        mediaId = "media-${stepKey}0d1f4a0c",
        sha256 = "0d1f4a0c8e75b2d46a0f5c3e7b9182d4a6c0e2f8b1d3957a4c6e0f2b8d1a3c5e7",
        bytes = ByteArray(0),
        widthPx = 640,
        heightPx = 480,
        capturedAtEpochMs = NOW - 900_000,
        simulated = simulated,
        simulatedReason = if (simulated) "No camera on this device." else null,
        mimeType = if (simulated) "image/png" else "image/jpeg",
    )

    private fun capturedSteps(simulated: Boolean): List<CaptureStepState> {
        val checklist = JobCatalog.checklistFor(JobType.ROOF_HAIL)
        val completedKeys = setOf("footprint", "slopes", "scope")
        return checklist.mapIndexed { index, step ->
            val complete = step.key in completedKeys
            val measurementDraft = if (step.key == "paddle") {
                previewMeasurementDraft()
            } else {
                MeasurementDraft()
            }
            CaptureStepState(
                key = step.key,
                label = step.label,
                hint = step.hint,
                proofKind = step.proofKind,
                required = step.required,
                note = if (complete) "Recorded on the south slope at ${10 + index}:12." else "",
                status = if (complete) StepStatus.PASS else StepStatus.OPEN,
                media = if (complete && step.proofKind == com.odasi.fieldwright.model.ProofKind.PHOTO) listOf(media(step.key, simulated)) else emptyList(),
                draft = measurementDraft,
            )
        }
    }

    fun measurementResult(): com.odasi.fieldwright.feature.capture.measure.MeasurementResult? =
        MeasurementMath.measure(
            reference = MeasurementReference.SITE_CARD,
            referenceStart = PixelPoint(120f, 200f),
            referenceEnd = PixelPoint(228f, 200f),
            measuredStart = PixelPoint(180f, 260f),
            measuredEnd = PixelPoint(396f, 260f),
        )

    fun previewMeasurementDraft(): MeasurementDraft = MeasurementDraft(
        reference = MeasurementReference.SITE_CARD,
        referenceStart = PixelPoint(120f, 200f),
        referenceEnd = PixelPoint(228f, 200f),
        targetStart = PixelPoint(180f, 260f),
        targetEnd = PixelPoint(396f, 260f),
        result = measurementResult(),
        label = "Hail strike",
    )

    fun gatesBlocked(): List<GateCheck> = listOf(
        GateCheck(
            id = "consent_on_file",
            label = "Consent on file",
            passed = false,
            detail = "No consent record exists for this job. Capture cannot be sealed until the subject signs the scope.",
            blocking = true,
        ),
        GateCheck(
            id = "redaction_resolved",
            label = "Redaction resolved",
            passed = true,
            detail = "2 frames reviewed · no faces or plates found.",
            blocking = true,
        ),
        GateCheck(
            id = "geofence",
            label = "Geofence satisfied",
            passed = true,
            detail = "Operator fix 84 m from site centre (radius 150 m).",
            blocking = true,
        ),
        GateCheck(
            id = "gps_accuracy",
            label = "GPS accuracy",
            passed = false,
            detail = "Last fix was ±38 m. The packet records it; capture accuracy is noted as weak.",
            blocking = false,
        ),
        GateCheck(
            id = "chain_verifies",
            label = "Chain verifies",
            passed = true,
            detail = "4 records walk clean from genesis.",
            blocking = true,
        ),
    )

    fun gatesClear(): List<GateCheck> = gatesBlocked().map { gate ->
        gate.copy(passed = true, detail = when (gate.id) {
            "consent_on_file" -> "Signed in app by Dana Whitlock · Owner, verified by Rae Calder."
            "gps_accuracy" -> "Fix ±4.2 m at capture time."
            else -> gate.detail
        })
    }

    private fun record(seq: Int, kind: RecordKind, summary: String): ChainRecord = ChainRecord(
        seq = seq,
        kind = kind,
        atEpochMs = NOW - 3_000_000L + seq * 1_000L,
        actorId = operator.id,
        summary = summary,
        payload = mapOf("jobId" to "FW-1048"),
        prevHash = if (seq == 1) GENESIS else "a".repeat(63) + (seq - 1).toString(),
        hash = (0x1f + seq).toString(16).padStart(64, '0'),
        signatureBase64 = "sig-$seq",
        signerKeyId = "fieldwright-op-rae",
    )

    fun packet(): EvidencePacket {
        val records = listOf(
            record(1, RecordKind.CAPTURE, "Capture session opened"),
            record(2, RecordKind.ATTEST, "Consent signed in app"),
            record(3, RecordKind.CAPTURE, "3 frames hashed"),
            record(4, RecordKind.CUSTODY, "Packet sealed"),
        )
        val badges = BadgeKey.requiredFor(JobType.ROOF_HAIL).map { key ->
            Badge(key = key, state = BadgeState.VERIFIED, required = true, rationale = "Evidence recorded.", evidenceRefs = emptyList())
        }
        return EvidencePacket(
            id = "FW-1048-PKT",
            jobId = "FW-1048",
            jobType = JobType.ROOF_HAIL,
            operatorId = operator.id,
            title = "Hail on the south slope · 1420 Creekside Bend",
            requesterLabel = "Creekside Mutual",
            site = job().site,
            consent = consent(),
            badges = badges,
            tier = EvidenceTier.VERIFIED_BY_AUCTUM,
            seal = SealState.EARNED,
            records = records,
            media = emptyList(),
            checklist = emptyList(),
            gates = gatesClear(),
            measurements = emptyList(),
            notes = "Owner present. South slope only.",
            demo = false,
            createdAtEpochMs = NOW - 3_000_000L,
            sealedAtEpochMs = NOW - 600_000L,
            merkleLeaf = records.last().hash,
            merkleProof = emptyList(),
            asOfEpochMs = NOW - 600_000L,
            signatureBase64 = "sig-chain",
        )
    }

    fun sealOutcome(): SealOutcome {
        val packet = packet()
        return SealOutcome(
            packet = packet,
            evaluation = SealEvaluation(
                state = SealState.EARNED,
                tier = EvidenceTier.VERIFIED_BY_AUCTUM,
                asOfEpochMs = NOW - 600_000L,
                gates = gatesClear(),
                reasons = emptyList(),
                packetHash = packet.packetHash,
            ),
            verification = ChainVerification(
                valid = true,
                entries = packet.records.size,
                brokenAtSeq = null,
                headHash = packet.packetHash ?: "",
                detail = "Chain verified from genesis to head.",
            ),
            badges = packet.badges,
        )
    }

    private fun baseState(phase: CapturePhase): CaptureState = CaptureState(
        isLoading = false,
        phase = phase,
        job = job(),
        operatorLabel = operator.displayName,
        operatorId = operator.id,
        consent = ConsentForm(
            scopeText = JobType.ROOF_HAIL.consentScope,
            subjectName = "Dana Whitlock",
            subjectRole = "Owner",
            method = ConsentMethod.IN_APP_SIGNATURE,
            typedSignature = "Dana Whitlock",
            confirmed = true,
            recorded = consent(),
        ),
        evidence = EvidenceState(steps = capturedSteps(simulated = false), activeStepKey = "square"),
        gates = GateReview(checks = gatesClear(), running = false, hasRun = true),
        seal = SealProgress(stage = SealStage.IDLE),
    )

    fun consentState(): CaptureState = baseState(CapturePhase.CONSENT).copy(
        phase = CapturePhase.CONSENT,
        consent = ConsentForm(
            scopeText = JobType.ROOF_HAIL.consentScope,
            subjectName = "",
            subjectRole = "",
            typedSignature = "",
            confirmed = false,
        ),
        evidence = EvidenceState(steps = emptyList(), activeStepKey = null),
    )

    fun consentSignedState(): CaptureState = baseState(CapturePhase.CONSENT).copy(phase = CapturePhase.CONSENT)

    fun evidenceState(simulated: Boolean = false): CaptureState = baseState(CapturePhase.EVIDENCE).copy(
        evidence = EvidenceState(
            steps = capturedSteps(simulated = simulated),
            activeStepKey = if (simulated) "slopes" else "square",
        ),
        cameraFailure = if (simulated) CameraFailure("Camera permission is not granted.", NOW) else null,
    )

    fun evidenceObservationState(): CaptureState = evidenceState().copy(
        evidence = evidenceState().evidence.copy(activeStepKey = "footprint"),
    )

    fun evidenceMeasurementState(): CaptureState = evidenceState(simulated = true).copy(
        evidence = evidenceState(simulated = true).evidence.copy(activeStepKey = "paddle"),
    )

    fun gatesBlockedState(): CaptureState = baseState(CapturePhase.GATES).copy(
        phase = CapturePhase.GATES,
        gates = GateReview(checks = gatesBlocked(), running = false, hasRun = true),
        banner = CaptureBanner(
            tone = BannerTone.WARNING,
            title = "2 blocking gates",
            detail = "Sealing stays disabled until consent is on file.",
        ),
    )

    fun gatesClearState(): CaptureState = baseState(CapturePhase.GATES).copy(phase = CapturePhase.GATES)

    fun gatesErrorState(): CaptureState = baseState(CapturePhase.GATES).copy(
        phase = CapturePhase.GATES,
        gates = GateReview(checks = emptyList(), running = false, hasRun = false, error = "Gates could not run: repository unavailable. Retry — sealing stays disabled until they pass."),
    )

    fun sealInProgressState(): CaptureState = baseState(CapturePhase.SEAL).copy(
        phase = CapturePhase.SEAL,
        seal = SealProgress(stage = SealStage.SIGNING),
    )

    fun sealedState(submitted: Boolean = false): CaptureState = baseState(CapturePhase.SEAL).copy(
        phase = CapturePhase.SEAL,
        seal = SealProgress(stage = SealStage.SEALED, outcome = sealOutcome(), submitted = submitted),
        banner = CaptureBanner(
            tone = BannerTone.INFO,
            title = "Packet sealed",
            detail = "Chain verified · 4 records · head 1f1f1f1f",
        ),
    )

    fun sealFailedState(): CaptureState = baseState(CapturePhase.SEAL).copy(
        phase = CapturePhase.SEAL,
        seal = SealProgress(stage = SealStage.FAILED, error = "Seal failed: consent is not on file. Nothing was sealed — fix the cause and retry."),
        banner = CaptureBanner(
            tone = BannerTone.ERROR,
            title = "The packet was not sealed",
            detail = "Consent is not on file.",
        ),
    )

    fun offlineState(): CaptureState = evidenceState().copy(
        syncPhase = SyncPhase.WAITING_FOR_NETWORK,
        queuedForSync = 3,
    )

    fun loadingState(): CaptureState = CaptureState()

    fun loadErrorState(): CaptureState = CaptureState(
        isLoading = false,
        loadError = "This job is no longer available. Go back to the job list and pick it again.",
        jobOpenForCapture = false,
    )

    private const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"
}
