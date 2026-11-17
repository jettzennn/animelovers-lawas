'use strict';
// Konfigurasi backend kecil AnimeLovers Revival.
// Semua dari environment (.env), semua punya nilai default sehingga
// server jalan tanpa file .env. Parser .env ditulis sendiri (tanpa
// dependensi dotenv) agar tetap nol-dependensi di Termux.
// Rahasia TIDAK PERNAH ditulis di kode — backend ini tidak butuh kunci.
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');

function loadEnvFile() {
  const file = path.join(ROOT, '.env');
  if (!fs.existsSync(file)) return;
  for (const rawLine of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"'))
      || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    if (!(key in process.env)) process.env[key] = value;
  }
}
loadEnvFile();

function num(name, fallback) {
  const v = Number.parseInt(process.env[name], 10);
  return Number.isFinite(v) && v > 0 ? v : fallback;
}

module.exports = {
  ROOT,
  PORT: num('PORT', 3100),
  HOST: process.env.HOST || '127.0.0.1',
  DATA_DIR: process.env.DATA_DIR || path.join(ROOT, 'data'),
  // Upstream (konstanta, bukan rahasia — alamat publik):
  VIDCLOUD_BASE_URL: 'https://vidcloud.sbs',
  MEGAPLAY_BASE_URL: 'https://megaplay.buzz',
  MEGAPLAY_REFERER: 'https://megaplay.buzz/',
  USER_AGENT: 'AnimLovers/1.0',
  // Email opsional MyMemory (bukan kunci) — lihat .env.example.
  MYMEMORY_EMAIL: process.env.MYMEMORY_EMAIL || '',
  // Terjemahan progresif:
  FIRST_BATCH_CUES: 10, // cue pertama diterjemahkan sinkron (target tampil ≤ 5 detik)
  FIRST_BATCH_TIMEOUT_MS: 12000,
  TRANSLATE_BATCH_CUES: 15, // pola Sanka: batch 15 cue
  TRANSLATE_WORKERS: 4, // pola Sanka: 4 worker paralel
  TRANSLATE_BATCH_GAP_MS: 50,
  TRANSLATE_BATCH_TIMEOUT_MS: 15000,
  // Validasi track "Indonesian" native di /resolve (fail-closed):
  // VTT track yang diklaim Indonesia diunduh + diperiksa isinya;
  // anggaran 5 detik supaya resolve video tidak tertahan lama.
  NATIVE_TRACK_VALIDATE_TIMEOUT_MS: 5000,
  NEGATIVE_CACHE_TTL_MS: 5 * 60 * 1000,
  // Rate limit per IP (per menit):
  RATE_LIMITS: {
    resolve: { max: 60, windowMs: 60 * 1000 },
    subtitle: { max: 10, windowMs: 60 * 1000 },
    proxy: { max: 120, windowMs: 60 * 1000 },
  },
};
