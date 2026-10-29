package ru.skywige.swds

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Фоновая синхронизация (WorkManager):
 *  • пропущенные уведомления, если постоянное соединение выключено или оборвалось;
 *  • отчёт о заряде/Wi-Fi/местоположении;
 *  • синхронизация выбранных папок телефона → SWDS;
 *  • файлы из «На телефон» → Загрузки/SWDS;
 *  • обновление виджетов.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val p = Prefs(ctx)
        if (!p.paired) return Result.success()
        val what = inputData.getString("what") ?: "all"
        var failed = false
        fun step(name: String, block: () -> Unit) {
            try { block() } catch (e: ApiException) {
                if (e.status == 401) { p.unpair(); throw e }
                android.util.Log.w("SWDS", "sync $name: ${e.message}"); failed = true
            } catch (e: Exception) { android.util.Log.w("SWDS", "sync $name: $e"); failed = true }
        }
        try {
            val streamFresh = System.currentTimeMillis() - p.lastStreamOk < 90_000
            if (what in setOf("all", "pending") && !streamFresh) step("pending") { Events.catchUp(ctx) }
            if (what in setOf("all", "report")) step("report") { Device.send(ctx) }
            if (what in setOf("all", "queue", "report") && OfflineQueue.count(ctx) > 0) step("queue") {
                OfflineQueue.flush(ctx)
                if (OfflineQueue.count(ctx) > 0) failed = true
            }
            if (what in setOf("all", "feed", "queue")) step("feed") { FeedStore.flush(ctx) }
            if (what == "all") step("today") {
                val r = Api.get(ctx, "/modules/mobile/api/widget/today", 20000)
                p.todayCache = r.put("fetched", System.currentTimeMillis()).toString()
            }
            if (what in setOf("all", "folders")) step("folders") { syncFolders(ctx) }
            if (what in setOf("all", "outbox") && p.receiveFiles) step("outbox") { outbox(ctx) }
            if (what in setOf("all", "widgets")) step("widgets") { SwdsWidget.updateAllSync(ctx) }
            if (what == "all") PushService.start(ctx)
        } catch (e: ApiException) {
            return Result.success()
        }
        val maxTries = if (what == "queue" || what == "feed") 30 else 2      // очередь ждёт связь долго
        return if (failed && runAttemptCount < maxTries && what != "all") Result.retry() else Result.success()
    }

    private fun outbox(ctx: Context) {
        val r = Api.get(ctx, "/modules/mobile/api/outbox")
        val items = r.optJSONArray("items") ?: return
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val name = it.optString("name")
            val size = it.optLong("size", -1)
            val uri = Api.download(ctx, it.optString("url")) { input, _, mime, len ->
                Files.saveToDownloads(ctx, name, mime, input, if (len > 0) len else size) { done, total ->
                    Notifier.progress(ctx, Notifier.ID_DOWNLOAD, "⬇ $name", Files.human(done) + " из " + Files.human(total), done, total)
                }
            }
            Notifier.cancel(ctx, Notifier.ID_DOWNLOAD)
            Api.post(ctx, "/modules/mobile/api/outbox/ack", JSONObject().put("rel", it.optString("rel")))
            val mime = Files.mimeOf(name)
            val open = android.app.PendingIntent.getActivity(ctx, 2000 + i, Files.viewIntent(uri, mime),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            Notifier.simple(ctx, 2000 + (name.hashCode() and 0xFFF), "📥 Файл с компьютера", "$name · ${Files.human(size)} → Загрузки/SWDS",
                Notifier.CH_INFO, open)
        }
        SwdsWidget.refreshKind(ctx, "files")
    }

    /** Правила: [{id, uri, name, dest, path, since, enabled}] */
    private fun syncFolders(ctx: Context) {
        val p = Prefs(ctx)
        val rules = p.syncRules
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (!rule.optBoolean("enabled", true)) continue
            val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(rule.optString("uri"))) ?: continue
            if (!tree.canRead()) continue
            val id = rule.optString("id")
            val state = p.syncedState(id)
            val since = rule.optLong("since", 0)
            val files = ArrayList<Pair<DocumentFile, String>>()
            collect(tree, "", files, 0)
            var sent = 0
            for ((f, rel) in files) {
                val key = rel + "|" + f.length() + "|" + f.lastModified()
                if (state.has(key) || f.lastModified() < since) continue
                if (f.name?.startsWith(".") == true || f.length() == 0L) continue
                val sub = rule.optString("path").trim('/')
                val relDir = rel.substringBeforeLast('/', "")
                val dest = rule.optString("dest", "shared")
                val path = if (dest == "shared") listOf(sub.ifEmpty { "С телефона/" + rule.optString("name") }, relDir)
                    .filter { it.isNotEmpty() }.joinToString("/") else relDir
                val fields = mutableMapOf("dest" to dest, "mtime" to f.lastModified().toString())
                if (path.isNotEmpty() && dest == "shared") fields["path"] = path
                val name = f.name ?: "file"
                Api.upload(ctx, "/modules/mobile/api/files/upload", name, f.type ?: Files.mimeOf(name), f.length(),
                    { ctx.contentResolver.openInputStream(f.uri) ?: throw ApiException("Нет доступа к $name") }, fields) { done, total ->
                    Notifier.progress(ctx, Notifier.ID_UPLOAD, "⬆ ${rule.optString("name")}: $name",
                        Files.human(done) + " из " + Files.human(total), done, total)
                }
                state.put(key, System.currentTimeMillis())
                p.setSyncedState(id, state)
                sent++
            }
            Notifier.cancel(ctx, Notifier.ID_UPLOAD)
            if (sent > 0) {
                rule.put("last_sync", System.currentTimeMillis()).put("last_count", sent)
                rules.put(i, rule); p.syncRules = rules
                Notifier.simple(ctx, 3100 + i, "📤 Синхронизация «${rule.optString("name")}»", "Отправлено файлов: $sent",
                    Notifier.CH_FILES, timeoutMs = 60_000)
            }
        }
    }

    private fun collect(dir: DocumentFile, prefix: String, out: MutableList<Pair<DocumentFile, String>>, depth: Int) {
        if (depth > 4 || out.size > 5000) return
        for (f in dir.listFiles()) {
            val n = f.name ?: continue
            if (n.startsWith(".")) continue
            if (f.isDirectory) collect(f, "$prefix$n/", out, depth + 1)
            else if (f.isFile) out.add(f to "$prefix$n")
        }
    }

    companion object {
        private val NET = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(NET).setInputData(workDataOf("what" to "all")).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("swds_periodic", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun now(ctx: Context, what: String) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(NET)
                .setInputData(workDataOf("what" to what)).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("swds_now_$what", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        fun cancelAll(ctx: Context) = WorkManager.getInstance(ctx).cancelAllWork()

        /** Новое правило синхронизации папки. */
        fun newRule(uri: String, name: String, dest: String, path: String, onlyNew: Boolean): JSONObject =
            JSONObject().put("id", java.util.UUID.randomUUID().toString().take(8)).put("uri", uri).put("name", name)
                .put("dest", dest).put("path", path).put("enabled", true)
                .put("since", if (onlyNew) System.currentTimeMillis() else 0L)
    }
}
