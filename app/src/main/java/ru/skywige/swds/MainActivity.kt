package ru.skywige.swds

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ActivityInfo
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
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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
        root.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
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
        TrackService.start(this)                                    // «Записывать маршруты» — запуск, пока приложение на экране
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
            val p2 = Prefs(app)
            if (p2.subEnabled) {                                    // саб-сервер: подтянуть свежее / отправить сделанное без сети
                LocalServer.start(app)
                val last = LocalStore.get(app).meta("last_sync")?.toLongOrNull() ?: 0
                if (System.currentTimeMillis() / 1000 - last > 300) runCatching { SubSync.sync(app) }
            }
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

    // ───────── геймпад → игровой режим консоли ВМ ─────────
    private var padToJs = false
    private var padAxes = FloatArray(8)

    private fun isPad(src: Int) = (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (padToJs && ::web.isInitialized && isPad(e.source)) {
            val idx = PAD_KEYS[e.keyCode]
            if (idx != null) {
                if (e.repeatCount == 0 && (e.action == KeyEvent.ACTION_DOWN || e.action == KeyEvent.ACTION_UP))
                    web.evaluateJavascript("window.SWDSPad&&SWDSPad.key($idx,${e.action == KeyEvent.ACTION_DOWN})", null)
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (padToJs && ::web.isInitialized && (e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            && e.action == MotionEvent.ACTION_MOVE) {
            val v = floatArrayOf(e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y),
                e.getAxisValue(MotionEvent.AXIS_Z), e.getAxisValue(MotionEvent.AXIS_RZ),
                maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)),
                maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)),
                e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y))
            if (v.indices.any { kotlin.math.abs(v[it] - padAxes[it]) > 0.02f }) {
                padAxes = v
                web.evaluateJavascript("window.SWDSPad&&SWDSPad.axes([${v.joinToString(",") { "%.3f".format(java.util.Locale.US, it) }}])", null)
            }
            return true
        }
        return super.dispatchGenericMotionEvent(e)
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
                text = android.text.SpannableString("$icon\n$label").apply {
                    setSpan(android.text.style.RelativeSizeSpan(1.55f), 0, icon.length, 0)
                }
                gravity = Gravity.CENTER; textSize = 11f
                setLineSpacing(dp(1).toFloat(), 1f)
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
            t.background = if (on) android.graphics.drawable.InsetDrawable(GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor((pal.accent and 0x00FFFFFF) or (if (pal.dark) 0x2E000000 else 0x1F000000))
            }, dp(6), dp(5), dp(6), dp(5)) else null
        }
    }

    /** «Ещё»: всё приложение плитками по разделам, в нижней шторке. */
    private fun more(@Suppress("UNUSED_PARAMETER") anchor: View) {
        val pal = Theme.palette(this)
        data class T(val icon: String, val title: String, val target: String, val color: Int)
        val blue = 0xFF3B82F6.toInt(); val violet = 0xFF8B5CF6.toInt(); val green = 0xFF10B981.toInt()
        val amber = 0xFFF59E0B.toInt(); val pink = 0xFFEC4899.toInt(); val slate = 0xFF64748B.toInt(); val cyan = 0xFF06B6D4.toInt()
        val groups = listOf(
            "Телефон и компьютер" to listOf(
                T("🎛", "Пульт ПК", "/modules/mobile/remote", blue),
                T("🖥", "Устройства", "/modules/remote_control/window", blue),
                T("💻", "Виртуальные машины", "/modules/virtualization/pocket", violet),
                T("📤", "Отправить на ПК", "#send", cyan),
                T("🔄", "Синхронизация", "#sync", cyan),
                T("📲", "Уведомления телефона", "/modules/mobile/window#feed", slate),
                T("📱", "Общая папка", "/modules/mobile/window", slate)
            ),
            "Личное" to listOf(
                T("🔐", "Пароли", "#passwords", amber),
                T("🎮", "Игры SWDS", "/modules/games/window", violet),
                T("🕹", "Игры телефона", "#games", violet),
                T("📍", "Мои места", "#places", green),
                T("▶️", "Ролики и каналы", "/modules/media_manager/window#/tube", pink),
                T("✉️", "Почта", "/modules/mail/window", blue),
                T("🛒", "Покупки", "/modules/shopping/window", green),
                T("🏠", "Умный дом", "/modules/smart_home/window", amber)
            ),
            "Здоровье и дела" to listOf(
                T("⌚", "Браслет", "/modules/bracelet/window", pink),
                T("✅", "Задачи", "/modules/tasks/window", green),
                T("📝", "Заметки", "/modules/notes_manager/window", amber),
                T("📅", "Календарь", "/modules/calendar/window", blue),
                T("💳", "Кошелёк", "/modules/wallet/window", green),
                T("🌦", "Погода", "/modules/weather/window", cyan),
                T("🗺", "Геолокация", "/modules/geolocation/window", green),
                T("🛣", "Передвижения", "/modules/travels/window", violet),
                T("📚", "Библиотека", "#library", amber),
                T("📴", "Без связи", "#offline", slate)
            ),
            "SWDS" to listOf(
                T("🎙", "Ассистент", "#assist", violet),
                T("🧩", "Все модули", "/ui/modules", blue),
                T("🔔", "Уведомления", "/modules/notifications/window", amber),
                T("⚡", "Команды", "/ui/commands", amber),
                T("🤖", "Автоматизации", "/ui/scheduler", cyan),
                T("🧱", "Виджеты", "/modules/desktop_widgets/window", pink),
                T("🎨", "Темы", "/modules/theme_manager/window", pink),
                T("💽", "Хранилище", "/ui/storage", slate)
            ),
            "Приложение" to listOf(
                T("⚙", "Настройки", "#settings", slate),
                T("❤️", "Health Connect", "#health", pink),
                T("⟳", "Обновить", "#reload", slate)
            )
        )
        val dlg = android.app.Dialog(this)
        val cols = if (resources.configuration.screenWidthDp >= 600) 6 else 4
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(24))
        }
        // ручка шторки
        list.addView(View(this).apply {
            background = GradientDrawable().apply { setColor((pal.muted and 0x00FFFFFF) or 0x55000000); cornerRadius = dp(3).toFloat() }
        }, LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(6) })
        fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
        for ((title, items) in groups) {
            list.addView(TextView(this).apply {
                text = title.uppercase(); textSize = 11f; letterSpacing = 0.08f
                setTextColor(pal.muted); setTypeface(null, Typeface.BOLD)
                setPadding(dp(6), dp(14), dp(6), dp(8))
            })
            val grid = android.widget.GridLayout(this).apply { columnCount = cols; useDefaultMargins = false }
            for (t in items) {
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(dp(4), dp(10), dp(4), dp(8))
                    background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf((t.color and 0x00FFFFFF) or 0x44000000),
                        GradientDrawable().apply {
                            cornerRadius = dp(18).toFloat()
                            setColor(mix(pal.surface, t.color, if (pal.dark) 0.16f else 0.09f))
                            setStroke(1, (t.color and 0x00FFFFFF) or (if (pal.dark) 0x30000000 else 0x22000000))
                        }, null)
                    setOnClickListener { dlg.dismiss(); openTarget(t.target) }
                    contentDescription = t.title
                }
                tile.addView(TextView(this).apply {
                    text = t.icon; textSize = 25f; gravity = Gravity.CENTER
                })
                tile.addView(TextView(this).apply {
                    text = t.title; textSize = 11.5f; gravity = Gravity.CENTER; setTextColor(pal.text)
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                val lp = android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)).apply {
                    width = 0; height = dp(86); setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                grid.addView(tile, lp)
            }
            list.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val sheet = android.widget.ScrollView(this).apply {
            addView(list)
            background = GradientDrawable().apply {
                setColor(pal.surface)
                val r = dp(26).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(sheet)
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.82).toInt())
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        dlg.show()
    }

    private fun openTarget(target: String) {
        when (target) {
            "#settings" -> startActivity(Intent(this, SettingsActivity::class.java))
            "#sync" -> startActivity(Intent(this, SettingsActivity::class.java).putExtra("section", "sync"))
            "#reload" -> web.reload()
            "#offline" -> showOffline()
            "#library" -> startActivity(Intent(this, LibraryActivity::class.java))
            "#passwords" -> startActivity(Intent(this, PassActivity::class.java))
            "#places" -> startActivity(Intent(this, PlacesActivity::class.java))
            "#games" -> startActivity(Intent(this, GamesActivity::class.java))
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
        // музыка и видео, скачанные на телефон, играют из памяти телефона (без сети и без шумов недогруженного потока)
        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): android.webkit.WebResourceResponse? =
            runCatching { MediaOffline.intercept(this@MainActivity, req) }.getOrNull() ?: super.shouldInterceptRequest(view, req)

        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val url = req.url.toString()
            if (url.startsWith("swds://")) { onNewIntent(Intent(Intent.ACTION_VIEW, req.url)); return true }
            if (url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")) return false     // саб-сервер на телефоне
            if (isOurs(url)) {
                val path = req.url.path ?: ""
                // книга уже скачана в телефон — открываем свою читалку: работает без связи, громкость листает
                Regex("^/modules/library/read/([0-9a-f]{8,40})$").find(path)?.let { m ->
                    val bid = m.groupValues[1]
                    if (Library.isLocal(this@MainActivity, bid)) {
                        startActivity(Intent(this@MainActivity, ReaderActivity::class.java).putExtra("id", bid))
                        return true
                    }
                }
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
            if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED      // ориентацию фиксирует только консоль ВМ
            padToJs = false
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
    /** Саб-сервер включён — офлайн-режим это полноценное приложение на телефоне с данными и своими модулями. */
    private fun subOffline(): Boolean {
        if (!Prefs(this).subEnabled) return false
        LocalServer.start(this)
        loggedIn = false
        web.loadUrl(LocalServer.url(this) + "/app/index.html")
        return true
    }

    fun showOffline() {
        if (subOffline()) return
        loggedIn = false
        web.loadUrl("file:///android_asset/offline.html#manual")
        Thread { runCatching { OfflineData.maybeFetch(applicationContext, 60_000L) }; main.post { web.evaluateJavascript("window.onBundle && onBundle()", null) } }.start()
    }

    private fun offline(reason: String) {
        if (System.currentTimeMillis() - lastError < 1500) return
        lastError = System.currentTimeMillis()
        loggedIn = false
        if (!subOffline()) web.loadUrl("file:///android_asset/offline.html#" + Uri.encode(reason))
        // в фоне: перебрать адреса сервера (дом ↔ интернет) и вернуться, когда найдётся рабочий
        Thread {
            for (i in 0 until 3) {
                if (runCatching { Bridge.hello(this) }.isSuccess) {
                    main.post { val u = web.url ?: ""; if (u.startsWith("file:") || u.startsWith("http://127.0.0.1:")) open("/") }
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
        @JavascriptInterface fun mediaSave(json: String) = MediaOffline.save(this@MainActivity, json)
        @JavascriptInterface fun mediaAutoSave(json: String) { if (Prefs(this@MainActivity).mediaAutoCache) MediaOffline.save(this@MainActivity, json, auto = true) }
        @JavascriptInterface fun mediaCached(): String = MediaOffline.cachedIds(this@MainActivity)
        @JavascriptInterface fun mediaRemove(id: Long) = MediaOffline.remove(this@MainActivity, id)
        @JavascriptInterface fun openOffline() = main.post { showOffline() }
        @JavascriptInterface fun openOnline() = main.post { open("/") }
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun openSettings() = main.post { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        @JavascriptInterface fun sendFiles() = main.post {
            startActivity(Intent(this@MainActivity, ShareActivity::class.java).setAction(ShareActivity.A_PICK))
        }
        @JavascriptInterface fun retry() = main.post { loggedIn = false; open("/") }
        /** Офлайн-экран и страницы SWDS: открыть книги, скачанные в телефон. */
        @JavascriptInterface fun openLibrary() = main.post { startActivity(Intent(this@MainActivity, LibraryActivity::class.java)) }
        @JavascriptInterface fun toast(text: String) = Events.toast(this@MainActivity, text)
        @JavascriptInterface fun deviceId(): String = Prefs(this@MainActivity).deviceId
        @JavascriptInterface fun server(): String = base()
        /** swds_now_playing.js: что играет в плеере SWDS на этой странице → медиа-уведомление. */
        @JavascriptInterface fun media(json: String) = MediaHub.onLocal(this@MainActivity, json)
        /** Плеер медиатеки открыт или закрыт: на это время страница занимает весь экран, без нижней панели. */
        /** Страница «Устройства»: разрешить подключиться к этому телефону. */
        @JavascriptInterface fun remoteShare() = main.post { startActivity(RemoteHostActivity.intent(this@MainActivity)) }

        @JavascriptInterface fun immersive(on: Boolean) = main.post { pageImmersive = on; applyImmersive() }
        /** Консоль ВМ: зафиксировать ориентацию экрана — landscape | portrait | auto. */
        @JavascriptInterface fun orientation(mode: String) = main.post {
            requestedOrientation = when (mode) {
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        /** Игровой режим консоли ВМ: кнопки и стики геймпада — странице (а «B» не работает как «Назад»). */
        @JavascriptInterface fun gamepad(on: Boolean) = main.post { padToJs = on }
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

        // ── модуль «Игры»: этот телефон как устройство с играми ──
        /** Id телефона для «Игр» (пусто, пока «Игры телефона» выключены) — страница покажет ▶ у игр, установленных здесь. */
        @JavascriptInterface fun gamesDeviceId(): String = Prefs(this@MainActivity).let { if (it.gamesEnabled) it.deviceId else "" }
        @JavascriptInterface fun launchApp(pkg: String): Boolean = PhoneGames.launch(this@MainActivity, pkg)
        // ── страницы SWDS могут открыть экраны приложения ──
        @JavascriptInterface fun openPasswords() = main.post { startActivity(Intent(this@MainActivity, PassActivity::class.java)) }
        @JavascriptInterface fun openPlaces() = main.post { startActivity(Intent(this@MainActivity, PlacesActivity::class.java)) }
        @JavascriptInterface fun openPhoneGames() = main.post { startActivity(Intent(this@MainActivity, GamesActivity::class.java)) }
    }

    companion object {
        /** Кнопки геймпада Android → номера кнопок стандартного Gamepad API браузера. */
        private val PAD_KEYS = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to 0, KeyEvent.KEYCODE_BUTTON_B to 1, KeyEvent.KEYCODE_BUTTON_X to 2, KeyEvent.KEYCODE_BUTTON_Y to 3,
            KeyEvent.KEYCODE_BUTTON_L1 to 4, KeyEvent.KEYCODE_BUTTON_R1 to 5, KeyEvent.KEYCODE_BUTTON_L2 to 6, KeyEvent.KEYCODE_BUTTON_R2 to 7,
            KeyEvent.KEYCODE_BUTTON_SELECT to 8, KeyEvent.KEYCODE_BUTTON_START to 9, KeyEvent.KEYCODE_BUTTON_THUMBL to 10,
            KeyEvent.KEYCODE_BUTTON_THUMBR to 11, KeyEvent.KEYCODE_DPAD_UP to 12, KeyEvent.KEYCODE_DPAD_DOWN to 13,
            KeyEvent.KEYCODE_DPAD_LEFT to 14, KeyEvent.KEYCODE_DPAD_RIGHT to 15, KeyEvent.KEYCODE_BUTTON_MODE to 16)

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
