package ru.skywige.swds

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** Состояние телефона для сервера: заряд, зарядка, Wi-Fi, сеть, местоположение. */
object Device {
    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun battery(ctx: Context): Pair<Int, Boolean> {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    fun net(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return ""
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }

    @Suppress("DEPRECATION")
    fun ssid(ctx: Context): String? {
        if (net(ctx) != "wifi") return ""
        if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) && !granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val s = wm.connectionInfo?.ssid?.trim('"') ?: return null
            if (s == "<unknown ssid>" || s.isEmpty()) null else s
        } catch (e: Exception) { null }
    }

    fun location(ctx: Context): Location? {
        if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) && !granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        return try {
            val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
            lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) { null }
    }

    fun report(ctx: Context, withLocation: Boolean = true): JSONObject {
        val p = Prefs(ctx)
        val o = JSONObject().put("app_version", BuildConfig.VERSION_NAME).put("net", net(ctx))
        if (p.reportDevice) {
            val (b, ch) = battery(ctx)
            if (b >= 0) o.put("battery", b)
            o.put("charging", ch)
            ssid(ctx)?.let { o.put("wifi", it) }
        }
        if (withLocation && p.reportLocation) {
            location(ctx)?.let {
                if (System.currentTimeMillis() - it.time < 30 * 60_000L)
                    o.put("lat", it.latitude).put("lng", it.longitude).put("accuracy", it.accuracy.toDouble())
            }
        }
        return o
    }

    fun send(ctx: Context): JSONObject = Api.post(ctx, "/modules/mobile/api/report", report(ctx))
}

/** «Найти телефон»: громкий сигнал будильника, даже в беззвучном режиме. */
object Ring {
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private val handler = Handler(Looper.getMainLooper())

    fun start(ctx: Context, text: String) {
        handler.post {
            stopNow(ctx)
            val am = ctx.getSystemService(AudioManager::class.java)
            try {
                savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            } catch (e: Exception) { savedVolume = -1 }
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(ctx, uri)
                    isLooping = true
                    prepare(); start()
                }
            } catch (e: Exception) { player = null }
            val stop = android.app.PendingIntent.getBroadcast(ctx, 77,
                Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.A_RING_STOP),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val n = androidx.core.app.NotificationCompat.Builder(ctx, Notifier.CH_RING)
                .setSmallIcon(R.drawable.ic_stat).setContentTitle("📢 Найти телефон").setContentText(text)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true).setContentIntent(stop).setDeleteIntent(stop)
                .addAction(0, "Остановить", stop).build()
            Notifier.post(ctx, Notifier.ID_RING, n)
            handler.postDelayed({ stopNow(ctx) }, 60_000)
        }
    }

    fun stop(ctx: Context) = handler.post { stopNow(ctx) }

    private fun stopNow(ctx: Context) {
        handler.removeCallbacksAndMessages(null)
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        if (savedVolume >= 0) {
            runCatching { ctx.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
            savedVolume = -1
        }
        Notifier.cancel(ctx, Notifier.ID_RING)
    }
}
