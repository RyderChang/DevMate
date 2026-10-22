"""Offline wire Stubs only, including stdlib header/chunk parser and TLS setup."""
import contextlib
import http.client
import io
import json
import ssl
import unittest
from dataclasses import replace
from email.message import Message
from unittest.mock import Mock, patch

from execution import MemoryRequest, PREPARATION_SHA, POLICY
from https_driver import (Deadline, DeadlineReader, DeadlineSocket, HOST, PATH,
                          HttpsDriver, connection, resolve)
from plan import prepare
from review import ReviewError, load_context
from run import HERE, public_source
from test_provider import envelope


class ResponseStub:
    def __init__(self, status=200, data=b'{}', headers=None):
        self.status, self.data, self.reads, self.closed = status, data, 0, False
        self.headers = Message()
        for key, value in (headers or [('Content-Type', 'application/json')]):
            self.headers[key] = value

    def read1(self, count):
        self.reads += 1
        data, self.data = self.data[:count], self.data[count:]
        return data

    def close(self): self.closed = True


class WireStub:
    def __init__(self, data, tick=None):
        self.data, self.tick, self.timeouts = data, tick, []
        self.closed = False

    def settimeout(self, value): self.timeouts.append(value)

    def recv_into(self, buffer):
        if self.tick: self.tick()
        count = min(len(buffer), len(self.data))
        buffer[:count], self.data = self.data[:count], self.data[count:]
        return count

    def sendall(self, data): pass

    def close(self): self.closed = True


class HttpsDriverTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        context = load_context((HERE/'frozen-inputs.json').read_bytes(), public_source, 1, 'baseline', [])
        cls.body, cls.row = prepare(context)

    def request(self):
        return MemoryRequest(PREPARATION_SHA, 1, 'baseline', self.row['payloadSha256'], self.body)

    def send(self, response=None, request=None):
        response = response or ResponseStub()
        client = Mock()
        client.getresponse.return_value = response
        with patch('https_driver.connection', return_value=client) as connect:
            reply = HttpsDriver()._exchange_once(request or self.request(), 'SYNTHETIC_NON_CREDENTIAL')
        return reply, response, client, connect

    def test_public_entry_fails_before_key_dns_socket_or_connection(self):
        with patch('https_driver.connection') as client, patch('socket.socket') as sock, \
             patch('socket.getaddrinfo') as dns, patch('getpass.getpass') as key:
            with self.assertRaisesRegex(ReviewError, '^LIVE_NOT_AUTHORIZED$'):
                HttpsDriver().exchange(self.request())
        for mock in (client, sock, dns, key): mock.assert_not_called()

    def test_endpoint_method_body_headers_and_single_dispatch(self):
        reply, response, client, connect = self.send(ResponseStub(data=json.dumps(envelope()).encode()))
        self.assertEqual(reply.code, 'NONE')
        self.assertEqual(reply.envelope, json.dumps(envelope()).encode())
        self.assertNotIn('SYNTHETIC', repr(reply))
        self.assertTrue(response.closed); client.close.assert_called_once(); connect.assert_called_once()
        client.request.assert_called_once()
        args, kwargs = client.request.call_args
        self.assertEqual(args, ('POST', '/chat/completions'))
        self.assertEqual(kwargs['body'], self.body)
        self.assertEqual(kwargs['headers']['Host'], 'api.deepseek.com')
        self.assertEqual(kwargs['headers']['Accept-Encoding'], 'identity')
        reply.clear(); self.assertIsNone(reply.envelope)

    def test_tls_verified_hostname_default_ca_minimum_version_no_proxy(self):
        context, raw, secured = Mock(), Mock(), Mock()
        context.verify_mode, context.check_hostname = ssl.CERT_REQUIRED, True
        context.wrap_socket.return_value = secured
        with patch('https_driver.ssl.create_default_context', return_value=context) as factory, \
             patch('https_driver.require_live_ready'), \
             patch('https_driver.resolve', return_value=(2,1,6,'',('192.0.2.1',443))), \
             patch('https_driver.socket.socket', return_value=raw) as sock, \
             patch.dict('os.environ', {'HTTPS_PROXY':'https://invalid.invalid'}):
            client = connection(Deadline())
        factory.assert_called_once_with()
        self.assertEqual(context.minimum_version, ssl.TLSVersion.TLSv1_2)
        context.wrap_socket.assert_called_once_with(raw, server_hostname=HOST)
        raw.connect.assert_called_once_with(('192.0.2.1',443)); sock.assert_called_once()
        self.assertIsInstance(client.sock, DeadlineSocket)
        client.close(); secured.close.assert_called_once()

    def test_insecure_context_or_tls_failure_never_falls_back(self):
        insecure=Mock(verify_mode=ssl.CERT_NONE,check_hostname=False)
        with patch('https_driver.require_live_ready'), \
             patch('https_driver.ssl.create_default_context',return_value=insecure), \
             patch('https_driver.resolve') as dns, self.assertRaises(ReviewError): connection(Deadline())
        dns.assert_not_called()
        context, raw=Mock(),Mock()
        context.verify_mode,context.check_hostname=ssl.CERT_REQUIRED,True
        context.wrap_socket.side_effect=ssl.SSLError('PRIVATE_TLS_SENTINEL')
        with patch('https_driver.require_live_ready'), \
             patch('https_driver.ssl.create_default_context',return_value=context), \
             patch('https_driver.resolve',return_value=(2,1,6,'',('192.0.2.1',443))), \
             patch('https_driver.socket.socket',return_value=raw), self.assertRaisesRegex(ReviewError,'HTTP_RESULT_UNKNOWN'):
            connection(Deadline())
        raw.connect.assert_called_once(); raw.close.assert_called_once()

    def test_dns_wait_has_total_deadline_and_uses_only_first_address(self):
        addresses=[(2,1,6,'',('192.0.2.1',443)),(2,1,6,'',('192.0.2.2',443))]
        with patch('socket.getaddrinfo',return_value=addresses) as dns:
            self.assertEqual(resolve(Deadline()),addresses[0])
        dns.assert_called_once_with(HOST,443,type=1)
        with patch('https_driver.threading.Thread') as thread, \
             patch('https_driver.queue.Queue') as queue:
            import queue as q
            queue.return_value.get.side_effect=q.Empty()
            with self.assertRaisesRegex(ReviewError,'HTTP_DEADLINE'): resolve(Deadline())
        self.assertTrue(thread.call_args.kwargs['daemon'])
        self.assertLessEqual(queue.return_value.get.call_args.kwargs['timeout'],120)

    def test_redirect_rate_limit_and_errors_do_not_read_body(self):
        for status in (301,302,303,307,308,401,429,500,201):
            reply,response,client,_=self.send(ResponseStub(status=status,data=b'ERROR_BODY_SENTINEL',
                headers=[('Location','https://invalid.invalid')]))
            self.assertEqual(reply.code,'HTTP_STATUS'); self.assertEqual(response.reads,0)
            client.request.assert_called_once(); self.assertIsNone(reply.envelope)

    def test_media_encoding_conflicting_framing_and_size_fail_closed(self):
        for headers in ([('Content-Type','text/html')],
                        [('Content-Type','application/json'),('Content-Encoding','gzip')],
                        [('Content-Type','application/json'),('Content-Type','application/json')],
                        [('Content-Type','application/json'),('Content-Length','1048577')],
                        [('Content-Type','application/json'),('Content-Length','-1')],
                        [('Content-Type','application/json'),('Content-Length','1'),('Content-Length','1')],
                        [('Content-Type','application/json'),('Content-Length','1'),('Transfer-Encoding','chunked')],
                        [('Content-Type','application/json'),('Transfer-Encoding','gzip')]):
            reply,response,client,_=self.send(ResponseStub(headers=headers))
            self.assertNotEqual(reply.code,'NONE'); self.assertEqual(response.reads,0)
        reply,_,_,_=self.send(ResponseStub(data=b'x'*(POLICY['maxResponseBytes']+1)))
        self.assertEqual(reply.code,'HTTP_RESPONSE_SIZE'); self.assertIsNone(reply.envelope)

    def test_length_short_read_and_total_timeout_do_not_retry(self):
        reply,_,_,_=self.send(ResponseStub(data=b'x',headers=[('Content-Type','application/json'),('Content-Length','2')]))
        self.assertEqual(reply.code,'HTTP_PROTOCOL')
        for failure in (TimeoutError('PRIVATE_TIMEOUT'),KeyboardInterrupt(),OSError('PRIVATE_ERROR')):
            client=Mock(); client.request.side_effect=failure
            with patch('https_driver.connection',return_value=client):
                reply=HttpsDriver()._exchange_once(self.request(),'SYNTHETIC_NON_CREDENTIAL')
            self.assertEqual(reply.code,'HTTP_RESULT_UNKNOWN'); client.request.assert_called_once()
            client.close.assert_called_once(); self.assertIsNone(reply.envelope)

    def test_deadline_includes_write_headers_body_and_eof_and_request_stays_bound(self):
        for stage in ('write','headers','body','eof','mutation'):
            now=[0.0]; response=ResponseStub()
            client=Mock(); client.getresponse.return_value=response
            request=self.request()
            if stage=='write': client.request.side_effect=lambda *a,**k:now.__setitem__(0,120)
            if stage=='headers':
                def headers(): now[0]=120; return response
                client.getresponse.side_effect=headers
            original=response.read1
            def read(count):
                data=original(count)
                if stage=='body' or (stage=='eof' and not data): now[0]=120
                if stage=='mutation': object.__setattr__(request,'payload_sha','0'*64)
                return data
            response.read1=read
            with patch('https_driver.time.monotonic',side_effect=lambda:now[0]), \
                 patch('https_driver.connection',return_value=client):
                reply=HttpsDriver()._exchange_once(request,'SYNTHETIC_NON_CREDENTIAL')
            self.assertEqual(reply.code,'HTTP_PAIRING' if stage=='mutation' else 'HTTP_DEADLINE')
            self.assertIsNone(reply.envelope); client.request.assert_called_once(); client.close.assert_called_once()

    def test_real_header_parser_trickle_uses_absolute_deadline(self):
        now=[0.0]
        def tick(): now[0]+=31
        wire=WireStub(b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n',tick)
        # One byte per read demonstrates that socket inactivity limits alone fail.
        original=wire.recv_into
        wire.recv_into=lambda buffer: original(memoryview(buffer)[:1])
        with patch('https_driver.time.monotonic',side_effect=lambda:now[0]):
            response=http.client.HTTPResponse(DeadlineSocket(wire,Deadline()))
            with self.assertRaisesRegex(ReviewError,'HTTP_DEADLINE'): response.begin()
        self.assertEqual(wire.timeouts,[120,89,58,27])
        response.close()

    def test_real_chunk_parser_and_wire_bytes_include_headers_and_framing(self):
        wire=WireStub(b'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: application/json\r\n\r\n2\r\n{}\r\n0\r\n\r\n')
        response=http.client.HTTPResponse(DeadlineSocket(wire,Deadline()))
        response.begin(); self.assertEqual(response.read1(4096),b'{}'); self.assertEqual(response.read1(4096),b'')
        response.close()
        reader=DeadlineReader(WireStub(b'x'*(POLICY['maxResponseBytes']+1)),Deadline())
        with self.assertRaisesRegex(ReviewError,'HTTP_RESPONSE_SIZE'):
            reader.readinto(bytearray(POLICY['maxResponseBytes']+1))

    def test_request_drift_mutation_and_header_injection_before_connection(self):
        for request in (replace(self.request(),body=b'PRIVATE_REQUEST_SENTINEL'),
                        replace(self.request(),scope='0'*64),replace(self.request(),question=True)):
            reply,_,client,connect=self.send(request=request)
            self.assertEqual(reply.code,'HTTP_PAIRING'); connect.assert_not_called(); client.request.assert_not_called()
        with patch('https_driver.connection') as connect:
            reply=HttpsDriver()._exchange_once(self.request(),'SYNTHETIC\r\nInjected: yes')
        self.assertEqual(reply.code,'HTTP_CREDENTIAL'); connect.assert_not_called()

    def test_no_body_key_or_exception_output(self):
        out,err=io.StringIO(),io.StringIO()
        with contextlib.redirect_stdout(out),contextlib.redirect_stderr(err), \
             patch('https_driver.connection',side_effect=RuntimeError('PRIVATE_EXCEPTION_SENTINEL')):
            reply=HttpsDriver()._exchange_once(self.request(),'SYNTHETIC_NON_CREDENTIAL')
        self.assertEqual(out.getvalue()+err.getvalue(),''); self.assertNotIn('PRIVATE',repr(reply))

    def test_private_wire_also_cannot_construct_real_connection(self):
        with patch('socket.socket') as sock,patch('socket.getaddrinfo') as dns, \
             patch('ssl.create_default_context') as tls:
            reply=HttpsDriver()._exchange_once(self.request(),'SYNTHETIC_NON_CREDENTIAL')
            with self.assertRaisesRegex(ReviewError,'LIVE_NOT_AUTHORIZED'): connection(Deadline())
        self.assertNotEqual(reply.code,'NONE'); self.assertIsNone(reply.envelope)
        for mock in (sock,dns,tls): mock.assert_not_called()


if __name__=='__main__': unittest.main()
