"""Own hidden Tk window smoke tests. No screen/clipboard capture or paid requests."""
import contextlib
import io
import json
import tempfile
import unittest
from unittest.mock import patch

from review import AtomicSink, Session, demo_context
from test_review import ANSWER, RECEIPT, claim
from window import ReviewWindow


class WindowTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.session = Session(demo_context(), ANSWER, RECEIPT)
        self.sink = AtomicSink(self.temp.name, self.session)
        self.sink.save(self.session)
        self.app = ReviewWindow(self.session, self.sink, visible=False)
        self.app.root.update_idletasks()

    def tearDown(self):
        if not self.app.closed: self.app.cancel()
        self.session.clear()
        self.temp.cleanup()

    def fill(self):
        a = self.app
        a.answer.tag_add('sel', '1.0', '1.17'); a.select_claim()
        a.support.set('SUPPORTED'); a.support_type.set('DIRECT')
        for v in a.core_checks.values(): v.set(True)
        a.add_check()
        a.correspondence.set('SUPPORTS'); a.link_type.set('DIRECT'); a.binding.set('GLOBAL_ONLY')
        a.add_link(); a.add_claim()
        self.assertEqual(len(self.session._claims), 1)
        for v in a.disposition.values(): v.set('PRESENT')
        a.relevance['C1'].set('RELEVANT'); a.relevance['C2'].set('IRRELEVANT')
        a.inventory.set(True); a.second.set(True)

    def test_complete_widget_path_contains_no_body(self):
        self.app.root.after(0, lambda: (self.fill(), self.app.submit()))
        self.app.run()
        self.assertTrue(self.app.closed)
        raw = self.sink.path.read_text(encoding='utf8')
        self.assertNotIn('ANSWER_SENTINEL', raw); self.assertNotIn('SYNTHETIC_SOURCE_SENTINEL', raw)
        self.assertEqual(json.loads(raw)['reviewStatus'], 'COMPLETED')

    def test_cancel_drops_unsaved_claims_preserves_usage(self):
        self.session.add(claim()); self.app.cancel()
        r = json.loads(self.sink.path.read_bytes())
        self.assertEqual(r['reviewStatus'], 'INTERRUPTED'); self.assertEqual(r['claims'], [])
        self.assertEqual(r['receipt'], RECEIPT)

    def test_unsaved_draft_and_incomplete_checks_prevent_submission(self):
        self.fill(); self.app.ranges = [[1, 2]]; self.app.submit()
        self.assertFalse(self.app.closed); self.assertEqual(self.app.message.get(), 'UNSAVED_DRAFT')
        self.app.reset(); self.app.second.set(False); self.app.submit()
        self.assertEqual(self.app.message.get(), 'INCOMPLETE_REVIEW')
        self.assertEqual(json.loads(self.sink.path.read_bytes())['reviewStatus'], 'NOT_REVIEWED')

    def test_save_failure_keeps_window_and_initial_receipt(self):
        self.fill(); before = self.sink.path.read_bytes()
        with patch('review.os.replace', side_effect=OSError('ANSWER_SENTINEL')): self.app.submit()
        self.assertFalse(self.app.closed); self.assertEqual(self.app.message.get(), 'SAVE_FAILED')
        self.assertEqual(self.session.status, 'NOT_REVIEWED')
        self.assertEqual(self.sink.path.read_bytes(), before)
        self.app.submit(); self.assertTrue(self.app.closed)

    def test_unexpected_callback_never_prints_traceback(self):
        out, error = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(error):
            def fail(): raise RuntimeError('ANSWER_SENTINEL')
            self.app.root.after(0, fail)
            self.app.run()
        self.assertEqual(out.getvalue()+error.getvalue(), '')
        self.assertTrue(self.app.closed)
        self.assertEqual(json.loads(self.sink.path.read_bytes())['reviewStatus'], 'INTERRUPTED')

    def test_read_only_views_have_copy_and_selection_export_disabled(self):
        for view in (self.app.answer, self.app.source):
            self.assertEqual(view.cget('state'), 'disabled')
            self.assertFalse(int(view.cget('exportselection')))
            for event in ('<<Copy>>', '<<Cut>>', '<<Paste>>', '<Control-c>', '<Control-Insert>'):
                self.assertTrue(view.bind(event))

    def test_unicode_offsets_use_python_characters(self):
        self.app.answer.configure(state='normal'); self.app.answer.delete('1.0', 'end')
        self.app.answer.insert('1.0', '😀甲。[C1]'); self.app.answer.configure(state='disabled')
        end = self.app.answer.search('甲', '1.0')
        self.app.answer.tag_add('sel', '1.0', end)
        self.assertEqual(self.app.selected(), [0, 1])


if __name__ == '__main__': unittest.main()
