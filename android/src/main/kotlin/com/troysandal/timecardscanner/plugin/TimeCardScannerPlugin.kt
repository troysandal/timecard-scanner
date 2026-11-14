package com.troysandal.timecardscanner.plugin

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

    private class Capture(val call: PluginCall, val folder: File)

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
    private val gate = CaptureGate<Capture>()

    override fun load() {
        // Must be registered before the activity starts, hence here and not when capturing.
        scannerLauncher = bridge.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { onScannerResult(it) }
    }

    override fun handleOnDestroy() {
        // The scanner's result can no longer reach this instance: settle the call instead of leaving it hanging.
        gate.settle { it.call.reject("The capture was interrupted: the app's activity was destroyed.", SCAN_FAILED) }
        worker.shutdown()
    }

    // ------------------------------------------------------------------ methods

    @PluginMethod
    fun isAvailable(call: PluginCall) {
        DocumentCapture.checkAvailable(context) { available -> call.resolve(JSObject().put("available", available)) }
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
        val capture = Capture(call, folder)
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

    @PluginMethod
    fun scanImage(call: PluginCall) {
        val path = call.getString("path")
        if (path.isNullOrEmpty()) return call.reject("`path` is required.", INVALID_ARGUMENT)
        val file = File(if (path.startsWith("file://")) Uri.parse(path).path ?: path else path)
        try {
            worker.execute { scan(file, emptyList(), call) }
        } catch (e: RejectedExecutionException) {
            call.reject("The plugin is shutting down.", SCAN_FAILED, e)
        }
    }

    // ----------------------------------------------------------------- scanning

    private fun onScannerResult(result: ActivityResult) {
        val pending = gate.take() ?: return
        val pages = DocumentCapture.pages(result.resultCode, result.data)
            ?: return pending.call.reject("The user cancelled the scan.", CANCELLED)
        val job = Runnable {
            val file = File(pending.folder, "timecard-${UUID.randomUUID()}.jpg")
            try {
                DocumentCapture.save(context, pages[0], file)
            } catch (e: Exception) {
                return@Runnable pending.call.reject("Could not save the photo: ${e.message}", SCAN_FAILED, e)
            }
            val extra = if (pages.size > 1) listOf("The scanner returned ${pages.size} pages; only the first was read.") else emptyList()
            scan(file, extra, pending.call)
        }
        try {
            worker.execute(job)
        } catch (e: RejectedExecutionException) {
            pending.call.reject("The plugin is shutting down.", SCAN_FAILED, e)
        }
    }

    private fun scan(file: File, extraWarnings: List<String>, call: PluginCall) {
        try {
            val read = ImageScanner.scan(context, file)
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
                    .put("flagged", scan.flags(index).isNotEmpty())
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
