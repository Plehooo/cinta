# Galeri Cinta — Android + Firebase

Aplikasi Android native Kotlin untuk dua pengguna, dengan galeri privat/bersama, pairing, dua slot foto, dan widget home screen. Jalur tambahan "Foto HP → Widget Pasangan" mengunggah foto widget terkompresi ke bucket privat Supabase Free, sementara metadata slot bersama tetap memakai Firestore.

> **Status penting:** Ini adalah source project awal yang mengimplementasikan alur inti. APK belum dikompilasi/diuji pada lingkungan pembuat source ini karena Android SDK, Gradle, dan akses unduhan dependensi tidak tersedia. Workflow GitHub Actions disiapkan untuk menjalankan kompilasi dan unit test sesungguhnya. Sinkronisasi dua HP hanya terbukti setelah backend Firebase milikmu dikonfigurasi dan diuji.

## Stack

- Kotlin, Jetpack Compose, Material 3, MVVM, Coroutines/Flow.
- Firebase Authentication, Cloud Firestore, Cloud Storage, Cloud Functions v2, FCM, App Check.
- Android Photo Picker, kamera melalui intent Android, Glance App Widget, WorkManager.
- Firebase Emulator Suite untuk aturan Firestore/Storage.

## Fitur yang sudah ada di source

- Daftar/masuk email dan kata sandi, reset password, logout, dan pembuatan profil.
- Pairing kode undangan acak 96-bit, masa berlaku 15 menit, hash kode di database, cooldown, dan penukaran satu kali dalam transaksi server.
- Media privat dan bersama dipisah ke path Firestore/Storage berbeda. Foto privat tidak dibagikan otomatis. Berbagi dari galeri privat menyalin media secara eksplisit.
- Unggah banyak media dari Photo Picker; foto/video dibatasi ukuran file; thumbnail gambar dibuat lokal. Jika tulis metadata gagal, aplikasi mencoba membersihkan objek Storage yang baru diunggah.
- Dua slot `slot1` dan `slot2`, konfigurasi pribadi atau bersama, foto manual/slideshow, interval 30 menit–24 jam, ganti/pasang/lepas tanpa menghapus file asli.
- Widget Glance responsif: layout ringkas menampilkan Slot 1; layout lebih besar menampilkan dua slot; foto di-cache lokal untuk akses offline. Widget harus ditambahkan sendiri melalui launcher.
- Jalur tambahan `Foto HP → Widget Pasangan`: memilih foto dari Photo Picker, mengompresnya menjadi JPEG maksimal sekitar 950 KB/1024 px, menyimpannya ke bucket Supabase `galeri-widget` yang privat, lalu menerbitkan URL bertanda tangan di dokumen slot bersama. HP pasangan mengambil foto ke cache lokal dan memperbarui widget saat aplikasi aktif atau WorkManager diizinkan berjalan.
- Jika perangkat pasangan belum pernah memilih scope widget, slot foto bersama akan dipilih sebagai default. Pilihan scope yang sudah tersimpan tetap dipertahankan.
- Notifikasi umum untuk media baru dan perubahan slot; payload tidak berisi foto, caption, token, atau URL media.
- Firestore dan Storage Rules tertutup, backend pairing tepercaya, App Check debug/release terpisah, workflow build APK dan tes Rules.

## Fitur yang belum lengkap / belum boleh dianggap lulus

- Detail media/pemutar video khusus dalam aplikasi, album UI, favorit UI, editor nama/foto profil, penghapusan akun end-to-end, layar kebijakan privasi yang terisi sesuai badan usaha/pengelola, konfigurasi beberapa instance widget, pemilihan acak slideshow, serta preview slot yang lengkap belum diimplementasikan seluruhnya.
- UI unggah belum menampilkan progres byte per file; unggahan batch menampilkan ringkasan hasil dan kesalahan.
- Tidak tersedia APK hasil build dari sesi ini. Workflow GitHub harus benar-benar selesai terlebih dahulu.
- Rules test Emulator, Cloud Functions TypeScript build, unit test, dan uji pada dua perangkat wajib dijalankan sebelum rilis produksi.


## Jalur foto widget online tanpa Firebase Storage

Fitur ini hanya untuk foto kecil yang ditampilkan di widget. Fitur ini **tidak** menghapus atau mengganti repositori galeri lama: unggah media ke Galeri Saya/Galeri Bersama masih menggunakan Firebase Storage, dan pairing/pemutusan pasangan masih menggunakan Cloud Functions. Karena Firebase mensyaratkan paket Blaze untuk Cloud Storage serta Cloud Functions, source aplikasi secara keseluruhan belum bisa dijamin sepenuhnya bebas billing. Jangan mengaktifkan billing atau menambahkan metode pembayaran jika belum setuju dengan kemungkinan biaya.

Jalur gambar widget hanya memakai Supabase Storage. Kuota Free yang relevan saat riset adalah 1 GB penyimpanan dan 5 GB egress; pemakaian di atas batas pada layanan gratis dapat membuat operasi tidak tersedia. Tetap di organisasi Free dan jangan menyetujui upgrade/menambahkan metode pembayaran jika tidak ingin berpotensi ditagih. Supabase dapat menjeda proyek Free yang kurang mendapat aktivitas database selama sekitar 7 hari; karena metadata proyek ini disimpan di Firestore, periksa dashboard Supabase secara berkala agar endpoint Storage tidak tiba-tiba tidak tersedia. Periksa halaman resmi sebelum setup: [harga dan kuota](https://supabase.com/docs/guides/platform/billing-on-supabase), [jeda proyek Free](https://supabase.com/docs/guides/platform/free-project-pausing), [Firebase third-party auth](https://supabase.com/docs/guides/auth/third-party/firebase-auth).

1. Buat proyek Supabase dengan paket **Free**, dan jangan memilih upgrade berbayar.
2. Di Supabase Dashboard, buka **Authentication → Third-party Auth → Firebase** dan daftarkan project ID Firebase yang sama dengan yang dipakai aplikasi.
3. Buka SQL Editor, ganti `YOUR_FIREBASE_PROJECT_ID` di `supabase/migrations/202610170001_widget_storage.sql` dengan project ID Firebase milikmu, lalu jalankan SQL tersebut. SQL membuat bucket `galeri-widget` privat dan memberi izin terbatas pada path `users/<Firebase UID>/...`.
4. Salin Project URL dan **publishable key** Supabase. Jangan memakai `service_role` key. Simpan keduanya sebagai GitHub repository variables bernama `SUPABASE_URL` dan `SUPABASE_PUBLISHABLE_KEY` (Settings → Secrets and variables → Actions → Variables). Keduanya juga dapat dimasukkan ke file lokal `supabase.properties` berdasarkan `supabase.properties.example`, dan file tersebut diabaikan Git.
5. Pastikan secret GitHub `GOOGLE_SERVICES_JSON_BASE64` juga disiapkan untuk Firebase. Push source ke GitHub; workflow akan membuat APK dengan konfigurasi kedua layanan. Jika variables Supabase belum disiapkan, build dapat tetap berjalan tetapi tombol upload widget online tidak akan berhasil.
6. Instal aplikasi pada dua HP, masuk ke akun berbeda, hubungkan pasangan, tambahkan widget Galeri Cinta dari daftar widget launcher pada kedua HP, lalu buka aplikasi satu kali di HP pasangan. Di `Yang Dipasang`, pilih **Sinkron pasangan** untuk slot yang dikehendaki dan tekan **Foto HP → Widget Pasangan**. Perubahan berikutnya ditulis ke slot bersama, lalu diunduh ke cache widget perangkat pasangan ketika perangkat online.

Bucket Supabase tetap privat. File fotonya tidak disimpan di Firebase Storage oleh jalur ini, tetapi link bertanda tangan yang dipakai widget bertindak seperti kunci akses selama berlaku (30 hari). Link hanya boleh dibagikan di metadata slot bersama yang akses Firestore-nya dibatasi untuk pasangan aktif. Setelah akses pasangan dicabut, jangan menganggap link yang sudah terbit langsung kedaluwarsa; ia bisa tetap dapat digunakan sampai expiry. Hindari foto sensitif.

**Batasan Android:** perubahan bisa terlihat hampir seketika jika pasangan sedang membuka aplikasi. Jika UI tertutup, WorkManager mencoba menyinkronkan berkala (minimal interval 15 menit yang dijadwalkan, bukan jaminan waktu eksekusi), dan launcher/Doze/penghemat baterai dapat menunda refresh. Link foto diperbarui oleh perangkat pemilik saat worker berjalan; bila pemilik offline cukup lama hingga link 30 hari kedaluwarsa, HP pasangan dapat mempertahankan gambar cache terakhir sampai link berhasil diperbarui. Ini bukan sinkronisasi instan 100%.

## 1. Konfigurasi Firebase

1. Buat project di [Firebase Console](https://console.firebase.google.com/).
2. Tambahkan aplikasi Android dengan Application ID **`com.plehooo.galericinta`**.
3. Unduh `google-services.json` dan letakkan di folder `app/google-services.json`. File itu sengaja masuk `.gitignore`; jangan menambahkannya ke commit publik.
4. Di Firebase Authentication, aktifkan provider **Email/Password**.
5. Buat Cloud Firestore dalam mode produksi. Jangan memakai Rules publik.
6. Siapkan Cloud Storage dan pahami biaya/quota lebih dahulu. **Cloud Storage for Firebase sekarang memerlukan paket Blaze (pay-as-you-go)**; penggunaan kecil pun perlu dipantau dan tidak bisa dijanjikan gratis. Pasang peringatan anggaran di Google Cloud Billing.
7. Di App Check, daftarkan aplikasi. Saat debug pertama kali, salin debug token yang dicetak SDK ke daftar token debug Firebase. Jangan mengaktifkan enforcement sebelum pengujian selesai. Build release memakai Play Integrity dan perlu dikonfigurasi untuk aplikasi release.
8. Daftarkan project lokal dengan menyalin `.firebaserc.example` menjadi `.firebaserc`, lalu ganti `YOUR_FIREBASE_PROJECT_ID` dengan ID project milikmu.

Referensi resmi:
- [Tambah Firebase ke aplikasi Android](https://firebase.google.com/docs/android/setup)
- [Firebase Authentication Android](https://firebase.google.com/docs/auth/android/start)
- [Cloud Storage untuk Android dan ketentuan paket](https://firebase.google.com/docs/storage/android/start)
- [Cloud Functions versi 2](https://firebase.google.com/docs/functions)
- [App Check Android](https://firebase.google.com/docs/app-check/android/play-integrity-provider)

## 2. Deploy Rules dan Cloud Functions

Butuh Node.js 22, Firebase CLI, dan akses ke project Firebase. Cloud Functions/Cloud Storage dapat memerlukan billing Blaze.

```bash
npm install
npm run build:functions
firebase login
firebase use --add
firebase deploy --only firestore:rules,firestore:indexes,storage,functions
```

Pastikan project aktif benar sebelum deploy. Jangan deploy ke project orang lain. Untuk merapikan kode undangan kedaluwarsa, pemilik project dapat menambahkan kebijakan Firestore TTL berbasis field `expiresAt` pada koleksi `pairingCodes`; penghapusan TTL tidak instan. Functions yang disediakan:

- `createInviteCode`: membuat kode undangan pendek umur terbatas dan hanya menyimpan hash.
- `redeemInviteCode`: membatasi 10 percobaan kode per akun setiap 15 menit dan menukar undangan satu kali secara transaksional.
- `disconnectCouple`: memutus hubungan dulu agar akses dicabut, lalu meminta pembersihan media bersama.
- Trigger notifikasi untuk media baru dan perubahan slot bersama.

Jika pembersihan Storage gagal saat hubungan diputus, fungsi mengembalikan `cleanupPending: true`; periksa Cloud Functions logs dan lakukan pembersihan terarah. Data privat pengguna tidak disentuh oleh fungsi pemutusan pasangan.

## 3. Build dan test

### Lokal Linux/Termux

Termux dapat menjalankan script bootstrap Gradle, tetapi build Android lokal tetap memerlukan Android SDK dan komponen SDK yang cocok. Alur yang disarankan adalah mengirim source ke GitHub lalu memakai GitHub Actions.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Untuk menguji rules dengan emulator (perlu Java 17+ dan Node 22):

```bash
npm install
npm run build:functions
npm run test:rules
```

### GitHub Actions

Dua workflow sudah tersedia:

- `Build Galeri Cinta APK`: membangun APK debug dan menjalankan unit test Kotlin, lalu mengunggah artifact `galeri-cinta-debug-apk`.
- `Test Firebase Security Rules`: compile Cloud Functions TypeScript lalu menjalankan Emulator Suite untuk Rules.

Tanpa konfigurasi Firebase, APK tetap bisa ter-build tetapi hanya menampilkan layar setup Firebase; autentikasi dan sinkronisasi tidak aktif. Untuk memasukkan konfigurasi ke workflow tanpa meng-commit file JSON, setelah `google-services.json` tersedia di folder `app/`, jalankan di Termux:

```bash
base64 app/google-services.json | tr -d '\n' | gh secret set GOOGLE_SERVICES_JSON_BASE64 --repo Plehooo/cinta
```

Ganti `Plehooo/cinta` jika menggunakan repository lain. Jangan pernah memasukkan service-account key/Admin SDK credential ke aplikasi atau repo.

## 4. Upload project dari Termux ke GitHub

Ekstrak arsip yang sudah diunduh ke `~/storage/downloads/GaleriCinta`, lalu jalankan:

```bash
pkg update
pkg install git gh nodejs-lts openjdk-17 curl unzip coreutils -y
termux-setup-storage
gh auth login
cd ~/storage/downloads/GaleriCinta
bash scripts/deploy-to-github-termux.sh
```

Script memakai repo default `Plehooo/cinta`. Jika repo belum ada, script meminta GitHub CLI membuatnya sebagai **private**; jika repo sudah ada, script clone lalu overlay source dan mempertahankan riwayat Git. Untuk tujuan lain:

```bash
GITHUB_REPO=USERNAME/NAMA_REPO bash scripts/deploy-to-github-termux.sh
```

Jika repo tujuan yang sudah ada benar-benar kosong dan clone gagal, buat satu commit awal melalui GitHub lalu ulangi. Script tidak memakai force-push atau menghapus sejarah repo.

Sesudah workflow `Build Galeri Cinta APK` berstatus sukses: buka tab **Actions** → run terbaru → artifact **`galeri-cinta-debug-apk`** → download dan ekstrak. APK berada di dalam artifact ZIP dengan nama `app-debug.apk`. Ini APK debug untuk pengujian, bukan release yang ditandatangani dengan kunci milikmu.

## 5. Pengujian minimum sebelum dipakai untuk foto pribadi

- Buat dua akun terpisah dan pastikan keduanya dapat pairing.
- Pastikan kode kedaluwarsa/terpakai ditolak, akun ketiga tidak dapat membaca galeri bersama, dan galeri privat tetap privat.
- Uji upload, retry setelah jaringan putus, penghapusan media, putus pasangan, dan status `cleanupPending`.
- Uji Slot 1/Slot 2 secara terpisah, mode manual/slideshow, scope pribadi/bersama, dan widget pada launcher/perangkat berbeda.
- Jalankan Rules emulator test dan tinjau hasil build/test di Actions. Jangan menerbitkan aplikasi jika salah satu workflow gagal.

## Struktur penting

```text
app/                 Kotlin + Compose, repository, widget, worker
functions/src/       Cloud Functions v2 (TypeScript)
firestore.rules      Aturan akses Firestore
storage.rules        Aturan akses objek media
firestore.indexes.json
firebase.json        Konfigurasi Firebase CLI + emulator
tests/               Tes rules dengan Firebase Emulator Suite
.github/workflows/   CI Android APK dan tes Rules
scripts/             Script deploy Termux → GitHub
```


## Upload dari Termux ke GitHub

Repository target default adalah `Plehooo/cinta`. Script mendukung repository kosong maupun repository yang sudah berisi file, mempertahankan riwayat Git, dan tidak menggunakan `--force`.

```bash
termux-setup-storage
pkg install git gh nodejs-lts openjdk-17 curl unzip coreutils -y
cd ~/storage/downloads
unzip -o GaleriCinta.zip
cd ~/storage/downloads/GaleriCinta
gh auth login
bash scripts/deploy-to-github-termux.sh
```

Untuk target lain, gunakan `GITHUB_REPO=OWNER/REPO bash scripts/deploy-to-github-termux.sh`. Setelah push, buka tab Actions dan unduh artifact APK hanya jika workflow selesai dengan status sukses.
