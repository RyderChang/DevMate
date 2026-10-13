"""Fixed HTTPS implementation. Public entry is closed; tests replace the wire.

No environment proxy, redirect handler, retry, body logging or configurable URL.
The private wire routine is not an authorization entry point.
"""
import http.client
import io
import queue
import socket
import ssl
import threading
import time
from dataclasses import dataclass, field

from execution import POLICY, PREPARATION_SHA, MemoryRequest, require_live_ready, frozen_preparation
from review import ReviewError, digest, require

HOST = 'api.deepseek.com'
PATH = '/chat/completions'


class Deadline:
    def __init__(self):
        self.end = time.monotonic() + POLICY['deadlineMs'] / 1000

    def remaining(self):
        remaining = self.end - time.monotonic()
        require(remaining > 0, 'HTTP_DEADLINE')
        return remaining


class DeadlineReader(io.RawIOBase):
    def __init__(self, wire, deadline, on_close=None):
        super().__init__()
        self.wire, self.deadline = wire, deadline
        self.received = 0
        self.on_close = on_close

    def readable(self):
        return True

    def readinto(self, buffer):
        self.wire.settimeout(self.deadline.remaining())
        count = self.wire.recv_into(buffer)
        self.received += count
        # Includes headers and chunk framing, not just decoded entity bytes.
        require(self.received <= POLICY['maxResponseBytes'], 'HTTP_RESPONSE_SIZE')
        self.deadline.remaining()  # Includes slow headers/chunk framing and EOF.
        return count

    def close(self):
        if not self.closed:
            super().close()
            if self.on_close is not None: self.on_close()


class DeadlineSocket:
    def __init__(self, wire, deadline):
        self.wire, self.deadline = wire, deadline
        self.readers, self.closing = 0, False

    def sendall(self, data):
        self.wire.settimeout(self.deadline.remaining())
        self.wire.sendall(data)
        self.deadline.remaining()

    def makefile(self, mode):
        require(mode == 'rb', 'HTTP_PROTOCOL')
        self.readers += 1
        return io.BufferedReader(DeadlineReader(self.wire, self.deadline, self._release))

    def _release(self):
        self.readers -= 1
        if self.closing and self.readers == 0: self.wire.close()

    def close(self):
        if not self.closing:
            self.closing = True
            if self.readers == 0: self.wire.close()


def resolve(deadline):
    # getaddrinfo has no portable timeout. A bounded daemon may finish DNS later;
    # it has no socket, key or payload and can never dispatch after the deadline.
    result = queue.Queue(maxsize=1)
    def lookup():
        try:
            result.put(socket.getaddrinfo(HOST, 443, type=socket.SOCK_STREAM))
        except BaseException:
            result.put(None)
    threading.Thread(target=lookup, daemon=True).start()
    try:
        addresses = result.get(timeout=deadline.remaining())
    except queue.Empty:
        raise ReviewError('HTTP_DEADLINE') from None
    deadline.remaining()
    require(bool(addresses), 'HTTP_RESULT_UNKNOWN')
    return addresses[0]  # One address, one connect; no connection fallback/retry.


def connection(deadline):
    require_live_ready()  # Also guard direct/private wire invocation at socket creation.
    context = ssl.create_default_context()
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    require(context.verify_mode == ssl.CERT_REQUIRED and context.check_hostname, 'HTTP_TLS')
    context.set_alpn_protocols(['http/1.1'])
    family, kind, protocol, _, address = resolve(deadline)
    wire = secured = None
    try:
        wire = socket.socket(family, kind, protocol)
        wire.settimeout(deadline.remaining())
        wire.connect(address)
        wire.settimeout(deadline.remaining())
        secured = context.wrap_socket(wire, server_hostname=HOST)
        deadline.remaining()
        # With an attached verified socket, HTTPConnection does not resolve again.
        client = http.client.HTTPConnection(HOST, 443)
        client.sock = DeadlineSocket(secured, deadline)
        return client
    except BaseException:
        if secured is not None: secured.close()
        elif wire is not None: wire.close()
        raise ReviewError('HTTP_RESULT_UNKNOWN') from None


def validate_request(request):
    require(type(request) is MemoryRequest and request.scope == PREPARATION_SHA, 'HTTP_PAIRING')
    preparation = frozen_preparation()
    row = next((r for r in preparation['samples']
                if type(request.question) is int and (r['question'], r['arm']) == (request.question, request.arm)), None)
    require(row is not None and type(request.body) is bytes
            and len(request.body) <= POLICY['maxRequestBytes']
            and request.payload_sha == row['payloadSha256'] == digest(request.body), 'HTTP_PAIRING')
    return row


@dataclass(repr=False)
class WireReply:
    request: MemoryRequest
    status: str
    code: str
    envelope: object = field(default=None, repr=False)

    def clear(self):
        self.request = self.envelope = None


class HttpsDriver:
    def exchange(self, request):
        require_live_ready()  # Before credentials, DNS, sockets and reservations.
        raise ReviewError('LIVE_NOT_AUTHORIZED')

    def _exchange_once(self, request, key):
        """Internal wire implementation, exercised with synthetic keys and Stub IO.

        A future authorized coordinator must own reservation + key acquisition.
        This routine supplies neither, and is not exposed by any CLI.
        """
        client = response = buffer = None
        try:
            validate_request(request)
            require(type(key) is str and 0 < len(key) <= 4096
                    and all(33 <= ord(c) <= 126 for c in key), 'HTTP_CREDENTIAL')
            snapshot = (request.scope, request.question, request.arm, request.payload_sha, request.body)
            deadline = Deadline()
            client = connection(deadline)
            client.request('POST', PATH, body=request.body,
                           headers={'Host': HOST, 'Authorization': 'Bearer ' + key,
                                    'Content-Type': 'application/json', 'Accept': 'application/json',
                                    'Accept-Encoding': 'identity', 'Connection': 'close'})
            deadline.remaining()
            response = client.getresponse()
            deadline.remaining()
            require(snapshot == (request.scope, request.question, request.arm, request.payload_sha, request.body),
                    'HTTP_PAIRING')
            validate_request(request)
            if response.status != 200:
                return WireReply(request, 'FAILED', 'HTTP_STATUS')  # Never read error body or Location.
            types = response.headers.get_all('Content-Type', [])
            encodings = response.headers.get_all('Content-Encoding', [])
            require(len(types) == 1 and types[0].lower() in
                    ('application/json', 'application/json; charset=utf-8'), 'HTTP_CONTENT_TYPE')
            require(encodings in ([], ['identity']), 'HTTP_CONTENT_ENCODING')
            lengths = response.headers.get_all('Content-Length', [])
            transfers = response.headers.get_all('Transfer-Encoding', [])
            require(len(lengths) <= 1 and transfers in ([], ['chunked'])
                    and not (lengths and transfers), 'HTTP_PROTOCOL')
            if lengths:
                require(lengths[0].isascii() and lengths[0].isdigit()
                        and len(lengths[0]) <= 10 and int(lengths[0]) <= POLICY['maxResponseBytes'],
                        'HTTP_RESPONSE_SIZE')
            buffer = bytearray()
            while True:
                deadline.remaining()
                chunk = response.read1(min(4096, POLICY['maxResponseBytes'] - len(buffer) + 1))
                deadline.remaining()
                if not chunk: break
                require(len(buffer) + len(chunk) <= POLICY['maxResponseBytes'], 'HTTP_RESPONSE_SIZE')
                buffer.extend(chunk)
            require(not lengths or len(buffer) == int(lengths[0]), 'HTTP_PROTOCOL')
            require(snapshot == (request.scope, request.question, request.arm, request.payload_sha, request.body),
                    'HTTP_PAIRING')
            validate_request(request)
            return WireReply(request, 'SUCCEEDED', 'NONE', bytes(buffer))
        except BaseException as error:
            code = str(error) if type(error) is ReviewError else 'HTTP_RESULT_UNKNOWN'
            if code not in ('HTTP_PAIRING', 'HTTP_CREDENTIAL', 'HTTP_DEADLINE', 'HTTP_CONTENT_TYPE',
                            'HTTP_CONTENT_ENCODING', 'HTTP_PROTOCOL', 'HTTP_RESPONSE_SIZE'):
                code = 'HTTP_RESULT_UNKNOWN'
            return WireReply(request, 'UNKNOWN', code)
        finally:
            if buffer is not None: buffer.clear()
            if response is not None:
                try: response.close()
                except BaseException: pass
            if client is not None:
                try: client.close()
                except BaseException: pass
            key = request = response = client = None
