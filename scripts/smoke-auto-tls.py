import json
import pathlib
import shutil
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.request
import uuid

root = pathlib.Path(__file__).resolve().parents[1]
prefix = 'teaching-tls-' + uuid.uuid4().hex[:8]
containers = []

def command(args, check=True):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if check and result.returncode:
        raise RuntimeError('Command failed: ' + ' '.join(map(str, args[:5])) + '\n' + result.stdout[-12000:])
    return result

def docker(*args, check=True):
    return command(['docker', *map(str, args)], check=check)

def port(name, target):
    return int(docker('port', name, str(target) + '/tcp').stdout.strip().split(':')[-1])

with tempfile.TemporaryDirectory(prefix='playedu-tls-test-') as folder:
    path = pathlib.Path(folder)
    for name in ('certs', 'state', 'config'):
        (path / name).mkdir()
    command(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2',
             '-subj', '/CN=pebble', '-addext', 'subjectAltName=DNS:pebble,DNS:localhost',
             '-keyout', str(path/'pebble.key'), '-out', str(path/'pebble.crt')])
    (path/'pebble.json').write_text(json.dumps({'pebble': {
        'listenAddress':'0.0.0.0:14000', 'managementListenAddress':'0.0.0.0:15000',
        'certificate':'/test/pebble.crt', 'privateKey':'/test/pebble.key',
        'httpPort':5002, 'tlsPort':5001, 'externalAccountBindingRequired':False}}))
    (path/'upstream.conf').write_text('events {}\nhttp { server { listen 8080; location / { return 200 "classroom-test"; } } server { listen 8333; location / { return 200 "video-test"; } } }\n')
    (path/'dns_ali.sh').write_text('#!/bin/sh\ndns_ali_add() { return 0; }\ndns_ali_rm() { return 0; }\n')
    (path/'client.sh').write_text('#!/bin/sh\nif [ "${FORCE_RENEW:-0}" = 1 ]; then exec /acmebin/acme.sh "$@" --ca-bundle /test/pebble.crt --force; else exec /acmebin/acme.sh "$@" --ca-bundle /test/pebble.crt; fi\n')
    (path/'client.sh').chmod(0o755)
    (path/'config/acme.env').write_text('ACME_EMAIL=ci@example.test\nAli_Key=ci-only-fake-access-key\nAli_Secret=ci-only-fake-secret\nACME_DNS_WAIT=0\n')
    shutil.copy(root/'deploy/synology/gateway.conf.template', path/'gateway.conf.template')
    docker('network', 'create', prefix)
    try:
        pebble = prefix + '-ca'
        docker('run','-d','--name',pebble,'--network',prefix,'--network-alias','pebble',
               '-p','127.0.0.1::15000','-e','PEBBLE_VA_ALWAYS_VALID=1','-e','PEBBLE_VA_NOSLEEP=1',
               '-e','PEBBLE_WFE_NONCEREJECT=0','-v',str(path)+':/test:ro',
               'ghcr.io/letsencrypt/pebble:latest','-config','/test/pebble.json','-strict=false')
        containers.append(pebble)
        management = 'https://localhost:' + str(port(pebble,15000)) + '/roots/0'
        context = ssl.create_default_context(cafile=str(path/'pebble.crt'))
        for attempt in range(60):
            try:
                with urllib.request.urlopen(management, context=context, timeout=2) as response:
                    (path/'issuer.pem').write_bytes(response.read())
                break
            except OSError:
                time.sleep(1)
        else:
            raise AssertionError('Pebble did not start')
        upstream = prefix + '-upstream'
        docker('run','-d','--name',upstream,'--network',prefix,'--network-alias','web',
               '--network-alias','storage','-v',str(path/'upstream.conf')+':/etc/nginx/nginx.conf:ro','nginx:1.28-alpine')
        containers.append(upstream)
        gateway = prefix + '-gateway'
        docker('run','-d','--name',gateway,'--network',prefix,'-p','127.0.0.1::8443',
               '-e','DOMAIN=classroom.test','-e','WEB_HTTPS_PORT=8443','-e','ADMIN_BIND_IP=127.0.0.1',
               '-e','ADMIN_PORT=9900','-e','TLS_TRUST_BUNDLE=/test/issuer.pem','-e','TEACHING_TLS_POLL_SECONDS=1',
               '-v',str(path)+':/test:ro','-v',str(path/'certs')+':/etc/teaching/certs:ro',
               '-v',str(path/'gateway.conf.template')+':/etc/nginx/templates/default.conf.template:ro',
               'playedu-teaching-gateway:local')
        containers.append(gateway)
        tls_port = port(gateway,8443)
        trusted = ssl.create_default_context(cafile=str(path/'issuer.pem'))
        def connect():
            raw = socket.create_connection(('127.0.0.1',tls_port),timeout=3)
            return trusted.wrap_socket(raw,server_hostname='classroom.test')
        try:
            connection=connect();connection.close()
            raise AssertionError('Gateway served TLS before a valid certificate existed')
        except (OSError, ssl.SSLError):
            pass
        def worker(force=False, client='/test/client.sh'):
            result = docker('run','--rm','--network',prefix,'-e','DOMAIN=classroom.test',
                '-e','TEACHING_ACME_ONCE=1','-e','TEACHING_ACME_SERVER=https://pebble:14000/dir',
                '-e','TEACHING_ACME_CLIENT='+client,'-e','TLS_TRUST_BUNDLE=/test/issuer.pem',
                '-e','FORCE_RENEW='+('1' if force else '0'),'-v',str(path)+':/test:ro',
                '-v',str(path/'config')+':/run/acme-config:ro','-v',str(path/'state')+':/acme.sh',
                '-v',str(path/'certs')+':/certs','-v',str(path/'dns_ali.sh')+':/acmebin/dnsapi/dns_ali.sh:ro',
                'playedu-teaching-acme:local')
            assert 'ci-only-fake-secret' not in result.stdout
            if not (path/'certs/current').exists():
                raise AssertionError('Certificate not installed: '+(path/'state/last-run.log').read_text()[-12000:])
            return result.stdout
        def current_serial():
            return command(['openssl','x509','-in',str(path/'certs/current/tls.crt'),'-noout','-serial']).stdout.strip().split('=')[1]
        def wait_serial(expected):
            for attempt in range(40):
                try:
                    connection=connect();actual=connection.getpeercert()['serialNumber'];connection.close()
                    if int(actual,16)==int(expected,16): return
                except OSError:
                    pass
                time.sleep(1)
            raise AssertionError('Gateway did not load expected certificate')
        worker()
        first=current_serial()
        wait_serial(first)
        live=connect()
        worker(force=True)
        second=current_serial()
        assert first != second, 'Real ACME renewal must issue a different certificate'
        wait_serial(second)
        live.sendall(b'GET / HTTP/1.1\r\nHost: classroom.test\r\nConnection: close\r\n\r\n')
        received=b''
        while b'classroom-test' not in received:
            part=live.recv(8192)
            if not part: break
            received+=part
        assert b'classroom-test' in received, 'Reload interrupted an existing connection'
        live.close()
        installed_mtime=(path/'certs/current/tls.crt').stat().st_mtime_ns
        worker()
        assert current_serial()==second, 'Restart unnecessarily renewed a still-valid certificate'
        assert (path/'certs/current/tls.crt').stat().st_mtime_ns==installed_mtime, 'Reinstall rewrote the active certificate generation'
        worker(client='/bin/false')
        assert current_serial()==second, 'Failed renewal replaced current certificate'
        good=(path/'certs/current').resolve()
        shutil.copy(good/'tls.crt',path/'certs/.incoming/tls.crt')
        command(['openssl','genrsa','-out',str(path/'certs/.incoming/tls.key'),'2048'])
        rejected=docker('run','--rm','--entrypoint','/bin/sh','-e','DOMAIN=classroom.test',
               '-e','TLS_TRUST_BUNDLE=/test/issuer.pem','-v',str(path)+':/test:ro',
               '-v',str(path/'certs')+':/certs','playedu-teaching-acme:local',
               '/opt/teaching/publish-certificate.sh',check=False)
        assert rejected.returncode!=0, 'Mismatched private key accepted'
        assert (path/'certs/current').resolve()==good
        shutil.copy(good/'tls.key',path/'certs/.incoming/tls.key')
        rejected=docker('run','--rm','--entrypoint','/bin/sh','-e','DOMAIN=wrong.test',
               '-e','TLS_TRUST_BUNDLE=/test/issuer.pem','-v',str(path)+':/test:ro',
               '-v',str(path/'certs')+':/certs','playedu-teaching-acme:local',
               '/opt/teaching/publish-certificate.sh',check=False)
        assert rejected.returncode!=0, 'Wrong hostname accepted'
        assert (path/'certs/current').resolve()==good
        invalid=path/'certs/managed/bad';invalid.mkdir()
        shutil.copy(good/'tls.crt',invalid/'tls.crt')
        command(['openssl','genrsa','-out',str(invalid/'tls.key'),'2048'])
        (path/'certs/current').unlink();(path/'certs/current').symlink_to('managed/bad')
        time.sleep(3)
        wait_serial(second)
        (path/'certs/current').unlink();(path/'certs/current').symlink_to('managed/'+good.name)
        print('Automatic TLS passed: real ACME issuance and renewal, trusted chain, atomic install, graceful reload, durable state, failure recovery, hostname/key rejection, no secret logging.')
    except Exception:
        for name in containers:
            print(docker('logs','--tail','30',name,check=False).stdout)
        if (path/'state/last-run.log').exists(): print((path/'state/last-run.log').read_text()[-12000:])
        raise
    finally:
        for name in reversed(containers): docker('rm','-f',name,check=False)
        docker('network','rm',prefix,check=False)
