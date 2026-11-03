"""Check managed-browser selection without requiring a browser installation."""
from pathlib import Path
import ast
import os
import tempfile
import unittest
from unittest.mock import Mock, patch
from browser_support import launch_chromium


class BrowserSupportTest(unittest.TestCase):
    def test_e2e_launches_cannot_bypass_shared_browser_selection(self):
        for path in Path(__file__).parent.glob('*_e2e.py'):
            tree = ast.parse(path.read_text())
            for node in ast.walk(tree):
                if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
                    receiver = node.func.value
                    self.assertFalse(node.func.attr == 'launch' and isinstance(receiver, ast.Attribute)
                                     and receiver.attr == 'chromium',
                                     str(path) + ': use the shared browser launcher')

    def test_default_uses_playwright_matching_browser_without_a_system_fallback(self):
        playwright = Mock()
        with patch.dict(os.environ, {}, clear=True):
            self.assertIs(launch_chromium(playwright), playwright.chromium.launch.return_value)
        playwright.chromium.launch.assert_called_once_with(
            headless=True, args=['--no-sandbox', '--disable-dev-shm-usage'])

    def test_explicit_existing_local_browser_is_used_without_changing_launch_flags(self):
        with tempfile.TemporaryDirectory() as tmp:
            executable = Path(tmp) / 'synthetic-browser'
            executable.write_text('synthetic fixture, never executed')
            executable.chmod(0o700)
            playwright = Mock()
            with patch.dict(os.environ, {'DISTILLY_TEST_CHROMIUM': str(executable)}, clear=True):
                launch_chromium(playwright)
            playwright.chromium.launch.assert_called_once_with(
                headless=True, args=['--no-sandbox', '--disable-dev-shm-usage'],
                executable_path=str(executable))

    def test_invalid_override_fails_instead_of_silently_switching_browsers(self):
        with tempfile.TemporaryDirectory() as tmp:
            file = Path(tmp) / 'non-executable'
            file.write_text('synthetic')
            for override in ['relative-browser', str(Path(tmp) / 'missing'), tmp, str(file)]:
                with self.subTest(override=override):
                    playwright = Mock()
                    with patch.dict(os.environ, {'DISTILLY_TEST_CHROMIUM': override}, clear=True):
                        with self.assertRaises(ValueError):
                            launch_chromium(playwright)
                    playwright.chromium.launch.assert_not_called()


if __name__ == '__main__':
    unittest.main()
