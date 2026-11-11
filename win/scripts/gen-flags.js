// Vygeneruje PNG vlajky pro kontextové menu ikony v liště (assets/flags/xx.png, @1.5x, @2x).
const fs = require('fs');
const path = require('path');
const { Resvg } = require('@resvg/resvg-js');
const { LANGS } = require('../src/i18n.js');
const out = path.join(__dirname, '..', 'assets', 'flags');
fs.mkdirSync(out, { recursive: true });
for (const { code, flag } of LANGS) {
  const inner = flag.replace('<svg ', '<svg x="0" y="2.5" width="16" height="11" ');
  const wrapped = `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">${inner}<rect x="0.25" y="2.75" width="15.5" height="10.5" fill="none" stroke="#000" stroke-opacity=".25" stroke-width=".5"/></svg>`;
  for (const [sfx, zoom] of [['', 1], ['@1.5x', 1.5], ['@2x', 2]]) {
    const png = new Resvg(wrapped, { fitTo: { mode: 'zoom', value: zoom } }).render().asPng();
    fs.writeFileSync(path.join(out, code + sfx + '.png'), png);
  }
}
console.log('flags:', LANGS.length);
