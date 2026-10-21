#!/usr/bin/env python3
"""Synthetic long-context disclosure: dedicated H2/default JVM/mock Worker/Chromium."""
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
TOKEN = 'context-scope-synthetic-worker-token-32-characters'


def run(output, baseline=False):
    output.mkdir(parents=True, exist_ok=True)
    safe = {key: os.environ[key] for key in ('HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'PATH', 'JAVA_HOME', 'JAVA_BIN') if key in os.environ}
    result = {'status': 'RUNNING', 'real_provider_calls': 0, 'checks': [], 'screenshots': {}, 'page_errors': [], 'external_requests': []}
    processes, logs = [], []
    with tempfile.TemporaryDirectory(prefix='distilly-context-scope-') as tmp:
        folder = Path(tmp); jar = folder / 'runtime.jar'
        shutil.copy2(ROOT / 'server/target/distilly-platform-0.1.0.jar', jar)
        result['jar_sha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
        base = f'http://127.0.0.1:{port}'
        env = dict(safe, PLATFORM_DEMO='true', PLATFORM_WORKER_TOKEN=TOKEN, MODEL_MODE='mock',
                   PLATFORM_PORT=str(port), PLATFORM_BIND='127.0.0.1', PLATFORM_API_URL=base,
                   PLATFORM_DB_URL='jdbc:h2:file:' + str(folder / 'db') + ';DATABASE_TO_LOWER=TRUE',
                   PLATFORM_DB_USER='sa', PLATFORM_DB_PASSWORD='', WORKER_POLL_SECONDS='0.2')
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def launch(name, command):
            log = (output / (name + '.log')).open('w'); logs.append(log)
            p = subprocess.Popen(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
            processes.append(p); return p

        def api(path, body=None, actor='owner', method=None, expected=200, raw=None, kind='application/json'):
            data = raw if raw is not None else None if body is None else json.dumps(body).encode()
            req = urllib.request.Request(base + path, data, {'Authorization': 'Bearer demo-' + actor, 'Content-Type': kind},
                                         method=method or ('GET' if data is None else 'POST'))
            try:
                with opener.open(req, timeout=8) as response: status, content = response.status, response.read()
            except urllib.error.HTTPError as error:
                with error: status, content = error.code, error.read()
            assert status == expected, 'synthetic HTTP status mismatch'
            return json.loads(content) if content else None

        def wait(fn):
            until = time.monotonic() + 30
            while time.monotonic() < until:
                value = fn()
                if value: return value
                time.sleep(.05)
            raise AssertionError('synthetic bounded condition timed out')

        def done(job, actor='owner', work=False):
            def poll():
                row = api(('/api/jobs/' if work else '/api/persona-jobs/') + job['id'], actor=actor)
                assert row['status'] not in ('FAILED', 'CANCELLED'), 'synthetic task failed'
                return row['result'] if row['status'] == 'SUCCEEDED' else None
            return wait(poll)

        def chat(text):
            return done(api('/api/conversations/' + conversation + '/messages', {'message': text}, actor='viewer'), actor='viewer')

        def message_scope(message_id):
            return next(m for m in api('/api/conversations/' + conversation, actor='viewer')['messages'] if m['id'] == message_id).get('context_scope')

        try:
            started = time.monotonic(); server = launch('api', [safe.get('JAVA_BIN', 'java'), '-jar', str(jar)])
            def healthy():
                assert server.poll() is None
                try: return api('/health') == {'status': 'UP'}
                except OSError: return False
            wait(healthy); result['default_jvm_startup_seconds'] = round(time.monotonic() - started, 3)
            launch('mock-worker', [sys.executable, str(ROOT / 'worker/worker.py')])
            project = api('/api/projects', {'title': '合成长上下文验收'})['id']
            api(f'/api/projects/{project}/members', {'user_id': 'viewer', 'role': 'VIEWER'}, method='PUT')
            person = api(f'/api/projects/{project}/personas', {'name': '合成长聊伙伴', 'family': 'colleague', 'origin': 'original', 'consent': False})['id']
            version = done(api(f'/api/personas/{person}/generate', {'material_ids': []}))['version_id']
            api(f'/api/personas/{person}/versions/{version}/review', {'approve': True})
            conversation = api(f'/api/personas/{person}/conversations', {}, actor='viewer')['id']
            for index in range(16): chat('合成长历史' + str(index) + '甲' * 3600)
            answer = chat('请继续讨论这个合成计划。')
            budget = answer['context_budget']
            assert budget['omitted_history'] > 0
            if not baseline:
                scope = message_scope(answer['message_id'])
                assert scope['history_outside_window'] == 8
                assert scope['omitted_history'] == budget['omitted_history']
                assert set(scope) == {'history_window_limit', 'history_outside_window', 'omitted_history', 'omitted_memories', 'omitted_experiences', 'omitted_knowledge'}
                api('/api/conversations/' + conversation, expected=403)
                result['long_scope'] = scope
                result['checks'].append('actual 32 prior messages: 8 outside recent24, plus measured budget omissions; fixed input/version retained; only numeric per-reply metadata returned to conversation owner')
            from playwright.sync_api import sync_playwright
            with sync_playwright() as pw:
                browser = pw.chromium.launch(executable_path='/usr/bin/chromium', headless=True, args=['--no-sandbox', '--disable-dev-shm-usage'])
                try:
                    page = browser.new_page(viewport={'width': 1440, 'height': 1080})
                    page.route('**/*', lambda route: route.continue_() if route.request.url.startswith(base + '/') or route.request.url.startswith(('data:', 'blob:')) else (result['external_requests'].append('blocked external'), route.abort()))
                    page.on('pageerror', lambda error: result['page_errors'].append(type(error).__name__))
                    page.goto(base + f'/?workspace={project}&persona={person}#chat', wait_until='networkidle')
                    page.locator('#token').fill('demo-viewer'); page.locator('#login-form button').click()
                    page.locator('#messages .bubble.assistant').last.wait_for()
                    if baseline:
                        assert message_scope(answer['message_id']) is None
                        assert page.locator('#messages .context-notice').count() == 0
                        path = output / 'baseline-hidden-context-omission.png'; page.locator('#messages .bubble.assistant').last.screenshot(path=str(path))
                        result['screenshots']['baseline-hidden-context-omission'] = {'file': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
                        result['status'] = 'OBSERVED_GAP'; result['checks'].append('budget omitted actual history, but conversation response and actual chat bubble expose no per-reply context scope'); return
                    notice = page.locator('#messages .bubble.assistant').last.locator('.context-notice')
                    notice.locator('summary').filter(has_text='省略了部分上下文').wait_for(); notice.locator('summary').click()
                    assert '之外有 8 条' in notice.inner_text() and f'历史 {scope["omitted_history"]} 条' in notice.inner_text()
                    path = output / 'long-context-exact-scope.png'; notice.screenshot(path=str(path))
                    result['screenshots']['long-context-exact-scope'] = {'file': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
                    page.reload(wait_until='networkidle'); page.locator('#messages .context-notice').last.wait_for()
                    assert message_scope(answer['message_id']) == scope
                    result['checks'].append('native chat bubble discloses limited context and precise window/budget counts; reload reads the same historical scope, not latest mutable source state')
                    chat('请记住，我的合成松针计划在清晨。')
                    memory = api(f'/api/personas/{person}/memories', actor='viewer')[0]['id']
                    api(f'/api/personas/{person}/memories/{memory}', {'status': 'CONFIRMED'}, actor='viewer', method='PUT')
                    chat('合成松针计划')
                    api(f'/api/personas/{person}/memories/{memory}', {'status': 'CONFIRMED', 'body': '我的合成松针计划改在午后。'}, actor='viewer', method='PUT')
                    chat('合成松针计划')
                    api(f'/api/personas/{person}/memories/{memory}', actor='viewer', method='DELETE')
                    assert message_scope(answer['message_id']) == scope
                    boundary = 'synthetic-context-scope'
                    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.txt"\r\nContent-Type: text/plain\r\n\r\n合成排班记录：值班安排在周二。\r\n--{boundary}--\r\n').encode()
                    material = api(f'/api/projects/{project}/materials', raw=body, kind='multipart/form-data; boundary=' + boundary)['id']
                    topic = api(f'/api/projects/{project}/profiles', {'title': '合成排班记录'})['id']
                    job = api(f'/api/profiles/{topic}/distill', {'material_ids': [material]})
                    published = done(job, work=True)['version_id']; api('/api/versions/' + published + '/review', {'approve': True})
                    api(f'/api/personas/{person}/knowledge', {'profile_ids': [topic]}, method='PUT')
                    assert chat('合成排班记录')['citations']
                    api(f'/api/profiles/{topic}/jobs/{job["id"]}/experience', method='DELETE')
                    assert chat('合成排班记录')['citations'] == [] and message_scope(answer['message_id']) == scope
                    result['checks'].append('confirmed memory correction/deletion and actual knowledge withdrawal do not relabel historical reply counts; fresh reply stops citing withdrawn knowledge')
                    page.reload(wait_until='networkidle'); page.locator('#messages .context-notice').last.wait_for()
                    held = []; page.route(base + '/api/conversations/' + conversation, lambda route: held.append(route), times=1)
                    old = api('/api/conversations/' + conversation, actor='viewer')
                    page.locator('#chat-refresh').click(); wait(lambda: bool(held))
                    page.locator('#new-chat').click(); page.wait_for_function("()=>document.querySelector('#messages').textContent.includes('从一句问候开始')")
                    page.locator('#message').fill('合成未发送草稿')
                    held[0].fulfill(status=200, content_type='application/json', body=json.dumps(old))
                    page.wait_for_timeout(120)
                    assert page.locator('#messages .context-notice').count() == 0 and page.locator('#message').input_value() == '合成未发送草稿'
                    result['checks'].append('late old-conversation success containing context counts cannot inject notices into a new conversation or replace its draft')
                    page.locator('#conversations').select_option(conversation)
                    page.locator('#messages .context-notice').last.wait_for()
                    assert page.locator('#messages .context-notice').count() > 0
                    api('/api/personas/' + person, method='DELETE')
                    page.locator('#chat-refresh').click(); page.locator('#chat-status').filter(has_text='不可访问').wait_for()
                    assert page.locator('#messages .context-notice').count() == 0 and page.locator('#send').is_disabled()
                    page.locator('#logout').click(); assert page.locator('#messages').inner_text() == ''
                    assert not result['page_errors'] and not result['external_requests']
                    result['checks'].append('actual archive permission loss and logout clear messages/notices; private conversation read was403; no external requests or page errors')
                    result['status'] = 'PASS'
                finally: browser.close()
        except BaseException as error:
            result['status'] = 'FAIL'; result['failure_type'] = type(error).__name__; raise
        finally:
            for process in reversed(processes):
                if process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=12)
                    except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
            for log in logs: log.close()
            result['cleanup_all_managed_processes_stopped'] = all(p.poll() is not None for p in processes)
            (output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print('PASS: context-scope', len(result['checks']), 'groups; real provider calls0')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--baseline', action='store_true')
    parser.add_argument('--output', type=Path, default=ROOT / 'server/target/context-scope-e2e')
    args = parser.parse_args(); run(args.output.resolve(), args.baseline)
