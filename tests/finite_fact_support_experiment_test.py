"""Exposed formal-fixture regressions, not Chinese/holdout/world-truth evaluation."""
import copy
import itertools
import unittest

from finite_fact_support_experiment import evaluate


def dataset():
    return {'contract': 'SYNTHETIC_LAMP_FACTS_V1', 'synthetic': True,
            'sources': [{'scope_id': 'synthetic-scope-a', 'source_id': 'synthetic-source-a',
                         'revision': 2, 'withdrawn': False}],
            'facts': [{'fact_id': 'synthetic-fact-a', 'scope_id': 'synthetic-scope-a',
                       'source_id': 'synthetic-source-a', 'source_revision': 2,
                       'lamp_id': 'synthetic-lamp-a', 'predicate': 'is_on', 'value': True,
                       'confirmation': 'CONFIRMED', 'valid_from': 100, 'valid_until': 200}]}


def query(**changes):
    value = {'scope_id': 'synthetic-scope-a', 'lamp_id': 'synthetic-lamp-a',
             'predicate': 'is_on', 'expected_value': True, 'at': 150}
    return {**value, **changes}


class FiniteFactExperimentTests(unittest.TestCase):
    def decide(self, data=None, **changes):
        return evaluate(dataset() if data is None else data, query(**changes), enabled=True)

    def test_default_closed_does_not_inspect_inputs(self):
        class Unreadable(dict):
            def keys(self):
                raise AssertionError('disabled path read data')
        self.assertEqual(evaluate(Unreadable(), Unreadable()),
                         {'status': 'DISABLED', 'fact_ids': (), 'source_versions': ()})

    def test_explicit_positive_support_has_current_lineage(self):
        self.assertEqual(self.decide(), {'status': 'SUPPORTED',
            'fact_ids': ('synthetic-fact-a',),
            'source_versions': (('synthetic-scope-a', 'synthetic-source-a', 2),)})

    def test_opposite_query_is_refuted_not_unknown(self):
        self.assertEqual(self.decide(expected_value=False)['status'], 'REFUTED')

    def test_explicit_negative_support_and_positive_refutation(self):
        data = dataset(); data['facts'][0]['value'] = False
        self.assertEqual(self.decide(data, expected_value=False)['status'], 'SUPPORTED')
        self.assertEqual(self.decide(data)['status'], 'REFUTED')

    def test_absence_never_implies_negative_fact(self):
        data = dataset(); data['facts'] = []
        for value in (True, False):
            with self.subTest(value=value):
                self.assertEqual(self.decide(data, expected_value=value),
                                 {'status': 'UNKNOWN', 'fact_ids': (), 'source_versions': ()})

    def test_half_open_time_boundaries(self):
        for at, expected in [(99, 'UNKNOWN'), (100, 'SUPPORTED'), (199, 'SUPPORTED'),
                             (200, 'UNKNOWN'), (201, 'UNKNOWN')]:
            with self.subTest(at=at):
                self.assertEqual(self.decide(at=at)['status'], expected)

    def test_pending_rejected_and_revoked_are_not_evidence(self):
        for state in ('PENDING', 'REJECTED', 'REVOKED'):
            data = dataset(); data['facts'][0]['confirmation'] = state
            with self.subTest(state=state):
                self.assertEqual(self.decide(data)['status'], 'UNKNOWN')

    def test_source_withdrawal_and_missing_source_are_unknown(self):
        data = dataset(); data['sources'][0]['withdrawn'] = True
        self.assertEqual(self.decide(data)['status'], 'UNKNOWN')
        data['sources'] = []
        self.assertEqual(self.decide(data)['status'], 'UNKNOWN')

    def test_source_revision_change_requires_explicit_new_confirmation(self):
        data = dataset(); data['sources'][0]['revision'] = 3
        self.assertEqual(self.decide(data)['status'], 'UNKNOWN')
        data['facts'][0].update(source_revision=3, confirmation='PENDING')
        self.assertEqual(self.decide(data)['status'], 'UNKNOWN')
        data['facts'][0]['confirmation'] = 'CONFIRMED'
        self.assertEqual(self.decide(data)['source_versions'],
                         (('synthetic-scope-a', 'synthetic-source-a', 3),))

    def test_future_revision_is_not_evidence_either(self):
        data = dataset(); data['facts'][0]['source_revision'] = 3
        self.assertEqual(self.decide(data)['status'], 'UNKNOWN')

    def test_conflict_is_not_resolved_by_order_or_query_polarity(self):
        data = dataset()
        data['facts'].append({**data['facts'][0], 'fact_id': 'synthetic-fact-b', 'value': False})
        for expected in (True, False):
            result = self.decide(data, expected_value=expected)
            self.assertEqual(result['status'], 'CONFLICT')
            self.assertEqual(result['fact_ids'], ('synthetic-fact-a', 'synthetic-fact-b'))
        reverse = copy.deepcopy(data); reverse['facts'].reverse()
        self.assertEqual(self.decide(data), self.decide(reverse))

    def test_expired_or_revoked_opposite_does_not_create_conflict(self):
        for updates in ({'valid_until': 150}, {'confirmation': 'REVOKED'}, {'source_revision': 1}):
            data = dataset(); data['facts'].append({**data['facts'][0], 'fact_id': 'synthetic-fact-b',
                                                  'value': False, **updates})
            with self.subTest(updates=updates):
                self.assertEqual(self.decide(data)['status'], 'SUPPORTED')

    def test_other_lamp_scope_or_predicate_never_supplies_an_answer(self):
        for changes in ({'scope_id': 'synthetic-scope-b'}, {'lamp_id': 'synthetic-lamp-b'},
                        {'predicate': 'brightness'}, {'lamp_id': 'synthetic-lamp-c'}):
            with self.subTest(changes=changes):
                self.assertEqual(self.decide(**changes),
                                 {'status': 'UNKNOWN', 'fact_ids': (), 'source_versions': ()})

    def test_foreign_scope_source_cannot_authorize_local_fact(self):
        data = dataset(); data['sources'][0]['scope_id'] = 'synthetic-scope-b'
        self.assertEqual(self.decide(data)['status'], 'UNKNOWN')

    def test_nonoverlapping_intervals_do_not_conflict(self):
        data = dataset(); data['facts'].append({**data['facts'][0], 'fact_id': 'synthetic-fact-b',
                                              'value': False, 'valid_from': 200, 'valid_until': 300})
        self.assertEqual(self.decide(data, at=199)['status'], 'SUPPORTED')
        self.assertEqual(self.decide(data, at=200)['status'], 'REFUTED')

    def test_duplicate_fact_or_source_identity_is_rejected(self):
        for field in ('facts', 'sources'):
            data = dataset(); data[field].append(dict(data[field][0]))
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.decide(data)

    def test_unstructured_text_or_support_flags_are_rejected(self):
        for key in ('body', 'quote', 'supported', 'score', 'answer'):
            data = dataset(); data['facts'][0][key] = 'untrusted'
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.decide(data)
        with self.assertRaises(ValueError):
            evaluate(dataset(), {**query(), 'message': 'free text'}, enabled=True)

    def test_boolean_and_integer_types_are_strict(self):
        for field, value in [('value', 'false'), ('value', 1), ('valid_from', True),
                             ('source_revision', True), ('valid_until', 100), ('valid_from', -1),
                             ('valid_until', 4102444801)]:
            data = dataset(); data['facts'][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                self.decide(data)
        for changes in ({'expected_value': 1}, {'at': True}, {'at': -1}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.decide(**changes)

    def test_manifest_and_sources_are_explicitly_synthetic_and_well_formed(self):
        for changes in ({'synthetic': False}, {'synthetic': 1}, {'contract': 'OTHER'}, {'extra': True}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.decide({**dataset(), **changes})
        for changes in ({'revision': 0}, {'revision': True}, {'withdrawn': 0},
                        {'source_id': 'ordinary-source'}, {'extra': True}):
            data = dataset(); data['sources'][0].update(changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.decide(data)

    def test_gate_cannot_be_enabled_by_truthy_strings(self):
        for value in ('true', 1, None):
            with self.subTest(value=value), self.assertRaises(ValueError):
                evaluate(dataset(), query(), enabled=value)

    def test_evaluation_is_pure_and_result_not_a_free_answer(self):
        data = dataset(); before = copy.deepcopy(data); question = query(); original = dict(question)
        result = evaluate(data, question, enabled=True)
        self.assertEqual(data, before); self.assertEqual(question, original)
        self.assertEqual(set(result), {'status', 'fact_ids', 'source_versions'})

    def test_exhaustive_applicability_and_polarity_truth_table(self):
        # 2 values x 2 queries x 4 confirmation states x 2 source states
        # x 3 revisions x 5 time positions = 480 explicit symbolic cases.
        for value, wanted, state, withdrawn, revision, at in itertools.product(
                (True, False), (True, False), ('CONFIRMED', 'PENDING', 'REJECTED', 'REVOKED'),
                (True, False), (1, 2, 3), (99, 100, 150, 199, 200)):
            data = dataset(); data['sources'][0]['withdrawn'] = withdrawn
            data['facts'][0].update(value=value, confirmation=state, source_revision=revision)
            applicable = state == 'CONFIRMED' and not withdrawn and revision == 2 and 100 <= at < 200
            expected = ('SUPPORTED' if value == wanted else 'REFUTED') if applicable else 'UNKNOWN'
            with self.subTest(value=value, wanted=wanted, state=state, withdrawn=withdrawn,
                              revision=revision, at=at):
                result = self.decide(data, expected_value=wanted, at=at)
                self.assertEqual(result['status'], expected)
                self.assertEqual(bool(result['fact_ids']), applicable)

    def test_bounded_input_collections_and_exact_shapes(self):
        for field, value in [('sources', {}), ('facts', None), ('sources', [None]),
                             ('facts', [None]), ('sources', [{}] * 129), ('facts', [{}] * 129)]:
            data = dataset(); data[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.decide(data)
        for field, value in [('predicate', 'brightness'), ('lamp_id', 'synthetic-lamp-c'),
                             ('confirmation', 'TRUE'), ('fact_id', 'not-synthetic')]:
            data = dataset(); data['facts'][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.decide(data)


if __name__ == '__main__':
    unittest.main()
