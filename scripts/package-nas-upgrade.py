import gzip
import hashlib
import io
import json
import pathlib
import shutil
import subprocess
import tarfile
import tempfile

root = pathlib.Path(__file__).resolve().parents[1]
destination = root / 'nas-library-upgrade'
destination.mkdir(exist_ok=True)
reports = []


def add_file(archive, name, payload):
    info = tarfile.TarInfo(name)
    info.size = len(payload)
    info.mode = 0o644
    info.mtime = 0
    archive.addfile(info, io.BytesIO(payload))


for tag in ('playedu-teaching-api:uploads-v1', 'playedu-teaching-web:uploads-v1'):
    with tempfile.TemporaryFile() as saved:
        subprocess.run(['docker', 'save', tag], stdout=saved, check=True)
        saved.seek(0)
        with tarfile.open(fileobj=saved, mode='r:') as archive:
            manifest = json.load(archive.extractfile('manifest.json'))
            assert len(manifest) == 1 and manifest[0]['RepoTags'] == [tag]
            entry = manifest[0]
            config_bytes = archive.extractfile(entry['Config']).read()
            config_id = hashlib.sha256(config_bytes).hexdigest()
            config = json.loads(config_bytes)
            assert config['architecture'] == 'amd64' and config['os'] == 'linux'
            diffs = config['rootfs']['diff_ids']
            assert len(diffs) == len(entry['Layers'])
            filename = tag.replace(':', '-') + '.tar.gz'
            exported_layers = []
            seen = set()
            with tarfile.open(destination / filename, 'w:gz', compresslevel=6) as output:
                add_file(output, config_id + '.json', config_bytes)
                previous = None
                for layer, diff in zip(entry['Layers'], diffs):
                    payload = archive.extractfile(layer).read()
                    if payload[:2] == b'\x1f\x8b':
                        payload = gzip.decompress(payload)
                    digest = hashlib.sha256(payload).hexdigest()
                    assert diff == 'sha256:' + digest
                    name = digest + '/layer.tar'
                    exported_layers.append(name)
                    if digest not in seen:
                        add_file(output, name, payload)
                        add_file(output, digest + '/VERSION', b'1.0')
                        metadata = {'id': digest}
                        if previous:
                            metadata['parent'] = previous
                        if config.get('created'):
                            metadata['created'] = config['created']
                        add_file(output, digest + '/json', json.dumps(metadata).encode())
                        seen.add(digest)
                    previous = digest
                exported = {'Config': config_id + '.json', 'RepoTags': [tag], 'Layers': exported_layers}
                add_file(output, 'manifest.json', json.dumps([exported]).encode())
                repository, version = tag.rsplit(':', 1)
                add_file(output, 'repositories', json.dumps({repository: {version: previous}}).encode())
    with tarfile.open(destination / filename, 'r:gz') as packaged:
        verified = json.load(packaged.extractfile('manifest.json'))[0]
        assert verified['RepoTags'] == [tag]
        assert packaged.extractfile(verified['Config']).read() == config_bytes
        for layer, diff in zip(verified['Layers'], diffs):
            assert 'sha256:' + hashlib.sha256(packaged.extractfile(layer).read()).hexdigest() == diff
    digest = hashlib.file_digest((destination / filename).open('rb'), 'sha256').hexdigest()
    reports.append({'file': filename, 'tag': tag, 'image_id': 'sha256:' + config_id,
                    'sha256': digest, 'verified_layers': len(diffs)})
for name in ('compose.nas.yaml', 'NAS-VIDEO.zh-CN.md', 'VIDEO-MANAGEMENT.zh-CN.md'):
    shutil.copy(root / 'deploy/synology' / name, destination / name)
(destination / 'BUILD.txt').write_text('Source commit: ' + subprocess.check_output(
    ['git', 'rev-parse', 'HEAD'], text=True).strip() + '\n', encoding='utf-8')
(destination / 'image-verification.json').write_text(json.dumps(reports, indent=2), encoding='utf-8')
(destination / 'SHA256SUMS.txt').write_text(''.join(
    item['sha256'] + '  ' + item['file'] + '\n' for item in reports), encoding='utf-8')
print(json.dumps(reports, indent=2))
