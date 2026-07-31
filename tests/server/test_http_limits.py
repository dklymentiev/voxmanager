# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Dmytro Klymentiev
"""Tests for what the HTTP front door accepts from an UNAUTHENTICATED peer.

Everyone on the same Wi-Fi can reach this port, and the request body has to be read
before the signature over it can be checked. So the reading itself must be bounded,
and one stalled peer must not stop the server answering everybody else.

None of these tests send a valid signature, so none of them can reach the typing
path -- running the suite never types into the machine it runs on.

Run:  python -m unittest discover -s tests   (from the repo root)
"""

import os
import socket
import sys
import threading
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "server"))
import voxmanager_server as v

SECRET = "ab" * 32


class HttpTestCase(unittest.TestCase):
    """Boots a real server on an ephemeral loopback port for each test."""

    def setUp(self):
        self.server = v.ExclusiveHTTPServer(("127.0.0.1", 0), v.make_handler(SECRET))
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def _connect(self, timeout=5):
        s = socket.create_connection(("127.0.0.1", self.port), timeout=timeout)
        self.addCleanup(s.close)
        return s

    def _status(self, sock):
        """First line of the response -> status code, or None if the peer said nothing."""
        data = b""
        while b"\r\n" not in data:
            chunk = sock.recv(4096)
            if not chunk:
                return None
            data += chunk
        return int(data.split(b" ")[1])

    def _raw(self, request):
        sock = self._connect()
        sock.sendall(request)
        return self._status(sock)


class TestBodyLimits(HttpTestCase):

    def test_oversized_body_refused_without_reading_it(self):
        # The peer ANNOUNCES a huge body and sends none of it. The answer must come
        # back anyway: the cap is checked against the header, before the read.
        code = self._raw(b"POST / HTTP/1.0\r\nContent-Length: 999999999\r\n\r\n")
        self.assertEqual(code, 413)

    def test_large_but_allowed_body_is_read(self):
        # The cap rejects what is over it, not what is merely bigger than a phrase.
        body = b"x" * 4096
        code = self._raw(b"POST / HTTP/1.0\r\nContent-Length: %d\r\n\r\n%s"
                         % (len(body), body))
        self.assertEqual(code, 401)     # read fine, then rejected for a missing signature

    def test_malformed_content_length_is_a_400_not_a_crash(self):
        code = self._raw(b"POST / HTTP/1.0\r\nContent-Length: not-a-number\r\n\r\n")
        self.assertEqual(code, 400)

    def test_negative_content_length_refused(self):
        code = self._raw(b"POST / HTTP/1.0\r\nContent-Length: -1\r\n\r\n")
        self.assertEqual(code, 400)


class TestUnauthenticated(HttpTestCase):

    def test_post_without_signature_is_401(self):
        code = self._raw(b'POST / HTTP/1.0\r\nContent-Length: 13\r\n\r\n{"text":"hi"}')
        self.assertEqual(code, 401)

    def test_get_without_signature_is_401(self):
        self.assertEqual(self._raw(b"GET / HTTP/1.0\r\n\r\n"), 401)

    def test_time_beacon_is_open(self):
        # Deliberately unauthenticated: the phone needs the clock offset before it can
        # stamp a signed request. It reveals only the time.
        self.assertEqual(self._raw(b"GET /time HTTP/1.0\r\n\r\n"), 200)


class TestStalledPeer(HttpTestCase):

    def test_one_stalled_connection_does_not_block_the_server(self):
        """The regression test for the wedged-server bug: a peer that announces a body
        and then goes quiet used to hold the only thread, and dictation stopped."""
        stalled = self._connect()
        stalled.sendall(b"POST / HTTP/1.0\r\nContent-Length: 5000\r\n\r\npart")  # 4 of 5000

        # A second, well-behaved request must still be served while that one hangs.
        self.assertEqual(self._raw(b"GET /time HTTP/1.0\r\n\r\n"), 200)


if __name__ == "__main__":
    unittest.main(verbosity=2)
