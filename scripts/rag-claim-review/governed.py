"""Independent ledger -> exact Stub exchange -> same-envelope production validation.

No payable dispatch or credentials. Returned content remains in memory for a
future review coordinator; saved metadata never claims a completed human review.
"""
from dataclasses import dataclass, field

from adapter import ISSUES, ProductionValidator
from authorization import RoundLedger
from execution import MemoryHttpStub, MemoryRequest, OfflineHttpHarness
from https_driver import validate_request
from provider import decode
from review import ReviewError, require

CODES = ISSUES + ('NONE', 'HTTP_STATUS', 'HTTP_RESULT_UNKNOWN', 'HTTP_PAIRING', 'HTTP_PROTOCOL',
                 'HTTP_CONTENT_TYPE', 'HTTP_CONTENT_ENCODING', 'HTTP_RESPONSE_SIZE', 'HTTP_DEADLINE',
                 'HTTP_USAGE_EXCEEDED', 'SAVE_FAILED', 'VALIDATOR_UNAVAILABLE',
                 'PROVIDER_ENVELOPE', 'PROVIDER_USAGE', 'PROVIDER_MODEL', 'PROVIDER_FINISH', 'PROVIDER_CONTENT')


@dataclass(repr=False)
class GovernedResult:
    attempt_id: str
    code: str
    usage: object = field(default=None, repr=False)
    answer: object = field(default=None, repr=False)
    citation_ids: object = field(default=None, repr=False)

    def clear(self):
        self.usage = self.answer = self.citation_ids = None


class LedgerStubCoordinator:
    def __init__(self, ledger, validator):
        require(type(ledger) is RoundLedger and type(validator) is ProductionValidator, 'OFFLINE_ONLY')
        self.ledger, self.validator = ledger, validator

    def exchange(self, request, stub):
        require(type(stub) is MemoryHttpStub and type(request) is MemoryRequest, 'OFFLINE_ONLY')
        require(self.ledger.busy.acquire(blocking=False), 'ROUND_BUSY')
        attempt = response = decoded = result = None
        receipt = None
        try:
            row = validate_request(request)
            attempt = self.ledger.reserve(request)
            receipt = dict(status='UNKNOWN', usage=None, reservedMicros=row['reservedMicros'])
            # The boolean here is private to this coordinator and follows actual
            # SQLite commit, unlike the earlier standalone memory rehearsal.
            response = OfflineHttpHarness().exchange(request, stub, {**receipt, 'durableUnknown': True})
            self.ledger.paired(attempt, request)
            require(response.code in CODES, 'HTTP_PROTOCOL')
            receipt['usage'] = response.usage
            self.ledger.record(attempt, request, receipt, response.code)
            if response.code != 'NONE':
                receipt['status'] = response.status
                self.ledger.record(attempt, request, receipt, response.code)
                self.ledger.stop()
                return GovernedResult(attempt, response.code, response.usage)
            # Same exchange envelope -> usage -> raw JSON -> production validator.
            decoded = decode(response.envelope)
            require(decoded.code == 'NONE' and decoded.usage == response.usage, 'HTTP_PAIRING')
            result = self.validator.validate(decoded.raw)
            receipt['status'] = result['status']
            code = result.get('code', 'NONE')
            self.ledger.record(attempt, request, receipt, code)
            if code != 'NONE':
                self.ledger.stop()  # All failures stop; no retransmission or refill.
                return GovernedResult(attempt, code, decoded.usage)
            self.ledger.active = None
            return GovernedResult(attempt, 'NONE', decoded.usage, result['answer'], tuple(result['citationIds']))
        except BaseException as error:
            if attempt is not None and receipt is not None and not self.ledger.stopped:
                code = str(error) if type(error) is ReviewError else 'HTTP_RESULT_UNKNOWN'
                if code not in CODES: code = 'HTTP_RESULT_UNKNOWN'
                try: self.ledger.record(attempt, request, receipt, code)
                except BaseException: pass  # Retain the earlier durable UNKNOWN/usage.
            self.ledger.stop()
            raise ReviewError('GOVERNED_STOPPED') from None
        finally:
            if response is not None: response.clear()
            if decoded is not None: decoded.raw = decoded.usage = None
            if type(stub) is MemoryHttpStub: stub.close()
            self.ledger.busy.release()
            request = decoded = result = response = None
