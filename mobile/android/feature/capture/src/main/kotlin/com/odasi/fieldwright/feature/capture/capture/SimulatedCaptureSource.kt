package com.odasi.fieldwright.feature.capture.capture

import kotlinx.coroutines.delay

/**
 * The always-available capture source.
 *
 * Used when there is no camera hardware, the camera permission was denied, the app is on an
 * emulator, or the real camera failed to start. It renders a deterministic synthetic frame
 * (see [SimulatedFrameRenderer]) that *states in the image* that it is simulated and why.
 *
 * Determinism matters because the frame is hashed and hashed again when the packet is
 * sealed: the same job, step and timestamp must always produce the same bytes. The source
 * reads no clock and no device state; everything comes from the [CaptureRequest].
 */
class SimulatedCaptureSource(
    reason: String,
    private val frameWidthPx: Int = SimulatedFrameSpec.DEFAULT_WIDTH,
    private val frameHeightPx: Int = SimulatedFrameSpec.DEFAULT_HEIGHT,
    /** A short shutter beat so the UI's "capturing" state is perceptible. */
    private val shutterLatencyMillis: Long = 140L,
) : CaptureSource {

    override val kind: CaptureKind = CaptureKind.SIMULATED

    override val simulatedReason: String = reason.ifBlank { FALLBACK_REASON }

    override suspend fun capture(request: CaptureRequest): Result<CapturedFrame> = runCatching {
        if (shutterLatencyMillis > 0) delay(shutterLatencyMillis)
        val spec = specFor(request)
        val bytes = SimulatedFrameRenderer.renderPng(spec)
        CapturedFrame(
            bytes = bytes,
            widthPx = spec.widthPx,
            heightPx = spec.heightPx,
            kind = CaptureKind.SIMULATED,
            simulatedReason = simulatedReason,
            capturedAtEpochMs = request.capturedAtEpochMs,
            mimeType = MIME_PNG,
        )
    }

    /** The exact synthetic scene for a request; public so tests can compare hashes. */
    fun specFor(request: CaptureRequest): SimulatedFrameSpec = SimulatedFrameSpec(
        jobId = request.jobId,
        siteLabel = request.siteLabel,
        stepLabel = request.stepLabel,
        capturedAtEpochMs = request.capturedAtEpochMs,
        simulatedReason = simulatedReason,
        widthPx = frameWidthPx,
        heightPx = frameHeightPx,
    )

    override fun release() {
        // Nothing is bound; the simulated source holds no device resources.
    }

    companion object {
        const val MIME_PNG: String = "image/png"
        const val FALLBACK_REASON: String = "No camera frame was available."
    }
}
