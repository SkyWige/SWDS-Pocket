package ru.skywige.swds

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * Маленькое окно поверх всего:
 *  • input   — ввести текст для команды (кнопка виджета «✏️ …», плитка, быстрые действия);
 *  • confirm — подтвердить команду;
 *  • ask     — ответить на вопрос SWDS (когда вариантов больше, чем помещается в уведомлении);
 *  • quick   — выбрать быстрое действие (плитка в шторке).
 */
class InputActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var pal: Palette

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pal = Theme.palette(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = GradientDrawable().apply { setColor(pal.surface); cornerRadius = dp(18).toFloat() }
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        setFinishOnTouchOutside(true)
        val spec = runCatching { JSONObject(intent.getStringExtra("spec") ?: intent.getStringExtra("prompt") ?: "{}") }.getOrDefault(JSONObject())
        when (intent.getStringExtra("mode")) {
            "confirm" -> confirm(spec)
            "ask" -> ask(spec)
            "quick" -> quick()
            else -> input(spec)
        }
    }

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(pal.text)
        setPadding(0, 0, 0, dp(10))
    }.also { root.addView(it) }

    private fun note(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(pal.muted); setPadding(0, 0, 0, dp(10))
    }.also { root.addView(it) }

    private fun button(t: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else pal.accent)
        background = GradientDrawable().apply {
            setColor(if (primary) pal.accent else (pal.accent and 0x00FFFFFF) or 0x1F000000)
            cornerRadius = dp(12).toFloat()
        }
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) }
    }.also { root.addView(it) }

    private fun field(hint: String, multi: Boolean = true): EditText = EditText(this).apply {
        this.hint = hint; textSize = 16f; setTextColor(pal.text); setHintTextColor(pal.muted)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                (if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        maxLines = 6
        imeOptions = EditorInfo.IME_ACTION_SEND
        background = GradientDrawable().apply {
            setColor((pal.text and 0x00FFFFFF) or 0x0D000000); cornerRadius = dp(12).toFloat()
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }.also { root.addView(it) }

    private fun busy(b: Button, text: String = "⏳") { b.isEnabled = false; b.text = text }

    private fun input(spec: JSONObject) {
        title(spec.optString("title").ifEmpty { "SWDS" })
        val cmd = spec.optString("command")
        if (cmd.isEmpty()) { finish(); return }
        val f = field(spec.optString("placeholder").ifEmpty { "Текст…" })
        lateinit var send: Button
        send = button("Отправить", primary = true) {
            val text = f.text.toString().trim()
            if (text.isEmpty()) return@button
            val args = JSONObject((spec.optJSONObject("args") ?: JSONObject()).toString())
            args.put(spec.optString("arg").ifEmpty { "text" }, text)
            busy(send)
            Actions.run(this, cmd, args) { ok ->
                if (ok) runOnUiThread { finish() } else runOnUiThread { send.isEnabled = true; send.text = "Отправить" }
                val wid = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                if (ok && wid >= 0) SwdsWidget.fetch(applicationContext, wid)
            }
        }
        f.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_SEND || (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) { send.performClick(); true } else false
        }
        f.requestFocus()
    }

    private fun confirm(spec: JSONObject) {
        title("Подтвердите")
        note(spec.optString("confirm"))
        lateinit var ok: Button
        ok = button("Выполнить", primary = true) {
            busy(ok)
            Actions.run(this, spec.optString("command"), spec.optJSONObject("args") ?: JSONObject(), spec.optString("toast")) {
                runOnUiThread { finish() }
                val wid = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                if (wid >= 0) SwdsWidget.fetch(applicationContext, wid)
            }
        }
        button("Отмена") { finish() }
    }

    private fun ask(p: JSONObject) {
        val pid = p.optString("id")
        title("❓ SWDS спрашивает")
        note(p.optString("question"))
        val opts = p.optJSONArray("options")
        fun answer(body: JSONObject, b: Button) {
            busy(b)
            Thread {
                val res = runCatching { Api.post(this, "/modules/mobile/api/answer", body.put("id", pid)) }
                runOnUiThread {
                    val r = res.getOrNull()
                    if (r?.optBoolean("ok") == true) {
                        Notifier.closeAsk(applicationContext, pid, "✅ Ответ отправлен")
                        finish()
                    } else {
                        Events.toast(this, "⚠ " + (r?.optString("error") ?: res.exceptionOrNull()?.message ?: "Ошибка"))
                        finish()
                    }
                }
            }.start()
        }
        if (opts != null) for (i in 0 until opts.length()) {
            lateinit var b: Button
            b = button(opts.optString(i), primary = i == 0) { answer(JSONObject().put("index", i).put("value", opts.optString(i)), b) }
        }
        if (p.optBoolean("allow_text")) {
            val f = field("Свой ответ…")
            lateinit var b: Button
            b = button("Ответить") {
                val t = f.text.toString().trim()
                if (t.isNotEmpty()) answer(JSONObject().put("value", t), b)
            }
        }
    }

    private fun quick() {
        title("⚡ Быстрые действия")
        val list = Prefs(this).quickCache()
        if (list.length() == 0) note("Список пуст. Откройте приложение, чтобы загрузить действия с сервера.")
        for (i in 0 until list.length()) {
            val q = list.optJSONObject(i) ?: continue
            button((q.optString("icon") + " " + q.optString("title").ifEmpty { q.optString("command") }).trim()) {
                val a = if (q.optString("input").isNotEmpty())
                    JSONObject().put("input", JSONObject().put("command", q.optString("command")).put("arg", q.optString("input"))
                        .put("args", q.optJSONObject("args") ?: JSONObject()).put("placeholder", q.optString("placeholder"))
                        .put("title", q.optString("title")))
                else JSONObject().put("command", q.optString("command")).put("args", q.optJSONObject("args") ?: JSONObject())
                Actions.perform(this, a)
                finish()
            }
        }
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(4)))
        root.gravity = Gravity.CENTER_HORIZONTAL
    }
}
