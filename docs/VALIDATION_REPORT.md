# Catatan riset dan validasi — Galeri Cinta

Tanggal catatan: 17 Oktober 2026.

Dokumen ini membedakan hasil yang benar-benar dijalankan dari rencana pemeriksaan yang akan berjalan setelah source di-push ke GitHub. Tidak ada klaim bahwa ribuan sumber telah dibaca, atau bahwa APK sudah berhasil dibuat.

## Keputusan implementasi berdasarkan dokumentasi resmi

1. **Firebase Android BoM** — proyek memakai `35.0.1`, yang tercantum pada dokumentasi setup Firebase Android saat riset. Menggunakan BoM agar versi SDK Firebase dipilih sebagai satu set yang kompatibel.
2. **Cloud Storage for Firebase** — konfigurasi bucket memerlukan paket Blaze (bayar sesuai penggunaan). Buat anggaran dan peringatan biaya sebelum mengunggah foto pribadi; paket ini tidak boleh disebut gratis tanpa syarat.
3. **Widget Android** — `updatePeriodMillis` untuk widget tidak mendukung interval kurang dari 30 menit. Proyek memakai WorkManager untuk rotasi yang boleh tertunda, tetapi Doze, launcher, baterai, dan koneksi dapat menunda eksekusi; pembaruan tepat detik tidak dijanjikan.
4. **Cloud Functions** — runtime Node.js 22 merupakan runtime yang didukung dokumentasi Firebase saat riset.
5. **Keamanan** — aturan Firestore dan Storage memvalidasi pemilik/anggota aktif, path media kanonis, dan referensi slot. Pairing code hanya dapat dikelola melalui callable Functions, memiliki masa berlaku, hash server-side, penukaran transaksional, dan pembatasan percobaan.
6. **Data privat** — galeri privat dan galeri bersama menggunakan koleksi/path berbeda. Membagikan media privat membuat salinan baru; memilih/lepas slot hanya mengubah konfigurasi referensi, bukan menghapus berkas aslinya.

## Validasi yang dijalankan dalam lingkungan source

| Pemeriksaan | Hasil | Batasan |
|---|---|---|
| Parse seluruh berkas JSON dan `.firebaserc.example` | LULUS | Hanya validitas sintaks JSON |
| Parse seluruh berkas XML | LULUS | Tidak menggantikan Android resource merger |
| `bash -n` untuk script deploy Termux | LULUS | Belum menjalankan push ke repo sungguhan |
| `sh -n` untuk bootstrap `gradlew` | LULUS | Gradle/Android SDK tidak terpasang di lingkungan ini |
| `node --check tests/rules.test.mjs` | LULUS | Memvalidasi sintaks JavaScript tes, bukan aturan Firebase |
| Transpilasi sintaks TypeScript Functions | LULUS | Bukan type-check penuh atau build dengan dependensi Firebase terpasang |
| Kompilasi `domain/Models.kt` dengan Kotlin compiler | LULUS | Hanya file domain mandiri, bukan seluruh aplikasi Android |
| Parse GitHub Actions workflow YAML setelah penambahan Supabase variables | LULUS | Parse YAML statis, bukan menjalankan GitHub Actions |
| `bash -n` / `sh -n` untuk script Termux dan bootstrap Gradle | LULUS | Tidak melakukan deploy atau download Gradle |
| Pemeriksaan jalur source widget langsung ke Supabase, private bucket, dan membership rule | LULUS | Pemeriksaan struktur/sumber; belum merupakan uji integrasi backend |

## Pemeriksaan yang belum bisa dinyatakan lulus

- `assembleDebug` dan `testDebugUnitTest` untuk semua source Android: belum berjalan karena Android SDK/Gradle dan dependency classpath tidak tersedia di lingkungan pembuatan source.
- `npm install` untuk mengambil dependensi pengujian dicoba, tetapi proses tidak selesai dalam batas waktu lingkungan; tidak ada `node_modules` atau lockfile parsial yang disertakan. Karena itu `npm run build:functions` dan emulator tests tetap belum dinyatakan lulus.
- Firestore/Storage Emulator Suite dan `npm run test:rules`: tes disediakan, tetapi belum dijalankan terhadap emulator.
- Uji dua akun pada dua perangkat Android, App Check release/Play Integrity, izin notifikasi, widget launcher, offline, Doze, serta biaya/billing Firebase: membutuhkan konfigurasi dan perangkat/proyek milik pemilik aplikasi.
- APK debug belum tersedia dalam ZIP ini. Workflow GitHub Actions akan menghasilkan artifact hanya setelah run build benar-benar selesai sukses.

## Dokumentasi resmi yang ditinjau

- Firebase Android setup dan BoM: https://firebase.google.com/docs/android/setup
- Firebase Android SDK release notes: https://firebase.google.com/support/release-notes/android
- Cloud Storage setup dan persyaratan billing: https://firebase.google.com/docs/storage/android/start
- Firebase Security Rules: https://firebase.google.com/docs/rules/basics
- Firebase Cloud Functions runtime: https://firebase.google.com/docs/functions/manage-functions
- Panduan widget Android dan pembaruan berkala: https://developer.android.com/develop/ui/views/appwidgets/advanced
- Android Activity/Photo Picker: https://developer.android.com/jetpack/androidx/releases/activity
- Android Lifecycle: https://developer.android.com/jetpack/androidx/releases/lifecycle

## Urutan verifikasi sebelum produksi

1. Push source ke GitHub dan tunggu workflow **Build Galeri Cinta APK** selesai. Perbaiki semua error build sebelum mengunduh APK.
2. Pastikan `GOOGLE_SERVICES_JSON_BASE64` berasal dari `google-services.json` untuk package `com.plehooo.galericinta`, lalu deploy Rules, indeks, dan Functions ke project Firebase yang benar.
3. Jalankan workflow **Test Firebase Security Rules** dan pastikan Emulator Suite lulus.
4. Uji seluruh alur dengan dua akun/perangkat dan akun ketiga sebagai penguji penolakan akses.
5. Setelah semua lulus, siapkan signing release secara terpisah; jangan memakai APK debug sebagai rilis publik dan jangan mengunggah private signing key/Admin SDK credential ke repository.


## Addendum: foto langsung ke widget pasangan

Source kini menambahkan `WidgetCloudStore.kt`, bucket privat Supabase `galeri-widget`, metadata `widgetImageUrl/widgetImagePath/widgetImageOwnerUid` pada konfigurasi slot bersama, `WidgetSyncWorker`, dan konfigurasi Supabase opsional di GitHub Actions. Jalur baru hanya mengunggah gambar widget yang dikompresi; galeri utama, Functions pairing, dan unggah media lama tetap memakai jalur Firebase yang sudah ada. Scope bersama dipilih otomatis sebagai default pada perangkat yang belum memiliki pilihan scope, tetapi pilihan scope lokal yang sudah tersimpan tidak ditimpa.

- Pemeriksaan source statis untuk fitur ini: belum cukup membuktikan integrasi HTTP, Supabase RLS, Firestore Rules, atau rendering widget di perangkat nyata.
- Uji Android `assembleDebug` / `testDebugUnitTest` tetap belum dijalankan di lingkungan ini.
- SQL migration memuat placeholder `YOUR_FIREBASE_PROJECT_ID`; wajib diganti sebelum dijalankan. Tambahkan integrasi Firebase di Supabase Third-party Auth menggunakan project ID yang sama.
- URL bertanda tangan memiliki masa berlaku 30 hari. Worker perangkat pemilik mencoba memperbaruinya saat hampir habis, sehingga foto mungkin tetap menggunakan cache jika pemilik offline lama.
- Supabase Free memiliki batas penggunaan dan dapat menjeda project yang tidak aktif; ini bukan jaminan hosting gratis tanpa gangguan. Firebase Storage dan Cloud Functions untuk fitur lama tetap memerlukan Blaze menurut ketentuan Firebase.
