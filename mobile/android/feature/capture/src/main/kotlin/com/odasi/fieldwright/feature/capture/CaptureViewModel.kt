package com.odasi.fieldwright.feature.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odasi.fieldwright.data.FieldwrightRepositories
import com.odasi.fieldwright.feature.capture.capture.CaptureKind
import com.odasi.fieldwright.feature.capture.capture.CaptureRequest
import com.odasi.fieldwright.feature.capture.capture.CaptureSource
import com.odasi.fieldwright.feature.capture.capture.CapturedFrame
import com.odasi.fieldwright.feature.capture.crypto.CaptureDigests
import com.odasi.fieldwright.feature.capture.crypto.Sha256
import com.odasi.fieldwright.feature.capture.measure.PixelPoint
import com.odasi.fieldwright.feature.capture.measure.MeasurementReference
import com.odasi.fieldwright.model.ActiveWork
import com.odasi.fieldwright.model.Clock
import com.odasi.fieldwright.model.Consent
import com.odasi.fieldwright.model.ConsentMethod
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.HashAlgorithm
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.MediaKind
import com.odasi.fieldwright.model.MediaRef
import com.odasi.fieldwright.model.MediaSource
import com.odasi.fieldwright.model.Operator
import com.odasi.fieldwright.model.ProofKind
import com.odasi.fieldwright.model.RedactionState
import com.odasi.fieldwright.model.StepResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The capture flow state machine: consent → evidence → gates → seal.
 *
 * It owns every decision the flow makes (what is blocking, when sealing is allowed, what a
 * failure means) and talks to the data layer only through `FieldwrightRepositories`. The
 * screen is stateless and the camera is behind `CaptureSource`, so this class is testable
 * without Android and without a camera.
 *
 * Business rules intentionally implemented here, not in the UI:
 * - **No consent, no capture/no seal.** Signing produces a `core:model Consent` whose
 *   signature hash binds the typed signature to the job's scope, method and time.
 * - **Simulated frames are labelled.** A frame's `CaptureKind`/reason is copied into the
 *   screen state and every thumbnail; nothing can present a simulated frame as a photo.
 * - **Only blocking gate failures prevent sealing** (§5.1). Non-blocking notes are shown.
 * - **Success is reported only when the repository confirms it.** The staged progress
 *   narration is cosmetic; the hero appears only after `capture.seal` returns a sealed
 *   packet with a passing evaluation.
 *
 * @param workScope injected for unit tests; production uses [viewModelScope].
 * @param sealStageDelayMillis the cosmetic beat between narrated seal stages; 0 in tests.
 */
class CaptureViewModel(
    private val repos: FieldwrightRepositories,
    private val jobId: String,
    private val clock: Clock = Clock { System.currentTimeMillis() },
    private val hasher: HashAlgorithm = Sha256,
    workScope: CoroutineScope? = null,
    private val sealStageDelayMillis: Long = 360L,
) : ViewModel() {

    private val scope: CoroutineScope = workScope ?: viewModelScope

    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private var loadedJob: Job? = null
    private var loadedWork: ActiveWork? = null
    private var loadedOperator: Operator? = null
    private var jobEmitted: Boolean = false
    private var workEmitted: Boolean = false
    private var beganCapture: Boolean = false
    private var closedJobNoted: Boolean = false
    private var sealJob: CoroutineJob? = null

    init {
        scope.launch { repos.jobs.observeJob(jobId).collect(::onJobEmitted) }
        scope.launch { repos.capture.observeWork(jobId).collect(::onWorkEmitted) }
        scope.launch { repos.operator.observeOperator().collect(::onOperatorEmitted) }
        // Offline-first reassurance: the seal writes locally and the outbox uploads later.
        scope.launch {
            repos.sync.observeSyncState().collect { sync ->
                _state.update { it.copy(syncPhase = sync.phase, queuedForSync = sync.queued) }
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Repository projections
    // ---------------------------------------------------------------------------------

    private fun onJobEmitted(job: Job?) {
        jobEmitted = true
        loadedJob = job
        if (job == null) {
            _state.update {
                it.copy(
                    isLoading = false,
                    loadError = "This job is no longer available. Go back to the job list and pick it again.",
                    jobOpenForCapture = false,
                )
            }
            return
        }
        publishDerived()
        // Only open a session once the work stream has spoken: opening one when a session
        // already exists would be a pointless write. The work collector calls back here too.
        if (workEmitted) maybeBeginCapture()
        noteClosedJobOnce(job)
    }

    private fun onWorkEmitted(work: ActiveWork?) {
        workEmitted = true
        loadedWork = work
        publishDerived()
        maybeBeginCapture()
    }

    private fun onOperatorEmitted(operator: Operator) {
        loadedOperator = operator
        publishDerived()
    }

    /** Rebuilds job/operator/checklist/consent projections without discarding session edits. */
    private fun publishDerived() {
        val job = loadedJob ?: return
        val work = loadedWork
        val operator = loadedOperator
        _state.update { current ->
            val steps = mergeSteps(job, work, current.evidence)
            val activeKey = current.evidence.activeStepKey
                ?.takeIf { key -> steps.any { it.key == key } }
                ?: steps.firstOrNull { !it.complete }?.key
                ?: steps.firstOrNull()?.key
            current.copy(
                isLoading = false,
                job = job,
                loadError = null,
                operatorLabel = operator?.displayName ?: current.operatorLabel,
                operatorId = operator?.id ?: current.operatorId,
                jobOpenForCapture = job.status.isOpenForCapture,
                consent = current.consent.copy(
                    scopeText = job.consentScope,
                    recorded = current.consent.recorded ?: work?.consent,
                ),
                evidence = current.evidence.copy(steps = steps, activeStepKey = activeKey),
            )
        }
    }

    /**
     * Opens the capture session exactly once. Failure is surfaced, not swallowed: if the
     * repositories cannot open the session the operator must know that frames might not
     * persist.
     */
    private fun maybeBeginCapture() {
        val job = loadedJob ?: return
        if (!workEmitted) return
        if (beganCapture || loadedWork != null) return
        if (!job.status.isOpenForCapture) return
        beganCapture = true
        scope.launch {
            repos.capture.begin(jobId, clock.nowEpochMs()).onFailure { error ->
                _state.update {
                    it.copy(
                        banner = CaptureBanner(
                            tone = BannerTone.WARNING,
                            title = "Capture session did not open",
                            detail = "${error.humanMessage()} You can still work, but frames may not persist. Retry by leaving and reopening capture.",
                        ),
                    )
                }
            }
        }
    }

    private fun noteClosedJobOnce(job: Job) {
        if (job.status.isOpenForCapture || closedJobNoted) return
        closedJobNoted = true
        _state.update {
            it.copy(
                jobOpenForCapture = false,
                banner = CaptureBanner(
                    tone = BannerTone.WARNING,
                    title = "This job is not open for capture",
                    detail = "The job status is ${job.status.label}. Only an on-site or in-progress job can take new evidence.",
                ),
            )
        }
    }

    private fun mergeSteps(job: Job, work: ActiveWork?, evidence: EvidenceState): List<CaptureStepState> {
        val previous = evidence.steps.associateBy { it.key }
        return job.checklist.map { definition ->
            val existing = previous[definition.key]
            val persisted = work?.checklist?.get(definition.key)
            val persistedStatus = when (persisted?.result) {
                StepResult.PASS -> StepStatus.PASS
                StepResult.NOT_APPLICABLE -> StepStatus.PASS
                StepResult.FAIL -> StepStatus.FAILED
                else -> StepStatus.OPEN
            }
            val status = when {
                existing == null -> persistedStatus
                existing.status == StepStatus.PASS -> StepStatus.PASS
                persistedStatus == StepStatus.PASS -> StepStatus.PASS
                else -> existing.status
            }
            val persistedMediaIds = persisted?.mediaIds.orEmpty().toSet()
            val mediaFromWork = work?.media.orEmpty()
                .filter { it.id in persistedMediaIds }
                .map { it.toCapturedMediaState() }
            val mergedNote = existing?.note?.takeIf { it.isNotBlank() } ?: persisted?.note.orEmpty()
            val base = existing ?: CaptureStepState(
                key = definition.key,
                label = definition.label,
                hint = definition.hint,
                proofKind = definition.proofKind,
                required = definition.required,
            )
            base.copy(
                label = definition.label,
                hint = definition.hint,
                proofKind = definition.proofKind,
                required = definition.required,
                status = status,
                note = mergedNote,
                media = (base.media + mediaFromWork).distinctBy { it.mediaId },
            )
        }
    }

    // ---------------------------------------------------------------------------------
    // Consent gate
    // ---------------------------------------------------------------------------------

    fun onSubjectNameChange(value: String) = updateConsentForm { it.copy(subjectName = value, error = null) }

    fun onSubjectRoleChange(value: String) = updateConsentForm { it.copy(subjectRole = value, error = null) }

    fun onSignatureChange(value: String) = updateConsentForm { it.copy(typedSignature = value, error = null) }

    fun onConsentMethodChange(method: ConsentMethod) = updateConsentForm { it.copy(method = method, error = null) }

    fun onConsentConfirmedChange(confirmed: Boolean) = updateConsentForm { it.copy(confirmed = confirmed, error = null) }

    /** Reopens an already-recorded consent for correction (recorded as a new consent). */
    fun onCorrectConsent() = updateConsentForm { it.copy(editing = true, error = null) }

    fun onCancelConsentCorrection() = updateConsentForm { it.copy(editing = false, error = null) }

    private fun updateConsentForm(transform: (ConsentForm) -> ConsentForm) {
        _state.update { current ->
            if (current.consent.isSaving && !current.consent.isSigned) current
            else current.copy(consent = transform(current.consent))
        }
    }

    /** Records the typed signature as a `core:model Consent`. No consent → no seal. */
    fun signConsent() {
        val current = _state.value
        val job = loadedJob ?: return
        if (!current.jobOpenForCapture) {
            banner(BannerTone.WARNING, "This job is not open for capture", "The job status is ${job.status.label}.")
            return
        }
        if (current.consent.isSaving || current.consent.isSigned) return
        val validation = current.consent.validationMessage()
        if (validation != null) {
            updateConsentForm { it.copy(error = validation) }
            return
        }
        val operator = loadedOperator ?: run {
            banner(
                BannerTone.WARNING,
                "Operator profile not loaded",
                "The consent record must name the operator who attested it. This screen will retry when the profile arrives.",
            )
            return
        }
        updateConsentForm { it.copy(isSaving = true, error = null) }
        scope.launch {
            val now = clock.nowEpochMs()
            val form = _state.value.consent
            val consent = Consent(
                name = form.subjectName.trim(),
                role = form.subjectRole.trim(),
                scopeText = job.consentScope,
                method = form.method,
                capturedAtEpochMs = now,
                signatureHash = CaptureDigests.consentSignatureHash(
                    hasher = hasher,
                    subjectName = form.subjectName,
                    subjectRole = form.subjectRole,
                    scopeText = job.consentScope,
                    method = form.method,
                    typedSignature = form.typedSignature,
                    capturedAtEpochMs = now,
                ),
                verifiedByOperatorId = operator.id,
            )
            repos.capture.setConsent(jobId, consent).fold(
                onSuccess = { work ->
                    loadedWork = work
                    _state.update {
                        it.copy(
                            consent = it.consent.copy(
                                recorded = consent,
                                isSaving = false,
                                editing = false,
                                error = null,
                                typedSignature = "",
                            ),
                            banner = CaptureBanner(
                                tone = BannerTone.INFO,
                                title = "Consent recorded",
                                detail = "Signed by operator · ${operator.displayName}. The scope shown is the job's scope; it cannot be edited here.",
                            ),
                        )
                    }
                    publishDerived()
                    completeConsentProofSteps(job)
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            consent = it.consent.copy(
                                isSaving = false,
                                error = "Consent was not saved: ${error.humanMessage()}. The signature is not on file — retry before capturing.",
                            ),
                        )
                    }
                },
            )
        }
    }

    /** Marks checklist steps whose proof is the consent itself. */
    private suspend fun completeConsentProofSteps(job: Job) {
        for (step in job.checklist.filter { it.proofKind == ProofKind.CONSENT }) {
            repos.capture.completeStep(jobId, step.key, StepResult.PASS, CONSENT_STEP_NOTE, emptyList()).fold(
                onSuccess = { work ->
                    loadedWork = work
                    updateStep(step.key) { it.copy(status = StepStatus.PASS, error = null) }
                    publishDerived()
                },
                onFailure = { error ->
                    updateStep(step.key) {
                        it.copy(
                            status = StepStatus.OPEN,
                            error = "Consent is on file, but this checklist step was not recorded: ${error.humanMessage()}.",
                        )
                    }
                },
            )
        }
    }

    fun continueToEvidence() {
        val current = _state.value
        val job = loadedJob ?: return
        if (!current.jobOpenForCapture) {
            banner(BannerTone.WARNING, "This job is not open for capture", "The job status is ${job.status.label}.")
            return
        }
        if (current.consent.recorded == null && loadedWork?.consent == null) {
            banner(
                BannerTone.ERROR,
                "Consent is required",
                "No consent is on file. Capture cannot start until the subject has read and signed the scope.",
            )
            return
        }
        _state.update { it.copy(phase = CapturePhase.EVIDENCE, banner = null) }
    }

    // ---------------------------------------------------------------------------------
    // Evidence capture
    // ---------------------------------------------------------------------------------

    fun selectStep(stepKey: String) {
        _state.update { it.copy(evidence = it.evidence.copy(activeStepKey = stepKey, error = null)) }
    }

    fun onStepNoteChange(stepKey: String, note: String) {
        updateStep(stepKey) { it.copy(note = note) }
    }

    /**
     * Takes one frame through the supplied source, hashes it and persists it. One shot per
     * call; the source decides camera vs simulated, the ViewModel never assumes which.
     */
    fun captureFrame(source: CaptureSource, stepKey: String, atEpochMs: Long = clock.nowEpochMs()) {
        val current = _state.value
        val job = loadedJob ?: return
        val step = current.evidence.steps.firstOrNull { it.key == stepKey } ?: return
        if (step.proofKind != ProofKind.PHOTO) return
        if (current.evidence.isBusy) return
        if (current.consent.recorded == null && loadedWork?.consent == null) {
            banner(BannerTone.ERROR, "Consent is required", "Capture is blocked until the consent gate is signed.")
            return
        }
        updateStep(stepKey) { it.copy(busy = true, error = null) }
        scope.launch {
            val request = CaptureRequest(
                jobId = job.id,
                siteLabel = siteLabel(job),
                stepKey = stepKey,
                stepLabel = step.label,
                capturedAtEpochMs = atEpochMs,
            )
            source.capture(request).fold(
                onSuccess = { frame -> persistFrame(stepKey, frame) },
                onFailure = { error -> onCaptureFailed(stepKey, source, error) },
            )
        }
    }

    private suspend fun persistFrame(stepKey: String, frame: CapturedFrame) {
        val step = _state.value.evidence.steps.firstOrNull { it.key == stepKey } ?: return
        val sha256 = hasher.sha256Hex(frame.bytes)
        val media = MediaRef(
            id = CaptureDigests.mediaId(sha256),
            kind = MediaKind.PHOTO,
            sha256 = sha256,
            bytes = frame.bytes.size.toLong(),
            capturedAtEpochMs = frame.capturedAtEpochMs,
            width = frame.widthPx,
            height = frame.heightPx,
            redaction = RedactionState.NONE,
            source = frame.mediaSource,
            note = step.note.trim(),
        )
        // The in-memory bytes are kept for the thumbnail; the evidence is the hash above.
        val captured = CapturedMediaState(
            mediaId = media.id,
            sha256 = sha256,
            bytes = frame.bytes,
            widthPx = frame.widthPx,
            heightPx = frame.heightPx,
            capturedAtEpochMs = frame.capturedAtEpochMs,
            simulated = frame.isSimulated,
            simulatedReason = frame.simulatedReason,
            mimeType = frame.mimeType,
        )
        updateStep(stepKey) {
            it.copy(media = (it.media + captured).distinctBy { existing -> existing.mediaId }, busy = true, error = null)
        }
        repos.capture.addMedia(jobId, media, frame.capturedAtEpochMs).fold(
            onSuccess = { work ->
                loadedWork = work
                publishDerived()
                val note = _state.value.evidence.steps.firstOrNull { it.key == stepKey }?.note.orEmpty()
                repos.capture.completeStep(jobId, stepKey, StepResult.PASS, note.trim(), listOf(media.id)).fold(
                    onSuccess = { updated ->
                        loadedWork = updated
                        updateStep(stepKey) { it.copy(status = StepStatus.PASS, busy = false, error = null) }
                        publishDerived()
                    },
                    onFailure = { error ->
                        updateStep(stepKey) {
                            it.copy(
                                busy = false,
                                error = "Frame saved and hashed, but the step was not marked complete: ${error.humanMessage()}. Try capture again.",
                            )
                        }
                    },
                )
            },
            onFailure = { error ->
                updateStep(stepKey) {
                    it.copy(
                        media = it.media.filterNot { existing -> existing.mediaId == media.id },
                        busy = false,
                        error = "Frame hashed (${sha256.take(8)}) but not stored: ${error.humanMessage()}. Retake when storage is available.",
                    )
                }
            },
        )
    }

    private fun onCaptureFailed(stepKey: String, source: CaptureSource, error: Throwable) {
        val message = error.humanMessage()
        updateStep(stepKey) {
            it.copy(
                busy = false,
                error = if (source.isSimulated) {
                    "Simulated frame failed: $message"
                } else {
                    "The camera could not capture a frame: $message"
                },
            )
        }
        if (source.kind == CaptureKind.CAMERA) {
            _state.update {
                it.copy(
                    cameraFailure = CameraFailure(message = message, atEpochMs = clock.nowEpochMs()),
                    banner = CaptureBanner(
                        tone = BannerTone.WARNING,
                        title = "Camera unavailable — switching to simulated frames",
                        detail = "No camera frame was captured. Simulated frames are clearly labelled and must not be sold as camera evidence.",
                    ),
                )
            }
        } else {
            banner(BannerTone.ERROR, "Capture failed", message)
        }
    }

    /** Confirms an observation step. The note *is* the evidence, so it is mandatory. */
    fun confirmObservation(stepKey: String) {
        val step = _state.value.evidence.steps.firstOrNull { it.key == stepKey } ?: return
        if (step.proofKind != ProofKind.OBSERVATION) return
        if (step.note.isBlank()) {
            updateStep(stepKey) {
                it.copy(error = "Add a one-line observation note first — the note is the evidence for this step.")
            }
            return
        }
        if (step.busy) return
        updateStep(stepKey) { it.copy(busy = true, error = null) }
        scope.launch {
            repos.capture.completeStep(jobId, stepKey, StepResult.PASS, step.note.trim(), emptyList()).fold(
                onSuccess = { work ->
                    loadedWork = work
                    updateStep(stepKey) { it.copy(status = StepStatus.PASS, busy = false, error = null) }
                    publishDerived()
                },
                onFailure = { error ->
                    updateStep(stepKey) {
                        it.copy(busy = false, error = "Observation was not recorded: ${error.humanMessage()}")
                    }
                },
            )
        }
    }

    // ---------------------------------------------------------------------------------
    // Measurement tool
    // ---------------------------------------------------------------------------------

    fun onMeasurementReferenceChange(stepKey: String, reference: MeasurementReference) {
        updateStep(stepKey) { it.copy(draft = it.draft.withReference(reference), error = null) }
    }

    fun onMeasurementTap(stepKey: String, point: PixelPoint) {
        updateStep(stepKey) {
            if (it.busy) it else it.copy(draft = it.draft.withTap(point))
        }
    }

    fun onMeasurementLabelChange(stepKey: String, label: String) {
        updateStep(stepKey) { it.copy(draft = it.draft.copy(label = label)) }
    }

    fun resetMeasurement(stepKey: String) {
        updateStep(stepKey) { it.copy(draft = it.draft.resetTaps(), error = null) }
    }

    /**
     * Persists a calibrated measurement. [simulated] must be the kind of the source that
     * produced the frame the taps were taken on; a simulated measurement is labelled as such.
     */
    fun saveMeasurement(stepKey: String, simulated: Boolean) {
        val step = _state.value.evidence.steps.firstOrNull { it.key == stepKey } ?: return
        if (step.proofKind != ProofKind.MEASUREMENT) return
        if (step.busy) return
        val result = step.draft.result ?: run {
            updateStep(stepKey) {
                it.copy(error = "Tap both ends of the reference and both ends of the target before saving.")
            }
            return
        }
        val now = clock.nowEpochMs()
        val label = step.draft.label.trim().ifBlank { "Measured span · ${step.label}" }
        val measurement = result.toModel(
            id = CaptureDigests.measurementId(
                label = label,
                meters = result.metres,
                referenceLabel = result.reference.label,
                referenceMeters = result.reference.meters,
                pixelSpan = result.measuredSpanPx,
                calibratedAtEpochMs = now,
            ),
            label = label,
            calibratedAtEpochMs = now,
            source = if (simulated) MediaSource.SIMULATED else MediaSource.CAMERA,
        )
        updateStep(stepKey) { it.copy(busy = true, error = null) }
        scope.launch {
            repos.capture.addMeasurement(jobId, measurement).fold(
                onSuccess = { work ->
                    loadedWork = work
                    updateStep(stepKey) { it.copy(savedMeasurement = measurement, status = StepStatus.PASS) }
                    publishDerived()
                    val note = _state.value.evidence.steps.firstOrNull { it.key == stepKey }?.note.orEmpty()
                    repos.capture.completeStep(jobId, stepKey, StepResult.PASS, note.trim(), emptyList()).fold(
                        onSuccess = { updated ->
                            loadedWork = updated
                            updateStep(stepKey) { it.copy(status = StepStatus.PASS, busy = false, error = null) }
                            publishDerived()
                        },
                        onFailure = { error ->
                            updateStep(stepKey) {
                                it.copy(
                                    busy = false,
                                    error = "Measurement saved, but the step was not marked complete: ${error.humanMessage()}.",
                                )
                            }
                        },
                    )
                },
                onFailure = { error ->
                    updateStep(stepKey) {
                        it.copy(busy = false, error = "Measurement was not saved: ${error.humanMessage()}")
                    }
                },
            )
        }
    }

    // ---------------------------------------------------------------------------------
    // Gates review
    // ---------------------------------------------------------------------------------

    fun goToGates() {
        val current = _state.value
        if (!current.requiredStepsComplete) {
            val next = current.evidence.steps.firstOrNull { it.required && !it.complete }
            _state.update {
                it.copy(
                    banner = CaptureBanner(
                        tone = BannerTone.WARNING,
                        title = "Finish the checklist first",
                        detail = "Every required step needs its proof before the gates can run.",
                    ),
                    evidence = it.evidence.copy(activeStepKey = next?.key ?: it.evidence.activeStepKey),
                )
            }
            return
        }
        _state.update { it.copy(phase = CapturePhase.GATES, banner = null) }
        refreshGates()
    }

    fun refreshGates() {
        if (_state.value.gates.running) return
        _state.update { it.copy(gates = it.gates.copy(running = true, error = null)) }
        scope.launch {
            repos.capture.runGates(jobId, clock.nowEpochMs()).fold(
                onSuccess = { checks ->
                    _state.update {
                        it.copy(gates = GateReview(checks = checks, running = false, hasRun = true))
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            gates = it.gates.copy(
                                running = false,
                                hasRun = false,
                                error = "Gates could not run: ${error.humanMessage()}. Retry — sealing stays disabled until they pass.",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun backToEvidence() {
        _state.update { it.copy(phase = CapturePhase.EVIDENCE, banner = null) }
    }

    fun backToGates() {
        _state.update { it.copy(phase = CapturePhase.GATES, banner = null) }
        refreshGates()
    }

    /** Reopens the consent phase from evidence/gates to review or correct the record. */
    fun openConsentForEdit() {
        _state.update {
            it.copy(
                phase = CapturePhase.CONSENT,
                consent = it.consent.copy(editing = it.consent.recorded != null, error = null),
                banner = null,
            )
        }
    }

    /**
     * The gates phase's single primary action: it either fixes the first blocking gate, runs
     * stale gates again, or seals. Keeping the branch here means the screen has one button.
     */
    fun primaryGateAction() {
        val current = _state.value
        when {
            current.gates.running -> Unit
            current.gates.blockingFailures.isNotEmpty() -> routeToFix(current.gates.blockingFailures.first())
            current.gates.canSeal -> sealPacket()
            else -> refreshGates()
        }
    }

    /**
     * Routes a failing gate back to the step that can fix it. Geofence failures are resolved
     * on the job screen (arrival with a recorded override), never hidden in the capture flow.
     */
    fun routeToFix(gate: GateCheck) {
        val id = gate.id.lowercase(Locale.ROOT)
        val label = gate.label.lowercase(Locale.ROOT)
        val job = loadedJob
        val owningStep = job?.checklist?.firstOrNull { step ->
            val key = step.key.lowercase(Locale.ROOT)
            val stepLabel = step.label.lowercase(Locale.ROOT)
            id.contains(key) || label.contains(stepLabel)
        }
        when {
            id.contains("consent") -> _state.update {
                it.copy(
                    phase = CapturePhase.CONSENT,
                    consent = it.consent.copy(editing = it.consent.recorded != null, error = null),
                    banner = CaptureBanner(BannerTone.INFO, "Fix consent", gate.detail),
                )
            }

            id.contains("geofence") || id.contains("arrival") || id.contains("site accuracy") -> _state.update {
                it.copy(
                    banner = CaptureBanner(
                        tone = BannerTone.INFO,
                        title = "Resolve the site check on the job screen",
                        detail = "Confirm arrival on the job (an override records a reason and flags the packet). ${gate.detail}",
                    ),
                )
            }

            owningStep != null -> _state.update {
                it.copy(
                    phase = CapturePhase.EVIDENCE,
                    evidence = it.evidence.copy(activeStepKey = owningStep.key),
                    banner = CaptureBanner(BannerTone.INFO, "Fix: ${gate.label}", gate.detail),
                )
            }

            else -> _state.update {
                it.copy(
                    phase = CapturePhase.EVIDENCE,
                    banner = CaptureBanner(BannerTone.INFO, "Fix: ${gate.label}", gate.detail),
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Seal
    // ---------------------------------------------------------------------------------

    /**
     * Seals the packet. Preconditions are re-checked here (consent + gates) so a stale UI
     * cannot seal; the repository is still the authority and its result is what is shown.
     */
    fun sealPacket() {
        val current = _state.value
        val job = loadedJob ?: return
        if (current.seal.isBusy) return
        if (current.consent.recorded == null && loadedWork?.consent == null) {
            _state.update {
                it.copy(
                    phase = CapturePhase.CONSENT,
                    banner = CaptureBanner(
                        tone = BannerTone.ERROR,
                        title = "No consent on file",
                        detail = "Sealing without a recorded consent is not allowed. Sign the consent gate first.",
                    ),
                    consent = it.consent.copy(error = "Consent is required before sealing."),
                )
            }
            return
        }
        if (!current.gates.canSeal) {
            val blocking = current.gates.blockingFailures.joinToString { it.label }
            _state.update {
                it.copy(
                    phase = CapturePhase.GATES,
                    banner = CaptureBanner(
                        tone = BannerTone.WARNING,
                        title = if (current.gates.blockingFailures.isEmpty()) "Run the gates first" else "Blocking gates are still failing",
                        detail = if (blocking.isBlank()) "The gates have not been checked yet." else "Sealing stays disabled: $blocking.",
                    ),
                )
            }
            return
        }
        sealJob?.cancel()
        _state.update { it.copy(phase = CapturePhase.SEAL, seal = SealProgress(stage = SealStage.CANONICALISING), banner = null) }
        sealJob = scope.launch {
            narrateSealStages()
            repos.capture.seal(job.id, clock.nowEpochMs()).fold(
                onSuccess = ::onSealReturned,
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            seal = it.seal.copy(
                                stage = SealStage.FAILED,
                                error = "Seal failed: ${error.humanMessage()}. Nothing was sealed — fix the cause and retry.",
                            ),
                        )
                    }
                    refreshGates()
                },
            )
        }
    }

    /** Cosmetic narration of the documented seal sequence; it never asserts success. */
    private suspend fun narrateSealStages() {
        for (stage in SEAL_STAGES) {
            _state.update { it.copy(seal = it.seal.copy(stage = stage, error = null)) }
            if (sealStageDelayMillis > 0) delay(sealStageDelayMillis)
        }
    }

    private fun onSealReturned(outcome: com.odasi.fieldwright.data.SealOutcome) {
        if (outcome.packet.isSealed && outcome.evaluation.canSeal) {
            _state.update {
                it.copy(
                    seal = SealProgress(stage = SealStage.SEALED, outcome = outcome),
                    banner = CaptureBanner(
                        tone = BannerTone.INFO,
                        title = "Packet sealed",
                        detail = "Chain verified · ${outcome.verification.entries} records · head ${outcome.verification.headHash.take(8)}",
                    ),
                )
            }
            return
        }
        val reasons = outcome.evaluation.reasons.ifEmpty { listOf("The seal preconditions were not met.") }
        _state.update {
            it.copy(
                seal = it.seal.copy(stage = SealStage.FAILED, outcome = outcome, error = reasons.joinToString(" ")),
                banner = CaptureBanner(BannerTone.ERROR, "The packet was not sealed", reasons.joinToString(" ")),
            )
        }
        refreshGates()
    }

    /** Confirms submission. Submission is queued by the data layer; it changes no evidence. */
    fun submitPacket() {
        val current = _state.value
        if (current.seal.outcome == null) return
        if (!current.seal.isSealed || current.seal.submitted) return
        _state.update {
            it.copy(
                seal = it.seal.copy(submitted = true),
                banner = CaptureBanner(
                    tone = BannerTone.INFO,
                    title = "Packet queued",
                    detail = "Submission is queued offline-first. Sealed evidence does not change after this point.",
                ),
            )
        }
    }

    fun acknowledgeCameraFailure() {
        _state.update { it.copy(cameraFailure = null) }
    }

    fun dismissBanner() {
        _state.update { it.copy(banner = null) }
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    private fun updateStep(stepKey: String, transform: (CaptureStepState) -> CaptureStepState) {
        _state.update { current ->
            val steps = current.evidence.steps.map { step -> if (step.key == stepKey) transform(step) else step }
            current.copy(evidence = current.evidence.copy(steps = steps))
        }
    }

    private fun banner(tone: BannerTone, title: String, detail: String?) {
        _state.update { it.copy(banner = CaptureBanner(tone, title, detail)) }
    }

    private fun siteLabel(job: Job): String =
        job.site.address + (job.site.unit?.let { unit -> " · $unit" } ?: "")

    private fun Throwable.humanMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "Unknown error"

    private companion object {
        const val CONSENT_STEP_NOTE = "Signed by operator"
        val SEAL_STAGES = listOf(
            SealStage.CANONICALISING,
            SealStage.HASHING,
            SealStage.SIGNING,
            SealStage.CHAIN_APPENDING,
        )
    }
}
