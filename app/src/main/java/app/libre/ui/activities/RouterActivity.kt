package app.libre.ui.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import app.libre.extensions.TAG
import app.libre.helpers.IntentHelper
import app.libre.helpers.NavigationHelper
import app.libre.ui.base.BaseActivity

class RouterActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val uri = if (!text.isNullOrBlank()) {
            val urlRegex = "(https?://\\S+)".toRegex()
            val match = urlRegex.find(text)
            if (match != null) {
                match.value.toUri()
            } else {
                Uri.Builder().scheme("libre").authority("search").appendQueryParameter("q", text.trim()).build()
            }
        } else {
            intent.data
        }

        if (uri != null) {
            // Start processing the given text, if available. Otherwise use the link shared as text
            // to the app.
            handleSendText(uri)
        } else {
            // start app as normal if unknown action, shouldn't be reachable
            NavigationHelper.restartMainActivity(this)
        }
    }

    private fun handleSendText(uri: Uri) {
        Log.i(TAG(), uri.toString())

        val intent = packageManager.getLaunchIntentForPackage(packageName)!!.let { intent ->
            IntentHelper.resolveType(intent, uri)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finishAndRemoveTask()
    }
}
