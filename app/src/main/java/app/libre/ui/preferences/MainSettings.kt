package app.libre.ui.preferences

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import app.libre.BuildConfig
import app.libre.R
import app.libre.constants.PreferenceKeys
import app.libre.extensions.toastFromMainDispatcher
import app.libre.helpers.BackupHelper
import app.libre.helpers.LocalAudioMatcher
import app.libre.helpers.PreferenceHelper
import app.libre.helpers.UpdateHelper
import app.libre.helpers.WifiSyncHelper
import app.libre.ui.base.BasePreferenceFragment
import app.libre.ui.dialogs.RequireRestartDialog
import app.libre.util.TextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToStream

class MainSettings : BasePreferenceFragment() {

    private var pendingBackupTrigger: Boolean = false

    private val selectBackupFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            pendingBackupTrigger = false
            return@registerForActivityResult
        }
        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        PreferenceHelper.putString(PreferenceKeys.BACKUP_FOLDER_URI, uri.toString())
        updateBackupFolderSummary()

        if (pendingBackupTrigger) {
            pendingBackupTrigger = false
            triggerBackup()
        }
    }

    private val selectOfflineFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        PreferenceHelper.putString(PreferenceKeys.OFFLINE_SONGS_FOLDER_URI, uri.toString())
        updateOfflineFolderSummary()
        LocalAudioMatcher.startAutoScan(requireContext())
    }

    private val getBackupFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            CoroutineScope(Dispatchers.IO).launch {
                BackupHelper.restoreAdvancedBackup(requireContext().applicationContext, uri)
                withContext(Dispatchers.Main) {
                    runCatching {
                        RequireRestartDialog().show(childFragmentManager, this@MainSettings::class.java.name)
                    }
                }
            }
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun triggerBackup() {
        val folder = BackupHelper.getBackupFolder(requireContext())
        if (folder != null && folder.exists() && folder.canWrite()) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val file = BackupHelper.getCompleteBackupFile()
                    val timestamp = TextUtils.getFileSafeTimeStampNow()
                    val backupFileName = "libretube-backup-${timestamp}.json"
                    val documentFile = folder.createFile("application/json", backupFileName)
                    if (documentFile != null) {
                        requireContext().contentResolver.openOutputStream(documentFile.uri)?.use { outputStream ->
                            app.libre.api.JsonHelper.json.encodeToStream(file, outputStream)
                        }

                        // Unified pruning for all LibreTube backup variations
                        BackupHelper.pruneBackupFolder(folder, maxKeep = 5)

                        requireContext().toastFromMainDispatcher(R.string.backup_created_success_folder)
                    } else {
                        requireContext().toastFromMainDispatcher(R.string.backup_creation_failed)
                    }
                } catch (e: Exception) {
                    requireContext().toastFromMainDispatcher(R.string.backup_creation_failed)
                }
            }
        } else {
            // Path not set -> redirect to folder picker, and trigger backup upon selection
            pendingBackupTrigger = true
            selectBackupFolder.launch(null)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)

        // Offline Music Folder
        findPreference<Preference>("offline_songs_pref")?.setOnPreferenceClickListener {
            val uriStr = PreferenceHelper.getString(PreferenceKeys.OFFLINE_SONGS_FOLDER_URI, "")
            val uri = if (uriStr.isNotEmpty()) Uri.parse(uriStr) else null
            selectOfflineFolder.launch(uri)
            true
        }

        // Backup Folder
        findPreference<Preference>("backup_folder")?.setOnPreferenceClickListener {
            val uriStr = PreferenceHelper.getString(PreferenceKeys.BACKUP_FOLDER_URI, "")
            val uri = if (uriStr.isNotEmpty()) Uri.parse(uriStr) else null
            selectBackupFolder.launch(uri)
            true
        }

        // Auto Backup Toggle
        findPreference<SwitchPreferenceCompat>("enable_auto_backup")?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: true
            if (enabled) {
                BackupHelper.enqueueAutoBackupWork(requireContext())
            }
            true
        }

        // Create Backup Now
        findPreference<Preference>("backup_now")?.setOnPreferenceClickListener {
            triggerBackup()
            true
        }

        // Restore Backup
        findPreference<Preference>("restore")?.setOnPreferenceClickListener {
            getBackupFile.launch("*/*")
            true
        }

        // Version & Update Check
        val update = findPreference<Preference>("update")
        update?.summary = BuildConfig.VERSION_NAME
        update?.setOnPreferenceClickListener {
            UpdateHelper.checkForUpdate(requireContext())
            true
        }

        // Crash Logs & Diagnostics
        findPreference<Preference>("crash_logs")?.setOnPreferenceClickListener {
            val reports = app.libre.crash.CrashManager.getAllReports()
            if (reports.isEmpty()) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.crash_logs)
                    .setMessage(R.string.no_crash_logs)
                    .setPositiveButton(R.string.okay, null)
                    .show()
            } else {
                val combinedText = reports.joinToString("\n\n---\n\n") { it.toFormattedMarkdown() }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("${getString(R.string.crash_logs)} (${reports.size})")
                    .setMessage(reports.first().toFormattedMarkdown())
                    .setNeutralButton(R.string.clear_crash_logs) { _, _ ->
                        app.libre.crash.CrashManager.clearAllReports()
                        updateCrashLogsSummary()
                        android.widget.Toast.makeText(requireContext(), "Crash logs cleared", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.share) { _, _ ->
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, combinedText)
                            type = "text/plain"
                        }
                        startActivity(Intent.createChooser(sendIntent, getString(R.string.share)))
                    }
                    .setPositiveButton(R.string.copy) { _, _ ->
                        app.libre.helpers.ClipboardHelper.save(requireContext(), text = combinedText, notify = true)
                    }
                    .show()
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        updateBackupFolderSummary()
        updateOfflineFolderSummary()
        updateWifiSyncStatus()
        updateCrashLogsSummary()
    }

    private fun updateCrashLogsSummary() {
        val crashLogsPref = findPreference<Preference>("crash_logs") ?: return
        val count = app.libre.crash.CrashManager.getAllReports().size
        crashLogsPref.summary = if (count == 0) {
            getString(R.string.no_crash_logs)
        } else {
            "$count crash report(s) recorded"
        }
    }

    private fun updateWifiSyncStatus() {
        val wifiSyncPref = findPreference<Preference>("wifi_sync_status") ?: return
        if (WifiSyncHelper.isRunningStatus) {
            wifiSyncPref.title = "Wi-Fi Sync: Running"
            wifiSyncPref.summary = WifiSyncHelper.getServerAddress()
        } else {
            wifiSyncPref.title = "Wi-Fi Sync: Inactive"
            wifiSyncPref.summary = "Wi-Fi is disconnected or server is stopped"
        }
    }

    private fun getDisplayPath(uriString: String): String {
        return try {
            val decoded = Uri.decode(uriString)
            if (decoded.contains("primary:")) {
                "Internal Storage/" + decoded.substringAfter("primary:")
            } else {
                val segments = decoded.split("/")
                segments.lastOrNull() ?: uriString
            }
        } catch (e: Exception) {
            uriString
        }
    }

    private fun updateBackupFolderSummary() {
        val backupFolderPreference = findPreference<Preference>("backup_folder") ?: return
        val uriString = PreferenceHelper.getString(PreferenceKeys.BACKUP_FOLDER_URI, "")
        if (uriString.isNotEmpty()) {
            val displayPath = getDisplayPath(uriString)
            backupFolderPreference.summary = getString(R.string.backup_folder_summary_set, displayPath)
        } else {
            backupFolderPreference.summary = getString(R.string.backup_folder_summary_not_set)
        }
    }

    private fun updateOfflineFolderSummary() {
        val offlineFolderPreference = findPreference<Preference>("offline_songs_pref") ?: return
        val uriString = PreferenceHelper.getString(PreferenceKeys.OFFLINE_SONGS_FOLDER_URI, "")
        if (uriString.isNotEmpty()) {
            val displayPath = getDisplayPath(uriString)
            offlineFolderPreference.summary = getString(R.string.offline_songs_folder_summary_set, displayPath)
        } else {
            offlineFolderPreference.summary = getString(R.string.offline_songs_folder_summary_not_set)
        }
    }
}
