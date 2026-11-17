'use strict';
// VidCloud — sumber video + subtitle utama.
// Kontrak terverifikasi live (Riset T1, 2026-11-17):
//   GET /embed-2/v3/e-1/getSources?id={ani_id}-{ep}-{server}-sub
//   → 200, {"encrypted":true,"data":"<base64url>"}, tanpa key/Referer, CORS *.
//   Dekode: base64url → tiap byte XOR (0x4D + (i % 23)) & 0xff → JSON.
//   Hasil: { sources:[{file: master.m3u8?t=..., label, default}],
//            tracks:[{file: subs.vtt?t=..., label, kind, default}],
//            intro, outro, server }.
// URL hasil decode membawa token ?t= — JANGAN PERNAH disimpan di DB
// (TTL belum diukur); ambil segar per request.
const config = require('../config');
const logger = require('../logger');

const XOR_BASE = 0x4d;
const XOR_MOD = 23;

class VidCloudError extends Error {
  constructor(status, message) {
    super(message);
    this.name = 'VidCloudError';
    this.status = status;
  }
}

function decodePayload(data) {
  // base64url -> base64 standar
  let b64 = String(data).replace(/-/g, '+').replace(/_/g, '/');
  b64 += '='.repeat((4 - (b64.length % 4)) % 4);
  const buf = Buffer.from(b64, 'base64');
  const out = Buffer.alloc(buf.length);
  for (let i = 0; i < buf.length; i++) {
    out[i] = buf[i] ^ ((XOR_BASE + (i % XOR_MOD)) & 0xff);
  }
  return JSON.parse(out.toString('utf8'));
}

async function fetchWithTimeout(url, options, timeoutMs, fetchImpl) {
  const impl = fetchImpl || fetch;
  const res = await impl(url, {
    ...options,
    signal: AbortSignal.timeout(timeoutMs),
  });
  return res;
}

async function getSources(aniId, ep, server, fetchImpl) {
  const srv = server || 1;
  const url = `${config.VIDCLOUD_BASE_URL}/embed-2/v3/e-1/getSources?id=${aniId}-${ep}-${srv}-sub`;
  let res;
  try {
    res = await fetchWithTimeout(url, {
      headers: { 'User-Agent': config.USER_AGENT, Accept: 'application/json' },
    }, 10000, fetchImpl);
  } catch (e) {
    throw new VidCloudError(0, `getSources server ${srv} tidak terjangkau (${e.message}).`);
  }
  if (!res.ok) {
    throw new VidCloudError(res.status, `getSources server ${srv} HTTP ${res.status}.`);
  }
  let json;
  try {
    json = await res.json();
  } catch (e) {
    throw new VidCloudError(502, `getSources server ${srv}: jawaban bukan JSON.`);
  }
  let payload;
  try {
    payload = json && json.encrypted ? decodePayload(json.data) : json;
  } catch (e) {
    throw new VidCloudError(502, `getSources server ${srv}: dekode XOR gagal (${e.message}).`);
  }
  if (!payload || !Array.isArray(payload.sources) || payload.sources.length === 0) {
    throw new VidCloudError(404, `getSources server ${srv}: tidak ada sumber video.`);
  }
  return payload;
}

// Cari track berdasarkan label (tidak peka huruf besar/kecil), mis. "indonesian", "english".
function findTrack(payload, label) {
  if (!payload || !Array.isArray(payload.tracks)) return null;
  const want = String(label).toLowerCase();
  return payload.tracks.find(
    (t) => t && typeof t.label === 'string' && t.label.toLowerCase().includes(want),
  ) || null;
}

// Ambil isi teks dari URL (VTT / m3u8). Jangan dipakai untuk URL
// yang mengandung token bila hasilnya akan dicatat — pemanggil yang
// bertanggung jawab tidak mencatat URL-nya.
async function fetchText(url, fetchImpl) {
  let res;
  try {
    res = await fetchWithTimeout(url, {
      headers: { 'User-Agent': config.USER_AGENT },
    }, 15000, fetchImpl);
  } catch (e) {
    throw new VidCloudError(0, `Unduh gagal (${e.message}).`);
  }
  if (!res.ok) {
    throw new VidCloudError(res.status, `Unduh HTTP ${res.status}.`);
  }
  return res.text();
}

module.exports = {
  VidCloudError, decodePayload, getSources, findTrack, fetchText,
  XOR_BASE, XOR_MOD,
};
