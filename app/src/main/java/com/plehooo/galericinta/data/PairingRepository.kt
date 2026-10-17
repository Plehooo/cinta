package com.plehooo.galericinta.data

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

class PairingRepository {
    private val functions = FirebaseFunctions.getInstance()

    suspend fun createInviteCode(): String {
        val result = functions.getHttpsCallable("createInviteCode").call().await()
        val data = result.data as? Map<*, *> ?: error("Respons server tidak valid.")
        return data["code"] as? String ?: error("Server tidak mengembalikan kode undangan.")
    }

    suspend fun redeemInviteCode(code: String) {
        require(code.trim().length >= 8) { "Masukkan kode undangan yang valid." }
        functions.getHttpsCallable("redeemInviteCode")
            .call(mapOf("code" to code.trim().uppercase()))
            .await()
    }

    suspend fun disconnectCouple(): Boolean {
        val result = functions.getHttpsCallable("disconnectCouple").call().await()
        val data = result.data as? Map<*, *> ?: error("Respons server tidak valid.")
        return data["cleanupPending"] as? Boolean ?: false
    }
}
