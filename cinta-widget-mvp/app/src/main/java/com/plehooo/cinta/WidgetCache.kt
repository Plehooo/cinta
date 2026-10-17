package com.plehooo.cinta

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object WidgetCache {
    private val photoPathKey = stringPreferencesKey("partner_photo_path")
    private val captionKey = stringPreferencesKey("partner_caption")
    private val versionKey = intPreferencesKey("partner_photo_version")
    private val photoIdKey = stringPreferencesKey("partner_photo_id")
    private val contentHashKey = stringPreferencesKey("partner_photo_hash")

    suspend fun savePartnerPhoto(
        context: Context,
        partnerUid: String,
        bytes: ByteArray,
        photo: MemberPhoto
    ) = withContext(Dispatchers.IO) {
        val cache = PhotoFiles.partnerPhotoCache(context, partnerUid)
        val temp = File(cache.parentFile, "${cache.name}.part")
        temp.outputStream().use { it.write(bytes); it.fd.sync() }
        if (!temp.renameTo(cache)) {
            temp.copyTo(cache, overwrite = true)
            temp.delete()
        }
        val manager = GlanceAppWidgetManager(context)
        manager.getGlanceIds(CintaWidget::class.java).forEach { glanceId ->
            updateAppWidgetState(context, glanceId) { state ->
                state[photoPathKey] = cache.absolutePath
                state[captionKey] = photo.caption.orEmpty()
                state[versionKey] = photo.version
                state[photoIdKey] = photo.photoId
                state[contentHashKey] = photo.contentHash
            }
            CintaWidget().update(context, glanceId)
        }
    }

    suspend fun clearPartnerCaches(context: Context) = withContext(Dispatchers.IO) {
        PhotoFiles.cacheDirectory(context).listFiles()?.forEach { it.delete() }
        val manager = GlanceAppWidgetManager(context)
        manager.getGlanceIds(CintaWidget::class.java).forEach { glanceId ->
            updateAppWidgetState(context, glanceId) { state ->
                state.remove(photoPathKey)
                state.remove(captionKey)
                state.remove(versionKey)
                state.remove(photoIdKey)
                state.remove(contentHashKey)
            }
            CintaWidget().update(context, glanceId)
        }
    }

    fun cacheMatches(context: Context, partnerUid: String, photo: MemberPhoto): Boolean {
        val localFile = PhotoFiles.partnerPhotoCache(context, partnerUid)
        if (!localFile.isFile || localFile.length() == 0L) return false
        val prefs = context.getSharedPreferences("partner-photo-cache", Context.MODE_PRIVATE)
        return prefs.getString("id:$partnerUid", null) == photo.photoId &&
            prefs.getInt("version:$partnerUid", -1) == photo.version &&
            prefs.getString("hash:$partnerUid", null) == photo.contentHash
    }

    fun rememberCache(context: Context, partnerUid: String, photo: MemberPhoto) {
        context.getSharedPreferences("partner-photo-cache", Context.MODE_PRIVATE).edit()
            .putString("id:$partnerUid", photo.photoId)
            .putInt("version:$partnerUid", photo.version)
            .putString("hash:$partnerUid", photo.contentHash)
            .apply()
    }

    fun cachedPhotoPath(context: Context, partnerUid: String): String? =
        PhotoFiles.partnerPhotoCache(context, partnerUid).takeIf { it.isFile && it.length() > 0 }?.absolutePath
}
