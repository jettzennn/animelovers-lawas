'use strict';
// GET /subtitle/id/{ani_id}/{ep} — VTT Indonesia.
// Alur (kontrak PRD §3.5 + aturan Riset T1):
//  1. Cache SQLite dulu (lengkap → instan; sebagian → kembalikan yang
//     sudah diterjemahkan + lanjutkan terjemahan di background).
//  2. Bila belum ada cache: cek track "Indonesian" VidCloud server 1
//     DULU — bila ada dan isinya benar-benar Indonesia, pakai langsung
//     (native, tanpa translate).
//  3. Bila tidak ada: ambil VTT Inggris server 1, terjemahkan cue awal
//     dulu (10 cue pertama, sinkron — target tampil ≤ 5 detik),
//     sisanya di background mendahului playhead.
//  4. Bila server 1 tidak ada: fallback ke MegaPlay (butuh query
//     mega_embed_id dari Frontend), BUKAN server 2 VidCloud
//     (VTT server 2 403 dari IP sandbox).
//  5. JANGAN PERNAH menyajikan track berlabel Indonesia yang isinya
//     Inggris: VTT sebagian hanya berisi cue yang SUDAH diterjemahkan
//     (cue yang belum diterjemahkan tidak ditulis sama sekali).
//  6. Bila semua layanan terjemah gagal → 502 (Frontend fallback ke
//     VTT Inggris langsung dari VidCloud, PRD §3.5 poin 4).
//
// Header respons:
//   X-Subtitle-Cache: hit | miss | native
//   X-Subtitle-Status: complete | partial
//   X-Subtitle-Source: native | translated
//   X-Subtitle-Translated-Cues / X-Subtitle-Total-Cues: angka
// Frontend meminta ulang bila Status: partial (tiap ~15 detik atau
// saat playhead mendekati cue terakhir yang diterjemahkan).
const config = require('../config');
const logger = require('../logger');
const db = require('../db');
const ratelimit = require('../ratelimit');
const vidcloud = require('../lib/vidcloud');
const megaplay = require('../lib/megaplay');
const vtt = require('../lib/vtt');
const translate = require('../lib/translate');
const { sendErr, parseIntParam } = require('./common');

// Janji background yang sedang berjalan per "ani_id:ep".
const inFlight = new Map();
// Negative cache kegagalan (in-memory, 5 menit): bila semua layanan
// gagal untuk satu episode, jangan coba lagi selama 5 menit.
const failedAt = new Map();

function isRecentlyFailed(key) {
  const at = failedAt.get(key);
  if (!at) return false;
  if (Date.now() - at < config.NEGATIVE_CACHE_TTL_MS) return true;
  failedAt.delete(key);
  return false;
}

function markFailed(key) {
  if (failedAt.size > 500) {
    const now = Date.now();
    for (const [k, at] of failedAt) {
      if (now - at >= config.NEGATIVE_CACHE_TTL_MS) failedAt.delete(k);
    }
  }
  failedAt.set(key, Date.now());
}

function renderFromTranslated(cues, translated) {
  const done = [];
  cues.forEach((c, i) => {
    if (translated[i] != null && String(translated[i]).trim() !== '') {
      done.push({ start: c.start, end: c.end, text: String(translated[i]) });
    }
  });
  return vtt.renderVtt(done);
}

function sendVtt(res, vttText, meta) {
  const body = vttText;
  const headers = {
    'Content-Type': 'text/vtt; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'X-Subtitle-Cache': meta.cache,
    'X-Subtitle-Status': meta.status,
    'X-Subtitle-Source': meta.source,
    'X-Subtitle-Translated-Cues': String(meta.translatedCues),
    'X-Subtitle-Total-Cues': String(meta.totalCues),
    'Cache-Control': meta.status === 'complete'
      ? 'public, max-age=86400'
      : 'no-store',
  };
  res.writeHead(200, headers);
  res.end(body);
}

// --- Sumber VTT -------------------------------------------------------

async function resolveSourceVtt(aniId, ep, megaEmbedId) {
  // 1. VidCloud server 1.
  try {
    const p1 = await vidcloud.getSources(aniId, ep, 1);
    const idTrack = vidcloud.findTrack(p1, 'indonesian');
    if (idTrack) {
      try {
        const text = await vidcloud.fetchText(idTrack.file);
        const parsed = vtt.parseVtt(text);
        // Validasi: label "Indonesian" belum tentu isinya Indonesia.
        if (parsed.cues.length >= 5
          && translate.looksIndonesian(text.slice(0, 3000))) {
          logger.info(`subtitle ani_id=${aniId} ep=${ep} track Indonesia native (vidcloud server 1)`);
          return {
            kind: 'native', vttText: text, cueCount: parsed.cues.length,
            enHash: vtt.sha256hex(text),
          };
        }
        logger.warn(`subtitle ani_id=${aniId} ep=${ep} track "Indonesian" vidcloud gagal validasi isi — lanjut translate dari Inggris.`);
      } catch (e) {
        logger.warn(`subtitle ani_id=${aniId} ep=${ep} VTT Indonesia vidcloud gagal diambil (${e.message}) — lanjut translate dari Inggris.`);
      }
    }
    const enTrack = vidcloud.findTrack(p1, 'english');
    if (enTrack) {
      const text = await vidcloud.fetchText(enTrack.file);
      const parsed = vtt.parseVtt(text);
      if (parsed.cues.length >= 5) {
        return {
          kind: 'english', cues: parsed.cues, enVttText: text,
          enHash: vtt.sha256hex(text),
        };
      }
    }
    logger.warn(`subtitle ani_id=${aniId} ep=${ep} vidcloud server 1 tanpa track Inggris yang valid.`);
  } catch (e) {
    logger.warn(`subtitle ani_id=${aniId} ep=${ep} vidcloud server 1 gagal (${e.message}).`);
  }

  // 2. MegaPlay (butuh episode_embed_id Anikoto dari Frontend).
  //    Server 2 VidCloud SENGAJA tidak dipakai (VTT 403 dari sandbox).
  if (megaEmbedId && /^\d+$/.test(megaEmbedId)) {
    try {
      const dataId = await megaplay.getEmbedDataId(megaEmbedId);
      const gs = await megaplay.getSources(dataId, megaEmbedId);
      const enTrack = (gs.tracks || []).find(
        (t) => t && typeof t.label === 'string' && t.label.toLowerCase().includes('english'),
      );
      if (enTrack) {
        const text = await megaplay.fetchText(enTrack.file);
        const parsed = vtt.parseVtt(text);
        if (parsed.cues.length >= 5) {
          logger.info(`subtitle ani_id=${aniId} ep=${ep} VTT Inggris dari megaplay`);
          return {
            kind: 'english', cues: parsed.cues, enVttText: text,
            enHash: vtt.sha256hex(text),
          };
        }
      }
    } catch (e) {
      logger.warn(`subtitle ani_id=${aniId} ep=${ep} megaplay gagal (${e.message}).`);
    }
  }

  throw new Error('Tidak ada sumber subtitle.');
}

// --- Terjemahan background --------------------------------------------

// Lanjutkan terjemahan cue yang belum diterjemahkan (batch 15, 4 worker).
// Dipanggil tanpa await (fire-and-forget) — hasilnya dibaca dari DB
// pada request berikutnya. In-flight dijaga supaya satu episode hanya
// punya satu job berjalan.
function continueTranslation(key, aniId, ep) {
  if (inFlight.has(key)) return;
  const job = (async () => {
    try {
      const row = db.getSubtitle(aniId, ep);
      if (!row || row.status === 'complete' || !row.en_vtt_text) return;
      const { cues } = vtt.parseVtt(row.en_vtt_text);
      let translated = JSON.parse(row.translated_json || '[]');
      const remaining = [];
      cues.forEach((c, i) => {
        if (translated[i] == null) remaining.push(i);
      });
      if (remaining.length === 0) {
        db.upsertSubtitle({
          ...rowToUpsert(row), status: 'complete',
          vttText: renderFromTranslated(cues, translated),
          translatedCues: translated.filter((x) => x != null).length,
        });
        return;
      }
      const result = await translate.translateCues(
        remaining.map((i) => cues[i].text),
        { timeoutMs: config.TRANSLATE_BATCH_TIMEOUT_MS },
      );
      let progressed = false;
      result.translated.forEach((t, j) => {
        if (t != null) {
          translated[remaining[j]] = t;
          progressed = true;
        }
      });
      const doneCount = translated.filter((x) => x != null).length;
      const complete = doneCount >= cues.length;
      db.upsertSubtitle({
        ...rowToUpsert(row),
        status: complete ? 'complete' : 'partial',
        vttText: renderFromTranslated(cues, translated),
        translatedJson: JSON.stringify(translated),
        translatedCues: doneCount,
      });
      logger.info(`subtitle ani_id=${aniId} ep=${ep} background: ${doneCount}/${cues.length} cue (${complete ? 'selesai' : 'sebagian'})`);
      if (!complete && !progressed) {
        // Tidak ada kemajuan sama sekali — layanan kemungkinan mati;
        // tahan percobaan ulang 5 menit (negative cache).
        markFailed(key);
      }
    } catch (e) {
      logger.warn(`subtitle ani_id=${aniId} ep=${ep} background gagal (${e.message})`);
    }
  })();
  inFlight.set(key, job);
  job.finally(() => inFlight.delete(key));
}

function rowToUpsert(row) {
  return {
    aniId: row.ani_id,
    ep: row.ep,
    source: row.source,
    status: row.status,
    vttText: row.vtt_text,
    enVttText: row.en_vtt_text,
    translatedJson: row.translated_json,
    totalCues: row.total_cues,
    translatedCues: row.translated_cues,
    enHash: row.en_hash,
  };
}

// --- Handler ----------------------------------------------------------

async function handleSubtitle(req, res, aniIdRaw, epRaw, query) {
  const aniId = parseIntParam(aniIdRaw, 999999999);
  const ep = parseIntParam(epRaw, 9999);
  if (aniId == null || ep == null) {
    return sendErr(res, 400, 'ID anime atau nomor episode tidak valid.');
  }
  const ip = ratelimit.clientIp(req);
  const limit = ratelimit.check(ip, 'subtitle');
  if (!limit.allowed) {
    return sendErr(res, 429, 'Terlalu banyak permintaan, coba lagi nanti.', limit.retryAfterSec);
  }
  const key = `${aniId}:${ep}`;
  const megaEmbedId = query.get('mega_embed_id') || null;

  // 1. Cache.
  const row = db.getSubtitle(aniId, ep);
  if (row && row.status === 'complete') {
    logger.info(`subtitle ani_id=${aniId} ep=${ep} cache hit (lengkap, ${row.source})`);
    return sendVtt(res, row.vtt_text, {
      cache: 'hit', status: 'complete', source: row.source,
      translatedCues: row.translated_cues, totalCues: row.total_cues,
    });
  }
  if (row && row.status === 'partial') {
    continueTranslation(key, aniId, ep);
    logger.info(`subtitle ani_id=${aniId} ep=${ep} cache hit (sebagian ${row.translated_cues}/${row.total_cues})`);
    return sendVtt(res, row.vtt_text, {
      cache: 'hit', status: 'partial', source: row.source,
      translatedCues: row.translated_cues, totalCues: row.total_cues,
    });
  }
  if (isRecentlyFailed(key)) {
    return sendErr(res, 502, 'Subtitle Indonesia belum bisa dibuat. Coba lagi sebentar lagi.');
  }

  // 2. Resolve sumber VTT.
  // Catatan: kegagalan resolve sumber TIDAK masuk negative cache —
  // sumber bisa muncul bila pemanggil mengulang dengan mega_embed_id
  // (fallback MegaPlay) atau bila upstream pulih. Negative cache hanya
  // untuk kegagalan layanan terjemah (di bawah).
  let source;
  try {
    source = await resolveSourceVtt(aniId, ep, megaEmbedId);
  } catch (e) {
    logger.warn(`subtitle ani_id=${aniId} ep=${ep} tidak ada sumber VTT (${e.message})`);
    return sendErr(res, 502, 'Subtitle Indonesia belum bisa dibuat. Coba lagi sebentar lagi.');
  }

  // 3a. Track Indonesia native — langsung lengkap.
  if (source.kind === 'native') {
    db.upsertSubtitle({
      aniId, ep, source: 'native', status: 'complete',
      vttText: source.vttText, enVttText: '', translatedJson: '[]',
      totalCues: source.cueCount, translatedCues: source.cueCount,
      enHash: source.enHash,
    });
    return sendVtt(res, source.vttText, {
      cache: 'native', status: 'complete', source: 'native',
      translatedCues: source.cueCount, totalCues: source.cueCount,
    });
  }

  // 3b. Translate cue awal dulu (sinkron, anggaran waktu ketat).
  const cues = source.cues;
  const firstCount = Math.min(config.FIRST_BATCH_CUES, cues.length);
  const firstTexts = cues.slice(0, firstCount).map((c) => c.text);
  let firstResult = null;
  try {
    firstResult = await Promise.race([
      translate.translateCues(firstTexts, { timeoutMs: 4000 }),
      new Promise((resolve) => setTimeout(
        () => resolve(null), config.FIRST_BATCH_TIMEOUT_MS,
      )),
    ]);
  } catch (e) {
    firstResult = null;
  }
  const translated = new Array(cues.length).fill(null);
  let okFirst = 0;
  if (firstResult) {
    firstResult.translated.forEach((t, i) => {
      if (t != null) {
        translated[i] = t;
        okFirst++;
      }
    });
  }
  if (okFirst === 0) {
    markFailed(key);
    logger.warn(`subtitle ani_id=${aniId} ep=${ep} terjemahan batch pertama gagal total — 502`);
    return sendErr(res, 502, 'Subtitle Indonesia belum bisa dibuat. Coba lagi sebentar lagi.');
  }
  const vttText = renderFromTranslated(cues, translated);
  db.upsertSubtitle({
    aniId, ep, source: 'translated', status: 'partial',
    vttText, enVttText: source.enVttText,
    translatedJson: JSON.stringify(translated),
    totalCues: cues.length, translatedCues: okFirst,
    enHash: source.enHash,
  });
  logger.info(`subtitle ani_id=${aniId} ep=${ep} miss: batch pertama ${okFirst}/${firstCount} cue, lanjut background`);
  continueTranslation(key, aniId, ep);
  return sendVtt(res, vttText, {
    cache: 'miss', status: 'partial', source: 'translated',
    translatedCues: okFirst, totalCues: cues.length,
  });
}

module.exports = { handleSubtitle };
