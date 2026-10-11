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

    def test_unvalidated_citation_ids_and_markers_cannot_persist_body_text(self):
        for citation_id in ("CPRIVATE_ANSWER", "C6", "C01", None):
            with self.subTest(citation_id=citation_id):
                with self.assertRaisesRegex(ValueError, "^Invalid citation ID$"):
                    collect("answer", [{"citationId": citation_id, "source": {}}], self.targets)
        for answer in ("[CPRIVATE_ANSWER]", "[C6]", "[C01]"):
            with self.subTest(answer=answer):
                with self.assertRaisesRegex(ValueError, "^Invalid inline citation ID$"):
                    collect(answer, self.citations, self.targets)

    def test_not_applicable_is_not_a_core_semantic_verdict(self):
        with self.assertRaisesRegex(ValueError, "^Invalid semantic verdict$"):
            collect("answer", self.citations, self.targets, {"E16": "NOT_APPLICABLE"})


if __name__ == "__main__":
    unittest.main()
