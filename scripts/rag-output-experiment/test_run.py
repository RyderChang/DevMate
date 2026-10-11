from decimal import Decimal
import io
import json
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
import urllib.error

import run


class RunnerTests(unittest.TestCase):
    def test_transport_failure_is_unknown_and_not_retried(self):
        with patch.object(run.urllib.request, "build_opener") as opener:
            opener.return_value.open.side_effect = urllib.error.URLError("PRIVATE_FAILURE")
            result = run.dispatch({"messages": []}, "PRIVATE_KEY")
        self.assertEqual(result["status"], "UNKNOWN")
        opener.return_value.open.assert_called_once()
        self.assertNotIn("PRIVATE", json.dumps(result))

    def test_http_error_never_saves_or_reads_error_body(self):
        error = urllib.error.HTTPError("https://api.deepseek.com", 401, "PRIVATE_REASON", {}, io.BytesIO(b"PRIVATE_BODY"))
        with patch.object(run.urllib.request, "build_opener") as opener:
            opener.return_value.open.side_effect = error
            result = run.dispatch({"messages": []}, "PRIVATE_KEY")
        self.assertEqual(result["httpStatus"], 401)
        self.assertEqual(error.fp.tell(), 0)
        self.assertNotIn("PRIVATE", json.dumps(result))

    def test_response_bytes_are_bounded_before_java_parsing(self):
        with patch.object(run.urllib.request, "build_opener") as opener, patch.object(run, "bridge") as bridge:
            response = opener.return_value.open.return_value.__enter__.return_value
            response.read.return_value = b"x" * (run.MAX_RESPONSE_BYTES + 1)
            result = run.dispatch({"messages": []}, "PRIVATE_KEY")
        self.assertEqual(result["failureCategory"], "RESPONSE_SIZE")
        bridge.assert_not_called()

    def test_oversized_request_is_rejected_before_network(self):
        with patch.object(run.urllib.request, "build_opener") as opener:
            with self.assertRaises(ValueError):
                run.dispatch({"messages": ["x" * 98305]}, "PRIVATE_KEY")
        opener.assert_not_called()

    def test_unknown_attempt_keeps_reserve_and_does_not_count_as_success(self):
        reserve = run.reserve_for({"messages": []})
        result = run.summary([{"arm": "baseline", "question": 9, "status": "UNKNOWN", "conservativeChargeCny": str(reserve)}])
        self.assertEqual(result["baseline"]["attempted"], 1)
        self.assertEqual(result["baseline"]["succeeded"], 0)
        self.assertEqual(result["baseline"]["semanticKnown"], 0)
        self.assertEqual(Decimal(result["baseline"]["conservativeChargeCny"]), reserve)
        self.assertEqual(result["baseline"]["expectedEvidenceSlots"], 17)

    def test_frozen_source_drift_is_rejected_before_bridge_or_network(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = json.loads((run.HERE / "frozen-inputs.json").read_text(encoding="utf-8"))
            first = root / manifest["sources"][0]["path"]
            first.parent.mkdir(parents=True, exist_ok=True)
            first.write_text("drift", encoding="utf-8")
            with self.assertRaises(ValueError):
                run.frozen_inputs(root / "unused.json", root)


if __name__ == "__main__":
    unittest.main()
