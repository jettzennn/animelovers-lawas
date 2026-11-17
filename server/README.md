# Backend Kecil — AnimeLovers Revival (`animelovers-lawas-server`)

Backend kecil (Node, **nol dependensi** — hanya `node:http` + `node:sqlite`)
untuk aplikasi Android AnimeLovers Revival. Tugasnya tiga hal:

1. **Resolve video** — VidCloud (utama) / MegaPlay (cadangan).
2. **Subtitle Indonesia** — native bila ada, bila tidak terjemahan
   progresif + cache SQLite (pola Server HD Mitsunime v1.5.0).
3. **Proxy HLS/VTT** — bila pemutaran langsung bermasalah, dan supaya
   URL upstream tidak sampai ke aplikasi.

Semua pesan error berbahasa Indonesia. Semua URL upstream ber-token
**tidak pernah** dicatat di log dan **tidak pernah** disimpan di DB.

## Menjalankan

```bash
cd server
node server.js
# → http://127.0.0.1:3100
```

### Di Termux (HP Faygo)

```bash
cd ~/animelovers-lawas/server
node server.js
```

Server jalan di `http://localhost:3100` — aplikasi Android di HP yang
sama memakai alamat itu sebagai `BASE_URL` backend. Bila aplikasi di
HP lain perlu mengakses server ini lewat WiFi, jalankan dengan
`HOST=0.0.0.0 PORT=3100 node server.js` (atau isi `.env`).

### Environment (semua opsional — ada default)

| Variabel | Default | Keterangan |
|---|---|---|
| `PORT` | `3100` | Port listen |
| `HOST` | `127.0.0.1` | Alamat listen (`0.0.0.0` untuk akses lewat WiFi) |
| `DATA_DIR` | `server/data/` | Folder SQLite cache subtitle |
| `MYMEMORY_EMAIL` | _(kosong)_ | Email opsional (bukan kunci) — menaikkan kuota gratis MyMemory 5.000 → 50.000 karakter/hari |

Salin `.env.example` ke `.env` bila perlu mengubah nilai di atas.
**Jangan menulis rahasia apa pun di `.env`** — backend ini tidak
butuh kunci.

### Menghentikan server

```bash
# Cari PID persisnya (jangan pkill pola lebar — bisa membunuh server lain):
ss -tlnp | grep 3100
kill -TERM <PID>
```

Server memakai graceful shutdown (SIGTERM/SIGINT). Bila keep-alive
menggantung, pakai `kill -KILL <PID>` pada PID yang sama.

## API

Semua respons sukses: `{ "ok": true, "data": ... }`.
Semua error: `{ "ok": false, "error": "<pesan Bahasa Indonesia>" }`
(+ header `Retry-After` bila 429).

### `GET /health`

Cek hidup (untuk Splash + diagnosa). Tidak pernah di-rate-limit.

```json
{ "ok": true, "service": "animelovers-lawas-server", "uptime_s": 123 }
```

### `GET /resolve/{ani_id}/{ep}`

Ambil sumber video + subtitle Inggris. Query opsional:
`?mega_embed_id={episode_embed_id}` (dari Anikoto `/series/{id}`,
dipakai untuk fallback MegaPlay).

Alur: VidCloud server 1 → 2 → 3 (video) → MegaPlay (bila
`mega_embed_id` diberikan) → 502 bila semua gagal.

```json
{
  "ok": true,
  "data": {
    "ani_id": 235,
    "episode": 1,
    "source": "vidcloud",
    "server": 1,
    "server_label": "Sora",
    "hls": "https://vidcloud.sbs/stream/manifest.m3u8?t=...",
    "hls_proxy": "/proxy/hls?u=...",
    "tracks": [
      { "lang": "en", "label": "English", "url": "https://...", "proxy_url": "/proxy/hls?u=..." },
      { "lang": "pt", "label": "Portuguese", "url": "https://...", "proxy_url": "/proxy/hls?u=..." }
    ],
    "has_indonesian_track": false,
    "intro": { "start": 0, "end": 0 },
    "outro": { "start": 1313, "end": 1403 }
  }
}
```

`hls` / `tracks[].url` adalah URL upstream langsung (kontrak PRD);
`hls_proxy` / `proxy_url` adalah padanannya lewat proxy ini —
Frontend boleh memilih salah satu.

### `GET /subtitle/id/{ani_id}/{ep}`

VTT Indonesia. Query opsional: `?mega_embed_id={id}` (fallback
MegaPlay bila VidCloud server 1 tidak punya track Inggris).

Header respons:

| Header | Arti |
|---|---|
| `X-Subtitle-Cache` | `hit` (dari cache) / `miss` (baru dibuat) / `native` (track Indonesia asli VidCloud) |
| `X-Subtitle-Status` | `complete` (semua cue) / `partial` (sebagian — minta ulang) |
| `X-Subtitle-Source` | `native` / `translated` |
| `X-Subtitle-Translated-Cues` / `X-Subtitle-Total-Cues` | Kemajuan, mis. `10` / `350` |

Alur:

1. **Cache SQLite dulu** — lengkap → instan; sebagian → kembalikan
   yang sudah diterjemahkan + lanjutkan terjemahan di background.
2. **Cek track "Indonesian" VidCloud server 1 dulu** — bila ada dan
   isinya benar-benar Indonesia (validasi heuristik), pakai langsung.
3. **Bila tidak ada** — ambil VTT Inggris server 1, terjemahkan
   **10 cue pertama sinkron** (target tampil ≤ 5 detik), sisanya di
   background mendahului playhead (batch 15 cue, 4 worker paralel).
4. **Bila server 1 tidak ada** — fallback ke MegaPlay (butuh
   `mega_embed_id`), **BUKAN server 2 VidCloud** (VTT server 2 403
   dari IP datacenter — terverifikasi live 2026-11-17).
5. VTT sebagian **hanya berisi cue yang sudah diterjemahkan** — tidak
   pernah ada track berlabel Indonesia yang isinya Inggris.
6. Bila semua layanan terjemah gagal → **502**
   `"Subtitle Indonesia belum bisa dibuat. Coba lagi sebentar lagi."`
   Frontend lalu fallback ke VTT Inggris langsung dari VidCloud.

Frontend **meminta ulang** bila `Status: partial` (tiap ~15 detik
atau saat playhead mendekati cue terakhir yang diterjemahkan).

Terjemahan: **gratis, tanpa API key** — 10 endpoint Google Translate
round-robin (pola Sanka; gtx sering 429 dari IP datacenter, endpoint
lain masih 200) + fallback MyMemory per item. **Jangan pernah**
memakai apikey `vttnime` Sanka.

### `GET /proxy/hls?u=<url>`

Proxy HLS (`.m3u8`), segmen (`.ts`), dan VTT. Playlist `.m3u8`
ditulis ulang supaya semua URI-nya kembali lewat proxy ini.

Keamanan (SSRF): hanya host yang diizinkan —
`vidcloud.sbs`, `*.mysubsplay.com`, `fetch.nexabloom.top`,
`*.nexabloom.top`; hanya `https`; hostname yang resolve ke IP
privat/loopback ditolak. Referer MegaPlay
(`https://megaplay.buzz/`) disetel otomatis untuk host MegaPlay.

## Rate limiting (per IP)

| Endpoint | Batas |
|---|---|
| `/resolve` | 60/menit |
| `/subtitle/id` | 10/menit |
| `/proxy/hls` | 120/menit |
| `/health` | tanpa batas, tidak pernah diblokir |

Pelanggaran dicatat di SQLite (`ip_blocks`) dan blokir naik
bertahap: 5 pelanggaran → 5 menit, 10 → 1 jam, 20 → 24 jam.
Blokir kedaluwarsa sendiri. Jawaban penolakan: **429** +
`"Terlalu banyak permintaan, coba lagi nanti."` + header
`Retry-After`. X-Forwarded-For **tidak** dipercaya.

## Struktur

```
server/
├── package.json          # nol dependensi
├── server.js             # entrypoint (port dari env, default 3100)
├── .env.example
├── src/
│   ├── config.js         # env + default
│   ├── logger.js         # log (tanpa token/URL upstream)
│   ├── db.js             # SQLite: subtitle_cache + ip_blocks
│   ├── ratelimit.js      # per-IP limit + blokir bertahap
│   ├── lib/
│   │   ├── vidcloud.js   # getSources + decode XOR (0x4D + (i % 23))
│   │   ├── megaplay.js   # embed + getSources + dekripsi AES enc
│   │   ├── vtt.js        # parse + render WebVTT
│   │   └── translate.js  # Google 10 endpoint + MyMemory, batch 15x4
│   └── routes/
│       ├── health.js
│       ├── resolve.js
│       ├── subtitle.js   # cache + progresif + background
│       ├── proxy.js      # proxy + tulis ulang .m3u8 + anti-SSRF
│       └── common.js     # format respons/error
├── tests/
│   └── run-tests.test.js # unit test (node:test) — npm test
└── data/
    └── subtitle-cache.db # SQLite (gitignored — jangan masuk repo)
```

## Pengujian

### Unit test (19 test, lolos)

```bash
npm test
```

Mencakup: decode XOR VidCloud, dekripsi AES MegaPlay (round-trip),
parse/render VTT, parse jawaban Google (2 bentuk), pemecah penanda
`«n»`, pelindung tag VTT, heuristik bahasa, allowlist + tulis ulang
proxy, validasi param, simpan/baca SQLite.

### Uji live (2026-11-17, dari IP sandbox VM — server port 3100)

| Uji | Hasil |
|---|---|
| `GET /health` | 200 `{ok:true}` (~4 ms) |
| `GET /resolve/235/1` | 200, VidCloud server 1 "Sora", hls + track EN/PT/ES (~1,2 dtk) |
| `GET /resolve/160803/1` | 200, server 1, `tracks: []` (judul ini tidak punya track di server 1) |
| `GET /resolve/abc/1` | 400 `"ID anime atau nomor episode tidak valid."` |
| `GET /subtitle/id/235/1` (pertama) | 200, `X-Subtitle-Status: partial`, 10/350 cue, **3,9 dtk** (target ≤ 5 dtk ✓), VTT Indonesia asli (lirik OP Conan diterjemahkan, tag `<i>` utuh) |
| `GET /subtitle/id/235/1` (poll ~25 dtk kemudian) | 200, `Status: complete`, **350/350 cue** — background selesai |
| `GET /subtitle/id/160803/1` (tanpa `mega_embed_id`) | 502 jujur (server 1 tanpa track, MegaPlay tanpa ID) |
| `GET /subtitle/id/160803/1?mega_embed_id=452819` | 502 jujur — MegaPlay `getSources` judul ini hanya berisi track `thumbnails`, tanpa track subtitle |
| `GET /proxy/hls?u=<m3u8 VidCloud>` | 200, playlist ditulis ulang (semua varian → `/proxy/hls?u=...`) |
| `GET /proxy/hls?u=https://evil.com/x.m3u8` | 403 `"Host tidak diizinkan."` |
| `GET /proxy/hls` (tanpa `u`) | 400 `"Parameter u (URL) wajib diisi."` |
| Rate limit: 16× `/subtitle/id/235/1` cepat | 10× 200 lalu 6× 429 + `Retry-After: 300` (blokir 5 menit setelah 5 pelanggaran); `/health` tetap 200 saat IP diblokir |
| VidCloud server 2 VTT (cek langsung) | 403 `"Subtitle error: 403"` (EN + ID) — aturan "jangan pakai server 2 untuk subtitle" terkonfirmasi |

### Yang BELUM terverifikasi (butuh uji rumah / HP Faygo)

- **Terjemahan dari koneksi rumah (Termux):** gtx dari IP sandbox
  429 (blokir Google), tapi endpoint alternatif (clients1,
  translate.google.co.id) masih 200 dari sandbox. Dari IP rumah
  (Axis, residensial) peluang gtx normal jauh lebih besar — wajib
  uji `GET /subtitle/id/{ani_id}/{ep}` dari Termux.
- **VTT server 2 dari koneksi rumah:** bila dari rumah 200, maka
  403 ini hanya blokir IP datacenter — aturan "prefer server 1"
  tetap, tapi fallback server 2 untuk judul yang subtitlenya hanya
  ada di server 2 (mis. 160803) jadi sah. Bila dari rumah juga 403,
  server 2 resmi tidak bisa dipakai untuk subtitle.
- **Isi track "Indonesian" VidCloud:** labelnya ada di sebagian
  judul (server 2), tapi isinya belum bisa dibaca (403) — jangan
  jadikan andalan sebelum ada yang membaca isinya dari rumah.
- **Pemutaran nyata** (hls.js/Media3 sampai selesai) — tugas
  Frontend/Cari Bug, bukan backend ini.
- **TTL token `?t=` VidCloud** belum diukur — backend menganggapnya
  pendek dan selalu ambil segar per request.

## Catatan untuk Frontend

- `BASE_URL` backend: `http://localhost:3100` (Termux) — bisa
  diubah di konfigurasi aplikasi.
- Urutan subtitle di player: VTT Indonesia dari backend ini →
  VTT Inggris langsung dari VidCloud (`tracks[].url` di `/resolve`)
  → tanpa subtitle. Jangan pernah menampilkan track berlabel
  Indonesia yang isinya Inggris.
- Bila `/subtitle/id` mengembalikan `partial`, minta ulang tiap
  ~15 detik sampai `complete`.
- Bila `/resolve` mengembalikan `has_indonesian_track: true`,
  subtitle native tersedia — tapi tetap minta lewat `/subtitle/id`
  (backend yang memeriksa + memvalidasi isinya).
- Untuk MegaPlay fallback, Frontend harus mengirim
  `mega_embed_id` (dari Anikoto `episodes[].episode_embed_id`).
