package app.libre.crash

import android.os.Build
import app.libre.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Serializable
data class CrashReport(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val formattedDate: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
    val exceptionType: String,
    val message: String?,
    val stackTrace: String,
    val threadName: String,
    val isFatal: Boolean,
    val manufacturer: String = runCatching { Build.MANUFACTURER }.getOrNull() ?: "Unknown",
    val model: String = runCatching { Build.MODEL }.getOrNull() ?: "Unknown",
    val androidVersion: String = runCatching { Build.VERSION.RELEASE }.getOrNull() ?: "Unknown",
    val sdkInt: Int = runCatching { Build.VERSION.SDK_INT }.getOrNull() ?: 0,
    val appVersionName: String = runCatching { BuildConfig.VERSION_NAME }.getOrNull() ?: "Unknown",
    val appVersionCode: Long = runCatching { BuildConfig.VERSION_CODE.toLong() }.getOrNull() ?: 0L,
    val isDebug: Boolean = runCatching { BuildConfig.DEBUG }.getOrNull() ?: false
) {
    fun toFormattedMarkdown(): String {
        return buildString {
            appendLine("### ⚠️ Crash Report (${if (isFatal) "Fatal" else "Non-Fatal"})")
            appendLine("- **Timestamp**: $formattedDate")
            appendLine("- **App Version**: $appVersionName ($appVersionCode) [Debug: $isDebug]")
            appendLine("- **Device**: $manufacturer $model (Android $androidVersion, API $sdkInt)")
            appendLine("- **Thread**: $threadName")
            appendLine("- **Exception**: `$exceptionType`")
            if (!message.isNullOrBlank()) {
                appendLine("- **Message**: $message")
            }
            appendLine()
            appendLine("```")
            appendLine(stackTrace.trim())
            appendLine("```")
        }
    }

    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

        fun fromThrowable(throwable: Throwable, thread: Thread, isFatal: Boolean = true): CrashReport {
            return CrashReport(
                exceptionType = throwable.javaClass.name,
                message = throwable.localizedMessage ?: throwable.message,
                stackTrace = throwable.stackTraceToString(),
                threadName = thread.name,
                isFatal = isFatal
            )
        }

        fun fromJson(jsonStr: String): CrashReport? {
            return runCatching { json.decodeFromString<CrashReport>(jsonStr) }.getOrNull()
        }

        fun toJson(report: CrashReport): String {
            return json.encodeToString(report)
        }
    }
}
