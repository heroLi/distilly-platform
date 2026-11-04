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

    def run_source_deletion_probe(self):
        """New regression outside the frozen 30 cases; actual mock memory lifecycle."""
        project = self.api('/api/projects', {'title': 'Synthetic cross-conversation source deletion'})['id']
        self.api(f'/api/projects/{project}/members', {'user_id': 'viewer', 'role': 'VIEWER'}, method='PUT')
        person, _ = self.create_persona(project, {
            'name': 'Synthetic source boundary', 'family': 'relationship', 'origin': 'original',
            'personality': '简洁', 'expression': '短句', 'background': '合成角色',
            'relationship': '合成朋友', 'boundaries': '不冒充真人', 'consent': False})
        source = self.api(f'/api/personas/{person}/conversations', {}, actor='viewer')['id']
        first = self.api(f'/api/conversations/{source}/messages',
                         {'message': '请记住，我喜欢青岛海边拍照。', 'request_id': str(uuid.uuid4())}, actor='viewer')
        self.execute(first)
        self.result(first, actor='viewer')
        memories = self.api(f'/api/personas/{person}/memories', actor='viewer')
        require(len(memories) == 1 and memories[0]['status'] == 'PENDING', 'source_probe_pending')
        memory = memories[0]['id']
        self.api(f'/api/personas/{person}/memories/{memory}', {'status': 'CONFIRMED'}, actor='viewer', method='PUT')
        target = self.api(f'/api/personas/{person}/conversations', {}, actor='viewer')['id']
        held = self.api(f'/api/conversations/{target}/messages', {'message': '青岛海边拍照'}, actor='viewer')
        claimed = self.worker.request('/internal/persona-jobs/claim', {})
        require(claimed['id'] == held['id'], 'source_probe_claim_identity')
        require([m['id'] for m in claimed['payload']['memories']] == [memory], 'source_probe_memory_selected')
        self.worker.request(f'/internal/persona-jobs/{held["id"]}/delta',
                            {'lease_token': claimed['lease_token'], 'partial': '合成在途片段'})
        self.api(f'/api/conversations/{source}', method='DELETE', expected=403)
        require(self.api('/api/persona-jobs/' + held['id'], actor='viewer')['status'] == 'RUNNING', 'source_probe_unauthorized_no_cancel')
        waiting = self.api(f'/api/personas/{person}/conversations', {}, actor='viewer')['id']
        queued = self.api(f'/api/conversations/{waiting}/messages', {'message': '青岛海边拍照'}, actor='viewer')
        owner = self.api(f'/api/personas/{person}/conversations', {})['id']
        independent = self.api(f'/api/conversations/{owner}/messages', {'message': '合成独立交流'})
        self.api(f'/api/conversations/{source}', actor='viewer', method='DELETE')
        self.api(f'/api/conversations/{source}', actor='viewer', expected=404)
        require(self.api(f'/api/personas/{person}/memories', actor='viewer') == [], 'source_probe_memory_removed')
        for job in (held, queued):
            row = self.api('/api/persona-jobs/' + job['id'], actor='viewer')
            require(row['status'] == 'CANCELLED' and row['partial_reply'] == '', 'source_probe_cancelled')
        require(self.api('/api/persona-jobs/' + independent['id'])['status'] == 'QUEUED', 'source_probe_other_user_unaffected')
        import persona
        persona.execute(self.worker, claimed)
        before = self.api('/api/conversations/' + target, actor='viewer')
        require(len(before['messages']) == 1 and before['active_job'] == [], 'source_probe_no_late_reply')
        require(before['latest_chat_job']['status'] == 'CANCELLED', 'source_probe_terminal_after_worker')
        self.execute(independent)
        self.result(independent)
        fresh = self.api(f'/api/conversations/{target}/messages', {'message': '青岛海边拍照'}, actor='viewer')
        current = self.execute(fresh)
        require(current['payload']['memories'] == [], 'source_probe_fresh_context_empty')
        self.result(fresh, actor='viewer')
        require(len(self.api('/api/conversations/' + target, actor='viewer')['messages']) == 3, 'source_probe_fresh_unique_reply')
        return {'status': 'PASS', 'fixture': 'new independent source-deletion protocol probe; not part of frozen 30',
                'confirmed_memories_removed': 1, 'held_and_queued_cancelled': 2, 'late_assistant_messages': 0,
                'other_user_job_preserved': True, 'fresh_context_memories': 0, 'browser': 'NOT_RUN'}

    def run_memory_support_boundary_probe(self):
        """Exposed regression/characterization, separate from frozen 30 and any holdout."""
        import persona
        data = json.loads((FIXTURES / 'memory_m0_dev_v1.json').read_text())
        cases = []
        for case in data['cases']:
            if case['category'] == 'strict_support':
                source = next(row for row in data['memories'] if row.get('family_id') == case['family_id']
                              and row['scope'] == 'self' and row['status'] == 'CONFIRMED')
                cases.append((case['id'], 'exposed_strict', case['query'], source['body'], False))
        cases += [
            ('REG-MISSING', 'missing_condition', '绒石飞轮样机周六使用雾陶外壳',
             '合成回归：绒石飞轮样机使用雾陶外壳。', False),
            ('REG-NEGATED', 'explicit_negation', '绒石飞轮样机使用雾陶外壳',
             '合成回归：绒石飞轮样机不使用雾陶外壳。', False),
            ('REG-LEGIT', 'legitimate_control', '绒石飞轮样机使用雾陶外壳',
             '合成回归：绒石飞轮样机使用雾陶外壳。', True)]
        project = self.api('/api/projects', {'title': 'Synthetic support boundary regressions'})['id']
        observations = []
        for name, category, query, body, supports in cases:
            person, _ = self.create_persona(project, {'name': 'Synthetic boundary ' + name,
                'family': 'relationship', 'origin': 'original', 'boundaries': '不把召回当支持'})
            source = self.api(f'/api/personas/{person}/conversations', {})['id']
            first = self.api(f'/api/conversations/{source}/messages', {'message': '请记住，' + body})
            self.execute(first)
            self.result(first)
            memories = self.api(f'/api/personas/{person}/memories')
            require(len(memories) == 1, 'support_probe_single_pending')
            memory = memories[0]['id']
            self.api(f'/api/personas/{person}/memories/{memory}', {'status': 'CONFIRMED'}, method='PUT')
            target = self.api(f'/api/personas/{person}/conversations', {})['id']
            job = self.api(f'/api/conversations/{target}/messages', {'message': query})
            lease = self.execute(job)
            require([row['id'] for row in lease['payload']['memories']] == [memory], 'support_probe_actual_recall')
            result = self.result(job)
            require('相关不等于支持' in result['answer'] and '模拟模式不判断' in result['answer'],
                    'support_probe_mock_reference_notice')
            require(memories[0]['body'] in result['answer'], 'support_probe_source_preserved')
            require('我记得你确认过' not in result['answer'] and result['memory_citations'] == [],
                    'support_probe_no_mock_support_assertion')
            reference = self.api(f'/api/personas/{person}/memory-references', {'mode': 'SOURCE_REFERENCE', 'query': query})
            require(reference['support_status'] == 'NOT_ASSESSED' and 'answer' not in reference
                    and [r['memory_id'] for r in reference['references']] == [memory]
                    and reference['references'][0]['record_text'] == memories[0]['body'], 'support_probe_reference_only_mode')
            # Actual Worker validation and Java completion accept valid IDs, not entailment.
            injected_session = self.api(f'/api/personas/{person}/conversations', {})['id']
            injected_job = self.api(f'/api/conversations/{injected_session}/messages', {'message': query})
            held = self.worker.request('/internal/persona-jobs/claim', {})
            require(held['id'] == injected_job['id'], 'support_probe_injected_identity')
            class ScriptedOfflineModel:
                mode = 'gateway'
                def call(self, system, payload, **kwargs):
                    require('召回仅提供候选' in system, 'support_probe_prompt_received')
                    return {'answer': '合成注入断言：' + query + '。', 'memory_candidates': [],
                            'emotion': {'label': 'calm', 'intensity': .1, 'reason': '合成引用边界探针'},
                            'memory_citations': [memory], 'citations': [], 'experience_citations': []}
            output = persona.chat(ScriptedOfflineModel(), held['payload'])
            self.worker.request('/internal/persona-jobs/' + held['id'] + '/complete',
                                {'lease_token': held['lease_token'], 'output': output})
            stored = self.result(injected_job)
            require(stored['answer'] == output['answer'] and stored['memory_citations'] == [memory],
                    'support_probe_current_id_only_boundary')
            observations.append({'id': name, 'category': category, 'selected_candidates': 1,
                                 'mock_reference_only': True, 'injected_claim_supported_by_fixture': supports,
                                 'injected_claim_accepted_by_id_checks': True})
        print('OBSERVED: memory support boundary; mock references ' + str(len(observations))
              + '; unsupported injected claims accepted ' + str(sum(not row[-1] for row in cases))
              + '; semantic verifier NOT_IMPLEMENTED; real calls0; holdout NOT_RUN', flush=True)
        return {'status': 'CHARACTERIZED_NOT_SEMANTIC_ACCEPTANCE', 'cases': observations,
                'mock_reference_regressions_passed': len(observations), 'exposed_strict_candidates': 3,
                'unsupported_injected_claims_accepted': sum(not row[-1] for row in cases),
                'legitimate_control_accepted': 1, 'semantic_verifier': 'NOT_IMPLEMENTED',
                'real_provider_calls': 0, 'holdout': 'NOT_RUN', 'browser': 'NOT_RUN'}

    def run_memory_reference_mode_probe(self):
        """Explicit read-only protocol, not an answer quality or blind evaluation."""
        project = self.api('/api/projects', {'title': 'Synthetic reference-only protocol'})['id']
        self.api(f'/api/projects/{project}/members', {'user_id': 'viewer', 'role': 'VIEWER'}, method='PUT')
        person, _ = self.create_persona(project, {'name': 'Synthetic reference', 'family': 'relationship', 'origin': 'original'})
        source = self.api(f'/api/personas/{person}/conversations', {}, actor='viewer')['id']
        first = self.api(f'/api/conversations/{source}/messages', {'message': '请记住，合成参考：绒石灯座不使用青藤电池。'}, actor='viewer')
        self.execute(first)
        self.result(first, actor='viewer')
        rows = self.api(f'/api/personas/{person}/memories', actor='viewer')
        require(len(rows) == 1, 'reference_probe_pending')
        memory = rows[0]['id']
        self.api(f'/api/personas/{person}/memories/{memory}', {'status': 'CONFIRMED'}, actor='viewer', method='PUT')
        path = f'/api/personas/{person}/memory-references'
        body = {'mode': 'SOURCE_REFERENCE', 'query': '绒石灯座青藤电池'}
        sessions_before = self.api(f'/api/personas/{person}/conversations', actor='viewer')
        messages_before = len(self.api('/api/conversations/' + source, actor='viewer')['messages'])
        reference = self.api(path, body, actor='viewer')
        require(set(reference) == {'contract', 'mode', 'support_status', 'notice', 'references'}
                and reference['contract'] == 'MEMORY_REFERENCE_V1' and reference['support_status'] == 'NOT_ASSESSED', 'reference_probe_contract')
        require(len(reference['references']) == 1 and reference['references'][0]['record_text'] == rows[0]['body'], 'reference_probe_literal')
        require(self.api(path, body)['references'] == [], 'reference_probe_owner_cannot_read_viewer_memory')
        require(self.api(f'/api/personas/{person}/conversations', actor='viewer') == sessions_before
                and len(self.api('/api/conversations/' + source, actor='viewer')['messages']) == messages_before,
                'reference_probe_no_conversation_writes')
        require(self.worker.request('/internal/persona-jobs/claim', {}) is None, 'reference_probe_no_worker_job')
        self.api(path, {**body, 'stream': True}, actor='viewer', expected=422)
        self.api(path, {**body, 'answer': 'synthetic injection'}, actor='viewer', expected=422)
        self.api(f'/api/conversations/{source}/messages', {'message': body['query'], 'mode': 'SOURCE_REFERENCE'}, actor='viewer', expected=422)
        corrected = rows[0]['body'] + '（合成已纠错）'
        self.api(f'/api/personas/{person}/memories/{memory}', {'status': 'CONFIRMED', 'body': corrected}, actor='viewer', method='PUT')
        current = self.api(path, body, actor='viewer')['references'][0]
        require(current['record_text'] == corrected and current['record_fingerprint'] != reference['references'][0]['record_fingerprint'], 'reference_probe_current_revision')
        self.api(f'/api/personas/{person}/memories/{memory}', actor='viewer', method='DELETE')
        require(self.api(path, body, actor='viewer')['references'] == [], 'reference_probe_forget')
        require(len(self.api('/api/conversations/' + source, actor='viewer')['messages']) == messages_before, 'reference_probe_history_unchanged')
        self.api(f'/api/personas/{person}', method='DELETE')
        self.api(path, body, actor='viewer', expected=404)
        print('PASS: explicit memory-reference JSON protocol; SQL literal records, private scope, refresh/forget, no chat/job/delta channel; support NOT_ASSESSED', flush=True)
        return {'status': 'PASS_PROTOCOL_NOT_ANSWER_QUALITY', 'contract': 'MEMORY_REFERENCE_V1',
                'literal_record_checked': True, 'private_scope_checked': True, 'correction_forget_checked': True,
                'chat_fallback_and_stream_rejected': True, 'no_chat_or_job_writes': True,
                'archived_persona_denied': True, 'support_status': 'NOT_ASSESSED',
                'real_provider_calls': 0, 'browser': 'NOT_RUN'}

    def run_memory_candidate_limits_probe(self):
        """Actual mock -> callback -> pending SQL; outside frozen quality cases."""
        project = self.api('/api/projects', {'title': 'Synthetic candidate length boundaries'})['id']
        self.api(f'/api/projects/{project}/members', {'user_id': 'viewer', 'role': 'VIEWER'}, method='PUT')
        person, _ = self.create_persona(project, {'name': 'Synthetic candidate boundary',
            'family': 'relationship', 'origin': 'original', 'boundaries': '仅合成资料'})
        suffix = '仅在预约成功时有效。'
        cases = [('UTF16_EDGE', '请记住🙂：' + '合成填充' * 75 + '但从未执行。'),
                 ('UTF16_MANY', '请记住：' + '🙂' * 155 + '仅在预约成功时参加。'),
                 ('BODY_1000', '请记住：' + '甲' * (1000 - len('请记住：') - len(suffix)) + suffix)]
        for name, message in cases:
            conversation = self.api(f'/api/personas/{person}/conversations', {}, actor='viewer')['id']
            job = self.api(f'/api/conversations/{conversation}/messages', {'message': message}, actor='viewer')
            self.execute(job)
            self.result(job, actor='viewer')
            rows = self.api(f'/api/personas/{person}/memories', actor='viewer')
            selected = [row for row in rows if row['body'] == message]
            require(len(selected) == 1 and selected[0]['status'] == 'PENDING', 'candidate_limits_pending_full_body_' + name)
            memory = selected[0]['id']
            require(self.api(f'/api/personas/{person}/memories') == [], 'candidate_limits_private_scope')
            self.api(f'/api/personas/{person}/memories/{memory}', {'status': 'CONFIRMED'}, method='PUT', expected=403)
            self.api(f'/api/personas/{person}/memories/{memory}', {'status': 'CONFIRMED'}, actor='viewer', method='PUT')
            require(next(row for row in self.api(f'/api/personas/{person}/memories', actor='viewer')
                         if row['id'] == memory)['body'] == message, 'candidate_limits_status_only_preserves_body')
            source = f'/api/personas/{person}/memories/{memory}/source'
            require(self.api(source, actor='viewer')['body'] == message, 'candidate_limits_original_source')
            self.api(source, expected=404)
            self.api(f'/api/personas/{person}/memories/{memory}',
                     {'status': 'CONFIRMED', 'body': '用户主动改为合成午后安排。'}, actor='viewer', method='PUT')
            require(next(row for row in self.api(f'/api/personas/{person}/memories', actor='viewer')
                         if row['id'] == memory)['body'] == '用户主动改为合成午后安排。', 'candidate_limits_explicit_edit')
            require(self.api(source, actor='viewer')['body'] == message, 'candidate_limits_edit_keeps_original')
        before = len(self.api(f'/api/personas/{person}/memories', actor='viewer'))
        conversation = self.api(f'/api/personas/{person}/conversations', {}, actor='viewer')['id']
        message = '请记住：' + '甲' * (1001 - len('请记住：') - len(suffix)) + suffix
        job = self.api(f'/api/conversations/{conversation}/messages', {'message': message}, actor='viewer')
        self.execute(job)
        self.result(job, actor='viewer', status='FAILED')
        require(len(self.api(f'/api/personas/{person}/memories', actor='viewer')) == before, 'candidate_limits_overflow_no_truncated_row')
        detail = self.api(f'/api/conversations/{conversation}', actor='viewer')
        require(len(detail['messages']) == 1 and detail['messages'][0]['body'] == message,
                'candidate_limits_overflow_completion_atomic')
        print('PASS: memory-candidate limits 4 HTTP/mock groups; UTF-16 quote, full body, explicit edit, provenance/private scope, atomic overflow; model calls0', flush=True)
        return {'status': 'PASS_BOUNDARY_NOT_SEMANTIC_QUALITY', 'groups': 4,
                'actual_worker_mock': True, 'gateway_output_injected': False,
                'real_provider_calls': 0, 'browser': 'NOT_RUN'}

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
                        if family == 'relationship':
                            report['additional_source_deletion_probe'] = runtime.run_source_deletion_probe()
                            report['additional_memory_support_boundary'] = runtime.run_memory_support_boundary_probe()
                            report['additional_memory_reference_mode'] = runtime.run_memory_reference_mode_probe()
                            report['additional_memory_candidate_limits'] = runtime.run_memory_candidate_limits_probe()
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
