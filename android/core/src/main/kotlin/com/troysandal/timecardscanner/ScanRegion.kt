package com.troysandal.timecardscanner

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The part of a photo to read, as fractions (0..1) of the upright photo's width and height, top-left origin:
 * what the user dragged a box around on a photo of the whole card. Same rules as `ScanRegion.swift`
 * (plugin/ios/Core): keep the two in sync.
 */
class ScanRegion private constructor(val x: Double, val y: Double, val width: Double, val height: Double) {
    /** The box in pixels: left, top, width, height. */
    data class Pixels(val left: Int, val top: Int, val width: Int, val height: Int)

    /** The box in pixels of a photo of [w] x [h], rounded outward and clamped to the photo. */
    fun pixels(w: Int, h: Int): Pixels {
        val x0 = max(0, floor(x * w).toInt())
        val y0 = max(0, floor(y * h).toInt())
        val x1 = min(w, ceil((x + width) * w).toInt())
        val y1 = min(h, ceil((y + height) * h).toInt())
        return Pixels(x0, y0, max(1, x1 - x0), max(1, y1 - y0))
    }

    companion object {
        /** A box over the middle of a whole card, where its table usually is: the starting box when no table was found. */
        val FALLBACK: ScanRegion = of(0.04, 0.2, 0.92, 0.62)!!

        /**
         * A starting box for the user, around a table that was found: the value cells ([cells], in pixels of an
         * image of [imageWidth] x [imageHeight]) plus the printed `Ck.#` column to their left, the caption row
         * above them, and a small margin. `null` when no cell has area. Mirrors `ScanRegion.enclosing` (Swift).
         */
        fun enclosing(cells: List<List<Box>>, imageWidth: Int, imageHeight: Int): ScanRegion? {
            val measured = cells.flatten().filter { it.right > it.left && it.bottom > it.top }
            if (measured.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
            val l = measured.minOf { it.left }.toDouble()
            val t = measured.minOf { it.top }.toDouble()
            val r = measured.maxOf { it.right }.toDouble()
            val b = measured.maxOf { it.bottom }.toDouble()
            val rowHeight = measured.map { (it.bottom - it.top).toDouble() }.sorted()[measured.size / 2]
            val margin = 0.02
            val w = imageWidth.toDouble()
            val h = imageHeight.toDouble()
            val x = max(0.0, l / w - 0.20 * (r - l) / w - margin)
            val y = max(0.0, t / h - rowHeight / h - margin)
            return of(x, y, min(1.0, r / w + margin) - x, min(1.0, b / h + margin) - y)
        }

        /** Smallest side accepted, as a fraction of the photo: a box this small cannot hold a table. */
        const val MIN_SIDE = 0.05
        private const val TOLERANCE = 0.001

        /** `null` when a value is not finite, the box leaves the photo (beyond 0.001 of rounding), or a side is under [MIN_SIDE]. */
        fun of(x: Double, y: Double, width: Double, height: Double): ScanRegion? {
            if (!(x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite())) return null
            if (x < -TOLERANCE || y < -TOLERANCE || width < MIN_SIDE || height < MIN_SIDE) return null
            if (x + width > 1 + TOLERANCE || y + height > 1 + TOLERANCE) return null
            val cx = max(0.0, x)
            val cy = max(0.0, y)
            return ScanRegion(cx, cy, min(width, 1 - cx), min(height, 1 - cy))
        }
    }
}

/**
 * What a finger does to the box on the selection screen, as pure arithmetic on fractions of the photo (so it can be
 * tested). Mirrors the iOS overlay: dragging a corner resizes, dragging inside moves, and the box never leaves the
 * photo or gets thinner than [ScanRegion.MIN_SIDE].
 */
object BoxDrag {
    /** A box as fractions of the photo. */
    data class Frac(val x: Double, val y: Double, val width: Double, val height: Double) {
        val right get() = x + width
        val bottom get() = y + height
    }

    /** Corners are numbered clockwise from the top left: 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left. */
    fun move(start: Frac, dx: Double, dy: Double) = start.copy(
        x = (start.x + dx).coerceIn(0.0, 1 - start.width),
        y = (start.y + dy).coerceIn(0.0, 1 - start.height),
    )

    fun resize(start: Frac, corner: Int, dx: Double, dy: Double): Frac {
        val min = ScanRegion.MIN_SIDE
        var x0 = start.x
        var y0 = start.y
        var x1 = start.right
        var y1 = start.bottom
        if (corner == 0 || corner == 3) x0 = (x0 + dx).coerceIn(0.0, x1 - min) else x1 = (x1 + dx).coerceIn(x0 + min, 1.0)
        if (corner == 0 || corner == 1) y0 = (y0 + dy).coerceIn(0.0, y1 - min) else y1 = (y1 + dy).coerceIn(y0 + min, 1.0)
        return Frac(x0, y0, x1 - x0, y1 - y0)
    }
}
