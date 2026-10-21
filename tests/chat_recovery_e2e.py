#!/usr/bin/env python3
"""Synthetic current-chat failure, explicit retry, cancellation and lifecycle recovery.

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
TOKEN = 'chat-recovery-synthetic-worker-token-32-characters'
SOURCE = '合成人格资料：表达简洁，先核对来源，再回答问题，不编造人物亲历。'


def run(output, baseline=False):
    output = output.resolve(); output.mkdir(parents=True, exist_ok=True)
    safe = {key: value for key, value in os.environ.items() if key in (
        'HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'PATH', 'JAVA_HOME', 'JAVA_BIN', 'MAVEN_OPTS', 'MAVEN_ARGS')}
    result = {'status': 'RUNNING', 'baseline': baseline, 'real_provider_calls': 0,
              'checks': [], 'screenshots': {}, 'page_errors': [], 'external_requests': []}
    processes, logs = [], []
    with tempfile.TemporaryDirectory(prefix='distilly-chat-recovery-') as tmp:
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

        try:
            result['stage'] = 'default-JVM startup and mock publication'
            started=time.monotonic(); server=launch('api',[safe.get('JAVA_BIN','java'),'-jar',str(jar)])
            wait(healthy); result['default_jvm_startup_seconds']=round(time.monotonic()-started,3)
            project=api('/api/projects',{'title':'合成聊天恢复'})['id']
            person=api(f'/api/projects/{project}/personas',{'name':'合成聊天伙伴','family':'colleague','origin':'original','consent':False})['id']
            worker=launch('mock-worker',[sys.executable,str(ROOT/'worker/worker.py')])
            generated=api(f'/api/personas/{person}/generate',{'material_ids':[]})
            job=wait(lambda: (lambda j:j if j['status']=='SUCCEEDED' else None)(api('/api/persona-jobs/'+generated['id'])))
            api(f"/api/personas/{person}/versions/{job['result']['version_id']}/review",{'approve':True});stop(worker)
            conversation=api(f'/api/personas/{person}/conversations',{})['id']
            def internal(path,body,expected=200):
                req=urllib.request.Request(base+path,json.dumps(body).encode(),{'Authorization':'Bearer '+TOKEN,'Content-Type':'application/json'})
                try:
                    with opener.open(req,timeout=8) as response:
                        assert response.status==expected;raw=response.read();return json.loads(raw) if raw else None
                except urllib.error.HTTPError as error:
                    assert error.code==expected,('internal fixture status',error.code,expected);error.close();return None
            def failed_turn(text):
                j=api(f'/api/conversations/{conversation}/messages',{'message':text})
                claimed=internal('/internal/persona-jobs/claim',{})
                assert claimed['id']==j['id']
                internal('/internal/persona-jobs/'+j['id']+'/fail',{'lease_token':claimed['lease_token'],'code':'MODEL_CONFIGURATION_ERROR'})
                assert api('/api/persona-jobs/'+j['id'])['status']=='FAILED'
                return j
            failed=failed_turn('合成首轮失败')
            from playwright.sync_api import sync_playwright
            with sync_playwright() as pw:
                browser=pw.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
                try:
                    page=browser.new_page(viewport={'width':1440,'height':1080})
                    page.add_init_script("window.syntheticPageShow=[];window.addEventListener('pageshow',event=>window.syntheticPageShow.push(event.persisted));")
                    def restrict(route):
                        if route.request.url.startswith(base+'/') or route.request.url.startswith(('data:','blob:')):route.continue_()
                        else:result['external_requests'].append('blocked external request');route.abort()
                    page.route('**/*',restrict);page.on('pageerror',lambda error:result['page_errors'].append(str(error)))
                    def shot(name):
                        path=output/(name+'.png');page.screenshot(path=str(path),full_page=True)
                        result['screenshots'][name]={'file':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
                    page.goto(base+f'/?workspace={project}&persona={person}#chat',wait_until='networkidle')
                    page.locator('#token').fill('demo-owner');page.locator('#login-form button').click()
                    page.locator('#messages .bubble.user').wait_for()
                    if baseline:
                        assert page.locator('#chat-status').inner_text()==''
                        assert page.locator('[data-panel="chat"] [data-job="retry"]').count()==0
                        assert page.locator('[data-panel="studio"]').is_hidden()
                        assert page.locator('#jobs [data-job="retry"]').count()==1
                        shot('baseline-failure-invisible-in-chat');result['status']='OBSERVED_GAP'
                        result['checks'].append('failed current turn has empty chat status and no visible retry; existing retry exists only in hidden Studio task log');return
                    result['stage']='visible failure and explicit lost-ack retry'
                    page.locator('#chat-status').filter(has_text='本次回应失败').wait_for()
                    assert page.locator('#chat-retry').is_visible();assert page.locator('#chat-stop').is_hidden()
                    page.locator('#message').fill('未发送的合成草稿')
                    shot('current-chat-failure')
                    retries=[];cancels=[]
                    retry_path=base+'/api/persona-jobs/'+failed['id']+'/retry'
                    cancel_path=base+'/api/persona-jobs/'+failed['id']+'/cancel'
                    page.on('request',lambda request:retries.append(1) if request.url==retry_path and request.method=='POST' else cancels.append(1) if request.url==cancel_path and request.method=='POST' else None)
                    def lost_ack(route):
                        assert route.fetch().status==200;route.abort('failed')
                    page.route(retry_path,lost_ack,times=1)
                    page.locator('#chat-retry').click()
                    page.locator('#chat-status').filter(has_text='尚未确认').wait_for()
                    assert page.locator('#chat-retry').is_disabled();assert len(retries)==1
                    assert api('/api/persona-jobs/'+failed['id'])['status']=='QUEUED'
                    page.locator('#chat-refresh').click()
                    page.locator('#chat-status').filter(has_text='排队中').wait_for()
                    assert page.locator('#message').input_value()=='未发送的合成草稿'
                    assert len(api('/api/conversations/'+conversation)['messages'])==1
                    result['checks'].append('failed current turn is visible in chat; explicit retry reuses same job and original user message; lost committed response requires GET recheck without auto repost and preserves draft')
                    result['stage']='cancel partial reply, duplicate click and late completion'
                    claimed=internal('/internal/persona-jobs/claim',{});assert claimed['id']==failed['id']
                    internal('/internal/persona-jobs/'+failed['id']+'/delta',{'lease_token':claimed['lease_token'],'partial':'合成临时回应，不是完成回答'})
                    page.locator('#chat-refresh').click();page.locator('#messages').filter(has_text='合成临时回应').wait_for()
                    page.evaluate("()=>{document.querySelector('#chat-stop').click();document.querySelector('#chat-stop').click();}")
                    page.locator('#chat-status').filter(has_text='已停止').wait_for();assert len(cancels)==1
                    assert '合成临时回应' not in page.locator('#messages').inner_text()
                    internal('/internal/persona-jobs/'+failed['id']+'/complete',{'lease_token':claimed['lease_token'],'output':{'answer':'晚到的合成回答'}},expected=409)
                    assert len(api('/api/conversations/'+conversation)['messages'])==1
                    assert page.locator('#chat-retry').is_hidden();shot('cancelled-current-turn')
                    result['checks'].append('running partial reply can be stopped in chat; duplicate clicks issue one cancel; temporary reply disappears, cancelled turn stays cancelled and late completion is409')
                    result['stage']='continue through actual mock Worker'
                    page.locator('#message').fill('合成停止后继续交流');page.locator('#send').click()
                    worker=launch('resumed-mock-worker',[sys.executable,str(ROOT/'worker/worker.py')])
                    page.locator('#messages .bubble.assistant').wait_for(timeout=20000);stop(worker)
                    assert '模拟' in page.locator('#messages .bubble.assistant').inner_text()
                    assert len(api('/api/conversations/'+conversation)['messages'])==3
                    result['checks'].append('new user turn after cancellation completes through actual mock Worker; original failed/cancelled input is not duplicated')
                    result['stage']='failure survives refresh and native page return'
                    failed_turn('合成刷新后仍可恢复')
                    page.locator('#chat-status').filter(has_text='本次回应失败').wait_for(timeout=10000)
                    page.reload(wait_until='networkidle');page.locator('#chat-status').filter(has_text='本次回应失败').wait_for()
                    page.goto(base+'/knowledge.html',wait_until='networkidle');page.go_back(wait_until='networkidle')
                    page.locator('#chat-status').filter(has_text='本次回应失败').wait_for()
                    result['native_return_pageshow_persisted']=page.evaluate('()=>window.syntheticPageShow')
                    shot('failure-after-page-return')
                    result['checks'].append('server-persisted failed current-turn status and explicit retry survive full reload and native navigation away/back; no auto retry')
                    result['stage']='old read response after switching conversations'
                    held=[]
                    page.route(base+'/api/conversations/'+conversation,lambda route:held.append(route),times=1)
                    page.locator('#chat-refresh').click();wait(lambda:bool(held))
                    page.locator('#new-chat').click()
                    page.wait_for_function("()=>document.querySelector('#messages').textContent.includes('从一句问候开始')")
                    page.locator('#message').fill('新交流未发送草稿')
                    held[0].fulfill(status=403,content_type='application/json',body=json.dumps({'error':'private_conversation'}))
                    page.wait_for_timeout(150)
                    assert '不可访问' not in page.locator('#chat-status').inner_text()
                    assert page.locator('#message').input_value()=='新交流未发送草稿'
                    assert page.locator('#messages .bubble').count()==0
                    result['checks'].append('late403 from old conversation read cannot clear or label the newly selected conversation, and preserves its unsent draft')
                    result['stage']='actual access loss and logout'
                    api('/api/personas/'+person,method='DELETE')
                    page.locator('#chat-refresh').click();page.locator('#chat-status').filter(has_text='不可访问').wait_for()
                    assert page.locator('#messages .bubble').count()==0
                    assert page.locator('#chat-refresh').is_disabled();assert page.locator('#send').is_disabled()
                    shot('archived-access-clears-chat')
                    page.locator('#logout').click();assert page.locator('#workspace').is_hidden()
                    assert page.locator('#messages .bubble').count()==0
                    assert page.locator('#message').input_value()=='' and page.locator('#chat-image').input_value()==''
                    assert not result['page_errors'] and not result['external_requests']
                    result['checks'].append('actual archived-person404 clears current conversation and disables controls; logout hides workspace; private/revoked-membership403 also covered by backend and UI regression')
                    result['status']='PASS';result['stage']='completed'
                finally:browser.close()
        except BaseException as error:
            result['status']='FAIL';result['failure_type']=type(error).__name__;raise
        finally:
            for process in reversed(processes):stop(process)
            for log in logs:log.close()
            result['cleanup_all_processes_stopped']=all(process.poll() is not None for process in processes)
            (output/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print('PASS: chat-recovery',len(result['checks']),'groups; real calls 0')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--baseline',action='store_true')
    parser.add_argument('--output',type=Path,default=ROOT/'server/target/chat-recovery-e2e')
    args=parser.parse_args();run(args.output,args.baseline)
