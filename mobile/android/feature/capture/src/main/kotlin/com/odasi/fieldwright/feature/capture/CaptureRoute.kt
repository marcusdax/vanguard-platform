package com.odasi.fieldwright.feature.capture

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.odasi.fieldwright.data.FieldwrightRepositories
import com.odasi.fieldwright.feature.capture.capture.CaptureSource
import com.odasi.fieldwright.feature.capture.capture.CameraCapability
import com.odasi.fieldwright.feature.capture.capture.CaptureSources
import com.odasi.fieldwright.feature.capture.ui.CaptureCallbacks
import com.odasi.fieldwright.feature.capture.ui.CaptureScreen

/**
 * Feature entrypoint (§6). The app passes repositories and navigation callbacks; the
 * feature owns its ViewModel and its own full-screen chrome.
 *
 * Camera policy is resolved here, once per capability:
 * - hardware camera + granted permission + not an emulator → [CameraXCaptureSource];
 * - anything else → [SimulatedCaptureSource] with a stated reason.
 *
 * Permission is requested at most once per screen instance, from a rationale card. Denial
 * never blocks the flow and never crashes it: the simulated source keeps working and every
 * produced frame is labelled. If the real camera fails to deliver a frame, the ViewModel
 * reports it and the route swaps to the simulated source rather than faking a photo.
 */
@Composable
fun CaptureRoute(
    repos: FieldwrightRepositories,
    jobId: String,
    onBack: () -> Unit,
    onSealed: (String) -> Unit,
) {
    val viewModel: CaptureViewModel = viewModel(key = "capture-$jobId") {
        CaptureViewModel(repos = repos, jobId = jobId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var permissionGranted by remember { mutableStateOf(CaptureSources.hasCameraPermission(context)) }
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    var rationaleDismissed by rememberSaveable { mutableStateOf(false) }
    var cameraFailed by rememberSaveable { mutableStateOf(false) }
    var sealedDelivered by rememberSaveable { mutableStateOf(false) }

    val capability: CameraCapability = remember(context, permissionGranted, cameraFailed) {
        CaptureSources.capability(
            context = context,
            permissionGranted = permissionGranted,
            cameraFailed = cameraFailed,
        )
    }
    val captureSource: CaptureSource = remember(capability, lifecycleOwner) {
        CaptureSources.create(context = context, lifecycleOwner = lifecycleOwner, capability = capability)
    }
    // Unbind the camera and cancel nothing else: the ViewModel owns its own coroutines.
    DisposableEffect(captureSource) {
        onDispose { captureSource.release() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        permissionAsked = true
    }

    // The user may grant the permission in system settings and come back to the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionGranted = CaptureSources.hasCameraPermission(context)
    }

    // The camera failed mid-flow: swap to the labelled simulated source on the next frame.
    LaunchedEffect(state.cameraFailure) {
        if (state.cameraFailure != null) {
            cameraFailed = true
            viewModel.acknowledgeCameraFailure()
        }
    }

    // A sealed packet whose submission the operator confirmed: hand the packet id back to
    // the app for navigation. Fires once.
    LaunchedEffect(state.seal.submitted) {
        if (state.seal.submitted && !sealedDelivered) {
            sealedDelivered = true
            state.seal.packetId?.let(onSealed)
        }
    }

    val callbacks = remember(viewModel, captureSource) {
        CaptureCallbacks(
            onSubjectNameChange = viewModel::onSubjectNameChange,
            onSubjectRoleChange = viewModel::onSubjectRoleChange,
            onSignatureChange = viewModel::onSignatureChange,
            onConsentMethodChange = viewModel::onConsentMethodChange,
            onConsentConfirmedChange = viewModel::onConsentConfirmedChange,
            onCorrectConsent = viewModel::onCorrectConsent,
            onCancelConsentCorrection = viewModel::onCancelConsentCorrection,
            onSignConsent = viewModel::signConsent,
            onContinueToEvidence = viewModel::continueToEvidence,
            onSelectStep = viewModel::selectStep,
            onNoteChange = viewModel::onStepNoteChange,
            onCaptureFrame = { stepKey -> viewModel.captureFrame(source = captureSource, stepKey = stepKey) },
            onConfirmObservation = viewModel::confirmObservation,
            onReferenceChange = viewModel::onMeasurementReferenceChange,
            onMeasurementTap = viewModel::onMeasurementTap,
            onMeasurementLabelChange = viewModel::onMeasurementLabelChange,
            onResetMeasurement = viewModel::resetMeasurement,
            onSaveMeasurement = { stepKey, simulated -> viewModel.saveMeasurement(stepKey, simulated) },
            onOpenConsent = viewModel::openConsentForEdit,
            onGoToGates = viewModel::goToGates,
            onRefreshGates = viewModel::refreshGates,
            onFixGate = viewModel::routeToFix,
            onBackToEvidence = viewModel::backToEvidence,
            onPrimaryGateAction = viewModel::primaryGateAction,
            onSealPacket = viewModel::sealPacket,
            onSubmitPacket = viewModel::submitPacket,
            onBackToGates = viewModel::backToGates,
            onDismissBanner = viewModel::dismissBanner,
        )
    }

    CaptureScreen(
        state = state,
        captureSource = captureSource,
        cameraCapability = capability,
        permissionAsked = permissionAsked,
        rationaleDismissed = rationaleDismissed,
        callbacks = callbacks,
        onBack = onBack,
        onRequestCameraPermission = {
            permissionAsked = true
            if (capability.hasCameraHardware) {
                permissionLauncher.launch(CaptureSources.CAMERA_PERMISSION)
            }
        },
        onDismissRationale = { rationaleDismissed = true },
    )
}
