package com.odasi.fieldwright.feature.capture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.odasi.fieldwright.design.Fw
import com.odasi.fieldwright.design.FwCard
import com.odasi.fieldwright.design.FwGhostButton
import com.odasi.fieldwright.design.FwKeyValue
import com.odasi.fieldwright.design.FwNotice
import com.odasi.fieldwright.design.FwPill
import com.odasi.fieldwright.design.FwPrimaryButton
import com.odasi.fieldwright.design.FwTextField
import com.odasi.fieldwright.design.PillTone
import com.odasi.fieldwright.feature.capture.CaptureState
import com.odasi.fieldwright.feature.capture.CaptureStepState
import com.odasi.fieldwright.feature.capture.StepStatus
import com.odasi.fieldwright.feature.capture.capture.CameraCapability
import com.odasi.fieldwright.feature.capture.capture.CaptureSource
import com.odasi.fieldwright.feature.capture.capture.SimulatedCaptureSource
import com.odasi.fieldwright.model.ProofKind

/**
 * Phase 2 — evidence.
 *
 * The checklist is the job's checklist, rendered as obligations rather than a to-do list.
 * Exactly one step is expanded: the one that has to be captured next. The viewport is
 * either a real CameraX preview or a simulated placeholder that says so, and the shutter is
 * the phase's obvious action.
 */
@Composable
internal fun EvidencePhase(
    state: CaptureState,
    captureSource: CaptureSource,
    cameraCapability: CameraCapability,
    permissionAsked: Boolean,
    rationaleDismissed: Boolean,
    callbacks: CaptureCallbacks,
    onRequestCameraPermission: () -> Unit,
    onDismissRationale: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val job = state.job
    val activeStep = state.evidence.activeStep
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Fw.space.gutter, vertical = Fw.space.sm),
        verticalArrangement = Arrangement.spacedBy(Fw.space.md),
    ) {
        PhaseHeader(
            overline = "Evidence · ${state.evidence.completedRequired} of ${state.evidence.requiredTotal} required",
            title = "Capture the obligations",
            body = job?.type?.captureStandard ?: "Every checklist step needs its proof before the gates can run.",
        )

        when {
            cameraCapability.shouldOfferPermission && permissionAsked -> CameraDeniedNotice()
            cameraCapability.shouldOfferPermission && !rationaleDismissed -> CameraPermissionCard(
                onRequestPermission = onRequestCameraPermission,
                onUseSimulated = onDismissRationale,
            )
        }
        if (captureSource.isSimulated) {
            SimulatedFrameNotice(reason = captureSource.simulatedReason)
        }

        StepChecklist(
            steps = state.evidence.steps,
            activeStepKey = state.evidence.activeStepKey,
            onSelectStep = callbacks.onSelectStep,
        )

        activeStep?.let { step ->
            ActiveStepCard(
                state = state,
                step = step,
                captureSource = captureSource,
                callbacks = callbacks,
            )
        }

        FwNotice(
            title = "Framing rules",
            body = "Keep faces and plate numbers out of frame unless the consent scope names them. When in doubt, retake wide and add a second close frame.",
            tone = PillTone.NEUTRAL,
        )
    }
}

@Composable
private fun ActiveStepCard(
    state: CaptureState,
    step: CaptureStepState,
    captureSource: CaptureSource,
    callbacks: CaptureCallbacks,
) {
    when (step.proofKind) {
        ProofKind.PHOTO -> PhotoStepCard(step = step, captureSource = captureSource, callbacks = callbacks)
        ProofKind.MEASUREMENT -> MeasurementToolCard(step = step, captureSource = captureSource, callbacks = callbacks)
        ProofKind.OBSERVATION -> ObservationStepCard(step = step, callbacks = callbacks)
        ProofKind.CONSENT -> ConsentStepCard(state = state, step = step, callbacks = callbacks)
    }
}

@Composable
private fun PhotoStepCard(step: CaptureStepState, captureSource: CaptureSource, callbacks: CaptureCallbacks) {
    FwCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "PHOTO PROOF", style = Fw.type.micro, color = Fw.colors.inkSoft)
        Text(text = step.label, style = Fw.type.heading, color = Fw.colors.ink)
        Text(text = step.hint, style = Fw.type.body, color = Fw.colors.inkSoft)
        CaptureViewport(
            captureSource = captureSource,
            contentDescription = "Preview for ${step.label}",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Fw.space.xs),
        ) {
            FwPrimaryButton(
                text = if (step.complete) "Take another frame" else "Take frame",
                onClick = { callbacks.onCaptureFrame(step.key) },
                modifier = Modifier.weight(1f),
                enabled = !step.busy,
                loading = step.busy,
            )
        }
        FwTextField(
            value = step.note,
            onValueChange = { note -> callbacks.onNoteChange(step.key, note) },
            label = "Note",
            placeholder = "What does this frame show?",
            singleLine = false,
        )
        CapturedFrameRow(media = step.media, modifier = Modifier.padding(top = Fw.space.xs))
        step.error?.let { message -> FieldError(message) }
        if (step.complete) {
            FwPill(text = "Step recorded", tone = PillTone.OLIVE)
        }
    }
}

@Composable
private fun ObservationStepCard(step: CaptureStepState, callbacks: CaptureCallbacks) {
    FwCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "OBSERVATION", style = Fw.type.micro, color = Fw.colors.inkSoft)
        Text(text = step.label, style = Fw.type.heading, color = Fw.colors.ink)
        Text(text = step.hint, style = Fw.type.body, color = Fw.colors.inkSoft)
        FwTextField(
            value = step.note,
            onValueChange = { note -> callbacks.onNoteChange(step.key, note) },
            label = "Observation note",
            placeholder = "One line: what did you see, where, and when?",
            singleLine = false,
        )
        step.error?.let { message -> FieldError(message) }
        if (step.status != StepStatus.PASS) {
            FwPrimaryButton(
                text = "Confirm observation",
                onClick = { callbacks.onConfirmObservation(step.key) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !step.busy,
                loading = step.busy,
            )
        } else {
            FwPill(text = "Observation recorded", tone = PillTone.OLIVE)
        }
    }
}

@Composable
private fun ConsentStepCard(state: CaptureState, step: CaptureStepState, callbacks: CaptureCallbacks) {
    FwCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "CONSENT PROOF", style = Fw.type.micro, color = Fw.colors.inkSoft)
        Text(text = step.label, style = Fw.type.heading, color = Fw.colors.ink)
        Text(text = step.hint, style = Fw.type.body, color = Fw.colors.inkSoft)
        val recorded = state.consent.recorded
        if (recorded != null) {
            FwKeyValue(label = "Subject", value = "${recorded.name} · ${recorded.role}")
            FwKeyValue(label = "Method", value = recorded.method.label)
            FwKeyValue(label = "Signed by operator", value = state.operatorLabel.ifBlank { recorded.verifiedByOperatorId })
            FwPill(text = "Consent on file", tone = PillTone.OLIVE)
        } else {
            FwNotice(
                title = "Consent is missing",
                body = "This step cannot be satisfied without a consent record. Return to the consent gate and sign the scope.",
                tone = PillTone.FLAG,
            )
        }
        FwGhostButton(text = "Review consent", onClick = callbacks.onOpenConsent)
    }
}

@Preview(name = "Capture · evidence (photo)", widthDp = 400, heightDp = 1100, showBackground = true)
@Composable
private fun EvidencePhotoPreview() {
    CapturePreviewSurface {
        EvidencePhase(
            state = CaptureSamples.evidenceState(),
            captureSource = previewSimulatedSource,
            cameraCapability = CameraCapability.SIMULATED_ONLY,
            permissionAsked = false,
            rationaleDismissed = false,
            callbacks = CaptureCallbacks(),
            onRequestCameraPermission = {},
            onDismissRationale = {},
        )
    }
}

@Preview(name = "Capture · evidence (simulated + permission)", widthDp = 400, heightDp = 1100, showBackground = true)
@Composable
private fun EvidenceSimulatedPreview() {
    CapturePreviewSurface {
        EvidencePhase(
            state = CaptureSamples.evidenceState(simulated = true),
            captureSource = previewSimulatedSource,
            cameraCapability = CameraCapability(
                hasCameraHardware = true,
                permissionGranted = false,
                isEmulator = false,
            ),
            permissionAsked = false,
            rationaleDismissed = false,
            callbacks = CaptureCallbacks(),
            onRequestCameraPermission = {},
            onDismissRationale = {},
        )
    }
}

@Preview(name = "Capture · evidence (observation)", widthDp = 400, heightDp = 1000, showBackground = true)
@Composable
private fun EvidenceObservationPreview() {
    CapturePreviewSurface {
        EvidencePhase(
            state = CaptureSamples.evidenceObservationState(),
            captureSource = previewSimulatedSource,
            cameraCapability = CameraCapability.SIMULATED_ONLY,
            permissionAsked = false,
            rationaleDismissed = true,
            callbacks = CaptureCallbacks(),
            onRequestCameraPermission = {},
            onDismissRationale = {},
        )
    }
}

@Preview(name = "Capture · evidence (measurement)", widthDp = 400, heightDp = 1200, showBackground = true)
@Composable
private fun EvidenceMeasurementPreview() {
    CapturePreviewSurface {
        EvidencePhase(
            state = CaptureSamples.evidenceMeasurementState(),
            captureSource = previewSimulatedSource,
            cameraCapability = CameraCapability.SIMULATED_ONLY,
            permissionAsked = true,
            rationaleDismissed = true,
            callbacks = CaptureCallbacks(),
            onRequestCameraPermission = {},
            onDismissRationale = {},
        )
    }
}

/** Shared by the previews: a simulated source with no shutter beat. */
internal val previewSimulatedSource: CaptureSource = SimulatedCaptureSource(
    reason = "No camera on this device.",
    shutterLatencyMillis = 0L,
)
