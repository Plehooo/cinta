package com.plehooo.cinta

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class PhotoSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!SupabaseProvider.configured) return Result.failure()
        return try {
            val repository = PhotoSyncRepository(applicationContext)
            val uid = repository.awaitUid() ?: return if (PhotoQueue.hasAnyPending(applicationContext)) Result.retry() else Result.success()
            if (PhotoQueue.hasPending(applicationContext, uid)) {
                when (repository.publishQueuedOwnPhoto()) {
                    UploadOutcome.RETRY -> return Result.retry()
                    UploadOutcome.PERMANENT_FAILURE -> {
                        // Keep the queued file for an explicit user retry; don't loop on a version conflict.
                    }
                    else -> Unit
                }
            }
            repository.refreshPartnerPhoto()
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Result.retry()
        }
    }
}
