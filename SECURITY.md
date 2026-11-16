# Security

## Reporting a problem

Please report a security or privacy problem privately through GitHub's "Report a vulnerability" form of this repository (Security tab),
not in a public issue. Include the Taen version, the phone and Android version, the steps to reproduce and what you expected.

You should get an answer within two weeks. A fix is released as a new signed version with a note in the release text; you are credited
unless you ask not to be.

## Scope

In scope: anything that breaks a rule in `INVARIANTS.md` or a statement in `PRIVACY.md`, for example typed text reaching a log, a file
or the network, user data stored in plain text, a password field being learned, the app lock or the self-destruct not doing what is
described, or a downloaded file being used without its pinned hash.

Out of scope: what `PRIVACY.md` lists under "What a keyboard cannot protect against" (the target app, accessibility services, root,
adb, the phone maker's own clipboard or content capture), and builds that were changed or signed by someone else.

## Verifying a release

Build the same commit with `make verify-repro` or `tools/build-linux.sh` and compare the SHA-256 of the unsigned APK with the published
`SHA256SUMS`; the signed APK differs only by its signature block (`BUILDING.md`, sections 5 and 6).
