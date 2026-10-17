# Cinta Widget ❤️

MVP Android native (Kotlin + Compose + Glance home-screen widget) yang fokus pada satu foto pasangan. **Widget HP A hanya menampilkan foto B; widget HP B hanya menampilkan foto A.** Foto pribadi tidak dipakai sebagai isi widget pada HP pemiliknya.

> **Status build:** source project dan pipeline tersedia, tetapi APK belum dibangun/diuji dalam lingkungan pembuatan paket ini karena Gradle/Android SDK tidak tersedia dan koneksi jaringan build tidak dapat dipakai. Jalankan GitHub Actions atau build lokal untuk mendapatkan hasil nyata; jangan menganggap artifact tersedia sebelum workflow sukses.

## Aturan foto dua arah

- Metadata disimpan terpisah untuk setiap `owner_uid` di `member_photos` dengan `couple_id`, `object_path`, `caption`, `version`, dan SHA-256 `content_hash`.
- A mengirim foto dirinya ke jalur `couple_id/A_UID/UUID.webp`; hanya widget B yang memilih metadata pemilik A. Berlaku terbalik untuk B.
- `PhotoRouting` hanya membuat route jika tepat ada dua anggota aktif. Jika keanggotaan tidak lengkap atau muncul anggota ketiga, routing gagal tertutup.
- Foto WebP diunggah ke bucket privat sebagai objek baru terlebih dahulu. RPC `publish_member_photo` memvalidasi owner, pasangan, object storage yang sudah diunggah, dan expected version sebelum mengganti metadata. Foto lama baru dibersihkan setelah metadata sukses.
- Cache foto pasangan disimpan di direktori privat, dipindah atomik, dan widget tetap menggunakan cache saat request gagal. Pemilihan saat offline membuat antrean per UID; upload dilanjutkan WorkManager saat jaringan ada dan server masih mengonfirmasi keanggotaan.
- Supabase Realtime digunakan selama proses aplikasi hidup. WorkManager menyediakan fallback saat aplikasi tidak aktif. **Android tidak menjamin sinkronisasi instan saat force-stop, Doze, pembatasan OEM, atau tidak ada jaringan.** Periodic WorkManager 6 jam adalah fallback, bukan interval real-time.

## 1. Siapkan Supabase Free

1. Buat proyek baru di [dashboard Supabase](https://supabase.com/dashboard), pilih paket Free, dan jangan aktifkan add-on/billing berbayar untuk proyek ini.
2. Buka SQL Editor, jalankan isi `supabase/migrations/202610170001_cinta_widget.sql` satu kali pada proyek baru. Migration membuat tabel, fungsi RPC, RLS dan bucket privat `couple-photos`.
3. Pada Authentication, pilih pengaturan email sesuai kebutuhan. Jika konfirmasi email aktif, pengguna harus menyelesaikan konfirmasi sebelum login.
4. Buka **Project Settings → API Keys** dan salin **Project URL** dan **Publishable key** (`sb_publishable_...`). Jangan pernah pakai atau memasukkan `service_role`/secret backend ke APK, file source, atau GitHub.
5. Di Settings/Replication, pastikan perubahan `public.member_photos` terdaftar dalam publikasi Realtime. Migration mencoba menambahkannya otomatis jika publication `supabase_realtime` tersedia.

### Catatan biaya dan kuota

Berdasarkan halaman harga resmi saat dokumentasi paket ini dibuat (17 Oktober 2026), paket Free mencakup antara lain 50.000 MAU, database 500 MB/proyek, file storage 1 GB, egress 5 GB, cached egress 5 GB, 2 juta Realtime messages, dan 200 peak Realtime connections. Paket membatasi dua proyek aktif dan proyek Free dapat dijeda setelah satu minggu tidak aktif. Kuota, batasan, dan kebijakan dapat berubah; verifikasi langsung sebelum rilis:

- [Supabase Pricing](https://supabase.com/pricing)
- [Supabase Billing Limits](https://supabase.com/docs/guides/platform/billing-on-supabase)
- [Supabase Database Size and Free-plan behavior](https://supabase.com/docs/guides/platform/database-size)

Tidak ada kartu pembayaran atau aktivasi billing yang dibutuhkan oleh alur aplikasi ini. Free bukan gratis tanpa batas: foto dan metadata mengonsumsi storage/egress, Realtime mengonsumsi kuota, dan proyek dapat dijeda atau mencapai batas. Backup otomatis tidak termasuk paket Free. Buat cadangan migration/data secara berkala bila diperlukan.

## 2. Build debug APK

### GitHub Actions (paling mudah)

Workflow `.github/workflows/android-ci.yml` memeriksa source, menjalankan unit test, lint dan `assembleDebug`, kemudian menerbitkan artifact APK selama build benar-benar berhasil.

Di repository GitHub, buka **Settings → Secrets and variables → Actions → Variables**, lalu tambahkan:

- `SUPABASE_URL` = Project URL
- `SUPABASE_PUBLISHABLE_KEY` = Publishable key

Keduanya adalah konfigurasi client. Publishable key memang untuk client, tetapi keselamatan data tetap wajib ditangani oleh RLS dan Storage policies. Jika belum menambahkan variabel, APK tetap bisa dibuat tetapi layar aplikasi akan menampilkan pesan bahwa Supabase belum dikonfigurasi.

Setelah push ke `main`/`master`, buka tab **Actions**, pilih run paling baru dan unduh artifact `cinta-widget-debug-apk` hanya jika job sukses. Tidak ada APK yang dicantumkan sebagai artifact sebelum pipeline berjalan.

### Build lokal

Perlu Android SDK platform 36, JDK 17, serta Gradle 8.13. Di lingkungan yang sudah menyiapkan Android SDK:

```bash
gradle --no-daemon testDebugUnitTest lintDebug assembleDebug \
  -PsupabaseUrl="https://YOUR_PROJECT.supabase.co" \
  -PsupabasePublishableKey="YOUR_SB_PUBLISHABLE_KEY"
```

APK hasil debug berada di `app/build/outputs/apk/debug/app-debug.apk`. Jangan membagikan APK yang dibangun tanpa konfigurasi lalu berharap langsung bisa login.

## 3. Pasang dan gunakan di dua HP

1. Pasang APK yang sama di HP A dan HP B; masing-masing membuat akun sendiri.
2. HP A login, tekan **Buat pasangan**, lalu **Buat kode undangan**. Kode acak hanya berlaku 10 menit; kirim langsung secara pribadi kepada pasangan.
3. HP B login, masukkan kode, baca konfirmasi identitas pasangan, lalu tekan tombol konfirmasi.
4. Di HP A pilih **Ganti Foto Saya**, pilih foto sendiri, opsional isi caption, dan simpan. Foto baru akan tampil di widget HP B, bukan HP A.
5. Di HP B lakukan hal yang sama. Foto B akan tampil di widget HP A.
6. Tambah widget secara manual: tekan lama layar utama → **Widget** → **Cinta Widget**, lalu pilih kecil/sedang. Widget bukan overlay dan tidak menambahkan diri otomatis.

Untuk memilih foto ketika offline, akun harus sudah pernah terhubung dan cache membership lokal tersedia. Foto masuk antrean lokal privat; penerbitan tetap menunggu koneksi dan validasi server. Jika pasangan diputus, backend menolak akses dan perangkat membersihkan cache pada sinkronisasi yang berhasil berikutnya.

## 4. Struktur source utama

- `MainActivity.kt` — login/registrasi, pairing, pemilih foto, caption dan kontrol.
- `PhotoRouting.kt` — invariant routing satu arah per perangkat dan fail-closed saat bukan dua anggota aktif.
- `PhotoSyncRepository.kt` — Auth, pairing, upload versi, download foto pasangan, Realtime dan status.
- `PhotoQueue.kt`, `PhotoFiles.kt` — antrean owner-specific dan kompresi WebP pada direktori privat.
- `WidgetCache.kt`, `CintaWidget.kt`, `CintaWidgetReceiver.kt` — cache offline dan home-screen widget Glance.
- `PhotoSyncWorker.kt`, `CintaApplication.kt` — upload tertunda dan sync periodik berbasis jaringan.
- `supabase/migrations/202610170001_cinta_widget.sql` — tabel, RPC, RLS, bucket Storage privat.

## 5. QA yang disertakan dan belum diselesaikan

`app/src/test/.../PhotoRoutingTest.kt` menguji: A melihat foto B; B melihat foto A; foto sendiri tidak bisa memengaruhi widget sendiri; akun ketiga; anggota yang dicabut; membership duplikat. `scripts/verify-invariants.py` mengecek invariant source dan konfigurasi keamanan yang bisa diperiksa tanpa server.

Belum dapat dianggap lulus hingga menjalankan uji integrasi terhadap dua akun/proyek Supabase serta dua perangkat nyata: RLS/Storage untuk akun ketiga, race dua update, foto offline → online, restart, cache rusak, upload gagal setelah file ditulis, metadata gagal, logout dan putus pasangan. Kumpulan source-level checks bukan pengganti tes jaringan/SQL nyata. Perubahan foto dipublikasikan dengan optimistic version; jika konflik, foto lama tetap tampil dan pengguna diminta mencoba ulang dengan versi terkini.
