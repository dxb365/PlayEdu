export type TextSubtitleCue = { start: number; end: number; text: string };

const MAX_CUES = 20_000;
const ENTITIES: Record<string, string> = {
  amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: "\u00a0",
  lrm: "\u200e", rlm: "\u200f",
};

function timestamp(value: string): number {
  const match = /^(?:(\d{1,3}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})$/.exec(value);
  if (!match) return NaN;
  const [, hours, minutes, seconds, fraction] = match;
  if (Number(minutes) > 59 || Number(seconds) > 59) return NaN;
  return Number(hours || 0) * 3600 + Number(minutes) * 60 + Number(seconds) +
    Number(fraction.padEnd(3, "0")) / 1000;
}

function plainText(value: string): string {
  return value.replace(/<[^<>\r\n]*>/g, "").replace(
    /&(#\d{1,7}|#x[0-9a-f]{1,6}|[a-z]+);/gi,
    (entity, code: string) => {
      if (code.startsWith("#")) {
        const point = code[1]?.toLowerCase() === "x"
          ? parseInt(code.slice(2), 16) : Number(code.slice(1));
        return point > 0 && point <= 0x10ffff ? String.fromCodePoint(point) : entity;
      }
      return Object.prototype.hasOwnProperty.call(ENTITIES, code) ? ENTITIES[code] : entity;
    },
  ).trim();
}

export function parseTextSubtitles(source: string, format: string): TextSubtitleCue[] {
  const lines = source.replace(/^\uFEFF/, "").replace(/\r\n?/g, "\n").split("\n");
  const cues: TextSubtitleCue[] = [];
  const append = (start: string, end: string, content: string) => {
    const startTime = timestamp(start);
    const endTime = timestamp(end);
    const text = plainText(content);
    if (Number.isFinite(startTime) && endTime > startTime && text) {
      cues.push({ start: startTime, end: endTime, text: text.slice(0, 4096) });
    }
  };
  if (format === "ass" || format === "ssa") {
    let inEvents = false;
    let fields = ["layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text"];
    for (const line of lines) {
      if (cues.length >= MAX_CUES) break;
      if (/^\s*\[.+\]\s*$/.test(line)) inEvents = /^\s*\[events\]\s*$/i.test(line);
      if (!inEvents) continue;
      const formatLine = /^\s*format:\s*(.*)$/i.exec(line);
      if (formatLine) {
        fields = formatLine[1].toLowerCase().split(",").map((field) => field.trim());
        continue;
      }
      const dialogue = /^\s*dialogue:\s*(.*)$/i.exec(line);
      if (!dialogue || fields.at(-1) !== "text") continue;
      const values = dialogue[1].split(",");
      if (values.length < fields.length) continue;
      const start = values[fields.indexOf("start")]?.trim();
      const end = values[fields.indexOf("end")]?.trim();
      const text = values.slice(fields.length - 1).join(",");
      if (start && end && !/\{[^{}]*\\p[1-9]/i.test(text)) {
        append(start, end, text.replace(/\{[^{}]*\}/g, "").replace(/\\[Nn]/g, "\n").replace(/\\h/g, "\u00a0"));
      }
    }
  } else {
    for (let index = 0; index < lines.length && cues.length < MAX_CUES; index++) {
      if (format === "vtt" && /^(?:NOTE(?:\s|$)|STYLE\s*$|REGION\s*$)/.test(lines[index])) {
        while (index + 1 < lines.length && lines[index + 1].trim()) index++;
        continue;
      }
      const timing = /^\s*(\S+)\s+-->\s+(\S+)/.exec(lines[index]);
      if (!timing) continue;
      const content: string[] = [];
      while (index + 1 < lines.length && lines[index + 1].trim()) content.push(lines[++index]);
      append(timing[1], timing[2], content.join("\n"));
    }
  }
  return cues.sort((a, b) => a.start - b.start);
}
