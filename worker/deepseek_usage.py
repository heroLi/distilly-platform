"""Offline DeepSeek text-usage preparation, never billing or egress authority.

No environment, credentials, network or storage access. Responses supplied here
are untrusted evidence. Missing/conflicting evidence must retain a future hold;
it must never be interpreted as a zero bill or trigger an automatic retry.
"""
from dataclasses import dataclass
import re

MODEL = 'deepseek-flash'
PRICE_OBSERVED_ON = '2026-10-21'
PRICE_SOURCE = 'https://api-docs.deepseek.com/zh-cn/quick_start/pricing/'
# Local review label, not a supplier-signed price version or future price guarantee.
PRICE_REVIEW = 'deepseek-flash-public-20261021-peak-ceiling-v1'
_TOKEN = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}\Z')


class UsageEvidenceUnavailable(ValueError):
    """A receipt cannot be inferred; any future reservation remains unresolved."""
    failure_code = 'MODEL_USAGE_UNVERIFIED'


def _invalid():
    # Never include response values, material, IDs or error bodies in errors.
    raise UsageEvidenceUnavailable('Unverified text usage; retain any reservation')


def _count(value):
    if type(value) is not int or not 0 <= value <= 2**63 - 1:
        _invalid()
    return value


def _identifier(value):
    if not isinstance(value, str) or not _TOKEN.fullmatch(value):
        _invalid()
    return value


@dataclass(frozen=True)
class UsageObservation:
    completion_id: str
    model: str
    created: int
    system_fingerprint: str
    prompt_tokens: int
    cache_hit_tokens: int
    cache_miss_tokens: int
    completion_tokens: int
    total_tokens: int
    reasoning_tokens: int | None
    finish_reason: str


def _observation(frame):
    if not isinstance(frame, dict) or 'error' in frame or frame.get('model') != MODEL:
        _invalid()
    completion_id = _identifier(frame.get('id'))
    fingerprint = _identifier(frame.get('system_fingerprint'))
    created = _count(frame.get('created'))
    choices = frame.get('choices')
    if not isinstance(choices, list) or len(choices) != 1 or not isinstance(choices[0], dict):
        _invalid()
    reason = choices[0].get('finish_reason')
    # A failed/truncated generation can still consume tokens. It is not a zero bill.
    if not isinstance(reason, str) or reason not in {'stop', 'length', 'content_filter', 'insufficient_system_resource', 'aborted'}:
        _invalid()
    usage = frame.get('usage')
    if not isinstance(usage, dict):
        _invalid()
    prompt, completion, total, hit, miss = (_count(usage.get(key)) for key in (
        'prompt_tokens', 'completion_tokens', 'total_tokens', 'prompt_cache_hit_tokens', 'prompt_cache_miss_tokens'))
    if prompt != hit + miss or total != prompt + completion:
        _invalid()
    details = usage.get('prompt_tokens_details')
    if not isinstance(details, dict) or _count(details.get('cached_tokens')) != hit:
        _invalid()
    reasoning = None
    if 'completion_tokens_details' in usage:
        details = usage['completion_tokens_details']
        if not isinstance(details, dict):
            _invalid()
        if 'reasoning_tokens' in details:
            reasoning = _count(details['reasoning_tokens'])
            if reasoning > completion:
                _invalid()
    return UsageObservation(completion_id, MODEL, created, fingerprint, prompt, hit, miss,
                            completion, total, reasoning, reason)


def completion_usage(response):
    """Validate one nonstreaming response without reading its generated text."""
    if not isinstance(response, dict) or response.get('object') != 'chat.completion':
        _invalid()
    return _observation(response)


class UsageStream:
    """Cumulative usage is observed once, not summed across repeated SSE frames.

    A caller must feed every decoded frame and explicitly confirm the DONE marker.
    A failed collector stays failed; finalizing a partial stream cannot mint evidence.
    """
    def __init__(self):
        self._identity = None
        self._observation = None
        self._failed = False
        self._done = False

    def feed(self, frame):
        if self._failed or self._done:
            self._failed = True
            _invalid()
        try:
            if not isinstance(frame, dict) or 'error' in frame or frame.get('object') != 'chat.completion.chunk':
                _invalid()
            identity = (_identifier(frame.get('id')), frame.get('model'), _count(frame.get('created')),
                        _identifier(frame.get('system_fingerprint')))
            if identity[1] != MODEL or (self._identity is not None and identity != self._identity):
                _invalid()
            self._identity = identity
            choices = frame.get('choices')
            if (not isinstance(choices, list) or len(choices) != 1 or not isinstance(choices[0], dict)
                    or not isinstance(choices[0].get('delta'), dict)):
                _invalid()
            reason = choices[0].get('finish_reason')
            if frame.get('usage') is not None:
                delta = choices[0]['delta']
                if (not set(delta).issubset({'content', 'reasoning_content', 'tool_calls', 'role'})
                        or delta.get('content') not in (None, '')
                        or delta.get('reasoning_content') not in (None, '')
                        or delta.get('tool_calls') not in (None, [])
                        or delta.get('role') not in (None, 'assistant')):
                    _invalid()
                observation = _observation(frame)
                if self._observation is not None and observation != self._observation:
                    _invalid()
                self._observation = observation
            elif self._observation is not None or reason is not None:
                _invalid()  # No content may follow the final usage frame.
        except UsageEvidenceUnavailable:
            self._failed = True
            raise

    def finish(self, *, done_received):
        if self._failed or done_received is not True or self._observation is None:
            self._failed = True
            _invalid()
        self._done = True
        return self._observation


def public_peak_estimate(observation):
    """Exact integer arithmetic, rounded UP to fen for a local review estimate.

    Current public peak CNY / 1M tokens: hit .04, miss 2, output 8.
    Reasoning is a completion breakdown, not another charge added to completion.
    This is post-response estimation, not a preflight bound. It does not
    authenticate a receipt, decide time bands, or release a hold.
    """
    if not isinstance(observation, UsageObservation):
        _invalid()
    # Revalidate numeric fields even for an explicitly constructed dataclass.
    hit, miss, output = (_count(value) for value in (observation.cache_hit_tokens,
                         observation.cache_miss_tokens, observation.completion_tokens))
    if (observation.model != MODEL or _count(observation.prompt_tokens) != hit + miss
            or _count(observation.total_tokens) != hit + miss + output
            or (observation.reasoning_tokens is not None and _count(observation.reasoning_tokens) > output)):
        _invalid()
    nano_cny = hit * 40 + miss * 2000 + output * 8000
    return {'contract': 'UNVERIFIED_DEEPSEEK_TEXT_ESTIMATE_V1', 'currency': 'CNY',
            'price_review': PRICE_REVIEW, 'price_observed_on': PRICE_OBSERVED_ON, 'price_source': PRICE_SOURCE,
            'estimated_nano_cny': nano_cny, 'estimated_ceil_fen': (nano_cny + 9_999_999) // 10_000_000,
            'provider_receipt_authenticated': False, 'is_bill': False, 'real_egress_authorized': False}
