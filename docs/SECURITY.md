# Security model (v2, mutual authentication)

Anyone else on the same Wi-Fi must **not** be able to type into your PC, and the
phone must **never** type into a stranger's / old / rogue server. Both directions
are authenticated.

## The shared secret

- The PC holds a high-entropy **256-bit secret** in `~/.voxmanager/config.json`.
- It is handed to a phone exactly once, during pairing.

## Pairing (6-digit code, no QR, no camera)

1. The PC shows a **6-digit code** (tray pairing window, or `--pair` / `--console`).
2. The phone generates an ephemeral **X25519** keypair and sends
   `POST /pair {code, eph_pub}`.
3. On the right code the server does X25519 **ECDH** against `eph_pub`, derives an
   AES-256-GCM key via **HKDF-SHA256** (the code mixed into the HKDF info), and
   returns the secret **sealed** under it (`{server_pub, nonce, ct}`). The phone
   opens it with its private key. The secret is therefore **never transmitted in the
   clear**: a passive Wi-Fi sniffer sees only public keys and ciphertext.
4. Hardening: the code is single-use, expires (2 min in the tray, 10 min in
   console), and is locked out after 5 wrong attempts. The pairing window
   auto-closes once a phone pairs.

Active-MITM note: the 6-digit code still travels in the request, so a full active
on-path attacker at pairing time is out of scope here; closing that needs a true
PAKE (SPAKE2). The dominant LAN threat, a passive sniffer, is fully covered.

## Per-request signing

Every authed request carries `X-Ts`, `X-Nonce`, `X-Sig` where

```
sig = HMAC-SHA256(secret, "method\npath\nts\nnonce\nbody")
```

The server rejects (401) anything with a bad signature, a stale timestamp, or a
replayed nonce. To keep the timestamp window **tight** (2 min) without rejecting a
phone whose clock drifts, the phone first calls the unsigned `GET /time` beacon,
learns the server-clock offset, and stamps every signed request in *server* time.
A captured request is therefore replayable for at most ~2 min, even across a server
restart (which clears the in-memory nonce set).

## Signed replies (the key win over v1)

The server also signs its response:

```
resp_sig = HMAC-SHA256(secret, "resp\nts\nnonce\nbody")
```

The phone verifies this before trusting the connection, so it will not type into a
server that doesn't hold the secret, even if something answers on the right port.

## Where the text goes (typing target)

By design the server types into **whatever window has focus** on the PC, via
`pynput`. There is no per-app allow-list: the dictation start/stop control on the
phone is the arm/disarm. To make this explicit rather than blind, the signed status
(`GET /`) reports `active_window` (the focused window's title), and the phone shows
"→ <window>" when dictation starts, refreshed at that moment so it is accurate.
Advice: don't dictate into password fields. The OS already blocks synthetic
keystrokes to elevated / secure-desktop windows (UAC, lock screen).

## What an unpaired peer can reach

Everyone on the Wi-Fi can open the port, and three routes answer without a
signature, by necessity:

- `GET /time` returns the server clock and nothing else (the phone needs the offset
  before it can stamp a signed request).
- `POST /request-pair` pops the pairing window. It cannot leak anything, the secret
  is still gated behind the code, and it is rate-limited so a peer cannot spam the
  screen.
- `POST /pair` is authorised by the 6-digit code (single use, expiring, locked out
  after 5 wrong tries).

Everything else is refused with `401` before it can act. The request body is read
before its signature can be checked, so that read is bounded: connections are
handled one thread each with an idle timeout, a body over 1 MiB is refused from the
`Content-Length` header without reading it, and a malformed length is a `400`. This
is what stops an unpaired peer from wedging the server instead of breaking into it.

## What's covered by tests

- `tests/server/test_security.py`: the core: HMAC signing format, nonce replay
  rejection, the pairing-code state machine (one-time use, wrong-code handling,
  expiry, lockout), and the v3 key exchange (round trip, wrong code cannot open, the
  secret never appears in cleartext).
- `tests/server/test_http_limits.py`: the front door above, against a real server on
  a loopback port, including a stalled peer that must not block anyone else.

## Known limitations / future work

- Replay window relies on per-nonce uniqueness tracked in memory (reset on restart),
  now bounded by the tight 2-min timestamp window so a restart exposes at most that.
- Pairing now encrypts the secret (X25519 + HKDF + AES-GCM), so a passive sniffer
  can't recover it. The remaining gap is an **active** on-path MITM at pairing time
  (the code is still sent); closing that needs a true PAKE (SPAKE2). See
  `docs/ROADMAP.md`.
- Post-pairing payloads are **encrypted** (AES-256-GCM, key = HKDF-SHA256 of the
  shared secret) in addition to being HMAC-signed, so a passive sniffer can no longer
  read the dictated text. Transport is still plain HTTP at the socket level (no TLS),
  but the body itself carries no cleartext.
- `active_window` awareness is point-in-time (refreshed when dictation starts); it
  is an awareness cue, not an enforced guard.
