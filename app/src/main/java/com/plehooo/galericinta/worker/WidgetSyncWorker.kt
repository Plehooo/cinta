package com.plehooo.galericinta.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.plehooo.galericinta.data.GalleryRepository

/** Periodic fallback: a partner's home-screen widget can catch up while the app UI is closed. */
class WidgetSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val expectedUid = inputData.getString("ownerUid") ?: return Result.failure()
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return Result.success()
        if (currentUser.uid != expectedUid) return Result.success()
        return try {
            GalleryRepository(applicationContext).synchronizeWidgetCache(expectedUid)
            Result.success()
        } catch (_: SecurityException) {
            Result.failure()
        } catch (_: Exception) {
            // A periodic sync should survive temporary network/service errors instead of becoming
            // permanently failed after a small fixed number of retries.
            Result.retry()
        }
    }
}
