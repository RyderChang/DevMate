"""Offline execution readiness and one-shot HTTP protocol rehearsal.

No socket/client construction, key lookup, live switch or payable grant is exposed.
The existing adapter continues to require its precise OFFLINE_STUB types.
"""
import json
from dataclasses import dataclass, field
from pathlib import Path

from plan import ENDPOINT, PLAN_SHA, canonical, frozen_plan
from review import ARMS, ReviewError, choice, digest, integer, keys, require

HERE = Path(__file__).resolve().parent
EVIDENCE_SHA = 'a7bdc556b1ae31ecbae157182e5d54e9510cf85ba10e40330b4ac85b38578173'
PREPARATION_SHA = 'b11957ebaedeb61e4383fa7bf634be923d354cdfcec07f798a6bfa878f9efd7b'
CONTEXT_LIMIT = 1048576

POLICY = dict(version='claim-http-policy-v1', endpoint=ENDPOINT, method='POST',
              redirects=0, retries=0, concurrency=1, maxRequestBytes=98304,
              maxResponseBytes=1048576, deadlineMs=120000, mediaType='application/json',
              contentEncoding='identity', tls='HTTPS verified certificate and hostname required',
              proxy='NO_IMPLICIT_ENVIRONMENT_PROXY', debugBodyLogging=False,
              unknownConsumesAttempt=True, failureKeepsReservation=True,
              resume=False, topUp=False, liveClientPresent=False)


def evidence():
    try:
        value = json.loads((HERE / 'token-evidence.json').read_bytes())
        require(digest(canonical(value)) == EVIDENCE_SHA, 'READINESS_DRIFT')
        return value
    except BaseException:
        raise ReviewError('READINESS_DRIFT') from None


def build_preparation():
    plan, parent_sha = frozen_plan()
    proof = evidence()
    require(proof['profile']['contextTokens'] == CONTEXT_LIMIT, 'READINESS_DRIFT')
    price = plan['price']
    # Documented model context is the conservative fallback; do not substitute the
    # unproven byte/framing estimate when deciding whether payable execution fits.
    rows = [dict(question=r['question'], arm=r['arm'], payloadSha256=r['payloadSha256'],
                 inputTokenBound=CONTEXT_LIMIT, maxOutputTokens=r['maxOutputTokens'],
                 reservedMicros=CONTEXT_LIMIT * price['inputMissMicrosPerToken']
                                + r['maxOutputTokens'] * price['outputMicrosPerToken'])
            for r in plan['samples']]
    total = sum(r['reservedMicros'] for r in rows)
    return dict(version='claim-execution-preparation-v1', parentPlanSha256=parent_sha,
                authorization='NOT_GRANTED', liveEnabled=False,
                tokenEvidenceSha256=EVIDENCE_SHA, transportPolicy=POLICY,
                model=plan['model'], chatCalls=plan['chatCalls'], embeddingCalls=plan['embeddingCalls'],
                price=price, capMicros=plan['capMicros'], estimatedReservedMicros=plan['totalReservedMicros'],
                costBasis='Documented full context input + separately reserved maximum output; conservative over-reservation',
                totalReservedMicros=total, budgetShortfallMicros=max(0, total-plan['capMicros']),
                readiness='BLOCKED_BUDGET_AND_AUTHORIZATION',
                blockers=['BUDGET_INSUFFICIENT', 'HOSTED_TOKENIZER_BINDING_UNPROVEN',
                          'NO_PAYABLE_AUTHORIZATION', 'NO_LIVE_CLIENT', 'OWNER_ENVIRONMENT_UNCONFIRMED'],
                environment=dict(tk='OFFLINE_SYNTHETIC_TESTS_ONLY',
                                 privateDesktop='OWNER_CONFIRMATION_REQUIRED',
                                 screenRecording='OWNER_CONFIRMATION_REQUIRED',
                                 clipboardHistory='OWNER_CONFIRMATION_REQUIRED',
                                 debuggerAndCrashCapture='OWNER_CONFIRMATION_REQUIRED',
                                 ownerReviewer='OWNER_CONFIRMATION_REQUIRED',
                                 applicationBodyPersistence=False, osMemoryErasureGuarantee=False),
                samples=rows)


def frozen_preparation():
    try:
        value = json.loads((HERE / 'execution-preparation.json').read_bytes())
        require(digest(canonical(value)) == PREPARATION_SHA and value == build_preparation(), 'READINESS_DRIFT')
        return value
    except BaseException:
        raise ReviewError('READINESS_DRIFT') from None


def require_live_ready():
    """Fail before constructing a client, reading credentials or consuming attempts.

    There is deliberately no accepted paid authorization type in this preparation.
    Changing JSON/env vars or passing a StubGrant cannot enable live transmission.
    """
    frozen_preparation()
    raise ReviewError('LIVE_NOT_AUTHORIZED')


@dataclass(frozen=True, repr=False)
class MemoryRequest:
    scope: str
    question: int
    arm: str
    payload_sha: str
    body: bytes = field(repr=False)


@dataclass(repr=False)
class MemoryResponse:
    request: MemoryRequest
    status: int
    content_type: str
    content_encoding: str
    # Simulated absolute milliseconds from request start, BEFORE each read.
    chunks: tuple = field(repr=False)


class MemoryHttpStub:
    """Only in-memory protocol fixtures; no URL, headers, credentials or callback."""
    def __init__(self, status=200, content_type='application/json', content_encoding='identity',
                 chunks=(), outcome='RETURN'):
        self.status, self.content_type, self.content_encoding = status, content_type, content_encoding
        self.chunks, self.outcome = chunks, outcome
        self.sent, self.closed, self.reads = 0, False, 0

    def exchange(self, request):
        self.sent += 1
        if self.outcome == 'TIMEOUT': raise TimeoutError()
        if self.outcome == 'DISCONNECT': raise OSError()
        if self.outcome == 'INTERRUPT': raise KeyboardInterrupt()
        require(self.outcome == 'RETURN', 'HTTP_STUB_INVALID')
        return MemoryResponse(request, self.status, self.content_type, self.content_encoding, self.chunks)

    def close(self):
        self.closed = True
        self.chunks = ()


@dataclass(repr=False)
class ExchangeReceipt:
    status: str
    code: str
    usage: object = field(default=None, repr=False)
    envelope: object = field(default=None, repr=False)

    def clear(self):
        self.usage = self.envelope = None


class OfflineHttpHarness:
    """Rehearse request binding, Stub persistence acknowledgement and response policy.

    The harness is not a payable round ledger or a replacement for OfflineAdapter.
    It only dispatches to exact MemoryHttpStub and accepts no authorization/keys.
    """
    def __init__(self):
        self.used = False

    def live(self):
        require_live_ready()

    def exchange(self, request, stub, reservation):
        buffer = response = None
        try:
            require(type(stub) is MemoryHttpStub and type(request) is MemoryRequest, 'OFFLINE_ONLY')
            preparation = frozen_preparation()
            require(not self.used and request.scope == PREPARATION_SHA, 'HTTP_PAIRING')
            integer(request.question, 1, 10); choice(request.arm, ARMS)
            row = next((r for r in preparation['samples']
                        if (r['question'], r['arm']) == (request.question, request.arm)), None)
            require(row is not None and request.payload_sha == row['payloadSha256']
                    and type(request.body) is bytes and digest(request.body) == request.payload_sha
                    and len(request.body) <= POLICY['maxRequestBytes'], 'HTTP_PAIRING')
            keys(reservation, ('status', 'usage', 'reservedMicros', 'durableUnknown'))
            require(reservation['status'] == 'UNKNOWN' and reservation['usage'] is None
                    and reservation['durableUnknown'] is True
                    and type(reservation['reservedMicros']) is int
                    and reservation['reservedMicros'] == row['reservedMicros'], 'HTTP_RESERVATION')
            # Snapshot BEFORE exchange; mutation or another request's response is rejected.
            snapshot = (request.scope, request.question, request.arm, request.payload_sha, request.body)
            self.used = True  # Unknown/failure retains attempt and reservation. Never retry.
            response = stub.exchange(request)
            require(type(response) is MemoryResponse and response.request is request
                    and (request.scope, request.question, request.arm, request.payload_sha, request.body) == snapshot,
                    'HTTP_PAIRING')
            integer(response.status, 100, 599)
            if response.status != 200:
                # Do not read a Location, error body, headers, reason or upstream id.
                return ExchangeReceipt('FAILED', 'HTTP_STATUS')
            require(type(response.content_type) is str and response.content_type.lower() in
                    ('application/json', 'application/json; charset=utf-8'), 'HTTP_CONTENT_TYPE')
            require(response.content_encoding == 'identity', 'HTTP_CONTENT_ENCODING')
            require(type(response.chunks) is tuple and len(response.chunks) <= 257, 'HTTP_RESPONSE_SIZE')
            buffer = bytearray()
            previous = 0
            for elapsed, chunk in response.chunks:
                integer(elapsed, previous, 120000)
                require(elapsed < POLICY['deadlineMs'], 'HTTP_DEADLINE')
                previous = elapsed
                require(type(chunk) is bytes and len(chunk) <= 4096, 'HTTP_RESPONSE_SIZE')
                stub.reads += 1
                require(len(buffer) + len(chunk) <= POLICY['maxResponseBytes'], 'HTTP_RESPONSE_SIZE')
                buffer.extend(chunk)
            from provider import decode
            decoded = decode(bytes(buffer))
            if decoded.usage is not None:
                usage = decoded.usage
                if (usage['inputTokens'] > row['inputTokenBound']
                        or usage['outputTokens'] > row['maxOutputTokens']
                        or usage['inputTokens'] * preparation['price']['inputMissMicrosPerToken']
                           + usage['outputTokens'] * preparation['price']['outputMicrosPerToken']
                           > row['reservedMicros']):
                    return ExchangeReceipt('FAILED', 'HTTP_USAGE_EXCEEDED', usage)
            if decoded.code != 'NONE':
                return ExchangeReceipt('FAILED', decoded.code, decoded.usage)
            # Content remains raw; production RAG validation belongs to OfflineAdapter.
            return ExchangeReceipt('SUCCEEDED', 'NONE', decoded.usage, bytes(buffer))
        except BaseException as error:
            if type(error) is ReviewError:
                code = str(error)
                if code not in ('OFFLINE_ONLY', 'READINESS_DRIFT', 'HTTP_PAIRING', 'HTTP_RESERVATION',
                                'HTTP_CONTENT_TYPE', 'HTTP_CONTENT_ENCODING', 'HTTP_RESPONSE_SIZE', 'HTTP_DEADLINE'):
                    code = 'HTTP_PROTOCOL'
            else:
                code = 'HTTP_RESULT_UNKNOWN'
            return ExchangeReceipt('UNKNOWN', code)
        finally:
            if type(stub) is MemoryHttpStub: stub.close()
            if buffer is not None: buffer.clear()
            if 'decoded' in locals(): decoded.raw = None
            response = buffer = request = None


if __name__ == '__main__':
    try:
        value = frozen_preparation()
        print(json.dumps(dict(preparationSha256=PREPARATION_SHA, readiness=value['readiness'],
                              totalReservedMicros=value['totalReservedMicros'], capMicros=value['capMicros'],
                              budgetShortfallMicros=value['budgetShortfallMicros'],
                              ownerEnvironment='UNCONFIRMED', authorization='NOT_GRANTED', paidRequests=0)))
    except BaseException:
        print('EXECUTION_PREPARATION_STOPPED')
        raise SystemExit(1) from None
