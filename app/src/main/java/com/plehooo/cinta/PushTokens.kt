package com.plehooo.cinta

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object PushTokens {
    private suspend fun currentToken(): String? = suspendCancellableCoroutine { cont ->
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
            }
        } catch (_: Throwable) {
            if (cont.isActive) cont.resume(null)
        }
    }

    suspend fun sync(context: Context) {
        try {
            val token = currentToken() ?: return
            val repo = PhotoSyncRepository(context.applicationContext)
            if (repo.currentUid() == null) return
            repo.registerPushToken(token)
        } catch (_: Throwable) {
        }
    }
}
