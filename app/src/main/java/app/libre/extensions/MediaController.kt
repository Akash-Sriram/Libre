package app.libre.extensions

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import app.libre.enums.PlayerCommand
import app.libre.services.AbstractPlayerService

@OptIn(UnstableApi::class)
fun MediaController.navigateVideo(videoId: String) {
    sendCustomCommand(
        AbstractPlayerService.runPlayerActionCommand,
        Bundle().apply { putString(PlayerCommand.PLAY_VIDEO_BY_ID.name, videoId) }
    )
}