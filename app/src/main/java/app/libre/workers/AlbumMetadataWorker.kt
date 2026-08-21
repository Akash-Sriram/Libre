package app.libre.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.libre.api.YtMusicApi
import app.libre.db.DatabaseHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AlbumMetadataWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        android.util.Log.i(WORK_NAME, "Starting AlbumMetadataWorker background sync")
        try {
            val dao = DatabaseHolder.Database.localPlaylistsDao()
            // Get all playlist items
            val allPlaylists = dao.getAll()
            
            var updatedCount = 0
            for (playlistRelation in allPlaylists) {
                for (item in playlistRelation.videos) {
                    if (item.albumName == null) {
                        // Fetch album name
                        val fetchedAlbum = YtMusicApi.fetchAlbumName(item.videoId)
                        if (fetchedAlbum != null) {
                            // Update item in DB
                            val updatedItem = item.copy(albumName = fetchedAlbum)
                            dao.updatePlaylistVideo(updatedItem)
                            updatedCount++
                        }
                    }
                }
            }
            android.util.Log.i(WORK_NAME, "Finished AlbumMetadataWorker sync. Updated $updatedCount items.")
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e(WORK_NAME, "Error in AlbumMetadataWorker", e)
            Result.retry()
        }
    }
    
    companion object {
        const val WORK_NAME = "AlbumMetadataSyncWorker"
    }
}
