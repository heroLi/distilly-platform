"""Static allowlist and artifact integrity tests, no network or model."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('preview_package', ROOT / 'scripts/package_synthetic_preview.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PreviewPackageTest(unittest.TestCase):
    def test_allowlist_and_every_digest_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'preview.zip'
            module.package(path)
            with zipfile.ZipFile(path) as z:
                self.assertEqual(set(z.namelist()), set(module.FILES) | {'MANIFEST.json'})
                m = json.loads(z.read('MANIFEST.json'))
                for name, digest in m['sha256'].items():
                    self.assertEqual(hashlib.sha256(z.read(name)).hexdigest(), digest)
                self.assertFalse(m['deployed'])
                self.assertFalse(m['backend_included'])
            before = path.read_bytes()
            with self.assertRaises(FileExistsError):
                module.package(path)
            self.assertEqual(path.read_bytes(), before)

    def test_deterministic_archive(self):
        with tempfile.TemporaryDirectory() as d:
            a, b = Path(d) / 'a.zip', Path(d) / 'b.zip'
            module.package(a)
            module.package(b)
            self.assertEqual(a.read_bytes(), b.read_bytes())

    def test_no_network_or_persistent_storage_in_browser_code(self):
        base = ROOT / 'preview/synthetic-text/dist'
        for name in ['demo-state.js', 'preview.js']:
            body = (base / name).read_text()
            for forbidden in ['fetch(', 'XMLHttpRequest', 'WebSocket', 'sendBeacon', 'localStorage', 'sessionStorage', 'indexedDB', 'innerHTML', 'eval(']:
                self.assertNotIn(forbidden, body)
        html = (base / 'index.html').read_text()
        self.assertIn("connect-src 'none'", html)
        self.assertNotIn('type="file"', html)
        self.assertNotIn('https://', html)


if __name__ == '__main__':
    unittest.main()
