# Arsitektur — AnimLovers v2 (Sanka, hardsub)

## Lapisan

```
UI (Activity/Fragment + Adapter + ViewBinding)
        ↓
Repository (SankaRepository, PlayerRepository)
        ↓
API (WebView tersembunyi)       Lokal (Room + SharedPreferences)
  SankaWebTransport (satu-satunya) AppDatabase (record.db, kunci SLUG)
  RateLimiter + cache           Prefs ("animlovers")
        ↓
AuthRepository (seam login)
```

Tanpa DI framework — dependensi dibuat di `AnimeloversApp` (lazy) dan
diambil lewat `Context.app()`. Sengaja sederhana: tim kecil, aplikasi
kecil, dan pola ini 1:1 dengan aslinya.

**Perbedaan terbesar dari v1:** tidak ada kotak "backend kecil" di tengah.
Aplikasi bicara langsung ke Sanka untuk data dan langsung ke
Pixeldrain/embed untuk video. Konsekuensinya: (a) tidak ada lagi yang
harus dijalankan di Termux untuk menonton; (b) semua disiplin (rate
limit, cache, retry) harus hidup DI DALAM aplikasi.

## Alur data utama

1. **Katalog** — `SankaRepository.home()` (via transport WebView) → `featured` (slider), `latest`
   (Episode Baru), `popular` (Terlaris). Jelajahi: pencarian server-side
   (`/search?q=`) + genre (`/genre/:slug`) — bukan filter lokal seperti v1.
2. **Detail** — `SankaRepository.detail(slug)` → judul, poster, status, genre,
   deskripsi, daftar episode (`num/title/date/slug/url`). Beberapa field
   bisa null (`alt_title`, `score`) — model harus tahan null. **Jangan
   pernah** memanggil detail dengan slug episode (`/detail/<slug-episode>`
   → 404/500); detail hanya untuk slug serial.
3. **Sumber episode** — `SankaRepository.episode(slug)` (terkonfirmasi T1b) →
   `default_iframe` + `sources[]` + `mirrors[]` + `downloads[]`. Slug
   WAJIB dari respons detail — jangan dirangkai sendiri (contoh: ep 4
   Ao no Hako S2 slug-nya tanpa akhiran `-subtitle-indonesia`).
4. **Putar** — `PlayerRepository` memilih sumber dengan prioritas (T1b):
   (1) Pixeldrain direct 720p dari `sources[]` (`kind: direct`, terbukti
   3/4 episode); (2) stream token Animekompi dari `mirrors[]` type `mp4`
   (206 terbukti); (3) Blogger hasil ekstrak — itag 22 (720p) → itag 18
   (360p); (4) WebView embed (fallback terakhir). Bila satu sumber gagal
   → fallback otomatis ke berikut tanpa tanya pengguna. URL ber-token /
   hasil ekstrak terikat waktu + IP — ambil segar tiap kali putar,
   **JANGAN** disimpan di DB/riwayat.
5. **Riwayat** — Room tabel `video` + `favorite`. **Kunci berubah:**
   `idtitle` kini SLUG Sanka (teks), bukan id numerik v1. Aturan
   tulis resume: episode sama → update posisi; episode beda → hapus
   baris lama + insert baru (mulai 0). Posisi simpan = posisi − 30 detik.
   Migrasi v1→v2 destruktif: riwayat/favorit v1 dihapus bersih (keputusan
   Faygo D4 — id v1 tidak bisa dipetakan ke slug).

## Disiplin rate limit — WAJIB (PRD v2 §3.5)

Sanka: **45 request/menit** (angka ketat), **3 pelanggaran → BAN
PERMANEN** (IP diblokir selamanya). Satu ban mematikan aplikasi.

- Throttle client-side: jeda minimum **≥ 1,4 detik** antar panggilan
  Sanka (≈ 42/menit). Semua panggilan lewat satu gerbang (`SankaTransport` +
  `RateLimiter`) — tidak ada panggilan liar dari layar mana pun.
- Tanpa fan-out paralel: satu layar = satu request berjalan; jangan ambil
  detail 10 judul sekaligus (pakai data `home` yang sudah ada).
- Cache agresif: `home`, `search`, `detail` di-cache (memori + cache
  OkHttp/Room) — buka ulang layar yang sama = baca cache, bukan request
  baru. Cache juga menolong koneksi lambat (pola Mitsunime: katalog lokal).
- Dedup + batalkan: request yang sama digabung; request layar yang sudah
  ditinggalkan dibatalkan.
- Backoff + berhenti: bila 429 → berhenti total, jangan retry membabi
  buta; tampilkan "Terlalu banyak permintaan, coba lagi sebentar lagi".
- User-Agent: UA OkHttp default atau UA browser (terbukti 200). JANGAN
  UA curl polos (403) dan jangan UA kosong.
- Toleransi 500: scraper Animekompi kadang timeout (500 "timeout of
  20000ms exceeded" teramati) — aplikasi harus tahan 500 + backoff jujur,
  bukan crash.

## Tema

Dua tema (`DarkTheme`/`LightTheme`) lewat atribut `?attr/bgColor`,
`?attr/textColor`, `?attr/metaTextColor` (gelap default `#2b2a2a`,
terang `#ffffff`). Tiap activity memanggil `setTheme` di `onCreate`
(`BaseActivity`) sebelum `super.onCreate`. Detail memakai status bar
transparan; VideoPlay memakai `FullscreenTheme` dari manifest (tanpa tema
aplikasi).

## Gerbang Ecchi

Satu aturan di `SankaRepository.isEcchi` + `isEcchiUnlocked`:

- Genre mengandung "Ecchi" **dan** (belum login **atau** toggle Ecchi mati)
  → konten dikunci. (Ecchi + Erotica + Hentai terkunci di balik gerbang
  yang sama — keputusan Faygo v1.)
- Detail: overlay kunci + "Buka Sekarang" → Pengaturan.
- Jelajahi: genre Ecchi disembunyikan dari spinner + hasil.
- Login: `AuthRepository` — implementasi aktif `StubAuthRepository`
  (selalu belum login) sampai Firebase dipasang; ganti satu baris di
  `AnimeloversApp` untuk mengaktifkan Firebase (lihat
  `docs/firebase-setup.md`).

## Player (Media3 + WebView)

- `ExoPlayer` + `DefaultLoadControl` (buffer 50 dtk, mulai 2,5 dtk).
- MediaSource utama: **MP4 progresif** (`ProgressiveMediaSource`) untuk
  Pixeldrain / stream token / Blogger hasil ekstrak — bukan HLS.
- **TIDAK ADA tombol Subtitle** (hardsub tidak punya track) dan tidak ada
  logika ganti track subtitle.
- Kualitas: tombol alih `resolution` — "720p" / "480p" sesuai sumber yang
  diputar (jujur, jangan tulis 1080p); bila hanya satu sumber, tampil
  varian `_off` dan tidak bisa diketuk. Pref `hd_only` diartikan ulang
  sebagai "utamakan 720p/480p".
- Mode WebView (embed Blogger/Abyssplayer, fallback terakhir): bilah atas
  minimal + kontrol bawaan halaman embed; Berikutnya/Sebelumnya +
  auto-play-next TIDAK berlaku di mode WebView (aplikasi tidak bisa
  mendeteksi video selesai).
- Loading: `ProgressBar` + teks "Memuat video…" sebelum frame pertama;
  kontrol tidak auto-hide selama memuat supaya tombol kualitas selalu
  bisa diketuk.
- FIT/FILL: `PlayerView.setResizeMode` (0/3).
- Orientasi: pref `orientation` "6"/"7" (SENSOR_LANDSCAPE/PORTRAIT);
  `awallayar` menyimpan orientasi sebelum masuk player dan dipulihkan
  saat keluar.

## Unduh (DownloadManager)

- Via DownloadManager sistem, tanpa ADM, folder
  `Downloads/AnimLovers/<Judul>-Ep-<n>.mp4` (tetap).
- Yang diunduh kini **file .mp4 betulan** — URL Pixeldrain direct dari
  `sources[]` (sama dengan sumber streaming). `downloads[]` Sanka berisi
  halaman unduhan saja (bukan file langsung) — jangan dipakai. Bila
  episode tanpa Pixeldrain: tidak ada unduhan video (hanya embed).
- Tanpa pilihan kualitas (keputusan Faygo D3) — unduh kualitas yang
  sedang diputar.

## Yang sengaja TIDAK ada (PRD v2 §13)

Iklan, tombol Request, chat, FCM, ADM, cache video, playback speed, PiP,
Chromecast, resolusi granular, Remote Config / checkloc / kill-switch,
sistem subtitle (VTT/translate/tracking), backend lokal.

## Batasan yang diketahui

- Compile belum terverifikasi lokal (tanpa Android SDK) — bukti compile
  adalah CI hijau (`android-ci.yml`).
- 1080p streaming TIDAK ADA di sumber ini (0/4 episode contoh) — label
  kualitas aplikasi tidak boleh menulis 1080p.
- Pixeldrain 720p hanya di 3/4 episode contoh — sisanya jatuh ke embed
  480p (fallback otomatis).
- File 720p 142–264 MB/episode — di koneksi lambat, frame pertama bisa
  lama; player menampilkan spinner + "Memuat video…" yang jujur.
- Sanka adalah scraper Animekompi tanpa kontrak resmi — bentuk respons
  bisa berubah tanpa pemberitahuan (host stream sudah pindah v10→v11).
  Cadangan bila Sanka mati: Samehadaku (keputusan Faygo D5 — jalur
  berbeda, butuh adapter sendiri, lihat `docs/kontrak-api.md` §5).
- Putar URL hasil ekstrak Blogger + URL stream token di jaringan rumah
  belum teruji dari IP sandbox (terikat IP) — wajib uji rumah.
