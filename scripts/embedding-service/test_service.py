"""Synthetic supervisor/HTTP tests. Actual tokenizer, no weights, no model account."""
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
import time
import unittest
import urllib.request
import uuid
from unittest.mock import patch

from tokenizers import Tokenizer
from spec import bundled_tokenizer, inputs, counts, metadata, SPEC
from server import Supervisor, Server


def frozen_tokenizer():
    return Tokenizer.from_str(bundled_tokenizer())


def synthetic_worker(pipe, ignored):
    pipe.send("READY")
    while True:
        texts, expected = pipe.recv()
        if texts[0] == "timeout":
            time.sleep(30)
        pipe.send((True, [[1.0] + [0.0]*1023 for _ in texts]))


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.db = Path(self.folder.name) / "operations.sqlite"
        self.override = patch("server.tokenizer", frozen_tokenizer)
        self.override.start()
        self.supervisor = Supervisor(Path(self.folder.name), self.db, deadline=0.3, target=synthetic_worker)

    def tearDown(self):
        self.supervisor.close()
        self.override.stop()
        self.folder.cleanup()

    def body(self, text="synthetic"):
        return {"spec": SPEC["id"], "operation_id": str(uuid.uuid4()), "input": [text]}

    def test_exact_boundaries_nfc_special_markers_and_padding_limit(self):
        tokens = self.supervisor.tokens
        for value in (5999, 6000):
            self.assertEqual([value], counts(tokens, ["x "*(value-2)+"x"], inference=True))
        with self.assertRaises(ValueError):
            counts(tokens, ["x "*5999+"x"])
        self.assertEqual(counts(tokens, ["é"]), counts(tokens, ["e\u0301"]))
        self.assertEqual([2], counts(tokens, ["<|endoftext|>"]))
        with self.assertRaises(ValueError):
            counts(tokens, ["x "*2998+"x", "x "*1498+"x", "x"], inference=True)
        with self.assertRaises(ValueError):
            inputs({"spec": SPEC["id"], "input": ["   "]})

    def test_inference_reply_and_replay_do_not_compute_again(self):
        body = self.body()
        code, response = self.supervisor.embed(body)
        self.assertEqual(200, code)
        self.assertEqual(metadata()["fingerprint"], response["fingerprint"])
        self.assertEqual("SUCCEEDED", self.supervisor.operation(body["operation_id"])["state"])
        self.assertEqual(409, self.supervisor.embed(body)[0])

    def test_deadline_terminates_and_joins_before_recording_ended(self):
        body = self.body("timeout")
        started = time.monotonic()
        self.assertEqual(503, self.supervisor.embed(body)[0])
        self.assertLess(time.monotonic()-started, 8)
        self.assertFalse(self.supervisor.process.is_alive())
        self.assertEqual("TERMINATED", self.supervisor.operation(body["operation_id"])["state"])

    def test_busy_rejection_has_no_hidden_queue_or_new_worker(self):
        self.assertTrue(self.supervisor.slot.acquire(blocking=False))
        pid = self.supervisor.process.pid
        try:
            body = self.body()
            self.assertEqual(429, self.supervisor.embed(body)[0])
            self.assertEqual("ABSENT", self.supervisor.operation(body["operation_id"])["state"])
            self.assertEqual(pid, self.supervisor.process.pid)
        finally:
            self.supervisor.slot.release()

    def test_http_is_loopback_and_uses_complete_contract(self):
        server = Server(("127.0.0.1", 0), self.supervisor)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            address = "http://127.0.0.1:"+str(server.server_port)
            body = self.body()
            request = urllib.request.Request(address+"/embeddings", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=5) as response:
                value = json.load(response)
            self.assertEqual(body["operation_id"], value["operation_id"])
            with urllib.request.urlopen(address+"/operations/"+body["operation_id"], timeout=5) as response:
                self.assertEqual("SUCCEEDED", json.load(response)["state"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(5)

    def test_restart_preserves_uncertain_operation_without_false_termination(self):
        identity = str(uuid.uuid4())
        self.supervisor.db.execute("INSERT INTO operations VALUES(?,?,'RUNNING',?)", (identity, "frozen", time.time()))
        self.supervisor.db.commit()
        self.supervisor.close()
        self.supervisor = Supervisor(Path(self.folder.name), self.db, target=synthetic_worker)
        self.assertEqual("UNKNOWN", self.supervisor.operation(identity)["state"])


if __name__ == "__main__":
    unittest.main()
