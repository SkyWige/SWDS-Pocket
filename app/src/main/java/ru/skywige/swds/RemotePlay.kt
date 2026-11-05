package ru.skywige.swds

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Запуск с сервера (команда media_manager.play, автоматизация «пришёл домой»):
 *  • приложение открыто — играет плеер SWDS на странице, и музыка, и видео;
 *  • приложение в фоне, только музыка — играет здесь, с кнопками в уведомлении;
 *  • приложение в фоне, есть видео — Android не даёт открыть окно самому, поэтому важное уведомление:
 *    на заблокированном экране плеер открывается сразу (полноэкранное уведомление), иначе — по нажатию.
 */
object RemotePlay {
    private const val MAX_AGE_SEC = 600.0
    private const val ID_VIDEO = 4200

    fun handle(ctx: Context, e: JSONObject) {
        val items = e.optJSONArray("items") ?: return
        if (items.length() == 0) return
        // телефон был без связи: старый запуск («пришёл домой» утром) вечером включать не надо
        val created = e.optDouble("created", 0.0)
        if (created > 0 && System.currentTimeMillis() / 1000.0 - created > MAX_AGE_SEC) return
        if (MainActivity.visible && MainActivity.current?.get() != null) {
            RemoteAudio.stop(ctx)
            MainActivity.playQueue(e)
            return
        }
        val hasVideo = (0 until items.length()).any { items.optJSONObject(it)?.optString("mediaType") == "video" }
        if (!hasVideo) {
            RemoteAudio.play(ctx, items, e.optInt("index", 0), e.optString("repeat") == "all", e.optString("page"))
            return
        }
        val page = e.optString("page")
        val pi = Notifier.openIntent(ctx, page, ID_VIDEO)
        val first = items.optJSONObject(0)
        val n = NotificationCompat.Builder(ctx, Notifier.CH_WARN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("🎬 Включить видео")
            .setContentText(first?.optString("title").orEmpty())
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setTimeoutAfter(5 * 60_000L)
            .build()
        Notifier.post(ctx, ID_VIDEO, n)
    }
}

/** Простой плеер для музыки, пока приложение свёрнуто. Служба связи (PushService) держит процесс живым. */
object RemoteAudio {
    const val A_CMD = "ru.skywige.swds.REMOTE_AUDIO"
    private const val ID_AUDIO = 4300
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var queue = JSONArray()
    private var index = 0
    private var repeat = false
    private var page = ""
    private var triedTranscode = false
    private var focus: AudioFocusRequest? = null

    fun play(ctx: Context, items: JSONArray, start: Int, loop: Boolean, pagePath: String) {
        val app = ctx.applicationContext
        main.post {
            queue = items
            index = start.coerceIn(0, items.length() - 1)
            repeat = loop
            page = pagePath
            requestFocus(app)
            load(app, false)
        }
    }

    fun command(ctx: Context, cmd: String) {
        val app = ctx.applicationContext
        main.post {
            when (cmd) {
                "next" -> advance(app, 1)
                "prev" -> advance(app, -1)
                "toggle" -> player?.let { if (it.isPlaying) it.pause() else it.start(); notifyState(app) }
                "stop" -> stopNow(app)
            }
        }
    }

    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        main.post { stopNow(app) }
    }

    private fun stopNow(ctx: Context) {
        release()
        queue = JSONArray()
        focus?.let { ctx.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
        Notifier.cancel(ctx, ID_AUDIO)
    }

    private fun release() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    private fun requestFocus(ctx: Context) {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> stopNow(ctx)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player?.let { if (it.isPlaying) { it.pause(); notifyState(ctx) } }
                }
            }.build()
        focus = req
        ctx.getSystemService(AudioManager::class.java).requestAudioFocus(req)
    }

    private fun url(ctx: Context, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val p = Prefs(ctx)
        return p.activeServer.ifEmpty { p.servers.firstOrNull().orEmpty() } + path
    }

    private fun load(ctx: Context, transcode: Boolean) {
        val it = queue.optJSONObject(index) ?: return stopNow(ctx)
        release()
        triedTranscode = transcode
        val src = if (transcode) it.optString("tc") else it.optString("src")
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(url(ctx, src))
                setOnPreparedListener { mp -> mp.start(); notifyState(ctx) }
                setOnCompletionListener { advance(ctx, 1) }
                setOnErrorListener { _, _, _ ->
                    main.post {                              // формат не поддержан — через перекодирование, потом следующий
                        if (!triedTranscode && it.optString("tc").isNotEmpty()) load(ctx, true) else advance(ctx, 1)
                    }
                    true
                }
                prepareAsync()
            }
            notifyState(ctx)
        } catch (e: Exception) {
            android.util.Log.w("SWDS", "remote audio: $e")
            advance(ctx, 1)
        }
    }

    private fun advance(ctx: Context, step: Int) {
        val next = index + step
        when {
            queue.length() == 0 -> stopNow(ctx)
            next in 0 until queue.length() -> { index = next; load(ctx, false) }
            repeat -> { index = if (next < 0) queue.length() - 1 else 0; load(ctx, false) }
            step < 0 -> runCatching { player?.seekTo(0) }
            else -> stopNow(ctx)
        }
    }

    private fun action(ctx: Context, cmd: String, req: Int): PendingIntent =
        PendingIntent.getBroadcast(ctx, req, Intent(ctx, ActionReceiver::class.java).setAction(A_CMD).putExtra("cmd", cmd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notifyState(ctx: Context) {
        val it = queue.optJSONObject(index) ?: return
        val playing = runCatching { player?.isPlaying == true }.getOrDefault(false)
        val b = NotificationCompat.Builder(ctx, MediaHub.CH_MEDIA)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(it.optString("title").ifEmpty { "SWDS" })
            .setContentText(it.optString("artist"))
            .setOnlyAlertOnce(true).setSilent(true).setOngoing(playing)
            .setDeleteIntent(action(ctx, "stop", 4304))
            .setContentIntent(Notifier.openIntent(ctx, page, ID_AUDIO))
            .addAction(0, "⏮", action(ctx, "prev", 4301))
            .addAction(0, if (playing) "⏸" else "▶", action(ctx, "toggle", 4302))
            .addAction(0, "⏭", action(ctx, "next", 4303))
            .addAction(0, "✕", action(ctx, "stop", 4304))
        Notifier.post(ctx, ID_AUDIO, b.build())
    }
}
