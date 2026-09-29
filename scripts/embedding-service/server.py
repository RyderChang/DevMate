"""Loopback supervisor with persistent operation identity and kill/join deadlines."""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import multiprocessing
import os
from pathlib import Path
import socket
import sqlite3
import threading
import time
import uuid

from spec import metadata, tokenizer, inputs, counts, SPEC
from worker import serve
from runtime_limits import exclusive, verify_linux_envelope

JOURNAL_LIMIT = 100000

def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate key")
        result[key] = value
    return result


class Supervisor:
    def __init__(self, model_dir, journal, deadline=295, target=serve):
        self.identity_lock = exclusive(journal)
        self.tokens = tokenizer()
        self.model_dir, self.deadline, self.target = model_dir, deadline, target
        self.slot = threading.Lock()
        self.db_lock = threading.Lock()
        self.db = sqlite3.connect(journal, check_same_thread=False)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.execute("CREATE TABLE IF NOT EXISTS operations(id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, state TEXT NOT NULL, created REAL NOT NULL)")
        self.db.execute("UPDATE operations SET state='UNKNOWN' WHERE state='RUNNING'")
        self.db.commit()
        self.process = None
        self.connection = None
        self.start()

    def start(self, timeout=120):
        if self.process is not None and self.process.is_alive():
            raise RuntimeError("worker still alive")
        context = multiprocessing.get_context("spawn")
        self.connection, child = context.Pipe()
        self.process = context.Process(target=self.target, args=(child, self.model_dir), daemon=True)
        self.process.start()
        child.close()
        if not self.connection.poll(timeout) or self.connection.recv() != "READY":
            self.stop()
            raise RuntimeError("worker readiness failed")

    def stop(self):
        if self.process is not None:
            if self.process.is_alive():
                self.process.terminate()
            self.process.join(5)
            if self.process.is_alive():
                self.process.kill()
                self.process.join(5)
            if self.process.is_alive():
                raise RuntimeError("worker termination unconfirmed")
        if self.connection is not None:
            self.connection.close()

    def operation(self, identity):
        with self.db_lock:
            row = self.db.execute("SELECT state FROM operations WHERE id=?", (identity,)).fetchone()
        return {**metadata(), "operation_id": identity, "state": row[0] if row else "ABSENT"}

    def embed(self, body):
        texts = inputs(body)
        expected = counts(self.tokens, texts, inference=True)
        identity = str(uuid.UUID(body["operation_id"]))
        if identity != body["operation_id"]:
            raise ValueError("canonical operation required")
        fingerprint = hashlib.sha256(json.dumps([SPEC["id"], texts], ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()
        # Check identity, acquire the slot and insert atomically. A replay while
        # busy must never receive proof that an existing operation was not started.
        with self.db_lock:
            if self.db.execute("SELECT id FROM operations WHERE id=?", (identity,)).fetchone():
                return 409, {**metadata(), "code": "OPERATION_REPLAY"}
            if not self.slot.acquire(blocking=False):
                return 429, {**metadata(), "operation_id": identity, "state": "NOT_STARTED", "code": "BUSY"}
            try:
                self.db.execute("DELETE FROM operations WHERE id IN (SELECT id FROM operations WHERE state IN ('SUCCEEDED','TERMINATED') AND created<? LIMIT 20)", (time.time()-86400,))
                if self.db.execute("SELECT COUNT(*) FROM operations").fetchone()[0] >= JOURNAL_LIMIT:
                    self.db.commit()
                    self.slot.release()
                    return 429, {**metadata(), "operation_id": identity, "state": "NOT_STARTED", "code": "JOURNAL_FULL"}
                self.db.execute("INSERT INTO operations VALUES(?,?,'RUNNING',?)", (identity, fingerprint, time.time()))
                self.db.commit()
            except BaseException:
                self.slot.release()
                raise
        try:
            deadline = time.monotonic() + self.deadline
            ended = False
            try:
                if not self.process.is_alive():
                    self.start(timeout=max(0.001, min(120, deadline-time.monotonic())))
                self.connection.send((texts, expected))
                if not self.connection.poll(max(0.001, deadline-time.monotonic())):
                    self.stop()  # Only successful join establishes TERMINATED.
                    ended = True
                    result = None
                else:
                    success, result = self.connection.recv()
                    ended = True
                    if not success:
                        result = None
            except (OSError, EOFError):
                self.stop()
                ended = True
                result = None
            with self.db_lock:
                self.db.execute("UPDATE operations SET state=? WHERE id=?", ("SUCCEEDED" if result is not None else "TERMINATED" if ended else "UNKNOWN", identity))
                self.db.commit()
            if result is None:
                return 503, {**metadata(), "operation_id": identity, "code": "WORKER_TERMINATED"}
            return 200, {**metadata(), "operation_id": identity, "model": SPEC["model"], "object": "list",
                         "data": [{"index": index, "object": "embedding", "embedding": vector} for index, vector in enumerate(result)],
                         "usage": {"total_tokens": sum(expected), "prompt_tokens": sum(expected)}}
        finally:
            self.slot.release()

    def close(self):
        self.stop()
        self.db.close()
        self.identity_lock.close()


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 4
    def __init__(self, address, supervisor):
        self.supervisor = supervisor
        self.connections = threading.BoundedSemaphore(4)
        super().__init__(address, Handler)

    def process_request(self, request, address):
        if not self.connections.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, address)
        except BaseException:
            self.connections.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.connections.release()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send_json(self, code, body):
        raw = json.dumps(body, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()
        if len(raw) > 1048576:
            raise ValueError("response limit")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path == "/spec":
            self.send_json(200, metadata())
        elif self.path.startswith("/operations/"):
            try:
                identity = str(uuid.UUID(self.path[12:]))
                self.send_json(200, self.server.supervisor.operation(identity))
            except ValueError:
                self.send_json(400, {"code": "INVALID_OPERATION"})
        else:
            self.send_json(404, {"code": "NOT_FOUND"})

    def do_POST(self):
        try:
            self.connection.settimeout(10)
            length = int(self.headers["Content-Length"])
            if not 0 < length <= 524288 or self.headers.get("Transfer-Encoding"):
                raise ValueError("request limit")
            deadline = time.monotonic() + 10
            raw = bytearray()
            while len(raw) < length:
                self.connection.settimeout(max(0.001, deadline-time.monotonic()))
                part = self.rfile.read1(min(16384, length-len(raw)))
                if not part or time.monotonic() >= deadline:
                    raise ValueError("request deadline")
                raw.extend(part)
            body = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=unique)
            if self.path == "/tokenize":
                texts = inputs(body)
                self.send_json(200, {**metadata(), "counts": counts(self.server.supervisor.tokens, texts)})
            elif self.path == "/embeddings":
                code, value = self.server.supervisor.embed(body)
                self.send_json(code, value)
            else:
                self.send_json(404, {"code": "NOT_FOUND"})
        except (ValueError, KeyError, TypeError, socket.timeout):
            self.send_json(400, {"code": "INVALID_INPUT"})
        except (BrokenPipeError, ConnectionResetError):
            pass  # Durable operation result survives a lost HTTP acknowledgement.


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8091)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--journal", type=Path, required=True)
    args = parser.parse_args()
    verify_linux_envelope()
    # These paths are operator startup inputs only, never accepted over HTTP.
    supervisor = Supervisor(args.model_dir.resolve(), args.journal.resolve())
    server = Server(("127.0.0.1", args.port), supervisor)
    try:
        print(json.dumps({**metadata(), "port": server.server_port, "ready": True}), flush=True)
        server.serve_forever(poll_interval=0.2)
    finally:
        server.server_close()
        supervisor.close()


if __name__ == "__main__":
    multiprocessing.freeze_support()
    main()
