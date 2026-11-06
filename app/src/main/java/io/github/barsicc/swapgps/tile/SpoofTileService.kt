package io.github.barsicc.swapgps.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.barsicc.swapgps.engine.MockEngine
import io.github.barsicc.swapgps.engine.SpoofState
import io.github.barsicc.swapgps.ui.MainActivity

/**
 * Плитка в шторке быстрых настроек.
 * Подмена идёт — касание сбрасывает её. Подмены нет — касание открывает приложение.
 */
class SpoofTileService : TileService() {

    companion object {
        fun requestUpdate(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, SpoofTileService::class.java))
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val status = SpoofState.status.value
        // Прерванную системой подмену тоже сбрасываем — на ней могла «залипнуть» точка
        if (status.active || status.interrupted) {
            MockEngine.reset(applicationContext)
            android.widget.Toast.makeText(this, "Подмена выключена, возвращаем реальное местоположение", android.widget.Toast.LENGTH_SHORT).show()
            refresh()
        } else {
            openApp()
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val status = SpoofState.status.value
        val active = status.active || status.interrupted
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (active) "Нажмите, чтобы выключить" else "Выключена"
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
