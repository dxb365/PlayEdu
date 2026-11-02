import { createRequire } from 'node:module';
import fs from 'node:fs';
import assert from 'node:assert/strict';

const require = createRequire(process.env.NAS_BROWSER_PACKAGE + '/package.json');
const { chromium } = require('playwright');
const fixture = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const browser = await chromium.launch({ args: [
  '--ignore-certificate-errors-spki-list=' + fixture.certificatePin,
  '--autoplay-policy=no-user-gesture-required',
] });
const errors = [];
const contexts = [];
fs.mkdirSync('nas-browser-results', { recursive: true });

async function context(options = {}) {
  const current = await browser.newContext(options);
  contexts.push(current);
  await current.route('**/*', route => {
    const url = new URL(route.request().url());
    return ['localhost', '127.0.0.1'].includes(url.hostname)
      || ['data:', 'blob:'].includes(url.protocol) ? route.continue() : route.abort();
  });
  return current;
}

try {
  const adminContext = await context({ viewport: { width: 1440, height: 1000 } });
  await adminContext.addInitScript(token => localStorage.setItem('playedu-backend-token', token), fixture.adminToken);
  const admin = await adminContext.newPage();
  admin.on('pageerror', error => errors.push(error.message));
  await admin.goto('http://127.0.0.1:9900/videos');
  await admin.getByRole('button', { name: '从 NAS 视频库添加', exact: true }).click();
  const dialog = admin.getByRole('dialog');
  const row = dialog.getByRole('row').filter({ hasText: '界面测试.mp4' });
  await row.getByRole('checkbox').check();
  await dialog.getByRole('row').filter({ hasText: '损坏视频.mp4' }).getByRole('checkbox').check();
  await dialog.getByRole('button', { name: '添加所选视频（2）', exact: true }).click();
  await row.getByText('已添加', { exact: true }).waitFor();
  await dialog.getByText('以下视频未添加', { exact: true }).waitFor();
  await dialog.getByRole('button', { name: /^关\s*闭$/ }).click();
  await admin.getByRole('row').filter({ hasText: '界面测试' }).getByRole('button', { name: /^预\s*览$/ }).click();
  await admin.waitForFunction(() => window.player?.video?.readyState >= 2);
  await admin.evaluate(async () => {
    window.player.video.muted = true;
    await window.player.video.play();
  });
  await admin.waitForFunction(() => window.player.video.currentTime > 0.5);
  await admin.evaluate(() => { window.player.video.currentTime = 8; });
  await admin.waitForFunction(() => window.player.video.currentTime >= 8 && !window.player.video.seeking);
  await admin.evaluate(() => window.player.video.pause());
  assert(await admin.evaluate(() => window.player.video.paused));
  await admin.screenshot({ path: 'nas-browser-results/admin-preview.png', fullPage: true });
  async function api(route, data, token = fixture.adminToken, origin = 'http://127.0.0.1:9900') {
    const response = await adminContext.request.fetch(origin + '/api' + route, {
      method: data === undefined ? 'GET' : 'POST', data,
      headers: { Authorization: 'Bearer ' + token },
    });
    const body = await response.json();
    assert(response.ok() && body.code === 0, route + ': ' + JSON.stringify(body));
    return body.data;
  }
  await api('/backend/v1/course/create', {
    title: 'NAS 原目录测试课程', thumb: 0, short_desc: 'isolated CI fixture',
    is_show: 1, is_required: 0, dep_ids: [], category_ids: [], chapters: [],
    hours: [{ name: 'NAS 第一课', type: 'VIDEO', duration: 20, rid: fixture.resourceId }],
  });
  const courseList = await api('/backend/v1/course/index');
  const course = courseList.data.find(item => item.title === 'NAS 原目录测试课程');
  assert(course);
  const courseDetail = await api('/backend/v1/course/' + course.id);
  const hour = courseDetail.hours['0'][0];
  assert(hour);
  await api('/backend/v1/user/create', {
    email: 'nas-fixture@example.com', password: 'ci-only-learner-password',
    name: 'NAS测试学员', avatar: 0, dep_ids: [],
  });
  const login = await api('/api/v1/auth/login/password', {
    email: 'nas-fixture@example.com', password: 'ci-only-learner-password',
  }, '');
  const learnerToken = login.token;
  let previous = 0;
  for (const mobile of [false, true]) {
    const learnerContext = await context(mobile ? {
      viewport: { width: 1280, height: 800 },
      userAgent: 'Mozilla/5.0 (Linux; Android 12; Tablet) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36',
    } : { viewport: { width: 1440, height: 1000 } });
    await learnerContext.addInitScript(({ token, mobile }) => {
      localStorage.setItem(mobile ? 'playedu-h5-token' : 'playedu-frontend-token', token);
    }, { token: learnerToken, mobile });
    const learner = await learnerContext.newPage();
    learner.on('pageerror', error => errors.push(error.message));
    const address = 'https://localhost:8443/course/' + course.id + '/hour/' + hour.id;
    await learner.goto(address);
    await learner.waitForFunction(() => window.player?.video?.readyState >= 2);
    if (previous > 0) await learner.waitForFunction(value => window.player.video.currentTime >= value, previous);
    await learner.evaluate(async () => { window.player.video.muted = true; await window.player.video.play(); });
    await learner.waitForFunction(value => window.player.video.currentTime > value + 2, previous);
    await learner.evaluate(() => window.player.video.pause());
    const recordPath = '/api/v1/course/' + course.id + '/hour/' + hour.id;
    await learner.waitForFunction(async ({ route, token }) => {
      const response = await fetch('/api' + route, { headers: { Authorization: 'Bearer ' + token } });
      const body = await response.json();
      return body.data?.playback_position?.position >= 2;
    }, { route: recordPath, token: learnerToken });
    const saved = await api(recordPath, undefined, learnerToken);
    previous = saved.playback_position.position;
    assert(previous >= 2 && saved.user_hour_record.is_finished === 0);
    await learner.reload();
    await learner.waitForFunction(value => window.player?.video?.readyState >= 2
      && window.player.video.currentTime >= value, previous);
    await learner.screenshot({ path: 'nas-browser-results/' + (mobile ? 'android-layout' : 'desktop') + '-resume.png', fullPage: true });
    await learnerContext.close();
  }
  await api('/api/v1/course/' + course.id + '/hour/' + hour.id + '/record', {
    duration: 20, position: 20, clientId: 'ci-nas-completion', sequence: 1,
  }, learnerToken);
  const finished = await api('/api/v1/course/' + course.id + '/hour/' + hour.id, undefined, learnerToken);
  assert.equal(finished.user_hour_record.is_finished, 1);
  assert.equal(finished.playback_position.position, 20);
  assert.deepEqual(errors, []);
  console.log('Browser checks passed: NAS chooser, original MP4 preview/seek/pause, desktop and Android layout resume, completion.');
} catch (error) {
  let index = 0;
  for (const current of contexts) {
    for (const page of current.pages()) {
      const name = 'nas-browser-results/failure-' + index++;
      await page.screenshot({ path: name + '.png', fullPage: true }).catch(() => {});
      fs.writeFileSync(name + '.txt', page.url() + '\n' + await page.locator('body').innerText().catch(() => ''));
    }
  }
  console.error('Browser page errors:', errors);
  throw error;
} finally {
  await browser.close();
}
