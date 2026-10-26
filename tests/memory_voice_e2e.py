#!/usr/bin/env python3
"""Synthetic long-memory provenance and mock clone lifecycle, native UI + real mock Worker.

One isolated file H2, loopback only, no inherited provider configuration or recordings.
"""
import argparse
import base64
import io
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
import wave

ROOT = Path(__file__).resolve().parents[1]
TOKEN = 'memory-voice-synthetic-worker-token-32-characters'


def run(output):
    output.mkdir(parents=True, exist_ok=True)
    safe = {k: v for k, v in os.environ.items() if k in ('HOME','LANG','LC_ALL','TMPDIR','PATH','JAVA_HOME','JAVA_BIN','MAVEN_OPTS','MAVEN_ARGS')}
    result = {'status':'RUNNING','real_provider_calls':0,'real_voice_clone':False,'checks':[],'page_errors':[],'external_requests':[]}
    processes, logs = [], []
    with tempfile.TemporaryDirectory(prefix='distilly-memory-voice-') as tmp:
        folder=Path(tmp);jar=folder/'runtime.jar';shutil.copy2(ROOT/'server/target/distilly-platform-0.1.0.jar',jar)
        with socket.socket() as sock: sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
        base=f'http://127.0.0.1:{port}'
        env=dict(safe,PLATFORM_DEMO='true',PLATFORM_WORKER_TOKEN=TOKEN,MODEL_MODE='mock',PLATFORM_PORT=str(port),PLATFORM_BIND='127.0.0.1',PLATFORM_API_URL=base,PLATFORM_DB_URL='jdbc:h2:file:'+str(folder/'db')+';DATABASE_TO_LOWER=TRUE',PLATFORM_DB_USER='sa',PLATFORM_DB_PASSWORD='')
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        def api(path,body=None,actor='owner',method=None,expected=200):
            request=urllib.request.Request(base+path,None if body is None else json.dumps(body).encode(),{'Authorization':'Bearer demo-'+actor,'Content-Type':'application/json'},method=method or ('POST' if body is not None else 'GET'))
            try:
                with opener.open(request,timeout=8) as response: status,raw=response.status,response.read()
            except urllib.error.HTTPError as error:
                with error: status,raw=error.code,error.read()
            assert status==expected,(path,status,expected)
            return json.loads(raw) if raw else None
        def wait(fn,seconds=30):
            end=time.monotonic()+seconds
            while time.monotonic()<end:
                value=fn()
                if value:return value
                time.sleep(.1)
            raise AssertionError('bounded synthetic memory/voice condition timed out')
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
                row=api('/api/persona-jobs/'+j['id'])
                assert row['status'] not in ('FAILED','CANCELLED'),row.get('error_code')
                return row.get('result') if row['status']=='SUCCEEDED' else None
            return wait(done)
        try:
            result['stage']='startup'
            server=launch('api',[safe.get('JAVA_BIN','java'),'-jar',str(jar)]);wait(healthy)
            worker=launch('worker',[sys.executable,'worker/worker.py'])
            project=api('/api/projects',{'title':'合成记忆与声音首批'})['id']
            api(f'/api/projects/{project}/members',{'user_id':'editor','role':'EDITOR'},method='PUT')
            api(f'/api/projects/{project}/members',{'user_id':'viewer','role':'VIEWER'},method='PUT')
            persona=api(f'/api/projects/{project}/personas',{'name':'合成小溪','family':'relationship','origin':'original','background':'原创合成角色，不代表真人。'})['id']
            version=job(api(f'/api/personas/{persona}/generate',{'material_ids':[]}))['version_id']
            api(f'/api/personas/{persona}/versions/{version}/review',{'approve':True})
            conversation=api(f'/api/personas/{persona}/conversations',{})['id']
            job(api(f'/api/conversations/{conversation}/messages',{'message':'请记住，合成偏好是周五海边拍照。'}))
            memories=api(f'/api/personas/{persona}/memories');assert len(memories)==1 and memories[0]['status']=='PENDING'
            memory=memories[0]['id'];source=api(f'/api/personas/{persona}/memories/{memory}/source')
            assert source['conversation_id']==conversation
            api(f'/api/personas/{persona}/memories/{memory}/source',actor='editor',expected=404)
            result['checks'].append('mock chat extracts pending memory; private source is linked to the original user message; other editor denied')
            from playwright.sync_api import sync_playwright
            with sync_playwright() as pw:
                browser=pw.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
                page=browser.new_page(viewport={'width':1360,'height':950})
                page.on('pageerror',lambda error:result['page_errors'].append(str(error)))
                page.route('**/*',lambda route:route.continue_() if route.request.url.startswith(base+'/') or route.request.url.startswith('data:') else (result['external_requests'].append('blocked external request'),route.abort()))
                page.goto(base);page.locator('#token').fill('demo-owner');page.locator('#login-form button').click()
                page.locator('#projects').select_option(project);page.locator(f'[data-person="{persona}"]').click()
                page.locator('[data-view="chat"]').click();page.locator('[data-memory="source"]').click()
                page.locator('#memory-source-body').filter(has_text='周五海边拍照').wait_for()
                page.screenshot(path=str(output/'memory-source.png'),full_page=True)
                page.locator('#memory-source-close').click();page.locator('[data-memory="confirm"]').click()
                wait(lambda:api(f'/api/personas/{persona}/memories')[0]['status']=='CONFIRMED')
                next_conversation=api(f'/api/personas/{persona}/conversations',{})['id']
                answer=job(api(f'/api/conversations/{next_conversation}/messages',{'message':'海边拍照偏好是什么？'}))
                assert '周五海边拍照' in answer['answer']
                result['checks'].append('native source dialog and confirmation; confirmed memory is recalled in a different conversation')
                result['stage']='late memory read and stale correction'
                # Delay a passive GET across typing, then commit a correction through another client.
                held=[];pattern=base+f'/api/personas/{persona}/memories'
                def hold_once(route):
                    if not held:held.append(route)
                    else:route.continue_()
                page.route(pattern,hold_once)
                with page.expect_request(lambda request:request.url==pattern and request.method=='GET'):
                    page.evaluate('()=>{void loadMemories();}')
                page.wait_for_timeout(50);assert len(held)==1
                page.locator('[data-memory-body]').fill('合成旧页面草稿：周日海边拍照')
                current=api(f'/api/personas/{persona}/memories')[0]
                corrected='合成偏好已纠正为周六海边拍照'
                api(f'/api/personas/{persona}/memories/{memory}',{'status':'CONFIRMED','body':corrected,'expected_revision':current['revision']},method='PUT')
                with page.expect_response(lambda response:response.url==pattern and response.request.method=='GET') as late:
                    held[0].continue_()
                late.value.body();page.unroute(pattern)
                assert page.locator('[data-memory-body]').input_value()=='合成旧页面草稿：周日海边拍照'
                page.locator('[data-memory="confirm"]').click()
                page.locator('#notice').filter(has_text='当前草稿已保留').wait_for()
                assert page.locator('[data-memory-body]').input_value()=='合成旧页面草稿：周日海边拍照'
                assert api(f'/api/personas/{persona}/memories')[0]['body']==corrected
                page.locator('#memory-refresh').click();wait(lambda:page.locator('[data-memory-body]').input_value()==corrected)
                source_again=api(f'/api/personas/{persona}/memories/{memory}/source');assert source_again['body']==source['body']
                another=api(f'/api/personas/{persona}/conversations',{})['id']
                paraphrase=job(api(f'/api/conversations/{another}/messages',{'message':'海边拍照偏好是什么？'}))
                result['semantic_observations']=[{'case':'corrected-memory-paraphrase-new-probe','matched':corrected in paraphrase['answer'],'classification':'diagnostic, not a semantic quality acceptance gate'}]
                literal=api(f'/api/personas/{persona}/conversations',{})['id']
                assert corrected in job(api(f'/api/conversations/{literal}/messages',{'message':corrected}))['answer']
                result['checks'].append('native stale correction rejects without losing draft; refresh shows committed body; original source remains unchanged; literal query in next conversation recalls correction; paraphrase diagnostic reported separately')
                result['stage']='native clone lifecycle'
                page.locator('[data-view="media"]').click();page.locator('#clone-name').fill('纯合成测试档案');page.locator('#clone-consent').check();page.locator('#clone-create').click()
                wait(lambda:len(api(f'/api/personas/{persona}/voice-profiles'))==1)
                profile=api(f'/api/personas/{persona}/voice-profiles')[0];profile_id=profile['id']
                assert profile['authorization_basis']=='SYNTHETIC_ONLY' and profile['purpose']=='MOCK_PREVIEW'
                api(f'/api/personas/{persona}/voice-profiles/{profile_id}',actor='editor',expected=403)
                api(f'/api/personas/{persona}/voice-profiles',actor='viewer',expected=403)
                page.locator('[data-clone="preview"]').click(timeout=30000)
                page.locator('#clone-audio:not([hidden])').wait_for()
                raw=base64.b64decode(page.locator('#clone-audio').get_attribute('src').split(',')[1],validate=True)
                with wave.open(io.BytesIO(raw)) as sound:assert (sound.getnframes(),sound.getframerate())==(3200,8000)
                assert page.locator('#clone-audio').evaluate('(a)=>a.paused')
                page.locator('#clone-audio').click(position={'x':22,'y':25})
                wait(lambda:page.locator('#clone-audio').evaluate('(a)=>a.ended'))
                page.screenshot(path=str(output/'mock-clone-preview.png'),full_page=True)
                result['checks'].append('native consent/create/status/preview through actual mock Worker; valid 400 ms tone, no autoplay, native player click reaches ended; editor/viewer isolation')
                page.locator('[data-clone="revoke"]').click()
                wait(lambda:api(f'/api/personas/{persona}/voice-profiles/{profile_id}')['authorization_status']=='REVOKED')
                assert page.locator('#clone-audio').get_attribute('src') is None
                closed=api(f'/api/personas/{persona}/voice-profiles/{profile_id}')
                assert all(j['status']=='CANCELLED' and 'result' not in j for j in closed['jobs'])
                page.reload();page.locator('[data-view="media"]').click();page.locator('#clone-profiles').filter(has_text='授权已撤销').wait_for()
                assert page.locator('[data-clone="preview"]').is_disabled()
                result['checks'].append('native revoke clears playback and completed preview caches; reload preserves closed authorization and disables preview')
                page.locator('#logout').click()
                page.close();browser.close()
                result['stage']='same database restart'
                stop(worker);stop(server)
                server=launch('restarted-api',[safe.get('JAVA_BIN','java'),'-jar',str(jar)]);wait(healthy)
                worker=launch('restarted-worker',[sys.executable,'worker/worker.py'])
                assert api(f'/api/personas/{persona}/memories')[0]['body']==corrected
                assert api(f'/api/personas/{persona}/voice-profiles/{profile_id}')['authorization_status']=='REVOKED'
                api(f'/api/personas/{persona}/voice-profiles/{profile_id}/preview',{'request_id':'11111111-1111-4111-8111-111111111111'},expected=409)
                api(f'/api/personas/{persona}/voice-profiles/{profile_id}',method='DELETE')
                assert not api(f'/api/personas/{persona}/voice-profiles')
                tombstone=api(f'/api/personas/{persona}/voice-profiles/{profile_id}');assert tombstone['name']=='' and tombstone['authorization_status']=='DELETED'
                result['checks'].append('API and mock Worker restart retain corrected memory and revoked consent; closed preview denied, deletion leaves minimal tombstone')
                browser=pw.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
                page=browser.new_page(viewport={'width':1360,'height':950})
                page.on('pageerror',lambda error:result['page_errors'].append(str(error)))
                page.route('**/*',lambda route:route.continue_() if route.request.url.startswith(base+'/') or route.request.url.startswith('data:') else (result['external_requests'].append('blocked external request'),route.abort()))
                page.goto(base);page.locator('#token').fill('demo-owner');page.locator('#login-form button').click()
                page.locator('#projects').select_option(project);page.locator(f'[data-person="{persona}"]').click();page.locator('[data-view="chat"]').click()
                page.locator('[data-memory-body]').fill('合成删除前的未保存草稿')
                page.locator('[data-memory="delete"]').click();page.locator('#interaction-form button.primary').click()
                page.locator('[data-memory-body]').wait_for(state='detached')
                assert not api(f'/api/personas/{persona}/memories')
                page.locator('#logout').click();page.close();browser.close()
                api(f'/api/personas/{persona}/memories/{memory}/source',expected=404)
                fresh=api(f'/api/personas/{persona}/conversations',{})['id']
                after=job(api(f'/api/conversations/{fresh}/messages',{'message':'海边拍照偏好是什么？'}))
                assert '我记得你确认过' not in after['answer'] and not api(f'/api/personas/{persona}/memories')
                result['checks'].append('native forget after editing removes its card, source access and recall in a fresh conversation without deleting historical messages')
                assert not result['page_errors'] and not result['external_requests'], 'browser errors or external requests'
            result['status']='PASS';result['stage']='complete'
        finally:
            for process in reversed(processes):stop(process)
            for log in logs:log.close()
            result['cleanup_all_managed_processes_stopped']=all(p.poll() is not None for p in processes)
            (output/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print('PASS: memory-voice',len(result['checks']),'native/HTTP/restart groups; real provider calls0; real clone NOT_MEASURED')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,default=ROOT/'server/target/memory-voice-e2e')
    run(parser.parse_args().output.resolve())
