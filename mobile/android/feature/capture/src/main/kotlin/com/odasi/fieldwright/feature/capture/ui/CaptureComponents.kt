package com.odasi.fieldwright.feature.capture.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import com.odasi.fieldwright.design.ChecklistRowState
import com.odasi.fieldwright.design.FieldwrightTheme
import com.odasi.fieldwright.design.Fw
import com.odasi.fieldwright.design.FwCard
import com.odasi.fieldwright.design.FwChainHash
import com.odasi.fieldwright.design.FwChecklistRow
import com.odasi.fieldwright.design.FwGhostButton
import com.odasi.fieldwright.design.FwKeyValue
import com.odasi.fieldwright.design.FwNotice
import com.odasi.fieldwright.design.FwPill
import com.odasi.fieldwright.design.FwPrimaryButton
import com.odasi.fieldwright.design.FwSecondaryButton
import com.odasi.fieldwright.design.FwSegmentedControl
import com.odasi.fieldwright.design.FwSwitchRow
import com.odasi.fieldwright.design.FwTextField
import com.odasi.fieldwright.design.PillTone
import com.odasi.fieldwright.feature.capture.BannerTone
import com.odasi.fieldwright.feature.capture.CaptureBanner
import com.odasi.fieldwright.feature.capture.CapturePhase
import com.odasi.fieldwright.feature.capture.CaptureState
import com.odasi.fieldwright.feature.capture.CaptureStepState
import com.odasi.fieldwright.feature.capture.CapturedMediaState
import com.odasi.fieldwright.feature.capture.GateReview
import com.odasi.fieldwright.feature.capture.MeasurementDraft
import com.odasi.fieldwright.feature.capture.SealStage
import com.odasi.fieldwright.feature.capture.StepStatus
import com.odasi.fieldwright.feature.capture.capture.CaptureSource
import com.odasi.fieldwright.feature.capture.capture.PreviewBinder
import com.odasi.fieldwright.feature.capture.measure.MeasurementMath
import com.odasi.fieldwright.feature.capture.measure.MeasurementReference
import com.odasi.fieldwright.feature.capture.measure.MeasurementResult
import com.odasi.fieldwright.feature.capture.measure.PixelPoint
import com.odasi.fieldwright.model.ConsentMethod
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.MediaSource
import com.odasi.fieldwright.model.ProofKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Screen-wide interaction surface. Kept as one object so phase composables stay stateless. */
@Stable
class CaptureCallbacks(
    val onSubjectNameChange: (String) -> Unit = {},
    val onSubjectRoleChange: (String) -> Unit = {},
    val onSignatureChange: (String) -> Unit = {},
    val onConsentMethodChange: (ConsentMethod) -> Unit = {},
    val onConsentConfirmedChange: (Boolean) -> Unit = {},
    val onCorrectConsent: () -> Unit = {},
    val onCancelConsentCorrection: () -> Unit = {},
    val onSignConsent: () -> Unit = {},
    val onContinueToEvidence: () -> Unit = {},
    val onSelectStep: (String) -> Unit = {},
    val onNoteChange: (String, String) -> Unit = { _, _ -> },
    val onCaptureFrame: (String) -> Unit = {},
    val onConfirmObservation: (String) -> Unit = {},
    val onReferenceChange: (String, MeasurementReference) -> Unit = { _, _ -> },
    val onMeasurementTap: (String, PixelPoint) -> Unit = { _, _ -> },
    val onMeasurementLabelChange: (String, String) -> Unit = { _, _ -> },
    val onResetMeasurement: (String) -> Unit = {},
    val onSaveMeasurement: (String, Boolean) -> Unit = { _, _ -> },
    val onOpenConsent: () -> Unit = {},
    val onGoToGates: () -> Unit = {},
    val onRefreshGates: () -> Unit = {},
    val onFixGate: (GateCheck) -> Unit = {},
    val onBackToEvidence: () -> Unit = {},
    val onPrimaryGateAction: () -> Unit = {},
    val onSealPacket: () -> Unit = {},
    val onSubmitPacket: () -> Unit = {},
    val onBackToGates: () -> Unit = {},
    val onDismissBanner: () -> Unit = {},
)

/** 4:3 is the frame the capture standard asks for; the preview and the saved JPEG agree. */
internal const val PREVIEW_ASPECT_RATIO: Float = 4f / 3f

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.US)

internal fun formatClock(epochMs: Long): String =
    TIME_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

internal fun formatTimestamp(epochMs: Long): String =
    DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

// ---------------------------------------------------------------------------------------
// Top bar, banners, footer
// ---------------------------------------------------------------------------------------

@Composable
internal fun CaptureTopBar(state: CaptureState, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Fw.colors.sheet)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Fw.dimens.topBarHeight)
                .padding(horizontal = Fw.space.sm, vertical = Fw.space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(Fw.dimens.touchTarget),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ArrowBack,
                    contentDescription = "Back to job",
                    tint = Fw.colors.ink,
                    modifier = Modifier.size(Fw.dimens.icon),
                )
            }
            Spacer(Modifier.width(Fw.space.xs))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Capture · ${state.job?.id ?: "—"}",
                    style = Fw.type.micro,
                    color = Fw.colors.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.jobTitle,
                    style = Fw.type.heading,
                    color = Fw.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.demo) {
                Spacer(Modifier.width(Fw.space.xs))
                FwPill(text = "Sample", tone = PillTone.NEUTRAL)
            }
            if (!state.jobOpenForCapture) {
                Spacer(Modifier.width(Fw.space.xs))
                FwPill(text = "Not open", tone = PillTone.FLAG)
            }
        }
        Box(Modifier.fillMaxWidth().height(Fw.dimens.hairline).background(Fw.colors.line))
    }
}

@Composable
internal fun CaptureBannerView(banner: CaptureBanner, onDismiss: () -> Unit) {
    val tone = when (banner.tone) {
        BannerTone.INFO -> PillTone.OLIVE
        BannerTone.WARNING -> PillTone.AMBER
        BannerTone.ERROR -> PillTone.FLAG
    }
    FwNotice(
        title = banner.title,
        body = banner.detail ?: " ",
        tone = tone,
        actionLabel = "Dismiss",
        onAction = onDismiss,
        modifier = Modifier.padding(horizontal = Fw.space.gutter, vertical = Fw.space.xs),
    )
}

/**
 * The single primary action for the current phase. The action stays in one place so the
 * operator never has to choose between two equally weighted buttons.
 */
@Composable
internal fun CaptureFooter(state: CaptureState, callbacks: CaptureCallbacks) {
    Column(Modifier.fillMaxWidth().background(Fw.colors.sheet)) {
        Box(Modifier.fillMaxWidth().height(Fw.dimens.hairline).background(Fw.colors.line))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Fw.space.gutter, vertical = Fw.space.sm),
            verticalArrangement = Arrangement.spacedBy(Fw.space.xs),
        ) {
            val hint = footerHint(state)
            if (hint != null) {
                Text(text = hint, style = Fw.type.meta, color = Fw.colors.inkSoft)
            }
            when (state.phase) {
                CapturePhase.CONSENT -> {
                    if (state.consent.isSigned) {
                        FwPrimaryButton(
                            text = "Continue to evidence",
                            onClick = callbacks.onContinueToEvidence,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.jobOpenForCapture,
                        )
                    } else {
                        FwPrimaryButton(
                            text = "Sign consent",
                            onClick = callbacks.onSignConsent,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.jobOpenForCapture,
                            loading = state.consent.isSaving,
                        )
                    }
                }

                CapturePhase.EVIDENCE -> {
                    FwPrimaryButton(
                        text = "Review gates",
                        onClick = callbacks.onGoToGates,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.requiredStepsComplete && state.jobOpenForCapture,
                        loading = state.evidence.isBusy,
                    )
                }

                CapturePhase.GATES -> {
                    when {
                        state.gates.running -> FwPrimaryButton(
                            text = "Checking gates",
                            onClick = {},
                            modifier = Modifier.fillMaxWidth(),
                            enabled = false,
                            loading = true,
                        )

                        state.gates.blockingFailures.isNotEmpty() -> FwPrimaryButton(
                            text = "Fix ${state.gates.blockingFailures.size} blocking gate${if (state.gates.blockingFailures.size == 1) "" else "s"}",
                            onClick = callbacks.onPrimaryGateAction,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        state.gates.canSeal -> FwPrimaryButton(
                            text = "Seal packet",
                            onClick = callbacks.onSealPacket,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        else -> FwPrimaryButton(
                            text = "Run gates",
                            onClick = callbacks.onRefreshGates,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.gates.running,
                        )
                    }
                    if (!state.gates.running) {
                        FwSecondaryButton(
                            text = "Back to evidence",
                            onClick = callbacks.onBackToEvidence,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                CapturePhase.SEAL -> {
                    when {
                        state.seal.isSealed -> FwPrimaryButton(
                            text = if (state.seal.submitted) "Submitted" else "Submit packet",
                            onClick = callbacks.onSubmitPacket,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.seal.submitted,
                        )

                        state.seal.stage == SealStage.FAILED -> {
                            FwPrimaryButton(
                                text = "Retry seal",
                                onClick = callbacks.onSealPacket,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = state.gates.canSeal,
                            )
                            FwSecondaryButton(
                                text = "Back to gates",
                                onClick = callbacks.onBackToGates,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        else -> FwPrimaryButton(
                            text = state.seal.stage.label,
                            onClick = {},
                            modifier = Modifier.fillMaxWidth(),
                            enabled = false,
                            loading = true,
                        )
                    }
                }
            }
        }
    }
}

private fun footerHint(state: CaptureState): String? = when (state.phase) {
    CapturePhase.CONSENT -> if (state.consent.isSigned) {
        "Consent recorded · signed by operator · ${state.consent.recorded?.name ?: ""}".trim()
    } else {
        "No consent, no capture. The scope is the job's scope and cannot be edited here."
    }

    CapturePhase.EVIDENCE -> "${state.evidence.completedRequired} of ${state.evidence.requiredTotal} required steps complete" +
        if (state.evidence.hasSimulatedFrames) " · ${state.evidence.simulatedFrameCount} simulated frame(s)" else ""

    CapturePhase.GATES -> when {
        state.gates.running -> "Checking the blocking gates against the evidence on file."
        state.gates.error != null -> state.gates.error
        state.gates.blockingFailures.isNotEmpty() -> "Sealing is disabled while a blocking gate fails."
        state.gates.canSeal -> "All blocking gates pass. Sealing is irreversible."
        else -> "Gates have not run yet."
    }

    CapturePhase.SEAL -> when {
        state.seal.isSealed -> "Sealed packets cannot be edited. Corrections are appended records."
        state.seal.stage == SealStage.FAILED -> state.seal.error
        else -> "Do not leave the screen while sealing."
    }
}

// ---------------------------------------------------------------------------------------
// Camera status: rationale, simulated notice
// ---------------------------------------------------------------------------------------

@Composable
internal fun SimulatedFrameNotice(reason: String?, modifier: Modifier = Modifier) {
    FwNotice(
        title = "Simulated frames",
        body = (reason ?: "No camera frame is available.") +
            " Frames are generated, labelled \"simulated frame\" in the image itself, and recorded with MediaSource.SIMULATED. They must not be sold as camera evidence.",
        tone = PillTone.AMBER,
        modifier = modifier,
    )
}

@Composable
internal fun CameraPermissionCard(
    onRequestPermission: () -> Unit,
    onUseSimulated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FwCard(modifier = modifier) {
        Text(
            text = "CAMERA",
            style = Fw.type.micro,
            color = Fw.colors.inkSoft,
        )
        Text(
            text = "Use the camera for live frames?",
            style = Fw.type.heading,
            color = Fw.colors.ink,
        )
        Text(
            text = "Vanguard Field opens the camera only while you press the shutter — no preview is recorded and nothing is captured in the background. Permission is asked once. If it is denied, the flow continues with clearly-labelled simulated frames.",
            style = Fw.type.body,
            color = Fw.colors.inkSoft,
        )
        Spacer(Modifier.heightIn(min = Fw.space.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.xs)) {
            FwSecondaryButton(text = "Allow camera", onClick = onRequestPermission)
            FwGhostButton(text = "Use simulated frames", onClick = onUseSimulated)
        }
    }
}

@Composable
internal fun CameraDeniedNotice(modifier: Modifier = Modifier) {
    FwNotice(
        title = "Camera permission denied",
        body = "The flow keeps working with simulated frames. Every frame is labelled, and the seal records the simulated source.",
        tone = PillTone.FLAG,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------------------
// Capture viewport: live preview or simulated placeholder, framing guide, measurement taps
// ---------------------------------------------------------------------------------------

@Composable
internal fun CaptureViewport(
    captureSource: CaptureSource,
    modifier: Modifier = Modifier,
    showFramingGuide: Boolean = true,
    draft: MeasurementDraft? = null,
    onTapPoint: ((PixelPoint) -> Unit)? = null,
    contentDescription: String = "Capture preview",
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(PREVIEW_ASPECT_RATIO)
            .clip(Fw.shape.card)
            .background(Fw.colors.mapPaper)
            .border(Fw.dimens.hairline, Fw.colors.line, Fw.shape.card),
    ) {
        if (captureSource is PreviewBinder) {
            key(captureSource) {
                AndroidView(
                    factory = { context ->
                        PreviewView(context).also { view ->
                            view.scaleType = PreviewView.ScaleType.FIT_CENTER
                            view.contentDescription = contentDescription
                            captureSource.attachPreview(view)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            SimulatedPreview(
                reason = captureSource.simulatedReason,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (showFramingGuide) {
            FramingGuide(modifier = Modifier.fillMaxSize())
        }
        if (onTapPoint != null) {
            // A transparent overlay above the preview surface: it receives the taps without
            // interfering with PreviewView's own touch handling, and the tap coordinates are
            // the preview's displayed pixels, which is the space MeasurementMath expects.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(onTapPoint) {
                        detectTapGestures { offset -> onTapPoint(PixelPoint(offset.x, offset.y)) }
                    },
            )
        }
        if (draft != null) {
            MeasurementTapOverlay(draft = draft, modifier = Modifier.fillMaxSize())
        }
        if (captureSource.isSimulated) {
            Box(Modifier.align(Alignment.TopStart).padding(Fw.space.xs)) {
                FwPill(text = "Simulated frame", tone = PillTone.AMBER)
            }
        }
    }
}

@Composable
private fun SimulatedPreview(reason: String?, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(color = Fw.colors.mapParcel)
            drawRect(
                color = Fw.colors.mapPaper,
                size = Size(size.width, size.height * 0.46f),
            )
            drawRect(
                color = Fw.colors.mapRoad,
                topLeft = Offset(0f, size.height * 0.46f),
                size = Size(size.width, size.height * 0.09f),
            )
            val centre = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = Fw.colors.olive.copy(alpha = 0.45f),
                radius = size.minDimension * 0.16f,
                center = centre,
                style = Stroke(width = Fw.dimens.stroke.toPx()),
            )
            drawLine(
                color = Fw.colors.olive.copy(alpha = 0.45f),
                start = Offset(centre.x - size.minDimension * 0.22f, centre.y),
                end = Offset(centre.x + size.minDimension * 0.22f, centre.y),
                strokeWidth = Fw.dimens.stroke.toPx(),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(Fw.space.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "SIMULATED PREVIEW",
                style = Fw.type.heading,
                color = Fw.colors.oxide,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.heightIn(min = Fw.space.xxs))
            Text(
                text = reason ?: "No camera frame is available.",
                style = Fw.type.meta,
                color = Fw.colors.inkSoft,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FramingGuide(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = Fw.space.lg.toPx()
            val arm = Fw.space.xl.toPx()
            val stroke = Fw.dimens.stroke.toPx()
            val color = Fw.colors.oxide.copy(alpha = 0.85f)
            val corners = listOf(
                Offset(inset, inset) to Offset(1f, 1f),
                Offset(size.width - inset, inset) to Offset(-1f, 1f),
                Offset(inset, size.height - inset) to Offset(1f, -1f),
                Offset(size.width - inset, size.height - inset) to Offset(-1f, -1f),
            )
            corners.forEach { (corner, direction) ->
                drawLine(color, corner, Offset(corner.x + arm * direction.x, corner.y), stroke)
                drawLine(color, corner, Offset(corner.x, corner.y + arm * direction.y), stroke)
            }
            val centreY = size.height / 2f
            drawLine(
                color = Fw.colors.oxide.copy(alpha = 0.55f),
                start = Offset(size.width * 0.38f, centreY),
                end = Offset(size.width * 0.62f, centreY),
                strokeWidth = Fw.dimens.hairline.toPx(),
            )
        }
        Text(
            text = "No faces · no plates · fill the frame",
            style = Fw.type.micro,
            color = Fw.colors.ink,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(Fw.space.xs)
                .clip(Fw.shape.button)
                .background(Fw.colors.sheet.copy(alpha = 0.92f))
                .padding(horizontal = Fw.space.xs, vertical = Fw.space.xxs),
        )
    }
}

@Composable
private fun MeasurementTapOverlay(draft: MeasurementDraft, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val markerRadius = Fw.dimens.dotSmall.toPx()
        val stroke = Fw.dimens.stroke.toPx()
        draft.referenceStart?.let { point ->
            draft.referenceEnd?.let { end ->
                drawLine(
                    color = Fw.colors.olive,
                    start = Offset(point.x, point.y),
                    end = Offset(end.x, end.y),
                    strokeWidth = stroke,
                )
            }
        }
        draft.targetStart?.let { point ->
            draft.targetEnd?.let { end ->
                drawLine(
                    color = Fw.colors.oxide,
                    start = Offset(point.x, point.y),
                    end = Offset(end.x, end.y),
                    strokeWidth = stroke,
                )
            }
        }
        listOfNotNull(draft.referenceStart, draft.referenceEnd).forEach { point ->
            drawCircle(color = Fw.colors.olive, radius = markerRadius, center = Offset(point.x, point.y))
        }
        listOfNotNull(draft.targetStart, draft.targetEnd).forEach { point ->
            drawCircle(color = Fw.colors.oxide, radius = markerRadius, center = Offset(point.x, point.y))
        }
    }
}

// ---------------------------------------------------------------------------------------
// Checklist and captured frames
// ---------------------------------------------------------------------------------------

@Composable
internal fun StepChecklist(steps: List<CaptureStepState>, activeStepKey: String?, onSelectStep: (String) -> Unit) {
    FwCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "CHECKLIST",
            style = Fw.type.micro,
            color = Fw.colors.inkSoft,
        )
        steps.forEach { step ->
            FwChecklistRow(
                label = step.label,
                hint = checklistHint(step, selected = step.key == activeStepKey),
                state = when (step.status) {
                    StepStatus.PASS -> ChecklistRowState.PASS
                    StepStatus.FAILED -> ChecklistRowState.FAIL
                    StepStatus.OPEN -> ChecklistRowState.OPEN
                },
                onClick = { onSelectStep(step.key) },
            )
        }
    }
}

private fun checklistHint(step: CaptureStepState, selected: Boolean): String {
    val proof = when (step.proofKind) {
        ProofKind.PHOTO -> "Photo"
        ProofKind.MEASUREMENT -> "Measurement"
        ProofKind.OBSERVATION -> "Observation"
        ProofKind.CONSENT -> "Consent"
    }
    val state = when (step.status) {
        StepStatus.PASS -> "recorded"
        StepStatus.FAILED -> "needs attention"
        StepStatus.OPEN -> if (selected) "capturing now" else "open"
    }
    val requirement = if (step.required) "required" else "optional"
    return "$proof · $state · $requirement"
}

@Composable
internal fun CapturedThumb(media: CapturedMediaState) {
    Box(
        modifier = Modifier
            .size(Fw.dimens.thumbnail)
            .clip(Fw.shape.card)
            .background(Fw.colors.mapParcel)
            .border(Fw.dimens.hairline, Fw.colors.line, Fw.shape.card),
        contentAlignment = Alignment.Center,
    ) {
        val image = remember(media.mediaId, media.bytes) {
            media.bytes.takeIf { it.isNotEmpty() }?.let { bytes ->
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
            }
        }
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = if (media.simulated) {
                    "Simulated frame thumbnail, hash ${media.shortHash}"
                } else {
                    "Camera frame thumbnail, hash ${media.shortHash}"
                },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = media.shortHash.take(6),
                    style = Fw.type.hash,
                    color = Fw.colors.inkSoft,
                )
                Text(
                    text = if (media.simulated) "SIM" else "JPEG",
                    style = Fw.type.micro,
                    color = if (media.simulated) Fw.colors.amber else Fw.colors.inkSoft,
                )
            }
        }
    }
}

@Composable
internal fun CapturedFrameRow(media: List<CapturedMediaState>, modifier: Modifier = Modifier) {
    if (media.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Fw.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        media.forEach { captured -> CapturedThumb(captured) }
        Column(Modifier.weight(1f)) {
            media.lastOrNull()?.let { latest ->
                Text(
                    text = "shorthash ${latest.shortHash}",
                    style = Fw.type.hash,
                    color = Fw.colors.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (media.any { it.simulated }) {
                Text(
                    text = "Simulated frame · not a camera photo",
                    style = Fw.type.micro,
                    color = Fw.colors.amber,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Measurement tool
// ---------------------------------------------------------------------------------------

@Composable
internal fun MeasurementToolCard(
    step: CaptureStepState,
    captureSource: CaptureSource,
    callbacks: CaptureCallbacks,
) {
    val simulated = captureSource.isSimulated
    FwCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "CALIBRATED MEASUREMENT",
            style = Fw.type.micro,
            color = Fw.colors.inkSoft,
        )
        Text(text = step.label, style = Fw.type.heading, color = Fw.colors.ink)
        Text(text = step.hint, style = Fw.type.body, color = Fw.colors.inkSoft)
        Spacer(Modifier.heightIn(min = Fw.space.xs))
        Text(
            text = "REFERENCE OBJECT",
            style = Fw.type.micro,
            color = Fw.colors.inkSoft,
        )
        FwSegmentedControl(
            options = MeasurementReference.ALL.map { it.shortLabel },
            selectedIndex = MeasurementReference.ALL.indexOfFirst { it.id == step.draft.reference.id }
                .coerceAtLeast(0),
            onSelect = { index -> callbacks.onReferenceChange(step.key, MeasurementReference.ALL[index]) },
        )
        Spacer(Modifier.heightIn(min = Fw.space.xs))
        CaptureViewport(
            captureSource = captureSource,
            draft = step.draft,
            onTapPoint = { point -> callbacks.onMeasurementTap(step.key, point) },
            contentDescription = "Measurement preview; tap the ends of the reference then the target",
        )
        Spacer(Modifier.heightIn(min = Fw.space.xs))
        Text(text = step.draft.stageLabel, style = Fw.type.micro, color = Fw.colors.oxide)
        Text(text = step.draft.instruction, style = Fw.type.bodyStrong, color = Fw.colors.ink)
        Text(
            text = "Tap ${step.draft.tapCount} of 4 · two taps define one span.",
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
        )
        step.draft.error?.let { message ->
            Text(text = message, style = Fw.type.meta, color = Fw.colors.flag)
        }
        if (simulated) {
            FwNotice(
                title = "Simulated measurement",
                body = "These taps were placed on a simulated frame. The result is labelled as simulated evidence and cannot stand in for a calibrated camera measurement.",
                tone = PillTone.AMBER,
            )
        }
        step.draft.result?.let { result -> MeasurementResultCard(result = result, simulated = simulated) }
        FwTextField(
            value = step.draft.label,
            onValueChange = { value -> callbacks.onMeasurementLabelChange(step.key, value) },
            label = "Measurement label",
            placeholder = "e.g. hail strike diameter",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Fw.space.xs),
        ) {
            FwGhostButton(
                text = "Reset taps",
                onClick = { callbacks.onResetMeasurement(step.key) },
                modifier = Modifier.weight(1f),
            )
            FwPrimaryButton(
                text = "Save measurement",
                onClick = { callbacks.onSaveMeasurement(step.key, simulated) },
                modifier = Modifier.weight(1f),
                enabled = step.draft.result != null && !step.busy && step.status != StepStatus.PASS,
                loading = step.busy,
            )
        }
        step.error?.let { message ->
            Text(text = message, style = Fw.type.meta, color = Fw.colors.flag)
        }
        step.savedMeasurement?.let { saved ->
            FwPill(text = "Saved · ${saved.label}", tone = PillTone.OLIVE)
        }
    }
}

@Composable
private fun MeasurementResultCard(result: MeasurementResult, simulated: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Fw.shape.card)
            .background(Fw.colors.oliveTint)
            .border(Fw.dimens.hairline, Fw.colors.line, Fw.shape.card)
            .padding(Fw.space.sm),
        verticalArrangement = Arrangement.spacedBy(Fw.space.xxs),
    ) {
        Text(
            text = if (simulated) "SIMULATED RESULT" else "CALIBRATED RESULT",
            style = Fw.type.micro,
            color = if (simulated) Fw.colors.amber else Fw.colors.oliveDeep,
        )
        Text(text = result.headline, style = Fw.type.numericLarge, color = Fw.colors.ink)
        FwKeyValue(label = "Reference", value = "${result.reference.label} · ${result.reference.meters} m")
        FwKeyValue(
            label = "Reference span",
            value = "${result.referenceSpanPx.toInt()} px → ${result.measuredSpanPx.toInt()} px",
        )
        Text(
            text = "Uncertainty ${result.uncertaintyLabel} from tap precision (±${MeasurementMath.TAP_TOLERANCE_PX.toInt()} px per tap). Keep the reference in the same plane as the target; perspective error is not included.",
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
        )
    }
}

// ---------------------------------------------------------------------------------------
// Phase headers
// ---------------------------------------------------------------------------------------

@Composable
internal fun PhaseHeader(overline: String, title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Fw.space.xxs)) {
        Text(text = overline, style = Fw.type.micro, color = Fw.colors.oxide)
        Text(text = title, style = Fw.type.displayL, color = Fw.colors.ink)
        Text(text = body, style = Fw.type.body, color = Fw.colors.inkSoft)
    }
}

@Composable
internal fun FieldError(message: String) {
    Text(text = message, style = Fw.type.meta, color = Fw.colors.flag)
}

@Composable
internal fun FieldHint(message: String) {
    Text(text = message, style = Fw.type.meta, color = Fw.colors.inkSoft)
}

@Composable
internal fun PhaseRule() {
    Box(
        Modifier
            .padding(vertical = Fw.space.xs)
            .size(width = Fw.dimens.ruleWidth, height = Fw.dimens.ruleHeight)
            .background(Fw.colors.olive),
    )
}

/** Wraps a preview body in the theme and the parchment background. */
@Composable
internal fun CapturePreviewSurface(content: @Composable () -> Unit) {
    FieldwrightTheme {
        Box(Modifier.fillMaxSize().background(Fw.colors.desk)) {
            content()
        }
    }
}

internal fun MediaSource.isSimulatedSource(): Boolean = this == MediaSource.SIMULATED
