package ru.skywige.swds

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.RemoteInput
import org.json.JSONObject

/** Выполнение команд SWDS в фоне с коротким ответом. */
object Actions {
    fun resultText(r: JSONObject): String {
        r.optString("message").takeIf { it.isNotEmpty() }?.let { return it }
        r.optString("headline").takeIf { it.isNotEmpty() }?.let { return it }
        r.optString("text").takeIf { it.isNotEmpty() }?.let { return it }
        return "✅ Готово"
    }

    /** Выполнить команду; onDone вызывается в фоновом потоке. */
    fun run(ctx: Context, command: String, args: JSONObject, okToast: String? = null, onDone: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread {
            var ok = false
            try {
                val r = Api.execute(app, command, args)
                ok = true
                Events.toast(app, okToast?.takeIf { it.isNotEmpty() } ?: resultText(r).take(200))
            } catch (e: Exception) {
                Events.toast(app, "⚠ " + (e.message ?: "Ошибка"))
            }
            onDone?.invoke(ok)
        }.start()
    }

    /** Действие строки виджета / быстрой кнопки: {command,args,confirm,toast} | {url} | {open} | {input} | {sync} | {pick} */
    fun perform(act: Activity, a: JSONObject, widgetId: Int = -1) {
        val ctx = act.applicationContext
        when {
            a.has("input") -> act.startActivity(Intent(act, InputActivity::class.java)
                .putExtra("mode", "input").putExtra("spec", a.getJSONObject("input").toString())
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            a.optString("confirm").isNotEmpty() -> act.startActivity(Intent(act, InputActivity::class.java)
                .putExtra("mode", "confirm").putExtra("spec", a.toString())
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            a.optString("command").isNotEmpty() -> {
                Events.toast(ctx, "⏳ " + a.optString("command"))
                run(ctx, a.optString("command"), a.optJSONObject("args") ?: JSONObject(), a.optString("toast")) {
                    if (widgetId >= 0) SwdsWidget.fetch(ctx, widgetId)
                }
            }
            a.has("open") || a.optString("url").startsWith("/") -> act.startActivity(Intent(act, MainActivity::class.java)
                .putExtra("path", a.optString("open").ifEmpty { a.optString("url") })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            a.optString("url").isNotEmpty() -> runCatching {
                act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.optString("url"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            a.optBoolean("sync") -> { Events.toast(ctx, a.optString("toast", "Синхронизация…")); SyncWorker.now(ctx, "all") }
            a.optBoolean("pick") -> act.startActivity(Intent(act, ShareActivity::class.java).setAction(ShareActivity.A_PICK)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Нажатия в уведомлениях: ответ на вопрос, команда-кнопка, «остановить звонок». */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        when (intent.action) {
            A_RING_STOP -> Ring.stop(app)
            A_ANSWER -> {
                val pid = intent.getStringExtra("pid") ?: return
                val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()
                val body = JSONObject().put("id", pid)
                if (reply != null) body.put("value", reply)
                else body.put("index", intent.getIntExtra("index", 0)).put("value", intent.getStringExtra("value"))
                val pending = goAsync()
                Thread {
                    try {
                        val r = Api.post(app, "/modules/mobile/api/answer", body)
                        Notifier.closeAsk(app, pid, if (r.optBoolean("ok")) "✅ Ответ отправлен: ${reply ?: intent.getStringExtra("value")}"
                        else "⚠ " + r.optString("error", "Не удалось ответить"))
                    } catch (e: Exception) {
                        Notifier.closeAsk(app, pid, "⚠ Нет связи с SWDS: ответ не отправлен")
                    } finally { pending.finish() }
                }.start()
            }
            A_NOTIF_CMD -> {
                val cmd = intent.getStringExtra("command") ?: return
                val args = runCatching { JSONObject(intent.getStringExtra("args") ?: "{}") }.getOrDefault(JSONObject())
                Notifier.cancel(app, intent.getIntExtra("notif_id", 0))
                val pending = goAsync()
                Actions.run(app, cmd, args) { pending.finish() }
            }
        }
    }

    companion object {
        const val A_ANSWER = "ru.skywige.swds.ANSWER"
        const val A_NOTIF_CMD = "ru.skywige.swds.NOTIF_CMD"
        const val A_RING_STOP = "ru.skywige.swds.RING_STOP"
    }
}

/** Невидимая «прокладка» для нажатий в виджете (Android не даёт запускать окна из фона). */
class RouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val a = intent.getStringExtra("row_action")?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (a != null) Actions.perform(this, a, widgetId)
        else if (widgetId >= 0) {
            val data = Prefs(this).widgetCache(widgetId)?.let { runCatching { JSONObject(it) }.getOrNull() }
            startActivity(Intent(this, MainActivity::class.java).putExtra("path", data?.optString("open")?.ifEmpty { null } ?: "/")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val A_WIDGET_ROW = "ru.skywige.swds.WIDGET_ROW"
    }
}
