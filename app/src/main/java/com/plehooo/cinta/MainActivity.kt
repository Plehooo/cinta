package com.plehooo.cinta

import io.github.jan.supabase.auth.auth
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CintaTheme { CintaHome() } }
    }
}

@androidx.compose.runtime.Composable
private fun CintaTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Color(0xFFAD3D78),
        onPrimary = Color.White,
        secondary = Color(0xFF7660A8),
        background = Color(0xFFFFF7FA),
        surface = Color.White,
        onSurface = Color(0xFF352530)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@androidx.compose.runtime.Composable
private fun CintaHome() {
    val context = LocalContext.current
    val repository = remember { PhotoSyncRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var userId by remember { mutableStateOf<String?>(null) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf<String?>(null) }
    var previewUri by remember { mutableStateOf<Uri?>(null) }
    var members by remember { mutableStateOf<List<CoupleMember>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmJoin by remember { mutableStateOf(false) }
    var expectedVersion by remember { mutableStateOf(0) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            previewUri = uri
            scope.launch {
                busy = true
                message = "Menyimpan foto ke antrean aman…"
                try {
                    val uid = userId ?: error("Silakan login kembali.")
                    val bytes = withContext(Dispatchers.IO) { PhotoFiles.encodeWebp(context, uri) }
                    val coupleId = repository.cachedActiveMembers(uid)
                        .firstOrNull { it.userId == uid && it.leftAt == null }?.coupleId
                        ?: error("Ruang pasangan tidak tersedia di perangkat.")
                    repository.queueOwnPhoto(coupleId, bytes, caption, expectedVersion)
                    message = "Foto tersimpan. Foto ini akan tampil di widget pasanganmu setelah sinkronisasi berhasil."
                } catch (_: Throwable) {
                    message = "Foto belum berhasil disimpan. Pastikan sudah terhubung dengan pasangan dan ruang penyimpanan cukup."
                } finally {
                    busy = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!SupabaseProvider.configured) {
            message = "Konfigurasi Supabase belum diisi. Tambahkan supabaseUrl dan supabasePublishableKey saat build."
            return@LaunchedEffect
        }
        try {
            SupabaseProvider.client.auth.sessionStatus.collect { _ ->
                // currentUserOrNull also preserves the cached identity during a temporary refresh/network failure.
                userId = repository.currentUid()
                if (userId != null) {
                    PhotoSyncRepository.startRealtimeObserver(context.applicationContext)
                    // Restore last-known membership locally first so an offline user can queue a photo.
                    members = repository.cachedActiveMembers(userId!!)
                    try {
                        members = repository.activeMembers()
                        repository.refreshPartnerPhoto()
                        SyncScheduler.enqueueNow(context.applicationContext)
                    } catch (_: Throwable) {
                        // Keep the cached route and widget image when a request fails.
                    }
                } else {
                    members = emptyList()
                    inviteCode = null
                }
            }
        } catch (_: Throwable) {
            message = "Sesi belum dapat dibaca. Periksa konfigurasi Supabase dan koneksi internet."
        }
    }

    val myMember = members.firstOrNull { it.userId == userId && it.leftAt == null }
    val partnerMember = myMember?.let { mine ->
        members.firstOrNull { it.coupleId == mine.coupleId && it.userId != userId && it.leftAt == null }
    }
    val localStatus = userId?.let(repository::photoStatus).orEmpty()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Cinta", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            Text("Satu foto dari orang tersayang, langsung di layar utama.", style = MaterialTheme.typography.bodyLarge)

            if (!SupabaseProvider.configured) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE7ED))) {
                    Text(
                        "Supabase belum dikonfigurasi. Lihat README.md pada paket source untuk mengisi SUPABASE_URL dan SUPABASE_PUBLISHABLE_KEY. Tidak ada data tiruan yang digunakan.",
                        modifier = Modifier.padding(16.dp), color = Color(0xFF7D214A)
                    )
                }
            } else if (userId == null) {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Masuk atau buat akun", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Kata sandi") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Button(
                            enabled = !busy && email.contains('@') && password.length >= 8,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    message = ""
                                    try {
                                        repository.signIn(email, password)
                                        userId = repository.currentUid()
                                        if (userId != null) {
                                            message = "Berhasil masuk."
                                            SyncScheduler.enqueueNow(context.applicationContext)
                                        }
                                    } catch (_: Throwable) {
                                        message = "Login gagal. Periksa email, kata sandi, dan koneksi. Jika belum punya akun, pilih Daftar."
                                    } finally { busy = false }
                                }
                            }, modifier = Modifier.fillMaxWidth()
                        ) { Text(if (busy) "Memproses…" else "Masuk") }
                        OutlinedButton(
                            enabled = !busy && email.contains('@') && password.length >= 8,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        repository.signUp(email, password)
                                        userId = repository.currentUid()
                                        message = if (userId == null) "Pendaftaran dikirim. Periksa email untuk konfirmasi, lalu masuk kembali." else "Akun dibuat."
                                    } catch (_: Throwable) {
                                        message = "Pendaftaran gagal. Gunakan email valid dan kata sandi minimal 8 karakter."
                                    } finally { busy = false }
                                }
                            }, modifier = Modifier.fillMaxWidth()
                        ) { Text("Daftar") }
                    }
                }
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Pengaturan foto", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Akun: $email", style = MaterialTheme.typography.bodySmall, color = Color(0xFF796674))
                        Text("Ganti Foto Saya", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Foto ini akan tampil di widget pasanganmu. Widget di HP-mu hanya menampilkan foto pasangan, bukan foto milikmu sendiri.")

                        if (previewUri != null) {
                            AsyncImage(model = previewUri, contentDescription = "Preview foto yang akan dikirim ke pasangan", modifier = Modifier.fillMaxWidth().height(210.dp))
                        } else {
                            Box(
                                Modifier.fillMaxWidth().height(150.dp).background(Color(0xFFFFEAF2), RoundedCornerShape(18.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("♡", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        OutlinedTextField(
                            value = caption, onValueChange = { caption = it.take(100) },
                            label = { Text("Caption (opsional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled = !busy && myMember != null,
                            onClick = {
                                scope.launch {
                                    expectedVersion = repository.ownPhotoVersionOrCached(userId!!)
                                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                            }, modifier = Modifier.fillMaxWidth()
                        ) { Text("Ganti Foto Saya") }
                        Text(localStatus, style = MaterialTheme.typography.bodySmall, color = Color(0xFF856477))
                        Text("Foto lokal disimpan privat dan dipertahankan jika koneksi gagal. Widget pasangan berubah setelah upload dan metadata berhasil.", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Hubungkan pasangan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        when {
                            myMember == null -> {
                                Text("Buat pasangan baru, lalu kirim kode undangan rahasia kepada pasanganmu.")
                                Button(enabled = !busy, onClick = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            repository.createCouple()
                                            members = repository.activeMembers()
                                            message = "Ruang pasangan dibuat. Buat kode undangan berikutnya."
                                        } catch (_: Throwable) { message = "Tidak bisa membuat pasangan. Akun mungkin sudah tergabung atau koneksi gagal." }
                                        finally { busy = false }
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Buat pasangan") }
                                OutlinedTextField(value = codeInput, onValueChange = { codeInput = it.filter { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' }.take(64) }, label = { Text("Kode dari pasangan") }, modifier = Modifier.fillMaxWidth())
                                OutlinedButton(enabled = !busy && codeInput.length >= 16, onClick = { confirmJoin = true }, modifier = Modifier.fillMaxWidth()) { Text("Hubungkan dengan kode") }
                            }
                            partnerMember == null -> {
                                Text("Ruang pasangan sudah dibuat. Bagikan kode satu kali pakai di bawah ini; kode kedaluwarsa dalam 10 menit.")
                                Button(enabled = !busy, onClick = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            inviteCode = repository.createInviteCode()
                                            message = "Kode dibuat. Kirim hanya kepada pasangan yang benar."
                                        } catch (_: Throwable) { message = "Kode belum berhasil dibuat. Pastikan ruang masih berisi satu anggota." }
                                        finally { busy = false }
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Buat kode undangan") }
                                inviteCode?.let { code ->
                                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEAF2))) {
                                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                            Text("Kode rahasia hanya ditampilkan di sini. Jangan unggah ke publik.", style = MaterialTheme.typography.bodySmall)
                                            OutlinedButton(onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("Cinta pairing code", code))
                                                message = "Kode disalin. Kirim secara pribadi kepada pasanganmu."
                                            }, modifier = Modifier.fillMaxWidth()) { Text("Salin kode") }
                                        }
                                    }
                                }
                            }
                            else -> {
                                Text("Pasangan terhubung ❤️", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("HP ini menampilkan foto pasangan. Saat kamu mengganti foto sendiri, hanya widget HP pasangan yang menerima foto itu.")
                                OutlinedButton(enabled = !busy, onClick = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            repository.refreshPartnerPhoto()
                                            message = "Sinkronisasi foto pasangan selesai jika ada versi baru."
                                        } catch (_: Throwable) { message = "Belum tersambung ke server; cache foto pasangan tetap dipertahankan." }
                                        finally { busy = false }
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Sinkronkan foto pasangan") }
                                OutlinedButton(enabled = !busy, onClick = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            repository.leaveCouple()
                                            members = emptyList()
                                            inviteCode = null
                                            message = "Pasangan diputus dan cache lokal dibersihkan."
                                        } catch (_: Throwable) { message = "Tidak bisa memutus pasangan saat ini. Coba lagi ketika online." }
                                        finally { busy = false }
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Putuskan pasangan", color = Color(0xFF9B2854)) }
                            }
                        }
                    }
                }

                OutlinedButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            repository.signOut()
                            userId = null
                            members = emptyList()
                            inviteCode = null
                            message = "Keluar. Cache foto pasangan telah dibersihkan dari widget."
                        } catch (_: Throwable) { message = "Tidak bisa keluar saat ini." }
                        finally { busy = false }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Keluar") }
            }

            if (message.isNotBlank()) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEAF2)), shape = RoundedCornerShape(16.dp)) {
                    Text(message, modifier = Modifier.padding(14.dp), color = Color(0xFF74244C))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Widget home-screen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Setelah memasang aplikasi: tekan lama layar utama → Widget → Cinta Widget. Pilih ukuran kecil atau sedang.", style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (confirmJoin) {
        AlertDialog(
            onDismissRequest = { confirmJoin = false },
            title = { Text("Pastikan identitas pasangan") },
            text = { Text("Lanjut hanya jika kode dikirim langsung oleh pasangan yang kamu kenal. Setelah terhubung, kalian dapat melihat foto satu sama lain pada widget masing-masing.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmJoin = false
                    scope.launch {
                        busy = true
                        try {
                            val joined = repository.redeemCode(codeInput)
                            if (joined) {
                                codeInput = ""
                                members = repository.activeMembers()
                                message = "Pasangan berhasil dihubungkan."
                            } else message = "Kode tidak valid, kedaluwarsa, sudah dipakai, atau batas percobaan tercapai."
                        } catch (_: Throwable) { message = "Kode belum dapat dipakai. Periksa koneksi dan coba lagi." }
                        finally { busy = false }
                    }
                }) { Text("Saya yakin, hubungkan") }
            },
            dismissButton = { TextButton(onClick = { confirmJoin = false }) { Text("Batal") } }
        )
    }
}
