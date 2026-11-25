package com.example.antigravity

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "antigravity_prefs"
        private const val KEY_SESSION_URL = "session_target_url"
        private const val DEFAULT_TEMPLATE_URL = "https://antigravity.google.com"
        private const val SPLASH_FALLBACK_TIMEOUT_MS = 3500L
        private const val SPLASH_FADE_DURATION_MS = 260L
        private const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; 25102RKBEC) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.107 Mobile Safari/537.36"
    }

    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
    private var splashOverlay: FrameLayout? = null
    private var splashAnimators: MutableList<Animator> = mutableListOf()
    private var splashDismissed = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val splashTimeoutRunnable = Runnable {
        dismissSplashOverlay()
    }

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileUploadCallback
        fileUploadCallback = null
        if (callback == null) return@registerForActivityResult

        var uris: Array<Uri>? = null
        try {
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                if (data != null) {
                    val clipData = data.clipData
                    if (clipData != null && clipData.itemCount > 0) {
                        val uriList = ArrayList<Uri>(clipData.itemCount)
                        for (i in 0 until clipData.itemCount) {
                            clipData.getItemAt(i)?.uri?.let { uriList.add(it) }
                        }
                        if (uriList.isNotEmpty()) {
                            uris = uriList.toTypedArray()
                        }
                    } else if (data.data != null) {
                        uris = arrayOf(data.data!!)
                    }
                }
            }
        } catch (_: Exception) {
            uris = null
        }

        try {
            callback.onReceiveValue(uris)
        } catch (_: Exception) {
            // Ignore if WebView already detached
        }
    }

    private fun isDarkMode(): Boolean {
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isDark = isDarkMode()
        val adaptiveBgColor = if (isDark) Color.parseColor("#0F172A") else Color.parseColor("#FFFFFF")

        // Adaptive status & navigation bar colors matching system light/dark mode
        @Suppress("DEPRECATION")
        run {
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = adaptiveBgColor
            window.navigationBarColor = adaptiveBgColor
        }

        // Let adjustResize keep the WebView above the software keyboard.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }

        rootContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(adaptiveBgColor)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(adaptiveBgColor)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            // Hardware acceleration
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        rootContainer.addView(webView)

        // Native smooth splash overlay on top of WebView to eliminate cold-start flash
        val overlay = createSplashOverlay(isDark, adaptiveBgColor)
        splashOverlay = overlay
        rootContainer.addView(overlay)

        setContentView(rootContainer)

        setupWebView()

        // Safety fallback timeout for splash dismissal (3.5s)
        mainHandler.postDelayed(splashTimeoutRunnable, SPLASH_FALLBACK_TIMEOUT_MS)

        // Dynamic session URL loading: restore state or load configured session URL
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            val savedUrl = getSavedSessionUrl()
            if (!savedUrl.isNullOrBlank()) {
                webView.loadUrl(savedUrl)
            } else {
                dismissSplashOverlay(immediate = true)
                showSessionConfigDialog(isFirstLaunch = true)
            }
        }
    }

    private fun createSplashOverlay(isDark: Boolean, bgColor: Int): FrameLayout {
        val density = resources.displayMetrics.density
        val titleColor = if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A")
        val subtitleColor = if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")
        val iconCardColor = if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#FFFFFF")
        val iconBorderColor = if (isDark) Color.parseColor("#334155") else Color.parseColor("#E2E8F0")
        val dotColor = if (isDark) Color.parseColor("#60A5FA") else Color.parseColor("#3B82F6")

        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(bgColor)
            isClickable = true
            isFocusable = true
        }

        val centerColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        // Icon container with soft elevation shadow & subtle breathing scale animation
        val iconContainer = FrameLayout(this).apply {
            val size = dp(82)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                bottomMargin = dp(20)
            }
            background = GradientDrawable().apply {
                setColor(iconCardColor)
                cornerRadius = 22 * density
                setStroke((1 * density).toInt().coerceAtLeast(1), iconBorderColor)
            }
            elevation = dp(10).toFloat()
            clipToOutline = true
        }

        val iconView = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        iconContainer.addView(iconView)
        centerColumn.addView(iconContainer)

        // Subtle breathing animation on icon container
        val scaleXAnim = ObjectAnimator.ofFloat(iconContainer, View.SCALE_X, 0.96f, 1.04f).apply {
            duration = 1350L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleYAnim = ObjectAnimator.ofFloat(iconContainer, View.SCALE_Y, 0.96f, 1.04f).apply {
            duration = 1350L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val iconBreathSet = AnimatorSet().apply {
            playTogether(scaleXAnim, scaleYAnim)
            start()
        }
        splashAnimators.add(iconBreathSet)

        // Brand title
        val brandTitle = TextView(this).apply {
            text = "Antigravity"
            textSize = 24f
            letterSpacing = 0.04f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(titleColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        }
        centerColumn.addView(brandTitle)

        // Subtle minimalist subtitle
        val subtitle = TextView(this).apply {
            text = "Workspace"
            textSize = 13f
            letterSpacing = 0.08f
            setTextColor(subtitleColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(22)
            }
        }
        centerColumn.addView(subtitle)

        // Minimalist 3-dot subtle loading indicator
        val dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val dotSize = dp(6)
        val dotMargin = dp(4)
        for (i in 0 until 3) {
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginStart = dotMargin
                    marginEnd = dotMargin
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dotColor)
                }
                alpha = 0.25f
            }
            dotsRow.addView(dot)

            val dotAnim = ObjectAnimator.ofFloat(dot, View.ALPHA, 0.22f, 0.90f).apply {
                duration = 650L
                startDelay = i * 180L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            splashAnimators.add(dotAnim)
        }
        centerColumn.addView(dotsRow)

        overlay.addView(centerColumn)
        return overlay
    }

    private fun stopSplashAnimations() {
        splashAnimators.forEach { animator ->
            try {
                animator.cancel()
            } catch (_: Exception) {}
        }
        splashAnimators.clear()
    }

    private fun dismissSplashOverlay(immediate: Boolean = false) {
        if (splashDismissed) return
        splashDismissed = true
        mainHandler.removeCallbacks(splashTimeoutRunnable)

        val overlay = splashOverlay ?: return
        if (overlay.visibility != View.VISIBLE) {
            stopSplashAnimations()
            return
        }

        if (immediate) {
            stopSplashAnimations()
            overlay.alpha = 0f
            overlay.visibility = View.GONE
            return
        }

        overlay.animate()
            .alpha(0f)
            .setDuration(SPLASH_FADE_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                stopSplashAnimations()
                overlay.visibility = View.GONE
            }
            .start()
    }

    private fun getSavedSessionUrl(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SESSION_URL, null)?.takeIf { it.isNotBlank() }
    }

    private fun saveSessionUrl(url: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SESSION_URL, url.trim()).apply()
    }

    private fun showSessionConfigDialog(isFirstLaunch: Boolean) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(!isFirstLaunch)

        val context = this
        val density = resources.displayMetrics.density

        // Root Dialog Card Container (Sleek Dark OLED Theme)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B")) // Slate 800
                cornerRadius = 16 * density
                setStroke((1 * density).toInt(), Color.parseColor("#334155")) // Slate 700 border
            }
            background = bg
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Title
        val titleView = TextView(context).apply {
            text = "Antigravity Session"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
        }
        card.addView(titleView)

        // Subtitle / Instruction
        val subtitleView = TextView(context).apply {
            text = "Paste your Antigravity session URL below. Tip: Tap with 3 fingers on screen anytime to reopen this menu."
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(8), 0, dp(16))
            setLineSpacing(0f, 1.2f)
        }
        card.addView(subtitleView)

        // URL Input Field
        val inputEdit = EditText(context).apply {
            hint = "https://antigravity.google.com/..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 14f
            maxLines = 3
            isSingleLine = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(14), dp(12), dp(14), dp(12))

            val inputBg = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A")) // Slate 900
                cornerRadius = 8 * density
                setStroke((1 * density).toInt(), Color.parseColor("#334155"))
            }
            background = inputBg

            val existing = getSavedSessionUrl() ?: ""
            setText(existing)
            if (existing.isNotEmpty()) {
                setSelection(existing.length)
            }
        }
        card.addView(inputEdit)

        // Helper button: "Use Default Template"
        val templateBtn = TextView(context).apply {
            text = "Use Default Template: antigravity.google.com"
            textSize = 12f
            setTextColor(Color.parseColor("#60A5FA"))
            setPadding(0, dp(10), 0, dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                inputEdit.setText(DEFAULT_TEMPLATE_URL)
                inputEdit.setSelection(DEFAULT_TEMPLATE_URL.length)
            }
        }
        card.addView(templateBtn)

        // Account Switch & Reset Actions
        val accountActions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }

        val switchAccountBtn = TextView(context).apply {
            text = "🔄 切换谷歌账号 (Switch Google Account)"
            textSize = 13f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = 8 * density
                setStroke((1 * density).toInt(), Color.parseColor("#0284C7"))
            }
            background = btnBg
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                val chooserUrl = "https://accounts.google.com/AccountChooser?continue=https://antigravity.google.com/"
                saveSessionUrl(DEFAULT_TEMPLATE_URL)
                webView.loadUrl(chooserUrl)
            }
        }
        accountActions.addView(switchAccountBtn)

        val clearSessionBtn = TextView(context).apply {
            text = "🧹 清除登录凭证并退出 (Clear Session & Re-login)"
            textSize = 12f
            setTextColor(Color.parseColor("#F87171"))
            setPadding(dp(4), dp(10), 0, dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    saveSessionUrl(DEFAULT_TEMPLATE_URL)
                    runOnUiThread {
                        webView.clearCache(true)
                        webView.loadUrl("https://accounts.google.com/AccountChooser?continue=https://antigravity.google.com/")
                    }
                }
            }
        }
        accountActions.addView(clearSessionBtn)

        card.addView(accountActions)

        // Action Buttons Row
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        if (!isFirstLaunch) {
            val cancelBtn = Button(context).apply {
                text = "Cancel"
                setTextColor(Color.parseColor("#94A3B8"))
                setBackgroundColor(Color.TRANSPARENT)
                textSize = 14f
                setOnClickListener {
                    dialog.dismiss()
                }
            }
            buttonRow.addView(cancelBtn)
        }

        val connectBtn = Button(context).apply {
            text = "Connect & Launch"
            setTextColor(Color.WHITE)
            textSize = 14f
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#2563EB")) // Primary Blue
                cornerRadius = 8 * density
            }
            background = btnBg
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener {
                var url = inputEdit.text.toString().trim()
                if (url.isEmpty()) {
                    url = DEFAULT_TEMPLATE_URL
                } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }

                saveSessionUrl(url)
                webView.loadUrl(url)
                dialog.dismiss()
            }
        }
        buttonRow.addView(connectBtn)
        card.addView(buttonRow)

        dialog.setContentView(card)

        if (isFirstLaunch) {
            dialog.setOnCancelListener {
                finish()
            }
        }

        dialog.show()

        dialog.window?.let { win ->
            val displayMetrics = resources.displayMetrics
            win.setLayout(
                (displayMetrics.widthPixels * 0.90).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.allowContentAccess = true
        settings.allowFileAccess = true
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = true

        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString = CHROME_UA

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                dismissSplashOverlay()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                dismissSplashOverlay()

                CookieManager.getInstance().flush()

                // Persist current account base portal (e.g. /u/1/ or /) without locking onto temporary dead /r/ sessions
                url?.let { currentUrl ->
                    if (currentUrl.startsWith("https://antigravity.google.com") &&
                        !currentUrl.contains("/signin/") &&
                        !currentUrl.contains("/oauth") &&
                        !currentUrl.contains("accounts.google.com")) {
                        val baseAccountUrl = if (currentUrl.contains("/u/")) {
                            val match = Regex("""(https://antigravity\.google\.com/u/\d+/)""").find(currentUrl)
                            match?.value ?: DEFAULT_TEMPLATE_URL
                        } else {
                            DEFAULT_TEMPLATE_URL
                        }
                        saveSessionUrl(baseAccountUrl)
                    }
                }

                // Inject AGY chat panel auto-expansion script
                view?.evaluateJavascript(
                    """
                    (function() {
                        // 1. Hide scrollbars
                        var style = document.createElement('style');
                        style.textContent = '::-webkit-scrollbar { display: none !important; }';
                        document.head.appendChild(style);

                        // 2. Auto-expand AGY panel if on desktop download welcome screen
                        setTimeout(function() {
                            var menuBtns = document.querySelectorAll('button, div[role="button"], a');
                            for (var i = 0; i < menuBtns.length; i++) {
                                var btn = menuBtns[i];
                                var aria = (btn.getAttribute('aria-label') || '').toLowerCase();
                                var text = (btn.innerText || '').toLowerCase();
                                if (aria.indexOf('menu') !== -1 || aria.indexOf('sidebar') !== -1 || aria.indexOf('agent') !== -1 || text.indexOf('agy') !== -1) {
                                    btn.click();
                                    break;
                                }
                            }
                        }, 1200);
                    })();
                    """.trimIndent(),
                    null
                )
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                try {
                    val urlToReload = getSavedSessionUrl() ?: DEFAULT_TEMPLATE_URL
                    view?.loadUrl(urlToReload)
                } catch (_: Exception) {}
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    dismissSplashOverlay()
                    // Prompt user with config dialog if the remote URL could not be resolved or reached
                    showSessionConfigDialog(isFirstLaunch = false)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Always release any pending callback first to prevent <input type="file"> lockup
                try {
                    fileUploadCallback?.onReceiveValue(null)
                } catch (_: Exception) {}
                fileUploadCallback = filePathCallback

                return try {
                    val contentIntent = buildFileChooserIntent(fileChooserParams)
                    val chooserTitle = fileChooserParams?.title?.toString()?.takeIf { it.isNotBlank() } ?: "选择文件"
                    fileChooserLauncher.launch(Intent.createChooser(contentIntent, chooserTitle))
                    true
                } catch (_: Exception) {
                    try {
                        fileUploadCallback?.onReceiveValue(null)
                    } catch (_: Exception) {}
                    fileUploadCallback = null
                    false
                }
            }
        }
    }

    private fun buildFileChooserIntent(fileChooserParams: WebChromeClient.FileChooserParams?): Intent {
        val rawAcceptTypes = fileChooserParams?.acceptTypes.orEmpty()
        val resolvedMimeTypes = LinkedHashSet<String>()

        for (raw in rawAcceptTypes) {
            for (token in raw.split(",")) {
                val trimmed = token.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed == "*" || trimmed == "*/*") {
                    resolvedMimeTypes.clear()
                    resolvedMimeTypes.add("*/*")
                    break
                }
                if (trimmed.startsWith(".")) {
                    val ext = trimmed.removePrefix(".").lowercase()
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    if (!mime.isNullOrBlank()) {
                        resolvedMimeTypes.add(mime)
                    }
                } else if (trimmed.contains("/")) {
                    resolvedMimeTypes.add(trimmed)
                }
            }
            if (resolvedMimeTypes.contains("*/*")) break
        }

        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            when {
                resolvedMimeTypes.isEmpty() || resolvedMimeTypes.contains("*/*") -> {
                    type = "*/*"
                }
                resolvedMimeTypes.size == 1 -> {
                    type = resolvedMimeTypes.first()
                }
                else -> {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, resolvedMimeTypes.toTypedArray())
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null && ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            if (ev.pointerCount >= 3) {
                showSessionConfigDialog(isFirstLaunch = false)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onPause() {
        super.onPause()
        webView.url?.let { currentUrl ->
            if (currentUrl.startsWith("https://antigravity.google.com") &&
                !currentUrl.contains("/signin/") &&
                !currentUrl.contains("/oauth") &&
                !currentUrl.contains("accounts.google.com")) {
                val baseAccountUrl = if (currentUrl.contains("/u/")) {
                    val match = Regex("""(https://antigravity\.google\.com/u/\d+/)""").find(currentUrl)
                    match?.value ?: DEFAULT_TEMPLATE_URL
                } else {
                    DEFAULT_TEMPLATE_URL
                }
                saveSessionUrl(baseAccountUrl)
            }
        }
        CookieManager.getInstance().flush()
    }

    override fun onStop() {
        super.onStop()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(splashTimeoutRunnable)
        stopSplashAnimations()
        try {
            fileUploadCallback?.onReceiveValue(null)
        } catch (_: Exception) {}
        fileUploadCallback = null
        CookieManager.getInstance().flush()
        super.onDestroy()
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            showSessionConfigDialog(isFirstLaunch = false)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event?.startTracking()
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            } else {
                // Return to desktop while keeping process resident in memory for 0ms instant resume
                moveTaskToBack(true)
                return true
            }
        } else if (keyCode == KeyEvent.KEYCODE_MENU) {
            showSessionConfigDialog(isFirstLaunch = false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
