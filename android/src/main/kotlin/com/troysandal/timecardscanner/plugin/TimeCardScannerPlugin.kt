package com.troysandal.timecardscanner.plugin

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.getcapacitor.FileUtils
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.troysandal.timecardscanner.CardFinder
import com.troysandal.timecardscanner.ScanRegion
import com.troysandal.timecardscanner.TimeCardScanner
import com.troysandal.timecardscanner.ScanFlag
import com.troysandal.timecardscanner.TableScan
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Capacitor bridge for the Android timecard scanner: the same three methods, result shape and rejection
 * codes as the iOS plugin (see `src/definitions.ts`). Capture is [DocumentCapture], reading a photo is
 * [ImageScanner], and the reader itself is the pure-Kotlin core in `android/core`.
 */
@CapacitorPlugin(name = "TimeCardScanner")
class TimeCardScannerPlugin : Plugin() {
    private companion object {
        const val CANCELLED = "CANCELLED"
        const val UNAVAILABLE = "UNAVAILABLE"
        const val NO_TABLE = "NO_TABLE"
        const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
        const val BUSY = "BUSY"
        const val SCAN_FAILED = "SCAN_FAILED"
    }

    /** One capture, from the scanner to the result. [file] and [extra] are filled in once the photo is saved. */
    private class Capture(val call: PluginCall, val folder: File, val selectTable: Boolean, val cropSource: File? = null) {
        var file: File? = null
        var extra: List<String> = emptyList()
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var boxLauncher: ActivityResultLauncher<Intent>
    private val gate = CaptureGate<Capture>()

    override fun load() {
        // Must be registered before the activity starts, hence here and not when capturing.
        scannerLauncher = bridge.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { onScannerResult(it) }
        boxLauncher = bridge.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onBoxResult(it) }
    }

    override fun handleOnDestroy() {
        // The scanner's result can no longer reach this instance: settle the call instead of leaving it hanging.
        gate.settle { it.call.reject("The capture was interrupted: the app's activity was destroyed.", SCAN_FAILED) }
        worker.shutdown()
    }

    // ------------------------------------------------------------------ methods

    @PluginMethod
    fun isAvailable(call: PluginCall) {
        DocumentCapture.checkAvailable(context) { available -> call.resolve(JSObject().put("captureAvailable", available).put("available", available).put("photoAvailable", true)) }
    }

    @PluginMethod
    fun captureCard(call: PluginCall) {
        if (!DocumentCapture.hasPlayServices(context)) {
            return call.reject("The document scanner needs Google Play services.", UNAVAILABLE)
        }
        val folder = try {
            destinationFolder(call.getString("directory"), call.getString("folder"))
        } catch (e: IllegalArgumentException) {
            return call.reject("Invalid destination: ${e.message}", INVALID_ARGUMENT)
        }
        // The user draws a box around the table after the scanner unless the host turns that off.
        val capture = Capture(call, folder, call.getBoolean("selectTable") ?: true)
        if (!gate.begin(capture)) return call.reject("A capture is already in progress.", BUSY)
        DocumentCapture.start(
            activity,
            onReady = { sender ->
                // Already settled (e.g. the plugin was destroyed meanwhile): do not open a scanner nobody awaits.
                if (!gate.holds(capture)) return@start
                try {
                    scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
                } catch (e: Exception) {
                    // e.g. the activity is finishing. Without this the gate would stay busy for good.
                    gate.settle(capture) { it.call.reject("Could not open the document scanner: ${e.message}", SCAN_FAILED, e) }
                }
            },
            onError = { e ->
                // Typically the scanner module has not been downloaded yet (the phone was never online with the app).
                gate.settle(capture) { it.call.reject("The document scanner is not available: ${e.message}", UNAVAILABLE, e) }
            },
        )
    }

    /** Lets the user drag a box around the card on a photo the host took, and saves the cropped card. No reading. */
    @PluginMethod
    fun cropCard(call: PluginCall) {
        val path = call.getString("path")
        if (path.isNullOrEmpty()) return call.reject("`path` is required.", INVALID_ARGUMENT)
        val source = File(if (path.startsWith("file://")) Uri.parse(path).path ?: path else path)
        val folder = try {
            destinationFolder(call.getString("directory"), call.getString("folder"))
        } catch (e: IllegalArgumentException) {
            return call.reject("Invalid destination: ${e.message}", INVALID_ARGUMENT)
        }
        val capture = Capture(call, folder, selectTable = false, cropSource = source)
        if (!gate.begin(capture)) return call.reject("A capture is already in progress.", BUSY)
        val job = Runnable {
            val bitmap = try { ImageScanner.decodeUpright(source, 1024) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
            if (bitmap == null) return@Runnable gate.settle(capture) { it.call.reject("Could not read an image at ${source.path}.", INVALID_ARGUMENT) }
            val start = try {
                val argb = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                CardFinder.find(TimeCardScanner.grayFromArgb(argb), bitmap.width, bitmap.height)
            } catch (e: Exception) { null } finally { bitmap.recycle() } ?: CardFinder.FALLBACK
            activity.runOnUiThread {
                if (!gate.holds(capture)) return@runOnUiThread
                try {
                    boxLauncher.launch(TableBoxActivity.intent(context, source, start, R.string.timecard_scanner_card_title, R.string.timecard_scanner_card_use))
                } catch (e: Exception) {
                    gate.settle(capture) { it.call.reject("Could not open the crop screen: ${e.message}", SCAN_FAILED, e) }
                }
            }
        }
        try {
            worker.execute(job)
        } catch (e: RejectedExecutionException) {
            gate.settle(capture) { it.call.reject("The plugin is shutting down.", SCAN_FAILED, e) }
        }
    }

    @PluginMethod
    fun scanImage(call: PluginCall) {
        val path = call.getString("path")
        if (path.isNullOrEmpty()) return call.reject("`path` is required.", INVALID_ARGUMENT)
        val file = File(if (path.startsWith("file://")) Uri.parse(path).path ?: path else path)
        var region: ScanRegion? = null
        if (call.data.has("region") && !call.data.isNull("region")) {
            val raw = call.getObject("region")
            region = raw?.let { ScanRegion.of(it.optDouble("x", Double.NaN), it.optDouble("y", Double.NaN), it.optDouble("width", Double.NaN), it.optDouble("height", Double.NaN)) }
                ?: return call.reject("`region` must be {x, y, width, height}, fractions of the photo inside 0..1, each side at least 0.05.", INVALID_ARGUMENT)
        }
        try {
            worker.execute { scan(file, emptyList(), call, region) }
        } catch (e: RejectedExecutionException) {
            call.reject("The plugin is shutting down.", SCAN_FAILED, e)
        }
    }

    // ----------------------------------------------------------------- scanning

    private fun onScannerResult(result: ActivityResult) {
        val pending = gate.current() ?: return
        val pages = DocumentCapture.pages(result.resultCode, result.data)
            ?: return gate.settle(pending) { it.call.reject("The user cancelled the scan.", CANCELLED) }
        val job = Runnable {
            val file = File(pending.folder, "timecard-${UUID.randomUUID()}.jpg")
            try {
                DocumentCapture.save(context, pages[0], file)
            } catch (e: Exception) {
                return@Runnable gate.settle(pending) { it.call.reject("Could not save the photo: ${e.message}", SCAN_FAILED, e) }
            }
            val extra = if (pages.size > 1) listOf("The scanner returned ${pages.size} pages; only the first was read.") else emptyList()
            pending.file = file
            pending.extra = extra
            if (!pending.selectTable) {
                return@Runnable gate.settle(pending) { scan(file, extra, it.call) }
            }
            // Where the table is, as far as the automatic read can tell, is where the user's box starts.
            val first = try { ImageScanner.scan(context, file) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
            val start = first?.let { ScanRegion.enclosing(it.scan.cells, it.width, it.height) } ?: ScanRegion.FALLBACK
            activity.runOnUiThread {
                if (!gate.holds(pending)) return@runOnUiThread
                try {
                    boxLauncher.launch(TableBoxActivity.intent(context, file, start))
                } catch (e: Exception) {
                    gate.settle(pending) { it.call.reject("Could not open the table selection: ${e.message}", SCAN_FAILED, e) }
                }
            }
        }
        try {
            worker.execute(job)
        } catch (e: RejectedExecutionException) {
            gate.settle(pending) { it.call.reject("The plugin is shutting down.", SCAN_FAILED, e) }
        }
    }

    private fun onBoxResult(result: ActivityResult) {
        val pending = gate.current() ?: return
        val source = pending.cropSource
        val file = pending.file ?: source
        val data = result.data
        val region = if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScanRegion.of(data.getDoubleExtra("x", Double.NaN), data.getDoubleExtra("y", Double.NaN), data.getDoubleExtra("width", Double.NaN), data.getDoubleExtra("height", Double.NaN))
        } else {
            null
        }
        if (file == null || region == null) {
            // Cancelled: nothing was asked of the photo, so do not leave it behind. (A host's own photo is not ours to delete.)
            if (source == null) file?.delete()
            return gate.settle(pending) { it.call.reject("The user cancelled the scan.", CANCELLED) }
        }
        if (source != null) {
            return try {
                worker.execute { gate.settle(pending) { saveCrop(source, region, pending.folder, it.call) } }
            } catch (e: RejectedExecutionException) {
                gate.settle(pending) { it.call.reject("The plugin is shutting down.", SCAN_FAILED, e) }
            }
        }
        try {
            worker.execute { gate.settle(pending) { scan(file, pending.extra, it.call, region) } }
        } catch (e: RejectedExecutionException) {
            gate.settle(pending) { it.call.reject("The plugin is shutting down.", SCAN_FAILED, e) }
        }
    }

    private fun saveCrop(source: File, region: ScanRegion, folder: File, call: PluginCall) {
        var bitmap: android.graphics.Bitmap? = null
        var part: android.graphics.Bitmap? = null
        try {
            bitmap = ImageScanner.decodeUpright(source) ?: return call.reject("Could not read an image at ${source.path}.", INVALID_ARGUMENT)
            val p = region.pixels(bitmap.width, bitmap.height)
            part = android.graphics.Bitmap.createBitmap(bitmap, p.left, p.top, p.width, p.height)
            val file = File(folder, "card-${UUID.randomUUID()}.jpg")
            folder.mkdirs()
            file.outputStream().use { part.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            val image = JSObject().put("path", file.path)
                .put("webPath", FileUtils.getPortablePath(context, bridge.localUrl, Uri.fromFile(file)))
            call.resolve(JSObject().put("image", image))
        } catch (e: Exception) {
            call.reject("Could not save the cropped photo: ${e.message}", SCAN_FAILED, e)
        } catch (e: OutOfMemoryError) {
            call.reject("Could not save the cropped photo: the photo is too large.", SCAN_FAILED)
        } finally {
            part?.recycle()
            if (bitmap !== part) bitmap?.recycle()
        }
    }

    private fun scan(file: File, extraWarnings: List<String>, call: PluginCall, region: ScanRegion? = null) {
        try {
            val read = ImageScanner.scan(context, file, region)
                ?: return call.reject("Could not read an image at ${file.path}.", INVALID_ARGUMENT)
            val result = result(read.scan, file, extraWarnings)
            if (read.scan.rows.isEmpty()) {
                // Keep the photo so the host can show what was captured.
                call.reject("No table was found in the photo.", NO_TABLE, null, result)
            } else {
                call.resolve(result)
            }
        } catch (e: Exception) {
            call.reject("Scanning failed: ${e.message}", SCAN_FAILED, e)
        } catch (e: OutOfMemoryError) {
            call.reject("Scanning failed: the photo is too large.", SCAN_FAILED)
        }
    }

    // ------------------------------------------------------------------ mapping

    private fun result(scan: TableScan, file: File, extraWarnings: List<String>): JSObject {
        val rows = JSArray()
        for ((index, row) in scan.rows.withIndex()) {
            rows.put(
                JSObject().put("c1", row.getOrElse(0) { "" }).put("c2", row.getOrElse(1) { "" }).put("c3", row.getOrElse(2) { "" })
                    .put("flagged", JSArray().also { a -> scan.flaggedCells(index).forEach { a.put(it) } })
            )
        }
        val warnings = JSArray()
        extraWarnings.forEach { warnings.put(it) }
        scan.flags.forEach { warnings.put(describe(it)) }
        val image = JSObject().put("path", file.path)
            .put("webPath", FileUtils.getPortablePath(context, bridge.localUrl, Uri.fromFile(file)))
        return JSObject().put("image", image).put("rows", rows).put("warnings", warnings)
    }

    private fun describe(flag: ScanFlag): String {
        val row = flag.row ?: return flag.detail
        val column = flag.column ?: return "row ${row + 1}: ${flag.detail}"
        return "row ${row + 1}, column ${column + 1}: ${flag.detail}"
    }

    /**
     * Resolves a `@capacitor/filesystem`-style directory name plus a relative folder to the same place that
     * plugin uses on Android: DATA and LIBRARY are the app's files directory, CACHE its cache, DOCUMENTS the
     * shared Documents folder (which the host must have permission to write). Default: DATA.
     */
    private fun destinationFolder(directory: String?, folder: String?): File {
        var dir = when (directory) {
            "CACHE" -> context.cacheDir
            "DOCUMENTS" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            else -> context.filesDir
        }
        for (part in (folder ?: "").split('/')) {
            if (part.isEmpty()) continue
            require(part != "..") { "`folder` must not contain .." }
            dir = File(dir, part)
        }
        return dir
    }
}
