package ru.skywige.swds

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject

/** Обработка событий от сервера (поток SSE и /pending). */
object Events {
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    var onTheme: (() -> Unit)? = null

    /** Забрать пропущенное (когда поток не работал). */
    fun catchUp(ctx: Context) {
        val p = Prefs(ctx)
        if (!p.paired) return
        if (p.lastQueueId == 0L) {
            p.lastQueueId = Api.get(ctx, "/modules/mobile/api/hello").optLong("last_id", 0)
            return
        }
        val r = Api.get(ctx, "/modules/mobile/api/pending?after=${p.lastQueueId}")
        val items = r.optJSONArray("items") ?: return
        for (i in 0 until items.length()) items.optJSONObject(i)?.let { handle(ctx, it) }
    }

    fun handle(ctx: Context, e: JSONObject) {
        val id = e.optLong("id", 0)
        val p = Prefs(ctx)
        synchronized(lock) {
            if (id > 0) {
                if (id <= p.lastQueueId) return
                p.lastQueueId = id
            }
        }
        val app = ctx.applicationContext
        try {
            when (e.optString("kind")) {
                "hello" -> {
                    val last = e.optLong("last_id", 0)
                    if (p.lastQueueId == 0L && last > 0) p.lastQueueId = last   // первое подключение — старое не показываем
                }
                "notification" -> {
                    // слишком старые (телефон был выключен сутки) показываем тихо
                    val age = System.currentTimeMillis() / 1000.0 - e.optDouble("created", 0.0)
                    if (age > 6 * 3600) e.put("silent", true)
                    Notifier.show(app, e)
                    SwdsWidget.refreshKind(app, "notifications")
                }
                "ask" -> e.optJSONObject("prompt")?.let { Notifier.ask(app, it) }
                "close" -> {
                    val st = e.optString("status")
                    Notifier.closeAsk(app, e.optString("pid"),
                        if (st == "answered") "✅ Ответ: ${e.optString("answer")} (${e.optString("by")})" else null)
                }
                "clipboard" -> main.post {
                    val cm = app.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("SWDS", e.optString("text")))
                    val t = e.optString("text")
                    Notifier.simple(app, 5, "📋 Скопировано с компьютера", if (t.length > 80) t.take(80) + "…" else t,
                        timeoutMs = 15000)
                }
                "open" -> {
                    val url = e.optString("url")
                    if (MainActivity.visible && url.startsWith("/")) {
                        app.startActivity(Intent(app, MainActivity::class.java).putExtra("path", url)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    } else if (MainActivity.visible) {
                        runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    } else {
                        Notifier.simple(app, 6, "🌐 Открыть с компьютера", url, Notifier.CH_WARN,
                            Notifier.openIntent(app, url, 6))
                    }
                }
                "ring" -> Ring.start(app, e.optString("text", "Телефон ищут через SWDS"))
                "outbox" -> if (p.receiveFiles) SyncWorker.now(app, "outbox")
                "widgets" -> SwdsWidget.refreshAll(app)
                "theme" -> {
                    e.optJSONObject("theme")?.let { p.theme = it }
                    SwdsWidget.refreshAll(app)
                    ButtonWidget.renderAll(app)
                    main.post { onTheme?.invoke() }
                }
                "sync" -> SyncWorker.now(app, "all")
                "media" -> MediaHub.onRemote(app, e)
                "unpaired" -> {
                    p.unpair()
                    PushService.stop(app)
                    Notifier.simple(app, 7, "SWDS", "Телефон отвязан от сервера. Откройте приложение, чтобы привязать заново.")
                }
            }
        } catch (ex: Exception) {
            android.util.Log.w("SWDS", "event ${e.optString("kind")}: $ex")
        }
    }

    fun toast(ctx: Context, text: String) = main.post { Toast.makeText(ctx.applicationContext, text, Toast.LENGTH_SHORT).show() }
}
