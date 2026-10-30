package ru.skywige.swds

import android.content.Context
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
            if (what in setOf("all", "folders")) step("folders") {
                if (!FolderSync.runAll(ctx, manual = inputData.getBoolean("manual", false))) failed = true
            }
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

    companion object {
        private val NET = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(NET).setInputData(workDataOf("what" to "all")).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("swds_periodic", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun now(ctx: Context, what: String, manual: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(NET)
                .setInputData(workDataOf("what" to what, "manual" to manual)).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("swds_now_$what", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        fun cancelAll(ctx: Context) = WorkManager.getInstance(ctx).cancelAllWork()

        /** Новое правило синхронизации папки. */
        fun newRule(uri: String, name: String, dest: String, path: String, onlyNew: Boolean, mode: String = FolderSync.UPLOAD,
                    wifiOnly: Boolean = false, chargingOnly: Boolean = false): JSONObject =
            JSONObject().put("id", java.util.UUID.randomUUID().toString().take(8)).put("uri", uri).put("name", name)
                .put("dest", dest).put("path", path).put("enabled", true).put("mode", mode)
                .put("wifi_only", wifiOnly).put("charging_only", chargingOnly)
                .put("since", if (onlyNew && mode == FolderSync.UPLOAD) System.currentTimeMillis() else 0L)
    }
}
