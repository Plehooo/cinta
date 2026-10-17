package com.plehooo.cinta

import android.graphics.Color
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.background
import java.io.File
import android.graphics.Bitmap
import android.graphics.BitmapFactory

class CintaWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(110.dp, 120.dp), DpSize(250.dp, 165.dp))
    )
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        provideContent {
            val state = currentState<Preferences>()
            val path = state[stringPreferencesKey("partner_photo_path")].orEmpty()
            val caption = state[stringPreferencesKey("partner_caption")].orEmpty()
            val version = state[intPreferencesKey("partner_photo_version")] ?: 0
            val file = path.takeIf { it.isNotBlank() }?.let(::File)
            // Keep bitmap small enough for RemoteViews' Binder payload when launching the widget.
            val widgetBitmap = file?.takeIf { it.isFile && it.length() > 0L }?.let(::decodeWidgetBitmap)
            val wide = LocalSize.current.width >= 180.dp

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(ComposeColor(255, 240, 246)))
                    .cornerRadius(22.dp)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (widgetBitmap != null) {
                    Image(
                        provider = ImageProvider(widgetBitmap),
                        contentDescription = "Foto pasangan",
                        contentScale = ContentScale.Crop,
                        modifier = GlanceModifier.fillMaxWidth().height(if (wide) 112.dp else 82.dp)
                    )
                    if (wide && caption.isNotBlank()) {
                        Text(
                            text = caption.take(100),
                            style = TextStyle(color = ColorProvider(ComposeColor(95, 40, 76)), fontWeight = FontWeight.Medium),
                            maxLines = 2
                        )
                    }
                    Text(
                        text = "♥  ${if (version > 0) "Tersinkron" else "Tersimpan"}",
                        style = TextStyle(color = ColorProvider(ComposeColor(164, 52, 105)), fontWeight = FontWeight.Medium)
                    )
                } else {
                    Text("♡", style = TextStyle(color = ColorProvider(ComposeColor(200, 82, 137)), fontWeight = FontWeight.Bold))
                    Text("Foto pasangan", style = TextStyle(color = ColorProvider(ComposeColor(95, 40, 76)), fontWeight = FontWeight.Medium))
                    Text("Atur di Cinta Widget", style = TextStyle(color = ColorProvider(ComposeColor(120, 95, 110))))
                }
            }
        }
    }
}

private fun decodeWidgetBitmap(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 384) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}
