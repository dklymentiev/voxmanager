# Security policy

Vox Manager types into whatever window is focused on your computer, and it accepts
that text over the network. A flaw here is not cosmetic, so please report one
privately before discussing it in public.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting on this repository:

**Security** tab -> **Report a vulnerability**

That opens a private thread visible only to the maintainers. There is no mailing
address on purpose: the private report keeps the details out of public issues until
a fix exists.

Please do not open a public issue, a pull request or a discussion for a security
problem first.

Useful things to include, as much as you have:

- what an attacker gains, in one sentence
- the version (`VoxManager-Server.exe --version`, or the app's build number)
- your operating system and whether the phone and PC were on the same network
- the smallest sequence of steps that reproduces it

## What to expect

This is a small project with a single maintainer, so treat these as intentions and
not as a contractual SLA:

- an acknowledgement within a few days
- an assessment of whether it reproduces, with reasoning, not just a verdict
- a fix in a release, and credit in `CHANGELOG.md` unless you ask otherwise

There is no bug bounty. Nothing is paid for a report.

## Scope

The threat model, what the pairing handshake proves and what it deliberately does
not, is written up in [`docs/SECURITY.md`](docs/SECURITY.md). Read it first: some
properties are missing by design rather than by oversight, and that document says
which.

In scope, roughly:

- anything that lets an unpaired device get text typed into a paired computer
- recovering the shared secret, or the dictated text, from the network
- replaying, forging or tampering with a signed request
- the pairing code flow: brute force, reuse of a consumed code, lockout bypass
- the update path: getting the server to install something that is not the
  published build

Out of scope:

- an attacker who already has an interactive session on the paired computer, or
  who can read files under the user's profile. Such an attacker owns the machine
  regardless of this software.
- the phone's speech recognition sending audio to the operating system's own
  recognizer. That is Android's behaviour and it is described in the privacy
  policy, not a flaw in this project.
- reports produced only by an automated scanner, with no working reproduction.

## Supported versions

The project is pre-release. Only the latest published version is supported; there
are no backported fixes for older ones.
