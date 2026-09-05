package app.libre.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.libre.R
import app.libre.crash.CrashManager
import app.libre.crash.CrashReport
import app.libre.databinding.ActivityCrashBinding
import app.libre.helpers.ClipboardHelper

class CrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCrashBinding
    private var crashReport: CrashReport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val reportJson = intent.getStringExtra(EXTRA_CRASH_REPORT_JSON)
        crashReport = if (!reportJson.isNullOrBlank()) {
            CrashReport.fromJson(reportJson)
        } else {
            CrashManager.getLatestReport()
        }

        setupViews()
        setupListeners()
    }

    private fun setupViews() {
        val report = crashReport
        if (report == null) {
            binding.crashSubtitle.text = getString(R.string.no_crash_logs)
            binding.deviceInfoText.text = ""
            binding.stackTraceText.text = ""
            return
        }

        binding.crashSubtitle.text = report.message?.takeIf { it.isNotBlank() } ?: report.exceptionType

        val deviceInfo = buildString {
            appendLine("App Version: ${report.appVersionName} (${report.appVersionCode}) [Debug: ${report.isDebug}]")
            appendLine("Device: ${report.manufacturer} ${report.model}")
            appendLine("Android OS: ${report.androidVersion} (API ${report.sdkInt})")
            appendLine("Thread: ${report.threadName}")
            appendLine("Time: ${report.formattedDate}")
        }
        binding.deviceInfoText.text = deviceInfo
        binding.stackTraceText.text = report.stackTrace
    }

    private fun setupListeners() {
        binding.restartBtn.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)
            finish()
        }

        binding.copyBtn.setOnClickListener {
            val textToCopy = crashReport?.toFormattedMarkdown() ?: binding.stackTraceText.text.toString()
            if (textToCopy.isNotBlank()) {
                ClipboardHelper.save(this, text = textToCopy, notify = true)
            }
        }

        binding.shareBtn.setOnClickListener {
            val textToShare = crashReport?.toFormattedMarkdown() ?: binding.stackTraceText.text.toString()
            if (textToShare.isNotBlank()) {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, textToShare)
                    type = "text/plain"
                }
                startActivity(Intent.createChooser(sendIntent, getString(R.string.share)))
            }
        }
    }

    companion object {
        const val EXTRA_CRASH_REPORT_JSON = "extra_crash_report_json"
    }
}
