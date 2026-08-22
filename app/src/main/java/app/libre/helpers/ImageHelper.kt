package app.libre.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.storage.StorageManager
import android.widget.ImageView
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.load
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.toBitmap
import app.libre.BuildConfig
import app.libre.extensions.toAndroidUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.nio.file.Path

object ImageHelper {
    private lateinit var imageLoader: ImageLoader

    private val Context.coilFile get() = cacheDir.resolve("coil")
    private const val HTTP_SCHEME = "http"

    /**
     * Initialize the image loader
     */
    fun initializeImageLoader(context: Context) {
        val httpClient = OkHttpClient().newBuilder()

        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            httpClient.addInterceptor(loggingInterceptor)
        }

        imageLoader = ImageLoader.Builder(context)
            .crossfade(true)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(httpClient.build())
                )
            }
            .memoryCache {
                coil3.memory.MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .apply {
                diskCachePolicy(CachePolicy.ENABLED)
                memoryCachePolicy(CachePolicy.ENABLED)

                val diskCache = DiskCache.Builder()
                    .directory(context.coilFile)
                    .maxSizeBytes(512L * 1024L * 1024L) // 512 MB dedicated local thumbnail disk cache
                    .build()
                diskCache(diskCache)
            }
            .build()

        warmUpCache()
    }

    /**
     * Pre-warms the DiskCache asynchronously on a background thread so UI requests never block.
     */
    private fun warmUpCache() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val snapshot = imageLoader.diskCache?.openSnapshot("__warmup__")
                snapshot?.close()
            } catch (e: Exception) {
                // Ignore warmup errors
            }
        }
    }

    /**
     * Checks if the corresponding image for the given key (e.g. a url) is cached.
     */
    private fun isCached(key: String): Boolean {
        val diskCache = imageLoader.diskCache ?: return false
        val cacheSnapshot = runCatching { diskCache.openSnapshot(key) }.getOrNull()
        val isCacheHit = cacheSnapshot?.data?.toFile()?.exists()
        cacheSnapshot?.close()

        return isCacheHit ?: false
    }

    /**
     * load an image from a url into an imageView
     */
    fun loadImage(url: String?, target: ImageView, whiteBackground: Boolean = false) {
        if (url.isNullOrEmpty()) return

        // clear image to avoid loading issues at fast scrolling
        target.setImageDrawable(null)

        target.load(url) {
            listener(
                onSuccess = { _, _ ->
                    // set the background to white for transparent images
                    if (whiteBackground) target.setBackgroundColor(Color.WHITE)
                }
            )
        }
    }

    suspend fun downloadImage(context: Context, url: String, path: Path) {
        val bitmap = getImage(context, url) ?: return
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(path.toAndroidUri())?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 25, it)
            }
        }
    }

    suspend fun getImage(context: Context, url: String?): Bitmap? {
        return getImage(context, url?.toUri())
    }

    suspend fun getImage(context: Context, url: Uri?): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .build()

        return imageLoader.execute(request).image?.toBitmap()
    }

    /**
     * Get a squared bitmap with the same width and height from a bitmap
     * @param bitmap The bitmap to resize
     */
    fun getSquareBitmap(bitmap: Bitmap): Bitmap {
        val newSize = minOf(bitmap.width, bitmap.height)
        return Bitmap.createBitmap(
            bitmap,
            (bitmap.width - newSize) / 2,
            (bitmap.height - newSize) / 2,
            newSize,
            newSize
        )
    }
}
