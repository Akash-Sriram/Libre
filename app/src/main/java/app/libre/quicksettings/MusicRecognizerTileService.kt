package app.libre.quicksettings

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.libre.R

/**
 * Quick Settings tile — shows a WindowManager system overlay over the current app.
 * Uses TYPE_APPLICATION_OVERLAY so no Activity is launched and the current app
 * (Instagram, browser, music player) NEVER loses focus or pauses audio.
 */
class MusicRecognizerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            icon = Icon.createWithResource(this@MusicRecognizerTileService, R.drawable.ic_mic)
            label = getString(R.string.identify_music)
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()

        // Start the foreground service — it creates a WindowManager overlay (no Activity).
        // RecognitionLaunchActivity is a transparent trampoline that just starts the
        // service and immediately finishes, collapsing the notification shade.
        val trampolineIntent = Intent(
            this,
            app.libre.recognition.RecognitionLaunchActivity::class.java
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, trampolineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(trampolineIntent)
        }
    }
}
