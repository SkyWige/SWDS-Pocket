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
 *
 * Режим «только места» (Prefs.trackMode = places): отметка раз в 2–120 минут, без связи с сервером.
 * Стоянки склеиваются в одну запись «с … до …» (Places), на сервер уходят лишь приход и уход.
 */
class TrackService : Service(), LocationListener {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val buf = JSONArray()
    private var lastSend = 0L
    private var last: Location? = null
    private var count = 0
    private var places = false
    private var lastPlaceFix = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        places = Prefs(this).trackMode == "places"
        val n = notification(if (places) "Отмечаю места" else "Записываю маршрут")
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
        val p = Prefs(this)
        places = p.trackMode == "places"
        val every = if (places) p.placesInterval.coerceIn(2, 120) * 60_000L else p.trackInterval.coerceIn(5, 300) * 1000L
        try {
            lm.removeUpdates(this)
            val providers = lm.getProviders(true)
            if (places) {
                // редкие отметки: «слитный» источник сам выбирает Wi-Fi/вышки/GPS и почти не тратит заряд
                val fused = Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in providers
                if (fused) lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, every, 0f, this, thread!!.looper)
                else {
                    if (LocationManager.NETWORK_PROVIDER in providers)
                        lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, every, 0f, this, thread!!.looper)
                    if (LocationManager.GPS_PROVIDER in providers && granted(Manifest.permission.ACCESS_FINE_LOCATION))
                        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, every, 0f, this, thread!!.looper)
                }
                // чужие отметки (навигатор, другое приложение) — бесплатно уточняют, где мы
                if (LocationManager.PASSIVE_PROVIDER in lm.allProviders)
                    lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, every, 0f, this, thread!!.looper)
                placeStatus()
                return
            }
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

    private fun placeStatus() {
        val cur = Places.current(this)
        val every = Prefs(this).placesInterval
        val step = if (every >= 60) "раз в ${every / 60} ч" else "раз в $every мин"
        if (cur == null) { update("Отмечаю места $step"); return }
        val name = Places.labelAt(this, cur.optDouble("lat"), cur.optDouble("lng")).ifEmpty { "на месте" }
        val since = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(cur.optLong("from") * 1000))
        update("📍 $name с $since · отметка $step")
    }

    /** Режим «только места»: не чаще одной отметки за интервал, грубые отметки пропускаем. */
    private fun onPlaceFix(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > 400) return
        val every = Prefs(this).placesInterval.coerceIn(2, 120) * 60_000L
        val now = System.currentTimeMillis()
        if (now - loc.time > every) return                                // старая отметка из кеша системы
        if (now - lastPlaceFix < every * 8 / 10) return
        lastPlaceFix = now
        count++
        Places.onFix(this, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else 0f, loc.time / 1000, upload = true)
        placeStatus()
    }

    override fun onLocationChanged(loc: Location) {
        if (places) { onPlaceFix(loc); return }
        val prev = last
        if (loc.hasAccuracy() && loc.accuracy > 150) return            // слишком неточно — лишь шум на карте
        if (prev != null) {
            // из нескольких источников берём лучшее: менее точная точка сразу после точной не нужна
            if (loc.time - prev.time < 4000 && loc.accuracy > prev.accuracy) return
            if (loc.distanceTo(prev) < 5 && loc.time - prev.time < 60_000) return
        }
        last = loc
        count++
        // журнал «Мои места» ведётся и при подробном маршруте (точки на сервер здесь отправляет сам маршрут)
        runCatching { Places.onFix(this, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else 0f, loc.time / 1000, upload = false) }
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
        if (intent?.action == A_NOW) handler?.post { lastPlaceFix = 0L; requestUpdates() }
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
        .setContentTitle(if (places) "SWDS · мои места" else "SWDS · карта передвижений")
        .setContentText(text)
        .setOngoing(true).setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(if (places) android.app.PendingIntent.getActivity(this, 7, Intent(this, PlacesActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        else Notifier.openIntent(this, "/modules/travels/window", 7))
        .build()

    private fun update(text: String) {
        runCatching { getSystemService(android.app.NotificationManager::class.java).notify(ID, notification(text)) }
    }

    companion object {
        const val ID = 7
        const val A_FLUSH = "ru.skywige.swds.TRACK_FLUSH"
        const val A_RESTART = "ru.skywige.swds.TRACK_RESTART"
        const val A_NOW = "ru.skywige.swds.TRACK_NOW"

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

        /** «Отметиться сейчас»: не ждать следующего интервала. */
        fun markNow(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, TrackService::class.java).setAction(A_NOW)) }
        }

        fun restart(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, TrackService::class.java).setAction(A_RESTART)) }
        }
    }
}
