import importlib.util
import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("distilly_worker", Path(__file__).with_name("worker.py"))
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class WorkerTest(unittest.TestCase):
    chunk = {"material_id": "m1", "chunk_id": "m1:0", "text": "# 接口规范\n接口设计必须校验幂等键，重试不能重复扣款。"}

    def test_mock_claims_have_verifiable_evidence(self):
        result = worker.Model(mode="mock").distill_chunk(self.chunk)
        worker.validate_claims(result["claims"], [self.chunk])

    def test_fabricated_evidence_is_rejected(self):
        result = worker.Model(mode="mock").distill_chunk(self.chunk)
        result["claims"][0]["evidence"][0]["quote"] = "不存在的材料内容"
        with self.assertRaises(ValueError):
            worker.validate_claims(result["claims"], [self.chunk])

    def test_gateway_configuration_does_not_silently_use_mock(self):
        with patch.dict(os.environ, {"MODEL_MODE": "gateway"}, clear=True):
            with self.assertRaises(ValueError):
                worker.Model()

    def test_chat_completions_http_protocol(self):
        observed = []
        class Gateway(BaseHTTPRequestHandler):
            def do_POST(self):
                observed.append((self.path, json.loads(self.rfile.read(int(self.headers["Content-Length"]))), self.headers["Authorization"]))
                result = {"claims": []}
                data = json.dumps({"choices": [{"message": {"content": json.dumps(result)}}]}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            def log_message(self, *_):
                pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), Gateway)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with patch.dict(os.environ, {"MODEL_ALLOW_HTTP": "true", "MODEL_JSON_MODE": "true"}):
                model = worker.Model(mode="gateway", url=f"http://127.0.0.1:{server.server_port}/v1", model="fixture-model", key="test-fixture-key")
                self.assertEqual(model.distill_chunk(self.chunk), {"claims": []})
            self.assertEqual(observed[0][0], "/v1/chat/completions")
            self.assertEqual(observed[0][1]["model"], "fixture-model")
            self.assertEqual(observed[0][1]["response_format"], {"type": "json_object"})
            self.assertEqual(observed[0][2], "Bearer test-fixture-key")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
