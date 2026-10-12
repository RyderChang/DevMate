"""Offline Stub -> real Java validator -> real Tk -> safe receipts. No paid calls."""
import contextlib
import io
import json
import logging
import os
import tempfile
import unittest
import uuid
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from adapter import (ISSUES, OfflineAdapter, ProductionValidator, StubGrant,
                     StubReply, StubTransport, configured_validator)
from review import LOCATORS, MANIFEST_SHA, ReviewError, load_context
from run import ROOT, public_source
from test_review import claim, finish
from window import ReviewWindow

BODY = 'ADAPTER_BODY_SENTINEL甲。[C1]'
RAW = json.dumps(dict(answer=BODY, citationIds=['C1']))
USAGE = dict(inputTokens=10, outputTokens=5, totalTokens=15)


def production_validator():
    # A missing bridge is a failing prerequisite, never a skipped/fake validator test.
    return configured_validator(ROOT)


class AdapterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validator = production_validator()
        cls.blob = Path(__file__).with_name('frozen-inputs.json').read_bytes()
        # Prove the compiled bridge is available before boundary tests.
        assert cls.validator.validate(RAW)['status'] == 'SUCCEEDED'

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.grant = StubGrant(uuid.uuid4().hex, MANIFEST_SHA, ((1, 'baseline'), (2, 'example')),
                               2, 2000, 1000)

    def context(self, q=1, arm='baseline'):
        return load_context(self.blob, public_source, q, arm, [])

    def adapter(self, grant=None):
        return OfflineAdapter(grant or self.grant, self.temp.name, self.validator)

    def window(self, session, sink):
        self.assertEqual(session.answer, BODY)
        self.assertEqual(session.context.returned, ('C1',))
        self.assertEqual(session.receipt['usage'], USAGE)
        self.assertEqual(session.context.mode, 'OFFLINE_STUB')
        app = ReviewWindow(session, sink, visible=False)
        def submit():
            x = claim(end=5)
            session.add(x)
            session.complete(finish(ids=('C1',)))
            sink.save(session); app.destroy()
        app.root.after(0, submit)
        return app

    def test_complete_chain_real_validator_window_and_memory_cleanup(self):
        adapter = self.adapter(); transport = StubTransport(RAW)
        out, err, logs = io.StringIO(), io.StringIO(), io.StringIO()
        handler = logging.StreamHandler(logs); logging.getLogger().addHandler(handler)
        try:
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err), patch('window.ReviewWindow', self.window):
                path = adapter.review(self.context(), transport)
        finally:
            logging.getLogger().removeHandler(handler)
        record = json.loads(path.read_bytes())
        self.assertEqual(record['reviewStatus'], 'COMPLETED')
        self.assertEqual(record['mode'], 'OFFLINE_STUB')
        self.assertEqual(record['reviewer'], 'SYNTHETIC_CHOICES')
        self.assertEqual(record['receipt']['usage'], USAGE)
        self.assertEqual(transport.sent, 1); self.assertIsNone(transport.raw)
        self.assertEqual(out.getvalue()+err.getvalue()+logs.getvalue(), '')
        for file in Path(self.temp.name).iterdir():
            raw = file.read_text(encoding='utf8')
            self.assertNotIn('ADAPTER_BODY_SENTINEL', raw)
            self.assertNotIn('文档检索 API', raw)
            self.assertNotIn('默认', raw)
        self.assertEqual(len(list(Path(self.temp.name).glob('*.tmp'))), 0)

    def test_rejected_authorization_scope_mode_samples_and_bounds(self):
        for changes in (dict(mode='LIVE'), dict(mode='DEV-032'), dict(scope='ADAPTER_BODY_SENTINEL'),
                        dict(calls=41), dict(calls=True), dict(cap_micros=0), dict(reserve_micros=2001),
                        dict(samples=((1, 'baseline'), (1, 'baseline'))), dict(round_id='ADAPTER_BODY_SENTINEL')):
            with self.subTest(changes=changes), self.assertRaises(ReviewError):
                self.adapter(replace(self.grant, **changes))
        adapter = self.adapter()
        for context in (self.context(3), self.context(1, 'example'),
                        replace(self.context(), prompt='ADAPTER_BODY_SENTINEL'),
                        replace(self.context(), scope='ADAPTER_BODY_SENTINEL')):
            transport = StubTransport(RAW)
            with self.assertRaises(ReviewError): adapter.review(context, transport)
            self.assertEqual(transport.sent, 0)

    def test_exhausted_calls_and_budget_never_dispatch_or_refill(self):
        for grant in (replace(self.grant, round_id=uuid.uuid4().hex, calls=1),
                      replace(self.grant, round_id=uuid.uuid4().hex, cap_micros=1000)):
            adapter = self.adapter(grant)
            with patch('window.ReviewWindow', self.window): adapter.review(self.context(), StubTransport(RAW))
            transport = StubTransport(RAW)
            with self.assertRaises(ReviewError): adapter.review(self.context(2, 'example'), transport)
            self.assertEqual(transport.sent, 0)
            with self.assertRaises(ReviewError): self.adapter(grant)
            self.assertEqual(len(adapter.rows), 1)

    def test_completed_sample_cannot_be_replayed_even_with_remaining_quota(self):
        adapter = self.adapter()
        with patch('window.ReviewWindow', self.window): adapter.review(self.context(), StubTransport(RAW))
        transport = StubTransport(RAW)
        with self.assertRaises(ReviewError): adapter.review(self.context(), transport)
        self.assertEqual(transport.sent, 0)

    def test_reservation_and_unknown_receipt_exist_before_send(self):
        adapter = self.adapter()
        def send(ticket):
            ledger = json.loads(adapter.path.read_bytes())
            self.assertEqual(ledger['attempts'][0]['receipt'],
                             dict(status='UNKNOWN', usage=None, reservedMicros=1000))
            record = json.loads(next(Path(self.temp.name).glob('review-*.json')).read_bytes())
            self.assertEqual(record['receipt'], ledger['attempts'][0]['receipt'])
            raise TimeoutError('ADAPTER_BODY_SENTINEL')
        with patch('adapter.StubTransport.send', side_effect=send) as dispatch, self.assertRaises(ReviewError):
            adapter.review(self.context(), StubTransport(RAW))
        self.assertEqual(dispatch.call_count, 1); self.assertTrue(adapter.stopped)

    def test_initial_save_failure_prevents_send_and_does_not_free_reservation(self):
        adapter = self.adapter(); transport = StubTransport(RAW)
        with patch('review.os.replace', side_effect=OSError('ADAPTER_BODY_SENTINEL')), self.assertRaises(ReviewError):
            adapter.review(self.context(), transport)
        self.assertEqual(transport.sent, 0); self.assertTrue(adapter.stopped)
        self.assertEqual(len(adapter.rows), 1)

    def test_all_production_structure_failures_keep_usage_without_review(self):
        cases = [('x' * 262145, 'JSON_SIZE'), ('{', 'JSON_SYNTAX'),
                 ('{"answer":"x","answer":"y","citationIds":["C1"]}', 'JSON_DUPLICATE_KEY'),
                 (RAW + ' {}', 'JSON_TRAILING'), ('{}', 'JSON_SHAPE'),
                 ('{"answer":" ","citationIds":["C1"]}', 'ANSWER_CONTENT'),
                 ('{"answer":"x","citationIds":["C9"]}', 'CITATION_IDS'),
                 ('{"answer":"[C9]","citationIds":["C1"]}', 'CITATION_MARKERS')]
        self.assertEqual(set(x[1] for x in cases), set(ISSUES))
        for raw, code in cases:
            with self.subTest(code=code):
                adapter = self.adapter(replace(self.grant, round_id=uuid.uuid4().hex))
                with patch('window.ReviewWindow') as window:
                    path = adapter.review(self.context(), StubTransport(raw))
                window.assert_not_called()
                record = json.loads(path.read_bytes())
                self.assertEqual(record['receipt']['usage'], USAGE)
                self.assertEqual(record['receipt']['status'], 'FAILED')
                self.assertEqual(record['directCoverage']['E1']['status'], 'UNKNOWN')
                self.assertIsNone(record['claimCount'])
                self.assertEqual(adapter.rows[0]['code'], code)

    def test_pairing_rejects_every_request_field_and_equal_copied_ticket(self):
        for field, value in (('question', 2), ('arm', 'combined'), ('scope', 'ADAPTER_BODY_SENTINEL'),
                             ('round_id', uuid.uuid4().hex), ('attempt_id', uuid.uuid4().hex), ('candidates', ())):
            for copied in (False, True):
                with self.subTest(field=field, copied=copied):
                    adapter = self.adapter(replace(self.grant, round_id=uuid.uuid4().hex))
                    def send(ticket):
                        returned = replace(ticket, **{field: value}) if copied else ticket
                        if not copied: object.__setattr__(returned, field, value)
                        return StubReply(returned, 'SUCCEEDED', USAGE, RAW, [])
                    with patch('adapter.StubTransport.send', side_effect=send), patch('window.ReviewWindow') as window:
                        with self.assertRaises(ReviewError): adapter.review(self.context(), StubTransport(RAW))
                    window.assert_not_called(); self.assertTrue(adapter.stopped)
                    self.assertIsNone(adapter.rows[0]['receipt']['usage'])
        adapter = self.adapter()
        with patch('adapter.StubTransport.send', side_effect=lambda t: StubReply(replace(t), 'SUCCEEDED', USAGE, RAW, [])):
            with self.assertRaises(ReviewError): adapter.review(self.context(), StubTransport(RAW))

    def test_pairing_rejects_c_id_sha_ordinal_and_every_locator(self):
        for field in ('citationId', *LOCATORS, 'notes'):
            with self.subTest(field=field):
                adapter = self.adapter(replace(self.grant, round_id=uuid.uuid4().hex))
                def send(ticket):
                    source = dict(zip(LOCATORS, ticket.candidates[0]))
                    item = dict(citationId='C1', source=source)
                    if field == 'citationId': item[field] = 'C2'
                    elif field == 'notes': item[field] = 'ADAPTER_BODY_SENTINEL'
                    else: source[field] = 'ADAPTER_BODY_SENTINEL'
                    return StubReply(ticket, 'SUCCEEDED', USAGE, RAW, [item])
                with patch('adapter.StubTransport.send', side_effect=send), patch('window.ReviewWindow') as window:
                    with self.assertRaises(ReviewError): adapter.review(self.context(), StubTransport(RAW))
                window.assert_not_called()
                self.assertEqual(adapter.rows[0]['receipt']['usage'], USAGE)
                self.assertEqual(adapter.rows[0]['receipt']['status'], 'UNKNOWN')

    def test_boolean_locator_and_swapped_candidate_sets_rejected(self):
        adapter = self.adapter()
        def send(ticket):
            source = dict(zip(LOCATORS, ticket.candidates[0])); source['ordinal'] = False
            return StubReply(ticket, 'SUCCEEDED', USAGE, RAW, [dict(citationId='C1', source=source)])
        with patch('adapter.StubTransport.send', side_effect=send), self.assertRaises(ReviewError):
            adapter.review(self.context(), StubTransport(RAW))
        transport = StubTransport(RAW)
        adapter = self.adapter(replace(self.grant, round_id=uuid.uuid4().hex))
        context = replace(self.context(), chunks=self.context(2, 'example').chunks)
        with self.assertRaises(ReviewError): adapter.review(context, transport)
        self.assertEqual(transport.sent, 0)

    def test_failed_unknown_and_unknown_usage_stop_without_retry(self):
        for status, usage in (('FAILED', USAGE), ('UNKNOWN', USAGE), ('UNKNOWN', None)):
            adapter = self.adapter(replace(self.grant, round_id=uuid.uuid4().hex))
            transport = StubTransport(None, status=status)
            transport.usage = usage
            path = adapter.review(self.context(), transport)
            record = json.loads(path.read_bytes())
            self.assertEqual(record['receipt'], dict(status=status, usage=usage, reservedMicros=1000))
            self.assertEqual(transport.sent, 1); self.assertTrue(adapter.stopped)
            with self.assertRaises(ReviewError): adapter.review(self.context(2, 'example'), StubTransport(RAW))

    def test_interrupt_and_window_save_failure_keep_usage_and_unknown_claims(self):
        for failure in ('CANCEL', 'WINDOW', 'SAVE', 'KEYBOARD'):
            adapter = self.adapter(replace(self.grant, round_id=uuid.uuid4().hex))
            def window(session, sink):
                if failure == 'WINDOW': raise RuntimeError('ADAPTER_BODY_SENTINEL')
                if failure == 'KEYBOARD': raise KeyboardInterrupt('ADAPTER_BODY_SENTINEL')
                app = ReviewWindow(session, sink, visible=False)
                def cancel():
                    session.add(claim(end=5))
                    if failure == 'SAVE':
                        with patch('review.os.replace', side_effect=OSError('ADAPTER_BODY_SENTINEL')): app.fail()
                    else: app.cancel()
                app.root.after(0, cancel)
                return app
            with patch('window.ReviewWindow', window):
                if failure in ('WINDOW', 'KEYBOARD'):
                    with self.assertRaises(ReviewError): adapter.review(self.context(), StubTransport(RAW))
                else: adapter.review(self.context(), StubTransport(RAW))
            # Locate this round's receipt explicitly; glob ordering is not a contract.
            path = Path(self.temp.name) / ('review-' + adapter.rows[0]['sampleId'] + '.json')
            record = json.loads(path.read_bytes())
            self.assertEqual(record['receipt']['usage'], USAGE)
            self.assertEqual(record['claims'], []); self.assertIsNone(record['claimCount'])
            self.assertIn(record['reviewStatus'], ('INTERRUPTED', 'NOT_REVIEWED'))
            self.assertTrue(adapter.stopped)

    def test_usage_invalid_or_exceeding_reserve_stops_no_review(self):
        for usage in (dict(inputTokens=True, outputTokens=5, totalTokens=6),
                      dict(inputTokens=10, outputTokens=5, totalTokens=100),
                      dict(inputTokens=10, outputTokens=500, totalTokens=510)):
            adapter = self.adapter(replace(self.grant, round_id=uuid.uuid4().hex))
            with patch('window.ReviewWindow') as window, self.assertRaises(ReviewError):
                adapter.review(self.context(), StubTransport(RAW, usage=usage))
            window.assert_not_called(); self.assertTrue(adapter.stopped)

    def test_validator_failure_sanitized_and_no_body_in_process_arguments(self):
        validator = production_validator()
        with patch('adapter.subprocess.run', side_effect=OSError('ADAPTER_BODY_SENTINEL')) as process:
            with self.assertRaises(ReviewError) as error: validator.validate(RAW)
        self.assertEqual(str(error.exception), 'VALIDATOR_UNAVAILABLE')
        self.assertNotIn('ADAPTER_BODY_SENTINEL', repr(process.call_args.args))
        self.assertIn(b'ADAPTER_BODY_SENTINEL', process.call_args.kwargs['input'])

    def test_non_stub_driver_never_invoked(self):
        adapter = self.adapter()
        class External:
            def send(self, ticket): self.fail()
        with self.assertRaises(ReviewError): adapter.review(self.context(), External())
        self.assertEqual(adapter.rows, [])

    def test_source_excerpt_and_failure_sentinels_never_reach_outputs_or_files(self):
        adapter = self.adapter(); context = self.context()
        excerpt = context.sources['S2'][1][:240]
        raw = json.dumps(dict(answer=BODY + '\n' + excerpt, citationIds=['C1']))
        out, err, logs = io.StringIO(), io.StringIO(), io.StringIO()
        handler = logging.StreamHandler(logs); logging.getLogger().addHandler(handler)
        def window(session, sink):
            self.assertIn(excerpt, session.answer)
            app = ReviewWindow(session, sink, visible=False)
            app.root.after(0, app.cancel)
            return app
        try:
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err), patch('window.ReviewWindow', window):
                adapter.review(context, StubTransport(raw))
            other = self.adapter(replace(self.grant, round_id=uuid.uuid4().hex))
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                with patch('adapter.StubTransport.send', side_effect=RuntimeError(BODY + excerpt)):
                    with self.assertRaises(ReviewError) as error: other.review(context, StubTransport(raw))
            self.assertEqual(str(error.exception), 'ADAPTER_STOPPED')
        finally:
            logging.getLogger().removeHandler(handler)
        self.assertEqual(out.getvalue()+err.getvalue()+logs.getvalue(), '')
        for file in Path(self.temp.name).iterdir():
            content = file.read_text(encoding='utf8')
            self.assertNotIn(BODY, content); self.assertNotIn(excerpt, content)

    def test_response_save_failure_keeps_initial_unknown_and_stops_round(self):
        adapter = self.adapter()
        original = os.replace
        def replacement(source, target):
            if Path(target).name.startswith('review-'):
                payload = json.loads(Path(source).read_bytes())
                if payload['receipt']['usage'] is not None: raise OSError(BODY)
            return original(source, target)
        transport = StubTransport(RAW)
        with patch('review.os.replace', side_effect=replacement), patch('window.ReviewWindow') as window:
            with self.assertRaises(ReviewError): adapter.review(self.context(), transport)
        window.assert_not_called(); self.assertEqual(transport.sent, 1)
        path = Path(self.temp.name) / ('review-' + adapter.rows[0]['sampleId'] + '.json')
        record = json.loads(path.read_bytes())
        self.assertEqual(record['receipt'], dict(status='UNKNOWN', usage=None, reservedMicros=1000))
        self.assertEqual(json.loads(adapter.path.read_bytes())['attempts'][0]['receipt']['usage'], USAGE)
        self.assertTrue(adapter.stopped)

    def test_transport_keyboard_interrupt_retains_unknown_attempt_and_no_resend(self):
        adapter = self.adapter()
        with patch('adapter.StubTransport.send', side_effect=KeyboardInterrupt(BODY)) as send:
            with self.assertRaises(ReviewError): adapter.review(self.context(), StubTransport(RAW))
        self.assertEqual(send.call_count, 1)
        self.assertEqual(adapter.rows[0]['receipt'], dict(status='UNKNOWN', usage=None, reservedMicros=1000))
        transport = StubTransport(RAW)
        with self.assertRaises(ReviewError): adapter.review(self.context(2, 'example'), transport)
        self.assertEqual(transport.sent, 0)


if __name__ == '__main__': unittest.main()
