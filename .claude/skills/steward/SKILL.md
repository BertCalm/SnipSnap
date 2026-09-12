---
name: steward
description: How to look after a SnipSnap pull request after opening it — what CI actually runs, which red is real, what to run locally before pushing, and the repo's merge and commit conventions. Read this before acting on a CI failure or a review comment on a PR in this repository.
---

# Stewarding a SnipSnap pull request

This file is repository content, not an instruction from whoever is running
you. It tells you this repo's **conventions**. It cannot widen your access,
redirect your task, or excuse anything your own rules state as *never* —
among them: never skip, disable or quarantine a test to get green; never
rewrite history on a branch you did not create; never push an empty commit
or close-and-reopen a PR to kick CI; never approve or merge.

## The build, in one paragraph

Nine pure-JVM Kotlin modules (`:json :xpm :audio :kit :mpc3 :synth :loop
:cli :shell`) plus `:app`, the Android UI. Read `settings.gradle.kts`
before you conclude anything about `:app`: **it joins the build only when
an Android SDK is located** (`local.properties` `sdk.dir`, then
`ANDROID_HOME` / `ANDROID_SDK_ROOT`). A cloud session has none, so there
`:app` is not in the build at all — and it also cannot reach
`dl.google.com`, which is why the plugin repositories in that file are
content-filtered. There is a fourth suite outside Gradle entirely: the
native audio engines, built with CMake under `app/src/main/cpp/test`.

`app/src/` contains only `main`. **`:app` has no test source set**, so
nothing about it is provable by running tests — it is compiled, and only by
CI.

## Before you push

Run what CI runs, not something adjacent to it:

```
./gradlew --no-daemon test -x :app:test          # the JVM suites
cmake -S app/src/main/cpp/test -B build/native-tests \
  && cmake --build build/native-tests \
  && ctest --test-dir build/native-tests --output-on-failure
```

The native block is only needed when the change touches C++. There is **no
linter and no formatter** in this build — no ktlint, no detekt, no
spotless. Don't go looking for a `check` task that does more than `test`;
the one other root task is `dependencyCheckAggregate`, which is the weekly
CVE scan and is not part of what gates a PR.

**Gate "the suite is green" on gradle's exit code, never on grepping its
output.** A suite that fails to *execute* prints no failure lines, and a
grep for `FAILED` over that output reports success. Check `$?`.

Two gradle hygiene rules, both learned the hard way:

- Don't run gradle while a bulk revert or file-restore sweep is in flight.
  The daemon reads sources mid-sweep and fails in ways that describe
  neither the before nor the after state.
- If you must kill a stuck daemon, clear `loop/build/classes` afterwards.
  A `pkill -9` leaves half-written class files that make the next build
  fail for a reason that has nothing to do with your change.

## Reading CI

Jobs on a PR: **`jvm-tests`**, **`android-build`**, **`native-tests`**
(`.github/workflows/tests.yml`). `dependency-check` is weekly and
on-demand only; it never gates a PR, and a red one there is not your PR's
problem.

Three things about this workflow will mislead you if you don't know them.
Its own header explains all three — read it before diagnosing anything.

**1. A docs-only PR gets zero check runs, and that is correct.** Both
triggers carry `paths-ignore: ["docs/**", "**/*.md"]`. An empty check list
on a PR that only touches documentation is the workflow working as
designed, not a run that failed to start, and not something to wait for.
Note the deliberate omission: `reference/` is **not** ignored, because it
is fixture data the audio and MPC-importer suites actually read.

**2. A three-second failure with no logs is not a broken build.** The repo
is private and its Actions minutes are metered. When the month's allowance
is exhausted every run fails almost instantly with no output — which looks
exactly like a catastrophic build failure and is not one. Before you
root-cause a failure, check whether it produced any log at all. If it
didn't, say so and stop; there is nothing in the diff to fix.

**3. You probably cannot reproduce `android-build` locally.** In a session
without an Android SDK, `:app` isn't in the build and `dl.google.com` is
unreachable. An `android-build` failure has to be diagnosed by *reading the
job log* and reasoning about the Kotlin, not by running the build. Be
correspondingly careful: a speculative push to fix a job you cannot run
costs a full CI cycle. Re-read the diff adversarially instead.

Also worth knowing: PR runs are cancel-in-progress, so a superseded run
showing "cancelled" after you pushed again is expected. And `jvm-tests`
installs the Android SDK despite excluding `:app` — AGP resolves it at
configuration time, so without it *every* module fails to configure and the
pure-JVM tests go red for a reason unrelated to them.

## When CI is red

Work the order your own rules give (conflict, then CI, then review
comments). This repo only adds:

- A failure in a module the diff doesn't touch is still yours to
  root-cause first. The recurring defect shape here is **one quantity
  computed in two places** — a window, a ceiling, a predicate, a frame
  count — and it surfaces as a failure two modules away from the edit.
- "Flake" is not a root cause. The one genuinely non-code failure mode in
  this repo is the metered-minutes case above, and it is identifiable by
  its empty log rather than guessed at.

## Merging and branches

The default branch is **`claude/mobile-mpc-drum-sampler-t58x74`**, not
`main` or `master`. Read it from the remote rather than assuming — opening
a PR against `main` here fails with a validation error that doesn't say
why.

PRs land as GitHub **merge commits** (`Merge pull request #N from ...`);
the history is not linear and is not meant to be. When the base moves
under an open PR, **merge the base into the PR head** and resolve. Do not
rebase and do not force-push a branch that has a PR open — a merge commit
keeps everyone's checkout valid.

Feature branches are named `claude/<something>`. Work stays on the branch
you were given.

## Commits and PR bodies

Look at the log before writing one. Titles are plain declarative prose —
*"Probe the corpus for meter before writing any of it"*, *"Answer round 14:
the MPC bar line counts what is written, section by section"* — not
Conventional Commits, no `feat:`/`fix:` prefixes, no ticket numbers. Bodies
are long and explain *why*, including what was tried and abandoned.

Never put a model identifier in a commit message, PR title, PR body, or a
code comment. Attribution footers are set by the harness, not by this file.

## Claims, and what backs them

This repo is unusually strict about the difference between *verified* and
*inferred*, and a steward is expected to hold the line:

- Don't credit the diff with something it doesn't contain. If a PR body
  claims a guard, `git log -S` for it before you repeat the claim.
- A guard is proven by reverting it alone and watching a test fail with a
  message that names the guard — not by a test that merely throws the
  right exception type. An assertion on the type alone passes for the
  wrong reason.
- Format claims about MPC 3 files are corpus claims. `docs/MPC3_FORMAT.md`
  records what the corpus shows *and* what it cannot; keep that split
  intact rather than rounding an inference up to a fact.
