// Tests the actual PC/mobile binding with browser events, without running a browser.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../playedu-pc/package.json', import.meta.url));
const ts = require('typescript');
const pc = await fs.readFile(new URL('../playedu-pc/src/utils/playback.ts', import.meta.url), 'utf8');
const mobile = await fs.readFile(new URL('../playedu-h5/src/utils/playback.ts', import.meta.url), 'utf8');
assert.equal(pc, mobile, 'PC and mobile must enforce the same playback rules');
const source = pc.replace("import { getToken } from './index';", "const getToken = () => 'test-token';")
    .replaceAll('import.meta.env.VITE_APP_URL', "''");
const js = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText;
const { bindLearningPlayer } = await import('data:text/javascript;base64,' + Buffer.from(js).toString('base64'));
const intervals = new Map(); let nextId = 0;
globalThis.window = Object.assign(new EventTarget(), {
    setInterval(fn,ms) { intervals.set(++nextId,{fn,ms}); return nextId; },
    clearInterval(id) { intervals.delete(id); },
});
globalThis.document = Object.assign(new EventTarget(), { visibilityState: 'visible' });
let requests = [], rejectNext = false;
globalThis.fetch = async (url, init) => {
    requests.push({url,...init, payload:init.body ? JSON.parse(init.body) : null});
    if (rejectNext) { rejectNext=false; throw new Error('offline'); }
    return {ok:true, json:async()=>({code:0,data:null})};
};
const settle = async () => { for(let i=0;i<5;i++) await Promise.resolve(); };
function setup(overrides={}) {
    const video = Object.assign(new EventTarget(), { currentTime:0, duration:300, readyState:1, paused:false, ended:false, seeking:false, playbackRate:1 });
    let destroyed=0;const player={video,destroy(){destroyed++;}};
    const notices=[];
    bindLearningPlayer(player,{courseId:10,hourId:20,total:300,initial:100,completed:100,allowSeek:true,allowSpeed:true,notify:s=>notices.push(s),...overrides});
    video.dispatchEvent(new Event('seeked'));
    return {video,player,notices,destroyed:()=>destroyed};
}
let t=setup();assert.equal(t.video.currentTime,100,'automatically restore the saved time');
await settle();requests=[];
t.video.currentTime=180;t.video.dispatchEvent(new Event('timeupdate'));t.video.dispatchEvent(new Event('pause'));
await settle();assert.equal(requests.at(-1).payload.duration,180);assert.equal(requests.at(-1).payload.position,180);
t.video.currentTime=32;t.video.dispatchEvent(new Event('seeking'));t.video.dispatchEvent(new Event('seeked'));
await settle();assert.equal(requests.at(-1).payload.position,32,'save the rewind cursor');assert.equal(requests.at(-1).payload.duration,180,'preserve completion progress');
t.player.destroy();t.player.destroy();assert.equal(t.destroyed(),1);assert.equal(intervals.size,0,'clean up timers');
t=setup({allowSeek:false,allowSpeed:false});await settle();requests=[];
t.video.currentTime=240;t.video.dispatchEvent(new Event('seeking'));assert.equal(t.video.currentTime,100,'reject manual seeking');
t.video.dispatchEvent(new Event('seeked'));t.video.dispatchEvent(new Event('timeupdate'));
t.video.playbackRate=2;t.video.dispatchEvent(new Event('ratechange'));assert.equal(t.video.playbackRate,1,'reject disabled speed');
t.player.destroy();
t=setup({allowSeek:false,allowSpeed:true});t.video.playbackRate=2;t.video.dispatchEvent(new Event('ratechange'));assert.equal(t.video.playbackRate,2,'speed stays independent from seeking');
await settle();requests=[];rejectNext=true;t.video.currentTime=110;t.video.dispatchEvent(new Event('timeupdate'));t.video.dispatchEvent(new Event('pause'));await settle();
assert.ok(t.notices.some(s=>s.includes('暂未同步')),'show a failed save');
for(const timer of intervals.values())if(timer.ms===3000)timer.fn();await settle();assert.equal(requests.length,2,'retry a failed save');
t.video.ended=true;t.video.currentTime=300;t.video.dispatchEvent(new Event('ended'));await settle();assert.equal(requests.at(-1).payload.duration,300,'record exact completion');
t.player.destroy();assert.equal(intervals.size,0);
let completedSaves = 0;
t = setup({ onCompleted: () => completedSaves++ });
await settle();
rejectNext = true;
t.video.currentTime = 300;
t.video.dispatchEvent(new Event('ended'));
await settle();
assert.equal(completedSaves, 0, 'unlock notification requires a successful completion save');
t.video.dispatchEvent(new Event('pause'));
await settle();
assert.equal(completedSaves, 1, 'successful completion refreshes the course locks');
t.player.destroy();
console.log('Playback tests passed: resume, rewind, completion, seek/speed independence, retry, cleanup.');
