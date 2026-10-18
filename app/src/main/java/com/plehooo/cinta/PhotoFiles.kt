package com.plehooo.cinta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

object PhotoFiles {
    private const val MAX_BYTES = 512 * 1024
    private const val MAX_EDGE = 1080

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

    fun newObjectPath(coupleId: String, ownerUid: String): String =
        "$coupleId/$ownerUid/${UUID.randomUUID()}.webp"

    fun encodeWebp(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver
        val bounds = resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Foto tidak dapat dibuka." }
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { options ->
                BitmapFactory.decodeStream(input, null, options)
            }
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "File foto tidak valid atau tidak didukung." }

        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_EDGE * 2) sample *= 2
        val decoded = resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Foto tidak dapat dibuka." }
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("File foto tidak valid atau tidak didukung.")
        val source = rotateByExif(context, uri, decoded)

        var resized: Bitmap? = null
        try {
            val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1))
            val bitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    source,
                    (source.width * scale).toInt().coerceAtLeast(1),
                    (source.height * scale).toInt().coerceAtLeast(1),
                    true
                ).also { resized = it }
            } else source

            val format = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
            else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP

            var quality = 86
            var output: ByteArray
            do {
                val stream = ByteArrayOutputStream()
                check(bitmap.compress(format, quality, stream)) { "Kompresi foto gagal." }
                output = stream.toByteArray()
                quality -= 8
            } while (output.size > MAX_BYTES && quality >= 38)

            require(output.size <= MAX_BYTES) { "Ukuran foto masih lebih dari 512 KiB setelah kompresi." }
            return output
        } finally {
            resized?.takeIf { it !== source }?.recycle()
            source.recycle()
        }
    }

    private fun rotateByExif(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) 0 else when (android.media.ExifInterface(input).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL
                )) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (_: Throwable) {
            0
        }
        if (degrees == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    fun pendingDirectory(context: Context): File = File(context.filesDir, "photo-queue").apply { mkdirs() }
    fun cacheDirectory(context: Context): File = File(context.filesDir, "photo-cache").apply { mkdirs() }

    fun pendingPhoto(context: Context, ownerUid: String) = File(pendingDirectory(context), "owner-$ownerUid.webp")
    fun pendingMetadata(context: Context, ownerUid: String) = File(pendingDirectory(context), "owner-$ownerUid.json")
    fun partnerPhotoCache(context: Context, partnerUid: String) = File(cacheDirectory(context), "partner-$partnerUid.webp")
}
