#!/usr/bin/env python3
"""Native multiple pages: tab-local logout and current denial cleanup; background state reported honestly."""
from browser_support import launch_chromium
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
TOKEN = 'multi-tab-synthetic-worker-token-32-characters'


def run(output):
    output.mkdir(parents=True, exist_ok=True)
    safe = {k: os.environ[k] for k in ('HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'PATH', 'JAVA_HOME', 'JAVA_BIN') if k in os.environ}
    result = {'status': 'RUNNING', 'real_provider_calls': 0, 'holdout_run': False, 'checks': [],
              'screenshots': {}, 'page_errors': [], 'external_requests': []}
    processes, logs = [], []
    with tempfile.TemporaryDirectory(prefix='distilly-multi-page-') as tmp:
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

        def api(path, body=None, actor='owner', method=None):
            request = urllib.request.Request(base + path, None if body is None else json.dumps(body).encode(),
                                             {'Authorization': 'Bearer demo-' + actor, 'Content-Type': 'application/json'},
                                             method=method or ('GET' if body is None else 'POST'))
            with opener.open(request, timeout=8) as response:
                raw = response.read()
                return json.loads(raw) if raw else None

        def wait(fn):
            until = time.monotonic() + 30
            while time.monotonic() < until:
                value = fn()
                if value: return value
                time.sleep(.05)
            raise AssertionError('bfcache_ui_bounded_condition_timeout')

        def launch(name, command):
            log = (output / (name + '.log')).open('w'); logs.append(log)
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
            processes.append(process); return process

        def done(job, actor='owner'):
            def poll():
                row = api('/api/persona-jobs/' + job['id'], actor=actor)
                assert row['status'] not in ('FAILED', 'CANCELLED'), 'synthetic_job_failed'
                return row['result'] if row['status'] == 'SUCCEEDED' else None
            return wait(poll)

        def person(name):
            p = api(f'/api/projects/{project}/personas', {'name': name, 'family': 'relationship', 'origin': 'original'})['id']
            version = done(api(f'/api/personas/{p}/generate', {'material_ids': []}))['version_id']
            api(f'/api/personas/{p}/versions/{version}/review', {'approve': True})
            return p

        try:
            server = launch('api', [safe.get('JAVA_BIN', 'java'), '-jar', str(jar)])
            started = time.monotonic()
            def healthy():
                assert server.poll() is None
                try: return api('/health') == {'status': 'UP'}
                except OSError: return False
            wait(healthy); result['default_jvm_startup_seconds'] = round(time.monotonic() - started, 3)
            launch('mock-worker', [sys.executable, 'worker/worker.py'])
            project = api('/api/projects', {'title': '合成多页访问验证'})['id']
            api(f'/api/projects/{project}/members', {'user_id': 'viewer', 'role': 'VIEWER'}, method='PUT')
            selected = person('合成多页伙伴')
            conversation = api(f'/api/personas/{selected}/conversations', {}, actor='viewer')['id']
            original = '请记住，合成多页资料：霜石灯座只用合成银松电池。'
            draft = '合成尚未发送的私人草稿-X9'
            done(api(f'/api/conversations/{conversation}/messages', {'message': original}, actor='viewer'), actor='viewer')
            memory = api(f'/api/personas/{selected}/memories', actor='viewer')[0]['id']
            api(f'/api/personas/{selected}/memories/{memory}', {'status': 'CONFIRMED'}, actor='viewer', method='PUT')
            from browser_support import wait_for_browser_condition
            from playwright.sync_api import sync_playwright, expect
            with sync_playwright() as pw:
                browser = launch_chromium(pw, native_bfcache=True)
                try:
                    context = browser.new_context(viewport={'width': 1360, 'height': 1000})
                    a = context.new_page(); denied = []
                    a.on('pageerror', lambda e: result['page_errors'].append(type(e).__name__))
                    def observed(response):
                        if response.url == base + '/api/personas/' + selected + '/jobs' and response.status == 404:
                            denied.append(response.status)
                    a.on('response', observed)
                    a.add_init_script("window.nativeShows=[];window.addEventListener('pageshow',e=>nativeShows.push(e.persisted));")
                    a.goto(base + '/?workspace=' + project + '&persona=' + selected + '#chat', wait_until='networkidle')
                    a.locator('#token').fill('demo-viewer'); a.locator('#login-form button').click()
                    expect(a.locator('#workspace')).to_be_visible(); a.locator('[data-view=chat]').click()
                    a.locator('#conversations').select_option(conversation)
                    expect(a.locator('#messages')).to_contain_text('【模拟交流】')
                    with a.expect_popup() as opened:
                        a.evaluate("window.open('/knowledge.html','syntheticOtherTab')")
                    b = opened.value; b.wait_for_load_state('networkidle'); expect(b.locator('#workspace')).to_be_visible()
                    b.bring_to_front()
                    result['visibility_observation'] = {'A': a.evaluate('document.visibilityState'), 'B': b.evaluate('document.visibilityState'),
                                                        'A_focused': a.evaluate('document.hasFocus()'), 'B_focused': b.evaluate('document.hasFocus()')}
                    result['real_hidden_background_exercised'] = result['visibility_observation']['A'] == 'hidden'
                    b.locator('#logout').click(); expect(b.locator('#login')).to_be_visible()
                    expect(a.locator('#messages')).to_contain_text('【模拟交流】')
                    result['checks'].append('documented tab-local logout preserves the other legitimate login')
                    a.reload(wait_until='networkidle'); expect(a.locator('#messages')).to_contain_text('【模拟交流】')
                    assert a.evaluate('nativeShows') == [False]
                    assert a.evaluate('performance.getEntriesByType("navigation")[0].type') == 'reload'
                    result['checks'].append('ordinary reload after other-tab logout remains authorized; not BFCache')
                    a.locator('#message').fill(draft)
                    a.locator('[data-memory=source]').first.click(); expect(a.locator('#memory-source-dialog')).to_be_visible()
                    b.locator('#token').fill('demo-owner'); b.locator('#login-form button').click(); expect(b.locator('#workspace')).to_be_visible()
                    b.bring_to_front(); api(f'/api/personas/{selected}', method='DELETE')
                    wait_for_browser_condition(a, lambda: denied)
                    expect(a.locator('#chat-status')).to_contain_text('不可访问')
                    expect(a.locator('#conversations')).to_be_empty(); expect(a.locator('#message')).to_have_value('')
                    expect(a.locator('#messages')).not_to_contain_text('【模拟交流】'); expect(a.locator('#memories')).to_be_empty()
                    expect(a.locator('#memory-source-dialog')).not_to_be_visible(); expect(a.locator('#memory-source-body')).to_be_empty()
                    a.bring_to_front()
                    expect(a.locator('#message')).to_have_value(''); expect(a.locator('#conversations')).to_be_empty()
                    expect(b.locator('#workspace')).to_be_visible(); assert a.evaluate('PlatformSession.token()') == 'demo-viewer'
                    shot = output / 'current-denial.png'; a.screenshot(path=str(shot), full_page=True)
                    result['screenshots']['current-denial'] = {'file': shot.name, 'sha256': hashlib.sha256(shot.read_bytes()).hexdigest()}
                    result['checks'].append('processed current404 removes private titles, draft, memory and source modal; other login unchanged')
                    api(f'/api/personas/{selected}/restore', {})
                    a.locator('#refresh').click(); expect(a.locator('#messages')).to_contain_text('【模拟交流】')
                    expect(a.locator(f'[data-memory-body="{memory}"]')).to_have_value(original)
                    assert len(api('/api/conversations/' + conversation, actor='viewer')['messages']) == 2
                    result['checks'].append('legitimate restored access recovers original history once without re-login or duplicate task')
                    a.locator('#memory-reference-open').click(); a.locator('#memory-reference-query').fill('霜石灯座')
                    a.locator('#memory-reference-submit').click(); expect(a.locator('#memory-reference-results pre')).to_have_count(1)
                    denied.clear(); api(f'/api/personas/{selected}', method='DELETE')
                    wait_for_browser_condition(a, lambda: denied)
                    expect(a.locator('#memory-reference-dialog')).not_to_be_visible()
                    expect(a.locator('#memory-reference-query')).to_have_value(''); expect(a.locator('#memory-reference-results')).to_be_empty()
                    expect(a.locator('#conversations')).to_be_empty(); expect(a.locator('#message')).to_have_value('')
                    result['checks'].append('real automatic current404 closes the reference dialog before any explicit refresh; private reference/query erased')
                finally:
                    browser.close()
            assert not result['page_errors']
            result['status'] = 'PASS'
        except BaseException as error:
            result['status'] = 'FAIL'; result['failure_type'] = type(error).__name__
            raise
        finally:
            for process in reversed(processes):
                if process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=12)
                    except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
            for log in logs: log.close()
            result['cleanup_all_managed_processes_stopped'] = all(p.poll() is not None for p in processes)
            (output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print('PASS: native multi-page access', len(result['checks']), 'groups; actual visibility recorded, hidden background not assumed; real calls0')
    return 0


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'server/target/multi-tab-access-e2e')
    raise SystemExit(run(parser.parse_args().output.resolve()))
