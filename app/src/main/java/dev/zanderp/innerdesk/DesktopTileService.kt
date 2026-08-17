package dev.zanderp.innerdesk

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class DesktopTileService : TileService() {
    override fun onStartListening() {
        applyState()
    }

    override fun onClick() {
        unlockAndRun {
            DesktopControls.toggle(this)
            applyState()
        }
    }

    private fun applyState() {
        val tile = qsTile ?: return
        val running = AppSession.dexRunning
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = getString(if (running) R.string.tile_on else R.string.tile_off)
        tile.updateTile()
    }
}
