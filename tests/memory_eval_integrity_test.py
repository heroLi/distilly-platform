#!/usr/bin/env python3
"""Integrity metadata only: no new memory queries, answers or holdout samples."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

TOOL = Path(__file__).resolve().parents[1] / 'scripts/memory_eval_integrity.py'
loader = importlib.util.spec_from_file_location('memory_eval_integrity', TOOL)
integrity = importlib.util.module_from_spec(loader)
loader.loader.exec_module(integrity)


class IntegrityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name, value in {'A.py': '# metadata placeholder A; never executed\n',
                            'B.py': '# metadata placeholder B; never executed\n',
                            'scorer.py': '# metadata placeholder; never executed\n'}.items():
            (self.root / name).write_text(value)
        self.put('configuration.json', {'mode': 'OFFLINE_SHADOW', 'model_calls': 0})
        self.put('inventory.json', {'split': 'development', 'cases': [{'id': 'META_1'}, {'id': 'META_2'}]})
        self.git('init', '-q')
        self.git('add', '.')
        self.git('-c', 'user.name=IntegrityFixture', '-c', 'user.email=integrity@example.invalid',
                 '-c', 'commit.gpgsign=false', 'commit', '-qm', 'metadata fixtures')
        self.commit = self.git('rev-parse', 'HEAD').strip()
        self.spec = {'schema_version': 1, 'scope': 'OFFLINE_SHADOW', 'dataset_kind': 'development',
                     'implementations': {side: {'commit': self.commit, 'sources': [side + '.py']}
                                         for side in ('A', 'B')},
                     'configuration': ['configuration.json'], 'dataset': ['inventory.json'],
                     'scorer': ['scorer.py'], 'case_inventory': 'inventory.json'}
        self.manifest = integrity.freeze(self.root, self.spec)
        self.pin = integrity.write_new(self.root, 'manifest.json', self.manifest)
        self.run = {'schema_version': 1, 'manifest_sha256': self.pin, 'run_id': 'METADATA_ONLY',
                    'status': 'COMPLETED', 'sides': {}}
        for side in ('A', 'B'):
            result = {'schema_version': 1, 'side': side, 'manifest_sha256': self.pin,
                      'case_results': [{'id': value, 'status': 'COMPLETED'}
                      for value in ('META_1', 'META_2')], 'metrics': {'metadata_count': 2, 'undefined': None}}
            self.put(side + '-result.json', result)
            self.run['sides'][side] = {
                'commit': self.commit,
                'status': 'COMPLETED', 'exit_code': 0,
                'implementation_digest': integrity.digest(integrity.canonical(self.manifest['implementations'][side])),
                **{role + '_digest': integrity.digest(integrity.canonical(self.manifest[role]))
                   for role in ('configuration', 'dataset', 'scorer')},
                'result': {'path': side + '-result.json', 'sha256': self.hash(side + '-result.json')}}
        self.put('run.json', self.run)

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], text=True,
                                       stderr=subprocess.DEVNULL)

    def put(self, name, value):
        (self.root / name).write_text(json.dumps(value) + '\n')

    def hash(self, name):
        return integrity.digest((self.root / name).read_bytes())

    def audit(self):
        return integrity.audit(self.root, 'manifest.json', self.pin, 'run.json')

    def reject(self, code=None):
        value = self.audit()
        self.assertEqual(value['status'], 'REJECTED')
        if code:
            self.assertEqual(value['error_code'], code)
        return value

    def replace_result(self, change):
        result = json.loads((self.root / 'B-result.json').read_text())
        change(result)
        self.put('B-result.json', result)
        self.run['sides']['B']['result']['sha256'] = self.hash('B-result.json')
        self.put('run.json', self.run)

    def test_complete_bound_metadata_passes_without_quality_or_blindness_claim(self):
        receipt = self.audit()
        self.assertEqual(receipt['status'], 'INTEGRITY_VERIFIED')
        self.assertEqual(receipt['sides']['A']['completed_cases'], 2)
        self.assertEqual(receipt['quality_status'], 'NOT_ASSESSED')
        self.assertEqual(receipt['blinding_status'], 'NOT_ESTABLISHED_BY_INTEGRITY_CHECK')
        self.assertNotIn('metrics', receipt['sides']['A'])

    def test_full_commit_and_nonempty_source_lists_required(self):
        for value in ('HEAD', self.commit[:7], 'f' * 40):
            with self.subTest(value=value), self.assertRaises(integrity.IntegrityError):
                spec = copy.deepcopy(self.spec)
                spec['implementations']['B']['commit'] = value
                integrity.freeze(self.root, spec)
        spec = copy.deepcopy(self.spec)
        spec['implementations']['A']['sources'] = []
        with self.assertRaises(integrity.IntegrityError):
            integrity.freeze(self.root, spec)

    def test_each_contract_field_is_required_and_no_runtime_scope_allowed(self):
        for field in self.spec:
            with self.subTest(field=field), self.assertRaises(integrity.IntegrityError):
                spec = copy.deepcopy(self.spec)
                del spec[field]
                integrity.freeze(self.root, spec)
        spec = copy.deepcopy(self.spec)
        spec['scope'] = 'PRODUCTION'
        with self.assertRaises(integrity.IntegrityError):
            integrity.freeze(self.root, spec)

    def test_old_partial_freeze_is_not_a_complete_contract(self):
        self.put('legacy.json', {'candidate_id': 'old', 'source_sha256': {}})
        with self.assertRaises(integrity.IntegrityError):
            integrity.verify(self.root, 'legacy.json', self.hash('legacy.json'))

    def test_external_pin_rejects_manifest_changes_even_when_internally_resigned(self):
        manifest = copy.deepcopy(self.manifest)
        manifest['case_count'] = 1
        self.put('manifest.json', manifest)
        self.reject('manifest_digest_mismatch')
        with self.assertRaisesRegex(integrity.IntegrityError, 'bound_inputs_changed'):
            integrity.verify(self.root, 'manifest.json', self.hash('manifest.json'))

    def test_configuration_dataset_and_scorer_drift_rejected(self):
        for path in ('configuration.json', 'inventory.json', 'scorer.py'):
            with self.subTest(path=path):
                original = (self.root / path).read_bytes()
                (self.root / path).write_bytes(original + b' ')
                self.reject('bound_inputs_changed')
                (self.root / path).write_bytes(original)

    def test_missing_side_or_mismatched_commit_and_role_bindings_rejected(self):
        original = copy.deepcopy(self.run)
        del self.run['sides']['B']
        self.put('run.json', self.run)
        self.reject('schema_fields')
        for field in ('commit', 'implementation_digest', 'configuration_digest', 'dataset_digest', 'scorer_digest'):
            with self.subTest(field=field):
                self.run = copy.deepcopy(original)
                self.run['sides']['B'][field] = '0' * (40 if field == 'commit' else 64)
                self.put('run.json', self.run)
                self.reject('run_commit_mismatch' if field == 'commit' else 'run_binding_mismatch')

    def test_failed_or_incomplete_run_rejected(self):
        self.run['status'] = 'RUNNING'
        self.put('run.json', self.run)
        self.reject('run_incomplete')

    def test_nonzero_side_exit_and_swapped_result_rejected(self):
        self.run['sides']['B']['exit_code'] = 1
        self.put('run.json', self.run)
        self.reject('side_incomplete_or_failed')
        self.run['sides']['B']['exit_code'] = 0
        self.run['sides']['B']['result'] = self.run['sides']['A']['result']
        self.put('run.json', self.run)
        self.reject('result_binding_mismatch')

    def test_missing_duplicate_extra_or_pending_case_rejected(self):
        original = (self.root / 'B-result.json').read_bytes()
        changes = [lambda d: d['case_results'].pop(),
                   lambda d: d['case_results'].__setitem__(1, d['case_results'][0]),
                   lambda d: d['case_results'].append({'id': 'EXTRA_METADATA', 'status': 'COMPLETED'}),
                   lambda d: d['case_results'][0].__setitem__('status', 'PENDING')]
        for index, change in enumerate(changes):
            with self.subTest(index=index):
                (self.root / 'B-result.json').write_bytes(original)
                self.replace_result(change)
                self.reject('case_incomplete' if index == 3 else 'case_population_mismatch')

    def test_result_digest_and_nonfinite_or_boolean_metrics_rejected(self):
        original = json.loads((self.root / 'B-result.json').read_text())
        (self.root / 'B-result.json').write_bytes(b'{}')
        self.reject('result_digest_mismatch')
        for value in (float('nan'), float('inf'), True, 'unmeasured'):
            result = copy.deepcopy(original)
            result['metrics'] = {'value': value}
            self.put('B-result.json', result)
            self.run['sides']['B']['result']['sha256'] = self.hash('B-result.json')
            self.put('run.json', self.run)
            self.reject('nonfinite_number' if type(value) is float else 'metrics_schema')

    def test_validator_version_and_run_manifest_mismatch_rejected(self):
        self.run['manifest_sha256'] = '0' * 64
        self.put('run.json', self.run)
        self.reject('run_manifest_mismatch')
        self.manifest['validator_sha256'] = '0' * 64
        self.put('manifest.json', self.manifest)
        with self.assertRaisesRegex(integrity.IntegrityError, 'bound_inputs_changed'):
            integrity.verify(self.root, 'manifest.json', self.hash('manifest.json'))

    def test_hidden_absolute_traversal_and_symlink_paths_rejected_without_read(self):
        for path in ('.env', '../outside', '/tmp/outside', 'folder/../outside', 'folder//file'):
            with self.subTest(path=path), self.assertRaises(integrity.IntegrityError):
                integrity.read(self.root, path)
        (self.root / 'link').symlink_to(self.root / 'inventory.json')
        with self.assertRaisesRegex(integrity.IntegrityError, 'symlink_path'):
            integrity.read(self.root, 'link')

    def test_inventory_duplicates_split_and_empty_roles_rejected(self):
        for value in ({'split': 'development', 'cases': [{'id': 'DUP'}, {'id': 'DUP'}]},
                      {'split': 'holdout', 'cases': [{'id': 'META_1'}]}):
            self.put('inventory.json', value)
            with self.assertRaises(integrity.IntegrityError):
                integrity.freeze(self.root, self.spec)
        for role in ('configuration', 'scorer', 'dataset'):
            spec = copy.deepcopy(self.spec)
            spec[role] = []
            with self.assertRaises(integrity.IntegrityError):
                integrity.freeze(self.root, spec)

    def test_duplicate_json_keys_rejected(self):
        with self.assertRaisesRegex(integrity.IntegrityError, 'duplicate_json_key'):
            integrity.decode(b'{"status":1,"status":2}')

    def test_cli_writes_safe_failure_receipt_and_never_overwrites(self):
        self.run['sides'].pop('B')
        self.put('run.json', self.run)
        args = [sys.executable, str(TOOL), '--repo', str(self.root), 'audit', '--manifest', 'manifest.json',
                '--expected-manifest-sha256', self.pin, '--run', 'run.json', '--out', 'receipt.json']
        result = subprocess.run(args, capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        receipt = json.loads((self.root / 'receipt.json').read_text())
        self.assertEqual(receipt['error_code'], 'schema_fields')
        before = self.hash('receipt.json')
        self.assertEqual(subprocess.run(args, capture_output=True).returncode, 2)
        self.assertEqual(self.hash('receipt.json'), before)
        self.assertNotIn(str(self.root), result.stdout + result.stderr)

    def test_cli_freeze_verify_and_audit_success_and_output_collision(self):
        self.put('spec.json', self.spec)
        creation = subprocess.run([sys.executable, str(TOOL), '--repo', str(self.root), 'freeze',
                                   '--spec', 'spec.json', '--out', 'cli-manifest.json'],
                                  capture_output=True, text=True)
        self.assertEqual(creation.returncode, 0, creation.stdout)
        pin = json.loads(creation.stdout)['manifest_sha256']
        self.assertEqual(pin, self.pin)
        verification = subprocess.run([sys.executable, str(TOOL), '--repo', str(self.root), 'verify',
                                       '--manifest', 'cli-manifest.json', '--expected-manifest-sha256', pin],
                                      capture_output=True, text=True)
        self.assertEqual(verification.returncode, 0, verification.stdout)
        args = [sys.executable, str(TOOL), '--repo', str(self.root), 'audit', '--manifest', 'cli-manifest.json',
                '--expected-manifest-sha256', pin, '--run', 'run.json']
        result = subprocess.run(args + ['--out', 'cli-receipt.json'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(json.loads((self.root / 'cli-receipt.json').read_text())['status'], 'INTEGRITY_VERIFIED')
        before = self.hash('run.json')
        collision = subprocess.run(args + ['--out', 'run.json'], capture_output=True)
        self.assertEqual(collision.returncode, 2)
        self.assertEqual(self.hash('run.json'), before)


if __name__ == '__main__':
    unittest.main()
