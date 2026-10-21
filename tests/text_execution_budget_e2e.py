#!/usr/bin/env python3
"""Real API/Worker against literal-loopback synthetic DeepSeek usage receipts.

Every amount, receipt, account and source in this regression is synthetic. The
fixture does not verify a provider bill or authorize any real model request.
"""
import argparse
from collections import Counter, deque
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
WORKER_TOKEN = 'execution-budget-fixture-worker-token-32-characters'
FIXTURE_KEY = 'synthetic-fixture-key-not-a-real-secret'
SOURCE = '合成接口记录：重试必须核对幂等键，不能重复扣款。'


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class Gateway(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        state = self.server.fixture
        if self.path != '/v1/chat/completions' or self.headers.get('Authorization') != 'Bearer ' + FIXTURE_KEY:
            state['unexpected_requests'].append('unexpected route or synthetic authorization')
            self.send_error(403)
            return
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        payload = json.loads(request['messages'][1]['content'])
        if 'history' in payload:
            stage = 'persona_chat'
            output = {'answer': '合成回答：先核对幂等键。', 'citations': [],
                      'experience_citations': [], 'memory_citations': [],
                      'emotion': {'label': 'calm', 'intensity': .2, 'reason': '合成流程'},
                      'memory_candidates': []}
        elif 'chunk_id' in payload:
            stage = 'distill'
            quote = next(line.strip() for line in payload['text'].splitlines() if line.strip())[:100]
            output = {'claims': [{'section': '合成记录', 'text': quote, 'evidence': [{
                'material_id': payload['material_id'], 'chunk_id': payload['chunk_id'], 'quote': quote}]}]}
        elif 'sources' in payload:
            stage = 'persona_analysis'
            output = {'analysis': {'expression': '简洁', 'inferences': [], 'insufficient': []},
                      'evidence': [{'material_id': row['material_id'], 'quote': row['text'][:60]}
                                   for row in payload['sources']]}
        elif 'claims' in payload:
            stage = 'knowledge_answer'
            first = payload['claims'][0]
            output = {'answer': first['text'], 'citations': [first['id']]}
        else:
            stage = 'persona_build'
            output = {'persona_markdown': '# 合成伙伴\n\n原创人物，表达简洁，核对资料，不编造亲历。'}
        with state['lock']:
            state['calls'][stage] += 1
            sequence = sum(state['calls'].values())
            mode = state['plan'].popleft() if state['plan'] else 'success'
            state['observations'].append({'sequence': sequence, 'stage': stage, 'mode': mode,
                                          'model': request.get('model'), 'stream': request.get('stream', False)})
        assert request['model'] == 'deepseek-flash'
        if mode == 'http503':
            self.send_error(503, 'synthetic uncertain upstream failure')
            return
        frame = {'id': 'synthetic-completion-' + str(sequence), 'object': 'chat.completion',
                 'model': 'deepseek-flash', 'created': 1792580000,
                 'system_fingerprint': 'synthetic-system-v1',
                 'choices': [{'finish_reason': 'length' if mode == 'length' else 'stop',
                              'message': {'content': json.dumps(output, ensure_ascii=False)}}],
                 'usage': {'prompt_tokens': 100, 'completion_tokens': 20, 'total_tokens': 120,
                           'prompt_cache_hit_tokens': 20, 'prompt_cache_miss_tokens': 80,
                           'prompt_tokens_details': {'cached_tokens': 20}}}
        if mode == 'missing_usage':
            frame.pop('usage')
        elif mode == 'malformed_usage':
            frame['usage']['total_tokens'] = 121
        if mode in ('stream_success', 'stream_missing_done'):
            assert request.get('stream') is True and stage == 'persona_chat'
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.end_headers()
            identity = {key: frame[key] for key in ('id', 'model', 'created', 'system_fingerprint')}
            identity['object'] = 'chat.completion.chunk'
            content = json.dumps(output, ensure_ascii=False)
            frames = [dict(identity, choices=[{'delta': {'content': content}, 'finish_reason': None}]),
                      dict(identity, choices=[{'delta': {}, 'finish_reason': 'stop'}], usage=frame['usage'])]
            for item in frames:
                self.wfile.write(('data: ' + json.dumps(item, ensure_ascii=False) + '\n\n').encode())
                self.wfile.flush()
            if mode == 'stream_success':
                self.wfile.write(b'data: [DONE]\n\n')
                self.wfile.flush()
            self.close_connection = True
            return
        raw = json.dumps(frame, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


def run(output):
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    safe = {key: value for key, value in os.environ.items() if key in (
        'HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'PATH', 'JAVA_HOME', 'JAVA_BIN',
        'MAVEN_OPTS', 'MAVEN_ARGS')}
    result = {'status': 'RUNNING', 'started_at_utc': datetime.now(timezone.utc).isoformat(),
              'real_provider_calls': 0, 'checks': [], 'default_jvm_startups_seconds': [],
              'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    processes, logs = [], []
    fixture = {'calls': Counter(), 'plan': deque(), 'observations': [],
               'unexpected_requests': [], 'lock': threading.Lock()}
    gateway = ThreadingHTTPServer(('127.0.0.1', 0), Gateway)
    gateway.daemon_threads = True
    gateway.fixture = fixture
    gateway_thread = threading.Thread(target=gateway.serve_forever, daemon=True)
    gateway_thread.start()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def stop(process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    with tempfile.TemporaryDirectory(prefix='distilly-execution-budget-') as tmp:
        folder = Path(tmp)
        jar = folder / 'runtime.jar'
        shutil.copy2(ROOT / 'server/target/distilly-platform-0.1.0.jar', jar)
        result['jar_sha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
        base = f'http://127.0.0.1:{free_port()}'
        env = dict(safe, PLATFORM_DEMO='true', PLATFORM_WORKER_TOKEN=WORKER_TOKEN,
                   PLATFORM_PORT=base.rsplit(':', 1)[1], PLATFORM_BIND='127.0.0.1',
                   PLATFORM_DB_URL='jdbc:h2:file:' + str(folder / 'db') + ';DATABASE_TO_LOWER=TRUE',
                   PLATFORM_DB_USER='sa', PLATFORM_DB_PASSWORD='', PLATFORM_API_URL=base,
                   MODEL_MODE='gateway', MODEL_BASE_URL=f'http://127.0.0.1:{gateway.server_port}/v1',
                   MODEL_NAME='deepseek-flash', MODEL_API_KEY=FIXTURE_KEY,
                   MODEL_ALLOW_HTTP='true', MODEL_SYNTHETIC_TEXT_FIXTURE='true',
                   MODEL_STREAMING='false', MODEL_TIMEOUT_SECONDS='4',
                   MODEL_SYNTHETIC_GLOBAL_BUDGET='true', WORKER_POLL_SECONDS='0.2')

        def api(path, body=None, actor='owner', method=None, expected=200, worker=False, token=None):
            request = urllib.request.Request(
                base + path, None if body is None else json.dumps(body, ensure_ascii=False).encode(),
                {'Authorization': 'Bearer ' + (token or (WORKER_TOKEN if worker else 'demo-' + actor)),
                 'Content-Type': 'application/json'},
                method=method or ('POST' if body is not None else 'GET'))
            try:
                with opener.open(request, timeout=8) as response:
                    status, data = response.status, response.read()
                    if 'text-budget' in path and status == 200:
                        assert response.headers.get('Cache-Control') == 'no-store'
            except urllib.error.HTTPError as error:
                with error:
                    status, data = error.code, error.read()
            if expected is not None:
                assert status == expected, (path, status, expected)
            value = json.loads(data) if data else None
            return (status, value) if expected is None else value

        def wait(predicate, seconds=35):
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                value = predicate()
                if value:
                    return value
                time.sleep(.1)
            raise AssertionError('bounded synthetic execution condition timed out')

        def launch(name, command, values=None, stdin=None, stdout=None):
            log = (output / (name + '.log')).open('w')
            logs.append(log)
            process = subprocess.Popen(command, cwd=ROOT, env=values or env, stdin=stdin,
                                       stdout=stdout or log, stderr=log, text=True)
            processes.append(process)
            return process

        def start_api(name):
            started = time.monotonic()
            process = launch(name, [safe.get('JAVA_BIN', 'java'), '-jar', str(jar)])
            def healthy():
                assert process.poll() is None, 'isolated API exited'
                try:
                    return api('/health') == {'status': 'UP'}
                except OSError:
                    return False
            wait(healthy, seconds=30)
            result['default_jvm_startups_seconds'].append(round(time.monotonic() - started, 3))
            return process

        def upload(project):
            boundary = 'execution-budget-one-synthetic-txt'
            content = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.txt"\r\n'
                       f'Content-Type: text/plain\r\n\r\n{SOURCE}\r\n--{boundary}--\r\n').encode()
            request = urllib.request.Request(base + f'/api/projects/{project}/materials', content,
                                             {'Authorization': 'Bearer demo-owner',
                                              'Content-Type': 'multipart/form-data; boundary=' + boundary})
            with opener.open(request, timeout=8) as response:
                return json.load(response)['id']

        def global_budget():
            row = api('/internal/text-budget', worker=True)
            assert row['contract'] == 'SYNTHETIC_TEXT_EXECUTION_BUDGET_V1'
            assert row['scope'] == 'INSTANCE_DATABASE_GLOBAL'
            assert row['currency'] == 'CNY' and row['unit'] == 'nano_cny'
            assert row['synthetic'] is True and row['is_bill'] is False
            assert row['real_egress_authorized'] is False
            summary = row['summary']
            assert summary['limit_nano_cny'] == 10_000_000_000
            assert summary['held_nano_cny'] + summary['spent_nano_cny'] + summary['available_nano_cny'] == 10_000_000_000
            assert all('lease_token' not in item for item in row['items'])
            for item in row['items']:
                events = item['events']
                assert 1 <= len(events) <= 4
                assert [event['event_number'] for event in events] == list(range(1, len(events) + 1))
                assert events[0]['outcome'] == 'RESERVED' and events[-1]['outcome'] == item['status']
                assert all(set(event) == {'event_number', 'outcome', 'evidence', 'actual_nano_cny', 'created_at'} for event in events)
            return row

        def task_state(job, queue='WORK', status='SUCCEEDED', actor='owner'):
            path = '/api/jobs/' if queue == 'WORK' else '/api/persona-jobs/'
            row = api(path + job['id'], actor=actor)
            assert row['status'] == status, ('unexpected synthetic task state', queue, row['status'], row.get('error_code'))
            return row

        def claim(job, queue='WORK'):
            path = '/internal/jobs/claim' if queue == 'WORK' else '/internal/persona-jobs/claim'
            row = api(path, {}, worker=True)
            assert row['id'] == job['id'], 'fixture claimed a different queued task'
            return row

        def execute(job, queue='WORK', ack_loss=False, mock=False, claimed=None, stop_after_checkpoint=False, streaming=False):
            row = claimed or claim(job, queue)
            values = dict(env)
            if streaming:
                values['MODEL_STREAMING'] = 'true'
            if mock:
                values.update(MODEL_MODE='mock', MODEL_SYNTHETIC_GLOBAL_BUDGET='false')
            child = launch('worker-' + str(len(result.setdefault('worker_runs', []))),
                           [sys.executable, str(Path(__file__).resolve()), '--execute-claim'],
                           values=values, stdin=subprocess.PIPE, stdout=subprocess.PIPE)
            output_text, _ = child.communicate(json.dumps({'queue': queue, 'job': row, 'ack_loss': ack_loss,
                                                                            'stop_after_checkpoint': stop_after_checkpoint}), timeout=40)
            assert child.returncode == 0, 'synthetic Worker execution subprocess failed'
            stats = json.loads(output_text)
            result['worker_runs'].append(stats)
            return row

        def profile_job(project, material, title='合成执行预算资料'):
            profile = api(f'/api/projects/{project}/profiles', {'title': title})['id']
            return profile, api(f'/api/profiles/{profile}/distill', {'material_ids': [material]})

        def persona_job(project, material, name):
            persona = api(f'/api/projects/{project}/personas', {
                'name': name, 'family': 'colleague', 'origin': 'original',
                'background': '仅用于合成测试，不含真人资料。', 'personality': '简洁，核对来源。',
                'expression': '短句', 'relationship': '合成工作伙伴',
                'boundaries': '不编造亲历，不冒充真人', 'consent': True})['id']
            return persona, api(f'/api/personas/{persona}/generate', {'material_ids': [material]})

        def reservation(job, queue, stage, marker, expected=200, lease=None):
            return api('/internal/text-budget/reservations', {
                'queue': queue, 'job_id': job['id'], 'lease_token': lease or job['lease_token'],
                'stage': stage, 'unit_digest': hashlib.sha256(marker.encode()).hexdigest(),
                'request_key': marker, 'model': 'deepseek-flash'}, worker=True, expected=expected)

        try:
            result['stage'] = 'default-JVM startup and access control'
            server = start_api('api')
            assert global_budget()['summary']['reservation_count'] == 0
            project = api('/api/projects', {'title': '合成全局执行预算空间A'})['id']
            other_project = api('/api/projects', {'title': '合成全局执行预算空间B'})['id']
            for actor, role in (('viewer', 'VIEWER'), ('editor', 'EDITOR')):
                api(f'/api/projects/{project}/members', {'user_id': actor, 'role': role}, method='PUT')
            for actor in ('owner', 'editor', 'viewer'):
                api('/internal/text-budget', actor=actor, expected=401)
                api('/internal/text-budget/reservations', {}, actor=actor, expected=401)
            for actor in ('editor', 'viewer'):
                api(f'/api/projects/{project}/text-budget', actor=actor, expected=403)
                api(f'/api/projects/{other_project}/text-budget', actor=actor, expected=403)
            api('/internal/text-budget', worker=True, token='synthetic-invalid-worker-token', expected=401)
            material, other_material = upload(project), upload(other_project)
            result['checks'].append('internal budget accepts only the configured synthetic Worker identity; project metadata is OWNER-only and foreign members are denied')

            result['stage'] = 'five actual text stages and completion replay'
            profile, job = profile_job(project, material)
            first_claim = execute(job, ack_loss=True)
            completed = task_state(job)
            version = completed['result']['version_id']
            assert result['worker_runs'][-1]['completion_requests'] == 2
            assert result['worker_runs'][-1]['injected_ack_losses'] == 1
            budget_after_distill = global_budget()
            assert budget_after_distill['summary']['reservation_count'] == 1
            assert budget_after_distill['summary']['held_nano_cny'] == 0
            assert budget_after_distill['summary']['spent_nano_cny'] == 320_800
            assert first_claim.get('checkpoint_protocol') == 1
            api('/internal/jobs/' + job['id'] + '/complete', {
                'lease_token': first_claim['lease_token'], 'output': {'checkpointed': True}}, worker=True)
            assert global_budget() == budget_after_distill
            assert fixture['calls'] == Counter({'distill': 1})
            api(f'/api/versions/{version}/review', {'approve': True, 'note': '合成记录人工审核'})
            answer_job = api(f'/api/profiles/{profile}/questions', {'question': '重试应核对什么？'}, actor='viewer')
            execute(answer_job)
            assert task_state(answer_job, actor='viewer')['result']['citations']
            persona, generation = persona_job(project, material, '合成预算伙伴')
            execute(generation, queue='PERSONA')
            persona_version = task_state(generation, 'PERSONA')['result']['version_id']
            api(f'/api/personas/{persona}/versions/{persona_version}/review', {'approve': True})
            conversation = api(f'/api/personas/{persona}/conversations', {}, actor='viewer')['id']
            chat = api(f'/api/conversations/{conversation}/messages', {'message': '合成资料中重试应核对什么？'}, actor='viewer')
            execute(chat, queue='PERSONA')
            task_state(chat, 'PERSONA', actor='viewer')
            snapshot = global_budget()
            assert snapshot['summary']['reservation_count'] == 5
            assert snapshot['summary']['spent_nano_cny'] == 5 * 320_800
            assert snapshot['summary']['held_nano_cny'] == 0
            assert {row['stage'] for row in snapshot['items']} == {'distill', 'knowledge_answer', 'persona_analysis', 'persona_build', 'default'}
            assert set(fixture['calls']) == {'distill', 'knowledge_answer', 'persona_analysis', 'persona_build', 'persona_chat'}
            result['checks'].append('real Java + actual Python Worker execute all five text stages with synthetic complete usage; exact 320800 nano-CNY each, checkpoint completion lost-ack retry and replay do not duplicate calls or charges')

            result['stage'] = 'saved chunk checkpoint with a fresh Worker lease'
            checkpoint_material = upload(project)
            checkpoint_profile = api(f'/api/projects/{project}/profiles', {'title': '合成分块恢复预算'})['id']
            checkpoint_job = api(f'/api/profiles/{checkpoint_profile}/distill', {'material_ids': [material, checkpoint_material]})
            before_checkpoint = global_budget()
            distills_before_checkpoint = fixture['calls']['distill']
            execute(checkpoint_job, stop_after_checkpoint=True)
            task_state(checkpoint_job, status='QUEUED')
            partial_checkpoint = global_budget()
            assert partial_checkpoint['summary']['spent_nano_cny'] == before_checkpoint['summary']['spent_nano_cny'] + 320_800
            resumed_claim = claim(checkpoint_job)
            assert len(resumed_claim['checkpoints']) == 1
            execute(checkpoint_job, claimed=resumed_claim)
            task_state(checkpoint_job)
            snapshot = global_budget()
            assert snapshot['summary']['reservation_count'] == before_checkpoint['summary']['reservation_count'] + 2
            assert snapshot['summary']['spent_nano_cny'] == before_checkpoint['summary']['spent_nano_cny'] + 641_600
            assert fixture['calls']['distill'] == distills_before_checkpoint + 2
            result['checks'].append('two real TXT chunks resume under a fresh lease and Worker after one saved checkpoint; cached chunk is not requested or charged twice')

            result['stage'] = 'default mock and charged failed output'
            mock_profile, mock_job = profile_job(other_project, other_material, '默认mock不记执行账')
            execute(mock_job, mock=True)
            task_state(mock_job)
            assert global_budget() == snapshot
            limit_profile, limited = profile_job(project, material, '合成截断仍有用量')
            fixture['plan'].append('length')
            execute(limited)
            failed = task_state(limited, status='FAILED')
            assert failed['error_code'] == 'MODEL_OUTPUT_LIMIT' and failed['attempt'] == 1
            charged = global_budget()
            assert charged['summary']['spent_nano_cny'] == snapshot['summary']['spent_nano_cny'] + 320_800
            assert charged['summary']['held_nano_cny'] == 0
            assert api(f'/api/profiles/{limit_profile}/versions') == []
            api('/api/jobs/' + limited['id'] + '/retry', method='POST')
            execute(limited)
            task_state(limited)
            assert global_budget()['summary']['reservation_count'] == charged['summary']['reservation_count'] + 1
            result['checks'].append('default mock produces no execution ledger entry; length-failed output with verified synthetic usage is still charged, produces no candidate version, and an explicit retry adds one fresh settled invocation')

            result['stage'] = 'unknown usage, cross-stage and new-lease blocking'
            unknown_persona, unknown_generation = persona_job(other_project, other_material, '合成缺回执人物')
            fixture['plan'].extend(('success', 'missing_usage'))
            execute(unknown_generation, queue='PERSONA')
            failure = task_state(unknown_generation, 'PERSONA', status='FAILED')
            assert failure['error_code'] == 'MODEL_EXECUTION_BUDGET_UNVERIFIED' and failure['attempt'] == 1
            unknown = global_budget()
            assert unknown['summary']['held_nano_cny'] == 1_000_000_000
            assert unknown['summary']['unresolved_count'] == 1
            unknown_item = next(item for item in unknown['items'] if item['job_id'] == unknown_generation['id'] and item['status'] == 'UNCERTAIN')
            assert unknown_item['stage'] == 'persona_build'
            assert [event['outcome'] for event in unknown_item['events']] == ['RESERVED', 'DISPATCHED', 'UNCERTAIN']
            assert unknown_item['events'][-1]['evidence'] == 'SYNTHETIC_UNKNOWN'
            calls_before_retry = sum(fixture['calls'].values())
            api('/api/persona-jobs/' + unknown_generation['id'] + '/retry', method='POST')
            new_claim = claim(unknown_generation, 'PERSONA')
            reservation(new_claim, 'PERSONA', 'persona_analysis', 'unknown-cross-analysis', expected=409)
            reservation(new_claim, 'PERSONA', 'persona_build', 'unknown-cross-build', expected=409)
            execute(unknown_generation, queue='PERSONA', claimed=new_claim)
            assert task_state(unknown_generation, 'PERSONA', status='FAILED')['error_code'] == 'MODEL_EXECUTION_BUDGET_UNVERIFIED'
            assert sum(fixture['calls'].values()) == calls_before_retry
            assert global_budget() == unknown
            result['checks'].append('missing build usage retains full hold; explicit retry with a new task lease cannot reserve analysis or build, sends no model request, and does not duplicate reservations')

            result['stage'] = 'unknown receipt survives actual server and Worker restart'
            crash_snapshot = global_budget()
            crash_acknowledged_at = time.monotonic()
            assert crash_snapshot == unknown
            server.kill()
            result['summary_ack_to_sigkill_seconds'] = round(time.monotonic() - crash_acknowledged_at, 6)
            server.wait(timeout=12)
            result['sigkill_returncode'] = server.returncode
            server = start_api('api-after-sigkill')
            assert global_budget() == crash_snapshot
            result['sigkill_snapshot_including_events_sha256'] = hashlib.sha256(
                json.dumps(crash_snapshot, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
            api('/api/persona-jobs/' + unknown_generation['id'] + '/retry', method='POST')
            execute(unknown_generation, queue='PERSONA')
            assert task_state(unknown_generation, 'PERSONA', status='FAILED')['error_code'] == 'MODEL_EXECUTION_BUDGET_UNVERIFIED'
            assert sum(fixture['calls'].values()) == calls_before_retry
            assert global_budget() == unknown
            for mode in ('http503', 'malformed_usage'):
                unknown_profile, uncertain_job = profile_job(project, material, '合成未知用量-' + mode)
                fixture['plan'].append(mode)
                execute(uncertain_job)
                row = task_state(uncertain_job, status='FAILED')
                assert row['error_code'] == 'MODEL_EXECUTION_BUDGET_UNVERIFIED' and row['attempt'] == 1
                assert api(f'/api/profiles/{unknown_profile}/versions') == []
            held = global_budget()
            assert held['summary']['held_nano_cny'] == 3_000_000_000
            assert held['summary']['unresolved_count'] == 3
            result['checks'].append('same-file H2 SIGKILL/restart immediately after acknowledged summary and fresh actual Worker execution preserve spent, unknown holds and every returned event; 503 and malformed usage stay terminal unknown with no candidate or automatic retry')

            result['stage'] = 'actual HTTP SSE usage and missing DONE'
            before_streams = global_budget()
            streaming_conversation = api(f'/api/personas/{persona}/conversations', {}, actor='viewer')['id']
            streaming_job = api(f'/api/conversations/{streaming_conversation}/messages',
                                {'message': '合成完整SSE用量验证'}, actor='viewer')
            fixture['plan'].append('stream_success')
            execute(streaming_job, queue='PERSONA', streaming=True)
            task_state(streaming_job, 'PERSONA', actor='viewer')
            complete_stream = global_budget()
            assert complete_stream['summary']['reservation_count'] == before_streams['summary']['reservation_count'] + 1
            assert complete_stream['summary']['spent_nano_cny'] == before_streams['summary']['spent_nano_cny'] + 320_800
            assert complete_stream['summary']['held_nano_cny'] == before_streams['summary']['held_nano_cny']
            settled_stream = next(item for item in complete_stream['items'] if item['job_id'] == streaming_job['id'])
            assert settled_stream['status'] == 'SETTLED'
            assert [event['outcome'] for event in settled_stream['events']] == ['RESERVED', 'DISPATCHED', 'SETTLED']
            assert len(api('/api/conversations/' + streaming_conversation, actor='viewer')['messages']) == 2
            broken_conversation = api(f'/api/personas/{persona}/conversations', {}, actor='viewer')['id']
            broken_stream = api(f'/api/conversations/{broken_conversation}/messages',
                                {'message': '合成缺失SSE结束标记验证'}, actor='viewer')
            fixture['plan'].append('stream_missing_done')
            execute(broken_stream, queue='PERSONA', streaming=True)
            stream_failure = task_state(broken_stream, 'PERSONA', status='FAILED', actor='viewer')
            assert stream_failure['error_code'] == 'MODEL_EXECUTION_BUDGET_UNVERIFIED' and stream_failure['attempt'] == 1
            unknown_stream = global_budget()
            assert unknown_stream['summary']['held_nano_cny'] == 4_000_000_000
            assert unknown_stream['summary']['spent_nano_cny'] == complete_stream['summary']['spent_nano_cny']
            assert unknown_stream['summary']['unresolved_count'] == 4
            stream_hold = next(item for item in unknown_stream['items'] if item['job_id'] == broken_stream['id'])
            assert stream_hold['status'] == 'UNCERTAIN'
            assert [event['outcome'] for event in stream_hold['events']] == ['RESERVED', 'DISPATCHED', 'UNCERTAIN']
            assert len(api('/api/conversations/' + broken_conversation, actor='viewer')['messages']) == 1
            before_stream_retry_calls = sum(fixture['calls'].values())
            api('/api/persona-jobs/' + broken_stream['id'] + '/retry', actor='viewer', method='POST')
            execute(broken_stream, queue='PERSONA', streaming=True)
            assert task_state(broken_stream, 'PERSONA', status='FAILED', actor='viewer')['error_code'] == 'MODEL_EXECUTION_BUDGET_UNVERIFIED'
            assert sum(fixture['calls'].values()) == before_stream_retry_calls
            assert global_budget() == unknown_stream
            assert len(api('/api/conversations/' + broken_conversation, actor='viewer')['messages']) == 1
            result['checks'].append('actual HTTP SSE content + final empty-delta usage + DONE settles once; final usage without DONE retains full unknown hold, creates no final reply, and explicit retry under a new lease sends no gateway request')

            result['stage'] = 'cross-space global capacity and concurrent last slot'
            reserved = []
            while global_budget()['summary']['available_nano_cny'] >= 2_000_000_000:
                index = len(reserved)
                target, source = (project, material) if index % 2 == 0 else (other_project, other_material)
                _, pending = profile_job(target, source, '合成容量占款-' + str(index))
                pending_claim = claim(pending)
                row = reservation(pending_claim, 'WORK', 'distill', 'capacity-' + str(index))
                reserved.append((row, pending_claim))
            contenders = []
            for index, (target, source) in enumerate(((project, material), (other_project, other_material))):
                _, pending = profile_job(target, source, '合成全局最后额度-' + str(index))
                contenders.append(claim(pending))
            barrier = threading.Barrier(2)
            def compete(index):
                barrier.wait(timeout=5)
                return reservation(contenders[index], 'WORK', 'distill', 'last-slot-' + str(index), expected=None)
            with ThreadPoolExecutor(max_workers=2) as pool:
                attempts = list(pool.map(compete, (0, 1)))
            assert sorted(status for status, _ in attempts) == [200, 409]
            exhausted = global_budget()
            assert exhausted['summary']['held_nano_cny'] == 9_000_000_000
            assert 0 <= exhausted['summary']['available_nano_cny'] < 1_000_000_000
            calls_before_capacity_check = sum(fixture['calls'].values())
            losing = contenders[next(index for index, attempt in enumerate(attempts) if attempt[0] == 409)]
            execute({'id': losing['id']}, claimed=losing)
            assert task_state(losing, status='FAILED')['error_code'] == 'MODEL_EXECUTION_BUDGET_UNVERIFIED'
            assert sum(fixture['calls'].values()) == calls_before_capacity_check
            assert global_budget() == exhausted
            for target in (project, other_project):
                scoped = api(f'/api/projects/{target}/text-budget')
                assert scoped['contract'] == 'SYNTHETIC_TEXT_EXECUTION_BUDGET_V1'
                allowed_ids = {item['id'] for item in exhausted['items'] if item['project_id'] == target}
                assert {item['id'] for item in scoped['items']} == allowed_ids
                assert all(not {'lease_token', 'job_id', 'project_id', 'unit_digest', 'request_key', 'receipt_key', 'events'}.intersection(item)
                           for item in scoped['items'])
                assert 'available_nano_cny' not in scoped['summary']
            assert not fixture['unexpected_requests']
            result['checks'].append('different spaces compete for one global remaining reservation: one success and one 409; actual Worker on exhausted space sends no request; OWNER views contain only own-project metadata and no global available balance')
            stop(server)
            server = start_api('api-final-normal-restart')
            assert global_budget() == exhausted
            result['final_global_summary'] = exhausted['summary']
            assert len(result['default_jvm_startups_seconds']) == 3
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
            gateway.shutdown()
            gateway.server_close()
            gateway_thread.join(timeout=5)
            result['fixture_calls_by_stage'] = dict(fixture['calls'])
            result['fixture_observations'] = fixture['observations']
            result['unexpected_fixture_requests'] = fixture['unexpected_requests']
            result['cleanup_all_processes_stopped'] = all(process.poll() is not None for process in processes)
            result['cleanup_gateway_stopped'] = not gateway_thread.is_alive()
            (output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print('PASS: text-execution-budget', len(result['checks']), 'groups; real provider calls 0')


def execute_claim():
    """One actual Worker execution, receiving only this test's synthetic lease via stdin."""
    from urllib.parse import urlsplit
    target = urlsplit(os.environ.get('MODEL_BASE_URL', ''))
    api_target = urlsplit(os.environ.get('PLATFORM_API_URL', ''))
    if (os.environ.get('PLATFORM_DEMO') != 'true' or target.hostname != '127.0.0.1'
            or api_target.hostname != '127.0.0.1' or os.environ.get('MODEL_API_KEY') != FIXTURE_KEY):
        raise RuntimeError('one-shot Worker requires the isolated literal-loopback fixture')
    sys.path.insert(0, str(ROOT / 'worker'))
    from worker import Worker
    import persona
    payload = json.load(sys.stdin)
    runner = Worker()
    stats = {'queue': payload['queue'], 'completion_requests': 0, 'injected_ack_losses': 0,
             'checkpoint_stops': 0, 'streaming': os.environ.get('MODEL_STREAMING') == 'true'}
    original = runner.request
    completion_path = ('/internal/jobs/' if payload['queue'] == 'WORK' else '/internal/persona-jobs/') + payload['job']['id'] + '/complete'
    def request(path, body, **kwargs):
        if path != completion_path:
            value = original(path, body, **kwargs)
            if payload.get('stop_after_checkpoint') and path.endswith('/chunks/complete') and stats['checkpoint_stops'] == 0:
                stats['checkpoint_stops'] += 1
                runner.stop.set()
            return value
        stats['completion_requests'] += 1
        value = original(path, body, **kwargs)
        if payload['ack_loss'] and stats['injected_ack_losses'] == 0:
            stats['injected_ack_losses'] += 1
            raise ConnectionResetError('synthetic completion acknowledgement lost after commit')
        return value
    runner.request = request
    if payload['queue'] == 'WORK':
        runner.execute(payload['job'])
    else:
        persona.execute(runner, payload['job'])
    print(json.dumps(stats))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=ROOT / 'server/target/text-execution-budget-e2e')
    parser.add_argument('--execute-claim', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    execute_claim() if args.execute_claim else run(args.output)
