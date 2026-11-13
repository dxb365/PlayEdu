function word(value) {
  return [value >> 8, value & 255];
}

function displayRange(first, last) {
  return [first >> 4, (first & 15) << 4 | last >> 8, last & 255];
}

function picture(paletteIndex) {
  const field = Array.from({ length: 4 }, () => [0x01, 0x01, 0, 0]).flat();
  const controls = 4 + 2 * field.length;
  const commands = [
    3, 0, paletteIndex << 4,
    4, 0, 0xf0,
    5, ...displayRange(96, 223), ...displayRange(140, 147),
    6, ...word(4), ...word(4 + field.length),
    1, 0xff,
  ];
  const stop = controls + 4 + commands.length;
  return Uint8Array.from([
    ...word(stop + 6), ...word(controls), ...field, ...field,
    0, 0, ...word(stop), ...commands,
    ...word(900), ...word(stop), 2, 0xff,
  ]);
}

function sector(index, pictureBytes, first = true) {
  const pack = [0, 0, 1, 0xba, 0x44, 0, 4, 0, 4, 1, 0, 1, 0x89, 0xf8];
  const header = first ? [0x80, 0x80, 5, 0x21, 0, 1, 0, 1] : [0x80, 0, 0];
  const payload = [...header, 0x20 + index, ...pictureBytes];
  const data = new Uint8Array(2048);
  data.set([...pack, 0, 0, 1, 0xbd, ...word(payload.length), ...payload]);
  const padding = pack.length + 6 + payload.length;
  data.fill(0xff, padding);
  data.set([0, 0, 1, 0xbe, ...word(data.length - padding - 6)], padding);
  return data;
}

export function createVobSubFixture() {
  const english = picture(1);
  const chinese = picture(2);
  const sub = new Uint8Array(3 * 2048);
  sub.set(sector(0, english.subarray(0, 17)), 0);
  sub.set(sector(1, chinese), 2048);
  sub.set(sector(0, english.subarray(17), false), 4096);
  const palette = ['000000', 'ff0000', '00ff00', ...Array(13).fill('000000')];
  const indexText = [
    '# VobSub index file, v7 (do not modify this line!)',
    'size: 320x180',
    `palette: ${palette.join(', ')}`,
    'langidx: 0',
    'id: en, index: 0',
    'timestamp: 00:00:00:500, filepos: 000000000',
    'id: zh, index: 1',
    'timestamp: 00:00:00:500, filepos: 000000800',
    '',
  ].join('\n');
  return { indexText, sub };
}
