package app.libre.recognition

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.libre.R
import app.libre.databinding.ActivityRecognitionOverlayBinding
import app.libre.ui.activities.MainActivity
import coil3.load
import coil3.request.allowHardware
import coil3.request.crossfade
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Floating translucent Activity that shows the Shazam-style music recognition overlay
 * directly on top of whatever app/website is currently visible — without redirecting
 * into Libre's main interface.
 *
 * UX modeled on Google Sound Search / Shazam floating UI:
 *  • Dimmed scrim background (tap outside to dismiss)
 *  • Floating rounded card anchored to the bottom
 *  • Pulsing ripple rings while listening
 *  • Track info + action buttons on success
 *  • "Try Again" / dismiss on no-match or error
 *
 * Launched by [MusicRecognizerTileService] via startActivityAndCollapse().
 *
 * Ported / adapted for Libre from Metrolist.
 */
class RecognitionOverlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecognitionOverlayBinding
    private var statusJob: Job? = null
    private var recognitionJob: Job? = null

    // Ripple animators
    private var outerRippleAnimator: ObjectAnimator? = null
    private var innerRippleAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make window transparent so the scrim in our layout shows underlying app.
        // FLAG_NOT_FOCUSABLE: prevents stealing window focus from Instagram/Chrome,
        // which would otherwise pause Reels video playback.
        window.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        binding = ActivityRecognitionOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDismissOnScrim()
        setupCloseButton()
        startRecognitionFlow()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRipple()
        statusJob?.cancel()
        recognitionJob?.cancel()
        MusicRecognitionService.reset()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupDismissOnScrim() {
        binding.overlayRoot.setOnClickListener { finish() }
        // Prevent clicks on the card itself from bubbling to the scrim
        binding.overlayCard.setOnClickListener { /* consume */ }
    }

    private fun setupCloseButton() {
        binding.btnCloseOverlay.setOnClickListener { finish() }
    }

    // ── Recognition flow ──────────────────────────────────────────────────────

    private fun startRecognitionFlow() {
        if (!hasRecordPermission()) {
            openPermissionFlow()
            return
        }

        showListeningState()
        MusicRecognitionService.reset()

        statusJob = lifecycleScope.launch {
            MusicRecognitionService.recognitionStatus.collect { status ->
                handleStatus(status)
            }
        }

        recognitionJob = lifecycleScope.launch {
            val result = MusicRecognitionService.recognize(this@RecognitionOverlayActivity)
            if (result is RecognitionStatus.Error &&
                MusicRecognitionService.recognitionStatus.value !is RecognitionStatus.Error
            ) {
                handleStatus(result)
            }
        }
    }

    private fun handleStatus(status: RecognitionStatus) {
        when (status) {
            is RecognitionStatus.Ready -> { /* initial — do nothing */ }
            is RecognitionStatus.Listening -> showListeningState()
            is RecognitionStatus.Processing -> showProcessingState()
            is RecognitionStatus.Success -> showSuccess(status.result)
            is RecognitionStatus.NoMatch -> showNoMatch()
            is RecognitionStatus.Error -> showError(status.message)
        }
    }

    // ── UI States ─────────────────────────────────────────────────────────────

    private fun showListeningState() {
        binding.recognitionStatusText.text = getString(R.string.recognition_listening)
        binding.recognitionProgress.visibility = View.VISIBLE
        binding.recognitionCover.setImageResource(R.drawable.ic_mic)
        binding.recognitionCover.imageTintList = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(this, android.R.color.transparent)
        )
        binding.recognitionTitle.visibility = View.GONE
        binding.recognitionArtist.visibility = View.GONE
        binding.recognitionActionsLayout.visibility = View.GONE
        binding.btnTryAgain.visibility = View.GONE
        binding.btnGrantOverlay.visibility = View.GONE
        startRipple()
    }

    private fun showProcessingState() {
        binding.recognitionStatusText.text = getString(R.string.recognition_processing)
        stopRipple()
        binding.recognitionProgress.visibility = View.VISIBLE
    }

    private fun showSuccess(result: RecognitionResult) {
        stopRipple()
        binding.recognitionProgress.visibility = View.GONE
        binding.recognitionStatusText.text = ""
        binding.recognitionTitle.apply {
            text = result.title
            visibility = View.VISIBLE
        }
        binding.recognitionArtist.apply {
            text = buildString {
                append(result.artist)
                if (!result.album.isNullOrBlank()) append(" • ${result.album}")
            }
            visibility = View.VISIBLE
        }
        binding.recognitionActionsLayout.visibility = View.VISIBLE
        binding.btnTryAgain.visibility = View.GONE

        // Load album art into the cover view
        val artUrl = result.coverArtHqUrl ?: result.coverArtUrl
        if (!artUrl.isNullOrBlank()) {
            binding.recognitionCover.imageTintList = null
            binding.recognitionCover.load(artUrl) {
                allowHardware(false)
                crossfade(true)
            }
        }

        if (!result.localFilePath.isNullOrBlank()) {
            binding.btnPlayLocal.visibility = View.VISIBLE
            binding.btnPlayLocal.setOnClickListener { openInLibre(result, playLocal = true) }
        }
        binding.btnPlayStream.setOnClickListener { openInLibre(result, playLocal = false) }
        binding.btnSearchTrack.setOnClickListener { openInLibreSearch(result) }
    }

    private fun showNoMatch() {
        stopRipple()
        binding.recognitionProgress.visibility = View.GONE
        binding.recognitionCover.setImageResource(R.drawable.ic_mic)
        binding.recognitionCover.imageTintList = null
        binding.recognitionStatusText.text = getString(R.string.recognition_no_match)
        binding.recognitionActionsLayout.visibility = View.GONE
        binding.btnTryAgain.visibility = View.VISIBLE
        binding.btnTryAgain.setOnClickListener { restartRecognition() }
    }

    private fun showError(message: String) {
        stopRipple()
        binding.recognitionProgress.visibility = View.GONE
        binding.recognitionCover.setImageResource(R.drawable.ic_mic)
        binding.recognitionCover.imageTintList = null
        binding.recognitionStatusText.text = message.ifBlank { getString(R.string.recognition_error) }
        binding.recognitionActionsLayout.visibility = View.GONE
        binding.btnTryAgain.visibility = View.VISIBLE
        binding.btnTryAgain.setOnClickListener { restartRecognition() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun restartRecognition() {
        statusJob?.cancel()
        recognitionJob?.cancel()
        startRecognitionFlow()
    }

    private fun openInLibre(result: RecognitionResult, playLocal: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(RecognitionForegroundService.EXTRA_RECOGNITION_RESULT_TITLE, result.title)
            putExtra(RecognitionForegroundService.EXTRA_RECOGNITION_RESULT_ARTIST, result.artist)
            putExtra(RecognitionForegroundService.EXTRA_RECOGNITION_RESULT_COVER,
                result.coverArtHqUrl ?: result.coverArtUrl)
            putExtra(RecognitionForegroundService.EXTRA_RECOGNITION_RESULT_ALBUM, result.album)
            if (playLocal) {
                putExtra(RecognitionForegroundService.EXTRA_RECOGNITION_RESULT_LOCAL_PATH,
                    result.localFilePath)
            }
            putExtra(MainActivity.EXTRA_TRIGGER_RECOGNITION, true)
        }
        startActivity(intent)
        finish()
    }

    private fun openInLibreSearch(result: RecognitionResult) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(app.libre.constants.IntentData.query, "${result.title} ${result.artist}")
        }
        startActivity(intent)
        finish()
    }

    private fun hasRecordPermission(): Boolean {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun openPermissionFlow() {
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_CODE_RECORD_AUDIO)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startRecognitionFlow()
            } else {
                showError("Microphone permission required to recognize music")
            }
        }
    }

    // ── Ripple animation ──────────────────────────────────────────────────────

    private fun startRipple() {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.8f, 1.15f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.8f, 1.15f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.0f, 0.45f, 0.0f)

        outerRippleAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.rippleRingOuter, scaleX, scaleY, alpha
        ).apply {
            duration = 1600
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        val scaleX2 = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.85f, 1.1f)
        val scaleY2 = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.85f, 1.1f)
        val alpha2 = PropertyValuesHolder.ofFloat(View.ALPHA, 0.0f, 0.55f, 0.0f)

        innerRippleAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.rippleRingInner, scaleX2, scaleY2, alpha2
        ).apply {
            duration = 1600
            startDelay = 300
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopRipple() {
        outerRippleAnimator?.cancel()
        innerRippleAnimator?.cancel()
        binding.rippleRingOuter.alpha = 0f
        binding.rippleRingInner.alpha = 0f
    }

    companion object {
        private const val REQUEST_CODE_RECORD_AUDIO = 1001
    }
}
