package com.troysandal.timecardscanner.plugin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.troysandal.timecardscanner.Box
import com.troysandal.timecardscanner.OcrSymbol
import com.troysandal.timecardscanner.TableScan
import com.troysandal.timecardscanner.TimeCardScanner
import java.io.File

/**
 * Photo on disk -> [TableScan]. The Android-specific half of the pipeline: decode the image upright, run
 * ML Kit text recognition on it, and hand pixels and characters to the pure-Kotlin core. No Capacitor
 * types, so a test bench can call it directly. Blocking: call it off the main thread.
 */
object ImageScanner {
    /** Longest side the photo is decoded at. A scanned card is ~3000 px; a raw 50 MP photo is downsampled. */
    const val MAX_SIDE = 4096

    class Timing(val recognizeMs: Long, val readMs: Long)
    class Result(val scan: TableScan, val width: Int, val height: Int, val timing: Timing)

    /** Returns null when [file] is not a readable image. Throws if text recognition itself fails. */
    fun scan(context: Context, file: File): Result? {
        val bitmap = decodeUpright(file) ?: return null
        try {
            return scan(context, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun scan(@Suppress("UNUSED_PARAMETER") context: Context, bitmap: Bitmap): Result {
        val t0 = System.nanoTime()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val symbols = ArrayList<OcrSymbol>()
        try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            for (block in text.textBlocks) for (line in block.lines) for (element in line.elements) for (s in element.symbols) {
                val r = s.boundingBox ?: continue
                symbols.add(OcrSymbol(s.text, Box(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()), s.confidence))
            }
        } finally {
            recognizer.close()
        }
        val t1 = System.nanoTime()
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        val scan = TimeCardScanner.scan(TimeCardScanner.grayFromArgb(argb), w, h, symbols)
        val t2 = System.nanoTime()
        return Result(scan, w, h, Timing((t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000))
    }

    /** Decodes [file] with its EXIF orientation applied, downsampled by powers of two to at most [MAX_SIDE]. */
    fun decodeUpright(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(file.path, options) ?: return null
        val matrix = Matrix()
        when (runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return raw
        }
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (upright !== raw) raw.recycle()
        return upright
    }
}
