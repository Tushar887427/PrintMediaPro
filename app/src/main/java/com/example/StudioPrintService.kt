package com.example

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class StudioPrintService : PrintService() {

    companion object {
        private const val TAG = "StudioPrintService"
        private const val VIRTUAL_PRINTER_LOCAL_ID = "printstudio_pro_virtual_printer"
        const val EXTRA_SPOOLED_FILE_NAME = "extra_spooled_file_name"
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return object : PrinterDiscoverySession() {
            private val printerId: PrinterId = generatePrinterId(VIRTUAL_PRINTER_LOCAL_ID)

            private fun buildVirtualPrinterInfo(): PrinterInfo {
                val capabilities = PrinterCapabilitiesInfo.Builder(printerId)
                    .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                    .addMediaSize(PrintAttributes.MediaSize.NA_LEGAL, false)
                    .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
                    .addMediaSize(PrintAttributes.MediaSize.ISO_A3, false)
                    .addResolution(
                        PrintAttributes.Resolution(
                            "printstudio_300dpi",
                            "300 DPI Studio Reflow",
                            300,
                            300
                        ),
                        true
                    )
                    .setColorModes(
                        PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME,
                        PrintAttributes.COLOR_MODE_COLOR
                    )
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build()

                return PrinterInfo.Builder(
                    printerId,
                    getString(R.string.print_service_label),
                    PrinterInfo.STATUS_IDLE
                )
                    .setDescription(getString(R.string.virtual_printer_description))
                    .setCapabilities(capabilities)
                    .build()
            }

            override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
                addPrinters(listOf(buildVirtualPrinterInfo()))
            }

            override fun onStopPrinterDiscovery() {
                // No background hardware scanning required for virtual print target
            }

            override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
                if (printerIds.contains(printerId)) {
                    addPrinters(listOf(buildVirtualPrinterInfo()))
                }
            }

            override fun onStartPrinterStateTracking(printerId: PrinterId) {
                if (printerId == this.printerId) {
                    addPrinters(listOf(buildVirtualPrinterInfo()))
                }
            }

            override fun onStopPrinterStateTracking(printerId: PrinterId) {
                // No state tracking resources to release
            }

            override fun onDestroy() {
                // Clean up discovery session
            }
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        if (printJob.isStarted || printJob.isQueued) {
            printJob.cancel()
        }
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        if (printJob.isQueued) {
            printJob.start()
        }

        val document = printJob.document
        val rawTitle = document.info?.name?.trim().takeUnless { it.isNullOrEmpty() }
            ?: "Spooled_Document.pdf"
        val sanitizedName = rawTitle
            .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
            .let { if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf" }

        val pfd = document.data
        if (pfd == null) {
            Log.e(TAG, "PrintJob document data ParcelFileDescriptor is null")
            printJob.fail("Unable to read spooled print document")
            return
        }

        try {
            val spoolDir = File(cacheDir, "spooled_prints").apply {
                if (!exists()) mkdirs()
            }
            val spooledFile = File(spoolDir, "${System.currentTimeMillis()}_$sanitizedName")

            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                FileOutputStream(spooledFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            printJob.complete()

            val contentUri = FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                spooledFile
            )

            val launchIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(contentUri, "application/pdf")
                putExtra(EXTRA_SPOOLED_FILE_NAME, sanitizedName)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            Toast.makeText(
                this,
                "Opening \"$sanitizedName\" in PrintStudio Pro...",
                Toast.LENGTH_SHORT
            ).show()

            startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to spool PDF to PrintStudio Pro", e)
            printJob.fail("Failed to forward PDF to PrintStudio Pro: ${e.localizedMessage}")
        }
    }
}
