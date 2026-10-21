#!/usr/bin/env python3
"""Synthetic literal filters -> UI submitted snapshot -> human correction -> no fallback.

Uses an isolated default-JVM API, file H2 and mock Worker. No real provider is
configured. Chromium checks exercise the actual record/correction UI.
"""
import argparse
from contextlib import ExitStack
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
TOKEN = 'record-filters-synthetic-worker-token-32-characters'
SOURCE = '合成重试记录：2032-04-08，REQ-007；间隔是 2 秒；另有 2032/04/09 与 PREQ-008、REQ-009-extra、X/REQ-010。'
CORRECTED = '合成重试记录：2032-05-11，REQ-017；间隔是 5 秒；原条件已失效。'
QUESTION = '重试间隔是多少？'
FILTERS = {'date_from': '2032-04-08', 'date_to': '2032-04-08', 'identifiers': ['REQ-007']}
NEW_FILTERS = {'date_from': '2032-05-11', 'date_to': '2032-05-11', 'identifiers': ['REQ-017']}


def run(output):
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    safe = {k: v for k, v in os.environ.items() if k in (
        'HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'PATH', 'JAVA_HOME', 'JAVA_BIN',
        'MAVEN_OPTS', 'MAVEN_ARGS')}
    result = {'status': 'RUNNING', 'real_provider_calls': 0, 'checks': [],
              'screenshots': {}, 'page_errors': [], 'external_browser_requests': [],
              'filter_contract': 'RECORD_LITERAL_FILTERS_V1'}
    processes, logs = [], []
    with tempfile.TemporaryDirectory(prefix='distilly-record-filters-') as tmp:
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
                   PLATFORM_DB_USER='sa', PLATFORM_DB_PASSWORD='',
                   PLATFORM_API_URL=base, MODEL_MODE='mock')

        def launch(name, command):
            log = (output / (name + '.log')).open('w')
            logs.append(log)
            process = subprocess.Popen(command, cwd=ROOT, env=env,
                                       stdout=log, stderr=subprocess.STDOUT)
            processes.append(process)
            return process

        def stop(process):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=12)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)

        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def api(path, body=None, actor='owner', method=None, expected=200,
                cache_check=False):
            request = urllib.request.Request(
                base + path,
                None if body is None else json.dumps(body, ensure_ascii=False).encode(),
                {'Authorization': 'Bearer demo-' + actor, 'Content-Type': 'application/json'},
                method=method or ('POST' if body is not None else 'GET'))
            try:
                with opener.open(request, timeout=8) as response:
                    status, data = response.status, response.read()
                    if cache_check:
                        assert response.headers.get('Cache-Control') == 'no-store'
            except urllib.error.HTTPError as error:
                with error:
                    status, data = error.code, error.read()
            assert status == expected, (path, status, expected)
            return json.loads(data) if data else None

        def wait(predicate, seconds=30):
            end = time.monotonic() + seconds
            while time.monotonic() < end:
                value = predicate()
                if value:
                    return value
                time.sleep(.1)
            raise AssertionError('bounded synthetic condition timed out')

        def healthy():
            assert server.poll() is None, 'isolated API exited'
            try:
                return api('/health') == {'status': 'UP'}
            except OSError:
                return False

        def completed(job, persona=False):
            prefix = '/api/persona-jobs/' if persona else '/api/jobs/'
            row = api(prefix + job['id'])
            assert row['status'] not in ('FAILED', 'CANCELLED'), ('synthetic task failed', row.get('error_code'))
            return row['result'] if row['status'] == 'SUCCEEDED' else None

        def create_persona(name):
            return api(f'/api/projects/{project}/personas', {
                'name': name, 'family': 'colleague', 'origin': 'original',
                'background': '合成角色，没有真实个人资料。', 'personality': '简洁，核对来源。',
                'expression': '短句', 'relationship': '合成工作伙伴',
                'boundaries': '不编造亲历，不冒充真人', 'consent': False})['id']

        def ask(actor='viewer', expected=200, body=None):
            return api(f'/api/personas/{persona}/record-questions',
                       {'question': QUESTION, 'filters': FILTERS} if body is None else body,
                       actor, expected=expected, cache_check=expected == 200)

        def snapshot():
            return {
                'work_jobs': api(f'/api/projects/{project}/jobs'),
                'persona_jobs': api(f'/api/personas/{persona}/jobs'),
                'viewer_conversations': api(f'/api/personas/{persona}/conversations', actor='viewer'),
                'owner_conversations': api(f'/api/personas/{persona}/conversations'),
                'viewer_memories': api(f'/api/personas/{persona}/memories', actor='viewer'),
                'owner_memories': api(f'/api/personas/{persona}/memories'),
                'synthetic_costs': api(f'/api/projects/{project}/synthetic-costs/reservations?page=1&size=20&status=ALL')}

        def check_cards(answer, expected_version, expected_text, filters=FILTERS):
            assert answer['contract'] == 'RECORD_CARDS_V1'
            assert answer['filter_contract'] == 'RECORD_LITERAL_FILTERS_V1'
            assert answer['applied_filters'] == filters
            assert answer['persona_id'] == persona
            assert answer['persona_version_id'] == persona_version
            assert answer['question'] == QUESTION and answer['model_calls'] == 0
            assert len(answer['record_cards']) == 1
            card = answer['record_cards'][0]
            assert card['profile_id'] == profile and card['version_id'] == expected_version
            assert card['text'] == expected_text and card['claim_id']
            assert card['id'] == f"{profile}/{expected_version}/{card['claim_id']}"
            assert card['origin'] == 'published_workspace_record'
            assert card['label'] == '已审核工作资料，不证明人物亲历'
            return card

        try:
            result['stage'] = 'default-JVM startup'
            started = time.monotonic()
            server = launch('api', [safe.get('JAVA_BIN', 'java'), '-jar', str(jar)])
            wait(healthy)
            result['default_jvm_startup_seconds'] = round(time.monotonic() - started, 3)
            result['stage'] = 'synthetic TXT and mock publication'
            project = api('/api/projects', {'title': '合成显式条件闭环验证'})['id']
            for actor, role in (('viewer', 'VIEWER'), ('editor', 'EDITOR')):
                api(f'/api/projects/{project}/members', {'user_id': actor, 'role': role}, method='PUT')
            boundary = 'record-cards-one-txt'
            data = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.txt"\r\n'
                    f'Content-Type: text/plain\r\n\r\n{SOURCE}\r\n--{boundary}--\r\n').encode()
            request = urllib.request.Request(base + f'/api/projects/{project}/materials', data,
                                             {'Authorization': 'Bearer demo-owner',
                                              'Content-Type': 'multipart/form-data; boundary=' + boundary})
            with opener.open(request, timeout=8) as response:
                material = json.load(response)['id']
            worker = launch('mock-worker', [sys.executable, str(ROOT / 'worker/worker.py')])
            profile = api(f'/api/projects/{project}/profiles', {'title': '合成重试操作记录'})['id']
            distill_job = api(f'/api/profiles/{profile}/distill', {'material_ids': [material]})
            version = wait(lambda: completed(distill_job))['version_id']
            api(f'/api/versions/{version}/review', {'approve': True, 'note': '合成记录人工审核'})
            persona = create_persona('合成记录助手')
            api(f'/api/personas/{persona}/record-questions', {'question': QUESTION}, expected=403)
            api_generate = api(f'/api/personas/{persona}/generate', {'material_ids': []})
            persona_version = wait(lambda: completed(api_generate, True))['version_id']
            api(f'/api/personas/{persona}/versions/{persona_version}/review', {'approve': True})
            api(f'/api/personas/{persona}/knowledge', {'profile_ids': [profile]}, method='PUT')
            stop(worker)
            assert worker.poll() is not None
            before = snapshot()
            result['stage'] = 'literal filter API contract'
            first = check_cards(ask(), version, SOURCE)
            for actor in ('owner', 'editor'):
                check_cards(ask(actor), version, SOURCE)
            result['checks'].append('one synthetic TXT import, mock extraction, explicit profile/persona publication and three-role first record question; no running Worker is required')
            check_cards(ask(body={'question': QUESTION}), version, SOURCE, {})
            for filters in ({'date_from': '2032-04-07', 'date_to': '2032-04-08'},
                            {'date_from': '2032-04-08', 'date_to': '2032-04-10'},
                            {'identifiers': ['REQ-007']},
                            {'identifiers': ['REQ-007', 'REQ-009-extra']}):
                check_cards(ask(body={'question': QUESTION, 'filters': filters}), version, SOURCE, filters)
            no_matches = [
                {'date_from': '2032-04-09', 'date_to': '2032-04-09'},
                {'identifiers': ['req-007']}, {'identifiers': ['REQ-00']},
                {'identifiers': ['REQ-008']}, {'identifiers': ['REQ-009']},
                {'identifiers': ['REQ-010']},
                {'identifiers': ['REQ-007', 'MISSING-7']},
                dict(FILTERS, identifiers=['MISSING-7'])]
            for filters in no_matches:
                answer = ask(body={'question': QUESTION, 'filters': filters})
                assert answer['record_cards'] == [] and '没有匹配' in answer['answer']
                assert answer['filter_contract'] == 'RECORD_LITERAL_FILTERS_V1'
                assert answer['applied_filters'] == filters and answer['model_calls'] == 0
            lexical_miss = ask(body={'question': 'zxqvunknown', 'filters': FILTERS})
            assert lexical_miss['record_cards'] == []
            result['checks'].append('inclusive ISO dates, date-only/identifier-only compatibility and all-ID AND; slash dates, wrong case, partial/prefixed/hyphen/slash-connected identifiers and lexical misses never fall back')
            invalid_filters = [None, {}, [], 'REQ-007', True,
                {'unknown': 'x'}, {'date_from': '2032-04-08'},
                {'date_to': '2032-04-08'},
                {'date_from': '2032-04-09', 'date_to': '2032-04-08'},
                {'date_from': '2031-02-29', 'date_to': '2031-03-01'},
                {'date_from': '2032-4-08', 'date_to': '2032-04-08'},
                {'date_from': '0000-01-01', 'date_to': '0001-01-01'},
                {'date_from': '2032/04/08', 'date_to': '2032/04/08'},
                {'identifiers': []}, {'identifiers': ['REQ-007', 'REQ-007']},
                {'identifiers': ['REQ-007', 7]}, {'identifiers': 'REQ-007'},
                {'identifiers': ['编号-7']}, {'identifiers': ['REQ/007']},
                {'identifiers': ['x' * 81]},
                {'identifiers': ['ID-' + str(i) for i in range(6)]}]
            for filters in invalid_filters:
                ask(body={'question': QUESTION, 'filters': filters}, expected=422)
            result['invalid_filter_requests'] = len(invalid_filters)
            result['literal_negative_requests'] = len(no_matches) + 1
            result['checks'].append('invalid filter shape, unknown keys, partial/reversed/invalid/ambiguous dates and invalid/duplicate/over-limit identifiers rejected with 422')
            endpoint = f"/api/profiles/{profile}/claims/{first['claim_id']}"
            correction = {'version_id': version, 'section': first['section'],
                          'text': CORRECTED, 'note': '合成资料负责人纠错；原材料与旧版本保留'}
            for actor in ('editor', 'viewer'):
                api(endpoint, correction, actor=actor, method='PUT', expected=403)

            result['stage'] = 'native Chromium filters and OWNER correction'
            from playwright.sync_api import sync_playwright
            with sync_playwright() as pw, ExitStack() as browser_cleanup:
                chromium = pw.chromium.launch(executable_path='/usr/bin/chromium', headless=True,
                                             args=['--no-sandbox', '--disable-dev-shm-usage'])
                browser_cleanup.callback(chromium.close)
                def new_page(actor, path):
                    context = chromium.new_context(viewport={'width': 1440, 'height': 1080})
                    page = context.new_page()
                    def restrict(route):
                        if route.request.url.startswith(base + '/') or route.request.url.startswith(('data:', 'blob:')):
                            route.continue_()
                        else:
                            result['external_browser_requests'].append('blocked external request')
                            route.abort()
                    page.route('**/*', restrict)
                    page.on('pageerror', lambda error: result['page_errors'].append(str(error)))
                    page.goto(base + path, wait_until='networkidle')
                    page.locator('#token').fill('demo-' + actor)
                    page.locator('#login-form button').click()
                    page.locator('#workspace').wait_for(state='visible')
                    page.locator('#projects').select_option(project)
                    return page
                def screenshot(page, name):
                    shot = output / (name + '.png')
                    page.screenshot(path=str(shot), full_page=True)
                    result['screenshots'][name] = {'file': str(shot), 'sha256': hashlib.sha256(shot.read_bytes()).hexdigest()}
                def fill_filters(page, criteria):
                    page.locator('#record-date-from').fill(criteria['date_from'])
                    page.locator('#record-date-to').fill(criteria['date_to'])
                    page.locator('#record-identifiers').fill('\n'.join(criteria['identifiers']))
                def query(page, button, expected_filters, count):
                    with page.expect_response(lambda response: response.url == base + f'/api/personas/{persona}/record-questions'
                                              and response.request.method == 'POST') as response_info:
                        page.locator(button).click()
                    response = response_info.value
                    assert response.status == 200
                    assert response.request.post_data_json == {'question': QUESTION, 'filters': expected_filters}
                    answer = response.json()
                    assert answer['applied_filters'] == expected_filters
                    assert answer['filter_contract'] == 'RECORD_LITERAL_FILTERS_V1'
                    assert len(answer['record_cards']) == count
                    page.wait_for_function("()=>document.querySelector('#record-submit').disabled === false")
                    assert page.locator('#record-cards .record-card').count() == count
                    return answer
                reader = new_page('viewer', '/')
                reader.locator(f'[data-person="{persona}"]').click()
                reader.locator('[data-view="chat"]').click()
                submitted = []
                reader.on('request', lambda request: submitted.append(request.post_data_json)
                          if request.url == base + f'/api/personas/{persona}/record-questions'
                          and request.method == 'POST' else None)
                reader.locator('#record-question').fill(QUESTION)
                fill_filters(reader, FILTERS)
                check_cards(query(reader, '#record-submit', FILTERS, 1), version, SOURCE)
                assert version in reader.locator('#record-cards').inner_text()
                assert 'REQ-007' in reader.locator('#record-applied-filters').inner_text()
                screenshot(reader, 'matching-literal-filters')
                # One of the two dates alone is an invalid range and must stay local.
                reader.locator('#record-date-to').fill('')
                requests_before = len(submitted)
                reader.locator('#record-submit').click()
                assert '日期' in reader.locator('#record-status').inner_text()
                assert len(submitted) == requests_before
                assert reader.locator('#record-cards .record-card').count() == 0
                assert reader.locator('#record-applied-filters').inner_text() == ''
                assert reader.locator('#record-answer').inner_text() == ''
                screenshot(reader, 'partial-date-rejected-locally')
                query(reader, '#record-recheck', FILTERS, 1)
                assert reader.locator('#record-date-to').input_value() == ''
                absent = dict(FILTERS, date_from='2032-04-09', date_to='2032-04-09')
                fill_filters(reader, absent)
                query(reader, '#record-submit', absent, 0)
                screenshot(reader, 'unmatched-date-no-fallback')
                wrong_case = dict(FILTERS, identifiers=['req-007'])
                fill_filters(reader, wrong_case)
                query(reader, '#record-submit', wrong_case, 0)
                screenshot(reader, 'identifier-case-no-fallback')
                fill_filters(reader, FILTERS)
                query(reader, '#record-submit', FILTERS, 1)
                # Recheck must read the successful submitted snapshot, retaining current drafts.
                draft_question = '尚未提交的新问题草稿'
                reader.locator('#record-question').fill(draft_question)
                fill_filters(reader, NEW_FILTERS)
                query(reader, '#record-recheck', FILTERS, 1)
                assert reader.locator('#record-question').input_value() == draft_question
                assert reader.locator('#record-date-from').input_value() == NEW_FILTERS['date_from']
                assert reader.locator('#record-date-to').input_value() == NEW_FILTERS['date_to']
                assert reader.locator('#record-identifiers').input_value() == 'REQ-017'
                assert 'REQ-007' in reader.locator('#record-applied-filters').inner_text()
                screenshot(reader, 'recheck-retains-unsent-criteria')
                owner = new_page('owner', '/knowledge.html')
                owner.locator('[data-page="published-knowledge"]').click()
                owner.locator('#published-knowledge-list tr').filter(has_text=SOURCE).get_by_role('button', name='编辑知识', exact=True).click()
                dialog = owner.get_by_role('dialog', name='编辑已审核知识', exact=True)
                dialog.get_by_label('知识内容', exact=True).fill(CORRECTED)
                dialog.get_by_label('修订说明（可选）', exact=True).fill(correction['note'])
                dialog.get_by_role('button', name='保存并发布修订', exact=True).click()
                owner.locator('#published-knowledge-list tr').filter(has_text=CORRECTED).wait_for()
                screenshot(owner, 'owner-corrects-date-and-identifier')
                reader.bring_to_front()
                query(reader, '#record-recheck', FILTERS, 0)
                assert reader.locator('#record-date-from').input_value() == NEW_FILTERS['date_from']
                assert reader.locator('#record-identifiers').input_value() == 'REQ-017'
                screenshot(reader, 'old-criteria-after-correction-no-fallback')
                reader.locator('#record-question').fill(QUESTION)
                new_answer = query(reader, '#record-submit', NEW_FILTERS, 1)
                next_version = new_answer['record_cards'][0]['version_id']
                check_cards(new_answer, next_version, CORRECTED, NEW_FILTERS)
                assert version not in reader.locator('#record-cards').inner_text()
                assert 'REQ-007' not in reader.locator('#record-cards').inner_text()
                screenshot(reader, 'new-criteria-current-record')
                reader.locator('#logout').click()
                assert reader.locator('#record-cards .record-card').count() == 0
                for field in ('question', 'date-from', 'date-to', 'identifiers'):
                    assert reader.locator('#record-' + field).input_value() == ''
                assert reader.locator('#record-applied-filters').inner_text() == ''
                assert reader.locator('#record-answer').inner_text() == ''
                assert not result['page_errors'] and not result['external_browser_requests']
            result['checks'].append('native Chromium date/identifier match, partial range rejected without request, date and case mismatch no fallback, submitted criteria recheck preserves unsent drafts, OWNER correction invalidates old criteria, new criteria returns current version, logout clears filters; no page exceptions/external requests')
            assert next_version != version
            assert ask()['record_cards'] == []
            check_cards(ask(body={'question': QUESTION, 'filters': NEW_FILTERS}), next_version, CORRECTED, NEW_FILTERS)
            api(endpoint, correction, method='PUT', expected=409)
            assert any(row['id'] == version for row in api(f'/api/profiles/{profile}/versions'))
            assert snapshot() == before, 'record reading or correction created a task/conversation/memory'
            result['checks'].append('OWNER correction creates a new approved version; repeated question returns only corrected text/version, original version remains, stale correction is 409; tasks/conversations/memories/synthetic ledger unchanged')
            api('/api/materials/' + material, actor='viewer', expected=403)
            other_project = api('/api/projects', {'title': '无阅读者权限的合成空间'})['id']
            other_persona = api(f'/api/projects/{other_project}/personas', {
                'name': '独立合成人物', 'family': 'colleague', 'origin': 'original',
                'background': '仅用于权限验证', 'personality': '简洁',
                'expression': '短句', 'boundaries': '不冒充真人', 'consent': False})['id']
            api(f'/api/personas/{other_persona}/record-questions', {'question': QUESTION, 'filters': NEW_FILTERS}, actor='viewer', expected=403)
            for body in ({'question': ''}, {'question': 'x' * 4001},
                         {'question': QUESTION, 'answer': '客户端伪造答案'},
                         {'question': QUESTION, 'version_id': version}):
                ask(expected=422, body=body)
            unknown = ask(body={'question': 'zxqvunknown'})
            assert unknown['record_cards'] == [] and '没有匹配' in unknown['answer']
            result['checks'].append('raw material and cross-workspace reads forbidden; empty/long/forged questions rejected; unknown input yields fixed insufficient-record response')
            result['stage'] = 'same-file H2 default-JVM restart'
            stop(server)
            server = launch('api-restart', [safe.get('JAVA_BIN', 'java'), '-jar', str(jar)])
            wait(healthy)
            assert ask()['record_cards'] == []
            check_cards(ask(body={'question': QUESTION, 'filters': NEW_FILTERS}), next_version, CORRECTED, NEW_FILTERS)
            assert snapshot() == before
            result['checks'].append('default-JVM restart of same isolated file H2 retains corrected version and source selection without query history/tasks/memory or synthetic cost changes')
            api(f'/api/personas/{persona}/knowledge', {'profile_ids': []}, method='PUT')
            assert ask(body={'question': QUESTION, 'filters': NEW_FILTERS})['record_cards'] == []
            api(f'/api/personas/{persona}/knowledge', {'profile_ids': [profile]}, method='PUT')
            api(f'/api/profiles/{profile}', method='DELETE')
            assert ask(body={'question': QUESTION, 'filters': NEW_FILTERS})['record_cards'] == []
            api(f'/api/profiles/{profile}/restore', method='POST')
            check_cards(ask(body={'question': QUESTION, 'filters': NEW_FILTERS}), next_version, CORRECTED, NEW_FILTERS)
            api(f"/api/profiles/{profile}/jobs/{distill_job['id']}/experience", method='DELETE')
            assert ask(body={'question': QUESTION, 'filters': NEW_FILTERS})['record_cards'] == []
            result['checks'].append('deselection, archive and withdrawal remove cards on next read; archive restoration returns current corrected record')
            result['stage'] = 'completed'
            result['status'] = 'PASS'
        except BaseException as error:
            result['status'] = 'FAIL'
            result['failure_type'] = type(error).__name__
            raise
        finally:
            for process in reversed(processes):
                stop(process)
            for log in logs:
                log.close()
            result['cleanup_all_processes_stopped'] = all(process.poll() is not None for process in processes)
            (output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print('PASS: record-filters', len(result['checks']), 'groups; real provider calls 0')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=ROOT / 'server/target/record-filters-e2e')
    args = parser.parse_args()
    run(args.output)
