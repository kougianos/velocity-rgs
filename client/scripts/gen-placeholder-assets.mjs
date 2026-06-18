/* eslint-env node */
/**
 * Generates placeholder symbol PNGs (128x128 solid color) under
 * `public/assets/symbols/<symbolId>.png` per `client-requirements.md` Appendix G.
 *
 * Real art ships in M9; until then these solid colors let the asset pipeline
 * and Pixi sprite wiring exercise end-to-end. Run with:
 *
 *   node scripts/gen-placeholder-assets.mjs
 */

import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.resolve(__dirname, '..', 'public', 'assets', 'symbols');

const SYMBOLS = [
  { id: 1, name: 'ACE', rgb: [0xe7, 0x4c, 0x3c] },
  { id: 2, name: 'KING', rgb: [0x34, 0x98, 0xdb] },
  { id: 3, name: 'QUEEN', rgb: [0x9b, 0x59, 0xb6] },
  { id: 4, name: 'JACK', rgb: [0xf1, 0xc4, 0x0f] },
  { id: 5, name: 'TEN', rgb: [0x2e, 0xcc, 0x71] },
  { id: 6, name: 'NINE', rgb: [0x8d, 0x6e, 0x63] },
  { id: 7, name: 'STATUE', rgb: [0xa1, 0x88, 0x7f] },
  { id: 8, name: 'MASK', rgb: [0xd3, 0x5a, 0x00] },
  { id: 9, name: 'WILD', rgb: [0x10, 0x10, 0x10] },
  { id: 10, name: 'BONUS_A', rgb: [0x00, 0xac, 0xc1] },
  { id: 11, name: 'BONUS_B', rgb: [0x69, 0xf0, 0xae] },
  { id: 12, name: 'SCATTER', rgb: [0xe6, 0x7e, 0x22] },
];

const SIZE = 128;
const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

const crcTable = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(buf) {
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    crc = crcTable[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crcBuf]);
}

function makeSolidPng(width, height, [r, g, b]) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // color type RGB
  ihdr[10] = 0; // compression
  ihdr[11] = 0; // filter
  ihdr[12] = 0; // interlace

  const rowLen = width * 3;
  const raw = Buffer.alloc((rowLen + 1) * height);
  for (let y = 0; y < height; y++) {
    const off = y * (rowLen + 1);
    raw[off] = 0; // filter: None
    for (let x = 0; x < width; x++) {
      const p = off + 1 + x * 3;
      raw[p] = r;
      raw[p + 1] = g;
      raw[p + 2] = b;
    }
  }
  const idat = deflateSync(raw);

  return Buffer.concat([
    PNG_SIGNATURE,
    chunk('IHDR', ihdr),
    chunk('IDAT', idat),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

mkdirSync(OUT_DIR, { recursive: true });

for (const s of SYMBOLS) {
  const buf = makeSolidPng(SIZE, SIZE, s.rgb);
  const file = path.join(OUT_DIR, `${s.id}.png`);
  writeFileSync(file, buf);
  process.stdout.write(`wrote ${file} (${buf.length}B)\n`);
}
