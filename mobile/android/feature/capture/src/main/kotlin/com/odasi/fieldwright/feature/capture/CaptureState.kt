package com.odasi.fieldwright.feature.capture

import com.odasi.fieldwright.data.SealOutcome
import com.odasi.fieldwright.feature.capture.measure.MeasurementMath
import com.odasi.fieldwright.feature.capture.measure.MeasurementReference
import com.odasi.fieldwright.feature.capture.measure.MeasurementResult
import com.odasi.fieldwright.feature.capture.measure.PixelPoint
import com.odasi.fieldwright.model.Consent
import com.odasi.fieldwright.model.ConsentMethod
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.Measurement
import com.odasi.fieldwright.model.MediaRef
import com.odasi.fieldwright.model.MediaSource
import com.odasi.fieldwright.model.ProofKind
import com.odasi.fieldwright.model.StepResult
import com.odasi.fieldwright.model.SyncPhase

/**
 * The four capture phases, in order. One phase is visible at a time and each has exactly one
 * obvious primary action; the progress strip is derived from [ordinal].
 */
enum class CapturePhase(val label: String) {
    CONSENT("Consent"),
    EVIDENCE("Evidence"),
    GATES("Gates"),
    SEAL("Seal"),
    ;

    companion object {
        val PROGRESS_LABELS: List<String> = CapturePhase.entries.map { it.label }
    }
}

/** An operator-facing message. Rendered as a banner, never as a silent log. */
data class CaptureBanner(
    val tone: BannerTone,
    val title: String,
    val detail: String? = null,
)

enum class BannerTone { INFO, WARNING, ERROR }

/** Reported when the real camera could not deliver a frame, so the screen can offer the
 *  clearly-labelled simulated fallback instead of silently substituting one. */
data class CameraFailure(
    val message: String,
    val atEpochMs: Long,
)

/** The consent gate form. The scope text is owned by the job, never typed by the operator. */
data class ConsentForm(
    val subjectName: String = "",
    val subjectRole: String = "",
    val method: ConsentMethod = ConsentMethod.IN_APP_SIGNATURE,
    val typedSignature: String = "",
    val confirmed: Boolean = false,
    val scopeText: String = "",
    val recorded: Consent? = null,
    val isSaving: Boolean = false,
    val error: String? = null,
    /** True while the operator is correcting an already-recorded consent. */
    val editing: Boolean = false,
) {
    val isSigned: Boolean get() = recorded != null && !editing

    /** Null when the form may be signed, otherwise the exact thing that is missing. */
    fun validationMessage(): String? = when {
        subjectName.trim().length < 2 -> "Enter the full name of the person giving consent."
        subjectRole.trim().isEmpty() -> "Enter that person's role — owner, tenant, or site contact."
        scopeText.isBlank() -> "This job has no consent scope on file. Ask the requester before capturing."
        typedSignature.trim().isEmpty() -> "Type the name that signs the scope. A typed signature is the record."
        !confirmed -> "Confirm that the subject read and agreed to the stated scope."
        else -> null
    }
}

enum class StepStatus { OPEN, PASS, FAILED }

/**
 * One captured frame as the screen knows it. [bytes] are kept in memory for the thumbnail
 * only; the evidence itself is the SHA-256 and its [mediaId] in the data layer.
 */
class CapturedMediaState(
    val mediaId: String,
    val sha256: String,
    val bytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    val capturedAtEpochMs: Long,
    val simulated: Boolean,
    val simulatedReason: String?,
    val mimeType: String,
) {
    val shortHash: String get() = sha256.take(8)

    /** True when the frame's own bytes are available for a thumbnail (not a re-hydrated ref). */
    val hasPreview: Boolean get() = bytes.isNotEmpty()

    /** Media equals identity of the stored evidence, not of the in-memory thumbnail. */
    override fun equals(other: Any?): Boolean =
        other is CapturedMediaState && other.mediaId == mediaId && other.sha256 == sha256

    override fun hashCode(): Int = mediaId.hashCode() * 31 + sha256.hashCode()

    override fun toString(): String = "CapturedMediaState($mediaId, $shortHash, simulated=$simulated)"
}

/** Converts a persisted [MediaRef] (no bytes in memory) into screen state. */
fun MediaRef.toCapturedMediaState(): CapturedMediaState = CapturedMediaState(
    mediaId = id,
    sha256 = sha256,
    bytes = ByteArray(0),
    widthPx = width,
    heightPx = height,
    capturedAtEpochMs = capturedAtEpochMs,
    simulated = source == MediaSource.SIMULATED,
    simulatedReason = null,
    mimeType = "image/jpeg",
)

enum class MeasurementStage { REFERENCE_START, REFERENCE_END, TARGET_START, TARGET_END, READY }

/**
 * Two-tap calibrated measurement, in progress.
 *
 * First the operator taps the two ends of the chosen reference object, then the two ends of
 * the thing being measured. Two taps per span is the smallest input that defines a length;
 * anything less is a guess and the tool refuses to produce a number from it.
 */
data class MeasurementDraft(
    val reference: MeasurementReference = MeasurementReference.SITE_CARD,
    val referenceStart: PixelPoint? = null,
    val referenceEnd: PixelPoint? = null,
    val targetStart: PixelPoint? = null,
    val targetEnd: PixelPoint? = null,
    val result: MeasurementResult? = null,
    val label: String = "",
    val error: String? = null,
) {
    val stage: MeasurementStage
        get() = when {
            referenceStart == null -> MeasurementStage.REFERENCE_START
            referenceEnd == null -> MeasurementStage.REFERENCE_END
            targetStart == null -> MeasurementStage.TARGET_START
            targetEnd == null -> MeasurementStage.TARGET_END
            else -> MeasurementStage.READY
        }

    val tapCount: Int
        get() = listOfNotNull(referenceStart, referenceEnd, targetStart, targetEnd).size

    val instruction: String
        get() = when (stage) {
            MeasurementStage.REFERENCE_START -> "Tap one end of the ${reference.shortLabel} on the preview."
            MeasurementStage.REFERENCE_END -> "Tap the other end of the ${reference.shortLabel}."
            MeasurementStage.TARGET_START -> "Now tap one end of the span you are measuring."
            MeasurementStage.TARGET_END -> "Tap the other end of the span you are measuring."
            MeasurementStage.READY -> "Span measured. Check the result, label it, then save."
        }

    /** What the next tap will do; rendered as the tool's micro status line. */
    val stageLabel: String
        get() = when (stage) {
            MeasurementStage.REFERENCE_START,
            MeasurementStage.REFERENCE_END,
            -> "Calibrating reference · ${reference.shortLabel}"
            MeasurementStage.TARGET_START,
            MeasurementStage.TARGET_END,
            -> "Measuring target span"
            MeasurementStage.READY -> "Result ready"
        }

    fun withReference(reference: MeasurementReference): MeasurementDraft =
        copy(reference = reference, referenceStart = null, referenceEnd = null, targetStart = null, targetEnd = null, result = null, error = null)

    fun withTap(point: PixelPoint): MeasurementDraft = when {
        referenceStart == null -> copy(referenceStart = point, error = null)

        referenceEnd == null -> {
            val start = referenceStart ?: return copy(referenceStart = point, error = null)
            val span = MeasurementMath.spanPx(start, point)
            if (span < MeasurementMath.MIN_SPAN_PX) {
                copy(referenceStart = point, error = "Those points were too close together. Tap the two ends of the ${reference.shortLabel} again.")
            } else {
                copy(referenceEnd = point, error = null)
            }
        }

        targetStart == null -> copy(targetStart = point, error = null)

        targetEnd == null -> {
            val targetStartPoint = targetStart
            val referenceStartPoint = referenceStart
            val referenceEndPoint = referenceEnd
            if (targetStartPoint == null || referenceStartPoint == null || referenceEndPoint == null) {
                copy(targetStart = point, error = null)
            } else {
                val computed = MeasurementMath.measure(
                    reference = reference,
                    referenceStart = referenceStartPoint,
                    referenceEnd = referenceEndPoint,
                    measuredStart = targetStartPoint,
                    measuredEnd = point,
                )
                when {
                    computed != null -> copy(targetEnd = point, result = computed, error = null)
                    MeasurementMath.spanPx(targetStartPoint, point) < MeasurementMath.MIN_SPAN_PX -> copy(
                        targetStart = point,
                        error = "Those points were too close together. Tap the two ends of the target span again.",
                    )
                    else -> copy(
                        targetStart = point,
                        error = "That span is not plausible (over ${MeasurementMath.MAX_PLAUSIBLE_METERS.toInt()} m). Check the reference and measure again.",
                    )
                }
            }
        }

        else -> copy(error = "Reset the tool to measure again.")
    }

    fun resetTaps(): MeasurementDraft =
        copy(referenceStart = null, referenceEnd = null, targetStart = null, targetEnd = null, result = null, error = null)
}

/** One checklist step and everything captured against it in this session. */
data class CaptureStepState(
    val key: String,
    val label: String,
    val hint: String,
    val proofKind: ProofKind,
    val required: Boolean,
    val note: String = "",
    val status: StepStatus = StepStatus.OPEN,
    val media: List<CapturedMediaState> = emptyList(),
    val draft: MeasurementDraft = MeasurementDraft(),
    val savedMeasurement: Measurement? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val complete: Boolean get() = status == StepStatus.PASS
}

data class EvidenceState(
    val steps: List<CaptureStepState> = emptyList(),
    val activeStepKey: String? = null,
    val error: String? = null,
) {
    val activeStep: CaptureStepState? get() = steps.firstOrNull { it.key == activeStepKey }

    val requiredSteps: List<CaptureStepState> get() = steps.filter { it.required }

    val completedRequired: Int get() = requiredSteps.count { it.complete }

    val requiredTotal: Int get() = requiredSteps.size

    val allRequiredComplete: Boolean get() = requiredTotal > 0 && completedRequired == requiredTotal

    val simulatedFrameCount: Int get() = steps.sumOf { step -> step.media.count { it.simulated } }

    val hasSimulatedFrames: Boolean get() = simulatedFrameCount > 0

    val isBusy: Boolean get() = steps.any { it.busy }
}

/**
 * The gate review. Only *blocking* failures prevent sealing: a non-blocking note is shown
 * with its reason but does not stop the packet, exactly as §5.1 requires.
 */
data class GateReview(
    val checks: List<GateCheck> = emptyList(),
    val running: Boolean = false,
    val hasRun: Boolean = false,
    val error: String? = null,
) {
    val blockingFailures: List<GateCheck> get() = checks.filter { it.blocking && !it.passed }

    val nonBlockingFailures: List<GateCheck> get() = checks.filter { !it.blocking && !it.passed }

    val failures: List<GateCheck> get() = checks.filter { !it.passed }

    val passedCount: Int get() = checks.count { it.passed }

    val canSeal: Boolean
        get() = hasRun && !running && error == null && checks.isNotEmpty() && blockingFailures.isEmpty()
}

/** The seal operation's narrated stages. Success is reported only after the repository returns. */
enum class SealStage(val label: String, val detail: String) {
    IDLE("Ready to seal", "Sealing canonicalises, hashes, signs and chains the packet."),
    CANONICALISING("Canonicalising", "Sorting fields into the canonical JSON form."),
    HASHING("Hashing", "SHA-256 over every record in the packet."),
    SIGNING("Signing", "Signing the packet hash with the operator key."),
    CHAIN_APPENDING("Appending to chain", "Appending the seal record after the previous hash."),
    SEALED("Sealed", "The packet is sealed and queued for submission."),
    FAILED("Seal failed", "The packet was not sealed."),
}

data class SealProgress(
    val stage: SealStage = SealStage.IDLE,
    val error: String? = null,
    val outcome: SealOutcome? = null,
    val submitted: Boolean = false,
) {
    val isBusy: Boolean
        get() = stage == SealStage.CANONICALISING || stage == SealStage.HASHING ||
            stage == SealStage.SIGNING || stage == SealStage.CHAIN_APPENDING

    val isSealed: Boolean get() = stage == SealStage.SEALED && outcome != null

    val packetId: String? get() = outcome?.packet?.id

    val shortHash: String get() = outcome?.packet?.shortHash ?: ""

    val stageOrder: List<SealStage> = listOf(SealStage.CANONICALISING, SealStage.HASHING, SealStage.SIGNING, SealStage.CHAIN_APPENDING, SealStage.SEALED)
}

/** Everything the stateless capture screen renders. */
data class CaptureState(
    val isLoading: Boolean = true,
    val phase: CapturePhase = CapturePhase.CONSENT,
    val job: Job? = null,
    val operatorLabel: String = "",
    val operatorId: String = "",
    val loadError: String? = null,
    val banner: CaptureBanner? = null,
    val consent: ConsentForm = ConsentForm(),
    val evidence: EvidenceState = EvidenceState(),
    val gates: GateReview = GateReview(),
    val seal: SealProgress = SealProgress(),
    val jobOpenForCapture: Boolean = true,
    val cameraFailure: CameraFailure? = null,
    val syncPhase: SyncPhase = SyncPhase.IDLE,
    val queuedForSync: Int = 0,
) {
    val progressIndex: Int get() = phase.ordinal

    /**
     * Capture is offline-first: frames, gates and the seal all run on-device. Offline is
     * therefore a *reassurance* state, not an error — the outbox uploads later.
     */
    val isOffline: Boolean
        get() = syncPhase == SyncPhase.WAITING_FOR_NETWORK || syncPhase == SyncPhase.RETRYING

    val demo: Boolean get() = job?.demo == true

    val jobTitle: String get() = job?.title ?: "Capture"

    val siteLabel: String
        get() = job?.let { it.site.address + (it.site.unit?.let { unit -> " · $unit" } ?: "") } ?: ""

    val requiredStepsComplete: Boolean get() = evidence.allRequiredComplete

    /** The text of the single primary action for the current phase. */
    val primaryActionLabel: String
        get() = when {
            phase == CapturePhase.CONSENT && consent.isSigned -> "Continue to evidence"
            phase == CapturePhase.CONSENT -> "Sign consent"
            phase == CapturePhase.EVIDENCE -> "Review gates"
            phase == CapturePhase.GATES -> when {
                gates.running -> "Checking gates"
                gates.blockingFailures.isNotEmpty() -> "Fix ${gates.blockingFailures.size} blocking gate${if (gates.blockingFailures.size == 1) "" else "s"}"
                gates.canSeal -> "Seal packet"
                else -> "Run gates"
            }
            phase == CapturePhase.SEAL && seal.isSealed -> if (seal.submitted) "Submitted" else "Submit packet"
            phase == CapturePhase.SEAL && seal.stage == SealStage.FAILED -> "Retry seal"
            phase == CapturePhase.SEAL -> "Sealing packet"
            else -> "Continue"
        }
}
