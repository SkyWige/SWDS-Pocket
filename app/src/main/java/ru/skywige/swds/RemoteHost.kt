package ru.skywige.swds

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Телефон как управляемое устройство. Без согласия владельца подключиться нельзя: Android сам
 * спрашивает разрешение на показ экрана при каждом сеансе, а пока он идёт, висит уведомление
 * с кнопкой «Остановить». Касания работают, только если в «Спец. возможностях» включена служба SWDS.
 */
object RemoteHost {
    const val PATH = "/modules/remote_control/api/host"
    const val CH = "swds_remote"
    private const val REQUEST_ID = 7301

    fun channel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "Подключение к телефону", NotificationManager.IMPORTANCE_HIGH))
    }

    /** С другого устройства просят подключиться. */
    fun onRequest(ctx: Context, e: JSONObject) {
        val sid = e.optString("sid")
        if (sid.isEmpty() || e.optDouble("expires", 0.0) * 1000 < System.currentTimeMillis()) return
        channel(ctx)
        val who = e.optString("from", "Другое устройство")
        val open = Intent(ctx, RemoteHostActivity::class.java).putExtra("sid", sid).putExtra("from", who)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(ctx, REQUEST_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("🖥 Запрос на подключение")
            .setContentText("$who хочет подключиться к этому телефону")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setTimeoutAfter(90_000)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(REQUEST_ID, n) }
    }

    fun onEnd(ctx: Context, sid: String) {
        ctx.getSystemService(NotificationManager::class.java).cancel(REQUEST_ID)
        if (RemoteHostService.currentSid == sid) RemoteHostService.stop(ctx, false)
    }

    fun answer(ctx: Context, sid: String, allow: Boolean) {
        Api.post(ctx, "$PATH/answer", JSONObject().put("sid", sid).put("allow", allow), 8000)
    }

    fun touchEnabled(ctx: Context): Boolean {
        if (RemoteA11yService.instance != null) return true
        val want = ComponentName(ctx, RemoteA11yService::class.java).flattenToString()
        return (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').any { it.equals(want, ignoreCase = true) }
    }
}

/** Экран согласия: кто просит, управление касаниями, «Разрешить». Дальше — системный вопрос Android. */
class RemoteHostActivity : LocalPageActivity() {
    override val page = "remote_host"
    private var sid = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        sid = intent?.getStringExtra("sid") ?: ""
        super.onCreate(savedInstanceState)
        if (!Prefs(this).paired) {
            startActivity(Intent(this, PairActivity::class.java))
            finish()
        }
    }

    override fun extraState(): JSONObject = JSONObject().put("sid", sid).put("from", intent?.getStringExtra("from") ?: "")
        .put("touch", RemoteHost.touchEnabled(this)).put("running", RemoteHostService.currentSid != null)

    override fun handle(id: String, method: String, a: JSONObject): JSONObject? = when (method) {
        "allow" -> {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            main.post {
                await(RC_PROJECTION, id)
                startActivityForResult(mgr.createScreenCaptureIntent(), RC_PROJECTION)
            }
            null
        }
        "deny" -> {
            if (sid.isNotEmpty()) runCatching { RemoteHost.answer(this, sid, false) }
            main.post { finish() }
            ok()
        }
        "stop" -> { RemoteHostService.stop(this, true); ok() }
        "touch_settings" -> { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); ok() }
        "app_settings" -> {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            ok()
        }
        "status" -> ok().put("touch", RemoteHost.touchEnabled(this)).put("running", RemoteHostService.currentSid != null)
        else -> super.handle(id, method, a)
    }

    override fun onResult(id: String, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != RC_PROJECTION) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            Thread { if (sid.isNotEmpty()) runCatching { RemoteHost.answer(this, sid, false) } }.start()
            reply(id, err("Показ экрана не разрешён"))
            return
        }
        // служба должна перейти на передний план раньше, чем получит разрешение: требование Android 14
        val i = Intent(this, RemoteHostService::class.java).putExtra("sid", sid).putExtra("code", resultCode).putExtra("data", data)
        startForegroundService(i)
        reply(id, ok())
        main.postDelayed({ finish() }, 300)
    }

    companion object {
        const val RC_PROJECTION = 51
        fun intent(ctx: Context): Intent = Intent(ctx, RemoteHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}

/** Трансляция: кадры на сервер, ввод с сервера. Живёт, пока идёт сеанс; уведомление снять нельзя. */
class RemoteHostService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var sid = ""
    @Volatile private var running = false
    @Volatile private var state = "waiting"
    @Volatile private var side = 1280
    @Volatile private var quality = 60
    @Volatile private var fps = 12
    @Volatile private var viewer = ""
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == A_STOP) { shutdown(true); return START_NOT_STICKY }
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION") val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null || running) { if (!running) stopSelf(); return START_NOT_STICKY }
        RemoteHost.channel(this)
        val notification = notice("Подготовка показа экрана…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTE, notification)
        sid = intent.getStringExtra("sid") ?: ""
        try {
            val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
                ?: throw IllegalStateException("Android не выдал показ экрана")
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { shutdown(true) }
            }, main)
            projection = mp
            setupDisplay()
        } catch (e: Exception) {
            android.util.Log.w("SWDS", "remote start: $e")
            shutdown(false)
            return START_NOT_STICKY
        }
        running = true
        Thread { begin() }.start()
        return START_NOT_STICKY
    }

    private fun notice(text: String) = NotificationCompat.Builder(this, RemoteHost.CH)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle("🔴 Экран телефона показывается")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .addAction(0, "Остановить", PendingIntent.getService(this, 7303, Intent(this, RemoteHostService::class.java).setAction(A_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun updateNote(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTE, notice(text))
    }

    private fun realSize(): Pair<Int, Int> {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    private var curW = 0
    private var curH = 0
    private var realW = 0
    private var realH = 0

    private fun setupDisplay() {
        val (rw, rh) = realSize()
        val k = min(1.0, side.toDouble() / max(rw, rh))
        val w = ((rw * k).toInt() / 2) * 2
        val h = ((rh * k).toInt() / 2) * 2
        if (w == curW && h == curH && display != null) return
        val old = reader
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val dpi = resources.displayMetrics.densityDpi
        val d = display
        if (d == null) {
            display = projection?.createVirtualDisplay("swds-remote", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main)
        } else {
            d.resize(w, h, dpi)
            d.surface = r.surface
        }
        reader = r
        old?.close()
        curW = w; curH = h; realW = rw; realH = rh
    }

    private fun begin() {
        try {
            if (sid.isEmpty()) {
                sid = Api.post(this, "${RemoteHost.PATH}/offer", JSONObject(), 8000).getString("sid")
                state = "waiting"
            } else {
                RemoteHost.answer(this, sid, true)
                state = "active"
            }
            currentSid = sid
        } catch (e: Exception) {
            android.util.Log.w("SWDS", "remote begin: $e")
            main.post { android.widget.Toast.makeText(this, "Не удалось начать: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
            shutdown(false)
            return
        }
        main.post { updateNote(if (state == "active") "Подключено" else "Ждёт подключения с другого устройства") }
        Thread({ inputLoop() }, "swds-remote-in").start()
        frameLoop()
    }

    /** Снимок берётся только при изменении экрана; кадр отправляется, когда прошлый дошёл: сеть сама задаёт темп. */
    private fun frameLoop() {
        var bmp: Bitmap? = null
        var dirty = true
        var lastSent = 0L
        val out = ByteArrayOutputStream(256 * 1024)
        var errors = 0
        while (running) {
            val t0 = System.currentTimeMillis()
            try {
                val (rw, rh) = realSize()
                if (rw != realW || rh != realH) main.post { runCatching { setupDisplay() } }   // телефон повернули
                val img = runCatching { reader?.acquireLatestImage() }.getOrNull()
                if (img != null) {
                    img.use {
                        val p = it.planes[0]
                        val rowW = p.rowStride / p.pixelStride
                        var b = bmp
                        if (b == null || b.width != rowW || b.height != it.height) {
                            b?.recycle()
                            b = Bitmap.createBitmap(rowW, it.height, Bitmap.Config.ARGB_8888)
                            bmp = b
                        }
                        b!!.copyPixelsFromBuffer(p.buffer)
                        dirty = true
                    }
                }
                val b = bmp
                if (state == "active" && b != null && (dirty || t0 - lastSent > 1500)) {
                    val w = min(curW, b.width)
                    val h = min(curH, b.height)
                    val crop = if (b.width != w || b.height != h) Bitmap.createBitmap(b, 0, 0, w, h) else b
                    out.reset()
                    crop.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    if (crop !== b) crop.recycle()
                    val q = "sid=${Api.enc(sid)}&w=$realW&h=$realH&ctl=${if (RemoteA11yService.instance != null) 1 else 0}"
                    val r = Api.postBytes(this, "${RemoteHost.PATH}/frame?$q", out.toByteArray(), "image/jpeg", 5000, 15000)
                    dirty = false
                    lastSent = t0
                    errors = 0
                    onState(r.optString("state"))
                    r.optJSONObject("want")?.let { w2 ->
                        quality = w2.optInt("q", quality)
                        fps = max(1, w2.optInt("fps", fps))
                        val s = w2.optInt("side", side)
                        if (s != side) { side = s; main.post { runCatching { setupDisplay() } } }
                    }
                }
            } catch (e: ApiException) {
                if (e.status == 404 || e.status == 401) { shutdown(false); return }
                if (++errors > 30) { shutdown(false); return }
                sleep(500)
            } catch (e: Exception) {
                android.util.Log.w("SWDS", "remote frame: $e")
                sleep(300)
            }
            val pause = (if (state == "active") 1000L / fps else 300L) - (System.currentTimeMillis() - t0)
            if (pause > 0) sleep(pause)
        }
        bmp?.recycle()
    }

    private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }

    private fun onState(st: String) {
        if (st.isEmpty() || st == state) return
        val was = state
        state = st
        if (st == "closed") { shutdown(false); return }
        if (st == "active" && was != "active") main.post { updateNote("Подключено: ${viewer.ifEmpty { "другое устройство" }}") }
    }

    private fun inputLoop() {
        var errors = 0
        val input = RemoteGestures { realW to realH }
        while (running) {
            try {
                val r = Api.withServerGet(this, "${RemoteHost.PATH}/input?sid=${Api.enc(sid)}&wait=15", 8000, 25000)
                errors = 0
                viewer = r.optString("viewer", viewer)
                onState(r.optString("state"))
                val ev = r.optJSONArray("events") ?: continue
                for (i in 0 until ev.length()) ev.optJSONObject(i)?.let { runCatching { input.handle(it) } }
            } catch (e: ApiException) {
                if (e.status == 404 || e.status == 401) { shutdown(false); return }
                if (++errors > 20) { shutdown(false); return }
                sleep(1000)
            } catch (e: Exception) {
                sleep(1000)
            }
        }
    }

    private fun shutdown(tellServer: Boolean) {
        val wasSid = sid
        if (tellServer && wasSid.isNotEmpty()) {
            val app = applicationContext
            Thread { runCatching { Api.post(app, "${RemoteHost.PATH}/end", JSONObject().put("sid", wasSid), 5000) } }.start()
        }
        running = false
        currentSid = null
        main.post {
            runCatching { display?.release() }
            runCatching { projection?.stop() }
            runCatching { reader?.close() }
            display = null; projection = null; reader = null
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    companion object {
        const val A_STOP = "ru.skywige.swds.REMOTE_STOP"
        private const val NOTE = 7302
        @Volatile var currentSid: String? = null

        fun stop(ctx: Context, tellServer: Boolean) {
            if (currentSid == null && !tellServer) return
            runCatching {
                ctx.startService(Intent(ctx, RemoteHostService::class.java).setAction(A_STOP))
            }
        }
    }
}

/** Служба специальных возможностей: единственный способ передать касания другому приложению. */
class RemoteA11yService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onUnbind(intent: Intent?): Boolean { if (instance === this) instance = null; return super.onUnbind(intent) }

    /** Жест целиком; следующий нельзя начинать раньше: он отменил бы текущий. */
    fun gesture(path: Path, duration: Long, start: Long = 0): Boolean {
        val d = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, start, duration.coerceIn(1, GestureDescription.getMaxGestureDuration())))
            .build()
        val done = CountDownLatch(1)
        var ok = false
        val sent = dispatchGesture(d, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok = true; done.countDown() }
            override fun onCancelled(g: GestureDescription?) { done.countDown() }
        }, null)
        if (!sent) return false
        done.await(duration + 800, TimeUnit.MILLISECONDS)
        return ok
    }

    fun typeText(text: String) {
        val node = focused() ?: return
        val cur = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
        setText(node, cur + text)
    }

    fun key(name: String) {
        val node = focused()
        when (name) {
            "backspace" -> node?.let { val cur = if (it.isShowingHintText) "" else it.text?.toString() ?: ""; if (cur.isNotEmpty()) setText(it, cur.dropLast(1)) }
            "enter" -> node?.let {
                if (Build.VERSION.SDK_INT >= 30 && it.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return
                setText(it, (it.text?.toString() ?: "") + "\n")
            }
        }
    }

    private fun focused(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    private fun setText(node: AccessibilityNodeInfo, value: String) {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    companion object {
        @Volatile var instance: RemoteA11yService? = null
    }
}

/** События панели → жесты. Координаты приходят в долях экрана. */
class RemoteGestures(private val size: () -> Pair<Int, Int>) {
    private var path: Path? = null
    private var startedAt = 0L

    private fun px(e: JSONObject): Pair<Float, Float> {
        val (w, h) = size()
        return (e.optDouble("x", 0.5) * (w - 1)).toFloat() to (e.optDouble("y", 0.5) * (h - 1)).toFloat()
    }

    fun handle(e: JSONObject) {
        val a11y = RemoteA11yService.instance ?: return
        when (e.optString("a")) {
            "c" -> {
                val (x, y) = px(e)
                val p = Path().apply { moveTo(x, y) }
                a11y.gesture(p, if (e.optString("b") == "right") 650 else 40)     // «правая кнопка» — долгое нажатие
            }
            "d" -> {
                val (x, y) = px(e)
                path = Path().apply { moveTo(x, y) }
                startedAt = System.currentTimeMillis()
            }
            "m" -> path?.let { val (x, y) = px(e); it.lineTo(x, y) }
            "u" -> path?.let {
                val (x, y) = px(e)
                it.lineTo(x, y)
                path = null
                a11y.gesture(it, (System.currentTimeMillis() - startedAt).coerceIn(60, 4000))
            }
            "s" -> {
                val (w, h) = size()
                val (x, y) = px(e)
                val dy = (e.optDouble("dy", 0.0) / 120.0 * h * 0.1).toFloat()
                val dx = (-e.optDouble("dx", 0.0) / 120.0 * w * 0.1).toFloat()
                val p = Path().apply {
                    moveTo(x, y)
                    lineTo((x + dx).coerceIn(1f, w - 2f), (y + dy).coerceIn(1f, h - 2f))
                }
                a11y.gesture(p, 120)
            }
            "g" -> global(a11y, e.optString("g"))
            "text" -> a11y.typeText(e.optString("t"))
            "key" -> a11y.key(e.optString("keys"))
        }
    }

    private fun global(a: AccessibilityService, name: String) {
        val code = when (name) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "lock" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else return
            else -> return
        }
        a.performGlobalAction(code)
    }
}

/** Виджет подключения: «Компьютер» — сразу на экран ПК, «Мой экран» — разрешить подключиться к телефону. */
class RemoteWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(ctx, mgr, id)
    }

    companion object {
        fun renderAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            for (id in mgr.getAppWidgetIds(ComponentName(ctx, RemoteWidget::class.java))) render(ctx, mgr, id)
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val pal = Theme.widgetColors(ctx, null)
            val rv = RemoteViews(ctx.packageName, R.layout.widget_remote)
            WidgetStyle.applyBg(ctx, rv, pal, id, 250, 90)
            for (bgId in intArrayOf(R.id.r_pc_bg, R.id.r_phones_bg, R.id.r_share_bg)) {
                rv.setInt(bgId, "setColorFilter", pal.accent)
                rv.setInt(bgId, "setImageAlpha", if (pal.dark) 46 else 28)
            }
            for (v in intArrayOf(R.id.r_pc_icon, R.id.r_phones_icon, R.id.r_share_icon)) rv.setTextColor(v, pal.accent)
            for (v in intArrayOf(R.id.r_pc_label, R.id.r_phones_label, R.id.r_share_label)) rv.setTextColor(v, pal.text)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            fun page(req: Int, path: String) = PendingIntent.getActivity(ctx, req, Intent(ctx, MainActivity::class.java)
                .setAction("ru.skywige.swds.REMOTE_$req").putExtra("path", path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), flags)
            rv.setOnClickPendingIntent(R.id.r_pc, page(72000, "/modules/remote_control/window?connect=pc"))
            rv.setOnClickPendingIntent(R.id.r_phones, page(72001, "/modules/remote_control/window"))
            rv.setOnClickPendingIntent(R.id.r_share, PendingIntent.getActivity(ctx, 72002, RemoteHostActivity.intent(ctx), flags))
            mgr.updateAppWidget(id, rv)
        }
    }
}
