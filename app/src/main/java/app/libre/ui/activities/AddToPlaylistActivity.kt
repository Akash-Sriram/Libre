package app.libre.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import app.libre.R
import app.libre.api.MediaServiceRepository
import app.libre.api.obj.StreamItem
import app.libre.constants.IntentData
import app.libre.extensions.toastFromMainDispatcher
import app.libre.helpers.IntentHelper
import app.libre.helpers.PreferenceHelper
import app.libre.ui.base.BaseActivity
import app.libre.ui.dialogs.AddToPlaylistDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddToPlaylistActivity : BaseActivity() {
    override val isDialogActivity: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val videoId = intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
            IntentHelper.resolveType(it.toUri())
        }?.getStringExtra(IntentData.videoId)

        if (videoId == null) {
            finish()
            return
        }

        supportFragmentManager.setFragmentResultListener(
            AddToPlaylistDialog.ADD_TO_PLAYLIST_DIALOG_DISMISSED_KEY,
            this
        ) { _, _ -> finish() }

        lifecycleScope.launch(Dispatchers.IO) {
            val videoInfo = if (PreferenceHelper.getToken().isEmpty()) {
                try {
                    MediaServiceRepository.instance.getStreams(videoId).toStreamItem(videoId)
                } catch (e: Exception) {
                    toastFromMainDispatcher(R.string.unknown_error)
                    withContext(Dispatchers.Main) {
                        finish()
                    }
                    return@launch
                }
            } else {
                StreamItem(videoId)
            }

            withContext(Dispatchers.Main) {
                AddToPlaylistDialog().apply {
                    arguments = Bundle().apply { putParcelable(IntentData.videoInfo, videoInfo) }
                }.show(supportFragmentManager, null)
            }
        }
    }
}