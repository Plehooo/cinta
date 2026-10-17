package com.plehooo.galericinta.domain

data class MediaItem(
    val id: String,
    val ownerUid: String,
    val caption: String,
    val storagePath: String,
    val thumbnailPath: String?,
    val mimeType: String,
    val createdAt: Long,
    val shared: Boolean
)

data class SlotConfig(
    val slotId: String,
    val mediaId: String? = null,
    val storagePath: String? = null,
    val thumbnailPath: String? = null,
    val caption: String = "",
    val mode: String = "MANUAL",
    val rotationIds: List<String> = emptyList(),
    val intervalMinutes: Int = 60,
    val updatedAt: Long = 0L,
    /** A time-limited Supabase signed URL used only by the cross-device home-screen widget. */
    val widgetImageUrl: String? = null,
    /** Private Supabase object path; never a public bucket URL. */
    val widgetImagePath: String? = null,
    val widgetImageOwnerUid: String? = null
)

data class UserProfile(
    val displayName: String = "",
    val coupleId: String? = null,
    val photoUrl: String? = null
)

val supportedRotationIntervals = setOf(30, 60, 180, 360, 720, 1440)

fun normalizeRotationInterval(minutes: Int): Int =
    minutes.takeIf { it in supportedRotationIntervals } ?: 60

/** Returns the next distinct media ID, wrapping at the end; null means slideshow is not possible. */
fun nextRotationMediaId(currentId: String?, mediaIds: List<String>): String? {
    val ids = mediaIds.filter { it.isNotBlank() }.distinct()
    if (ids.size < 2) return null
    val currentIndex = ids.indexOf(currentId)
    val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % ids.size
    return ids[nextIndex]
}
