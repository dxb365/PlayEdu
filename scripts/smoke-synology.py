"""Boot the exact NAS compose stack in temporary directories; never package test keys."""
import json, os, pathlib, shutil, ssl, subprocess, tempfile, time, urllib.request, urllib.error
import boto3
from botocore.config import Config
root = pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='playedu-smoke-') as folder:
    path = pathlib.Path(folder)
    for name in ('compose.yaml', 'gateway.conf.template'):
        shutil.copy(root / 'deploy/synology' / name, path / name)
    (path / 'certs').mkdir()
    subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost','-keyout',str(path/'certs/tls.key'),'-out',str(path/'certs/tls.crt')],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    (path/'.env').write_text('DOMAIN=localhost\nWEB_HTTPS_PORT=8443\nSTORAGE_HTTPS_PORT=9443\nADMIN_BIND_IP=127.0.0.1\nADMIN_PORT=9900\nDB_PASSWORD=ci-only-database-password\nJWT_SECRET=ci-only-jwt-secret-0000000000000000000000000000000000000000\nSTORAGE_ACCESS_KEY=ci-only-access\nSTORAGE_SECRET_KEY=ci-only-storage-password\n')
    compose=['docker','compose','--project-name','playedu-smoke','--project-directory',str(path),'-f',str(path/'compose.yaml')]
    subprocess.run(compose+['config','--quiet'],check=True)
    try:
        subprocess.run(compose+['up','-d'],check=True)
        context=ssl.create_default_context(cafile=str(path/'certs/tls.crt'))
        endpoint='https://localhost:8443/api/api/v1/system/config'
        for attempt in range(90):
            try:
                with urllib.request.urlopen(endpoint,context=context,timeout=4) as response:
                    config=json.load(response)
                if config.get('code')==0: break
            except (OSError,ValueError): pass
            time.sleep(2)
        else: raise AssertionError('API failed to start')
        assert config['data']['player-auto-resume']=='1'
        assert config['data']['player-disabled-speed']=='0'
        for agent in ('Mozilla/5.0 desktop','Mozilla/5.0 Android Mobile'):
            request=urllib.request.Request('https://localhost:8443/',headers={'User-Agent':agent})
            with urllib.request.urlopen(request,context=context) as response:
                assert b'<html' in response.read().lower()
        with urllib.request.urlopen('http://127.0.0.1:9900/') as response:
            assert b'<html' in response.read().lower()
        s3=boto3.client('s3',endpoint_url='https://localhost:9443',region_name='us-east-1',aws_access_key_id='ci-only-access',aws_secret_access_key='ci-only-storage-password',verify=str(path/'certs/tls.crt'),config=Config(signature_version='s3v4',s3={'addressing_style':'path'}))
        for attempt in range(30):
            try:
                s3.head_bucket(Bucket='videos'); break
            except Exception:
                if attempt==29: raise
                time.sleep(2)
        acl=s3.get_bucket_acl(Bucket='videos')
        assert not any(g.get('Grantee',{}).get('Type')=='Group' for g in acl.get('Grants',[]))
        s3.put_object(Bucket='videos',Key='smoke.mp4',Body=b'0123456789')
        url=s3.generate_presigned_url('get_object',Params={'Bucket':'videos','Key':'smoke.mp4'},ExpiresIn=60)
        with urllib.request.urlopen(urllib.request.Request(url,headers={'Range':'bytes=2-5'}),context=context) as response:
            assert response.status==206
            assert response.read()==b'2345'
        try:
            urllib.request.urlopen('https://localhost:9443/videos/smoke.mp4',context=context)
            raise AssertionError('Video must not be publicly readable without a signature')
        except urllib.error.HTTPError as error:
            assert error.code in (401,403)
        print('NAS smoke test passed: API, PC/mobile, admin, TLS, private bucket, signed video range requests.')
    except Exception:
        subprocess.run(compose+['logs','--tail','80'],check=False)
        raise
    finally:
        subprocess.run(compose+['down'],check=False)
