'use strict';
// SQLite via modul bawaan Node (node:sqlite, Node >= 22.5).
// Tanpa dependensi npm — tidak ada better-sqlite3 / node-gyp yang
// rawan gagal dipasang di Termux HP.
//
// Tabel:
// - subtitle_cache: VTT Indonesia (TEKS saja — BUKAN URL).
//   URL upstream ber-token (?t=) TIDAK PERNAH disimpan di DB
//   (TTL token belum diukur; ambil segar per request).
// - ip_blocks: catatan pelanggaran rate limit (bertahan saat restart).
const fs = require('node:fs');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');
const config = require('./config');
const logger = require('./logger');

let db = null;

function nowSec() {
  return Math.floor(Date.now() / 1000);
}

function init(dbFile) {
  const file = dbFile || path.join(config.DATA_DIR, 'subtitle-cache.db');
  fs.mkdirSync(path.dirname(file), { recursive: true });
  db = new DatabaseSync(file);
  db.exec(`
    CREATE TABLE IF NOT EXISTS subtitle_cache (
      ani_id INTEGER NOT NULL,
      ep INTEGER NOT NULL,
      source TEXT NOT NULL CHECK (source IN ('native', 'translated')),
      status TEXT NOT NULL CHECK (status IN ('partial', 'complete')),
      vtt_text TEXT NOT NULL,
      en_vtt_text TEXT NOT NULL DEFAULT '',
      translated_json TEXT NOT NULL DEFAULT '[]',
      total_cues INTEGER NOT NULL DEFAULT 0,
      translated_cues INTEGER NOT NULL DEFAULT 0,
      en_hash TEXT,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL,
      PRIMARY KEY (ani_id, ep)
    );
    CREATE TABLE IF NOT EXISTS ip_blocks (
      ip TEXT PRIMARY KEY,
      violations INTEGER NOT NULL DEFAULT 0,
      blocked_until INTEGER NOT NULL DEFAULT 0,
      updated_at INTEGER NOT NULL
    );
  `);
  logger.info(`SQLite dibuka: ${file}`);
  return db;
}

function get() {
  if (!db) init();
  return db;
}

function getSubtitle(aniId, ep) {
  return get().prepare(
    'SELECT * FROM subtitle_cache WHERE ani_id = ? AND ep = ?',
  ).get(aniId, ep) || null;
}

function upsertSubtitle(row) {
  const t = nowSec();
  get().prepare(`
    INSERT INTO subtitle_cache
      (ani_id, ep, source, status, vtt_text, en_vtt_text, translated_json,
       total_cues, translated_cues, en_hash, created_at, updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(ani_id, ep) DO UPDATE SET
      source = excluded.source,
      status = excluded.status,
      vtt_text = excluded.vtt_text,
      en_vtt_text = excluded.en_vtt_text,
      translated_json = excluded.translated_json,
      total_cues = excluded.total_cues,
      translated_cues = excluded.translated_cues,
      en_hash = excluded.en_hash,
      updated_at = excluded.updated_at
  `).run(
    row.aniId, row.ep, row.source, row.status, row.vttText,
    row.enVttText || '', row.translatedJson || '[]',
    row.totalCues || 0, row.translatedCues || 0, row.enHash || null,
    row.createdAt || t, t,
  );
}

function deleteSubtitle(aniId, ep) {
  get().prepare('DELETE FROM subtitle_cache WHERE ani_id = ? AND ep = ?').run(aniId, ep);
}

function getBlock(ip) {
  return get().prepare('SELECT * FROM ip_blocks WHERE ip = ?').get(ip) || null;
}

function upsertBlock(ip, violations, blockedUntil) {
  get().prepare(`
    INSERT INTO ip_blocks (ip, violations, blocked_until, updated_at)
    VALUES (?, ?, ?, ?)
    ON CONFLICT(ip) DO UPDATE SET
      violations = excluded.violations,
      blocked_until = excluded.blocked_until,
      updated_at = excluded.updated_at
  `).run(ip, violations, blockedUntil, nowSec());
}

function close() {
  if (db) {
    db.close();
    db = null;
  }
}

module.exports = {
  init, get, getSubtitle, upsertSubtitle, deleteSubtitle,
  getBlock, upsertBlock, close, nowSec,
};
