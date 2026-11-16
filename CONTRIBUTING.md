# Contributing

## Setup

Enable the repository's git hooks once per clone (python3 is required):

    scripts/setup-hooks

This runs `git config core.hooksPath githooks`. The hooks then block a commit, a commit message or a push
that fails `scripts/public-check`.

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
