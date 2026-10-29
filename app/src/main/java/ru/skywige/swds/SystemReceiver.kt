package ru.skywige.swds

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Загрузка телефона, обновление приложения, зарядка → переподключение и отчёт серверу. */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        if (!p.paired) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                PushService.start(ctx)
                SyncWorker.schedule(ctx)
                SyncWorker.now(ctx, "all")
            }
            Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> SyncWorker.now(ctx, "report")
        }
    }
}
