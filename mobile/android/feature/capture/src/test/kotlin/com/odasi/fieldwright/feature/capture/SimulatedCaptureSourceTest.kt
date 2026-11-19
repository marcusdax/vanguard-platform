package com.odasi.fieldwright.feature.capture

import com.odasi.fieldwright.feature.capture.capture.CaptureKind
import com.odasi.fieldwright.feature.capture.capture.CaptureRequest
import com.odasi.fieldwright.feature.capture.capture.SimulatedCaptureSource
import com.odasi.fieldwright.feature.capture.capture.SimulatedFrameRenderer
import com.odasi.fieldwright.feature.capture.capture.SimulatedFrameSpec
import com.odasi.fieldwright.feature.capture.crypto.Sha256
import com.odasi.fieldwright.model.MediaSource
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The simulated source is the one this project actually exercises, so its contract is
 * tested end to end: a real PNG whose bytes, and therefore whose SHA-256, depend only on the
 * request. Determinism is the property the seal relies on.
 */
class SimulatedCaptureSourceTest {

    private val request = CaptureRequest(
        jobId = "FW-1048",
        siteLabel = "1420 Creekside Bend · Lot 12",
        stepKey = "slopes",
        stepLabel = "Four slopes",
        capturedAtEpochMs = 1_763_000_000_000L,
    )

    private fun source(reason: String = REASON) = SimulatedCaptureSource(
        reason = reason,
        shutterLatencyMillis = 0L,
    )

    @Test
    fun `the same request renders byte identical frames and one hash`() = runBlocking {
        val first = source().capture(request).getOrThrow()
        val second = source().capture(request).getOrThrow()
        assertTrue("Simulated frames must be deterministic.", first.bytes.contentEquals(second.bytes))
        assertEquals(Sha256.sha256Hex(first.bytes), Sha256.sha256Hex(second.bytes))
        assertEquals(64, Sha256.sha256Hex(first.bytes).length)
        assertEquals(CaptureKind.SIMULATED, first.kind)
        assertTrue(first.isSimulated)
        assertEquals(MediaSource.SIMULATED, first.mediaSource)
        assertEquals("image/png", first.mimeType)
        assertEquals(request.capturedAtEpochMs, first.capturedAtEpochMs)
    }

    @Test
    fun `a different capture instant changes the hash`() = runBlocking {
        val a = source().capture(request).getOrThrow()
        val b = source().capture(request.copy(capturedAtEpochMs = request.capturedAtEpochMs + 1_000L)).getOrThrow()
        assertNotEquals(Sha256.sha256Hex(a.bytes), Sha256.sha256Hex(b.bytes))
    }

    @Test
    fun `a different step or job changes the hash`() = runBlocking {
        val base = Sha256.sha256Hex(source().capture(request).getOrThrow().bytes)
        val otherStep = Sha256.sha256Hex(source().capture(request.copy(stepLabel = "Test square")).getOrThrow().bytes)
        val otherJob = Sha256.sha256Hex(source().capture(request.copy(jobId = "FW-0994")).getOrThrow().bytes)
        assertNotEquals(base, otherStep)
        assertNotEquals(base, otherJob)
    }

    @Test
    fun `the frame is a png with the documented dimensions`() = runBlocking {
        val frame = source().capture(request).getOrThrow()
        val magic = frame.bytes.take(4).map { it.toInt() and 0xFF }
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), magic)
        assertEquals(SimulatedFrameSpec.DEFAULT_WIDTH, frame.widthPx)
        assertEquals(SimulatedFrameSpec.DEFAULT_HEIGHT, frame.heightPx)
        assertEquals(SimulatedFrameSpec.DEFAULT_WIDTH, readIntBe(frame.bytes, 16))
        assertEquals(SimulatedFrameSpec.DEFAULT_HEIGHT, readIntBe(frame.bytes, 20))
    }

    @Test
    fun `the watermark names the job site step timestamp and the simulation`() {
        val spec = source().specFor(request)
        val lines = SimulatedFrameRenderer.watermarkLines(spec)
        val joined = lines.joinToString("\n")
        assertTrue(joined, joined.contains("FW-1048"))
        assertTrue(joined, joined.contains("1420 CREEKSIDE BEND"))
        assertTrue(joined, joined.contains("FOUR SLOPES"))
        assertTrue(joined, joined.contains(Instant.ofEpochMilli(request.capturedAtEpochMs).toString()))
        assertTrue(joined, joined.contains("SIMULATED"))
        assertTrue(joined, joined.contains("NO CAMERA ON THIS DEVICE"))
        assertEquals(6, lines.size)
    }

    @Test
    fun `the caption is honest about the source`() {
        val caption = SimulatedFrameRenderer.caption(source().specFor(request))
        assertTrue(caption.contains("Simulated frame"))
        assertTrue(caption.contains(request.jobId))
    }

    @Test
    fun `a blank reason falls back to an honest default`() {
        assertEquals(SimulatedCaptureSource.FALLBACK_REASON, source(reason = "   ").simulatedReason)
    }

    @Test
    fun `release is idempotent and does not break later captures`() = runBlocking {
        val simulated = source()
        simulated.release()
        simulated.release()
        assertTrue(simulated.capture(request).isSuccess)
    }

    @Test
    fun `sha256 matches the standard test vectors`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.sha256Hex("abc"),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun `sanitise keeps the pixel font honest`() {
        // Characters the built-in font cannot draw become '?', so a frame never renders
        // half a watermark line silently.
        assertEquals("ABC 123 - ?", SimulatedFrameRenderer.sanitize("abc 123 - \u00e9"))
        assertEquals("SITE A/B", SimulatedFrameRenderer.sanitize("site a/b"))
    }

    private fun readIntBe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private companion object {
        const val REASON = "No camera on this device."
    }
}
