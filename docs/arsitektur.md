# Arsitektur — AnimLovers

## Lapisan

```
UI (Activity/Fragment + Adapter + ViewBinding)
        ↓
Repository (AnimeRepository, PlayerRepository)
        ↓
API (Retrofit/OkHttp)          Lokal (Room + SharedPreferences)
  AnikotoApi  VidCloudApi       AppDatabase (record.db)
  SubtitleApi (backend)        Prefs ("animlovers")
        ↓
AuthRepository (seam login)
```

Tanpa DI framework — dependensi dibuat di `AnimeloversApp` (lazy) dan
diambil lewat `Context.app()`. Sengaja sederhana: tim kecil, aplikasi
kecil, dan pola ini 1:1 dengan aslinya.

## Alur data utama

1. **Katalog** — `AnikotoApi.recentAnime(page)` (20 item/halaman, 471 halaman).
   Beranda memakai halaman 1; Jelajahi memuat halaman demi halaman di latar
   belakang dan memfilter lokal (genre + judul).
2. **Detail** — `AnikotoApi.series(id)` → judul, poster, status, genre,
   deskripsi, daftar episode. Genre bisa `null` pada detail — ditangani
   null-safe (`genres` kosong bila tidak ada).
3. **Putar** — `PlayerRepository.resolve(aniId, ep)`:
   backend `/resolve` (bila ada) → VidCloud `getSources` server 1→2→3.
   Subtitle: track "Indonesian" native → backend `/subtitle/id` → Inggris.
   URL ber-token (`?t=`) hanya di memori, tidak pernah disimpan.
4. **Riwayat** — Room tabel `video` (skema asli) + `favorite` (baru).
   Aturan tulis resume: episode sama → update posisi; episode beda →
   hapus baris lama + insert baru (mulai 0). Posisi simpan = posisi − 30 detik.

## Tema

Dua tema (`DarkTheme`/`LightTheme`) lewat atribut `?attr/bgColor`,
`?attr/textColor`, `?attr/metaTextColor` (gelap default `#2b2a2a`,
terang `#ffffff`). Tiap activity memanggil `setTheme` di `onCreate`
(`BaseActivity`) sebelum `super.onCreate`. Detail memakai status bar
transparan; VideoPlay memakai `FullscreenTheme` dari manifest (tanpa tema
aplikasi).

## Gerbang Ecchi

Satu aturan di `AnimeRepository.isEcchi` + `isEcchiUnlocked`:

- Genre mengandung "Ecchi" **dan** (belum login **atau** toggle Ecchi mati)
  → konten dikunci.
- Detail: overlay kunci + "Buka Sekarang" → Pengaturan.
- Jelajahi: genre Ecchi disembunyikan dari spinner + hasil.
- Login: `AuthRepository` — implementasi aktif `StubAuthRepository`
  (selalu belum login) sampai Firebase dipasang; ganti satu baris di
  `AnimeloversApp` untuk mengaktifkan Firebase (lihat
  `docs/firebase-setup.md`).

## Player (Media3)

- `ExoPlayer` + `DefaultLoadControl` (buffer 50 dtk, mulai 2,5 dtk).
- HLS lewat `MediaItem` (mime `application/x-mpegURL`).
- Subtitle eksternal: `SubtitleConfiguration` VTT (Indonesia `DEFAULT`,
  Inggris cadangan).
- SD/HD: `DefaultTrackSelector` max-video-size (SD cap 480p, HD tanpa cap)
  — tanpa reload karena sumbernya HLS.
- FIT/FILL: `PlayerView.setResizeMode` (0/3).
- Orientasi: pref `orientation` "6"/"7" (SENSOR_LANDSCAPE/PORTRAIT);
  `awallayar` menyimpan orientasi sebelum masuk player dan dipulihkan
  saat keluar.

## Yang sengaja TIDAK ada (PRD §12)

Iklan, tombol Request, chat, FCM, ADM, cache video, playback speed, PiP,
Chromecast, resolusi granular, Remote Config / checkloc / kill-switch.

## Batasan yang diketahui

- Compile belum terverifikasi lokal (tanpa Android SDK) — bukti compile
  adalah CI hijau (`android-ci.yml`).
- "Unduh" via DownloadManager mengunduh file playlist `.m3u8` saja, bukan
  video offline (HLS butuh segmen). Unduhan offline HLS yang benar butuh
  Media3 DownloadService — di luar cakupan v1.
- Pencarian Jelajahi hanya menyaring halaman katalog yang sudah termuat.
- "Terlaris" diurutkan skor tertinggi (Anikoto tidak memberi jumlah tonton).
- VTT server 2 VidCloud → 403 dari IP sandbox; subtitle diambil dari
  server 1, fallback MegaPlay (belum diimplementasikan — jalur VidCloud
  langsung + backend).
