import { createRequire } from 'node:module';
import fs from 'node:fs';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

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
  await dialog.getByRole('button', { name: /批量选择/ }).click();
  await dialog.getByRole('checkbox').first().check();
  await dialog.getByText('已选择 200 / 200 个视频', { exact: true }).waitFor();
  await dialog.locator('.ant-pagination-next button').click();
  const excessCheckbox = dialog.getByRole('row').filter({ hasText: '批量200.mp4' }).getByRole('checkbox');
  await excessCheckbox.click();
  assert.equal(await excessCheckbox.isChecked(), false);
  await dialog.getByText('已选择 200 / 200 个视频', { exact: true }).waitFor();
  await admin.screenshot({ path: 'nas-browser-results/nas-200-selection.png', fullPage: true });
  await dialog.getByRole('button', { name: /^关\s*闭$/ }).click();
  await admin.getByRole('button', { name: '从 NAS 视频库添加', exact: true }).click();
  const row = dialog.getByRole('row').filter({ hasText: '界面测试.mp4' });
  await row.getByRole('checkbox').check();
  await dialog.getByRole('row').filter({ hasText: '损坏视频.mp4' }).getByRole('checkbox').check();
  await dialog.getByRole('button', { name: '添加所选视频（2）', exact: true }).click();
  await row.getByText('已添加', { exact: true }).waitFor();
  await dialog.getByText('以下视频未添加', { exact: true }).waitFor();
  await dialog.getByRole('button', { name: /^关\s*闭$/ }).click();
  await admin.getByRole('button', { name: '批量操作', exact: true }).click();
  await admin.getByRole('row').filter({ hasText: '界面测试' }).getByRole('checkbox').check();
  const toolbar = admin.getByRole('toolbar', { name: '视频批量操作' });
  for (const label of ['批量移动', '批量复制', '批量改分类', '批量删除']) {
    await toolbar.getByRole('button', { name: label, exact: true }).waitFor();
  }
  await toolbar.getByRole('button', { name: '批量复制', exact: true }).click();
  await dialog.getByRole('button', { name: /^确\s*定$/ }).click();
  await dialog.waitFor({ state: 'hidden' });
  await admin.getByRole('row').filter({ hasText: '界面测试（副本）' }).waitFor();
  await admin.screenshot({ path: 'nas-browser-results/video-bulk-copy.png', fullPage: true });
  await admin.getByRole('button', { name: '取消操作', exact: true }).click();
  await admin.getByRole('row').filter({ has: admin.getByText('界面测试', { exact: true }) })
    .getByRole('button', { name: /^预\s*览$/ }).click();
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
  async function requestApi(route, data, token = fixture.adminToken, method = data === undefined ? 'GET' : 'POST') {
    const response = await adminContext.request.fetch('http://127.0.0.1:9900/api' + route, {
      method, data,
      headers: { Authorization: 'Bearer ' + token },
    });
    return { ok: response.ok(), body: await response.json() };
  }
  async function api(route, data, token = fixture.adminToken, method) {
    const result = await requestApi(route, data, token, method);
    assert(result.ok && result.body.code === 0, route + ': ' + JSON.stringify(result.body));
    return result.body.data;
  }
  async function denied(route, data, token = fixture.adminToken, method) {
    const result = await requestApi(route, data, token, method);
    assert(!result.ok || result.body.code !== 0, 'Request unexpectedly accepted: ' + route);
  }
  for (const name of ['批量分类甲', '批量分类乙']) {
    await api('/backend/v1/resource-category/create', { name, parent_id: 0, sort: 0 });
  }
  const categories = await api('/backend/v1/resource-category/index');
  const categoryA = categories.categories['0'].find(item => item.name === '批量分类甲').id;
  const categoryB = categories.categories['0'].find(item => item.name === '批量分类乙').id;
  const bulk = (operation, ids, category_ids) => api('/backend/v1/resource/bulk', { operation, ids, category_ids });
  const firstCopy = await bulk('copy', [fixture.resourceId], [categoryA]);
  let copiedIds = [...firstCopy.created_ids];
  assert.equal(firstCopy.affected, 1);
  const copiedDetail = await api('/backend/v1/resource/' + copiedIds[0]);
  const sourceDetail = await api('/backend/v1/resource/' + fixture.resourceId);
  assert.equal(copiedDetail.resources.path, sourceDetail.resources.path);
  assert.equal(copiedDetail.resources.disk, 'nas');
  assert.deepEqual(copiedDetail.category_ids, [categoryA]);
  while (copiedIds.length < 201) {
    const result = await bulk('copy', copiedIds.slice(0, Math.min(copiedIds.length, 201 - copiedIds.length)), [categoryA]);
    copiedIds.push(...result.created_ids);
  }
  await api('/backend/v1/resource/' + copiedIds[0], { name: 'CI-sort-A', category_id: categoryA }, fixture.adminToken, 'PUT');
  await new Promise(resolve => setTimeout(resolve, 1100));
  await api('/backend/v1/resource/' + copiedIds[1], { name: 'CI-sort-B', category_id: categoryA }, fixture.adminToken, 'PUT');
  for (const [field, order, expected] of [
    ['name', 'asc', ['CI-sort-A', 'CI-sort-B']],
    ['name', 'desc', ['CI-sort-B', 'CI-sort-A']],
    ['updated_at', 'asc', ['CI-sort-A', 'CI-sort-B']],
    ['updated_at', 'desc', ['CI-sort-B', 'CI-sort-A']],
  ]) {
    const sorted = await api('/backend/v1/resource/index?type=VIDEO&name=CI-sort&sort_field=' + field + '&sort_algo=' + order);
    assert.deepEqual(sorted.result.data.map(item => item.name), expected);
  }
  await denied('/backend/v1/resource/bulk', {
    operation: 'move', ids: copiedIds.slice(0, 201), category_ids: [categoryB],
  });
  assert.deepEqual((await api('/backend/v1/resource/' + copiedIds[0])).category_ids, [categoryA]);
  const selectedIds = copiedIds.slice(0, 200);
  assert.equal((await bulk('move', selectedIds, [categoryB])).affected, 200);
  assert.deepEqual((await api('/backend/v1/resource/' + selectedIds[199])).category_ids, [categoryB]);
  assert.equal((await bulk('categories', selectedIds, [categoryA, categoryB])).affected, 200);
  assert.deepEqual((await api('/backend/v1/resource/' + selectedIds[0])).category_ids.sort((a, b) => a - b),
    [categoryA, categoryB].sort((a, b) => a - b));
  const categorized = await api('/backend/v1/resource/index?type=VIDEO&size=200&category_ids=' + categoryB);
  const categorizedIds = new Set(categorized.result.data.map(item => item.id));
  assert(selectedIds.every(id => categorizedIds.has(id)));
  await denied('/backend/v1/resource/bulk', {
    operation: 'categories', ids: [selectedIds[0], 99999999], category_ids: [],
  });
  assert.deepEqual((await api('/backend/v1/resource/' + selectedIds[0])).category_ids.sort((a, b) => a - b),
    [categoryA, categoryB].sort((a, b) => a - b));
  await denied('/backend/v1/resource/destroy-multi', { ids: copiedIds.slice(0, 201) });
  await api('/backend/v1/resource/destroy-multi', { ids: selectedIds });
  const remaining = await api('/backend/v1/resource/index?type=VIDEO&size=200&category_ids=' + categoryB);
  assert.equal(remaining.result.data.length, 0);
  assert.equal((await api('/backend/v1/resource/' + fixture.resourceId)).resources.id, fixture.resourceId);
  const resources = await api('/backend/v1/resource/index?type=VIDEO&size=200');
  const interfaceResource = resources.result.data.find(item => item.path === '界面测试.mp4' && !item.name.includes('副本'));
  assert(interfaceResource);
  const uploadPage = await adminContext.newPage();
  uploadPage.on('pageerror', error => errors.push(error.message));
  await uploadPage.goto('http://127.0.0.1:9900/videos');
  await uploadPage.getByRole('button', { name: '上传视频', exact: true }).click();
  const uploadDialog = uploadPage.getByRole('dialog', { name: '上传视频', exact: true });
  const uploadInput = uploadDialog.locator('input[type="file"]');
  const uploadVideo = fs.readFileSync(fixture.uploadVideoPath);
  const uploadFiles = Array.from({ length: 101 }, (_, index) => ({
    name: 'CI上传' + String(index).padStart(3, '0') + '.mp4', mimeType: 'video/mp4', buffer: uploadVideo,
  }));
  let uploadStarts = 0;
  let uploadMerges = 0;
  uploadPage.on('request', request => {
    if (request.url().includes('/backend/v1/upload/minio/upload-id?')) uploadStarts++;
    if (request.url().includes('/backend/v1/upload/minio/merge-file')) uploadMerges++;
  });
  await uploadInput.setInputFiles(uploadFiles);
  await uploadPage.getByText('单次最多上传100个视频', { exact: true }).waitFor();
  assert.equal(await uploadDialog.locator('tbody tr[data-row-key]').count(), 0);
  assert.equal(uploadStarts, 0);
  await uploadPage.evaluate(() => {
    const originalSize = Object.getOwnPropertyDescriptor(Blob.prototype, 'size').get;
    Object.defineProperty(File.prototype, 'size', {
      configurable: true,
      get() { return this.name === 'CI超限视频.mp4' ? 10 * 1024 ** 3 + 1 : originalSize.call(this); },
    });
  });
  await uploadInput.setInputFiles({ name: 'CI超限视频.mp4', mimeType: 'video/mp4', buffer: uploadVideo });
  await uploadPage.getByText('CI超限视频.mp4 大小超过10GB', { exact: true }).waitFor();
  assert.equal(uploadStarts, 0);
  await uploadPage.evaluate(() => { delete File.prototype.size; });
  let releaseUploads;
  const uploadGate = new Promise(resolve => { releaseUploads = resolve; });
  const uploadRoute = '**/api/backend/v1/upload/minio/upload-id?*';
  await uploadPage.route(uploadRoute, async route => { await uploadGate; await route.continue(); });
  try {
    await uploadInput.setInputFiles(uploadFiles.slice(0, 100));
    for (let attempt = 0; attempt < 300 && uploadStarts < 10; attempt++) {
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    assert.equal(uploadStarts, 10, 'Ten videos can prepare their uploads concurrently');
    await new Promise(resolve => setTimeout(resolve, 300));
    assert.equal(uploadStarts, 10, 'Queued videos wait while ten uploads are active');
    releaseUploads();
    await uploadDialog.getByText('上传成功', { exact: true }).nth(99).waitFor({ timeout: 180000 });
    assert.equal(await uploadDialog.getByText('上传成功', { exact: true }).count(), 100);
    assert.equal(uploadStarts, 100);
    assert.equal(uploadMerges, 100);
  } finally {
    releaseUploads();
    await uploadPage.unroute(uploadRoute);
  }
  const uploaded = await api('/backend/v1/resource/index?type=VIDEO&size=200&name=' + encodeURIComponent('CI上传'));
  assert.equal(uploaded.result.data.length, 100);
  assert(uploaded.result.data.every(item => item.disk !== 'nas' && item.size === uploadVideo.length));
  assert.equal(new Set(uploaded.result.data.map(item => item.path)).size, 100);
  const uploadDigest = createHash('sha256').update(uploadVideo).digest('hex');
  const storedDigests = await uploadPage.evaluate(async urls => Promise.all(urls.map(async url => {
    const response = await fetch(url);
    if (!response.ok) throw new Error('Uploaded object unavailable: ' + response.status);
    const digest = await crypto.subtle.digest('SHA-256', await response.arrayBuffer());
    return Array.from(new Uint8Array(digest), value => value.toString(16).padStart(2, '0')).join('');
  })), [uploaded.resource_url[uploaded.result.data[0].id], uploaded.resource_url[uploaded.result.data[99].id]]);
  assert.deepEqual(storedDigests, [uploadDigest, uploadDigest]);
  await uploadPage.screenshot({ path: 'nas-browser-results/video-100-uploads.png', fullPage: false });
  await uploadDialog.getByRole('button', { name: /^完\s*成$/ }).click();
  await uploadDialog.waitFor({ state: 'hidden' });
  await uploadPage.close();
  await api('/backend/v1/app-config', { data: { 'player.sequential_learning': '0' } }, fixture.adminToken, 'PUT');
  await api('/backend/v1/course/create', {
    title: 'NAS 原目录测试课程', thumb: 0, short_desc: 'isolated CI fixture',
    is_show: 1, is_required: 0, dep_ids: [], category_ids: [], chapters: [],
    hours: [
      { name: 'NAS 第一课', type: 'VIDEO', duration: 20, rid: fixture.resourceId },
      { name: 'NAS 第二课', type: 'VIDEO', duration: 20, rid: interfaceResource.id },
    ],
  });
  const courseList = await api('/backend/v1/course/index');
  await api('/backend/v1/course/create', {
    title: 'CI课时排序测试', thumb: 0, short_desc: 'ordering fixture',
    is_show: 1, is_required: 0, dep_ids: [], category_ids: [], chapters: [],
    hours: [
      { name: '第10课', type: 'VIDEO', duration: 20, rid: fixture.resourceId },
      { name: '第2课', type: 'VIDEO', duration: 20, rid: interfaceResource.id },
    ],
  });
  const orderingCourse = (await api('/backend/v1/course/index')).data.find(item => item.title === 'CI课时排序测试');
  assert(orderingCourse);
  const orderingPath = '/backend/v1/course/' + orderingCourse.id;
  const originalHours = (await api(orderingPath)).hours['0'];
  await admin.goto('http://127.0.0.1:9900/course');
  await admin.getByRole('row').filter({ hasText: 'CI课时排序测试' }).getByRole('button', { name: /更多/ }).hover();
  await admin.getByRole('button', { name: '课时', exact: true }).click();
  const hoursDrawer = admin.getByRole('dialog', { name: '课时管理' });
  await hoursDrawer.getByRole('button', { name: '排序并保存', exact: true }).click();
  await admin.getByRole('dialog', { name: '调整课时顺序' }).getByRole('button', { name: '排序并保存', exact: true }).click();
  await admin.getByText('课时顺序已保存', { exact: true }).waitFor();
  assert.deepEqual((await api(orderingPath)).hours['0'].map(item => item.title), ['第2课', '第10课']);
  await admin.screenshot({ path: 'nas-browser-results/course-hour-ordering.png', fullPage: true });
  await hoursDrawer.locator('.ant-drawer-close').click();
  for (const [field, order] of [['name', 'desc'], ['modified_at', 'asc'], ['modified_at', 'desc']]) {
    await api(orderingPath + '/hour/update/auto-sort', { field, order }, fixture.adminToken, 'PUT');
    const sorted = (await api(orderingPath)).hours['0'];
    assert.deepEqual(sorted.map(item => item.id).sort((a,b) => a-b), originalHours.map(item => item.id).sort((a,b) => a-b));
    if (field === 'name') assert.deepEqual(sorted.map(item => item.title), ['第10课', '第2课']);
  }
  await denied(orderingPath + '/hour/update/auto-sort', { field: 'invalid', order: 'asc' }, fixture.adminToken, 'PUT');
  await api(orderingPath, undefined, fixture.adminToken, 'DELETE');
  const course = courseList.data.find(item => item.title === 'NAS 原目录测试课程');
  assert(course);
  const courseDetail = await api('/backend/v1/course/' + course.id);
  const hour = courseDetail.hours['0'][0];
  const nextHour = courseDetail.hours['0'][1];
  assert(hour);
  assert(nextHour);
  await api('/backend/v1/user/create', {
    email: 'nas-fixture@example.com', password: 'ci-only-learner-password',
    name: 'NAS测试学员', avatar: 0, dep_ids: [],
  });
  const login = await api('/api/v1/auth/login/password', {
    email: 'nas-fixture@example.com', password: 'ci-only-learner-password',
  }, '');
  const learnerToken = login.token;
  const firstPath = '/api/v1/course/' + course.id + '/hour/' + hour.id;
  const nextPath = '/api/v1/course/' + course.id + '/hour/' + nextHour.id;
  const firstTracks = (await api(firstPath + '/play', undefined, learnerToken)).subtitle_tracks;
  const nextTracks = (await api(nextPath + '/play', undefined, learnerToken)).subtitle_tracks;
  assert.equal(firstTracks.length, 4);
  assert.equal(nextTracks.length, 1);
  const englishText = firstTracks.find(track => track.format === 'vtt' && track.language.startsWith('en'));
  const englishBitmap = firstTracks.find(track => track.format === 'vobsub' && track.language.startsWith('en'));
  const chineseBitmap = firstTracks.find(track => track.format === 'vobsub' && track.language.startsWith('zh'));
  assert(englishText && englishBitmap?.data_url && chineseBitmap?.data_url);
  for (const url of [englishText.url, englishBitmap.data_url]) {
    const anonymous = await adminContext.request.get('http://127.0.0.1:9900/api' + url);
    assert.equal(anonymous.status(), 401);
  }
  await api('/backend/v1/app-config', { data: { 'player.sequential_learning': '1' } }, fixture.adminToken, 'PUT');
  const initiallyLocked = await api('/api/v1/course/' + course.id, undefined, learnerToken);
  assert.deepEqual(initiallyLocked.locked_hour_ids, [nextHour.id]);
  await denied(nextPath, undefined, learnerToken);
  await denied(nextPath + '/play', undefined, learnerToken);
  await denied(nextTracks[0].url, undefined, learnerToken);
  await denied(nextPath + '/record', {
    duration: 20, position: 20, clientId: 'ci-locked-forgery', sequence: 1,
  }, learnerToken);
  await denied(nextPath + '/ping', {}, learnerToken);
  assert.equal((await api('/api/v1/course/' + course.id, undefined, learnerToken)).learn_hour_records[nextHour.id], undefined);
  await api('/backend/v1/app-config', { data: { 'player.sequential_learning': '0' } }, fixture.adminToken, 'PUT');
  await api(nextPath + '/play', undefined, learnerToken);
  await api('/backend/v1/app-config', { data: { 'player.sequential_learning': '1' } }, fixture.adminToken, 'PUT');
  await denied('/backend/v1/resource/destroy-multi', { ids: [copiedIds[200], fixture.resourceId] });
  assert.equal((await api('/backend/v1/resource/' + copiedIds[200])).resources.id, copiedIds[200]);
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
    const directory = 'https://localhost:8443/course/' + course.id;
    await learner.goto(directory);
    const lockedHour = learner.locator('[aria-disabled="true"]').filter({ hasText: 'NAS 第二课' });
    await lockedHour.waitFor();
    await lockedHour.click({ force: true });
    assert.equal(learner.url(), directory);
    const address = 'https://localhost:8443/course/' + course.id + '/hour/' + hour.id;
    await learner.goto(address);
    await learner.waitForFunction(() => window.player?.video?.readyState >= 2);
    if (previous > 0) await learner.waitForFunction(value => window.player.video.currentTime >= value, previous);
    const subtitleMenu = learner.getByRole('combobox', { name: '字幕' });
    const waitForSubtitle = text => learner.waitForFunction(value => {
      const node = document.querySelector('.playedu-subtitle-text');
      return node && !node.hidden && node.textContent.trim() === value;
    }, text);
    await subtitleMenu.waitFor();
    assert.equal(await subtitleMenu.locator('option').count(), 5);
    await waitForSubtitle('中文字幕测试');
    await subtitleMenu.selectOption(englishText.id);
    await waitForSubtitle('English subtitle test');
    await subtitleMenu.selectOption(englishBitmap.id);
    const startPosition = await learner.evaluate(() => window.player.video.currentTime);
    await learner.evaluate(() => { window.player.video.currentTime = 1; });
    const waitForBitmap = color => learner.waitForFunction(channel => {
      const canvas = document.querySelector('canvas.playedu-subtitle-canvas');
      if (!canvas || canvas.hidden || canvas.width !== 320 || canvas.height !== 180) return false;
      const pixel = canvas.getContext('2d').getImageData(100, 142, 1, 1).data;
      return pixel[channel] === 255 && pixel[1 - channel] === 0 && pixel[3] === 255;
    }, color);
    await waitForBitmap(0);
    await subtitleMenu.selectOption(chineseBitmap.id);
    await waitForBitmap(1);
    await learner.screenshot({ path: 'nas-browser-results/' + (mobile ? 'tablet' : 'desktop') + '-vobsub.png', fullPage: true });
    await subtitleMenu.selectOption('');
    assert.equal(await subtitleMenu.inputValue(), '');
    await learner.evaluate(value => { window.player.video.currentTime = value; }, startPosition);
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
    await subtitleMenu.waitFor();
    assert.equal(await subtitleMenu.inputValue(), '');
    await learner.screenshot({ path: 'nas-browser-results/' + (mobile ? 'android-layout' : 'desktop') + '-resume.png', fullPage: true });
    await learnerContext.close();
  }
  const savedBeforeMove = await api(firstPath, undefined, learnerToken);
  await bulk('move', [fixture.resourceId], [categoryA]);
  const savedAfterMove = await api(firstPath, undefined, learnerToken);
  assert.equal(savedAfterMove.playback_position.position, savedBeforeMove.playback_position.position);
  assert.equal(savedAfterMove.user_hour_record.finished_duration, savedBeforeMove.user_hour_record.finished_duration);
  await api('/api/v1/course/' + course.id + '/hour/' + hour.id + '/record', {
    duration: 20, position: 20, clientId: 'ci-nas-completion', sequence: 1,
  }, learnerToken);
  const finished = await api('/api/v1/course/' + course.id + '/hour/' + hour.id, undefined, learnerToken);
  assert.equal(finished.user_hour_record.is_finished, 1);
  assert.equal(finished.playback_position.position, 20);
  assert.deepEqual((await api('/api/v1/course/' + course.id, undefined, learnerToken)).locked_hour_ids, []);
  await api(nextPath + '/play', undefined, learnerToken);
  await api('/backend/v1/app-config', { data: { 'player.sequential_learning': '0' } }, fixture.adminToken, 'PUT');
  await api('/backend/v1/course/' + course.id, undefined, fixture.adminToken, 'DELETE');
  assert.deepEqual(errors, []);
  console.log('Subtitle checks passed: NAS sidecar discovery, unauthorized and locked-hour access, Chinese SRT, English VTT, VobSub bitmap, off preference, and desktop/tablet rendering.');
  console.log('Browser checks passed: 100 real video uploads with ten active tasks, 101-file and over-10GiB rejection, object integrity, 200-item NAS selection, bulk toolbar/copy/move/categories/deletion, original MP4 preview/seek/pause, desktop and Android resume, ordered learning enforcement/unlock, completion.');
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
