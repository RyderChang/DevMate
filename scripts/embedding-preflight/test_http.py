"""Loopback HTTP fixture; no provider or model service is contacted."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import threading
import unittest
import urllib.error
import urllib.request

from contract import validate
from isolation import NoRedirect

HERE = Path(__file__).resolve().parent
SPEC = json.loads((HERE / "environment.json").read_text())["spec"]
FIXTURE = json.loads((HERE / "fixtures/embedding-request.json").read_text())


class HttpContract(unittest.TestCase):
    def setUp(self):
        self.release = threading.Event()
        self.accepted = threading.Event()
        self.mode = "valid"
        self.requests = []
        case = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(handler):
                if handler.path != "/v1/embeddings":
                    handler.send_error(404)
                    return
                length = int(handler.headers.get("Content-Length", "0"))
                if length > 65536:
                    handler.send_error(413)
                    return
                case.requests.append(json.loads(handler.rfile.read(length)))
                case.accepted.set()
                if case.mode == "timeout":
                    case.release.wait(10)
                    return
                if case.mode == "redirect":
                    handler.send_response(307)
                    handler.send_header("Location", "/unapproved")
                    handler.end_headers()
                    return
                if case.mode == "rate_limit":
                    handler.send_response(429)
                    handler.end_headers()
                    return
                body = (HERE / "fixtures/embedding-valid.json").read_bytes()
                if case.mode == "oversize":
                    body = b" " * 1048577
                if case.mode == "utf8":
                    body = b"\xff"
                handler.send_response(200)
                handler.send_header("Content-Type", "application/json; charset=utf-8")
                handler.send_header("Content-Length", str(len(body)))
                handler.end_headers()
                try:
                    handler.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)
        self.assertFalse(self.thread.is_alive())

    def call(self, timeout=3):
        req = urllib.request.Request("http://127.0.0.1:" + str(self.server.server_port) + "/v1/embeddings",
                                     data=json.dumps(FIXTURE["request"]).encode(), method="POST",
                                     headers={"Content-Type": "application/json"})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        with opener.open(req, timeout=timeout) as response:
            body = response.read(SPEC["max_response_bytes"] + 1)
        return validate(body, model=SPEC["model"], inputs=2, dimensions=1024,
                        tokens=FIXTURE["expected_tokens"], max_bytes=SPEC["max_response_bytes"])

    def test_valid_request_and_usage(self):
        self.assertEqual(2, len(self.call()))
        self.assertEqual([FIXTURE["request"]], self.requests)

    def test_size_utf8_and_redirect_rejected(self):
        for mode in ("oversize", "utf8", "redirect"):
            self.mode = mode
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                self.call()
        self.assertEqual(3, len(self.requests))

    def test_timeout_and_rate_limit_have_no_automatic_retry(self):
        self.mode = "timeout"
        with self.assertRaises(TimeoutError):
            self.call(timeout=0.2)
        self.assertTrue(self.accepted.wait(5))
        self.release.set()
        self.mode = "rate_limit"
        with self.assertRaises(urllib.error.HTTPError) as raised:
            self.call()
        self.assertEqual(429, raised.exception.code)
        self.assertEqual(2, len(self.requests))


if __name__ == "__main__":
    unittest.main()
