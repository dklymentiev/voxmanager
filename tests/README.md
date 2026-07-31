# Tests

Pure-Python, no extra dependencies.

```bash
# from the repo root
python -m unittest discover -s tests        # all tests
python tests/server/test_security.py        # just the server security core
```

- `server/test_security.py`: locks down the security core (HMAC signing, nonce
  replay protection, the 6-digit pairing-code state machine, the pairing key
  exchange, and the typing paths). See [`../docs/SECURITY.md`](../docs/SECURITY.md).
- `server/test_http_limits.py`: what the HTTP front door accepts from an
  unauthenticated peer: body cap, malformed length, and the regression test for a
  stalled connection wedging the server. It boots a real server on a loopback port.

No test ever sends a valid signature, so running the suite cannot reach the typing
path and will not type into the machine it runs on.
