package app.libre.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import app.libre.R
import app.libre.api.PlaylistsHelper
import app.libre.constants.IntentData
import app.libre.databinding.DialogCreatePlaylistBinding
import app.libre.extensions.addSpringTouchFeedback
import app.libre.extensions.toastFromMainDispatcher
import app.libre.ui.sheets.ExpandedBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class CreatePlaylistDialog : ExpandedBottomSheet(R.layout.dialog_create_playlist) {
    private var _binding: DialogCreatePlaylistBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogCreatePlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fun submitCreate() {
            val appContext = context?.applicationContext
            val listName = binding.playlistName.text?.toString()?.trim()
            if (!listName.isNullOrEmpty()) {
                binding.createNewPlaylist.setOnClickListener(null)
                lifecycleScope.launch {
                    val playlistId = withContext(Dispatchers.IO) {
                        runCatching {
                            PlaylistsHelper.createPlaylist(listName)
                        }.getOrNull()
                    }
                    appContext?.toastFromMainDispatcher(
                        if (playlistId != null) R.string.playlistCreated else R.string.unknown_error
                    )
                    playlistId?.let {
                        setFragmentResult(
                            CREATE_PLAYLIST_DIALOG_REQUEST_KEY,
                            android.os.Bundle().apply {
                                putBoolean(IntentData.playlistTask, true)
                                putString(IntentData.playlistId, playlistId)
                            }
                        )
                    }
                    dismiss()
                }
            } else {
                Toast.makeText(context, R.string.emptyPlaylistName, Toast.LENGTH_LONG).show()
            }
        }

        binding.createNewPlaylist.addSpringTouchFeedback()
        binding.cancelCreate.addSpringTouchFeedback()

        binding.createNewPlaylist.setOnClickListener {
            submitCreate()
        }

        binding.playlistName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                submitCreate()
                true
            } else {
                false
            }
        }

        binding.cancelCreate.setOnClickListener {
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val CREATE_PLAYLIST_DIALOG_REQUEST_KEY = "create_playlist_dialog_request_key"
    }
}
