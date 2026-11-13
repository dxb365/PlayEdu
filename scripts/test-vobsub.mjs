import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { createVobSubFixture } from './vobsub-fixture.mjs';

const require = createRequire(new URL('../playedu-pc/package.json', import.meta.url));
const ts = require('typescript');
const pc = await fs.readFile(new URL('../playedu-pc/src/utils/vobsub.ts', import.meta.url), 'utf8');
const mobile = await fs.readFile(new URL('../playedu-h5/src/utils/vobsub.ts', import.meta.url), 'utf8');
assert.equal(pc, mobile);
const compiled = ts.transpileModule(pc, { compilerOptions: {
  target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022,
} }).outputText;
const { decodeVobSub } = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));
const { indexText, sub } = createVobSubFixture();
const english = decodeVobSub(indexText, sub.buffer, 0);
const chinese = decodeVobSub(indexText, sub.buffer, 1);
assert.deepEqual([english.width, english.height, english.cues.length], [320, 180, 1]);
assert.deepEqual([chinese.width, chinese.height, chinese.cues.length], [320, 180, 1]);
const [cue] = english.cues;
assert.deepEqual([cue.start, cue.end, cue.x, cue.y, cue.width, cue.height], [0.5, 10.74, 96, 140, 128, 8]);
assert.equal(cue.rgba, cue.rgba);
assert.deepEqual([...cue.rgba.subarray(63 * 4, 65 * 4)], [255, 0, 0, 255, 0, 0, 0, 0]);
assert.deepEqual([...cue.rgba.subarray(128 * 4, 128 * 4 + 4)], [255, 0, 0, 255]);
assert.deepEqual([...chinese.cues[0].rgba.subarray(0, 4)], [0, 255, 0, 255]);
assert.throws(() => decodeVobSub(indexText, sub.buffer, 32), /超出读取范围/);
assert.throws(() => decodeVobSub(indexText, sub.buffer, 2), /没有可播放/);
assert.throws(() => decodeVobSub(indexText, sub.buffer.slice(0, 2048), 0), /没有可播放/);
assert.throws(() => decodeVobSub(indexText.replace('320x180', '9999x9999'), sub.buffer, 0), /尺寸超出范围/);

const malformed = sub.slice();
malformed[2048 + 14 + 6 + 8 + 1 + 4] = 0x03;
malformed[2048 + 14 + 6 + 8 + 1 + 5] = 0xff;
assert(decodeVobSub(indexText, malformed.buffer, 1).cues[0].rgba.every(byte => byte === 0));

const shifted = indexText.replace('id: en, index: 0', 'id: en, index: 0\ndelay: +00:00:00:250');
assert.equal(decodeVobSub(shifted, sub.buffer, 0).cues[0].start, 0.75);

for (const idxPath of process.argv.slice(2)) {
  const idx = await fs.readFile(idxPath, 'utf8');
  const source = await fs.readFile(idxPath.replace(/\.idx$/i, '.sub'));
  const bytes = Uint8Array.from(source).buffer;
  const streams = new Set([...idx.matchAll(/^\s*id:\s*[^,]+,\s*index:\s*(\d+)\b/gim)].map(match => Number(match[1])));
  for (const stream of streams) {
    const track = decodeVobSub(idx, bytes, stream);
    let visible = 0;
    for (const item of track.cues) {
      if (item.rgba.some((byte, index) => index % 4 === 3 && byte > 0)) visible++;
    }
    assert(visible > 0, 'Real VobSub sample should render visible images');
    console.log(`${path.basename(idxPath)} track ${stream}: ${track.cues.length} cues, ${visible} visible`);
  }
}
console.log('VobSub tests passed');
