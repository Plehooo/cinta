package com.plehooo.galericinta.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.glance.GlanceId
import androidx.glance.LocalSize
import androidx.glance.GlanceModifier
import androidx.glance.background
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.defaultWeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.plehooo.galericinta.MainActivity

class GalleryWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 100.dp), DpSize(250.dp, 150.dp), DpSize(340.dp, 220.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val slot1 = WidgetCache.loadBitmap(context, "slot1")
        val slot2 = WidgetCache.loadBitmap(context, "slot2")
        provideContent {
            val compact = LocalSize.current.width < 220.dp
            Column(
                modifier = GlanceModifier.fillMaxSize()
                    .background(ColorProvider(Color(0xFFFFF2F8)))
                    .padding(if (compact) 8.dp else 12.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Galeri Cinta ♥", style = TextStyle(color = ColorProvider(Color(0xFF8B2359))))
                Spacer(GlanceModifier.height(if (compact) 4.dp else 8.dp))
                if (compact) {
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (slot1 != null) Image(ImageProvider(slot1), "Foto Slot 1", modifier = GlanceModifier.size(60.dp), contentScale = ContentScale.Crop)
                        else PlaceholderSlot("Slot 1", compact = true)
                        Column(modifier = GlanceModifier.defaultWeight().padding(start = 8.dp)) {
                            Text("Kenangan pilihan", style = TextStyle(color = ColorProvider(Color(0xFF8B2359))))
                            Text("Slot 1", style = TextStyle(color = ColorProvider(Color(0xFFD63384))))
                        }
                    }
                } else {
                    Row(modifier = GlanceModifier.defaultWeight().fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (slot1 != null) Image(ImageProvider(slot1), "Foto Slot 1", modifier = GlanceModifier.size(76.dp), contentScale = ContentScale.Crop)
                            else PlaceholderSlot("Slot 1")
                            Text("Slot 1", style = TextStyle(color = ColorProvider(Color(0xFF8B2359))))
                        }
                        Spacer(GlanceModifier.width(8.dp))
                        Column(modifier = GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (slot2 != null) Image(ImageProvider(slot2), "Foto Slot 2", modifier = GlanceModifier.size(76.dp), contentScale = ContentScale.Crop)
                            else PlaceholderSlot("Slot 2")
                            Text("Slot 2", style = TextStyle(color = ColorProvider(Color(0xFF8B2359))))
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun PlaceholderSlot(label: String, compact: Boolean = false) {
        Column(
            modifier = GlanceModifier.size(if (compact) 48.dp else 76.dp).background(ColorProvider(Color(0xFFF7D7E8))),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("♥", style = TextStyle(color = ColorProvider(Color(0xFFD63384))))
            if (!compact) Text(label, style = TextStyle(color = ColorProvider(Color(0xFF8B2359))))
        }
    }
}

class GalleryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GalleryWidget()
}
