'use strict';
// Validasi track "Indonesian" native — SATU sumber kebenaran.
// Aturan tetap: JANGAN PERNAH menyajikan track berlabel Indonesia
// yang isinya Inggris. Kriteria ini dipakai di DUA tempat:
//   1. /subtitle/id — sebelum memakai track native VidCloud.
//   2. /resolve — sebelum mengklaim has_indonesian_track.
// Kriteria (sama persis di keduanya): VTT valid, ≥5 cue, dan 3000
// karakter pertama mengandung ≥3 penanda Bahasa Indonesia yang
// BERBEDA (translate.looksIndonesian — hitung penanda berbeda,
// bukan total kemunculan).
const vtt = require('./vtt');
const translate = require('./translate');

// Kembalikan jumlah cue bila teks adalah VTT Indonesia yang valid,
// 0 bila bukan (bukan VTT / <5 cue / isinya bukan Indonesia).
// Tidak pernah melempar.
function checkIndonesianVtt(text) {
  try {
    const parsed = vtt.parseVtt(text);
    if (parsed.cues.length >= 5
      && translate.looksIndonesian(String(text).slice(0, 3000))) {
      return parsed.cues.length;
    }
  } catch (e) {
    // Bukan VTT valid — bukan track Indonesia.
  }
  return 0;
}

// Ambil VTT dari URL lalu validasi. Kembalikan true hanya bila isinya
// terbukti Indonesia. TIDAK PERNAH melempar — gagal unduh (mis. 403),
// timeout, atau isi tidak valid semuanya berarti "tidak terverifikasi"
// (fail-closed: pemanggil harus memperlakukannya sebagai tidak ada
// track native, lalu fallback ke /subtitle/id).
// fetchTextFn: fungsi (url, fetchImpl, timeoutMs) => Promise<string>,
//   mis. vidcloud.fetchText / megaplay.fetchText (atau mock di tes).
async function isTrackIndonesian(fetchTextFn, url, timeoutMs) {
  try {
    const text = await fetchTextFn(url, undefined, timeoutMs);
    return checkIndonesianVtt(text) > 0;
  } catch (e) {
    return false;
  }
}

module.exports = { checkIndonesianVtt, isTrackIndonesian };
