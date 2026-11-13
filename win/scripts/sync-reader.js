// Zkopíruje sdílenou čtečku e-knih (/reader v kořeni repozitáře) do src/reader (balí se s aplikací).
'use strict';
const fs = require('fs');
const path = require('path');
const from = path.join(__dirname, '..', '..', 'reader');
const to = path.join(__dirname, '..', 'src', 'reader');
fs.rmSync(to, { recursive: true, force: true });
(function copy(a, b) {
  fs.mkdirSync(b, { recursive: true });
  for (const n of fs.readdirSync(a)) {
    if (n === 'test') continue;
    const s = path.join(a, n), d = path.join(b, n);
    if (fs.statSync(s).isDirectory()) copy(s, d); else fs.copyFileSync(s, d);
  }
})(from, to);
console.log('reader ->', to);
