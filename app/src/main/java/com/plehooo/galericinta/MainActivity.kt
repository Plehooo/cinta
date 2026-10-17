package com.plehooo.galericinta

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File
import androidx.core.content.FileProvider
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.storage.FirebaseStorage
import com.plehooo.galericinta.domain.MediaItem
import com.plehooo.galericinta.domain.SlotConfig
import com.plehooo.galericinta.ui.GalleryUiState
import com.plehooo.galericinta.ui.GalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private val Rose = androidx.compose.ui.graphics.Color(0xFFD63384)
private val DeepRose = androidx.compose.ui.graphics.Color(0xFF8B2359)
private val SoftPink = androidx.compose.ui.graphics.Color(0xFFFFF2F8)
private val Lavender = androidx.compose.ui.graphics.Color(0xFF8B5CF6)

class MainActivity : ComponentActivity() {
    private val galleryViewModel: GalleryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        galleryViewModel.configure()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Rose,
                    secondary = Lavender,
                    background = SoftPink,
                    surface = androidx.compose.ui.graphics.Color.White,
                    onPrimary = androidx.compose.ui.graphics.Color.White,
                    onBackground = DeepRose,
                    onSurface = DeepRose
                )
            ) {
                val state by galleryViewModel.state.collectAsState()
                Surface(modifier = Modifier.fillMaxSize(), color = SoftPink) {
                    GalleryRoot(galleryViewModel, state)
                }
            }
        }
    }
}

@Composable
private fun GalleryRoot(vm: GalleryViewModel, state: GalleryUiState) {
    var continueWithoutPairing by rememberSaveable { mutableStateOf(false) }
    when {
        !state.firebaseReady -> FirebaseSetupScreen()
        state.userId == null -> AuthScreen(vm, state)
        !state.profileLoaded -> CenterLoading("Menyiapkan galeri aman…")
        state.coupleId.isNullOrBlank() && !continueWithoutPairing ->
            PairingScreen(vm, state, onContinuePrivate = { continueWithoutPairing = true })
        else -> MainGalleryScreen(vm, state)
    }
}

@Composable
private fun FirebaseSetupScreen() {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        BrandHeader()
        Spacer(Modifier.height(24.dp))
        Text("Sambungkan Firebase", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("Source project sudah disiapkan, tetapi autentikasi dan sinkronisasi online belum dapat aktif sebelum konfigurasi Firebase milikmu dipasang.")
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Application ID", fontWeight = FontWeight.Bold)
                Text("com.plehooo.galericinta")
                Text("1. Buat project di Firebase Console dan daftarkan ID di atas.")
                Text("2. Unduh google-services.json dan letakkan di folder app/.")
                Text("3. Aktifkan Email/Password Authentication, Firestore, Storage, dan deploy Functions + Rules sesuai README.")
                Text("4. Jalankan ulang build dari GitHub Actions.")
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Tidak ada akun, foto, atau kode undangan dummy yang dibuat di mode ini.", color = DeepRose, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AuthScreen(vm: GalleryViewModel, state: GalleryUiState) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var createAccount by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        BrandHeader()
        Spacer(Modifier.height(24.dp))
        Text(if (createAccount) "Buat akun" else "Selamat datang kembali", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Kenangan berdua, dengan ruang privat yang tetap terjaga.", color = DeepRose.copy(alpha = 0.8f))
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Kata sandi (min. 6 karakter)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
        Spacer(Modifier.height(16.dp))
        Button(onClick = { vm.signIn(email, password, createAccount) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (createAccount) "Daftar" else "Masuk")
        }
        TextButton(onClick = { createAccount = !createAccount }, modifier = Modifier.fillMaxWidth()) {
            Text(if (createAccount) "Sudah punya akun? Masuk" else "Belum punya akun? Daftar")
        }
        TextButton(onClick = { vm.resetPassword(email) }, modifier = Modifier.fillMaxWidth()) { Text("Lupa kata sandi") }
        MessageBanner(state.message, vm::clearMessage)
        if (state.busy) LinearBusy()
    }
}

@Composable
private fun PairingScreen(vm: GalleryViewModel, state: GalleryUiState, onContinuePrivate: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        BrandHeader()
        Spacer(Modifier.height(24.dp))
        Text("Hubungkan pasangan", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Kedua orang perlu memiliki akun sendiri. Kode berlaku 15 menit dan hanya dapat digunakan sekali.")
        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Undang pasangan", fontWeight = FontWeight.Bold)
                Button(onClick = { vm.createInvite() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Buat kode undangan") }
                state.inviteCode?.let {
                    Text("Kode undangan", fontWeight = FontWeight.SemiBold)
                    Text(it, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Rose)
                    Text("Kirim kode ini langsung kepada pasangan. Jangan unggah ke tempat umum.")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Masukkan kode pasangan", fontWeight = FontWeight.Bold)
                OutlinedTextField(code, { code = it.uppercase() }, label = { Text("Kode undangan") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.redeemInvite(code) }, enabled = !state.busy && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Hubungkan akun") }
            }
        }
        MessageBanner(state.message, vm::clearMessage)
        TextButton(onClick = onContinuePrivate, modifier = Modifier.fillMaxWidth()) { Text("Lanjut ke Galeri Saya dulu") }
        TextButton(onClick = { vm.signOut() }, modifier = Modifier.fillMaxWidth()) { Text("Keluar akun") }
        if (state.busy) LinearBusy()
    }
}

private enum class Tab(val title: String) {
    MY_GALLERY("Galeri Saya"), SHARED_GALLERY("Galeri Bersama"), PINNED("Yang Dipasang"), PROFILE("Profil")
}

@Composable
private fun MainGalleryScreen(vm: GalleryViewModel, state: GalleryUiState) {
    var tabName by rememberSaveable { mutableStateOf(Tab.MY_GALLERY.name) }
    val availableTabs = Tab.entries.filter { it != Tab.SHARED_GALLERY || !state.coupleId.isNullOrBlank() }
    val tab = availableTabs.firstOrNull { it.name == tabName } ?: Tab.MY_GALLERY
    var caption by rememberSaveable { mutableStateOf("") }
    var pendingShare by remember { mutableStateOf<MediaItem?>(null) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) {
            vm.uploadMediaBatch(uris, shared = tab == Tab.SHARED_GALLERY, caption = caption)
            caption = ""
        }
    }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri
        if (saved && uri != null) vm.uploadMedia(context, uri, shared = tab == Tab.SHARED_GALLERY, caption = caption)
        cameraUri = null
        caption = ""
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Galeri Cinta ♥", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = DeepRose)
                Text("Kenangan tersimpan dengan penuh kendali", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { vm.refresh() }) { Text("Muat ulang") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            availableTabs.forEach { option ->
                if (option == tab) Button(onClick = { tabName = option.name }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 7.dp)) { Text(option.title, fontSize = 12.sp) }
                else OutlinedButton(onClick = { tabName = option.name }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 7.dp)) { Text(option.title, fontSize = 12.sp) }
            }
        }
        MessageBanner(state.message, vm::clearMessage)
        if (state.busy) LinearBusy()
        when (tab) {
            Tab.MY_GALLERY, Tab.SHARED_GALLERY -> {
                val isSharedGallery = tab == Tab.SHARED_GALLERY
                val media = if (isSharedGallery) state.sharedMedia else state.privateMedia
                Spacer(Modifier.height(8.dp))
                Text(if (isSharedGallery) "Terlihat oleh kedua anggota pasangan" else "Hanya bisa dibuka dari akun ini", fontWeight = FontWeight.Medium, color = DeepRose)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = caption, onValueChange = { caption = it.take(500) },
                    label = { Text("Caption (opsional) untuk unggahan berikutnya") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        Text("Pilih media", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = {
                        runCatching {
                            val dir = File(context.cacheDir, "camera")
                            dir.mkdirs()
                            val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
                            cameraUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            cameraLauncher.launch(cameraUri!!)
                        }.onFailure { vm.clearMessage() }
                    }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Kamera", fontSize = 12.sp) }
                }
                Text(if (isSharedGallery) "Unggahan di sini akan terlihat oleh pasangan." else "Media yang dipilih tetap privat sampai kamu menekan Bagikan secara eksplisit.", fontSize = 12.sp, color = DeepRose.copy(alpha = .85f))
                if (isSharedGallery) Text("Media privat tidak ikut tampil di sini kecuali kamu menekan Bagikan secara eksplisit.", fontSize = 12.sp, color = DeepRose.copy(alpha = .85f))
                if (media.isEmpty()) EmptyState(if (isSharedGallery) "Galeri Bersama masih kosong" else "Galeri Saya masih kosong", "Pilih foto atau video dari HP untuk memulai.")
                else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(media, key = { it.id }) { item ->
                            MediaCard(
                                item = item,
                                currentUid = state.userId.orEmpty(),
                                onAssignPrivate1 = { vm.assignMedia(item, "slot1", false) },
                                onAssignPrivate2 = { vm.assignMedia(item, "slot2", false) },
                                onAssignShared1 = { vm.assignMedia(item, "slot1", true) },
                                onAssignShared2 = { vm.assignMedia(item, "slot2", true) },
                                onRequestShare = { pendingShare = item },
                                onDelete = { vm.deleteMedia(item) },
                                isSharedGallery = isSharedGallery,
                                hasCouple = !state.coupleId.isNullOrBlank()
                            )
                        }
                    }
                }
            }
            Tab.PINNED -> PinnedScreen(vm, state)
            Tab.PROFILE -> ProfileScreen(vm, state) {
                if (Build.VERSION.SDK_INT >= 33) notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    pendingShare?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingShare = null },
            title = { Text("Bagikan ke Galeri Bersama?") },
            text = { Text("Pasanganmu akan dapat melihat salinan foto/video ini. File asli di Galeri Saya tetap privat dan tidak dipindahkan.") },
            confirmButton = {
                TextButton(onClick = { pendingShare = null; vm.sharePrivateMedia(item) }) { Text("Bagikan") }
            },
            dismissButton = { TextButton(onClick = { pendingShare = null }) { Text("Batal") } }
        )
    }
}

@Composable
private fun MediaCard(
    item: MediaItem,
    currentUid: String,
    onAssignPrivate1: () -> Unit,
    onAssignPrivate2: () -> Unit,
    onAssignShared1: () -> Unit,
    onAssignShared2: () -> Unit,
    onRequestShare: () -> Unit,
    onDelete: () -> Unit,
    isSharedGallery: Boolean,
    hasCouple: Boolean
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            MediaThumbnail(item, Modifier.fillMaxWidth().height(118.dp))
            Text(item.caption.ifBlank { if (item.mimeType.startsWith("video/")) "Video kenangan" else "Kenangan tanpa caption" }, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(if (item.shared) "Galeri Bersama" else "Privat", fontSize = 11.sp, color = DeepRose.copy(alpha = .72f))
            if (item.mimeType.startsWith("image/")) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallAction("Pasang 1", onAssignPrivate1, Modifier.weight(1f))
                    SmallAction("Pasang 2", onAssignPrivate2, Modifier.weight(1f))
                }
                if (isSharedGallery && hasCouple) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SmallAction("Sinkron 1", onAssignShared1, Modifier.weight(1f))
                        SmallAction("Sinkron 2", onAssignShared2, Modifier.weight(1f))
                    }
                }
            } else {
                Text("Video disimpan di galeri; slot Yang Dipasang memakai foto.", fontSize = 11.sp, color = DeepRose.copy(alpha = .75f))
            }
            if (!isSharedGallery && hasCouple && item.ownerUid == currentUid) {
                TextButton(onClick = onRequestShare, modifier = Modifier.fillMaxWidth()) { Text("Bagikan ke pasangan", fontSize = 12.sp) }
            }
            if (item.ownerUid == currentUid) TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Hapus media", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun PinnedScreen(vm: GalleryViewModel, state: GalleryUiState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        Text("Dua foto utama", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = DeepRose)
        Text("Atur cakupan Slot 1 dan Slot 2 secara terpisah. Foto privat tidak dapat masuk ke slot bersama tanpa dibagikan.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        SlotCard(vm, state, "slot1", "Slot 1")
        Spacer(Modifier.height(12.dp))
        SlotCard(vm, state, "slot2", "Slot 2")
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Tentang slideshow", fontWeight = FontWeight.Bold)
                Text("Aktifkan mode Slideshow melalui Atur, lalu pasang beberapa media ke slot yang sama. Pemutakhiran widget mengikuti kebijakan Android dan dapat tertunda oleh mode hemat baterai atau launcher.", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SlotCard(vm: GalleryViewModel, state: GalleryUiState, slotId: String, label: String) {
    val context = LocalContext.current
    var sharedScope by rememberSaveable(slotId, state.userId) {
        mutableStateOf(context.getSharedPreferences("galeri_widget", Context.MODE_PRIVATE).getBoolean("${slotId}_shared", false))
    }
    if (state.coupleId.isNullOrBlank() && sharedScope) {
        sharedScope = false
    }
    val config = (if (sharedScope) state.sharedSlots else state.privateSlots)[slotId] ?: SlotConfig(slotId)
    val media = if (sharedScope) state.sharedMedia else (state.privateMedia + state.sharedMedia).distinctBy { it.id }
    val current = media.firstOrNull { it.id == config.mediaId }
    val widgetOnly = !config.widgetImageUrl.isNullOrBlank()
    val hasCurrent = current != null || widgetOnly
    val widgetPreview = remember(config.widgetImagePath, config.widgetImageUrl, state.userId) {
        WidgetCache.loadBitmap(context, slotId)
    }
    val directWidgetPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && sharedScope) vm.publishWidgetPhoto(uri, slotId, config.caption)
    }
    LaunchedEffect(slotId, state.userId, state.coupleId, state.sharedSlots[slotId]?.widgetImageUrl) {
        val sharedPhotoUrl = state.sharedSlots[slotId]?.widgetImageUrl
        if (!state.coupleId.isNullOrBlank() && !sharedPhotoUrl.isNullOrBlank()) {
            val prefs = context.getSharedPreferences("galeri_widget", Context.MODE_PRIVATE)
            if (!prefs.contains("${slotId}_shared")) {
                sharedScope = true
                vm.selectWidgetScope(slotId, true)
            } else {
                sharedScope = prefs.getBoolean("${slotId}_shared", false)
            }
        }
    }
    var choosing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (sharedScope) "BERSAMA" else "PRIBADI", fontSize = 10.sp, color = if (sharedScope) Lavender else Rose, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (!sharedScope) Button(onClick = { sharedScope = false; vm.selectWidgetScope(slotId, false) }, modifier = Modifier.weight(1f)) { Text("HP saya", fontSize = 12.sp) }
                else OutlinedButton(onClick = { sharedScope = false; vm.selectWidgetScope(slotId, false) }, modifier = Modifier.weight(1f)) { Text("HP saya", fontSize = 12.sp) }
                if (sharedScope) Button(onClick = { sharedScope = true; vm.selectWidgetScope(slotId, true) }, enabled = !state.coupleId.isNullOrBlank(), modifier = Modifier.weight(1f)) { Text("Sinkron pasangan", fontSize = 12.sp) }
                else OutlinedButton(onClick = { sharedScope = true; vm.selectWidgetScope(slotId, true) }, enabled = !state.coupleId.isNullOrBlank(), modifier = Modifier.weight(1f)) { Text("Sinkron pasangan", fontSize = 12.sp) }
            }
            Text(if (sharedScope) "Perubahan slot ini disinkronkan ke kedua akun." else "Perubahan slot ini hanya tersimpan untuk akun ini.", fontSize = 11.sp, color = DeepRose.copy(alpha = .75f))
            if (sharedScope && !state.coupleId.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { directWidgetPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (widgetOnly) "Ganti foto HP → Widget Pasangan" else "Pilih foto HP → Widget Pasangan")
                }
                Text("Jalur widget saja: foto diperkecil dan diunggah ke bucket privat Supabase, tidak ke Firebase Storage atau Galeri Bersama.", fontSize = 11.sp, color = DeepRose.copy(alpha = .75f))
            }
            Spacer(Modifier.height(10.dp))
            if (current != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MediaThumbnail(current, Modifier.size(88.dp).clip(RoundedCornerShape(14.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(config.caption.ifBlank { current.caption.ifBlank { "Foto terpasang" } }, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Text(if (config.mode == "SLIDESHOW") "Slideshow • ${config.rotationIds.size} foto" else "Foto tetap", fontSize = 12.sp)
                        Text("Interval ${config.intervalMinutes} menit", fontSize = 12.sp, color = DeepRose.copy(alpha = .7f))
                    }
                }
            } else if (widgetOnly) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (widgetPreview != null) Image(widgetPreview.asImageBitmap(), contentDescription = "Foto widget pasangan", modifier = Modifier.size(88.dp).clip(RoundedCornerShape(14.dp)))
                    else Box(Modifier.size(88.dp).clip(RoundedCornerShape(14.dp)).background(SoftPink), contentAlignment = Alignment.Center) { Text("♥", color = Rose, fontSize = 24.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(config.caption.ifBlank { "Foto widget tersinkron" }, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Text("Foto langsung • sinkron pasangan", fontSize = 12.sp)
                        Text("Diperbarui saat perangkat online", fontSize = 12.sp, color = DeepRose.copy(alpha = .7f))
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(14.dp)).background(SoftPink), contentAlignment = Alignment.Center) {
                    Text("♡  Belum ada foto di $label", color = DeepRose)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Button(onClick = { choosing = true }, modifier = Modifier.weight(1f)) { Text(if (hasCurrent) "Ganti dari galeri" else "Pilih galeri", fontSize = 11.sp) }
                OutlinedButton(onClick = { editing = true }, enabled = hasCurrent, modifier = Modifier.weight(1f)) { Text("Atur") }
                OutlinedButton(onClick = { vm.removeSlot(slotId, sharedScope) }, enabled = hasCurrent, modifier = Modifier.weight(1f)) { Text("Lepas") }
            }
        }
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Pilih media untuk $label") },
            text = {
                if (media.isEmpty()) Text(if (sharedScope) "Galeri Bersama belum memiliki media." else "Tambahkan foto melalui Galeri Saya terlebih dahulu.")
                else LazyColumn(Modifier.height(300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(media.filter { it.mimeType.startsWith("image/") }, key = { it.id }) { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MediaThumbnail(item, Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.caption.ifBlank { "Kenangan" }, maxLines = 2, fontWeight = FontWeight.Medium)
                                Text(if (item.shared) "Bersama" else "Privat", fontSize = 11.sp)
                            }
                            TextButton(onClick = { choosing = false; vm.assignMedia(item, slotId, sharedScope) }) { Text("Pilih") }
                        }
                        Divider(color = SoftPink)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Tutup") } }
        )
    }

    if (editing && hasCurrent) {
        var mode by remember(config.mode) { mutableStateOf(if (widgetOnly) "MANUAL" else config.mode) }
        var interval by remember(config.intervalMinutes) { mutableIntStateOf(config.intervalMinutes) }
        var cap by remember(config.caption) { mutableStateOf(config.caption) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Atur $label") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mode tampilan", fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == "MANUAL", onClick = { mode = "MANUAL" })
                        Text("Foto tetap")
                    }
                    if (!widgetOnly) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = mode == "SLIDESHOW", onClick = { mode = "SLIDESHOW" })
                            Text("Slideshow")
                        }
                    } else {
                        Text("Foto HP untuk widget pasangan memakai mode Foto Tetap. Ganti fotonya dari tombol di kartu untuk menyinkronkan gambar baru.", fontSize = 12.sp, color = DeepRose.copy(alpha = .8f))
                    }
                    if (mode == "SLIDESHOW" && !widgetOnly) {
                        Text("Interval pergantian", fontWeight = FontWeight.SemiBold)
                        listOf(30, 60, 180, 360, 720, 1440).forEach { min ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = interval == min, onClick = { interval = min })
                                Text(when (min) { 30 -> "30 menit"; 60 -> "1 jam"; 180 -> "3 jam"; 360 -> "6 jam"; 720 -> "12 jam"; else -> "24 jam" })
                            }
                        }
                        Text("Untuk menambahkan foto ke daftar rotasi, tutup pengaturan lalu pilih media tambahan dan pasang ke slot ini.", fontSize = 12.sp)
                    }
                    OutlinedTextField(cap, { cap = it.take(120) }, label = { Text("Caption slot") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { editing = false; vm.setSlotMode(slotId, sharedScope, mode, interval, cap) }) { Text("Simpan") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Batal") } }
        )
    }
}

@Composable
private fun ProfileScreen(vm: GalleryViewModel, state: GalleryUiState, requestNotifications: () -> Unit) {
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Profil & privasi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.displayName.ifBlank { "Pengguna Galeri Cinta" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(state.email)
                Text("Status pasangan: ${if (state.coupleId.isNullOrBlank()) "belum terhubung" else "terhubung"}")
                Text("UID: ${state.userId.orEmpty()}", fontSize = 11.sp)
            }
        }
        if (state.coupleId.isNullOrBlank()) {
            var pairCode by rememberSaveable { mutableStateOf("") }
            Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hubungkan pasangan", fontWeight = FontWeight.Bold)
                    Text("Galeri privat tetap bisa digunakan sekarang. Hubungkan akun untuk mengaktifkan Galeri Bersama dan slot tersinkron.", fontSize = 12.sp)
                    Button(onClick = { vm.createInvite() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Buat kode undangan") }
                    state.inviteCode?.let { Text("Kode: $it", fontWeight = FontWeight.Bold, color = DeepRose) }
                    OutlinedTextField(pairCode, { pairCode = it.uppercase().take(32) }, label = { Text("Kode dari pasangan") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { vm.redeemInvite(pairCode) }, enabled = !state.busy && pairCode.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Hubungkan akun") }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Kontrol privasi", fontWeight = FontWeight.Bold)
                Text("Galeri Saya hanya bisa dibaca oleh akun pemilik. Memilih Bagikan akan menyalin media ke Galeri Bersama dan memberi pasangan akses ke salinan itu.")
                Text("Memasang atau melepas foto dari slot tidak menghapus file aslinya.")
                Text("Cache widget disimpan lokal pada HP ini agar foto terakhir tetap terlihat saat offline.")
            }
        }
        OutlinedButton(onClick = requestNotifications, modifier = Modifier.fillMaxWidth()) { Text("Aktifkan izin notifikasi") }
        if (!state.coupleId.isNullOrBlank()) {
            OutlinedButton(onClick = { showDisconnectConfirm = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Rose)) {
                Text("Putuskan hubungan pasangan")
            }
        }
        OutlinedButton(onClick = { vm.signOut() }, modifier = Modifier.fillMaxWidth()) { Text("Keluar akun") }
    }
    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text("Putuskan hubungan pasangan?") },
            text = { Text("Tindakan ini segera mencabut akses kedua akun ke Galeri Bersama dan menghapus media serta konfigurasi bersama dari server. Foto privat tetap ada, tetapi salinan bersama tidak dapat dipulihkan lewat aplikasi. Lanjutkan hanya jika kamu yakin.") },
            confirmButton = { TextButton(onClick = { showDisconnectConfirm = false; vm.disconnectCouple() }) { Text("Putuskan") } },
            dismissButton = { TextButton(onClick = { showDisconnectConfirm = false }) { Text("Batal") } }
        )
    }
}

@Composable
private fun MediaThumbnail(item: MediaItem, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = item.thumbnailPath) {
        val path = item.thumbnailPath
        value = if (path.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                val bytes = FirebaseStorage.getInstance().reference.child(path).getBytes(2L * 1024L * 1024L).await()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(bitmap!!.asImageBitmap(), contentDescription = item.caption.ifBlank { "Foto kenangan" }, modifier = modifier.clip(RoundedCornerShape(12.dp)))
    } else {
        Box(modifier.clip(RoundedCornerShape(12.dp)).background(SoftPink), contentAlignment = Alignment.Center) {
            Text(if (item.mimeType.startsWith("video/")) "▶" else "♥", fontSize = 28.sp, color = Rose)
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(60.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.White), contentAlignment = Alignment.Center) {
            Text("♥", fontSize = 35.sp, color = Rose)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Galeri Cinta", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = DeepRose)
            Text("Simpan momen yang berarti", color = DeepRose.copy(alpha = .75f))
        }
    }
}

@Composable
private fun EmptyState(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("♡", fontSize = 44.sp, color = Rose)
        Text(title, fontWeight = FontWeight.Bold, color = DeepRose)
        Text(description, fontSize = 12.sp, color = DeepRose.copy(alpha = .8f))
    }
}

@Composable
private fun MessageBanner(message: String?, clear: () -> Unit) {
    if (!message.isNullOrBlank()) {
        Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFFFE1EF))) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, modifier = Modifier.weight(1f), color = DeepRose, fontSize = 12.sp)
                TextButton(onClick = clear) { Text("Tutup") }
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp, vertical = 0.dp),
        shape = RoundedCornerShape(10.dp)
    ) { Text(label, fontSize = 10.sp, maxLines = 1) }
}

@Composable
private fun CenterLoading(message: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Rose)
        Spacer(Modifier.height(12.dp))
        Text(message, color = DeepRose)
    }
}

@Composable
private fun LinearBusy() {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Rose)
        Spacer(Modifier.width(8.dp))
        Text("Memproses…", fontSize = 12.sp, color = DeepRose)
    }
}

