package ru.skywige.swds

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Медиа-уведомление Android с кнопками ⏮ ⏯ ⏭ (и на экране блокировки, и для гарнитуры):
 *  • музыка/видео играет в плеере SWDS внутри приложения — управляем им напрямую (JS);
 *  • играет на компьютере (Spotify, браузер, плеер SWDS на ПК) — команды уходят на сервер.
 */
object MediaHub {
    const val CH_MEDIA = "swds_media"
    const val ID_MEDIA = 8
    const val A_MEDIA = "ru.skywige.swds.MEDIA"

    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var local: JSONObject? = null            // {provider, title, ..., status}
    private var localClient = ""
    private var localAt = 0L
    private var remote: List<JSONObject> = emptyList()
    private var remoteAt = 0L
    private var current: JSONObject? = null
    private var currentIsLocal = false
    private var lastSig = ""
    private val art = HashMap<String, Bitmap?>()
    @Volatile var foreground = false

    fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val c = NotificationChannel(CH_MEDIA, "Плеер", NotificationManager.IMPORTANCE_LOW)
        c.description = "Что играет в SWDS и на компьютере — с кнопками управления"
        c.setShowBadge(false)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(c)
    }

    /** Из WebView (swds_now_playing.js): {client, sessions:[…]} */
    fun onLocal(ctx: Context, json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val arr = o.optJSONArray("sessions") ?: JSONArray()
        main.post {
            localClient = o.optString("client")
            var best: JSONObject? = null
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                if (best == null || (s.optString("status") == "playing" && best.optString("status") != "playing")) best = s
            }
            local = best
            localAt = System.currentTimeMillis()
            update(ctx.applicationContext)
        }
    }

    /** Страница закрылась — локального плеера больше нет. */
    fun clearLocal(ctx: Context) = main.post { local = null; update(ctx.applicationContext) }

    /** С сервера (событие media): что играет на других устройствах. */
    fun onRemote(ctx: Context, e: JSONObject) {
        val ts = (e.optDouble("ts", 0.0) * 1000).toLong()
        if (ts > 0 && System.currentTimeMillis() - ts > 120_000) return       // старое из очереди
        val arr = e.optJSONArray("sessions") ?: JSONArray()
        main.post {
            val list = ArrayList<JSONObject>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optString("client") != localClient) list.add(it) }
            remote = list
            remoteAt = System.currentTimeMillis()
            update(ctx.applicationContext)
        }
    }

    private fun pick(ctx: Context): Pair<JSONObject?, Boolean> {
        val now = System.currentTimeMillis()
        val l = local?.takeIf { now - localAt < 30_000 }
        val showRemote = Prefs(ctx).remoteMedia
        val r = if (showRemote) remote.filter { now - remoteAt < 10 * 60_000L } else emptyList()
        l?.takeIf { it.optString("status") == "playing" }?.let { return it to true }
        r.firstOrNull { it.optString("status") == "playing" }?.let { return it to false }
        l?.let { return it to true }
        r.firstOrNull()?.let { return it to false }
        return null to false
    }

    private fun ensureSession(ctx: Context): MediaSession {
        session?.let { return it }
        val s = MediaSession(ctx, "SWDS")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = command(ctx, "play")
            override fun onPause() = command(ctx, "pause")
            override fun onSkipToNext() = command(ctx, "next")
            override fun onSkipToPrevious() = command(ctx, "prev")
            override fun onStop() = command(ctx, "pause")
            override fun onSeekTo(pos: Long) = command(ctx, "seek", pos / 1000.0)
        })
        session = s
        return s
    }

    fun update(ctx: Context) {
        val (cur, isLocal) = pick(ctx)
        current = cur; currentIsLocal = isLocal
        if (cur == null) { hide(ctx); return }
        val playing = cur.optString("status") == "playing"
        val s = ensureSession(ctx)
        val title = cur.optString("title").ifEmpty { "SWDS" }
        val artist = cur.optString("artist")
        val source = if (isLocal) "SWDS Pocket" else listOf(cur.optString("source"), cur.optString("device"))
            .filter { it.isNotEmpty() }.distinct().joinToString(" · ").ifEmpty { "Компьютер" }
        val cover = cur.optString("cover")
        val bmp = if (cover.isNotEmpty()) art[cover] else null
        if (cover.isNotEmpty() && !art.containsKey(cover)) loadArt(ctx, cover)
        val dur = (cur.optDouble("duration", 0.0) * 1000).toLong()
        val pos = (cur.optDouble("position", 0.0) * 1000).toLong()

        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, cur.optString("album"))
            .putLong(MediaMetadata.METADATA_KEY_DURATION, if (dur > 0) dur else -1)
        if (bmp != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp)
        s.setMetadata(md.build())
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
        if (cur.optBoolean("can_seek", isLocal) && dur > 0) actions = actions or PlaybackState.ACTION_SEEK_TO
        s.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, pos, if (playing) 1f else 0f)
            .build())
        s.isActive = true

        val sig = listOf(title, artist, source, playing, bmp != null, isLocal).joinToString("|")
        val n = build(ctx, s, title, artist, source, bmp, playing, isLocal)
        val wantFg = isLocal && playing
        if (wantFg && !foreground) {
            runCatching {
                val i = Intent(ctx, MediaService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }
        if (!wantFg && foreground) ctx.stopService(Intent(ctx, MediaService::class.java))
        if (sig != lastSig || wantFg) {
            lastSig = sig
            Notifier.post(ctx, ID_MEDIA, n)
        }
    }

    fun notification(ctx: Context): Notification? {
        val cur = current ?: return null
        val s = session ?: return null
        val source = if (currentIsLocal) "SWDS Pocket" else cur.optString("source").ifEmpty { "Компьютер" }
        return build(ctx, s, cur.optString("title").ifEmpty { "SWDS" }, cur.optString("artist"), source,
            art[cur.optString("cover")], cur.optString("status") == "playing", currentIsLocal)
    }

    private fun build(ctx: Context, s: MediaSession, title: String, artist: String, source: String, bmp: Bitmap?,
                      playing: Boolean, isLocal: Boolean): Notification {
        fun act(icon: Int, label: String, cmd: String, req: Int): Notification.Action {
            val pi = PendingIntent.getBroadcast(ctx, 600 + req, Intent(ctx, ActionReceiver::class.java).setAction(A_MEDIA)
                .putExtra("cmd", cmd), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Action.Builder(Icon.createWithResource(ctx, icon), label, pi).build()
        }
        val open = if (isLocal) Notifier.openIntent(ctx, null, 610) else Notifier.openIntent(ctx, "/modules/mobile/remote", 611)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH_MEDIA) else Notification.Builder(ctx)
        b.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(artist.ifEmpty { source })
            .setSubText(source)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(isLocal && playing)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .addAction(act(android.R.drawable.ic_media_previous, "Назад", "prev", 1))
            .addAction(if (playing) act(android.R.drawable.ic_media_pause, "Пауза", "pause", 2)
                       else act(android.R.drawable.ic_media_play, "Играть", "play", 2))
            .addAction(act(android.R.drawable.ic_media_next, "Вперёд", "next", 3))
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
        if (bmp != null) b.setLargeIcon(bmp)
        if (!isLocal) b.setDeleteIntent(PendingIntent.getBroadcast(ctx, 620, Intent(ctx, ActionReceiver::class.java)
            .setAction(A_MEDIA).putExtra("cmd", "dismiss"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        return b.build()
    }

    private fun hide(ctx: Context) {
        if (foreground) ctx.stopService(Intent(ctx, MediaService::class.java))
        Notifier.cancel(ctx, ID_MEDIA)
        session?.let { it.isActive = false; it.release() }
        session = null
        lastSig = ""
    }

    private fun loadArt(ctx: Context, url: String) {
        art[url] = null
        Thread {
            val bmp = runCatching {
                Api.download(ctx, url) { input, _, _, _ -> BitmapFactory.decodeStream(input) }
            }.getOrNull()?.let { Bitmap.createScaledBitmap(it, 256, 256 * it.height / maxOf(1, it.width), true) }
            main.post {
                art[url] = bmp
                if (art.size > 30) art.keys.take(10).toList().forEach { art.remove(it) }
                lastSig = ""
                update(ctx)
            }
        }.start()
    }

    /** Кнопки уведомления, гарнитуры и экрана блокировки. */
    fun command(ctx: Context, cmd: String, value: Double? = null) {
        val app = ctx.applicationContext
        main.post {
            val cur = current ?: return@post
            if (cmd == "dismiss") { remote = emptyList(); update(app); return@post }
            val c = when (cmd) {
                "play", "pause" -> if ((cmd == "pause") == (cur.optString("status") == "playing")) cmd else "play_pause"
                else -> cmd
            }
            if (cmd == "play" || cmd == "pause") {                          // мгновенный отклик
                cur.put("status", if (cmd == "play") "playing" else "paused"); lastSig = ""; update(app)
            }
            if (currentIsLocal) {
                val js = "window.SWDSNowPlaying && SWDSNowPlaying.command(${JSONObject.quote(cur.optString("provider"))}, " +
                        "${JSONObject.quote(c)}, ${value ?: "null"})"
                MainActivity.runJs(js)
            } else {
                val body = JSONObject().put("key", cur.optString("key")).put("cmd", c)
                if (value != null) body.put("value", value)
                Thread {
                    try { Api.post(app, "/modules/mobile/api/media/command", body) }
                    catch (e: Exception) { Events.toast(app, "⚠ " + (e.message ?: "Нет связи")) }
                }.start()
            }
        }
    }
}

/** Фоновая служба на время воспроизведения в приложении — чтобы Android не остановил музыку. */
class MediaService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = MediaHub.notification(this)
        if (n == null) { stopSelf(); return START_NOT_STICKY }
        if (Build.VERSION.SDK_INT >= 29) startForeground(MediaHub.ID_MEDIA, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(MediaHub.ID_MEDIA, n)
        MediaHub.foreground = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        MediaHub.foreground = false
        stopForeground(STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }
}
