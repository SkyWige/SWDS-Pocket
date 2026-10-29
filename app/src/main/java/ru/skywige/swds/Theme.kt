package ru.skywige.swds

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import org.json.JSONObject

/** Цвета активной темы SWDS (тема приходит с сервера: /hello и события theme). */
data class Palette(
    val bg: Int, val surface: Int, val text: Int, val muted: Int, val accent: Int, val topbar: Int, val dark: Boolean
)

object Theme {
    private val LIGHT = Palette(0xFFF1F4F9.toInt(), Color.WHITE, 0xFF0F172A.toInt(), 0xFF64748B.toInt(),
        0xFF2563EB.toInt(), 0xFF0F172A.toInt(), false)
    private val DARK = Palette(0xFF0B1020.toInt(), 0xFF151B2E.toInt(), 0xFFE5E9F5.toInt(), 0xFF94A3B8.toInt(),
        0xFF60A5FA.toInt(), 0xFF070B1A.toInt(), true)

    fun systemNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun color(s: String?, def: Int): Int {
        if (s.isNullOrBlank()) return def
        return try {
            var v = s.trim()
            if (v.length == 4 && v.startsWith("#")) v = "#" + v[1] + v[1] + v[2] + v[2] + v[3] + v[3]
            Color.parseColor(v)
        } catch (e: Exception) { def }
    }

    /** Токены темы, которые нужно применить сейчас (с учётом режима «как в системе»). */
    fun tokens(ctx: Context): JSONObject? {
        val t = Prefs(ctx).theme
        val mode = t.optString("mode", "fixed")
        val key = if (mode == "system" && systemNight(ctx)) "dark" else "light"
        return t.optJSONObject(key) ?: t.optJSONObject("light")
    }

    fun palette(ctx: Context): Palette {
        val tok = tokens(ctx)
        val night = systemNight(ctx)
        if (tok == null) return if (night) DARK else LIGHT
        val dark = tok.optString("mode") == "dark"
        val base = if (dark) DARK else LIGHT
        return Palette(
            bg = color(tok.optString("bg"), base.bg),
            surface = color(tok.optString("surface"), base.surface),
            text = color(tok.optString("text"), base.text),
            muted = color(tok.optString("muted"), base.muted),
            accent = color(tok.optString("accent"), base.accent),
            topbar = color(tok.optString("topbar"), base.topbar),
            dark = dark
        )
    }

    /** Для локальных страниц (assets): CSS-переменные. */
    fun cssVars(ctx: Context): JSONObject {
        val p = palette(ctx)
        fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)
        return JSONObject().put("bg", hex(p.bg)).put("surface", hex(p.surface)).put("text", hex(p.text))
            .put("muted", hex(p.muted)).put("accent", hex(p.accent)).put("topbar", hex(p.topbar)).put("dark", p.dark)
    }

    /** Цвета виджета из ответа сервера (colors: {mode, light, dark}). */
    fun widgetColors(ctx: Context, colors: JSONObject?): Palette {
        val base = palette(ctx)
        if (colors == null) return base
        val night = systemNight(ctx)
        val key = if (colors.optString("mode") == "system" && night) "dark" else "light"
        val c = colors.optJSONObject(key) ?: return base
        val dark = c.optBoolean("dark", false)
        return Palette(color(c.optString("bg"), base.surface), color(c.optString("bg"), base.surface),
            color(c.optString("text"), base.text), color(c.optString("muted"), base.muted),
            color(c.optString("accent"), base.accent), base.topbar, dark)
    }

    fun isLight(color: Int): Boolean {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        return (0.299 * r + 0.587 * g + 0.114 * b) > 160
    }
}
