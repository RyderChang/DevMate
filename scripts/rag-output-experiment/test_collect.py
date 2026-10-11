import json
import unittest
from collect import collect


class CollectionTests(unittest.TestCase):
    def setUp(self):
        self.targets = {"E16": {"sourceSha256": "a" * 64, "startLine": 49, "endLine": 52},
                        "E17": {"sourceSha256": "b" * 64, "startLine": 7, "endLine": 7}}
        self.citations = [{"citationId": "C2", "source": {"sourceSha256": "a" * 64,
                          "startLine": 41, "endLine": 58, "text": "PRIVATE_SOURCE"}},
                          {"citationId": "C4", "source": {"sourceSha256": "b" * 64,
                          "startLine": 1, "endLine": 13}}]

    def test_no_inline_markers_still_covers_both_sources(self):
        result = collect("PRIVATE_ANSWER", self.citations, self.targets)
        for slot in ("E16", "E17"):
            self.assertEqual(result["evidenceSlots"][slot]["sourceCoverage"], "COVERED")
            self.assertEqual(result["evidenceSlots"][slot]["inlineEvidenceIds"], [])
            self.assertEqual(result["evidenceSlots"][slot]["semanticVerdict"], "UNKNOWN")
        self.assertNotIn("PRIVATE", json.dumps(result))

    def test_inline_intersection_does_not_prove_semantics_or_binding(self):
        result = collect("Unrelated assertion [C2]", self.citations, self.targets)
        slot = result["evidenceSlots"]["E16"]
        self.assertEqual(slot["inlineEvidenceIds"], ["C2"])
        self.assertEqual(slot["claimCitationBinding"], "UNKNOWN")
        self.assertEqual(result["evidenceSlots"]["E17"]["inlineEvidenceIds"], [])

    def test_missing_and_empty_citations_are_distinct(self):
        self.assertEqual(collect(None, None, self.targets)["evidenceSlots"]["E16"]["sourceCoverage"], "UNKNOWN")
        self.assertEqual(collect("answer", [], self.targets)["evidenceSlots"]["E16"]["sourceCoverage"], "NOT_COVERED")

    def test_wrong_sha_and_partial_line_range_do_not_cover(self):
        for source in ({"sourceSha256": "c" * 64, "startLine": 1, "endLine": 100},
                       {"sourceSha256": "a" * 64, "startLine": 50, "endLine": 58}):
            result = collect("[C2]", [{"citationId": "C2", "source": source}], self.targets)
            self.assertEqual(result["evidenceSlots"]["E16"]["sourceCoverage"], "NOT_COVERED")

    def test_only_fixed_semantic_verdicts_can_be_saved(self):
        result = collect("answer", self.citations, self.targets, {"E16": "SUPPORTED"})
        self.assertEqual(result["evidenceSlots"]["E16"]["semanticVerdict"], "SUPPORTED")
        with self.assertRaises(ValueError):
            collect("answer", self.citations, self.targets, {"E16": "PRIVATE_ANSWER"})


if __name__ == "__main__":
    unittest.main()
