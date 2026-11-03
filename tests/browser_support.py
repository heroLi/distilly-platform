"""Synthetic browser fixtures use Playwright's matching browser by default."""
import os
import time
from pathlib import Path


def wait_for_browser_condition(page, predicate, timeout_seconds=30):
    """Pump sync Playwright callbacks while waiting for Python-side route state."""
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        page.wait_for_timeout(50)
    raise AssertionError('synthetic browser bounded condition timed out')


def launch_chromium(playwright):
    options = {'headless': True, 'args': ['--no-sandbox', '--disable-dev-shm-usage']}
    # Explicit local reuse only. CI installs the browser selected by pinned Playwright.
    executable = os.environ.get('DISTILLY_TEST_CHROMIUM')
    if executable:
        path = Path(executable)
        if not path.is_absolute() or not path.is_file() or not os.access(path, os.X_OK):
            raise ValueError('DISTILLY_TEST_CHROMIUM must name an existing absolute executable')
        options['executable_path'] = str(path)
    return playwright.chromium.launch(**options)
