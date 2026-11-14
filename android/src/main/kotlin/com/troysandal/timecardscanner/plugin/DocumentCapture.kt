package com.troysandal.timecardscanner.plugin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File

/**
 * The ML Kit document scanner (Google Play services): camera UI, crop and straighten, one page, JPEG out.
 * The Android counterpart of `DocumentCameraPresenter`. No Capacitor types, so a test bench can use it.
 */
object DocumentCapture {
    private fun client(): GmsDocumentScanner = GmsDocumentScanning.getClient(
        GmsDocumentScannerOptions.Builder()
            // crop and rotate only: no filters or clean-up that could alter faint handwriting
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)
            .setGalleryImportAllowed(false)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .build()
    )

    /** Google Play services present and current. Without it there is no document scanner at all. */
    fun hasPlayServices(context: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /**
     * Reports whether the scanner can be used now. Its module is downloaded by Play services on demand;
     * when it is missing this also asks for it, so a later call can succeed once the phone has been online.
     */
    fun checkAvailable(context: Context, done: (Boolean) -> Unit) {
        if (!hasPlayServices(context)) return done(false)
        val scanner = client()
        val modules = ModuleInstall.getClient(context)
        modules.areModulesAvailable(scanner)
            .addOnSuccessListener { response ->
                if (!response.areModulesAvailable()) {
                    modules.installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build()) // fire and forget
                }
                done(response.areModulesAvailable())
            }
            .addOnFailureListener { done(false) }
    }

    /** Asks Play services for the intent that opens the scanner. */
    fun start(activity: Activity, onReady: (IntentSender) -> Unit, onError: (Exception) -> Unit) {
        client().getStartScanIntent(activity).addOnSuccessListener(onReady).addOnFailureListener(onError)
    }

    /** Uris of the scanned pages in an activity result, or null when the user backed out. */
    fun pages(resultCode: Int, data: Intent?): List<Uri>? {
        if (resultCode != Activity.RESULT_OK) return null
        val pages = GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages ?: return null
        return pages.map { it.imageUri }.ifEmpty { null }
    }

    /** Copies a scanned page out of the scanner's cache into [destination]. */
    fun save(context: Context, page: Uri, destination: File) {
        destination.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(page) ?: throw java.io.IOException("cannot open $page")
        input.use { source -> destination.outputStream().use { source.copyTo(it) } }
    }
}
