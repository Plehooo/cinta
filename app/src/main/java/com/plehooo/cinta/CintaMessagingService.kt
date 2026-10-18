package com.plehooo.cinta

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
