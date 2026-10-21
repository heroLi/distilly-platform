#!/usr/bin/env python3
"""Guards against changing the acceptance population or reporting mock scores."""
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest

from text_scenarios_e2e import ContractFailure, FIXTURES, load_contract


class FrozenContractTest(unittest.TestCase):
    def mutate(self, change, filename='text_scenarios_v1.json', resign=True):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            for name in ('text_scenarios_v1.json', 'text_scenarios_rubric_v1.json', 'text_scenarios_v1.lock.json'):
                shutil.copy2(FIXTURES / name, folder / name)
            path = folder / filename
            document = json.loads(path.read_text())
            change(document)
            path.write_text(json.dumps(document))
            if resign:
                lock_path = folder / 'text_scenarios_v1.lock.json'
                lock = json.loads(lock_path.read_text())
                lock['sha256'][filename] = hashlib.sha256(path.read_bytes()).hexdigest()
                lock_path.write_text(json.dumps(lock))
            return load_contract(folder)

    def test_frozen_population(self):
        suite, rubric, _ = load_contract()
        self.assertEqual(len(suite['cases']), 30)
        self.assertEqual(rubric['real_quality_status'], 'NOT_MEASURED')

    def test_unacknowledged_fixture_edit_fails(self):
        with self.assertRaisesRegex(ContractFailure, 'freeze_hash_mismatch'):
            self.mutate(lambda d: d['cases'].pop(), resign=False)

    def test_removed_failure_cannot_shrink_population(self):
        with self.assertRaisesRegex(ContractFailure, 'case_count'):
            self.mutate(lambda d: d['cases'].pop())

    def test_duplicate_case_cannot_replace_missing_case(self):
        with self.assertRaisesRegex(ContractFailure, 'duplicate_case'):
            self.mutate(lambda d: d['cases'].__setitem__(0, d['cases'][1]))

    def test_missing_category_rejected(self):
        with self.assertRaisesRegex(ContractFailure, 'category_coverage'):
            self.mutate(lambda d: d['cases'][0].__setitem__('category', 'celebration'))

    def test_one_turn_is_not_multi_turn(self):
        with self.assertRaisesRegex(ContractFailure, 'multi_turn_required'):
            self.mutate(lambda d: d['cases'][0].__setitem__('steps', d['cases'][0]['steps'][:1]))

    def test_unknown_action_cannot_silently_pass(self):
        with self.assertRaisesRegex(ContractFailure, 'unknown_operation'):
            self.mutate(lambda d: d['cases'][0]['steps'].append({'id': 'extra', 'op': 'pretend_pass'}))

    def test_mock_quality_score_forbidden(self):
        with self.assertRaisesRegex(ContractFailure, 'mock_scoring_forbidden'):
            self.mutate(lambda d: d.__setitem__('mock_quality_scores', 5), 'text_scenarios_rubric_v1.json')


if __name__ == '__main__':
    unittest.main()
