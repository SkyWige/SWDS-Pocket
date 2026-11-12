package ru.skywige.swds

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Режим камеры: старый телефон на зарядке → камера для модуля «Камеры» SWDS.
 *
 * Кадры JPEG уходят на /modules/cameras/api/ingest. Как часто — решает сервер (ответ interval_ms):
 * кто-то смотрит — до 5 кадров/с, охрана — 1–2, в покое — раз в несколько секунд. Сервер же может
 * попросить включить фонарик или сменить камеру. Экран можно затемнить — съёмка продолжается.
 */
class CameraActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var shade: View
    private lateinit var bar: LinearLayout
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private val sender = Executors.newSingleThreadExecutor()
    private val analyzer = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false
    @Volatile private var nextAt = 0L
    @Volatile private var intervalMs = 1000L
    @Volatile private var quality = 70
    @Volatile private var width = 960
    @Volatile private var torch = false
    @Volatile private var running = true
    private var sent = 0
    private var fails = 0
    private var lastErr = ""
    private var watching = false
    private var armed = false
    private var recording = false
    private var dimmed = false

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else { status.text = "Нужен доступ к камере"; Events.toast(this, "Без доступа к камере режим не работает") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        buildUi()
        if (!Prefs(this).paired) { status.text = "Сначала привяжите телефон к SWDS"; return }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0x55FFFFFF) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x88000000.toInt()) }
            text = "📷 Запускаю камеру…"
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(28), dp(12), 0) })
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(button("🌙 Затемнить") { dim(true) })
            addView(button("🔄") { switchLens() })
            addView(button("🔦") { setTorch(!torch) })
            addView(button("■ Стоп") { finish() })
        }
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(8), 0, dp(8), dp(24)) })
        shade = View(this).apply {
            setBackgroundColor(Color.BLACK); visibility = View.GONE
            setOnClickListener { dim(false); main.postDelayed({ if (!isFinishing) dim(true) }, 15000) }
        }
        root.addView(shade, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    /** Затемнение: чёрный экран и минимальная яркость — телефон меньше греется, съёмка идёт. Нажатие — показать на 15 с. */
    private fun dim(on: Boolean) {
        dimmed = on
        shade.visibility = if (on) View.VISIBLE else View.GONE
        window.attributes = window.attributes.apply { screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            provider = runCatching { f.get() }.getOrNull()
            if (provider == null) { status.text = "⚠ Камера недоступна"; return@addListener }
            bind()
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bind() {
        val pr = provider ?: return
        val back = Prefs(this).cameraBack
        val sel = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val res = ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
        val pv = Preview.Builder().setResolutionSelector(res).build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        val an = ImageAnalysis.Builder().setResolutionSelector(res)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
        an.setAnalyzer(analyzer) { img ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (running && !busy && now >= nextAt) {
                    busy = true
                    val jpeg = encode(img.toBitmap(), img.imageInfo.rotationDegrees)
                    sender.execute { send(jpeg) }
                }
            } catch (e: Exception) {
                busy = false
                lastErr = e.message ?: "кадр"
            } finally { img.close() }
        }
        try {
            pr.unbindAll()
            camera = pr.bindToLifecycle(this, sel, pv, an)
            camera?.cameraControl?.enableTorch(torch)
        } catch (e: Exception) {
            if (!back) { Prefs(this).cameraBack = true; bind(); return }
            status.text = "⚠ " + (e.message ?: "камера не запустилась")
        }
    }

    private fun encode(src: Bitmap, rotation: Int): ByteArray {
        var bmp = src
        val w = width
        if (rotation != 0 || bmp.width > w && bmp.height > w) {
            val m = Matrix()
            val longSide = maxOf(bmp.width, bmp.height)
            if (longSide > w) { val k = w.toFloat() / longSide; m.postScale(k, k) }
            if (rotation != 0) m.postRotate(rotation.toFloat())
            bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (bmp !== src) bmp.recycle()
        return out.toByteArray()
    }

    private fun battery(): Pair<Int, Boolean> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return Pair(-1, false)
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return Pair(if (lvl >= 0) lvl * 100 / sc else -1, plugged)
    }

    private fun send(jpeg: ByteArray) {
        val p = Prefs(this)
        try {
            val (bat, chg) = battery()
            val q = "?key=" + Api.enc(p.cameraKey) + "&name=" + Api.enc(p.cameraName) + "&battery=$bat&charging=${if (chg) 1 else 0}" +
                "&lens=" + (if (p.cameraBack) "back" else "front") + "&torch=" + (if (torch) 1 else 0) + "&model=" + Api.enc(Build.MODEL)
            val r = Api.postBytes(this, "/modules/cameras/api/ingest$q", jpeg, "image/jpeg", 6000, 12000)
            intervalMs = r.optLong("interval_ms", 1000L).coerceIn(150L, 60_000L)
            quality = r.optInt("quality", 70).coerceIn(30, 95)
            width = r.optInt("width", 960).coerceIn(320, 1920)
            watching = r.optBoolean("watching"); armed = r.optBoolean("armed"); recording = r.optBoolean("recording")
            if (r.has("torch")) main.post { setTorch(r.optBoolean("torch")) }
            if (r.has("lens")) {
                val wantBack = r.optString("lens") != "front"
                if (wantBack != p.cameraBack) main.post { p.cameraBack = wantBack; bind() }
            }
            if (r.optString("name").isNotEmpty() && r.optString("name") != p.cameraName) p.cameraName = r.optString("name")
            sent++; fails = 0; lastErr = ""
        } catch (e: Exception) {
            fails++
            lastErr = e.message ?: "нет связи"
            intervalMs = minOf(30_000L, 2000L * fails)
        } finally {
            nextAt = SystemClock.elapsedRealtime() + intervalMs
            busy = false
            main.post { updateStatus() }
        }
    }

    private fun updateStatus() {
        if (isFinishing) return
        val p = Prefs(this)
        val fps = if (intervalMs > 0) 1000.0 / intervalMs else 0.0
        val rate = if (fps >= 1) "%.0f к/с".format(fps) else "кадр раз в ${(intervalMs / 1000.0).let { if (it >= 10) "%.0f".format(it) else "%.1f".format(it) }} с"
        status.text = if (fails > 0) "⚠ ${p.cameraName}: нет связи с SWDS ($lastErr) — повтор через ${intervalMs / 1000} с"
        else "📷 ${p.cameraName} · $rate" + (if (watching) " · 👀 смотрят" else "") + (if (armed) " · 🔒 охрана" else "") +
            (if (recording) " · ⏺ запись" else "") + "\nотправлено кадров: $sent"
    }

    private fun setTorch(on: Boolean) {
        torch = on
        runCatching { camera?.cameraControl?.enableTorch(on) }
    }

    private fun switchLens() {
        val p = Prefs(this)
        p.cameraBack = !p.cameraBack
        bind()
    }

    /** Сервер «будит» камеру (кто-то открыл живое видео / тревога) — следующий кадр сразу. */
    fun wake() { nextAt = 0L }

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        runCatching { provider?.unbindAll() }
        sender.shutdown(); analyzer.shutdown()
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: CameraActivity? = null
        fun intent(ctx: Context): Intent = Intent(ctx, CameraActivity::class.java)
        fun wakeUp() { instance?.wake() }
    }
}
