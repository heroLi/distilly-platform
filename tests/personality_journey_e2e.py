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
TOKEN = 'personality-journey-synthetic-worker-token-32-characters'
SOURCE = '合成人格资料：表达简洁，先核对来源，再回答问题，不编造人物亲历。'


def run(output, baseline=False):
    output = output.resolve(); output.mkdir(parents=True, exist_ok=True)
    safe = {key: value for key, value in os.environ.items() if key in (
        'HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'PATH', 'JAVA_HOME', 'JAVA_BIN', 'MAVEN_OPTS', 'MAVEN_ARGS')}
    result = {'status': 'RUNNING', 'baseline': baseline, 'real_provider_calls': 0,
              'checks': [], 'screenshots': {}, 'page_errors': [], 'external_requests': []}
    processes, logs = [], []
    with tempfile.TemporaryDirectory(prefix='distilly-personality-journey-') as tmp:
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
            result['stage'] = 'default-JVM startup'
            started = time.monotonic(); server = launch('api', [safe.get('JAVA_BIN', 'java'), '-jar', str(jar)])
            wait(healthy); result['default_jvm_startup_seconds'] = round(time.monotonic() - started, 3)
            launch('mock-worker', [sys.executable, str(ROOT / 'worker/worker.py')])
            from playwright.sync_api import sync_playwright
            with sync_playwright() as pw:
                browser = pw.chromium.launch(executable_path='/usr/bin/chromium', headless=True,
                                            args=['--no-sandbox', '--disable-dev-shm-usage'])
                try:
                    context = browser.new_context(viewport={'width': 1440, 'height': 1080})
                    page = context.new_page()
                    def restrict(route):
                        if route.request.url.startswith(base + '/') or route.request.url.startswith(('data:', 'blob:')): route.continue_()
                        else: result['external_requests'].append('blocked external request'); route.abort()
                    page.route('**/*', restrict)
                    page.on('pageerror', lambda error: result['page_errors'].append(str(error)))
                    def shot(name):
                        path = output / (name + '.png'); page.screenshot(path=str(path), full_page=True)
                        result['screenshots'][name] = {'file': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
                    result['stage'] = 'first entry and synthetic person creation'
                    page.goto(base + '/', wait_until='networkidle')
                    page.locator('#token').fill('demo-owner'); page.locator('#login-form button').click()
                    page.locator('#workspace').wait_for(state='visible')
                    page.locator('#new-project').click(); page.locator('#interaction-value').fill('合成首次绑定旅程')
                    page.locator('#interaction-form button.primary').click()
                    page.wait_for_function("()=>document.querySelector('#projects').selectedOptions[0]?.textContent==='合成首次绑定旅程'")
                    project = page.locator('#projects').input_value()
                    page.locator('#create-persona').click()
                    form = page.locator('#persona-form'); form.locator('[name="name"]').fill('合成来源伙伴')
                    form.locator('[name="background"]').fill('原创合成角色，没有真人资料。')
                    with page.expect_response(lambda response: response.url == base + f'/api/projects/{project}/personas' and response.request.method == 'POST') as saved:
                        page.locator('#save-persona').click()
                    target = saved.value.json()['id']
                    page.locator('#persona-name').filter(has_text='合成来源伙伴').wait_for()
                    decoy = api(f'/api/projects/{project}/personas', {'name': '另一个合成人物', 'family': 'colleague',
                                'origin': 'original', 'personality': '简洁', 'consent': False})['id']
                    # The newer unrelated person is first in the library's existing list.
                    people = api(f'/api/projects/{project}/personas'); assert people[0]['id'] == decoy
                    page.locator('#personality-create-link').click()
                    page.locator('[data-page="personalities"]').wait_for()
                    result['stage'] = 'actual TXT upload and scoped prefill'
                    page.locator('[data-page="materials"]').click(); page.locator('#new-material').click()
                    page.locator('#file').set_input_files({'name': 'synthetic.txt', 'mimeType': 'text/plain', 'buffer': SOURCE.encode()})
                    page.locator('#upload-only').click(); page.locator('#upload-modal').wait_for(state='hidden')
                    page.locator('[data-page="personalities"]').click()
                    page.get_by_role('button', name='沿用人物设定新增人格', exact=True).click()
                    create = page.get_by_role('dialog', name='新增人格', exact=True)
                    create.locator('[name="name"]').wait_for()
                    assert create.locator('[name="name"]').input_value() == '合成来源伙伴人格'
                    consent = create.locator('input[type="checkbox"]'); assert not consent.is_checked(); consent.check()
                    with page.expect_response(lambda response: response.url == base + f'/api/projects/{project}/personalities' and response.request.method == 'POST') as saved:
                        create.get_by_role('button', name='保存人格设定', exact=True).click()
                    personality = saved.value.json()['id']
                    detail = page.get_by_role('dialog', name='人格详情', exact=True)
                    detail.get_by_role('button', name='生成新候选', exact=True).click()
                    generate = page.get_by_role('dialog', name='生成人格 · 选择材料', exact=True)
                    generate.get_by_role('checkbox', name='选择材料 synthetic.txt').check()
                    generate.get_by_role('button', name='提交生成', exact=True).click()
                    detail = page.get_by_role('dialog', name='人格详情', exact=True)
                    detail.get_by_role('button', name='批准并发布', exact=True).wait_for(timeout=20000)
                    detail.get_by_role('button', name='批准并发布', exact=True).click()
                    detail.get_by_role('button', name='绑定到虚拟人', exact=True).wait_for()
                    version = api('/api/personas/' + personality)['published_id']
                    detail.get_by_role('button', name='绑定到虚拟人', exact=True).click()
                    binding = page.get_by_role('dialog', name='绑定人格版本', exact=True)
                    selected = binding.get_by_label('选择虚拟人').input_value()
                    if baseline:
                        assert selected == decoy and selected != target
                        shot('baseline-wrong-default-target')
                        result['checks'].append('baseline native UI selects the first unrelated person instead of the explicit source person')
                        result['status'] = 'OBSERVED_GAP'; return
                    assert selected == target
                    shot('intended-source-preselected')
                    result['checks'].append('fresh login, workspace/person creation, actual synthetic TXT upload, explicit prefill consent, mock generation and publication; intended source preselected despite unrelated first row')
                    result['stage'] = 'lost binding response and read-only recovery'
                    path = base + f'/api/personas/{target}/personality'; writes = []
                    page.on('request', lambda request: writes.append(1) if request.url == path and request.method == 'PUT' else None)
                    def lost_ack(route):
                        response = route.fetch(); assert response.status == 200
                        route.abort('failed')
                    page.route(path, lost_ack, times=1)
                    binding.get_by_role('button', name='确认绑定', exact=True).click()
                    binding.get_by_role('status').filter(has_text='核对绑定状态').wait_for()
                    assert binding.get_by_label('选择虚拟人').input_value() == target
                    first = api('/api/personas/' + target); assert len(first['versions']) == 1
                    assert api('/api/personas/' + decoy)['published_id'] is None
                    binding.get_by_role('button', name='核对绑定状态', exact=True).click()
                    back = binding.get_by_role('link', name='返回 合成来源伙伴 开始交流', exact=True); back.wait_for()
                    assert len(writes) == 1
                    shot('lost-ack-readback-confirmed')
                    back.click(); page.locator('#persona-name').filter(has_text='合成来源伙伴').wait_for()
                    page.wait_for_function("()=>document.querySelector('#heading').textContent==='交流与记忆'")
                    page.reload(wait_until='networkidle'); page.locator('#persona-name').filter(has_text='合成来源伙伴').wait_for()
                    page.wait_for_function("()=>document.querySelector('#heading').textContent==='交流与记忆'")
                    api(f'/api/personas/{target}/personality', {'personality_id': personality, 'version_id': version}, method='PUT')
                    assert len(api('/api/personas/' + target)['versions']) == 1
                    result['checks'].append('committed PUT with lost browser response recovered by GET only; no duplicate binding; return and reload select the intended person and open chat, manual same-binding replay is idempotent')
                    result['stage'] = 'first actual mock conversation'
                    page.locator('#new-chat').click(); page.locator('#message').fill('你好，请先核对我们的合成资料。')
                    page.locator('#send').click(); page.locator('#messages .bubble.assistant').wait_for(timeout=20000)
                    assert '模拟' in page.locator('#messages .bubble.assistant').inner_text()
                    shot('first-mock-conversation')
                    result['checks'].append('new conversation and first mock assistant reply succeed using the returned bound person; this is workflow, not real model quality acceptance')
                    result['stage'] = 'permission revocation before confirm'
                    page.goto(base + f'/knowledge.html?workspace={project}&from_persona={target}#personalities', wait_until='networkidle')
                    page.locator('#personality-library tr').filter(has_text='合成来源伙伴人格').get_by_role('button', name='查看详情', exact=True).click()
                    detail = page.get_by_role('dialog', name='人格详情', exact=True)
                    detail.get_by_role('button', name='绑定到虚拟人', exact=True).click()
                    binding = page.get_by_role('dialog', name='绑定人格版本', exact=True)
                    api(f'/api/projects/{project}/members', {'user_id': 'editor', 'role': 'OWNER'}, method='PUT')
                    api(f'/api/projects/{project}/members', {'user_id': 'owner', 'role': 'EDITOR'}, actor='editor', method='PUT')
                    binding.get_by_role('button', name='确认绑定', exact=True).click()
                    binding.get_by_role('status').filter(has_text='权限已变化').wait_for()
                    assert binding.get_by_role('button', name='确认绑定', exact=True).is_disabled()
                    assert binding.get_by_role('link').count() == 0
                    assert len(api('/api/personas/' + target)['versions']) == 1
                    shot('revoked-binding-disabled')
                    api(f'/api/projects/{project}/members', {'user_id': 'owner', 'role': 'OWNER'}, actor='editor', method='PUT')
                    result['checks'].append('permission revoked after dialog opens: server403, no binding write or return success, controls disabled; existing version remains unchanged')
                    result['stage'] = 'return target missing after refresh and logout'
                    api('/api/personas/' + target, method='DELETE')
                    page.goto(base + f'/?workspace={project}&persona={target}#chat', wait_until='networkidle')
                    page.locator('#workspace').wait_for(state='visible')
                    assert page.locator('#persona-content').is_hidden()
                    assert page.locator('#messages .bubble').count() == 0
                    assert page.locator(f'[data-person="{decoy}"]').count() == 1
                    shot('missing-return-target-no-fallback')
                    page.locator('#logout').click(); assert page.locator('#workspace').is_hidden()
                    assert not result['page_errors'] and not result['external_requests']
                    result['checks'].append('archived return target never falls back to the first unrelated person on fresh load; private chat cleared and logout hides workspace')
                    result['status'] = 'PASS'; result['stage'] = 'completed'
                finally: browser.close()
        except BaseException as error:
            result['status'] = 'FAIL'; result['failure_type'] = type(error).__name__; raise
        finally:
            for process in reversed(processes): stop(process)
            for log in logs: log.close()
            result['cleanup_all_processes_stopped'] = all(process.poll() is not None for process in processes)
            (output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print('PASS: personality-journey', len(result['checks']), 'groups; real calls 0')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--output', type=Path, default=ROOT / 'server/target/personality-journey-e2e')
    parser.add_argument('--baseline', action='store_true'); args = parser.parse_args(); run(args.output, args.baseline)
