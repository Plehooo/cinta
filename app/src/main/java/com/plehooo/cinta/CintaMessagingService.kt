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
        val type = message.data["type"]
        if (type != null && type != "photo") return
        val ctx = applicationContext
        val ok = runBlocking(Dispatchers.IO) {
            try {
                val repo = PhotoSyncRepository(ctx)
                if (repo.awaitUid() != null) {
                    repo.refreshPartnerPhoto()
                    true
                } else false
            } catch (_: Throwable) {
                false
            }
        }
        if (!ok) SyncScheduler.enqueueNow(ctx)
    }
}
