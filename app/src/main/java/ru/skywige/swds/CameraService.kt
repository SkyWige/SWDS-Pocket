package ru.skywige.swds

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Камера в фоне: служба переднего плана (тип «камера»). Работает с выключенным экраном и закрытым приложением —
 * телефон на зарядке остаётся камерой «Камер» SWDS, пока службу не остановят (кнопка в уведомлении или в приложении).
 *
 * Объективы: основной, фронтальный или «оба» — тогда камеры чередуются (на большинстве телефонов две камеры
 * одновременно не работают); если кто-то смотрит живое видео с одной из них — держится именно она.
 * На сервере это две камеры: «<название>» и «<название> · фронт».
 */
class CameraService : LifecycleService() {
    private val main = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var lens = "back"                  // текущий объектив
    private var mode = "back"                  // back | front | both
    private val sender = Executors.newSingleThreadExecutor()
    private val analyzerExec = Executors.newSingleThreadExecutor()
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var busy = false
    @Volatile private var nextAt = 0L
    private var switchAt = 0L

    override fun onCreate() {
        super.onCreate()
        val stop = PendingIntent.getService(this, 1, Intent(this, CameraService::class.java).setAction(A_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, CameraActivity.intent(this), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Notifier.CH_SERVICE).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("📷 Телефон работает камерой").setContentText(Prefs(this).cameraName + " → «Камеры» SWDS")
            .setOngoing(true).setContentIntent(open).addAction(0, "■ Остановить", stop).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(ID, n)
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "swds:camera").apply {
            setReferenceCounted(false); acquire()
        }
        running = true
        instance = this
        status = "Запускаю камеру…"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == A_STOP) { stopSelf(); return START_NOT_STICKY }
        mode = Prefs(this).cameraLens
        lens = if (mode == "front") "front" else "back"
        main.post { start() }
        return START_STICKY
    }

    private fun start() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            provider = runCatching { f.get() }.getOrNull()
            if (provider == null) { status = "⚠ Камера недоступна"; return@addListener }
            bind()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hasLens(back: Boolean): Boolean = runCatching {
        provider?.hasCamera(if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA) == true
    }.getOrDefault(false)

    @SuppressLint("UnsafeOptInUsageError")
    private fun bind() {
        val pr = provider ?: return
        if (lens == "front" && !hasLens(false)) lens = "back"
        if (lens == "back" && !hasLens(true)) lens = "front"
        val sel = if (lens == "back") CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val res = ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
        val an = ImageAnalysis.Builder().setResolutionSelector(res).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
        val myLens = lens
        an.setAnalyzer(analyzerExec) { img ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (running && !busy && now >= nextAt) {
                    busy = true
                    val jpeg = encode(img.toBitmap(), img.imageInfo.rotationDegrees)
                    sender.execute { send(jpeg, myLens) }
                }
            } catch (e: Exception) {
                busy = false
            } finally { img.close() }
        }
        try {
            pr.unbindAll()
            camera = pr.bindToLifecycle(this, sel, an)
            camera?.cameraControl?.enableTorch(torch && lens == "back")
            switchAt = SystemClock.elapsedRealtime() + SWITCH_MS
            status = "📷 " + (if (lens == "back") "основная камера" else "фронтальная камера")
        } catch (e: Exception) {
            status = "⚠ " + (e.message ?: "камера не запустилась")
        }
    }

    private fun encode(src: Bitmap, rotation: Int): ByteArray {
        var bmp = src
        val w = width
        val m = Matrix()
        val longSide = maxOf(bmp.width, bmp.height)
        if (longSide > w) { val k = w.toFloat() / longSide; m.postScale(k, k) }
        if (rotation != 0) m.postRotate(rotation.toFloat())
        if (!m.isIdentity) bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (bmp !== src) bmp.recycle()
        return out.toByteArray()
    }

    private fun battery(): Pair<Int, Boolean> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return Pair(-1, false)
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return Pair(if (lvl >= 0) lvl * 100 / sc else -1, i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
    }

    private fun send(jpeg: ByteArray, which: String) {
        val p = Prefs(this)
        var watching = false
        try {
            val (bat, chg) = battery()
            val key = if (which == "front" && mode == "both") p.cameraKey + "f" else p.cameraKey
            val name = if (which == "front" && mode == "both") p.cameraName + " · фронт" else p.cameraName
            val q = "?key=" + Api.enc(key) + "&name=" + Api.enc(name) + "&battery=$bat&charging=${if (chg) 1 else 0}" +
                "&lens=$which&torch=${if (torch) 1 else 0}&model=" + Api.enc(Build.MODEL)
            val r = Api.postBytes(this, "/modules/cameras/api/ingest$q", jpeg, "image/jpeg", 6000, 12000)
            intervalMs = r.optLong("interval_ms", 1000L).coerceIn(150L, 60_000L)
            quality = r.optInt("quality", 70).coerceIn(30, 95)
            width = r.optInt("width", 960).coerceIn(320, 1920)
            watching = r.optBoolean("watching")
            armed = r.optBoolean("armed"); recording = r.optBoolean("recording")
            if (r.has("torch")) main.post { torch = r.optBoolean("torch"); runCatching { camera?.cameraControl?.enableTorch(torch && lens == "back") } }
            if (r.has("lens") && mode != "both") {
                val want = if (r.optString("lens") == "front") "front" else "back"
                if (want != lens) main.post { lens = want; p.cameraBack = want == "back"; p.cameraLens = want; mode = want; bind() }
            }
            lastJpeg = jpeg
            sent++; fails = 0; lastErr = ""
        } catch (e: Exception) {
            fails++
            lastErr = e.message ?: "нет связи"
            intervalMs = minOf(30_000L, 2000L * fails)
        } finally {
            nextAt = SystemClock.elapsedRealtime() + intervalMs
            busy = false
            // «оба объектива»: чередуем, но если эту камеру сейчас смотрят — не переключаемся
            if (mode == "both" && !watching && !recording && SystemClock.elapsedRealtime() >= switchAt) {
                main.post { lens = if (lens == "back") "front" else "back"; nextAt = 0L; bind() }
            }
        }
    }

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        status = "Остановлена"
        runCatching { provider?.unbindAll() }
        sender.shutdown(); analyzerExec.shutdown()
        runCatching { wake?.release() }
        super.onDestroy()
    }

    companion object {
        private const val ID = 4400
        private const val A_STOP = "ru.skywige.swds.CAMERA_STOP"
        private const val SWITCH_MS = 12_000L
        @Volatile var running = false
        @Volatile var status = ""
        @Volatile var sent = 0
        @Volatile var fails = 0
        @Volatile var lastErr = ""
        @Volatile var intervalMs = 1000L
        @Volatile var quality = 70
        @Volatile var width = 960
        @Volatile var torch = false
        @Volatile var armed = false
        @Volatile var recording = false
        @Volatile var lastJpeg: ByteArray? = null

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, CameraService::class.java))
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, CameraService::class.java)) }
        @Volatile private var instance: CameraService? = null
        /** Сервер «будит» камеру (открыли живое видео, тревога) — следующий кадр сразу. */
        fun wake() { instance?.nextAt = 0L }
    }
}
