package ru.skywige.swds

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
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
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
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
    private var fsView: View? = null                               // видео во весь экран (Fullscreen API страницы)
    private var fsCallback: WebChromeClient.CustomViewCallback? = null
    private var pageImmersive = false                              // страница открыла свой видеоплеер на весь экран

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val TABS = listOf(
        Triple("☀️", "Сегодня", "/"),
        Triple("🎛", "Пульт", "/modules/mobile/remote"),
        Triple("🎬", "Медиа", "/modules/media_manager/window"),
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
        current = java.lang.ref.WeakReference(this)
        askNotificationPermission()
        if (intent?.getBooleanExtra("offline", false) == true) showOffline() else open(pathFrom(intent) ?: "/")
        PushService.start(this)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val data = intent?.data
        if (data?.scheme == "swds" && data.host == "pair") {
            startActivity(Intent(this, PairActivity::class.java).setData(data)); return
        }
        if (intent?.getBooleanExtra("offline", false) == true) { showOffline(); return }
        pathFrom(intent)?.let { open(it) }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        if (!Prefs(this).paired) { startActivity(Intent(this, PairActivity::class.java)); finish(); return }
        val app = applicationContext
        Thread {
            runCatching { Bridge.hello(this) }
            main.post { applyTheme() }
            // приложение на экране — Health Connect разрешает читать и без «фонового» разрешения
            val p = Prefs(app)
            if (p.healthEnabled && System.currentTimeMillis() - p.healthLastSync > 20 * 60_000L) {
                runCatching { Health.sync(app) }
                runCatching { Health.pullServerSummary(app) }
            }
            runCatching { OfflineData.maybeFetch(app, 10 * 60_000L) }
        }.start()
        PushService.start(this)
        syncFoldersIfStale()
    }

    /** Открыли приложение — подтянуть изменения папок «в обе стороны», если давно не синхронизировались. */
    private fun syncFoldersIfStale() {
        val rules = Prefs(this).syncRules
        val now = System.currentTimeMillis()
        val stale = (0 until rules.length()).mapNotNull { rules.optJSONObject(it) }.any {
            it.optBoolean("enabled", true) && it.optString("mode", FolderSync.UPLOAD) != FolderSync.UPLOAD &&
                    now - it.optLong("last_sync", 0) > 2 * 60_000
        }
        if (stale) SyncWorker.now(this, "folders")
    }

    override fun onPause() {
        visible = false
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        if (Events.onTheme != null) Events.onTheme = null
        removeFullscreenView()
        if (current?.get() === this) { current = null; MediaHub.clearLocal(this) }
        if (::web.isInitialized) runCatching { web.destroy() }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fsView != null) exitFullscreen()
        else if (pageImmersive) web.evaluateJavascript(JS_PLAYER_BACK, null)   // «Назад» закрывает плеер, а не уводит со страницы
        else if (::web.isInitialized && web.canGoBack()) web.goBack()
        else if (MediaHub.foreground) moveTaskToBack(true)          // музыка играет — не закрываем, а сворачиваем
        else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()                               // системные панели возвращаются после диалогов и смены приложений
    }

    // ───────── полноэкранное видео ─────────
    /** Пока идёт видео на весь экран, нижняя навигация и системные панели не должны его перекрывать. */
    private fun applyImmersive() {
        if (!::nav.isInitialized) return
        val on = fsView != null || pageImmersive
        nav.visibility = if (on) View.GONE else View.VISIBLE
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (on) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (on) {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.systemBars())
            } else c.show(WindowInsets.Type.systemBars())
        } else {
            val hide = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                if (on) window.decorView.systemUiVisibility or hide else window.decorView.systemUiVisibility and hide.inv()
        }
    }

    private fun removeFullscreenView() {
        val v = fsView ?: return
        (window.decorView as? ViewGroup)?.removeView(v)
        fsView = null
        fsCallback = null
        applyImmersive()
    }

    /** Выход по кнопке «Назад»: WebView сообщит странице, что полноэкранный режим закрыт. */
    private fun exitFullscreen() {
        val cb = fsCallback
        removeFullscreenView()
        cb?.onCustomViewHidden()
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

    /** «Ещё»: всё приложение одним списком по разделам, снизу экрана. */
    private fun more(@Suppress("UNUSED_PARAMETER") anchor: View) {
        val pal = Theme.palette(this)
        val groups = listOf(
            "Телефон и компьютер" to listOf(
                "🎛  Пульт: мышь, клавиатура, экран ПК" to "/modules/mobile/remote",
                "📤  Отправить файл на ПК" to "#send",
                "🔄  Синхронизация папок" to "#sync",
                "📲  Лента уведомлений телефона" to "/modules/mobile/window#feed",
                "📱  Телефон и общая папка" to "/modules/mobile/window"
            ),
            "Здоровье и дела" to listOf(
                "⌚  Браслет: шаги, сон, пульс" to "/modules/bracelet/window",
                "✅  Задачи" to "/modules/tasks/window",
                "📝  Заметки" to "/modules/notes_manager/window",
                "📍  Геолокация" to "/modules/geolocation/window",
                "📴  Без связи: данные на телефоне" to "#offline"
            ),
            "SWDS" to listOf(
                "🎙  Ассистент" to "#assist",
                "🧩  Все модули" to "/ui/modules",
                "🔔  Уведомления" to "/modules/notifications/window",
                "⚡  Команды" to "/ui/commands",
                "🤖  Автоматизации" to "/ui/scheduler",
                "🧱  Виджеты" to "/modules/desktop_widgets/window",
                "🎨  Темы" to "/modules/theme_manager/window"
            ),
            "Приложение" to listOf(
                "⚙  Настройки" to "#settings",
                "❤️  Health Connect" to "#health",
                "⟳  Обновить страницу" to "#reload"
            )
        )
        val dlg = android.app.Dialog(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(20))
        }
        for ((title, items) in groups) {
            list.addView(TextView(this).apply {
                text = title.uppercase(); textSize = 11f; letterSpacing = 0.07f
                setTextColor(pal.muted); setTypeface(null, Typeface.BOLD)
                setPadding(dp(12), dp(12), dp(12), dp(6))
            })
            for ((label, target) in items) {
                list.addView(TextView(this).apply {
                    text = label; textSize = 16f; setTextColor(pal.text); gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(48); setPadding(dp(12), 0, dp(12), 0)
                    val tv = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    setBackgroundResource(tv.resourceId)
                    setOnClickListener { dlg.dismiss(); openTarget(target) }
                })
            }
        }
        val sheet = android.widget.ScrollView(this).apply {
            addView(list)
            background = GradientDrawable().apply {
                setColor(pal.surface)
                val r = dp(20).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(sheet)
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
    }

    private fun openTarget(target: String) {
        when (target) {
            "#settings" -> startActivity(Intent(this, SettingsActivity::class.java))
            "#sync" -> startActivity(Intent(this, SettingsActivity::class.java).putExtra("section", "sync"))
            "#reload" -> web.reload()
            "#offline" -> showOffline()
            "#health" -> startActivity(Intent(this, SettingsActivity::class.java).putExtra("section", "health"))
            "#assist" -> startActivity(Intent(this, AssistActivity::class.java))
            "#send" -> startActivity(Intent(this, ShareActivity::class.java).setAction(ShareActivity.A_PICK))
            else -> open(target)
        }
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
        applyImmersive()
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
            if (pageImmersive) { pageImmersive = false; applyImmersive() }  // новая страница — панель снова нужна
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

    /** Экран «без связи» с данными на телефоне — можно открыть и при живой связи из меню «Ещё». */
    fun showOffline() {
        loggedIn = false
        web.loadUrl("file:///android_asset/offline.html#manual")
        Thread { runCatching { OfflineData.maybeFetch(applicationContext, 60_000L) }; main.post { web.evaluateJavascript("window.onBundle && onBundle()", null) } }.start()
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
        // Без этой пары WebView считает, что приложение не умеет полный экран: requestFullscreen() на странице не срабатывает.
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fsView != null) { callback.onCustomViewHidden(); return }
            fsView = view
            fsCallback = callback
            view.setBackgroundColor(Color.BLACK)
            (window.decorView as ViewGroup).addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            applyImmersive()
        }

        override fun onHideCustomView() = removeFullscreenView()

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            bar.progress = newProgress
            bar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
            fileCb?.onReceiveValue(null)
            fileCb = cb
            val i = try { params.createIntent().putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) }
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
        /** swds_now_playing.js: что играет в плеере SWDS на этой странице → медиа-уведомление. */
        @JavascriptInterface fun media(json: String) = MediaHub.onLocal(this@MainActivity, json)
        /** Плеер медиатеки открыт или закрыт: на это время страница занимает весь экран, без нижней панели. */
        /** Страница «Устройства»: разрешить подключиться к этому телефону. */
        @JavascriptInterface fun remoteShare() = main.post { startActivity(RemoteHostActivity.intent(this@MainActivity)) }

        @JavascriptInterface fun immersive(on: Boolean) = main.post { pageImmersive = on; applyImmersive() }
        /** offline.html: последняя сводка «Сегодня» и очередь. */
        @JavascriptInterface fun cache(): String = org.json.JSONObject()
            .put("today", runCatching { org.json.JSONObject(Prefs(this@MainActivity).todayCache) }.getOrNull() ?: org.json.JSONObject.NULL)
            .put("queue", OfflineQueue.count(this@MainActivity))
            .put("feed", FeedStore.pendingCount(this@MainActivity)).toString()
        @JavascriptInterface fun assistant() = main.post { startActivity(Intent(this@MainActivity, AssistActivity::class.java)) }
        @JavascriptInterface fun openSync() = main.post { startActivity(Intent(this@MainActivity, SettingsActivity::class.java).putExtra("section", "sync")) }
        @JavascriptInterface fun deviceName(): String = Prefs(this@MainActivity).deviceName

        // ── офлайн-экран: снимок данных и правки без связи ──
        @JavascriptInterface fun bundle(): String = OfflineData.read(this@MainActivity)
        @JavascriptInterface fun saveBundle(json: String) = OfflineData.save(this@MainActivity, json)
        @JavascriptInterface fun health(): String = Prefs(this@MainActivity).healthToday
        @JavascriptInterface fun queued(): Int = OfflineQueue.count(this@MainActivity)
        /** Команда из офлайн-экрана: в очередь (client_id защищает от дублей), отправка — как только будет связь. */
        @JavascriptInterface fun exec(command: String, argsJson: String, title: String, clientId: String): Boolean {
            val args = runCatching { org.json.JSONObject(argsJson) }.getOrNull() ?: return false
            OfflineQueue.addExec(this@MainActivity, command, args, title, clientId)
            return true
        }
        @JavascriptInterface fun refreshBundle() {
            val app = applicationContext
            Thread {
                val ok = runCatching { OfflineQueue.flush(app); OfflineData.fetch(app) }.getOrDefault(false)
                main.post { if (!isFinishing) web.evaluateJavascript("window.onBundle && onBundle($ok)", null) }
            }.start()
        }
        @JavascriptInterface fun syncHealth() = SyncWorker.now(this@MainActivity, "health", manual = true)
        @JavascriptInterface fun openPath(path: String) = main.post { loggedIn = false; open(path) }
    }

    companion object {
        @Volatile var visible = false
        @Volatile var current: java.lang.ref.WeakReference<MainActivity>? = null

        /** Выполнить JS на открытой странице SWDS (управление плеером из медиа-уведомления). */
        fun runJs(code: String) {
            val a = current?.get() ?: return
            a.main.post { if (!a.isFinishing) a.web.evaluateJavascript(code, null) }
        }
        /** Запуск с сервера: очередь — в плеер SWDS на странице, а если страница не наша — отдельная страница плеера. */
        fun playQueue(e: org.json.JSONObject) {
            val a = current?.get() ?: return
            val js = "(function(){var g=window.GlobalMediaPlayer;if(!g)return false;g.playQueue(" + e.optJSONArray("items") +
                "," + e.optInt("index", 0) + ",{repeat:" + org.json.JSONObject.quote(e.optString("repeat", "off")) + "});return true})()"
            a.main.post {
                if (a.isFinishing) return@post
                a.web.evaluateJavascript(js) { r -> if (r != "true") a.open(e.optString("page")) }
            }
        }
        /** «Назад» при открытом плеере: страница может быть и во фрейме хоста (swds_host.js). */
        private const val JS_PLAYER_BACK = """(function(){var n='swds:pocket-back';window.dispatchEvent(new Event(n));
            var f=document.getElementById('swds-frame');try{if(f&&f.contentWindow)f.contentWindow.dispatchEvent(new Event(n))}catch(e){}})()"""
        const val RC_FILE = 31
        const val RC_PERM = 32
        const val RC_MIC = 33

        @Suppress("unused")
        fun finishAll(a: Activity) = a.finishAffinity()
    }
}
