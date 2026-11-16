#!/usr/bin/env python3
"""Real Chromium interaction/layout checks for synthetic static assets only."""
import functools
import http.server
import json
import os
from pathlib import Path
import tempfile
import threading

from playwright.sync_api import sync_playwright, expect
from browser_support import launch_chromium

ROOT = Path(__file__).resolve().parents[1]
INTRO = '我习惯在周六上午去月湾公园散步。'


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *_args):
        pass


def main():
    output = Path(os.environ.get('DISTILLY_PREVIEW_QA_OUTPUT', tempfile.mkdtemp(prefix='distilly-preview-qa-')))
    output.mkdir(parents=True, exist_ok=True)
    handler = functools.partial(QuietHandler, directory=str(ROOT / 'preview/synthetic-text/dist'))
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    results = []
    try:
        with sync_playwright() as p:
            browser = launch_chromium(p)
            try:
                for width, height, label in [(1440, 1000, 'desktop'), (390, 844, 'mobile')]:
                    context = browser.new_context(viewport={'width': width, 'height': height})
                    page = context.new_page()
                    errors, requests = [], []
                    page.on('pageerror', lambda e: errors.append(str(e)))
                    page.on('request', lambda r: requests.append(r.url))
                    base = f'http://127.0.0.1:{server.server_port}/'
                    page.goto(base)
                    expect(page.get_by_role('heading', name='小禾', exact=True)).to_be_visible()
                    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
                    page.screenshot(path=str(output / (label + '-initial.png')), full_page=True)
                    page.locator('#intro').click()
                    expect(page.locator('#memories .badge')).to_have_text('待确认')
                    page.locator('#recall').click()
                    expect(page.locator('#messages .assistant').last).to_contain_text('没有可用的已确认')
                    if label == 'mobile':
                        page.locator('#memory-tab').click()
                    page.get_by_role('button', name='查看来源', exact=True).click()
                    expect(page.locator('#source-dialog')).to_be_visible()
                    expect(page.locator('#source-body')).to_have_text(INTRO)
                    page.locator('#close-source').click()
                    page.get_by_role('button', name='确认留下', exact=True).click()
                    expect(page.locator('#memories .badge')).to_have_text('已确认')
                    page.screenshot(path=str(output / (label + '-memory.png')), full_page=True)
                    if label == 'mobile':
                        page.locator('#chat-tab').click()
                    page.locator('#conversation').select_option('again')
                    page.locator('#recall').click()
                    expect(page.locator('#messages .assistant').last).to_contain_text('周六')
                    if label == 'mobile':
                        page.locator('#memory-tab').click()
                    page.get_by_role('button', name='纠错：切换周六 / 周日', exact=True).click()
                    page.get_by_role('button', name='查看来源', exact=True).click()
                    expect(page.locator('#source-body')).to_contain_text('周六')
                    page.locator('#close-source').click()
                    if label == 'mobile':
                        page.locator('#chat-tab').click()
                    page.locator('#recall').click()
                    expect(page.locator('#messages .assistant').last).to_contain_text('周日')
                    if label == 'mobile':
                        page.locator('#memory-tab').click()
                    page.get_by_role('button', name='遗忘这条', exact=True).click()
                    expect(page.locator('#memories')).not_to_contain_text(INTRO)
                    if label == 'mobile':
                        page.locator('#chat-tab').click()
                    page.locator('#recall').click()
                    expect(page.locator('#messages .assistant').last).to_contain_text('没有可用的已确认')
                    page.locator('#reset').click()
                    page.locator('#intro').click()
                    page.locator('#stop').click()
                    expect(page.locator('#retry')).to_be_visible()
                    page.locator('#retry').click()
                    expect(page.locator('#memories .badge')).to_have_text('待确认')
                    expect(page.locator('#messages .user')).to_have_count(1)
                    if label == 'mobile':
                        page.locator('#memory-tab').click()
                    page.get_by_role('button', name='确认留下', exact=True).click()
                    page.get_by_role('button', name='查看来源', exact=True).click()
                    page.locator('#change-source').click()
                    expect(page.locator('#memories .badge')).to_have_text('来源已更正 · 已失效')
                    page.locator('#visitor').select_option('B')
                    expect(page.locator('#memories .memory-card')).to_have_count(0)
                    if label == 'mobile':
                        page.locator('#chat-tab').click()
                    page.locator('#intro').click()
                    page.locator('#visitor').select_option('A')
                    page.wait_for_timeout(500)
                    expect(page.locator('#memories .badge')).to_have_text('来源已更正 · 已失效')
                    page.locator('#message').fill('<img src=x onerror="window.injected=1">')
                    page.locator('#send').click()
                    expect(page.locator('#messages .assistant').last).to_contain_text('不会据此提取记忆')
                    assert page.locator('#messages img').count() == 0
                    assert page.evaluate('window.injected === undefined')
                    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
                    page.locator('#exit').click()
                    expect(page.locator('#left')).to_be_visible()
                    expect(page.locator('#memories .memory-card')).to_have_count(0)
                    page.locator('#restart').click()
                    page.locator('#intro').click()
                    expect(page.locator('#memories .badge')).to_have_text('待确认')
                    page.reload()
                    expect(page.locator('#memories .memory-card')).to_have_count(0)
                    expect(page.locator('#messages .user')).to_have_count(0)
                    assert page.evaluate('localStorage.length === 0 && sessionStorage.length === 0')
                    assert not errors, errors
                    assert all(u.startswith(base) for u in requests), 'unexpected external request'
                    assert {u.removeprefix(base) for u in requests} <= {'', 'index.html', 'preview.css', 'demo-state.js', 'preview.js'}
                    results.append({'viewport': label, 'width': width, 'height': height, 'result': 'PASS', 'external_requests': 0, 'page_errors': 0})
                    context.close()
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
    (output / 'result.json').write_text(json.dumps({'browser': 'native Chromium via existing Playwright', 'results': results, 'synthetic_only': True}, indent=2) + '\n')
    print(json.dumps({'results': results, 'evidence': str(output)}))


if __name__ == '__main__':
    main()
