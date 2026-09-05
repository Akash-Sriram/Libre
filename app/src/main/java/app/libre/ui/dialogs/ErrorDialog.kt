package app.libre.ui.dialogs

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import app.libre.R
import app.libre.crash.CrashManager
import app.libre.helpers.ClipboardHelper
import app.libre.helpers.PreferenceHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ErrorDialog : DialogFragment() {
    @SuppressLint("PrivateResource")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val latestReport = CrashManager.getLatestReport()
        val errorLog = latestReport?.toFormattedMarkdown() ?: PreferenceHelper.getErrorLog()
        // reset legacy error log
        PreferenceHelper.saveErrorLog("")

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.error_occurred)
            .setMessage(errorLog)
            .setNegativeButton(R.string.okay, null)
            .setNeutralButton(R.string.share) { _, _ ->
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, errorLog)
                    type = "text/plain"
                }
                startActivity(Intent.createChooser(sendIntent, getString(R.string.share)))
            }
            .setPositiveButton(R.string.copy) { _, _ ->
                ClipboardHelper.save(requireContext(), text = errorLog, notify = true)
            }
            .show()
    }
}
