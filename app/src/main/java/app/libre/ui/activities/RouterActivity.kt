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
        val uri = intent.getStringExtra(Intent.EXTRA_TEXT)?.toUri() ?: intent.data
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
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finishAndRemoveTask()
    }
}
