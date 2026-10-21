#!/usr/bin/env python3
"""Native Chromium regression for the read-only synthetic cost ledger page.

Starts only an isolated loopback API with file H2. All users and ledger amounts
are synthetic; no Worker, provider, payment or real invoice is involved.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
TOKEN = 'synthetic-cost-page-unused-worker-token-32-characters'


def run(output):
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    safe = {key: value for key, value in os.environ.items() if key in (
        'HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'PATH', 'JAVA_HOME', 'JAVA_BIN',
        'MAVEN_OPTS', 'MAVEN_ARGS')}
    result = {'status': 'RUNNING', 'started_at_utc': datetime.now(timezone.utc).isoformat(),
              'real_provider_calls': 0, 'workers_started': 0, 'checks': [],
              'screenshots': {}, 'page_errors': [], 'external_browser_requests': [],
              'browser_cost_write_requests': 0}
    processes, logs = [], []
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def stop(process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    with tempfile.TemporaryDirectory(prefix='distilly-cost-page-') as tmp:
        folder = Path(tmp)
        jar = folder / 'runtime.jar'
        shutil.copy2(ROOT / 'server/target/distilly-platform-0.1.0.jar', jar)
        result['jar_sha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        base = f'http://127.0.0.1:{port}'
        env = dict(safe, PLATFORM_DEMO='true', PLATFORM_WORKER_TOKEN=TOKEN,
                   PLATFORM_PORT=str(port), PLATFORM_BIND='127.0.0.1',
                   PLATFORM_DB_URL='jdbc:h2:file:' + str(folder / 'db') + ';DATABASE_TO_LOWER=TRUE',
                   PLATFORM_DB_USER='sa', PLATFORM_DB_PASSWORD='', MODEL_MODE='mock')

        def api(path, body=None, actor='owner', method=None, expected=200):
            request = urllib.request.Request(
                base + path, None if body is None else json.dumps(body).encode(),
                {'Authorization': 'Bearer demo-' + actor, 'Content-Type': 'application/json'},
                method=method or ('POST' if body is not None else 'GET'))
            try:
                with opener.open(request, timeout=8) as response:
                    status, data = response.status, response.read()
                    if '/synthetic-costs' in path:
                        assert response.headers.get('Cache-Control') == 'no-store'
            except urllib.error.HTTPError as error:
                with error:
                    status, data = error.code, error.read()
            assert status == expected, (path, status, expected)
            value = json.loads(data) if data else None
            if status == 200 and '/synthetic-costs' in path:
                assert value['contract'] == 'SYNTHETIC_CNY_LEDGER_V1'
                assert value['synthetic'] is True and value['real_egress_authorized'] is False
            return value

        try:
            log = (output / 'api.log').open('w')
            logs.append(log)
            started = time.monotonic()
            server = subprocess.Popen([safe.get('JAVA_BIN', 'java'), '-jar', str(jar)],
                                      cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
            processes.append(server)
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                assert server.poll() is None, 'isolated API exited'
                try:
                    if api('/health') == {'status': 'UP'}:
                        break
                except OSError:
                    pass
                time.sleep(.1)
            else:
                raise AssertionError('default-JVM API startup timed out')
            result['default_jvm_startup_seconds'] = round(time.monotonic() - started, 3)
            project = api('/api/projects', {'title': '合成账本页面验收 A'})['id']
            other = api('/api/projects', {'title': '合成账本空空间 B'})['id']
            for actor, role in (('editor', 'EDITOR'), ('viewer', 'VIEWER')):
                api(f'/api/projects/{project}/members', {'user_id': actor, 'role': role}, method='PUT')
            path = f'/api/projects/{project}/synthetic-costs'
            before_jobs = api(f'/api/projects/{project}/jobs')

            def reserve(key, amount):
                return api(path + '/reservations', {'request_key': key, 'reserved_fen': amount})

            def outcome(row, key, status, evidence, actual=None):
                body = {'callback_key': key, 'outcome': status, 'evidence': evidence}
                if actual is not None:
                    body['actual_fen'] = actual
                return api(path + '/reservations/' + row['id'] + '/outcomes', body)

            from playwright.sync_api import sync_playwright
            with sync_playwright() as pw:
                browser = pw.chromium.launch(executable_path='/usr/bin/chromium', headless=True,
                                            args=['--no-sandbox', '--disable-dev-shm-usage'])

                def new_page(actor):
                    context = browser.new_context(viewport={'width': 1440, 'height': 1080})
                    page = context.new_page()
                    def restrict(route):
                        url = route.request.url
                        if url.startswith(base + '/') or url.startswith(('data:', 'blob:')):
                            if '/synthetic-costs' in url and route.request.method != 'GET':
                                result['browser_cost_write_requests'] += 1
                            route.continue_()
                        else:
                            result['external_browser_requests'].append('blocked external request')
                            route.abort()
                    page.route('**/*', restrict)
                    page.on('pageerror', lambda error: result['page_errors'].append(str(error)))
                    page.goto(base + '/knowledge.html', wait_until='networkidle')
                    page.locator('#token').fill('demo-' + actor)
                    page.locator('#login-form button').click()
                    page.locator('#workspace').wait_for(state='visible')
                    page.locator('#projects').select_option(project)
                    page.locator('[data-page="settings"]').click()
                    return page

                def screenshot(page, name):
                    shot = output / (name + '.png')
                    page.screenshot(path=str(shot), full_page=True)
                    result['screenshots'][name] = {'file': str(shot), 'sha256': hashlib.sha256(shot.read_bytes()).hexdigest()}

                def settled(page):
                    page.wait_for_function("() => !document.querySelector('#synthetic-cost-refresh').disabled")

                def rows(page):
                    return page.locator('#synthetic-cost-list tbody tr')

                def wait_route(page, pending):
                    deadline = time.monotonic() + 8
                    while not pending and time.monotonic() < deadline:
                        page.wait_for_timeout(25)
                    assert len(pending) == 1, 'one held synthetic list response was expected'

                owner = new_page('owner')
                owner.locator('#synthetic-cost-panel').wait_for(state='visible')
                owner.get_by_text('没有未决预留', exact=False).wait_for()
                settled(owner)
                assert '0 分（¥0.00）' in owner.locator('#synthetic-cost-summary').inner_text()
                assert '模拟' in owner.locator('#synthetic-cost-panel').inner_text()
                screenshot(owner, 'empty-synthetic-ledger')
                result['checks'].append('native OWNER settings page renders empty simulated ledger and explicit simulation label without a real-model entry')

                pending = reserve('pending-300', 300)
                unknown = reserve('unknown-200', 200)
                billed = reserve('settled-100', 100)
                released = reserve('released-100', 100)
                outcome(unknown, 'mark-unknown', 'UNCERTAIN', 'SIMULATED_UNKNOWN')
                outcome(billed, 'bill-50', 'SETTLED', 'SIMULATED_BILL', 50)
                outcome(released, 'zero-bill', 'RELEASED', 'SIMULATED_ZERO_BILL')
                owner.locator('#synthetic-cost-refresh').click()
                owner.locator('[data-cost-reservation="' + unknown['id'] + '"]').wait_for()
                settled(owner)
                assert rows(owner).count() == 2
                summary = owner.locator('#synthetic-cost-summary').inner_text()
                for text in ('500 分（¥5.00）', '50 分（¥0.50）', '450 分（¥4.50）'):
                    assert text in summary, text
                owner.locator('[data-cost-reservation="' + unknown['id'] + '"]').click()
                owner.locator('#synthetic-cost-detail').wait_for(state='visible')
                owner.locator('#synthetic-cost-detail-body').get_by_text('unknown-200', exact=False).wait_for()
                assert '200 分（¥2.00）' in owner.locator('#synthetic-cost-detail-body').inner_text()
                screenshot(owner, 'unknown-hold-and-summary')
                owner.locator('#synthetic-cost-detail-close').click()
                owner.locator('#synthetic-cost-detail').wait_for(state='hidden')
                for status, expected in (('ALL', 4), ('SETTLED', 1), ('RELEASED', 1)):
                    owner.locator('#synthetic-cost-filter').select_option(status)
                    settled(owner)
                    assert rows(owner).count() == expected
                assert 'released-100' in owner.locator('#synthetic-cost-list').inner_text()
                screenshot(owner, 'released-synthetic-reservation')
                owner.locator('#synthetic-cost-filter').select_option('SETTLED')
                settled(owner)
                assert '50 分（¥0.50）' in owner.locator('#synthetic-cost-list').inner_text()
                screenshot(owner, 'settled-synthetic-reservation')
                result['checks'].append('four fixture states render exact fen/yuan amounts; default unresolved includes unknown full hold; settled/released filters and event detail remain distinct')

                for number in range(21):
                    reserve('page-' + str(number), 1)
                owner.locator('#synthetic-cost-filter').select_option('UNRESOLVED')
                settled(owner)
                assert rows(owner).count() == 20
                owner.locator('#synthetic-cost-next').click()
                settled(owner)
                assert rows(owner).count() == 3
                assert owner.locator('#synthetic-cost-next').is_disabled()
                owner.locator('#synthetic-cost-prev').click()
                settled(owner)
                assert rows(owner).count() == 20
                result['checks'].append('native pagination exposes all 23 unresolved holds over two pages and prevents moving past the final page')

                list_pattern = '**/api/projects/*/synthetic-costs/reservations?*'
                held = []
                def hold_refresh(route):
                    held.append((route, route.fetch()))
                owner.route(list_pattern, hold_refresh)
                owner.locator('#synthetic-cost-refresh').click()
                owner.wait_for_function("() => document.querySelector('#synthetic-cost-refresh').disabled")
                wait_route(owner, held)
                owner.locator('#synthetic-cost-refresh').evaluate('(button)=>button.click()')
                assert len(held) == 1
                assert owner.locator('#synthetic-cost-summary').inner_text() == ''
                held[0][0].fulfill(response=held[0][1])
                owner.unroute(list_pattern, hold_refresh)
                settled(owner)
                assert '521 分（¥5.21）' in owner.locator('#synthetic-cost-summary').inner_text()
                result['checks'].append('held native refresh disables repeated submission and clears the preceding balance while its new snapshot is unresolved')

                def fail_refresh(route):
                    route.fulfill(status=500, content_type='application/json', body='{"error":"synthetic_current_read_failure"}')
                owner.route(list_pattern, fail_refresh)
                owner.locator('#synthetic-cost-refresh').click()
                owner.wait_for_function("() => document.querySelector('#synthetic-cost-status').textContent.includes('加载失败')")
                assert owner.locator('#synthetic-cost-summary').inner_text() == ''
                assert rows(owner).count() == 0
                assert owner.locator('#synthetic-cost-detail').is_hidden()
                screenshot(owner, 'current-error-clears-amounts')
                owner.unroute(list_pattern, fail_refresh)
                owner.locator('#synthetic-cost-refresh').click()
                settled(owner)
                assert rows(owner).count() == 20
                def deny_refresh(route):
                    route.fulfill(status=403, content_type='application/json', body='{"error":"synthetic_access_revoked"}')
                owner.route(list_pattern, deny_refresh)
                owner.locator('#synthetic-cost-refresh').click()
                owner.wait_for_function("() => document.querySelector('#synthetic-cost-status').textContent.includes('不可访问')")
                assert owner.locator('#synthetic-cost-summary').inner_text() == ''
                assert rows(owner).count() == 0
                assert owner.locator('#synthetic-cost-detail').is_hidden()
                owner.unroute(list_pattern, deny_refresh)
                owner.locator('#synthetic-cost-refresh').click()
                settled(owner)

                old = []
                def hold_old_project(route):
                    if '/api/projects/' + project + '/' in route.request.url:
                        old.append((route, route.fetch()))
                    else:
                        route.continue_()
                owner.route(list_pattern, hold_old_project)
                owner.locator('#synthetic-cost-refresh').click()
                owner.wait_for_function("() => document.querySelector('#synthetic-cost-refresh').disabled")
                wait_route(owner, old)
                owner.locator('#projects').select_option(other)
                owner.get_by_text('没有未决预留', exact=False).wait_for()
                old[0][0].fulfill(response=old[0][1])
                owner.unroute(list_pattern, hold_old_project)
                settled(owner)
                assert rows(owner).count() == 0
                assert '521 分' not in owner.locator('#synthetic-cost-summary').inner_text()
                assert 'pending-300' not in owner.locator('#synthetic-cost-list').inner_text()
                result['checks'].append('current server error clears stale balances/details; a late success from workspace A cannot overwrite workspace B empty state')

                for actor in ('viewer', 'editor'):
                    reader = new_page(actor)
                    assert reader.locator('#synthetic-cost-summary').inner_text() == ''
                    assert reader.locator('#synthetic-cost-list').inner_text() == ''
                    api(path + '/reservations', actor=actor, expected=403)
                    reader.close()
                owner.locator('#projects').select_option(project)
                settled(owner)
                owner.locator('#logout').click()
                owner.locator('#login').wait_for(state='visible')
                assert owner.locator('#synthetic-cost-summary').inner_text() == ''
                assert owner.locator('#synthetic-cost-list').inner_text() == ''
                assert owner.locator('#synthetic-cost-detail').is_hidden()
                assert result['browser_cost_write_requests'] == 0
                assert not result['page_errors'] and not result['external_browser_requests']
                result['checks'].append('editor/viewer cannot read owner ledger, logout clears all amounts and detail, and browser cost requests are GET-only with no external request or page error')
                browser.close()
            assert api(f'/api/projects/{project}/jobs') == before_jobs
            result['status'] = 'PASS'
        except Exception as error:
            result['status'] = 'FAIL'
            result['failure_type'] = type(error).__name__
            raise
        finally:
            for process in reversed(processes):
                stop(process)
            for log in logs:
                log.close()
            result['cleanup_all_processes_stopped'] = all(process.poll() is not None for process in processes)
            result['finished_at_utc'] = datetime.now(timezone.utc).isoformat()
            (output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print('PASS: synthetic-cost-page', len(result['checks']), 'native browser groups; real provider calls 0; Workers 0')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=ROOT / 'server/target/synthetic-cost-page-e2e')
    args = parser.parse_args()
    run(args.output)
