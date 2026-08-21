package app.libre.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.libre.helpers.BackupHelper

class AutoBackupWorker(appContext: Context, parameters: WorkerParameters) :
    CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        BackupHelper.runAutoBackup(applicationContext)
        return Result.success()
    }
}
