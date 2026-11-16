package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.LimeAccent
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.SkyAccent
import com.example.ui.theme.SlateBg
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateCard
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PrintStudioMainActivity"
        private const val STUDIO_ASSET_URL = "file:///android_asset/pdf-print-studio.html"
        private const val JS_BRIDGE_NAME = "AndroidBridge"
        private const val PREFS_NAME = "printstudio_prefs"
        private const val KEY_PRINT_BANNER_DISMISSED = "print_banner_dismissed"
    }

    private data class IncomingFilePayload(
        val fileName: String,
        val mimeType: String,
        val base64Data: String
    )

    private var webViewRef: WebView? = null
    private var isWebPageReady: Boolean = false
    private var pendingPayload: IncomingFilePayload? = null
    private var canGoBackInWebView by mutableStateOf(false)
    private var isPrintServiceEnabled by mutableStateOf(false)
    private var isPrintBannerDismissed by mutableStateOf(false)

    // File chooser callback for <input type="file"> inside WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris = when {
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
            callback.onReceiveValue(uris)
        } else {
            callback.onReceiveValue(null)
        }
    }

    private val nativeOpenDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            loadUriAndForwardToWebView(uri, null, contentResolver.getType(uri))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WebView.setWebContentsDebuggingEnabled(true)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isPrintBannerDismissed = prefs.getBoolean(KEY_PRINT_BANNER_DISMISSED, false)
        refreshPrintServiceStatus()

        // Process initial launch intent (ACTION_VIEW, ACTION_SEND, or PrintService spool)
        handleIncomingIntent(intent)

        setContent {
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        refreshPrintServiceStatus()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            MyApplicationTheme {
                BackHandler(enabled = canGoBackInWebView) {
                    webViewRef?.let { wv ->
                        if (wv.canGoBack()) wv.goBack()
                    }
                }

                PrintStudioWebViewScreen(
                    showPrintSetupBanner = !isPrintServiceEnabled && !isPrintBannerDismissed,
                    onEnablePrintServiceClick = { openSystemPrintSettings() },
                    onDismissBannerClick = {
                        isPrintBannerDismissed = true
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEY_PRINT_BANNER_DISMISSED, true)
                            .apply()
                    },
                    onWebViewCreated = { webView ->
                        webViewRef = webView
                        configureWebView(webView)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun refreshPrintServiceStatus() {
        isPrintServiceEnabled = checkPrintServiceEnabled()
    }

    private fun checkPrintServiceEnabled(): Boolean {
        return try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                "enabled_print_services"
            )
            if (enabledServices != null) {
                enabledServices.contains(packageName) ||
                    enabledServices.contains(StudioPrintService::class.java.name)
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to inspect enabled_print_services setting", e)
            false
        }
    }

    private fun openSystemPrintSettings() {
        try {
            val settingsIntent = Intent(Settings.ACTION_PRINT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(settingsIntent)
            Toast.makeText(
                this,
                "Turn ON \"PrintStudio Pro\" so it appears in your Print menu.",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open system print settings", e)
            Toast.makeText(
                this,
                "Open Android Settings → Printing → enable PrintStudio Pro.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun handleIncomingIntent(incomingIntent: Intent?) {
        if (incomingIntent == null) return
        val action = incomingIntent.action ?: return

        val targetUri: Uri? = when (action) {
            Intent.ACTION_VIEW -> incomingIntent.data
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(
                    incomingIntent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java
                ) ?: incomingIntent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            else -> null
        }

        if (targetUri != null) {
            val spooledName = incomingIntent.getStringExtra(StudioPrintService.EXTRA_SPOOLED_FILE_NAME)
            val explicitMime = incomingIntent.type
            loadUriAndForwardToWebView(targetUri, spooledName, explicitMime)
        }
    }

    private fun loadUriAndForwardToWebView(
        uri: Uri,
        overrideFileName: String?,
        explicitMimeType: String?
    ) {
        lifecycleScope.launch {
            val payload = withContext(Dispatchers.IO) {
                try {
                    val resolvedMime = contentResolver.getType(uri)
                        ?: explicitMimeType
                        ?: inferMimeTypeFromName(overrideFileName ?: uri.lastPathSegment.orEmpty())

                    val resolvedName = overrideFileName?.takeIf { it.isNotBlank() }
                        ?: queryDisplayName(uri)
                        ?: defaultFileNameForMime(resolvedMime)

                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@withContext null

                    if (bytes.isEmpty()) return@withContext null

                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    IncomingFilePayload(
                        fileName = ensureExtension(resolvedName, resolvedMime, bytes),
                        mimeType = resolvedMime,
                        base64Data = base64
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read incoming URI: $uri", e)
                    null
                }
            }

            if (payload == null) {
                Toast.makeText(
                    this@MainActivity,
                    "Unable to read shared document.",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            pendingPayload = payload
            if (isWebPageReady) {
                dispatchPendingFileToWebView()
            }
        }
    }

    private fun dispatchPendingFileToWebView() {
        val payload = pendingPayload ?: return
        val wv = webViewRef ?: return
        if (!isWebPageReady) return

        pendingPayload = null

        val jsFileName = JSONObject.quote(payload.fileName)
        val jsMimeType = JSONObject.quote(payload.mimeType)
        val jsBase64 = JSONObject.quote(payload.base64Data)

        wv.post {
            wv.evaluateJavascript(
                "if (typeof window.receiveAndroidFile === 'function') { window.receiveAndroidFile($jsFileName, $jsMimeType, $jsBase64); }",
                null
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            databaseEnabled = true
            loadWithOverviewMode = false
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.addJavascriptInterface(AndroidBridge(this), JS_BRIDGE_NAME)

        // Intercept blob: and standard PDF downloads from WebView
        webView.setDownloadListener(
            DownloadListener { url, _, contentDisposition, mimeType, _ ->
                val suggestedName = URLUtil.guessFileName(
                    url,
                    contentDisposition,
                    mimeType.ifBlank { "application/pdf" }
                ).let {
                    if (it.endsWith(".bin", ignoreCase = true)) {
                        it.removeSuffix(".bin").removeSuffix(".BIN") + ".pdf"
                    } else {
                        it
                    }
                }

                if (url.startsWith("blob:", ignoreCase = true)) {
                    val escapedUrl = JSONObject.quote(url)
                    val escapedName = JSONObject.quote(suggestedName)
                    val escapedMime = JSONObject.quote(mimeType.ifBlank { "application/pdf" })

                    val fetchBlobScript = """
                        (async function() {
                            try {
                                const res = await fetch($escapedUrl);
                                const blob = await res.blob();
                                const reader = new FileReader();
                                reader.onloadend = function() {
                                    window.$JS_BRIDGE_NAME.saveBlobPdf(reader.result, $escapedName, $escapedMime);
                                };
                                reader.readAsDataURL(blob);
                            } catch (e) {
                                console.error('Blob download intercept failed:', e);
                            }
                        })();
                    """.trimIndent()

                    webView.evaluateJavascript(fetchBlobScript, null)
                }
            }
        )

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isWebPageReady = true
                canGoBackInWebView = view?.canGoBack() == true
                dispatchPendingFileToWebView()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                Log.w(TAG, "WebView resource error: ${request?.url} -> ${error?.description}")
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                Log.e(TAG, "WebView render process gone (didCrash=${detail?.didCrash()}), reloading")
                isWebPageReady = false
                view?.loadUrl(STUDIO_ASSET_URL)
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d(
                        TAG,
                        "WebView JS [${it.messageLevel()}] ${it.sourceId()}:${it.lineNumber()}: ${it.message()}"
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val supportedMimeTypes = arrayOf(
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel",
                    "text/csv",
                    "application/octet-stream"
                )

                val openDocIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, supportedMimeTypes)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                return try {
                    fileChooserLauncher.launch(openDocIntent)
                    true
                } catch (e: ActivityNotFoundException) {
                    try {
                        val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                        fileChooserLauncher.launch(
                            Intent.createChooser(fallbackIntent, "Select PDF, Word, or Excel File")
                        )
                        true
                    } catch (ex: Exception) {
                        Log.e(TAG, "Failed to launch file chooser", ex)
                        this@MainActivity.filePathCallback = null
                        false
                    }
                }
            }
        }

        webView.loadUrl(STUDIO_ASSET_URL)
    }

    /**
     * @JavascriptInterface bridge connecting the HTML/JS PrintStudio Pro engine
     * with native Android URI ingestion, MediaStore Downloads export, and PrintManager.
     */
    inner class AndroidBridge(private val context: Context) {

        @JavascriptInterface
        fun onWebAppReady() {
            runOnUiThread {
                isWebPageReady = true
                dispatchPendingFileToWebView()
            }
        }

        @JavascriptInterface
        fun openSystemPrintSettings() {
            runOnUiThread {
                this@MainActivity.openSystemPrintSettings()
            }
        }

        @JavascriptInterface
        fun openNativeFilePicker() {
            runOnUiThread {
                try {
                    nativeOpenDocumentLauncher.launch(
                        arrayOf(
                            "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/msword",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.ms-excel",
                            "*/*"
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open native document picker", e)
                }
            }
        }

        @JavascriptInterface
        fun saveBlobPdf(base64Data: String, fileName: String, mimeType: String?) {
            saveBase64ToDownloads(base64Data, fileName, mimeType ?: "application/pdf")
        }

        @JavascriptInterface
        fun downloadBase64File(base64Data: String, fileName: String, mimeType: String?) {
            saveBase64ToDownloads(base64Data, fileName, mimeType ?: "application/pdf")
        }

        @JavascriptInterface
        fun printPdf(base64Data: String, fileName: String?) {
            lifecycleScope.launch {
                val pdfBytes = withContext(Dispatchers.IO) {
                    decodeBase64Payload(base64Data)
                }
                if (pdfBytes == null || pdfBytes.isEmpty()) {
                    Toast.makeText(
                        context,
                        "Unable to prepare PDF for printing.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val cleanJobName = (fileName?.takeIf { it.isNotBlank() } ?: "PrintStudio_Document.pdf")
                    .removeSuffix(".pdf")
                    .removeSuffix(".PDF")

                val printManager = getSystemService(Context.PRINT_SERVICE) as? PrintManager
                if (printManager == null) {
                    Toast.makeText(context, "Print service unavailable.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                printManager.print(
                    cleanJobName,
                    ByteArrayPrintDocumentAdapter(cleanJobName, pdfBytes),
                    PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                        .build()
                )
            }
        }

        @JavascriptInterface
        fun printCurrentWebView(fileName: String?) {
            runOnUiThread {
                val wv = webViewRef ?: return@runOnUiThread
                val printManager = getSystemService(Context.PRINT_SERVICE) as? PrintManager
                if (printManager == null) {
                    Toast.makeText(context, "Print service unavailable.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val cleanJobName = (fileName?.takeIf { it.isNotBlank() } ?: "PrintStudio_Document")
                    .removeSuffix(".pdf")
                    .removeSuffix(".PDF")
                val adapter = wv.createPrintDocumentAdapter(cleanJobName)
                printManager.print(
                    cleanJobName,
                    adapter,
                    PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                        .build()
                )
            }
        }

        private fun saveBase64ToDownloads(
            base64Data: String,
            rawFileName: String,
            mimeType: String
        ) {
            lifecycleScope.launch {
                val cleanFileName = sanitizeDownloadFileName(rawFileName)
                val resolvedMime = mimeType.ifBlank { "application/pdf" }

                val savedSuccess = withContext(Dispatchers.IO) {
                    try {
                        val bytes = decodeBase64Payload(base64Data) ?: return@withContext false
                        if (bytes.isEmpty()) return@withContext false

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val resolver = context.contentResolver
                            val contentValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, cleanFileName)
                                put(MediaStore.MediaColumns.MIME_TYPE, resolvedMime)
                                put(
                                    MediaStore.MediaColumns.RELATIVE_PATH,
                                    Environment.DIRECTORY_DOWNLOADS
                                )
                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                            }

                            val itemUri = resolver.insert(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                contentValues
                            ) ?: return@withContext false

                            resolver.openOutputStream(itemUri)?.use { output ->
                                output.write(bytes)
                                output.flush()
                            } ?: return@withContext false

                            val publishValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.IS_PENDING, 0)
                            }
                            resolver.update(itemUri, publishValues, null, null)
                            true
                        } else {
                            @Suppress("DEPRECATION")
                            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS
                            ).apply {
                                if (!exists()) mkdirs()
                            }
                            val targetFile = File(downloadsDir, cleanFileName)
                            FileOutputStream(targetFile).use { output ->
                                output.write(bytes)
                                output.flush()
                            }
                            true
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving PDF to MediaStore Downloads", e)
                        false
                    }
                }

                if (savedSuccess) {
                    Toast.makeText(
                        context,
                        "Saved to Downloads: $cleanFileName",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "Failed to save PDF to Downloads",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun decodeBase64Payload(rawBase64: String): ByteArray? {
        return try {
            val stripped = if (rawBase64.contains(",")) {
                rawBase64.substringAfter(",")
            } else {
                rawBase64
            }
            Base64.decode(stripped, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode Base64 payload", e)
            null
        }
    }

    private fun sanitizeDownloadFileName(rawName: String): String {
        val base = rawName.trim().ifEmpty { "PrintStudio_Export_${System.currentTimeMillis()}.pdf" }
            .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
        return if (base.endsWith(".pdf", ignoreCase = true)) base else "$base.pdf"
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to query display name from content resolver", e)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun inferMimeTypeFromName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            lower.endsWith(".doc") -> "application/msword"
            lower.endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            lower.endsWith(".xls") -> "application/vnd.ms-excel"
            else -> "application/pdf"
        }
    }

    private fun defaultFileNameForMime(mimeType: String): String {
        val lower = mimeType.lowercase()
        return when {
            lower.contains("spreadsheet") || lower.contains("excel") || lower.contains("sheet") ->
                "Shared_Spreadsheet.xlsx"
            lower.contains("word") || lower.contains("document") ->
                "Shared_Document.docx"
            else -> "Shared_Document.pdf"
        }
    }

    private fun ensureExtension(fileName: String, mimeType: String, bytes: ByteArray): String {
        val clean = fileName.trim().ifEmpty { "Document" }
        if (Regex("\\.(pdf|docx|doc|xlsx|xls)$", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
            return clean
        }

        // Check %PDF magic header
        val isPdfHeader = bytes.size >= 4 &&
            bytes[0] == 0x25.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x44.toByte() &&
            bytes[3] == 0x46.toByte()

        val lowerMime = mimeType.lowercase()
        return when {
            isPdfHeader || lowerMime.contains("pdf") -> "$clean.pdf"
            lowerMime.contains("spreadsheet") || lowerMime.contains("excel") || lowerMime.contains("sheet") ->
                "$clean.xlsx"
            lowerMime.contains("word") || lowerMime.contains("document") ->
                "$clean.docx"
            else -> "$clean.pdf"
        }
    }

    private class ByteArrayPrintDocumentAdapter(
        private val jobName: String,
        private val pdfBytes: ByteArray
    ) : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback?,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback?.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder("$jobName.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback?.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback?
        ) {
            if (destination == null) {
                callback?.onWriteFailed("Destination ParcelFileDescriptor is null")
                return
            }
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
                    output.write(pdfBytes)
                    output.flush()
                }
                callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback?.onWriteFailed(e.localizedMessage ?: "Failed to write PDF bytes")
            }
        }
    }
}

@Composable
fun PrintStudioWebViewScreen(
    showPrintSetupBanner: Boolean,
    onEnablePrintServiceClick: () -> Unit,
    onDismissBannerClick: () -> Unit,
    onWebViewCreated: (WebView) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlateBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AnimatedVisibility(visible = showPrintSetupBanner) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlateCard)
                    .border(width = 1.dp, color = SlateBorder, shape = RoundedCornerShape(0.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SkyAccent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Print,
                            contentDescription = "Enable PrintStudio Pro in System Print Menu",
                            tint = SkyAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Not seeing PrintStudio Pro in Print menu?",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "Android disables new Print Services until enabled in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onEnablePrintServiceClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LimeAccent,
                            contentColor = SlateBg
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("enable_print_service_button")
                    ) {
                        Text(
                            text = "Enable",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    IconButton(
                        onClick = onDismissBannerClick,
                        modifier = Modifier.testTag("dismiss_print_banner_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Dismiss Print Setup Banner",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(android.graphics.Color.parseColor("#0F172A"))
                        onWebViewCreated(this)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("print_studio_webview")
            )
        }
    }
}
