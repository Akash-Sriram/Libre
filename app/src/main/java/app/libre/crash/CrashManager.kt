package app.libre.crash

import android.content.Context
import android.content.Intent
import android.util.Log
import app.libre.ui.activities.CrashActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

object CrashManager {
    private const val TAG = "CrashManager"
    private const val CRASH_DIR_NAME = "crash_logs"
    private const val MAX_REPORTS_KEPT = 10
    private const val OKHTTP_THREAD_NAME = "OkHttp Dispatcher"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun getCrashDir(): File {
        val dir = File(appContext.filesDir, CRASH_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun isIgnoredException(throwable: Throwable, thread: Thread): Boolean {
        // 1. Filter benign OkHttp background connection/header parsing threads
        if (thread.name == OKHTTP_THREAD_NAME) {
            return true
        }

        // 2. Filter Android 12+ (API 31+) background foreground service start rejections
        var current: Throwable? = throwable
        while (current != null) {
            if (current is IllegalStateException && current.javaClass.simpleName == "ForegroundServiceStartNotAllowedException") {
                return true
            }
            current = current.cause
        }

        return false
    }

    fun handleUncaughtException(thread: Thread, throwable: Throwable) {
        val isIgnored = isIgnoredException(throwable, thread)
        val report = CrashReport.fromThrowable(throwable, thread, isFatal = !isIgnored)

        try {
            saveReportSync(report)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash report", e)
        }

        if (isIgnored) {
            Log.w(TAG, "Intercepted non-fatal background platform exception on thread ${thread.name}: ${throwable.message}")
            return
        }

        // For fatal crashes, launch CrashActivity in a clean new task
        try {
            val intent = Intent(appContext, CrashActivity::class.java).apply {
                putExtra(CrashActivity.EXTRA_CRASH_REPORT_JSON, CrashReport.toJson(report))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            appContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CrashActivity", e)
        }

        // Kill the crashed process cleanly
        android.os.Process.killProcess(android.os.Process.myPid())
        System.exit(10)
    }

    private fun saveReportSync(report: CrashReport) {
        val dir = getCrashDir()
        val file = File(dir, "crash_${report.timestamp}_${report.id.take(8)}.json")
        file.writeText(CrashReport.toJson(report))
        trimOldReports(dir)
    }

    private fun trimOldReports(dir: File) {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return
        if (files.size > MAX_REPORTS_KEPT) {
            files.sortedByDescending { it.lastModified() }
                .drop(MAX_REPORTS_KEPT)
                .forEach { it.delete() }
        }
    }

    fun getAllReports(): List<CrashReport> {
        if (!::appContext.isInitialized) return emptyList()
        val dir = getCrashDir()
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }
            .mapNotNull { file ->
                runCatching { CrashReport.fromJson(file.readText()) }.getOrNull()
            }
    }

    fun getLatestReport(): CrashReport? {
        return getAllReports().firstOrNull()
    }

    fun clearAllReports() {
        if (!::appContext.isInitialized) return
        val dir = getCrashDir()
        dir.listFiles()?.forEach { it.delete() }
    }
}
