'use strict';
// Log terstruktur minimal: [waktu] LEVEL pesan.
// JANGAN PERNAH mencatat token, URL upstream ber-token (?t=), atau
// rahasia apa pun — pemanggil hanya mencatat ID + status.
function stamp() {
  return new Date().toISOString();
}

function info(msg) {
  console.log(`[${stamp()}] INFO ${msg}`);
}

function warn(msg) {
  console.log(`[${stamp()}] WARN ${msg}`);
}

function error(msg) {
  console.error(`[${stamp()}] ERROR ${msg}`);
}

module.exports = { info, warn, error };
