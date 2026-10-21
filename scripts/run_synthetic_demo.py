#!/usr/bin/env python3
"""Start a local mock demo without loading .env or inheriting provider/database settings."""
import argparse
import os
import signal
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SAFE_KEYS = ('HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'PATH', 'JAVA_HOME', 'JAVA_BIN')


def environment(source, root=ROOT):
    result = {key: source[key] for key in SAFE_KEYS if key in source}
    result.update(PLATFORM_DB_URL='jdbc:h2:file:' + str(root / 'data/synthetic-demo/platform') + ';DATABASE_TO_LOWER=TRUE',
                  PLATFORM_DB_USER='sa', PLATFORM_DB_PASSWORD='', MODEL_MODE='mock',
                  PLATFORM_DEMO='true', PLATFORM_BIND='127.0.0.1')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=18080)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error('port must be between 1 and 65535')
    (ROOT / 'data/synthetic-demo').mkdir(parents=True, exist_ok=True)
    command = [sys.executable, str(ROOT / 'run_local.py'), '--demo', '--env-file', os.devnull,
               '--port', str(args.port)]
    # run_local owns the child API/Worker shutdown and uses a copied runtime JAR.
    child = subprocess.Popen(command, cwd=ROOT, env=environment(os.environ))
    def forward(signum, _frame):
        if child.poll() is None:
            child.send_signal(signum)
    previous = {sig: signal.signal(sig, forward) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        return child.wait()
    finally:
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=30)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)
        for sig, handler in previous.items():
            signal.signal(sig, handler)


if __name__ == '__main__':
    raise SystemExit(main())
