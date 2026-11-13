import base64
import hashlib
import json
import os
import pathlib
import shutil
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

root = pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='playedu-nas-smoke-') as folder:
    path = pathlib.Path(folder)
    library = path / 'library'
    library.mkdir()
    (library / '章节').mkdir()
    (library / 'outside.mp4').symlink_to('/etc/passwd')
    for name in ('compose.nas.yaml', 'gateway.conf.template'):
        shutil.copy(root / 'deploy/synology' / name, path / name)
    (path / 'certs').mkdir()
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2',
                    '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost',
                    '-keyout', str(path / 'certs/tls.key'), '-out', str(path / 'certs/tls.crt')],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    (path / '.env').write_text('DOMAIN=localhost\nWEB_HTTPS_PORT=8443\nSTORAGE_HTTPS_PORT=9443\n'
        'ADMIN_BIND_IP=127.0.0.1\nADMIN_PORT=9900\nDB_PASSWORD=ci-only-database-password\n'
        'JWT_SECRET=ci-only-jwt-secret-0000000000000000000000000000000000000000\n'
        'STORAGE_ACCESS_KEY=ci-only-access\nSTORAGE_SECRET_KEY=ci-only-storage-password\n'
        'NAS_VIDEO_DIR=' + str(library) + '\n', encoding='utf-8')
    (path / 'test-trust.yaml').write_text('services:\n  gateway:\n    environment:\n'
        '      TLS_TRUST_BUNDLE: /etc/teaching/certs/tls.crt\n', encoding='utf-8')
    compose = ['docker', 'compose', '--project-name', 'playedu-nas-smoke', '--project-directory',
        str(path), '-f', str(path / 'compose.nas.yaml'), '-f', str(path / 'test-trust.yaml')]
    context = ssl.create_default_context(cafile=str(path / 'certs/tls.crt'))
    base = 'https://localhost:8443/api'

    def request(url, token=None, data=None, method=None, headers=None):
        extra = dict(headers or {})
        if token:
            extra['Authorization'] = 'Bearer ' + token
        payload = None
        if data is not None:
            payload = json.dumps(data).encode()
            extra['Content-Type'] = 'application/json'
        req = urllib.request.Request(url, payload, extra, method=method)
        try:
            with urllib.request.urlopen(req, context=context, timeout=65) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.headers, error.read()

    def api(route, token=None, data=None, method=None):
        status, _, body = request(base + route, token, data, method)
        result = json.loads(body)
        assert status == 200 and result['code'] == 0, (route, status, result)
        return result.get('data')

    subprocess.run(compose + ['config', '--quiet'], check=True)
    try:
        subprocess.run(['docker', 'run', '--rm', '--entrypoint', '/usr/bin/ffmpeg',
            '-v', str(library) + ':/fixtures', 'playedu-teaching-api:subtitles-v1', '-v', 'error',
            '-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=12', '-t', '20',
            '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
            '/fixtures/章节/第一课.mp4'], check=True)
        shutil.copy(library / '章节/第一课.mp4', library / '界面测试.mp4')
        (library / '章节/第一课.zh-CN.srt').write_text(
            '1\n00:00:00,000 --> 00:00:19,999\n中文字幕测试\n', encoding='utf-8')
        (library / '章节/第一课.en.vtt').write_text(
            'WEBVTT\n\n00:00:00.000 --> 00:00:19.999\nEnglish subtitle test\n', encoding='utf-8')
        (library / '界面测试.zh-CN.srt').write_text(
            '1\n00:00:00,000 --> 00:00:19,999\n第二课字幕\n', encoding='utf-8')
        subprocess.run(['node', '--input-type=module', '-e',
            "import fs from 'node:fs'; import {createVobSubFixture} from './scripts/vobsub-fixture.mjs'; "
            "const {indexText,sub}=createVobSubFixture(); fs.writeFileSync(process.argv[1]+'.idx',indexText); "
            "fs.writeFileSync(process.argv[1]+'.sub',sub);", str(library / '章节/第一课')],
            cwd=root, check=True)
        (library / '损坏视频.mp4').write_bytes(b'invalid video fixture')
        (library / '批量选择').mkdir()
        for index in range(201):
            destination = library / '批量选择' / f'批量{index:03}.mp4'
            shutil.copyfile(library / '章节/第一课.mp4', destination)
            os.utime(destination, (1700000000 + 200 - index, 1700000000 + 200 - index))
        sources = {str(p.relative_to(library)): hashlib.sha256(p.read_bytes()).hexdigest()
                   for p in library.rglob('*') if p.is_file() and not p.is_symlink()}
        subprocess.run(compose + ['up', '-d'], check=True)
        for attempt in range(100):
            try:
                if api('/api/v1/system/config'):
                    break
            except (OSError, ValueError, AssertionError):
                pass
            time.sleep(2)
        else:
            raise AssertionError('NAS test API did not start')
        admin = api('/backend/v1/auth/login', data={'email': 'admin@playedu.xyz', 'password': 'playedu'})['token']
        assert api('/backend/v1/nas-video/status', admin) == {'enabled': True, 'mounted': True}
        assert request(base + '/backend/v1/nas-video/browse')[0] == 401
        listing = api('/backend/v1/nas-video/browse', admin)['entries']
        assert not any(item['name'] == 'outside.mp4' for item in listing)
        assert any(item['name'] == '章节' and item['directory'] for item in listing)
        for field, order, expected in (('name', 'asc', '批量000.mp4'),
                ('name', 'desc', '批量200.mp4'), ('modified_at', 'asc', '批量200.mp4'),
                ('modified_at', 'desc', '批量000.mp4')):
            query = urllib.parse.urlencode({'path': '批量选择', 'size': 200,
                'sort_field': field, 'sort_order': order})
            sorted_listing = api('/backend/v1/nas-video/browse?' + query, admin)
            assert sorted_listing['entries'][0]['name'] == expected
            assert len(sorted_listing['entries']) == 200 and sorted_listing['total'] == 201
        for invalid in ('../etc/passwd.mp4', '/etc/passwd', 'outside.mp4'):
            status, _, body = request(base + '/backend/v1/nas-video/import', admin,
                {'path': invalid, 'category_ids': []})
            assert status != 200 or json.loads(body)['code'] != 0
        payload = {'path': '章节/第一课.mp4', 'category_ids': []}
        entry = api('/backend/v1/nas-video/import', admin, payload)
        assert entry['created'] and entry['disk'] == 'nas' and entry['duration'] == 20
        assert entry['video_codec'] == 'h264'
        duplicate = api('/backend/v1/nas-video/import', admin, payload)
        assert not duplicate['created'] and duplicate['id'] == entry['id']
        resources = api('/backend/v1/resource/index?type=VIDEO', admin)
        url = resources['resource_url'][str(entry['id'])]
        original = (library / '章节/第一课.mp4').read_bytes()
        status, headers, content = request(url, headers={'Range': 'bytes=5-99'})
        assert status == 206 and content == original[5:100]
        assert headers['Content-Range'] == 'bytes 5-99/' + str(len(original))
        assert headers['Content-Length'] == '95'
        assert request(url, method='HEAD')[2] == b''
        status, _, content = request(url)
        assert status == 200 and content == original
        assert request(url, headers={'Range': 'bytes=-10'})[2] == original[-10:]
        assert request(url, headers={'Range': 'bytes=999999999-'})[0] == 416
        assert request(url, headers={'Range': 'bytes=0-1,5-7'})[0] == 416
        assert request(url.replace('kind=original', 'kind=download'))[0] == 403
        assert request(url.replace('/' + str(entry['id']) + '/file', '/999999/file'))[0] == 403
        parts = urllib.parse.urlsplit(url)
        query = urllib.parse.parse_qs(parts.query)
        query['expires'] = ['1']
        expired = urllib.parse.urlunsplit(parts._replace(query=urllib.parse.urlencode(query, doseq=True)))
        assert request(expired)[0] == 403
        assert request(url, headers={'Origin': 'https://untrusted.example'})[0] == 403
        preflight = request(url, method='OPTIONS', headers={'Origin': 'http://127.0.0.1:9900',
            'Access-Control-Request-Method': 'GET', 'Access-Control-Request-Headers': 'range'})
        assert preflight[0] == 200 and preflight[1]['Access-Control-Allow-Origin'] == 'http://127.0.0.1:9900'
        api_id = subprocess.check_output(compose + ['ps', '-q', 'api'], text=True).strip()
        mounts = json.loads(subprocess.check_output(['docker', 'inspect', api_id], text=True))[0]['Mounts']
        assert any(item['Destination'] == '/nas-videos' and not item['RW'] for item in mounts)
        public_key = subprocess.check_output(['openssl', 'x509', '-in', str(path / 'certs/tls.crt'), '-pubkey', '-noout'])
        public_der = subprocess.check_output(['openssl', 'pkey', '-pubin', '-outform', 'DER'], input=public_key)
        fixture = {'adminToken': admin, 'resourceId': entry['id'],
            'uploadVideoPath': str(library / '章节/第一课.mp4'),
            'certificatePin': base64.b64encode(hashlib.sha256(public_der).digest()).decode()}
        fixture_file = path / 'browser-fixture.json'
        fixture_file.write_text(json.dumps(fixture), encoding='utf-8')
        subprocess.run(['node', str(root / 'scripts/smoke-nas-browser.mjs'), str(fixture_file)], check=True)
        subprocess.run(compose + ['stop', 'api'], check=True)
        database_id = subprocess.check_output(compose + ['ps', '-q', 'db'], text=True).strip()
        assert database_id and isinstance(entry['id'], int) and entry['id'] > 0
        legacy_sql = ('ALTER TABLE resource DROP COLUMN updated_at; '
            "UPDATE resource SET created_at='2020-01-02 03:04:05' WHERE id=" + str(entry['id']) + ';')
        subprocess.run(['docker', 'exec', '-e', 'MYSQL_PWD=ci-only-database-password',
            database_id, 'mysql', '-u', 'root', '-D', 'playedu', '-e', legacy_sql], check=True)
        subprocess.run(compose + ['start', 'api'], check=True)
        for attempt in range(100):
            try:
                admin = api('/backend/v1/auth/login', data={'email': 'admin@playedu.xyz', 'password': 'playedu'})['token']
                migrated = api('/backend/v1/resource/' + str(entry['id']), admin)['resources']
                assert migrated['updated_at'] == migrated['created_at']
                assert str(migrated['created_at']).startswith('2020-')
                break
            except (OSError, ValueError, AssertionError, KeyError):
                time.sleep(2)
        else:
            raise AssertionError('Legacy resource modification timestamps were not backfilled')
        print('Legacy migration check passed: historical creation time preserved as modification time.')
        api('/backend/v1/resource/' + str(entry['id']), admin, method='DELETE')
        assert request(url)[0] == 404
        assert sources == {str(p.relative_to(library)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in library.rglob('*') if p.is_file() and not p.is_symlink()}
        print('NAS checks passed: read-only source, Unicode browse/import, duplicate identity, signing, ranges, CORS, browser playback/resume, reference-only deletion.')
    except Exception:
        subprocess.run(compose + ['logs', '--tail', '40'], check=False)
        raise
    finally:
        subprocess.run(compose + ['down'], check=False)
        if path.parent.resolve() != pathlib.Path(tempfile.gettempdir()).resolve() or not path.name.startswith('playedu-nas-smoke-'):
            raise RuntimeError('Unexpected NAS smoke-test directory')
        subprocess.run(['sudo', 'chown', '-R', str(os.getuid()) + ':' + str(os.getgid()), str(path)], check=True)
