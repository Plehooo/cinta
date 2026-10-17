package com.plehooo.galericinta.worker

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.plehooo.galericinta.widget.GalleryWidget
import com.plehooo.galericinta.domain.nextRotationMediaId
import com.plehooo.galericinta.widget.WidgetCache
import kotlinx.coroutines.tasks.await

class SlideshowWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val user = FirebaseAuth.getInstance().currentUser ?: return Result.success()
            val expectedUid = inputData.getString("ownerUid") ?: return Result.success()
            if (user.uid != expectedUid) return Result.success()
            val slotId = inputData.getString("slotId") ?: return Result.failure()
            val shared = inputData.getBoolean("shared", false)
            val expectedCoupleId = inputData.getString("coupleId").orEmpty()
            val db = FirebaseFirestore.getInstance()
            val userDoc = db.collection("users").document(user.uid).get().await()
            val coupleId = userDoc.getString("coupleId")
            if (shared && (coupleId.isNullOrBlank() || coupleId != expectedCoupleId)) return Result.success()
            val slotRef = if (shared) {
                db.collection("couples").document(coupleId!!).collection("sharedDisplay").document("slots")
                    .collection("items").document(slotId)
            } else {
                db.collection("users").document(user.uid).collection("displaySettings").document("default")
                    .collection("slots").document(slotId)
            }
            val slot = slotRef.get().await()
            if (!slot.exists() || slot.getString("mode") != "SLIDESHOW") return Result.success()
            val ids = (slot.get("rotationIds") as? List<*>)?.filterIsInstance<String>()?.distinct().orEmpty()
            if (ids.size < 2) return Result.success()
            val current = slot.getString("mediaId")
            val nextId = nextRotationMediaId(current, ids) ?: return Result.success()
            val media = if (shared) {
                db.collection("couples").document(coupleId!!).collection("sharedMedia").document(nextId).get().await()
            } else {
                val privateDoc = db.collection("users").document(user.uid).collection("privateMedia").document(nextId).get().await()
                if (privateDoc.exists()) privateDoc else if (!coupleId.isNullOrBlank()) {
                    db.collection("couples").document(coupleId).collection("sharedMedia").document(nextId).get().await()
                } else privateDoc
            }
            if (!media.exists()) return Result.success()
            val thumbPath = media.getString("thumbnailPath")
            slotRef.update(
                mapOf(
                    "mediaId" to nextId,
                    "storagePath" to media.getString("storagePath"),
                    "thumbnailPath" to thumbPath,
                    // Keep slot caption stable; media captions should not trigger rotation notifications.
                    "updatedBy" to user.uid,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            if (WidgetCache.isSharedScope(applicationContext, slotId) == shared) {
                if (thumbPath.isNullOrBlank()) WidgetCache.clearSlot(applicationContext, slotId)
                else {
                    val bytes = FirebaseStorage.getInstance().reference.child(thumbPath).getBytes(2L * 1024L * 1024L).await()
                    WidgetCache.save(applicationContext, slotId, bytes)
                }
                GalleryWidget().updateAll(applicationContext)
            }
            Result.success()
        } catch (_: SecurityException) {
            Result.failure()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
