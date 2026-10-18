package com.plehooo.cinta

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SoftPink = Color(0xFFFFEAF2)
private val Muted = Color(0xFF796674)
private val Danger = Color(0xFF9B2854)

@Composable
private fun CintaCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

private fun copyCode(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Cinta pairing code", code))
}

private fun shareCode(context: Context, code: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Kode undangan Cinta (berlaku 10 menit): " + code)
    }
    context.startActivity(Intent.createChooser(send, "Bagikan kode").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun CintaHomeNew() {
    val context = LocalContext.current
    val repository = remember { PhotoSyncRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var userId by remember { mutableStateOf<String?>(null) }
    var codeInput by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf<String?>(null) }
    var previewUri by remember { mutableStateOf<Uri?>(null) }
    var members by remember { mutableStateOf<List<CoupleMember>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmJoin by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var expectedVersion by remember { mutableStateOf(0) }

    var showSource by remember { mutableStateOf(false) }
    var cameraUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Uri?>(null) }
    val savePhoto: (Uri) -> Unit = { uri ->
        previewUri = uri
        scope.launch {
            busy = true
            message = "Menyimpan foto…"
            try {
                val uid = userId ?: error("Belum siap.")
                val bytes = withContext(Dispatchers.IO) { PhotoFiles.encodeWebp(context, uri) }
                val coupleId = repository.cachedActiveMembers(uid)
                    .firstOrNull { it.userId == uid && it.leftAt == null }?.coupleId
                    ?: error("Ruang pasangan tidak tersedia.")
                repository.queueOwnPhoto(coupleId, bytes, "", expectedVersion)
                message = "Foto tersimpan dan sedang dikirim ke widget pasanganmu."
            } catch (e: Throwable) {
                message = "Foto belum berhasil disimpan (${e.message}). Pastikan sudah terhubung dengan pasangan."
            } finally {
                busy = false
            }
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val taken = cameraUri
        if (ok && taken != null) savePhoto(taken)
    }
    val launchCamera: () -> Unit = {
        try {
            val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = java.io.File.createTempFile("cinta_", ".jpg", dir)
            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            cameraUri = uri
            cameraLauncher.launch(uri)
        } catch (_: Throwable) {
            message = "Kamera tidak tersedia di HP ini."
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            previewUri = uri
            scope.launch {
                busy = true
                message = "Menyimpan foto…"
                try {
                    val uid = userId ?: error("Belum siap.")
                    val bytes = withContext(Dispatchers.IO) { PhotoFiles.encodeWebp(context, uri) }
                    val coupleId = repository.cachedActiveMembers(uid)
                        .firstOrNull { it.userId == uid && it.leftAt == null }?.coupleId
                        ?: error("Ruang pasangan tidak tersedia.")
                    repository.queueOwnPhoto(coupleId, bytes, "", expectedVersion)
                    message = "Foto tersimpan dan sedang dikirim ke widget pasanganmu."
                } catch (e: Throwable) {
                    message = "Foto belum berhasil disimpan (${e.message}). Pastikan sudah terhubung dengan pasangan."
                } finally {
                    busy = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!SupabaseProvider.configured) return@LaunchedEffect
        RealtimeService.start(context.applicationContext)
        try {
            SupabaseProvider.client.auth.awaitInitialization()
            repository.signInAnonymouslyIfNeeded()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            message = "Belum bisa menyiapkan akun. Pastikan internet aktif."
        }
    }

    LaunchedEffect(Unit) {
        if (!SupabaseProvider.configured) return@LaunchedEffect
        try {
            SupabaseProvider.client.auth.sessionStatus.collect { _ ->
                userId = repository.currentUid()
                if (userId != null) {
                    PhotoSyncRepository.startRealtimeObserver(context.applicationContext)
                    members = repository.cachedActiveMembers(userId!!)
                    try {
                        members = repository.activeMembers()
                        repository.refreshPartnerPhoto()
                        PushTokens.sync(context.applicationContext)
                        SyncScheduler.enqueueNow(context.applicationContext)
                    } catch (_: Throwable) {
                    }
                } else {
                    members = emptyList()
                    inviteCode = null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            message = "Sesi belum dapat dibaca. Periksa koneksi internet."
        }
    }

    LaunchedEffect(userId) {
        if (userId == null) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(10_000)
            try {
                val fresh = repository.activeMembers()
                val key = { l: List<CoupleMember> -> l.map { it.coupleId + ":" + it.userId }.toSet() }
                if (key(fresh) != key(members)) {
                    members = fresh
                    runCatching { repository.refreshPartnerPhoto() }
                    if (fresh.none { it.userId == userId }) { previewUri = null; inviteCode = null }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
    }

    val myMember = members.firstOrNull { it.userId == userId && it.leftAt == null }
    val partnerMember = myMember?.let { mine ->
        members.firstOrNull { it.coupleId == mine.coupleId && it.userId != userId && it.leftAt == null }
    }
    val localStatus = userId?.let { repository.photoStatus(it) }.orEmpty()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Cinta ♥", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                Text("Satu foto dari orang tersayang, langsung di layar utama.", style = MaterialTheme.typography.bodyMedium, color = Muted)
            }

            if (message.isNotBlank()) {
                Card(colors = CardDefaults.cardColors(containerColor = SoftPink), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, modifier = Modifier.weight(1f).padding(vertical = 12.dp), color = Color(0xFF74244C))
                        TextButton(onClick = { message = "" }) { Text("OK") }
                    }
                }
            }

            when {
                !SupabaseProvider.configured -> CintaCard {
                    Text("Aplikasi belum dikonfigurasi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("SUPABASE_URL dan SUPABASE_PUBLISHABLE_KEY belum terisi saat build.", color = Muted)
                }

                userId == null -> CintaCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Text("Menyiapkan…", style = MaterialTheme.typography.titleMedium)
                    }
                }

                myMember == null -> CintaCard {
                    Text("Hubungkan dengan pasangan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Satu orang membuat undangan, yang lain memasukkan kodenya.", color = Muted)
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    repository.createCouple()
                                    members = repository.activeMembers()
                                    inviteCode = repository.createInviteCode()
                                    message = "Undangan dibuat. Kirim kodenya ke pasanganmu."
                                } catch (_: Throwable) {
                                    message = "Belum bisa membuat undangan. Periksa internet lalu coba lagi."
                                } finally { busy = false }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text(if (busy) "Memproses…" else "💌  Buat undangan") }
                    OutlinedButton(
                        onClick = { showJoin = !showJoin },
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text("🔑  Punya kode dari pasangan?") }
                    if (showJoin) {
                        OutlinedTextField(
                            value = codeInput,
                            onValueChange = { codeInput = it.filter { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' }.take(64) },
                            label = { Text("Tempel kode di sini") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled = !busy && codeInput.length >= 16,
                            onClick = { confirmJoin = true },
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) { Text("Hubungkan") }
                    }
                }

                partnerMember == null -> CintaCard {
                    Text("Menunggu pasangan…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Kirim kode ini ke pasanganmu. Sekali pakai dan berlaku 10 menit.", color = Muted)
                    val code = inviteCode
                    if (code != null) {
                        Box(
                            Modifier.fillMaxWidth().background(SoftPink, RoundedCornerShape(16.dp)).padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = Color(0xFF74244C))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    copyCode(context, code)
                                    message = "Kode disalin."
                                },
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) { Text("Salin") }
                            Button(
                                onClick = { shareCode(context, code) },
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) { Text("Bagikan") }
                        }
                    }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    inviteCode = repository.createInviteCode()
                                    message = "Kode baru dibuat."
                                } catch (_: Throwable) {
                                    message = "Belum bisa membuat kode. Coba lagi."
                                } finally { busy = false }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text(if (code == null) "Tampilkan kode undangan" else "Buat kode baru") }
                }

                else -> {
                    CintaCard {
                        Text("Foto untuk pasanganmu", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (previewUri != null) {
                            AsyncImage(
                                model = previewUri,
                                contentDescription = "Foto yang kamu kirim",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp))
                            )
                        } else {
                            Box(
                                Modifier.fillMaxWidth().height(160.dp).background(SoftPink, RoundedCornerShape(18.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("♡", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Button(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    expectedVersion = repository.ownPhotoVersionOrCached(userId!!)
                                    showSource = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) { Text(if (busy) "Memproses…" else "📷  Ganti foto saya") }
                        if (localStatus.isNotBlank()) {
                            Text(localStatus, style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }

                    CintaCard {
                        Text("Pasangan terhubung ❤️", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("Foto pasangan otomatis tampil di widget HP-mu.", color = Muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                enabled = !busy,
                                onClick = { confirmLeave = true },
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) { Text("Putuskan", color = Danger) }
                        }
                    }
                }
            }

            PermissionsCard()

            Card(colors = CardDefaults.cardColors(containerColor = SoftPink), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Pasang widget", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Tekan lama layar utama, pilih Widget, lalu Cinta.", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    }

    if (showSource) {
        AlertDialog(
            onDismissRequest = { showSource = false },
            title = { Text("Ambil foto dari") },
            text = { Text("Pilih sumber foto untuk dikirim ke pasanganmu.") },
            confirmButton = {
                TextButton(onClick = {
                    showSource = false
                    launchCamera()
                }) { Text("📷 Kamera") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSource = false
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("🖼️ Galeri") }
            }
        )
    }

    if (confirmJoin) {
        AlertDialog(
            onDismissRequest = { confirmJoin = false },
            title = { Text("Pastikan itu pasanganmu") },
            text = { Text("Lanjut hanya jika kode dikirim langsung oleh pasangan yang kamu kenal. Setelah terhubung, kalian melihat foto satu sama lain di widget.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmJoin = false
                    scope.launch {
                        busy = true
                        try {
                            val joined = repository.redeemCode(codeInput)
                            if (joined) {
                                codeInput = ""
                                showJoin = false
                                members = repository.activeMembers()
                                message = "Berhasil terhubung ❤️"
                            } else message = "Kode tidak valid, kedaluwarsa, atau sudah dipakai."
                        } catch (_: Throwable) {
                            message = "Kode belum dapat dipakai. Periksa koneksi lalu coba lagi."
                        } finally { busy = false }
                    }
                }) { Text("Ya, hubungkan") }
            },
            dismissButton = { TextButton(onClick = { confirmJoin = false }) { Text("Batal") } }
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Putuskan pasangan?") },
            text = { Text("Foto pasangan di widget akan dihapus dari HP ini. Kalian bisa terhubung lagi dengan kode baru.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    scope.launch {
                        busy = true
                        try {
                            repository.leaveCouple()
                            members = emptyList()
                            inviteCode = null
                            previewUri = null
                            message = "Pasangan diputus."
                        } catch (_: Throwable) {
                            message = "Belum bisa memutus. Coba lagi saat online."
                        } finally { busy = false }
                    }
                }) { Text("Putuskan", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Batal") } }
        )
    }
}
