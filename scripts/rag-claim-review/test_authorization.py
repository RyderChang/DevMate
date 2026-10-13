"""Full transactions, cross-process locks/crashes and same-request offline chain."""
import contextlib
import io
import http.client
import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import unittest
import uuid
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from adapter import StubGrant, configured_validator
from authorization import NewRoundAuthorization, RehearsalAuthorization, RoundLedger
from execution import PREPARATION_SHA, MemoryRequest, MemoryHttpStub, MemoryResponse, frozen_preparation
from governed import LedgerStubCoordinator
from https_driver import DeadlineSocket, HttpsDriver
from plan import canonical, prepare
from review import MANIFEST_SHA, ReviewError, load_context
from run import HERE, ROOT, public_source
from test_provider import envelope
from test_https_driver import WireStub


class AuthorizationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validator=configured_validator(ROOT)
        cls.requests=[]
        for q,arm in ((1,'baseline'),(2,'example')):
            context=load_context((HERE/'frozen-inputs.json').read_bytes(),public_source,q,arm,[])
            body,row=prepare(context)
            cls.requests.append(MemoryRequest(PREPARATION_SHA,q,arm,row['payloadSha256'],body))

    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.grant=RehearsalAuthorization(uuid.uuid4().hex,uuid.uuid4().hex)

    def ledger(self, grant=None):
        ledger=RoundLedger(grant or self.grant,self.temp.name)
        self.addCleanup(ledger.close)
        return ledger

    def stub(self, data=None, **kwargs):
        data=canonical(envelope()) if data is None else data
        return MemoryHttpStub(chunks=tuple((n,data[i:i+4096]) for n,i in enumerate(range(0,len(data),4096))),**kwargs)

    def read(self):
        with contextlib.closing(sqlite3.connect(Path(self.temp.name)/'authorization.sqlite3')) as db:
            return db.execute('SELECT attempt_id,question,arm,payload_sha,sample_id,receipt,code,review_status '
                              'FROM attempts ORDER BY rowid').fetchall()

    def child(self, code, *args):
        env={**os.environ,'PYTHONPATH':str(HERE),'PYTHONDONTWRITEBYTECODE':'1'}
        return subprocess.run([sys.executable,'-B','-c',code,self.temp.name,*args],env=env,
                              capture_output=True,timeout=40)

    def test_live_grant_old_grant_and_scope_cannot_create_files_or_read_keys(self):
        grants=[NewRoundAuthorization(uuid.uuid4().hex,uuid.uuid4().hex,PREPARATION_SHA,'0'*64,'0'*64),
                StubGrant(uuid.uuid4().hex,MANIFEST_SHA,((1,'baseline'),),1,100,100),
                replace(self.grant,scope_sha=MANIFEST_SHA),replace(self.grant,mode='LIVE')]
        with patch('socket.socket') as sock,patch('getpass.getpass') as key:
            for grant in grants:
                with self.assertRaises(ReviewError): RoundLedger(grant,self.temp.name)
        sock.assert_not_called(); key.assert_not_called()
        self.assertEqual(list(Path(self.temp.name).iterdir()),[])

    def test_frozen_plan_and_synthetic_cap_are_separate_from_real_budget(self):
        ledger=self.ledger()
        self.assertEqual(ledger.plan['capMicros'],2000000)
        self.assertEqual(ledger.plan['totalReservedMicros'],84213760)
        with contextlib.closing(sqlite3.connect(Path(self.temp.name)/'authorization.sqlite3')) as db:
            mode,calls,cap=db.execute('SELECT mode,approved_calls,rehearsal_cap FROM rounds').fetchone()
        self.assertEqual((mode,calls,cap),('OFFLINE_LEDGER_STUB',40,84213760))
        self.assertFalse(frozen_preparation()['liveEnabled'])

    def test_unknown_reservation_visible_from_other_connection_before_send(self):
        ledger=self.ledger(); coordinator=LedgerStubCoordinator(ledger,self.validator)
        original=MemoryHttpStub.exchange
        def send(stub,request):
            rows=self.read(); self.assertEqual(len(rows),1)
            self.assertEqual(json.loads(rows[0][5]),dict(status='UNKNOWN',usage=None,reservedMicros=2105344))
            self.assertEqual(rows[0][7],'UNKNOWN')
            return original(stub,request)
        with patch('execution.MemoryHttpStub.exchange',send):
            result=coordinator.exchange(self.requests[0],self.stub())
        self.assertEqual(result.code,'NONE'); self.assertIsNotNone(result.answer)
        self.assertEqual(result.citation_ids,('C2','C1')); self.assertEqual(result.usage['totalTokens'],15)
        rows=self.read(); self.assertEqual(rows[0][6:8],('NONE','UNKNOWN'))
        self.assertEqual(json.loads(rows[0][5])['status'],'SUCCEEDED')
        result.clear(); self.assertIsNone(result.answer)

    def test_reservation_commit_or_save_failure_never_dispatches(self):
        for failure in (OSError('PRIVATE_SAVE'),KeyboardInterrupt()):
            with self.subTest(failure=type(failure).__name__):
                # A separate root is required for each independent rehearsal.
                with tempfile.TemporaryDirectory() as directory:
                    with RoundLedger(RehearsalAuthorization(uuid.uuid4().hex,uuid.uuid4().hex),directory) as ledger:
                        stub=self.stub(); coordinator=LedgerStubCoordinator(ledger,self.validator)
                        with patch.object(ledger,'_commit',side_effect=failure),self.assertRaises(ReviewError):
                            coordinator.exchange(self.requests[0],stub)
                        self.assertEqual(stub.sent,0); self.assertTrue(ledger.stopped)
                        with self.assertRaises(ReviewError): coordinator.exchange(self.requests[1],self.stub())

    def test_full_sqlite_https_parser_provider_and_production_validator_chain(self):
        ledger=self.ledger(); coordinator=LedgerStubCoordinator(ledger,self.validator)
        data=canonical(envelope())
        wire=WireStub(b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: '
                      +str(len(data)).encode()+b'\r\n\r\n'+data)
        def connect(deadline):
            self.assertEqual(json.loads(self.read()[0][5])['status'],'UNKNOWN')
            client=http.client.HTTPConnection('api.deepseek.com',443)
            client.sock=DeadlineSocket(wire,deadline)
            return client
        def exchange(stub,request):
            stub.sent+=1
            with patch('https_driver.connection',connect):
                reply=HttpsDriver()._exchange_once(request,'SYNTHETIC_NON_CREDENTIAL')
            self.assertEqual(reply.code,'NONE')
            return MemoryResponse(reply.request,200,'application/json','identity',((0,reply.envelope),))
        with patch('execution.MemoryHttpStub.exchange',exchange):
            result=coordinator.exchange(self.requests[0],self.stub())
        self.assertEqual(result.code,'NONE'); self.assertEqual(result.citation_ids,('C2','C1'))
        self.assertTrue(wire.closed); self.assertEqual(json.loads(self.read()[0][5])['usage']['totalTokens'],15)
        result.clear()

    def test_ambiguous_commit_retains_unknown_and_never_sends(self):
        ledger=self.ledger(); real=ledger._commit
        def ambiguous(): real(); raise OSError('PRIVATE_COMMIT_ACK')
        stub=self.stub()
        with patch.object(ledger,'_commit',side_effect=ambiguous),self.assertRaises(ReviewError):
            LedgerStubCoordinator(ledger,self.validator).exchange(self.requests[0],stub)
        self.assertEqual(stub.sent,0)
        self.assertEqual(json.loads(self.read()[0][5])['status'],'UNKNOWN')
        self.assertEqual(json.loads(self.read()[0][5])['reservedMicros'],2105344)

    def test_same_sample_reissue_stops_without_releasing_or_topping_up(self):
        ledger=self.ledger(); coordinator=LedgerStubCoordinator(ledger,self.validator)
        coordinator.exchange(self.requests[0],self.stub()).clear()
        second=self.stub()
        with self.assertRaises(ReviewError): coordinator.exchange(replace(self.requests[0]),second)
        self.assertEqual(second.sent,0); self.assertTrue(ledger.stopped)
        self.assertEqual(len(self.read()),1); self.assertEqual(json.loads(self.read()[0][5])['reservedMicros'],2105344)

    def test_failed_unknown_or_interrupt_retains_reservation_and_blocks_next(self):
        for kwargs in (dict(status=429),dict(outcome='TIMEOUT'),dict(outcome='DISCONNECT'),dict(outcome='INTERRUPT')):
            with tempfile.TemporaryDirectory() as directory:
                with RoundLedger(RehearsalAuthorization(uuid.uuid4().hex,uuid.uuid4().hex),directory) as ledger:
                    coordinator=LedgerStubCoordinator(ledger,self.validator); stub=self.stub(**kwargs)
                    result=coordinator.exchange(self.requests[0],stub)
                    self.assertNotEqual(result.code,'NONE'); self.assertEqual(stub.sent,1)
                    next_stub=self.stub()
                    with self.assertRaises(ReviewError): coordinator.exchange(self.requests[1],next_stub)
                    self.assertEqual(next_stub.sent,0)
                    with contextlib.closing(sqlite3.connect(Path(directory)/'authorization.sqlite3')) as db:
                        receipt=json.loads(db.execute('SELECT receipt FROM attempts').fetchone()[0])
                    self.assertEqual(receipt['reservedMicros'],2105344)

    def test_valid_usage_survives_provider_structure_and_validator_failure(self):
        for kind in ('provider','structure','validator'):
            with tempfile.TemporaryDirectory() as directory:
                with RoundLedger(RehearsalAuthorization(uuid.uuid4().hex,uuid.uuid4().hex),directory) as ledger:
                    e=envelope()
                    if kind=='provider': e['choices'][0]['finish_reason']='length'
                    if kind=='structure': e['choices'][0]['message']['content']='{"answer":false,"citationIds":[]}'
                    coordinator=LedgerStubCoordinator(ledger,self.validator)
                    if kind=='validator':
                        with patch.object(self.validator,'validate',side_effect=ReviewError('VALIDATOR_UNAVAILABLE')), \
                             self.assertRaises(ReviewError): coordinator.exchange(self.requests[0],self.stub(canonical(e)))
                    else:
                        result=coordinator.exchange(self.requests[0],self.stub(canonical(e)))
                        self.assertNotEqual(result.code,'NONE'); self.assertIsNone(result.answer)
                    self.assertTrue(ledger.stopped)
                    with contextlib.closing(sqlite3.connect(Path(directory)/'authorization.sqlite3')) as db:
                        receipt=json.loads(db.execute('SELECT receipt FROM attempts').fetchone()[0])
                    self.assertEqual(receipt['usage']['totalTokens'],15)
                    self.assertEqual(receipt['reservedMicros'],2105344)

    def test_over_limit_and_missing_usage_stop_and_keep_full_reservation(self):
        for missing in (False,True):
            with tempfile.TemporaryDirectory() as directory:
                with RoundLedger(RehearsalAuthorization(uuid.uuid4().hex,uuid.uuid4().hex),directory) as ledger:
                    e=envelope()
                    if missing: del e['usage']
                    else: e['usage']=dict(prompt_tokens=1048577,completion_tokens=1025,total_tokens=1049602)
                    result=LedgerStubCoordinator(ledger,self.validator).exchange(self.requests[0],self.stub(canonical(e)))
                    self.assertEqual(result.code,'PROVIDER_USAGE' if missing else 'HTTP_USAGE_EXCEEDED')
                    self.assertIsNone(result.answer); self.assertTrue(ledger.stopped)
                    with contextlib.closing(sqlite3.connect(Path(directory)/'authorization.sqlite3')) as db:
                        receipt=json.loads(db.execute('SELECT receipt FROM attempts').fetchone()[0])
                    self.assertEqual(receipt['reservedMicros'],2105344)
                    if missing: self.assertIsNone(receipt['usage'])
                    else: self.assertEqual(receipt['usage']['totalTokens'],1049602)

    def test_result_save_failure_keeps_committed_usage_and_stops(self):
        ledger=self.ledger(); original=ledger._commit; commits=[0]
        def commit():
            commits[0]+=1
            if commits[0]==3: raise OSError('PRIVATE_RESULT_SAVE')
            original()
        stub=self.stub()
        with patch.object(ledger,'_commit',side_effect=commit),self.assertRaises(ReviewError):
            LedgerStubCoordinator(ledger,self.validator).exchange(self.requests[0],stub)
        self.assertEqual(stub.sent,1); self.assertTrue(ledger.stopped)
        receipt=json.loads(self.read()[0][5])
        self.assertEqual(receipt['status'],'UNKNOWN'); self.assertEqual(receipt['usage']['totalTokens'],15)
        self.assertEqual(receipt['reservedMicros'],2105344)

    def test_copied_cross_request_or_mutated_reply_rejects_usage_attribution(self):
        for kind in ('copy','cross','mutate'):
            with tempfile.TemporaryDirectory() as directory:
                with RoundLedger(RehearsalAuthorization(uuid.uuid4().hex,uuid.uuid4().hex),directory) as ledger:
                    request=replace(self.requests[0])
                    def send(stub,req):
                        other=replace(req) if kind=='copy' else self.requests[1] if kind=='cross' else req
                        if kind=='mutate': object.__setattr__(req,'payload_sha','0'*64)
                        return MemoryResponse(other,200,'application/json','identity',((0,canonical(envelope())),))
                    with patch('execution.MemoryHttpStub.exchange',send):
                        if kind=='mutate':
                            with self.assertRaises(ReviewError): LedgerStubCoordinator(ledger,self.validator).exchange(request,self.stub())
                        else:
                            result=LedgerStubCoordinator(ledger,self.validator).exchange(request,self.stub())
                            self.assertEqual(result.code,'HTTP_PAIRING')
                    with contextlib.closing(sqlite3.connect(Path(directory)/'authorization.sqlite3')) as db:
                        receipt=json.loads(db.execute('SELECT receipt FROM attempts').fetchone()[0])
                    self.assertIsNone(receipt['usage']); self.assertTrue(ledger.stopped)

    def test_process_lock_rejects_concurrent_round_and_preserves_file(self):
        ledger=self.ledger()
        code='''import sys,uuid
from authorization import RoundLedger,RehearsalAuthorization
from review import ReviewError
try:
    ledger=RoundLedger(RehearsalAuthorization(uuid.uuid4().hex,uuid.uuid4().hex),sys.argv[1])
except ReviewError:
    print('LOCK_REJECTED')
else:
    ledger.close(); print('UNEXPECTED')
'''
        child=self.child(code)
        self.assertEqual(child.returncode,0); self.assertEqual(child.stdout.strip(),b'LOCK_REJECTED')
        self.assertEqual(child.stderr,b''); self.assertTrue((Path(self.temp.name)/'authorization.lock').exists())
        ledger.close()

    def test_closed_round_authorization_or_round_id_reuse_cannot_resume(self):
        self.ledger().close()
        for grant in (self.grant,replace(self.grant,round_id=uuid.uuid4().hex),
                      replace(self.grant,authorization_id=uuid.uuid4().hex)):
            with self.assertRaisesRegex(ReviewError,'LEDGER_UNAVAILABLE'): self.ledger(grant)

    def test_process_crash_after_reservation_persists_unknown_and_prevents_resume(self):
        code='''import os,sys
from authorization import RoundLedger,RehearsalAuthorization
from execution import MemoryRequest,PREPARATION_SHA
from plan import prepare
from review import load_context
from run import HERE,public_source
ledger=RoundLedger(RehearsalAuthorization(sys.argv[2],sys.argv[3]),sys.argv[1])
context=load_context((HERE/'frozen-inputs.json').read_bytes(),public_source,1,'baseline',[])
body,row=prepare(context)
ledger.reserve(MemoryRequest(PREPARATION_SHA,1,'baseline',row['payloadSha256'],body))
os._exit(23)
'''
        child=self.child(code,self.grant.authorization_id,self.grant.round_id)
        self.assertEqual(child.returncode,23); self.assertEqual(child.stdout+child.stderr,b'')
        row=self.read()[0]; self.assertEqual(json.loads(row[5]),dict(status='UNKNOWN',usage=None,reservedMicros=2105344))
        with self.assertRaises(ReviewError): self.ledger()

    def test_thread_busy_and_non_stub_transport_cannot_dispatch(self):
        ledger=self.ledger(); coordinator=LedgerStubCoordinator(ledger,self.validator)
        ledger.busy.acquire()
        try:
            stub=self.stub()
            with self.assertRaisesRegex(ReviewError,'ROUND_BUSY'): coordinator.exchange(self.requests[0],stub)
            self.assertEqual(stub.sent,0)
        finally: ledger.busy.release()
        class Child(MemoryHttpStub): pass
        with self.assertRaisesRegex(ReviewError,'OFFLINE_ONLY'): coordinator.exchange(self.requests[0],Child())
        self.assertEqual(self.read(),[])

    def test_mutated_grant_corrupt_database_and_missing_commit_fail_closed(self):
        ledger=self.ledger(); object.__setattr__(ledger.grant,'scope_sha','0'*64)
        stub=self.stub()
        with self.assertRaises(ReviewError): LedgerStubCoordinator(ledger,self.validator).exchange(self.requests[0],stub)
        self.assertEqual(stub.sent,0); ledger.close()
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory)/'authorization.sqlite3').write_bytes(b'CORRUPT_DB_SENTINEL')
            with self.assertRaises(ReviewError): RoundLedger(RehearsalAuthorization(uuid.uuid4().hex,uuid.uuid4().hex),directory)
            self.assertEqual((Path(directory)/'authorization.sqlite3').read_bytes(),b'CORRUPT_DB_SENTINEL')

    def test_answer_source_key_or_error_never_persist_or_reach_output(self):
        ledger=self.ledger(); coordinator=LedgerStubCoordinator(ledger,self.validator)
        sentinel='LEDGER_RESPONSE_BODY_SENTINEL'
        e=envelope(); e['choices'][0]['message']['content']=json.dumps(dict(answer=sentinel+'[C1]',citationIds=['C1']))
        out,err=io.StringIO(),io.StringIO()
        with contextlib.redirect_stdout(out),contextlib.redirect_stderr(err):
            result=coordinator.exchange(self.requests[0],self.stub(canonical(e)))
        self.assertIn(sentinel,result.answer); self.assertNotIn(sentinel,repr(result)); result.clear(); ledger.close()
        self.assertEqual(out.getvalue()+err.getvalue(),'')
        for path in Path(self.temp.name).iterdir():
            data=path.read_bytes()
            self.assertNotIn(sentinel.encode(),data)
            self.assertNotIn(self.requests[0].body,data)


if __name__=='__main__': unittest.main()
