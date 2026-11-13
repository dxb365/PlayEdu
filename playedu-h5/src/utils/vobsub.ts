export interface VobSubCue {
  start: number;
  end: number;
  x: number;
  y: number;
  width: number;
  height: number;
  readonly rgba: Uint8ClampedArray;
}

export interface VobSubTrack {
  width: number;
  height: number;
  cues: VobSubCue[];
}

interface IndexEntry {
  time: number;
  offset: number;
}

interface Picture {
  packet: Uint8Array;
  control: number;
  evenField: number;
  oddField: number;
  x: number;
  y: number;
  width: number;
  height: number;
  colors: number[];
  opacity: number[];
  start: number;
  end: number | undefined;
}

const MAX_INDEX_LENGTH = 4 * 1024 * 1024;
const MAX_SUB_LENGTH = 32 * 1024 * 1024;
const MAX_CUES = 12000;
const MAX_FRAME_WIDTH = 1920;
const MAX_FRAME_HEIGHT = 1080;
const MAX_PACKET_SPAN = 1024 * 1024;
const CONTROL_TIME_UNIT = 1024 / 90000;

function word(bytes: Uint8Array, offset: number) {
  return (bytes[offset] << 8) | bytes[offset + 1];
}

function clock(hours: string, minutes: string, seconds: string, millis: string) {
  return Number(hours) * 3600 + Number(minutes) * 60 + Number(seconds) + Number(millis) / 1000;
}

function parseIndex(indexText: string, streamIndex: number, dataSize: number) {
  let width = 720;
  let height = 480;
  let palette = [0, 0xffffff, 0xaaaaaa, 0x555555, ...Array<number>(12).fill(0)];
  let alpha = 1;
  let stream = 0;
  let delay = 0;
  const entries: IndexEntry[] = [];
  for (const rawLine of indexText.split(/\r?\n|\r/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const dimensions = /^size:\s*(\d{1,4})\s*x\s*(\d{1,4})\s*$/i.exec(line);
    if (dimensions) {
      width = Number(dimensions[1]);
      height = Number(dimensions[2]);
      continue;
    }
    const colors = /^palette:\s*(.+)$/i.exec(line);
    if (colors) {
      const values = colors[1].split(',').map(value => value.trim());
      if (values.length === 16 && values.every(value => /^[0-9a-f]{6}$/i.test(value))) {
        palette = values.map(value => parseInt(value, 16));
      }
      continue;
    }
    const opacity = /^alpha:\s*(\d{1,3})%$/i.exec(line);
    if (opacity) {
      alpha = Math.min(Number(opacity[1]), 100) / 100;
      continue;
    }
    const track = /^id:\s*[^,]+,\s*index:\s*(\d{1,2})\b/i.exec(line);
    if (track) {
      stream = Number(track[1]);
      continue;
    }
    const timeShift = /^delay:\s*([+-]?)(\d{1,4}):([0-5]\d):([0-5]\d):(\d{3})\s*$/i.exec(line);
    if (timeShift) {
      delay = (timeShift[1] === '-' ? -1 : 1) * clock(timeShift[2], timeShift[3], timeShift[4], timeShift[5]);
      continue;
    }
    if (stream !== streamIndex) continue;
    const entry = /^timestamp:\s*(-?)(\d{1,4}):([0-5]\d):([0-5]\d):(\d{3}),\s*filepos:\s*([\da-f]{1,12})\b/i.exec(line);
    if (!entry) continue;
    const offset = parseInt(entry[6], 16);
    if (offset >= dataSize) continue;
    if (entries.length >= MAX_CUES) throw new Error('VobSub 字幕条目过多');
    const time = (entry[1] === '-' ? -1 : 1) * clock(entry[2], entry[3], entry[4], entry[5]) + delay;
    entries.push({ time, offset });
  }
  if (width < 1 || height < 1 || width > MAX_FRAME_WIDTH || height > MAX_FRAME_HEIGHT) {
    throw new Error('VobSub 字幕尺寸超出范围');
  }
  return { width, height, palette, alpha, entries };
}

function pesPayload(bytes: Uint8Array, start: number, end: number) {
  if (start >= end) return end;
  if ((bytes[start] & 0xc0) === 0x80) {
    return start + 3 <= end ? start + 3 + bytes[start + 2] : end;
  }
  let offset = start;
  while (offset < end && bytes[offset] === 0xff) offset++;
  if ((bytes[offset] & 0xc0) === 0x40) offset += 2;
  const marker = bytes[offset];
  if ((marker & 0xf0) === 0x20) return offset + 5;
  if ((marker & 0xf0) === 0x30) return offset + 10;
  return marker === 0x0f ? offset + 1 : end;
}

function readPicturePacket(bytes: Uint8Array, start: number, end: number, streamIndex: number) {
  const chunks: Uint8Array[] = [];
  let length = 0;
  let pictureLength = 0;
  let offset = start;
  while (offset + 6 <= end) {
    if (bytes[offset] !== 0 || bytes[offset + 1] !== 0 || bytes[offset + 2] !== 1) {
      offset++;
      continue;
    }
    const code = bytes[offset + 3];
    if (code === 0xba) {
      const mpeg2 = (bytes[offset + 4] & 0xc0) === 0x40;
      if (mpeg2 && offset + 14 > end) break;
      offset += mpeg2 ? 14 + (bytes[offset + 13] & 7) : 12;
      continue;
    }
    if (code < 0xbb) {
      offset += 4;
      continue;
    }
    const endOfPacket = offset + 6 + word(bytes, offset + 4);
    if (endOfPacket > end) break;
    if (code === 0xbd) {
      const payload = pesPayload(bytes, offset + 6, endOfPacket);
      if (payload + 1 < endOfPacket && bytes[payload] === 0x20 + streamIndex) {
        const chunk = bytes.subarray(payload + 1, endOfPacket);
        if (chunks.length === 0 && chunk.length >= 2) pictureLength = word(chunk, 0);
        if (length === 1) pictureLength = (chunks[0][0] << 8) | chunk[0];
        chunks.push(chunk);
        length += chunk.length;
        if (length >= 2 && pictureLength < 8) return undefined;
        if (pictureLength && length >= pictureLength) {
          if (chunks.length === 1) return chunk.subarray(0, pictureLength);
          const packet = new Uint8Array(pictureLength);
          let written = 0;
          for (const part of chunks) {
            const piece = part.subarray(0, pictureLength - written);
            packet.set(piece, written);
            written += piece.length;
          }
          return packet;
        }
      }
    }
    offset = endOfPacket;
  }
  return undefined;
}

function nibbles(value: number) {
  return [value & 15, (value >> 4) & 15, (value >> 8) & 15, value >> 12];
}

function readPicture(packet: Uint8Array, frameWidth: number, frameHeight: number): Picture | undefined {
  const control = word(packet, 2);
  if (control < 4 || control + 4 >= packet.length) return undefined;
  let start = 0;
  let end: number | undefined;
  let x = 0;
  let y = 0;
  let width = 0;
  let height = 0;
  let colors = [0, 1, 2, 3];
  let opacity = [0, 15, 15, 15];
  let evenField = -1;
  let oddField = -1;
  for (let sequence = control; sequence + 4 < packet.length;) {
    const time = word(packet, sequence) * CONTROL_TIME_UNIT;
    const next = word(packet, sequence + 2);
    if (next < sequence || (next > sequence && (next < sequence + 5 || next + 4 >= packet.length))) {
      return undefined;
    }
    const limit = next === sequence ? packet.length : next;
    for (let offset = sequence + 4; offset < limit;) {
      const command = packet[offset++];
      if (command === 0xff) break;
      if (command === 0 || command === 1) {
        start = time;
      } else if (command === 2) {
        end = time;
      } else if (command === 3 || command === 4) {
        if (offset + 2 > limit) return undefined;
        const values = nibbles(word(packet, offset));
        if (command === 3) colors = values;
        else opacity = values;
        offset += 2;
      } else if (command === 5) {
        if (offset + 6 > limit) return undefined;
        x = word(packet, offset) >> 4;
        y = word(packet, offset + 3) >> 4;
        width = (word(packet, offset + 1) & 0xfff) - x + 1;
        height = (word(packet, offset + 4) & 0xfff) - y + 1;
        offset += 6;
      } else if (command === 6) {
        if (offset + 4 > limit) return undefined;
        evenField = word(packet, offset);
        oddField = word(packet, offset + 2);
        offset += 4;
      } else {
        break;
      }
    }
    if (next === sequence) break;
    sequence = next;
  }
  if (width < 1 || height < 1 || x + width > frameWidth || y + height > frameHeight ||
      evenField < 4 || evenField >= control || (height > 1 && (oddField < 4 || oddField >= control))) {
    return undefined;
  }
  return { packet, control, evenField, oddField, x, y, width, height, colors, opacity, start, end };
}

function drawField(picture: Picture, parity: number, start: number, end: number,
    colors: number[][], rgba: Uint8ClampedArray) {
  let position = start * 2;
  const limit = end * 2;
  const readNibble = () => {
    const byte = picture.packet[position >> 1];
    return (position++ & 1) === 0 ? byte >> 4 : byte & 15;
  };
  for (let y = parity; y < picture.height; y += 2) {
    for (let x = 0; x < picture.width;) {
      if (position >= limit) return false;
      let code = readNibble();
      if (code < 4) {
        if (position >= limit) return false;
        code = (code << 4) | readNibble();
        if (code < 0x10) {
          if (position >= limit) return false;
          code = (code << 4) | readNibble();
          if (code < 0x40) {
            if (position >= limit) return false;
            code = (code << 4) | readNibble();
          }
        }
      }
      const run = (code >> 2) || picture.width - x;
      if (run > picture.width - x) return false;
      const color = colors[code & 3];
      let offset = (y * picture.width + x) * 4;
      for (let count = 0; count < run; count++) {
        rgba[offset++] = color[0];
        rgba[offset++] = color[1];
        rgba[offset++] = color[2];
        rgba[offset++] = color[3];
      }
      x += run;
    }
    position = (position + 1) & ~1;
  }
  return true;
}

function drawPicture(picture: Picture, palette: number[], alpha: number) {
  const colors = picture.colors.map((index, pixel) => {
    const rgb = palette[index];
    return [rgb >> 16, (rgb >> 8) & 255, rgb & 255, picture.opacity[pixel] * 17 * alpha];
  });
  const rgba = new Uint8ClampedArray(picture.width * picture.height * 4);
  const evenEnd = picture.oddField > picture.evenField ? picture.oddField : picture.control;
  const oddEnd = picture.evenField > picture.oddField ? picture.evenField : picture.control;
  if (!drawField(picture, 0, picture.evenField, evenEnd, colors, rgba) ||
      (picture.height > 1 && !drawField(picture, 1, picture.oddField, oddEnd, colors, rgba))) {
    rgba.fill(0);
  }
  return rgba;
}

export function decodeVobSub(indexText: string, data: ArrayBuffer, streamIndex: number): VobSubTrack {
  if (!Number.isInteger(streamIndex) || streamIndex < 0 || streamIndex > 31 ||
      indexText.length > MAX_INDEX_LENGTH || data.byteLength > MAX_SUB_LENGTH) {
    throw new Error('VobSub 字幕超出读取范围');
  }
  const bytes = new Uint8Array(data);
  const { width, height, palette, alpha, entries } = parseIndex(indexText, streamIndex, bytes.length);
  const offsets = Array.from(new Set(entries.map(entry => entry.offset))).sort((left, right) => left - right);
  const pictures = new Map<number, Picture>();
  offsets.forEach((offset, index) => {
    const end = Math.min(offsets[index + 1] ?? bytes.length, offset + MAX_PACKET_SPAN);
    const packet = readPicturePacket(bytes, offset, end, streamIndex);
    const picture = packet && readPicture(packet, width, height);
    if (picture) pictures.set(offset, picture);
  });
  entries.sort((left, right) => left.time - right.time || left.offset - right.offset);
  let cached: { picture: Picture; rgba: Uint8ClampedArray } | undefined;
  const cues: VobSubCue[] = [];
  entries.forEach((entry, index) => {
    const picture = pictures.get(entry.offset);
    if (!picture) return;
    const start = Math.max(0, entry.time + picture.start);
    const naturalEnd = entry.time + (picture.end ?? picture.start + 4);
    const end = Math.min(naturalEnd, entries[index + 1]?.time ?? naturalEnd);
    if (end <= start) return;
    cues.push({
      start, end, x: picture.x, y: picture.y, width: picture.width, height: picture.height,
      get rgba() {
        if (cached?.picture !== picture) cached = { picture, rgba: drawPicture(picture, palette, alpha) };
        return cached.rgba;
      },
    });
  });
  if (!cues.length) throw new Error('VobSub 字幕没有可播放的图像');
  return { width, height, cues };
}
