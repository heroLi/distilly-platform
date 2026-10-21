#!/usr/bin/env python3
"""Synthetic TXT -> first record question -> human correction -> current record.

Uses an isolated default-JVM API, file H2 and mock Worker. No real provider is
configured. Optional Chromium checks exercise the actual record/correction UI.
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
TOKEN = 'record-cards-synthetic-worker-token-32-characters'
SOURCE = '合成操作记录：重试间隔是 2 秒，重试必须沿用同一幂等键。'
CORRECTED = '合成操作记录：重试间隔是 5 秒，重试必须沿用同一幂等键。'
QUESTION = '重试间隔是多少？'


def run(output, browser=False):
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    safe = {k: v for k, v in os.environ.items() if k in (
        'HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'PATH', 'JAVA_HOME', 'JAVA_BIN',
        'MAVEN_OPTS', 'MAVEN_ARGS')}
    result = {'status': 'RUNNING', 'real_provider_calls': 0, 'checks': [],
              'screenshots': {}, 'page_errors': [], 'external_browser_requests': []}
    processes, logs = [], []
    with tempfile.TemporaryDirectory(prefix='distilly-record-cards-') as tmp:
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

        def api(path, body=None, actor='owner', method=None, expected=200,
                cache_check=False):
            request = urllib.request.Request(
                base + path,
                None if body is None else json.dumps(body, ensure_ascii=False).encode(),
                {'Authorization': 'Bearer demo-' + actor, 'Content-Type': 'application/json'},
                method=method or ('POST' if body is not None else 'GET'))
            try:
                with urllib.request.urlopen(request, timeout=8) as response:
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
                       {'question': QUESTION} if body is None else body,
                       actor, expected=expected, cache_check=expected == 200)

        def snapshot():
            return {
                'work_jobs': api(f'/api/projects/{project}/jobs'),
                'persona_jobs': api(f'/api/personas/{persona}/jobs'),
                'viewer_conversations': api(f'/api/personas/{persona}/conversations', actor='viewer'),
                'owner_conversations': api(f'/api/personas/{persona}/conversations'),
                'viewer_memories': api(f'/api/personas/{persona}/memories', actor='viewer'),
                'owner_memories': api(f'/api/personas/{persona}/memories')}

        def check_cards(answer, expected_version, expected_text):
            assert answer['contract'] == 'RECORD_CARDS_V1'
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
            started = time.monotonic()
            server = launch('api', [safe.get('JAVA_BIN', 'java'), '-jar', str(jar)])
            wait(healthy)
            result['default_jvm_startup_seconds'] = round(time.monotonic() - started, 3)
            project = api('/api/projects', {'title': '合成记录卡闭环验证'})['id']
            for actor, role in (('viewer', 'VIEWER'), ('editor', 'EDITOR')):
                api(f'/api/projects/{project}/members', {'user_id': actor, 'role': role}, method='PUT')
            boundary = 'record-cards-one-txt'
            data = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.txt"\r\n'
                    f'Content-Type: text/plain\r\n\r\n{SOURCE}\r\n--{boundary}--\r\n').encode()
            request = urllib.request.Request(base + f'/api/projects/{project}/materials', data,
                                             {'Authorization': 'Bearer demo-owner',
                                              'Content-Type': 'multipart/form-data; boundary=' + boundary})
            with urllib.request.urlopen(request, timeout=8) as response:
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
            first = check_cards(ask(), version, SOURCE)
            for actor in ('owner', 'editor'):
                check_cards(ask(actor), version, SOURCE)
            result['checks'].append('one synthetic TXT import, mock extraction, explicit profile/persona publication and three-role first record question; no running Worker is required')
            endpoint = f"/api/profiles/{profile}/claims/{first['claim_id']}"
            correction = {'version_id': version, 'section': first['section'],
                          'text': CORRECTED, 'note': '合成资料负责人纠错；原材料与旧版本保留'}
            for actor in ('editor', 'viewer'):
                api(endpoint, correction, actor=actor, method='PUT', expected=403)

            if browser:
                from playwright.sync_api import sync_playwright
                with sync_playwright() as pw:
                    chromium = pw.chromium.launch(executable_path='/usr/bin/chromium', headless=True,
                                                 args=['--no-sandbox', '--disable-dev-shm-usage'])
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
                    reader = new_page('viewer', '/')
                    reader.locator(f'[data-person="{persona}"]').click()
                    reader.locator('[data-view="chat"]').click()
                    reader.locator('#record-question').fill(QUESTION)
                    reader.locator('#record-submit').click()
                    reader.locator('#record-cards .record-text').filter(has_text=SOURCE).wait_for()
                    assert reader.locator('#record-cards .record-card').count() == 1
                    assert version in reader.locator('#record-cards').inner_text()
                    screenshot(reader, 'first-record-question')
                    owner = new_page('owner', '/knowledge.html')
                    owner.locator('[data-page="published-knowledge"]').click()
                    owner.locator('#published-knowledge-list tr').filter(has_text=SOURCE).get_by_role('button', name='编辑知识', exact=True).click()
                    dialog = owner.get_by_role('dialog', name='编辑已审核知识', exact=True)
                    # The actual modal uses accessible form labels, without test-only code paths.
                    dialog.get_by_label('知识内容', exact=True).fill(CORRECTED)
                    dialog.get_by_label('修订说明（可选）', exact=True).fill(correction['note'])
                    dialog.get_by_role('button', name='保存并发布修订', exact=True).click()
                    owner.locator('#published-knowledge-list tr').filter(has_text=CORRECTED).wait_for()
                    screenshot(owner, 'human-record-correction')
                    reader.bring_to_front()
                    reader.locator('#record-question').fill('尚未提交的新问题草稿')
                    reader.locator('#record-recheck').click()
                    reader.locator('#record-cards .record-text').filter(has_text=CORRECTED).wait_for()
                    assert reader.locator('#record-question').input_value() == '尚未提交的新问题草稿'
                    assert '2 秒' not in reader.locator('#record-cards').inner_text()
                    screenshot(reader, 'corrected-record-question')
                    api(f'/api/personas/{persona}/knowledge', {'profile_ids': []}, method='PUT')
                    # No click: the visible page must recheck and remove revoked cards itself.
                    reader.get_by_text('当前没有可用的已发布资料记录，请到虚拟经历台核对或纠错。', exact=True).wait_for(timeout=12000)
                    assert reader.locator('#record-cards .record-card').count() == 0
                    screenshot(reader, 'revoked-record-cards')
                    api(f'/api/personas/{persona}/knowledge', {'profile_ids': [profile]}, method='PUT')
                    reader.locator('#record-recheck').click()
                    reader.locator('#record-cards .record-text').filter(has_text=CORRECTED).wait_for()
                    reader.locator('#logout').click()
                    assert reader.locator('#record-cards .record-card').count() == 0
                    assert reader.locator('#record-question').input_value() == ''
                    assert reader.locator('#record-answer').inner_text() == ''
                    assert not result['page_errors'] and not result['external_browser_requests']
                    chromium.close()
                api(f'/api/personas/{persona}/knowledge', {'profile_ids': [profile]}, method='PUT')
                page = api(f'/api/projects/{project}/published-knowledge/page')
                next_version = next(item['version_id'] for item in page['items'] if item['claim_id'] == first['claim_id'])
                result['checks'].append('native Chromium first question, OWNER correction form, recheck with unsent draft retained; automatic source-revocation recheck and logout clear cards; screenshots, no page exceptions/external requests')
            else:
                changed = api(endpoint, correction, method='PUT')
                assert changed['changed'] is True
                next_version = changed['version_id']

            assert next_version != version
            check_cards(ask(), next_version, CORRECTED)
            api(endpoint, correction, method='PUT', expected=409)
            assert any(row['id'] == version for row in api(f'/api/profiles/{profile}/versions'))
            assert snapshot() == before, 'record reading or correction created a task/conversation/memory'
            result['checks'].append('OWNER correction creates a new approved version; repeated question returns only corrected text/version, original version remains, stale correction is 409; tasks/conversations/memories unchanged')
            api('/api/materials/' + material, actor='viewer', expected=403)
            other_project = api('/api/projects', {'title': '无阅读者权限的合成空间'})['id']
            other_persona = api(f'/api/projects/{other_project}/personas', {
                'name': '独立合成人物', 'family': 'colleague', 'origin': 'original',
                'background': '仅用于权限验证', 'personality': '简洁',
                'expression': '短句', 'boundaries': '不冒充真人', 'consent': False})['id']
            api(f'/api/personas/{other_persona}/record-questions', {'question': QUESTION}, actor='viewer', expected=403)
            for body in ({'question': ''}, {'question': 'x' * 4001},
                         {'question': QUESTION, 'answer': '客户端伪造答案'},
                         {'question': QUESTION, 'version_id': version}):
                ask(expected=422, body=body)
            unknown = ask(body={'question': 'zxqvunknown'})
            assert unknown['record_cards'] == [] and '没有匹配' in unknown['answer']
            result['checks'].append('raw material and cross-workspace reads forbidden; empty/long/forged questions rejected; unknown input yields fixed insufficient-record response')
            stop(server)
            server = launch('api-restart', [safe.get('JAVA_BIN', 'java'), '-jar', str(jar)])
            wait(healthy)
            check_cards(ask(), next_version, CORRECTED)
            assert snapshot() == before
            result['checks'].append('default-JVM restart of same isolated file H2 retains corrected version and source selection without query history/tasks/memory')
            api(f'/api/personas/{persona}/knowledge', {'profile_ids': []}, method='PUT')
            assert ask()['record_cards'] == []
            api(f'/api/personas/{persona}/knowledge', {'profile_ids': [profile]}, method='PUT')
            api(f'/api/profiles/{profile}', method='DELETE')
            assert ask()['record_cards'] == []
            api(f'/api/profiles/{profile}/restore', method='POST')
            check_cards(ask(), next_version, CORRECTED)
            api(f"/api/profiles/{profile}/jobs/{distill_job['id']}/experience", method='DELETE')
            assert ask()['record_cards'] == []
            result['checks'].append('deselection, archive and withdrawal remove cards on next read; archive restoration returns current corrected record')
            result['status'] = 'PASS'
        finally:
            for process in reversed(processes):
                stop(process)
            for log in logs:
                log.close()
            result['cleanup_all_processes_stopped'] = all(process.poll() is not None for process in processes)
            (output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print('PASS: record-cards', len(result['checks']), 'groups; real provider calls 0')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=ROOT / 'server/target/record-cards-e2e')
    parser.add_argument('--browser', action='store_true')
    args = parser.parse_args()
    run(args.output, args.browser)
