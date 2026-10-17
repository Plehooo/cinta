package com.plehooo.cinta

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

class CintaMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { PushTokens.sync(applicationContext) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "photo") return
        val ctx = applicationContext
        val ok = runBlocking(Dispatchers.IO) {
            try {
                val repo = PhotoSyncRepository(ctx)
                if (repo.currentUid() != null) repo.refreshPartnerPhoto()
                true
            } catch (_: Throwable) {
                false
            }
        }
        if (!ok) SyncScheduler.enqueueNow(ctx)
    }
}
