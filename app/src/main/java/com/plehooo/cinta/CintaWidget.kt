package com.plehooo.cinta

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.io.File
import kotlin.math.abs

class CintaWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        provideContent {
            val state = currentState<Preferences>()
            val storedPath = state[stringPreferencesKey("partner_photo_path")].orEmpty()
            // Kalau state widget kosong/hilang, tetap tampilkan foto terbaru dari cache disk.
            val path = storedPath.takeIf { it.isNotBlank() && File(it).isFile }
                ?: WidgetCache.newestCachedFile(context)?.absolutePath.orEmpty()
            val stamp = path.takeIf { it.isNotBlank() }?.let { File(it).lastModified() } ?: 0L
            val version = state[intPreferencesKey("partner_photo_version")] ?: 0
            val hash = state[stringPreferencesKey("partner_photo_hash")].orEmpty()
            val size = LocalSize.current
            val aspect = (size.width.value / size.height.value.coerceAtLeast(1f)).coerceIn(0.4f, 3f)
            val key = (aspect * 100).toInt()
            val bitmap = remember(path, version, hash, key, stamp) {
                path.takeIf { it.isNotBlank() }
                    ?.let(::File)
                    ?.takeIf { it.isFile && it.length() > 0L }
                    ?.let { composeFullBleed(it, aspect) }
            }

            if (bitmap != null) {
                Image(
                    provider = ImageProvider(bitmap),
                    contentDescription = "Foto pasangan",
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.fillMaxSize().cornerRadius(22.dp)
                )
            } else {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(ColorProvider(Color(0xFFFFF0F6)))
                        .cornerRadius(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("♡", style = TextStyle(color = ColorProvider(Color(0xFFC85289)), fontWeight = FontWeight.Bold))
                    Text("Foto pasangan", style = TextStyle(color = ColorProvider(Color(0xFF5F284C)), fontWeight = FontWeight.Medium))
                }
            }
        }
    }
}

// Hasil bitmap selalu berbentuk sama dengan widget: foto utuh di tengah, sisa ruang diisi versi buramnya.
private fun composeFullBleed(file: File, aspect: Float): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 720) sample *= 2
    val src = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null

    val longEdge = 480
    val ow = if (aspect >= 1f) longEdge else (longEdge * aspect).toInt().coerceAtLeast(1)
    val oh = if (aspect >= 1f) (longEdge / aspect).toInt().coerceAtLeast(1) else longEdge
    val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    val srcAspect = src.width.toFloat() / src.height.toFloat()
    val cover: Rect = if (srcAspect > aspect) {
        val w = (src.height * aspect).toInt().coerceIn(1, src.width)
        val left = (src.width - w) / 2
        Rect(left, 0, left + w, src.height)
    } else {
        val h = (src.width / aspect).toInt().coerceIn(1, src.height)
        val top = (src.height - h) / 2
        Rect(0, top, src.width, top + h)
    }

    if (abs(srcAspect / aspect - 1f) < 0.12f) {
        canvas.drawBitmap(src, cover, Rect(0, 0, ow, oh), paint)
    } else {
        val tinyW = if (aspect >= 1f) 32 else (32 * aspect).toInt().coerceAtLeast(1)
        val tinyH = if (aspect >= 1f) (32 / aspect).toInt().coerceAtLeast(1) else 32
        val tiny = Bitmap.createBitmap(tinyW, tinyH, Bitmap.Config.ARGB_8888)
        Canvas(tiny).drawBitmap(src, cover, Rect(0, 0, tinyW, tinyH), paint)
        canvas.drawBitmap(tiny, null, Rect(0, 0, ow, oh), paint)
        tiny.recycle()
        canvas.drawColor(android.graphics.Color.argb(40, 0, 0, 0))

        val scale = minOf(ow.toFloat() / src.width, oh.toFloat() / src.height)
        val fw = (src.width * scale).toInt().coerceAtLeast(1)
        val fh = (src.height * scale).toInt().coerceAtLeast(1)
        val left = (ow - fw) / 2
        val top = (oh - fh) / 2
        canvas.drawBitmap(src, null, Rect(left, top, left + fw, top + fh), paint)
    }
    src.recycle()
    return out
}
