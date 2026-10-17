package com.plehooo.galericinta.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.ListenerRegistration
import com.plehooo.galericinta.data.GalleryRepository
import com.plehooo.galericinta.data.PairingRepository
import com.plehooo.galericinta.domain.MediaItem
import com.plehooo.galericinta.domain.SlotConfig
import com.plehooo.galericinta.domain.normalizeRotationInterval
import com.plehooo.galericinta.widget.GalleryWidget
import com.plehooo.galericinta.widget.WidgetCache
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

 data class GalleryUiState(
    val firebaseReady: Boolean = false,
    val profileLoaded: Boolean = false,
    val busy: Boolean = false,
    val userId: String? = null,
    val email: String = "",
    val displayName: String = "",
    val coupleId: String? = null,
    val inviteCode: String? = null,
    val privateMedia: List<MediaItem> = emptyList(),
    val sharedMedia: List<MediaItem> = emptyList(),
    val privateSlots: Map<String, SlotConfig> = emptyMap(),
    val sharedSlots: Map<String, SlotConfig> = emptyMap(),
    val message: String? = null
)

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    private var configured = false
    private var auth: FirebaseAuth? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var repository: GalleryRepository? = null
    private var pairingRepository: PairingRepository? = null
    private var currentUid: String? = null
    private var dataKey: String? = null
    private var profileListener: ListenerRegistration? = null
    private var privateMediaListener: ListenerRegistration? = null
    private var sharedMediaListener: ListenerRegistration? = null
    private var privateSlotsListener: ListenerRegistration? = null
    private var sharedSlotsListener: ListenerRegistration? = null

    fun configure() {
        if (configured) return
        configured = true
        val context = getApplication<Application>().applicationContext
        if (FirebaseApp.getApps(context).isEmpty()) {
            _state.update { it.copy(firebaseReady = false, message = null) }
            return
        }
        try {
            com.plehooo.galericinta.AppCheckSetup.install()
            repository = GalleryRepository(context)
            pairingRepository = PairingRepository()
            auth = FirebaseAuth.getInstance()
            _state.update { it.copy(firebaseReady = true) }
            authListener = FirebaseAuth.AuthStateListener { firebaseAuth -> handleAuth(firebaseAuth.currentUser) }
            auth?.addAuthStateListener(authListener!!)
        } catch (e: Exception) {
            _state.update { it.copy(firebaseReady = false, message = "Firebase belum siap: ${e.message.orEmpty()}") }
        }
    }

    private fun handleAuth(user: FirebaseUser?) {
        if (user == null) {
            val previousUid = currentUid
            repository?.cancelWidgetSync(previousUid)
            currentUid = null
            dataKey = null
            removeListeners()
            viewModelScope.launch {
                runCatching {
                    repository?.cancelAllRotations()
                    WidgetCache.clearAll(getApplication())
                    GalleryWidget().updateAll(getApplication())
                }
            }
            _state.update {
                GalleryUiState(firebaseReady = it.firebaseReady, message = it.message?.takeIf { msg -> msg.startsWith("Firebase belum siap") })
            }
            return
        }
        if (currentUid == user.uid) return
        currentUid = user.uid
        repository?.scheduleWidgetSync(user.uid)
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            repository?.saveDeviceToken(user.uid, token)
        }
        dataKey = null
        removeListeners()
        _state.update {
            it.copy(
                userId = user.uid,
                email = user.email.orEmpty(),
                displayName = user.displayName.orEmpty(),
                coupleId = null,
                profileLoaded = false,
                busy = true,
                inviteCode = null,
                privateMedia = emptyList(), sharedMedia = emptyList(),
                privateSlots = emptyMap(), sharedSlots = emptyMap(), message = null
            )
        }
        viewModelScope.launch {
            try {
                repository?.ensureProfile(user)
                profileListener = repository?.listenProfile(user.uid) { name, coupleId, photoUrl ->
                    val newKey = "${user.uid}|${coupleId.orEmpty()}"
                    _state.update { it.copy(displayName = name.orEmpty().ifBlank { user.email.orEmpty().substringBefore('@') }, coupleId = coupleId, profileLoaded = true, busy = false) }
                    if (newKey != dataKey) attachDataListeners(user.uid, coupleId, newKey)
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "Profil gagal dimuat: ${friendlyError(e)}") }
            }
        }
    }

    private fun attachDataListeners(uid: String, coupleId: String?, newKey: String) {
        dataKey = newKey
        privateMediaListener?.remove(); sharedMediaListener?.remove()
        privateSlotsListener?.remove(); sharedSlotsListener?.remove()
        val repo = repository ?: return
        // A couple/account switch must not leave a previous private/shared photo in the widget cache.
        _state.update { it.copy(privateMedia = emptyList(), sharedMedia = emptyList(), privateSlots = emptyMap(), sharedSlots = emptyMap()) }
        viewModelScope.launch { runCatching { repo.clearSharedWidgetCache() } }
        privateMediaListener = repo.listenPrivateMedia(uid) { list ->
            val previousIds = _state.value.privateMedia.map { it.id }.toSet()
            val currentIds = list.map { it.id }.toSet()
            _state.update { it.copy(privateMedia = list) }
            val removedIds = previousIds - currentIds
            if (removedIds.isNotEmpty()) viewModelScope.launch {
                removedIds.forEach { id -> runCatching { repo.cleanupPersonalSlotsAfterMediaRemoval(uid, coupleId, id) } }
            }
            refreshWidget()
        }
        privateSlotsListener = repo.listenPrivateSlots(uid) { slots ->
            _state.update { it.copy(privateSlots = slots) }
            repo.synchronizeRotationSchedules(uid, _state.value.coupleId, slots, _state.value.sharedSlots)
            refreshWidget()
        }
        if (coupleId != null) {
            sharedMediaListener = repo.listenSharedMedia(coupleId) { list ->
                val previousIds = _state.value.sharedMedia.map { it.id }.toSet()
                val currentIds = list.map { it.id }.toSet()
                _state.update { it.copy(sharedMedia = list) }
                val removedIds = previousIds - currentIds
                if (removedIds.isNotEmpty()) viewModelScope.launch {
                    removedIds.forEach { id -> runCatching { repo.cleanupPersonalSlotsAfterMediaRemoval(uid, coupleId, id) } }
                }
                refreshWidget()
            }
            sharedSlotsListener = repo.listenSharedSlots(coupleId) { slots ->
                _state.update { it.copy(sharedSlots = slots) }
                repo.synchronizeRotationSchedules(uid, coupleId, _state.value.privateSlots, slots)
                refreshWidget()
            }
        } else {
            _state.update { it.copy(sharedMedia = emptyList(), sharedSlots = emptyMap()) }
            viewModelScope.launch { runCatching { repo.clearSharedWidgetCache() } }
        }
    }

    private fun refreshWidget() {
        val repo = repository ?: return
        viewModelScope.launch {
            val s = _state.value
            runCatching { repo.refreshAllWidgetImages(s.privateSlots, s.sharedSlots) }
        }
    }

    private fun removeListeners() {
        listOf(profileListener, privateMediaListener, sharedMediaListener, privateSlotsListener, sharedSlotsListener)
            .forEach { runCatching { it?.remove() } }
        profileListener = null
        privateMediaListener = null
        sharedMediaListener = null
        privateSlotsListener = null
        sharedSlotsListener = null
    }

    fun signIn(email: String, password: String, createAccount: Boolean) = viewModelScope.launch {
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank() || !cleanEmail.contains('@') || password.length < 6) {
            showMessage("Masukkan email yang benar dan kata sandi minimal 6 karakter.")
            return@launch
        }
        val firebaseAuth = auth ?: run { showMessage("Hubungkan Firebase terlebih dahulu."); return@launch }
        _state.update { it.copy(busy = true, message = null) }
        try {
            if (createAccount) {
                val result = firebaseAuth.createUserWithEmailAndPassword(cleanEmail, password).await()
                val user = result.user ?: error("Akun gagal dibuat.")
                repository?.ensureProfile(user)
                runCatching { user.sendEmailVerification().await() }
                _state.update { it.copy(message = "Akun dibuat. Periksa email untuk verifikasi bila email verifikasi diterima.") }
            } else {
                firebaseAuth.signInWithEmailAndPassword(cleanEmail, password).await()
            }
        } catch (e: Exception) {
            _state.update { it.copy(message = friendlyError(e)) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun resetPassword(email: String) = viewModelScope.launch {
        if (email.isBlank() || !email.contains('@')) { showMessage("Masukkan alamat email yang valid."); return@launch }
        _state.update { it.copy(busy = true, message = null) }
        try {
            auth?.sendPasswordResetEmail(email.trim())?.await() ?: error("Firebase belum siap.")
            showMessage("Jika akun cocok, instruksi reset kata sandi akan dikirim ke email.")
        } catch (e: Exception) { showMessage(friendlyError(e)) }
        finally { _state.update { it.copy(busy = false) } }
    }

    fun signOut() { auth?.signOut() }

    fun createInvite() = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = null) }
        try {
            val code = pairingRepository?.createInviteCode() ?: error("Firebase Functions belum siap.")
            _state.update { it.copy(inviteCode = code, message = "Kode berlaku 15 menit dan hanya bisa dipakai sekali.") }
        } catch (e: Exception) { showMessage(friendlyError(e)) }
        finally { _state.update { it.copy(busy = false) } }
    }

    fun redeemInvite(code: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = null) }
        try {
            pairingRepository?.redeemInviteCode(code) ?: error("Firebase Functions belum siap.")
            showMessage("Kode diterima. Status pasangan sedang disinkronkan.")
        } catch (e: Exception) { showMessage(friendlyError(e)) }
        finally { _state.update { it.copy(busy = false) } }
    }

    fun uploadMedia(context: Context, uri: Uri, shared: Boolean, caption: String) = viewModelScope.launch {
        val uid = _state.value.userId ?: return@launch
        _state.update { it.copy(busy = true, message = null) }
        try {
            repository?.uploadMedia(uid, _state.value.coupleId, uri, shared, caption)
            showMessage(if (shared) "Media berhasil dibagikan ke Galeri Bersama." else "Media tersimpan di Galeri Saya.")
        } catch (e: Exception) { showMessage(friendlyError(e)) }
        finally { _state.update { it.copy(busy = false) } }
    }

    fun uploadMediaBatch(uris: List<Uri>, shared: Boolean, caption: String) = viewModelScope.launch {
        val uid = _state.value.userId ?: return@launch
        if (uris.isEmpty()) return@launch
        _state.update { it.copy(busy = true, message = null) }
        var succeeded = 0
        val errors = mutableListOf<String>()
        try {
            uris.forEach { uri ->
                try {
                    repository?.uploadMedia(uid, _state.value.coupleId, uri, shared, caption)
                    succeeded++
                } catch (e: Exception) {
                    if (errors.size < 3) errors += friendlyError(e)
                }
            }
            val base = "$succeeded dari ${uris.size} media berhasil ${if (shared) "dibagikan" else "disimpan"}."
            showMessage(if (errors.isEmpty()) base else "$base ${errors.joinToString(" | ")}")
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun sharePrivateMedia(item: MediaItem) = viewModelScope.launch {
        val uid = _state.value.userId ?: return@launch
        _state.update { it.copy(busy = true, message = null) }
        try {
            repository?.sharePrivateMedia(uid, _state.value.coupleId, item)
            showMessage("Salinan media dibuat di Galeri Bersama. File privat aslinya tetap ada.")
        } catch (e: Exception) { showMessage(friendlyError(e)) }
        finally { _state.update { it.copy(busy = false) } }
    }

    fun deleteMedia(item: MediaItem) = viewModelScope.launch {
        val uid = _state.value.userId ?: return@launch
        if (item.ownerUid != uid) { showMessage("Hanya pengunggah yang dapat menghapus media ini."); return@launch }
        _state.update { it.copy(busy = true, message = null) }
        try { repository?.deleteMedia(_state.value.coupleId, item); showMessage("Media dihapus.") }
        catch (e: Exception) { showMessage(friendlyError(e)) }
        finally { _state.update { it.copy(busy = false) } }
    }

    fun publishWidgetPhoto(uri: Uri, slotId: String, caption: String) = viewModelScope.launch {
        val s = _state.value
        val uid = s.userId ?: return@launch
        val coupleId = s.coupleId
        if (coupleId.isNullOrBlank()) {
            showMessage("Hubungkan akun pasangan terlebih dahulu.")
            return@launch
        }
        _state.update { it.copy(busy = true, message = null) }
        try {
            repository?.publishWidgetPhoto(uid, coupleId, uri, slotId, caption)
                ?: error("Repositori galeri belum siap.")
            showMessage("Foto widget berhasil disinkronkan ke pasangan. Widget pasangan memperbarui foto saat Android mengizinkan sinkronisasi.")
        } catch (e: Exception) {
            showMessage(friendlyError(e))
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun assignMedia(item: MediaItem, slotId: String, sharedSlot: Boolean) = viewModelScope.launch {
        if (!item.mimeType.startsWith("image/")) {
            showMessage("Yang Dipasang hanya mendukung foto. Video tetap tersimpan di galeri.")
            return@launch
        }
        val s = _state.value
        val uid = s.userId ?: return@launch
        if (sharedSlot && !item.shared) { showMessage("Foto privat belum dibagikan. Gunakan tombol Bagikan ke Galeri Bersama terlebih dahulu."); return@launch }
        _state.update { it.copy(busy = true, message = null) }
        try {
            val old = (if (sharedSlot) s.sharedSlots else s.privateSlots)[slotId] ?: SlotConfig(slotId)
            val ids = if (old.mode == "SLIDESHOW") (old.rotationIds + item.id).distinct() else listOf(item.id)
            val next = old.copy(
                mediaId = item.id,
                storagePath = item.storagePath,
                thumbnailPath = item.thumbnailPath,
                caption = item.caption,
                rotationIds = ids,
                widgetImageUrl = null,
                widgetImagePath = null,
                widgetImageOwnerUid = null
            )
            repository?.saveSlot(uid, s.coupleId, next, sharedSlot)
            showMessage(if (sharedSlot) "$slotId disinkronkan ke pasangan." else "$slotId dipasang hanya di HP ini.")
        } catch (e: Exception) { showMessage(friendlyError(e)) }
        finally { _state.update { it.copy(busy = false) } }
    }

    fun setSlotMode(slotId: String, sharedSlot: Boolean, mode: String, intervalMinutes: Int, caption: String) = viewModelScope.launch {
        val s = _state.value
        val uid = s.userId ?: return@launch
        val old = (if (sharedSlot) s.sharedSlots else s.privateSlots)[slotId]
        if (old?.mediaId.isNullOrBlank()) { showMessage("Pasang foto terlebih dahulu sebelum mengatur slot."); return@launch }
        if (!old.widgetImageUrl.isNullOrBlank() && mode == "SLIDESHOW") {
            showMessage("Foto langsung untuk widget pasangan memakai mode Foto Tetap. Ganti foto dari tombol Foto HP untuk memperbaruinya.")
            return@launch
        }
        _state.update { it.copy(busy = true, message = null) }
        try {
            val rotation = if (mode == "SLIDESHOW") (old!!.rotationIds + old.mediaId!!).distinct() else listOf(old!!.mediaId!!)
            repository?.saveSlot(uid, s.coupleId, old.copy(mode = mode, intervalMinutes = normalizeRotationInterval(intervalMinutes), caption = caption, rotationIds = rotation), sharedSlot)
            showMessage("Pengaturan $slotId disimpan.")
        } catch (e: Exception) { showMessage(friendlyError(e)) }
        finally { _state.update { it.copy(busy = false) } }
    }

    fun selectWidgetScope(slotId: String, shared: Boolean) = viewModelScope.launch {
        val s = _state.value
        if (shared && s.coupleId.isNullOrBlank()) { showMessage("Hubungkan akun pasangan dahulu."); return@launch }
        try {
            repository?.selectWidgetScope(slotId, shared, s.privateSlots, s.sharedSlots)
        } catch (e: Exception) { showMessage(friendlyError(e)) }
    }

    fun removeSlot(slotId: String, sharedSlot: Boolean) = viewModelScope.launch {
        val s = _state.value
        val uid = s.userId ?: return@launch
        try {
            repository?.removeSlot(uid, s.coupleId, slotId, sharedSlot)
            showMessage("Foto dilepas dari $slotId. File asli tetap tersimpan di galeri.")
        } catch (e: Exception) { showMessage(friendlyError(e)) }
    }

    fun disconnectCouple() = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = null) }
        try {
            val cleanupPending = pairingRepository?.disconnectCouple() ?: error("Firebase Functions belum siap.")
            showMessage(if (cleanupPending) "Akses pasangan sudah dicabut, tetapi sebagian pembersihan media perlu dicek dari backend." else "Hubungan diputus. Akses Galeri Bersama dicabut dan pembersihan media telah diminta.")
        } catch (e: Exception) { showMessage(friendlyError(e)) }
        finally { _state.update { it.copy(busy = false) } }
    }

    fun refresh() {
        // Firestore listeners are active while signed in; clearing the message gives users a manual retry affordance.
        _state.update { it.copy(message = null) }
    }

    fun clearMessage() { _state.update { it.copy(message = null) } }
    private fun showMessage(message: String) { _state.update { it.copy(message = message) } }

    private fun friendlyError(e: Throwable): String {
        val message = e.message.orEmpty()
        return when {
            e is FirebaseAuthInvalidCredentialsException -> "Email atau kata sandi tidak sesuai."
            e is FirebaseAuthInvalidUserException -> "Akun tidak ditemukan atau sudah dinonaktifkan."
            message.contains("network", true) || message.contains("unavailable", true) -> "Jaringan sedang bermasalah. Periksa internet lalu coba lagi."
            message.contains("permission", true) || message.contains("PERMISSION_DENIED", true) -> "Akses ditolak oleh Security Rules Firebase. Periksa konfigurasi backend."
            message.contains("not found", true) && message.contains("function", true) -> "Cloud Functions belum di-deploy. Ikuti README."
            else -> message.take(220).ifBlank { "Terjadi kesalahan. Coba lagi." }
        }
    }

    override fun onCleared() {
        removeListeners()
        authListener?.let { listener -> runCatching { auth?.removeAuthStateListener(listener) } }
        super.onCleared()
    }
}

