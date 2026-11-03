#!/usr/bin/env python3
"""Private local status and explicitly enabled offline sync; never contacts Hindsight."""
from browser_support import launch_chromium
import argparse
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

ROOT=Path(__file__).resolve().parents[1]
TOKEN='memory-sync-native-synthetic-worker-token-32-characters'


def run(output):
    output.mkdir(parents=True,exist_ok=True)
    safe={k:v for k,v in os.environ.items() if k in ('HOME','LANG','LC_ALL','TMPDIR','PATH','JAVA_HOME','JAVA_BIN','MAVEN_OPTS','MAVEN_ARGS')}
    result={'status':'RUNNING','checks':[],'page_errors':[],'external_requests':[],'real_provider_calls':0,'hindsight_actual_sync':'NOT_RUN'}
    processes,logs=[],[]
    with tempfile.TemporaryDirectory(prefix='distilly-memory-consumer-native-') as tmp:
        folder=Path(tmp);jar=folder/'runtime.jar';shutil.copy2(ROOT/'server/target/distilly-platform-0.1.0.jar',jar)
        with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
        base=f'http://127.0.0.1:{port}'
        env=dict(safe,PLATFORM_DEMO='true',PLATFORM_WORKER_TOKEN=TOKEN,MODEL_MODE='mock',PLATFORM_PORT=str(port),PLATFORM_BIND='127.0.0.1',PLATFORM_API_URL=base,
                 PLATFORM_DB_URL='jdbc:h2:file:'+str(folder/'db')+';DATABASE_TO_LOWER=TRUE',PLATFORM_DB_USER='sa',PLATFORM_DB_PASSWORD='')
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        def api(path,body=None,actor='owner',method=None,expected=200):
            request=urllib.request.Request(base+path,None if body is None else json.dumps(body).encode(),{'Authorization':'Bearer demo-'+actor,'Content-Type':'application/json'},method=method or ('GET' if body is None else 'POST'))
            try:
                with opener.open(request,timeout=8) as response:status,raw=response.status,response.read()
            except urllib.error.HTTPError as error:
                with error:status,raw=error.code,error.read()
            assert status==expected,(path,status,expected)
            return json.loads(raw) if raw else None
        def wait(fn,seconds=30):
            end=time.monotonic()+seconds
            while time.monotonic()<end:
                value=fn()
                if value:return value
                time.sleep(.1)
            raise AssertionError('bounded offline consumer condition timed out')
        def launch(name,command):
            log=(output/(name+'.log')).open('w');logs.append(log)
            process=subprocess.Popen(command,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT);processes.append(process);return process
        def stop(process):
            if process.poll() is None:
                process.terminate()
                try:process.wait(timeout=12)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
        def healthy():
            assert server.poll() is None
            try:return api('/health')=={'status':'UP'}
            except OSError:return False
        def job(j):
            def done():
                row=api('/api/persona-jobs/'+j['id']);assert row['status'] not in ('FAILED','CANCELLED'),row.get('error_code')
                return row.get('result') if row['status']=='SUCCEEDED' else None
            return wait(done)
        try:
            result['stage']='default closed'
            server=launch('disabled-api',[safe.get('JAVA_BIN','java'),'-jar',str(jar)]);wait(healthy)
            worker=launch('mock-worker',[sys.executable,'worker/worker.py'])
            project=api('/api/projects',{'title':'合成离线同步可观测'})['id'];api(f'/api/projects/{project}/members',{'user_id':'editor','role':'EDITOR'},method='PUT')
            person=api(f'/api/projects/{project}/personas',{'name':'合成同步人物','family':'relationship','origin':'original'})['id']
            version=job(api(f'/api/personas/{person}/generate',{'material_ids':[]}))['version_id'];api(f'/api/personas/{person}/versions/{version}/review',{'approve':True})
            conversation=api(f'/api/personas/{person}/conversations',{})['id'];job(api(f'/api/conversations/{conversation}/messages',{'message':'请记住，合成偏好是周六海边拍照。'}))
            memory=api(f'/api/personas/{person}/memories')[0];mid=memory['id'];path=f'/api/personas/{person}/memory-sync';action=f'/api/personas/{person}/memories/{mid}/sync/'
            api(f'/api/personas/{person}/memories/{mid}',{'status':'CONFIRMED','expected_revision':memory['revision']},method='PUT')
            before=api(path);assert before['mode']=='DISABLED' and before['items'][0]['state']=='PENDING'
            api(action+'advance',{},expected=409);assert api(path)==before
            assert api(path,actor='editor')['items']==[];api(action+'advance',{},actor='editor',expected=409)
            for row in before['items']:assert not {'content','body','source_id','fingerprint','user_id'}&row.keys()
            result['checks'].append('default closed blocks manual consume; GET is private and content-free, does not dispatch; SQL memory remains available')
            from playwright.sync_api import sync_playwright
            with sync_playwright() as pw:
                browser=launch_chromium(pw)
                page=browser.new_page(viewport={'width':1360,'height':1000});posts=[]
                page.on('pageerror',lambda e:result['page_errors'].append(str(e)))
                page.on('request',lambda r:posts.append(r.method) if '/sync/' in r.url and r.method=='POST' else None)
                page.route('**/*',lambda r:r.continue_() if r.request.url.startswith(base+'/') or r.request.url.startswith('data:') else (result['external_requests'].append('blocked external'),r.abort()))
                page.goto(base);page.locator('#token').fill('demo-owner');page.locator('#login-form button').click();page.locator('#projects').select_option(project)
                page.locator(f'[data-person="{person}"]').click();page.locator('[data-view="chat"]').click()
                page.locator('#memory-sync-status').filter(has_text='消费者关闭').wait_for();assert page.locator('[data-memory-sync]').count()==0
                assert not posts;page.locator('#memory-sync-panel').screenshot(path=str(output/'default-closed.png'))
                result['checks'].append('native default status honestly shows closed and offers no consume button; read-only polling never POSTs')
                stop(worker);stop(server);env['PLATFORM_MEMORY_SYNC_MODE']='offline';result['stage']='offline mode startup'
                server=launch('offline-api',[safe.get('JAVA_BIN','java'),'-jar',str(jar)]);wait(healthy)
                page.reload();page.locator('[data-view="chat"]').click();page.locator('[data-memory-sync="advance"]').wait_for();assert not posts
                page.locator('[data-memory-sync="advance"]').click();page.locator('#memory-sync-items').filter(has_text='离线模拟完成（非真实同步）').wait_for()
                confirmed=api(path)['items'][0];assert confirmed['state']=='ACKED' and confirmed['receipt_mode']=='OFFLINE_SIMULATION' and confirmed['receipt_outcome']=='APPLIED'
                api(action+'advance',{});assert api(path)['items'][0]==confirmed
                api(action+'advance',{'bank_id':'rejected'},expected=422);api(action+'advance',{},actor='editor',expected=404)
                result['checks'].append('explicit offline enable and native bounded advancement reach typed simulated receipt; repeated advance unchanged; caller bank override and another editor rejected')
                result['stage']='correction and deletion'
                page.locator('[data-memory-body]').fill('合成偏好改为周日海边拍照');page.locator('[data-memory="confirm"]').click()
                wait(lambda:api(path)['items'][0]['state']=='PENDING');page.locator('[data-memory-sync="advance"]').click()
                wait(lambda:api(path)['items'][0]['state']=='ACKED');corrected=api(path)['items'][0]
                assert corrected['revision']==2 and corrected['operation_id']!=confirmed['operation_id']
                page.locator('[data-memory="delete"]').click();page.locator('#interaction-form button.primary').click();page.locator('[data-memory-body]').wait_for(state='detached')
                wait(lambda:api(path)['items'][0]['kind']=='DELETE');page.locator('[data-memory-sync="advance"]').click()
                wait(lambda:api(path)['items'][0]['state']=='ACKED');deleted=api(path)['items'][0]
                assert deleted['kind']=='DELETE' and deleted['revision']==3 and not api(f'/api/personas/{person}/memories')
                page.locator('#memory-sync-items').filter(has_text='清理记录').wait_for();page.locator('#memory-sync-panel').screenshot(path=str(output/'offline-delete-complete.png'))
                result['checks'].append('native correction advances a new revision; forgotten memory disappears but private simulated cleanup tombstone remains observable and can complete')
                result['stage']='restart and denied status'
                page.locator('#logout').click();page.close();browser.close();stop(server)
                server=launch('restart-offline-api',[safe.get('JAVA_BIN','java'),'-jar',str(jar)]);wait(healthy);assert api(path)['items'][0]==deleted
                browser=launch_chromium(pw);page=browser.new_page(viewport={'width':1360,'height':1000})
                page.on('pageerror',lambda e:result['page_errors'].append(str(e)))
                page.goto(base);page.locator('#token').fill('demo-owner');page.locator('#login-form button').click();page.locator('#projects').select_option(project);page.locator(f'[data-person="{person}"]').click();page.locator('[data-view="chat"]').click()
                page.locator('#memory-sync-items').filter(has_text='非真实同步').wait_for()
                page.route(base+path,lambda r:r.fulfill(status=403,content_type='application/json',body='{"error":"project_access_denied"}'))
                page.locator('#memory-sync-refresh').click();page.locator('#memory-sync-status').filter(has_text='权限已变化').wait_for();assert page.locator('#memory-sync-items').inner_text()==''
                page.locator('#logout').click();page.close();browser.close()
                result['checks'].append('same file DB restart retains simulated terminal receipt; injected browser permission error clears cached status without dispatch (actual backend revocation covered in service tests)')
            assert not result['page_errors'] and not result['external_requests']
            result['status']='PASS';result['stage']='complete'
        except BaseException:
            result['status']='FAIL'
            raise
        finally:
            for process in reversed(processes):stop(process)
            for log in logs:log.close()
            result['cleanup_all_managed_processes_stopped']=all(p.poll() is not None for p in processes)
            (output/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print('PASS: memory-sync-consumer',len(result['checks']),'HTTP/native/restart groups; offline simulation only; real provider calls0')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,default=ROOT/'server/target/memory-sync-consumer-e2e');run(parser.parse_args().output.resolve())
