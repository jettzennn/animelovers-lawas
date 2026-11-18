# Arsitektur — AnimLovers v2 (Sanka, hardsub)

## Lapisan

```
UI (Activity/Fragment + Adapter + ViewBinding)
        ↓
Repository (SankaRepository, PlayerRepository)
        ↓
API (WebView ditempel 1×1)    Lokal (Room + SharedPreferences)
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

**v2.0.3 — transport WebView ditempel:** WebView transport DITEMPEL ke
decor view MainActivity (1×1 px, INVISIBLE, tidak bisa difokuskan) —
v2.0.2 yang tak tertempel tidak memicu `onPageFinished` di Vivo Faygo
(Beranda + Jelajahi muter terus). UA default WebView minus penanda
"Version/4.0" (sisa UA persis bawaan stack WebView di HP itu). Alasan
gagal tampil di toast (`Gagal memuat (…alasan…)`) + `Log.e`
(`SankaWebTransport`) — tidak lagi toast generik buta.

**v2.0.4 — DIAGNOSTIK + PERBAIKAN R8:** uji HP v2.0.3 tetap gagal
(Beranda kosong + toast GENERIK tanpa alasan, Jelajahi "Tidak
ditemukan") padahal server Sanka sehat — kegagalannya jenis yang belum
pernah dipetakan. Satu variabel: buat kegagalan ini BERSUARA. (a)
data-null amplop (home/detail/episode) kini `SankaApiErrorException`
— status + message Sanka ikut ke toast (`Gagal memuat (Sanka: error —
…)`), dan 300 karakter pertama raw response di-`Log.e`
(`SankaRepository`); dulu IOException polos yang tak terpetakan.
(b) `sankaErrorReason` kini punya catch-all: exception tak dikenal pun
tampil sebagai `ClassName: pesan` di toast — tidak ada lagi kegagalan
tanpa nama. Rate limit tetap ditangani terpisah (bukan lewat toast
ini). Throttle 1,4 dtk, timeout 30 dtk, attach WebView, UA strip, dan
aturan retry 429/5xx TIDAK berubah.

**v2.0.4 — PERBAIKAN R8 (root cause "Gagal memuat" v2.0.0–v2.0.3):**
laporan forensik Gemini + verifikasi Orchestrator dari APK release
v2.0.3 asli: R8 Full Mode (`isMinifyEnabled = true`, R8 8.2.47)
menggabungkan subclass TypeToken anonim di `parseEnvelope` + menghapus
generic Signature → constructor Gson melempar `IllegalStateException`
tiap parse — dan tipe itu tak terpetakan di toast, jadi kegagalan
tampil bisu. Tes JVM tidak pernah bisa menangkapnya (tanpa obfuskasi).
Perbaikan dua lapis: (1) `SankaRepository.parseEnvelope` kini memakai
`TypeToken.getParameterized` (`envelopeType`/`envelopeListType`) —
tanpa subclass anonim, kebal R8 apa pun; (2) `proguard-rules.pro`
menahan `-keepattributes Signature, InnerClasses, EnclosingMethod,
*Annotation*` + `-keep class com.google.gson.reflect.TypeToken` +
`-keep class animelovers.rebornhd.data.**` + keep `@SerializedName`.
`verify.py` kini menolak subclass TypeToken anonim di kode produksi.
Perilaku yang diharapkan di HP: Beranda + Jelajahi terisi.

**v2.0.5 — DIALOG GELAP + PLAYER TANPA DIALOG MODE + DETAIL TERANG + SPLASH 0:**

1. **Dialog gelap penuh:** parent `DarkAlert` dulu
   `Theme.AppCompat.Light.Dialog.Alert` — `android:background` hanya
   menggelapkan ISI dialog, latar window/decor ikut parent Light (putih):
   strip putih di kanan dialog data seluler / WiFi terputus / disclaimer.
   Parent kini `Theme.AppCompat.Dialog.Alert` + `android:windowBackground`
   dan `android:colorBackground` gelap penuh (`@color/darktheme`).
   `LightAlert` tidak disentuh; `alertTheme()` (BaseActivity) tidak berubah.
2. **Dialog mode di player DIHAPUS (D1):** `showModeDialog()` +
   parameter `showModeDialog` di `loadEpisode()` + string
   `pilih_mode`/`streaming` dihapus (`batal` tetap: dipakai tombol Batal
   di Pengaturan); semua call site langsung
   `playCandidate(pickStartCandidate(resolved))` — masuk player langsung
   memutar (pref `hdOnly` tetap dihormati). **Unduh PINDAH ke kontrol
   player** (D1): tombol ikon `download_new` di kolom kanan kontrol Media3
   (`downloadBtn`, `exo_playback_control_view.xml`) + bilah atas mode
   WebView (`webViewDownload`, `activity_video.xml`); keduanya memanggil
   `downloadSource(downloadUrl, finishAfter=false)` — unduh TIDAK lagi
   menutup player. Tombol hanya tampil bila ada file .mp4 langsung
   (Pixeldrain) — `updateDownloadUi()`. PENYIMPANGAN disengaja dari REA
   §4.1 (dialog "Silahkan pilih salah satu mode" memang ada di aslinya).
3. **Detail terang setelah load:** `ShimmerFrameLayout` dulu membungkus
   SELURUH konten dan setelah load hanya `stopShimmer()` — wadah shimmer
   tetap di atas konten (tampak gelap/redup). Kini konten anak langsung
   `ScrollView`; skeleton jadi overlay terpisah (id sama,
   `shimmer_view_container`) yang hanya tampil saat loading
   (`detail == null`); `hideShimmer()` (stop + `View.GONE`) dipanggil di
   `finally` `load()` — sukses MAUPUN gagal. Geometri konten TIDAK berubah
   (poster 350dp, overlay kunci Ecchi, grid episode, FAB). State error
   menetap (temuan Minor Cari Bug): bila `detail` gagal dimuat dan belum
   ada data, tampil pesan + tombol "Coba lagi" (memanggil `load()`) —
   bukan cuma toast; bila konten sudah termuat lalu refresh gagal, konten
   tetap tampil + toast saja. Overlay poster
   `@color/black_overlay` (#47000000) dan placeholder `splashh` SENGAJA
   dipertahankan: overlay hanya strip bawah poster (kontras teks judul,
   desain asli) dan placeholder hanya tampil sampai Coil memuat poster asli.
4. **Render Beranda (D2 + D3):** `SPLASH_DELAY_MS` 2.000 ms → **0**
   (penyimpangan fidelitas yang disetujui — REA: Splash → Main delay
   2 detik). Target: Beranda terisi ≤2 detik setelah Main tampil (di luar
   Splash). **Jelajahi TIDAK dibuat malas (D3):** `BrowseFragment.load()`
   memanggil `repo.home()` yang sama — cache + dedup Mutex per kunci
   (`SankaRepository.cached`) membuat Home + Jelajahi hanya menghasilkan
   SATU request jaringan, jadi Jelajahi tidak menambah beban jaringan di
   jalur kritis Beranda; `offscreenPageLimit=1` pun tidak menunda Jelajahi
   (ViewPager2 selalu membuat halaman tetangga); malas yang sesungguhnya
   justru membuat Jelajahi kosong saat pertama dibuka — persis yang
   dilarang D3. `offscreenPageLimit` tetap 10. Warm-up WebView lebih awal
   TIDAK dilakukan: satu-satunya API yang ada (`attachToWindow`) tidak
   membuat WebView bila belum ada, dan membuat WebView tiruan / prefetch
   melanggar larangan (tanpa menyentuh `SankaWebTransport.kt`).
   **Pengukuran (wajib di HP):** log `AnimLoversTiming` (tag logcat) —
   `Splash onCreate` (T+0) → `Splash -> Main` → `Main onCreate` →
   `Home home() mulai` → `Home home() kembali` → `Home applyData selesai
   (render)`; selisih Main onCreate → applyData selesai = waktu render
   Beranda (target ≤2 detik). Throttle 1,4 dtk, timeout 30 dtk, dan
   aturan retry TIDAK berubah.
5. **Tema mengikuti sistem HP (item 5):** `Prefs.isDarkTheme` dibaca dari
   uiMode sistem; `AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM` di
   `AnimeloversApp.onCreate`; switch tema manual di Pengaturan dihapus
   (baris statis "Tema mengikuti sistem HP"). `DarkTheme`/`LightTheme`
   dan `DarkAlert`/`LightAlert` tetap utuh dan dipilih lewat
   `isDarkTheme` yang sama — perbaikan #1–#4 berlaku di kedua tema
   (dialog gelap penuh di sistem gelap, dialog terang di sistem terang;
   splash ikut tema lewat `?attr/bgColor`).
6. **v2.0.6:** (1) player direct-dulu + anti-flip (lihat "Alur data
   utama" §4 dan "Player"); (2) label kualitas `"v720p"` dari tinggi
   video aktual (`onVideoSizeChanged`) + label `"Movie"` untuk film
   (`type == "Movie"`, dibawa lewat `EXTRA_MOVIE`); tanpa hardcode
   `"480p"`; (3) sortir pencarian client-side yang stabil — judul persis
   → TV → Special/Movie (`SankaRepository.sortSearchResults`, dari field
   `type`); (4) poster: `loadPoster` pakai `size()` sesuai ImageView,
   Coil `ImageLoaderFactory` (connect 15 dtk / read 30 dtk / write 15 dtk
   — read 30 dtk sama seperti timeout transport Sanka), drawable error
   `ic_poster_error` BERBEDA dari placeholder chibi `splashh` (loading
   vs gagal bisa dibedakan); (5) buffering ala asli — saat BUFFERING
   overlay next/prev tampil, tombol + label kualitas sembunyi, resolusi
   tidak bisa diketuk; READY membalikkan; (6) resume disimpan saat seek
   (penanda seek programatis supaya tidak rewind berulang −30 dtk) +
   ENDED → episode berikut (film / `teruskan=="false"` / episode terakhir
   → keluar); auto-next di WebView DINONAKTIFKAN (tidak ada event ENDED
   dari embed; polling durasi per-embed rapuh — diputuskan Frontend);
   (7) Beranda render dari cache dulu — `HomeFragment.load()` menampilkan
   `homeCached()` (cache 10 menit) segera, lalu tetap fetch segar dan
   perbarui tampilan; `offscreenPageLimit` tetap 10 (tidak terbukti
   penyebab lag); throttle 1.400 ms + guard R8 TIDAK disentuh.

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
   (360p); (4) WebView embed (fallback terakhir). **v2.0.6 (D4): player
   WAJIB mulai dari direct** — default pref `hdOnly` kini `"1"`;
   `pickStartCandidate` tidak pernah memilih embed bila masih ada
   kandidat direct. Bila satu sumber direct gagal → fallback otomatis
   ke direct berikut (termasuk ekstrak Blogger lazy sekali); **ke embed
   TIDAK otomatis** — bila semua direct habis, tampil dialog tanya
   ("Putar lewat website?") dan hanya ketukan eksplisit yang memindahkan
   player ke website (anti-flip #1b — laporan Faygo 17:27 WIB). Jaringan
   lambat bukan kegagalan: timeout HTTP video 15 dtk (connect) / 30 dtk
   (read) + retry load internal 8× (default Media3: 8/8 dtk, 3×).
   URL ber-token / hasil ekstrak terikat waktu + IP — ambil segar tiap
   kali putar, **JANGAN** disimpan di DB/riwayat.
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
`?attr/textColor`, `?attr/metaTextColor` (gelap `#2b2a2a`, terang
`#ffffff`). v2.0.5 (item 5): tema **mengikuti sistem HP** — HP dark →
app dark, HP terang → app terang. `Prefs.isDarkTheme` dibaca dari uiMode
sistem (`Configuration.UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES`), bukan
lagi pref manual "theme" (pref lama dihapus; nilai tersimpannya diabaikan);
`AnimeloversApp.onCreate` memanggil
`AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_FOLLOW_SYSTEM)`. Tiap
activity memanggil `setTheme` di `onCreate` (`BaseActivity`) sebelum
`super.onCreate`; dialog memakai `DarkAlert` (sistem gelap) /
`LightAlert` (sistem terang) lewat `alertTheme()` yang sama; splash
mengikuti tema lewat `?attr/bgColor`. Saat tema HP berubah, sistem
membuat ulang activity (tanpa `configChanges="uiMode"` di manifest)
sehingga tema baru langsung diterapkan. Switch tema manual di Pengaturan
dihapus (diganti baris statis "Tema mengikuti sistem HP"). Detail memakai
status bar transparan; VideoPlay memakai `FullscreenTheme` dari manifest
(tanpa tema aplikasi).

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
- Kualitas: tombol alih `resolution` — direct 720p ↔ direct 360p **di
  player yang sama** (Media3), BUKAN ganti mesin (Media3 ↔ WebView);
  bila hanya satu tier direct, tampil varian `_off` dan tidak bisa
  diketuk. Bila tier 360p belum ada di daftar, ketuk `resolution` memicu
  fetch Blogger lazy sekali. Pref `hd_only` default `"1"` (v2.0.6 D4:
  direct dulu); `"0"` = utamakan 360p direct. Label `kualitas_video` =
  `"v" + tinggi video AKTUAL + "p"` (mis. `"v720p"`, dari
  `onVideoSizeChanged`, seperti asli) — bukan dari sumber; sebelum ukuran
  diketahui, label sumber dipakai. Label episode = `"Movie"` untuk film
  (`type == "Movie"`); tanpa hardcode `"480p"` di mana pun.
- **Anti-flip (v2.0.6 #1b):** jaringan lambat BUKAN kegagalan — timeout
  HTTP video 15 dtk (connect) / 30 dtk (read) + retry load internal 8×
  (default Media3: 8/8 dtk, 3×) supaya kandidat direct yang sama dicoba
  ulang dulu sebelum dianggap gagal. Bila semua direct habis, fallback ke
  WebView DITANYAKAN lewat dialog (`showWebViewFallbackDialog`,
  sekali per episode) — hanya ketukan "Putar lewat website" yang
  memindahkan player ke website; "Tidak"/batal = toast jujur + keluar
  (seperti asli). Jalur kandidat-berikut WAJIB lewat `playNextOrAsk` —
  tidak ada lagi `playCandidate(nextIndex...)` langsung. Badge `"720p"` di
  bilah WebView tetap ada sebagai jalan pindah WebView → Media3 (ketukan
  pengguna, arah sebaliknya).
- Mode WebView (embed Blogger/Abyssplayer, fallback terakhir): bilah atas
  minimal + kontrol bawaan halaman embed; Berikutnya/Sebelumnya +
  auto-play-next TIDAK berlaku di mode WebView (aplikasi tidak bisa
  mendeteksi video selesai — auto-next DINONAKTIFKAN, bukan dipura-pura).
- Buffering (v2.0.6 #5, ala asli): saat BUFFERING overlay next/prev
  TAMPIL, tombol next/prev PNG + label kualitas DISEMBUNYIKAN, klik ikon
  resolusi DINONAKTIFKAN; READY membalikkan. Hanya mode Media3.
- Resume (v2.0.6 #6, ala asli): disimpan −30 detik saat seek / ganti
  kualitas / keluar; seek programatis (resume saat mulai) ditandai supaya
  tidak rewind berulang. ENDED → episode berikut (bila `teruskan=="true"`
  dan ada episode berikut); film / episode terakhir → keluar.
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
- v2.0.5 (D1): tombol Unduh di kontrol player (Media3) + bilah atas mode
  WebView; unduh tidak lagi menutup player (dulu lewat dialog mode).

## Yang sengaja TIDAK ada (PRD v2 §13)

Iklan, tombol Request, chat, FCM, ADM, cache video, playback speed, PiP,
Chromecast, resolusi granular, Remote Config / checkloc / kill-switch,
sistem subtitle (VTT/translate/tracking), backend lokal.

## Batasan yang diketahui

- Compile lokal TERBLOKIR di sandbox (bukan tanpa SDK): socket loopback
  Java diintersep sandbox — klien daemon Gradle menerima balasan
  `"muse: Other TCP connections is ..."` (byte pertama 'm' = type tag
  109) sehingga daemon tidak pernah bisa dipakai; terbukti 2026-11-18
  lewat uji socket Java vs Python + 4× percobaan daemon bersih. Pemakaian
  API Media3 baru (v2.0.6 #1b) terverifikasi lewat harness kotlinc
  1.9.22 standalone melawan mirror lokal (Media3 1.2.1) — bukti compile
  penuh tetap CI hijau (`android-ci.yml`).
- 1080p streaming TIDAK ADA di sumber ini (0/4 episode contoh) — label
  kualitas aplikasi tidak boleh menulis 1080p.
- Pixeldrain 720p hanya di 3/4 episode contoh — sisanya embed (fallback
  terakhir, v2.0.6: hanya lewat dialog tanya, tidak otomatis).
- File 720p 142–264 MB/episode — di koneksi lambat, frame pertama bisa
  lama; player menampilkan spinner + "Memuat video…" yang jujur.
- Sanka adalah scraper Animekompi tanpa kontrak resmi — bentuk respons
  bisa berubah tanpa pemberitahuan (host stream sudah pindah v10→v11).
  Cadangan bila Sanka mati: Samehadaku (keputusan Faygo D5 — jalur
  berbeda, butuh adapter sendiri, lihat `docs/kontrak-api.md` §5).
- Putar URL hasil ekstrak Blogger + URL stream token di jaringan rumah
  belum teruji dari IP sandbox (terikat IP) — wajib uji rumah.
