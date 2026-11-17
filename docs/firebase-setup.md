# Firebase Setup — AnimLovers

Login Facebook/Google memakai Firebase — proyek **baru** (proyek lama
`animlov-fcea5` sudah dihapus, tidak bisa dipakai lagi). Sampai langkah di
bawah selesai, aplikasi memakai `StubAuthRepository`: tombol Masuk
menampilkan pesan "masuk belum tersedia" dan gerbang Ecchi tetap terkunci
dengan aman. **Jangan mengarang kredensial** — tanpa config asli, login
memang tidak jalan.

## 1. Buat proyek Firebase

1. Buka [Firebase Console](https://console.firebase.google.com/) → Add project.
2. Nama bebas (mis. `animelovers-reborn`), matikan Google Analytics bila
   tidak perlu → Create project.

## 2. Daftarkan aplikasi Android

1. Di Project Overview → Add app → Android.
2. Android package name: **`animelovers.rebornhd`** (harus persis).
3. App nickname: `AnimLovers`.
4. SHA-1 (untuk Google Sign-In): dari keystore rilis
   ```bash
   keytool -list -v -keystore keystore.jks -alias animelovers
   ```
   Salin nilai `SHA1` → masukkan di Firebase (Project settings → Your apps
   → Add fingerprint). Untuk debug, tambahkan juga SHA-1 debug keystore.
5. Download **`google-services.json`** → letakkan di `app/google-services.json`
   (sejajar `app/build.gradle.kts`). File ini di-`.gitignore` — untuk CI,
   simpan isinya sebagai secret dan tulis ulang saat build rilis.

## 3. Aktifkan metode masuk

Firebase Console → Authentication → Sign-in method:

- **Google** → Enable → isi support email → Save.
- **Facebook** → Enable → isi App ID + App secret dari
  [Meta for Developers](https://developers.facebook.com/) (buat aplikasi
  Facebook baru, tambahkan produk Facebook Login, isi OAuth redirect URI
  dari Firebase) → Save.

## 4. Tambah dependensi

`gradle/libs.versions.toml` (bagian `[libraries]`):

```toml
firebase-bom = { group = "com.google.firebase", name = "firebase-bom", version = "32.7.0" }
firebase-auth = { group = "com.google.firebase", name = "firebase-auth" }
firebase-auth-ktx = { group = "com.google.firebase", name = "firebase-auth-ktx" }
play-services-auth = { group = "com.google.android.gms", name = "play-services-auth", version = "20.7.0" }
facebook-login = { group = "com.facebook.android", name = "facebook-login", version = "16.3.0" }
```

`app/build.gradle.kts` (bagian atas + `dependencies`):

```kotlin
plugins {
    id("com.google.gms.google-services")
    // …plugin yang sudah ada
}

dependencies {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    implementation(libs.play.services.auth)
    implementation(libs.facebook.login)
}
```

Root `build.gradle.kts` (bagian `plugins`):

```kotlin
id("com.google.gms.google-services") version "4.4.0" apply false
```

## 5. Ganti Stub dengan implementasi Firebase

Buat `data/auth/FirebaseAuthRepository.kt` yang mengimplementasikan
`AuthRepository` (antarmuka tidak berubah — UI + gerbang tidak tersentuh):

- `isSignedIn()` → `FirebaseAuth.getInstance().currentUser != null`
- `userEmail()` / `userPhotoUrl()` → dari `currentUser`
- `provider()` → `"facebook"` / `"google"` (dari `providerData`)
- `signInGoogle()` → Google Sign-In (Credential Manager /
  `GoogleSignInClient`) → `GoogleAuthProvider.getCredential(idToken)` →
  `signInWithCredential`, lalu simpan email/foto/provider ke `Prefs`
  (`auth_email`, `auth_photo`, `auth_provider`).
- `signInFacebook()` → `LoginManager.logInWithReadPermissions(activity,
  listOf("email", "public_profile"))` → `FacebookAuthProvider.getCredential(token)`
  → `signInWithCredential`, lalu simpan ke `Prefs` seperti di atas.
- `signOut()` → `FirebaseAuth.signOut()` + `LoginManager.logOut()` +
  `GoogleSignInClient.signOut()` + `Prefs.clearAuth()` + `Prefs.clearEcchi()`.

Lalu ganti **satu baris** di `AnimeloversApp.kt`:

```kotlin
// SEBELUM:
val authRepository: AuthRepository by lazy { StubAuthRepository(this) }
// SESUDAH:
val authRepository: AuthRepository by lazy { FirebaseAuthRepository(this) }
```

## 6. Uji

1. Build debug, buka Pengaturan → Masuk dengan Google → email + foto
   tampil, toggle "Aktifkan Ecchi" muncul.
2. Nyalakan toggle → Simpan → buka judul bergenre Ecchi → overlay kunci
   hilang.
3. Keluar → toggle Ecchi mati lagi (pref `hrdku87` dihapus) → judul Ecchi
   terkunci lagi.

## 7. Detail aplikasi Facebook + troubleshooting (tambahan Backend)

### 7a. Membuat aplikasi Facebook (sumber App ID + App Secret)

1. Buka [developers.facebook.com](https://developers.facebook.com),
   masuk dengan akun Facebook Faygo.
2. **My Apps → Create App** → tipe **Consumer** (atau "Lainnya" bila
   Consumer tidak muncul) → nama bebas, mis. `AnimeLovers Revival`.
3. **Add Product → Facebook Login → Set Up**.
4. **Settings → Basic** → catat **App ID** dan **App Secret** (tekan
   Show — jangan dibagikan ke siapa pun, jangan ditulis ke file
   kode/chat/repo; hanya di kolom Firebase langkah 3).
5. **Facebook Login → Settings → Valid OAuth Redirect URIs** →
   tempel URI dari Firebase (bentuknya
   `https://<project-id>.firebaseapp.com/__/auth/handler`) → Save.

> **Mode Development:** aplikasi Facebook baru berstatus Development —
> hanya admin/developer/tester aplikasi itu yang bisa login. Untuk
> revival ini (dipakai Faygo sendiri + penguji yang ia tunjuk) itu
> sudah cukup. Bila suatu hari dibuka untuk umum, barulah aplikasi
> Facebook perlu diajukan ke mode Live + App Review.

### 7b. Bila mentok

- **"App ID / App Secret salah" di Firebase** → periksa ulang salinan
  dari Facebook Settings → Basic (App Secret harus ditekan Show dulu,
  jangan diketik dari ingatan).
- **Login Google gagal `DEVELOPER_ERROR`** → SHA-1 belum terdaftar
  (langkah 2) atau `google-services.json` belum diperbarui sesudah
  SHA-1 ditambahkan (unduh ulang dari Firebase).
- **Login Facebook gagal "URL Blocked"** → redirect URI langkah 7a
  belum tersimpan / salah salin.
- **Jangan memakai proyek `animlov-fcea5`** (mati) atau kredensial
  apa pun dari laporan bedah REA §7 (kunci JWT lama dll. — milik
  server mati, bukan untuk dipakai).
- Selain itu: kirim screenshot pesan errornya ke Orkestrator — jangan
  kirim App Secret-nya.
