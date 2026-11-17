'use strict';
// Terjemahan Inggris → Indonesia untuk subtitle VTT.
// GRATIS, tanpa API key. Pola Sanka (terverifikasi dari kode player
// Sankanime, laporan riset-sankanime-2026-11-17.md §2.6–§3.4):
// - 10 endpoint Google Translate, round-robin (gtx sering 429 dari
//   IP datacenter — endpoint lain masih 200; terverifikasi live
//   2026-11-17: gtx 429, clients1 + translate.google.co.id 200).
// - Batch 15 cue, 4 worker paralel, jeda 50 ms antar batch.
// - Tiap item diberi penanda «{index}»{teks}, newline jadi [BR],
//   digabung \n — penanda dipakai untuk memecah hasil kembali per cue.
// - Tag HTML VTT (<i>, <v>, ...) dilindungi placeholder, dipulihkan
//   sesudahnya.
// - Bila semua endpoint Google gagal untuk satu batch → fallback
//   MyMemory per item (kuota gratis anonim 5.000 karakter/hari/IP).
// - Sukses bila ≥50% cue dalam satu batch berhasil diterjemahkan.
// - JANGAN PERNAH pakai apikey vttnime Sanka (bukan milik kita).
const config = require('../config');
const logger = require('../logger');

// 10 endpoint Google Translate (semua sl=en, tl=id). Urutan = urutan Sanka.
const GOOGLE_ENDPOINTS = [
  'https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=id&dt=t&q=',
  'https://clients1.google.com/translate_a/t?client=android&sl=en&tl=id&dt=t&q=',
  'https://www.google.com/translate_a/t?client=gtrans&sl=en&tl=id&q=',
  'https://encrypted.google.com/translate_a/t?client=gtd&sl=en&tl=id&q=',
  'https://translate.google.com/translate_a/t?client=tw-ob&sl=en&tl=id&dt=t&q=',
  'https://clients5.google.com/translate_a/single?client=dict-chrome-ex&sl=en&tl=id&q=',
  'https://translate.google.net/translate_a/t?client=gtd&sl=en&tl=id&q=',
  'https://translate.google.co.id/translate_a/t?client=chrome&sl=en&tl=id&q=',
  'https://google.com/translate_a/t?client=crx&sl=en&tl=id&q=',
  'https://translate.google.com.sg/translate_a/t?client=web&sl=en&tl=id&q=',
];

// Kursor round-robin global (berbagi antar panggilan).
let endpointCursor = 0;

const INDONESIAN_MARKERS = /\b(yang|dan|adalah|sebuah|ketika|dengan|untuk|dalam|seorang|mereka|tersebut|sehingga|namun|karena|kemudian)\b/gi;

function looksIndonesian(text) {
  const hits = String(text).match(INDONESIAN_MARKERS);
  return !!hits && hits.length >= 3;
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

// Parse jawaban Google ke satu string terjemahan.
// Bentuk A (endpoint .../single, mis. gtx): data[0] = segmen
// [terjemahan, sumber, ...] → gabung seg[0].
// Bentuk B (endpoint .../t, mis. android): data = array string
// → gabung semuanya.
function parseGoogleResponse(data) {
  if (Array.isArray(data) && data.length > 0 && Array.isArray(data[0])) {
    const joined = data[0].map((seg) => (Array.isArray(seg) ? seg[0] : '')).join('');
    if (!joined || !joined.trim()) throw new Error('Google: terjemahan kosong.');
    return joined;
  }
  if (Array.isArray(data) && data.length > 0 && typeof data[0] === 'string') {
    const joined = data.join('');
    if (!joined || !joined.trim()) throw new Error('Google: terjemahan kosong.');
    return joined;
  }
  throw new Error('Google: format jawaban tidak dikenal.');
}

// Pecah hasil terjemahan berpenanda «n» kembali per cue.
// Mengembalikan array sepanjang count (null bila penanda hilang).
function splitMarked(text, count) {
  const out = new Array(count).fill(null);
  const re = /«(\d+)»/g;
  const marks = [];
  let m;
  while ((m = re.exec(text)) !== null) {
    marks.push({ index: Number.parseInt(m[1], 10), pos: m.index, len: m[0].length });
  }
  for (let k = 0; k < marks.length; k++) {
    const { index, pos, len } = marks[k];
    if (index < 0 || index >= count) continue;
    const end = k + 1 < marks.length ? marks[k + 1].pos : text.length;
    const piece = text.slice(pos + len, end).replace(/\[BR\]/g, '\n').trim();
    if (piece) out[index] = piece;
  }
  return out;
}

// Lindungi tag HTML VTT (<i>, <v Nama>, ...) dengan placeholder
// supaya tidak diterjemahkan/dirusak Google. Mengembalikan
// { text, restore } — restore(mapping) mengembalikan tag aslinya.
function protectTags(text) {
  const tags = [];
  const protectedText = String(text).replace(/<[^>\n]{0,80}>/g, (tag) => {
    const id = tags.length;
    tags.push(tag);
    return `__TAG${id}__`;
  });
  return {
    text: protectedText,
    restore(translated) {
      let out = String(translated);
      tags.forEach((tag, id) => {
        out = out.split(`__TAG${id}__`).join(tag);
      });
      return out;
    },
  };
}

async function translateBatchGoogle(batch, startEndpoint, signal, fetchImpl) {
  const impl = fetchImpl || fetch;
  // Lindungi tag per cue, lalu beri penanda «i».
  const protectedBatch = batch.map((t) => protectTags(t));
  const q = protectedBatch
    .map((p, i) => `«${i}»${p.text.replace(/\n/g, '[BR]')}`)
    .join('\n');
  for (let attempt = 0; attempt < GOOGLE_ENDPOINTS.length; attempt++) {
    const epIdx = (startEndpoint + attempt) % GOOGLE_ENDPOINTS.length;
    const url = GOOGLE_ENDPOINTS[epIdx] + encodeURIComponent(q);
    try {
      const res = await impl(url, {
        headers: {
          'User-Agent': 'Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36',
        },
        signal,
      });
      if (!res.ok) continue; // 429/dll — coba endpoint berikutnya
      const data = await res.json();
      const joined = parseGoogleResponse(data);
      const split = splitMarked(joined, batch.length);
      const restored = split.map((piece, i) => (
        piece == null ? null : protectedBatch[i].restore(piece)
      ));
      const okCount = restored.filter((x) => x != null).length;
      if (okCount >= Math.ceil(batch.length / 2)) {
        return { translated: restored, okCount, provider: 'google' };
      }
      // < 50% berhasil — coba endpoint berikutnya.
    } catch (e) {
      if (e && e.name === 'AbortError') throw e;
      // Jaringan/parse gagal — coba endpoint berikutnya.
    }
  }
  return { translated: new Array(batch.length).fill(null), okCount: 0, provider: 'google' };
}

async function translateOneMyMemory(text, signal, fetchImpl) {
  const impl = fetchImpl || fetch;
  const url = 'https://api.mymemory.translated.net/get?q='
    + encodeURIComponent(text)
    + '&langpair=en%7Cid'
    + (config.MYMEMORY_EMAIL ? `&de=${encodeURIComponent(config.MYMEMORY_EMAIL)}` : '');
  const res = await impl(url, {
    headers: { Accept: 'application/json' },
    signal,
  });
  if (!res.ok) {
    const e = new Error(`MyMemory HTTP ${res.status}`);
    e.status = res.status;
    throw e;
  }
  const data = await res.json();
  const t = data && data.responseData && data.responseData.translatedText;
  if (!t || !String(t).trim()) throw new Error('MyMemory: terjemahan kosong.');
  if (String(t).startsWith('MYMEMORY WARNING')
    || String(t).startsWith('QUERY LENGTH LIMIT')) {
    const e = new Error('MyMemory: kuota gratis habis atau teks terlalu panjang.');
    e.status = 429;
    throw e;
  }
  return String(t);
}

async function translateBatchMyMemory(batch, signal, fetchImpl) {
  const out = new Array(batch.length).fill(null);
  for (let i = 0; i < batch.length; i++) {
    try {
      // eslint-disable-next-line no-await-in-loop
      out[i] = await translateOneMyMemory(batch[i], signal, fetchImpl);
    } catch (e) {
      if (e && e.status === 429) {
        // Cooldown 3 detik (pola Sanka), lalu lanjut item berikutnya.
        // eslint-disable-next-line no-await-in-loop
        await sleep(3000);
      }
      out[i] = null;
    }
    // eslint-disable-next-line no-await-in-loop
    await sleep(100);
  }
  return { translated: out, okCount: out.filter((x) => x != null).length, provider: 'mymemory' };
}

// Terjemahkan daftar teks cue (urutan dipertahankan).
// Mengembalikan { translated: (string|null)[], provider, okCount }.
// Melempar hanya bila sinyal dibatalkan (timeout) — kegagalan layanan
// menghasilkan null per item, bukan lemparan.
async function translateCues(cueTexts, opts) {
  const o = opts || {};
  const batchSize = o.batchSize || config.TRANSLATE_BATCH_CUES;
  const workers = o.workers || config.TRANSLATE_WORKERS;
  const timeoutMs = o.timeoutMs || config.TRANSLATE_BATCH_TIMEOUT_MS;
  const fetchImpl = o.fetchImpl || fetch;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs * 4);
  const signal = controller.signal;
  const translated = new Array(cueTexts.length).fill(null);
  let provider = 'google';
  try {
    const batches = [];
    for (let i = 0; i < cueTexts.length; i += batchSize) {
      batches.push({ start: i, texts: cueTexts.slice(i, i + batchSize) });
    }
    // Kelompok worker: 4 batch paralel, jeda 50 ms antar kelompok.
    for (let off = 0; off < batches.length; off += workers) {
      const group = batches.slice(off, off + workers);
      // eslint-disable-next-line no-await-in-loop
      const results = await Promise.all(group.map(async (b) => {
        const startEp = endpointCursor;
        endpointCursor = (endpointCursor + 1) % GOOGLE_ENDPOINTS.length;
        let r = await translateBatchGoogle(b.texts, startEp, signal, fetchImpl);
        if (r.okCount < Math.ceil(b.texts.length / 2)) {
          const mm = await translateBatchMyMemory(b.texts, signal, fetchImpl);
          // Gabung: yang Google gagal, isi dari MyMemory bila ada.
          r = {
            translated: r.translated.map((t, i) => (t != null ? t : mm.translated[i])),
            okCount: r.translated.filter((t, i) => t != null || mm.translated[i] != null).length,
            provider: 'mixed',
          };
        }
        return { start: b.start, result: r };
      }));
      for (const { start, result } of results) {
        result.translated.forEach((t, i) => {
          if (t != null) translated[start + i] = t;
        });
        if (result.provider !== 'google') provider = result.provider;
      }
      if (off + workers < batches.length) {
        // eslint-disable-next-line no-await-in-loop
        await sleep(config.TRANSLATE_BATCH_GAP_MS);
      }
    }
    return {
      translated,
      okCount: translated.filter((x) => x != null).length,
      provider,
    };
  } finally {
    clearTimeout(timer);
  }
}

module.exports = {
  GOOGLE_ENDPOINTS,
  looksIndonesian,
  parseGoogleResponse,
  splitMarked,
  protectTags,
  translateCues,
  translateBatchGoogle,
  translateBatchMyMemory,
};
