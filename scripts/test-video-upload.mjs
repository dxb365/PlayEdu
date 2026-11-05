import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { createRequire } from 'node:module';

const require = createRequire(new URL('../playedu-admin/package.json', import.meta.url));
const ts = require('typescript');
const compile = source => ts.transpileModule(source, { compilerOptions: {
  target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022,
} }).outputText;
const limits = await import('data:text/javascript;base64,' + Buffer.from(compile(await fs.readFile(
  new URL('../playedu-admin/src/js/video-upload-limits.ts', import.meta.url), 'utf8'))).toString('base64'));
const file = { name: '边界测试.mp4', type: 'video/mp4', size: 10 * 1024 ** 3 };
assert.equal(limits.MAX_VIDEO_UPLOAD_FILES, 100);
assert.equal(limits.VIDEO_UPLOAD_CONCURRENCY, 10);
assert.equal(limits.videoUploadError(file, 100), null);
assert.match(limits.videoUploadError(file, 101), /100/);
assert.match(limits.videoUploadError({ ...file, size: file.size + 1 }, 1), /10GB/);
assert.match(limits.videoUploadError({ ...file, type: 'image/png' }, 1), /mp4/);

let signed = [], sent = [];
globalThis.uploadAxios = { create(options) {
  assert.equal(options.timeout, 300000);
  return { async put(url, body) { sent.push({ url, part: body.part }); } };
} };
globalThis.signUploadPart = async (uploadId, filename, index) => {
  signed.push(index);
  return { data: { url: 'https://storage.invalid/part/' + index } };
};
globalThis.File = class { constructor(parts, name) { this.part = parts[0]; this.name = name; } };
const source = (await fs.readFile(new URL('../playedu-admin/src/js/minio-upload-chunk.ts', import.meta.url), 'utf8'))
  .replace(/import axios[^\n]*\n/, 'const axios = globalThis.uploadAxios;\n')
  .replace(/import \{ minioPreSignUrl \}[^\n]*\n/, 'const minioPreSignUrl = globalThis.signUploadPart;\n');
const { UploadChunk } = await import('data:text/javascript;base64,' + Buffer.from(compile(source)).toString('base64'));
for (const size of [10 * 1024 ** 3, 10 * 1024 ** 3 - 1]) {
  signed = []; sent = [];
  const input = { name: file.name, size, slice(start, end) { return { start, end: Math.min(end, size) }; } };
  const upload = new UploadChunk(input, 'fixture-upload', 'fixture.mp4');
  await new Promise((resolve, reject) => {
    upload.on('success', resolve);
    upload.on('error', reject);
    upload.start();
  });
  assert.equal(signed.length, 2048);
  assert.deepEqual(signed, Array.from({ length: 2048 }, (_, index) => index + 1));
  assert.equal(sent.length, 2048);
  assert.equal(sent[0].part.start, 0);
  assert.equal(sent.at(-1).part.end, size);
  assert.equal(sent.at(-1).part.start, 2047 * 5 * 1024 ** 2);
  for (let i = 1; i < sent.length; i++) assert.equal(sent[i].part.start, sent[i-1].part.end);
  assert.equal(upload.getUploadStatus(), 7);
}
console.log('Video upload tests passed: 100/101 files, 10GiB byte boundary, concurrency=10, 2048 contiguous parts without allocating large files.');
