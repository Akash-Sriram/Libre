package app.libre.ui.sheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import app.libre.R
import app.libre.api.MediaServiceRepository
import app.libre.api.YtMusicApi
import app.libre.api.obj.StreamItem
import app.libre.databinding.BottomSheetTrackRecognitionBinding
import app.libre.helpers.LocalAudioMatcher
import app.libre.recognition.MusicRecognitionService
import app.libre.recognition.RecognitionResult
import app.libre.recognition.RecognitionStatus
import app.libre.ui.activities.MainActivity
import app.libre.helpers.NavigationHelper
import app.libre.parcelable.PlayerData
import coil3.load
import coil3.request.allowHardware
import coil3.request.crossfade
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrackRecognitionBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetTrackRecognitionBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetTrackRecognitionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnTryAgain.setOnClickListener {
            startRecognition()
        }

        binding.btnGoogleSoundSearch.setOnClickListener {
            launchGoogleSoundSearch()
        }

        val preTitle = arguments?.getString(ARG_TITLE)
        val preArtist = arguments?.getString(ARG_ARTIST)

        if (!preTitle.isNullOrBlank() && !preArtist.isNullOrBlank()) {
            val preCover = arguments?.getString(ARG_COVER)
            val preAlbum = arguments?.getString(ARG_ALBUM)
            val preLocal = arguments?.getString(ARG_LOCAL_PATH)

            val preResult = RecognitionResult(
                trackId = "",
                title = preTitle,
                artist = preArtist,
                album = preAlbum,
                coverArtUrl = preCover,
                coverArtHqUrl = preCover,
                genre = null,
                releaseDate = null,
                label = null,
                lyrics = null,
                shazamUrl = null,
                appleMusicUrl = null,
                isrc = null,
                localFilePath = preLocal
            )
            renderStatus(RecognitionStatus.Success(preResult))
        } else {
            startRecognition()
        }
    }

    private fun startRecognition() {
        binding.recognitionProgress.isVisible = true
        binding.recognitionCover.setImageResource(R.drawable.ic_mic)
        binding.recognitionStatusText.text = getString(R.string.identifying_music)
        binding.recognitionTitle.isVisible = false
        binding.recognitionArtist.isVisible = false
        binding.recognitionActionsLayout.isVisible = false
        binding.btnTryAgain.isVisible = false
        binding.btnGoogleSoundSearch.isVisible = false

        lifecycleScope.launch {
            val status = MusicRecognitionService.recognize(requireContext())
            renderStatus(status)
        }
    }

    private fun renderStatus(status: RecognitionStatus) {
        when (status) {
            is RecognitionStatus.Ready, is RecognitionStatus.Listening -> {
                binding.recognitionProgress.isVisible = true
                binding.recognitionStatusText.text = getString(R.string.identifying_music)
            }
            is RecognitionStatus.Processing -> {
                binding.recognitionProgress.isVisible = true
                binding.recognitionStatusText.text = getString(R.string.recognition_processing)
            }
            is RecognitionStatus.Success -> {
                binding.recognitionProgress.isVisible = false
                val track = status.result
                binding.recognitionStatusText.text = getString(R.string.recognition_track_identified)
                binding.recognitionTitle.text = track.title
                binding.recognitionTitle.isVisible = true

                val subText = buildString {
                    append(track.artist)
                    if (!track.album.isNullOrBlank()) {
                        append(" • ")
                        append(track.album)
                    }
                }
                binding.recognitionArtist.text = subText
                binding.recognitionArtist.isVisible = true

                val art = track.coverArtHqUrl ?: track.coverArtUrl
                if (!art.isNullOrBlank()) {
                    binding.recognitionCover.load(art) {
                        allowHardware(false)
                        crossfade(true)
                    }
                }

                binding.recognitionActionsLayout.isVisible = true

                // Local offline file or user playlist track detected!
                if (!track.localFilePath.isNullOrBlank()) {
                    binding.btnPlayLocal.isVisible = true
                    binding.btnPlayLocal.text = getString(R.string.play_local_file)
                    binding.btnPlayLocal.setOnClickListener {
                        val act = activity as? MainActivity ?: return@setOnClickListener
                        dismissAllowingStateLoss()
                        NavigationHelper.navigateVideo(
                            context = act,
                            playerData = PlayerData(
                                videoId = track.localFilePath,
                                isOffline = true
                            ),
                            audioOnlyPlayerRequested = true
                        )
                    }
                } else if (!track.playlistVideoId.isNullOrBlank()) {
                    binding.btnPlayLocal.isVisible = true
                    binding.btnPlayLocal.text = "Play from Local Playlist"
                    binding.btnPlayLocal.setOnClickListener {
                        val act = activity as? MainActivity ?: return@setOnClickListener
                        val isOff = track.playlistVideoId.startsWith("/") || track.playlistVideoId.contains("\\")
                        dismissAllowingStateLoss()
                        NavigationHelper.navigateVideo(
                            context = act,
                            playerData = PlayerData(
                                videoId = track.playlistVideoId,
                                isOffline = isOff
                            ),
                            audioOnlyPlayerRequested = true
                        )
                    }
                } else {
                    binding.btnPlayLocal.isVisible = false
                }

                binding.btnPlayStream.setOnClickListener {
                    val act = activity as? MainActivity ?: return@setOnClickListener
                    binding.btnPlayStream.isEnabled = false
                    binding.recognitionProgress.isVisible = true
                    binding.recognitionStatusText.text = getString(R.string.recognition_processing)

                    act.lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            // 1. If we have a local offline file, play it directly
                            val localMatch = track.localFilePath ?: LocalAudioMatcher.getLocalPathByTitle(track.title, track.artist)
                            if (!localMatch.isNullOrBlank()) {
                                withContext(Dispatchers.Main) {
                                    dismissAllowingStateLoss()
                                    NavigationHelper.navigateVideo(
                                        context = act,
                                        playerData = PlayerData(
                                            videoId = localMatch,
                                            isOffline = true
                                        ),
                                        audioOnlyPlayerRequested = true
                                    )
                                }
                                return@launch
                            }

                            // 2. If it exists in the user's local playlist, play it directly
                            val playlistMatch = track.playlistVideoId ?: LocalAudioMatcher.findPlaylistVideoId(track.title, track.artist)
                            if (!playlistMatch.isNullOrBlank()) {
                                withContext(Dispatchers.Main) {
                                    val isOff = playlistMatch.startsWith("/") || playlistMatch.contains("\\")
                                    dismissAllowingStateLoss()
                                    NavigationHelper.navigateVideo(
                                        context = act,
                                        playerData = PlayerData(
                                            videoId = playlistMatch,
                                            isOffline = isOff
                                        ),
                                        audioOnlyPlayerRequested = true
                                    )
                                }
                                return@launch
                            }

                            // 3. Try YouTube Music Studio Master
                            val master = YtMusicApi.resolveStudioMaster(track.title, track.artist)
                            if (master != null) {
                                withContext(Dispatchers.Main) {
                                    dismissAllowingStateLoss()
                                    NavigationHelper.navigateVideo(
                                        context = act,
                                        playerData = PlayerData(
                                            videoId = master.url.orEmpty(),
                                            isOffline = false,
                                            source = "ytm"
                                        ),
                                        audioOnlyPlayerRequested = true
                                    )
                                }
                                return@launch
                            }

                            // 4. Robust YouTube Search fallback: finds top matching video/song and plays immediately
                            try {
                                val search = MediaServiceRepository.instance.getSearchResults("${track.title} ${track.artist}", "all")
                                val firstStream = search.items.filterIsInstance<StreamItem>().firstOrNull()
                                if (firstStream != null) {
                                    withContext(Dispatchers.Main) {
                                        dismissAllowingStateLoss()
                                        NavigationHelper.navigateVideo(
                                            context = act,
                                            playerData = PlayerData(
                                                videoId = firstStream.url,
                                                isOffline = false
                                            ),
                                            audioOnlyPlayerRequested = true
                                        )
                                    }
                                    return@launch
                                }
                            } catch (e: Exception) {
                                android.util.Log.w(TAG, "Search fallback failed: ${e.message}")
                            }

                            // 5. Final fallback: open search view
                            withContext(Dispatchers.Main) {
                                dismissAllowingStateLoss()
                                act.setQuery("${track.title} ${track.artist}", true)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e(TAG, "Error resolving track for playback", e)
                            withContext(Dispatchers.Main) {
                                dismissAllowingStateLoss()
                                act.setQuery("${track.title} ${track.artist}", true)
                            }
                        }
                    }
                }

                binding.btnSearchTrack.setOnClickListener {
                    val act = activity as? MainActivity ?: return@setOnClickListener
                    dismissAllowingStateLoss()
                    act.setQuery("${track.title} ${track.artist}", true)
                }
            }
            is RecognitionStatus.NoMatch -> {
                binding.recognitionProgress.isVisible = false
                binding.recognitionStatusText.text = status.message
                binding.btnTryAgain.isVisible = true
                binding.btnGoogleSoundSearch.isVisible = true
            }
            is RecognitionStatus.Error -> {
                binding.recognitionProgress.isVisible = false
                binding.recognitionStatusText.text = status.message
                binding.btnTryAgain.isVisible = true
                binding.btnGoogleSoundSearch.isVisible = true
            }
        }
    }

    private fun launchGoogleSoundSearch() {
        val googleIntent = android.content.Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH").apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            android.widget.Toast.makeText(requireContext(), R.string.google_sound_search_hint, android.widget.Toast.LENGTH_LONG).show()
            startActivity(googleIntent)
            dismissAllowingStateLoss()
        } catch (_: Exception) {
            try {
                android.widget.Toast.makeText(requireContext(), R.string.google_sound_search_hint, android.widget.Toast.LENGTH_LONG).show()
                val webIntent = android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH).apply {
                    putExtra(android.app.SearchManager.QUERY, "what's this song")
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(webIntent)
                dismissAllowingStateLoss()
            } catch (_: Exception) {
                android.widget.Toast.makeText(requireContext(), R.string.google_app_not_found, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "TrackRecognitionBottomSheet"
        const val ARG_TITLE = "arg_title"
        const val ARG_ARTIST = "arg_artist"
        const val ARG_COVER = "arg_cover"
        const val ARG_ALBUM = "arg_album"
        const val ARG_LOCAL_PATH = "arg_local_path"

        fun newInstance(
            title: String? = null,
            artist: String? = null,
            cover: String? = null,
            album: String? = null,
            localPath: String? = null
        ): TrackRecognitionBottomSheet {
            return TrackRecognitionBottomSheet().apply {
                if (!title.isNullOrBlank()) {
                    arguments = Bundle().apply {
                        putString(ARG_TITLE, title)
                        putString(ARG_ARTIST, artist)
                        putString(ARG_COVER, cover)
                        putString(ARG_ALBUM, album)
                        putString(ARG_LOCAL_PATH, localPath)
                    }
                }
            }
        }
    }
}
