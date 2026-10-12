"""Offline-only single-attempt adapter. No network client, live authorization or resume.

The Stub and production validator boundary are intentionally separate. A future live
driver needs its own reviewed authorization path; these grants permit only Stub use.
"""
import json
import os
import re
import subprocess
import uuid
from dataclasses import dataclass, field, replace
from pathlib import Path

from review import (ARMS, LOCATORS, MANIFEST_SHA, AtomicSink, ReviewError, Session,
                    choice, integer, keys, require, safe_context, safe_receipt)

ISSUES = ('JSON_SIZE', 'JSON_DUPLICATE_KEY', 'JSON_SYNTAX', 'JSON_TRAILING',
          'JSON_SHAPE', 'ANSWER_CONTENT', 'CITATION_IDS', 'CITATION_MARKERS')
FAILURES = ISSUES + ('NONE', 'PAIRING_REJECTED', 'PROVIDER_FAILED', 'RESULT_UNKNOWN',
                     'INTERRUPTED', 'VALIDATOR_UNAVAILABLE', 'SAVE_FAILED', 'USAGE_EXCEEDED')
# Fixed simulated rates for offline ledger tests; never a current provider price quote.
STUB_INPUT_MICROS = 2
STUB_OUTPUT_MICROS = 8


def local_id(value):
    require(type(value) is str and re.fullmatch('[a-f0-9]{32}', value) is not None)
    return value


@dataclass(frozen=True, repr=False)
class StubGrant:
    round_id: str
    scope: str
    samples: tuple
    calls: int
    cap_micros: int
    reserve_micros: int
    mode: str = 'OFFLINE_STUB'

    def validate(self):
        local_id(self.round_id)
        require(self.mode == 'OFFLINE_STUB' and self.scope == MANIFEST_SHA, 'AUTHORIZATION_REJECTED')
        integer(self.calls, 1, 40)
        integer(self.cap_micros, 1, 2000000)
        integer(self.reserve_micros, 1, self.cap_micros)
        require(type(self.samples) is tuple and 0 < len(self.samples) <= 40)
        for q, arm in self.samples:
            integer(q, 1, 10); choice(arm, ARMS)
        require(len(set(self.samples)) == len(self.samples))


@dataclass(frozen=True, repr=False)
class Ticket:
    attempt_id: str
    round_id: str
    question: int
    arm: str
    scope: str
    # Tuple of fixed immutable candidate locators; no source/question body here.
    candidates: tuple = field(repr=False)


@dataclass(repr=False)
class StubReply:
    ticket: Ticket
    status: str
    usage: object = field(repr=False)
    raw: object = field(repr=False)
    citations: object = field(repr=False)

    def clear(self):
        self.raw = None
        self.citations = None
        self.usage = None
        self.ticket = None


class StubTransport:
    """A deterministic memory-only response; never accepts URLs, keys or clients."""
    def __init__(self, raw, ids=('C1',), usage=None, status='SUCCEEDED'):
        self.raw, self.ids, self.status = raw, tuple(ids), status
        self.usage = usage if usage is not None else dict(inputTokens=10, outputTokens=5, totalTokens=15)
        self.sent = 0

    def send(self, ticket):
        self.sent += 1
        citations = [{'citationId': cid, 'source': dict(zip(LOCATORS, ticket.candidates[int(cid[1]) - 1]))}
                     for cid in self.ids] if self.status == 'SUCCEEDED' else []
        return StubReply(ticket, self.status, self.usage,
                         self.raw if self.status == 'SUCCEEDED' else None, citations)

    def clear(self):
        self.raw = None; self.usage = None; self.ids = ()


class ProductionValidator:
    """Use the existing Java validator, without reimplementing its JSON contract."""
    def __init__(self, java, classpath):
        self.command = [str(java), '-XX:-CreateCoredumpOnCrash', '-cp', str(classpath),
                        'com.devmate.conversation.service.ClaimReviewValidationBridge']

    def validate(self, raw):
        result = payload = None
        try:
            require(type(raw) is str, 'VALIDATOR_UNAVAILABLE')
            payload = json.dumps({'raw': raw, 'allowed': [f'C{i}' for i in range(1, 6)]},
                                 ensure_ascii=True).encode('utf8')
            require(len(payload) <= 1048576, 'VALIDATOR_UNAVAILABLE')
            result = subprocess.run(self.command, input=payload, capture_output=True, timeout=30)
            require(result.returncode == 0 and len(result.stdout) <= 1048576, 'VALIDATOR_UNAVAILABLE')
            parsed = json.loads(result.stdout)
            if parsed.get('status') == 'FAILED':
                keys(parsed, ('status', 'code'))
                choice(parsed['code'], ISSUES + ('VALIDATOR_UNAVAILABLE',))
            else:
                keys(parsed, ('status', 'answer', 'citationIds'))
                require(parsed['status'] == 'SUCCEEDED', 'VALIDATOR_UNAVAILABLE')
            return parsed
        except BaseException:
            # Never echo a pipe, parser error or traceback containing response text.
            raise ReviewError('VALIDATOR_UNAVAILABLE') from None
        finally:
            result = payload = raw = None


def configured_validator(root):
    """Classpath must be prepared locally with Maven; never download or build here."""
    try:
        root = Path(root)
        java_home = Path(os.environ['JAVA_HOME'])
        dependencies = (root / 'tmp/dev-034/validator-classpath.txt').read_text(encoding='utf8').strip()
        require(bool(dependencies), 'VALIDATOR_UNAVAILABLE')
        cp = os.pathsep.join([str(root / 'devmate-server/target/test-classes'),
                              str(root / 'devmate-server/target/classes'), dependencies])
        return ProductionValidator(java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java'), cp)
    except BaseException:
        raise ReviewError('VALIDATOR_UNAVAILABLE') from None


class OfflineAdapter:
    """Durable unknown reservation precedes dispatch; every sample is attempted once.

    All reservations remain held (even known failures). Unknown outcomes, interrupts,
    pairing/usage/validator/save failures stop this round. Existing round files reject
    reconstruction; there is deliberately no load, retry, resume or top-up method.
    """
    def __init__(self, grant, directory, validator):
        require(type(grant) is StubGrant and type(validator) is ProductionValidator)
        grant.validate()
        self.grant, self.directory, self.validator = grant, Path(directory), validator
        self.rows, self.stopped = [], False
        self.path = self.directory / ('stub-ledger-' + grant.round_id + '.json')
        try:
            self.directory.mkdir(parents=True, exist_ok=True)
            with self.path.open('xb') as file:
                file.write(self._payload()); file.flush(); os.fsync(file.fileno())
        except BaseException:
            raise ReviewError('ROUND_UNAVAILABLE') from None

    def _payload(self):
        self.grant.validate()
        require(type(self.stopped) is bool)
        rows = []
        for row in self.rows:
            keys(row, ('attemptId', 'sampleId', 'question', 'arm', 'receipt', 'code'))
            local_id(row['attemptId']); local_id(row['sampleId'])
            integer(row['question'], 1, 10); choice(row['arm'], ARMS)
            require((row['question'], row['arm']) in self.grant.samples)
            choice(row['code'], FAILURES)
            receipt = safe_receipt(row['receipt'])
            require(receipt['reservedMicros'] == self.grant.reserve_micros)
            rows.append({**row, 'receipt': receipt})
        require(len(rows) <= self.grant.calls)
        require(len({(r['question'], r['arm']) for r in rows}) == len(rows))
        require(sum(r['receipt']['reservedMicros'] for r in rows) <= self.grant.cap_micros)
        value = {'version': 'claim-stub-ledger-v1', 'mode': 'OFFLINE_STUB',
                 'roundId': self.grant.round_id, 'scopeSha256': self.grant.scope,
                 'approvedCalls': self.grant.calls, 'capMicros': self.grant.cap_micros,
                 'reservePerCallMicros': self.grant.reserve_micros,
                 'stubPriceMicros': {'inputToken': STUB_INPUT_MICROS, 'outputToken': STUB_OUTPUT_MICROS},
                 'samples': [list(x) for x in self.grant.samples], 'stopped': self.stopped,
                 'attempts': rows}
        return json.dumps(value, ensure_ascii=True, sort_keys=True).encode('utf8')

    def _save(self):
        temporary = self.directory / ('ledger-' + uuid.uuid4().hex + '.tmp')
        try:
            payload = self._payload()
            with temporary.open('xb') as file:
                file.write(payload); file.flush(); os.fsync(file.fileno())
            os.replace(temporary, self.path)
        except BaseException:
            self.stopped = True
            try: temporary.unlink(missing_ok=True)
            except OSError: pass
            raise ReviewError('SAVE_FAILED') from None

    def review(self, context, transport):
        session = sink = reply = parsed = ticket = None
        row = None
        try:
            require(type(transport) is StubTransport, 'OFFLINE_ONLY')
            safe_context(context)
            require(context.mode == 'FROZEN' and not context.returned, 'PAIRING_REJECTED')
            sample = (context.question, context.arm)
            require(not self.stopped and context.scope == self.grant.scope
                    and sample in self.grant.samples, 'AUTHORIZATION_REJECTED')
            require(len(self.rows) < self.grant.calls
                    and (len(self.rows) + 1) * self.grant.reserve_micros <= self.grant.cap_micros
                    and all((r['question'], r['arm']) != sample for r in self.rows), 'QUOTA_EXHAUSTED')
            ticket = Ticket(uuid.uuid4().hex, self.grant.round_id, *sample, context.scope,
                            tuple(tuple(c[k] for k in LOCATORS) for c in context.chunks))
            receipt = dict(status='UNKNOWN', usage=None, reservedMicros=self.grant.reserve_micros)
            session = Session(replace(context, mode='OFFLINE_STUB'), None, receipt)
            sink = AtomicSink(self.directory, session)
            row = dict(attemptId=ticket.attempt_id, sampleId=session.sample, question=sample[0],
                       arm=sample[1], receipt=receipt, code='RESULT_UNKNOWN')
            self.rows.append(row)
            self._save(); sink.save(session)  # Both durable unknowns BEFORE any send.
            reply = transport.send(ticket)  # Exactly once; no retry even on BaseException.
            require(type(reply) is StubReply, 'PAIRING_REJECTED')
            require(reply.ticket is ticket
                    and (ticket.attempt_id, ticket.round_id, ticket.question, ticket.arm, ticket.scope, ticket.candidates)
                    == (row['attemptId'], self.grant.round_id, *sample, context.scope,
                        tuple(tuple(c[k] for k in LOCATORS) for c in context.chunks)), 'PAIRING_REJECTED')
            usage = safe_receipt(dict(status='UNKNOWN', usage=reply.usage,
                                      reservedMicros=self.grant.reserve_micros))['usage']
            row['receipt'] = dict(status='UNKNOWN', usage=usage, reservedMicros=self.grant.reserve_micros)
            session.receipt = row['receipt']
            self._save(); sink.save(session)  # Preserve usage even if subsequent pairing/JSON fails.
            choice(reply.status, ('SUCCEEDED', 'FAILED', 'UNKNOWN'))
            if usage and (usage['inputTokens'] * STUB_INPUT_MICROS
                          + usage['outputTokens'] * STUB_OUTPUT_MICROS) > self.grant.reserve_micros:
                raise ReviewError('USAGE_EXCEEDED')
            if reply.status != 'SUCCEEDED':
                require(reply.raw is None and reply.citations == [], 'PAIRING_REJECTED')
                row['receipt']['status'] = reply.status
                row['code'] = 'PROVIDER_FAILED' if reply.status == 'FAILED' else 'RESULT_UNKNOWN'
                self.stopped = True  # Unknown/provider failures require investigation; no follow-on send.
            else:
                parsed = self.validator.validate(reply.raw)
                if parsed['status'] == 'FAILED':
                    row['receipt']['status'] = 'FAILED'; row['code'] = parsed['code']
                    if parsed['code'] == 'VALIDATOR_UNAVAILABLE': self.stopped = True
                else:
                    require(type(reply.citations) is list and len(reply.citations) == len(parsed['citationIds']),
                            'PAIRING_REJECTED')
                    for actual, cid in zip(reply.citations, parsed['citationIds']):
                        keys(actual, ('citationId', 'source')); keys(actual['source'], LOCATORS)
                        require(actual['citationId'] == cid, 'PAIRING_REJECTED')
                        expected = context.chunks[int(cid[1]) - 1]
                        require(all(type(actual['source'][k]) is type(expected[k])
                                    and actual['source'][k] == expected[k] for k in LOCATORS), 'PAIRING_REJECTED')
                    session.clear()
                    session = Session(replace(context, mode='OFFLINE_STUB', returned=tuple(parsed['citationIds'])),
                                      parsed['answer'], {**row['receipt'], 'status': 'SUCCEEDED'})
                    session.sample = row['sampleId']
                    row['receipt']['status'] = 'SUCCEEDED'; row['code'] = 'NONE'
            session.receipt = safe_receipt(row['receipt'])
            self._save(); sink.save(session)
            if session.answer is not None:
                from window import ReviewWindow
                ReviewWindow(session, sink).run()
                if session.status != 'COMPLETED':
                    self.stopped = True; row['code'] = 'INTERRUPTED'; self._save()
            return sink.path
        except BaseException as error:
            if row is not None:
                self.stopped = True
                code = str(error) if type(error) is ReviewError else 'RESULT_UNKNOWN'
                row['code'] = code if code in FAILURES else 'PAIRING_REJECTED'
                if session is not None:
                    session.interrupt()
                    try: sink.save(session)
                    except BaseException: pass  # Earlier durable unknown/usage receipt remains.
                try: self._save()
                except BaseException: pass  # Never dispatch again after a persistence failure.
            raise ReviewError('ADAPTER_STOPPED') from None
        finally:
            if session is not None: session.clear()
            if type(reply) is StubReply: reply.clear()
            if type(transport) is StubTransport: transport.clear()
            context = reply = parsed = ticket = None
