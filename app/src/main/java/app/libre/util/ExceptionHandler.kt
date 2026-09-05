package app.libre.util

import app.libre.crash.CrashManager
import app.libre.helpers.PreferenceHelper

class ExceptionHandler(
    private val defaultExceptionHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, exc: Throwable) {
        // Save to legacy preference for backward compatibility
        try {
            PreferenceHelper.saveErrorLog(exc.stackTraceToString())
        } catch (_: Exception) {}

        // Delegate to modern CrashManager
        CrashManager.handleUncaughtException(thread, exc)
    }
}
