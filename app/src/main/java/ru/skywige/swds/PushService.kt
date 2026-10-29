package ru.skywige.swds

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection

/**
 * Постоянное соединение с SWDS (Server-Sent Events): уведомления, вопросы, файлы, буфер обмена
 * приходят мгновенно. При обрыве — переподключение с паузой; при появлении сети — сразу.
 */
class PushService : Service() {
    @Volatile private var running = false
    @Volatile private var conn: HttpURLConnection? = null
    private var thread: Thread? = null
    private val wake = Object()
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var lastText = ""
    private val powerRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { reportAsync(1500) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val n = Notifier.service(this, "Подключение…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(Notifier.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(Notifier.ID_SERVICE, n)
        val cm = getSystemService(ConnectivityManager::class.java)
        netCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { kick(reconnect = true) }
        }
        runCatching { cm.registerDefaultNetworkCallback(netCb!!) }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        runCatching { ContextCompat.registerReceiver(this, powerRx, f, ContextCompat.RECEIVER_EXPORTED) }
    }

    private fun reportAsync(delayMs: Long = 0) {
        Thread {
            if (delayMs > 0) runCatching { Thread.sleep(delayMs) }
            runCatching { Device.send(this) }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = Prefs(this)
        if (!p.paired || !p.persistentPush) { stopSelf(); return START_NOT_STICKY }
        if (intent?.getBooleanExtra("reconnect", false) == true) kick(reconnect = true)
        if (thread?.isAlive != true) {
            running = true
            thread = Thread({ loop() }, "swds-push").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { conn?.disconnect() }
        kick(false)
        netCb?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        runCatching { unregisterReceiver(powerRx) }
        super.onDestroy()
    }

    private fun kick(reconnect: Boolean) {
        if (reconnect) runCatching { conn?.disconnect() }
        synchronized(wake) { wake.notifyAll() }
    }

    private fun status(text: String) {
        if (text == lastText) return
        lastText = text
        Notifier.post(this, Notifier.ID_SERVICE, Notifier.service(this, text))
    }

    private fun loop() {
        var backoff = 2L
        var firstConnect = true
        while (running) {
            val p = Prefs(this)
            if (!p.paired) { stopSelf(); return }
            try {
                if (firstConnect) {
                    runCatching { Events.catchUp(this) }
                    firstConnect = false
                }
                stream()
                backoff = 2
            } catch (e: ApiException) {
                if (e.status == 401) {
                    status("Телефон отвязан от сервера")
                    p.unpair(); stopSelf(); return
                }
                status("Нет связи с SWDS — повтор через ${backoff} с")
            } catch (e: Exception) {
                status("Нет связи с SWDS — повтор через ${backoff} с")
            }
            if (!running) break
            synchronized(wake) { runCatching { wake.wait(backoff * 1000) } }
            backoff = minOf(backoff * 2, 120)
            firstConnect = firstConnect || backoff > 30
        }
    }

    private fun stream() {
        val after = Prefs(this).lastQueueId
        Api.withServer(this, "/modules/mobile/api/stream?after=$after", "GET", 10000, 75000) { c ->
            conn = c
            c.setRequestProperty("Accept", "text/event-stream")
            val code = c.responseCode
            if (code == 401 || code == 403) throw ApiException("Нет доступа", 401)
            if (code >= 400) throw ApiException("Ошибка $code", code)
            status("На связи · ${Prefs(this).activeServer.substringAfter("://")}")
            reportAsync()
            Thread {                                             // связь есть — отправить накопленное без сети
                runCatching { if (OfflineQueue.count(this) > 0) OfflineQueue.flush(this) }
                runCatching { FeedStore.flush(this) }
            }.start()
            Prefs(this).lastStreamOk = System.currentTimeMillis()
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                val data = StringBuilder()
                while (running) {
                    val line = r.readLine() ?: break
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) {
                                runCatching { Events.handle(this, JSONObject(data.toString())) }
                                data.setLength(0)
                            }
                            Prefs(this).lastStreamOk = System.currentTimeMillis()
                        }
                        line.startsWith("data:") -> data.append(line.substring(5).trim())
                        line.startsWith(":") -> Prefs(this).lastStreamOk = System.currentTimeMillis()
                    }
                }
            }
            c.disconnect()
        }
    }

    companion object {
        fun start(ctx: Context, reconnect: Boolean = false) {
            val p = Prefs(ctx)
            if (!p.paired || !p.persistentPush) return
            val i = Intent(ctx, PushService::class.java).putExtra("reconnect", reconnect)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                // из фона Android 12+ не даёт запустить службу — сработает WorkManager
                SyncWorker.now(ctx, "pending")
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PushService::class.java))
        }
    }
}
