package com.plehooo.cinta

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object PhotoQueue {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(context: Context, uid: String, data: ByteArray, metadata: PendingPhoto) {
        val image = PhotoFiles.pendingPhoto(context, uid)
        val imagePart = File(image.parentFile, image.name + ".part")
        imagePart.outputStream().use { out -> out.write(data); out.fd.sync() }
        if (!imagePart.renameTo(image)) {
            imagePart.copyTo(image, overwrite = true)
            imagePart.delete()
        }
        val sidecar = PhotoFiles.pendingMetadata(context, uid)
        val temp = File(sidecar.parentFile, sidecar.name + ".part")
        temp.writeText(json.encodeToString(metadata))
        if (!temp.renameTo(sidecar)) {
            temp.copyTo(sidecar, overwrite = true)
            temp.delete()
        }
    }

    fun readMetadata(context: Context, uid: String): PendingPhoto? = runCatching {
        val metadataFile = PhotoFiles.pendingMetadata(context, uid)
        if (!metadataFile.isFile) return null
        json.decodeFromString<PendingPhoto>(metadataFile.readText())
    }.getOrNull()

    fun readImage(context: Context, uid: String): ByteArray? = runCatching {
        val file = PhotoFiles.pendingPhoto(context, uid)
        if (!file.isFile || file.length() !in 1..(512L * 1024L)) return null
        file.readBytes()
    }.getOrNull()

    fun clear(context: Context, uid: String) {
        PhotoFiles.pendingPhoto(context, uid).delete()
        PhotoFiles.pendingMetadata(context, uid).delete()
    }

    fun hasPending(context: Context, uid: String): Boolean =
        PhotoFiles.pendingPhoto(context, uid).isFile && PhotoFiles.pendingMetadata(context, uid).isFile

    fun hasAnyPending(context: Context): Boolean =
        PhotoFiles.pendingDirectory(context).listFiles()?.any {
            it.isFile && it.name.startsWith("owner-") && it.name.endsWith(".json")
        } == true
}
