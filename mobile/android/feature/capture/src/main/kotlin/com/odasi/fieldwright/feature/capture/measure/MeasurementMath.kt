package com.odasi.fieldwright.feature.capture.measure

import com.odasi.fieldwright.model.Measurement
import com.odasi.fieldwright.model.MediaSource
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A known object the operator can hold in frame to calibrate the capture. The metric value
 * is the object's real dimension; the picker never asks the operator to type a scale.
 */
data class MeasurementReference(
    val id: String,
    val label: String,
    val shortLabel: String,
    val meters: Double,
) {
    companion object {
        /** Standard 30 cm site card the operator carries. */
        val SITE_CARD = MeasurementReference(
            id = "site_card",
            label = "Site card · 30 cm",
            shortLabel = "Site card 30 cm",
            meters = 0.30,
        )

        /** A standard three-tab shingle course. */
        val SHINGLE = MeasurementReference(
            id = "shingle",
            label = "Shingle · 90 cm",
            shortLabel = "Shingle 90 cm",
            meters = 0.90,
        )

        /** A typical exterior door leaf, used where no card is available. */
        val DOOR = MeasurementReference(
            id = "door",
            label = "Door · 91 cm",
            shortLabel = "Door 91 cm",
            meters = 0.91,
        )

        val ALL: List<MeasurementReference> = listOf(SITE_CARD, SHINGLE, DOOR)

        fun byId(id: String): MeasurementReference? = ALL.firstOrNull { it.id == id }
    }
}

/** A tap on the preview, in display pixels. */
data class PixelPoint(val x: Float, val y: Float) {
    fun distanceTo(other: PixelPoint): Double = hypot((x - other.x).toDouble(), (y - other.y).toDouble())
}

/**
 * A calibrated result: metres (the evidence unit) and feet (the field unit), plus the
 * uncertainty the tap precision implies.
 */
data class MeasurementResult(
    val reference: MeasurementReference,
    val referenceSpanPx: Double,
    val measuredSpanPx: Double,
    val metres: Double,
    val uncertaintyMeters: Double,
) {
    val feet: Double get() = MeasurementMath.metersToFeet(metres)

    val metresLabel: String get() = MeasurementMath.formatMeters(metres)

    val feetLabel: String get() = MeasurementMath.formatFeet(feet)

    val headline: String get() = "$metresLabel  ·  $feetLabel"

    val uncertaintyLabel: String get() = MeasurementMath.formatUncertainty(uncertaintyMeters, metres)

    fun toModel(
        id: String,
        label: String,
        calibratedAtEpochMs: Long,
        source: MediaSource,
    ): Measurement = Measurement(
        id = id,
        label = label,
        meters = metres,
        referenceLabel = reference.label,
        referenceMeters = reference.meters,
        pixelSpan = measuredSpanPx,
        calibratedAtEpochMs = calibratedAtEpochMs,
        source = source,
    )
}

/**
 * Pure calibrated-measurement maths.
 *
 * The operator taps the two ends of the chosen reference object, then the two ends of the
 * thing being measured. Both spans are display pixels, so the result is a ratio and is
 * independent of the preview's zoom/aspect fill:
 *
 *     pixelsPerMetre = referenceSpanPx / referenceMeters
 *     metres        = measuredSpanPx / pixelsPerMetre
 *
 * Uncertainty: each tap is assumed to be accurate to ±[TAP_TOLERANCE_PX]. A span therefore
 * carries ±2 tolerances, and relative error is the sum of the reference-span and
 * measured-span relative errors. This is *tap* uncertainty only; the dominant real-world
 * error is perspective (the reference must sit in the same plane as the target), which the
 * UI states explicitly rather than hides. No camera intrinsics are invented here.
 */
object MeasurementMath {

    /** Human tap precision on a phone preview, in display pixels, per endpoint. */
    const val TAP_TOLERANCE_PX: Double = 3.0

    /** Below this a span is a stray tap, not a measurement. */
    const val MIN_SPAN_PX: Double = 8.0

    /** Upper sanity bound: a field measurement longer than this means a mis-tap. */
    const val MAX_PLAUSIBLE_METERS: Double = 30.0

    /**
     * Feet per metre, matching `core:model Measurement.feet` exactly. Both the calibrated
     * result shown on screen and the persisted Measurement must agree to the last bit, so
     * the constant is deliberately the same 3.28084 the model uses rather than a longer
     * conversion factor that would make the two disagree in the seventh decimal.
     */
    private const val FEET_PER_METRE = 3.28084

    fun spanPx(a: PixelPoint, b: PixelPoint): Double = a.distanceTo(b)

    /** Pixels per metre implied by a reference of [referenceMeters] spanning [referenceSpanPx]. */
    fun pixelsPerMetre(referenceSpanPx: Double, referenceMeters: Double): Double {
        require(referenceSpanPx > 0.0) { "The reference span must be positive." }
        require(referenceMeters > 0.0) { "The reference length must be positive." }
        return referenceSpanPx / referenceMeters
    }

    fun distanceMeters(measuredSpanPx: Double, pixelsPerMetre: Double): Double {
        require(pixelsPerMetre > 0.0) { "Calibrate against a reference before measuring." }
        return measuredSpanPx / pixelsPerMetre
    }

    fun metersToFeet(meters: Double): Double = meters * FEET_PER_METRE

    /**
     * Error propagation for two pixel spans, each with two taps of ±[tapTolerancePx].
     * Returns an absolute uncertainty in metres.
     */
    fun uncertaintyMeters(
        metres: Double,
        referenceSpanPx: Double,
        measuredSpanPx: Double,
        tapTolerancePx: Double = TAP_TOLERANCE_PX,
    ): Double {
        if (metres <= 0.0 || referenceSpanPx <= 0.0 || measuredSpanPx <= 0.0) return 0.0
        val referenceErrorPx = 2.0 * tapTolerancePx
        val measuredErrorPx = 2.0 * tapTolerancePx
        val relative = referenceErrorPx / referenceSpanPx + measuredErrorPx / measuredSpanPx
        return abs(metres) * relative
    }

    /**
     * Computes the result, or null when either span is degenerate. Returning null (rather
     * than a zero or a NaN) keeps an invalid calibration out of the evidence state.
     */
    fun measure(
        reference: MeasurementReference,
        referenceStart: PixelPoint,
        referenceEnd: PixelPoint,
        measuredStart: PixelPoint,
        measuredEnd: PixelPoint,
        tapTolerancePx: Double = TAP_TOLERANCE_PX,
    ): MeasurementResult? {
        val referenceSpan = spanPx(referenceStart, referenceEnd)
        val measuredSpan = spanPx(measuredStart, measuredEnd)
        if (referenceSpan < MIN_SPAN_PX || measuredSpan < MIN_SPAN_PX) return null

        val pixelsPerMetre = pixelsPerMetre(referenceSpan, reference.meters)
        val metres = distanceMeters(measuredSpan, pixelsPerMetre)
        if (metres <= 0.0 || metres > MAX_PLAUSIBLE_METERS) return null

        return MeasurementResult(
            reference = reference,
            referenceSpanPx = referenceSpan,
            measuredSpanPx = measuredSpan,
            metres = metres,
            uncertaintyMeters = uncertaintyMeters(metres, referenceSpan, measuredSpan, tapTolerancePx),
        )
    }

    fun formatMeters(meters: Double): String = String.format(Locale.US, "%.2f m", meters)

    fun formatFeet(feet: Double): String = String.format(Locale.US, "%.2f ft", feet)

    /** "0.42 m ± 0.03 m" style; cm under a metre reads better in the field. */
    fun formatUncertainty(uncertaintyMeters: Double, metres: Double): String {
        val plusMinus = "\u00B1"
        return if (metres < 1.0) {
            String.format(Locale.US, "%s %.0f cm", plusMinus, uncertaintyMeters * 100.0)
        } else {
            String.format(Locale.US, "%s %.2f m", plusMinus, uncertaintyMeters)
        }
    }
}
