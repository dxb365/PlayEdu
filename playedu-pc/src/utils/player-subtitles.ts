import { getToken } from "./index";
import { parseTextSubtitles, TextSubtitleCue } from "./text-subtitles";
import { decodeVobSub, VobSubCue, VobSubTrack } from "./vobsub";
import "./player-subtitles.scss";

export type SubtitleTrack = {
  id: string;
  label: string;
  language: string;
  format: "vtt" | "srt" | "ass" | "ssa" | "vobsub";
  url: string;
  data_url?: string;
  stream_index?: number;
};

type Player = {
  container: HTMLElement;
  video: HTMLVideoElement;
  destroy: () => void;
  fullScreen?: {
    toggle: (mode?: string) => void;
    cancel: (mode?: string) => void;
    isFullScreen: (mode?: string) => boolean;
  };
};

const PREFERENCE_KEY = "playedu-subtitle-preference";
const TEXT_BYTES = 4 * 1024 * 1024;
const BITMAP_BYTES = 32 * 1024 * 1024;

function initialTrack(tracks: SubtitleTrack[]): SubtitleTrack | undefined {
  try {
    const stored = localStorage.getItem(PREFERENCE_KEY);
    if (stored === "off") return undefined;
    if (stored) {
      const preference = JSON.parse(stored);
      const previous = tracks.find((track) => track.id === preference.id)
        || tracks.find((track) => track.language === preference.language && track.format === preference.format)
        || tracks.find((track) => track.language === preference.language);
      if (previous) return previous;
    }
  } catch {}
  for (const language of ["zh", "en"]) {
    const matches = tracks.filter((track) => track.language?.toLowerCase().split("-")[0] === language);
    const preferred = matches.find((track) => track.format !== "vobsub") || matches[0];
    if (preferred) return preferred;
  }
  return tracks[0];
}

async function readSubtitle(path: string, limit: number, signal: AbortSignal): Promise<ArrayBuffer> {
  if (!/^\/api\/v1\/course\/\d+\/hour\/\d+\/subtitle\/[a-z0-9_-]+\/(main|sub)$/i.test(path)) {
    throw new Error("Invalid subtitle path");
  }
  const prefix = (import.meta.env.VITE_APP_URL || "").replace(/\/$/, "");
  const response = await fetch(prefix + path, {
    headers: { Authorization: "Bearer " + getToken() },
    redirect: "error",
    signal,
  });
  if (!response.ok || Number(response.headers.get("Content-Length")) > limit) {
    throw new Error("Subtitle download failed");
  }
  const data = await response.arrayBuffer();
  if (data.byteLength > limit) throw new Error("Subtitle file too large");
  return data;
}

function readText(data: ArrayBuffer, language: string): string {
  const bom = new Uint8Array(data, 0, Math.min(2, data.byteLength));
  if (bom[0] === 0xff && bom[1] === 0xfe) return new TextDecoder("utf-16le").decode(data);
  if (bom[0] === 0xfe && bom[1] === 0xff) return new TextDecoder("utf-16be").decode(data);
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(data);
  } catch {
    const code = language?.toLowerCase() || "";
    const encoding = /^zh-(hant|tw|hk)/.test(code) ? "big5"
      : code.startsWith("zh") ? "gb18030" : "windows-1252";
    return new TextDecoder(encoding).decode(data);
  }
}

function cueIndex(cues: readonly { start: number }[], time: number): number {
  let left = 0;
  let right = cues.length;
  while (left < right) {
    const middle = Math.floor((left + right) / 2);
    if (cues[middle].start <= time) left = middle + 1;
    else right = middle;
  }
  return left - 1;
}

export function bindPlayerSubtitles(player: Player, tracks: SubtitleTrack[], notify: (message: string) => void): void {
  if (!Array.isArray(tracks) || tracks.length === 0) return;
  const { container, video } = player;
  const wrap = container.querySelector<HTMLElement>(".dplayer-video-wrap");
  if (!wrap) return;

  const controls = document.createElement("label");
  controls.className = "playedu-subtitle-controls";
  const caption = document.createElement("span");
  caption.textContent = "字幕";
  caption.setAttribute("aria-hidden", "true");
  const select = document.createElement("select");
  select.setAttribute("aria-label", "字幕");
  select.add(new Option("关闭字幕", ""));
  for (const track of tracks) {
    const sameLabel = tracks.filter((candidate) => candidate.label === track.label);
    const sameFormat = sameLabel.filter((candidate) => candidate.format === track.format);
    const suffix = sameFormat.length > 1 ? ` ${sameFormat.indexOf(track) + 1}` : "";
    const label = sameLabel.length > 1
      ? `${track.label}（${track.format.toUpperCase()}${suffix}）` : track.label;
    select.add(new Option(label, track.id));
  }
  controls.append(caption, select);
  container.append(controls);

  const frame = document.createElement("div");
  frame.className = "playedu-subtitle-frame";
  frame.setAttribute("aria-hidden", "true");
  const text = document.createElement("div");
  text.className = "playedu-subtitle-text";
  const canvas = document.createElement("canvas");
  canvas.className = "playedu-subtitle-canvas";
  canvas.hidden = true;
  frame.append(text, canvas);
  wrap.append(frame);
  const context = canvas.getContext("2d");

  let bitmap: VobSubTrack | undefined;
  let textCues: TextSubtitleCue[] = [];
  let endTimes: number[] = [];
  let drawnCue: VobSubCue | undefined;
  let request: AbortController | undefined;
  let disposed = false;
  let animation = 0;

  const resize = () => {
    const bounds = video.getBoundingClientRect();
    const parent = wrap.getBoundingClientRect();
    const ratio = video.videoWidth && video.videoHeight
      ? video.videoWidth / video.videoHeight : bounds.width / bounds.height;
    if (!Number.isFinite(ratio) || !ratio || !bounds.width || !bounds.height) return;
    const width = Math.min(bounds.width, bounds.height * ratio);
    const height = width / ratio;
    frame.style.left = `${bounds.left - parent.left + (bounds.width - width) / 2}px`;
    frame.style.top = `${bounds.top - parent.top + (bounds.height - height) / 2}px`;
    frame.style.width = `${width}px`;
    frame.style.height = `${height}px`;
    text.style.fontSize = `${Math.max(14, Math.min(42, height * 0.05))}px`;
  };

  const paint = () => {
    const time = video.currentTime;
    if (bitmap && context) {
      const cue = bitmap.cues[cueIndex(bitmap.cues, time)];
      const visible = cue && time < cue.end ? cue : undefined;
      if (visible !== drawnCue) {
        drawnCue = visible;
        context.clearRect(0, 0, canvas.width, canvas.height);
        if (visible) {
          const image = context.createImageData(visible.width, visible.height);
          image.data.set(visible.rgba);
          context.putImageData(image, visible.x, visible.y);
        }
      }
      return;
    }
    const lines: string[] = [];
    for (let index = cueIndex(textCues, time); index >= 0 && endTimes[index] > time && lines.length < 8; index--) {
      if (textCues[index].end > time) lines.push(textCues[index].text);
    }
    const value = lines.reverse().join("\n");
    if (text.textContent !== value) text.textContent = value;
  };

  const tick = () => {
    animation = 0;
    paint();
    if (!video.paused && !video.ended && (bitmap || textCues.length)) {
      animation = window.requestAnimationFrame(tick);
    }
  };

  const start = () => {
    if (!animation && (bitmap || textCues.length)) animation = window.requestAnimationFrame(tick);
  };

  const stop = () => {
    if (animation) window.cancelAnimationFrame(animation);
    animation = 0;
    paint();
  };

  const selectTrack = async (track?: SubtitleTrack) => {
    request?.abort();
    request = undefined;
    bitmap = undefined;
    textCues = [];
    endTimes = [];
    drawnCue = undefined;
    stop();
    canvas.hidden = true;
    context?.clearRect(0, 0, canvas.width, canvas.height);
    text.textContent = "";
    select.value = track?.id || "";
    if (!track) return;

    const current = new AbortController();
    request = current;
    try {
      if (track.format === "vobsub") {
        if (!track.data_url || !context) throw new Error("Missing bitmap data");
        const [index, data] = await Promise.all([
          readSubtitle(track.url, TEXT_BYTES, current.signal),
          readSubtitle(track.data_url, BITMAP_BYTES, current.signal),
        ]);
        if (current.signal.aborted || disposed) return;
        const parsed = decodeVobSub(readText(index, track.language), data, track.stream_index ?? 0);
        if (!parsed.cues.length) throw new Error("No bitmap cues");
        bitmap = parsed;
        canvas.width = parsed.width;
        canvas.height = parsed.height;
        canvas.hidden = false;
      } else {
        const data = await readSubtitle(track.url, TEXT_BYTES, current.signal);
        if (current.signal.aborted || disposed) return;
        textCues = parseTextSubtitles(readText(data, track.language), track.format);
        if (!textCues.length) throw new Error("No text cues");
        let maximum = 0;
        endTimes = textCues.map((cue) => (maximum = Math.max(maximum, cue.end)));
      }
      paint();
      if (!video.paused) start();
    } catch {
      if (!current.signal.aborted && !disposed) {
        current.abort();
        bitmap = undefined;
        textCues = [];
        canvas.hidden = true;
        select.value = "";
        notify("字幕加载失败，请重新选择字幕");
      }
    }
  };

  const change = () => {
    const track = tracks.find((candidate) => candidate.id === select.value);
    try {
      localStorage.setItem(PREFERENCE_KEY, track
        ? JSON.stringify({ id: track.id, language: track.language, format: track.format })
        : "off");
    } catch {}
    void selectTrack(track);
  };

  select.addEventListener("change", change);
  select.addEventListener("keydown", (event) => event.stopPropagation());
  controls.addEventListener("click", (event) => event.stopPropagation());
  controls.addEventListener("touchend", (event) => event.stopPropagation());
  for (const event of ["timeupdate", "seeked", "seeking"]) video.addEventListener(event, paint);
  video.addEventListener("loadedmetadata", resize);
  video.addEventListener("play", start);
  video.addEventListener("pause", stop);
  video.addEventListener("ended", stop);
  const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(resize);
  observer?.observe(video);
  observer?.observe(wrap);
  window.addEventListener("resize", resize);
  document.addEventListener("fullscreenchange", resize);
  resize();

  const fullscreen = player.fullScreen;
  const alternate = container as HTMLElement & { webkitRequestFullscreen?: () => void };
  const fullscreenAvailable = (typeof container.requestFullscreen === "function" && document.fullscreenEnabled !== false)
    || typeof alternate.webkitRequestFullscreen === "function";
  if (fullscreen && !fullscreenAvailable) {
    const toggle = fullscreen.toggle.bind(fullscreen);
    fullscreen.toggle = (mode = "browser") => toggle(mode === "browser" ? "web" : mode);
  }

  const destroy = player.destroy.bind(player);
  player.destroy = () => {
    if (disposed) return;
    disposed = true;
    request?.abort();
    request = undefined;
    bitmap = undefined;
    textCues = [];
    endTimes = [];
    drawnCue = undefined;
    stop();
    canvas.width = 0;
    canvas.height = 0;
    if (fullscreen?.isFullScreen("web")) fullscreen.cancel("web");
    observer?.disconnect();
    window.removeEventListener("resize", resize);
    document.removeEventListener("fullscreenchange", resize);
    for (const event of ["timeupdate", "seeked", "seeking"]) video.removeEventListener(event, paint);
    video.removeEventListener("loadedmetadata", resize);
    video.removeEventListener("play", start);
    video.removeEventListener("pause", stop);
    video.removeEventListener("ended", stop);
    controls.remove();
    frame.remove();
    destroy();
  };

  void selectTrack(initialTrack(tracks));
}
