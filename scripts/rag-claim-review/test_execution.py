"""Cost evidence and closed HTTP protocol rehearsal. Never contact a provider."""
import contextlib
import io
import json
import os
import unittest
from dataclasses import replace
from unittest.mock import patch

from execution import (CONTEXT_LIMIT, EVIDENCE_SHA, POLICY, PREPARATION_SHA,
                       MemoryHttpStub, MemoryRequest, MemoryResponse, OfflineHttpHarness,
                       build_preparation, evidence, frozen_preparation, require_live_ready)
from plan import PLAN_SHA, canonical, frozen_plan, prepare
from review import MANIFEST_SHA, ReviewError, digest, load_context
from run import HERE, public_source
from test_provider import BODY, envelope
from token_evidence import FILES, FRAME, REVISION, inspect_directory, inspect_profile


class ExecutionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        context = load_context((HERE / 'frozen-inputs.json').read_bytes(), public_source, 1, 'baseline', [])
        cls.body, cls.row = prepare(context)

    def request(self):
        return MemoryRequest(PREPARATION_SHA, 1, 'baseline', self.row['payloadSha256'], self.body)

    def reserve(self):
        return dict(status='UNKNOWN', usage=None, reservedMicros=2105344, durableUnknown=True)

    def stub(self, body=None, **kwargs):
        data = canonical(envelope()) if body is None else body
        chunks = tuple((n, data[i:i+4096]) for n, i in enumerate(range(0, len(data), 4096)))
        return MemoryHttpStub(chunks=chunks, **kwargs)

    def run_stub(self, stub, request=None, reserve=None):
        harness = OfflineHttpHarness()
        receipt = harness.exchange(request or self.request(), stub, reserve or self.reserve())
        return harness, receipt

    def test_conservative_cost_reproduces_with_unchanged_scope_calls_cap_payloads(self):
        frozen = frozen_preparation()
        self.assertEqual(frozen, build_preparation())
        parent, _ = frozen_plan()
        self.assertEqual(frozen['parentPlanSha256'], PLAN_SHA)
        self.assertEqual(frozen['chatCalls'], 40); self.assertEqual(frozen['embeddingCalls'], 0)
        self.assertEqual(frozen['capMicros'], 2000000)
        self.assertEqual(frozen['estimatedReservedMicros'], 1397304)
        self.assertEqual(frozen['totalReservedMicros'], 84213760)
        self.assertEqual(frozen['budgetShortfallMicros'], 82213760)
        self.assertEqual({r['reservedMicros'] for r in frozen['samples']}, {2105344})
        self.assertEqual([r['payloadSha256'] for r in frozen['samples']], [r['payloadSha256'] for r in parent['samples']])
        self.assertEqual({r['inputTokenBound'] for r in frozen['samples']}, {CONTEXT_LIMIT})

    def test_evidence_hashes_and_hosted_uncertainty_remain_explicit(self):
        e = evidence()
        self.assertEqual(digest(canonical(e)), EVIDENCE_SHA)
        self.assertEqual(e['revision'], REVISION)
        self.assertEqual({r['path']:r['sha256'] for r in e['sources']}, FILES)
        self.assertEqual(e['profile']['byteAlphabetCoverage'], 256)
        self.assertEqual(e['profile']['referenceChatFrameBytes'], 80)
        self.assertEqual(e['profile']['hostedApiEquivalence'], 'UNPROVEN')
        self.assertEqual(e['profile']['hostedJsonModeFraming'], 'UNPROVEN')
        self.assertFalse(e['profile']['sourceExecuted'])

    def test_live_gate_rejects_before_network_key_lookup_or_attempt(self):
        harness = OfflineHttpHarness()
        with patch('socket.socket') as sock, patch('getpass.getpass') as key:
            for env in ({}, {'CLAIM_REVIEW_LIVE':'true', 'APPROVED_CALLS':'40', 'APPROVED_CNY':'2'}):
                with patch.dict(os.environ, env), self.assertRaises(ReviewError) as error:
                    harness.live()
                self.assertEqual(str(error.exception), 'LIVE_NOT_AUTHORIZED')
        sock.assert_not_called(); key.assert_not_called(); self.assertFalse(harness.used)

    def test_preparation_or_evidence_drift_rejects_closed(self):
        for field in ('PREPARATION_SHA','EVIDENCE_SHA'):
            with patch('execution.'+field, '0'*64), self.assertRaises(ReviewError): require_live_ready()

    def test_environment_is_owner_unconfirmed_not_promoted_by_synthetic_tests(self):
        value = frozen_preparation()
        self.assertIn('OWNER_ENVIRONMENT_UNCONFIRMED', value['blockers'])
        for key in ('privateDesktop', 'screenRecording', 'clipboardHistory', 'debuggerAndCrashCapture', 'ownerReviewer'):
            self.assertEqual(value['environment'][key], 'OWNER_CONFIRMATION_REQUIRED')
        self.assertFalse(value['environment']['osMemoryErasureGuarantee'])

    def test_valid_envelope_is_same_request_bound_in_memory_and_clearable(self):
        stub = self.stub()
        harness, receipt = self.run_stub(stub)
        self.assertTrue(harness.used); self.assertEqual(stub.sent, 1); self.assertTrue(stub.closed)
        self.assertEqual((receipt.status, receipt.code), ('SUCCEEDED','NONE'))
        self.assertEqual(receipt.usage, dict(inputTokens=10,outputTokens=5,totalTokens=15))
        self.assertEqual(BODY, json.loads(json.loads(receipt.envelope)['choices'][0]['message']['content'])['answer'])
        self.assertNotIn(BODY, repr(receipt))
        receipt.clear(); self.assertIsNone(receipt.envelope)

    def test_unknown_reservation_precedes_dispatch_and_is_not_released(self):
        reserve = self.reserve()
        for field, value in (('durableUnknown',False), ('usage',{}), ('status','SUCCEEDED'),
                             ('reservedMicros',True), ('reservedMicros',33112)):
            stub = self.stub()
            _, receipt = self.run_stub(stub, reserve={**reserve, field:value})
            self.assertEqual(receipt.code, 'HTTP_RESERVATION'); self.assertEqual(stub.sent, 0)
        _, receipt = self.run_stub(self.stub(outcome='TIMEOUT'), reserve=reserve)
        self.assertEqual(receipt.status,'UNKNOWN')
        self.assertEqual(reserve, self.reserve())

    def test_request_scope_sha_question_and_bytes_drift_never_dispatch(self):
        for changes in (dict(scope=PLAN_SHA),dict(question=2),dict(question=True),dict(payload_sha='0'*64),
                        dict(body=b'PROTOCOL_BODY_SENTINEL')):
            stub = self.stub()
            _, receipt = self.run_stub(stub, request=replace(self.request(),**changes))
            self.assertIn(receipt.code,('HTTP_PAIRING','HTTP_PROTOCOL')); self.assertEqual(stub.sent,0)

    def test_redirect_and_error_status_never_read_body_or_follow_location(self):
        for status in (301,302,307,308,401,402,403,429,500,201):
            stub = self.stub(body=b'ERROR_BODY_SENTINEL',status=status)
            _, receipt = self.run_stub(stub)
            self.assertEqual((receipt.status,receipt.code),('FAILED','HTTP_STATUS'))
            self.assertEqual(stub.sent,1); self.assertEqual(stub.reads,0); self.assertTrue(stub.closed)
            self.assertIsNone(receipt.envelope)

    def test_timeout_disconnect_and_interrupt_consume_single_attempt_without_retry(self):
        for outcome in ('TIMEOUT','DISCONNECT','INTERRUPT'):
            stub = self.stub(outcome=outcome)
            harness,receipt = self.run_stub(stub)
            self.assertEqual(receipt.code,'HTTP_RESULT_UNKNOWN'); self.assertTrue(harness.used)
            next_stub = self.stub()
            rejected=harness.exchange(self.request(),next_stub,self.reserve())
            self.assertEqual(rejected.code,'HTTP_PAIRING'); self.assertEqual(next_stub.sent,0)
            self.assertEqual(stub.sent,1); self.assertTrue(stub.closed)

    def test_total_deadline_is_not_reset_by_each_chunk(self):
        stub=self.stub(); stub.chunks=((0,b'{'),(119999,b' '),(120000,b'}'))
        _,receipt=self.run_stub(stub)
        self.assertEqual(receipt.code,'HTTP_DEADLINE'); self.assertEqual(stub.reads,2)

    def test_response_limit_encoding_type_and_utf8_fail_without_body_export(self):
        for stub in (self.stub(content_type='text/html'), self.stub(content_encoding='gzip'),
                     MemoryHttpStub(chunks=tuple((n,b'x'*4096) for n in range(256))+((256,b'x'),)),
                     self.stub(body=b'\xff')):
            _,receipt=self.run_stub(stub)
            self.assertNotEqual(receipt.code,'NONE'); self.assertIsNone(receipt.envelope)

    def test_copied_or_mutated_request_response_is_rejected_without_usage(self):
        for mutation in ('copy','sha'):
            stub=self.stub()
            def respond(request):
                other=replace(request) if mutation=='copy' else request
                if mutation=='sha': object.__setattr__(request,'payload_sha','0'*64)
                return MemoryResponse(other,200,'application/json','identity',((0,canonical(envelope())),))
            with patch('execution.MemoryHttpStub.exchange', side_effect=respond):
                _,receipt=self.run_stub(stub)
            self.assertEqual(receipt.code,'HTTP_PAIRING'); self.assertIsNone(receipt.usage)

    def test_valid_usage_on_provider_failure_remains_without_answer_or_envelope(self):
        e=envelope(); e['choices'][0]['finish_reason']='length'
        _,receipt=self.run_stub(self.stub(body=canonical(e)))
        self.assertEqual(receipt.code,'PROVIDER_FINISH'); self.assertEqual(receipt.usage['totalTokens'],15)
        self.assertIsNone(receipt.envelope)

    def test_over_input_output_and_cost_limits_preserve_usage_then_stop(self):
        for input_tokens,output_tokens in ((1048577,5),(10,1025),(1048577,1025)):
            e=envelope(); e['usage']=dict(prompt_tokens=input_tokens,completion_tokens=output_tokens,
                                         total_tokens=input_tokens+output_tokens)
            harness,receipt=self.run_stub(self.stub(body=canonical(e)))
            self.assertEqual(receipt.code,'HTTP_USAGE_EXCEEDED'); self.assertTrue(harness.used)
            self.assertEqual(receipt.usage['inputTokens'],input_tokens)
            self.assertIsNone(receipt.envelope)

    def test_non_stub_driver_or_subclass_never_invoked(self):
        class External:
            def exchange(self,*args): raise AssertionError('must not dispatch')
        class Child(MemoryHttpStub): pass
        for stub in (External(),Child()):
            _,receipt=self.run_stub(stub)
            self.assertEqual(receipt.code,'OFFLINE_ONLY')

    def test_sensitive_fixture_and_errors_never_reach_stdout_or_stderr(self):
        out,err=io.StringIO(),io.StringIO()
        stub=self.stub()
        with contextlib.redirect_stdout(out),contextlib.redirect_stderr(err), \
             patch('execution.MemoryHttpStub.exchange', side_effect=RuntimeError(BODY)):
            _,receipt=self.run_stub(stub)
        self.assertEqual(out.getvalue()+err.getvalue(),'')
        self.assertNotIn(BODY,repr(receipt)); self.assertIsNone(receipt.envelope)
        self.assertTrue(stub.closed)

    def test_optional_source_reproduction_absent_or_drift_has_fixed_error(self):
        with patch('token_evidence.Path.read_bytes',return_value=b'UNTRUSTED_SOURCE_SENTINEL'), \
             self.assertRaises(ReviewError) as error:
            inspect_directory('.')
        self.assertEqual(str(error.exception),'TOKEN_EVIDENCE_INVALID')

    def test_profile_rejects_normalization_prefix_addition_or_missing_byte(self):
        # Small synthetic JSON profile; do not install/import a tokenizer runtime.
        splitters=[dict(type='Split',behavior='Isolated',invert=False)]*3
        splitters.append(dict(type='ByteLevel',add_prefix_space=False,trim_offsets=True,use_regex=False))
        base=list(range(33,127))+list(range(161,173))+list(range(174,256))
        chars=base+list(range(256,256+256-len(base)))
        tok=dict(normalizer=dict(type='Sequence',normalizers=[]),
                 pre_tokenizer=dict(type='Sequence',pretokenizers=splitters),
                 post_processor=dict(type='ByteLevel',add_prefix_space=True,trim_offsets=False,use_regex=True),
                 model=dict(type='BPE',continuing_subword_prefix=None,end_of_word_suffix=None,dropout=None,
                            unk_token=None,vocab={chr(x):i for i,x in enumerate(chars)}),added_tokens=[])
        config=dict(add_bos_token=False,add_eos_token=False,model_max_length=1048576)
        model=dict(text_config=dict(max_position_embeddings=1048576))
        source='\n'.join(f'{name}: str = {value!r}' for name,value in {
            'system_msg_template':'{content}','user_msg_template':'{content}',
            'bos_token':'<｜begin▁of▁sentence｜>','SYSTEM_SP_TOKEN':'<｜System｜>',
            'USER_SP_TOKEN':'<｜User｜>','ASSISTANT_SP_TOKEN':'<｜Assistant｜>',
            'thinking_end_token':'</think>'}.items())
        self.assertEqual(inspect_profile(tok,config,model,source)['referenceChatFrameBytes'],80)
        for mutation in ('normalizer','prefix','byte','bos','content'):
            t=json.loads(json.dumps(tok)); c=dict(config); s=source
            if mutation=='normalizer': t['normalizer']['normalizers']=[{'type':'NFKC'}]
            if mutation=='prefix': t['pre_tokenizer']['pretokenizers'][-1]['add_prefix_space']=True
            if mutation=='byte': del t['model']['vocab']['!']
            if mutation=='bos': c['add_bos_token']=True
            if mutation=='content': s=s.replace("'{content}'","'{content}extra'")
            with self.subTest(mutation=mutation),self.assertRaises(ReviewError): inspect_profile(t,c,model,s)


if __name__=='__main__': unittest.main()
