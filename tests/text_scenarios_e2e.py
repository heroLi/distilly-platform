#!/usr/bin/env python3
"""Frozen synthetic multi-turn contracts: real API/context/Worker, mock only.

The outer process passes only toolchain variables to a child before importing
Worker code. No answer, material body, token or private runtime path is reported.
"""
import argparse
from collections import Counter
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
import uuid

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / 'tests/fixtures'
TOKEN = 'synthetic-text-scenarios-worker-token-32-characters'
FAMILIES = {'relationship', 'celebrity', 'colleague'}
CATEGORIES = {'comfort', 'celebration', 'conflict', 'followup', 'unknown',
              'correction', 'preference', 'deletion', 'cross_user', 'upgrade'}
OPERATIONS = {'chat', 'confirm_memory', 'publish_knowledge', 'correct_knowledge',
              'assert_original_knowledge', 'memory_enabled', 'hold_chat',
              'delete_memory', 'finish_held', 'withdraw_knowledge',
              'delete_conversation', 'new_conversation', 'assert_private_memory',
              'assert_raw_material_denied', 'assert_cross_workspace_binding_denied',
              'revise_persona', 'assert_original_persona'}


class ContractFailure(Exception):
    pass


def require(condition, code):
    if not condition:
        raise ContractFailure(code)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def load_contract(folder=FIXTURES):
    lock = json.loads((folder / 'text_scenarios_v1.lock.json').read_text())
    require(set(lock['sha256']) == {'text_scenarios_v1.json', 'text_scenarios_rubric_v1.json'}, 'freeze_files')
    for name, expected in lock['sha256'].items():
        require(digest((folder / name).read_bytes()) == expected, 'freeze_hash_mismatch')
    suite = json.loads((folder / 'text_scenarios_v1.json').read_text())
    rubric = json.loads((folder / 'text_scenarios_rubric_v1.json').read_text())
    cases = suite['cases']
    require(len(cases) == lock['case_count'] == 30, 'case_count')
    require(len({c['id'] for c in cases}) == 30, 'duplicate_case')
    require(Counter(c['family'] for c in cases) == {k: 10 for k in FAMILIES}, 'family_count')
    for family in FAMILIES:
        require({c['category'] for c in cases if c['family'] == family} == CATEGORIES, 'category_coverage')
    require(suite['quality_policy']['mode'] == rubric['real_quality_status'] == 'NOT_MEASURED', 'quality_status')
    require(suite['quality_policy']['allow_mock_scores'] is False and rubric['mock_quality_scores'] is None, 'mock_scoring_forbidden')
    for case in cases:
        require(case['synthetic_only'] is True and case['definition']['origin'] == 'original', 'synthetic_only')
        require(len({s['id'] for s in case['steps']}) == len(case['steps']), 'duplicate_step')
        require(sum(s['op'] == 'chat' for s in case['steps']) >= 2, 'multi_turn_required')
        require(all(s['op'] in OPERATIONS for s in case['steps']), 'unknown_operation')
    return suite, rubric, lock


class Runtime:
    def __init__(self, base, worker):
        self.base, self.worker = base, worker
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.chat_count = 0

    def api(self, path, body=None, actor='owner', method=None, expected=200, raw=None, kind='application/json'):
        data = raw if raw is not None else None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data,
                                     {'Authorization': 'Bearer demo-' + actor, 'Content-Type': kind},
                                     method=method or ('POST' if data is not None else 'GET'))
        try:
            with self.opener.open(req, timeout=10) as response:
                status, content = response.status, response.read()
        except urllib.error.HTTPError as error:
            with error:
                status, content = error.code, error.read()
        require(status == expected, 'http_' + str(status) + '_expected_' + str(expected))
        return json.loads(content) if content else None

    def execute(self, job, work=False):
        import persona
        claimed = self.worker.request('/internal/jobs/claim' if work else '/internal/persona-jobs/claim', {})
        require(claimed is not None and claimed['id'] == job['id'], 'claimed_job_identity')
        if work:
            self.worker.execute(claimed)
        else:
            persona.execute(self.worker, claimed)
        return claimed

    def result(self, job, actor='owner', work=False, status='SUCCEEDED'):
        row = self.api(('/api/jobs/' if work else '/api/persona-jobs/') + job['id'], actor=actor)
        require(row['status'] == status, 'job_' + row['status'] + '_expected_' + status)
        return row.get('result')

    def create_persona(self, project, definition):
        p = self.api(f'/api/projects/{project}/personas', definition)['id']
        job = self.api(f'/api/personas/{p}/generate', {'material_ids': []})
        self.execute(job)
        version = self.result(job)['version_id']
        self.api(f'/api/personas/{p}/versions/{version}/review', {'approve': True})
        return p, version

    def run_case(self, case):
        project = self.api('/api/projects', {'title': 'Synthetic ' + case['id']})['id']
        self.api(f'/api/projects/{project}/members', {'user_id': 'viewer', 'role': 'VIEWER'}, method='PUT')
        person, initial = self.create_persona(project, case['definition'])
        versions = {'initial': initial}
        persona_snapshots = {'initial': self.api(f'/api/personas/{person}')['versions'][0]['content']['persona_markdown']}
        sessions, session_actors, session_versions = {}, {}, {}
        memories, knowledge_versions, knowledge_texts = {}, {}, {}
        active = 'initial'
        sessions[active] = self.api(f'/api/personas/{person}/conversations', {}, actor='viewer')['id']
        session_actors[active], session_versions[active] = 'viewer', 'initial'
        held = None
        observations = []

        def session_path():
            return '/api/conversations/' + sessions[active]

        def check_context(payload, expected):
            version_name = expected.get('version', session_versions[active])
            require(payload['persona_version_id'] == versions[version_name], 'fixed_version')
            require(payload['persona']['persona_markdown'] == persona_snapshots[version_name], 'fixed_identity')
            require(sorted(m['body'] for m in payload['memories']) == sorted(expected['memories']), 'memory_set')
            require(all(m['origin'] == 'confirmed_interaction' and m['source_id'] for m in payload['memories']), 'memory_provenance')
            wanted = expected['knowledge']
            require(sorted(k['version_id'] for k in payload['knowledge']) == sorted(knowledge_versions[v] for v in wanted), 'knowledge_versions')
            require(sorted(k['text'] for k in payload['knowledge']) == sorted(knowledge_texts[v] for v in wanted), 'knowledge_text')
            for k in payload['knowledge']:
                require(k['profile_id'] == profile and k['id'].startswith(profile + '/' + k['version_id'] + '/'), 'knowledge_provenance')
            require(all(e['origin'] in {'fictional_setting', 'user_setting_unverified', 'authorized_source_excerpt'} for e in payload['experiences']), 'experience_origin')
            if 'experiences' in expected:
                require(len(payload['experiences']) == expected['experiences'], 'unknown_experiences')
            if 'history_users' in expected:
                require(sum(h['role'] == 'user' for h in payload['history']) == expected['history_users'], 'history_continuity')
            return {'memories': len(payload['memories']), 'knowledge': len(payload['knowledge']),
                    'experiences': len(payload['experiences']), 'history': len(payload['history']),
                    'fixed_version': version_name}

        for step in case['steps']:
            op = step['op']
            observation = {'step': step['id'], 'operation': op, 'status': 'PASS'}
            try:
                if op in ('chat', 'hold_chat'):
                    actor = session_actors[active]
                    before = self.api(session_path(), actor=actor)
                    job = self.api(session_path() + '/messages', {'message': step['text'], 'request_id': str(uuid.uuid4())}, actor=actor)
                    claimed = self.worker.request('/internal/persona-jobs/claim', {})
                    require(claimed is not None and claimed['id'] == job['id'], 'chat_claim_identity')
                    require(claimed['payload']['message'] == step['text'], 'current_input')
                    observation['context'] = check_context(claimed['payload'], step['expect'])
                    if op == 'hold_chat':
                        held = (job, claimed, actor, sessions[active], len(before['messages']) + 1)
                    else:
                        import persona
                        persona.execute(self.worker, claimed)
                        answer = self.result(job, actor=actor)
                        after = self.api(session_path(), actor=actor)
                        require(len(after['messages']) == len(before['messages']) + 2, 'message_pair')
                        require(after['messages'][-1]['id'] == answer['message_id'], 'persisted_reply')
                        require(answer['version_id'] == claimed['payload']['persona_version_id'], 'result_version')
                        require({c['id'] for c in answer['citations']} == {k['id'] for k in claimed['payload']['knowledge']}, 'mock_output_citation_scope')
                        require(answer['emotion']['previous'] == claimed['payload']['emotion']['label'], 'emotion_state_continuity')
                        require(answer['answer'].startswith('【模拟交流】'), 'mock_marker')
                        name = step['expect'].get('save_memory_as')
                        if name:
                            candidates = self.api(f'/api/personas/{person}/memories', actor=actor)
                            candidates = [m for m in candidates if m['body'] == step['text']]
                            require(len(candidates) == 1 and candidates[0]['status'] == 'PENDING', 'pending_candidate')
                            memories[name] = candidates[0]['id']
                        self.chat_count += 1
                elif op == 'confirm_memory':
                    body = {'status': 'CONFIRMED'}
                    if 'body' in step:
                        body['body'] = step['body']
                    self.api(f'/api/personas/{person}/memories/{memories[step["memory"]]}', body, actor='viewer', method='PUT')
                elif op == 'delete_memory':
                    self.api(f'/api/personas/{person}/memories/{memories[step["memory"]]}', actor='viewer', method='DELETE')
                elif op == 'memory_enabled':
                    self.api(session_path() + '/memory', {'enabled': step['enabled']}, actor=session_actors[active], method='PUT')
                elif op == 'finish_held':
                    require(held is not None, 'held_context_required')
                    import persona
                    job, claimed, actor, conversation, count = held
                    persona.execute(self.worker, claimed)
                    terminal = self.api('/api/persona-jobs/' + job['id'], actor=actor)
                    observation['terminal_status'] = terminal['status']
                    observation['assistant_messages_added'] = len(self.api('/api/conversations/' + conversation, actor=actor)['messages']) - count
                    require(observation['assistant_messages_added'] == 0, 'stale_output_not_persisted')
                    require(terminal['status'] == step['status'], 'held_terminal_state')
                    held = None
                elif op == 'new_conversation':
                    active = step['name']
                    actor = step.get('actor', 'viewer')
                    sessions[active] = self.api(f'/api/personas/{person}/conversations', {}, actor=actor)['id']
                    session_actors[active], session_versions[active] = actor, step.get('version', 'initial')
                elif op == 'delete_conversation':
                    old = session_path()
                    self.api(old, actor=session_actors[active], method='DELETE')
                    self.api(old, actor=session_actors[active], expected=404)
                    require(self.api(f'/api/personas/{person}/memories', actor=session_actors[active]) == [], 'derived_memories_deleted')
                elif op == 'publish_knowledge':
                    boundary = 'synthetic-scenario-boundary'
                    raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="request_id"\r\n\r\n{uuid.uuid4()}\r\n'
                           f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.txt"\r\nContent-Type: text/plain\r\n\r\n{step["text"]}\r\n--{boundary}--\r\n').encode()
                    material = self.api(f'/api/projects/{project}/materials', raw=raw, kind='multipart/form-data; boundary=' + boundary)['id']
                    profile = self.api(f'/api/projects/{project}/profiles', {'title': step['title']})['id']
                    knowledge_job = self.api(f'/api/profiles/{profile}/distill', {'material_ids': [material]})
                    self.execute(knowledge_job, work=True)
                    version = self.result(knowledge_job, work=True)['version_id']
                    self.api('/api/versions/' + version + '/review', {'approve': True})
                    knowledge_versions['initial'], knowledge_texts['initial'] = version, step['text']
                    self.api(f'/api/personas/{person}/knowledge', {'profile_ids': [profile]}, method='PUT')
                elif op == 'correct_knowledge':
                    page = self.api(f'/api/projects/{project}/published-knowledge/page')
                    entry = next(e for e in page['items'] if e['profile_id'] == profile)
                    revision = self.api(f'/api/profiles/{profile}/claims/{entry["claim_id"]}',
                                        {'version_id': knowledge_versions['initial'], 'section': entry['section'],
                                         'text': step['text'], 'note': 'Synthetic correction'}, method='PUT')
                    require(revision['changed'], 'correction_changed')
                    knowledge_versions['corrected'], knowledge_texts['corrected'] = revision['version_id'], step['text']
                elif op == 'assert_original_knowledge':
                    rows = self.api(f'/api/profiles/{profile}/versions')
                    original = next(v for v in rows if v['id'] == knowledge_versions['initial'])
                    require(step['text'] in original['work'], 'original_knowledge_immutable')
                    require(knowledge_versions['initial'] != knowledge_versions['corrected'], 'new_knowledge_version')
                elif op == 'withdraw_knowledge':
                    self.api(f'/api/profiles/{profile}/jobs/{knowledge_job["id"]}/experience', method='DELETE')
                elif op == 'assert_private_memory':
                    self.api(session_path(), actor='owner', expected=403)
                    require(self.api(f'/api/personas/{person}/memories') == [], 'private_memory_list')
                    self.api(f'/api/personas/{person}/memories/{memories["preference"]}', {'status': 'CONFIRMED'}, expected=403, method='PUT')
                elif op == 'assert_raw_material_denied':
                    self.api('/api/materials/' + material, actor='viewer', expected=403)
                elif op == 'assert_cross_workspace_binding_denied':
                    other_project = self.api('/api/projects', {'title': 'Synthetic isolated source boundary'})['id']
                    other, _ = self.create_persona(other_project, case['definition'])
                    self.api(f'/api/personas/{other}/knowledge', {'profile_ids': [profile]}, method='PUT', expected=403)
                    self.api(f'/api/personas/{other}', actor='viewer', expected=403)
                elif op == 'revise_persona':
                    version = self.api(f'/api/personas/{person}/versions/{initial}/revise',
                                       {'persona_markdown': step['markdown'], 'correction': 'Synthetic version comparison'})['version_id']
                    self.api(f'/api/personas/{person}/versions/{version}/review', {'approve': True})
                    versions['revised'], persona_snapshots['revised'] = version, step['markdown']
                elif op == 'assert_original_persona':
                    rows = self.api(f'/api/personas/{person}')['versions']
                    old = next(v for v in rows if v['id'] == initial)
                    require(old['content']['persona_markdown'] == persona_snapshots['initial'], 'original_persona_immutable')
                else:
                    raise ContractFailure('unhandled_operation')
            except Exception as error:
                observation.update(status='FAIL', failure=str(error) if isinstance(error, ContractFailure) else type(error).__name__)
                observations.append(observation)
                return {'id': case['id'], 'family': case['family'], 'category': case['category'], 'status': 'FAIL', 'steps': observations}
            observations.append(observation)
        require(held is None, 'no_unfinished_held_context')
        return {'id': case['id'], 'family': case['family'], 'category': case['category'], 'status': 'PASS', 'steps': observations}


def run(output):
    suite, rubric, lock = load_contract()
    output.mkdir(parents=True, exist_ok=True)
    report = {'suite_id': suite['suite_id'], 'status': 'RUNNING', 'freeze_sha256': lock['sha256'],
              'real_provider_calls': 0, 'real_quality_status': 'NOT_MEASURED',
              'quality_scores': {dimension: None for dimension in rubric['dimensions']},
              'narrow_semantic_targets': '0/4 UNRESOLVED; not rerun or redefined',
              'selection': suite['selection'], 'family_overlap': suite['family_overlap'], 'cases': [],
              'execution_mode': 'actual HTTP API, actual claim/context/Worker execution; synchronous mock, no background scheduler',
              'browser': 'NOT_RUN', 'startups_seconds': []}
    sys.path.insert(0, str(ROOT / 'worker'))
    from worker import Worker, Model
    processes = []
    try:
        for family in sorted(FAMILIES):
            with tempfile.TemporaryDirectory(prefix='distilly-text-scenarios-') as tmp:
                folder = Path(tmp)
                jar = folder / 'runtime.jar'
                shutil.copy2(ROOT / 'server/target/distilly-platform-0.1.0.jar', jar)
                report['jar_sha256'] = digest(jar.read_bytes())
                with socket.socket() as sock:
                    sock.bind(('127.0.0.1', 0))
                    port = sock.getsockname()[1]
                base = 'http://127.0.0.1:' + str(port)
                os.environ.update(PLATFORM_API_URL=base, PLATFORM_WORKER_TOKEN=TOKEN, MODEL_MODE='mock')
                env = dict(os.environ, PLATFORM_DEMO='true', PLATFORM_BIND='127.0.0.1', PLATFORM_PORT=str(port),
                           PLATFORM_DB_URL='jdbc:h2:file:' + str(folder / 'db') + ';DATABASE_TO_LOWER=TRUE',
                           PLATFORM_DB_USER='sa', PLATFORM_DB_PASSWORD='')
                model = Model(mode='mock')

                def forbid_gateway(*args, **kwargs):
                    raise ContractFailure('unexpected_gateway_call')

                model.call = forbid_gateway
                runtime = Runtime(base, Worker(model))
                with (output / (family + '-api.log')).open('w') as log:
                    process = subprocess.Popen([env.get('JAVA_BIN', 'java'), '-jar', str(jar)], cwd=ROOT,
                                               env=env, stdout=log, stderr=subprocess.STDOUT)
                    processes.append(process)
                    started = time.monotonic()
                    try:
                        while time.monotonic() - started < 30:
                            require(process.poll() is None, 'test_api_exited')
                            try:
                                if runtime.api('/health') == {'status': 'UP'}:
                                    break
                            except OSError:
                                pass
                            time.sleep(.1)
                        else:
                            raise ContractFailure('default_startup_timeout')
                        report['startups_seconds'].append(round(time.monotonic() - started, 3))
                        for case in (c for c in suite['cases'] if c['family'] == family):
                            try:
                                row = runtime.run_case(case)
                            except Exception as error:
                                row = {'id': case['id'], 'family': family, 'category': case['category'], 'status': 'FAIL',
                                       'failure': str(error) if isinstance(error, ContractFailure) else type(error).__name__, 'steps': []}
                            report['cases'].append(row)
                            (output / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
                            print(row['status'] + ': ' + case['id'], flush=True)
                        report.setdefault('completed_chat_turns', 0)
                        report['completed_chat_turns'] += runtime.chat_count
                    finally:
                        if process.poll() is None:
                            process.terminate()
                            try:
                                process.wait(timeout=12)
                            except subprocess.TimeoutExpired:
                                process.kill()
                                process.wait(timeout=5)
        report['passed'] = sum(c['status'] == 'PASS' for c in report['cases'])
        report['failed'] = sum(c['status'] == 'FAIL' for c in report['cases'])
        report['status'] = 'PASS' if report['passed'] == 30 and report['failed'] == 0 else 'FAIL'
    finally:
        report['cleanup_all_managed_processes_stopped'] = all(p.poll() is not None for p in processes)
        (output / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(f"{report['status']}: {report['passed']}/30 contracts; real quality NOT_MEASURED; provider calls 0")
    return 0 if report['status'] == 'PASS' else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'server/target/text-scenarios-e2e')
    parser.add_argument('--isolated-child', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.isolated_child:
        return run(args.output.resolve())
    # Do not read provider values, .env, stored sessions or real DB settings.
    safe = {key: os.environ[key] for key in ('HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'PATH', 'JAVA_HOME', 'JAVA_BIN') if key in os.environ}
    return subprocess.run([sys.executable, str(Path(__file__).resolve()), '--isolated-child',
                           '--output', str(args.output.resolve())], cwd=ROOT, env=safe).returncode


if __name__ == '__main__':
    sys.exit(main())
