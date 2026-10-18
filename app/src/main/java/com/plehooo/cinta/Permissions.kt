package com.plehooo.cinta

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun batteryUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

private fun isXiaomi(): Boolean {
    val m = Build.MANUFACTURER.lowercase()
    return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
}

private fun launch(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: Throwable) {
    false
}

private fun openAppDetails(context: Context) {
    launch(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
}

@Composable
fun PermissionsCard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cinta-local-state", Context.MODE_PRIVATE) }
    var notifOk by remember { mutableStateOf(notificationsGranted(context)) }
    var batteryOk by remember { mutableStateOf(batteryUnrestricted(context)) }
    var autostartDone by remember { mutableStateOf(prefs.getBoolean("autostart-done", false)) }
    val xiaomi = remember { isXiaomi() }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifOk = granted || notificationsGranted(context)
        if (!granted) {
            // Sudah pernah ditolak: sistem tidak menampilkan dialog lagi, arahkan ke pengaturan aplikasi.
            if (prefs.getBoolean("notif-denied", false)) openAppDetails(context)
            prefs.edit().putBoolean("notif-denied", true).apply()
        }
    }

    // Izin notifikasi diminta otomatis sekali saat pertama dibuka.
    LaunchedEffect(Unit) {
        if (!notifOk && !prefs.getBoolean("notif-asked", false)) {
            prefs.edit().putBoolean("notif-asked", true).apply()
            try { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } catch (_: Throwable) {}
        }
    }
    // Segarkan status setelah pengguna kembali dari layar pengaturan.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_500)
            notifOk = notificationsGranted(context)
            batteryOk = batteryUnrestricted(context)
        }
    }

    if (notifOk && batteryOk && (!xiaomi || autostartDone)) return

    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Izin agar foto masuk otomatis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Aktifkan sekali saja, supaya widget terbarui sendiri walau Cinta tertutup.", color = Color(0xFF796674))
            if (!notifOk) {
                Button(
                    onClick = { try { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } catch (_: Throwable) { openAppDetails(context) } },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("🔔  Izinkan notifikasi") }
            }
            if (!batteryOk) {
                Button(
                    onClick = {
                        val ok = launch(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)))
                        if (!ok && !launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppDetails(context)
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("🔋  Boleh jalan di latar belakang") }
            }
            if (xiaomi && !autostartDone) {
                Button(
                    onClick = {
                        val intent = Intent().setComponent(
                            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                        )
                        if (!launch(context, intent)) openAppDetails(context)
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("🚀  Aktifkan Mulai otomatis") }
                OutlinedButton(
                    onClick = {
                        prefs.edit().putBoolean("autostart-done", true).apply()
                        autostartDone = true
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Sudah saya aktifkan") }
            }
        }
    }
}
