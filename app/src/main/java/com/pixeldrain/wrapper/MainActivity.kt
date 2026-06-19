package com.pixeldrain.wrapper

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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

    // Background-upload keep-alive state. While an in-page upload is running we
    // must NOT pause the WebView's JS/timers and we keep a foreground service +
    // wake lock alive so the transfer completes even when the app is backgrounded.
    private var uploadActive = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopKeepAliveRunnable = Runnable { stopUploadKeepAlive() }

    // Whether the page (incl. inner DOM scroll containers) is scrolled to the top.
    // Pull-to-refresh is only allowed when true, so mid-scroll swipes never refresh.
    private var pageAtTop = true

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
        // Keep the WebView (and therefore the JS upload) running in the
        // background while a transfer is in flight; otherwise pause to save power.
        if (!uploadActive) {
            binding.webView.onPause()
            binding.webView.pauseTimers()
        }
        connectivity.stop()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Persists history + scroll across rotation and process death.
        binding.webView.saveState(outState)
    }

    override fun onDestroy() {
        // Release the upload keep-alive resources so nothing leaks.
        mainHandler.removeCallbacks(stopKeepAliveRunnable)
        stopUploadKeepAlive()
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

        // Paint the WebView dark to match Pixeldrain's UI and avoid white flashes
        // between the splash, page loads and navigations.
        binding.webView.setBackgroundColor(
            ContextCompat.getColor(this, R.color.page_background)
        )

        binding.webView.webViewClient = PixeldrainWebViewClient()
        binding.webView.webChromeClient = PixeldrainChromeClient()
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            onDownloadRequested(url, userAgent, contentDisposition, mimeType)
        }

        // Native bridge the injected scripts call (upload + scroll state).
        binding.webView.addJavascriptInterface(NativeBridge(), JS_BRIDGE_NAME)

        // Install the page hooks before page scripts run, when the device's
        // WebView supports document-start scripts (most modern devices).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val origins = setOf("https://pixeldrain.com", "https://*.pixeldrain.com")
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(binding.webView, UPLOAD_HOOK_JS, origins)
                WebViewCompat.addDocumentStartJavaScript(binding.webView, SCROLL_HOOK_JS, origins)
            }
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
        // Require a deliberate, longer pull so a glancing swipe can't trigger it.
        binding.swipeRefresh.setDistanceToTriggerSync(
            (resources.displayMetrics.density * REFRESH_TRIGGER_DP).toInt()
        )
        // Backstop for the gesture: only consider a refresh when the page (including
        // inner scroll containers, tracked via JS) is genuinely at the top.
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ -> !pageAtTop }
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
        // The host chrome behind the system bars is intentionally dark (it matches
        // Pixeldrain's dark UI), so we always use light status/navigation icons.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
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

    // --- Background uploads ---------------------------------------------------

    /**
     * JavaScript bridge for the injected page hooks. [onUploadStateChanged] tracks
     * in-flight uploads; [onScrollTopChanged] reports whether the effective scroll
     * position (including inner DOM containers) is at the top.
     */
    private inner class NativeBridge {
        @JavascriptInterface
        fun onUploadStateChanged(active: Boolean) {
            mainHandler.post { setUploadActive(active) }
        }

        @JavascriptInterface
        fun onScrollTopChanged(atTop: Boolean) {
            mainHandler.post {
                pageAtTop = atTop
                // Disable the whole gesture (not just the trigger) while scrolled
                // down so a normal scroll can never be mistaken for a refresh pull.
                binding.swipeRefresh.isEnabled = atTop
            }
        }
    }

    /**
     * Reacts to upload activity. Starting is immediate; stopping is debounced so
     * the foreground service doesn't flicker between chunked/sequential uploads.
     */
    private fun setUploadActive(active: Boolean) {
        if (active) {
            mainHandler.removeCallbacks(stopKeepAliveRunnable)
            if (!uploadActive) startUploadKeepAlive()
        } else {
            mainHandler.removeCallbacks(stopKeepAliveRunnable)
            mainHandler.postDelayed(stopKeepAliveRunnable, UPLOAD_STOP_DEBOUNCE_MS)
        }
    }

    private fun startUploadKeepAlive() {
        uploadActive = true
        // Foreground service keeps the process from being frozen/killed so the
        // WebView's JS upload keeps running while the app is in the background.
        runCatching { UploadService.start(this) }
        acquireUploadWakeLock()
        Snackbar.make(
            binding.root, getString(R.string.upload_in_background), Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun stopUploadKeepAlive() {
        if (!uploadActive) return
        uploadActive = false
        runCatching { UploadService.stop(this) }
        releaseUploadWakeLock()
        // If we deferred pausing the WebView while uploading and we're now in the
        // background, settle back into the paused state to conserve power.
        if (!isResumedState) {
            binding.webView.onPause()
            binding.webView.pauseTimers()
        }
    }

    private val isResumedState: Boolean
        get() = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)

    private fun acquireUploadWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "Pixeldrain:upload"
        ).apply {
            setReferenceCounted(false)
            // Safety timeout so a missed "end" event can never pin the CPU forever.
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseUploadWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // --- WebViewClient --------------------------------------------------------

    private inner class PixeldrainWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView, request: WebResourceRequest
        ): Boolean = handleExternalUrl(request.url.toString())

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            binding.progressBar.isVisible = true
            binding.progressBar.progress = 0
            // A fresh page starts at the top; the JS hook corrects this as the user
            // scrolls. Re-enabling here also recovers if the bridge never fires.
            pageAtTop = true
            binding.swipeRefresh.isEnabled = true
            // Fallback for WebViews without document-start script support: the
            // hooks are idempotent (guard against double-install), so this is safe.
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                view.evaluateJavascript(UPLOAD_HOOK_JS, null)
                view.evaluateJavascript(SCROLL_HOOK_JS, null)
            }
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

        // Background-upload tuning.
        private const val JS_BRIDGE_NAME = "PixeldrainNative"
        private const val UPLOAD_STOP_DEBOUNCE_MS = 3_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        // Pull distance required to trigger a refresh (default is ~64dp).
        private const val REFRESH_TRIGGER_DP = 120f

        /**
         * Injected before page scripts run. Reports whether the page is scrolled to
         * the top to native, using a capture-phase scroll listener so it also sees
         * Pixeldrain's inner scroll containers (whose scrolling never moves the
         * WebView's own scrollY). Native uses this to enable pull-to-refresh only at
         * the very top, preventing accidental refreshes during normal scrolling.
         * Notifies only on state transitions to keep bridge traffic minimal.
         */
        private val SCROLL_HOOK_JS = """
            (function () {
              if (window.__pdScrollHookInstalled) return;
              window.__pdScrollHookInstalled = true;

              var lastAtTop = null;
              function notify(atTop) {
                if (atTop === lastAtTop) return;
                lastAtTop = atTop;
                try { PixeldrainNative.onScrollTopChanged(atTop); } catch (e) {}
              }

              function scrollTopOf(target) {
                var el = target;
                if (!el || el === document || el === window ||
                    el === document.documentElement || el === document.body) {
                  el = document.scrollingElement || document.documentElement || document.body;
                }
                return (el && typeof el.scrollTop === 'number') ? el.scrollTop : 0;
              }

              document.addEventListener('scroll', function (e) {
                notify(scrollTopOf(e.target) <= 0);
              }, true); // capture phase: catches inner scrollers too

              notify(true);
            })();
        """.trimIndent()

        /**
         * Injected before page scripts run. Wraps XMLHttpRequest and fetch to
         * count in-flight file uploads (requests whose body is a File/Blob/
         * FormData/ArrayBuffer — not small JSON API calls) and notifies native
         * when the active count crosses zero. Idempotent via an install guard.
         */
        private val UPLOAD_HOOK_JS = """
            (function () {
              if (window.__pdUploadHookInstalled) return;
              window.__pdUploadHookInstalled = true;

              var active = 0;
              function notify() {
                try { PixeldrainNative.onUploadStateChanged(active > 0); } catch (e) {}
              }
              function begin() { active++; notify(); }
              function end() { active = Math.max(0, active - 1); notify(); }

              function isUploadBody(body) {
                if (!body) return false;
                if (typeof FormData !== 'undefined' && body instanceof FormData) return true;
                if (typeof Blob !== 'undefined' && body instanceof Blob) return true;
                if (typeof File !== 'undefined' && body instanceof File) return true;
                if (typeof ArrayBuffer !== 'undefined' && body instanceof ArrayBuffer) return true;
                if (body && body.buffer instanceof ArrayBuffer) return true;
                return false;
              }
              function isWriteMethod(m) {
                return /^(post|put|patch)${'$'}/i.test(String(m || ''));
              }

              // --- XMLHttpRequest ---
              var origOpen = XMLHttpRequest.prototype.open;
              var origSend = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function (method) {
                this.__pdMethod = method;
                return origOpen.apply(this, arguments);
              };
              XMLHttpRequest.prototype.send = function (body) {
                if (isWriteMethod(this.__pdMethod) && isUploadBody(body)) {
                  var done = false;
                  var finish = function () { if (!done) { done = true; end(); } };
                  begin();
                  this.addEventListener('load', finish);
                  this.addEventListener('error', finish);
                  this.addEventListener('abort', finish);
                  this.addEventListener('timeout', finish);
                }
                return origSend.apply(this, arguments);
              };

              // --- fetch ---
              if (typeof window.fetch === 'function') {
                var origFetch = window.fetch;
                window.fetch = function (input, init) {
                  var method = (init && init.method) ||
                    (input && typeof input === 'object' && input.method) || 'GET';
                  var body = (init && init.body) ||
                    (input && typeof input === 'object' && input.body);
                  if (isWriteMethod(method) && isUploadBody(body)) {
                    begin();
                    return origFetch.apply(this, arguments).then(
                      function (r) { end(); return r; },
                      function (e) { end(); throw e; }
                    );
                  }
                  return origFetch.apply(this, arguments);
                };
              }
            })();
        """.trimIndent()
    }
}
