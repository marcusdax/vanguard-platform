package com.odasi.fieldwright.feature.capture

import com.odasi.fieldwright.feature.capture.measure.MeasurementMath
import com.odasi.fieldwright.feature.capture.measure.MeasurementReference
import com.odasi.fieldwright.feature.capture.measure.PixelPoint
import com.odasi.fieldwright.model.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measurement engine is pure maths, so it is tested as maths: known ratios, known
 * conversions, and the boundaries where the tool must refuse to produce a number.
 */
class MeasurementMathTest {

    private fun spanOf(px: Double): Pair<PixelPoint, PixelPoint> =
        PixelPoint(0f, 0f) to PixelPoint(px.toFloat(), 0f)

    @Test
    fun `reference objects carry the documented metric lengths`() {
        assertEquals(0.30, MeasurementReference.SITE_CARD.meters, 1e-9)
        assertEquals(0.90, MeasurementReference.SHINGLE.meters, 1e-9)
        assertEquals(0.91, MeasurementReference.DOOR.meters, 1e-9)
        assertEquals(3, MeasurementReference.ALL.size)
        assertEquals("site_card", MeasurementReference.byId("site_card")?.id)
        assertNull(MeasurementReference.byId("nope"))
    }

    @Test
    fun `span uses euclidean distance`() {
        assertEquals(5.0, MeasurementMath.spanPx(PixelPoint(0f, 0f), PixelPoint(3f, 4f)), 1e-9)
        assertEquals(0.0, MeasurementMath.spanPx(PixelPoint(10f, 10f), PixelPoint(10f, 10f)), 1e-9)
    }

    @Test
    fun `calibration is a pure ratio of pixels to metres`() {
        assertEquals(100.0 / 0.30, MeasurementMath.pixelsPerMetre(100.0, 0.30), 1e-9)
        assertEquals(0.5, MeasurementMath.distanceMeters(50.0, 100.0), 1e-9)
        assertThrows(IllegalArgumentException::class.java) { MeasurementMath.pixelsPerMetre(0.0, 0.30) }
        assertThrows(IllegalArgumentException::class.java) { MeasurementMath.pixelsPerMetre(100.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { MeasurementMath.distanceMeters(10.0, 0.0) }
    }

    @Test
    fun `a 30 cm card spanning 108 px makes a 216 px target 60 cm`() {
        val (refStart, refEnd) = spanOf(108.0)
        val measured = MeasurementMath.measure(
            reference = MeasurementReference.SITE_CARD,
            referenceStart = refStart,
            referenceEnd = refEnd,
            measuredStart = PixelPoint(120f, 260f),
            measuredEnd = PixelPoint(336f, 260f),
        )!!
        assertEquals(0.60, measured.metres, 1e-9)
        assertEquals(1.9685, measured.feet, 1e-4)
        assertEquals("0.60 m", measured.metresLabel)
        assertEquals("1.97 ft", measured.feetLabel)
        assertEquals("0.60 m  ·  1.97 ft", measured.headline)
        assertTrue(measured.uncertaintyMeters > 0.0)
    }

    @Test
    fun `uncertainty grows with tap tolerance and shrinks with longer spans`() {
        val (aStart, aEnd) = spanOf(100.0)
        val shortSpan = MeasurementMath.measure(
            MeasurementReference.SITE_CARD,
            aStart,
            aEnd,
            PixelPoint(0f, 50f),
            PixelPoint(200f, 50f),
        )!!
        val (bStart, bEnd) = spanOf(400.0)
        val longSpan = MeasurementMath.measure(
            MeasurementReference.SITE_CARD,
            bStart,
            bEnd,
            PixelPoint(0f, 50f),
            PixelPoint(800f, 50f),
        )!!
        assertEquals(shortSpan.metres, longSpan.metres, 1e-9)
        assertTrue(longSpan.uncertaintyMeters < shortSpan.uncertaintyMeters)

        val sloppy = MeasurementMath.uncertaintyMeters(
            metres = 2.0,
            referenceSpanPx = 200.0,
            measuredSpanPx = 200.0,
            tapTolerancePx = 10.0,
        )
        val precise = MeasurementMath.uncertaintyMeters(
            metres = 2.0,
            referenceSpanPx = 200.0,
            measuredSpanPx = 200.0,
            tapTolerancePx = 1.0,
        )
        assertTrue(sloppy > precise)
    }

    @Test
    fun `degenerate spans are refused instead of reported as zero`() {
        val (refStart, refEnd) = spanOf(4.0) // below MIN_SPAN_PX
        assertNull(
            MeasurementMath.measure(
                MeasurementReference.SITE_CARD,
                refStart,
                refEnd,
                PixelPoint(0f, 50f),
                PixelPoint(200f, 50f),
            ),
        )
        val (goodStart, goodEnd) = spanOf(108.0)
        assertNull(
            MeasurementMath.measure(
                MeasurementReference.SITE_CARD,
                goodStart,
                goodEnd,
                PixelPoint(0f, 50f),
                PixelPoint(4f, 50f),
            ),
        )
    }

    @Test
    fun `implausible distances are refused`() {
        val (refStart, refEnd) = spanOf(10.0) // 0.03 m per px
        assertNull(
            MeasurementMath.measure(
                MeasurementReference.SITE_CARD,
                refStart,
                refEnd,
                PixelPoint(0f, 50f),
                PixelPoint(2_000f, 50f), // ~66 m, above MAX_PLAUSIBLE_METERS
            ),
        )
    }

    @Test
    fun `uncertainty label uses centimetres under one metre`() {
        assertEquals("\u00B1 6 cm", MeasurementMath.formatUncertainty(0.06, 0.6))
        assertEquals("\u00B1 0.10 m", MeasurementMath.formatUncertainty(0.10, 2.4))
    }

    @Test
    fun `the measurement model keeps the evidence fields`() {
        val (refStart, refEnd) = spanOf(108.0)
        val result = MeasurementMath.measure(
            MeasurementReference.SHINGLE,
            refStart,
            refEnd,
            PixelPoint(120f, 260f),
            PixelPoint(336f, 260f),
        )
        assertNotNull(result)
        val model = result!!.toModel(
            id = "meas-test",
            label = "Hail strike",
            calibratedAtEpochMs = 1_763_000_000_000L,
            source = MediaSource.SIMULATED,
        )
        assertEquals("meas-test", model.id)
        assertEquals("Hail strike", model.label)
        assertEquals(MeasurementReference.SHINGLE.label, model.referenceLabel)
        assertEquals(0.90, model.referenceMeters, 1e-9)
        assertEquals(result.metres, model.meters, 1e-9)
        assertEquals(result.measuredSpanPx, model.pixelSpan, 1e-9)
        assertEquals(MediaSource.SIMULATED, model.source)
        assertEquals(1_763_000_000_000L, model.calibratedAtEpochMs)
        assertEquals(result.feet, model.feet, 1e-9)
    }
}
