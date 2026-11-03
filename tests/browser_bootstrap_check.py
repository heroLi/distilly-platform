"""Fail before expensive E2E work if the selected browser is unavailable."""
from importlib.metadata import version
from playwright.sync_api import sync_playwright
from browser_support import launch_chromium


with sync_playwright() as playwright:
    browser = launch_chromium(playwright)
    try:
        page = browser.new_page()
        page.set_content('<p id="probe">synthetic browser bootstrap</p>')
        assert page.locator('#probe').inner_text() == 'synthetic browser bootstrap'
        print('PASS: browser bootstrap; Playwright ' + version('playwright')
              + '; Chromium ' + browser.version + '; no external page requests')
    finally:
        browser.close()
