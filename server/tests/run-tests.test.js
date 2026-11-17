'use strict';
// Unit test backend kecil AnimeLovers Revival — fungsi murni, tanpa jaringan.
// Jalankan: npm test  (node --test tests/)
const test = require('node:test');
const assert = require('node:assert/strict');
const os = require('node:os');
const path = require('node:path');
const fs = require('node:fs');

const vidcloud = require('../src/lib/vidcloud');
const megaplay = require('../src/lib/megaplay');
const vtt = require('../src/lib/vtt');
const translate = require('../src/lib/translate');
const nativeTrack = require('../src/lib/native-track');
const { buildVidCloudResponse } = require('../src/routes/resolve');
const proxy = require('../src/routes/proxy');
const { parseIntParam } = require('../src/routes/common');
const db = require('../src/db');

// --- VidCloud XOR -------------------------------------------------------

test('vidcloud: decodePayload round-trip (encode -> decode)', () => {
  const original = {
    sources: [{ file: 'https://vidcloud.sbs/stream/manifest.m3u8?t=abc', type: 'hls', label: 'Sora', default: true }],
    tracks: [{ file: 'https://vidcloud.sbs/stream/subs.vtt?t=abc', label: 'English', kind: 'captions', default: true }],
    intro: { start: 0, end: 0 },
    outro: { start: 1313, end: 1403 },
    server: 1,
  };
  const json = JSON.stringify(original);
  const buf = Buffer.from(json, 'utf8');
  const enc = Buffer.alloc(buf.length);
  for (let i = 0; i < buf.length; i++) {
    enc[i] = buf[i] ^ ((vidcloud.XOR_BASE + (i % vidcloud.XOR_MOD)) & 0xff);
  }
  const b64url = enc.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  assert.deepEqual(vidcloud.decodePayload(b64url), original);
});

test('vidcloud: findTrack tidak peka huruf besar/kecil', () => {
  const payload = { tracks: [
    { label: 'English', file: 'en.vtt' },
    { label: 'Indonesian', file: 'id.vtt' },
  ] };
  assert.equal(vidcloud.findTrack(payload, 'indonesian').file, 'id.vtt');
  assert.equal(vidcloud.findTrack(payload, 'ENGLISH').file, 'en.vtt');
  assert.equal(vidcloud.findTrack(payload, 'german'), null);
});

// --- MegaPlay AES -------------------------------------------------------

test('megaplay: decryptEnc round-trip (encrypt -> decrypt)', () => {
  const obj = { file: 'https://fetch.nexabloom.top/anime/xyz/master.m3u8' };
  const enc = megaplay.encryptEncForTest(obj);
  assert.deepEqual(megaplay.decryptEnc(enc), obj);
});

test('megaplay: decryptEnc gagal bila kunci salah (tamper)', () => {
  const obj = { file: 'https://example.com/master.m3u8' };
  const enc = megaplay.encryptEncForTest(obj);
  // Ubah satu byte ciphertext — dekripsi harus gagal (padding/JSON rusak).
  const buf = Buffer.from(enc, 'base64');
  buf[5] ^= 0xff;
  assert.throws(() => megaplay.decryptEnc(buf.toString('base64')));
});

// --- VTT ----------------------------------------------------------------

const SAMPLE_VTT = `WEBVTT

NOTE Komentar ini dilewati

1
00:00:16.060 --> 00:00:22.140
The first end of the century in 100 years.

00:00:23.000 --> 00:00:25.500 align:start
Good morning, everyone.
Second line here.
`;

test('vtt: parseVtt melewati NOTE + pengenal cue + multi-baris', () => {
  const { cues } = vtt.parseVtt(SAMPLE_VTT);
  assert.equal(cues.length, 2);
  assert.equal(cues[0].start, '00:00:16.060');
  assert.equal(cues[0].end, '00:00:22.140');
  assert.equal(cues[0].text, 'The first end of the century in 100 years.');
  assert.equal(cues[1].text, 'Good morning, everyone.\nSecond line here.');
});

test('vtt: parseVtt menolak bukan-VTT dan VTT tanpa cue', () => {
  assert.throws(() => vtt.parseVtt('Hello world'), /WebVTT/);
  assert.throws(() => vtt.parseVtt('WEBVTT\n\n'), /cue/);
});

test('vtt: renderVtt menghasilkan WEBVTT valid yang bisa di-parse lagi', () => {
  const { cues } = vtt.parseVtt(SAMPLE_VTT);
  const rendered = vtt.renderVtt(cues);
  assert.ok(rendered.startsWith('WEBVTT'));
  const again = vtt.parseVtt(rendered);
  assert.deepEqual(again.cues, cues);
});

test('vtt: renderVtt hanya cue yang diterjemahkan (sebagian)', () => {
  const { cues } = vtt.parseVtt(SAMPLE_VTT);
  const partial = vtt.renderVtt([cues[0]]);
  assert.ok(partial.includes('The first end'));
  assert.ok(!partial.includes('Good morning'));
});

// --- Translate: parse + marker ------------------------------------------

test('translate: parseGoogleResponse bentuk A (single/gtx)', () => {
  const data = [[['Selamat pagi.', 'Good morning.', null, null, 1]], null, 'en'];
  assert.equal(translate.parseGoogleResponse(data), 'Selamat pagi.');
});

test('translate: parseGoogleResponse bentuk B (t/android)', () => {
  assert.equal(translate.parseGoogleResponse(['Selamat pagi semuanya.']), 'Selamat pagi semuanya.');
});

test('translate: splitMarked memecah hasil per cue + pulihkan [BR]', () => {
  const joined = '«0»Akhir abad pertama.\n«1»Selamat pagi semuanya.\n«2»Saya akan menemukan kebenaran.';
  assert.deepEqual(translate.splitMarked(joined, 3), [
    'Akhir abad pertama.',
    'Selamat pagi semuanya.',
    'Saya akan menemukan kebenaran.',
  ]);
  // Penanda hilang -> null (bukan teks cue lain).
  assert.deepEqual(translate.splitMarked('«0»Satu.\n«2»Tiga.', 3), ['Satu.', null, 'Tiga.']);
});

test('translate: protectTags melindungi tag VTT dan memulihkannya', () => {
  const p = translate.protectTags('<i>Hello</i> <v Conan>Hi</v>');
  assert.ok(!p.text.includes('<i>'));
  assert.equal(p.restore('__TAG0__Halo__TAG1__ __TAG2__Hai__TAG3__'), '<i>Halo</i> <v Conan>Hai</v>');
});

test('translate: looksIndonesian membedakan Indonesia vs Inggris', () => {
  assert.equal(translate.looksIndonesian('Selamat pagi semuanya yang ada di sini dan mereka yang datang dengan senang hati.'), true);
  assert.equal(translate.looksIndonesian('Good morning everyone, this is the first day of the case.'), false);
});

test('translate: translateCues sejajar dengan fetch mock (bentuk B)', async () => {
  const mockFetch = async (url) => {
    const q = decodeURIComponent(url.split('q=')[1]);
    // Tiru endpoint .../t: kembalikan tiap baris yang sudah "diterjemahkan".
    const translated = q.split('\n').map((line) => line.replace(/«\d+»/, (m) => `${m}ID-`)).join('\n');
    return { ok: true, json: async () => [translated] };
  };
  const res = await translate.translateCues(
    ['Hello world.', 'Good morning.'],
    { fetchImpl: mockFetch, timeoutMs: 5000 },
  );
  assert.equal(res.okCount, 2);
  assert.ok(res.translated[0].includes('ID-'));
  assert.ok(res.translated[1].includes('ID-'));
});

// --- Proxy --------------------------------------------------------------

test('proxy: isAllowedHost hanya host upstream yang dikenal', () => {
  assert.equal(proxy.isAllowedHost('vidcloud.sbs'), true);
  assert.equal(proxy.isAllowedHost('hc6kp.mysubsplay.com'), true);
  assert.equal(proxy.isAllowedHost('fetch.nexabloom.top'), true);
  assert.equal(proxy.isAllowedHost('evil.com'), false);
  assert.equal(proxy.isAllowedHost('vidcloud.sbs.evil.com'), false);
  assert.equal(proxy.isAllowedHost('notmysubsplay.com'), false);
});

test('proxy: isPrivateIp menolak alamat privat/loopback', () => {
  assert.equal(proxy.isPrivateIp('127.0.0.1'), true);
  assert.equal(proxy.isPrivateIp('10.0.0.5'), true);
  assert.equal(proxy.isPrivateIp('192.168.1.1'), true);
  assert.equal(proxy.isPrivateIp('172.16.0.9'), true);
  assert.equal(proxy.isPrivateIp('172.32.0.9'), false);
  assert.equal(proxy.isPrivateIp('8.8.8.8'), false);
  assert.equal(proxy.isPrivateIp('::1'), true);
});

test('proxy: rewriteM3u8 menulis ulang URI relatif + EXT-X-KEY', () => {
  const playlist = `#EXTM3U
#EXT-X-VERSION:3
#EXT-X-KEY:METHOD=AES-128,URI="key.bin"
#EXTINF:10.0,
seg1.ts
#EXTINF:10.0,
https://cdn.contoh/x/seg2.ts
`;
  const out = proxy.rewriteM3u8(playlist, 'https://vidcloud.sbs/stream/master.m3u8?t=tok');
  assert.ok(out.includes('URI="/proxy/hls?u=https%3A%2F%2Fvidcloud.sbs%2Fstream%2Fkey.bin"'));
  assert.ok(out.includes('/proxy/hls?u=https%3A%2F%2Fvidcloud.sbs%2Fstream%2Fseg1.ts'));
  assert.ok(out.includes('/proxy/hls?u=https%3A%2F%2Fcdn.contoh%2Fx%2Fseg2.ts'));
  assert.ok(out.startsWith('#EXTM3U'));
});

// --- Validasi + DB ------------------------------------------------------

test('common: parseIntParam menolak input tidak valid', () => {
  assert.equal(parseIntParam('235', 999999999), 235);
  assert.equal(parseIntParam('0', 9999), null);
  assert.equal(parseIntParam('10000', 9999), null);
  assert.equal(parseIntParam('12a', 9999), null);
  assert.equal(parseIntParam('', 9999), null);
  assert.equal(parseIntParam(undefined, 9999), null);
});

test('db: subtitle_cache simpan + baca (DB sementara)', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'animelovers-db-'));
  db.init(path.join(tmp, 'test.db'));
  try {
    db.upsertSubtitle({
      aniId: 235, ep: 1, source: 'translated', status: 'partial',
      vttText: 'WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHalo.\n',
      enVttText: 'WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello.\n',
      translatedJson: JSON.stringify(['Halo.']),
      totalCues: 2, translatedCues: 1, enHash: 'abc',
    });
    const row = db.getSubtitle(235, 1);
    assert.equal(row.status, 'partial');
    assert.equal(row.translated_cues, 1);
    assert.equal(db.getSubtitle(999, 1), null);
    // Update menjadi complete.
    db.upsertSubtitle({
      aniId: 235, ep: 1, source: 'translated', status: 'complete',
      vttText: 'WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHalo.\n',
      enVttText: 'WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello.\n',
      translatedJson: JSON.stringify(['Halo.']),
      totalCues: 1, translatedCues: 1, enHash: 'abc',
    });
    assert.equal(db.getSubtitle(235, 1).status, 'complete');
  } finally {
    db.close();
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

// --- Track "Indonesian" native: validasi isi (temuan Cari Bug #2) ------

function makeVtt(cueTexts) {
  return 'WEBVTT\n\n' + cueTexts.map((t, i) => {
    const s = String(i + 1).padStart(2, '0');
    const e = String(i + 2).padStart(2, '0');
    return `00:00:${s}.000 --> 00:00:${e}.000\n${t}`;
  }).join('\n\n') + '\n';
}

const ID_CUES = [
  'Selamat pagi semuanya yang ada di sini.',
  'Mereka datang dengan membawa kabar yang penting.',
  'Ketika dia tiba, semua orang sudah menunggu.',
  'Ini adalah sebuah cerita tentang mereka.',
  'Karena itu, kita harus pergi sekarang juga.',
  'Namun perjalanan ini tidak akan mudah.',
];

const EN_CUES = [
  'Good morning everyone, welcome to the show.',
  'They came here with important news today.',
  'When he arrived, everyone was already waiting.',
  'This is a story about their journey.',
  'So we have to leave right now.',
  'But this journey will not be easy.',
];

test('native-track: checkIndonesianVtt menerima Indonesia ≥5 cue, menolak Inggris / <5 cue / bukan VTT', () => {
  assert.equal(nativeTrack.checkIndonesianVtt(makeVtt(ID_CUES)), 6);
  assert.equal(nativeTrack.checkIndonesianVtt(makeVtt(EN_CUES)), 0);
  assert.equal(nativeTrack.checkIndonesianVtt(makeVtt(ID_CUES.slice(0, 4))), 0);
  assert.equal(nativeTrack.checkIndonesianVtt('Hello world'), 0);
  assert.equal(nativeTrack.checkIndonesianVtt('WEBVTT\n\n'), 0);
});

test('native-track: isTrackIndonesian memakai fetch mock (valid / unduh gagal / isi Inggris)', async () => {
  const idVtt = makeVtt(ID_CUES);
  const enVtt = makeVtt(EN_CUES);
  assert.equal(await nativeTrack.isTrackIndonesian(async () => idVtt, 'https://x/id.vtt'), true);
  assert.equal(await nativeTrack.isTrackIndonesian(async () => enVtt, 'https://x/id.vtt'), false);
  // Unduh gagal (mis. VTT server 2 403) = tidak terverifikasi, bukan error.
  assert.equal(await nativeTrack.isTrackIndonesian(async () => { throw new Error('HTTP 403'); }, 'https://x/id.vtt'), false);
  // Timeout diteruskan ke fetchText (mock menerimanya sebagai argumen ke-3).
  let gotTimeout = null;
  await nativeTrack.isTrackIndonesian(async (url, impl, timeoutMs) => { gotTimeout = timeoutMs; return idVtt; }, 'https://x/id.vtt', 5000);
  assert.equal(gotTimeout, 5000);
});

test('resolve: buildVidCloudResponse membuang track Indonesian yang tidak terverifikasi', async () => {
  const payload = {
    sources: [{ file: 'https://vidcloud.sbs/stream/master.m3u8?t=abc', label: 'Zexy', default: true }],
    tracks: [
      { file: 'https://vidcloud.sbs/stream/id.vtt?t=abc', label: 'Indonesian', kind: 'captions' },
      { file: 'https://vidcloud.sbs/stream/en.vtt?t=abc', label: 'English', kind: 'captions' },
    ],
    intro: { start: 0, end: 0 },
    outro: { start: 1313, end: 1403 },
    server: 2,
  };
  // Validasi gagal (VTT 403 / isi Inggris) -> track 'id' dibuang,
  // flag false, track Inggris tetap ada (fallback /subtitle/id).
  const dropped = await buildVidCloudResponse(235, 1, payload, {
    validateIndonesian: async () => false,
  });
  assert.equal(dropped.has_indonesian_track, false);
  assert.deepEqual(dropped.tracks.map((t) => t.lang), ['en']);
  assert.equal(dropped.tracks[0].label, 'English');
  // Validasi lolos -> track dipertahankan + flag true.
  const kept = await buildVidCloudResponse(235, 1, payload, {
    validateIndonesian: async () => true,
  });
  assert.equal(kept.has_indonesian_track, true);
  assert.deepEqual(kept.tracks.map((t) => t.lang), ['id', 'en']);
});

test('resolve: buildVidCloudResponse tidak memvalidasi bila tidak ada track Indonesian', async () => {
  const payload = {
    sources: [{ file: 'https://vidcloud.sbs/stream/master.m3u8?t=abc', label: 'Sora', default: true }],
    tracks: [
      { file: 'https://vidcloud.sbs/stream/en.vtt?t=abc', label: 'English', kind: 'captions' },
    ],
    intro: { start: 0, end: 0 },
    outro: { start: 0, end: 0 },
    server: 1,
  };
  let calls = 0;
  const res = await buildVidCloudResponse(235, 1, payload, {
    validateIndonesian: async () => { calls++; return true; },
  });
  assert.equal(calls, 0);
  assert.equal(res.has_indonesian_track, false);
  assert.equal(res.tracks.length, 1);
});
