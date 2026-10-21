"""Task-bound synthetic text execution accounting; never real egress authority.

Only the Model's existing literal-loopback gate may reach this adapter. Budget
RPCs are replayable with a frozen payload; dispatch and model HTTP are one-shot.
No prompts, response bodies, credentials, or lease tokens are logged or persisted
here. The server is authoritative for global holds, leases, and receipt reuse.
"""
from contextlib import contextmanager
from contextvars import ContextVar
import hashlib
import json
import re
import time
import urllib.error
import uuid

from deepseek_usage import MODEL, PRICE_REVIEW, UsageStream, completion_usage, public_peak_estimate

_CONTEXT = ContextVar('synthetic_text_execution_context', default=None)
_ID = re.compile(r'[A-Za-z0-9_-]{1,160}\Z')
_STAGES = {('WORK', 'DISTILL'): {'distill'}, ('WORK', 'ANSWER'): {'knowledge_answer'},
           ('PERSONA', 'PERSONA'): {'persona_analysis', 'persona_build'},
           ('PERSONA', 'CHAT'): {'default'}}
_RESERVATION = 1_000_000_000


class ExecutionBudgetUnverified(RuntimeError):
    """No additional physical model attempt is authorized by this exception."""
    failure_code = 'MODEL_EXECUTION_BUDGET_UNVERIFIED'


def _invalid():
    raise ExecutionBudgetUnverified('Synthetic execution accounting unverified; retain any hold')


@contextmanager
def execution_context(worker, queue, job):
    # Always enter/clear at the task boundary. Default mock jobs do not create or
    # validate a budget, and old test doubles need no new model attributes.
    context = {'worker': worker, 'queue': queue, 'job_id': job.get('id'),
               'kind': job.get('kind'), 'lease_token': job.get('lease_token')}
    token = _CONTEXT.set(context)
    try:
        yield
    finally:
        _CONTEXT.reset(token)


def _replayable_rpc(worker, path, payload):
    frozen = json.loads(json.dumps(payload))
    for attempt in range(3):
        try:
            return worker.request(path, json.loads(json.dumps(frozen)), timeout=10)
        except urllib.error.HTTPError as error:
            error.close()
            if error.code not in (408, 425, 429, 500, 502, 503, 504):
                raise
        except (OSError, ValueError):
            pass
        if attempt < 2:
            time.sleep(0.01 * (attempt + 1))
    _invalid()


class _Invocation:
    def __init__(self, mode, model, stage, request_body, image):
        context = _CONTEXT.get()
        if (mode != 'gateway' or model != MODEL or image or not isinstance(context, dict)
                or stage not in _STAGES.get((context['queue'], context['kind']), set())
                or not isinstance(context['job_id'], str) or not context['job_id']
                or not isinstance(context['lease_token'], str) or not context['lease_token']):
            _invalid()
        self.worker = context['worker']
        self.lease_token = context['lease_token']
        self.id = None
        self.settled = False
        self.stream = UsageStream()
        # One digest per physical call, including exact messages, model, and
        # output policy. Only this digest is sent to the budget endpoint.
        encoded = json.dumps({'stage': stage, 'request': request_body}, ensure_ascii=False,
                             sort_keys=True, separators=(',', ':')).encode('utf-8')
        self.payload = {'queue': context['queue'], 'job_id': context['job_id'],
                        'lease_token': self.lease_token, 'stage': stage,
                        'unit_digest': hashlib.sha256(encoded).hexdigest(),
                        'request_key': uuid.uuid4().hex, 'model': model}

    def _validate_ack(self, result, status, *, known_id=True):
        if not isinstance(result, dict):
            _invalid()
        identity = result.get('id')
        if (not isinstance(identity, str) or not _ID.fullmatch(identity)
                or (known_id and identity != self.id)
                or result.get('status') != status
                or result.get('contract') != 'SYNTHETIC_TEXT_EXECUTION_BUDGET_V1'
                or result.get('scope') != 'INSTANCE_DATABASE_GLOBAL'
                or result.get('currency') != 'CNY' or result.get('unit') != 'nano_cny'
                or result.get('synthetic') is not True or result.get('is_bill') is not False
                or result.get('real_egress_authorized') is not False
                or result.get('price_review') != PRICE_REVIEW
                or type(result.get('reserved_nano_cny')) is not int
                or result['reserved_nano_cny'] != _RESERVATION):
            _invalid()
        for key in ('queue', 'job_id', 'stage', 'unit_digest', 'request_key', 'model'):
            if result.get(key) != self.payload[key]:
                _invalid()
        return identity

    def reserve(self):
        result = _replayable_rpc(self.worker, '/internal/text-budget/reservations', self.payload)
        # Do not direct cleanup at an ID from a response bound to another task.
        self.id = self._validate_ack(result, 'RESERVED', known_id=False)

    def dispatch(self):
        # Deliberately no replay wrapper: losing this acknowledgement must never
        # cause a second dispatch or a model request on an uncertain grant.
        try:
            result = self.worker.request(self._path('dispatch'), {'lease_token': self.lease_token}, timeout=10)
        except urllib.error.HTTPError as error:
            error.close()
            raise
        self._validate_ack(result, 'DISPATCHED')
        if result.get('dispatch_permitted') is not True:
            _invalid()

    def _path(self, suffix):
        return '/internal/text-budget/reservations/' + self.id + '/' + suffix

    def observe_complete(self, frame):
        self._settle(completion_usage(frame))

    def feed_stream(self, frame):
        self.stream.feed(frame)

    def finish_stream(self):
        self._settle(self.stream.finish(done_received=True))

    def _settle(self, observation):
        estimate = public_peak_estimate(observation)
        payload = {'lease_token': self.lease_token, 'outcome': 'SETTLED',
                   'evidence': 'SYNTHETIC_USAGE_V1',
                   # Provider IDs can contain punctuation disallowed in a budget
                   # key. Stable global hashing preserves collision/replay checks.
                   'receipt_key': hashlib.sha256(observation.completion_id.encode('utf-8')).hexdigest(),
                   'actual_nano_cny': estimate['estimated_nano_cny']}
        result = _replayable_rpc(self.worker, self._path('outcomes'), payload)
        self._validate_ack(result, 'SETTLED')
        if (type(result.get('actual_nano_cny')) is not int
                or result['actual_nano_cny'] != payload['actual_nano_cny']
                or result.get('receipt_key') != payload['receipt_key']):
            _invalid()
        self.settled = True

    def unknown(self):
        if self.id is None or self.settled:
            return
        try:
            _replayable_rpc(self.worker, self._path('outcomes'),
                            {'lease_token': self.lease_token, 'outcome': 'UNCERTAIN',
                             'evidence': 'SYNTHETIC_UNKNOWN'})
        except Exception:
            # No acknowledgement is evidence of zero use. The persistent hold
            # remains authoritative even if cleanup cannot reach the API.
            pass


@contextmanager
def budgeted_call(*, enabled, mode, model, stage, request_body, image=False, before_dispatch=None):
    if enabled is not True:
        yield None
        return
    invocation = _Invocation(mode, model, stage, request_body, image)
    try:
        invocation.reserve()
        if before_dispatch:
            before_dispatch()
        invocation.dispatch()
        yield invocation
        if not invocation.settled:
            _invalid()
    except Exception as error:
        if invocation.settled:
            # A verified synthetic charge and business acceptance are separate.
            # Preserve precise output-limit/JSON errors after known settlement.
            raise
        invocation.unknown()
        if isinstance(error, ExecutionBudgetUnverified):
            raise
        raise ExecutionBudgetUnverified('Synthetic execution accounting unverified; retain any hold') from error
