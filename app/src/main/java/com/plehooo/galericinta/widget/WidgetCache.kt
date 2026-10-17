package com.plehooo.galericinta.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

object WidgetCache {
    private fun file(context: Context, slotId: String) = File(context.filesDir, "widget_${slotId}.jpg")
    private fun prefs(context: Context) = context.getSharedPreferences("galeri_widget", Context.MODE_PRIVATE)

    fun save(context: Context, slotId: String, bytes: ByteArray, sourceKey: String? = null) {
        if (slotId !in setOf("slot1", "slot2") || bytes.isEmpty()) return
        runCatching {
            val target = file(context, slotId)
            val temp = File(context.filesDir, "widget_${slotId}.tmp")
            FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
            if (!temp.renameTo(target)) {
                target.delete()
                check(temp.renameTo(target)) { "Tidak dapat memperbarui cache widget." }
            }
            val editor = prefs(context).edit()
            if (sourceKey.isNullOrBlank()) editor.remove("${slotId}_source")
            else editor.putString("${slotId}_source", sourceKey)
            editor.apply()
        }
    }

    fun loadBitmap(context: Context, slotId: String): Bitmap? =
        runCatching { BitmapFactory.decodeFile(file(context, slotId).absolutePath) }.getOrNull()

    fun hasImage(context: Context, slotId: String): Boolean = file(context, slotId).isFile && file(context, slotId).length() > 0L

    fun matchesSource(context: Context, slotId: String, sourceKey: String?): Boolean =
        !sourceKey.isNullOrBlank() && hasImage(context, slotId) && prefs(context).getString("${slotId}_source", null) == sourceKey

    fun clearSlot(context: Context, slotId: String) {
        if (slotId !in setOf("slot1", "slot2")) return
        runCatching { file(context, slotId).delete() }
        runCatching { File(context.filesDir, "widget_${slotId}.tmp").delete() }
        prefs(context).edit().remove("${slotId}_source").apply()
    }

    fun setScope(context: Context, slotId: String, shared: Boolean) {
        if (slotId !in setOf("slot1", "slot2")) return
        prefs(context).edit().putBoolean("${slotId}_shared", shared).apply()
    }

    fun isSharedScope(context: Context, slotId: String): Boolean =
        prefs(context).getBoolean("${slotId}_shared", false)

    /** False only until this device explicitly or automatically adopts a scope. */
    fun hasScopeChoice(context: Context, slotId: String): Boolean =
        slotId in setOf("slot1", "slot2") && prefs(context).contains("${slotId}_shared")

    fun clearAll(context: Context) {
        clearSlot(context, "slot1")
        clearSlot(context, "slot2")
        prefs(context).edit().clear().apply()
    }
}
