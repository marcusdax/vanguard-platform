package com.odasi.fieldwright.feature.capture.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Implemented by capture sources that drive a live [PreviewView]. The screen checks for
 * this interface with `as?` and only then shows the CameraX preview surface; the simulated
 * source has no preview because there is nothing to preview.
 */
interface PreviewBinder {
    fun attachPreview(previewView: PreviewView)
}

/**
 * The real-camera path: CameraX preview + `ImageCapture`, JPEG bytes returned to the same
 * hashing pipeline the simulated source uses.
 *
 * Lifecycle rules encoded here:
 * - Binding happens when the screen attaches a [PreviewView]; the camera is bound to the
 *   screen's [LifecycleOwner], so it stops when the screen is not visible.
 * - [release] unbinds everything and is idempotent; the screen calls it in `DisposableEffect`.
 * - One `takePicture` per call — no burst loop, no auto-retry.
 *
 * If binding fails (camera busy, HAL failure, an emulator without a working virtual camera)
 * the source reports the failure and the ViewModel/screen offers the simulated fallback
 * instead of pretending a photo was taken. A failed [capture] never throws.
 */
class CameraXCaptureSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val executor: Executor = ContextCompat.getMainExecutor(context),
) : CaptureSource, PreviewBinder {

    override val kind: CaptureKind = CaptureKind.CAMERA
    override val simulatedReason: String? = null

    @Volatile
    private var bindingDetail: String? = null

    override val statusDetail: String? get() = bindingDetail

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    @Volatile
    private var released: Boolean = false

    @Volatile
    private var bindingRequested: Boolean = false

    override fun attachPreview(previewView: PreviewView) {
        if (released) return
        bind(previewView)
    }

    private fun bind(view: PreviewView) {
        if (released || bindingRequested) return
        bindingRequested = true
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (released) {
                runCatching { providerFuture.get().unbindAll() }
                return@addListener
            }
            try {
                val provider = providerFuture.get()
                val rotation = view.display?.rotation ?: Surface.ROTATION_0
                val preview = Preview.Builder()
                    .setTargetRotation(rotation)
                    .build()
                    .also { it.setSurfaceProvider(view.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
                    .setTargetRotation(rotation)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                cameraProvider = provider
                imageCapture = capture
                bindingDetail = null
            } catch (error: Throwable) {
                bindingDetail = error.message ?: "Camera could not start."
                Log.w(TAG, "CameraX binding failed; simulated fallback should be offered.", error)
                runCatching { cameraProvider?.unbindAll() }
                imageCapture = null
            }
        }, executor)
    }

    override suspend fun capture(request: CaptureRequest): Result<CapturedFrame> = runCatching {
        val capture = imageCapture
            ?: throw IllegalStateException(
                bindingDetail ?: "The camera preview is still starting. Wait a moment and try again.",
            )
        if (released) throw IllegalStateException("The capture screen was closed.")

        suspendCancellableCoroutine { continuation ->
            val callback = object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buffer = image.planes.firstOrNull()?.buffer
                            ?: throw IllegalStateException("The camera returned a frame with no pixel plane.")
                        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        if (continuation.isActive) {
                            continuation.resume(
                                CapturedFrame(
                                    bytes = bytes,
                                    widthPx = image.width,
                                    heightPx = image.height,
                                    kind = CaptureKind.CAMERA,
                                    simulatedReason = null,
                                    capturedAtEpochMs = request.capturedAtEpochMs,
                                    mimeType = MIME_JPEG,
                                ),
                            )
                        }
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
            }
            try {
                capture.takePicture(executor, callback)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    override fun release() {
        released = true
        imageCapture = null
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
    }

    private companion object {
        const val TAG = "FieldwrightCamera"
        const val MIME_JPEG = "image/jpeg"
    }
}

/**
 * Device capability for capture, computed from the platform rather than assumed.
 * [canUseCamera] is the whole decision: hardware + permission + not an emulator + no
 * previous camera failure.
 */
data class CameraCapability(
    val hasCameraHardware: Boolean,
    val permissionGranted: Boolean,
    val isEmulator: Boolean,
    val cameraFailed: Boolean = false,
) {
    val canUseCamera: Boolean
        get() = hasCameraHardware && permissionGranted && !isEmulator && !cameraFailed

    /** True when the permission rationale card should be offered. */
    val shouldOfferPermission: Boolean
        get() = hasCameraHardware && !isEmulator && !permissionGranted && !cameraFailed

    /** Why the camera is unusable, or null when [canUseCamera]. */
    val simulatedReason: String?
        get() = when {
            isEmulator -> "Emulator device - hardware camera frames are not available."
            !hasCameraHardware -> "No camera on this device."
            !permissionGranted -> "Camera permission is not granted."
            cameraFailed -> "The camera could not start on this device."
            else -> null
        }

    companion object {
        /** A capability that always resolves to the simulated source; used by previews. */
        val SIMULATED_ONLY: CameraCapability = CameraCapability(
            hasCameraHardware = false,
            permissionGranted = false,
            isEmulator = false,
        )
    }
}

/**
 * Resolves the capture source for this device. The screen never decides on its own: it
 * asks here, gets a [CameraCapability] to render honestly, and shows the source it returns.
 */
object CaptureSources {
    const val CAMERA_PERMISSION: String = Manifest.permission.CAMERA

    fun hasCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun capability(context: Context, permissionGranted: Boolean, cameraFailed: Boolean = false): CameraCapability =
        CameraCapability(
            hasCameraHardware = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
            permissionGranted = permissionGranted,
            isEmulator = isEmulator(),
            cameraFailed = cameraFailed,
        )

    fun create(context: Context, lifecycleOwner: LifecycleOwner, capability: CameraCapability): CaptureSource =
        if (capability.canUseCamera) {
            CameraXCaptureSource(context = context, lifecycleOwner = lifecycleOwner)
        } else {
            SimulatedCaptureSource(reason = capability.simulatedReason ?: SimulatedCaptureSource.FALLBACK_REASON)
        }

    /** Emulators are treated as camera-less for evidence purposes: no fabricated frames. */
    fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("emulator") ||
            model.contains("android sdk built for") ||
            product.contains("sdk") ||
            product.contains("emulator") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu")
    }
}
