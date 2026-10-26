#!/usr/bin/env python3
"""One regression entry point. --e2e runs synthetic HTTP fixtures on fixed test ports."""
import argparse
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--e2e', action='store_true', help='Also run synthetic API/Worker end-to-end flows')
args = parser.parse_args()
commands = [
    [sys.executable, 'tests/architecture_check.py'],
    [sys.executable, 'tests/synthetic_demo_launcher_test.py'],
    [sys.executable, 'tests/text_scenarios_test.py'],
    ['mvn', '-B', '-ntp', '-f', 'server/pom.xml', 'package'],
    [sys.executable, '-m', 'unittest', 'discover', '-s', 'worker', '-p', 'test_*.py'],
]
commands.extend(['node', '--check', str(path.relative_to(ROOT))]
                for path in sorted((ROOT / 'server/src/main/resources/static').glob('*.js')))
commands.extend(['node', str(path.relative_to(ROOT))] for path in sorted((ROOT / 'tests').glob('*_test.cjs')))
if args.e2e:
    commands.extend([[sys.executable, 'tests/persona_e2e.py'], [sys.executable, 'tests/e2e.py'], [sys.executable, 'tests/text_egress_e2e.py'], [sys.executable, 'tests/record_cards_e2e.py'], [sys.executable, 'tests/synthetic_cost_ledger_e2e.py'], [sys.executable, 'tests/synthetic_cost_page_e2e.py'], [sys.executable, 'tests/record_filters_e2e.py'], [sys.executable, 'tests/text_execution_budget_e2e.py']])
    commands.append([sys.executable, 'tests/personality_journey_e2e.py'])
    commands.append([sys.executable, 'tests/chat_recovery_e2e.py'])
    commands.append([sys.executable, 'tests/chat_submission_e2e.py'])
    commands.append([sys.executable, 'tests/first_entry_e2e.py'])
    commands.append([sys.executable, 'tests/upload_receipt_e2e.py'])
    commands.append([sys.executable, 'tests/text_scenarios_e2e.py'])
    commands.append([sys.executable, 'tests/context_scope_e2e.py'])
    commands.append([sys.executable, 'tests/first_text_walkthrough_e2e.py', '--completion-between-detail-reads'])
    commands.append([sys.executable, 'tests/memory_voice_e2e.py'])
commands.append(['git', 'diff', '--check'])
for command in commands:
    print('RUN:', ' '.join(command), flush=True)
    result = subprocess.run(command, cwd=ROOT)
    if result.returncode:
        sys.exit(result.returncode)
print('PASS: all selected checks')
