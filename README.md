# AnimLovers

**Aplikasi fan-made yang dibangkitkan lagi** — dibuat ulang oleh penggemar
(Hadi as Faygo) dari AnimeLovers v2.47, aplikasi nonton anime subtitle
Indonesia yang dulu populer dan sudah mati total: server aslinya
(`apps.i59400f.xyz`) kedaluwarsa dan proyek Firebase-nya terhapus, sehingga
aplikasi aslinya mentok di Splash dan tidak bisa jalan lagi. Proyek ini
membangunnya kembali dari hasil bedah APK aslinya — dengan rasa, tampilan,
dan kontrol yang sama, tapi di atas sumber data modern yang masih hidup
(Anikoto + VidCloud/MegaPlay). **Ini bukan aplikasi resmi dan tidak
berafiliasi dengan pengembang asli (Reals Apps).**

Katalog dari Anikoto, video + subtitle Inggris dari VidCloud, subtitle
Indonesia dari backend kecil sendiri (lihat `docs/kontrak-api.md`).

- **Paket:** `animelovers.rebornhd`
- **Nama aplikasi:** AnimLovers (tanpa angka versi di mana pun)
- **Bahasa:** full Bahasa Indonesia
- **Min SDK 21, target SDK 34** — Kotlin, XML Views + ViewBinding, Media3,
  Retrofit + OkHttp, Coil, Room

## Fitur

- Beranda: slider, Episode Baru, Terlaris, Info
- Jelajahi: katalog 9.404 judul, filter genre + pencarian, tampilan grid/list
- Riwayat: lanjutkan tontonan (posisi tersimpan −30 detik), Favorit
- Detail: poster, deskripsi, grid episode (maks. 40 + "Lainnya")
- Player sendiri (Media3 + HLS): subtitle Indonesia/Inggris, SD/HD, FIT/FILL,
  rotate, volume, putar otomatis episode berikutnya, unduh (DownloadManager)
- Gerbang Ecchi: genre Ecchi, Erotica, dan Hentai terkunci sampai login
  (Facebook/Google) dan toggle "Aktifkan Ecchi" di Pengaturan dinyalakan
- Tanpa iklan, tanpa tombol Request, tanpa chat, tanpa FCM

## Build

APK **tidak** bisa di-build di mesin ini tanpa Android SDK — build jalan di
GitHub Actions:

- Setiap push ke `main`: `android-ci.yml` (assembleDebug + unit test).
- Setiap tag `v*`: `android-release.yml` (assembleRelease + verifikasi
  tanda tangan + batas ukuran 10 MB + GitHub Release).

### Keystore rilis (sekali saja)

```bash
keytool -genkeypair -v -keystore keystore.jks -alias animelovers \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 keystore.jks   # simpan hasilnya sebagai secret KEYSTORE_BASE64
```

Isi GitHub Secrets di repo `animelovers-lawas`:

| Secret | Isi |
|---|---|
| `KEYSTORE_BASE64` | Hasil `base64` dari `keystore.jks` |
| `KEYSTORE_PASSWORD` | Password keystore |
| `KEY_ALIAS` | `animelovers` |
| `KEY_PASSWORD` | Password key |

`keystore.jks` dan `keystore.properties` **tidak** di-commit (lihat
`.gitignore`).

### Build lokal (bila Android SDK tersedia)

```bash
./gradlew assembleDebug
```

## Login (Firebase)

Login Facebook/Google memakai Firebase — proyek Firebase **baru** (yang lama
sudah mati). Sampai proyek itu dibuat, tombol Masuk menampilkan pesan
"masuk belum tersedia" dan gerbang Ecchi tetap terkunci dengan aman.
Langkah lengkapnya: `docs/firebase-setup.md`.

## Backend Sub Indo

Backend kecil (Node) menyediakan `GET /resolve`, `GET /subtitle/id`,
`GET /proxy/hls`, `GET /health` — jalan di HP yang sama via Termux di
`http://localhost:3100` (diisi di `util/Constants.kt` sebagai
`BACKEND_BASE_URL`). Bila server tidak jalan, aplikasi memakai jalur
langsung (VidCloud) dan subtitle Inggris sebagai fallback. Kontrak lengkap:
`docs/kontrak-api.md`.

## Struktur

```
app/src/main/java/animelovers/rebornhd/
├── AnimeloversApp.kt          # Application + dependensi
├── data/
│   ├── api/                   # AnikotoApi, VidCloudApi, SubtitleApi
│   ├── auth/                  # AuthRepository + StubAuthRepository
│   ├── local/                 # Room: video + favorite (record.db)
│   ├── model/                 # Anime, Episode, VideoSource
│   └── repo/                  # AnimeRepository, PlayerRepository
├── ui/
│   ├── splash/  main/  detail/  episode/  player/  settings/
│   └── adapter/
└── util/                      # Constants, Prefs, Extensions
docs/
├── arsitektur.md  kontrak-api.md  firebase-setup.md
```

## Disclaimer

Ini aplikasi tidak resmi (fan-made) yang dibuat ulang oleh penggemar. Semua
merek dagang dan hak cipta adalah milik pemiliknya masing-masing. Aplikasi
ini tidak bertanggung jawab atas konten yang dihosting pihak ketiga dan
tidak terlibat dalam pengunggahan atau pengunduhan — hanya menampilkan
tautan yang tersedia di internet. Bila ada konten yang menurutmu melanggar
hak cipta dan kamu pemegang hak ciptanya, laporkan melalui repo GitHub
`animelovers-lawas` dan konten tersebut akan segera dihapus.
