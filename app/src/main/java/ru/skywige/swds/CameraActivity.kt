package ru.skywige.swds

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Пульт режима камеры. Снимает и отправляет кадры служба CameraService — она работает и при выключенном экране,
 * и после закрытия этого окна. Здесь: запуск/остановка, выбор объектива (основной, фронтальный, оба по очереди),
 * фонарик, затемнение и последний отправленный кадр вместо живого превью.
 */
class CameraActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var frame: ImageView
    private lateinit var shade: View
    private lateinit var startBtn: TextView
    private lateinit var lensBtn: TextView
    private var shownSent = -1

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startService() else Events.toast(this, "Без доступа к камере режим не работает")
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        buildUi()
        if (!Prefs(this).paired) { status.text = "Сначала привяжите телефон к SWDS"; return }
        if (!CameraService.running) ensureAndStart()
    }

    override fun onResume() { super.onResume(); main.post(tick) }
    override fun onPause() { main.removeCallbacks(tick); super.onPause() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0x55FFFFFF) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
    }

    private fun lensLabel(l: String) = when (l) { "front" -> "🤳 Фронт"; "both" -> "🔁 Обе"; else -> "📷 Основная" }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        frame = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x88000000.toInt()) }
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(28), dp(12), 0) })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        startBtn = button("▶ Начать") { if (CameraService.running) { CameraService.stop(this); refresh() } else ensureAndStart() }
        lensBtn = button(lensLabel(Prefs(this).cameraLens)) { cycleLens() }
        row1.addView(startBtn); row1.addView(lensBtn); row1.addView(button("🔦") { toggleTorch() })
        row2.addView(button("🌙 Затемнить") { dim(true) }); row2.addView(button("✕ Закрыть окно") { finish() })
        bar.addView(row1); bar.addView(row2)
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(8), 0, dp(8), dp(24)) })
        shade = View(this).apply {
            setBackgroundColor(Color.BLACK); visibility = View.GONE
            setOnClickListener { dim(false); main.postDelayed({ if (!isFinishing) dim(true) }, 15000) }
        }
        root.addView(shade, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun ensureAndStart() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startService()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startService() {
        CameraService.start(this)
        Events.toast(this, "📷 Камера работает и с выключенным экраном. Остановить — в уведомлении")
        refresh()
    }

    private fun cycleLens() {
        val p = Prefs(this)
        val next = when (p.cameraLens) { "back" -> "front"; "front" -> "both"; else -> "back" }
        p.cameraLens = next
        if (next != "both") p.cameraBack = next == "back"
        lensBtn.text = lensLabel(next)
        if (CameraService.running) CameraService.start(this)      // служба перечитает объектив
    }

    private fun toggleTorch() {
        CameraService.torch = !CameraService.torch
        if (CameraService.running) CameraService.start(this)
    }

    private fun dim(on: Boolean) {
        shade.visibility = if (on) View.VISIBLE else View.GONE
        window.attributes = window.attributes.apply { screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    private fun refresh() {
        val p = Prefs(this)
        startBtn.text = if (CameraService.running) "■ Остановить" else "▶ Начать"
        val iv = CameraService.intervalMs
        val rate = if (iv in 1..1000) "%.0f к/с".format(1000.0 / iv) else "кадр раз в ${"%.1f".format(iv / 1000.0)} с"
        status.text = when {
            !CameraService.running -> "📷 ${p.cameraName}: остановлена\nНажмите «Начать» — камера будет работать и при выключенном экране"
            CameraService.fails > 0 -> "⚠ нет связи с SWDS (${CameraService.lastErr}) — повтор через ${iv / 1000} с"
            else -> "${CameraService.status} · ${p.cameraName} · $rate" + (if (CameraService.armed) " · 🔒 охрана" else "") +
                (if (CameraService.recording) " · ⏺ запись" else "") + "\nотправлено кадров: ${CameraService.sent}"
        }
        val j = CameraService.lastJpeg
        if (j != null && CameraService.sent != shownSent) {
            shownSent = CameraService.sent
            runCatching { frame.setImageBitmap(BitmapFactory.decodeByteArray(j, 0, j.size)) }
        }
    }

    companion object {
        fun intent(ctx: Context): Intent = Intent(ctx, CameraActivity::class.java)
        fun wakeUp() = CameraService.wake()
    }
}
