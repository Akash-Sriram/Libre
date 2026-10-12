package app.libre.recognition

import android.app.Notification
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import app.libre.helpers.PreferenceHelper
import app.libre.ui.activities.MainActivity

/**
 * Listens for song recognition notifications from Google Sound Search and Google Pixel Now Playing,
 * extracts the song title and artist, and automatically routes them to Libre for playback.
 */
class SongRecognitionNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        // Check if feature is enabled in user settings
        val isEnabled = PreferenceHelper.getBoolean(PREF_CAPTURE_GOOGLE_NOTIFICATIONS, true)
        if (!isEnabled) return

        val pkg = sbn.packageName.orEmpty()
        val isTargetApp = pkg == PKG_GOOGLE_SEARCH ||
                pkg == PKG_GOOGLE_NOW_PLAYING ||
                pkg == PKG_GOOGLE_INTELLIGENCE

        if (!isTargetApp) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()

        if (title.isBlank() || text.isBlank()) return

        // Filter out non-song notifications from Google (e.g. weather, search suggestions, reminders)
        if (!isLikelySongNotification(pkg, notification, title, text)) return

        // Debounce to prevent duplicate triggers
        val songKey = "$title::$text"
        val now = System.currentTimeMillis()
        if (songKey == lastCapturedSong && (now - lastCapturedTime) < DEBOUNCE_MS) {
            return
        }

        lastCapturedSong = songKey
        lastCapturedTime = now

        Log.i(TAG, "Captured song recognition from $pkg: title='$title', artist='$text'")

        // Launch MainActivity with autoplay recognition payload
        val playIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_AUTOPLAY_RECOGNITION, true)
            putExtra(RecognitionForegroundService.EXTRA_RECOGNITION_RESULT_TITLE, title)
            putExtra(RecognitionForegroundService.EXTRA_RECOGNITION_RESULT_ARTIST, text)
        }
        startActivity(playIntent)
    }

    private fun isLikelySongNotification(
        pkg: String,
        notification: Notification,
        title: String,
        text: String
    ): Boolean {
        // Pixel Now Playing channel is always dedicated to music recognition
        if (pkg == PKG_GOOGLE_NOW_PLAYING || pkg == PKG_GOOGLE_INTELLIGENCE) {
            return true
        }

        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification.channelId?.lowercase().orEmpty()
        } else ""

        // Explicit channel IDs for Google Sound Search / Assistant music
        if (channelId.contains("sound_search") || channelId.contains("music") || channelId.contains("song")) {
            return true
        }

        // Exclude system search progress/status notifications
        val lowerTitle = title.lowercase()
        if (lowerTitle.startsWith("listening") || lowerTitle.startsWith("identifying") || lowerTitle.startsWith("searching")) {
            return false
        }

        // Subtext checks
        val subText = notification.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.lowercase().orEmpty()
        if (subText.contains("sound search") || subText.contains("music") || subText.contains("now playing")) {
            return true
        }

        // Sound Search often includes audio transport category
        if (notification.category == Notification.CATEGORY_TRANSPORT || notification.category == Notification.CATEGORY_RECOMMENDATION) {
            return true
        }

        // If title and artist are clean strings, exclude common Google alert keywords
        val lowerText = text.lowercase()
        val nonMusicKeywords = listOf("weather", "temperature", "forecast", "traffic", "flight", "reminder", "commute", "calendar", "alarm")
        if (nonMusicKeywords.none { lowerTitle.contains(it) || lowerText.contains(it) }) {
            return true
        }

        return false
    }

    companion object {
        private const val TAG = "SongNotifListener"
        const val PREF_CAPTURE_GOOGLE_NOTIFICATIONS = "capture_google_song_notifications"
        private const val PKG_GOOGLE_SEARCH = "com.google.android.googlequicksearchbox"
        private const val PKG_GOOGLE_NOW_PLAYING = "com.google.intelligence.sense"
        private const val PKG_GOOGLE_INTELLIGENCE = "com.google.android.as"
        private const val DEBOUNCE_MS = 10_000L

        private var lastCapturedSong: String? = null
        private var lastCapturedTime: Long = 0L
    }
}
