package com.plehooo.cinta

import android.content.Context
import android.util.Log
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.postgrest.query.filter.FilterOperation
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.jan.supabase.realtime.selectAsFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64

class PhotoSyncRepository(private val context: Context) {
    private val supabase get() = SupabaseProvider.client
    private val settings = context.getSharedPreferences("cinta-local-state", Context.MODE_PRIVATE)

    suspend fun signIn(email: String, password: String) {
        supabase.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signUp(email: String, password: String) {
        supabase.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signOut() {
        val uid = currentUid()
        supabase.auth.signOut()
        if (uid != null) PhotoQueue.clear(context, uid)
        WidgetCache.clearPartnerCaches(context)
    }

    fun currentUid(): String? = supabase.auth.currentUserOrNull()?.id

    suspend fun activeMembers(): List<CoupleMember> {
        val active = supabase.from("couple_members").select().decodeList<CoupleMember>()
            .filter { it.leftAt == null }
        val uid = currentUid()
        if (uid != null) {
            val ownMemberships = active.filter { it.userId == uid }
            if (ownMemberships.size == 1) {
                val own = ownMemberships.single()
                val partner = active.firstOrNull { it.coupleId == own.coupleId && it.userId != uid }
                settings.edit().putString("last-couple:$uid", own.coupleId).apply()
                if (partner != null) settings.edit().putString("last-partner:$uid", partner.userId).apply()
                else settings.edit().remove("last-partner:$uid").apply()
            } else {
                // This path runs only after a successful server response; stale local pairing is revoked.
                settings.edit().remove("last-couple:$uid").remove("last-partner:$uid").apply()
            }
        }
        return active
    }

    /** Best-effort local snapshot is for offline UI/queueing only; the server revalidates every publish. */
    fun cachedActiveMembers(uid: String): List<CoupleMember> {
        val coupleId = settings.getString("last-couple:$uid", null) ?: return emptyList()
        val partnerUid = settings.getString("last-partner:$uid", null)
        return buildList {
            add(CoupleMember(coupleId = coupleId, userId = uid))
            if (!partnerUid.isNullOrBlank() && partnerUid != uid) {
                add(CoupleMember(coupleId = coupleId, userId = partnerUid))
            }
        }
    }

    suspend fun ownPhotoVersionOrCached(uid: String): Int = withContext(Dispatchers.IO) {
        runCatching {
            val own = supabase.from("member_photos").select().decodeList<MemberPhoto>()
                .firstOrNull { it.ownerUid == uid }
            val version = own?.version ?: 0
            settings.edit().putInt("own-version:$uid", version).apply()
            version
        }.getOrElse { settings.getInt("own-version:$uid", 0) }
    }

    suspend fun createCouple(): String {
        val id = supabase.postgrest.rpc("create_couple").decodeAs<String>()
        SyncScheduler.enqueueNow(context)
        return id
    }

    suspend fun createInviteCode(): String {
        // 192 bits from SecureRandom; only SHA-256 is written to PostgreSQL.
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val hash = PhotoFiles.sha256(code.toByteArray(Charsets.UTF_8))
        supabase.postgrest.rpc("create_pairing_code", Json.encodeToJsonElement(CodeHashParams(hash)).jsonObject)
        return code
    }

    suspend fun redeemCode(code: String): Boolean {
        val result = supabase.postgrest.rpc("redeem_pairing_code", Json.encodeToJsonElement(RedeemCodeParams(code.trim())).jsonObject)
            .decodeAsOrNull<String>()
        if (result.isNullOrBlank()) return false
        SyncScheduler.enqueueNow(context)
        startRealtimeObserver(context)
        return true
    }

    suspend fun queueOwnPhoto(coupleId: String, bytes: ByteArray, caption: String, expectedVersion: Int) {
        require(bytes.size in 1..(512 * 1024)) { "Foto harus berukuran maksimal 512 KiB." }
        val uid = currentUid() ?: error("Silakan login kembali.")
        require(coupleId.isNotBlank() && settings.getString("last-couple:$uid", null) == coupleId) {
            "Ruang pasangan belum tersimpan di perangkat. Hubungkan pasangan saat online sekali, lalu coba lagi."
        }
        val metadata = PendingPhoto(
            coupleId = coupleId,
            ownerUid = uid,
            caption = caption.trim().take(100),
            expectedVersion = expectedVersion,
            createdAtEpochMillis = System.currentTimeMillis()
        )
        // No network call here: offline selections are written under this account's private queue.
        // The server checks current membership and expected version before publishing anything.
        PhotoQueue.save(context, uid, bytes, metadata)
        settings.edit().putString("own-photo-status:$uid", "Menunggu sinkronisasi")
            .remove("own-photo-error:$uid").apply()
        SyncScheduler.enqueueNow(context)
    }

    suspend fun publishQueuedOwnPhoto(): UploadOutcome = withContext(Dispatchers.IO) {
        val uid = currentUid() ?: return@withContext UploadOutcome.RETRY
        val queued = PhotoQueue.readMetadata(context, uid) ?: return@withContext UploadOutcome.NOTHING
        if (settings.getString("own-photo-error:$uid", null) == "conflict") {
            return@withContext UploadOutcome.PERMANENT_FAILURE
        }
        val bytes = PhotoQueue.readImage(context, uid) ?: return@withContext UploadOutcome.PERMANENT_FAILURE
        if (queued.ownerUid != uid) return@withContext UploadOutcome.PERMANENT_FAILURE

        var uploadedPath: String? = null
        try {
            val currentMembers = activeMembers()
            if (currentMembers.none { it.coupleId == queued.coupleId && it.userId == uid }) {
                PhotoQueue.clear(context, uid)
                settings.edit().putString("own-photo-status:$uid", "Pasangan terputus; antrean dibersihkan").apply()
                return@withContext UploadOutcome.NOTHING
            }

            val photos = supabase.from("member_photos").select().decodeList<MemberPhoto>()
                .filter { it.coupleId == queued.coupleId }
            val oldOwnPhoto = photos.firstOrNull { it.ownerUid == uid }
            val queuedHash = PhotoFiles.sha256(bytes)
            // Recover safely if the previous request committed metadata but its HTTP response was lost.
            if (oldOwnPhoto != null && oldOwnPhoto.contentHash == queuedHash && oldOwnPhoto.caption == queued.caption.ifBlank { null }) {
                settings.edit().putInt("own-version:$uid", oldOwnPhoto.version)
                    .putString("own-photo-status:$uid", "Foto terkirim ke widget pasangan")
                    .remove("own-photo-error:$uid").apply()
                PhotoQueue.clear(context, uid)
                return@withContext UploadOutcome.SUCCESS
            }
            val objectPath = PhotoFiles.newObjectPath(queued.coupleId, uid)
            // Upload a new immutable object first. Never upsert/replace the previous image.
            supabase.storage.from("couple-photos").upload(objectPath, bytes) { upsert = false }
            uploadedPath = objectPath

            val result = supabase.postgrest.rpc(
                "publish_member_photo",
                Json.encodeToJsonElement(PublishPhotoParams(
                    coupleId = queued.coupleId,
                    objectPath = objectPath,
                    caption = queued.caption.ifBlank { null },
                    contentHash = queuedHash,
                    expectedVersion = queued.expectedVersion
                )).jsonObject
            ).decodeList<MemberPhoto>()
            val published = result.firstOrNull() ?: error("Server tidak mengonfirmasi metadata foto.")

            // Metadata is committed. Old object cleanup is best-effort and happens only afterwards.
            if (oldOwnPhoto != null && oldOwnPhoto.objectPath != objectPath) {
                runCatching { supabase.storage.from("couple-photos").delete(oldOwnPhoto.objectPath) }
            }
            runCatching { cleanupOwnOldPhotos(queued.coupleId, uid, objectPath) }
            settings.edit().putInt("own-version:$uid", published.version)
                .putString("own-photo-status:$uid", "Foto terkirim ke widget pasangan")
                .remove("own-photo-error:$uid").apply()
            PhotoQueue.clear(context, uid)
            UploadOutcome.SUCCESS
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = error.message.orEmpty()
            if (message.contains("PHOTO_VERSION_CONFLICT", ignoreCase = true)) {
                // The database rejected the publication before changing metadata; safe to remove the new object.
                uploadedPath?.let { path -> runCatching { supabase.storage.from("couple-photos").delete(path) } }
                settings.edit().putString("own-photo-status:$uid", "Konflik versi: foto lama tetap tampil. Pilih foto lagi untuk mencoba versi terbaru.")
                    .putString("own-photo-error:$uid", "conflict").apply()
                UploadOutcome.PERMANENT_FAILURE
            } else {
                // On an ambiguous network failure, keep the uploaded object: the RPC may have committed
                // even if its response was lost. The idempotent hash check above reconciles on retry.
                settings.edit().putString("own-photo-status:$uid", "Offline/gagal terhubung — foto aman di antrean")
                    .apply()
                Log.w("CintaPhotoSync", "Photo upload deferred (${error.javaClass.simpleName})")
                UploadOutcome.RETRY
            }
        }
    }

    suspend fun refreshPartnerPhoto() = withContext(Dispatchers.IO) {
        val uid = currentUid() ?: return@withContext
        val members = activeMembers()
        val route = PhotoRouting.forViewer(members, uid)
        if (route == null) {
            // A successful membership query says there is no valid two-person route; fail closed.
            WidgetCache.clearPartnerCaches(context)
            return@withContext
        }
        val partnerUid = route.partnerUid
        val partnerPhoto = supabase.from("member_photos").select().decodeList<MemberPhoto>()
            .firstOrNull {
                it.coupleId == route.coupleId && PhotoRouting.canAffectWidget(route, it.ownerUid)
            }
            ?: return@withContext // Keep last valid cache on transient/empty metadata responses.
        if (WidgetCache.cacheMatches(context, partnerUid, partnerPhoto)) return@withContext

        val bytes = supabase.storage.from("couple-photos").downloadAuthenticated(partnerPhoto.objectPath)
        if (bytes.isEmpty() || bytes.size > 512 * 1024) return@withContext
        if (PhotoFiles.sha256(bytes) != partnerPhoto.contentHash) return@withContext
        // Save atomically; widget is refreshed only after a verified partner image is on disk.
        WidgetCache.savePartnerPhoto(context, partnerUid, bytes, partnerPhoto)
        WidgetCache.rememberCache(context, partnerUid, partnerPhoto)
    }

    suspend fun leaveCouple() {
        val uid = currentUid() ?: error("Silakan login kembali.")
        runCatching {
            val own = supabase.from("member_photos").select().decodeList<MemberPhoto>()
                .firstOrNull { it.ownerUid == uid }
            if (own != null) supabase.storage.from("couple-photos").delete(own.objectPath)
        }
        supabase.postgrest.rpc("leave_couple")
        PhotoQueue.clear(context, uid)
        WidgetCache.clearPartnerCaches(context)
        settings.edit().remove("last-couple:$uid").remove("last-partner:$uid")
            .remove("own-photo-error:$uid").putString("own-photo-status:$uid", "Pasangan terputus").apply()
    }

    suspend fun sendResetCode(email: String) {
        supabase.auth.resetPasswordForEmail(email.trim())
    }

    suspend fun resetPasswordWithCode(email: String, code: String, newPassword: String) {
        supabase.auth.verifyEmailOtp(type = OtpType.Email.RECOVERY, email = email.trim(), token = code.trim())
        supabase.auth.updateUser { password = newPassword }
    }

    suspend fun changeEmail(newEmail: String) {
        supabase.auth.updateUser { email = newEmail.trim() }
    }

    suspend fun changePassword(newPassword: String) {
        supabase.auth.updateUser { password = newPassword }
    }

    @OptIn(SupabaseExperimental::class)
    suspend fun signInAnonymouslyIfNeeded() {
        if (supabase.auth.currentUserOrNull() == null) supabase.auth.signInAnonymously()
    }

    suspend fun cleanupOwnOldPhotos(coupleId: String, uid: String, keepPath: String) {
        val bucket = supabase.storage.from("couple-photos")
        val folder = "$coupleId/$uid"
        bucket.list(folder).map { "$folder/${it.name}" }
            .filter { it != keepPath && it.endsWith(".webp") }
            .forEach { bucket.delete(it) }
    }

    suspend fun registerPushToken(token: String) {
        supabase.postgrest.rpc("register_device_token", buildJsonObject { put("p_token", token) })
    }

    fun photoStatus(uid: String): String =
        settings.getString("own-photo-status:$uid", "Belum ada foto yang dikirim") ?: "Belum ada foto yang dikirim"

    companion object {
        private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var observerStarted = false

        @OptIn(SupabaseExperimental::class)
        fun startRealtimeObserver(context: Context) {
            if (!SupabaseProvider.configured || observerStarted) return
            synchronized(this) {
                if (observerStarted) return
                observerStarted = true
            }
            val appContext = context.applicationContext
            observerScope.launch {
                while (isActive) {
                    try {
                        val client = SupabaseProvider.client
                        val uid = client.auth.currentUserOrNull()?.id
                        if (uid == null) {
                            delay(30_000)
                            continue
                        }
                        val route = PhotoRouting.forViewer(
                            client.from("couple_members").select().decodeList<CoupleMember>(), uid
                        )
                        if (route == null) {
                            delay(30_000)
                            continue
                        }
                        // Collect just the current partner's records, and restart when login/pairing changes.
                        val subscriptionFailed = java.util.concurrent.atomic.AtomicBoolean(false)
                        val subscription = launch {
                            try {
                                val changes = client.from("member_photos").selectAsFlow(
                                    primaryKey = MemberPhoto::photoId,
                                    filter = FilterOperation("owner_uid", FilterOperator.EQ, route.partnerUid)
                                )
                                changes.collect {
                                    runCatching { PhotoSyncRepository(appContext).refreshPartnerPhoto() }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                subscriptionFailed.set(true)
                                // Parent checks isActive and reconnects; periodic work is independent.
                            }
                        }
                        try {
                            while (isActive && subscription.isActive) {
                                delay(30_000)
                                val currentUid = client.auth.currentUserOrNull()?.id
                                if (currentUid != uid) break
                                val freshRoute = runCatching {
                                    PhotoRouting.forViewer(
                                        client.from("couple_members").select().decodeList<CoupleMember>(), uid
                                    )
                                }.getOrNull()
                                if (freshRoute?.partnerUid != route.partnerUid || freshRoute?.coupleId != route.coupleId) break
                            }
                        } finally {
                            subscription.cancelAndJoin()
                        }
                        if (subscriptionFailed.get()) delay(30_000)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        Log.w("CintaRealtime", "Realtime unavailable; periodic WorkManager remains active")
                        delay(30_000)
                    }
                }
            }
        }
    }
}

enum class UploadOutcome { SUCCESS, NOTHING, RETRY, PERMANENT_FAILURE }
