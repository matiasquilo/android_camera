package com.exe.vintagecameraapp

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Tile de ajustes rápidos: un toque en el panel de notificaciones abre Dusk al instante.
 * Hay que agregarlo a mano: deslizar el panel > lápiz de edición > arrastrar "Dusk" a los tiles activos.
 */
class CameraTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            label = getString(R.string.app_name)
            state = Tile.STATE_INACTIVE      // es un lanzador, no un interruptor
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_FROM_TILE, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
