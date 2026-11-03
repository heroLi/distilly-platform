#!/usr/bin/env python3
"""Native reference UI on isolated H2/mock Worker/loopback; exposed synthetic regressions."""
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
TOKEN = 'reference-ui-synthetic-worker-token-32-characters'


def run(output):
    output.mkdir(parents=True, exist_ok=True)
    safe = {k: os.environ[k] for k in ('HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'PATH', 'JAVA_HOME', 'JAVA_BIN') if k in os.environ}
    result = {'status': 'RUNNING', 'real_provider_calls': 0, 'holdout_run': False, 'checks': [],
              'screenshots': {}, 'page_errors': [], 'external_requests': []}
    processes, logs = [], []
    with tempfile.TemporaryDirectory(prefix='distilly-reference-ui-') as tmp:
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
            raise AssertionError('reference_ui_bounded_condition_timeout')

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
            project = api('/api/projects', {'title': '合成原文参考界面'})['id']
            api(f'/api/projects/{project}/members', {'user_id': 'viewer', 'role': 'VIEWER'}, method='PUT')
            selected = person('合成原文参考伙伴'); empty = person('合成空记录伙伴')
            conversation = api(f'/api/personas/{selected}/conversations', {}, actor='viewer')['id']
            text = '请记住，合成记录：绒石灯座禁止使用青藤电池。<img src=x onerror="window.__referenceXss=true"> <script>window.__referenceXss=true</script>'
            done(api(f'/api/conversations/{conversation}/messages', {'message': text}, actor='viewer'), actor='viewer')
            rows = api(f'/api/personas/{selected}/memories', actor='viewer'); assert len(rows) == 1
            memory = rows[0]['id']
            api(f'/api/personas/{selected}/memories/{memory}', {'status': 'CONFIRMED'}, actor='viewer', method='PUT')
            jobs_before = len(api(f'/api/personas/{selected}/jobs', actor='viewer'))
            messages_before = len(api('/api/conversations/' + conversation, actor='viewer')['messages'])
            from playwright.sync_api import sync_playwright, expect
            with sync_playwright() as pw:
                browser = launch_chromium(pw); page = browser.new_page(viewport={'width': 1360, 'height': 950})
                page.on('pageerror', lambda error: result['page_errors'].append(type(error).__name__))
                page.route('**/*', lambda route: route.continue_() if route.request.url.startswith(base + '/') or route.request.url.startswith('data:') else (result['external_requests'].append('blocked'), route.abort()))
                calls = []
                page.on('request', lambda request: calls.append('reference_read') if request.method == 'POST' and request.url.endswith('/memory-references') else None)
                page.goto(base); page.locator('#token').fill('demo-viewer'); page.locator('#login-form button').click()
                page.locator('#projects').select_option(project); page.locator(f'[data-person="{selected}"]').click(); page.locator('[data-view="chat"]').click()
                entry = page.locator('#memory-reference-open'); expect(entry).to_be_enabled()
                page.locator('#message').fill('合成未发送聊天草稿')
                assert not calls, 'reference_should_not_auto_read'
                entry.click(); expect(page.locator('#memory-reference-query')).to_be_focused(); assert not calls
                dialog = page.locator('#memory-reference-dialog'); expect(dialog).to_be_visible()
                expect(dialog).to_contain_text('支持关系未判定'); expect(dialog).to_contain_text('不是原始聊天逐字记录')

                def lookup(query='绒石灯座青藤电池'):
                    page.locator('#memory-reference-query').fill(query); page.locator('#memory-reference-submit').click()

                lookup(); quote = page.locator('#memory-reference-results pre'); expect(quote).to_have_count(1)
                assert quote.text_content() == text, 'literal_record_mismatch'
                assert page.locator('#memory-reference-results img, #memory-reference-results script').count() == 0
                assert page.evaluate('typeof window.__referenceXss') == 'undefined'
                assert jobs_before == len(api(f'/api/personas/{selected}/jobs', actor='viewer'))
                assert messages_before == len(api('/api/conversations/' + conversation, actor='viewer')['messages'])
                result['checks'].append('explicit native entry, actual SQL literal quote, no model/task/history writes, HTML remains text')
                for name, viewport in [('desktop-records', {'width': 1360, 'height': 950}), ('mobile-records', {'width': 390, 'height': 844})]:
                    page.set_viewport_size(viewport); dialog.evaluate('(el)=>el.scrollTop=0')
                    page.evaluate('()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))')
                    bounds = dialog.bounding_box(); assert bounds['x'] >= 0 and bounds['x'] + bounds['width'] <= viewport['width'] + 1
                    assert bounds['y'] >= 0 and bounds['y'] + bounds['height'] <= viewport['height'] + 1
                    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1')
                    shot = output / (name + '.png'); page.screenshot(path=str(shot))
                    result['screenshots'][name] = {'file': str(shot), 'sha256': hashlib.sha256(shot.read_bytes()).hexdigest()}
                    if name == 'mobile-records':
                        dialog.evaluate('(el)=>el.scrollTop=el.scrollHeight')
                        page.evaluate('()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))')
                        shot = output / 'mobile-records-bottom.png'; page.screenshot(path=str(shot))
                        result['screenshots']['mobile-records-bottom'] = {'file': str(shot), 'sha256': hashlib.sha256(shot.read_bytes()).hexdigest()}
                        dialog.evaluate('(el)=>el.scrollTop=0')
                result['checks'].append('desktop1360 and mobile390 native dialog bounds, text wrapping and screenshots')
                page.set_viewport_size({'width': 1360, 'height': 950})
                lookup('合成无匹配火星轨道'); expect(page.locator('#memory-reference-status')).to_contain_text('未匹配不表示从未发生'); expect(quote).to_have_count(0)
                result['checks'].append('empty results clear previous quote and do not imply event never happened')
                lookup(); expect(quote).to_have_count(1)
                pattern = '**/memory-references'
                def fail(route): route.fulfill(status=503, content_type='application/json', body='{"error":"synthetic_unavailable"}')
                page.route(pattern, fail); page.locator('#memory-reference-refresh').click(); expect(page.locator('#memory-reference-error')).to_contain_text('未完成'); expect(quote).to_have_count(0)
                assert page.locator('#memory-reference-query').input_value() == '绒石灯座青藤电池'
                page.unroute(pattern, fail); page.locator('#memory-reference-refresh').click(); expect(quote).to_have_count(1)
                result['checks'].append('request failure clears quote, retains editable query and explicit refresh recovers')
                corrected = '请记住，合成纠错：绒石灯座禁止使用青藤电池，需先核对说明。'
                api(f'/api/personas/{selected}/memories/{memory}', {'status': 'CONFIRMED', 'body': corrected}, actor='viewer', method='PUT')
                page.evaluate('loadMemories(true)'); expect(quote).to_have_count(0); expect(page.locator('#memory-reference-status')).to_contain_text('已变化')
                page.locator('#memory-reference-refresh').click(); expect(quote).to_have_count(1); assert quote.text_content() == corrected
                result['checks'].append('actual source correction observed by current loader invalidates old quote; refresh loads current SQL text')
                pending = []
                def hold(route): pending.append((route, route.fetch()))
                page.route(pattern, hold); page.locator('#memory-reference-refresh').click(); page.wait_for_function('document.querySelector("#memory-reference-submit").disabled')
                pending_deadline = time.monotonic() + 10
                while not pending and time.monotonic() < pending_deadline: page.wait_for_timeout(20)
                assert pending, 'reference_request_not_intercepted'
                page.evaluate('loadMemories(true)'); expect(page.locator('#memory-reference-status')).to_contain_text('重新加载')
                pending[0][0].fulfill(response=pending[0][1]); page.wait_for_timeout(100); expect(quote).to_have_count(0)
                page.unroute(pattern, hold); page.locator('#memory-reference-refresh').click(); expect(quote).to_have_count(1)
                result['checks'].append('accepted real memory reload supersedes pending fetched snapshot; explicit refresh recovers')
                pending.clear(); page.route(pattern, hold); page.locator('#memory-reference-refresh').click()
                pending_deadline = time.monotonic() + 10
                while not pending and time.monotonic() < pending_deadline: page.wait_for_timeout(20)
                assert pending, 'second_reference_request_not_intercepted'
                page.locator('#memory-reference-close').click(); expect(dialog).not_to_be_visible()
                expect(page.locator('#message')).to_have_value('合成未发送聊天草稿')
                newer = '请记住，合成新版：绒石灯座禁止使用青藤电池，改为核对新版说明。'
                api(f'/api/personas/{selected}/memories/{memory}', {'status': 'CONFIRMED', 'body': newer}, actor='viewer', method='PUT')
                page.unroute(pattern, hold); entry.click(); lookup(); expect(quote).to_have_count(1); assert quote.text_content() == newer
                pending[0][0].fulfill(response=pending[0][1]); page.wait_for_timeout(100)
                assert quote.text_content() == newer, 'closed_request_overwrote_current_quote'
                result['checks'].append('close clears private state and a real late old snapshot cannot overwrite reopened current source; chat draft preserved')
                api('/api/personas/' + selected, method='DELETE'); page.locator('#memory-reference-refresh').click()
                expect(page.locator('#memory-reference-error')).to_contain_text('不可访问'); expect(quote).to_have_count(0)
                expect(page.locator('#memory-reference-query')).to_have_value('')
                page.keyboard.press('Escape'); expect(dialog).not_to_be_visible()
                page.locator(f'[data-person="{empty}"]').click(); expect(entry).to_be_enabled(); entry.click()
                expect(page.locator('#memory-reference-query')).to_have_value(''); expect(quote).to_have_count(0)
                lookup(); expect(page.locator('#memory-reference-status')).to_contain_text('没有匹配'); expect(quote).to_have_count(0)
                result['checks'].append('real archived-persona denial clears visible quote/query; Escape and persona switch retain no previous private result')
                page.locator('#memory-reference-close').click(); page.locator('#logout').click()
                assert page.locator('#memory-reference-results').text_content() == ''
                assert page.locator('#memory-reference-query').input_value() == ''
                assert not result['page_errors'] and not result['external_requests']
                result['checks'].append('logout removes private query and result; no page errors or external requests')
                page.close(); browser.close()
            result['status'] = 'PASS'
        except Exception as error:
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
    print('PASS: memory-reference UI', len(result['checks']), 'native browser groups; desktop/mobile; real provider calls0; semantic support NOT_ASSESSED')
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'server/target/memory-reference-ui-e2e')
    args = parser.parse_args()
    return run(args.output.resolve())


if __name__ == '__main__':
    raise SystemExit(main())
