package app.libre.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {

    @Test
    fun testCrashReportSerialization() {
        val exception = IllegalStateException("Test exception message")
        val thread = Thread.currentThread()
        val report = CrashReport.fromThrowable(exception, thread, isFatal = true)

        assertEquals("java.lang.IllegalStateException", report.exceptionType)
        assertEquals("Test exception message", report.message)
        assertTrue(report.isFatal)
        assertTrue(report.stackTrace.contains("CrashReportTest"))

        val json = CrashReport.toJson(report)
        assertNotNull(json)

        val deserialized = CrashReport.fromJson(json)
        assertNotNull(deserialized)
        assertEquals(report.id, deserialized?.id)
        assertEquals(report.exceptionType, deserialized?.exceptionType)
        assertEquals(report.message, deserialized?.message)
        assertEquals(report.isFatal, deserialized?.isFatal)
    }

    @Test
    fun testFormattedMarkdown() {
        val exception = RuntimeException("Database error")
        val thread = Thread("WorkerThread")
        val report = CrashReport.fromThrowable(exception, thread, isFatal = false)

        val markdown = report.toFormattedMarkdown()
        assertTrue(markdown.contains("Crash Report (Non-Fatal)"))
        assertTrue(markdown.contains("WorkerThread"))
        assertTrue(markdown.contains("java.lang.RuntimeException"))
        assertTrue(markdown.contains("Database error"))
    }
}
