#!/usr/bin/env python3
"""Start real Java API + Python Worker + HTTP model fixture, then exercise the flow."""
import json
from collections import Counter
import os
import shutil
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
    checkpoint_calls = Counter()
    release_checkpoint = False
    def log_message(self, *_):
        pass
    def do_POST(self):
        if self.path != "/v1/chat/completions" or self.headers.get("Authorization") != "Bearer fixture-key":
            self.send_error(401)
            return
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        payload = json.loads(request["messages"][1]["content"])
        assert request.get("max_tokens") == (4096 if "chunk_id" in payload else 2048)
        assert "max_completion_tokens" not in request
        Gateway.calls += 1
        if "chunk_id" in payload:
            if payload.get("topic") != {"title": "接口评审经验"}:
                self.send_error(422, "topic context missing")
                return
            if "断点测试块" in payload["text"]:
                marker = payload["text"].splitlines()[-1].split("：", 1)[0]
                Gateway.checkpoint_calls[marker] += 1
                if marker == "断点测试块5" and not Gateway.release_checkpoint:
                    self.send_error(503, "synthetic transient failure")
                    return
            quote = next(line for line in reversed(payload["text"].splitlines()) if line.strip())[:100]
            output = {"claims": [{"section": "接口规范", "text": quote, "evidence": [{
                "material_id": payload["material_id"], "chunk_id": payload["chunk_id"], "quote": quote}]}]}
        else:
            claim = payload["claims"][0]
            output = {"answer": claim["text"], "citations": [claim["id"]]}
        finish = "length" if "合成截断测试材料" in payload.get("text", "") else "stop"
        body = json.dumps({"choices": [{"finish_reason":finish,"message": {"content": json.dumps(output, ensure_ascii=False)}}]}).encode()
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
    port = int(os.getenv("WORK_E2E_PORT", "18084"))
    gateway = ThreadingHTTPServer(("127.0.0.1", int(os.getenv("WORK_E2E_GATEWAY_PORT", "18085"))), Gateway)
    thread = threading.Thread(target=gateway.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{port}"
    processes = []
    with tempfile.TemporaryDirectory(prefix="distilly-e2e-") as tmp:
        runtime_jar = Path(tmp) / JAR.name
        shutil.copy2(JAR, runtime_jar)
        log = Path(tmp) / "runtime.log"
        env = {**os.environ, "PLATFORM_DEMO": "true", "PLATFORM_WORKER_TOKEN": WORKER_TOKEN,
               "PLATFORM_PORT": str(port), "PLATFORM_BIND": "127.0.0.1",
               "PLATFORM_DB_URL": "jdbc:h2:file:" + str(Path(tmp) / "db") + ";DATABASE_TO_LOWER=TRUE",
               "PLATFORM_DB_USER": "sa", "PLATFORM_DB_PASSWORD": "",
               "PLATFORM_API_URL": base, "MODEL_MODE": "gateway",
               "MODEL_BASE_URL": f"http://127.0.0.1:{gateway.server_port}/v1",
               "MODEL_ALLOW_HTTP": "true", "MODEL_NAME": "http-test-fixture", "MODEL_API_KEY": "fixture-key"}
        if not real_model:env.update({'MODEL_OUTPUT_TOKEN_PARAMETER': 'max_tokens', 'MODEL_DEFAULT_OUTPUT_TOKENS': '4096', 'MODEL_DISTILL_OUTPUT_TOKENS': '4096', 'MODEL_KNOWLEDGE_ANSWER_OUTPUT_TOKENS': '2048', 'MODEL_PERSONA_ANALYSIS_OUTPUT_TOKENS': '4096', 'MODEL_PERSONA_BUILD_OUTPUT_TOKENS': '8192', 'VISION_OUTPUT_TOKEN_PARAMETER': 'max_tokens', 'MODEL_TEMPERATURE': '0', 'VISION_TEMPERATURE': '0'})
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

            def upload_fixture(project, filename, content):
                boundary = "distilly-checkpoint-boundary"
                data = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
                        f"Content-Type: text/markdown\r\n\r\n{content}\r\n--{boundary}--\r\n").encode()
                request = urllib.request.Request(base + f"/api/projects/{project}/materials", data,
                         {"Authorization": "Bearer demo-owner", "Content-Type": "multipart/form-data; boundary=" + boundary})
                with urllib.request.urlopen(request, timeout=10) as response:
                    return json.load(response)["id"]

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
                p = subprocess.Popen([java, "-jar", str(runtime_jar)], cwd=tmp, env=env, stdout=output, stderr=subprocess.STDOUT)
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
                worker_process = subprocess.Popen([sys.executable, str(WORKER)], cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
                processes.append(worker_process)
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
                published = api(f"/api/projects/{project}/published-knowledge/page", token="demo-viewer")
                assert published["total"] == 1
                entry = published["items"][0]
                if not real_model:
                    truncated_profile=api(f"/api/projects/{project}/profiles", {"title":"接口评审经验"})["id"]
                    truncated_material=upload_fixture(project,"truncated.md","合成截断测试材料，不应成为候选知识。")
                    calls_before=Gateway.calls
                    truncated=api(f"/api/profiles/{truncated_profile}/distill", {"material_ids":[truncated_material]})
                    deadline=time.monotonic()+20
                    while time.monotonic()<deadline:
                        failed=api("/api/jobs/"+truncated["id"])
                        if failed["status"]=="FAILED":break
                        assert failed["status"]!="SUCCEEDED","truncated knowledge was accepted"
                        time.sleep(.2)
                    else:raise AssertionError("truncated knowledge did not fail")
                    assert failed["error_code"]=="MODEL_OUTPUT_LIMIT" and failed["attempt"]==1
                    assert Gateway.calls==calls_before+1,"output limit was automatically retried"
                    assert api(f"/api/profiles/{truncated_profile}/versions")==[]
                    assert api(f"/api/projects/{project}/published-knowledge/page",token="demo-viewer")["total"]==1
                original_version = version
                revision = api(f"/api/profiles/{profile}/claims/{entry['claim_id']}",
                               {"version_id": version, "section": entry["section"],
                                "text": entry["text"] + " 人工修订：重复请求仍需使用同一幂等键。", "note": "人工校对"}, method="PUT")
                version = revision["version_id"]
                assert revision["changed"] and version != original_version
                revised = api(f"/api/projects/{project}/published-knowledge/page?q=" + urllib.parse.quote("人工修订"), token="demo-viewer")
                assert revised["total"] == 1 and revised["items"][0]["version_id"] == version
                previous = next(v for v in api(f"/api/profiles/{profile}/versions") if v["id"] == original_version)
                assert "人工修订" not in previous["work"]
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
                server = start_api()
                versions = api(f"/api/profiles/{profile}/versions", token="demo-viewer")
                assert versions[0]["id"] == version
                api(f"/api/profiles/{profile}/jobs/{job['id']}/experience", method="DELETE")
                assert api(f"/api/profiles/{profile}/versions", token="demo-viewer") == []
                assert api(f"/api/materials/{material}")["id"] == material
                for path in (f"/api/profiles/{profile}/download", f"/api/profiles/{profile}/questions"):
                    try:
                        api(path, {"question": "删除后还能回答吗？"} if path.endswith("questions") else None, token="demo-viewer")
                        raise AssertionError("deleted knowledge remained usable")
                    except urllib.error.HTTPError as error:
                        assert error.code == 409
                api(f"/api/jobs/{job['id']}/restore", method="POST")
                assert api(f"/api/jobs/{job['id']}")["version_status"] == "DELETED"
                if not real_model:
                    long_topic = api(f"/api/projects/{project}/profiles", {"title": "接口评审经验"})["id"]
                    long_text = "前" * 4200 + "\n\n第二块：接口重试必须沿用幂等键。" + "尾" * 4000
                    long_material = upload_fixture(project, "long-boundaries.md", long_text)
                    preflight = api(f"/api/projects/{project}/distill/preflight",
                                    {"profile_id": long_topic, "material_ids": [long_material]})
                    assert preflight["chunk_count"] == 2
                    long_job = api(f"/api/profiles/{long_topic}/distill", {"material_ids": [long_material]})
                    long_version = wait_job(long_job)["version_id"]
                    api(f"/api/versions/{long_version}/review", {"approve": True, "note": "长材料定位验收"})
                    long_rows = api(f"/api/projects/{project}/published-knowledge/page?profile_id={long_topic}")["items"]
                    second = next(row for row in long_rows if "第二块" in row["text"])
                    assert second["evidence"][0]["chunk_id"] == long_material + ":1"
                    assert second["evidence"][0]["quote"] not in long_text[6000:]
                    revised_long = api(f"/api/profiles/{long_topic}/claims/{second['claim_id']}",
                                       {"version_id": long_version, "section": "重试规范",
                                        "text": "接口重试必须沿用幂等键。", "note": "长材料原始分块核验"}, method="PUT")
                    assert revised_long["changed"]
                    checkpoint_topic = api(f"/api/projects/{project}/profiles", {"title": "接口评审经验"})["id"]
                    materials = [upload_fixture(project, f"checkpoint-{i}.md",
                                 f"# 虚构验收记录\n断点测试块{i}：接口必须校验幂等键并记录请求编号。") for i in range(6)]
                    checkpoint_job = api(f"/api/profiles/{checkpoint_topic}/distill", {"material_ids": materials})
                    checkpoint_path = "/api/jobs/" + checkpoint_job["id"]
                    deadline = time.monotonic() + 40
                    while time.monotonic() < deadline:
                        state = api(checkpoint_path)
                        if state["status"] == "FAILED":
                            break
                        time.sleep(.1)
                    else:
                        raise AssertionError("checkpoint failure stage timed out")
                    assert state["checkpoint_count"] == 5 and state["progress"]["completed"] == 5
                    assert [Gateway.checkpoint_calls[f"断点测试块{i}"] for i in range(6)] == [1, 1, 1, 1, 1, 3]
                    worker_process.terminate()
                    worker_process.wait(timeout=10)
                    server.terminate()
                    server.wait(timeout=10)
                    server = start_api()
                    assert api(checkpoint_path)["checkpoint_count"] == 5
                    api(checkpoint_path + "/retry", method="POST")
                    api(checkpoint_path + "/pause", method="POST")
                    assert api(checkpoint_path)["status"] == "PAUSED"
                    assert api(checkpoint_path)["progress"]["completed"] == 5
                    server.terminate()
                    server.wait(timeout=10)
                    server = start_api()
                    assert api(checkpoint_path)["status"] == "PAUSED"
                    assert api(checkpoint_path)["checkpoint_count"] == 5
                    api(checkpoint_path + "/resume", method="POST")
                    Gateway.release_checkpoint = True
                    worker_process = subprocess.Popen([sys.executable, str(WORKER)], cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
                    processes.append(worker_process)
                    resumed = wait_job(checkpoint_job)
                    assert resumed["claim_count"] == 6
                    assert [Gateway.checkpoint_calls[f"断点测试块{i}"] for i in range(6)] == [1, 1, 1, 1, 1, 4]
                    assert api(checkpoint_path)["checkpoint_count"] == 6
                    api(f"/api/versions/{resumed['version_id']}/review", {"approve": True, "note": "断点恢复验收"})
                if not real_model:
                    assert Gateway.calls >= 2
                print(json.dumps({"status": "PASS", "flow": "upload → HTTP model distill → review → publish → manual revision → search → HTTP model answer → download → permission rejection → restart persistence → withdraw knowledge → reject use → task restore does not restore knowledge",
                                  "model": env["MODEL_NAME"], "gateway_calls": None if real_model else Gateway.calls,
                                  "checkpoint_resume": None if real_model else "5 saved blocks survive failure, pause and two API restarts; only block 6 recomputed"}, ensure_ascii=False))
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
