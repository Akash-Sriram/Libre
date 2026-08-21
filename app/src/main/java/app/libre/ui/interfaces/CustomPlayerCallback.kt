package app.libre.ui.interfaces

interface CustomPlayerCallback {
    fun toggleFullscreen()
    fun getVideoId(): String
    fun isVideoShort(): Boolean
    fun isVideoLive(): Boolean
}
