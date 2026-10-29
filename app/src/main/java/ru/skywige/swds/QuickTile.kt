package ru.skywige.swds

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.json.JSONObject

/** Плитка в шторке: выбранная в настройках команда или список быстрых действий. */
class QuickTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val t = qsTile ?: return
        val cmd = Prefs(this).tileCommand
        t.label = cmd?.optString("title")?.ifEmpty { null } ?: "SWDS"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (cmd == null) "Быстрые действия" else "Команда SWDS"
        t.state = if (Prefs(this).paired) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        t.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val cmd = Prefs(this).tileCommand
        if (cmd != null && cmd.optString("input").isEmpty() && cmd.optString("command").isNotEmpty()) {
            Events.toast(this, "⏳ " + cmd.optString("title"))
            Actions.run(this, cmd.optString("command"), cmd.optJSONObject("args") ?: JSONObject())
            return
        }
        val i = Intent(this, InputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (cmd != null) {
            i.putExtra("mode", "input").putExtra("spec", JSONObject().put("command", cmd.optString("command"))
                .put("arg", cmd.optString("input")).put("args", cmd.optJSONObject("args") ?: JSONObject())
                .put("placeholder", cmd.optString("placeholder")).put("title", cmd.optString("title")).toString())
        } else i.putExtra("mode", "quick")
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
