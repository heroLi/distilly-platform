#!/usr/bin/env python3
"""Isolated HTTP regression for the demo-only synthetic CNY reservation ledger.

No Worker or provider is started. All accounts, events and bills below are
synthetic; this ledger never authorizes actual model egress.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
WORKER_TOKEN = 'synthetic-ledger-unused-worker-token-32-characters'
USER_TOKEN = 'synthetic-ledger-nondemo-user-token-32-characters'


def run(output):
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    safe = {key: value for key, value in os.environ.items() if key in (
        'HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'PATH', 'JAVA_HOME', 'JAVA_BIN',
        'MAVEN_OPTS', 'MAVEN_ARGS')}
    result = {'status': 'RUNNING', 'started_at_utc': datetime.now(timezone.utc).isoformat(),
              'real_provider_calls': 0, 'workers_started': 0, 'checks': [],
              'default_jvm_startups_seconds': []}
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

    with tempfile.TemporaryDirectory(prefix='distilly-synthetic-ledger-') as tmp:
        folder = Path(tmp)
        jar = folder / 'runtime.jar'
        shutil.copy2(ROOT / 'server/target/distilly-platform-0.1.0.jar', jar)
        result['jar_sha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()

        def new_port():
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0))
                return sock.getsockname()[1]

        port = new_port()
        base = f'http://127.0.0.1:{port}'
        env = dict(safe, PLATFORM_DEMO='true', PLATFORM_WORKER_TOKEN=WORKER_TOKEN,
                   PLATFORM_PORT=str(port), PLATFORM_BIND='127.0.0.1',
                   PLATFORM_DB_URL='jdbc:h2:file:' + str(folder / 'demo-db') + ';DATABASE_TO_LOWER=TRUE',
                   PLATFORM_DB_USER='sa', PLATFORM_DB_PASSWORD='', MODEL_MODE='mock')

        def api(path, body=None, actor='owner', method=None, expected=200,
                target=base, token=None):
            request = urllib.request.Request(
                target + path,
                None if body is None else json.dumps(body).encode(),
                {'Authorization': 'Bearer ' + (token or 'demo-' + actor),
                 'Content-Type': 'application/json'},
                method=method or ('POST' if body is not None else 'GET'))
            try:
                with opener.open(request, timeout=8) as response:
                    status, data = response.status, response.read()
                    if '/synthetic-costs' in path and response.status == 200:
                        assert response.headers.get('Cache-Control') == 'no-store'
            except urllib.error.HTTPError as error:
                with error:
                    status, data = error.code, error.read()
            if expected is not None:
                assert status == expected, (path, status, expected)
            value = json.loads(data) if data else None
            if status == 200 and '/synthetic-costs' in path:
                assert value['contract'] == 'SYNTHETIC_CNY_LEDGER_V1'
                assert value['synthetic'] is True and value['real_egress_authorized'] is False
            return (status, value) if expected is None else value

        def launch(name, values, target):
            log = (output / (name + '.log')).open('w')
            logs.append(log)
            started = time.monotonic()
            process = subprocess.Popen([safe.get('JAVA_BIN', 'java'), '-jar', str(jar)],
                                       cwd=ROOT, env=values, stdout=log, stderr=subprocess.STDOUT)
            processes.append(process)
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                assert process.poll() is None, 'isolated API exited'
                try:
                    if api('/health', target=target) == {'status': 'UP'}:
                        result['default_jvm_startups_seconds'].append(round(time.monotonic() - started, 3))
                        return process
                except OSError:
                    pass
                time.sleep(.1)
            raise AssertionError('default-JVM API did not start within 30 seconds')

        try:
            server = launch('api', env, base)
            project = api('/api/projects', {'title': '合成费用账本隔离验收'})['id']
            path = f'/api/projects/{project}/synthetic-costs'
            for actor, role in (('editor', 'EDITOR'), ('viewer', 'VIEWER')):
                api(f'/api/projects/{project}/members', {'user_id': actor, 'role': role}, method='PUT')
            jobs_before = api(f'/api/projects/{project}/jobs')

            def summary(held, spent, count=None, unresolved=None, endpoint=path):
                row = api(endpoint)
                assert row['contract'] == 'SYNTHETIC_CNY_LEDGER_V1'
                assert row['synthetic'] is True and row['real_egress_authorized'] is False
                assert row['limit_fen'] == 1000 and row['held_fen'] == held and row['spent_fen'] == spent
                assert row['available_fen'] == 1000 - held - spent
                if count is not None:
                    assert row['reservation_count'] == count
                if unresolved is not None:
                    assert row['unresolved_count'] == unresolved
                return row

            def reserve(key, amount, expected=200, endpoint=path):
                return api(endpoint + '/reservations', {'request_key': key, 'reserved_fen': amount}, expected=expected)

            def detail(reservation, endpoint=path):
                return api(endpoint + '/reservations/' + reservation['id'])

            def outcome(reservation, key, status, evidence, actual=None,
                        expected=200, endpoint=path):
                body = {'callback_key': key, 'outcome': status, 'evidence': evidence}
                if actual is not None:
                    body['actual_fen'] = actual
                return api(endpoint + '/reservations/' + reservation['id'] + '/outcomes',
                           body, expected=expected)

            summary(0, 0, 0, 0)
            for actor in ('editor', 'viewer'):
                api(path, actor=actor, expected=403)
                api(path + '/reservations', {'request_key': 'denied-' + actor, 'reserved_fen': 100},
                    actor=actor, expected=403)
            for body in ({'request_key': 'zero', 'reserved_fen': 0},
                         {'request_key': 'negative', 'reserved_fen': -1},
                         {'request_key': 'overflow', 'reserved_fen': 1001},
                         {'request_key': 'fraction', 'reserved_fen': 1.5},
                         {'request_key': 'bool', 'reserved_fen': True},
                         {'request_key': '', 'reserved_fen': 100},
                         {'request_key': 'forged', 'reserved_fen': 100, 'real_egress_authorized': True}):
                api(path + '/reservations', body, expected=422)
            summary(0, 0, 0, 0)
            result['checks'].append('demo OWNER-only access and strict integer fen input; invalid/forged requests create no reservation')

            # Two distinct OWNER rows ensure the project budget lock, rather than
            # one caller's membership lock, must serialize the competing writes.
            api(f'/api/projects/{project}/members', {'user_id': 'editor', 'role': 'OWNER'}, method='PUT')
            barrier = threading.Barrier(2)
            def concurrent_reserve(index):
                barrier.wait(timeout=5)
                return api(path + '/reservations', {'request_key': f'parallel-{index}', 'reserved_fen': 600},
                           actor='owner' if index == 1 else 'editor', expected=None)
            with ThreadPoolExecutor(max_workers=2) as pool:
                attempts = list(pool.map(concurrent_reserve, (1, 2)))
            api(f'/api/projects/{project}/members', {'user_id': 'editor', 'role': 'EDITOR'}, method='PUT')
            assert sorted(status for status, _ in attempts) == [200, 409]
            winner = next(row for status, row in attempts if status == 200)
            assert winner['reserved_fen'] == 600 and winner['status'] == 'RESERVED'
            assert winner['actual_fen'] is None
            summary(600, 0, 1)
            initial_detail = detail(winner)
            assert len(initial_detail['events']) == 1
            replay = reserve(winner['request_key'], 600)
            assert replay['id'] == winner['id'] and detail(winner) == initial_detail
            reserve(winner['request_key'], 500, expected=409)
            reserve('exceeds-remaining', 401, expected=409)
            summary(600, 0, 1)
            result['checks'].append('two distinct OWNERs simultaneously reserving 600+600 against 1000 fen allow exactly one; request replay is idempotent and changed amount/oversubscription leave hold unchanged')

            unknown = reserve('uncertain-400', 400)
            summary(1000, 0, 2)
            outcome(winner, 'over-settlement', 'SETTLED', 'SIMULATED_BILL', 601, expected=409)
            summary(1000, 0, 2)
            uncertain = outcome(unknown, 'unknown-callback', 'UNCERTAIN', 'SIMULATED_UNKNOWN')
            assert uncertain['status'] == 'UNCERTAIN'
            unknown_acknowledged = time.monotonic()
            unknown_detail = detail(unknown)
            assert len(unknown_detail['events']) == 2
            outcome(unknown, 'unknown-callback', 'UNCERTAIN', 'SIMULATED_UNKNOWN')
            assert detail(unknown) == unknown_detail
            outcome(unknown, 'unknown-callback', 'RELEASED', 'SIMULATED_ZERO_BILL', expected=409)
            outcome(winner, 'unknown-callback', 'UNCERTAIN', 'SIMULATED_UNKNOWN', expected=409)
            summary(1000, 0, 2, 2)
            reserve('held-is-not-free', 1, expected=409)
            outcome(unknown, 'unsupported-release', 'RELEASED', 'SIMULATED_UNKNOWN', expected=422)
            outcome(unknown, 'missing-bill-amount', 'SETTLED', 'SIMULATED_BILL', expected=422)
            outcome(unknown, 'negative-bill-amount', 'SETTLED', 'SIMULATED_BILL', -1, expected=422)
            outcome(unknown, 'invented-bill', 'SETTLED', 'REAL_PROVIDER_BILL', 100, expected=422)
            assert detail(unknown) == unknown_detail
            summary(1000, 0, 2, 2)
            for actor in ('editor', 'viewer'):
                api(path + '/reservations/' + unknown['id'], actor=actor, expected=403)
                api(path + '/reservations/' + unknown['id'] + '/outcomes',
                    {'callback_key': 'denied-outcome-' + actor, 'outcome': 'RELEASED', 'evidence': 'SIMULATED_ZERO_BILL'},
                    actor=actor, expected=403)
            assert detail(unknown) == unknown_detail
            result['checks'].append('over-reservation settlement is rejected safely; UNCERTAIN retains the entire hold; exact callback replay adds no event and changed payload/resource or unauthorized release cannot free it')

            # Kill only the isolated fixture API, after its successful UNCERTAIN
            # response. Recovery must not depend on Spring/H2 shutdown hooks.
            crash_started = time.monotonic()
            assert server.poll() is None
            server.kill()
            server.wait(timeout=5)
            result['forced_crash'] = {
                'method': 'SIGKILL isolated API after acknowledged UNCERTAIN response',
                'exit_code': server.returncode,
                'kill_wait_seconds': round(time.monotonic() - crash_started, 3),
                'seconds_since_uncertain_response': round(time.monotonic() - unknown_acknowledged, 3),
                'graceful_shutdown': False}
            server = launch('api-restart', env, base)
            recovered_projects = api('/api/projects')
            recovered_status, _ = api(path, expected=None)
            result['post_crash_recovery'] = {
                'accessible_project_count': len(recovered_projects),
                'original_project_visible': any(row['id'] == project for row in recovered_projects),
                'ledger_http_status': recovered_status}
            summary(1000, 0, 2, 2)
            assert detail(unknown) == unknown_detail
            reserve('restart-does-not-refund', 1, expected=409)
            settled = outcome(unknown, 'resolve-uncertain', 'SETTLED', 'SIMULATED_BILL', 100)
            assert settled['status'] == 'SETTLED' and settled['actual_fen'] == 100
            settled_detail = detail(unknown)
            assert len(settled_detail['events']) == 3
            summary(600, 100, 2, 1)
            replay = outcome(unknown, 'unknown-callback', 'UNCERTAIN', 'SIMULATED_UNKNOWN')
            assert replay['status'] == 'SETTLED' and detail(unknown) == settled_detail
            outcome(unknown, 'resolve-uncertain', 'SETTLED', 'SIMULATED_BILL', 100)
            assert detail(unknown) == settled_detail
            outcome(unknown, 'resolve-uncertain', 'SETTLED', 'SIMULATED_BILL', 101, expected=409)
            outcome(unknown, 'new-terminal-callback', 'RELEASED', 'SIMULATED_ZERO_BILL', expected=409)
            assert detail(unknown) == settled_detail
            result['checks'].append('SIGKILL after acknowledged UNCERTAIN followed by default-JVM/file-H2 recovery preserves hold/events; explicit synthetic bill settles 100 of 400, returns unused hold, and replay never rolls terminal state back or duplicates events')

            # A successful synthetic zero bill is the only release tested here;
            # a duplicate or conflicting terminal outcome must never double refund.
            released = outcome(winner, 'zero-bill', 'RELEASED', 'SIMULATED_ZERO_BILL')
            assert released['status'] == 'RELEASED'
            released_detail = detail(winner)
            assert len(released_detail['events']) == 2
            outcome(winner, 'zero-bill', 'RELEASED', 'SIMULATED_ZERO_BILL')
            assert detail(winner) == released_detail
            outcome(winner, 'second-release', 'RELEASED', 'SIMULATED_ZERO_BILL', expected=409)
            outcome(winner, 'zero-bill', 'SETTLED', 'SIMULATED_BILL', 1, expected=409)
            assert detail(winner) == released_detail
            summary(0, 100, 2, 0)
            final = reserve('spend-final-900', 900)
            outcome(final, 'final-bill', 'SETTLED', 'SIMULATED_BILL', 900)
            final_summary = summary(0, 1000, 3, 0)
            reserve('limit-exhausted', 1, expected=409)
            assert api(path) == final_summary
            result['checks'].append('verified synthetic zero-bill release refunds exactly once; terminal conflicts do not refund; cumulative synthetic spend reaches fixed 1000-fen cap and further reservation is denied')

            other = api('/api/projects', {'title': '独立合成账本空间'})['id']
            other_path = f'/api/projects/{other}/synthetic-costs'
            summary(0, 0, 0, 0, endpoint=other_path)
            api(other_path + '/reservations/' + winner['id'], expected=404)
            api(other_path + '/reservations/' + winner['id'] + '/outcomes',
                {'callback_key': 'wrong-space', 'outcome': 'RELEASED', 'evidence': 'SIMULATED_ZERO_BILL'}, expected=404)
            separate = reserve(winner['request_key'], 600, endpoint=other_path)
            assert separate['id'] != winner['id']
            summary(600, 0, 1, endpoint=other_path)
            api(other_path, actor='viewer', expected=403)
            assert api(path) == final_summary
            assert api(f'/api/projects/{project}/jobs') == jobs_before
            result['checks'].append('project-specific reservations/keys/accounting stay isolated; foreign resource access is 404 and ledger operations create no project jobs')

            # This new configuration consists solely of public synthetic fixtures;
            # no user credential or existing environment secret is read.
            nondemo_port = new_port()
            nondemo_base = f'http://127.0.0.1:{nondemo_port}'
            nondemo_env = dict(env, PLATFORM_DEMO='false', PLATFORM_PORT=str(nondemo_port),
                               PLATFORM_DB_URL='jdbc:h2:file:' + str(folder / 'nondemo-db') + ';DATABASE_TO_LOWER=TRUE',
                               PLATFORM_USERS=json.dumps([{'id': 'synthetic_owner', 'token': USER_TOKEN}]))
            launch('api-nondemo', nondemo_env, nondemo_base)
            nondemo_project = api('/api/projects', {'title': '仅验证非演示关闭'},
                                  target=nondemo_base, token=USER_TOKEN)['id']
            disabled = f'/api/projects/{nondemo_project}/synthetic-costs'
            api(disabled, target=nondemo_base, token=USER_TOKEN, expected=404)
            api(disabled + '/reservations', {'request_key': 'disabled', 'reserved_fen': 1},
                target=nondemo_base, token=USER_TOKEN, expected=404)
            api(disabled + '/reservations/not-present', target=nondemo_base, token=USER_TOKEN, expected=404)
            api(disabled + '/reservations/not-present/outcomes',
                {'callback_key': 'disabled', 'outcome': 'RELEASED', 'evidence': 'SIMULATED_ZERO_BILL'},
                target=nondemo_base, token=USER_TOKEN, expected=404)
            result['checks'].append('separately configured synthetic non-demo account receives 404 for all ledger routes; no production provider or Worker is configured')
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
    print('PASS: synthetic-cost-ledger', len(result['checks']), 'groups; real provider calls 0; Workers 0')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=ROOT / 'server/target/synthetic-cost-ledger-e2e')
    args = parser.parse_args()
    run(args.output)
