package com.plehooo.cinta

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class CintaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncScheduler.schedule(this)
        if (SupabaseProvider.configured) RealtimeService.start(this)
    }
}

object SyncScheduler {
    private const val WORK_NAME = "cinta-photo-sync-periodic"

    fun schedule(context: android.content.Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        // 15 menit = interval minimum WorkManager; UPDATE supaya instalasi lama (6 jam) ikut berubah.
        val request = PeriodicWorkRequestBuilder<PhotoSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    fun enqueueNow(context: android.content.Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = androidx.work.OneTimeWorkRequestBuilder<PhotoSyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "cinta-photo-sync-now", androidx.work.ExistingWorkPolicy.REPLACE, request
        )
    }
}
