'use strict';
// Helper respons untuk semua route: format error konsisten
// { ok: false, error: "<pesan Bahasa Indonesia>" } dan JSON sukses
// { ok: true, data: ... }.
function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function sendErr(res, status, message, retryAfterSec) {
  const headers = {};
  if (retryAfterSec) headers['Retry-After'] = String(retryAfterSec);
  const body = JSON.stringify({ ok: false, error: message });
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
    ...headers,
  });
  res.end(body);
}

// Validasi param path integer: 1..max. Mengembalikan null bila tidak valid.
function parseIntParam(raw, max) {
  if (typeof raw !== 'string' || !/^\d+$/.test(raw)) return null;
  const n = Number.parseInt(raw, 10);
  if (!Number.isSafeInteger(n) || n < 1 || n > max) return null;
  return n;
}

module.exports = { sendJson, sendErr, parseIntParam };
