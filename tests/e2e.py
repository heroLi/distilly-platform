#!/usr/bin/env python3
"""Start real Java API + Python Worker + HTTP model fixture, then exercise the flow."""
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "server/target/distilly-platform-0.1.0.jar"
WORKER = ROOT / "worker/worker.py"
WORKER_TOKEN = "e2e-fixture-worker-token-32-characters"


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class Gateway(BaseHTTPRequestHandler):
    calls = 0
    def log_message(self, *_):
        pass
    def do_POST(self):
        if self.path != "/v1/chat/completions" or self.headers.get("Authorization") != "Bearer fixture-key":
            self.send_error(401)
            return
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        payload = json.loads(request["messages"][1]["content"])
        Gateway.calls += 1
        if "chunk_id" in payload:
            quote = payload["text"].splitlines()[-1][:100]
            output = {"claims": [{"section": "接口规范", "text": quote, "evidence": [{
                "material_id": payload["material_id"], "chunk_id": payload["chunk_id"], "quote": quote}]}]}
        else:
            claim = payload["claims"][0]
            output = {"answer": claim["text"], "citations": [claim["id"]]}
        body = json.dumps({"choices": [{"message": {"content": json.dumps(output, ensure_ascii=False)}}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    real_model = "--real-model" in sys.argv
    if real_model and not all(os.getenv(k) for k in ("MODEL_BASE_URL", "MODEL_NAME", "MODEL_API_KEY")):
        raise RuntimeError("Real model test requires MODEL_BASE_URL, MODEL_NAME and MODEL_API_KEY")
    if not JAR.exists():
        raise RuntimeError("Build server with Maven first")
    port = free_port()
    gateway = ThreadingHTTPServer(("127.0.0.1", 0), Gateway)
    thread = threading.Thread(target=gateway.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{port}"
    processes = []
    with tempfile.TemporaryDirectory(prefix="distilly-e2e-") as tmp:
        log = Path(tmp) / "runtime.log"
        env = {**os.environ, "PLATFORM_DEMO": "true", "PLATFORM_WORKER_TOKEN": WORKER_TOKEN,
               "PLATFORM_PORT": str(port), "PLATFORM_BIND": "127.0.0.1",
               "PLATFORM_DB_URL": "jdbc:h2:file:" + str(Path(tmp) / "db") + ";DATABASE_TO_LOWER=TRUE",
               "PLATFORM_DB_USER": "sa", "PLATFORM_DB_PASSWORD": "",
               "PLATFORM_API_URL": base, "MODEL_MODE": "gateway",
               "MODEL_BASE_URL": f"http://127.0.0.1:{gateway.server_port}/v1",
               "MODEL_ALLOW_HTTP": "true", "MODEL_NAME": "http-test-fixture", "MODEL_API_KEY": "fixture-key"}
        if real_model:
            env.update({k: os.environ[k] for k in ("MODEL_BASE_URL", "MODEL_NAME", "MODEL_API_KEY")})
            env["MODEL_ALLOW_HTTP"] = "false"
            env["MODEL_JSON_MODE"] = os.getenv("MODEL_JSON_MODE", "true")
        java = os.environ.get("JAVA_BIN", "java")
        with log.open("w") as output:
            def api(path, body=None, token="demo-owner", method=None, raw=False):
                headers = {"Authorization": "Bearer " + token}
                data = None
                if body is not None:
                    headers["Content-Type"] = "application/json"
                    data = json.dumps(body).encode()
                req = urllib.request.Request(base + path, data, headers, method=method)
                with urllib.request.urlopen(req, timeout=10) as response:
                    content = response.read()
                return content.decode() if raw else json.loads(content) if content else None

            def wait_job(job, token="demo-owner"):
                deadline = time.monotonic() + (240 if real_model else 40)
                while time.monotonic() < deadline:
                    state = api("/api/jobs/" + job["id"], token=token)
                    if state["status"] == "SUCCEEDED":
                        return state["result"]
                    if state["status"] == "FAILED":
                        raise AssertionError("job failed: " + state.get("error_code", ""))
                    time.sleep(.2)
                raise AssertionError("job timeout")

            def start_api():
                p = subprocess.Popen([java, "-jar", str(JAR)], cwd=tmp, env=env, stdout=output, stderr=subprocess.STDOUT)
                processes.append(p)
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    try:
                        api("/health")
                        return p
                    except OSError:
                        if p.poll() is not None:
                            raise AssertionError("API exited")
                        time.sleep(.2)
                raise AssertionError("API startup timeout")

            try:
                server = start_api()
                processes.append(subprocess.Popen([sys.executable, str(WORKER)], cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT))
                project = api("/api/projects", {"title": "端到端验证项目"})["id"]
                api(f"/api/projects/{project}/members", {"user_id": "viewer", "role": "VIEWER"}, method="PUT")
                profile = api(f"/api/projects/{project}/profiles", {"title": "接口评审经验"})["id"]
                boundary = "distilly-test-boundary"
                data = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"rules.md\"\r\nContent-Type: text/markdown\r\n\r\n# 接口规范\n接口设计必须校验幂等键，重试不能重复扣款。\r\n--{boundary}--\r\n").encode()
                request = urllib.request.Request(base + f"/api/projects/{project}/materials", data,
                                                 {"Authorization": "Bearer demo-owner", "Content-Type": "multipart/form-data; boundary=" + boundary})
                with urllib.request.urlopen(request) as response:
                    material = json.load(response)["id"]
                job = api(f"/api/profiles/{profile}/distill", {"material_ids": [material]})
                result = wait_job(job)
                version = result["version_id"]
                assert api(f"/api/profiles/{profile}/versions", token="demo-viewer") == []
                api(f"/api/versions/{version}/review", {"approve": True, "note": "测试审核"})
                question = api(f"/api/profiles/{profile}/questions", {"question": "接口重试要注意什么？"}, token="demo-viewer")
                answer = wait_job(question, "demo-viewer")
                assert answer["version_id"] == version and "幂等" in answer["answer"] and len(answer["citations"]) >= 1
                download = api(f"/api/profiles/{profile}/download", token="demo-viewer", raw=True)
                assert "幂等" in download and "Persona" not in download and "fixture-key" not in download
                try:
                    api(f"/api/materials/{material}", token="demo-viewer")
                    raise AssertionError("viewer could read raw material")
                except urllib.error.HTTPError as error:
                    assert error.code == 403
                # Restart the actual API and verify file DB state, current pointer and sources survive.
                server.terminate()
                server.wait(timeout=10)
                start_api()
                versions = api(f"/api/profiles/{profile}/versions", token="demo-viewer")
                assert versions[0]["id"] == version
                if not real_model:
                    assert Gateway.calls >= 2
                print(json.dumps({"status": "PASS", "flow": "upload → HTTP model distill → review → publish → HTTP model answer → download → permission rejection → restart persistence",
                                  "model": env["MODEL_NAME"], "gateway_calls": None if real_model else Gateway.calls}, ensure_ascii=False))
            except Exception:
                output.flush()
                print(log.read_text()[-6000:], file=sys.stderr)
                raise
            finally:
                for process in reversed(processes):
                    if process.poll() is None:
                        process.terminate()
                        try:
                            process.wait(timeout=10)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait()
                gateway.shutdown()
                gateway.server_close()
                thread.join(timeout=2)


if __name__ == "__main__":
    main()
