package ru.skywige.swds

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Записывать маршруты»: GPS-трек телефона для «Карты передвижений» SWDS.
 *
 * Точки (раз в 15–60 с и при сдвиге на десяток метров) копятся и уходят пачкой в «Геолокацию» раз в пару минут;
 * без связи — в очередь OfflineQueue и отправляются, когда сервер снова доступен. Пока телефон лежит на месте,
 * GPS почти не тратит заряд: Android не присылает новых точек, если нет движения.
 * Расстояние в SWDS считается по браслету, трек нужен только для карты.
 */
class TrackService : Service(), LocationListener {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val buf = JSONArray()
    private var lastSend = 0L
    private var last: Location? = null
    private var count = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val n = notification("Записываю маршрут")
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(ID, n)
        } catch (e: Exception) {                               // Android 14 без доступа к геоданным не даст запустить
            android.util.Log.w("SWDS", "track: $e"); stopSelf(); return
        }
        thread = HandlerThread("swds-track").also { it.start() }
        handler = Handler(thread!!.looper)
        requestUpdates()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun requestUpdates() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) { stopSelf(); return }
        val lm = getSystemService(LocationManager::class.java) ?: return
        val every = Prefs(this).trackInterval.coerceIn(5, 300) * 1000L
        try {
            lm.removeUpdates(this)
            val providers = lm.getProviders(true)
            if (LocationManager.GPS_PROVIDER in providers && granted(Manifest.permission.ACCESS_FINE_LOCATION))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, every, 12f, this, thread!!.looper)
            if (LocationManager.NETWORK_PROVIDER in providers)
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, every * 2, 40f, this, thread!!.looper)
            if (Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in providers)
                lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, every, 12f, this, thread!!.looper)
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    override fun onLocationChanged(loc: Location) {
        val prev = last
        if (loc.hasAccuracy() && loc.accuracy > 150) return            // слишком неточно — лишь шум на карте
        if (prev != null) {
            // из нескольких источников берём лучшее: менее точная точка сразу после точной не нужна
            if (loc.time - prev.time < 4000 && loc.accuracy > prev.accuracy) return
            if (loc.distanceTo(prev) < 5 && loc.time - prev.time < 60_000) return
        }
        last = loc
        count++
        synchronized(buf) {
            buf.put(JSONObject().put("lat", loc.latitude).put("lng", loc.longitude)
                .put("accuracy", if (loc.hasAccuracy()) loc.accuracy.toDouble() else JSONObject.NULL)
                .put("ts", loc.time / 1000)
                .apply { if (loc.hasSpeed()) put("speed", loc.speed.toDouble()) }
                .apply { if (loc.hasBearing()) put("heading", loc.bearing.toDouble()) })
        }
        val now = System.currentTimeMillis()
        if (now - lastSend > 120_000 || buf.length() >= 30) flush()
    }

    private fun flush() {
        val pts: JSONArray
        synchronized(buf) {
            if (buf.length() == 0) return
            pts = JSONArray(buf.toString())
            while (buf.length() > 0) buf.remove(0)
        }
        lastSend = System.currentTimeMillis()
        val p = Prefs(this)
        try {
            Api.post(this, "/modules/geolocation/api/client_report", JSONObject().put("device_id", "phone_" + p.deviceId)
                .put("name", p.deviceName).put("icon", "📱").put("points", pts), 15000, 30000)
            if (OfflineQueue.geoCount(this) > 0) runCatching { OfflineQueue.flushGeo(this) }
            update("Маршрут записывается · точек сегодня: $count")
        } catch (e: Exception) {
            for (i in 0 until pts.length()) OfflineQueue.addGeo(this, pts.getJSONObject(i))   // без связи — копим
            update("Нет связи с SWDS — точки копятся на телефоне")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == A_FLUSH) handler?.post { flush() }
        if (intent?.action == A_RESTART) handler?.post { requestUpdates() }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { getSystemService(LocationManager::class.java)?.removeUpdates(this) }
        handler?.post { flush() }
        thread?.quitSafely()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun notification(text: String) = NotificationCompat.Builder(this, Notifier.CH_SERVICE)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle("SWDS · карта передвижений")
        .setContentText(text)
        .setOngoing(true).setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(Notifier.openIntent(this, "/modules/travels/window", 7))
        .build()

    private fun update(text: String) {
        runCatching { getSystemService(android.app.NotificationManager::class.java).notify(ID, notification(text)) }
    }

    companion object {
        const val ID = 7
        const val A_FLUSH = "ru.skywige.swds.TRACK_FLUSH"
        const val A_RESTART = "ru.skywige.swds.TRACK_RESTART"

        fun start(ctx: Context) {
            val p = Prefs(ctx)
            if (!p.paired || !p.trackRoutes) return
            val ok = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!ok) return
            runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, TrackService::class.java)) }
                .onFailure { android.util.Log.w("SWDS", "track start: $it") }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, TrackService::class.java)) }
        }

        fun restart(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, TrackService::class.java).setAction(A_RESTART)) }
        }
    }
}
