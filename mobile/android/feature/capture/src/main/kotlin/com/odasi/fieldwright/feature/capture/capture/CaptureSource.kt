package com.odasi.fieldwright.feature.capture.capture

import com.odasi.fieldwright.model.MediaSource

/**
 * Which physical path produced a frame. This is recorded on every [CapturedFrame] and is
 * surfaced in the UI: a simulated frame is never presented as a camera frame.
 */
enum class CaptureKind { CAMERA, SIMULATED }

/** What is being photographed: the job, the checklist step and the shutter instant. */
data class CaptureRequest(
    val jobId: String,
    val siteLabel: String,
    val stepKey: String,
    val stepLabel: String,
    val capturedAtEpochMs: Long,
)

/**
 * A single captured frame, in memory, before it becomes a `core:model MediaRef`.
 *
 * The SHA-256 that becomes evidence is computed from [bytes] by the ViewModel through the
 * same path for camera and simulated sources, so the seal cannot tell them apart — which
 * is why [kind] and [simulatedReason] must travel with the frame into the user-visible
 * state and into the gate review.
 */
class CapturedFrame(
    val bytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    val kind: CaptureKind,
    val simulatedReason: String?,
    val capturedAtEpochMs: Long,
    val mimeType: String,
) {
    val mediaSource: MediaSource
        get() = when (kind) {
            CaptureKind.CAMERA -> MediaSource.CAMERA
            CaptureKind.SIMULATED -> MediaSource.SIMULATED
        }

    val isSimulated: Boolean get() = kind == CaptureKind.SIMULATED
}

/**
 * The camera boundary.
 *
 * The capture flow depends on this interface, never on CameraX directly. There are two
 * implementations: [CameraXCaptureSource] for a real device with permission, and
 * [SimulatedCaptureSource] for everything else (no camera, permission denied, emulator,
 * or a camera that failed to start). Both return raw bytes from the same `capture` call,
 * both are hashed by the same code, and the simulated one says so in its pixels.
 *
 * Contract:
 * - `capture` is a *single* shot. Implementations must not loop, burst, or retry silently.
 * - `capture` returns a failed [Result] instead of throwing; the UI must render the failure.
 * - `release` is idempotent and must cancel/unbind all device resources. The screen calls it
 *   from `DisposableEffect` when leaving.
 */
interface CaptureSource {
    val kind: CaptureKind

    /** Non-null exactly when [kind] is [CaptureKind.SIMULATED]: why no camera frame was possible. */
    val simulatedReason: String?

    /** Extra detail for a binding problem the user could act on (e.g. camera busy). */
    val statusDetail: String? get() = null

    val isSimulated: Boolean get() = kind == CaptureKind.SIMULATED

    suspend fun capture(request: CaptureRequest): Result<CapturedFrame>

    fun release()
}
