package app.libre.ui.models

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import app.libre.api.obj.StreamItem
import app.libre.extensions.updateIfChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface PlaybackStatus {
    object Idle : PlaybackStatus
    object Buffering : PlaybackStatus
    object Playing : PlaybackStatus
    object Paused : PlaybackStatus
    object Ended : PlaybackStatus
    data class Error(val message: String?) : PlaybackStatus
}

sealed interface PlayerExpansionState {
    object Hidden : PlayerExpansionState
    data class Collapsed(val progress: Float = 1.0f) : PlayerExpansionState
    data class Transitioning(val progress: Float) : PlayerExpansionState
    object Expanded : PlayerExpansionState
}

enum class MediaMode {
    AUDIO,
    VIDEO
}

data class PlayerUiState(
    val currentTrack: StreamItem? = null,
    val playbackStatus: PlaybackStatus = PlaybackStatus.Idle,
    val mediaMode: MediaMode = MediaMode.AUDIO,
    val expansionState: PlayerExpansionState = PlayerExpansionState.Expanded,
    val isMiniPlayer: Boolean = false,
    val isFullscreen: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L
)

class CommonPlayerViewModel : ViewModel() {
    val isMiniPlayerVisible = MutableLiveData(false)
    val isFullscreen = MutableLiveData(false)
    var maxSheetHeightPx = 0

    val sheetExpand = MutableLiveData<Boolean?>()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    fun updatePlaybackStatus(status: PlaybackStatus) {
        _uiState.value = _uiState.value.copy(playbackStatus = status)
    }

    fun updateCurrentTrack(track: StreamItem?, isAudioOnly: Boolean) {
        _uiState.value = _uiState.value.copy(
            currentTrack = track,
            mediaMode = if (isAudioOnly) MediaMode.AUDIO else MediaMode.VIDEO
        )
    }

    fun updateExpansionState(state: PlayerExpansionState) {
        val isMini = state is PlayerExpansionState.Collapsed
        _uiState.value = _uiState.value.copy(
            expansionState = state,
            isMiniPlayer = isMini
        )
        isMiniPlayerVisible.updateIfChanged(isMini)
    }

    fun updateFullscreen(fullscreen: Boolean) {
        _uiState.value = _uiState.value.copy(isFullscreen = fullscreen)
        isFullscreen.updateIfChanged(fullscreen)
    }

    fun updateProgress(currentMs: Long, totalMs: Long) {
        _uiState.value = _uiState.value.copy(currentPositionMs = currentMs, durationMs = totalMs)
    }

    fun setSheetExpand(state: Boolean?) {
        sheetExpand.updateIfChanged(state)
    }
}
