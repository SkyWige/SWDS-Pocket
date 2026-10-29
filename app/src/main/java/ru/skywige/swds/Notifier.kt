package ru.skywige.swds

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import org.json.JSONArray
import org.json.JSONObject

/** Уведомления в шторке: от SWDS, вопросы с кнопками, файлы, служебное. */
object Notifier {
    const val CH_INFO = "swds_info"
    const val CH_WARN = "swds_warn"
    const val CH_ALERT = "swds_alert"
    const val CH_ASK = "swds_ask"
    const val CH_FILES = "swds_files"
    const val CH_SERVICE = "swds_service"
    const val CH_RING = "swds_ring"

    const val ID_SERVICE = 1
    const val ID_RING = 2
    const val ID_UPLOAD = 3
    const val ID_DOWNLOAD = 4
    const val KEY_REPLY = "reply"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannelGroup(NotificationChannelGroup("swds", "SWDS"))
        fun ch(id: String, name: String, imp: Int, desc: String, block: NotificationChannel.() -> Unit = {}) {
            val c = NotificationChannel(id, name, imp)
            c.description = desc
            c.group = "swds"
            c.block()
            nm.createNotificationChannel(c)
        }
        ch(CH_INFO, "Уведомления SWDS", NotificationManager.IMPORTANCE_DEFAULT, "Обычные уведомления сервера")
        ch(CH_WARN, "Важные", NotificationManager.IMPORTANCE_HIGH, "Предупреждения: всплывают поверх экрана")
        ch(CH_ALERT, "Критичные", NotificationManager.IMPORTANCE_HIGH, "Критичные события сервера") {
            enableVibration(true); vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
        }
        ch(CH_ASK, "Вопросы SWDS", NotificationManager.IMPORTANCE_HIGH, "Вопросы с кнопками — ответ уходит на сервер")
        ch(CH_FILES, "Файлы", NotificationManager.IMPORTANCE_LOW, "Передача файлов между телефоном и компьютером")
        ch(CH_SERVICE, "Связь с сервером", NotificationManager.IMPORTANCE_MIN,
            "Постоянное соединение для мгновенных уведомлений. Это уведомление можно скрыть в настройках канала") {
            setShowBadge(false)
        }
        ch(CH_RING, "Поиск телефона", NotificationManager.IMPORTANCE_HIGH, "Звонок «Найти телефон»") {
            setSound(null, null)
        }
    }

    private fun pendingFlags(mutable: Boolean = false): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
                (if (mutable && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE
                else if (!mutable) PendingIntent.FLAG_IMMUTABLE else 0)

    fun openIntent(ctx: Context, url: String?, req: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (!url.isNullOrEmpty()) {
            if (url.startsWith("/")) i.putExtra("path", url)
            else return PendingIntent.getActivity(ctx, req, Intent(Intent.ACTION_VIEW, Uri.parse(url)), pendingFlags())
        }
        return PendingIntent.getActivity(ctx, req, i, pendingFlags())
    }

    private fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(ctx: Context, id: Int, n: android.app.Notification) {
        if (!canPost(ctx)) return
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (e: SecurityException) { }
    }

    fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)

    /** Уведомление SWDS: {id, title, body, level, actions:[{title, command, args, url}], url, silent} */
    fun show(ctx: Context, n: JSONObject) {
        val qid = n.optLong("id", System.currentTimeMillis()).toInt()
        val level = n.optString("level", "info")
        val ch = when (level) { "critical" -> CH_ALERT; "warning" -> CH_WARN; else -> CH_INFO }
        val title = n.optString("title", "SWDS")
        val body = n.optString("body", "")
        val pal = Theme.palette(ctx)
        val b = NotificationCompat.Builder(ctx, ch)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setColor(when (level) {
                "critical" -> 0xFFEF4444.toInt(); "warning" -> 0xFFF59E0B.toInt(); "success" -> 0xFF10B981.toInt()
                else -> pal.accent
            })
            .setContentIntent(openIntent(ctx, n.optString("url").ifEmpty { null }, qid))
            .setWhen((n.optDouble("ts", n.optDouble("created", 0.0)) * 1000).toLong().takeIf { it > 0 } ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setGroup("swds_notifications")
            .setPriority(when (level) { "critical", "warning" -> NotificationCompat.PRIORITY_HIGH; else -> NotificationCompat.PRIORITY_DEFAULT })
            .setCategory(if (level == "critical") NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
        if (n.optBoolean("silent")) b.setSilent(true)
        val acts = n.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until minOf(3, acts.length())) {
            val a = acts.optJSONObject(i) ?: continue
            val pi: PendingIntent = if (a.optString("command").isNotEmpty()) {
                val it = Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.A_NOTIF_CMD)
                    .putExtra("command", a.optString("command"))
                    .putExtra("args", (a.optJSONObject("args") ?: JSONObject()).toString())
                    .putExtra("notif_id", qid)
                PendingIntent.getBroadcast(ctx, qid * 10 + i, it, pendingFlags())
            } else openIntent(ctx, a.optString("url"), qid * 10 + i)
            b.addAction(0, a.optString("title", "Открыть"), pi)
        }
        post(ctx, qid, b.build())
        summary(ctx)
    }

    private fun summary(ctx: Context) {
        if (Build.VERSION.SDK_INT < 24) return
        val s = NotificationCompat.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat)
            .setGroup("swds_notifications").setGroupSummary(true)
            .setAutoCancel(true).setSilent(true)
            .setContentIntent(openIntent(ctx, "/modules/notifications/window", 9))
            .build()
        post(ctx, 9, s)
    }

    private fun askId(pid: String) = 100000 + (pid.hashCode() and 0x7FFFF)

    /** Вопрос с кнопками: {id, question, options[], allow_text, expires} */
    fun ask(ctx: Context, p: JSONObject) {
        val pid = p.optString("id")
        if (pid.isEmpty() || p.optString("status", "open") != "open") return
        val nid = askId(pid)
        val opts = p.optJSONArray("options") ?: JSONArray()
        val q = p.optString("question", "?")
        val open = Intent(ctx, InputActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("mode", "ask").putExtra("prompt", p.toString())
        val b = NotificationCompat.Builder(ctx, CH_ASK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("❓ SWDS спрашивает")
            .setContentText(q)
            .setStyle(NotificationCompat.BigTextStyle().bigText(q))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setColor(Theme.palette(ctx).accent)
            .setAutoCancel(false)
            .setContentIntent(PendingIntent.getActivity(ctx, nid, open, pendingFlags()))
        val exp = (p.optDouble("expires", 0.0) * 1000).toLong()
        if (exp > System.currentTimeMillis()) b.setTimeoutAfter(exp - System.currentTimeMillis())
        val shown = if (p.optBoolean("allow_text") && opts.length() > 2) 2 else minOf(3, opts.length())
        for (i in 0 until shown) {
            val it = Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.A_ANSWER)
                .putExtra("pid", pid).putExtra("index", i).putExtra("value", opts.optString(i)).putExtra("notif_id", nid)
            b.addAction(0, opts.optString(i), PendingIntent.getBroadcast(ctx, nid + i + 1, it, pendingFlags()))
        }
        if (p.optBoolean("allow_text")) {
            val ri = RemoteInput.Builder(KEY_REPLY).setLabel("Ваш ответ").build()
            val it = Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.A_ANSWER)
                .putExtra("pid", pid).putExtra("notif_id", nid)
            val pi = PendingIntent.getBroadcast(ctx, nid + 9, it, pendingFlags(mutable = true))
            b.addAction(NotificationCompat.Action.Builder(0, "Ответить", pi).addRemoteInput(ri).build())
        }
        post(ctx, nid, b.build())
    }

    fun closeAsk(ctx: Context, pid: String, text: String? = null) {
        val nid = askId(pid)
        if (text == null) { cancel(ctx, nid); return }
        val b = NotificationCompat.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("SWDS").setContentText(text)
            .setSilent(true).setTimeoutAfter(4000).setAutoCancel(true)
        post(ctx, nid, b.build())
    }

    fun simple(ctx: Context, id: Int, title: String, text: String, channel: String = CH_INFO,
               intent: PendingIntent? = null, timeoutMs: Long = 0) {
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(Theme.palette(ctx).accent).setAutoCancel(true)
        if (intent != null) b.setContentIntent(intent)
        if (timeoutMs > 0) b.setTimeoutAfter(timeoutMs)
        post(ctx, id, b.build())
    }

    fun progress(ctx: Context, id: Int, title: String, text: String, done: Long, total: Long) {
        val b = NotificationCompat.Builder(ctx, CH_FILES)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
        if (total > 0) b.setProgress(1000, (done * 1000 / total).toInt().coerceIn(0, 1000), false)
        else b.setProgress(0, 0, true)
        post(ctx, id, b.build())
    }

    fun service(ctx: Context, text: String): android.app.Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(Prefs(ctx).serverName.ifEmpty { "SWDS" })
            .setContentText(text)
            .setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openIntent(ctx, null, 1))
            .build()
}
