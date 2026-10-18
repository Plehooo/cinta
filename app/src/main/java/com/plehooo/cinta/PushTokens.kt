package com.plehooo.cinta

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

object PushTokens {
    private val lock = Mutex()

    private suspend fun <T> Task<T>.awaitResult(): T? = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { t ->
            if (cont.isActive) cont.resume(if (t.isSuccessful) t.result else null)
        }
    }

    private suspend fun currentToken(): String? = try {
        FirebaseMessaging.getInstance().token.awaitResult()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    suspend fun sync(context: Context) = lock.withLock {
        try {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("cinta-local-state", Context.MODE_PRIVATE)

            // Proyek Firebase berganti (sender ID beda): buang token lama supaya token baru cocok.
            val sender = FirebaseApp.getInstance().options.gcmSenderId.orEmpty()
            if (sender.isNotBlank() && prefs.getString("fcm-sender", null) != sender) {
                runCatching { FirebaseMessaging.getInstance().deleteToken().awaitResult() }
                prefs.edit().remove("fcm-registered").putString("fcm-sender", sender).apply()
            }

            val token = currentToken() ?: return@withLock
            val repo = PhotoSyncRepository(app)
            val uid = repo.awaitUid() ?: return@withLock
            val marker = "$uid:$token"
            // Sudah terdaftar untuk akun + token ini: lewati. Gagal -> dicoba lagi nanti.
            if (prefs.getString("fcm-registered", null) == marker) return@withLock
            repo.registerPushToken(token)
            prefs.edit().putString("fcm-registered", marker).apply()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
        }
    }
}
