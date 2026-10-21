#!/usr/bin/env python3
"""Synthetic original-message lost-response and request-idempotency journey.

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
TOKEN = 'chat-submission-synthetic-worker-token-32-characters'
SOURCE = '合成人格资料：表达简洁，先核对来源，再回答问题，不编造人物亲历。'


def run(output, baseline=False, upgrade_from=None, shortcut=False):
    output = output.resolve(); output.mkdir(parents=True, exist_ok=True)
    safe = {key: value for key, value in os.environ.items() if key in (
        'HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'PATH', 'JAVA_HOME', 'JAVA_BIN', 'MAVEN_OPTS', 'MAVEN_ARGS')}
    result = {'status': 'RUNNING', 'baseline': baseline, 'real_provider_calls': 0,
              'checks': [], 'screenshots': {}, 'page_errors': [], 'external_requests': []}
    processes, logs = [], []
    with tempfile.TemporaryDirectory(prefix='distilly-chat-submission-') as tmp:
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
            result['stage']='default JVM and mock publication'
            old_project=None
            if upgrade_from:
                old=folder/'old.jar';shutil.copy2(upgrade_from,old)
                server=launch('old-api',[safe.get('JAVA_BIN','java'),'-jar',str(old)]);wait(healthy)
                old_project=api('/api/projects',{'title':'合成V008升级保留'})['id'];stop(server)
            started=time.monotonic();server=launch('api',[safe.get('JAVA_BIN','java'),'-jar',str(jar)])
            wait(healthy);result['default_jvm_startup_seconds']=round(time.monotonic()-started,3)
            if old_project:
                assert any(item['id']==old_project for item in api('/api/projects'))
                result['checks'].append('actual previous V008 JAR database upgraded to V009 without dropping existing synthetic workspace')
            project=api('/api/projects',{'title':'合成消息提交恢复'})['id']
            person=api(f'/api/projects/{project}/personas',{'name':'合成提交伙伴','family':'colleague','origin':'original','consent':False})['id']
            worker=launch('mock-worker',[sys.executable,str(ROOT/'worker/worker.py')])
            generated=api(f'/api/personas/{person}/generate',{'material_ids':[]})
            job=wait(lambda:(lambda j:j if j['status']=='SUCCEEDED' else None)(api('/api/persona-jobs/'+generated['id'])))
            api(f"/api/personas/{person}/versions/{job['result']['version_id']}/review",{'approve':True});stop(worker)
            conversation=api(f'/api/personas/{person}/conversations',{})['id']
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
                    page.goto(base+f'/?workspace={project}&persona={person}#chat',wait_until='networkidle')
                    page.locator('#token').fill('demo-owner');page.locator('#login-form button').click()
                    page.locator('#workspace').wait_for(state='visible')
                    if page.locator('#projects').input_value()!=project:page.locator('#projects').select_option(project)
                    page.wait_for_function("()=>document.querySelector('#heading').textContent==='交流与记忆'")
                    messages_path=base+'/api/conversations/'+conversation+'/messages';writes=[]
                    page.on('request',lambda request:writes.append(request.post_data_json) if request.url==messages_path and request.method=='POST' else None)
                    def committed_lost(route):
                        response=route.fetch();assert response.status==200
                        result['first_job']=response.json()['id']
                        api('/api/persona-jobs/'+result['first_job']+'/cancel',{})
                        route.abort('failed')
                    page.route(messages_path,committed_lost,times=1)
                    page.locator('#message').fill('合成响应丢失的同一条消息');page.locator('#send').click()
                    if baseline:
                        page.locator('#notice').filter(has_text='无法连接').wait_for()
                        page.wait_for_function("()=>!document.querySelector('#send').disabled")
                        assert page.locator('#message').input_value()=='合成响应丢失的同一条消息'
                        page.locator('#send').click();wait(lambda:len(api('/api/conversations/'+conversation)['messages'])==2)
                        assert len(writes)==2;assert writes[0]==writes[1]
                        assert len([j for j in api('/api/personas/'+person+'/jobs') if j['kind']=='CHAT'])==2
                        shot('baseline-lost-ack-duplicates-original-message')
                        result['status']='OBSERVED_GAP';result['checks'].append('native UI resend after committed/cancelled lost response creates duplicate user message and second task');return
                    result['stage']='committed lost response, reload and same-file API restart'
                    page.locator('#chat-submit-status').filter(has_text='尚未确认').wait_for()
                    assert len(writes)==1 and writes[0].get('request_id')
                    stored=page.evaluate("()=>sessionStorage.getItem('platform-chat-submissions-v1')")
                    assert '合成响应丢失' not in stored and 'demo-owner' not in stored
                    assert page.locator('#send').is_disabled()
                    stop(server);server=launch('restarted-api',[safe.get('JAVA_BIN','java'),'-jar',str(jar)]);wait(healthy)
                    page.reload(wait_until='networkidle')
                    page.locator('#chat-submit-recheck').wait_for(state='visible');assert len(writes)==1
                    page.locator('#chat-submit-recheck').click()
                    page.locator('#chat-submit-recheck').wait_for(state='hidden')
                    page.wait_for_function("()=>!document.querySelector('#send').disabled")
                    page.locator('#messages .bubble.user').wait_for();assert len(writes)==1
                    assert page.locator('#send').is_enabled()
                    assert len(api('/api/conversations/'+conversation)['messages'])==1
                    receipt=api('/api/conversations/'+conversation+'/submissions/'+writes[0]['request_id'])
                    assert receipt['job_id']==result['first_job'] and set(receipt)=={'request_id','message_id','job_id','status'}
                    assert api('/api/persona-jobs/'+receipt['job_id'])['status']=='CANCELLED'
                    shot('committed-lost-response-recovered-after-restart')
                    result['checks'].append('committed/cancelled lost response survives browser reload and default-JVM same-file H2 restart; metadata contains no body/token; explicit GET recovers exact original receipt without second POST or resurrecting task')
                    result['stage']='before-commit disconnect and explicit same-key retry'
                    page.route(messages_path,lambda route:route.abort('failed'),times=1)
                    page.locator('#message').fill('合成未提交的第二条消息');page.locator('#send').click()
                    page.locator('#chat-submit-status').filter(has_text='尚未确认').wait_for()
                    pending_id=writes[-1]['request_id'];assert len(writes)==2
                    page.reload(wait_until='networkidle');page.locator('#chat-submit-recheck').wait_for(state='visible')
                    page.locator('#chat-submit-recheck').click();page.locator('#chat-submit-retry').wait_for(state='visible')
                    assert len(writes)==2
                    page.locator('#message').fill('不同的合成内容');page.locator('#chat-submit-retry').click()
                    page.locator('#chat-submit-status').filter(has_text='内容与待核对').wait_for();assert len(writes)==2
                    page.locator('#message').fill('合成未提交的第二条消息')
                    page.evaluate("()=>{document.querySelector('#chat-submit-retry').click();document.querySelector('#chat-submit-retry').click();}")
                    page.wait_for_function("()=>document.querySelector('#chat-submit-recheck').hidden")
                    assert len(writes)==3 and writes[-1]['request_id']==pending_id
                    worker=launch('recovery-mock-worker',[sys.executable,str(ROOT/'worker/worker.py')])
                    page.locator('#messages .bubble.assistant').wait_for(timeout=20000)
                    assert '模拟' in page.locator('#messages .bubble.assistant').inner_text()
                    session=api('/api/conversations/'+conversation);assert len(session['messages'])==3
                    shot('explicit-same-key-retry-completes-once')
                    result['checks'].append('before-commit disconnect: reload does not POST, missing receipt permits explicit same-key retry only with matching original content; duplicate clicks send once and actual mock Worker produces one reply')
                    result['stage']='HTTP concurrent replay, conflict and private receipt boundary'
                    from concurrent.futures import ThreadPoolExecutor
                    with ThreadPoolExecutor(max_workers=2) as pool:
                        returned=list(pool.map(lambda _:api('/api/conversations/'+conversation+'/messages',writes[-1]),range(2)))
                    assert returned[0]['id']==returned[1]['id']
                    api('/api/conversations/'+conversation+'/messages',{**writes[-1],'message':'不同正文'},expected=409)
                    assert len(api('/api/conversations/'+conversation)['messages'])==3
                    api(f'/api/projects/{project}/members',{'user_id':'viewer','role':'VIEWER'},method='PUT')
                    api('/api/conversations/'+conversation+'/submissions/'+pending_id,actor='viewer',expected=403)
                    result['checks'].append('actual concurrent HTTP replay returns one existing job after completion, different body conflicts409, other authorized workspace member cannot read the private receipt403')
                    result['stage']='late acceptance after changing conversation'
                    held=[]
                    def hold_accepted(route):
                        response=route.fetch();assert response.status==200;held.append((route,response.json()))
                    page.route(messages_path,hold_accepted,times=1)
                    page.locator('#message').fill('合成晚到的第三条消息')
                    if shortcut:page.locator('#message').press('Control+Enter')
                    else:page.locator('#send').click()
                    for _ in range(200):
                        if held:break
                        page.wait_for_timeout(50)
                    assert held
                    if shortcut:
                        assert page.locator('#message').input_value()=='合成晚到的第三条消息'
                        result['shortcut_default_newline_prevented']=True
                    page.locator('#new-chat').click();page.wait_for_function("()=>document.querySelector('#messages').textContent.includes('从一句问候开始')")
                    page.locator('#message').fill('新交流未发送草稿')
                    held[0][0].fulfill(status=200,content_type='application/json',body=json.dumps(held[0][1]))
                    page.wait_for_timeout(150);assert page.locator('#message').input_value()=='新交流未发送草稿'
                    assert page.locator('#messages .bubble').count()==0
                    page.locator('#conversations').select_option(conversation)
                    page.locator('#chat-submit-recheck').wait_for(state='visible');before=len(writes)
                    page.locator('#chat-submit-recheck').click();page.wait_for_function("()=>document.querySelector('#chat-submit-recheck').hidden")
                    assert len(writes)==before and page.locator('#message').input_value()=='新交流未发送草稿'
                    result['checks'].append('late accepted POST cannot clear or render into a new conversation; returning to original conversation recovers its retained operation by GET and preserves a different unsent draft')
                    result['stage']='access loss during an unresolved submission'
                    wait(lambda:all(j['status'] not in ('QUEUED','RUNNING') for j in api('/api/personas/'+person+'/jobs')));stop(worker)
                    page.route(messages_path,lambda route:route.abort('failed'),times=1)
                    page.locator('#message').fill('合成访问撤销前未提交');page.locator('#send').click()
                    page.locator('#chat-submit-status').filter(has_text='尚未确认').wait_for()
                    api('/api/personas/'+person,method='DELETE')
                    page.locator('#chat-submit-recheck').click();page.locator('#chat-status').filter(has_text='不可访问').wait_for()
                    assert page.locator('#send').is_disabled() and page.locator('#messages .bubble').count()==0
                    shot('revoked-submission-recovery-cleared')
                    page.locator('#logout').click();assert page.locator('#workspace').is_hidden()
                    assert page.evaluate("()=>sessionStorage.getItem('platform-chat-submissions-v1')") is None
                    assert not result['page_errors'] and not result['external_requests']
                    result['checks'].append('actual archived-person404 cannot recover old receipt or trigger a write, clears chat and disables send; logout clears operation metadata')
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
    print('PASS: chat-submission',len(result['checks']),'groups; real calls 0')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--baseline',action='store_true')
    parser.add_argument('--output',type=Path,default=ROOT/'server/target/chat-submission-e2e');parser.add_argument('--upgrade-from',type=Path);parser.add_argument('--shortcut',action='store_true')
    args=parser.parse_args();run(args.output,args.baseline,args.upgrade_from,args.shortcut)
