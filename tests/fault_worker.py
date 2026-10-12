#!/usr/bin/env python3
"""Synthetic local E2E Worker: inject one completion transport failure, never model faults."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import sys
import tempfile
import urllib.error
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'worker'))
from worker import Worker

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--job-id', required=True)
    parser.add_argument('--fault', choices=('after-commit', 'before-commit'), required=True)
    parser.add_argument('--counter-file', required=True)
    args = parser.parse_args()
    api = urlsplit(os.environ.get('PLATFORM_API_URL', ''))
    model = urlsplit(os.environ.get('MODEL_BASE_URL', ''))
    if (os.environ.get('PLATFORM_DEMO') != 'true' or os.environ.get('MODEL_MODE') != 'gateway'
            or api.hostname != '127.0.0.1' or api.port not in (18082, 18084)
            or model.hostname != '127.0.0.1' or model.port not in (18083, 18085)
            or os.environ.get('MODEL_API_KEY') != 'fixture-key'):
        raise RuntimeError('fault Worker requires the fixed local synthetic fixture environment')
    counter = Path(args.counter_file).resolve()
    if not any(counter.is_relative_to(base) for base in (Path(tempfile.gettempdir()).resolve(), Path('/tmp').resolve())):
        raise RuntimeError('fault counter must be in a temporary directory')
    stats = {'fault': args.fault, 'job_id': args.job_id, 'completion_requests': 0,
             'completion_acks': 0, 'injected_faults': 0, 'payload_digests': [], 'result_digests': []}
    def save(): counter.write_text(json.dumps(stats))
    runner = Worker()
    original = runner.request
    target = '/internal/persona-jobs/' + args.job_id + '/complete'
    def request(path, payload, **kwargs):
        if path != target: return original(path, payload, **kwargs)
        stats['completion_requests'] += 1
        stats['payload_digests'].append(hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest())
        first = stats['injected_faults'] == 0
        save()
        if first and args.fault == 'before-commit':
            stats['injected_faults'] += 1;save()
            raise urllib.error.HTTPError(runner.api + path, 503, 'synthetic before commit', {}, None)
        result = original(path, payload, **kwargs)
        stats['result_digests'].append(hashlib.sha256(json.dumps(result, sort_keys=True).encode()).hexdigest())
        if first and args.fault == 'after-commit':
            stats['injected_faults'] += 1;save()
            raise ConnectionResetError('synthetic lost completion acknowledgement after transaction commit')
        stats['completion_acks'] += 1;save()
        return result
    runner.request = request
    for sig in (signal.SIGTERM, signal.SIGINT): signal.signal(sig, lambda *_: runner.stop.set())
    save()
    try: runner.run()
    finally: save()

if __name__ == '__main__': main()
