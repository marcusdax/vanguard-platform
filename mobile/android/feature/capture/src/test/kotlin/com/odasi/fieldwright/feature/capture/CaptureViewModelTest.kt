package com.odasi.fieldwright.feature.capture

import com.odasi.fieldwright.feature.capture.capture.CaptureKind
import com.odasi.fieldwright.feature.capture.capture.CaptureRequest
import com.odasi.fieldwright.feature.capture.capture.CaptureSource
import com.odasi.fieldwright.feature.capture.capture.CapturedFrame
import com.odasi.fieldwright.feature.capture.capture.SimulatedCaptureSource
import com.odasi.fieldwright.feature.capture.measure.MeasurementReference
import com.odasi.fieldwright.feature.capture.measure.PixelPoint
import com.odasi.fieldwright.feature.capture.ui.CaptureSamples
import com.odasi.fieldwright.model.ConsentMethod
import com.odasi.fieldwright.model.FixedClock
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.MediaKind
import com.odasi.fieldwright.model.MediaRef
import com.odasi.fieldwright.model.MediaSource
import com.odasi.fieldwright.model.RedactionState
import com.odasi.fieldwright.model.StepResult
import com.odasi.fieldwright.model.SyncPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * State-machine tests for the capture flow.
 *
 * Coroutines run on `Dispatchers.Unconfined` with synchronous fakes, so each event completes
 * before the assertion — no test clocks or sleeps. What is asserted is the business decision
 * (a write did or did not happen), not only the rendered string.
 */
class CaptureViewModelTest {

    private val job = CaptureSamples.job()
    private val clock = FixedClock(1_763_000_000_000L)
    private lateinit var repos: FakeFieldwrightRepositories
    private lateinit var viewModel: CaptureViewModel

    @Before
    fun setUp() {
        repos = FakeFieldwrightRepositories(initialJob = job)
        viewModel = buildViewModel(repos)
    }

    private fun buildViewModel(repositories: FakeFieldwrightRepositories): CaptureViewModel = CaptureViewModel(
        repos = repositories,
        jobId = job.id,
        clock = clock,
        workScope = CoroutineScope(Dispatchers.Unconfined),
        sealStageDelayMillis = 0L,
    )

    private val simulatedSource: CaptureSource get() = SimulatedCaptureSource(
        reason = "No camera on this device.",
        shutterLatencyMillis = 0L,
    )

    private fun signConsent() {
        viewModel.onSubjectNameChange("Dana Whitlock")
        viewModel.onSubjectRoleChange("Owner")
        viewModel.onSignatureChange("Dana Whitlock")
        viewModel.onConsentConfirmedChange(true)
        viewModel.signConsent()
    }

    private fun continueToEvidence() {
        signConsent()
        viewModel.continueToEvidence()
    }

    private fun step(key: String) = viewModel.state.value.evidence.steps.first { it.key == key }

    private fun refreshGatesWith(checks: List<GateCheck>) {
        repos.captureRepo.gatesResult = Result.success(checks)
        viewModel.refreshGates()
    }

    // ---------------------------------------------------------------------------------
    // Loading and session start
    // ---------------------------------------------------------------------------------

    @Test
    fun `the job checklist becomes the evidence steps and the session opens once`() {
        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertEquals(job.id, state.job?.id)
        assertEquals(job.checklist.size, state.evidence.steps.size)
        assertEquals(job.checklist.map { it.key }, state.evidence.steps.map { it.key })
        assertEquals(CapturePhase.CONSENT, state.phase)
        assertEquals(1, repos.captureRepo.beginCalls)
        assertEquals(FakeFieldwrightRepositories.defaultOperator.displayName, state.operatorLabel)
    }

    @Test
    fun `offline sync state is surfaced as reassurance not as an error`() {
        val offlineRepos = FakeFieldwrightRepositories(initialJob = job)
        val offlineViewModel = buildViewModel(offlineRepos)
        assertFalse(offlineViewModel.state.value.isOffline)

        offlineRepos.syncRepo.setPhase(SyncPhase.WAITING_FOR_NETWORK, queued = 4)
        val state = offlineViewModel.state.value
        assertTrue(state.isOffline)
        assertEquals(4, state.queuedForSync)
        assertNull("Offline must not raise an error banner; the flow keeps working.", state.banner)
    }

    @Test
    fun `a session that cannot open is reported instead of hidden`() {
        val failingRepos = FakeFieldwrightRepositories(initialJob = job)
        failingRepos.captureRepo.beginFailure = IllegalStateException("storage is locked")
        val failingViewModel = buildViewModel(failingRepos)
        val banner = failingViewModel.state.value.banner
        assertEquals("Capture session did not open", banner?.title)
        assertTrue(banner?.detail?.contains("storage is locked") == true)
    }

    @Test
    fun `a missing job renders an honest load error`() {
        val missingRepos = FakeFieldwrightRepositories(initialJob = null)
        val missingViewModel = buildViewModel(missingRepos)
        assertNotNull(missingViewModel.state.value.loadError)
        assertFalse(missingViewModel.state.value.isLoading)
    }

    @Test
    fun `a job that is not open for capture is flagged`() {
        val sealedJob = job.copy(status = JobStatus.SEALED)
        val sealedRepos = FakeFieldwrightRepositories(initialJob = sealedJob)
        val sealedViewModel = buildViewModel(sealedRepos)
        assertFalse(sealedViewModel.state.value.jobOpenForCapture)
        assertEquals("This job is not open for capture", sealedViewModel.state.value.banner?.title)
        assertEquals(0, sealedRepos.captureRepo.beginCalls)
    }

    // ---------------------------------------------------------------------------------
    // Consent gate
    // ---------------------------------------------------------------------------------

    @Test
    fun `an incomplete consent form is refused with the missing thing named`() {
        viewModel.signConsent()
        assertTrue(repos.captureRepo.consentsRecorded.isEmpty())
        val error = requireNotNull(viewModel.state.value.consent.error)
        assertTrue(error.contains("full name"))
        assertFalse(viewModel.state.value.consent.isSigned)
    }

    @Test
    fun `signing records a consent with a signature digest bound to the scope`() {
        signConsent()
        val consent = repos.captureRepo.consentsRecorded.single()
        assertEquals("Dana Whitlock", consent.name)
        assertEquals("Owner", consent.role)
        assertEquals(job.consentScope, consent.scopeText)
        assertEquals(ConsentMethod.IN_APP_SIGNATURE, consent.method)
        assertEquals(clock.nowEpochMs(), consent.capturedAtEpochMs)
        assertEquals(FakeFieldwrightRepositories.defaultOperator.id, consent.verifiedByOperatorId)
        assertEquals(64, consent.signatureHash?.length)
        assertTrue(viewModel.state.value.consent.isSigned)
        assertFalse(viewModel.state.value.consent.editing)
    }

    @Test
    fun `the same consent inputs produce the same signature digest`() {
        signConsent()
        val first = repos.captureRepo.consentsRecorded.single().signatureHash
        val otherRepos = FakeFieldwrightRepositories(initialJob = job)
        val other = buildViewModel(otherRepos)
        other.onSubjectNameChange("Dana Whitlock")
        other.onSubjectRoleChange("Owner")
        other.onSignatureChange("Dana Whitlock")
        other.onConsentConfirmedChange(true)
        other.signConsent()
        assertEquals(first, otherRepos.captureRepo.consentsRecorded.single().signatureHash)
    }

    @Test
    fun `a different signature produces a different digest`() {
        signConsent()
        val first = repos.captureRepo.consentsRecorded.single().signatureHash
        viewModel.onCorrectConsent()
        viewModel.onSubjectNameChange("Dana Whitlock")
        viewModel.onSubjectRoleChange("Owner")
        viewModel.onSignatureChange("Dana W.")
        viewModel.onConsentConfirmedChange(true)
        viewModel.signConsent()
        assertEquals(2, repos.captureRepo.consentsRecorded.size)
        assertNotEquals(first, repos.captureRepo.consentsRecorded.last().signatureHash)
    }

    @Test
    fun `signing marks the consent proof step complete`() {
        signConsent()
        assertTrue(repos.captureRepo.completedSteps.contains("scope"))
        assertEquals(StepStatus.PASS, step("scope").status)
    }

    @Test
    fun `a consent that cannot be stored is not claimed as signed`() {
        repos.captureRepo.setConsentFailure = IllegalStateException("disk full")
        signConsent()
        val state = viewModel.state.value
        assertFalse(state.consent.isSigned)
        assertTrue(state.consent.error?.contains("disk full") == true)
        assertTrue(repos.captureRepo.consentsRecorded.isEmpty())
    }

    @Test
    fun `advancing without consent is blocked with a human reason`() {
        viewModel.continueToEvidence()
        assertEquals(CapturePhase.CONSENT, viewModel.state.value.phase)
        assertEquals("Consent is required", viewModel.state.value.banner?.title)
    }

    @Test
    fun `after signing the operator can enter evidence`() {
        continueToEvidence()
        assertEquals(CapturePhase.EVIDENCE, viewModel.state.value.phase)
    }

    @Test
    fun `editing an existing consent reopens the form without deleting the record`() {
        signConsent()
        viewModel.onCorrectConsent()
        assertTrue(viewModel.state.value.consent.editing)
        assertFalse(viewModel.state.value.consent.isSigned)
        viewModel.onCancelConsentCorrection()
        assertTrue(viewModel.state.value.consent.isSigned)
        assertEquals(1, repos.captureRepo.consentsRecorded.size)
    }

    // ---------------------------------------------------------------------------------
    // Evidence capture
    // ---------------------------------------------------------------------------------

    @Test
    fun `a simulated frame is hashed stored as SIMULATED media labelled and completes the step`() {
        continueToEvidence()
        viewModel.selectStep("slopes")
        viewModel.captureFrame(simulatedSource, "slopes", atEpochMs = clock.nowEpochMs())

        val stored = repos.captureRepo.mediaAdded.single()
        assertEquals(MediaSource.SIMULATED, stored.source)
        assertEquals(MediaKind.PHOTO, stored.kind)
        assertEquals(64, stored.sha256.length)
        assertEquals("media-${stored.sha256.take(16)}", stored.id)
        assertEquals(RedactionState.NONE, stored.redaction)
        assertTrue(stored.bytes > 0)
        assertEquals(640, stored.width)
        assertEquals(480, stored.height)

        val capturedStep = step("slopes")
        assertEquals(StepStatus.PASS, capturedStep.status)
        assertEquals(1, capturedStep.media.size)
        assertTrue(capturedStep.media.single().simulated)
        assertEquals(stored.sha256.take(8), capturedStep.media.single().shortHash)
        assertEquals(1, viewModel.state.value.evidence.simulatedFrameCount)
        assertTrue(viewModel.state.value.evidence.hasSimulatedFrames)
        assertTrue(repos.captureRepo.completedSteps.contains("slopes"))
    }

    @Test
    fun `capture is blocked until consent exists`() {
        viewModel.selectStep("slopes")
        viewModel.captureFrame(simulatedSource, "slopes", atEpochMs = clock.nowEpochMs())
        assertTrue(repos.captureRepo.mediaAdded.isEmpty())
        assertEquals("Consent is required", viewModel.state.value.banner?.title)
    }

    @Test
    fun `a camera failure is surfaced and switches the flow to the simulated fallback state`() {
        continueToEvidence()
        viewModel.selectStep("slopes")
        val failingCamera = object : CaptureSource {
            override val kind: CaptureKind = CaptureKind.CAMERA
            override val simulatedReason: String? = null
            override suspend fun capture(request: CaptureRequest): Result<CapturedFrame> =
                Result.failure(IllegalStateException("Camera is busy."))

            override fun release() = Unit
        }
        viewModel.captureFrame(failingCamera, "slopes", atEpochMs = clock.nowEpochMs())

        val state = viewModel.state.value
        val failure = requireNotNull(state.cameraFailure)
        assertTrue(failure.message.contains("Camera is busy."))
        assertTrue(step("slopes").error?.contains("camera could not capture") == true)
        assertTrue(repos.captureRepo.mediaAdded.isEmpty())
        assertEquals("Camera unavailable — switching to simulated frames", state.banner?.title)

        viewModel.acknowledgeCameraFailure()
        assertNull(viewModel.state.value.cameraFailure)
    }

    @Test
    fun `a failed media write does not leave a phantom thumbnail`() {
        continueToEvidence()
        repos.captureRepo.addMediaFailure = IllegalStateException("read-only database")
        viewModel.captureFrame(simulatedSource, "slopes", atEpochMs = clock.nowEpochMs())

        val capturedStep = step("slopes")
        assertTrue(capturedStep.media.isEmpty())
        assertEquals(StepStatus.OPEN, capturedStep.status)
        assertTrue(capturedStep.error?.contains("not stored") == true)
        assertTrue(capturedStep.error?.contains("read-only database") == true)
    }

    @Test
    fun `an observation needs its note before it is recorded`() {
        continueToEvidence()
        viewModel.selectStep("footprint")
        viewModel.confirmObservation("footprint")
        assertFalse(repos.captureRepo.completedSteps.contains("footprint"))
        assertTrue(step("footprint").error?.contains("observation note") == true)

        viewModel.onStepNoteChange("footprint", "South slope clear, no granule loss at the ridge.")
        viewModel.confirmObservation("footprint")
        assertTrue(repos.captureRepo.completedSteps.contains("footprint"))
        assertEquals(StepStatus.PASS, step("footprint").status)
    }

    // ---------------------------------------------------------------------------------
    // Measurement
    // ---------------------------------------------------------------------------------

    @Test
    fun `four taps produce a calibrated result that saves as a SIMULATED measurement`() {
        continueToEvidence()
        viewModel.selectStep("paddle")
        viewModel.onMeasurementReferenceChange("paddle", MeasurementReference.SITE_CARD)
        viewModel.onMeasurementTap("paddle", PixelPoint(120f, 200f))
        viewModel.onMeasurementTap("paddle", PixelPoint(228f, 200f))
        viewModel.onMeasurementTap("paddle", PixelPoint(180f, 260f))
        viewModel.onMeasurementTap("paddle", PixelPoint(396f, 260f))

        val draft = step("paddle").draft
        assertNotNull(draft.result)
        assertEquals(MeasurementReference.SITE_CARD, draft.reference)
        assertEquals(4, draft.tapCount)

        viewModel.onMeasurementLabelChange("paddle", "Hail strike")
        viewModel.saveMeasurement("paddle", simulated = true)

        val saved = repos.captureRepo.measurementsAdded.single()
        assertEquals("Hail strike", saved.label)
        assertEquals(0.30, saved.referenceMeters, 1e-9)
        assertEquals(MediaSource.SIMULATED, saved.source)
        assertEquals(0.60, saved.meters, 1e-9)
        assertTrue(saved.id.startsWith("meas-"))
        assertTrue(repos.captureRepo.completedSteps.contains("paddle"))
        assertEquals(MeasurementReference.SITE_CARD.label, saved.referenceLabel)
    }

    @Test
    fun `saving without taps is refused and nothing is written`() {
        continueToEvidence()
        viewModel.selectStep("paddle")
        viewModel.saveMeasurement("paddle", simulated = true)
        assertTrue(repos.captureRepo.measurementsAdded.isEmpty())
        assertTrue(step("paddle").error?.contains("Tap both ends") == true)
    }

    @Test
    fun `degenerate taps do not produce a result`() {
        continueToEvidence()
        viewModel.selectStep("paddle")
        viewModel.onMeasurementTap("paddle", PixelPoint(100f, 100f))
        viewModel.onMeasurementTap("paddle", PixelPoint(102f, 100f))
        assertNull(step("paddle").draft.result)
    }

    @Test
    fun `a measurement that cannot be stored is not reported as saved`() {
        continueToEvidence()
        repos.captureRepo.addMeasurementFailure = IllegalStateException("database is locked")
        viewModel.selectStep("paddle")
        viewModel.onMeasurementTap("paddle", PixelPoint(120f, 200f))
        viewModel.onMeasurementTap("paddle", PixelPoint(228f, 200f))
        viewModel.onMeasurementTap("paddle", PixelPoint(180f, 260f))
        viewModel.onMeasurementTap("paddle", PixelPoint(396f, 260f))
        viewModel.saveMeasurement("paddle", simulated = true)
        assertNull(step("paddle").savedMeasurement)
        assertEquals(StepStatus.OPEN, step("paddle").status)
        assertTrue(step("paddle").error?.contains("database is locked") == true)
    }

    // ---------------------------------------------------------------------------------
    // Gates review
    // ---------------------------------------------------------------------------------

    @Test
    fun `the gates cannot run while a required step is open`() {
        continueToEvidence()
        viewModel.goToGates()
        assertEquals(CapturePhase.EVIDENCE, viewModel.state.value.phase)
        assertEquals(0, repos.captureRepo.gatesCalls)
        assertEquals("Finish the checklist first", viewModel.state.value.banner?.title)
    }

    @Test
    fun `a blocking gate failure disables sealing and no seal is attempted`() {
        signConsent()
        refreshGatesWith(CaptureSamples.gatesBlocked())
        assertFalse(viewModel.state.value.gates.canSeal)
        assertEquals(1, viewModel.state.value.gates.blockingFailures.size)

        viewModel.sealPacket()
        assertEquals(0, repos.captureRepo.sealCalls)
        assertEquals(CapturePhase.GATES, viewModel.state.value.phase)
        assertEquals("Blocking gates are still failing", viewModel.state.value.banner?.title)
    }

    @Test
    fun `a non-blocking note does not prevent sealing`() {
        signConsent()
        refreshGatesWith(CaptureSamples.gatesBlocked().map { gate -> if (gate.blocking) gate.copy(passed = true) else gate })
        assertTrue(viewModel.state.value.gates.canSeal)
        assertTrue(viewModel.state.value.gates.nonBlockingFailures.isNotEmpty())
    }

    @Test
    fun `running gates reports a repository failure instead of pretending to pass`() {
        repos.captureRepo.gatesResult = Result.failure(IllegalStateException("gate engine offline"))
        viewModel.refreshGates()
        val gates = viewModel.state.value.gates
        assertFalse(gates.canSeal)
        assertFalse(gates.hasRun)
        assertTrue(gates.error?.contains("gate engine offline") == true)
    }

    @Test
    fun `a consent gate routes back to the consent phase without deleting the record`() {
        signConsent()
        refreshGatesWith(CaptureSamples.gatesBlocked())
        val consentGate = viewModel.state.value.gates.blockingFailures.first { it.id.contains("consent") }
        viewModel.routeToFix(consentGate)
        assertEquals(CapturePhase.CONSENT, viewModel.state.value.phase)
        assertNotNull(
            "Routing back to fix consent must keep the existing consent record visible.",
            viewModel.state.value.consent.recorded,
        )
        assertTrue(viewModel.state.value.consent.editing)
    }

    @Test
    fun `a geofence gate stays visible in the review and points at the job screen`() {
        // A completed session lets the operator reach the gates review, which is where a
        // geofence failure has to be shown: it must never be swallowed by the capture flow.
        val allComplete = job.checklist.associate { definition ->
            definition.key to stepResult(definition.key, StepResult.PASS, "recorded")
        }
        val work = workWith(
            jobId = job.id,
            checklist = allComplete,
            consent = CaptureSamples.consent(),
        )
        val reviewRepos = FakeFieldwrightRepositories(initialJob = job, initialWork = work)
        val reviewViewModel = buildViewModel(reviewRepos)
        reviewViewModel.goToGates()
        assertEquals(CapturePhase.GATES, reviewViewModel.state.value.phase)

        val geofenceGate = GateCheck(
            id = "geofence",
            label = "Geofence satisfied",
            passed = false,
            detail = "Operator fix 340 m from site centre.",
            blocking = true,
        )
        reviewRepos.captureRepo.gatesResult = Result.success(listOf(geofenceGate))
        reviewViewModel.refreshGates()

        val gates = reviewViewModel.state.value.gates
        assertFalse(gates.canSeal)
        assertEquals(1, gates.blockingFailures.size)
        assertTrue(gates.failures.any { it.id == "geofence" })

        reviewViewModel.routeToFix(geofenceGate)
        assertEquals(CapturePhase.GATES, reviewViewModel.state.value.phase)
        assertEquals("Resolve the site check on the job screen", reviewViewModel.state.value.banner?.title)
    }

    // ---------------------------------------------------------------------------------
    // Seal
    // ---------------------------------------------------------------------------------

    @Test
    fun `sealing without consent is refused before the repository is called`() {
        refreshGatesWith(FakeCaptureRepository.passingGates())
        viewModel.sealPacket()
        assertEquals(0, repos.captureRepo.sealCalls)
        assertEquals(CapturePhase.CONSENT, viewModel.state.value.phase)
        assertEquals("No consent on file", viewModel.state.value.banner?.title)
    }

    @Test
    fun `a successful seal renders the hero state from the repository result`() {
        signConsent()
        refreshGatesWith(FakeCaptureRepository.passingGates())
        val outcome = CaptureSamples.sealOutcome()
        repos.captureRepo.sealResult = Result.success(outcome)
        viewModel.sealPacket()

        val state = viewModel.state.value
        assertEquals(CapturePhase.SEAL, state.phase)
        assertEquals(SealStage.SEALED, state.seal.stage)
        assertTrue(state.seal.isSealed)
        assertEquals(outcome.packet.id, state.seal.packetId)
        assertEquals(outcome.packet.shortHash, state.seal.shortHash)
        assertNotNull(state.seal.outcome)
        assertEquals(1, repos.captureRepo.sealCalls)
        assertEquals("Packet sealed", state.banner?.title)
    }

    @Test
    fun `a seal failure is reported honestly and nothing is claimed sealed`() {
        signConsent()
        refreshGatesWith(FakeCaptureRepository.passingGates())
        repos.captureRepo.sealResult = Result.failure(IllegalStateException("consent is not on file"))
        viewModel.sealPacket()

        val state = viewModel.state.value
        assertEquals(SealStage.FAILED, state.seal.stage)
        assertFalse(state.seal.isSealed)
        assertNull(state.seal.outcome)
        assertTrue(state.seal.error?.contains("consent is not on file") == true)
        assertEquals(1, repos.captureRepo.sealCalls)
        assertTrue("Gates must be re-run after a seal failure.", repos.captureRepo.gatesCalls >= 2)
    }

    @Test
    fun `a repository outcome without a passing evaluation is not treated as sealed`() {
        signConsent()
        refreshGatesWith(FakeCaptureRepository.passingGates())
        val notSealed = CaptureSamples.sealOutcome().let { outcome ->
            outcome.copy(
                packet = outcome.packet.copy(seal = com.odasi.fieldwright.model.SealState.PENDING),
                evaluation = outcome.evaluation.copy(
                    state = com.odasi.fieldwright.model.SealState.PENDING,
                    reasons = listOf("Tier is not 5: required badges are missing."),
                ),
            )
        }
        repos.captureRepo.sealResult = Result.success(notSealed)
        viewModel.sealPacket()

        val state = viewModel.state.value
        assertEquals(SealStage.FAILED, state.seal.stage)
        assertFalse(state.seal.isSealed)
        assertTrue(state.seal.error?.contains("required badges") == true)
        assertEquals("The packet was not sealed", state.banner?.title)
    }

    @Test
    fun `submitting a sealed packet marks it queued and keeps the outcome`() {
        signConsent()
        refreshGatesWith(FakeCaptureRepository.passingGates())
        val outcome = CaptureSamples.sealOutcome()
        repos.captureRepo.sealResult = Result.success(outcome)
        viewModel.sealPacket()

        viewModel.submitPacket()
        val seal = viewModel.state.value.seal
        assertTrue(seal.submitted)
        assertEquals(outcome.packet.id, seal.packetId)
        assertEquals(1, repos.captureRepo.sealCalls)

        viewModel.submitPacket() // idempotent
        assertEquals(1, repos.captureRepo.sealCalls)
    }

    @Test
    fun `the primary gate action fixes a blocking gate instead of sealing`() {
        signConsent()
        refreshGatesWith(CaptureSamples.gatesBlocked())
        viewModel.primaryGateAction()
        assertEquals(0, repos.captureRepo.sealCalls)
        assertEquals(CapturePhase.CONSENT, viewModel.state.value.phase)
    }

    // ---------------------------------------------------------------------------------
    // Hydration from persisted work
    // ---------------------------------------------------------------------------------

    @Test
    fun `persisted work hydrates completed steps and their media without local bytes`() {
        val persistedMedia = MediaRef(
            id = "media-abc",
            kind = MediaKind.PHOTO,
            sha256 = "a".repeat(64),
            bytes = 12_345,
            capturedAtEpochMs = clock.nowEpochMs(),
            width = 640,
            height = 480,
            redaction = RedactionState.NONE,
            source = MediaSource.CAMERA,
        )
        val work = workWith(
            jobId = job.id,
            checklist = checklistOf(
                stepResult("slopes", StepResult.PASS, "Sky out of frame.", mediaIds = listOf("media-abc")),
                stepResult("scope", StepResult.PASS, "Signed by operator"),
            ),
            media = listOf(persistedMedia),
            consent = CaptureSamples.consent(),
        )
        val hydratedRepos = FakeFieldwrightRepositories(initialJob = job, initialWork = work)
        val hydrated = buildViewModel(hydratedRepos)

        val state = hydrated.state.value
        assertEquals(0, hydratedRepos.captureRepo.beginCalls)
        assertEquals(StepStatus.PASS, state.evidence.steps.first { it.key == "slopes" }.status)
        assertTrue(state.consent.isSigned)
        val media = state.evidence.steps.first { it.key == "slopes" }.media.single()
        assertEquals("media-abc", media.mediaId)
        assertFalse(media.simulated)
        assertEquals("A re-hydrated reference has no in-memory bytes.", 0, media.bytes.size)
    }
}
