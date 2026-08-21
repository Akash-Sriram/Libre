package app.libre.helpers

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat
import app.libre.BuildConfig
import app.libre.api.RetrofitInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

object UpdateHelper {
    private const val GITHUB_API_URL = "https://api.github.com/repos/Akash-Sriram/Libre/releases/latest"

    fun checkForUpdateOnLaunch(activity: android.app.Activity) {
        val appContext = activity.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url(GITHUB_API_URL)
                    .header("User-Agent", "Libre Updater")
                    .build()

                val response = RetrofitInstance.httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@launch

                val jsonStr = response.body.string()
                val json = JSONObject(jsonStr)
                val tagName = json.getString("tag_name")
                val assets = json.getJSONArray("assets")
                
                if (assets.length() == 0) return@launch
                
                val downloadUrl = assets.getJSONObject(0).getString("browser_download_url")

                val currentVersionNum = BuildConfig.VERSION_NAME.filter { it.isDigit() }.toLongOrNull() ?: 0L
                val latestVersionNum = tagName.filter { it.isDigit() }.toLongOrNull() ?: 0L

                if (latestVersionNum <= currentVersionNum) return@launch

                withContext(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) return@withContext
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                        .setTitle("Update Available")
                        .setMessage("A new version of Libre ($tagName) is available. Would you like to update now?")
                        .setPositiveButton("Update") { _, _ ->
                            showDownloadProgressDialog(activity, downloadUrl, tagName)
                        }
                        .setNegativeButton("Later", null)
                        .show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun checkForUpdate(context: Context) {
        val appContext = context.applicationContext
        Toast.makeText(appContext, "Checking for updates...", Toast.LENGTH_SHORT).show()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url(GITHUB_API_URL)
                    .header("User-Agent", "Libre Updater")
                    .build()

                val response = RetrofitInstance.httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "Failed to check for updates. Rate limited?", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val jsonStr = response.body.string()
                val json = JSONObject(jsonStr)
                val tagName = json.getString("tag_name")
                val assets = json.getJSONArray("assets")
                
                if (assets.length() == 0) return@launch
                
                val downloadUrl = assets.getJSONObject(0).getString("browser_download_url")

                val currentVersionNum = BuildConfig.VERSION_NAME.filter { it.isDigit() }.toLongOrNull() ?: 0L
                val latestVersionNum = tagName.filter { it.isDigit() }.toLongOrNull() ?: 0L

                if (latestVersionNum <= currentVersionNum) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "App is up to date (${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    val activity = context as? android.app.Activity
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        showDownloadProgressDialog(activity, downloadUrl, tagName)
                    } else {
                        Toast.makeText(appContext, "Downloading update ($tagName)...", Toast.LENGTH_SHORT).show()
                        startDownload(appContext, downloadUrl, tagName)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Error checking for updates: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showDownloadProgressDialog(activity: android.app.Activity, url: String, tagName: String) {
        val context = activity.applicationContext
        val density = activity.resources.displayMetrics.density
        val padding = (24 * density).toInt()

        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val progressText = android.widget.TextView(activity).apply {
            text = "Preparing download..."
            textSize = 15f
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        container.addView(progressText)

        val progressBar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        container.addView(progressBar)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("Downloading Update")
            .setView(container)
            .setCancelable(false)
            .create()

        dialog.show()

        startDownload(
            context = context,
            url = url,
            tagName = tagName,
            onProgress = { bytesRead, totalBytes ->
                if (activity.isFinishing || activity.isDestroyed) return@startDownload
                progressBar.isIndeterminate = false
                val progressPercent = ((bytesRead * 100) / totalBytes).toInt()
                progressBar.progress = progressPercent
                
                val readMb = bytesRead.toDouble() / (1024 * 1024)
                val totalMb = totalBytes.toDouble() / (1024 * 1024)
                progressText.text = String.format(java.util.Locale.US, "%.2f MB / %.2f MB (%d%%)", readMb, totalMb, progressPercent)
            },
            onComplete = {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    dialog.dismiss()
                }
            },
            onError = { error ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    dialog.dismiss()
                    Toast.makeText(context, "Download failed: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun startDownload(
        context: Context,
        url: String,
        tagName: String,
        onProgress: ((Long, Long) -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null
    ) {
        val apkFileName = "Libre-$tagName.apk"
        val updatesDir = context.cacheDir.resolve("updates")
        if (!updatesDir.exists()) {
            updatesDir.mkdirs()
        }

        // Delete any stale APKs BEFORE downloading the new one.
        updatesDir.listFiles()?.forEach { it.delete() }

        val apkFile = updatesDir.resolve(apkFileName)

        val notificationId = 1001
        val notificationManager = androidx.core.app.NotificationManagerCompat.from(context)
        val channelId = app.libre.LibreTubeApp.DOWNLOAD_CHANNEL_NAME

        val notificationBuilder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setContentTitle("Downloading Update")
            .setContentText("Libre $tagName")
            .setSmallIcon(app.libre.R.drawable.ic_download)
            .setOngoing(true)
            .setProgress(100, 0, true)

        try {
            if (androidx.core.app.ActivityCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationManager.notify(notificationId, notificationBuilder.build())
            }
        } catch (e: SecurityException) {
            // Permission not granted on 13+
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (apkFile.exists()) {
                    apkFile.delete()
                }
                val request = okhttp3.Request.Builder().url(url).build()
                val response = RetrofitInstance.httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw java.io.IOException("Failed to download file: $response")
                }

                val body = response.body
                val contentLength = body.contentLength()
                val inputStream = body.byteStream()
                val outputStream = apkFile.outputStream()

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastProgressUpdate = 0L

                outputStream.use { out ->
                    inputStream.use { inp ->
                        while (inp.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (contentLength > 0) {
                                val progress = ((totalBytesRead * 100) / contentLength).toInt()
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastProgressUpdate > 100) { // Update faster (100ms) for smooth UI dialog progress
                                    lastProgressUpdate = currentTime
                                    withContext(Dispatchers.Main) {
                                        onProgress?.invoke(totalBytesRead, contentLength)
                                    }
                                    notificationBuilder.setProgress(100, progress, false)
                                    if (androidx.core.app.ActivityCompat.checkSelfPermission(
                                            context,
                                            android.Manifest.permission.POST_NOTIFICATIONS
                                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notificationManager.notify(notificationId, notificationBuilder.build())
                                    }
                                }
                            }
                        }
                    }
                }

                // Download completed, update notification and launch installer
                notificationBuilder
                    .setContentTitle("Download Complete")
                    .setContentText("Click to install Libre $tagName")
                    .setProgress(0, 0, false)
                    .setOngoing(false)

                val authority = "${context.packageName}.provider"
                val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, apkFile)

                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }

                val pendingIntent = android.app.PendingIntent.getActivity(
                    context,
                    0,
                    installIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                notificationBuilder.setContentIntent(pendingIntent)
                if (androidx.core.app.ActivityCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    notificationManager.notify(notificationId, notificationBuilder.build())
                }

                withContext(Dispatchers.Main) {
                    onComplete?.invoke()
                    Toast.makeText(context, "Update downloaded. Tap to install...", Toast.LENGTH_LONG).show()
                    try {
                        context.startActivity(installIntent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        try {
                            val browserIntent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/Akash-Sriram/Libre/releases/latest")
                            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                            context.startActivity(browserIntent)
                        } catch (ex: Exception) {
                            Toast.makeText(context, "Install failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                notificationManager.cancel(notificationId)
                withContext(Dispatchers.Main) {
                    onError?.invoke(e)
                    Toast.makeText(context, "Download failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun deleteApkFile(context: Context, fileName: String) {
        try {
            val file = context.cacheDir.resolve("updates").resolve(fileName)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cleanUpOldApks(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Clean public downloads directory (legacy)
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("Libre-") && file.name.endsWith(".apk")) {
                        file.delete()
                    }
                }
                // Clean private cache updates directory
                val updatesDir = context.cacheDir.resolve("updates")
                updatesDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("Libre-") && file.name.endsWith(".apk")) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
