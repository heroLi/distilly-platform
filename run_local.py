#!/usr/bin/env python3
"""Run the API and Worker locally; demo credentials only exist with --demo."""
import argparse
import os
import re
import signal
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument("--demo", action="store_true", help="Explicitly enable local demonstration users and mock model")
parser.add_argument("--demo-model", choices=("mock","gateway"), default="mock", help="With --demo, optionally use the configured real model; loopback test accounts remain explicit")
parser.add_argument("--port", type=int, default=18080)
parser.add_argument("--env-file", type=Path, default=root / ".env", help="Local KEY=value configuration; process environment takes precedence")
args = parser.parse_args()
env = dict(os.environ)
if args.env_file.exists():
    for line in args.env_file.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        name, sep, value = line.partition("=")
        name = name.strip()
        if not sep or not re.fullmatch(r"[A-Z][A-Z0-9_]*", name):
            parser.error("Invalid configuration line in env file")
        env.setdefault(name, value.strip())
env["PLATFORM_PORT"] = str(args.port)
env["PLATFORM_BIND"] = "127.0.0.1"
env["PLATFORM_API_URL"] = f"http://127.0.0.1:{args.port}"
if args.demo:
    env.update(PLATFORM_DEMO="true", MODEL_MODE=args.demo_model, PLATFORM_WORKER_TOKEN="local-demo-worker-token-32-characters-long")
    if args.demo_model == "gateway":
        for name in ("MODEL_BASE_URL", "MODEL_NAME", "MODEL_API_KEY"):
            if not env.get(name):
                parser.error(f"Missing {name} for gateway demo")
else:
    env["PLATFORM_DEMO"] = "false"
    env["MODEL_MODE"] = "gateway"
    for name in ("PLATFORM_USERS", "PLATFORM_WORKER_TOKEN", "MODEL_BASE_URL", "MODEL_NAME", "MODEL_API_KEY"):
        if not env.get(name):
            parser.error(f"Missing {name}; use --demo only for local flow validation")
jar = root / "server/target/distilly-platform-0.1.0.jar"
if not jar.exists():
    parser.error("Build first: mvn -B -ntp -f server/pom.xml package")
java = env.get("JAVA_BIN", str(Path(env["JAVA_HOME"]) / "bin/java") if env.get("JAVA_HOME") else "java")
result = subprocess.run([java, "-version"], capture_output=True, text=True)
match = re.search(r'version "(\d+)', result.stderr + result.stdout)
if result.returncode or not match or int(match.group(1)) < 17:
    parser.error("Set JAVA_BIN to a Java 17+ executable")
(root / "data").mkdir(exist_ok=True)
if not env.get("PLATFORM_DB_URL"):
    env["PLATFORM_DB_URL"] = "jdbc:h2:file:" + str(root / "data/distilly") + ";DATABASE_TO_LOWER=TRUE"
    # Compose's PostgreSQL password does not belong to the local H2 database.
    env["PLATFORM_DB_USER"] = "sa"
    env["PLATFORM_DB_PASSWORD"] = ""
processes = []
stopping = False
runtime = tempfile.TemporaryDirectory(prefix="distilly-local-runtime-")


def stop(*_):
    global stopping
    stopping = True


for sig in (signal.SIGINT, signal.SIGTERM):
    signal.signal(sig, stop)
try:
    # Spring Boot loads nested classes lazily. Never run a JAR Maven may overwrite.
    runtime_jar = Path(runtime.name) / jar.name
    shutil.copy2(jar, runtime_jar)
    processes.append(subprocess.Popen([java, "-jar", str(runtime_jar)], cwd=root, env=env))
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline and not stopping:
        if processes[0].poll() is not None:
            raise RuntimeError("API failed to start")
        try:
            with urllib.request.urlopen(env["PLATFORM_API_URL"] + "/health", timeout=1):
                break
        except OSError:
            time.sleep(.2)
    else:
        raise RuntimeError("API startup timed out")
    processes.append(subprocess.Popen([sys.executable, str(root / "worker/worker.py")], cwd=root, env=env))
    print(f"Open {env['PLATFORM_API_URL']} ({'DEMO / ' + args.demo_model + ' model' if args.demo else 'configured gateway'})", flush=True)
    while not stopping:
        if any(p.poll() is not None for p in processes):
            raise RuntimeError("API or Worker exited")
        time.sleep(.5)
finally:
    for p in reversed(processes):
        if p.poll() is None:
            p.terminate()
            try:
                p.wait(timeout=10)
            except subprocess.TimeoutExpired:
                p.kill()
                p.wait()
    runtime.cleanup()
