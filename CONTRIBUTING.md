# Contributing

## Setup

Enable the repository's git hooks once per clone (python3 is required):

    scripts/setup-hooks

This runs `git config core.hooksPath githooks` (`make hooks` does the same). The hooks then block a commit, a
commit message or a push that fails `scripts/public-check`. Before a push the hook also runs
`tools/pre-push-checks.sh`: it refuses key files, runs the buildSrc, engine and unit tests with `privacyCheck`, and
builds the pushed commit twice to check that the APK is reproducible (`BUILDING.md`, sections 5 and 9).

## Changes to the code

- Read `INVARIANTS.md` first. A change that touches a rule (permissions, backup, storage, clipboard, logging,
  self-destruct, native code) needs a test that fails without the change, and `make check` must stay green.
- When you change a check (`privacyCheck`, SourceRules, the APK scan, the leak audit), plant the violation it is
  meant to catch, see the check fail, then keep the planted case as a test.
- New user data goes through `Privacy.kt` (encrypted stores). New dependencies need
  `./gradlew --write-verification-metadata sha256` and a reviewed diff of `gradle/verification-metadata.xml`.
- No network code, no analytics, no logging of user text. Log calls take one tag and one fixed string.
- Keep licence, copyright, SPDX and "modified" lines of every file. Files derived from AOSP keep their Apache-2.0
  header and get a "modified" line when changed.
- Write tests first where you can; tests run without network, phone or clock dependence.

Checks before a pull request:

    make check          # privacyCheck, buildSrc, engine and unit tests, release build
    make native-test    # when app/src/main/jni changes
    make leak-audit     # when the APK contents change

## Public check

`scripts/public-check` is a deterministic check with no network access. It scans the files and file names
of the staged tree, commit messages, author fields and the history that is about to be pushed. It reports
`file:line: [class] text` and exits non-zero on a finding. It rejects:

- task IDs, names of development tools and assistants, and wording that marks text as machine-written;
- agent instruction files and their directories, session links, internal paths;
- internal process wording and role names;
- commit trailers that name a co-author tool or a generation session;
- a removed or changed licence, copyright, SPDX or "modified" line, compared with the recorded baseline
  in `scripts/public-check.licences`.

The patterns are in `scripts/public-check.patterns`. A legitimate product word goes into
`scripts/public-check.allow` together with a reason. After importing third-party files, record their licence
lines once with `scripts/public-check --licence-update`; the baseline only grows, and a shrinking baseline
is reported in the history check.

Run the tests with:

    python3 -I -m unittest discover -s scripts/tests

Modes:

    scripts/public-check                       # the staged tree
    scripts/public-check --tree origin/main    # all files of a revision
    scripts/public-check --history origin/main # every commit of a history or range
    scripts/public-check --text FILE           # pull request or issue text
