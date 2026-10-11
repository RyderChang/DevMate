"""Synthetic data only. No private account, paid client or historical answers."""
import contextlib
import copy
import io
import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from review import AtomicSink, ReviewError, Session, demo_context, load_context, review_transient

ANSWER = 'ANSWER_SENTINEL甲。[C1] 额外乙。重复甲。相反甲。'
RECEIPT = {'status': 'SUCCEEDED', 'usage': {'inputTokens': 7, 'outputTokens': 3, 'totalTokens': 10}, 'reservedMicros': 20}


def claim(start=0, end=17, kind='CORE', ids=None):
    return {'spans': [[start, end]], 'kind': kind, 'coreIds': ['E1', 'E2'] if ids is None else ids,
            'support': 'SUPPORTED', 'supportType': 'DIRECT',
            'checks': [{'sourceId': 'S1', 'startLine': 1, 'endLine': 1}],
            'links': [{'citationId': 'C1', 'correspondence': 'SUPPORTS', 'supportType': 'DIRECT',
                       'binding': 'GLOBAL_ONLY', 'markerSpan': None}], 'unlinkedBinding': None}


def finish(q=1, ids=('C1', 'C2')):
    from review import CORE
    return {'inventoryComplete': True, 'secondPassComplete': True,
            'coreDisposition': {e: 'PRESENT' for e in CORE[q]},
            'citationRelevance': {c: 'RELEVANT' if c == 'C1' else 'IRRELEVANT' for c in ids},
            'abstentionVerdict': 'SUPPORTED' if q == 10 else 'NOT_APPLICABLE'}


class ReviewTest(unittest.TestCase):
    def session(self, q=1, ids=None, answer=ANSWER):
        return Session(demo_context(q, returned=ids), answer, copy.deepcopy(RECEIPT))

    def assert_safe(self, session, directory=None):
        output = json.dumps(session.record())
        self.assertNotIn('ANSWER_SENTINEL', output)
        self.assertNotIn('SYNTHETIC_SOURCE_SENTINEL', output)
        if directory:
            for p in Path(directory).iterdir():
                text = p.read_text(encoding='utf8')
                self.assertNotIn('ANSWER_SENTINEL', text)
                self.assertNotIn('SYNTHETIC_SOURCE_SENTINEL', text)

    def test_metadata_initial_and_completed_body_not_written(self):
        s = self.session()
        out, errors = io.StringIO(), io.StringIO()
        with tempfile.TemporaryDirectory() as d, contextlib.redirect_stdout(out), contextlib.redirect_stderr(errors):
            sink = AtomicSink(d, s); sink.save(s)
            initial = json.loads(sink.path.read_bytes())
            self.assertIsNone(initial['claimCount']); self.assertEqual(initial['reviewStatus'], 'NOT_REVIEWED')
            self.assertEqual(initial['claims'], [])
            s.add(claim()); s.complete(finish()); sink.save(s)
            self.assertEqual(json.loads(sink.path.read_bytes())['claimCount'], 1)
            self.assert_safe(s, d)
        self.assertEqual(out.getvalue() + errors.getvalue(), '')

    def test_illegal_payloads_are_rejected_without_echo(self):
        bad = []
        for field in ('answer', 'notes', 'filename', 'url'):
            x = claim(); x[field] = 'ANSWER_SENTINEL'; bad.append(x)
        for field in ('support', 'kind', 'supportType', 'unlinkedBinding'):
            x = claim(); x[field] = 'ANSWER_SENTINEL'; bad.append(x)
        x = claim(); x['links'][0]['citationId'] = 'C1_ANSWER_SENTINEL'; bad.append(x)
        x = claim(); x['checks'][0]['sourceId'] = 'S1_ANSWER_SENTINEL'; bad.append(x)
        x = claim(); x['checks'][0]['text'] = 'ANSWER_SENTINEL'; bad.append(x)
        x = claim(); x['links'][0]['notes'] = 'ANSWER_SENTINEL'; bad.append(x)
        for x in bad:
            with self.subTest(), self.assertRaises(ReviewError) as error: self.session().add(x)
            self.assertNotIn('ANSWER_SENTINEL', str(error.exception))

    def test_boolean_and_out_of_range_coordinates_rejected(self):
        for ranges in ([[True, 3]], [[-1, 3]], [[0, 999]], [[3, 3]], [[4, 5], [1, 2]]):
            x = claim(); x['spans'] = ranges
            with self.assertRaises(ReviewError): self.session().add(x)
        for first, last in ((True, 1), (0, 1), (2, 1), (1, 3)):
            x = claim(); x['checks'][0].update(startLine=first, endLine=last)
            with self.assertRaises(ReviewError): self.session().add(x)

    def test_citation_cannot_cross_sample(self):
        s = self.session(ids=['C2'], answer='无 C1 标记的合成回答。')
        with self.assertRaises(ReviewError): s.add(claim())

    def test_source_coverage_does_not_infer_support_or_binding(self):
        s = self.session(answer='无内联但有返回来源。')
        x = claim(0, 3); x.update(support='INSUFFICIENT', supportType='NONE')
        x['links'][0].update(correspondence='IRRELEVANT', supportType='NONE')
        s.add(x)
        decision = finish(); decision['citationRelevance']['C1'] = 'IRRELEVANT'
        s.complete(decision)
        r = s.record()
        self.assertEqual(r['directCoverage']['E1']['status'], 'COVERED')
        self.assertEqual(r['claims'][0]['support'], 'INSUFFICIENT')
        self.assertEqual(r['claims'][0]['links'][0]['binding'], 'GLOBAL_ONLY')

    def test_local_binding_can_have_unsupported_content(self):
        s = self.session(); x = claim()
        start = ANSWER.index('[C1]')
        x['links'][0].update(correspondence='INSUFFICIENT', supportType='NONE', binding='LOCAL', markerSpan=[start, start+4])
        s.add(x); s.complete(finish())
        self.assertEqual(s.record()['claims'][0]['links'][0]['correspondence'], 'INSUFFICIENT')
        for span in ([0, 4], None):
            x['links'][0]['markerSpan'] = span
            with self.assertRaises(ReviewError): self.session().add(x)

    def test_extra_repetition_contradiction_and_discontinuous_qualifier(self):
        s = self.session(); s.add(claim())
        x = claim(22, 25, 'EXTRA', []); x['spans'] = [[22, 24], [26, 27]]
        x.update(support='CONTRADICTED', supportType='DIRECT')
        s.add(x)
        x = claim(28, 30, 'EXTRA', []); x.update(support='CONFLICTING'); s.add(x)
        s.complete(finish())
        r = s.record(); self.assertEqual(r['claimCount'], 3)
        self.assertEqual([c['claimId'] for c in r['claims']], ['A1', 'A2', 'A3'])
        self.assertEqual(r['claims'][1]['support'], 'CONTRADICTED')

    def test_omitted_core_is_not_fabricated_as_a_claim(self):
        s = self.session(); s.add(claim(ids=['E1']))
        with self.assertRaises(ReviewError): s.complete(finish())
        f = finish(); f['coreDisposition']['E2'] = 'OMITTED'; s.complete(f)
        self.assertEqual(s.record()['completion']['coreDisposition']['E2'], 'OMITTED')
        self.assertEqual(s.record()['claimCount'], 1)

    def test_correct_abstention_does_not_clear_extra_failure(self):
        s = self.session(q=10)
        s.add(claim(kind='ABSTENTION', ids=[]))
        x = claim(22, 25, 'EXTRA', []); x.update(support='INSUFFICIENT', supportType='NONE'); s.add(x)
        s.complete(finish(q=10))
        r = s.record(); self.assertEqual(r['completion']['abstentionVerdict'], 'SUPPORTED')
        self.assertEqual(r['claims'][1]['support'], 'INSUFFICIENT')

    def test_unknown_and_incomplete_cannot_complete(self):
        for field in ('inventoryComplete', 'secondPassComplete'):
            s = self.session(); s.add(claim()); f = finish(); f[field] = False
            with self.assertRaises(ReviewError): s.complete(f)
        s = self.session(); x = claim(); x.update(support='UNKNOWN', supportType='UNKNOWN'); s.add(x)
        with self.assertRaises(ReviewError): s.complete(finish())
        self.assertEqual(s.record()['reviewStatus'], 'NOT_REVIEWED')

    def test_interruption_discards_all_claims_preserves_usage_and_reserve(self):
        s = self.session(); s.add(claim()); s.interrupt()
        r = s.record(); self.assertEqual(r['claims'], []); self.assertIsNone(r['claimCount'])
        self.assertEqual(r['receipt'], RECEIPT); self.assertIsNone(r['completion'])
        with self.assertRaises(ReviewError): s.complete(finish())

    def test_save_failure_leaves_initial_receipt_and_no_body_temporary(self):
        s = self.session()
        with tempfile.TemporaryDirectory() as d:
            sink = AtomicSink(d, s); sink.save(s)
            initial = sink.path.read_bytes()
            s.add(claim()); s.complete(finish())
            with patch('review.os.replace', side_effect=OSError('ANSWER_SENTINEL')), self.assertRaises(ReviewError) as error:
                sink.save(s)
            self.assertEqual(str(error.exception), 'SAVE_FAILED')
            self.assertEqual(sink.path.read_bytes(), initial)
            self.assertEqual(len(list(Path(d).iterdir())), 1)
            self.assert_safe(s, d)

    def test_missing_and_failed_body_are_not_reviewed_or_empty_coverage(self):
        for status in ('FAILED', 'UNKNOWN'):
            s = Session(demo_context(returned=[]), None, {**RECEIPT, 'status': status})
            self.assertIsNone(s.record()['returnedCitations'])
            self.assertEqual(s.record()['directCoverage']['E1']['status'], 'UNKNOWN')
            with self.assertRaises(ReviewError): s.complete(finish(ids=()))
        s = self.session(answer=None)
        self.assertIsNone(s.record()['claimCount'])
        with self.assertRaises(ReviewError): s.complete(finish())

    def test_receipt_extra_keys_and_alias_mutation_rejected(self):
        for field in ('notes', 'answer'):
            with self.assertRaises(ReviewError): Session(demo_context(), ANSWER, {**RECEIPT, field: 'ANSWER_SENTINEL'})
        s = self.session(); s.receipt['answer'] = 'ANSWER_SENTINEL'
        with self.assertRaises(ReviewError): s.record()
        s = self.session(); s.add(claim()); s.complete(finish()); r = s.record()
        r['completion']['notes'] = 'ANSWER_SENTINEL'
        self.assert_safe(s)

    def test_successful_empty_sources_are_not_coverage_unknown(self):
        s = self.session(ids=[], answer='有待核验的事实。')
        x = claim(0, 3); x.update(links=[], unlinkedBinding='ABSENT')
        s.add(x); s.complete(finish(ids=()))
        self.assertEqual(s.record()['directCoverage']['E1']['status'], 'NOT_COVERED')
        self.assertEqual(s.record()['claims'][0]['unlinkedBinding'], 'ABSENT')
        x['unlinkedBinding'] = 'GLOBAL_ONLY'
        with self.assertRaises(ReviewError): self.session(ids=[], answer='没有引用。').add(x)

    def test_context_invalid_scope_and_locator_rejected(self):
        with self.assertRaises(ReviewError): self.session(answer=ANSWER + '[C9]')
        with self.assertRaises(ReviewError): Session(replace(demo_context(), scope='ANSWER_SENTINEL'), ANSWER, RECEIPT)
        c = dict(demo_context().chunks[0]); c['ordinal'] = 'ANSWER_SENTINEL'
        with self.assertRaises(ReviewError): Session(replace(demo_context(), chunks=(c,)*5), ANSWER, RECEIPT)

    def test_frozen_manifest_drift_no_source_callback(self):
        with patch('builtins.print') as output:
            with self.assertRaises(ReviewError): load_context(b'ANSWER_SENTINEL', lambda *_: self.fail(), 1, 'baseline', ['C1'])
        output.assert_not_called()

    def test_frozen_source_drift_and_callback_errors_sanitized(self):
        blob = Path(__file__).with_name('frozen-inputs.json').read_bytes()
        for read in (lambda *_: b'ANSWER_SENTINEL', lambda *_: (_ for _ in ()).throw(RuntimeError('ANSWER_SENTINEL'))):
            with self.assertRaises(ReviewError) as error: load_context(blob, read, 1, 'baseline', ['C1'])
            self.assertNotIn('ANSWER_SENTINEL', str(error.exception))

    def test_unknown_command_arguments_never_echo_body(self):
        import run
        out, err = io.StringIO(), io.StringIO()
        with patch('sys.argv', ['run.py', '--answer', 'ANSWER_SENTINEL']), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            with self.assertRaises(ReviewError) as error: run.main()
        self.assertEqual(str(error.exception), 'INVALID_ARGUMENTS')
        self.assertEqual(out.getvalue()+err.getvalue(), '')

    def test_no_body_claims_copy_caller_data_and_enforce_bounds(self):
        s = self.session(); x = claim(); s.add(x); x['support'] = 'ANSWER_SENTINEL'
        self.assertEqual(s._claims[0]['support'], 'SUPPORTED')
        for _ in range(63): s.add(claim())
        with self.assertRaises(ReviewError): s.add(claim())
        with self.assertRaises(ReviewError): self.session(answer='A' * 32769)

    def test_clear_releases_own_answer_and_source_references(self):
        s = self.session(); s.add(claim()); s.clear()
        self.assertIsNone(s.answer); self.assertIsNone(s.context); self.assertEqual(s._claims, [])

    def test_body_missing_api_never_opens_window(self):
        with tempfile.TemporaryDirectory() as d, patch('window.ReviewWindow', side_effect=AssertionError('window unexpected')):
            path = review_transient(demo_context(), None, RECEIPT, d)
            self.assertEqual(json.loads(path.read_bytes())['reviewStatus'], 'NOT_REVIEWED')

    def test_window_creation_failure_sanitized_and_preserves_receipt(self):
        out, err = io.StringIO(), io.StringIO()
        with tempfile.TemporaryDirectory() as d, contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            with patch('window.ReviewWindow', side_effect=RuntimeError('ANSWER_SENTINEL')), self.assertRaises(ReviewError) as error:
                review_transient(demo_context(), ANSWER, RECEIPT, d)
            self.assertEqual(str(error.exception), 'REVIEW_INTERRUPTED')
            r = json.loads(next(Path(d).iterdir()).read_bytes())
            self.assertEqual(r['reviewStatus'], 'INTERRUPTED'); self.assertEqual(r['receipt'], RECEIPT)
        self.assertEqual(out.getvalue()+err.getvalue(), '')

    def test_initial_save_failure_never_opens_window(self):
        with (tempfile.TemporaryDirectory() as d, patch('review.os.replace', side_effect=OSError('ANSWER_SENTINEL')),
              patch('window.ReviewWindow') as window):
            with self.assertRaises(ReviewError) as error: review_transient(demo_context(), ANSWER, RECEIPT, d)
            self.assertEqual(str(error.exception), 'SAVE_FAILED'); window.assert_not_called()
            self.assertEqual(list(Path(d).iterdir()), [])


if __name__ == '__main__': unittest.main()
