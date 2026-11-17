'use strict';
// GET /health — cek hidup (untuk Splash + diagnosa).
// Tidak pernah di-rate-limit dan tidak pernah diblokir.
const { sendJson } = require('./common');

function handleHealth(req, res) {
  sendJson(res, 200, {
    ok: true,
    service: 'animelovers-lawas-server',
    uptime_s: Math.floor(process.uptime()),
  });
}

module.exports = { handleHealth };
