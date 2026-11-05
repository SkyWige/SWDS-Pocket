package ru.skywige.swds

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Быстрая запись с виджета: заметка или трата. Страница локальная и открывается мгновенно,
 * связь не нужна: нет сервера — запись ждёт в очереди и уходит сама. Повторная отправка не создаст
 * дубль — сервер отсекает её по client_id.
 */
class QuickActivity : LocalPageActivity() {
    override val page = "quick"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs(this).paired) {
            startActivity(Intent(this, PairActivity::class.java))
            finish()
        }
    }

    override fun extraState(): JSONObject {
        val p = Prefs(this)
        return JSONObject().put("mode", intent?.getStringExtra(EXTRA_MODE) ?: "note")
            .put("dest", p.quickDest).put("meta", p.quickMeta).put("queued", OfflineQueue.count(this))
    }

    override fun handle(id: String, method: String, a: JSONObject): JSONObject? = when (method) {
        "meta" -> {
            val p = Prefs(this)
            try {
                val m = Api.get(this, "/modules/mobile/api/quick/meta", 5000)
                p.quickMeta = m
                ok().put("meta", m)
            } catch (e: Exception) {
                ok().put("meta", p.quickMeta).put("cached", true)      // без связи — что запомнили в прошлый раз
            }
        }
        "set_dest" -> { Prefs(this).quickDest = a.optString("dest", "inbox"); ok() }
        "submit" -> submit(a)
        else -> super.handle(id, method, a)
    }

    private fun online(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun submit(a: JSONObject): JSONObject {
        val now = Date()
        val body = JSONObject(a.toString()).put("client_id", UUID.randomUUID().toString())
        if (body.optString("kind") == "expense") {
            if (!body.has("date")) body.put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now))
            body.put("time", SimpleDateFormat("HH:mm", Locale.US).format(now))
        }
        val title = quickTitle(body)
        if (!online()) return queued(body, title)
        // ждём ответ недолго: человек записывает на ходу. Опоздавший запрос не страшен — client_id отсечёт дубль
        val done = CountDownLatch(1)
        var res: JSONObject? = null
        var failure: Exception? = null
        Thread {
            try { res = Api.post(this, OfflineQueue.QUICK_PATH, body, 5000) } catch (e: Exception) { failure = e }
            done.countDown()
        }.start()
        if (!done.await(WAIT_MS, TimeUnit.MILLISECONDS)) return queued(body, title)
        failure?.let { e ->
            if (OfflineQueue.isOffline(e) || OfflineQueue.isServerTrouble(e)) return queued(body, title)
            return err(e.message)
        }
        return ok().put("message", res?.optString("message") ?: "Записано")
    }

    private fun queued(body: JSONObject, title: String): JSONObject {
        OfflineQueue.addQuick(this, body, title)
        return ok().put("queued", true).put("message", "Нет связи с SWDS — сохранено, отправится само")
            .put("count", OfflineQueue.count(this))
    }

    private fun quickTitle(b: JSONObject): String = if (b.optString("kind") == "expense")
        "💸 " + b.optString("amount") + " " + b.optString("category") else "📝 " + b.optString("text").take(50)

    companion object {
        const val EXTRA_MODE = "mode"
        private const val WAIT_MS = 6000L

        fun intent(ctx: Context, mode: String): Intent = Intent(ctx, QuickActivity::class.java)
            .setAction("ru.skywige.swds.QUICK_" + mode)
            .putExtra(EXTRA_MODE, mode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
