#!/usr/bin/env python3
"""Synthetic first-entry -> TXT -> personality -> intended person -> first mock chat.

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
TOKEN = 'first-entry-synthetic-worker-token-32-characters'
SOURCE = '合成人格资料：表达简洁，先核对来源，再回答问题，不编造人物亲历。'


def run(output, baseline=False):
    output = output.resolve(); output.mkdir(parents=True, exist_ok=True)
    safe = {key: value for key, value in os.environ.items() if key in (
        'HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'PATH', 'JAVA_HOME', 'JAVA_BIN', 'MAVEN_OPTS', 'MAVEN_ARGS')}
    result = {'status': 'RUNNING', 'baseline': baseline, 'real_provider_calls': 0,
              'checks': [], 'screenshots': {}, 'page_errors': [], 'external_requests': []}
    processes, logs = [], []
    with tempfile.TemporaryDirectory(prefix='distilly-first-entry-') as tmp:
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
            result['stage']='default-JVM startup'
            started=time.monotonic();server=launch('api',[safe.get('JAVA_BIN','java'),'-jar',str(jar)])
            wait(healthy);result['default_jvm_startup_seconds']=round(time.monotonic()-started,3)
            launch('mock-worker',[sys.executable,str(ROOT/'worker/worker.py')])
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
                    page.goto(base+'/',wait_until='networkidle');page.locator('#token').fill('demo-owner');page.locator('#login-form button').click()
                    page.locator('#workspace').wait_for(state='visible');page.locator('#empty-create').click()
                    if baseline:
                        page.locator('#notice').filter(has_text='请先创建工作空间').wait_for();assert not page.locator('#interaction-dialog').is_visible()
                        shot('baseline-empty-create-dead-end');page.locator('#new-project').click()
                    page.locator('#interaction-value').fill('合成首次导入旅程');page.locator('#interaction-form button.primary').click()
                    page.wait_for_function("()=>document.querySelector('#projects').selectedOptions[0]?.textContent==='合成首次导入旅程'")
                    project=page.locator('#projects').input_value()
                    page.locator('#empty-create').click();form=page.locator('#persona-form');form.locator('[name="name"]').fill('合成初见伙伴')
                    with page.expect_response(lambda response:response.url==base+f'/api/projects/{project}/personas' and response.request.method=='POST') as saved:page.locator('#save-persona').click()
                    person=saved.value.json()['id'];page.locator('#persona-name').filter(has_text='合成初见伙伴').wait_for()
                    page.locator('#personality-create-link').click();page.get_by_role('button',name='沿用人物设定新增人格',exact=True).click()
                    create=page.get_by_role('dialog',name='新增人格',exact=True);create.locator('input[type="checkbox"]').check()
                    with page.expect_response(lambda response:response.url==base+f'/api/projects/{project}/personalities' and response.request.method=='POST') as saved:create.get_by_role('button',name='保存人格设定',exact=True).click()
                    personality=saved.value.json()['id'];detail=page.get_by_role('dialog',name='人格详情',exact=True)
                    detail.get_by_role('button',name='生成新候选',exact=True).click();generate=page.get_by_role('dialog',name='生成人格 · 选择材料',exact=True)
                    if baseline:
                        assert generate.get_by_role('button',name='上传 TXT 材料后返回',exact=True).count()==0
                        generate.get_by_text('暂无材料，可直接按原创设定生成，或先到材料管理上传。',exact=True).wait_for();shot('baseline-empty-picker-no-upload-return')
                        result['status']='OBSERVED_GAP';result['checks'].append('empty Studio create only errors without creating workspace; empty personality picker has no actionable TXT import/return and requires manual navigation');return
                    result['checks'].append('fresh empty Studio action opens the existing create-workspace dialog, then existing create-person form and scoped personality prefill; material consent remains explicit')
                    result['stage']='cancel import and resume saved draft after reload'
                    generate.get_by_role('button',name='上传 TXT 材料后返回',exact=True).click()
                    upload=page.locator('#upload-modal');assert upload.is_visible();assert page.locator('#upload-continue').is_hidden()
                    page.locator('#file').set_input_files({'name':'synthetic.txt','mimeType':'text/plain','buffer':SOURCE.encode()})
                    upload.get_by_role('button',name='取消',exact=True).click()
                    assert api(f'/api/projects/{project}/materials')==[]
                    page.reload(wait_until='networkidle')
                    row=page.locator('#personality-library tr').filter(has_text='合成初见伙伴人格')
                    row.get_by_role('button',name='生成',exact=True).wait_for()
                    assert len(api(f'/api/projects/{project}/personalities'))==1
                    row.get_by_role('button',name='生成',exact=True).click()
                    generate=page.get_by_role('dialog',name='生成人格 · 选择材料',exact=True)
                    generate.get_by_role('button',name='上传 TXT 材料后返回',exact=True).click()
                    result['checks'].append('cancelled import writes no material; reload finds the one saved personality draft and continues through its existing Generate action without duplicate creation')
                    result['stage']='actual permission loss while upload is open'
                    api(f'/api/projects/{project}/members',{'user_id':'editor','role':'OWNER'},method='PUT')
                    api(f'/api/projects/{project}/members',{'user_id':'owner','role':'VIEWER'},actor='editor',method='PUT')
                    page.locator('#file').set_input_files({'name':'synthetic.txt','mimeType':'text/plain','buffer':SOURCE.encode()})
                    page.locator('#upload-only').click();page.locator('#upload-error').filter(has_text='权限已变化').wait_for()
                    assert page.locator('#upload-only').is_disabled()
                    assert api(f'/api/projects/{project}/materials',actor='editor')==[]
                    shot('revoked-upload-remains-blocked')
                    api(f'/api/projects/{project}/members',{'user_id':'owner','role':'OWNER'},actor='editor',method='PUT')
                    upload.get_by_role('button',name='取消',exact=True).click();page.reload(wait_until='networkidle')
                    row=page.locator('#personality-library tr').filter(has_text='合成初见伙伴人格')
                    row.get_by_role('button',name='生成',exact=True).click()
                    generate=page.get_by_role('dialog',name='生成人格 · 选择材料',exact=True)
                    generate.get_by_placeholder('搜索材料名称或上传人').fill('不匹配的旧搜索')
                    generate.get_by_role('button',name='搜索',exact=True).click()
                    generate.get_by_role('button',name='上传 TXT 材料后返回',exact=True).click()
                    result['checks'].append('actual owner-to-viewer revocation while dialog opens: server403, upload disabled, zero saved materials; explicit permission restoration plus reload resumes existing draft')
                    result['stage']='correct invalid UTF-8 and reuse existing upload form'
                    page.locator('#file').set_input_files({'name':'invalid.txt','mimeType':'text/plain','buffer':bytes([255,254,0,65])})
                    page.locator('#upload-only').click();page.locator('#upload-error').filter(has_text='UTF-8').wait_for()
                    assert upload.is_visible() and page.locator('#upload-only').is_enabled()
                    shot('invalid-utf8-correctable')
                    page.locator('#file').set_input_files({'name':'synthetic.txt','mimeType':'text/plain','buffer':SOURCE.encode()})
                    # Only the paginated list refresh fails; the saved upload and fresh picker read remain authoritative.
                    page.route(base+f'/api/projects/{project}/materials/page?*',lambda route:route.fulfill(status=503,content_type='application/json',body=json.dumps({'error':'synthetic_list_unavailable'})),times=1)
                    page.locator('#upload-only').click()
                    generate=page.get_by_role('dialog',name='生成人格 · 选择材料',exact=True)
                    selected=generate.get_by_role('checkbox',name='选择材料 synthetic.txt');selected.wait_for()
                    assert selected.is_checked();assert '已选择 1 份材料' in generate.inner_text()
                    assert len(api(f'/api/projects/{project}/materials'))==1
                    generate.get_by_role('status').filter(has_text='材料已保存，材料列表暂未刷新').wait_for()
                    assert generate.get_by_placeholder('搜索材料名称或上传人').input_value()==''
                    shot('saved-upload-returns-to-selected-picker')
                    result['checks'].append('invalid UTF-8422 stays correctable; one valid synthetic TXT uses the existing upload dialog, saved-vs-list-refresh failure is explicit, return re-reads and selects only the uploaded authorized-space material without generating automatically')
                    result['stage']='existing material and draft survive leaving and reload'
                    generate.get_by_role('button',name='关闭',exact=True).click()
                    page.locator('[data-page="materials"]').click();page.locator('#materials').filter(has_text='synthetic.txt').wait_for()
                    page.locator('[data-page="personalities"]').click();page.reload(wait_until='networkidle')
                    row=page.locator('#personality-library tr').filter(has_text='合成初见伙伴人格')
                    row.get_by_role('button',name='生成',exact=True).click()
                    generate=page.get_by_role('dialog',name='生成人格 · 选择材料',exact=True)
                    material=generate.get_by_role('checkbox',name='选择材料 synthetic.txt');material.wait_for();assert not material.is_checked()
                    assert generate.get_by_role('button',name='再上传 TXT 材料',exact=True).is_visible()
                    assert len(api(f'/api/projects/{project}/materials'))==1 and len(api(f'/api/projects/{project}/personalities'))==1
                    material.check();generate.get_by_role('button',name='提交生成',exact=True).click()
                    detail=page.get_by_role('dialog',name='人格详情',exact=True)
                    detail.get_by_role('button',name='批准并发布',exact=True).wait_for(timeout=20000)
                    detail.get_by_role('button',name='批准并发布',exact=True).click()
                    detail.get_by_role('button',name='绑定到虚拟人',exact=True).click()
                    binding=page.get_by_role('dialog',name='绑定人格版本',exact=True)
                    assert binding.get_by_label('选择虚拟人').input_value()==person
                    binding.get_by_role('button',name='确认绑定',exact=True).click()
                    binding.get_by_role('link',name='返回 合成初见伙伴 开始交流',exact=True).click()
                    page.wait_for_function("()=>document.querySelector('#heading').textContent==='交流与记忆'")
                    page.locator('#new-chat').click();page.locator('#message').fill('你好，合成初见伙伴。');page.locator('#send').click()
                    page.locator('#messages .bubble.assistant').wait_for(timeout=20000)
                    assert '模拟' in page.locator('#messages .bubble.assistant').inner_text()
                    shot('first-entry-reaches-mock-chat')
                    result['checks'].append('existing TXT and draft survive page leave/reload, existing picker requires explicit material selection, mock generation/review/binding returns the intended person and completes one first mock conversation')
                    page.locator('#logout').click();assert page.locator('#workspace').is_hidden()
                    assert not result['page_errors'] and not result['external_requests']
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
    print('PASS: first-entry',len(result['checks']),'groups; real calls 0')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--baseline',action='store_true')
    parser.add_argument('--output',type=Path,default=ROOT/'server/target/first-entry-e2e')
    args=parser.parse_args();run(args.output,args.baseline)
