package com.snipsnap.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.snipsnap.shell.Copy
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * The quick-settings tile (M1): the snip that doesn't need the app open.
 *
 * Armed, the tile is lit and a tap is SNIP — the session is already a
 * foreground service, so the start is an ordinary dispatch to a running
 * one. Idle, a tap opens the app on the shelf, where ARM TAPE and ARM
 * INSIDE live: arming has to happen from a visible Activity anyway (a
 * foreground service may not start from the background on Android 15,
 * and INSIDE needs the projection consent dialog, which only an Activity
 * can show), so the tile hands over rather than pretending — see
 * `docs/CAPTURE_RESEARCH_2026.md`, "Quick Settings tile / background
 * start: explicitly unresolved".
 *
 * State follows [MicSessionService.armed] while the tile is visible; the
 * collector is cancelled the moment it isn't.
 */
class SnipTileService : TileService() {

    private var watching: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watching?.cancel()
        watching = MainScope().launch {
            MicSessionService.armed.collect { render(it) }
        }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (MicSessionService.armed.value) {
            MicSessionService.snip(this)
            return
        }
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(open)
        }
    }

    private fun render(armed: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (armed) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = Copy.TILE_LABEL
        tile.subtitle = if (armed) Copy.TILE_ARMED else Copy.TILE_IDLE
        tile.updateTile()
    }
}
