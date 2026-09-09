package app.libre.recognition

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import app.libre.R
import app.libre.constants.IntentData
import app.libre.databinding.ActivityRecognitionOverlayBinding
import app.libre.ui.activities.MainActivity
import coil3.load
import coil3.request.allowHardware
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * System Overlay (`TYPE_APPLICATION_OVERLAY`) floating card for Shazam music recognition.
 *
 * Rendered directly onto the WindowManager without launching an Activity.
 * Because no Activity state transition occurs, Instagram Reels, YouTube, Chrome,
 * and social apps remain 100% in RESUMED state and NEVER pause video/audio playback!
 */
class FloatingRecognitionOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var overlayBinding: ActivityRecognitionOverlayBinding? = null
    private var isShowing = false
    private var statusJob: Job? = null
    private var recognitionJob: Job? = null

    private var outerRippleAnimator: ObjectAnimator? = null
    private var innerRippleAnimator: ObjectAnimator? = null

    fun show() {
        if (!Settings.canDrawOverlays(context)) {
            // If overlay permission not granted, request permission
            openOverlayPermissionSetting()
            return
        }

        if (isShowing) {
            restartRecognition()
            return
        }

        val themedContext = android.view.ContextThemeWrapper(context, R.style.BaseTheme)
        val binding = ActivityRecognitionOverlayBinding.inflate(LayoutInflater.from(themedContext))
        overlayBinding = binding

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        binding.overlayRoot.setOnClickListener { dismiss() }
        binding.overlayCard.setOnClickListener { /* consume click */ }
        binding.btnCloseOverlay.setOnClickListener { dismiss() }

        try {
            windowManager.addView(binding.root, params)
            isShowing = true
            startRecognitionFlow()
        } catch (e: Exception) {
            e.printStackTrace()
            dismiss()
        }
    }

    fun dismiss() {
        if (!isShowing) return
        stopRipple()
        statusJob?.cancel()
        recognitionJob?.cancel()
        scope.cancel()

        overlayBinding?.let { binding ->
            try {
                windowManager.removeView(binding.root)
            } catch (_: Exception) {}
        }
        overlayBinding = null
        isShowing = false
        MusicRecognitionService.reset()

        try {
            val serviceIntent = Intent(context, RecognitionForegroundService::class.java)
            context.stopService(serviceIntent)
        } catch (_: Exception) {}
    }

    private fun startRecognitionFlow() {
        showListeningState()
        MusicRecognitionService.reset()

        statusJob = scope.launch {
            MusicRecognitionService.recognitionStatus.collectLatest { status ->
                handleStatus(status)
            }
        }

        recognitionJob = scope.launch {
            val result = MusicRecognitionService.recognize(context)
            if (result is RecognitionStatus.Error &&
                MusicRecognitionService.recognitionStatus.value !is RecognitionStatus.Error
            ) {
                handleStatus(result)
            }
        }
    }

    private fun restartRecognition() {
        statusJob?.cancel()
        recognitionJob?.cancel()
        startRecognitionFlow()
    }

    private fun handleStatus(status: RecognitionStatus) {
        val binding = overlayBinding ?: return
        when (status) {
            is RecognitionStatus.Ready -> { /* initial */ }
            is RecognitionStatus.Listening -> showListeningState()
            is RecognitionStatus.Processing -> showProcessingState()
            is RecognitionStatus.Success -> showSuccess(status.result)
            is RecognitionStatus.NoMatch -> showNoMatch()
            is RecognitionStatus.Error -> showError(status.message)
        }
    }

    private fun showListeningState() {
        val binding = overlayBinding ?: return
        binding.recognitionStatusText.text = context.getString(R.string.recognition_listening)
        binding.recognitionProgress.visibility = View.VISIBLE
        binding.recognitionCover.setImageResource(R.drawable.ic_mic)
        binding.recognitionTitle.visibility = View.GONE
        binding.recognitionArtist.visibility = View.GONE
        binding.recognitionActionsLayout.visibility = View.GONE
        binding.btnTryAgain.visibility = View.GONE
        startRipple()
    }

    private fun showProcessingState() {
        val binding = overlayBinding ?: return
        binding.recognitionStatusText.text = context.getString(R.string.recognition_processing)
        stopRipple()
        binding.recognitionProgress.visibility = View.VISIBLE
    }

    private fun showSuccess(result: RecognitionResult) {
        val binding = overlayBinding ?: return
        stopRipple()
        binding.recognitionProgress.visibility = View.GONE
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
        binding.recognitionStatusText.text = ""
        binding.recognitionActionsLayout.visibility = View.VISIBLE
        binding.btnTryAgain.visibility = View.GONE

        val artUrl = result.coverArtHqUrl ?: result.coverArtUrl
        if (!artUrl.isNullOrBlank()) {
            binding.recognitionCover.load(artUrl) {
                allowHardware(false)
                crossfade(true)
            }
        }

        if (!result.localFilePath.isNullOrBlank()) {
            binding.btnPlayLocal.visibility = View.VISIBLE
            binding.btnPlayLocal.setOnClickListener {
                openInLibre(result, playLocal = true)
            }
        }

        binding.btnPlayStream.setOnClickListener {
            openInLibre(result, playLocal = false)
        }

        binding.btnSearchTrack.setOnClickListener {
            openInLibreSearch(result)
        }
    }

    private fun showNoMatch() {
        val binding = overlayBinding ?: return
        stopRipple()
        binding.recognitionProgress.visibility = View.GONE
        binding.recognitionStatusText.text = context.getString(R.string.recognition_no_match)
        binding.recognitionCover.setImageResource(R.drawable.ic_mic)
        binding.recognitionActionsLayout.visibility = View.GONE
        binding.btnTryAgain.visibility = View.VISIBLE
        binding.btnTryAgain.setOnClickListener { restartRecognition() }
    }

    private fun showError(message: String) {
        val binding = overlayBinding ?: return
        stopRipple()
        binding.recognitionProgress.visibility = View.GONE
        binding.recognitionStatusText.text = message.ifBlank { context.getString(R.string.recognition_error) }
        binding.recognitionCover.setImageResource(R.drawable.ic_mic)
        binding.recognitionActionsLayout.visibility = View.GONE
        binding.btnTryAgain.visibility = View.VISIBLE
        binding.btnTryAgain.setOnClickListener { restartRecognition() }
    }

    private fun openInLibre(result: RecognitionResult, playLocal: Boolean) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(RecognitionForegroundService.EXTRA_RECOGNITION_RESULT_TITLE, result.title)
            putExtra(RecognitionForegroundService.EXTRA_RECOGNITION_RESULT_ARTIST, result.artist)
            putExtra(RecognitionForegroundService.EXTRA_RECOGNITION_RESULT_COVER, result.coverArtHqUrl ?: result.coverArtUrl)
            putExtra(RecognitionForegroundService.EXTRA_RECOGNITION_RESULT_ALBUM, result.album)
            if (playLocal) {
                putExtra(RecognitionForegroundService.EXTRA_RECOGNITION_RESULT_LOCAL_PATH, result.localFilePath)
            }
            putExtra(MainActivity.EXTRA_AUTOPLAY_RECOGNITION, true)
        }
        context.startActivity(intent)
        dismiss()
    }

    private fun openInLibreSearch(result: RecognitionResult) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(IntentData.query, "${result.title} ${result.artist}")
        }
        context.startActivity(intent)
        dismiss()
    }

    private fun openOverlayPermissionSetting() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    private fun startRipple() {
        val binding = overlayBinding ?: return
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
        overlayBinding?.rippleRingOuter?.alpha = 0f
        overlayBinding?.rippleRingInner?.alpha = 0f
    }
}
