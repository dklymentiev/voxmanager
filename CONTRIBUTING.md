# Contributing

Thanks for looking. This is a small project, so the short version is: open an issue
before writing anything large, and keep patches close to the style of the code
around them.

Found a security problem? Do not open an issue. See [`SECURITY.md`](SECURITY.md).

## Getting it running

Two halves, and you need both to test anything end to end: the phone app records
and recognises, the PC server types.

**PC server** (Python 3.10+):

```bash
cd server
pip install -r requirements.txt
python voxmanager_server.py --console   # console mode, prints a pairing code
```

**Android app** (JDK 17, Android SDK 35):

```bash
cd android
./gradlew assembleDebug
```

The debug build uses a `.dev` application id suffix, so it installs alongside a
release build on the same phone.

**Tests** (no extra dependencies):

```bash
python -m unittest discover -s tests
```

**Packaging** the standalone executable additionally needs
`server/requirements-build.txt`. See [`server/README.md`](server/README.md).

Architecture, and why the pieces are split the way they are:
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Before you send a pull request

- **Run the tests.** `packaging/release.ps1` refuses to build when they are red, so
  a failing test blocks releases, not just review.
- **Match the surrounding code.** Same naming, same comment density. This codebase
  comments *why* something is done, not what the next line does; please keep that.
- **One concern per pull request.** A rename plus a bug fix plus a new feature in one
  branch is three reviews wearing one hat.
- **Describe the behaviour change**, not the diff. What could a user do before, what
  can they do now, and how did you check.

## Things that need discussion first

Not rejections, just work that is cheaper to agree on before you write it:

- **The wire protocol.** Signing, the pairing handshake, the HKDF salts, the
  discovery payload. Both halves ship independently, so any change here breaks
  phones that are already paired with servers that are already installed.
- **Cryptography.** Please do not send a change to the signing or key-exchange code
  as a drive-by. Open an issue and say what property you are trying to fix.
- **New runtime dependencies.** Every one of them lands in the frozen executable and
  in the installer size. There is usually a reason the code does something by hand.
- **New platform support.** Linux input is the known gap: `pynput` is X11-only, so
  keystroke injection does not work under Wayland. That needs a second input backend,
  not a patch.

## Reporting a bug

Include the version, your operating system, whether the phone and PC were on the
same network, and what you saw versus what you expected. For typing problems, the
application that had focus matters, so name it. Console mode
(`--console`) prints what the server received and what it did with it, which is
usually the fastest way to tell a recognition problem from a typing problem.

## Licence

The project is GPL-3.0. By opening a pull request you are offering your change under
GPL-3.0-or-later, and you also grant the maintainer the right to distribute your
contribution under other terms. That second half is what lets the project keep the
option of a commercially licensed edition later without having to track down every
contributor; it takes nothing away from you, since your change stays available to
everyone under the GPL regardless.

There is no contributor licence agreement to sign, and no copyright assignment: you
keep the copyright on what you write.
