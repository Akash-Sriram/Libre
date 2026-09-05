package app.libre.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupHelperTest {

    @Test
    fun testIsLibreTubeBackupFile() {
        // Standard formats
        assertTrue(BackupHelper.isLibreTubeBackupFile("libretube-backup-2026-08-22-09_57_26.json"))
        assertTrue(BackupHelper.isLibreTubeBackupFile("libretube-auto-backup-2026-08-31-20_45_39.json"))
        assertTrue(BackupHelper.isLibreTubeBackupFile("libretube_backup_20260905-105740.json"))
        assertTrue(BackupHelper.isLibreTubeBackupFile("LibreTube-Backup-2026.json"))
        assertTrue(BackupHelper.isLibreTubeBackupFile("libretube_auto_backup_123.json"))

        // Invalid non-backup files
        assertFalse(BackupHelper.isLibreTubeBackupFile("my_songs.json"))
        assertFalse(BackupHelper.isLibreTubeBackupFile("libretube-backup.txt"))
        assertFalse(BackupHelper.isLibreTubeBackupFile("backup.json"))
        assertFalse(BackupHelper.isLibreTubeBackupFile(null))
    }
}
