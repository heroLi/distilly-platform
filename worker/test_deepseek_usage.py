"""New synthetic protocol cases; none are provider receipts, account data or calls."""
from copy import deepcopy
from dataclasses import FrozenInstanceError, replace
import unittest
from unittest.mock import patch
from deepseek_usage import (completion_usage, UsageStream, UsageEvidenceUnavailable,
                            public_peak_estimate, PRICE_REVIEW)


def response(*, stream=False, reason='stop', hit=100, miss=200, output=50):
    return {'id': 'synthetic-completion-01', 'model': 'deepseek-flash', 'created': 1792580000,
            'system_fingerprint': 'synthetic_fp_01',
            'object': 'chat.completion.chunk' if stream else 'chat.completion',
            'choices': [{'finish_reason': reason, 'delta' if stream else 'message':
                         {} if stream else {'content': 'SYNTHETIC_BODY_NOT_A_BILL'}}],
            'usage': {'prompt_tokens': hit + miss, 'prompt_cache_hit_tokens': hit,
                      'prompt_cache_miss_tokens': miss, 'prompt_tokens_details': {'cached_tokens': hit},
                      'completion_tokens': output, 'total_tokens': hit + miss + output,
                      'completion_tokens_details': {'reasoning_tokens': min(20, output)}}}


class DeepSeekUsageTests(unittest.TestCase):
    def test_nonstream_observation_is_immutable_metadata_only(self):
        raw = response(); observed = completion_usage(raw)
        self.assertEqual((observed.completion_id, observed.prompt_tokens, observed.completion_tokens),
                         ('synthetic-completion-01', 300, 50))
        self.assertNotIn('SYNTHETIC_BODY', repr(observed))
        raw['usage']['prompt_tokens'] = 999
        self.assertEqual(observed.prompt_tokens, 300)
        with self.assertRaises(FrozenInstanceError): observed.prompt_tokens = 999

    def test_absent_or_malformed_usage_is_unknown_not_zero(self):
        for value in (None, {}, [], 'untrusted'):
            raw = response(); raw['usage'] = value
            with self.subTest(value=value), self.assertRaises(UsageEvidenceUnavailable): completion_usage(raw)
        raw = response(); del raw['usage']
        with self.assertRaises(UsageEvidenceUnavailable): completion_usage(raw)

    def test_counters_are_bounded_nonnegative_integers_without_coercion(self):
        for key in ('prompt_tokens', 'prompt_cache_hit_tokens', 'prompt_cache_miss_tokens',
                    'completion_tokens', 'total_tokens'):
            for value in (True, False, -1, 1.5, '100', None, 2**63):
                raw = response(); raw['usage'][key] = value
                with self.subTest(key=key, value=value), self.assertRaises(UsageEvidenceUnavailable): completion_usage(raw)

    def test_cache_and_total_arithmetic_must_agree(self):
        for key in ('prompt_tokens', 'total_tokens', 'prompt_cache_hit_tokens', 'prompt_cache_miss_tokens'):
            raw = response(); raw['usage'][key] += 1
            with self.subTest(key=key), self.assertRaises(UsageEvidenceUnavailable): completion_usage(raw)
        for details in (None, {}, {'cached_tokens': 99}, {'cached_tokens': True}):
            raw = response(); raw['usage']['prompt_tokens_details'] = details
            with self.subTest(details=details), self.assertRaises(UsageEvidenceUnavailable): completion_usage(raw)

    def test_reasoning_is_a_breakdown_never_an_extra_bill(self):
        raw = response(); first = public_peak_estimate(completion_usage(raw))
        raw['usage']['completion_tokens_details']['reasoning_tokens'] = 50
        self.assertEqual(first, public_peak_estimate(completion_usage(raw)))
        del raw['usage']['completion_tokens_details']
        self.assertIsNone(completion_usage(raw).reasoning_tokens)
        for value in (51, -1, True, '20'):
            raw['usage']['completion_tokens_details'] = {'reasoning_tokens': value}
            with self.subTest(value=value), self.assertRaises(UsageEvidenceUnavailable): completion_usage(raw)

    def test_wrong_model_identifier_fingerprint_and_shape_do_not_yield_evidence(self):
        for key, value in [('id', ''), ('id', 'untrusted\nidentifier'), ('id', 'x' * 161),
                           ('created', True), ('system_fingerprint', None), ('model', 'deepseek-v4-pro'),
                           ('object', 'chat.completion.chunk'), ('choices', []), ('choices', [None]),
                           ('choices', [{'finish_reason': []}])]:
            raw = response(); raw[key] = value
            with self.subTest(key=key), self.assertRaises(UsageEvidenceUnavailable): completion_usage(raw)

    def test_truncation_abort_and_filtering_are_not_zero_cost(self):
        for reason in ('length', 'aborted', 'content_filter', 'insufficient_system_resource'):
            raw = response(reason=reason)
            observed = completion_usage(raw)
            self.assertEqual(observed.finish_reason, reason)
            self.assertGreater(public_peak_estimate(observed)['estimated_nano_cny'], 0)

    def test_stream_cumulative_final_and_duplicate_are_not_summed(self):
        collector = UsageStream(); chunk = response(stream=True)
        first = deepcopy(chunk); first['usage'] = None; first['choices'][0]['finish_reason'] = None
        first['choices'][0]['delta'] = {'content': 'SYNTHETIC_BODY_NOT_A_BILL'}
        collector.feed(first); collector.feed(chunk); collector.feed(deepcopy(chunk))
        observed = collector.finish(done_received=True)
        self.assertEqual(observed.total_tokens, 350)
        self.assertEqual(collector.finish(done_received=True), observed)
        self.assertEqual(public_peak_estimate(observed)['estimated_nano_cny'], 804000)

    def test_stream_disconnect_missing_usage_and_unconfirmed_done_remain_unresolved(self):
        for has_usage, done in ((False, True), (True, False), (True, 1), (False, False)):
            collector = UsageStream()
            if has_usage: collector.feed(response(stream=True))
            with self.subTest(has_usage=has_usage, done=done), self.assertRaises(UsageEvidenceUnavailable):
                collector.finish(done_received=done)
            with self.assertRaises(UsageEvidenceUnavailable): collector.finish(done_received=True)

    def test_stream_changed_identity_conflicting_usage_and_late_content_poison_collector(self):
        for key, value in [('id', 'synthetic-other'), ('model', 'deepseek-v4-pro'),
                           ('system_fingerprint', 'synthetic_other'), ('created', 1792580001),
                           ('usage', None), ('usage', response(output=51)['usage']), ('error', {})]:
            collector = UsageStream(); collector.feed(response(stream=True))
            changed = response(stream=True); changed[key] = value
            with self.subTest(key=key), self.assertRaises(UsageEvidenceUnavailable): collector.feed(changed)
            with self.assertRaises(UsageEvidenceUnavailable): collector.finish(done_received=True)
        collector = UsageStream(); collector.feed(response(stream=True)); collector.finish(done_received=True)
        with self.assertRaises(UsageEvidenceUnavailable): collector.feed(response(stream=True))
        with self.assertRaises(UsageEvidenceUnavailable): collector.finish(done_received=True)

    def test_terminal_usage_frame_cannot_carry_new_text_reasoning_or_tools(self):
        for delta in ({'content': 'late synthetic text'}, {'reasoning_content': 'late synthetic reasoning'},
                      {'tool_calls': [{'id': 'synthetic_tool'}]}, {'function_call': {}}, {'role': 'user'}):
            collector = UsageStream(); collector.feed(response(stream=True))
            changed = response(stream=True); changed['choices'][0]['delta'] = delta
            with self.subTest(delta=delta), self.assertRaises(UsageEvidenceUnavailable): collector.feed(changed)
            with self.assertRaises(UsageEvidenceUnavailable): collector.finish(done_received=True)

    def test_peak_review_math_uses_subfen_precision_and_never_authorizes(self):
        quote = public_peak_estimate(completion_usage(response(hit=1, miss=0, output=0)))
        self.assertEqual(quote['estimated_nano_cny'], 40)
        self.assertEqual(quote['estimated_ceil_fen'], 1)
        self.assertEqual(quote['price_review'], PRICE_REVIEW)
        self.assertIs(quote['provider_receipt_authenticated'], False)
        self.assertIs(quote['is_bill'], False)
        self.assertIs(quote['real_egress_authorized'], False)
        zero = public_peak_estimate(completion_usage(response(hit=0, miss=0, output=0)))
        self.assertEqual(zero['estimated_ceil_fen'], 0)
        self.assertIs(zero['is_bill'], False)
        self.assertEqual(public_peak_estimate(completion_usage(response(hit=0, miss=1_000_000, output=0)))['estimated_ceil_fen'], 200)
        self.assertEqual(public_peak_estimate(completion_usage(response(hit=0, miss=0, output=1_000_000)))['estimated_ceil_fen'], 800)
        observed = completion_usage(response())
        for bad in (replace(observed, cache_hit_tokens=-1), replace(observed, total_tokens=0),
                    replace(observed, model='other'), replace(observed, reasoning_tokens=999)):
            with self.assertRaises(UsageEvidenceUnavailable): public_peak_estimate(bad)

    def test_pure_adapter_does_not_access_environment_credentials_network_or_files(self):
        with patch('os.getenv', side_effect=AssertionError('unexpected environment access')), \
             patch('builtins.open', side_effect=AssertionError('unexpected file access')), \
             patch('urllib.request.build_opener', side_effect=AssertionError('unexpected network')):
            self.assertFalse(public_peak_estimate(completion_usage(response()))['real_egress_authorized'])


if __name__ == '__main__': unittest.main()
