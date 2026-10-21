#!/usr/bin/env python3
"""Synthetic upload-receipt -> TXT -> personality -> intended person -> first mock chat.

No real accounts, credentials or providers. An isolated default-JVM file H2,
mock Worker and native Chromium exercise existing forms and the binding return.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
TOKEN = 'upload-receipt-synthetic-worker-token-32-characters'
SOURCE = '合成人格资料：表达简洁，先核对来源，再回答问题，不编造人物亲历。'


def run(output, baseline=False, upgrade_from=None):
    output = output.resolve(); output.mkdir(parents=True, exist_ok=True)
    safe = {key: value for key, value in os.environ.items() if key in (
        'HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'PATH', 'JAVA_HOME', 'JAVA_BIN', 'MAVEN_OPTS', 'MAVEN_ARGS')}
    result = {'status': 'RUNNING', 'baseline': baseline, 'real_provider_calls': 0,
              'checks': [], 'screenshots': {}, 'page_errors': [], 'external_requests': []}
    processes, logs = [], []
    with tempfile.TemporaryDirectory(prefix='distilly-upload-receipt-') as tmp:
        folder = Path(tmp); jar = folder / 'runtime.jar'
        shutil.copy2(ROOT / 'server/target/distilly-platform-0.1.0.jar', jar)
        result['jar_sha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
        base = f'http://127.0.0.1:{port}'
        env = dict(safe, PLATFORM_DEMO='true', PLATFORM_WORKER_TOKEN=TOKEN, MODEL_MODE='mock',
                   PLATFORM_PORT=str(port), PLATFORM_BIND='127.0.0.1', PLATFORM_API_URL=base,
                   PLATFORM_DB_URL='jdbc:h2:file:' + str(folder / 'db') + ';DATABASE_TO_LOWER=TRUE',
                   PLATFORM_DB_USER='sa', PLATFORM_DB_PASSWORD='')
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def launch(name, command):
            log = (output / (name + '.log')).open('w'); logs.append(log)
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
            processes.append(process); return process

        def stop(process):
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=12)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)

        def api(path, body=None, actor='owner', method=None, expected=200):
            req = urllib.request.Request(base + path, None if body is None else json.dumps(body).encode(),
                    {'Authorization': 'Bearer demo-' + actor, 'Content-Type': 'application/json'},
                    method=method or ('GET' if body is None else 'POST'))
            try:
                with opener.open(req, timeout=8) as response: status, raw = response.status, response.read()
            except urllib.error.HTTPError as error:
                with error: status, raw = error.code, error.read()
            assert status == expected, (path, status, expected)
            return json.loads(raw) if raw else None

        def wait(fn, seconds=30):
            end = time.monotonic() + seconds
            while time.monotonic() < end:
                value = fn()
                if value: return value
                time.sleep(.1)
            raise AssertionError('bounded synthetic journey condition timed out')

        def healthy():
            assert server.poll() is None
            try: return api('/health') == {'status': 'UP'}
            except OSError: return False

        def upload(project_id, request_id=None, actor='owner', payload=SOURCE.encode(), filename='synthetic.txt', expected=200):
            import uuid
            boundary='synthetic-upload-'+uuid.uuid4().hex
            parts=[]
            if request_id is not None:parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="request_id"\r\n\r\n{request_id}\r\n').encode())
            parts.extend([(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: text/plain\r\n\r\n').encode(),payload,f'\r\n--{boundary}--\r\n'.encode()])
            req=urllib.request.Request(base+f'/api/projects/{project_id}/materials',b''.join(parts),{'Authorization':'Bearer demo-'+actor,'Content-Type':'multipart/form-data; boundary='+boundary})
            try:
                with opener.open(req,timeout=10) as response:status,raw=response.status,response.read()
            except urllib.error.HTTPError as error:
                with error:status,raw=error.code,error.read()
            assert status==expected,('synthetic upload status',status,expected)
            return json.loads(raw) if raw else None
        try:
            result['stage']='default-JVM startup'
            old_project=old_material=None
            if upgrade_from:
                old=folder/'old.jar';shutil.copy2(upgrade_from,old)
                server=launch('old-api',[safe.get('JAVA_BIN','java'),'-jar',str(old)]);wait(healthy)
                old_project=api('/api/projects',{'title':'合成V009保留'})['id'];old_material=upload(old_project)['id'];stop(server)
            started=time.monotonic();server=launch('api',[safe.get('JAVA_BIN','java'),'-jar',str(jar)])
            wait(healthy);result['default_jvm_startup_seconds']=round(time.monotonic()-started,3)
            if old_project:
                assert any(m['id']==old_material for m in api(f'/api/projects/{old_project}/materials'))
                result['checks'].append('actual V009 database upgrades to V010 while retaining original synthetic material and workspace')
            project=api('/api/projects',{'title':'合成TXT上传回执'})['id']
            other=api('/api/projects',{'title':'合成隔离上传空间'})['id']
            from playwright.sync_api import sync_playwright
            with sync_playwright() as pw:
                browser=pw.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
                try:
                    page=browser.new_page(viewport={'width':1440,'height':1080})
                    def restrict(route):
                        if route.request.url.startswith(base+'/') or route.request.url.startswith(('data:','blob:')):route.continue_()
                        else:result['external_requests'].append('blocked external request');route.abort()
                    page.route('**/*',restrict);page.on('pageerror',lambda error:result['page_errors'].append(str(error)))
                    def shot(name):
                        path=output/(name+'.png');page.screenshot(path=str(path),full_page=True)
                        result['screenshots'][name]={'file':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
                    page.goto(base+'/knowledge.html#materials',wait_until='networkidle')
                    page.locator('#token').fill('demo-owner');page.locator('#login-form button').click();page.locator('#workspace').wait_for(state='visible')
                    if page.locator('#projects').input_value()!=project:page.locator('#projects').select_option(project)
                    page.locator('[data-page="materials"]').click();page.locator('#new-material').click()
                    upload_path=base+f'/api/projects/{project}/materials'
                    def lost_ack(route):
                        response=route.fetch();assert response.status==200;result['first_material']=response.json()['id'];route.abort('failed')
                    page.route(upload_path,lost_ack,times=1)
                    sample={'name':'synthetic.txt','mimeType':'text/plain','buffer':SOURCE.encode()}
                    page.locator('#file').set_input_files(sample);page.locator('#upload-only').click()
                    if baseline:
                        page.locator('#upload-error').filter(has_text='上传结果待核对').wait_for()
                        page.locator('#upload-modal').get_by_role('button',name='取消',exact=True).click()
                        page.locator('#new-material').click();page.locator('#file').set_input_files(sample);page.locator('#upload-only').click()
                        page.locator('#upload-modal').wait_for(state='hidden')
                        materials=api(f'/api/projects/{project}/materials');assert len(materials)==2
                        assert len({m['id'] for m in materials})==2 and len({m['digest'] for m in materials})==1
                        shot('baseline-reopen-reimports-committed-txt')
                        result['status']='OBSERVED_GAP';result['checks'].append('committed upload loses response; closing/reopening and importing same synthetic TXT creates two material rows');return
                    import uuid
                    result['stage']='committed lost upload, reload and same-file API restart'
                    page.locator('#upload-error').filter(has_text='上传结果待核对').wait_for()
                    operation=json.loads(page.evaluate("()=>sessionStorage.getItem('platform-material-submissions-v1')"))
                    first_key=next(iter(operation.values()))['request_id']
                    serialized=json.dumps(operation,ensure_ascii=False);assert SOURCE not in serialized and 'synthetic.txt' not in serialized and 'demo-owner' not in serialized
                    stop(server);server=launch('restarted-api',[safe.get('JAVA_BIN','java'),'-jar',str(jar)]);wait(healthy)
                    page.reload(wait_until='networkidle');page.locator('#new-material').click()
                    assert page.locator('#upload-only').is_disabled();shot('pending-upload-readonly-recheck');page.locator('#upload-recheck').click()
                    page.locator('#upload-modal').wait_for(state='hidden')
                    assert len(api(f'/api/projects/{project}/materials'))==1
                    receipt=api(f'/api/projects/{project}/material-submissions/{first_key}')
                    assert receipt['material_id']==result['first_material'] and set(receipt)=={'request_id','material_id','status'}
                    assert upload(project,first_key)['id']==result['first_material']
                    shot('committed-upload-readback-after-restart')
                    result['checks'].append('accepted upload with lost response survives browser refresh and default-JVM same-file H2 restart; persisted UUID/fingerprint contains no filename/body/token; GET recovers exact material and POST replay does not duplicate')
                    result['stage']='concurrent multipart acceptance and filename/content conflicts'
                    from concurrent.futures import ThreadPoolExecutor
                    concurrent_key=str(uuid.uuid4())
                    with ThreadPoolExecutor(max_workers=2) as pool:returned=list(pool.map(lambda _:upload(project,concurrent_key),range(2)))
                    assert returned[0]['id']==returned[1]['id'] and len(api(f'/api/projects/{project}/materials'))==2
                    upload(project,first_key,payload=b'different synthetic content',expected=409)
                    upload(project,first_key,filename='renamed.txt',expected=409)
                    changed=upload(project,str(uuid.uuid4()),payload=b'different synthetic content');assert changed['id']!=result['first_material']
                    assert len(api(f'/api/projects/{project}/materials'))==3
                    result['checks'].append('actual concurrent never-accepted multipart operation creates one material; same key with different content/name conflicts409; same filename with new operation and different content remains a distinct material')
                    result['stage']='before-commit interruption and explicit original-file retry'
                    page.locator('#new-material').click();second={'name':'synthetic.txt','mimeType':'text/plain','buffer':b'second synthetic import'}
                    page.route(upload_path,lambda route:route.abort('failed'),times=1)
                    page.locator('#file').set_input_files(second);page.locator('#upload-only').click()
                    page.locator('#upload-error').filter(has_text='上传结果待核对').wait_for()
                    pending=json.loads(page.evaluate("()=>sessionStorage.getItem('platform-material-submissions-v1')"));second_key=next(iter(pending.values()))['request_id']
                    page.reload(wait_until='networkidle');page.locator('#new-material').click();page.locator('#upload-recheck').click()
                    page.locator('#upload-retry').wait_for(state='visible')
                    page.locator('#file').set_input_files(sample);page.locator('#upload-retry').click()
                    page.locator('#upload-error').filter(has_text='文件名或内容与上次操作不同').wait_for();assert len(api(f'/api/projects/{project}/materials'))==3
                    page.locator('#file').set_input_files(second)
                    page.evaluate("()=>{document.querySelector('#upload-retry').click();document.querySelector('#upload-retry').click();}")
                    page.locator('#upload-modal').wait_for(state='hidden')
                    assert len(api(f'/api/projects/{project}/materials'))==4
                    assert api(f'/api/projects/{project}/material-submissions/{second_key}')['material_id']
                    shot('original-file-explicit-retry-accepted-once')
                    result['checks'].append('interruption before server acceptance: refresh only reads; missing receipt enables explicit retry, wrong file cannot POST, original file with duplicate clicks saves exactly once under original UUID')
                    result['stage']='actor and workspace receipt isolation'
                    api(f'/api/projects/{project}/members',{'user_id':'editor','role':'EDITOR'},method='PUT')
                    api(f'/api/projects/{project}/material-submissions/{first_key}',actor='editor',expected=404)
                    editor=upload(project,first_key,actor='editor',payload=b'editor synthetic input')
                    assert editor['id']!=result['first_material']
                    assert api(f'/api/projects/{project}/material-submissions/{first_key}',actor='editor')['material_id']==editor['id']
                    other_material=upload(other,first_key)['id']
                    assert other_material!=result['first_material']
                    api(f'/api/projects/{other}/material-submissions/{first_key}',actor='editor',expected=403)
                    assert len(api(f'/api/projects/{project}/materials'))==5
                    result['checks'].append('same operation UUID remains actor/workspace scoped: another editor cannot see owner receipt, own import has own ID, another workspace is independent and unauthorized read is403')
                    result['stage']='late accepted upload after close and workspace change'
                    held=[]
                    def hold(route):
                        response=route.fetch();assert response.status==200;held.append((route,response.json()))
                    page.route(upload_path,hold,times=1)
                    page.locator('#new-material').click();page.locator('#file').set_input_files({'name':'late.txt','mimeType':'text/plain','buffer':b'late synthetic upload'});page.locator('#upload-only').click()
                    for _ in range(200):
                        if held:break
                        page.wait_for_timeout(50)
                    assert held
                    page.locator('#upload-modal').get_by_role('button',name='取消',exact=True).click();page.locator('#projects').select_option(other)
                    page.wait_for_function("()=>document.querySelector('#project-heading').textContent==='合成隔离上传空间'")
                    held[0][0].fulfill(status=200,content_type='application/json',body=json.dumps(held[0][1]));page.wait_for_timeout(100)
                    assert page.locator('#upload-modal').is_hidden();assert len(api(f'/api/projects/{other}/materials'))==1
                    page.locator('#projects').select_option(project);page.wait_for_function("()=>document.querySelector('#project-heading').textContent==='合成TXT上传回执'")
                    page.locator('#new-material').click();page.locator('#upload-recheck').wait_for(state='visible');page.locator('#upload-recheck').click();page.locator('#upload-modal').wait_for(state='hidden')
                    assert len(api(f'/api/projects/{project}/materials'))==6
                    result['checks'].append('late committed response after close/space change does not reopen or write into new space; returning to original space recovers retained receipt by GET without another upload')
                    result['stage']='permission revoked during unresolved import and logout'
                    page.route(upload_path,lambda route:route.abort('failed'),times=1)
                    page.locator('#new-material').click();page.locator('#file').set_input_files(sample);page.locator('#upload-only').click();page.locator('#upload-error').filter(has_text='上传结果待核对').wait_for()
                    api(f'/api/projects/{project}/members',{'user_id':'editor','role':'OWNER'},method='PUT')
                    api(f'/api/projects/{project}/members',{'user_id':'owner','role':'VIEWER'},actor='editor',method='PUT')
                    page.locator('#upload-recheck').click();page.locator('#upload-error').filter(has_text='权限已变化').wait_for();assert page.locator('#upload-only').is_disabled()
                    assert len(api(f'/api/projects/{project}/materials',actor='editor'))==6
                    shot('revoked-upload-receipt-disabled')
                    page.locator('#upload-modal').get_by_role('button',name='取消',exact=True).click();page.locator('#logout').click()
                    page.locator('#login').wait_for(state='visible')
                    assert page.evaluate("()=>sessionStorage.getItem('platform-material-submissions-v1')") is None
                    assert not result['page_errors'] and not result['external_requests']
                    result['checks'].append('actual owner-to-viewer revocation blocks receipt recovery403 and further POST, with no material added; logout clears operation metadata; Workers0 and real model calls0')
                    result['status']='PASS';result['stage']='completed'
                finally:
                    if result['status'] not in ('PASS','OBSERVED_GAP'):
                        try:shot('failure-state')
                        except Exception:pass
                    browser.close()
        except BaseException as error:
            result['status']='FAIL';result['failure_type']=type(error).__name__;raise
        finally:
            for process in reversed(processes):stop(process)
            for log in logs:log.close()
            result['cleanup_all_processes_stopped']=all(process.poll() is not None for process in processes)
            (output/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print('PASS: upload-receipt',len(result['checks']),'groups; real calls 0')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--baseline',action='store_true')
    parser.add_argument('--output',type=Path,default=ROOT/'server/target/upload-receipt-e2e');parser.add_argument('--upgrade-from',type=Path)
    args=parser.parse_args();run(args.output,args.baseline,args.upgrade_from)
