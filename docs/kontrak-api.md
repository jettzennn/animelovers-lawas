# Kontrak API — AnimLovers v2 (Sanka, hardsub)

Satu-satunya API v2: **Sanka Vollerei** — scraper situs anime Indonesia
(Animekompi). Tanpa key, gratis. Tidak ada lagi Anikoto, VidCloud,
MegaPlay, atau backend lokal. Subtitle **menempel di video (hardsub Sub
Indo)** — tidak ada sistem subtitle sama sekali (tanpa VTT, tanpa
translate, tanpa tracking).

Base: `https://www.sankavollerei.web.id`
Timeout: connect 15 dtk / read 30 dtk. Tanpa logging interceptor (URL
upstream tidak boleh bocor ke log).

## 1. Rate limit — BACA DULU (mengikat semua endpoint)

- **45 request/menit** (angka ketat; FAQ beranda menyebut 100 — pakai 45).
  **3 pelanggaran → BAN PERMANEN** (IP diblokir selamanya).
- Aplikasi WAJIB: throttle ≥ 1,4 detik antar request, cache agresif,
  dedup + batalkan request yang ditinggalkan, berhenti total bila 429
  (jangan retry membabi buta).
- User-Agent: UA OkHttp default atau UA browser (terbukti 200). JANGAN
  UA curl polos (403) dan jangan UA kosong.
- Scraper kadang timeout (500 "timeout of 20000ms exceeded" teramati) —
  aplikasi harus tahan 500 + backoff jujur, bukan crash.

## 2. Katalog

### `GET /anime/animekompi/home` → 200

- 273 KB. Memberi `featured` (→ slider Beranda), `latest` (→ Episode
  Baru), `popular` (→ Terlaris), `trending`.
- Bentuk field `popular` belum dipastikan (ada angka tonton atau tidak) —
  aplikasi menampilkan badge jumlah tonton HANYA bila Sanka memberinya;
  bila tidak, badge disembunyikan (jangan angka palsu).

### `GET /anime/animekompi/terbaru` (atau `latest`) → 200

- Mengembalikan item **EPISODE**, bukan serial — cocok untuk seksi
  "Episode Baru" (Beranda + Lainnya).

### `GET /anime/animekompi/search?q=<kata>` → 200

- Pencarian **server-side** (lebih baik dari v1 yang filter lokal).
  Contoh: `q=naruto` → 16 hasil; stabil saat diulang. Item punya `slug`
  + `id` yang sama dengan detail.

### `GET /anime/animekompi/genre/:slug` → 200

- Contoh: `genre/action` → 24 judul. Spinner genre diisi dari daftar
  genre Sanka.

### Endpoint katalog lain (tercantum di dok, BELUM dites)

`batch`, `movie`, `donghua`, `schedule`, `list`, `az`, `filter`,
`season/:slug`, `studio/:slug`, `status/:slug`, `type/:slug`,
`order/:slug` — hanya dipakai bila layar butuh, dan harus dites dulu.

## 3. Detail + episode

### `GET /anime/animekompi/detail/:slug` → 200

- Info + sinopsis + metadata + daftar episode (`num/title/date/slug/url`).
- Contoh: Ao no Hako S2 → 6 episode (ongoing, sesuai simulcast).
- Slug + id SAMA antara search dan detail (terverifikasi:
  `boruto-naruto-the-movie-sub-indo`, id 8658).
- Beberapa field bisa null (`alt_title`, `score`) — model harus tahan
  null. **JANGAN** memanggil detail dengan slug episode
  (`/detail/<slug-episode>` → 404/500); detail hanya untuk slug serial.

### `GET /anime/animekompi/episode/:slug` → 200 (TERKONFIRMASI T1b)

- Contoh teruji: Ao no Hako S2 ep 1 (5.917 byte), ep 2 (6.145 byte).
- Respons: `id/title/episode/slug/detail_slug/anime_slug/anime_title/
  prev_episode/next_episode/default_iframe/mirrors/sources/downloads/
  anime/episode_list`.
- **Slug WAJIB dari respons detail — jangan dirangkai sendiri.** Contoh:
  ep 4 Ao no Hako S2 slug-nya `ao-no-hako-season-2-episode-4` TANPA
  akhiran `-subtitle-indonesia` (ep 1/2/3/5/6 pakai akhiran itu).

## 4. Sumber video per episode

### Struktur `sources[]` / `mirrors[]` / `downloads[]`

- `sources[]`: tiap item `label/kind/quality/url`. Field `quality`
  sering null — kualitas dibaca dari LABEL ("Pixel HD", "Server GDrive")
  atau dari `kind: direct`, bukan dari field `quality` semata.
- `mirrors[]`: entri ke-3 type `mp4` (tidak ada di `sources[]`) —
  URL stream token Animekompi
  (`https://v10.animekompi.sbs/api/episodes/stream/<token>`, redirect
  301 → v11), teruji 206 Partial Content, `video/mp4` (264 MB — ukuran
  file 720p yang sama dari Pixeldrain).
- `downloads[]`: 2 grup (MP4 480p + 720p) × 5 host
  (acefile/gofile/buzzheavier/pixeldrain/mirrored) — **SEMUA halaman
  unduhan, bukan file langsung** (Pixeldrain di sini berupa halaman LIST
  `pixeldrain.com/u/…`, bukan `/api/file/`). **JANGAN** dipakai untuk
  streaming maupun unduh.

### Prioritas putar (revisi T1b atas PRD §3.3)

| Prioritas | Sumber | Kualitas | Jenis | Bukti |
|---|---|---|---|---|
| 1 | Pixeldrain "Pixel HD" (`kind: direct`) | 720p | File MP4 langsung (`pixeldrain.com/api/file/<id>`) | HEAD + `Range` → 206, `video/mp4`, `Accept-Ranges: bytes`; 142–264 MB/episode; terbukti 3/4 episode (75%) |
| 2 | Stream token Animekompi (`mirrors[]` type `mp4`) | 720p | File MP4 langsung | 206 terbukti (264 MB) |
| 3 | Blogger hasil ekstrak (itag 22 → 18) | 720p → 360p | File MP4 langsung (hasil ekstrak) | Ekstrak stabil 2/2 episode; putar langsung dari IP sandbox BELUM terbukti (terikat IP) — wajib uji rumah |
| 4 | WebView embed (Blogger/Abyssplayer/dll.) | 480p (tipikal) | Embed (halaman player) | Fallback terakhir |

### Ekstraksi embed Blogger (T1b, terbukti stabil 2/2)

- Halaman `default_iframe` (`blogger.com/video.g?token=…`) → 200,
  ~27,7 KB. Di dalam HTML-nya ada URL video langsung Google:
  **itag 18** = 360p MP4, **itag 22** = **720p MP4**, keduanya `dur` =
  panjang episode penuh.
- Cara ekstrak: regex
  `https://<host>---sn-<…>.googlevideo.com/videoplayback…`, potong di
  `\&quot;`, lalu decode escape JSON `\uXXXX` (`\u003d` → `=`,
  `\u0026` → `&`). Pilih itag tertinggi (22 bila ada, fallback 18).
- Koreksi atas riset utama: label "Blogger = 480p" hanya berlaku untuk
  tampilan default embed — file di baliknya ada 720p (itag 22).
- **URL hasil ekstrak terikat waktu + IP** (`expire` + `sig` + `ip=`) —
  ambil segar tiap kali putar, **JANGAN** disimpan di DB/riwayat.
  Aturan yang sama berlaku untuk URL Pixeldrain dan stream token:
  URL ber-token **TIDAK BOLEH** disimpan (TTL tidak terukur) — ambil
  segar per request.

### Unduh

- Unduh memakai **URL Pixeldrain direct dari `sources[]`** (sama dengan
  sumber streaming) — bukan entri `downloads[]`.
- Bila episode tanpa Pixeldrain: tidak ada unduhan video (hanya embed).

## 5. Cadangan bila Sanka mati (keputusan Faygo D5: Samehadaku)

Sumber Sanka lain, **jalur BERBEDA** dari Animekompi (butuh adapter
sendiri, bukan drop-in):

- `GET /anime/samehadaku/home` → 200 (29.555 byte); struktur beda:
  `recent/batch/movie/top10` (tiap seksi dict
  `href/samehadakuUrl/animeList`).
- `GET /anime/samehadaku/search?q=<kata>` → 200 (contoh `psyren` →
  7 hasil); item berupa EPISODE
  (`href: /samehadaku/anime/psyren-episode-6`).
- `GET /anime/samehadaku/anime/:slug` → 200 (Psyren: 6 episode,
  judul/sinopsis/genre). **PERHATIAN: jalur detail Samehadaku adalah
  `/anime/…`, BUKAN `/detail/…`** (tebakan `/detail/psyren` → 403
  "Mungkin Kamu Salah Endpoint").
- Episode Samehadaku: `href: /samehadaku/episode/<slug>`. Endpoint
  sumber video Samehadaku TIDAK dites (di luar ruang lingkup T1b —
  cukup bukti cadangan ada).

## 6. Aturan bentuk data (pelajaran dari v1 — jangan ulangi)

1. SEMUA field Sanka yang dipakai aplikasi harus toleran tipe/null
   (string ATAU number, null ATAU hilang), mengikuti pola lenient yang
   sudah dibuktikan di `Anime.kt` v1.6.6.
2. Slug adalah kunci utama (bukan id numerik).
3. Jangan pernah mengklaim 1080p — label kualitas harus sesuai sumber
   yang benar-benar diputar (720p / 480p).
4. Jangan pernah menyimpan URL ber-token di DB, log, atau riwayat.
