package com.plehooo.cinta

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ForgotPasswordCard(repository: PhotoSyncRepository, defaultEmail: String, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var mail by remember { mutableStateOf(defaultEmail) }
    var code by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { open = !open }) { Text(if (open) "Tutup" else "Lupa kata sandi?") }
            if (open) {
                OutlinedTextField(value = mail, onValueChange = { mail = it }, label = { Text("Email akun") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && mail.contains('@'), onClick = {
                    scope.launch {
                        busy = true
                        try {
                            repository.sendResetCode(mail)
                            sent = true
                            onMessage("Kode reset dikirim ke email. Masukkan kode itu di bawah.")
                        } catch (e: CancellationException) { throw e
                        } catch (_: Throwable) { onMessage("Gagal mengirim kode. Cek email dan koneksi, lalu coba lagi nanti.")
                        } finally { busy = false }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Memproses…" else "Kirim kode reset") }
                if (sent) {
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Kode dari email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newPass, onValueChange = { newPass = it }, label = { Text("Kata sandi baru (min. 8)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Button(enabled = !busy && code.isNotBlank() && newPass.length >= 8, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                repository.resetPasswordWithCode(mail, code, newPass)
                                onMessage("Kata sandi diganti. Kamu sudah masuk.")
                            } catch (e: CancellationException) { throw e
                            } catch (_: Throwable) { onMessage("Kode salah atau kedaluwarsa. Kirim kode baru lalu coba lagi.")
                            } finally { busy = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Simpan kata sandi baru") }
                }
            }
        }
    }
}

@Composable
fun AccountCard(repository: PhotoSyncRepository, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var newEmail by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Akun", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = newEmail, onValueChange = { newEmail = it }, label = { Text("Email baru") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && newEmail.contains('@'), onClick = {
                scope.launch {
                    busy = true
                    try {
                        repository.changeEmail(newEmail)
                        onMessage("Email diganti.")
                        newEmail = ""
                    } catch (e: CancellationException) { throw e
                    } catch (_: Throwable) { onMessage("Gagal mengganti email. Pastikan email valid, belum dipakai, dan koneksi aktif.")
                    } finally { busy = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Ganti email") }
            OutlinedTextField(value = newPass, onValueChange = { newPass = it }, label = { Text("Kata sandi baru (min. 8)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && newPass.length >= 8, onClick = {
                scope.launch {
                    busy = true
                    try {
                        repository.changePassword(newPass)
                        onMessage("Kata sandi diganti.")
                        newPass = ""
                    } catch (e: CancellationException) { throw e
                    } catch (_: Throwable) { onMessage("Gagal mengganti kata sandi. Coba lagi.")
                    } finally { busy = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Ganti kata sandi") }
        }
    }
}
