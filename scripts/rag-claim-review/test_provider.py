"""Official-shaped synthetic envelopes and exact frozen requests; no HTTP or paid models."""
import contextlib
import io
import json
import tempfile
import unittest
import uuid
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from adapter import OfflineAdapter, StubGrant, StubReply, StubTransport, configured_validator
from plan import PLAN_SHA, canonical, frozen_plan, prepare, verify_plan
from provider import MODEL, decode
from review import MANIFEST_SHA, ReviewError, load_context
from run import ROOT, public_source

BODY = 'PROVIDER_BODY_SENTINEL。引用[C2]，再引用[C1]。'
RAW = json.dumps(dict(answer=BODY, citationIds=['C2', 'C1']))
USAGE = dict(inputTokens=10, outputTokens=5, totalTokens=15)


def envelope(raw=RAW):
    return dict(id='synthetic-local-only', model=MODEL, object='chat.completion',
                choices=[dict(index=0, finish_reason='stop', message=dict(role='assistant', content=raw))],
                usage=dict(prompt_tokens=10, completion_tokens=5, total_tokens=15,
                           prompt_cache_hit_tokens=3, prompt_cache_miss_tokens=7))


class ProviderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validator = configured_validator(ROOT)
        cls.blob = Path(__file__).with_name('frozen-inputs.json').read_bytes()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)

    def context(self, q=1, arm='baseline'):
        return load_context(self.blob, public_source, q, arm, [])

    def adapter(self):
        return OfflineAdapter(StubGrant(uuid.uuid4().hex, MANIFEST_SHA,
            ((1, 'baseline'), (2, 'example')), 2, 80000, 40000), self.temp.name, self.validator)

    def run_envelope(self, e, adapter=None):
        adapter = adapter or self.adapter()
        transport = StubTransport(None, envelope=canonical(e))
        return adapter, transport, adapter.review(self.context(), transport)

    def test_frozen_all_40_requests_reproduce_and_budget(self):
        self.assertEqual(verify_plan(), PLAN_SHA)
        plan, _ = frozen_plan()
        self.assertEqual(len(plan['samples']), 40)
        self.assertEqual(plan['totalReservedMicros'], 1397304)
        self.assertLessEqual(plan['totalReservedMicros'], plan['capMicros'])
        self.assertEqual({r['maxOutputTokens'] for r in plan['samples']}, {1024})
        self.assertEqual(plan['authorization'], 'NOT_GRANTED')
        self.assertFalse(plan['liveEnabled'])

    def test_stable_canonical_request_across_separate_java_processes(self):
        a, row = prepare(self.context())
        b, second = prepare(self.context())
        self.assertEqual((a, row), (b, second))
        payload = json.loads(a)
        data = json.loads(payload['messages'][1]['content'])
        self.assertEqual(data['history'], [])
        self.assertEqual(len(data['documents']), 5)
        self.assertEqual(data['question'], self.context().prompt)
        self.assertEqual(payload['thinking'], {'type': 'disabled'})

    def test_four_group_configs_keep_production_v1(self):
        for arm in ('baseline', 'example', 'json_mode', 'combined'):
            body, row = prepare(self.context(1, arm))
            payload = json.loads(body)
            self.assertEqual(row['templateVersion'], 'project-rag-v1')
            self.assertEqual('response_format' in payload, arm in ('json_mode', 'combined'))
            self.assertEqual('JSON output example' in payload['messages'][0]['content'],
                             arm in ('example', 'combined'))

    def test_plan_or_request_drift_never_dispatch(self):
        adapter = self.adapter(); transport = StubTransport(None, envelope=canonical(envelope()))
        with patch('plan.PLAN_SHA', '0'*64), self.assertRaises(ReviewError):
            adapter.review(self.context(), transport)
        self.assertEqual(transport.sent, 0)
        with self.assertRaises(ReviewError): prepare(replace(self.context(), prompt='PROVIDER_BODY_SENTINEL'))

    def test_envelope_usage_answer_and_actual_citation_order_share_ticket(self):
        adapter = self.adapter()
        captured = []
        def window(session, sink):
            captured.append((session.answer, session.context.returned, session.receipt['usage']))
            class Cancel:
                def run(self): session.interrupt(); sink.save(session)
            return Cancel()
        with patch('window.ReviewWindow', window):
            _, transport, path = self.run_envelope(envelope(), adapter)
        self.assertEqual(captured, [(BODY, ('C2', 'C1'), USAGE)])
        self.assertEqual(transport.sent, 1)
        self.assertIsNone(transport.envelope)
        row = adapter.rows[0]
        self.assertEqual(row['planScopeSha256'], PLAN_SHA)
        self.assertEqual(len(row['payloadSha256']), 64)
        self.assertEqual(json.loads(path.read_bytes())['receipt']['usage'], USAGE)
        self.assertTrue(adapter.stopped)

    def test_initial_unknown_plan_binding_is_durable_before_envelope_handoff(self):
        adapter = self.adapter()
        def send(ticket):
            row = json.loads(adapter.path.read_bytes())['attempts'][0]
            self.assertEqual(row['receipt']['status'], 'UNKNOWN')
            self.assertEqual(row['planScopeSha256'], ticket.plan_scope)
            self.assertEqual(row['payloadSha256'], ticket.payload_sha)
            raise TimeoutError(BODY)
        with patch('adapter.StubTransport.send', side_effect=send), self.assertRaises(ReviewError):
            self.run_envelope(envelope(), adapter)
        self.assertTrue(adapter.stopped)

    def test_cross_ticket_envelope_rejected_without_attributing_usage(self):
        adapter = self.adapter(); transport = StubTransport(None, envelope=canonical(envelope()))
        def send(ticket):
            return StubReply(replace(ticket), 'UNKNOWN', None, None, [], transport.envelope)
        with patch('adapter.StubTransport.send', side_effect=send), self.assertRaises(ReviewError):
            adapter.review(self.context(), transport)
        self.assertIsNone(adapter.rows[0]['receipt']['usage'])

    def test_replaced_envelope_and_ticket_scope_rejected_before_usage(self):
        for field in ('envelope', 'payload_sha', 'plan_scope'):
            adapter = self.adapter(); transport = StubTransport(None, envelope=canonical(envelope()))
            def send(ticket):
                if field == 'envelope':
                    transport.envelope = canonical(envelope('OTHER_RESPONSE_SENTINEL'))
                else:
                    object.__setattr__(ticket, field, '0'*64)
                return StubReply(ticket, 'UNKNOWN', None, None, [], transport.envelope)
            with self.subTest(field=field), patch('adapter.StubTransport.send', side_effect=send), self.assertRaises(ReviewError):
                adapter.review(self.context(), transport)
            self.assertIsNone(adapter.rows[0]['receipt']['usage'])

    def test_success_with_missing_usage_keeps_unknown_and_stops(self):
        adapter = self.adapter(); transport = StubTransport(RAW); transport.usage = None
        with patch('window.ReviewWindow') as window, self.assertRaises(ReviewError):
            adapter.review(self.context(), transport)
        window.assert_not_called(); self.assertTrue(adapter.stopped)
        self.assertEqual(adapter.rows[0]['code'], 'PROVIDER_USAGE')

    def test_usage_survives_model_finish_message_and_cache_failures(self):
        for failure in ('model', 'finish', 'role', 'content', 'tools', 'reasoning', 'cache', 'index', 'choices'):
            e = envelope()
            if failure == 'model': e['model'] = 'deepseek-chat'
            if failure == 'finish': e['choices'][0]['finish_reason'] = 'length'
            if failure == 'role': e['choices'][0]['message']['role'] = 'tool'
            if failure == 'content': e['choices'][0]['message']['content'] = None
            if failure == 'tools': e['choices'][0]['message']['tool_calls'] = [{'secret': BODY}]
            if failure == 'reasoning': e['choices'][0]['message']['reasoning_content'] = BODY
            if failure == 'cache': e['usage']['prompt_cache_miss_tokens'] = 9
            if failure == 'index': e['choices'][0]['index'] = False
            if failure == 'choices': e['choices'] *= 2
            with self.subTest(failure=failure):
                adapter = self.adapter()
                with patch('window.ReviewWindow') as window, self.assertRaises(ReviewError):
                    self.run_envelope(e, adapter)
                window.assert_not_called()
                self.assertEqual(adapter.rows[0]['receipt']['usage'], USAGE)
                self.assertTrue(adapter.stopped)

    def test_missing_invalid_usage_never_opens_window_or_retries(self):
        for usage in (None, {}, dict(prompt_tokens=True, completion_tokens=5, total_tokens=6),
                      dict(prompt_tokens=10, completion_tokens=5, total_tokens=14)):
            e = envelope(); e['usage'] = usage
            adapter = self.adapter()
            with patch('window.ReviewWindow') as window, self.assertRaises(ReviewError):
                self.run_envelope(e, adapter)
            window.assert_not_called(); self.assertTrue(adapter.stopped)
            self.assertIsNone(adapter.rows[0]['receipt']['usage'])
            t = StubTransport(None, envelope=canonical(envelope()))
            with self.assertRaises(ReviewError): adapter.review(self.context(2, 'example'), t)
            self.assertEqual(t.sent, 0)

    def test_usage_over_output_bound_retained_before_stop(self):
        e = envelope(); e['usage'].update(completion_tokens=1025, total_tokens=1035)
        adapter = self.adapter()
        with self.assertRaises(ReviewError): self.run_envelope(e, adapter)
        self.assertEqual(adapter.rows[0]['code'], 'USAGE_EXCEEDED')
        self.assertEqual(adapter.rows[0]['receipt']['usage']['outputTokens'], 1025)

    def test_usage_over_input_bound_retained_before_stop(self):
        e = envelope(); e['usage'] = dict(prompt_tokens=50000, completion_tokens=5, total_tokens=50005)
        adapter = self.adapter()
        with self.assertRaises(ReviewError): self.run_envelope(e, adapter)
        self.assertEqual(adapter.rows[0]['code'], 'USAGE_EXCEEDED')
        self.assertEqual(adapter.rows[0]['receipt']['usage']['inputTokens'], 50000)

    def test_real_production_json_failure_retains_same_envelope_usage(self):
        adapter, _, path = self.run_envelope(envelope('{'))
        self.assertEqual(adapter.rows[0]['code'], 'JSON_SYNTAX')
        self.assertEqual(json.loads(path.read_bytes())['receipt']['usage'], USAGE)

    def test_ambiguous_json_encoding_size_and_stream_fail_closed(self):
        for body in (b'{', b'\xff', b'x'*1048577,
                     b'{"object":"chat.completion","object":"chat.completion"}',
                     b'data: '+canonical(envelope()), b'{"object":"chat.completion","usage":NaN}'):
            result = decode(body)
            self.assertNotEqual(result.code, 'NONE'); self.assertIsNone(result.raw)

    def test_envelope_body_hidden_text_and_sources_do_not_reach_files_or_output(self):
        excerpt = self.context().sources['S2'][1][:200]
        e = envelope(json.dumps(dict(answer=BODY+excerpt, citationIds=['C2', 'C1'])))
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            with patch('window.ReviewWindow', side_effect=RuntimeError(BODY+excerpt)), self.assertRaises(ReviewError):
                self.run_envelope(e)
        self.assertEqual(out.getvalue()+err.getvalue(), '')
        for file in Path(self.temp.name).iterdir():
            content = file.read_text(encoding='utf8')
            self.assertNotIn(BODY, content); self.assertNotIn(excerpt, content)
        self.assertNotIn(BODY, repr(decode(canonical(e))))

    def test_stub_requires_sufficient_formal_plan_reservation(self):
        adapter = OfflineAdapter(StubGrant(uuid.uuid4().hex, MANIFEST_SHA, ((1, 'baseline'),),
             1, 1000, 1000), self.temp.name, self.validator)
        transport = StubTransport(None, envelope=canonical(envelope()))
        with self.assertRaises(ReviewError): adapter.review(self.context(), transport)
        self.assertEqual(transport.sent, 0)

    def test_envelope_cost_check_uses_frozen_formal_rates_not_legacy_simulation(self):
        with patch('adapter.STUB_INPUT_MICROS', 1000000), patch('adapter.STUB_OUTPUT_MICROS', 1000000):
            adapter, _, _ = self.run_envelope(envelope('{'))
        self.assertEqual(adapter.rows[0]['code'], 'JSON_SYNTAX')


if __name__ == '__main__': unittest.main()
