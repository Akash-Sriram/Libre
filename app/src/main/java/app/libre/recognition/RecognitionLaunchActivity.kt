package app.libre.recognition

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import app.libre.ui.activities.MainActivity

/**
 * Transparent trampoline Activity invoked by Quick Settings Tile or Widget.
 * Immediately launches [RecognitionForegroundService] in the background
 * and finishes itself so the current website, app, or lock screen is NOT disturbed.
 *
 * Ported from Metrolist for Libre.
 */
@android.annotation.SuppressLint("CustomSplashScreen")
class RecognitionLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRecognitionLaunch()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleRecognitionLaunch()
    }

    private fun handleRecognitionLaunch() {
        if (hasRecordPermission()) {
            startRecognitionService()
        } else {
            openRecognitionPermissionFlow()
        }
        finish()
    }

    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun openRecognitionPermissionFlow() {
        val intent = Intent(this, RecognitionOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        startActivity(intent)
    }

    private fun startRecognitionService() {
        if (!hasRecordPermission()) return

        val serviceIntent = Intent(this, RecognitionForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
