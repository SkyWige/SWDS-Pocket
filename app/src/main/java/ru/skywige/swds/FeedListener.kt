package ru.skywige.swds

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Лента уведомлений телефона: всё, что появляется в шторке (банк, SMS, мессенджеры), копится в файле
 * и пачками уходит на сервер (/modules/mobile/api/feed). Без сети — ждёт, ничего не теряется.
 */
class FeedListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val p = Prefs(this)
        if (!p.paired || !p.feedEnabled) return
        val item = FeedStore.fromNotification(this, sbn) ?: return
        if (!FeedStore.allowed(p, item.optString("package"))) return
        if (FeedStore.add(this, item)) FeedStore.scheduleFlush(this)
    }

    companion object {
        fun hasAccess(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, FeedListener::class.java).flattenToString()
            return flat.split(":").any { it == me || it.startsWith(ctx.packageName + "/") }
        }
    }
}

object FeedStore {
    private val lock = Any()
    private val recent = LinkedHashMap<String, Long>()
    private const val MAX_LOCAL = 5000
    private val IGNORED_CATEGORIES = setOf(Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE,
        Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SYSTEM)
    val DEFAULT_EXCLUDED = listOf("android", "com.android.systemui", "com.android.providers.downloads",
        "com.miui.securitycenter", "com.lbe.security.miui", "com.android.vending")
    @Volatile private var flushing = false
    @Volatile private var pendingFlush = false

    private fun file(ctx: Context) = File(ctx.filesDir, "feed_queue.jsonl")

    fun allowed(p: Prefs, pkg: String): Boolean {
        val list = p.feedApps
        return when (p.feedMode) {
            "only" -> pkg in list
            else -> pkg !in list && pkg !in DEFAULT_EXCLUDED
        }
    }

    private fun cs(v: Any?): String = (v as? CharSequence)?.toString()?.trim() ?: ""

    fun appLabel(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) { pkg }

    fun fromNotification(ctx: Context, sbn: StatusBarNotification): JSONObject? {
        val pkg = sbn.packageName ?: return null
        if (pkg == ctx.packageName) return null
        val n = sbn.notification ?: return null
        if (sbn.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        if (n.category in IGNORED_CATEGORIES) return null
        val ex = n.extras ?: return null
        val title = cs(ex.getCharSequence(Notification.EXTRA_TITLE)).ifEmpty { cs(ex.getCharSequence(Notification.EXTRA_TITLE_BIG)) }
        var text = cs(ex.getCharSequence(Notification.EXTRA_BIG_TEXT)).ifEmpty { cs(ex.getCharSequence(Notification.EXTRA_TEXT)) }
        // переписка (MessagingStyle): берём последнее сообщение
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null && msgs.isNotEmpty()) {
            (msgs.last() as? android.os.Bundle)?.getCharSequence("text")?.toString()?.let { if (it.isNotBlank()) text = it }
        }
        val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (text.isEmpty() && lines != null && lines.isNotEmpty()) text = lines.joinToString("\n") { it.toString() }
        if (title.isEmpty() && text.isEmpty()) return null
        val sub = cs(ex.getCharSequence(Notification.EXTRA_SUB_TEXT))
        val sig = "$pkg|$title|$text"
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val last = recent[sig]
            if (last != null && now - last < 10 * 60_000L) return null       // то же самое обновилось — не дублируем
            recent[sig] = now
            if (recent.size > 600) recent.keys.take(200).toList().forEach { recent.remove(it) }
        }
        val o = JSONObject()
            .put("nkey", (sig.hashCode().toLong() and 0xFFFFFFFFL).toString(16) + "_" + (sbn.postTime / 1000))
            .put("package", pkg).put("app", appLabel(ctx, pkg))
            .put("title", title).put("text", text.take(4000)).put("sub", sub)
            .put("category", n.category ?: "").put("posted", sbn.postTime)
        if (Build.VERSION.SDK_INT >= 26) n.channelId?.let { o.put("channel", it) }
        cs(ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)).takeIf { it.isNotEmpty() }?.let { o.put("conversation", it) }
        return o
    }

    fun add(ctx: Context, item: JSONObject): Boolean = synchronized(lock) {
        val f = file(ctx)
        f.appendText(item.toString() + "\n")
        if (f.length() > 4_000_000) {                                    // очень долго без связи — режем старое
            val all = f.readLines()
            f.writeText(all.takeLast(MAX_LOCAL).joinToString("\n", postfix = "\n"))
        }
        true
    }

    fun pendingCount(ctx: Context): Int = synchronized(lock) {
        val f = file(ctx)
        if (!f.exists()) 0 else f.readLines().count { it.isNotBlank() }
    }

    /** Отправить накопленное. true — всё ушло. */
    fun flush(ctx: Context): Boolean {
        val p = Prefs(ctx)
        if (!p.paired) return true
        if (flushing) { pendingFlush = true; return false }
        flushing = true
        try {
            while (true) {
                val batch: List<String> = synchronized(lock) {
                    val f = file(ctx)
                    if (!f.exists()) emptyList() else f.readLines().filter { it.isNotBlank() }.take(200)
                }
                if (batch.isEmpty()) return true
                val arr = JSONArray()
                batch.forEach { runCatching { arr.put(JSONObject(it)) } }
                Api.post(ctx, "/modules/mobile/api/feed", JSONObject().put("items", arr), readTimeoutMs = 30000)
                synchronized(lock) {
                    val f = file(ctx)
                    val rest = f.readLines().filter { it.isNotBlank() }.drop(batch.size)
                    if (rest.isEmpty()) f.delete() else f.writeText(rest.joinToString("\n", postfix = "\n"))
                }
                p.feedLastSent = System.currentTimeMillis()
            }
        } finally {
            flushing = false
            if (pendingFlush) { pendingFlush = false; scheduleFlush(ctx) }
        }
    }

    /** Через пару секунд — чтобы пачка уведомлений ушла одним запросом. */
    fun scheduleFlush(ctx: Context) {
        val app = ctx.applicationContext
        if (System.currentTimeMillis() - Prefs(app).lastStreamOk < 90_000) {
            Thread {
                runCatching { Thread.sleep(2500) }
                try { flush(app) } catch (e: Exception) { SyncWorker.now(app, "feed") }
            }.start()
        } else SyncWorker.now(app, "feed")
    }

    /** Приложения с иконкой на рабочем столе — для выбора в настройках. */
    fun launcherApps(ctx: Context): JSONArray {
        val pm = ctx.packageManager
        val i = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }.filter { it.first != ctx.packageName }.sortedBy { it.second.lowercase() }
        val out = JSONArray()
        list.forEach { (pkg, label) -> out.put(JSONObject().put("package", pkg).put("app", label)) }
        return out
    }
}
