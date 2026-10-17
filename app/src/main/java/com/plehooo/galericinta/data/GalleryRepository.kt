package com.plehooo.galericinta.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageException
import com.plehooo.galericinta.domain.MediaItem
import com.plehooo.galericinta.domain.SlotConfig
import com.plehooo.galericinta.domain.normalizeRotationInterval
import com.plehooo.galericinta.widget.GalleryWidget
import com.plehooo.galericinta.widget.WidgetCache
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.plehooo.galericinta.worker.SlideshowWorker
import com.plehooo.galericinta.worker.WidgetSyncWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

class GalleryRepository(private val context: Context) {
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val resolver: ContentResolver = context.contentResolver

    fun saveDeviceToken(uid: String, token: String) {
        val tokenId = java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(40)
        db.collection("users").document(uid).collection("deviceTokens").document(tokenId)
            .set(mapOf("token" to token, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
    }

    suspend fun ensureProfile(user: FirebaseUser) {
        val ref = db.collection("users").document(user.uid)
        val current = ref.get().await()
        if (!current.exists()) {
            ref.set(
                mapOf(
                    "displayName" to (user.displayName?.takeIf { it.isNotBlank() } ?: user.email.orEmpty().substringBefore('@')),
                    "photoUrl" to user.photoUrl?.toString(),
                    "coupleId" to null,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    fun listenProfile(uid: String, callback: (String?, String?, String?) -> Unit): ListenerRegistration =
        db.collection("users").document(uid).addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
            callback(snapshot.getString("displayName"), snapshot.getString("coupleId"), snapshot.getString("photoUrl"))
        }

    fun listenPrivateMedia(uid: String, callback: (List<MediaItem>) -> Unit): ListenerRegistration =
        db.collection("users").document(uid).collection("privateMedia")
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) callback(snapshot.documents.mapNotNull { it.toMedia(shared = false) })
            }

    fun listenSharedMedia(coupleId: String, callback: (List<MediaItem>) -> Unit): ListenerRegistration =
        db.collection("couples").document(coupleId).collection("sharedMedia")
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) callback(snapshot.documents.mapNotNull { it.toMedia(shared = true) })
            }

    fun listenPrivateSlots(uid: String, callback: (Map<String, SlotConfig>) -> Unit): ListenerRegistration =
        db.collection("users").document(uid).collection("displaySettings").document("default")
            .collection("slots").addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) callback(snapshot.documents.associate { it.id to it.toSlot() })
            }

    fun listenSharedSlots(coupleId: String, callback: (Map<String, SlotConfig>) -> Unit): ListenerRegistration =
        db.collection("couples").document(coupleId).collection("sharedDisplay").document("slots")
            .collection("items").addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) callback(snapshot.documents.associate { it.id to it.toSlot() })
            }

    suspend fun uploadMedia(uid: String, coupleId: String?, uri: Uri, shareNow: Boolean, caption: String): MediaItem =
        withContext(Dispatchers.IO) {
            require(uid.isNotBlank()) { "Akun belum siap." }
            val mime = resolver.getType(uri)?.lowercase() ?: error("Format file tidak dikenali.")
            require(mime.startsWith("image/") || mime.startsWith("video/")) { "Pilih foto atau video." }
            val size = querySize(uri)
            val limit = if (mime.startsWith("video/")) 100L * 1024 * 1024 else 30L * 1024 * 1024
            require(size <= 0L || size <= limit) {
                if (mime.startsWith("video/")) "Ukuran video maksimal 100 MB." else "Ukuran foto maksimal 30 MB."
            }
            if (shareNow) require(!coupleId.isNullOrBlank()) { "Hubungkan akun pasangan dahulu." }

            val id = UUID.randomUUID().toString()
            val basePath = if (shareNow) "couples/$coupleId/shared/$uid/$id" else "users/$uid/private/$id"
            val originalPath = "$basePath/original"
            val thumbPath = "$basePath/thumbnail.jpg"
            val uploadedPaths = mutableListOf<String>()
            try {
                storage.reference.child(originalPath).putFile(
                    uri, StorageMetadata.Builder().setContentType(mime).build()
                ).await()
                uploadedPaths += originalPath

                val thumbBytes = if (mime.startsWith("image/")) makeThumbnail(uri) else null
                if (thumbBytes != null) {
                    storage.reference.child(thumbPath).putBytes(
                        thumbBytes,
                        StorageMetadata.Builder().setContentType("image/jpeg").build()
                    ).await()
                    uploadedPaths += thumbPath
                }

                val item = MediaItem(
                    id, uid, caption.trim().take(500), originalPath,
                    if (thumbBytes != null) thumbPath else null, mime,
                    System.currentTimeMillis(), shareNow
                )
                val doc = mediaDocument(item)
                if (shareNow) {
                    db.collection("couples").document(coupleId!!).collection("sharedMedia").document(id).set(doc).await()
                } else {
                    db.collection("users").document(uid).collection("privateMedia").document(id).set(doc).await()
                }
                item
            } catch (error: Throwable) {
                // Roll back uploaded objects when metadata creation fails, avoiding orphaned files.
                uploadedPaths.asReversed().forEach { path ->
                    try { deleteStorageObjectIfExists(path) } catch (_: Exception) { /* Preserve the original error. */ }
                }
                throw error
            }
        }

    suspend fun sharePrivateMedia(uid: String, coupleId: String?, item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        require(!item.shared && item.ownerUid == uid) { "Hanya media privat milikmu yang dapat dibagikan." }
        require(!coupleId.isNullOrBlank()) { "Hubungkan akun pasangan dahulu." }
        val id = UUID.randomUUID().toString()
        val base = "couples/$coupleId/shared/$uid/$id"
        val originalTemp = File(context.cacheDir, "share-$id.bin")
        val uploadedPaths = mutableListOf<String>()
        try {
            storage.reference.child(item.storagePath).getFile(originalTemp).await()
            storage.reference.child("$base/original").putFile(
                Uri.fromFile(originalTemp),
                StorageMetadata.Builder().setContentType(item.mimeType).build()
            ).await()
            uploadedPaths += "$base/original"

            var thumbPath: String? = null
            item.thumbnailPath?.let { oldThumb ->
                val bytes = storage.reference.child(oldThumb).getBytes(2L * 1024L * 1024L).await()
                storage.reference.child("$base/thumbnail.jpg").putBytes(
                    bytes,
                    StorageMetadata.Builder().setContentType("image/jpeg").build()
                ).await()
                thumbPath = "$base/thumbnail.jpg"
                uploadedPaths += thumbPath!!
            }
            val shared = item.copy(
                storagePath = "$base/original", thumbnailPath = thumbPath,
                shared = true, createdAt = System.currentTimeMillis()
            )
            db.collection("couples").document(coupleId).collection("sharedMedia").document(id)
                .set(mediaDocument(shared)).await()
            shared
        } catch (error: Throwable) {
            uploadedPaths.asReversed().forEach { path ->
                try { deleteStorageObjectIfExists(path) } catch (_: Exception) { /* Preserve the original error. */ }
            }
            throw error
        } finally {
            originalTemp.delete()
        }
    }

    suspend fun deleteMedia(coupleId: String?, item: MediaItem) {
        require(item.ownerUid.isNotBlank()) { "Pemilik media tidak valid." }
        // Treat missing objects as an already-completed delete, but do not hide real Storage errors.
        deleteStorageObjectIfExists(item.storagePath)
        item.thumbnailPath?.let { deleteStorageObjectIfExists(it) }
        if (item.shared) {
            require(!coupleId.isNullOrBlank()) { "Hubungan pasangan sudah tidak aktif." }
            db.collection("couples").document(coupleId).collection("sharedMedia").document(item.id).delete().await()
        } else {
            db.collection("users").document(item.ownerUid).collection("privateMedia").document(item.id).delete().await()
        }
    }

    /** Removes deleted IDs from this user's personal slot configs; shared slots are cleaned by Functions. */
    suspend fun cleanupPersonalSlotsAfterMediaRemoval(uid: String, coupleId: String?, removedMediaId: String) {
        for (slotId in listOf("slot1", "slot2")) {
            val privateRef = db.collection("users").document(uid).collection("displaySettings").document("default")
                .collection("slots").document(slotId)
            cleanSlotReference(privateRef, uid, coupleId, slotId, sharedSlot = false, removedMediaId = removedMediaId)
        }
    }

    private suspend fun cleanSlotReference(
        ref: com.google.firebase.firestore.DocumentReference,
        uid: String,
        coupleId: String?,
        slotId: String,
        sharedSlot: Boolean,
        removedMediaId: String
    ) {
        val snapshot = ref.get().await()
        if (!snapshot.exists()) return
        val activeId = snapshot.getString("mediaId")
        val oldIds = (snapshot.get("rotationIds") as? List<*>)?.filterIsInstance<String>()?.distinct().orEmpty()
        if (activeId != removedMediaId && removedMediaId !in oldIds) return
        val remainingIds = oldIds.filterNot { it == removedMediaId }

        if (activeId == removedMediaId) {
            var replacement: MediaItem? = null
            if (snapshot.getString("mode") == "SLIDESHOW") {
                for (candidateId in remainingIds) {
                    replacement = findMediaForSlot(uid, coupleId, candidateId, sharedOnly = sharedSlot)
                    if (replacement != null) break
                }
            }
            if (replacement == null) {
                ref.delete().await()
                WorkManager.getInstance(context).cancelUniqueWork(rotationWorkName(uid, sharedSlot, slotId))
                if (WidgetCache.isSharedScope(context, slotId) == sharedSlot) refreshWidgetCache(slotId, null)
            } else {
                ref.update(mapOf(
                    "mediaId" to replacement.id,
                    "storagePath" to replacement.storagePath,
                    "thumbnailPath" to replacement.thumbnailPath,
                    "rotationIds" to remainingIds,
                    "updatedBy" to uid,
                    "updatedAt" to FieldValue.serverTimestamp()
                )).await()
            }
        } else {
            ref.update(mapOf(
                "rotationIds" to remainingIds,
                "updatedBy" to uid,
                "updatedAt" to FieldValue.serverTimestamp()
            )).await()
        }
    }

    private suspend fun findMediaForSlot(uid: String, coupleId: String?, mediaId: String, sharedOnly: Boolean): MediaItem? {
        if (!sharedOnly) {
            val privateDoc = db.collection("users").document(uid).collection("privateMedia").document(mediaId).get().await()
            if (privateDoc.exists()) return privateDoc.toMedia(shared = false)
        }
        if (coupleId.isNullOrBlank()) return null
        val sharedDoc = db.collection("couples").document(coupleId).collection("sharedMedia").document(mediaId).get().await()
        return if (sharedDoc.exists()) sharedDoc.toMedia(shared = true) else null
    }

    private suspend fun deleteStorageObjectIfExists(path: String) {
        try {
            storage.reference.child(path).delete().await()
        } catch (error: StorageException) {
            if (error.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw error
        }
    }

    suspend fun saveSlot(uid: String, coupleId: String?, slot: SlotConfig, shared: Boolean) {
        if (shared) require(!coupleId.isNullOrBlank()) { "Hubungkan akun pasangan dahulu." }
        val target = if (shared) {
            db.collection("couples").document(coupleId!!).collection("sharedDisplay").document("slots")
                .collection("items").document(slot.slotId)
        } else {
            db.collection("users").document(uid).collection("displaySettings").document("default")
                .collection("slots").document(slot.slotId)
        }
        val previous = target.get().await()
        val previousWidgetPath = previous.getString("widgetImagePath")
        val previousWidgetOwner = previous.getString("widgetImageOwnerUid")
        val payload = mapOf(
            "mediaId" to slot.mediaId,
            "storagePath" to slot.storagePath,
            "thumbnailPath" to slot.thumbnailPath,
            "caption" to slot.caption.take(120),
            "mode" to slot.mode,
            "rotationIds" to slot.rotationIds.distinct().take(100),
            "intervalMinutes" to normalizeRotationInterval(slot.intervalMinutes),
            "source" to if (shared) "shared" else "private",
            "widgetImageUrl" to slot.widgetImageUrl,
            "widgetImagePath" to slot.widgetImagePath,
            "widgetImageOwnerUid" to slot.widgetImageOwnerUid,
            "updatedBy" to uid,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        target.set(payload, SetOptions.merge()).await()
        if (previousWidgetOwner == uid && !previousWidgetPath.isNullOrBlank() && previousWidgetPath != slot.widgetImagePath) {
            runCatching { WidgetCloudStore().deleteOwnObject(previousWidgetPath, uid) }
        }
        WidgetCache.setScope(context, slot.slotId, shared)
        scheduleRotation(uid, coupleId, slot, shared)
        refreshWidgetCache(slot.slotId, slot.thumbnailPath, slot.widgetImageUrl, slot.widgetImagePath)
    }

    /** Uploads only a compressed widget image to Supabase; the full gallery file remains untouched. */
    suspend fun publishWidgetPhoto(uid: String, coupleId: String?, uri: Uri, slotId: String, caption: String) =
        withContext(Dispatchers.IO) {
            require(slotId in setOf("slot1", "slot2")) { "Slot widget tidak valid." }
            require(!coupleId.isNullOrBlank()) { "Hubungkan akun pasangan dahulu." }
            val cloud = WidgetCloudStore()
            val uploaded = cloud.uploadWidgetImage(resolver, uri, uid)
            val target = db.collection("couples").document(coupleId!!).collection("sharedDisplay").document("slots")
                .collection("items").document(slotId)
            var oldPath: String? = null
            var oldOwner: String? = null
            try {
                val existing = target.get().await()
                oldPath = existing.getString("widgetImagePath")
                oldOwner = existing.getString("widgetImageOwnerUid")
                val widgetMediaId = "widget-${uploaded.id}"
                val payload = mapOf(
                    "mediaId" to widgetMediaId,
                    "storagePath" to "widget-only",
                    "thumbnailPath" to null,
                    "caption" to caption.trim().take(120),
                    "mode" to "MANUAL",
                    "rotationIds" to listOf(widgetMediaId),
                    "intervalMinutes" to 60,
                    "source" to "shared",
                    "widgetImageUrl" to uploaded.signedUrl,
                    "widgetImagePath" to uploaded.path,
                    "widgetImageOwnerUid" to uid,
                    "updatedBy" to uid,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                target.set(payload).await()
            } catch (error: Throwable) {
                runCatching { cloud.deleteOwnObject(uploaded.path, uid) }
                throw error
            }
            WidgetCache.setScope(context, slotId, true)
            WidgetCache.save(context, slotId, uploaded.jpegBytes, uploaded.path)
            GalleryWidget().updateAll(context)
            if (oldOwner == uid && !oldPath.isNullOrBlank() && oldPath != uploaded.path) {
                runCatching { cloud.deleteOwnObject(oldPath!!, uid) }
            }
        }

    suspend fun selectWidgetScope(slotId: String, shared: Boolean, privateSlots: Map<String, SlotConfig>, sharedSlots: Map<String, SlotConfig>) {
        WidgetCache.setScope(context, slotId, shared)
        val chosen = if (shared) sharedSlots[slotId] else privateSlots[slotId]
        val active = chosen?.takeIf { !it.mediaId.isNullOrBlank() }
        refreshWidgetCache(slotId, active?.thumbnailPath, active?.widgetImageUrl, active?.widgetImagePath)
    }

    private fun rotationWorkName(uid: String, shared: Boolean, slotId: String): String =
        "galeri-cinta-$uid-${if (shared) "shared" else "private"}-$slotId"

    private fun scheduleRotation(uid: String, coupleId: String?, slot: SlotConfig, shared: Boolean) {
        val workManager = WorkManager.getInstance(context)
        val workName = rotationWorkName(uid, shared, slot.slotId)
        if (slot.mode != "SLIDESHOW" || slot.rotationIds.distinct().size < 2 || (shared && coupleId.isNullOrBlank())) {
            workManager.cancelUniqueWork(workName)
            return
        }
        val delayMinutes = normalizeRotationInterval(slot.intervalMinutes).toLong()
        val scopeTag = if (shared) "galeri-cinta-rotation-shared" else "galeri-cinta-rotation-private"
        val request = PeriodicWorkRequestBuilder<SlideshowWorker>(delayMinutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(
                "slotId" to slot.slotId,
                "shared" to shared,
                "ownerUid" to uid,
                "coupleId" to if (shared) coupleId.orEmpty() else ""
            ))
            .addTag("galeri-cinta-rotation")
            .addTag(scopeTag)
            .build()
        workManager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Reconcile schedules from server state, including changes made on the other device. */
    fun synchronizeRotationSchedules(
        uid: String,
        coupleId: String?,
        privateSlots: Map<String, SlotConfig>,
        sharedSlots: Map<String, SlotConfig>
    ) {
        for (slotId in listOf("slot1", "slot2")) {
            privateSlots[slotId]?.let { scheduleRotation(uid, coupleId, it, shared = false) }
                ?: WorkManager.getInstance(context).cancelUniqueWork(rotationWorkName(uid, false, slotId))
            val sharedSlot = if (coupleId.isNullOrBlank()) null else sharedSlots[slotId]
            sharedSlot?.let { scheduleRotation(uid, coupleId, it, shared = true) }
                ?: WorkManager.getInstance(context).cancelUniqueWork(rotationWorkName(uid, true, slotId))
        }
    }

    suspend fun removeSlot(uid: String, coupleId: String?, slotId: String, shared: Boolean) {
        val target = if (shared) {
            require(!coupleId.isNullOrBlank()) { "Hubungkan akun pasangan dahulu." }
            db.collection("couples").document(coupleId).collection("sharedDisplay").document("slots")
                .collection("items").document(slotId)
        } else {
            db.collection("users").document(uid).collection("displaySettings").document("default")
                .collection("slots").document(slotId)
        }
        val existing = target.get().await()
        val oldPath = existing.getString("widgetImagePath")
        val oldOwner = existing.getString("widgetImageOwnerUid")
        target.delete().await()
        if (oldOwner == uid && !oldPath.isNullOrBlank()) {
            runCatching { WidgetCloudStore().deleteOwnObject(oldPath, uid) }
        }
        WidgetCache.setScope(context, slotId, false)
        WidgetCache.clearSlot(context, slotId)
        WorkManager.getInstance(context).cancelUniqueWork(rotationWorkName(uid, shared, slotId))
        GalleryWidget().updateAll(context)
    }

    fun cancelAllRotations() {
        WorkManager.getInstance(context).cancelAllWorkByTag("galeri-cinta-rotation")
    }

    fun scheduleWidgetSync(uid: String) {
        if (uid.isBlank()) return
        val work = PeriodicWorkRequestBuilder<WidgetSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("ownerUid" to uid))
            .addTag(WIDGET_SYNC_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "galeri-cinta-widget-sync-$uid", ExistingPeriodicWorkPolicy.UPDATE, work
        )
    }

    fun cancelWidgetSync(uid: String?) {
        if (!uid.isNullOrBlank()) WorkManager.getInstance(context).cancelUniqueWork("galeri-cinta-widget-sync-$uid")
    }

    /** Background fallback for when the partner app is not open and Firestore listeners are detached. */
    suspend fun synchronizeWidgetCache(uid: String) {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
        if (user.uid != uid) return
        val profile = db.collection("users").document(uid).get().await()
        val coupleId = profile.getString("coupleId")
        val privateSnapshot = db.collection("users").document(uid).collection("displaySettings").document("default")
            .collection("slots").get().await()
        val privateSlots = privateSnapshot.documents.associate { it.id to it.toSlot() }.toMutableMap()
        val sharedSlots = if (coupleId.isNullOrBlank()) mutableMapOf() else {
            val snapshot = db.collection("couples").document(coupleId).collection("sharedDisplay").document("slots")
                .collection("items").get().await()
            snapshot.documents.associate { it.id to it.toSlot() }.toMutableMap()
        }

        // The owner refreshes a soon-to-expire signed URL so a fresh install on the partner phone
        // can still download the same private object without making its bucket public.
        val cloud = WidgetCloudStore()
        if (!coupleId.isNullOrBlank() && cloud.isConfigured()) {
            for ((slotId, slot) in sharedSlots.toMap()) {
                val path = slot.widgetImagePath ?: continue
                val url = slot.widgetImageUrl ?: continue
                if (slot.widgetImageOwnerUid != uid || !path.startsWith("users/$uid/") || !cloud.signedUrlExpiringSoon(url)) continue
                runCatching {
                    val renewed = cloud.createSignedUrl(path, uid)
                    if (renewed != url) {
                        db.collection("couples").document(coupleId).collection("sharedDisplay").document("slots")
                            .collection("items").document(slotId)
                            .update(mapOf("widgetImageUrl" to renewed, "updatedBy" to uid, "updatedAt" to FieldValue.serverTimestamp())).await()
                        sharedSlots[slotId] = slot.copy(widgetImageUrl = renewed)
                    }
                }
            }
        }
        refreshAllWidgetImages(privateSlots, sharedSlots)
    }

    suspend fun clearSharedWidgetCache() {
        WorkManager.getInstance(context).cancelAllWorkByTag("galeri-cinta-rotation-shared")
        for (slotId in listOf("slot1", "slot2")) {
            if (WidgetCache.isSharedScope(context, slotId)) WidgetCache.clearSlot(context, slotId)
        }
        GalleryWidget().updateAll(context)
    }

    suspend fun refreshWidgetCache(
        slotId: String,
        thumbnailPath: String?,
        widgetImageUrl: String? = null,
        widgetImagePath: String? = null
    ) {
        val source = widgetImagePath ?: widgetImageUrl ?: thumbnailPath
        if (source.isNullOrBlank()) {
            WidgetCache.clearSlot(context, slotId)
        } else if (!WidgetCache.matchesSource(context, slotId, source)) {
            try {
                val bytes = if (!widgetImageUrl.isNullOrBlank()) {
                    WidgetCloudStore().downloadSignedImage(widgetImageUrl)
                } else {
                    storage.reference.child(thumbnailPath ?: source).getBytes(2L * 1024L * 1024L).await()
                }
                WidgetCache.save(context, slotId, bytes, source)
            } catch (_: Exception) {
                // Preserve the last known-good bitmap during a transient network, expired-link, or quota error.
                if (!WidgetCache.hasImage(context, slotId)) WidgetCache.clearSlot(context, slotId)
            }
        }
        GalleryWidget().updateAll(context)
    }

    suspend fun refreshAllWidgetImages(privateSlots: Map<String, SlotConfig>, sharedSlots: Map<String, SlotConfig>) {
        for (slotId in listOf("slot1", "slot2")) {
            val sharedPhoto = sharedSlots[slotId]
            // A new partner device shows a shared widget photo by default, without overwriting
            // an existing explicit per-device choice such as "HP saya".
            if (!WidgetCache.hasScopeChoice(context, slotId) && !sharedPhoto?.widgetImageUrl.isNullOrBlank()) {
                WidgetCache.setScope(context, slotId, true)
            }
            val chosen = if (WidgetCache.isSharedScope(context, slotId)) sharedSlots[slotId] else privateSlots[slotId]
            val active = chosen?.takeIf { !it.mediaId.isNullOrBlank() }
            refreshWidgetCache(slotId, active?.thumbnailPath, active?.widgetImageUrl, active?.widgetImagePath)
        }
    }

    companion object {
        private const val WIDGET_SYNC_TAG = "galeri-cinta-widget-sync"
    }

    private fun DocumentSnapshot.toMedia(shared: Boolean): MediaItem? {
        val path = getString("storagePath") ?: return null
        return MediaItem(
            id = id,
            ownerUid = getString("ownerUid") ?: "",
            caption = getString("caption").orEmpty(),
            storagePath = path,
            thumbnailPath = getString("thumbnailPath"),
            mimeType = getString("mimeType") ?: "application/octet-stream",
            createdAt = getTimestamp("createdAt")?.toDate()?.time ?: 0L,
            shared = shared
        )
    }

    private fun DocumentSnapshot.toSlot(): SlotConfig = SlotConfig(
        slotId = id,
        mediaId = getString("mediaId"),
        storagePath = getString("storagePath"),
        thumbnailPath = getString("thumbnailPath"),
        caption = getString("caption").orEmpty(),
        mode = getString("mode")?.takeIf { it == "MANUAL" || it == "SLIDESHOW" } ?: "MANUAL",
        rotationIds = (get("rotationIds") as? List<*>)?.filterIsInstance<String>()
            ?.filter { it.isNotBlank() && it.length <= 80 }?.distinct()?.take(100).orEmpty(),
        intervalMinutes = normalizeRotationInterval((getLong("intervalMinutes") ?: 60L).toInt()),
        updatedAt = getTimestamp("updatedAt")?.toDate()?.time ?: 0L,
        widgetImageUrl = getString("widgetImageUrl"),
        widgetImagePath = getString("widgetImagePath"),
        widgetImageOwnerUid = getString("widgetImageOwnerUid")
    )

    private fun mediaDocument(item: MediaItem): Map<String, Any?> = mapOf(
        "ownerUid" to item.ownerUid,
        "caption" to item.caption.take(500),
        "storagePath" to item.storagePath,
        "thumbnailPath" to item.thumbnailPath,
        "mimeType" to item.mimeType,
        "visibility" to if (item.shared) "shared" else "private",
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )

    private fun querySize(uri: Uri): Long {
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun makeThumbnail(uri: Uri): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth / sample, bounds.outHeight / sample) > 1024) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            val scale = min(1f, 512f / max(decoded.width, decoded.height).toFloat())
            val thumb = Bitmap.createScaledBitmap(decoded, max(1, (decoded.width * scale).toInt()), max(1, (decoded.height * scale).toInt()), true)
            val output = ByteArrayOutputStream()
            thumb.compress(Bitmap.CompressFormat.JPEG, 84, output)
            if (thumb !== decoded) thumb.recycle()
            decoded.recycle()
            output.toByteArray().takeIf { it.size <= 2 * 1024 * 1024 }
        } catch (_: Exception) {
            null
        }
    }
}
