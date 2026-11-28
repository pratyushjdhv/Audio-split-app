package com.audiosplit

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The Quick Settings tile: start, pause and resume from the shade.
 *
 * Pause and resume act on the live session directly. Starting needs the system's consent
 * dialog every time, so it opens [StartMirrorActivity], which shows the dialog over whatever
 * is playing and closes itself.
 */
class MirrorTileService : TileService() {

    private var listening: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = MainScope().also { scope ->
            scope.launch { MirrorState.status.collect(::render) }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val status = MirrorState.status.value
        when {
            status.sharing -> MirrorService.pause(this)
            status.running -> MirrorService.resume(this)
            isLocked -> unlockAndRun(::startSession)
            else -> startSession()
        }
    }

    private fun startSession() {
        val intent = Intent(this, StartMirrorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            // The only overload below Android 14. Lint flags it regardless of the version
            // check; it only throws when called on 14+, which this branch never is.
            @SuppressLint("StartActivityAndCollapseDeprecated")
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(status: MirrorStatus) {
        val tile = qsTile ?: return
        val state = when {
            status.sharing -> "Sharing"
            status.running -> "Paused"
            else -> "Off"
        }
        tile.state = if (status.sharing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (status.sharing) status.targetLabel ?: state else state
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = state
        tile.updateTile()
    }

    companion object {
        /** Asks the system to add the tile, which it confirms with the user. Android 13+. */
        fun requestAdd(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, MirrorTileService::class.java),
                context.getString(R.string.tile_label),
                Icon.createWithResource(context, R.drawable.ic_headphones),
                context.mainExecutor,
            ) { result ->
                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                    Toast.makeText(context, "The tile is already in Quick Settings", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }
}
