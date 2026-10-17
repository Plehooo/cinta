package com.plehooo.galericinta.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.plehooo.galericinta.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** Private Supabase Storage path dedicated to the small image shown by the shared home-screen widget. */
class WidgetCloudStore {
    data class UploadedWidgetImage(
        val id: String,
        val path: String,
        val signedUrl: String,
        val jpegBytes: ByteArray
    )

    private val baseUrl = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    private val publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim()

    fun isConfigured(): Boolean =
        baseUrl.startsWith("https://") && baseUrl.endsWith(".supabase.co") && publishableKey.isNotBlank()

    suspend fun uploadWidgetImage(contentResolver: ContentResolver, uri: Uri, uid: String): UploadedWidgetImage =
        withContext(Dispatchers.IO) {
            requireConfigured()
            require(uid.isNotBlank()) { "Akun belum siap." }
            val currentUser = FirebaseAuth.getInstance().currentUser
                ?: error("Masuk ke akun terlebih dahulu.")
            require(currentUser.uid == uid) { "Akun berubah. Coba ulangi." }
            val token = currentUser.getIdToken(false).await().token
                ?: error("Token login tidak tersedia. Masuk ulang lalu coba lagi.")
            val jpeg = compressWidgetImage(contentResolver, uri)
            require(jpeg.size <= MAX_IMAGE_BYTES) { "Foto widget masih terlalu besar. Pilih foto lain." }
            val objectId = UUID.randomUUID().toString()
            val path = "users/$uid/$objectId.jpg"
            val encodedPath = encodePath(path)
            val uploadUrl = "$baseUrl/storage/v1/object/$BUCKET/$encodedPath"
            val uploadResponse = request(
                method = "POST",
                endpoint = uploadUrl,
                firebaseToken = token,
                body = jpeg,
                contentType = "image/jpeg",
                extraHeaders = mapOf("x-upsert" to "false", "cache-control" to "3600")
            )
            require(uploadResponse.code in 200..299) {
                "Unggah foto widget gagal (${uploadResponse.code}). Periksa konfigurasi Supabase dan kebijakan Storage."
            }
            try {
                val signedUrl = createSignedUrl(path, token, SIGNED_URL_TTL_SECONDS)
                UploadedWidgetImage(objectId, path, signedUrl, jpeg)
            } catch (error: Throwable) {
                // Do not leave an uploaded object behind if generating its limited-time link fails.
                runCatching { deleteOwnObject(path, token) }
                throw error
            }
        }

    suspend fun createSignedUrl(path: String, expectedUid: String): String = withContext(Dispatchers.IO) {
        require(path.startsWith("users/$expectedUid/")) { "Foto hanya dapat ditandatangani oleh pemiliknya." }
        val user = FirebaseAuth.getInstance().currentUser ?: error("Masuk ke akun terlebih dahulu.")
        require(user.uid == expectedUid) { "Akun berubah. Coba ulangi." }
        val token = user.getIdToken(false).await().token ?: error("Token login tidak tersedia.")
        createSignedUrl(path, token, SIGNED_URL_TTL_SECONDS)
    }

    suspend fun deleteOwnObject(path: String, expectedUid: String) = withContext(Dispatchers.IO) {
        require(path.startsWith("users/$expectedUid/")) { "Penghapusan foto ditolak: bukan milik akun ini." }
        val user = FirebaseAuth.getInstance().currentUser ?: return@withContext
        if (user.uid != expectedUid) return@withContext
        val token = user.getIdToken(false).await().token ?: return@withContext
        deleteOwnObject(path, token)
    }

    suspend fun downloadSignedImage(signedUrl: String): ByteArray = withContext(Dispatchers.IO) {
        requireConfigured()
        val target = runCatching { URL(signedUrl) }.getOrElse { error("Tautan foto widget tidak valid.") }
        val expectedHost = runCatching { URL(baseUrl).host }.getOrNull()
        require(target.protocol == "https" && target.host.endsWith(".supabase.co") &&
            (expectedHost == null || target.host == expectedHost)) {
            "Tautan foto widget berasal dari host yang tidak diizinkan."
        }
        val response = request("GET", signedUrl, null, null, null)
        require(response.code in 200..299) { "Foto widget belum dapat diunduh (${response.code})." }
        require(response.bytes.isNotEmpty() && response.bytes.size <= MAX_IMAGE_BYTES) {
            "Ukuran foto widget tidak valid."
        }
        response.bytes
    }

    fun signedUrlExpiringSoon(signedUrl: String, withinSeconds: Long = 7L * 24L * 60L * 60L): Boolean {
        return runCatching {
            val query = URL(signedUrl).query.orEmpty().split('&')
            val token = query.firstOrNull { it.startsWith("token=") }?.substringAfter('=') ?: return false
            val pieces = token.split('.')
            if (pieces.size < 2) return false
            val payload = String(Base64.decode(pieces[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val expiry = JSONObject(payload).optLong("exp", 0L)
            expiry > 0L && expiry - System.currentTimeMillis() / 1000L <= withinSeconds
        }.getOrDefault(false)
    }

    private fun requireConfigured() {
        check(isConfigured()) {
            "Widget online belum dikonfigurasi. Isi SUPABASE_URL dan SUPABASE_PUBLISHABLE_KEY lalu build ulang dari GitHub Actions."
        }
    }

    private fun createSignedUrl(path: String, token: String, expiresInSeconds: Int): String {
        requireConfigured()
        val body = JSONObject().put("expiresIn", expiresInSeconds).toString().toByteArray(Charsets.UTF_8)
        val response = request(
            method = "POST",
            endpoint = "$baseUrl/storage/v1/object/sign/$BUCKET/${encodePath(path)}",
            firebaseToken = token,
            body = body,
            contentType = "application/json"
        )
        require(response.code in 200..299) {
            "Supabase tidak dapat membuat tautan foto (${response.code}). Periksa integrasi Firebase Auth dan aturan Storage."
        }
        val json = JSONObject(String(response.bytes, Charsets.UTF_8))
        val returned = json.optString("signedURL", json.optString("signedUrl", ""))
        require(returned.isNotBlank()) { "Supabase tidak mengembalikan tautan foto." }
        when {
            returned.startsWith("https://") -> returned
            returned.startsWith("/storage/v1/") -> baseUrl + returned
            returned.startsWith("/") -> "$baseUrl/storage/v1$returned"
            else -> "$baseUrl/storage/v1/$returned"
        }
    }

    private fun deleteOwnObject(path: String, token: String) {
        val body = JSONObject().put("prefixes", org.json.JSONArray().put(path)).toString().toByteArray(Charsets.UTF_8)
        val response = request(
            method = "DELETE",
            endpoint = "$baseUrl/storage/v1/object/$BUCKET",
            firebaseToken = token,
            body = body,
            contentType = "application/json"
        )
        require(response.code in 200..299) { "Pembersihan foto widget gagal (${response.code})." }
    }

    private data class HttpResult(val code: Int, val bytes: ByteArray)

    private fun request(
        method: String,
        endpoint: String,
        firebaseToken: String?,
        body: ByteArray?,
        contentType: String?,
        extraHeaders: Map<String, String> = emptyMap()
    ): HttpResult {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("apikey", publishableKey)
            if (firebaseToken != null) setRequestProperty("Authorization", "Bearer $firebaseToken")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            extraHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) doOutput = true
        }
        return try {
            if (body != null) connection.outputStream.use { output -> output.write(body) }
            val status = connection.responseCode
            val input: InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = input?.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    require(output.size() <= MAX_RESPONSE_BYTES) { "Respons layanan terlalu besar." }
                }
                output.toByteArray()
            } ?: ByteArray(0)
            HttpResult(status, bytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun compressWidgetImage(contentResolver: ContentResolver, uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("Foto tidak dapat dibuka.")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "File yang dipilih bukan gambar yang valid." }
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION * 2 || bounds.outHeight / sample > MAX_DIMENSION * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        var bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Foto tidak dapat dibaca.")
        try {
            val largest = maxOf(bitmap.width, bitmap.height)
            if (largest > MAX_DIMENSION) {
                val ratio = MAX_DIMENSION.toFloat() / largest.toFloat()
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
            var quality = 82
            var encoded: ByteArray
            do {
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                encoded = output.toByteArray()
                quality -= 7
            } while (encoded.size > MAX_IMAGE_BYTES && quality >= 54)
            require(encoded.size <= MAX_IMAGE_BYTES) { "Foto terlalu kompleks untuk widget gratis. Pilih foto lain." }
            encoded
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodePath(path: String): String = path.split('/').joinToString("/") {
        URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
    }

    companion object {
        const val BUCKET = "galeri-widget"
        const val MAX_IMAGE_BYTES = 950_000
        private const val MAX_DIMENSION = 1024
        private const val SIGNED_URL_TTL_SECONDS = 30 * 24 * 60 * 60
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 18_000
        private const val MAX_RESPONSE_BYTES = 1_200_000
    }
}
