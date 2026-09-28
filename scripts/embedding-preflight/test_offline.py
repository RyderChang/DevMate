"""Offline tokenizer and HTTP response contracts using only bundled synthetic data."""
import copy
import importlib.metadata
import json
from pathlib import Path
import unittest

from tokenizers import Tokenizer
from contract import validate
from assets import bundled_tokenizer, LOCK

HERE = Path(__file__).resolve().parent
ENV = json.loads((HERE / "environment.json").read_text())
SPEC = ENV["spec"]
TOKENS = json.loads((HERE / "fixtures/embedding-request.json").read_text())["expected_tokens"]


class TokenizerContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if importlib.metadata.version("tokenizers") != "0.22.1":
            raise RuntimeError("wrong tokenizer runtime")
        raw = bundled_tokenizer()
        if LOCK["revision"] != SPEC["revision"]:
            raise RuntimeError("model revision mismatch")
        cls.tokenizer = Tokenizer.from_str(raw)
        cls.cases = json.loads((HERE / "fixtures/tokenizer-cases.json").read_text(encoding="utf-8"))

    def test_official_goldens(self):
        for case in self.cases["cases"]:
            with self.subTest(case=case["name"]):
                text = (SPEC["query_prefix"] if case["role"] == "query" else "") + case["text"]
                ids = self.tokenizer.encode(text).ids
                self.assertEqual(case["ids"], ids)
                self.assertEqual(case["tokens"], len(ids))

    def test_no_truncation_at_application_boundary(self):
        for count in (5999, 6000, 6001):
            ids = self.tokenizer.encode("x " * (count - 2) + "x").ids
            self.assertEqual(count, len(ids))
            self.assertEqual(151643, ids[-1])

    def test_model_normalizes_but_source_is_not_rewritten(self):
        self.assertEqual(self.tokenizer.encode("é").ids, self.tokenizer.encode("e\u0301").ids)
        self.assertNotEqual("é".encode(), "e\u0301".encode())

    def test_special_marker_and_query_prefix_are_counted(self):
        self.assertEqual([151643, 151643], self.tokenizer.encode("<|endoftext|>").ids)
        self.assertGreater(len(self.tokenizer.encode(SPEC["query_prefix"] + "事务").ids),
                           len(self.tokenizer.encode("事务").ids))


class ResponseContract(unittest.TestCase):
    def setUp(self):
        self.good = json.loads((HERE / "fixtures/embedding-valid.json").read_text())

    def check(self, response):
        return validate(json.dumps(response).encode(), model=SPEC["model"], inputs=2, dimensions=1024, tokens=TOKENS)

    def test_reorders_by_index_and_accepts_total_only(self):
        self.good["data"].reverse()
        self.assertEqual(1.0, self.check(self.good)[0][0])

    def test_invalid_vectors_and_usage(self):
        mutations = [
            lambda d: d["data"].pop(),
            lambda d: d["data"][1].update(index=0),
            lambda d: d["data"][0].update(index=True),
            lambda d: d["data"][0]["embedding"].pop(),
            lambda d: d["data"][0].update(embedding=[0] * 1024),
            lambda d: d["data"][0]["embedding"].__setitem__(0, True),
            lambda d: d["data"][0]["embedding"].__setitem__(0, float("inf")),
            lambda d: d["data"][0]["embedding"].__setitem__(0, 1e308),
            lambda d: d["data"][0].update(embedding=[1e-310] * 1024),
            lambda d: d["data"][0]["embedding"].__setitem__(0, 10**400),
            lambda d: d["data"][0]["embedding"].__setitem__(0, 2.0),
            lambda d: d["data"].__setitem__(0, "invalid"),
            lambda d: d.update(model="different-model"),
            lambda d: d.pop("usage"),
            lambda d: d["usage"].update(total_tokens=TOKENS + 1),
            lambda d: d["usage"].update(total_tokens=float(TOKENS)),
            lambda d: d["usage"].update(prompt_tokens=-1),
            lambda d: d.update(usage=[]),
        ]
        for mutation in mutations:
            changed = copy.deepcopy(self.good)
            mutation(changed)
            with self.subTest(mutation=mutations.index(mutation)), self.assertRaises(ValueError):
                self.check(changed)

    def test_utf8_size_and_duplicate_field(self):
        for raw, maximum in ((b"\xff", 1048576), (b'{"model":1,"model":2}', 1048576), (b"{}", 1)):
            with self.subTest(raw=raw), self.assertRaises((ValueError, UnicodeError)):
                validate(raw, model=SPEC["model"], inputs=2, dimensions=1024, tokens=TOKENS, max_bytes=maximum)


if __name__ == "__main__":
    unittest.main()
