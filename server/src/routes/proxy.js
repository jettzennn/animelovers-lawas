'use strict';
// GET /proxy/hls?u=<url> — proxy HLS (.m3u8) / segmen (.ts) / VTT.
// Dipakai bila pemutaran langsung dari URL upstream bermasalah
// (mis. butuh Referer khusus) atau supaya URL upstream tidak sampai
// ke aplikasi. Playlist .m3u8 ditulis ulang: semua URI relatif
// diarahkan kembali lewat proxy ini.
//
// KEAMANAN (SSRF): hanya host upstream yang dikenal yang diizinkan
// (allowlist), hanya https, dan hostname yang resolve ke IP privat /
// loopback / link-local DITOLAK (perlindungan DNS rebinding).
// URL yang di-proxy TIDAK PERNAH dicatat di log (mengandung token).
const dns = require('node:dns').promises;
const { Readable } = require('node:stream');
const config = require('../config');
const logger = require('../logger');
const ratelimit = require('../ratelimit');
const { sendErr } = require('./common');

const MAX_PLAYLIST_BYTES = 2 * 1024 * 1024;
const MAX_BODY_BYTES = 64 * 1024 * 1024;

// Host upstream yang diizinkan (VidCloud + CDN MegaPlay).
const ALLOWED = [
  { exact: 'vidcloud.sbs' },
  { suffix: '.mysubsplay.com' },
  { exact: 'fetch.nexabloom.top' },
  { suffix: '.nexabloom.top' },
];

function isAllowedHost(hostname) {
  const h = String(hostname).toLowerCase();
  return ALLOWED.some((rule) => (
    (rule.exact && h === rule.exact)
    || (rule.suffix && h.endsWith(rule.suffix))
  ));
}

function isPrivateIp(addr) {
  if (addr === '::1' || addr === '127.0.0.1') return true;
  if (addr.startsWith('10.')) return true;
  if (addr.startsWith('192.168.')) return true;
  if (addr.startsWith('169.254.')) return true;
  if (addr.startsWith('fe80:') || addr.startsWith('fc') || addr.startsWith('fd')) return true;
  const m172 = addr.match(/^172\.(\d+)\./);
  if (m172) {
    const second = Number.parseInt(m172[1], 10);
    if (second >= 16 && second <= 31) return true;
  }
  return false;
}

async function assertPublicHost(hostname) {
  let addrs;
  try {
    addrs = await dns.lookup(hostname, { all: true, verbatim: true });
  } catch (e) {
    throw new Error(`Host tidak dapat di-resolve (${e.message}).`);
  }
  if (!addrs || addrs.length === 0) throw new Error('Host tidak dapat di-resolve.');
  if (addrs.some((a) => isPrivateIp(a.address))) {
    throw new Error('Host mengarah ke alamat privat.');
  }
}

function proxyUrlFor(absoluteUrl) {
  return `/proxy/hls?u=${encodeURIComponent(absoluteUrl)}`;
}

// Tulis ulang playlist .m3u8: URI relatif → absolut → lewat proxy.
// Menangani juga URI di dalam tag #EXT-X-KEY / #EXT-X-MAP.
function rewriteM3u8(text, baseUrl) {
  const lines = String(text).split(/\r?\n/);
  const out = lines.map((line) => {
    const trimmed = line.trim();
    if (!trimmed) return line;
    if (trimmed.startsWith('#')) {
      // #EXT-X-KEY:METHOD=...,URI="..." / #EXT-X-MAP:URI="..."
      return line.replace(/URI="([^"]+)"/g, (whole, uri) => {
        try {
          const abs = new URL(uri, baseUrl).href;
          return `URI="${proxyUrlFor(abs)}"`;
        } catch (e) {
          return whole;
        }
      });
    }
    try {
      const abs = new URL(trimmed, baseUrl).href;
      return proxyUrlFor(abs);
    } catch (e) {
      return line;
    }
  });
  return out.join('\n');
}

function refererFor(hostname) {
  const h = String(hostname).toLowerCase();
  if (h.endsWith('mysubsplay.com') || h.endsWith('nexabloom.top')) {
    return config.MEGAPLAY_REFERER;
  }
  return null;
}

async function handleProxy(req, res, query) {
  const u = query.get('u');
  if (!u) return sendErr(res, 400, 'Parameter u (URL) wajib diisi.');
  let target;
  try {
    target = new URL(u);
  } catch (e) {
    return sendErr(res, 400, 'URL tidak valid.');
  }
  if (target.protocol !== 'https:') {
    return sendErr(res, 400, 'Hanya URL https yang boleh di-proxy.');
  }
  if (!isAllowedHost(target.hostname)) {
    return sendErr(res, 403, 'Host tidak diizinkan.');
  }
  try {
    // eslint-disable-next-line no-await-in-loop
    await assertPublicHost(target.hostname);
  } catch (e) {
    logger.warn(`proxy: host ${target.hostname} ditolak (${e.message})`);
    return sendErr(res, 403, 'Host tidak diizinkan.');
  }

  const ip = ratelimit.clientIp(req);
  const limit = ratelimit.check(ip, 'proxy');
  if (!limit.allowed) {
    return sendErr(res, 429, 'Terlalu banyak permintaan, coba lagi nanti.', limit.retryAfterSec);
  }

  const headers = { 'User-Agent': config.USER_AGENT };
  const ref = refererFor(target.hostname);
  if (ref) headers.Referer = ref;

  let upstream;
  try {
    upstream = await fetch(target.href, {
      headers, signal: AbortSignal.timeout(20000),
    });
  } catch (e) {
    logger.warn(`proxy: upstream tidak terjangkau (${e.message})`);
    return sendErr(res, 502, 'Upstream tidak dapat dijangkau.');
  }
  if (!upstream.ok) {
    logger.warn(`proxy: upstream HTTP ${upstream.status}`);
    return sendErr(res, 502, 'Upstream tidak dapat dijangkau.');
  }
  const contentType = upstream.headers.get('content-type') || 'application/octet-stream';
  const isPlaylist = contentType.includes('mpegurl')
    || contentType.includes('application/x-mpegURL')
    || target.pathname.endsWith('.m3u8');

  if (isPlaylist) {
    let text = '';
    try {
      const buf = Buffer.from(await upstream.arrayBuffer());
      if (buf.length > MAX_PLAYLIST_BYTES) {
        return sendErr(res, 502, 'Playlist terlalu besar.');
      }
      text = buf.toString('utf8');
    } catch (e) {
      return sendErr(res, 502, 'Gagal membaca playlist.');
    }
    const rewritten = rewriteM3u8(text, target.href);
    const body = Buffer.from(rewritten, 'utf8');
    res.writeHead(200, {
      'Content-Type': 'application/vnd.apple.mpegurl',
      'Content-Length': body.length,
      'Cache-Control': 'no-store',
    });
    res.end(body);
    return;
  }

  // Biner (segmen .ts / VTT): streaming, tanpa buffering penuh.
  const webStream = upstream.body;
  if (!webStream) return sendErr(res, 502, 'Upstream tidak mengirim isi.');
  res.writeHead(200, {
    'Content-Type': contentType,
    'Cache-Control': 'no-store',
  });
  let bytes = 0;
  let aborted = false;
  const nodeStream = Readable.fromWeb(webStream);
  nodeStream.on('data', (chunk) => {
    bytes += chunk.length;
    if (bytes > MAX_BODY_BYTES && !aborted) {
      aborted = true;
      nodeStream.destroy(new Error('Ukuran respons melebihi batas.'));
    }
  });
  nodeStream.on('error', () => {
    if (!res.writableEnded) res.destroy();
  });
  nodeStream.pipe(res);
}

module.exports = { handleProxy, isAllowedHost, isPrivateIp, rewriteM3u8 };
