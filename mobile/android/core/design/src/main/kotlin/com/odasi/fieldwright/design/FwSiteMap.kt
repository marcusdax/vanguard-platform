package com.odasi.fieldwright.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.HotZone
import com.odasi.fieldwright.model.Money
import kotlin.math.roundToInt
import java.util.Locale

/**
 * A job as it appears on the map: the id a tap reports, the fix, a human label for
 * accessibility, and the payout the pin is labelled with. Rendering concern, so it lives in
 * `core:design`; the domain `Job` is mapped to it by the feature.
 */
data class MapPin(
    val id: String,
    val point: GeoPoint,
    val label: String,
    val payoutCents: Int,
    val selected: Boolean = false,
)

/**
 * A geofence ring drawn on the map (the same radius the arrival check uses). Design-local
 * because it is a rendering concern; [radiusMeters] must come from the job's site.
 */
data class FwGeofence(
    val id: String,
    val point: GeoPoint,
    val radiusMeters: Int,
)

/** Smallest viewport span so a single fix does not zoom to a meaningless scale (~450 m). */
private const val MIN_SPAN_DEGREES = 0.004

/** Metres per degree of latitude. Good enough for a parcel-scale map. */
private const val METERS_PER_DEGREE_LAT = 111_320.0

private val mapPinLabelStyle = TextStyle(
    fontFamily = Fw.fonts.outfit,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    color = Fw.colors.ink,
    fontFeatureSettings = "tnum",
)

private val mapZoneLabelStyle = TextStyle(
    fontFamily = Fw.fonts.outfit,
    fontWeight = FontWeight.Medium,
    fontSize = 10.sp,
    color = Fw.colors.ink,
    letterSpacing = 0.06.em,
)

/**
 * The parchment site map.
 *
 * It is a real Compose `Canvas`, not a screenshot: parcels, roads and a vignette are drawn
 * from deterministic geometry, then the operator fix (with its accuracy halo), hot-zone
 * surge rings, geofence rings, an optional route polyline and payout-labelled job pins are
 * projected from lat/lng. The projection is derived only from the points and the measured
 * canvas size, so the picture is identical across recompositions.
 *
 * Taps are handled by transparent 48 dp overlays per pin — that keeps the pins accessible
 * (each is a real semantics node with a label and a selected state), instead of a
 * tap-only canvas. The map as a whole exposes a description; the visual marks are
 * decorative and are not individually focusable.
 *
 * @param operatorFix the operator's last fix, or null when location is unavailable.
 * @param routeTo destination for the optional route polyline (the next accepted job).
 * @param geofences site geofences to outline; radius is metres.
 * @param accessibleDescription overrides the generated map description.
 */
@Composable
fun SiteMapCanvas(
    operatorFix: GeoPoint?,
    jobs: List<MapPin>,
    hotZones: List<HotZone>,
    routeTo: GeoPoint? = null,
    modifier: Modifier = Modifier,
    onPinClick: (String) -> Unit = {},
    geofences: List<FwGeofence> = emptyList(),
    accessibleDescription: String? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val description = accessibleDescription
        ?: "Site map · ${jobs.size} job pins · ${hotZones.size} hot zones"
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Fw.dimens.mapHeight)
            .clip(Fw.shape.card)
            .semantics { contentDescription = description },
    ) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = with(density) {
            if (constraints.hasBoundedHeight) {
                constraints.maxHeight.toFloat()
            } else {
                Fw.dimens.mapHeight.toPx()
            }
        }.coerceAtLeast(1f)
        val heightDp = with(density) { heightPx.toDp() }

        val projection = remember(widthPx, heightPx, operatorFix, jobs, hotZones, routeTo, geofences) {
            val points = buildList {
                if (operatorFix != null) add(operatorFix)
                jobs.forEach { pin -> add(pin.point) }
                hotZones.forEach { zone -> add(zone.point) }
                if (routeTo != null) add(routeTo)
                geofences.forEach { fence -> add(fence.point) }
            }
            MapProjection(
                points = points,
                widthPx = widthPx,
                heightPx = heightPx,
            )
        }
        val pinTargetPx = with(density) { Fw.dimens.mapPinTarget.toPx() }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp),
        ) {
            drawRect(color = Fw.colors.mapPaper)
            drawParcels()
            drawRoads()
            drawVignette()
            hotZones.forEach { zone -> drawHotZone(projection, zone, textMeasurer) }
            geofences.forEach { fence -> drawGeofence(projection, fence) }
            if (operatorFix != null && routeTo != null) {
                drawRoute(projection.project(operatorFix), projection.project(routeTo))
            }
            jobs.forEach { pin -> drawPin(projection, pin, textMeasurer) }
            operatorFix?.let { fix -> drawOperatorFix(projection, fix) }
        }

        jobs.forEach { pin ->
            val center = projection.project(pin.point)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            x = (center.x - pinTargetPx / 2f).roundToInt(),
                            y = (center.y - pinTargetPx / 2f).roundToInt(),
                        )
                    }
                    .size(Fw.dimens.mapPinTarget)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onPinClick(pin.id) }
                    .semantics {
                        contentDescription = "${pin.label}, ${Money.format(pin.payoutCents)}"
                        this.selected = pin.selected
                    },
            )
        }
    }
}

/**
 * Equirectangular projection from the point cloud's bounds. Deterministic: same points and
 * same canvas size produce the same pixels, every recomposition.
 */
private class MapProjection(
    points: List<GeoPoint>,
    private val widthPx: Float,
    private val heightPx: Float,
) {
    private val minLat: Double
    private val spanLat: Double
    private val minLng: Double
    private val spanLng: Double

    /** Metres per vertical pixel, used to turn accuracy and geofence radii into pixels. */
    val metersPerPixel: Float

    init {
        if (points.isEmpty()) {
            minLat = 0.0
            spanLat = MIN_SPAN_DEGREES
            minLng = 0.0
            spanLng = MIN_SPAN_DEGREES
        } else {
            val latMin = points.minOf { it.lat }
            val latMax = points.maxOf { it.lat }
            val lngMin = points.minOf { it.lng }
            val lngMax = points.maxOf { it.lng }
            // Pad the viewport by a third of the spread, with a floor so a single fix is
            // still a readable neighbourhood rather than an extreme zoom.
            val padLat = ((latMax - latMin) * 0.35).coerceAtLeast(MIN_SPAN_DEGREES * 0.5)
            val padLng = ((lngMax - lngMin) * 0.35).coerceAtLeast(MIN_SPAN_DEGREES * 0.5)
            minLat = latMin - padLat
            minLng = lngMin - padLng
            spanLat = ((latMax - latMin) + padLat * 2.0).coerceAtLeast(MIN_SPAN_DEGREES)
            spanLng = ((lngMax - lngMin) + padLng * 2.0).coerceAtLeast(MIN_SPAN_DEGREES)
        }
        metersPerPixel = ((spanLat * METERS_PER_DEGREE_LAT) / heightPx).toFloat()
    }

    fun project(point: GeoPoint): Offset {
        val x = ((point.lng - minLng) / spanLng).toFloat() * widthPx
        val y = (1.0 - (point.lat - minLat) / spanLat).toFloat() * heightPx
        return Offset(x.toFloat(), y.toFloat())
    }

    fun radiusPx(meters: Float): Float =
        (meters / metersPerPixel).coerceAtLeast(1f)
}

/** Deterministic pseudo-random in [0, 1) for parcel/road jitter. Same seed, same parcel. */
private fun jitter(seed: Int): Float {
    var x = seed * 374_761_393 + 668_265_263
    x = (x xor (x shr 13)) * 1_274_126_177
    x = x xor (x shr 16)
    return ((x and 0x7fff_ffff) % 1_000) / 1_000f
}

private fun DrawScope.drawParcels() {
    val columns = 5
    val rows = 6
    val cellWidth = size.width / columns
    val cellHeight = size.height / rows
    val strokeWidth = Fw.dimens.hairline.toPx()
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            val seed = row * 31 + column * 7
            val left = column * cellWidth
            val top = row * cellHeight
            val topLeft = Offset(
                left + jitter(seed) * cellWidth * 0.14f,
                top + jitter(seed + 1) * cellHeight * 0.14f,
            )
            val topRight = Offset(
                left + cellWidth - jitter(seed + 2) * cellWidth * 0.14f,
                top + jitter(seed + 3) * cellHeight * 0.14f,
            )
            val bottomRight = Offset(
                left + cellWidth - jitter(seed + 4) * cellWidth * 0.14f,
                top + cellHeight - jitter(seed + 5) * cellHeight * 0.14f,
            )
            val bottomLeft = Offset(
                left + jitter(seed + 6) * cellWidth * 0.14f,
                top + cellHeight - jitter(seed + 7) * cellHeight * 0.14f,
            )
            val parcel = Path().apply {
                moveTo(topLeft.x, topLeft.y)
                lineTo(topRight.x, topRight.y)
                lineTo(bottomRight.x, bottomRight.y)
                lineTo(bottomLeft.x, bottomLeft.y)
                close()
            }
            drawPath(parcel, color = Fw.colors.mapParcel.copy(alpha = 0.92f))
            drawPath(
                parcel,
                color = Fw.colors.line.copy(alpha = 0.55f),
                style = Stroke(width = strokeWidth),
            )
        }
    }
}

private fun DrawScope.drawRoads() {
    val mainWidth = 7.dp.toPx()
    val minorWidth = 4.dp.toPx()
    val hairline = Fw.dimens.hairline.toPx()
    val centreLine = PathEffect.dashPathEffect(
        floatArrayOf(9.dp.toPx(), 7.dp.toPx()),
        0f,
    )

    val mainRoad = Path().apply {
        moveTo(-size.width * 0.05f, size.height * 0.72f)
        cubicTo(
            size.width * 0.26f, size.height * 0.60f,
            size.width * 0.58f, size.height * 0.48f,
            size.width * 1.05f, size.height * 0.36f,
        )
    }
    drawPath(
        mainRoad,
        color = Fw.colors.mapRoad,
        style = Stroke(width = mainWidth, cap = StrokeCap.Round),
    )
    drawPath(
        mainRoad,
        color = Fw.colors.line.copy(alpha = 0.75f),
        style = Stroke(width = hairline, cap = StrokeCap.Round, pathEffect = centreLine),
    )

    val crossRoad = Path().apply {
        moveTo(size.width * 0.24f, -size.height * 0.05f)
        cubicTo(
            size.width * 0.30f, size.height * 0.30f,
            size.width * 0.34f, size.height * 0.62f,
            size.width * 0.44f, size.height * 1.05f,
        )
    }
    drawPath(
        crossRoad,
        color = Fw.colors.mapRoad,
        style = Stroke(width = minorWidth, cap = StrokeCap.Round),
    )

    val lane = Path().apply {
        moveTo(size.width * 0.72f, -size.height * 0.05f)
        cubicTo(
            size.width * 0.70f, size.height * 0.22f,
            size.width * 0.82f, size.height * 0.52f,
            size.width * 0.78f, size.height * 1.05f,
        )
    }
    drawPath(
        lane,
        color = Fw.colors.mapRoad.copy(alpha = 0.8f),
        style = Stroke(width = minorWidth * 0.7f, cap = StrokeCap.Round),
    )
}

/** The one gradient the visual system allows: a subtle parchment vignette. */
private fun DrawScope.drawVignette() {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Fw.colors.sheetShadow.copy(alpha = 0.12f)),
            center = center,
            radius = size.maxDimension * 0.72f,
        ),
    )
}

private fun DrawScope.drawHotZone(
    projection: MapProjection,
    zone: HotZone,
    textMeasurer: TextMeasurer,
) {
    val centre = projection.project(zone.point)
    val radius = projection.radiusPx(zone.radiusMeters.toFloat()).coerceAtLeast(8.dp.toPx())
    drawCircle(
        color = Fw.colors.amber.copy(alpha = 0.10f),
        radius = radius,
        center = centre,
    )
    drawCircle(
        color = Fw.colors.oxide.copy(alpha = 0.55f),
        radius = radius,
        center = centre,
        style = Stroke(
            width = Fw.dimens.hairline.toPx() * 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())),
        ),
    )
    val label = "${String.format(Locale.US, "%.1f", zone.surgeMultiplier)}× · ${zone.label}"
    val measured = textMeasurer.measure(AnnotatedString(label), style = mapZoneLabelStyle)
    val padX = 4.dp.toPx()
    val padY = 2.dp.toPx()
    val chipWidth = measured.size.width + padX * 2
    val chipHeight = measured.size.height + padY * 2
    val minLeft = 2.dp.toPx()
    val maxLeft = (size.width - chipWidth - 2.dp.toPx()).coerceAtLeast(minLeft)
    val chipLeft = (centre.x - chipWidth / 2f).coerceIn(minLeft, maxLeft)
    val chipTop = (centre.y - radius - chipHeight - 4.dp.toPx()).coerceAtLeast(2.dp.toPx())
    drawRoundRect(
        color = Fw.colors.sheet,
        topLeft = Offset(chipLeft, chipTop),
        size = Size(chipWidth, chipHeight),
        cornerRadius = CornerRadius(padY * 2f, padY * 2f),
    )
    drawRoundRect(
        color = Fw.colors.oxide.copy(alpha = 0.55f),
        topLeft = Offset(chipLeft, chipTop),
        size = Size(chipWidth, chipHeight),
        cornerRadius = CornerRadius(padY * 2f, padY * 2f),
        style = Stroke(width = Fw.dimens.hairline.toPx()),
    )
    drawText(measured, topLeft = Offset(chipLeft + padX, chipTop + padY))
}

private fun DrawScope.drawGeofence(projection: MapProjection, fence: FwGeofence) {
    val centre = projection.project(fence.point)
    val radius = projection.radiusPx(fence.radiusMeters.toFloat()).coerceAtLeast(6.dp.toPx())
    drawCircle(
        color = Fw.colors.oxide.copy(alpha = 0.06f),
        radius = radius,
        center = centre,
    )
    drawCircle(
        color = Fw.colors.oxide.copy(alpha = 0.65f),
        radius = radius,
        center = centre,
        style = Stroke(
            width = Fw.dimens.hairline.toPx() * 1.4f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
        ),
    )
}

private fun DrawScope.drawRoute(from: Offset, to: Offset) {
    val bendX = (to.y - from.y) * 0.16f
    val bendY = (from.x - to.x) * 0.16f
    val route = Path().apply {
        moveTo(from.x, from.y)
        cubicTo(
            from.x + (to.x - from.x) * 0.33f + bendX,
            from.y + (to.y - from.y) * 0.33f + bendY,
            from.x + (to.x - from.x) * 0.66f + bendX,
            from.y + (to.y - from.y) * 0.66f + bendY,
            to.x,
            to.y,
        )
    }
    drawPath(
        route,
        color = Fw.colors.olive,
        style = Stroke(
            width = Fw.dimens.stroke.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 7.dp.toPx())),
        ),
    )
}

private fun DrawScope.drawPin(
    projection: MapProjection,
    pin: MapPin,
    textMeasurer: TextMeasurer,
) {
    val centre = projection.project(pin.point)
    val dotRadius = Fw.dimens.mapPinRadius.toPx()
    val fill = if (pin.selected) Fw.colors.oxide else Fw.colors.olive
    drawCircle(
        color = Fw.colors.sheet,
        radius = dotRadius + Fw.dimens.stroke.toPx(),
        center = centre,
    )
    drawCircle(color = fill, radius = dotRadius, center = centre)
    drawCircle(
        color = if (pin.selected) Fw.colors.oxide else Fw.colors.oliveDeep,
        radius = dotRadius * 0.32f,
        center = centre,
    )

    val measured = textMeasurer.measure(
        AnnotatedString(Money.format(pin.payoutCents)),
        style = mapPinLabelStyle,
    )
    val padX = 6.dp.toPx()
    val padY = 3.dp.toPx()
    val chipWidth = measured.size.width + padX * 2
    val chipHeight = measured.size.height + padY * 2
    val minLeft = 2.dp.toPx()
    val maxLeft = (size.width - chipWidth - 2.dp.toPx()).coerceAtLeast(minLeft)
    val chipLeft = (centre.x - chipWidth / 2f).coerceIn(minLeft, maxLeft)
    val chipTop = (centre.y - dotRadius - Fw.dimens.stroke.toPx() - chipHeight - 3.dp.toPx())
        .coerceAtLeast(2.dp.toPx())
    drawRoundRect(
        color = if (pin.selected) Fw.colors.oliveTint else Fw.colors.sheet,
        topLeft = Offset(chipLeft, chipTop),
        size = Size(chipWidth, chipHeight),
        cornerRadius = CornerRadius(padY * 2f, padY * 2f),
    )
    drawRoundRect(
        color = if (pin.selected) Fw.colors.oxide else Fw.colors.line,
        topLeft = Offset(chipLeft, chipTop),
        size = Size(chipWidth, chipHeight),
        cornerRadius = CornerRadius(padY * 2f, padY * 2f),
        style = Stroke(width = Fw.dimens.hairline.toPx()),
    )
    drawText(measured, topLeft = Offset(chipLeft + padX, chipTop + padY))
}

private fun DrawScope.drawOperatorFix(projection: MapProjection, fix: GeoPoint) {
    val centre = projection.project(fix)
    val halo = projection.radiusPx(fix.accuracyMeters).coerceAtLeast(Fw.dimens.operatorDot.toPx() * 1.6f)
    drawCircle(
        color = Fw.colors.olive.copy(alpha = 0.14f),
        radius = halo,
        center = centre,
    )
    drawCircle(
        color = Fw.colors.olive.copy(alpha = 0.40f),
        radius = halo,
        center = centre,
        style = Stroke(width = Fw.dimens.hairline.toPx()),
    )
    drawCircle(
        color = Fw.colors.sheet,
        radius = Fw.dimens.operatorDot.toPx(),
        center = centre,
    )
    drawCircle(
        color = Fw.colors.olive,
        radius = Fw.dimens.operatorDot.toPx() * 0.62f,
        center = centre,
    )
}

private fun previewPoint(lat: Double, lng: Double): GeoPoint = GeoPoint(
    lat = lat,
    lng = lng,
    recordedAtEpochMs = 1_700_000_000_000L,
    accuracyMeters = 9f,
)

private val previewOperator = previewPoint(30.2691, -97.7421)

private val previewPins = listOf(
    MapPin(
        id = "job-1",
        point = previewPoint(30.2712, -97.7402),
        label = "Roof hail assessment · 1841 Creekside Dr",
        payoutCents = 42_000,
        selected = true,
    ),
    MapPin(
        id = "job-2",
        point = previewPoint(30.2662, -97.7458),
        label = "Annual RPZ test · 902 Ridgeview",
        payoutCents = 9_500,
    ),
    MapPin(
        id = "job-3",
        point = previewPoint(30.2728, -97.7471),
        label = "Monthly property walk · Sabine Court",
        payoutCents = 6_500,
    ),
)

private val previewHotZone = HotZone(
    label = "Creekside",
    point = previewPoint(30.2701, -97.7435),
    radiusMeters = 850,
    surgeMultiplier = 1.4,
    reason = "Hail event density and two inspectors offline",
)

@Preview(name = "Site map", showBackground = true, widthDp = 360)
@Composable
private fun SiteMapCanvasPreview() {
    FwPreviewSurface {
        SiteMapCanvas(
            operatorFix = previewOperator,
            jobs = previewPins,
            hotZones = listOf(previewHotZone),
            routeTo = previewPoint(30.2712, -97.7402),
            onPinClick = {},
            geofences = listOf(FwGeofence(id = "job-1", point = previewPoint(30.2712, -97.7402), radiusMeters = 120)),
            modifier = Modifier.height(Fw.dimens.mapHeight),
        )
    }
}

@Preview(name = "Site map · off the clock", showBackground = true, widthDp = 360)
@Composable
private fun SiteMapCanvasEmptyPreview() {
    FwPreviewSurface {
        SiteMapCanvas(
            operatorFix = null,
            jobs = emptyList(),
            hotZones = emptyList(),
            modifier = Modifier.height(Fw.dimens.mapHeight),
        )
    }
}
