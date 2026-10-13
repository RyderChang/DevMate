"""Exact frozen-request audit and rejection of unsupported hosted-proof promotion."""
import contextlib
import io
import json
import unittest
from copy import deepcopy
from unittest.mock import patch

from cost_contract import (AUDIT_SHA, HOSTED_GAPS, JSON_INSTRUCTION, JSON_SCHEMA, SOURCE_REVIEW_SHA,
                           analyze, build_audit, reference_row, source_review, verify_audit, verify_local_sources)
from execution import PREPARATION_SHA, frozen_preparation, require_live_ready
from plan import PLAN_SHA, canonical, frozen_plan
from review import ReviewError, digest
from run import HERE


class CostContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.audit=build_audit()

    def payload(self, arm='baseline', **changes):
        payload=dict(model='deepseek-flash',stream=False,thinking={'type':'disabled'},max_tokens=1024,
                     messages=[dict(role='system',content='SYSTEM_SENTINEL'),dict(role='user',content='甲')])
        if arm in ('json_mode','combined'): payload['response_format']={'type':'json_object'}
        payload.update(changes)
        body=canonical(payload)
        return body,dict(question=1,arm=arm,payloadSha256=digest(body))

    def test_full_40_payload_regeneration_matches_review_and_both_old_scopes(self):
        expected=json.loads((HERE/'cost-contract-audit.json').read_bytes())
        self.assertEqual(expected,self.audit)
        self.assertEqual(digest(canonical(expected)),AUDIT_SHA)
        self.assertEqual(expected['parentPlanSha256'],PLAN_SHA)
        self.assertEqual(expected['preparationSha256'],PREPARATION_SHA)
        self.assertEqual(expected['chatCalls'],40); self.assertEqual(expected['embeddingCalls'],0)
        plan,_=frozen_plan()
        self.assertEqual([r['payloadSha256'] for r in expected['samples']],
                         [r['payloadSha256'] for r in plan['samples']])

    def test_reference_json_overhead_is_included_for_exactly_twenty_groups(self):
        rows=self.audit['samples']
        suffix=len((JSON_INSTRUCTION+JSON_SCHEMA).encode('utf8'))
        self.assertEqual(sum(r['referenceJsonSuffixBytes']>0 for r in rows),20)
        for row in rows:
            self.assertEqual(row['referenceFrameBytes'],80)
            self.assertEqual(row['referenceJsonSuffixBytes'],suffix if row['arm'] in ('json_mode','combined') else 0)
            self.assertEqual(row['referenceInputTokenBound'],row['messageUtf8Bytes']+80+row['referenceJsonSuffixBytes'])
        plain,row=self.payload(); base=reference_row(plain,row)
        json_body,row=self.payload('json_mode'); json_row=reference_row(json_body,row)
        self.assertEqual(json_row['referenceInputTokenBound']-base['referenceInputTokenBound'],suffix)

    def test_required_input_threshold_is_budget_arithmetic_not_a_proven_limit(self):
        audit=self.audit
        self.assertEqual(audit['totalOutputTokens'],40960)
        self.assertEqual(audit['outputReservedMicros'],327680)
        self.assertEqual(audit['requiredTotalInputTokenBoundAtCap'],836160)
        self.assertEqual(audit['equalAllocationInputThreshold'],20904)
        self.assertEqual(audit['originalAssumedInputTokens'],534812)
        self.assertEqual(audit['originalAssumptionHeadroomTokens'],301348)
        self.assertEqual(audit['originalAssumedReservedMicros'],1397304)
        self.assertIsNone(audit['hostedInputTokenBound']); self.assertIsNone(audit['hostedReservedMicros'])

    def test_joint_context_candidate_is_tighter_still_over_budget_and_not_refrozen(self):
        audit=self.audit
        self.assertEqual(audit['sharedContextCandidateReservedMicros'],84131840)
        self.assertEqual(audit['sharedContextCandidateShortfallMicros'],82131840)
        self.assertEqual(audit['frozenFallbackReservedMicros'],84213760)
        self.assertFalse(audit['frozenFallbackChanged'])
        self.assertEqual(frozen_preparation()['totalReservedMicros'],84213760)
        maximum=84131840//40
        for output in (0,1,512,1024):
            self.assertLessEqual((1048576-output)*2+output*8,maximum)
        self.assertEqual((1048576-1024)*2+1024*8,maximum)

    def test_reference_result_does_not_promote_hosted_proof_or_authorization(self):
        self.assertLess(self.audit['referenceReservedMicros'],2000000)
        self.assertEqual(self.audit['hostedContract'],{gap:'UNPROVEN' for gap in HOSTED_GAPS})
        self.assertEqual(self.audit['decision'],'BLOCKED_HOSTED_BOUND')
        self.assertEqual(self.audit['authorization'],'NOT_GRANTED')
        self.assertFalse(self.audit['liveEnabled']); self.assertFalse(self.audit['sourceVerifiedInCi'])
        self.assertEqual(self.audit['ownerEnvironment'],'UNCONFIRMED')
        self.assertTrue(all(r['hostedInputTokenBound'] is None and r['usableForPaidReservation'] is False
                            for r in self.audit['samples']))
        with patch('socket.socket') as sock,patch('getpass.getpass') as key:
            with self.assertRaisesRegex(ReviewError,'LIVE_NOT_AUTHORIZED'): require_live_ready()
        sock.assert_not_called(); key.assert_not_called()

    def test_sources_revision_price_and_hash_scope_are_closed(self):
        value=source_review()
        self.assertEqual(digest(canonical(value)),SOURCE_REVIEW_SHA)
        self.assertEqual(len(value['sources']),10)
        self.assertEqual(value['observedPrice']['inputMissMicrosPerToken'],2)
        self.assertEqual(value['observedPrice']['outputMicrosPerToken'],8)
        self.assertFalse(value['sourceExecuted']); self.assertFalse(value['officialSourceReplayInCi'])
        self.assertEqual({s['revision'] for s in value['sources'] if 'revision' in s},
                         {'8cadfede7063c896b944e7bae05daa3549ae97ea'})
        with patch('cost_contract.SOURCE_REVIEW_SHA','0'*64),self.assertRaises(ReviewError): source_review()
        with patch('cost_contract.AUDIT_SHA','0'*64),self.assertRaises(ReviewError): verify_audit()

    def test_changed_or_missing_group_and_hosted_promotion_reject(self):
        for kind in ('missing','copied','hosted','approved','negative','boolean','underestimate'):
            rows=deepcopy(self.audit['samples'])
            if kind=='missing': rows.pop()
            if kind=='copied': rows[1]=rows[0]
            if kind=='hosted': rows[0]['hostedInputTokenBound']=rows[0]['referenceInputTokenBound']
            if kind=='approved': rows[0]['usableForPaidReservation']=True
            if kind=='negative': rows[0]['messageUtf8Bytes']=-1
            if kind=='boolean': rows[0]['referenceFrameBytes']=True
            if kind=='underestimate': rows[0]['referenceInputTokenBound']-=1
            with self.assertRaises(ReviewError): analyze(rows)
        review=deepcopy(source_review()); review['observedPrice']['inputMissMicrosPerToken']=1
        with patch('cost_contract.source_review',return_value=review),self.assertRaises(ReviewError):
            analyze(self.audit['samples'])

    def test_request_shape_thinking_tools_image_history_and_sha_drift_reject(self):
        for changes in (dict(thinking={'type':'enabled'}),dict(max_tokens=True),dict(tools=[]),
                        dict(messages=[dict(role='system',content='x')]),
                        dict(messages=[dict(role='system',content='x'),dict(role='user',content=[])])):
            body,row=self.payload(**changes)
            with self.assertRaises(ReviewError): reference_row(body,row)
        body,row=self.payload()
        with self.assertRaises(ReviewError): reference_row(body,{**row,'payloadSha256':'0'*64})

    def test_source_replay_drift_has_fixed_error_and_never_executes_or_fetches(self):
        out,err=io.StringIO(),io.StringIO()
        with contextlib.redirect_stdout(out),contextlib.redirect_stderr(err), \
             patch('cost_contract.Path.read_bytes',return_value=b'UNTRUSTED_SOURCE_SENTINEL'), \
             patch('socket.socket') as sock,patch('subprocess.run') as execute, \
             self.assertRaisesRegex(ReviewError,'COST_SOURCE_DRIFT'):
            verify_local_sources('.')
        self.assertEqual(out.getvalue()+err.getvalue(),''); sock.assert_not_called(); execute.assert_not_called()

    def test_audit_contains_only_bounded_metadata_not_source_or_payload(self):
        serialized=canonical(self.audit)
        for sentinel in (b'SYSTEM_SENTINEL',b'You MUST strictly adhere',b'ProjectRagPromptBuilder',b'"messages"'):
            self.assertNotIn(sentinel,serialized)
        self.assertNotIn('instructions',self.audit)


if __name__=='__main__': unittest.main()
