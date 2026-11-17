'use strict';
// MegaPlay — sumber video + subtitle CADANGAN (bila VidCloud gagal).
// Kontrak terverifikasi live (Riset T1, 2026-11-17):
//  1. Halaman embed https://megaplay.buzz/stream/s-2/{embed_id}/sub
//     dengan header Referer: https://megaplay.buzz/ PERSIS (origin +
//     trailing slash) → HTML berisi data-id="...".
//     Tanpa Referer, halaman error tanpa data-id.
//  2. GET /stream/getSources?id={data-id}&platform=OTHER&st=other
//     dengan Referer halaman embed → JSON {tracks, t, intro, outro,
//     server, image, enc}.
//  3. `enc` = AES-256-CBC, kunci + IV hardcode di e1-player.min.js
//     publik (BUKAN rahasia): kunci "i?LMTAx0Q6,:}50U" dipad nol ke
//     32 byte, IV "W0;27ToaUpl_P%'c" (16 byte) → {"file": "<master.m3u8>"}.
//     Kunci BISA dirotasi MegaPlay kapan saja — bila dekripsi gagal,
//     pemanggil WAJIB log jelas dan fallback ke VidCloud.
//  4. VTT Inggris di tracks (host mysubsplay.com) + master.m3u8
//     diunduh dengan Referer origin https://megaplay.buzz/.
//  5. data-id BERUBAH tiap request halaman embed — ambil segar.
const crypto = require('node:crypto');
const config = require('../config');

// Kunci + IV hardcode publik dari e1-player.min.js MegaPlay.
// Bukan rahasia, bukan kredensial — tertanam di file JS publik mereka.
const ENC_KEY = 'i?LMTAx0Q6,:}50U';
const ENC_IV = "W0;27ToaUpl_P%'c";

class MegaPlayError extends Error {
  constructor(status, message) {
    super(message);
    this.name = 'MegaPlayError';
    this.status = status;
  }
}

function padKeyBytes(str, len) {
  const buf = Buffer.alloc(len);
  Buffer.from(str, 'utf8').copy(buf);
  return buf;
}

function decryptEnc(enc) {
  let b64 = String(enc).replace(/-/g, '+').replace(/_/g, '/');
  b64 += '='.repeat((4 - (b64.length % 4)) % 4);
  const buf = Buffer.from(b64, 'base64');
  const decipher = crypto.createDecipheriv(
    'aes-256-cbc', padKeyBytes(ENC_KEY, 32), Buffer.from(ENC_IV, 'utf8'),
  );
  const plain = Buffer.concat([decipher.update(buf), decipher.final()]);
  return JSON.parse(plain.toString('utf8')); // { file: "<master.m3u8>" }
}

function encryptEncForTest(obj) {
  // Hanya untuk unit test (membuktikan dekripsi bekerja dua arah).
  const cipher = crypto.createCipheriv(
    'aes-256-cbc', padKeyBytes(ENC_KEY, 32), Buffer.from(ENC_IV, 'utf8'),
  );
  const enc = Buffer.concat([cipher.update(JSON.stringify(obj), 'utf8'), cipher.final()]);
  return enc.toString('base64');
}

async function fetchWithTimeout(url, options, timeoutMs, fetchImpl) {
  const impl = fetchImpl || fetch;
  return impl(url, { ...options, signal: AbortSignal.timeout(timeoutMs) });
}

function embedUrl(embedId) {
  return `${config.MEGAPLAY_BASE_URL}/stream/s-2/${encodeURIComponent(embedId)}/sub`;
}

// Ambil data-id segar dari halaman embed. Referer PERSIS wajib.
async function getEmbedDataId(embedId, fetchImpl) {
  const url = embedUrl(embedId);
  let res;
  try {
    res = await fetchWithTimeout(url, {
      headers: {
        'User-Agent': config.USER_AGENT,
        Referer: config.MEGAPLAY_REFERER,
      },
    }, 10000, fetchImpl);
  } catch (e) {
    throw new MegaPlayError(0, `Halaman embed tidak terjangkau (${e.message}).`);
  }
  if (!res.ok) {
    throw new MegaPlayError(res.status, `Halaman embed HTTP ${res.status}.`);
  }
  const html = await res.text();
  const m = html.match(/data-id="(\d+)"/);
  if (!m) {
    throw new MegaPlayError(502, 'Halaman embed tidak berisi data-id (mungkin Referer ditolak).');
  }
  return m[1];
}

async function getSources(dataId, embedId, fetchImpl) {
  const url = `${config.MEGAPLAY_BASE_URL}/stream/getSources?id=${encodeURIComponent(dataId)}&platform=OTHER&st=other`;
  let res;
  try {
    res = await fetchWithTimeout(url, {
      headers: {
        'User-Agent': config.USER_AGENT,
        Referer: embedUrl(embedId),
      },
    }, 10000, fetchImpl);
  } catch (e) {
    throw new MegaPlayError(0, `getSources MegaPlay tidak terjangkau (${e.message}).`);
  }
  if (!res.ok) {
    throw new MegaPlayError(res.status, `getSources MegaPlay HTTP ${res.status}.`);
  }
  let json;
  try {
    json = await res.json();
  } catch (e) {
    throw new MegaPlayError(502, 'getSources MegaPlay: jawaban bukan JSON.');
  }
  if (!json || typeof json.enc !== 'string') {
    throw new MegaPlayError(502, 'getSources MegaPlay: tidak ada field enc.');
  }
  return json;
}

// Ambil isi teks (VTT / m3u8) dengan Referer origin MegaPlay.
async function fetchText(url, fetchImpl) {
  let res;
  try {
    res = await fetchWithTimeout(url, {
      headers: {
        'User-Agent': config.USER_AGENT,
        Referer: config.MEGAPLAY_REFERER,
      },
    }, 15000, fetchImpl);
  } catch (e) {
    throw new MegaPlayError(0, `Unduh MegaPlay gagal (${e.message}).`);
  }
  if (!res.ok) {
    throw new MegaPlayError(res.status, `Unduh MegaPlay HTTP ${res.status}.`);
  }
  return res.text();
}

module.exports = {
  MegaPlayError, decryptEnc, encryptEncForTest,
  getEmbedDataId, getSources, fetchText, embedUrl,
  ENC_KEY, ENC_IV,
};
