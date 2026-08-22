package app.libre

import android.app.Application
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import app.libre.helpers.ImageHelper
import app.libre.helpers.NewPipeExtractorInstance
import app.libre.helpers.PreferenceHelper
import androidx.core.content.pm.ShortcutManagerCompat
import app.libre.util.ExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LibreTubeApp : Application(), androidx.work.Configuration.Provider {

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.ERROR)
            .build()

    override fun onCreate() {
        super.onCreate()
        instance = this

        /**
         * Initialize the needed notification channels for DownloadService and BackgroundMode
         */
        initializeNotificationChannels()

        /**
         * Initialize the [PreferenceHelper]
         */
        PreferenceHelper.initialize(applicationContext)
        PreferenceHelper.migrate()

        /**
         * Set the api and the auth api url
         */
        ImageHelper.initializeImageLoader(this)



        /**
         * Initialize the auto backup worker in the background after UI startup
         */
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(5000L)
            app.libre.helpers.BackupHelper.enqueueAutoBackupWork(applicationContext)
        }

        /**
         * Asynchronously load the music category DataStore into memory for instant access
         */
        app.libre.helpers.MusicCategoryCache.initializeAsync(applicationContext)

        /**
         * Handler for uncaught exceptions
         */
        val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        val exceptionHandler = ExceptionHandler(defaultExceptionHandler)
        Thread.setDefaultUncaughtExceptionHandler(exceptionHandler)

        // Remove all dynamic app shortcuts
        ShortcutManagerCompat.removeAllDynamicShortcuts(this)

        NewPipeExtractorInstance.init()

        // Schedule periodic background sync for album metadata (runs once a day, deferred to not block startup)
        CoroutineScope(Dispatchers.IO).launch {
            delay(8000L)
            try {
                val albumWorkRequest = androidx.work.PeriodicWorkRequestBuilder<app.libre.workers.AlbumMetadataWorker>(
                    1, java.util.concurrent.TimeUnit.DAYS
                ).setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                ).build()
                androidx.work.WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                    app.libre.workers.AlbumMetadataWorker.WORK_NAME,
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    albumWorkRequest
                )
            } catch (e: Exception) {
                // Ignore WorkManager initialization errors during shutdown
            }
        }
    }

    /**
     * Initializes the required notification channels for the app.
     */
    private fun initializeNotificationChannels() {
        val downloadChannel = NotificationChannelCompat.Builder(
            PLAYLIST_DOWNLOAD_ENQUEUE_CHANNEL_NAME,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(getString(R.string.download_playlist))
            .setDescription(getString(R.string.enqueue_playlist_description))
            .build()
        val playlistDownloadEnqueueChannel = NotificationChannelCompat.Builder(
            DOWNLOAD_CHANNEL_NAME,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(getString(R.string.download_channel_name))
            .setDescription(getString(R.string.download_channel_description))
            .build()
        val playerChannel = NotificationChannelCompat.Builder(
            PLAYER_CHANNEL_NAME,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(getString(R.string.player_channel_name))
            .setDescription(getString(R.string.player_channel_description))
            .build()
        val pushChannel = NotificationChannelCompat.Builder(
            PUSH_CHANNEL_NAME,
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        )
            .setName(getString(R.string.push_channel_name))
            .setDescription(getString(R.string.push_channel_description))
            .build()

        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.createNotificationChannelsCompat(
            listOf(
                downloadChannel,
                playlistDownloadEnqueueChannel,
                pushChannel,
                playerChannel
            )
        )
    }

    companion object {
        lateinit var instance: LibreTubeApp

        const val DOWNLOAD_CHANNEL_NAME = "download_service"
        const val PLAYLIST_DOWNLOAD_ENQUEUE_CHANNEL_NAME = "playlist_download_enqueue"
        const val PLAYER_CHANNEL_NAME = "player_mode"
        const val PUSH_CHANNEL_NAME = "notification_worker"
    }
}
