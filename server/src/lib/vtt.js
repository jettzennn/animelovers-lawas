'use strict';
// Parse + render file WebVTT.
// Dipakai untuk: (1) membaca VTT Inggris sumber, (2) merender VTT
// Indonesia hasil terjemahan (hanya cue yang sudah diterjemahkan —
// cue yang belum diterjemahkan TIDAK ditulis, supaya track berlabel
// Indonesia tidak pernah berisi teks Inggris).
const crypto = require('node:crypto');

const TS_RE = /^(\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})\s+-->\s+(\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})(?:\s+.*)?$/;

function sha256hex(text) {
  return crypto.createHash('sha256').update(text, 'utf8').digest('hex');
}

// Parse VTT menjadi { cues: [{ start, end, text }] }.
// - Baris NOTE / STYLE / REGION dilewati.
// - Baris pengenal cue (opsional, sebelum timestamp) dilewati.
// - Timestamp dipertahankan apa adanya (string asli) supaya render
//   ulang identik.
// Melempar bila bukan VTT atau tidak ada cue.
function parseVtt(text) {
  const clean = String(text).replace(/^\uFEFF/, '');
  if (!clean.startsWith('WEBVTT')) {
    throw new Error('Bukan file WebVTT (tidak diawali WEBVTT).');
  }
  const lines = clean.split(/\r?\n/);
  const cues = [];
  let i = 0;
  // Lewati blok header (sampai baris kosong pertama).
  while (i < lines.length && lines[i].trim() !== '') i++;
  while (i < lines.length) {
    // Lewati baris kosong.
    while (i < lines.length && lines[i].trim() === '') i++;
    if (i >= lines.length) break;
    const first = lines[i].trim();
    // Blok NOTE / STYLE / REGION: lewati sampai baris kosong.
    if (first === 'NOTE' || first.startsWith('NOTE ') || first === 'STYLE' || first === 'REGION') {
      while (i < lines.length && lines[i].trim() !== '') i++;
      continue;
    }
    // Baris pengenal cue (bukan timestamp) → baris berikutnya harus timestamp.
    let tsLine = first;
    if (!TS_RE.test(tsLine)) {
      i++;
      if (i >= lines.length) break;
      tsLine = lines[i].trim();
      if (!TS_RE.test(tsLine)) {
        // Bukan cue yang valid — lewati blok ini.
        while (i < lines.length && lines[i].trim() !== '') i++;
        continue;
      }
    }
    const m = tsLine.match(TS_RE);
    const start = m[1];
    const end = m[2];
    i++;
    const textLines = [];
    while (i < lines.length && lines[i].trim() !== '') {
      textLines.push(lines[i]);
      i++;
    }
    const cueText = textLines.join('\n').trim();
    if (cueText) cues.push({ start, end, text: cueText });
  }
  if (cues.length === 0) {
    throw new Error('File VTT tidak berisi cue.');
  }
  return { cues };
}

// Render daftar cue menjadi teks WebVTT.
function renderVtt(cues) {
  const body = cues
    .map((c) => `${c.start} --> ${c.end}\n${c.text}`)
    .join('\n\n');
  return `WEBVTT\n\n${body}\n`;
}

module.exports = { parseVtt, renderVtt, sha256hex };
