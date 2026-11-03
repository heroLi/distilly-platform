#!/usr/bin/env python3
"""Native BFCache private-page return; isolated H2, mock, no cache disabling or routes."""
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
TOKEN = 'bfcache-ui-synthetic-worker-token-32-characters'


def run(output):
    output.mkdir(parents=True, exist_ok=True)
    safe = {k: os.environ[k] for k in ('HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'PATH', 'JAVA_HOME', 'JAVA_BIN') if k in os.environ}
    result = {'status': 'RUNNING', 'real_provider_calls': 0, 'holdout_run': False, 'checks': [],
              'screenshots': {}, 'page_errors': [], 'external_requests': []}
    processes, logs = [], []
    with tempfile.TemporaryDirectory(prefix='distilly-bfcache-ui-') as tmp:
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
            project = api('/api/projects', {'title': '合成返回缓存验证'})['id']
            api(f'/api/projects/{project}/members', {'user_id': 'viewer', 'role': 'VIEWER'}, method='PUT')
            selected = person('合成返回伙伴')
            conversation = api(f'/api/personas/{selected}/conversations', {}, actor='viewer')['id']
            original = '请记住，合成返回资料：松石灯座只用合成青藤电池。'
            corrected = '合成更正资料：松石灯座只用合成赤砂电池。'
            receipt_key = 'platform-chat-submissions-v1'
            new_identity_receipt = json.dumps({json.dumps(['owner', project, selected, 'synthetic-new-conversation']):
                {'request_id': '11111111-1111-4111-8111-111111111111', 'digest': 'a' * 64}})
            done(api(f'/api/conversations/{conversation}/messages', {'message': original}, actor='viewer'), actor='viewer')
            memory = api(f'/api/personas/{selected}/memories', actor='viewer')[0]['id']
            api(f'/api/personas/{selected}/memories/{memory}', {'status': 'CONFIRMED'}, actor='viewer', method='PUT')
            from playwright.sync_api import sync_playwright, expect
            with sync_playwright() as pw:
                browser = launch_chromium(pw, native_bfcache=True)
                result['browser_version'] = browser.version
                result['route_interception'] = False
                result['cache_disabled'] = False
                try:
                    for scenario in ['authorized', 'corrected', 'archived', 'logout', 'account_switch']:
                        if scenario == 'logout':
                            api(f'/api/personas/{selected}/restore', {})
                        context = browser.new_context(viewport={'width': 1360, 'height': 1000})
                        try:
                            page = context.new_page(); documents = []
                            page.on('pageerror', lambda error: result['page_errors'].append(type(error).__name__))
                            def observed(request):
                                if request.is_navigation_request() and request.frame == page.main_frame:
                                    documents.append(request.url.split('?', 1)[0])
                                if not request.url.startswith((base + '/', 'data:', 'blob:')):
                                    result['external_requests'].append('unexpected_external_request')
                            page.on('request', observed)
                            # Observe real native events. Never synthesize pageshow or intercept requests.
                            page.add_init_script("window.nativeHistoryEvents=[];window.nativeDocumentId=crypto.randomUUID();for(const t of ['pageshow','pagehide'])window.addEventListener(t,e=>window.nativeHistoryEvents.push({type:t,persisted:e.persisted}));")
                            page.goto(base + '/?workspace=' + project + '&persona=' + selected + '#chat', wait_until='networkidle')
                            page.locator('#token').fill('demo-viewer'); page.locator('#login-form button').click()
                            expect(page.locator('#workspace')).to_be_visible()
                            page.locator('[data-view=chat]').click(); page.locator('#conversations').select_option(conversation)
                            expect(page.locator('#messages')).to_contain_text('【模拟交流】')
                            expect(page.locator(f'[data-memory-body="{memory}"]')).to_have_value(original if scenario in ['authorized', 'corrected'] else corrected)
                            document_id = page.evaluate('nativeDocumentId')
                            page.goto(base + '/knowledge.html', wait_until='networkidle')
                            if scenario == 'corrected':
                                api(f'/api/personas/{selected}/memories/{memory}', {'status': 'CONFIRMED', 'body': corrected}, actor='viewer', method='PUT')
                            if scenario == 'archived':
                                api(f'/api/personas/{selected}', method='DELETE')
                            if scenario in ['logout', 'account_switch']:
                                page.locator('#logout').click(); expect(page.locator('#login')).to_be_visible()
                                if scenario == 'account_switch':
                                    page.locator('#token').fill('demo-owner'); page.locator('#login-form button').click()
                                    expect(page.locator('#workspace')).to_be_visible()
                                    # Synthetic pending-send metadata owned by the newly signed-in identity.
                                    page.evaluate('([key,value])=>sessionStorage.setItem(key,value)', [receipt_key, new_identity_receipt])
                            before = len(documents); page.go_back(wait_until='commit')
                            page.wait_for_function('window.nativeHistoryEvents?.some(e=>e.type==="pageshow")')
                            events = page.evaluate('nativeHistoryEvents')
                            native_hit = page.evaluate('nativeDocumentId') == document_id and any(e['type'] == 'pageshow' and e['persisted'] for e in events)
                            row = {'scenario': scenario, 'native_bfcache_hit': native_hit, 'events': events,
                                   'network_document_reload': len(documents) != before}
                            result['checks'].append(row)
                            assert native_hit and not row['network_document_reload'], 'native_BFCache_NOT_HIT_not_a_pass'
                            if scenario in ['authorized', 'corrected']:
                                expect(page.locator('#messages')).to_contain_text('【模拟交流】')
                                expect(page.locator(f'[data-memory-body="{memory}"]')).to_have_value(corrected if scenario == 'corrected' else original)
                                expect(page.locator('#workspace')).to_be_visible()
                                row['authorized_history_restored'] = True
                            else:
                                expect(page.locator('#login')).to_be_visible(); expect(page.locator('#workspace')).to_be_hidden()
                                expect(page.locator('#messages')).to_be_empty(); expect(page.locator('#memories')).to_be_empty()
                                assert page.locator('#token').input_value() == ''
                                row['private_dom_empty'] = True
                                # Compare synthetic test identities only, never real credentials.
                                expected_login = {'account_switch': 'demo-owner', 'archived': 'demo-viewer', 'logout': ''}[scenario]
                                assert page.evaluate('PlatformSession.token()') == expected_login
                                row['companion_login_preserved'] = True
                                if scenario == 'account_switch':
                                    assert page.evaluate('(key)=>sessionStorage.getItem(key)', receipt_key) == new_identity_receipt, 'new_identity_send_receipt_erased'
                                    row['new_identity_send_receipt_preserved'] = True
                            shot = output / (scenario + '.png'); page.screenshot(path=str(shot), full_page=True)
                            result['screenshots'][scenario] = {'file': shot.name, 'sha256': hashlib.sha256(shot.read_bytes()).hexdigest()}
                            # A genuine network reload is recorded separately from native BFCache.
                            if scenario == 'logout':
                                page.reload(wait_until='networkidle'); expect(page.locator('#login')).to_be_visible()
                                assert page.evaluate('nativeHistoryEvents[0].persisted') is False
                                assert page.evaluate('performance.getEntriesByType("navigation")[0].type') == 'reload'
                                row['ordinary_reload_logged_out'] = True
                        finally:
                            context.close()
                finally:
                    browser.close()
            assert not result['page_errors'] and not result['external_requests']
            assert len(api('/api/conversations/' + conversation, actor='viewer')['messages']) == 2
            result['history_preserved'] = True
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
    print('PASS: native BFCache', len(result['checks']), 'return scenarios; ordinary reload separate; real calls0')
    return 0


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'server/target/bfcache-return-e2e')
    raise SystemExit(run(parser.parse_args().output.resolve()))

