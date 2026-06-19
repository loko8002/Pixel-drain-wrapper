package com.pixeldrain.wrapper

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.snackbar.Snackbar
import com.pixeldrain.wrapper.databinding.ActivityMainBinding
import java.io.File

/**
 * Single-activity host for the Pixeldrain WebView. Owns the full WebView
 * lifecycle, download routing, file-chooser uploads, offline handling, back
 * navigation and edge-to-edge system bar treatment.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var connectivity: ConnectivityObserver

    // Pending file-chooser callbacks while we await the picker / permission.
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    // "Press back again to exit" state.
    private var backPressedAt = 0L

    private val baseUrl: String by lazy { getString(R.string.base_url) }

    // --- Activity result launchers -------------------------------------------

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback ?: return@registerForActivityResult
            val uris: Array<Uri>? = when {
                result.resultCode != RESULT_OK -> null
                // Camera capture path: data is null, file written to cameraImageUri.
                result.data?.data == null && result.data?.clipData == null ->
                    cameraImageUri?.let { arrayOf(it) }
                else -> parseChooserResult(result.data)
            }
            // Always deliver a result (even null) or the web page hangs forever.
            callback.onReceiveValue(uris)
            fileChooserCallback = null
            cameraImageUri = null
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    // --- Lifecycle ------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate / setContentView for the animation.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Draw edge-to-edge under transparent system bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBarInsets()
        configureSystemBarIconContrast()

        connectivity = ConnectivityObserver(this)

        keepSplashUntilFirstPaint(splash)
        setupWebView(savedInstanceState)
        setupSwipeRefresh()
        setupOfflineScreen()
        setupBackNavigation()
        requestNotificationPermissionIfNeeded()

        // Fresh start vs. restored state.
        if (savedInstanceState == null) {
            loadInitialOrOffline()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        binding.webView.resumeTimers()
        connectivity.start { online ->
            runOnUiThread { onConnectivityChanged(online) }
        }
    }

    override fun onPause() {
        binding.webView.onPause()
        binding.webView.pauseTimers()
        connectivity.stop()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Persists history + scroll across rotation and process death.
        binding.webView.saveState(outState)
    }

    override fun onDestroy() {
        // Detach and destroy to avoid leaking the WebView's context.
        binding.webView.apply {
            (parent as? android.view.ViewGroup)?.removeView(this)
            stopLoading()
            destroy()
        }
        super.onDestroy()
    }

    // --- WebView setup --------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(savedInstanceState: Bundle?) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            // Zoom is supported (pinch) but the on-screen controls stay hidden.
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            loadWithOverviewMode = true
            useWideViewPort = true

            // Allow secure pages to load mixed media so previews/players work.
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            // Safe file access for the upload/preview flows.
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true

            // Instant warm starts from cache when possible.
            cacheMode = WebSettings.LOAD_DEFAULT

            // Modern desktop-grade UA so Pixeldrain serves the full site.
            userAgentString = buildUserAgent(userAgentString)
        }

        binding.webView.webViewClient = PixeldrainWebViewClient()
        binding.webView.webChromeClient = PixeldrainChromeClient()
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            onDownloadRequested(url, userAgent, contentDisposition, mimeType)
        }

        // Restore the saved WebView state if we were recreated.
        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        }
    }

    /** Keeps the splash on screen until the WebView has painted its first frame. */
    private fun keepSplashUntilFirstPaint(splash: androidx.core.splashscreen.SplashScreen) {
        var ready = false
        splash.setKeepOnScreenCondition { !ready }
        binding.webView.postDelayed({ ready = true }, MAX_SPLASH_MS)
        // Also release as soon as the page reports visual content.
        firstPaintListener = { ready = true }
    }

    private var firstPaintListener: (() -> Unit)? = null

    private fun buildUserAgent(default: String): String {
        // Append a recognisable Chrome token; keep the device tokens intact.
        return if (default.contains("Chrome")) {
            "$default PixeldrainApp/$APP_VERSION"
        } else {
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 " +
                "Mobile Safari/537.36 PixeldrainApp/$APP_VERSION"
        }
    }

    // --- Downloads ------------------------------------------------------------

    private fun onDownloadRequested(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        // Ensure we can post the completion notification on Android 13+.
        requestNotificationPermissionIfNeeded()
        val fileName = DownloadHelper.enqueue(
            this, url, userAgent, contentDisposition, mimeType
        )
        Snackbar.make(
            binding.root,
            getString(R.string.download_started, fileName),
            Snackbar.LENGTH_LONG
        ).show()
    }

    // --- File chooser (uploads) ----------------------------------------------

    private fun openFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        // Cancel any stale pending callback to avoid leaking the web page.
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = callback

        val acceptTypes = params.acceptTypes
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("*/*") }
        val wantsCamera = params.isCaptureEnabled &&
            acceptTypes.any { it.startsWith("image/") }

        val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = if (acceptTypes.size == 1) acceptTypes.first() else "*/*"
            if (acceptTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
            )
        }

        val cameraIntent = if (wantsCamera) createCameraIntent() else null

        val chooser = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, contentIntent)
            putExtra(Intent.EXTRA_TITLE, getString(R.string.choose_file))
            if (cameraIntent != null) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
            }
        }

        return try {
            fileChooserLauncher.launch(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = null
            false
        }
    }

    private fun createCameraIntent(): Intent? {
        return try {
            val imageFile = File.createTempFile(
                "capture_${System.currentTimeMillis()}", ".jpg", cacheDir
            )
            val uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", imageFile
            )
            cameraImageUri = uri
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseChooserResult(data: Intent?): Array<Uri>? {
        if (data == null) return null
        data.clipData?.let { clip ->
            return Array(clip.itemCount) { clip.getItemAt(it).uri }
        }
        return data.data?.let { arrayOf(it) }
    }

    // --- Navigation -----------------------------------------------------------

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.offlineView.isVisible && binding.webView.canGoBack() -> {
                        binding.webView.goBack()
                    }
                    binding.webView.canGoBack() -> binding.webView.goBack()
                    else -> confirmExit()
                }
            }
        })
    }

    private fun confirmExit() {
        val now = System.currentTimeMillis()
        if (now - backPressedAt < EXIT_CONFIRM_WINDOW_MS) {
            finish()
        } else {
            backPressedAt = now
            Snackbar.make(
                binding.root, getString(R.string.press_back_again), Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    /** Opens non-http(s) and external-domain links in the proper external app. */
    private fun handleExternalUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()

        val isExternalScheme = scheme != null && scheme != "http" && scheme != "https"
        val isOffsite = (scheme == "http" || scheme == "https") &&
            uri.host?.let { host ->
                !host.equals("pixeldrain.com", true) && !host.endsWith(".pixeldrain.com", true)
            } ?: false

        if (!isExternalScheme && !isOffsite) return false

        // intent: URLs need special parsing.
        val intent = if (scheme == "intent") {
            runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        } ?: return false

        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(
                binding.root, getString(R.string.no_app_to_open), Snackbar.LENGTH_SHORT
            ).show()
            true
        }
    }

    // --- Pull to refresh ------------------------------------------------------

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.brand_primary)
        binding.swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.refresh_bg)
        binding.swipeRefresh.setOnRefreshListener {
            binding.swipeRefresh.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (connectivity.isOnline()) {
                binding.webView.reload()
            } else {
                binding.swipeRefresh.isRefreshing = false
                showOffline()
            }
        }
        // Only allow the swipe gesture when the WebView is scrolled to the top.
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            binding.webView.scrollY > 0
        }
    }

    // --- Offline screen -------------------------------------------------------

    private fun setupOfflineScreen() {
        binding.offlineRetryButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (connectivity.isOnline()) {
                hideOfflineAndReload()
            } else {
                Snackbar.make(
                    binding.root, getString(R.string.still_offline), Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun loadInitialOrOffline() {
        if (connectivity.isOnline()) {
            binding.webView.loadUrl(baseUrl)
        } else {
            showOffline()
        }
    }

    private fun onConnectivityChanged(online: Boolean) {
        if (online && binding.offlineView.isVisible) {
            hideOfflineAndReload()
        }
    }

    private fun hideOfflineAndReload() {
        hideOffline()
        val current = binding.webView.url
        if (current.isNullOrBlank() || current == "about:blank") {
            binding.webView.loadUrl(baseUrl)
        } else {
            binding.webView.reload()
        }
    }

    private fun showOffline() {
        binding.offlineView.isVisible = true
        binding.swipeRefresh.isVisible = false
    }

    private fun hideOffline() {
        binding.offlineView.isVisible = false
        binding.swipeRefresh.isVisible = true
    }

    // --- System bars / insets -------------------------------------------------

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun configureSystemBarIconContrast() {
        val isLight = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLight
            isAppearanceLightNavigationBars = isLight
        }
    }

    // --- Permissions ----------------------------------------------------------

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // --- WebViewClient --------------------------------------------------------

    private inner class PixeldrainWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView, request: WebResourceRequest
        ): Boolean = handleExternalUrl(request.url.toString())

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            binding.progressBar.isVisible = true
            binding.progressBar.progress = 0
        }

        override fun onPageFinished(view: WebView, url: String?) {
            binding.swipeRefresh.isRefreshing = false
            fadeOutProgress()
            firstPaintListener?.invoke()
            // If the page loaded successfully, make sure offline UI is gone.
            if (connectivity.isOnline()) hideOffline()
        }

        override fun onReceivedError(
            view: WebView, request: WebResourceRequest, error: WebResourceError
        ) {
            // Only react to main-frame failures, not sub-resource errors.
            if (request.isForMainFrame && !connectivity.isOnline()) {
                showOffline()
            }
        }

        override fun onReceivedSslError(
            view: WebView, handler: SslErrorHandler, error: SslError
        ) {
            // Never silently proceed on certificate errors.
            handler.cancel()
            Snackbar.make(
                binding.root, getString(R.string.ssl_error), Snackbar.LENGTH_LONG
            ).show()
        }

        override fun onRenderProcessGone(
            view: WebView, detail: android.webkit.RenderProcessGoneDetail?
        ): Boolean {
            // The render process died; rebuild gracefully instead of crashing.
            recreate()
            return true
        }
    }

    // --- WebChromeClient ------------------------------------------------------

    private inner class PixeldrainChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            binding.progressBar.progress = newProgress
            if (newProgress >= 100) fadeOutProgress()
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean = openFileChooser(filePathCallback, fileChooserParams)
    }

    private fun fadeOutProgress() {
        binding.progressBar.animate()
            .alpha(0f)
            .setDuration(PROGRESS_FADE_MS)
            .withEndAction {
                binding.progressBar.isVisible = false
                binding.progressBar.alpha = 1f
                binding.progressBar.progress = 0
            }
            .start()
    }

    companion object {
        private const val EXIT_CONFIRM_WINDOW_MS = 2000L
        private const val PROGRESS_FADE_MS = 350L
        private const val MAX_SPLASH_MS = 2500L
        private const val APP_VERSION = "1.0.0"
    }
}
