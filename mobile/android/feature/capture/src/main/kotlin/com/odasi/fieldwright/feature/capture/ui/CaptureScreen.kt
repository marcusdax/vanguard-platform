package com.odasi.fieldwright.feature.capture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.odasi.fieldwright.design.FieldwrightTheme
import com.odasi.fieldwright.design.Fw
import com.odasi.fieldwright.design.FwEmptyState
import com.odasi.fieldwright.design.FwNotice
import com.odasi.fieldwright.design.FwProgressSteps
import com.odasi.fieldwright.design.PillTone
import com.odasi.fieldwright.feature.capture.CapturePhase
import com.odasi.fieldwright.feature.capture.CaptureState
import com.odasi.fieldwright.feature.capture.capture.CameraCapability
import com.odasi.fieldwright.feature.capture.capture.CaptureSource

/**
 * Stateless capture screen: consent → evidence → gates → seal.
 *
 * The screen owns layout only. Every decision (what is blocking, whether sealing is allowed,
 * what a failure means) belongs to `CaptureViewModel`; the camera lives behind
 * [CaptureSource]. This keeps the screen previewable in every phase, including the sealed
 * hero, with no Android camera and no repositories.
 *
 * Chrome: AppRoot renders the capture destination bare and applies `WindowInsets.safeDrawing`
 * to the frame (design `FwScaffold` insets its whole column), so this screen deliberately
 * does not add its own scaffold or insets — the sticky primary action is already above the
 * navigation bar, and a second scaffold here would double the top bar.
 *
 * @param permissionAsked true once the runtime permission dialog has been shown; the flow
 *   asks once and then explains the simulated fallback instead of nagging.
 * @param rationaleDismissed true if the operator chose to continue with simulated frames.
 */
@Composable
fun CaptureScreen(
    state: CaptureState,
    captureSource: CaptureSource,
    cameraCapability: CameraCapability,
    permissionAsked: Boolean,
    rationaleDismissed: Boolean,
    callbacks: CaptureCallbacks,
    onBack: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onDismissRationale: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Fw.colors.desk),
    ) {
        CaptureTopBar(state = state, onBack = onBack)
        when {
            state.isLoading -> Box(Modifier.weight(1f)) { LoadingContent() }

            state.loadError != null -> FwEmptyState(
                title = "Capture unavailable",
                body = state.loadError,
                actionLabel = "Back to job",
                onAction = onBack,
                modifier = Modifier.padding(Fw.space.gutter),
            )

            else -> {
                FwProgressSteps(
                    labels = CapturePhase.PROGRESS_LABELS,
                    current = state.progressIndex,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Fw.space.gutter, vertical = Fw.space.sm),
                )
                state.banner?.let { banner ->
                    CaptureBannerView(banner = banner, onDismiss = callbacks.onDismissBanner)
                }
                if (state.isOffline) {
                    FwNotice(
                        title = "Offline",
                        body = "Capture, gates and the seal run on this device. ${state.queuedForSync} change(s) are queued and upload when the connection returns.",
                        tone = PillTone.AMBER,
                        modifier = Modifier.padding(horizontal = Fw.space.gutter, vertical = Fw.space.xs),
                    )
                }
                Box(Modifier.weight(1f)) {
                    when (state.phase) {
                        CapturePhase.CONSENT -> ConsentPhase(
                            state = state,
                            callbacks = callbacks,
                        )

                        CapturePhase.EVIDENCE -> EvidencePhase(
                            state = state,
                            captureSource = captureSource,
                            cameraCapability = cameraCapability,
                            permissionAsked = permissionAsked,
                            rationaleDismissed = rationaleDismissed,
                            callbacks = callbacks,
                            onRequestCameraPermission = onRequestCameraPermission,
                            onDismissRationale = onDismissRationale,
                        )

                        CapturePhase.GATES -> GatesPhase(state = state, callbacks = callbacks)

                        CapturePhase.SEAL -> SealPhase(state = state, callbacks = callbacks)
                    }
                }
                CaptureFooter(state = state, callbacks = callbacks)
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = Fw.colors.olive,
                strokeWidth = Fw.dimens.stroke,
            )
            Text(
                text = "Loading the job and its checklist",
                style = Fw.type.meta,
                color = Fw.colors.inkSoft,
                modifier = Modifier.padding(top = Fw.space.sm),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------
// Previews: every phase plus the sealed state
// ---------------------------------------------------------------------------------------

@Composable
private fun ScreenPreview(state: CaptureState) {
    FieldwrightTheme {
        CaptureScreen(
            state = state,
            captureSource = previewSimulatedSource,
            cameraCapability = CameraCapability.SIMULATED_ONLY,
            permissionAsked = false,
            rationaleDismissed = false,
            callbacks = CaptureCallbacks(),
            onBack = {},
            onRequestCameraPermission = {},
            onDismissRationale = {},
        )
    }
}

@Preview(name = "Capture screen · consent", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
private fun ConsentScreenPreview() {
    ScreenPreview(CaptureSamples.consentState())
}

@Preview(name = "Capture screen · evidence", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
private fun EvidenceScreenPreview() {
    ScreenPreview(CaptureSamples.evidenceState())
}

@Preview(name = "Capture screen · gates", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
private fun GatesScreenPreview() {
    ScreenPreview(CaptureSamples.gatesBlockedState())
}

@Preview(name = "Capture screen · offline", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
private fun OfflineScreenPreview() {
    ScreenPreview(CaptureSamples.offlineState())
}

@Preview(name = "Capture screen · seal", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
private fun SealScreenPreview() {
    ScreenPreview(CaptureSamples.sealInProgressState())
}

@Preview(name = "Capture screen · sealed", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
private fun SealedScreenPreview() {
    ScreenPreview(CaptureSamples.sealedState())
}

@Preview(name = "Capture screen · load error", widthDp = 400, heightDp = 700, showBackground = true)
@Composable
private fun LoadErrorPreview() {
    ScreenPreview(CaptureSamples.loadErrorState())
}
