#!/usr/bin/env python3
"""One fresh synthetic user journey, across both existing text modules.

No seeded business objects, real accounts or providers. Account fixtures live
only in a dedicated temporary H2 instance; this does not test self-registration.
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
ACCOUNTS = {role: {'id': 'walk_' + role + '_20261022',
                   'token': 'synthetic-walk-' + role + '-not-a-real-credential-20261022'}
            for role in ('owner', 'reader', 'outsider')}
WORKER_TOKEN = 'synthetic-walk-worker-not-a-real-credential-20261022'
SOURCE = '合成角色资料：表达简洁，先核对来源，不冒充真人。合成操作约定：重试间隔是 2 秒，必须沿用原幂等键。'
CORRECTED = SOURCE.replace('2 秒', '5 秒')
QUESTION = '重试间隔是多少？'


def run(output, completion_between_reads=False):
    output.mkdir(parents=True, exist_ok=True)
    safe = {key: os.environ[key] for key in ('HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'PATH', 'JAVA_HOME', 'JAVA_BIN') if key in os.environ}
    report = {'status': 'RUNNING', 'stage': 'startup', 'real_provider_calls': 0,
              'business_objects_seeded': 0, 'account_scope': 'three explicit synthetic fixtures in temporary database only',
              'checks': [], 'screenshots': {}, 'page_errors': [], 'external_requests': [],
              'controlled_detail_completion': completion_between_reads}
    processes, logs = [], []
    with tempfile.TemporaryDirectory(prefix='distilly-first-text-walk-') as tmp:
        folder = Path(tmp); jar = folder / 'runtime.jar'
        shutil.copy2(ROOT / 'server/target/distilly-platform-0.1.0.jar', jar)
        report['jar_sha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
        base = f'http://127.0.0.1:{port}'
        env = dict(safe, PLATFORM_DEMO='false', PLATFORM_USERS=json.dumps(list(ACCOUNTS.values())),
                   PLATFORM_WORKER_TOKEN=WORKER_TOKEN, MODEL_MODE='mock', WORKER_POLL_SECONDS='0.2',
                   PLATFORM_BIND='127.0.0.1', PLATFORM_PORT=str(port), PLATFORM_API_URL=base,
                   PLATFORM_DB_URL='jdbc:h2:file:' + str(folder / 'db') + ';DATABASE_TO_LOWER=TRUE',
                   PLATFORM_DB_USER='sa', PLATFORM_DB_PASSWORD='')
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def launch(name, command):
            log = (output / (name + '.log')).open('w'); logs.append(log)
            p = subprocess.Popen(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
            processes.append(p); return p

        def api(path, body=None, actor='owner', method=None, expected=200):
            req = urllib.request.Request(base + path, None if body is None else json.dumps(body).encode(),
                    {'Authorization': 'Bearer ' + ACCOUNTS[actor]['token'], 'Content-Type': 'application/json'},
                    method=method or ('GET' if body is None else 'POST'))
            try:
                with opener.open(req, timeout=8) as response: status, content = response.status, response.read()
            except urllib.error.HTTPError as error:
                with error: status, content = error.code, error.read()
            assert status == expected, 'synthetic HTTP status mismatch'
            return json.loads(content) if content else None

        def wait(fn):
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                value = fn()
                if value: return value
                time.sleep(.05)
            raise AssertionError('bounded walkthrough condition timed out')

        def checked(text):
            report['checks'].append(text)
            (output / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
            print('PASS: first-text checkpoint', len(report['checks']), flush=True)

        try:
            started = time.monotonic(); server = launch('api', [safe.get('JAVA_BIN', 'java'), '-jar', str(jar)])
            def healthy():
                assert server.poll() is None
                try: return api('/health') == {'status': 'UP'}
                except OSError: return False
            wait(healthy); report['default_jvm_startup_seconds'] = round(time.monotonic() - started, 3)
            for actor in ACCOUNTS: assert api('/api/projects', actor=actor) == []
            background_worker = launch('mock-worker', [sys.executable, str(ROOT / 'worker/worker.py')])
            from playwright.sync_api import sync_playwright
            with sync_playwright() as pw:
                browser = pw.chromium.launch(executable_path='/usr/bin/chromium', headless=True, args=['--no-sandbox', '--disable-dev-shm-usage'])
                try:
                    def new_page(actor, path='/'):
                        context = browser.new_context(viewport={'width': 1440, 'height': 1080})
                        assert context.cookies() == []
                        page = context.new_page()
                        page.route('**/*', lambda route: route.continue_() if route.request.url.startswith(base + '/') or route.request.url.startswith(('data:', 'blob:')) else (report['external_requests'].append('blocked external'), route.abort()))
                        page.on('pageerror', lambda error: report['page_errors'].append(type(error).__name__))
                        page.goto(base + path, wait_until='networkidle')
                        page.locator('#token').fill(ACCOUNTS[actor]['token']); page.locator('#login-form button').click()
                        page.locator('#workspace').wait_for(state='visible')
                        return page

                    def shot(page, name):
                        p = output / (name + '.png'); page.screenshot(path=str(p), full_page=True)
                        report['screenshots'][name] = {'file': str(p), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}

                    report['stage'] = 'fresh account empty state and person'
                    page = new_page('owner'); assert page.locator('#projects option').count() == 0
                    shot(page, '01-fresh-empty-workspace')
                    page.locator('#empty-create').click(); page.locator('#interaction-value').fill('合成文字完整走查')
                    page.locator('#interaction-form button.primary').click()
                    page.wait_for_function("()=>document.querySelector('#projects').selectedOptions[0]?.textContent==='合成文字完整走查'")
                    project = page.locator('#projects').input_value()
                    page.locator('#empty-create').click(); page.locator('#persona-form [name="name"]').fill('合成初行伙伴')
                    with page.expect_response(lambda r: r.url == base + f'/api/projects/{project}/personas' and r.request.method == 'POST') as saved:
                        page.locator('#save-persona').click()
                    person = saved.value.json()['id']; page.locator('#persona-name').filter(has_text='合成初行伙伴').wait_for()
                    checked('fresh configured synthetic account and browser start with no workspaces; workspace and original person created only through existing UI')

                    report['stage'] = 'personality and one TXT with correctable error'
                    report['step'] = 'create-personality'
                    page.locator('#personality-create-link').click()
                    page.get_by_role('button', name='沿用人物设定新增人格', exact=True).click()
                    create = page.get_by_role('dialog', name='新增人格', exact=True)
                    consent = create.locator('input[type="checkbox"]'); assert not consent.is_checked(); consent.check()
                    with page.expect_response(lambda r: r.url == base + f'/api/projects/{project}/personalities' and r.request.method == 'POST') as saved:
                        create.get_by_role('button', name='保存人格设定', exact=True).click()
                    personality = saved.value.json()['id']
                    report['step'] = 'open-generation-and-upload'
                    detail = page.get_by_role('dialog', name='人格详情', exact=True)
                    detail.get_by_role('button', name='生成新候选', exact=True).click()
                    generate = page.get_by_role('dialog', name='生成人格 · 选择材料', exact=True)
                    generate.get_by_role('button', name='上传 TXT 材料后返回', exact=True).click()
                    page.locator('#file').set_input_files({'name': 'invalid.txt', 'mimeType': 'text/plain', 'buffer': bytes([255,254,0,65])})
                    page.locator('#upload-only').click(); page.locator('#upload-error').filter(has_text='UTF-8').wait_for()
                    assert api(f'/api/projects/{project}/materials') == []
                    shot(page, '02-invalid-txt-correctable')
                    report['step'] = 'correct-valid-txt-upload-and-return'
                    page.locator('#file').set_input_files({'name': 'walkthrough.txt', 'mimeType': 'text/plain', 'buffer': SOURCE.encode()})
                    page.locator('#upload-only').click()
                    generate = page.get_by_role('dialog', name='生成人格 · 选择材料', exact=True)
                    generate.get_by_role('checkbox', name='选择材料 walkthrough.txt').wait_for()
                    assert generate.get_by_role('checkbox', name='选择材料 walkthrough.txt').is_checked()
                    material = api(f'/api/projects/{project}/materials')[0]['id']
                    report['step'] = 'reload-and-reopen-generation'
                    generate.get_by_role('button', name='关闭', exact=True).click(); page.reload(wait_until='networkidle')
                    row = page.locator('#personality-library tr').filter(has_text='合成初行伙伴人格')
                    row.get_by_role('button', name='生成', exact=True).click()
                    generate = page.get_by_role('dialog', name='生成人格 · 选择材料', exact=True)
                    generate.get_by_role('checkbox', name='选择材料 walkthrough.txt').check()
                    assert len(api(f'/api/projects/{project}/materials')) == len(api(f'/api/projects/{project}/personalities')) == 1
                    if completion_between_reads:
                        # Finish the real task at the jobs read; never invent API snapshots.
                        background_worker.terminate(); background_worker.wait(timeout=12)
                        trace = report['detail_interleaving'] = {'persona_version_counts': [], 'job_statuses_before': [], 'job_statuses_after': []}
                        gate = {'fired': False, 'persona_read': False}
                        detail_url = base + '/api/personas/' + personality
                        def persona_snapshot(route):
                            if gate['persona_read']: route.continue_(); return
                            gate['persona_read'] = True
                            snapshot = api('/api/personas/' + personality)
                            trace['persona_version_counts'].append(len(snapshot['versions']))
                            route.fulfill(status=200, content_type='application/json', body=json.dumps(snapshot))
                        def complete_at_jobs_read(route):
                            if gate['fired']: route.continue_(); return
                            gate['fired'] = True
                            rows = api('/api/personas/' + personality + '/jobs')
                            trace['job_statuses_before'] = [r['status'] for r in rows]
                            assert len(rows) == 1 and rows[0]['status'] == 'QUEUED'
                            code = 'from worker import Worker,Model; import persona; w=Worker(Model(mode="mock")); j=w.request("/internal/persona-jobs/claim",{}); assert j and j["kind"]=="PERSONA"; persona.execute(w,j)'
                            with (output / 'controlled-worker.log').open('w') as log:
                                completed = subprocess.run([sys.executable, '-c', code], cwd=ROOT / 'worker', env=env,
                                                           stdout=log, stderr=subprocess.STDOUT, timeout=30)
                            trace['worker_exit_code'] = completed.returncode
                            assert completed.returncode == 0
                            rows = api('/api/personas/' + personality + '/jobs')
                            trace['job_statuses_after'] = [r['status'] for r in rows]
                            assert len(rows) == 1 and rows[0]['status'] == 'SUCCEEDED'
                            route.fulfill(status=200, content_type='application/json', body=json.dumps(rows))
                        page.route(detail_url, persona_snapshot)
                        page.route(detail_url + '/jobs', complete_at_jobs_read)
                    report['step'] = 'submit-generation'
                    generate.get_by_role('button', name='提交生成', exact=True).click()
                    report['step'] = 'await-first-candidate'
                    detail = page.get_by_role('dialog', name='人格详情', exact=True)
                    detail.get_by_role('button', name='批准并发布', exact=True).wait_for(timeout=20000)
                    if completion_between_reads:
                        assert gate['fired'] and trace['persona_version_counts'] == [1]
                        page.unroute(detail_url, persona_snapshot); page.unroute(detail_url + '/jobs', complete_at_jobs_read)
                        background_worker = launch('mock-worker-resumed', [sys.executable, str(ROOT / 'worker/worker.py')])
                    report['step'] = 'approve-bind-and-first-chat'
                    detail.get_by_role('button', name='批准并发布', exact=True).click()
                    detail.get_by_role('button', name='绑定到虚拟人', exact=True).click()
                    binding = page.get_by_role('dialog', name='绑定人格版本', exact=True)
                    assert binding.get_by_label('选择虚拟人').input_value() == person
                    binding.get_by_role('button', name='确认绑定', exact=True).click()
                    binding.get_by_role('link', name='返回 合成初行伙伴 开始交流', exact=True).click()
                    page.locator('#new-chat').click(); page.locator('#message').fill('你好，先做一次合成交流。'); page.locator('#send').click()
                    page.locator('#messages .bubble.assistant').wait_for(timeout=20000)
                    assert '【模拟交流】' in page.locator('#messages .bubble.assistant').inner_text()
                    assert page.locator('#messages .context-notice').count() == 1
                    conversation = api(f'/api/personas/{person}/conversations')[0]['id']
                    shot(page, '03-bound-first-mock-chat')
                    checked('explicit consent, invalid UTF8422 recovery, one TXT import, reload/resume without duplicate material/personality; actual mock generation, publication, intended-person binding and first chat/context notice')

                    report['stage'] = 'reuse same TXT for reviewed work records'
                    page.locator('header a[href="/knowledge.html"]').click(); page.locator('#workspace').wait_for(state='visible')
                    page.locator('[data-page="materials"]').click()
                    page.locator('#materials tr').filter(has_text='walkthrough.txt').get_by_role('checkbox').check()
                    page.locator('#to-tasks').click(); page.locator('#task-profile').select_option('__new__')
                    page.locator('#task-new-topic-title').fill('合成重试约定')
                    with page.expect_response(lambda r: r.url.endswith('/distill') and r.request.method == 'POST') as submitted:
                        page.locator('#task-form button.primary').click()
                    job = submitted.value.json()['id']
                    row = page.locator('#task-list tr').filter(has_text='合成重试约定')
                    row.get_by_role('button', name='审核发布', exact=True).wait_for(timeout=20000)
                    row.get_by_role('button', name='审核发布', exact=True).click()
                    review = page.get_by_role('dialog', name='蒸馏任务详情', exact=True)
                    review.get_by_role('button', name='批准并发布', exact=True).click()
                    review.get_by_text('已审核', exact=True).first.wait_for()
                    review.get_by_role('button', name='关闭', exact=True).click()
                    profile = api('/api/jobs/' + job)['profile_id']
                    page.locator('a.module-link').click(); page.locator('#persona-name').filter(has_text='合成初行伙伴').wait_for()
                    page.locator('[data-view="studio"]').click()
                    page.locator('#knowledge-topics input[value="' + profile + '"]').check()
                    page.locator('#knowledge-save').click(); page.locator('#knowledge-summary').filter(has_text='已配置 1').wait_for()
                    page.locator('[data-view="chat"]').click(); page.locator('#record-question').fill(QUESTION); page.locator('#record-submit').click()
                    page.locator('#record-cards .record-text').filter(has_text=SOURCE).wait_for()
                    shot(page, '04-reviewed-record-first-question')
                    checked('same single TXT explicitly reused for work knowledge through task UI, OWNER review, current-person allowlist and deterministic first record question; no automatic promotion from personality source')

                    report['stage'] = 'actual correction form and recheck'
                    page.locator('#record-correction').click()
                    page.locator('[data-page="published-knowledge"]').click()
                    page.locator('#published-knowledge-list tr').filter(has_text=SOURCE).get_by_role('button', name='编辑知识', exact=True).click()
                    correction = page.get_by_role('dialog', name='编辑已审核知识', exact=True)
                    correction.get_by_label('知识内容', exact=True).fill(CORRECTED)
                    correction.get_by_label('修订说明（可选）', exact=True).fill('仅合成约定：间隔改为5秒')
                    correction.get_by_role('button', name='保存并发布修订', exact=True).click()
                    page.locator('#published-knowledge-list tr').filter(has_text=CORRECTED).wait_for()
                    page.locator('a.module-link').click(); page.locator('[data-view="chat"]').click()
                    page.locator('#record-question').fill(QUESTION); page.locator('#record-submit').click()
                    page.locator('#record-cards .record-text').filter(has_text=CORRECTED).wait_for()
                    assert '2 秒' not in page.locator('#record-cards').inner_text()
                    shot(page, '05-corrected-record-recheck')
                    checked('real OWNER correction form publishes new record version; next question reads corrected5 seconds with source trace and no old2 seconds; original TXT remains one unchanged source')

                    report['stage'] = 'fresh reader and outsider boundaries'
                    page.locator('[data-view="settings"]').click(); page.locator('#member-user').fill(ACCOUNTS['reader']['id'])
                    page.locator('#member-role').select_option('VIEWER'); page.locator('#member-form button').click()
                    page.locator('#members').filter(has_text=ACCOUNTS['reader']['id']).wait_for()
                    reader = new_page('reader', f'/?workspace={project}&persona={person}#chat')
                    reader.locator('[data-view="chat"]').click(); reader.locator('#record-question').fill(QUESTION); reader.locator('#record-submit').click()
                    reader.locator('#record-cards .record-text').filter(has_text=CORRECTED).wait_for()
                    api('/api/materials/' + material, actor='reader', expected=403)
                    api('/api/conversations/' + conversation, actor='reader', expected=403)
                    api('/api/personas/' + person, actor='outsider', expected=403)
                    api(f'/api/personas/{person}/knowledge', {'profile_ids': []}, actor='reader', method='PUT', expected=403)
                    shot(reader, '06-reader-current-records')
                    page.bring_to_front(); page.locator('[data-view="studio"]').click(); page.locator('#archive-persona').click()
                    page.locator('#interaction-form button.primary').click()
                    reader.bring_to_front()
                    reader.locator('#record-status').filter(has_text='当前资料不可访问').wait_for(timeout=12000)
                    assert reader.locator('#record-cards .record-card').count() == 0
                    shot(reader, '07-access-loss-clears-records')
                    reader.locator('#refresh').click(); reader.locator('#persona-content').wait_for(state='hidden')
                    assert reader.locator('[data-person="' + person + '"]').count() == 0
                    reader.locator('#logout').click(); assert reader.locator('#workspace').is_hidden()
                    assert reader.locator('#record-question').input_value() == ''
                    checked('new reader can read current shared records but cannot read raw TXT/private owner chat or change source selection; outsider denied; actual archive loss clears cards, explicit people refresh removes the stale person card, and logout clears the draft')
                    assert not report['page_errors'] and not report['external_requests']
                    report.update(status='PASS', stage='completed', material_count=1, personality_count=1)
                finally:
                    if report['status'] != 'PASS':
                        try: shot(page, 'failure-state')
                        except Exception: pass
                    browser.close()
        except BaseException as error:
            report.update(status='FAIL', failure_type=type(error).__name__)
            if 'personality' in locals():
                try:
                    p = api('/api/personas/' + personality)
                    jobs = api('/api/personas/' + personality + '/jobs')
                    report['failure_backend_snapshot'] = {'version_count': len(p['versions']),
                            'job_states': [{k:j.get(k) for k in ('kind','status','error_code','attempt')} for j in jobs]}
                except Exception as diagnostic: report['diagnostic_error_type'] = type(diagnostic).__name__
            report['worker_exit_codes'] = [p.poll() for p in processes[1:]]
            raise
        finally:
            for p in reversed(processes):
                if p.poll() is None:
                    p.terminate()
                    try: p.wait(timeout=12)
                    except subprocess.TimeoutExpired: p.kill(); p.wait(timeout=5)
            for log in logs: log.close()
            report['cleanup_all_managed_processes_stopped'] = all(p.poll() is not None for p in processes)
            (output / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('PASS: fresh first-text walkthrough', len(report['checks']), 'groups; real provider calls0')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--output', type=Path, default=ROOT / 'server/target/first-text-walkthrough-e2e')
    parser.add_argument('--completion-between-detail-reads', action='store_true', help='Complete actual mock generation at the detail jobs-read boundary')
    args = parser.parse_args()
    try:
        run(args.output.resolve(), args.completion_between_detail_reads)
    except Exception as error:
        # Playwright exceptions can include locator text/material content.
        # Keep detailed state in the synthetic screenshot/metadata, never logs.
        print('FAIL: fresh first-text walkthrough; ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
