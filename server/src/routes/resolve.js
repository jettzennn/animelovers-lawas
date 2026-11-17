'use strict';
// GET /resolve/{ani_id}/{ep} — ambil sumber video + subtitle Inggris.
// Alur (kontrak PRD §3.6):
//  1. VidCloud getSources server 1 (Sora) → bila gagal, server 2, lalu 3.
//  2. Bila VidCloud gagal semua dan query mega_embed_id diberikan:
//     MegaPlay (embed → data-id → getSources → dekripsi enc).
//  3. Bila semua gagal → 502 dengan pesan Bahasa Indonesia.
//
// Respons: { ok, data: { ani_id, episode, source, server, server_label,
//   hls, hls_proxy, tracks: [{lang, label, url, proxy_url}],
//   has_indonesian_track, intro, outro } }.
// URL upstream dikembalikan apa adanya (kontrak PRD) + versi proxy-nya
// (hls_proxy / proxy_url) supaya Frontend bisa memilih memutar lewat
// proxy tanpa membocorkan URL upstream ke log — server TIDAK PERNAH
// mencatat URL upstream (hanya ani_id/ep/sumber/server/status).
const config = require('../config');
const logger = require('../logger');
const ratelimit = require('../ratelimit');
const vidcloud = require('../lib/vidcloud');
const megaplay = require('../lib/megaplay');
const { sendJson, sendErr, parseIntParam } = require('./common');

function langOf(label) {
  const l = String(label || '').toLowerCase();
  if (l.includes('indonesian')) return 'id';
  if (l.includes('english')) return 'en';
  if (l.includes('portuguese')) return 'pt';
  if (l.includes('spanish')) return 'es';
  if (l.includes('german')) return 'de';
  if (l.includes('malay')) return 'ms';
  if (l.includes('thai')) return 'th';
  if (l.includes('vietnamese')) return 'vi';
  if (l.includes('chinese')) return 'zh';
  return l.slice(0, 2) || 'en';
}

function proxyUrl(url) {
  return `/proxy/hls?u=${encodeURIComponent(url)}`;
}

function buildVidCloudResponse(aniId, ep, payload) {
  const source = (payload.sources || []).find((s) => s && s.default) || payload.sources[0];
  const tracks = (payload.tracks || []).map((t) => ({
    lang: langOf(t.label),
    label: t.label,
    url: t.file,
    proxy_url: proxyUrl(t.file),
  }));
  return {
    ani_id: aniId,
    episode: ep,
    source: 'vidcloud',
    server: payload.server || 1,
    server_label: (source && source.label) || null,
    hls: source.file,
    hls_proxy: proxyUrl(source.file),
    tracks,
    has_indonesian_track: tracks.some((t) => t.lang === 'id'),
    intro: payload.intro || { start: 0, end: 0 },
    outro: payload.outro || { start: 0, end: 0 },
  };
}

async function resolveViaMegaPlay(aniId, ep, embedId) {
  const dataId = await megaplay.getEmbedDataId(embedId);
  const gs = await megaplay.getSources(dataId, embedId);
  let file = null;
  try {
    const dec = megaplay.decryptEnc(gs.enc);
    file = dec && dec.file ? dec.file : null;
  } catch (e) {
    // Kunci AES dirotasi MegaPlay — log jelas, jangan diam-diam gagal.
    logger.warn(`MegaPlay: dekripsi enc gagal untuk ani_id ${aniId} ep ${ep} — kunci kemungkinan dirotasi (${e.message}).`);
    throw new megaplay.MegaPlayError(502, 'MegaPlay: kunci dekripsi berubah.');
  }
  if (!file) throw new megaplay.MegaPlayError(502, 'MegaPlay: tidak ada URL video.');
  const tracks = (gs.tracks || []).map((t) => ({
    lang: langOf(t.label),
    label: t.label,
    url: t.file,
    proxy_url: proxyUrl(t.file),
  }));
  return {
    ani_id: aniId,
    episode: ep,
    source: 'megaplay',
    server: gs.server || null,
    server_label: null,
    hls: file,
    hls_proxy: proxyUrl(file),
    tracks,
    has_indonesian_track: tracks.some((t) => t.lang === 'id'),
    intro: gs.intro || { start: 0, end: 0 },
    outro: gs.outro || { start: 0, end: 0 },
  };
}

async function handleResolve(req, res, aniIdRaw, epRaw, query) {
  const aniId = parseIntParam(aniIdRaw, 999999999);
  const ep = parseIntParam(epRaw, 9999);
  if (aniId == null || ep == null) {
    return sendErr(res, 400, 'ID anime atau nomor episode tidak valid.');
  }
  const ip = ratelimit.clientIp(req);
  const limit = ratelimit.check(ip, 'resolve');
  if (!limit.allowed) {
    return sendErr(res, 429, 'Terlalu banyak permintaan, coba lagi nanti.', limit.retryAfterSec);
  }

  // 1. VidCloud server 1 → 2 → 3 (untuk VIDEO; subtitle diatur di /subtitle/id).
  let lastErr = null;
  for (const srv of [1, 2, 3]) {
    try {
      const payload = await vidcloud.getSources(aniId, ep, srv);
      const data = buildVidCloudResponse(aniId, ep, payload);
      logger.info(`resolve ani_id=${aniId} ep=${ep} sumber=vidcloud server=${srv} ok`);
      return sendJson(res, 200, { ok: true, data });
    } catch (e) {
      lastErr = e;
      logger.warn(`resolve ani_id=${aniId} ep=${ep} vidcloud server=${srv} gagal (${e.message})`);
    }
  }

  // 2. MegaPlay cadangan (butuh episode_embed_id Anikoto dari Frontend).
  const embedId = query.get('mega_embed_id');
  if (embedId && /^\d+$/.test(embedId)) {
    try {
      const data = await resolveViaMegaPlay(aniId, ep, embedId);
      logger.info(`resolve ani_id=${aniId} ep=${ep} sumber=megaplay ok`);
      return sendJson(res, 200, { ok: true, data });
    } catch (e) {
      logger.warn(`resolve ani_id=${aniId} ep=${ep} megaplay gagal (${e.message})`);
    }
  }

  logger.warn(`resolve ani_id=${aniId} ep=${ep} gagal semua sumber (${lastErr ? lastErr.message : 'tanpa detail'})`);
  return sendErr(res, 502, 'Video tidak dapat dimuat saat ini. Coba lagi sebentar lagi.');
}

module.exports = { handleResolve };
