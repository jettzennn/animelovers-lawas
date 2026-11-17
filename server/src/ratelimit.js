'use strict';
// Rate limiting per IP + auto-blokir bertahap (pola skill Backend).
// - Blokir berdasarkan perilaku, bukan identitas klien — User-Agent
//   gampang dipalsu, jadi tidak pernah dipakai sebagai sinyal.
// - X-Forwarded-For TIDAK dipercaya (server jalan di localhost
//   Termux; IP diambil dari socket).
// - IP bersama (CGNAT): tidak ada blokir permanen; blokir naik
//   bertahap 5 menit → 1 jam → 24 jam, dan kedaluwarsa sendiri.
// - /health tidak pernah dibatasi dan tidak pernah diblokir.
// - Catatan pelanggaran disimpan di SQLite (bertahan saat restart).
const config = require('./config');
const db = require('./db');
const logger = require('./logger');

// Jendela hitung di memori: key `${ip}:${bucket}` -> array timestamp ms.
const windows = new Map();

function prune(key, windowMs) {
  const now = Date.now();
  const arr = windows.get(key);
  if (!arr) return [];
  const kept = arr.filter((t) => now - t < windowMs);
  if (kept.length === 0) windows.delete(key);
  else windows.set(key, kept);
  return kept;
}

// Durasi blokir bertahap berdasarkan jumlah pelanggaran (dalam 10 menit terakhir).
function blockDurationMs(violations) {
  if (violations >= 20) return 24 * 60 * 60 * 1000;
  if (violations >= 10) return 60 * 60 * 1000;
  if (violations >= 5) return 5 * 60 * 1000;
  return 0;
}

function clientIp(req) {
  const addr = req.socket && req.socket.remoteAddress ? req.socket.remoteAddress : 'unknown';
  // Normalisasi IPv6-mapped IPv4 (::ffff:127.0.0.1 -> 127.0.0.1).
  return addr.startsWith('::ffff:') ? addr.slice(7) : addr;
}

// Catat satu pelanggaran (429) untuk IP ini. Mengembalikan detik blokir
// yang berlaku (0 = belum diblokir).
function recordViolation(ip) {
  const now = Date.now();
  const row = db.getBlock(ip);
  let violations = 1;
  if (row) {
    // Pelanggaran lama (> 10 menit) tidak dihitung — mulai dari 1 lagi.
    violations = (now - row.updated_at * 1000 < 10 * 60 * 1000)
      ? row.violations + 1 : 1;
  }
  const dur = blockDurationMs(violations);
  const blockedUntil = dur > 0 ? now + dur : (row ? row.blocked_until : 0);
  db.upsertBlock(ip, violations, blockedUntil);
  if (dur > 0) {
    logger.warn(`IP ${ip} diblokir ${Math.round(dur / 60000)} menit (pelanggaran ke-${violations}).`);
  }
  return dur > 0 ? Math.ceil(dur / 1000) : 0;
}

// Sisa detik blokir untuk IP ini (0 = tidak diblokir).
function blockedSeconds(ip) {
  const row = db.getBlock(ip);
  if (!row || !row.blocked_until) return 0;
  const remain = Math.ceil((row.blocked_until - Date.now()) / 1000);
  if (remain <= 0) {
    db.upsertBlock(ip, 0, 0);
    return 0;
  }
  return remain;
}

// Periksa batas untuk satu bucket. Mengembalikan:
// { allowed: true } atau { allowed: false, retryAfterSec }.
// Pemakaian normal tidak kena; pelanggaran dicatat dan bisa memicu blokir.
function check(ip, bucket) {
  const blocked = blockedSeconds(ip);
  if (blocked > 0) return { allowed: false, retryAfterSec: blocked, blocked: true };
  const limit = config.RATE_LIMITS[bucket];
  if (!limit) return { allowed: true };
  const key = `${ip}:${bucket}`;
  const kept = prune(key, limit.windowMs);
  if (kept.length >= limit.max) {
    const retryAfterSec = Math.max(
      1, Math.ceil((kept[0] + limit.windowMs - Date.now()) / 1000),
    );
    recordViolation(ip);
    return { allowed: false, retryAfterSec, blocked: false };
  }
  kept.push(Date.now());
  windows.set(key, kept);
  return { allowed: true };
}

module.exports = { clientIp, check, blockedSeconds, recordViolation, blockDurationMs };
