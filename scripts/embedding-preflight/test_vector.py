"""Real Qdrant/MySQL contracts; the SQL journal is an isolated proposal fixture."""
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import socket
import threading
import unittest
import uuid

from isolation import ENV, Isolation, docker, request

SERVICES = None
SPEC = ENV["spec"]["id"]
OWNER = "9007199254740993"
PROJECT = "42"


def source(document, processing, index):
    return f"{document}/{processing}/{index}/{SPEC}"


def point(document=101, processing=31, index=41, *, owner=OWNER, project=PROJECT, ordinal=0):
    key = source(document, processing, index)
    identity = f"{owner}/{project}/{key}/{ordinal}"
    return {"id": str(uuid.uuid5(uuid.NAMESPACE_URL, identity)), "vector": [1.0] + [0.0] * 1023,
            "payload": {"owner_user_id": owner, "project_id": project, "source_key": key,
                        "document_id": str(document), "processing_id": str(processing),
                        "index_id": str(index), "ordinal": ordinal, "spec": SPEC}}


def gate(keys, owner=OWNER, project=PROJECT):
    return {"must": [{"key": "owner_user_id", "match": {"value": owner}},
                     {"key": "project_id", "match": {"value": project}},
                     {"key": "spec", "match": {"value": SPEC}},
                     {"key": "source_key", "match": {"any": keys}}]}


class VectorContract(unittest.TestCase):
    def setUp(self):
        self.collection = "devmate019_" + uuid.uuid4().hex
        self.path = "/collections/" + self.collection
        code, _ = self.call("PUT", self.path, {"vectors": {"size": 1024, "distance": "Cosine"}})
        self.assertEqual(200, code)
        for field in ("owner_user_id", "project_id", "source_key", "spec"):
            self.assertEqual(200, self.call("PUT", self.path + "/index?wait=true",
                                          {"field_name": field, "field_schema": "keyword"})[0])
        SERVICES.sql("CREATE DATABASE IF NOT EXISTS devmate019; USE devmate019; "
                     "DROP TABLE IF EXISTS journal; CREATE TABLE journal ("
                     "id VARCHAR(36) PRIMARY KEY, owner BIGINT NOT NULL, project BIGINT NOT NULL, "
                     "document BIGINT NOT NULL, source_key VARCHAR(180) NOT NULL, "
                     "visible BOOLEAN NOT NULL DEFAULT FALSE, state VARCHAR(16) NOT NULL, "
                     "lease_version BIGINT NOT NULL);")

    def tearDown(self):
        self.assertEqual(200, self.call("DELETE", self.path)[0])

    def call(self, method, path, data=None, base=None):
        return request(base or SERVICES.base, method, path, data)

    def upsert(self, points, base=None):
        return self.call("PUT", self.path + "/points?wait=true", {"points": points}, base)

    def count(self):
        return self.call("POST", self.path + "/points/count", {"exact": True})[1]["result"]["count"]

    def query(self, filters):
        code, result = self.call("POST", self.path + "/points/query", {
            "query": [1.0] + [0.0] * 1023, "limit": 20, "filter": filters, "with_payload": True})
        self.assertEqual(200, code)
        return result["result"]["points"]

    def journal(self, p, state="DISPATCHED"):
        payload = p["payload"]
        SERVICES.sql(f"INSERT INTO devmate019.journal VALUES ('{p['id']}',{payload['owner_user_id']},"
                     f"{payload['project_id']},{payload['document_id']},'{payload['source_key']}',FALSE,'{state}',1);")

    def visible(self):
        return int(SERVICES.sql(f"SELECT COUNT(*) FROM devmate019.journal WHERE owner={OWNER} "
                                f"AND project={PROJECT} AND visible=TRUE AND state='CONFIRMED';").strip())

    def test_collection_contract_and_invalid_dimension(self):
        self.assertEqual(409, self.call("PUT", self.path, {"vectors": {"size": 1024, "distance": "Cosine"}})[0])
        bad = point()
        bad["vector"] = [1.0, 0.0]
        self.assertEqual(400, self.upsert([bad])[0])
        config = self.call("GET", self.path)[1]["result"]["config"]["params"]["vectors"]
        self.assertEqual({"size": 1024, "distance": "Cosine"}, config)

    def test_upsert_idempotence_and_retired_generation_separation(self):
        active, retired = point(), point(processing=32)
        self.assertNotEqual(active["id"], retired["id"])
        for points in ([active], [active], [retired]):
            self.assertEqual(200, self.upsert(points)[0])
        self.assertEqual(2, self.count())
        hits = self.query(gate([active["payload"]["source_key"]]))
        self.assertEqual([active["id"]], [hit["id"] for hit in hits])
        self.assertEqual({"owner_user_id", "project_id", "source_key", "document_id", "processing_id",
                          "index_id", "ordinal", "spec"}, set(hits[0]["payload"]))

    def test_full_tuple_filter_and_bigint_precision(self):
        good = [point(), point(102, 32, 42)]
        decoys = [point(101, 32, 42), point(102, 31, 41), point(owner="9007199254740992"), point(project="43")]
        self.assertEqual(200, self.upsert(good + decoys)[0])
        hits = self.query(gate([p["payload"]["source_key"] for p in good]))
        self.assertEqual({p["id"] for p in good}, {hit["id"] for hit in hits})
        self.assertEqual(OWNER, hits[0]["payload"]["owner_user_id"])
        self.journal(good[0])
        self.assertEqual(OWNER, SERVICES.sql("SELECT owner FROM devmate019.journal;").strip())

    def test_partial_batches_do_not_imply_publication(self):
        first, invalid = point(), point(ordinal=1)
        invalid["vector"] = [1.0]
        self.journal(first)
        self.assertEqual(200, self.upsert([first])[0])
        self.assertEqual(400, self.upsert([invalid])[0])
        self.assertEqual(1, self.count())
        self.assertEqual(0, self.visible())

    def test_restart_preserves_points_and_recovery_locator(self):
        p = point()
        self.journal(p, "UNKNOWN")
        self.assertEqual(200, self.upsert([p])[0])
        docker("restart", SERVICES.qdrant, SERVICES.mysql, timeout=60)
        SERVICES.wait_ready()
        self.assertEqual(1, self.count())
        self.assertEqual("UNKNOWN", SERVICES.sql("SELECT state FROM devmate019.journal;").strip())
        self.assertEqual(0, self.visible())

    def test_lost_ack_retains_durable_unknown_operation(self):
        p = point()
        self.journal(p)
        upstream = SERVICES.base

        class DropAck(BaseHTTPRequestHandler):
            def do_PUT(handler):
                body = json.loads(handler.rfile.read(int(handler.headers["Content-Length"])))
                code, _ = request(upstream, "PUT", handler.path, body)
                if code != 200:
                    handler.send_error(502)
                    return
                handler.connection.shutdown(socket.SHUT_RDWR)
                handler.connection.close()

            def log_message(self, *args):
                pass

        proxy = ThreadingHTTPServer(("127.0.0.1", 0), DropAck)
        thread = threading.Thread(target=proxy.serve_forever, daemon=True)
        thread.start()
        try:
            with self.assertRaises((OSError, http.client.RemoteDisconnected)):
                self.upsert([p], "http://127.0.0.1:" + str(proxy.server_port))
        finally:
            proxy.shutdown()
            proxy.server_close()
            thread.join(5)
        SERVICES.sql("UPDATE devmate019.journal SET state='UNKNOWN';")
        self.assertEqual(1, self.count())
        self.assertEqual(0, self.visible())
        self.assertEqual(1, int(SERVICES.sql("SELECT COUNT(*) FROM devmate019.journal WHERE state='UNKNOWN';")))

    def test_delete_before_late_write_cannot_prove_quiescence(self):
        p = point()
        self.journal(p, "UNKNOWN")
        started, release = threading.Event(), threading.Event()
        errors = []

        def delayed_write():
            started.set()
            if not release.wait(5):
                errors.append("gate timeout")
                return
            try:
                if self.upsert([p])[0] != 200:
                    errors.append("write failed")
            except Exception:
                errors.append("write failed")

        thread = threading.Thread(target=delayed_write, daemon=True)
        thread.start()
        self.assertTrue(started.wait(5))
        try:
            self.assertEqual(200, self.call("POST", self.path + "/points/delete?wait=true", {"points": [p["id"]]})[0])
            self.assertEqual(0, self.count())
            self.assertEqual(0, self.visible())
            release.set()
            thread.join(10)
            self.assertFalse(thread.is_alive())
            self.assertEqual([], errors)
            self.assertEqual(1, self.count())
            self.assertEqual(0, self.visible())
            self.assertEqual(1, int(SERVICES.sql("SELECT COUNT(*) FROM devmate019.journal WHERE state='UNKNOWN';")))
            for _ in range(2):
                self.assertEqual(200, self.call("POST", self.path + "/points/delete?wait=true", {"points": [p["id"]]})[0])
            self.assertEqual(0, self.count())
        finally:
            release.set()
            thread.join(10)


if __name__ == "__main__":
    SERVICES = Isolation()
    try:
        SERVICES.start()
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(VectorContract))
    finally:
        SERVICES.close()
    raise SystemExit(0 if result.wasSuccessful() else 1)
