// Ikony aplikace a lišty z originálního loga Audiobookshelf (assets/abs-logo.svg, z repozitáře advplyr/audiobookshelf).
const fs = require('fs');
const path = require('path');
const { Resvg } = require('@resvg/resvg-js');
const A = path.join(__dirname, '..', 'assets');
const svg = fs.readFileSync(path.join(A, 'abs-logo.svg'), 'utf8');
const png = (s) => new Resvg(svg, { fitTo: { mode: 'width', value: s } }).render().asPng();

// ICO s PNG obrázky (podporováno od Windows Vista)
function ico(sizes) {
  const imgs = sizes.map((s) => ({ s, d: png(s) }));
  const head = Buffer.alloc(6 + 16 * imgs.length);
  head.writeUInt16LE(0, 0); head.writeUInt16LE(1, 2); head.writeUInt16LE(imgs.length, 4);
  let off = head.length;
  imgs.forEach(({ s, d }, i) => {
    const e = 6 + i * 16;
    head.writeUInt8(s >= 256 ? 0 : s, e); head.writeUInt8(s >= 256 ? 0 : s, e + 1);
    head.writeUInt8(0, e + 2); head.writeUInt8(0, e + 3);
    head.writeUInt16LE(1, e + 4); head.writeUInt16LE(32, e + 6);
    head.writeUInt32LE(d.length, e + 8); head.writeUInt32LE(off, e + 12);
    off += d.length;
  });
  return Buffer.concat([head, ...imgs.map((x) => x.d)]);
}
fs.writeFileSync(path.join(A, 'app.ico'), ico([16, 20, 24, 32, 40, 48, 64, 128, 256]));
fs.writeFileSync(path.join(A, 'notify-icon.ico'), ico([16, 20, 24, 32, 40, 48, 64]));
fs.writeFileSync(path.join(A, 'icon.png'), png(256));
console.log('icons ok');
