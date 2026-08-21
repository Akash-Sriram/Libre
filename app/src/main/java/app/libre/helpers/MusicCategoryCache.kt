package app.libre.helpers

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// Extension property for DataStore
private val Context.musicCategoryDataStore by preferencesDataStore(name = "music_category_cache")

/**
 * Lightweight persistent cache mapping videoId → isMusicCategory.
 * Now backed by Preferences DataStore for thread-safe asynchronous I/O.
 * 
 * On first play: PlayerFragment detects category from stream info and populates the cache.
 * On 2nd+ play:  NavigationHelper reads the cache and opens AudioPlayerFragment directly,
 *                completely skipping the video player initialization.
 */
object MusicCategoryCache {

    private const val MAX_ENTRIES = 50_000

    // In-memory map for instant access within a session
    private val memCache = ConcurrentHashMap<String, Boolean>(256)
    private var initialized = false
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Load DataStore into memory async on app launch. */
    fun initializeAsync(context: Context) {
        if (initialized) return
        scope.launch {
            ensureInitialized(context)
        }
    }

    /** Ensure DataStore is loaded into memory before performing background scans. */
    suspend fun ensureInitialized(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        try {
            val prefs = appContext.musicCategoryDataStore.data.first()
            prefs.asMap().forEach { (k, v) ->
                if (v is Boolean) memCache[k.name] = v
            }
            initialized = true
        } catch (e: Exception) {
            // Ignore parsing errors on first run
        }
    }

    /**
     * Returns true if we already know this video is a music video,
     * false if we know it is NOT music, null if we have never seen it.
     */
    fun get(context: Context, videoId: String): Boolean? {
        // Fallback: if accessed before async init completes, just check what's in memCache.
        // It might be a miss on the very first quick tap of a cold launch, but subsequent plays will hit.
        return memCache[videoId]
    }

    /**
     * Store whether a video is a music-category video.
     * Call this after stream info is loaded in PlayerFragment or via Scanner.
     */
    fun put(context: Context, videoId: String, isMusic: Boolean) {
        memCache[videoId] = isMusic
        val appContext = context.applicationContext
        
        scope.launch {
            try {
                appContext.musicCategoryDataStore.edit { prefs ->
                    // Evict oldest entries when cache is full
                    if (prefs.asMap().size >= MAX_ENTRIES) {
                        val toRemove = prefs.asMap().keys.take(MAX_ENTRIES / 4)
                        toRemove.forEach { prefs.remove(it) }
                    }
                    prefs[booleanPreferencesKey(videoId)] = isMusic
                }
            } catch (e: Exception) {
                // Ignore write errors
            }
        }
    }
}
