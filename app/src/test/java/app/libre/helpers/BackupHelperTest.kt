package app.libre.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupHelperTest {

    @Test
    fun testIsBackupFile() {
        // New Libre formats
        assertTrue(BackupHelper.isBackupFile("libre-backup-2026-09-08-03_00_00.json"))
        assertTrue(BackupHelper.isBackupFile("libre-auto-backup-2026-09-08-03_00_00.json"))
        assertTrue(BackupHelper.isBackupFile("Libre-Backup-2026.json"))

        // Legacy LibreTube formats (Backward compatibility)
        assertTrue(BackupHelper.isBackupFile("libretube-backup-2026-08-22-09_57_26.json"))
        assertTrue(BackupHelper.isBackupFile("libretube-auto-backup-2026-08-31-20_45_39.json"))
        assertTrue(BackupHelper.isBackupFile("libretube_backup_20260905-105740.json"))
        assertTrue(BackupHelper.isBackupFile("LibreTube-Backup-2026.json"))
        assertTrue(BackupHelper.isBackupFile("libretube_auto_backup_123.json"))

        // Deprecated method compatibility
        @Suppress("DEPRECATION")
        assertTrue(BackupHelper.isLibreTubeBackupFile("libre-backup-2026.json"))
        @Suppress("DEPRECATION")
        assertTrue(BackupHelper.isLibreTubeBackupFile("libretube-backup-2026.json"))

        // Invalid non-backup files
        assertFalse(BackupHelper.isBackupFile("my_songs.json"))
        assertFalse(BackupHelper.isBackupFile("libre-backup.txt"))
        assertFalse(BackupHelper.isBackupFile("libretube-backup.txt"))
        assertFalse(BackupHelper.isBackupFile("backup.json"))
        assertFalse(BackupHelper.isBackupFile(null))
    }

    @Test
    fun testGenerateBackupFileNameFormat() {
        val fileName = BackupHelper.generateBackupFileName()
        assertTrue(fileName.startsWith("libre-backup-"))
        assertTrue(fileName.endsWith(".json"))
        assertTrue(BackupHelper.isBackupFile(fileName))
    }
}
