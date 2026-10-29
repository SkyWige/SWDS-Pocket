package ru.skywige.swds

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

/**
 * Главный экран: SWDS в WebView (все модули, темы, ассистент) + нижняя навигация.
 * Вход — по токену устройства: /modules/mobile/api/web_login ставит сессию и открывает нужную страницу.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var bar: ProgressBar
    private lateinit var nav: LinearLayout
    private val tabs = ArrayList<Pair<TextView, String>>()
    private val main = Handler(Looper.getMainLooper())
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private var loggedIn = false
    private var lastError = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val TABS = listOf(
        Triple("☀️", "Сегодня", "/"),
        Triple("🧩", "Модули", "/ui/modules"),
        Triple("🎙", "Ассистент", "/ui/assistant"),
        Triple("📂", "Файлы", "/modules/mobile/window#files"),
        Triple("☰", "Ещё", "")
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs(this)
        val data = intent?.data
        if (data?.scheme == "swds" && data.host == "pair") {
            startActivity(Intent(this, PairActivity::class.java).setData(data)); finish(); return
        }
        if (!p.paired) {
            startActivity(Intent(this, PairActivity::class.java)); finish(); return
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val frame = FrameLayout(this)
        web = WebView(this)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        frame.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        root.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        setContentView(root)
        buildNav()
        applyTheme()

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = userAgentString + " " + Api.UA
        }
        web.addJavascriptInterface(PageBridge(), "SWDSPocket")
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.setDownloadListener { url, ua, cd, mime, len -> download(url, ua, cd, mime, len) }

        Events.onTheme = { applyTheme(); if (loggedIn) web.reload() }
        askNotificationPermission()
        open(pathFrom(intent) ?: "/")
        PushService.start(this)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val data = intent?.data
        if (data?.scheme == "swds" && data.host == "pair") {
            startActivity(Intent(this, PairActivity::class.java).setData(data)); return
        }
        pathFrom(intent)?.let { open(it) }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        if (!Prefs(this).paired) { startActivity(Intent(this, PairActivity::class.java)); finish(); return }
        Thread {
            runCatching { Bridge.hello(this) }
            main.post { applyTheme() }
        }.start()
        PushService.start(this)
    }

    override fun onPause() {
        visible = false
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        if (Events.onTheme != null) Events.onTheme = null
        if (::web.isInitialized) runCatching { web.destroy() }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack()
        else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun pathFrom(i: Intent?): String? {
        i?.getStringExtra("path")?.takeIf { it.isNotEmpty() }?.let { return it }
        val d = i?.data ?: return null
        if (d.scheme == "swds") return d.getQueryParameter("path") ?: d.path?.ifEmpty { null } ?: "/"
        return null
    }

    private fun base() = Prefs(this).let { it.activeServer.ifEmpty { it.servers.firstOrNull() ?: "" } }

    /** Открыть страницу SWDS (через вход по токену, если сессии ещё нет). */
    fun open(path: String) {
        val b = base()
        if (b.isEmpty()) return
        if (path.startsWith("http://") || path.startsWith("https://")) { web.loadUrl(path); return }
        if (!loggedIn) login(path) else web.loadUrl(b + path)
    }

    private fun login(next: String) {
        val p = Prefs(this)
        web.loadUrl(base() + "/modules/mobile/api/web_login?next=" + Uri.encode(next),
            mapOf("Authorization" to "Bearer " + p.token))
        loggedIn = true
    }

    private fun isOurs(url: String): Boolean {
        val host = Uri.parse(url).host ?: return false
        return Prefs(this).servers.any { Uri.parse(it).host == host }
    }

    // ───────── навигация ─────────
    private fun buildNav() {
        nav.removeAllViews(); tabs.clear()
        for ((icon, label, path) in TABS) {
            val t = TextView(this).apply {
                text = "$icon\n$label"; gravity = Gravity.CENTER; textSize = 11f
                setLineSpacing(dp(2).toFloat(), 1f)
                setOnClickListener {
                    if (path.isEmpty()) more(this) else {
                        val cur = web.url ?: ""
                        if (cur.startsWith(base() + path.substringBefore('#')) && path != "/") web.reload() else open(path)
                    }
                }
            }
            nav.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            tabs.add(t to path)
        }
    }

    private fun highlight(url: String?) {
        val pal = Theme.palette(this)
        val path = url?.let { u -> val b = base(); if (u.startsWith(b)) u.substring(b.length) else "" } ?: ""
        var best: TextView? = null
        var bestLen = -1
        for ((t, p) in tabs) {
            if (p.isEmpty()) continue
            val pp = p.substringBefore('#')
            val hit = if (pp == "/") path == "/" || path.isEmpty() || path.startsWith("/?") else path.startsWith(pp) &&
                    (p.indexOf('#') < 0 || path.contains(p.substringAfter('#')))
            if (hit && pp.length > bestLen) { best = t; bestLen = pp.length }
        }
        for ((t, _) in tabs) {
            val on = t == best
            t.setTextColor(if (on) pal.accent else pal.muted)
            t.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun more(anchor: View) {
        val m = PopupMenu(this, anchor)
        val items = listOf(
            "🔔 Уведомления" to "/modules/notifications/window",
            "⚡ Команды" to "/ui/commands",
            "🤖 Планировщик" to "/ui/scheduler",
            "📱 Телефон и общая папка" to "/modules/mobile/window",
            "🧩 Виджеты" to "/modules/desktop_widgets/window",
            "🎨 Темы" to "/modules/theme_manager/window",
            "📤 Отправить файл на ПК" to "#send",
            "🔄 Обновить страницу" to "#reload",
            "⚙ Настройки приложения" to "#settings"
        )
        items.forEachIndexed { i, (t, _) -> m.menu.add(0, i, i, t) }
        m.setOnMenuItemClickListener { mi ->
            when (val target = items[mi.itemId].second) {
                "#settings" -> startActivity(Intent(this, SettingsActivity::class.java))
                "#reload" -> web.reload()
                "#send" -> startActivity(Intent(this, ShareActivity::class.java).setAction(ShareActivity.A_PICK))
                else -> open(target)
            }
            true
        }
        m.show()
    }

    private fun applyTheme() {
        val pal = Theme.palette(this)
        window.statusBarColor = pal.topbar
        window.navigationBarColor = pal.surface
        if (Build.VERSION.SDK_INT >= 26) {
            var flags = window.decorView.systemUiVisibility
            flags = if (Theme.isLight(pal.surface)) flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            else flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            flags = if (Theme.isLight(pal.topbar)) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
        nav.background = GradientDrawable().apply {
            setColor(pal.surface)
            setStroke(1, (pal.muted and 0x00FFFFFF) or 0x22000000)
        }
        web.setBackgroundColor(pal.bg)
        bar.progressTintList = android.content.res.ColorStateList.valueOf(pal.accent)
        highlight(web.url)
    }

    // ───────── WebView ─────────
    inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val url = req.url.toString()
            if (url.startsWith("swds://")) { onNewIntent(Intent(Intent.ACTION_VIEW, req.url)); return true }
            if (isOurs(url)) {
                val path = req.url.path ?: ""
                if (path.startsWith("/auth/login") || path.startsWith("/auth/register")) {
                    login(req.url.getQueryParameter("next") ?: "/")      // сессия истекла — входим токеном заново
                    return true
                }
                return false
            }
            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("intent:") ||
                url.startsWith("mailto:") || url.startsWith("tel:") || url.startsWith("tg:")) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            bar.visibility = View.VISIBLE
            highlight(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            bar.visibility = View.GONE
            highlight(url)
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(view: WebView, req: WebResourceRequest, error: WebResourceError) {
            if (!req.isForMainFrame) return
            offline(error.description?.toString() ?: "Нет связи")
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // самоподписанный сертификат домашнего SWDS — только для адресов, к которым привязан телефон
            if (isOurs(error.url)) handler.proceed() else handler.cancel()
        }
    }

    private fun offline(reason: String) {
        if (System.currentTimeMillis() - lastError < 1500) return
        lastError = System.currentTimeMillis()
        loggedIn = false
        web.loadUrl("file:///android_asset/offline.html#" + Uri.encode(reason))
        // в фоне: перебрать адреса сервера (дом ↔ интернет) и вернуться, когда найдётся рабочий
        Thread {
            for (i in 0 until 3) {
                if (runCatching { Bridge.hello(this) }.isSuccess) {
                    main.post { if ((web.url ?: "").startsWith("file:")) open("/") }
                    return@Thread
                }
                Thread.sleep(4000)
            }
        }.start()
    }

    inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            bar.progress = newProgress
            bar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCb?.onReceiveValue(null)
            fileCb = cb
            val i = try { params.createIntent().putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) }
            catch (e: Exception) { Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE) }
            return try { startActivityForResult(i, RC_FILE); true } catch (e: Exception) { fileCb = null; false }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            val ok = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!ok) requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), RC_PERM)
            callback.invoke(origin, ok, false)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            main.post {
                val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!isOurs(request.origin.toString())) { request.deny(); return@post }
                if (wantsMic && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingPermission = request
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RC_MIC)
                } else request.grant(request.resources)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_FILE) {
            val res: Array<Uri>? = if (resultCode != RESULT_OK || data == null) null else {
                val list = ArrayList<Uri>()
                data.clipData?.let { c -> for (i in 0 until c.itemCount) list.add(c.getItemAt(i).uri) }
                data.data?.let { if (it !in list) list.add(it) }
                list.toTypedArray()
            }
            fileCb?.onReceiveValue(res)
            fileCb = null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_MIC) {
            val r = pendingPermission ?: return
            pendingPermission = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) r.grant(r.resources) else r.deny()
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_PERM)
    }

    /** Скачивание из SWDS (кнопки «Скачать» в модулях) → Загрузки/SWDS. */
    private fun download(url: String, ua: String?, cd: String?, mime: String?, len: Long) {
        val name = URLUtil.guessFileName(url, cd, mime)
        Events.toast(this, "⬇ $name")
        val app = applicationContext
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
                if (isOurs(url)) conn.setRequestProperty("Authorization", "Bearer " + Prefs(app).token)
                ua?.let { conn.setRequestProperty("User-Agent", it) }
                if (conn is javax.net.ssl.HttpsURLConnection && isOurs(url)) {
                    // тот же самоподписанный сертификат, что и в WebView
                    val tm = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                        override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
                        override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                    })
                    val sc = javax.net.ssl.SSLContext.getInstance("TLS"); sc.init(null, tm, java.security.SecureRandom())
                    conn.sslSocketFactory = sc.socketFactory
                    conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
                if (conn.responseCode >= 400) throw ApiException("Ошибка ${conn.responseCode}")
                val type = mime ?: conn.contentType ?: Files.mimeOf(name)
                val uri = conn.inputStream.use { Files.saveToDownloads(app, name, type, it, if (len > 0) len else conn.contentLengthLong) { d, t ->
                    Notifier.progress(app, Notifier.ID_DOWNLOAD, "⬇ $name", Files.human(d) + " из " + Files.human(t), d, t)
                } }
                Notifier.cancel(app, Notifier.ID_DOWNLOAD)
                val pi = PendingIntent.getActivity(app, 4100, Files.viewIntent(uri, type),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                Notifier.simple(app, 4100 + (name.hashCode() and 0xFFF), "📥 Скачано", "$name → Загрузки/SWDS", Notifier.CH_FILES, pi)
            } catch (e: Exception) {
                Notifier.cancel(app, Notifier.ID_DOWNLOAD)
                Events.toast(app, "⚠ Не скачалось: ${e.message}")
            }
        }.start()
    }

    /** window.SWDSPocket — для страниц SWDS и offline.html. */
    inner class PageBridge {
        @JavascriptInterface fun isApp(): Boolean = true
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun openSettings() = main.post { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        @JavascriptInterface fun sendFiles() = main.post {
            startActivity(Intent(this@MainActivity, ShareActivity::class.java).setAction(ShareActivity.A_PICK))
        }
        @JavascriptInterface fun retry() = main.post { loggedIn = false; open("/") }
        @JavascriptInterface fun toast(text: String) = Events.toast(this@MainActivity, text)
        @JavascriptInterface fun deviceId(): String = Prefs(this@MainActivity).deviceId
        @JavascriptInterface fun server(): String = base()
    }

    companion object {
        @Volatile var visible = false
        const val RC_FILE = 31
        const val RC_PERM = 32
        const val RC_MIC = 33

        @Suppress("unused")
        fun finishAll(a: Activity) = a.finishAffinity()
    }
}
