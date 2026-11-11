package ru.skywige.swds

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Matrix
import android.os.Build
import android.widget.RemoteViews

/**
 * Современный вид виджетов на RemoteViews: фон рисуется картинкой (градиент поверхности, мягкое свечение акцента
 * в углу, тонкая светлая кромка, системный радиус скругления Android 12+), кольца прогресса и плитки — тоже картинками,
 * поэтому выглядят одинаково на всех версиях Android и в любой теме SWDS.
 */
object WidgetStyle {
    private fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density

    fun alpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
        )
    }

    /** Радиус как у системных виджетов (Android 12+: system_app_widget_background_radius), иначе 24dp. */
    fun radius(ctx: Context): Float {
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { return ctx.resources.getDimension(android.R.dimen.system_app_widget_background_radius) }
        }
        return dp(ctx, 24f)
    }

    /** Размер виджета в dp (ширина, высота) из параметров, которые сообщает домашний экран. */
    fun sizeDp(ctx: Context, id: Int, defW: Int = 250, defH: Int = 180): Pair<Int, Int> {
        return runCatching {
            val o = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id)
            val portrait = ctx.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val w = if (portrait) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            val h = if (portrait) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            Pair(if (w > 40) w else defW, if (h > 40) h else defH)
        }.getOrDefault(Pair(defW, defH))
    }

    /** Фон виджета. Картинка в ~0.75 плотности экрана — чтобы не упереться в лимит памяти RemoteViews. */
    fun background(ctx: Context, pal: Palette, id: Int, defW: Int = 250, defH: Int = 180, glow: Boolean = true): Bitmap {
        val (wDp, hDp) = sizeDp(ctx, id, defW, defH)
        val scale = (ctx.resources.displayMetrics.density * 0.75f).coerceIn(1f, 2.4f)
        val w = (wDp * scale).toInt().coerceIn(64, 1400)
        val h = (hDp * scale).toInt().coerceIn(64, 1400)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = radius(ctx) / ctx.resources.displayMetrics.density * scale
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // поверхность: лёгкий вертикальный градиент
        val top = if (pal.dark) mix(pal.surface, Color.WHITE, 0.05f) else mix(pal.surface, Color.WHITE, 0.6f)
        val bottom = if (pal.dark) mix(pal.surface, Color.BLACK, 0.12f) else mix(pal.surface, pal.accent, 0.04f)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), alpha(top, 246), alpha(bottom, 246), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, p)
        // свечение акцента в правом верхнем углу
        if (glow) {
            val save = c.save()
            val path = android.graphics.Path().apply { addRoundRect(rect, r, r, android.graphics.Path.Direction.CW) }
            c.clipPath(path)
            p.shader = RadialGradient(w * 0.92f, h * 0.0f, maxOf(w, h) * 0.75f,
                intArrayOf(alpha(pal.accent, if (pal.dark) 70 else 40), alpha(pal.accent, 0)), null, Shader.TileMode.CLAMP)
            c.drawRect(rect, p)
            p.shader = RadialGradient(w * 0.0f, h * 1.05f, maxOf(w, h) * 0.6f,
                intArrayOf(alpha(mix(pal.accent, Color.CYAN, 0.35f), if (pal.dark) 34 else 20), 0), null, Shader.TileMode.CLAMP)
            c.drawRect(rect, p)
            c.restoreToCount(save)
        }
        // кромка: светлая сверху, растворяется книзу
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            if (pal.dark) alpha(Color.WHITE, 46) else alpha(Color.BLACK, 18),
            if (pal.dark) alpha(Color.WHITE, 10) else alpha(Color.BLACK, 10), Shader.TileMode.CLAMP)
        p.style = Paint.Style.STROKE
        p.strokeWidth = scale
        c.drawRoundRect(RectF(scale / 2, scale / 2, w - scale / 2, h - scale / 2), r, r, p)
        return bmp
    }

    /** Подложка-плитка (кнопка, чип значка): скруглённый прямоугольник цвета color с прозрачностью и бликом. */
    fun tile(ctx: Context, color: Int, wDp: Float, hDp: Float, radiusDp: Float, a: Int, gloss: Boolean = true): Bitmap {
        val s = ctx.resources.displayMetrics.density.coerceIn(1f, 3f)
        val w = (wDp * s).toInt().coerceAtLeast(8)
        val h = (hDp * s).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val rr = minOf(radiusDp * s, minOf(w, h) / 2f)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), alpha(mix(color, Color.WHITE, 0.12f), a), alpha(color, (a * 0.8f).toInt()), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, rr, rr, p)
        if (gloss) {
            p.shader = LinearGradient(0f, 0f, 0f, h * 0.6f, alpha(Color.WHITE, 40), alpha(Color.WHITE, 0), Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, rr, rr, p)
        }
        return bmp
    }

    /** Кольцо прогресса со скруглёнными концами и градиентом. */
    fun ring(ctx: Context, value: Float, color: Int, track: Int, sizeDp: Float, strokeDp: Float, color2: Int? = null): Bitmap {
        val s = ctx.resources.displayMetrics.density.coerceIn(1f, 3f)
        val n = (sizeDp * s).toInt()
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val sw = strokeDp * s
        val rect = RectF(sw / 2 + 1, sw / 2 + 1, n - sw / 2 - 1, n - sw / 2 - 1)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND }
        p.color = track
        c.drawArc(rect, 0f, 360f, false, p)
        val v = value.coerceIn(0f, 1f)
        if (v > 0.002f) {
            val sh = SweepGradient(n / 2f, n / 2f, intArrayOf(alpha(color2 ?: mix(color, Color.WHITE, 0.35f), 255), color, color), floatArrayOf(0f, v.coerceAtLeast(0.05f), 1f))
            val m = Matrix().apply { setRotate(-90f, n / 2f, n / 2f) }
            sh.setLocalMatrix(m)
            p.shader = sh
            c.drawArc(rect, -90f, 360f * v, false, p)
            p.shader = null
        }
        return bmp
    }

    /** Применить фон к любому виджету с ImageView R.id.bg. */
    fun applyBg(ctx: Context, rv: RemoteViews, pal: Palette, id: Int, defW: Int, defH: Int, glow: Boolean = true) {
        runCatching {
            rv.setImageViewBitmap(R.id.bg, background(ctx, pal, id, defW, defH, glow))
            rv.setInt(R.id.bg, "setImageAlpha", 255)
        }.onFailure {
            rv.setInt(R.id.bg, "setColorFilter", pal.surface)
            rv.setInt(R.id.bg, "setImageAlpha", 245)
        }
    }
}
