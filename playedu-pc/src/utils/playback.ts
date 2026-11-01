// Teaching customization (2026-11-01): identical playback rules on PC and mobile.
import { getToken } from './index';

type Options = {
  courseId: number; hourId: number; total: number; initial: number; completed: number;
  allowSeek: boolean; allowSpeed: boolean; notify: (text: string) => void;
};
const apiUrl = (path: string) => (import.meta.env.VITE_APP_URL || '').replace(/\/$/, '') + path;
export async function latestPlayback() {
  const response = await fetch(apiUrl('/api/v1/playback/latest'), {
    headers: { Authorization: 'Bearer ' + getToken() },
  });
  if (!response.ok) return null;
  const result = await response.json();
  return result.code === 0 ? result.data : null;
}

export function bindLearningPlayer(player: any, options: Options) {
  const video: HTMLVideoElement = player.video;
  const clientId = Math.random().toString(36).slice(2) + '-' + Date.now();
  let sequence = 0, disposed = false, restoring = true, correcting = false;
  let approved = Math.max(0, options.initial), maximum = Math.max(0, options.completed);
  let failed = false, lastPayload = '', lastNotice = 0;
  const notice = (text: string) => {
    if (Date.now() - lastNotice > 2000) { options.notify(text); lastNotice = Date.now(); }
  };
  const save = (keepalive = false) => {
    if (!Number.isFinite(video.currentTime) || restoring || maximum <= 0) return;
    const position = Math.max(0, Math.min(options.total, Math.floor(video.currentTime)));
    const duration = Math.max(1, Math.min(options.total, Math.floor(maximum)));
    const signature = position + ':' + duration;
    if (signature === lastPayload && !failed) return;
    lastPayload = signature;
    const payload = { duration, position, clientId, sequence: ++sequence };
    void fetch(apiUrl('/api/v1/course/' + options.courseId + '/hour/' + options.hourId + '/record'), {
      method: 'POST', keepalive,
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + getToken() },
      body: JSON.stringify(payload),
    }).then(async response => {
      const body = await response.json();
      if (!response.ok || body.code !== 0) throw new Error('save failed');
      failed = false;
    }).catch(() => { failed = true; if (!disposed && document.visibilityState !== 'hidden') notice('学习进度暂未同步，请检查网络'); });
  };
  const restore = () => {
    approved = Math.max(0, Math.min(options.initial, Math.max(0, video.duration - 0.1)));
    if (approved > 0) { video.currentTime = approved; } else { restoring = false; }
  };
  const seeking = () => {
    if (!restoring && !options.allowSeek && Math.abs(video.currentTime - approved) > 0.75) {
      correcting = true;
      video.currentTime = approved;
      notice('本课程禁止拖动播放进度');
    }
  };
  const seeked = () => { restoring = false; correcting = false; approved = video.currentTime; save(); };
  const tick = () => {
    if (restoring || correcting || video.seeking) return;
    approved = video.currentTime;
    maximum = Math.max(maximum, approved);
  };
  const speed = () => {
    if (!options.allowSpeed && video.playbackRate !== 1) {
      video.playbackRate = 1;
      notice('本课程禁止倍速播放');
    }
  };
  const ended = () => { maximum = options.total; restoring = false; save(true); };
  const paused = () => { tick(); save(true); };
  const hidden = () => { if (document.visibilityState === 'hidden') save(true); };
  const leaving = () => save(true);
  const appBackground = () => { tick(); save(true); video.pause(); };
  video.addEventListener('loadedmetadata', restore);
  video.addEventListener('seeking', seeking);
  video.addEventListener('seeked', seeked);
  video.addEventListener('timeupdate', tick);
  video.addEventListener('ratechange', speed);
  video.addEventListener('ended', ended);
  video.addEventListener('pause', paused);
  document.addEventListener('visibilitychange', hidden);
  window.addEventListener('pagehide', leaving);
  window.addEventListener('playedu:app-background', appBackground);
  if (video.readyState >= 1) restore();
  const saver = window.setInterval(() => { tick(); if (!video.paused) save(); }, 3000);
  const ping = window.setInterval(() => {
    if (!video.paused && !video.ended) void fetch(apiUrl('/api/v1/course/' + options.courseId + '/hour/' + options.hourId + '/ping'), {
      method: 'POST', headers: { Authorization: 'Bearer ' + getToken() },
    }).catch(() => {});
  }, 10000);
  const destroy = player.destroy.bind(player);
  player.destroy = () => {
    if (disposed) return;
    tick(); save(true); disposed = true;
    window.clearInterval(saver); window.clearInterval(ping);
    video.removeEventListener('loadedmetadata', restore);
    video.removeEventListener('seeking', seeking);
    video.removeEventListener('seeked', seeked);
    video.removeEventListener('timeupdate', tick);
    video.removeEventListener('ratechange', speed);
    video.removeEventListener('ended', ended);
    video.removeEventListener('pause', paused);
    document.removeEventListener('visibilitychange', hidden);
    window.removeEventListener('pagehide', leaving);
    window.removeEventListener('playedu:app-background', appBackground);
    destroy();
  };
  return player.destroy;
}
