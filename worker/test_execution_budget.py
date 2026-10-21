"""New synthetic task-bound budget protocol tests; no provider or credentials used."""
import hashlib
import io
import json
import os
import threading
import unittest
import urllib.error
from copy import deepcopy
from unittest.mock import Mock, patch

import worker
import persona
from execution_budget import execution_context, budgeted_call, ExecutionBudgetUnverified
from deepseek_usage import PRICE_REVIEW


def usage_response(content='{"answer":"synthetic answer","citations":[]}', *, reason='stop', stream=False):
    return {'id': 'synthetic.receipt:01', 'model': 'deepseek-flash', 'created': 1792580000,
            'system_fingerprint': 'synthetic_fp',
            'object': 'chat.completion.chunk' if stream else 'chat.completion',
            'choices': [{'index': 0, 'finish_reason': reason,
                         'delta' if stream else 'message': {} if stream else {'content': content}}],
            'usage': {'prompt_tokens': 300, 'prompt_cache_hit_tokens': 100,
                      'prompt_cache_miss_tokens': 200, 'prompt_tokens_details': {'cached_tokens': 100},
                      'completion_tokens': 50, 'total_tokens': 350,
                      'completion_tokens_details': {'reasoning_tokens': 20}}}


class Reply:
    def __init__(self, data, stream=False):
        self.data = data
        self.buffer = io.BytesIO(data)
        self.headers = {'Content-Type': 'text/event-stream' if stream else 'application/json'}
    def __enter__(self): return self
    def __exit__(self, *unused): return None
    def read(self, count): return self.buffer.read(count)
    def __iter__(self): return iter(self.data.splitlines(keepends=True))


class BudgetApi:
    def __init__(self):
        self.stop = threading.Event()
        self.calls = []
        self.events = []
        self.reservations = {}
        self.receipts = {}
        self.drop_reserve = self.drop_dispatch = self.drop_outcome = 0
        self.reject_reserve = False
        self.tamper_reserve = self.tamper_dispatch = self.tamper_outcome = None
    def request(self, path, payload, timeout=30):
        payload = deepcopy(payload); self.calls.append((path, payload)); self.events.append(path)
        if path == '/internal/text-budget/reservations':
            if self.reject_reserve:
                raise urllib.error.HTTPError('http://127.0.0.1/synthetic', 409, 'synthetic unresolved', {}, None)
            key = payload['request_key']
            if key not in self.reservations:
                self.reservations[key] = {**{k:v for k,v in payload.items() if k!='lease_token'},
                    'id': 'reservation-' + str(len(self.reservations) + 1), 'status':'RESERVED',
                    'reserved_nano_cny':1_000_000_000, 'price_review':PRICE_REVIEW,
                    'contract':'SYNTHETIC_TEXT_EXECUTION_BUDGET_V1', 'scope':'INSTANCE_DATABASE_GLOBAL',
                    'currency':'CNY', 'unit':'nano_cny', 'synthetic':True, 'is_bill':False,
                    'real_egress_authorized':False}
            result = deepcopy(self.reservations[key])
            if self.drop_reserve:
                self.drop_reserve -= 1; raise OSError('synthetic lost reserve acknowledgement')
            if self.tamper_reserve: result.update(self.tamper_reserve)
            return result
        if path.endswith('/dispatch'):
            if self.drop_dispatch:
                self.drop_dispatch -= 1; raise OSError('synthetic lost dispatch acknowledgement')
            identity = path.split('/')[-2]
            result = self.view(identity, 'DISPATCHED'); result['dispatch_permitted']=True
            if self.tamper_dispatch: result.update(self.tamper_dispatch)
            return result
        if path.endswith('/outcomes'):
            identity = path.split('/')[-2]
            if payload['outcome'] == 'SETTLED':
                if payload['actual_nano_cny'] > 1_000_000_000:
                    raise urllib.error.HTTPError('http://127.0.0.1/synthetic', 409, 'synthetic amount exceeds hold', {}, None)
                receipt = payload['receipt_key']
                prior = self.receipts.get(receipt)
                if prior is not None and prior != identity:
                    raise urllib.error.HTTPError('http://127.0.0.1/synthetic', 409, 'synthetic receipt conflict', {}, None)
                self.receipts[receipt] = identity
            if self.drop_outcome:
                self.drop_outcome -= 1; raise OSError('synthetic lost outcome acknowledgement')
            result = self.view(identity, payload['outcome'])
            if payload['outcome']=='SETTLED':
                result.update({k:payload[k] for k in ('receipt_key','actual_nano_cny')})
                if self.tamper_outcome: result.update(self.tamper_outcome)
            return result
        return None
    def view(self, identity, status):
        row = next(value for value in self.reservations.values() if value['id']==identity)
        return {**deepcopy(row), 'status':status}
    def complete_with_retry(self, path, payload): return self.request(path, payload)


class ExecutionBudgetTests(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {'MODEL_SYNTHETIC_GLOBAL_BUDGET': 'true',
            'MODEL_SYNTHETIC_TEXT_FIXTURE': 'true', 'MODEL_ALLOW_HTTP': 'true',
            'MODEL_STREAMING': 'true', 'MODEL_JSON_MODE': 'false'}, clear=True)
        self.env.start(); self.addCleanup(self.env.stop)
        self.api = BudgetApi()
        self.job = {'id': 'synthetic-work', 'kind': 'ANSWER', 'lease_token': 'synthetic-lease',
                    'lease_seconds': 300, 'payload': {'question': 'synthetic question', 'claims': []}}
        self.model = worker.Model(mode='gateway', url='http://127.0.0.1:18099/v1',
                                  model='deepseek-flash', key='synthetic-fixture-only')
        self.opener = Mock()
        self.open_patch = patch('urllib.request.build_opener', return_value=self.opener)
        self.open_patch.start(); self.addCleanup(self.open_patch.stop)
        self.reply(usage_response())
    def reply(self, raw):
        self.opener.open.side_effect = None
        self.opener.open.return_value = Reply(json.dumps(raw).encode())
    def call(self, **kwargs):
        with execution_context(self.api, 'WORK', self.job):
            return self.model.call('synthetic system', {'topic': 'synthetic input'},
                                   output_stage='knowledge_answer', **kwargs)
    def outcomes(self):
        return [payload for path, payload in self.api.calls if path.endswith('/outcomes')]

    def test_default_off_preserves_old_protocol_fixture_without_budget(self):
        os.environ.pop('MODEL_SYNTHETIC_GLOBAL_BUDGET')
        self.reply({'choices': [{'finish_reason': 'stop', 'message': {'content': '{"old":true}'}}]})
        self.assertEqual(self.model.call('synthetic', {}), {'old': True})
        self.assertEqual(self.api.calls, [])

    def test_optin_bare_call_and_wrong_model_have_no_api_or_model_network(self):
        with self.assertRaises(ExecutionBudgetUnverified): self.model.call('synthetic', {})
        with execution_context(self.api, 'WORK', self.job):
            self.model.model = 'other-synthetic-model'
            with self.assertRaises(ExecutionBudgetUnverified):
                self.model.call('synthetic', {}, output_stage='knowledge_answer')
        self.assertEqual(self.api.calls, []); self.opener.open.assert_not_called()

    def test_real_remote_remains_blocked_before_budget_or_request_construction(self):
        self.model.url = 'https://api.deepseek.com/v1'
        with execution_context(self.api, 'WORK', self.job), patch('urllib.request.Request') as request:
            with self.assertRaises(Exception) as error:
                self.model.call('synthetic', {}, output_stage='knowledge_answer')
        self.assertEqual(worker.failure_code(error.exception), 'MODEL_BUDGET_NOT_VERIFIED')
        request.assert_not_called(); self.assertEqual(self.api.calls, []); self.opener.open.assert_not_called()

    def test_success_binds_context_hashes_receipt_and_settles_exact_nano_estimate(self):
        self.assertEqual(self.call()['answer'], 'synthetic answer')
        reserve = self.api.calls[0][1]
        self.assertEqual(set(reserve), {'queue','job_id','lease_token','stage','unit_digest','request_key','model'})
        self.assertEqual((reserve['queue'],reserve['job_id'],reserve['stage']), ('WORK','synthetic-work','knowledge_answer'))
        self.assertRegex(reserve['unit_digest'], r'^[a-f0-9]{64}$')
        self.assertRegex(reserve['request_key'], r'^[A-Za-z0-9_-]{1,80}$')
        self.assertNotIn('synthetic input', json.dumps(self.api.calls))
        settled = self.outcomes()[0]
        self.assertEqual(settled['actual_nano_cny'], 804000)
        self.assertEqual(settled['receipt_key'], hashlib.sha256(b'synthetic.receipt:01').hexdigest())
        self.assertEqual(settled['evidence'], 'SYNTHETIC_USAGE_V1')
        self.assertEqual([path.rsplit('/',1)[-1] for path,_ in self.api.calls], ['reservations','dispatch','outcomes'])
        self.assertEqual(self.opener.open.call_count, 1)

    def test_lost_reserve_ack_replays_same_payload_and_sends_model_once(self):
        self.api.drop_reserve = 1; self.call()
        reserves = [body for path,body in self.api.calls if path.endswith('/reservations')]
        self.assertEqual(len(reserves), 2); self.assertEqual(reserves[0], reserves[1])
        self.assertEqual(self.opener.open.call_count, 1)

    def test_dispatch_is_never_retried_after_lost_ack_and_model_is_not_sent(self):
        self.api.drop_dispatch = 1
        with self.assertRaises(ExecutionBudgetUnverified): self.call()
        self.assertEqual(sum(path.endswith('/dispatch') for path,_ in self.api.calls), 1)
        self.assertEqual(self.outcomes()[-1]['outcome'], 'UNCERTAIN')
        self.opener.open.assert_not_called()

    def test_settlement_ack_replays_same_receipt_without_model_retry(self):
        self.api.drop_outcome = 1; self.call()
        self.assertEqual(self.outcomes()[0], self.outcomes()[1]); self.assertEqual(self.opener.open.call_count, 1)

    def test_missing_usage_and_network_failure_hold_unknown_without_model_retry(self):
        for mode in ('missing_usage','network_error'):
            self.api = BudgetApi(); self.opener.open.reset_mock()
            if mode == 'missing_usage':
                raw = usage_response(); raw.pop('usage'); self.reply(raw)
            else: self.opener.open.side_effect = OSError('synthetic disconnect')
            with self.subTest(mode=mode), self.assertRaises(ExecutionBudgetUnverified) as error: self.call()
            self.assertEqual(worker.failure_code(error.exception), 'MODEL_EXECUTION_BUDGET_UNVERIFIED')
            self.assertEqual(self.outcomes()[-1]['outcome'], 'UNCERTAIN'); self.assertEqual(self.opener.open.call_count, 1)

    def test_complete_usage_settles_before_invalid_business_json_or_length_check(self):
        for raw in (usage_response(content='not JSON'), usage_response(reason='length')):
            self.api = BudgetApi(); self.reply(raw)
            with self.assertRaises(ValueError) as error: self.call()
            self.assertEqual(worker.failure_code(error.exception),
                'MODEL_OUTPUT_LIMIT' if raw['choices'][0]['finish_reason']=='length' else 'INVALID_MODEL_OUTPUT')
            self.assertEqual(self.outcomes()[0]['outcome'], 'SETTLED')
            self.assertEqual(self.outcomes()[0]['actual_nano_cny'], 804000)

    def stream(self, *, done=True, reason='stop', duplicate=False):
        intermediate = usage_response(stream=True)
        intermediate['usage'] = None; intermediate['choices'][0]['finish_reason'] = None
        intermediate['choices'][0]['delta'] = {'content': '{"answer":"synthetic answer","citations":[]}'}
        final = usage_response(stream=True,reason=reason)
        frames = [intermediate, final] + ([deepcopy(final)] if duplicate else [])
        data = b''.join(('data: '+json.dumps(frame)+'\n\n').encode() for frame in frames)
        if done: data += b'data: [DONE]\n\n'
        self.opener.open.return_value = Reply(data, stream=True)

    def test_stream_done_validates_complete_usage_once_and_duplicate_never_sums(self):
        self.stream(duplicate=True); result = self.call(on_chunk=lambda unused: None)
        self.assertEqual(result['answer'], 'synthetic answer'); self.assertEqual(len(self.outcomes()),1)
        self.assertEqual(self.outcomes()[0]['actual_nano_cny'], 804000)

    def test_stream_missing_done_is_unknown_and_length_settles_before_business_rejection(self):
        self.stream(done=False)
        with self.assertRaises(ExecutionBudgetUnverified): self.call(on_chunk=lambda unused: None)
        self.assertEqual(self.outcomes()[-1]['outcome'],'UNCERTAIN')
        self.api = BudgetApi(); self.stream(reason='length')
        with self.assertRaises(ValueError) as error: self.call(on_chunk=lambda unused: None)
        self.assertEqual(worker.failure_code(error.exception),'MODEL_OUTPUT_LIMIT')
        self.assertEqual(self.outcomes()[0]['outcome'],'SETTLED')

    def test_stream_cancellation_after_dispatch_keeps_hold(self):
        cancelled = threading.Event(); self.stream()
        with self.assertRaises(ExecutionBudgetUnverified):
            self.call(on_chunk=lambda unused: cancelled.set(), should_cancel=cancelled.is_set)
        self.assertEqual(self.outcomes()[-1]['outcome'],'UNCERTAIN'); self.assertEqual(self.opener.open.call_count,1)

    def test_reserve_ack_is_bound_to_protocol_task_stage_model_and_request(self):
        for tamper in ({'contract':'other'}, {'synthetic':1}, {'is_bill':True},
                       {'real_egress_authorized':True}, {'currency':'USD'}, {'unit':'fen'},
                       {'scope':'other'}, {'model':'other'}, {'price_review':'other'},
                       {'queue':'PERSONA'}, {'job_id':'other'}, {'stage':'distill'},
                       {'unit_digest':'0'*64}, {'request_key':'other'}, {'reserved_nano_cny':True}):
            self.api=BudgetApi(); self.api.tamper_reserve=tamper
            with self.subTest(tamper=tamper), self.assertRaises(ExecutionBudgetUnverified): self.call()
            self.opener.open.assert_not_called()

    def test_dispatch_ack_wrong_id_or_state_cannot_authorize_model_http(self):
        for tamper in ({'id':'other'}, {'status':'RESERVED'}, {'synthetic':False}, {'job_id':'other'}):
            self.api=BudgetApi(); self.api.tamper_dispatch=tamper
            with self.subTest(tamper=tamper), self.assertRaises(ExecutionBudgetUnverified): self.call()
            self.assertEqual(self.outcomes()[-1]['outcome'],'UNCERTAIN')
            self.opener.open.assert_not_called()

    def test_settlement_ack_wrong_id_amount_receipt_or_tag_is_not_confirmed(self):
        for tamper in ({'id':'other'}, {'status':'DISPATCHED'}, {'actual_nano_cny':0},
                       {'actual_nano_cny':804000.0}, {'receipt_key':'other'}, {'is_bill':True}):
            self.api=BudgetApi(); self.api.tamper_outcome=tamper; self.opener.open.reset_mock()
            self.reply(usage_response())
            with self.subTest(tamper=tamper), self.assertRaises(ExecutionBudgetUnverified): self.call()
            self.assertEqual(self.outcomes()[-1]['outcome'],'UNCERTAIN')
            self.assertEqual(self.opener.open.call_count,1)

    def test_short_circuit_without_observation_cannot_exit_guard_successfully(self):
        with execution_context(self.api,'WORK',self.job):
            with self.assertRaises(ExecutionBudgetUnverified):
                with budgeted_call(enabled=True,mode='gateway',model='deepseek-flash',
                        stage='knowledge_answer',request_body={'model':'deepseek-flash'}):
                    pass
        self.assertEqual(self.outcomes()[-1]['outcome'],'UNCERTAIN')
        self.opener.open.assert_not_called()

    def test_estimate_above_reservation_rejection_keeps_hold_and_never_retries_model(self):
        raw = usage_response(); raw['usage']['completion_tokens'] = 200_000
        raw['usage']['total_tokens'] = 200_300; self.reply(raw)
        with self.assertRaises(ExecutionBudgetUnverified): self.call()
        self.assertEqual([body['outcome'] for body in self.outcomes()], ['SETTLED','UNCERTAIN'])
        self.assertGreater(self.outcomes()[0]['actual_nano_cny'],1_000_000_000)
        self.assertEqual(self.opener.open.call_count,1)

    def test_unconfirmed_settlement_ack_is_terminal_without_model_reexecution(self):
        self.api.drop_outcome = 3
        with self.assertRaises(ExecutionBudgetUnverified): self.call()
        settled = [body for body in self.outcomes() if body['outcome']=='SETTLED']
        self.assertEqual(len(settled),3); self.assertTrue(all(body==settled[0] for body in settled))
        self.assertEqual(self.outcomes()[-1]['outcome'],'UNCERTAIN')
        self.assertEqual(self.opener.open.call_count,1)

    def test_dispatch_permission_requires_literal_true_not_a_truthy_value(self):
        for permission in (None, {}, {'dispatch_permitted':1}, {'dispatch_permitted':False}):
            self.api = BudgetApi(); original = self.api.request
            def request(path,payload,timeout=30):
                if path.endswith('/dispatch'):
                    self.api.calls.append((path,deepcopy(payload))); return permission
                return original(path,payload,timeout)
            self.api.request = request
            with self.subTest(permission=permission), self.assertRaises(ExecutionBudgetUnverified): self.call()
            self.assertEqual(self.outcomes()[-1]['outcome'],'UNCERTAIN')
            self.opener.open.assert_not_called()

    def test_prior_unresolved_reservation_prevents_new_model_http(self):
        self.api.reject_reserve = True
        with self.assertRaises(ExecutionBudgetUnverified): self.call()
        self.opener.open.assert_not_called()

    def test_execution_context_is_finally_cleared_after_success_and_failure(self):
        self.call()
        with self.assertRaises(ExecutionBudgetUnverified): self.model.call('synthetic', {})
        self.reply({})
        with self.assertRaises(ExecutionBudgetUnverified): self.call()
        with self.assertRaises(ExecutionBudgetUnverified): self.model.call('synthetic', {})
        self.assertEqual(self.opener.open.call_count, 2)

    def test_worker_and_persona_execution_bind_and_clear_context(self):
        self.api.model = self.model
        worker.Worker.execute(self.api, self.job)
        self.assertEqual(self.api.calls[0][1]['queue'], 'WORK')
        self.assertTrue(any(path.endswith('/complete') for path,_ in self.api.calls))
        with self.assertRaises(ExecutionBudgetUnverified): self.model.call('synthetic', {})
        self.api = BudgetApi(); self.api.model = self.model
        definition={'family':'relationship','name':'synthetic persona','personality':'synthetic','consent':False}
        job={'id':'synthetic-persona','kind':'PERSONA','lease_token':'synthetic-lease','lease_seconds':300,
             'payload':{'definition':definition,'sources':[]}}
        first=usage_response(content='{"analysis":{},"evidence":[]}')
        second=usage_response(content='{"persona_markdown":"# synthetic persona"}'); second['id']='synthetic.receipt:02'
        self.opener.open.side_effect=[Reply(json.dumps(first).encode()),Reply(json.dumps(second).encode())]
        persona.execute(self.api,job)
        reserves=[body for path,body in self.api.calls if path.endswith('/reservations')]
        self.assertEqual([r['stage'] for r in reserves], ['persona_analysis','persona_build'])
        self.assertTrue(all(r['queue']=='PERSONA' for r in reserves))
        self.assertNotEqual(reserves[0]['request_key'],reserves[1]['request_key'])
        self.assertNotEqual(reserves[0]['unit_digest'],reserves[1]['unit_digest'])
        with self.assertRaises(ExecutionBudgetUnverified): self.model.call('synthetic', {})

    def test_receipt_collision_cannot_trigger_a_second_physical_retry(self):
        self.call()
        with self.assertRaises(ExecutionBudgetUnverified): self.call()
        self.assertEqual(self.opener.open.call_count,2)
        self.assertEqual(self.outcomes()[-1]['outcome'],'UNCERTAIN')


if __name__ == '__main__': unittest.main()
