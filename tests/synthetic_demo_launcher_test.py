"""The demo launcher must never inherit an unrelated database or provider authority."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('synthetic_demo', ROOT / 'scripts/run_synthetic_demo.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DemoIsolationTest(unittest.TestCase):
    def test_only_explicit_toolchain_and_locale_keys_are_read(self):
        class Protected(dict):
            def __getitem__(self, key):
                if key not in module.SAFE_KEYS:
                    raise AssertionError('attempt to inspect a non-whitelisted value')
                return super().__getitem__(key)
        source = Protected(PATH='/synthetic/bin', JAVA_BIN='/synthetic/java',
                           MODEL_API_KEY='fixture', PLATFORM_DB_URL='fixture',
                           PLATFORM_USERS='fixture', HTTPS_PROXY='fixture')
        result = module.environment(source, ROOT)
        self.assertEqual('/synthetic/java', result['JAVA_BIN'])
        self.assertFalse({'MODEL_API_KEY', 'PLATFORM_USERS', 'HTTPS_PROXY'} & result.keys())
        self.assertIn('/data/synthetic-demo/platform;', result['PLATFORM_DB_URL'])
        self.assertEqual('mock', result['MODEL_MODE'])
        self.assertEqual('127.0.0.1', result['PLATFORM_BIND'])

    def test_empty_source_still_selects_isolated_mock_database(self):
        result = module.environment({}, ROOT)
        self.assertEqual('sa', result['PLATFORM_DB_USER'])
        self.assertEqual('', result['PLATFORM_DB_PASSWORD'])
        self.assertEqual('true', result['PLATFORM_DEMO'])
        self.assertEqual('mock', result['MODEL_MODE'])


if __name__ == '__main__':
    unittest.main()
