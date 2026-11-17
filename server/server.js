'use strict';
// Entry point backend kecil AnimeLovers Revival.
// Satu proses Node, nol dependensi (node:http + node:sqlite),
// supaya pasti jalan di Termux HP tanpa node-gyp.
// Jalankan: node server.js  →  http://localhost:3100
//
// Endpoint:
//   GET /health
//   GET /resolve/{ani_id}/{ep}
//   GET /subtitle/id/{ani_id}/{ep}
//   GET /proxy/hls?u=<url>
//
// Catatan shutdown: server ini memakai graceful shutdown — SIGTERM /
// SIGINT menutup koneksi lalu keluar. Bila keep-alive menggantung,
// pakai SIGKILL pada PID persisnya (jangan pkill pola lebar).
const http = require('node:http');
const config = require('./src/config');
const logger = require('./src/logger');
const db = require('./src/db');
const ratelimit = require('./src/ratelimit');
const { sendJson, sendErr } = require('./src/routes/common');
const { handleHealth } = require('./src/routes/health');
const { handleResolve } = require('./src/routes/resolve');
const { handleSubtitle } = require('./src/routes/subtitle');
const { handleProxy } = require('./src/routes/proxy');

process.on('uncaughtException', (e) => {
  logger.error(`uncaughtException (server tetap jalan): ${e && e.stack ? e.stack : String(e)}`);
});
process.on('unhandledRejection', (reason) => {
  logger.error(`unhandledRejection (server tetap jalan): ${reason && reason.stack ? reason.stack : String(reason)}`);
});

function setSecurityHeaders(res) {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
}

async function route(req, res) {
  const url = new URL(req.url, 'http://localhost');
  const path = url.pathname;

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }
  if (req.method !== 'GET') {
    return sendErr(res, 405, 'Metode tidak diizinkan (hanya GET).');
  }

  if (path === '/health') return handleHealth(req, res);

  let m = path.match(/^\/resolve\/([^/]+)\/([^/]+)$/);
  if (m) return handleResolve(req, res, m[1], m[2], url.searchParams);

  m = path.match(/^\/subtitle\/id\/([^/]+)\/([^/]+)$/);
  if (m) return handleSubtitle(req, res, m[1], m[2], url.searchParams);

  if (path === '/proxy/hls') return handleProxy(req, res, url.searchParams);

  return sendErr(res, 404, 'Endpoint tidak ditemukan.');
}

const server = http.createServer((req, res) => {
  const started = Date.now();
  setSecurityHeaders(res);
  // Log: path saja, TANPA query string — query /proxy/hls mengandung
  // URL upstream ber-token, dan query lain bisa mengandung ID sensitif.
  const logPath = new URL(req.url, 'http://localhost').pathname;
  route(req, res).catch((e) => {
    logger.error(`route ${logPath} gagal (${e && e.stack ? e.stack : String(e)})`);
    if (!res.writableEnded) sendErr(res, 500, 'Terjadi kesalahan di server.');
  }).finally(() => {
    const ms = Date.now() - started;
    logger.info(`${req.method} ${logPath} ${res.statusCode} ${ms}ms`);
  });
});

db.init();

server.listen(config.PORT, config.HOST, () => {
  logger.info(`animelovers-lawas-server jalan di http://${config.HOST}:${config.PORT}`);
  logger.info('Endpoint: /health, /resolve/{ani_id}/{ep}, /subtitle/id/{ani_id}/{ep}, /proxy/hls');
});

let shuttingDown = false;
function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  logger.info(`Menerima ${signal} — menutup server...`);
  server.close(() => {
    db.close();
    logger.info('Server berhenti.');
    process.exit(0);
  });
  if (typeof server.closeAllConnections === 'function') {
    server.closeAllConnections();
  }
  setTimeout(() => {
    logger.warn('Shutdown melebihi 5 detik — keluar paksa.');
    process.exit(0);
  }, 5000).unref();
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
