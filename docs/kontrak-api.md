# Kontrak API — AnimLovers

## 1. Anikoto (katalog, langsung dari aplikasi)

Base: `https://anikotoapi.site/` — publik, tanpa key.
Timeout 8 detik connect/read. Tanpa logging interceptor (URL upstream tidak
boleh bocor ke log).

### `GET /recent-anime?page={n}`

- 200, 20 item/halaman, total **9.404 judul** (471 halaman).
- Item: `id, title, jp_title, poster, type, score, status, year, season,
  episodes` (jumlah episode sebagai **string**, mis. `"12"`),
  `terms_by_type.genre[]` (genre **tidak** ada di top-level).
- `pagination: {current_page, total_pages, total_items}`.

### `GET /series/{id}`

- 200. `data.anime`: `id, title, jp_title, poster, description, status,
  score, type, year, season, mal_id, ani_id` — `terms_by_type` bisa
  **null** di sini (genre absen → tampil tanpa genre, jangan crash).
- `data.episodes[]`: tiap episode persis 7 key —
  `id, title, jp_title, number, episode_embed_id, embed_url{sub,dub},
  updated_at`.
- `ani_id` dipakai untuk VidCloud; bila tidak ada, pakai `mal_id`.

## 2. VidCloud (video + subtitle Inggris, langsung dari aplikasi)

Base: `https://vidcloud.sbs/`

### `GET /embed-2/v3/e-1/getSources?id={ani_id}-{ep}-{server}-sub`

- 200, tanpa key/Referer, CORS `*`. `server` = 1, 2, atau 3.
- Respons: `{"encrypted":true,"data":"<base64url>"}`.
- Decode: base64url (tambah padding `=` bila hilang) → tiap byte XOR
  `(0x4D + (i % 23)) & 0xff` → JSON:
  ```json
  {
    "sources": [{"file": "…/master.m3u8?t=…", "type": "hls",
                 "label": "Sora", "default": true}],
    "tracks": [{"label": "English", "file": "…/subs.vtt?t=…",
                "default": true}],
    "intro": {"start": 0, "end": 0},
    "outro": {"start": 0, "end": 0},
    "server": 1
  }
  ```
- Aturan: ambil subtitle dari **server 1**; bila server 1 tidak ada,
  fallback ke MegaPlay, **bukan** server 2 (VTT server 2 → 403 dari IP
  sandbox; video-nya tetap 200).
- Sebagian judul (simulcast 2026) punya track berlabel **"Indonesian"**
  (server 2 "Zexy") — backend wajib cek track ini dulu sebelum translate.
- URL ber-token (`?t=`) **TIDAK BOLEH** disimpan di DB (TTL tidak terukur)
  — ambil segar per request.

## 3. Backend kecil (Node, dibangun Backend) — PRD §3.6

Base URL: `http://localhost:3100` — server Node jalan di HP yang sama via
Termux (lihat `server/README.md`). Bila server tidak jalan, aplikasi
memakai jalur langsung (VidCloud) + subtitle Inggris.

### `GET /resolve/{ani_id}/{ep}` → 200

```json
{
  "hls": "https://…/master.m3u8",
  "tracks": [{"lang": "id", "url": "https://…/id.vtt"}],
  "intro": {"start": 0, "end": 0},
  "outro": {"start": 0, "end": 0}
}
```

### `GET /subtitle/id/{ani_id}/{ep}` → `text/vtt`

File VTT Indonesia (native bila ada, hasil translate + cache bila tidak).
Header `X-Subtitle-Cache: hit | miss | native`.

### `GET /proxy/hls?u=<url>` → isi HLS (opsional)

### `GET /health` → `{"ok": true}`

Dipanggil diam-diam dari Splash; gagal = lanjut saja (bukan error).

## 4. Aturan subtitle (PRD §3.5)

1. Track "Indonesian" native dari getSources → pakai langsung.
2. Bila tidak ada → backend `/subtitle/id` (translate + cache).
3. Bila backend tidak ada/tidak terjangkau → VTT Inggris langsung.
4. **Jangan pernah** menampilkan track berlabel Indonesia yang isinya
   Inggris.
5. Bila subtitle Indonesia belum tersedia saat putar, tampilkan toast
   "Subtitle Indonesia belum tersedia — menampilkan Inggris".
