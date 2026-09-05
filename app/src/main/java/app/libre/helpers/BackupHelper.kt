package app.libre.helpers

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.libre.R
import app.libre.api.JsonHelper
import app.libre.constants.PreferenceKeys
import app.libre.db.DatabaseHolder.Database
import app.libre.extensions.TAG
import app.libre.extensions.toastFromMainDispatcher
import app.libre.obj.BackupFile
import app.libre.obj.PipedImportPlaylist
import app.libre.obj.PreferenceItem
import app.libre.ui.dialogs.ShareDialog
import app.libre.util.TextUtils
import app.libre.workers.AutoBackupWorker
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Backup and restore the preferences
 */
object BackupHelper {
    private const val AUTO_BACKUP_WORK_NAME = "AutoBackupService"

    /**
     * Enqueue the daily auto-backup background work
     */
    fun enqueueAutoBackupWork(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .build()

        val currentDate = java.util.Calendar.getInstance()
        val dueDate = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 2)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
        }
        if (dueDate.before(currentDate)) {
            dueDate.add(java.util.Calendar.HOUR_OF_DAY, 24)
        }
        val initialDelay = dueDate.timeInMillis - currentDate.timeInMillis

        val autoBackupWorker = PeriodicWorkRequestBuilder<AutoBackupWorker>(
            24,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                AUTO_BACKUP_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                autoBackupWorker
            )
    }

    /**
     * Get user-configured backup folder
     */
    fun getBackupFolder(context: Context): DocumentFile? {
        val uriString = PreferenceHelper.getString(PreferenceKeys.BACKUP_FOLDER_URI, "")
        if (uriString.isNullOrEmpty()) return null
        return try {
            DocumentFile.fromTreeUri(context, Uri.parse(uriString))
        } catch (e: Exception) {
            Log.e(TAG(), "Error getting backup folder: $e")
            null
        }
    }

    val EXPORTED_PREFERENCE_KEYS = setOf(
        PreferenceKeys.OFFLINE_SONGS_FOLDER_URI,
        PreferenceKeys.BACKUP_FOLDER_URI,
        PreferenceKeys.ENABLE_AUTO_BACKUP,
        PreferenceKeys.AUTO_MUSIC_AUDIO_MODE
    )

    /**
     * Build a complete backup file of all active categories
     */
    suspend fun getCompleteBackupFile(): BackupFile = withContext(Dispatchers.IO) {
        val backupFile = BackupFile()
        backupFile.playlistBookmarks = Database.playlistBookmarkDao().getAll()
        backupFile.localPlaylists = Database.localPlaylistsDao().getAll()
        backupFile.preferences = PreferenceHelper.settings.all
            .filter { (key, _) -> key in EXPORTED_PREFERENCE_KEYS }
            .map { (key, value) ->
                val jsonValue = when (value) {
                    is Number -> JsonPrimitive(value)
                    is Boolean -> JsonPrimitive(value)
                    is String -> JsonPrimitive(value)
                    else -> JsonNull
                }
                PreferenceItem(key, jsonValue)
            }
        backupFile
    }

    fun isLibreTubeBackupFile(name: String?): Boolean {
        if (name == null) return false
        val lower = name.lowercase()
        return lower.startsWith("libretube") && lower.contains("backup") && lower.endsWith(".json")
    }

    fun pruneBackupFolder(folder: DocumentFile, maxKeep: Int = 5) {
        val files = folder.listFiles().filter { isLibreTubeBackupFile(it.name) }
        if (files.size > maxKeep) {
            val sortedDesc = files.sortedWith(
                compareByDescending<DocumentFile> { it.lastModified() }
                    .thenByDescending { it.name.orEmpty() }
            )
            val toDelete = sortedDesc.drop(maxKeep)
            for (f in toDelete) {
                try {
                    val deleted = f.delete()
                    Log.d(TAG(), "Backup pruning: deleting ${f.name} -> result: $deleted")
                } catch (e: Exception) {
                    Log.e(TAG(), "Backup pruning: failed to delete ${f.name}", e)
                }
            }
        }
    }

    /**
     * Run daily automatic backup, maintaining only the last 5 backups
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun runAutoBackup(context: Context) = withContext(Dispatchers.IO) {
        if (!PreferenceHelper.getBoolean(PreferenceKeys.ENABLE_AUTO_BACKUP, true)) {
            Log.d(TAG(), "Auto-backup is disabled in preferences.")
            return@withContext
        }
        try {
            val backupFile = getCompleteBackupFile()
            val timestamp = TextUtils.getFileSafeTimeStampNow()
            val backupFileName = "libretube-backup-${timestamp}.json"

            val folder = getBackupFolder(context)
            if (folder != null && folder.exists() && folder.canWrite()) {
                // Save to user chosen SAF folder
                val documentFile = folder.createFile("application/json", backupFileName)
                if (documentFile != null) {
                    context.contentResolver.openOutputStream(documentFile.uri)?.use { outputStream ->
                        JsonHelper.json.encodeToStream(backupFile, outputStream)
                    }

                    // Unified pruning for all LibreTube backup variations
                    pruneBackupFolder(folder, maxKeep = 5)
                }
            } else {
                // Fallback to internal storage
                val autoBackupDir = context.filesDir.resolve("auto_backups")
                if (!autoBackupDir.exists()) {
                    autoBackupDir.mkdirs()
                }
                val file = autoBackupDir.resolve(backupFileName)
                file.outputStream().use { outputStream ->
                    JsonHelper.json.encodeToStream(backupFile, outputStream)
                }

                var backupFiles = autoBackupDir.listFiles { _, name ->
                    isLibreTubeBackupFile(name)
                }?.toList() ?: emptyList()
                if (backupFiles.none { it.absolutePath == file.absolutePath }) {
                    backupFiles = backupFiles + file
                }
                if (backupFiles.size > 5) {
                    val sortedDesc = backupFiles.sortedWith(
                        compareByDescending<File> { it.lastModified() }
                            .thenByDescending { it.name }
                    )
                    val toDelete = sortedDesc.drop(5)
                    for (f in toDelete) {
                        val deleted = f.delete()
                        Log.d(TAG(), "Auto-backup internal pruning: deleting ${f.name} -> result: $deleted")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG(), "Auto backup failed: $e")
        }
    }

    /**
     * Write a [BackupFile] containing the database content as well as the preferences
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun createAdvancedBackup(context: Context, uri: Uri, backupFile: BackupFile) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                JsonHelper.json.encodeToStream(backupFile, outputStream)
            }
            context.toastFromMainDispatcher(R.string.backup_creation_success)
        } catch (e: Exception) {
            Log.e(TAG(), "Error while writing backup: $e")
            context.toastFromMainDispatcher(R.string.backup_creation_failed)
        }
    }

    /**
     * Restore data from a [BackupFile]
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun restoreAdvancedBackup(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val backupFile = context.contentResolver.openInputStream(uri)?.use {
            JsonHelper.json.decodeFromStream<BackupFile>(it)
        } ?: return@withContext

        restoreBackupFile(context, backupFile)
    }

    /**
     * Internal implementation of restore
     */
    private suspend fun restoreBackupFile(context: Context, backupFile: BackupFile) {
        Database.searchHistoryDao().insertAll(backupFile.searchHistory.orEmpty())
        Database.playlistBookmarkDao().insertAll(backupFile.playlistBookmarks.orEmpty())

        val currentPlaylists = Database.localPlaylistsDao().getAll()
        backupFile.localPlaylists?.forEach { backupPlaylist ->
            val existing = currentPlaylists.find { it.playlist.name == backupPlaylist.playlist.name }
            if (existing != null) {
                // Merge videos to avoid duplicates in existing playlist
                val existingVideoIds = existing.videos.map { it.videoId }.toSet()
                backupPlaylist.videos.forEach { playlistItem ->
                    if (playlistItem.videoId !in existingVideoIds) {
                        playlistItem.playlistId = existing.playlist.id
                        Database.localPlaylistsDao().addPlaylistVideo(playlistItem.copy(id = 0))
                    }
                }
            } else {
                // Create a new playlist and add all videos
                val playlistId = Database.localPlaylistsDao().createPlaylist(backupPlaylist.playlist.copy(id = 0))
                backupPlaylist.videos.forEach { playlistItem ->
                    playlistItem.playlistId = playlistId.toInt()
                    Database.localPlaylistsDao().addPlaylistVideo(playlistItem.copy(id = 0))
                }
            }
        }

        restorePreferences(context, backupFile.preferences)
    }

    /**
     * Restore the shared preferences from a backup file
     */
    private fun restorePreferences(context: Context, preferences: List<PreferenceItem>?) {
        if (preferences == null) return

        PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true) {
            // Only restore active, known preference keys and discard dead upstream keys
            preferences.forEach { (key, jsonValue) ->
                if (key !in EXPORTED_PREFERENCE_KEYS) return@forEach
                val value = if (jsonValue.isString) {
                    jsonValue.content
                } else {
                    jsonValue.booleanOrNull
                        ?: jsonValue.intOrNull
                        ?: jsonValue.longOrNull
                        ?: jsonValue.floatOrNull
                }
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Float -> putFloat(key, value)
                    is Long -> putLong(key, value)
                    is Int -> putInt(key, value)
                    is String -> putString(key, value)
                }
            }
        }

        // Re-index local audio if an offline songs folder was restored
        val offlineUri = PreferenceHelper.getString(PreferenceKeys.OFFLINE_SONGS_FOLDER_URI, "")
        if (offlineUri.isNotEmpty()) {
            LocalAudioMatcher.startAutoScan(context)
        }

        // Re-schedule 24h auto backup if enabled and backup folder is present
        if (PreferenceHelper.getBoolean(PreferenceKeys.ENABLE_AUTO_BACKUP, true) && getBackupFolder(context) != null) {
            enqueueAutoBackupWork(context)
        }


    }
}
