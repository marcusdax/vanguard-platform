package com.odasi.fieldwright.feature.capture.capture

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Locale
import java.util.zip.CRC32

/**
 * Inputs to a synthetic capture frame.
 *
 * Everything that ends up in the image is derived from this spec, and every value is
 * supplied by the caller — no clock, no random, no device state is read inside the
 * renderer. That is what makes the bytes (and therefore the SHA-256 recorded as evidence)
 * reproducible for the same job, step and timestamp.
 */
data class SimulatedFrameSpec(
    val jobId: String,
    val siteLabel: String,
    val stepLabel: String,
    val capturedAtEpochMs: Long,
    val simulatedReason: String,
    val widthPx: Int = DEFAULT_WIDTH,
    val heightPx: Int = DEFAULT_HEIGHT,
) {
    init {
        require(widthPx >= MIN_WIDTH) { "A capture frame must be at least $MIN_WIDTH px wide." }
        require(heightPx >= MIN_HEIGHT) { "A capture frame must be at least $MIN_HEIGHT px tall." }
    }

    /** The UTC instant the frame claims, formatted without locale or time-zone input. */
    val capturedAtLabel: String get() = Instant.ofEpochMilli(capturedAtEpochMs).toString()

    companion object {
        const val DEFAULT_WIDTH: Int = 640
        const val DEFAULT_HEIGHT: Int = 480
        const val MIN_WIDTH: Int = 320
        const val MIN_HEIGHT: Int = 240
    }
}

/**
 * Deterministic renderer for a "simulated frame" — what the app records when there is no
 * camera, no permission, or the device is an emulator.
 *
 * Two deliberate engineering choices:
 *
 * 1. **Pure Kotlin, no Android graphics.** The frame is composed in an `IntArray` raster
 *    with a built-in 5×7 pixel font and encoded as an uncompressed-block PNG. `Bitmap` and
 *    `Canvas` would make the bytes depend on device font rasterisation and GPU state; a
 *    pure renderer means the unit test can prove that two captures with the same inputs
 *    produce the same hash, which is exactly the property the seal relies on.
 * 2. **The frame says it is simulated, in the image itself.** The header, centre watermark
 *    and footer all name the simulated source and the reason. A downstream reader cannot
 *    mistake this file for a camera frame even if the metadata is stripped.
 *
 * The PNG written here is a real, decodable image (8-bit RGB, stored deflate blocks) so it
 * can be shown as a thumbnail and exported in a packet.
 */
object SimulatedFrameRenderer {

    // Synthetic scene palette. These are rendered *image* colours, not UI chrome: the
    // renderer runs in plain JVM unit tests and cannot read Compose theme tokens. The
    // values are borrowed from the Fieldwright palette so a simulated frame still looks
    // like it came from this app (see docs/CONTRACTS.md §3.1).
    private const val DESK = 0xEFE6D4
    private const val SHEET = 0xFFFDF7
    private const val INK = 0x1F1C16
    private const val INK_SOFT = 0x6E6556
    private const val OLIVE = 0x4E5D2C
    private const val OXIDE = 0x94451F
    private const val LINE = 0xD8CBB4
    private const val MAP_PAPER = 0xE9DFC9
    private const val MAP_ROAD = 0xD3C3A6
    private const val MAP_PARCEL = 0xF4EDDD

    /**
     * Renders the frame. The same [spec] always produces byte-identical output: same
     * raster, same font glyphs, stored (uncompressed) PNG blocks, same CRC path.
     */
    fun renderPng(spec: SimulatedFrameSpec): ByteArray = PngEncoder.encodeRgb(spec.widthPx, spec.heightPx, rasterFor(spec))

    /**
     * The watermark lines burned into the footer, in draw order. Public so tests can
     * assert that job, site, step, time and the simulation reason are all named.
     */
    fun watermarkLines(spec: SimulatedFrameSpec): List<String> = listOf(
        "SITE  ${sanitize(spec.siteLabel)}",
        "JOB   ${sanitize(spec.jobId)}",
        "STEP  ${sanitize(spec.stepLabel)}",
        "TAKEN ${spec.capturedAtLabel}",
        "SOURCE SIMULATED",
        "REASON ${sanitize(spec.simulatedReason)}",
    )

    /** One-line caption used by the UI next to a captured frame. */
    fun caption(spec: SimulatedFrameSpec): String =
        "Simulated frame · ${sanitize(spec.jobId)} · ${spec.capturedAtLabel}"

    private fun rasterFor(spec: SimulatedFrameSpec): IntArray {
        val width = spec.widthPx
        val height = spec.heightPx
        val raster = Raster(width, height)
        raster.fill(DESK)

        val headerHeight = (height / 12).coerceAtLeast(28)
        val footerHeight = (height / 4).coerceAtLeast(64)
        val footerTop = height - footerHeight
        val horizon = (height * 55) / 100

        // Scene placeholder: sky, ground, a road band and block silhouettes. It is a
        // stand-in for a lens view, not a depiction of the site.
        raster.rect(0, headerHeight, width, horizon - headerHeight, MAP_PAPER)
        raster.rect(0, horizon, width, footerTop - horizon, MAP_PARCEL)
        raster.rect(0, horizon + (height / 32), width, (height / 16).coerceAtLeast(4), MAP_ROAD)
        val blockWidth = width / 8
        for (i in 0..4) {
            val blockHeight = (height / 20) + (i % 2) * (height / 28)
            raster.rect(width / 16 + i * blockWidth, horizon - blockHeight - headerHeight / 2, blockWidth / 2, blockHeight, MAP_ROAD)
        }
        // Ground hatch lines: fixed stride, so the texture is identical every run.
        var hatchY = horizon + height / 10
        var hatchIndex = 0
        while (hatchY < footerTop - 8) {
            val inset = (hatchIndex % 3) * 24
            raster.rect(inset, hatchY, width - inset * 2, 1, MAP_ROAD)
            hatchY += 26
            hatchIndex++
        }

        // Repeated low-contrast watermark across the scene.
        var bandY = headerHeight + height / 8
        var band = 0
        while (bandY < footerTop - 24) {
            val text = if (band % 2 == 0) "SIMULATED FRAME" else "NO CAMERA IMAGE"
            val scale = 2
            val textWidth = widthOf(text, scale)
            raster.drawText((width - textWidth) / 2, bandY, text, scale, LINE)
            bandY += 64
            band++
        }

        // Frame guides: oxide corner brackets, as the live framing guide would show.
        drawBracket(raster, 28, headerHeight + 20, 44, 3)
        drawBracket(raster, width - 28 - 44, headerHeight + 20, 44, 3)
        drawBracket(raster, 28, footerTop - 20 - 44, 44, 3)
        drawBracket(raster, width - 28 - 44, footerTop - 20 - 44, 44, 3)

        // Seal-style reticle at the optical centre of the frame.
        val centreX = width / 2
        val centreY = (headerHeight + footerTop) / 2
        raster.crosshair(centreX, centreY, 26, 2, OLIVE)
        raster.circleOutline(centreX, centreY, 20, 2, OLIVE)

        // Header band: the first thing a reviewer sees is that this is not a camera image.
        raster.rect(0, 0, width, headerHeight, SHEET)
        raster.rect(0, headerHeight - 1, width, 1, LINE)
        val headerScale = if (width >= 480) 2 else 1
        raster.drawText(16, (headerHeight - 7 * headerScale) / 2, "SIMULATED FRAME", headerScale, OXIDE)
        raster.drawText(width - 16 - widthOf("NOT A CAMERA IMAGE", 1), (headerHeight - 7) / 2, "NOT A CAMERA IMAGE", 1, INK_SOFT)
        drawSealMark(raster, width - 16 - 120, headerHeight / 2, 10, OLIVE)

        // Footer band: the watermark record burned into the pixels.
        raster.rect(0, footerTop, width, footerHeight, SHEET)
        raster.rect(0, footerTop, width, 1, LINE)
        raster.rect(16, footerTop + 4, 64, 2, OLIVE) // signature olive rule
        raster.drawText(16, footerTop + 14, "SIMULATED - NOT CAMERA EVIDENCE", 2, OXIDE)
        var lineY = footerTop + 38
        for (line in watermarkLines(spec)) {
            raster.drawText(16, lineY, line, 1, INK)
            lineY += 11
        }
        raster.drawText(16, footerTop + footerHeight - 13, "FIELDWRIGHT CAPTURE / SIMULATED SOURCE", 1, INK_SOFT)

        return raster.pixels
    }

    private fun drawBracket(raster: Raster, x: Int, y: Int, arm: Int, thickness: Int) {
        val color = OXIDE
        raster.rect(x, y, arm, thickness, color)
        raster.rect(x, y, thickness, arm, color)
    }

    private fun drawSealMark(raster: Raster, centreX: Int, centreY: Int, radius: Int, color: Int) {
        raster.circleOutline(centreX, centreY, radius, 1, color)
        raster.crosshair(centreX, centreY, radius + 3, 1, color)
    }

    /** Uppercases and replaces characters the pixel font cannot draw, deterministically. */
    fun sanitize(value: String): String {
        val normalized = value.uppercase(Locale.ROOT)
        val out = StringBuilder(normalized.length)
        for (ch in normalized) out.append(if (Glyphs.contains(ch)) ch else '?')
        return out.toString()
    }

    private fun widthOf(text: String, scale: Int): Int = text.length * Glyphs.advance * scale

    /**
     * 5×7 pixel font. Rows are five bits, most-significant bit leftmost. Kept as readable
     * strings rather than packed ints: the renderer is evidence-adjacent code and a
     * reviewer should be able to check a glyph without decoding hex.
     */
    private object Glyphs {
        const val advance: Int = 6

        private val table: Map<Char, IntArray> = buildMap {
            fun glyph(rows: String): IntArray =
                rows.split(' ').map { row ->
                    row.fold(0) { acc, bit -> (acc shl 1) or (if (bit == '1') 1 else 0) }
                }.toIntArray()

            val definitions = listOf(
                'A' to "01110 10001 10001 11111 10001 10001 10001",
                'B' to "11110 10001 10001 11110 10001 10001 11110",
                'C' to "01110 10001 10000 10000 10000 10001 01110",
                'D' to "11110 10001 10001 10001 10001 10001 11110",
                'E' to "11111 10000 10000 11110 10000 10000 11111",
                'F' to "11111 10000 10000 11110 10000 10000 10000",
                'G' to "01110 10001 10000 10111 10001 10001 01111",
                'H' to "10001 10001 10001 11111 10001 10001 10001",
                'I' to "01110 00100 00100 00100 00100 00100 01110",
                'J' to "00111 00010 00010 00010 00010 10010 01100",
                'K' to "10001 10010 10100 11000 10100 10010 10001",
                'L' to "10000 10000 10000 10000 10000 10000 11111",
                'M' to "10001 11011 10101 10101 10001 10001 10001",
                'N' to "10001 11001 10101 10011 10001 10001 10001",
                'O' to "01110 10001 10001 10001 10001 10001 01110",
                'P' to "11110 10001 10001 11110 10000 10000 10000",
                'Q' to "01110 10001 10001 10001 10101 10010 01101",
                'R' to "11110 10001 10001 11110 10100 10010 10001",
                'S' to "01111 10000 10000 01110 00001 00001 11110",
                'T' to "11111 00100 00100 00100 00100 00100 00100",
                'U' to "10001 10001 10001 10001 10001 10001 01110",
                'V' to "10001 10001 10001 10001 10001 01010 00100",
                'W' to "10001 10001 10001 10101 10101 10101 01010",
                'X' to "10001 10001 01010 00100 01010 10001 10001",
                'Y' to "10001 10001 01010 00100 00100 00100 00100",
                'Z' to "11111 00001 00010 00100 01000 10000 11111",
                '0' to "01110 10001 10011 10101 11001 10001 01110",
                '1' to "00100 01100 00100 00100 00100 00100 01110",
                '2' to "01110 10001 00001 00010 00100 01000 11111",
                '3' to "11111 00010 00100 00010 00001 10001 01110",
                '4' to "00010 00110 01010 10010 11111 00010 00010",
                '5' to "11111 10000 11110 00001 00001 10001 01110",
                '6' to "00110 01000 10000 11110 10001 10001 01110",
                '7' to "11111 00001 00010 00100 01000 01000 01000",
                '8' to "01110 10001 10001 01110 10001 10001 01110",
                '9' to "01110 10001 10001 01111 00001 00010 01100",
                ' ' to "00000 00000 00000 00000 00000 00000 00000",
                '-' to "00000 00000 00000 11111 00000 00000 00000",
                '.' to "00000 00000 00000 00000 00000 01100 01100",
                ',' to "00000 00000 00000 00000 01100 00100 01000",
                ':' to "00000 00000 01100 01100 00000 01100 01100",
                '/' to "00001 00010 00010 00100 01000 01000 10000",
                '(' to "00010 00100 01000 01000 01000 00100 00010",
                ')' to "01000 00100 00010 00010 00010 00100 01000",
                '!' to "00100 00100 00100 00100 00100 00000 00100",
                '?' to "01110 10001 00001 00010 00100 00000 00100",
                '_' to "00000 00000 00000 00000 00000 00000 11111",
            )
            for ((ch, rows) in definitions) put(ch, glyph(rows))
        }

        fun contains(ch: Char): Boolean = table.containsKey(ch)

        fun bits(ch: Char): IntArray = table[ch] ?: table.getValue(' ')
    }

    private class Raster(val width: Int, val height: Int) {
        val pixels: IntArray = IntArray(width * height)

        fun fill(color: Int) {
            pixels.fill(color)
        }

        fun set(x: Int, y: Int, color: Int) {
            if (x in 0 until width && y in 0 until height) pixels[y * width + x] = color
        }

        fun rect(x: Int, y: Int, w: Int, h: Int, color: Int) {
            val x0 = x.coerceIn(0, width)
            val x1 = (x + w).coerceIn(0, width)
            val y0 = y.coerceIn(0, height)
            val y1 = (y + h).coerceIn(0, height)
            for (yy in y0 until y1) {
                val row = yy * width
                for (xx in x0 until x1) pixels[row + xx] = color
            }
        }

        fun drawText(x: Int, y: Int, text: String, scale: Int, color: Int) {
            var cursor = x
            for (ch in text) {
                val glyph = Glyphs.bits(ch)
                for (row in 0 until 7) {
                    val bits = glyph[row]
                    for (col in 0 until 5) {
                        if (bits and (1 shl (4 - col)) != 0) rect(cursor + col * scale, y + row * scale, scale, scale, color)
                    }
                }
                cursor += Glyphs.advance * scale
            }
        }

        fun crosshair(centreX: Int, centreY: Int, arm: Int, thickness: Int, color: Int) {
            rect(centreX - arm, centreY - thickness / 2, arm * 2 + 1, thickness, color)
            rect(centreX - thickness / 2, centreY - arm, thickness, arm * 2 + 1, color)
        }

        fun circleOutline(centreX: Int, centreY: Int, radius: Int, thickness: Int, color: Int) {
            val outer = radius + thickness
            for (dy in -outer..outer) {
                for (dx in -outer..outer) {
                    val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
                    if (distance >= radius - 0.5 && distance <= radius + thickness - 0.5) set(centreX + dx, centreY + dy, color)
                }
            }
        }
    }

    /**
     * Minimal PNG writer: 8-bit truecolour, one filter byte (None) per scanline, and a
     * zlib stream made of *stored* deflate blocks. Stored blocks avoid depending on a
     * zlib implementation (JDK vs Android differ in compressed bytes), so the output is
     * reproducible on every device and in JVM unit tests.
     */
    private object PngEncoder {
        private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        private const val MAX_STORED_BLOCK = 65_535

        fun encodeRgb(width: Int, height: Int, pixels: IntArray): ByteArray {
            require(pixels.size == width * height) { "Raster size does not match frame dimensions." }
            val raw = ByteArray(height * (1 + width * 3))
            var offset = 0
            for (y in 0 until height) {
                raw[offset++] = 0 // filter type: None
                val row = y * width
                for (x in 0 until width) {
                    val color = pixels[row + x]
                    raw[offset++] = (color ushr 16).toByte()
                    raw[offset++] = (color ushr 8).toByte()
                    raw[offset++] = color.toByte()
                }
            }

            val ihdr = ByteArrayOutputStream(13).apply {
                writeIntBeTo(this, width)
                writeIntBeTo(this, height)
                write(8) // bit depth
                write(2) // colour type: truecolour RGB
                write(0) // compression: deflate
                write(0) // filter method
                write(0) // interlace: none
            }.toByteArray()

            val out = ByteArrayOutputStream(raw.size / 4 + 1024)
            out.write(SIGNATURE)
            writeChunk(out, "IHDR", ihdr)
            writeChunk(out, "IDAT", zlibStored(raw))
            writeChunk(out, "IEND", ByteArray(0))
            return out.toByteArray()
        }

        private fun zlibStored(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(data.size + data.size / MAX_STORED_BLOCK * 5 + 16)
            out.write(0x78) // CMF: deflate, 32K window
            out.write(0x01) // FLG: no dictionary, fastest level
            if (data.isEmpty()) {
                out.write(1)
                out.write(0); out.write(0)
                out.write(0xFF); out.write(0xFF)
            } else {
                var offset = 0
                while (offset < data.size) {
                    val length = minOf(MAX_STORED_BLOCK, data.size - offset)
                    val isFinal = offset + length >= data.size
                    out.write(if (isFinal) 1 else 0) // BFINAL + BTYPE=00 (stored)
                    out.write(length and 0xFF)
                    out.write((length ushr 8) and 0xFF)
                    val complement = length.inv() and 0xFFFF
                    out.write(complement and 0xFF)
                    out.write((complement ushr 8) and 0xFF)
                    out.write(data, offset, length)
                    offset += length
                }
            }
            writeIntBeTo(out, adler32(data))
            return out.toByteArray()
        }

        private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
            writeIntBeTo(out, data.size)
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            out.write(typeBytes)
            out.write(data)
            val crc = CRC32().apply {
                update(typeBytes)
                update(data)
            }
            writeIntBeTo(out, crc.value.toInt())
        }

        private fun adler32(data: ByteArray): Int {
            var a = 1
            var b = 0
            for (byte in data) {
                a = (a + (byte.toInt() and 0xFF)) % 65_521
                b = (b + a) % 65_521
            }
            return (b shl 16) or a
        }

        private fun writeIntBe(value: Int): ByteArray = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

        private fun writeIntBeTo(out: ByteArrayOutputStream, value: Int) {
            out.write(value ushr 24)
            out.write(value ushr 16)
            out.write(value ushr 8)
            out.write(value)
        }
    }
}
