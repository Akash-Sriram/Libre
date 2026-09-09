package app.libre.ui.sheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import app.libre.R
import app.libre.api.YtMusicApi
import app.libre.databinding.BottomSheetTrackRecognitionBinding
import app.libre.recognition.MusicRecognitionService
import app.libre.recognition.RecognitionResult
import app.libre.recognition.RecognitionStatus
import app.libre.ui.activities.MainActivity
import app.libre.helpers.NavigationHelper
import app.libre.parcelable.PlayerData
import coil3.load
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
                        crossfade(true)
                    }
                }

                binding.recognitionActionsLayout.isVisible = true

                // Local offline file detected!
                if (!track.localFilePath.isNullOrBlank()) {
                    binding.btnPlayLocal.isVisible = true
                    binding.btnPlayLocal.setOnClickListener {
                        dismiss()
                        (activity as? MainActivity)?.let { act ->
                            NavigationHelper.navigateVideo(
                                context = act,
                                playerData = PlayerData(
                                    videoId = track.localFilePath,
                                    isOffline = true
                                ),
                                audioOnlyPlayerRequested = true
                            )
                        }
                    }
                } else {
                    binding.btnPlayLocal.isVisible = false
                }

                binding.btnPlayStream.setOnClickListener {
                    dismiss()
                    lifecycleScope.launch(Dispatchers.IO) {
                        val master = YtMusicApi.resolveStudioMaster(track.title, track.artist)
                        withContext(Dispatchers.Main) {
                            (activity as? MainActivity)?.let { act ->
                                if (master != null) {
                                    NavigationHelper.navigateVideo(
                                        context = act,
                                        playerData = PlayerData(
                                            videoId = master.url.orEmpty(),
                                            isOffline = false,
                                            source = "ytm"
                                        ),
                                        audioOnlyPlayerRequested = true
                                    )
                                } else {
                                    act.setQuery("${track.title} ${track.artist}", true)
                                }
                            }
                        }
                    }
                }

                binding.btnSearchTrack.setOnClickListener {
                    dismiss()
                    (activity as? MainActivity)?.setQuery("${track.title} ${track.artist}", true)
                }
            }
            is RecognitionStatus.NoMatch -> {
                binding.recognitionProgress.isVisible = false
                binding.recognitionStatusText.text = status.message
                binding.btnTryAgain.isVisible = true
            }
            is RecognitionStatus.Error -> {
                binding.recognitionProgress.isVisible = false
                binding.recognitionStatusText.text = status.message
                binding.btnTryAgain.isVisible = true
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
