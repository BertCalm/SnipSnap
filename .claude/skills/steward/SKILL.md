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

There is no `app/src/test` (nor `app/src/androidTest`), so **`:app` has no
Kotlin test source set** — say the path, because `app/src/main/cpp/test`
*does* exist and is a different thing entirely. Its Compose and ViewModel
code is proved only by a compiler — `android-build` in CI, or
`./gradlew :app:assembleDebug` on a machine with an SDK (`app/README.md` is
worth reading: that tree was written by a session that could not compile
it).

Its *native* half is a different story, and "no test source set" must not
be read as "nothing under `app/` is tested": `app/src/main/cpp/test` holds
host-run tests for the audio engines **and the JNI bridge**
(`jni_tests.cpp`), and those run anywhere, SDK or not.

## Before you push

Run what CI runs, not something adjacent to it:

```
./gradlew --no-daemon test                       # the JVM suites, no SDK present
./gradlew --no-daemon test -x :app:test          # ...with an SDK present (what CI runs)

cmake -S app/src/main/cpp/test -B build/native-tests \
  && cmake --build build/native-tests \
  && ctest --test-dir build/native-tests --output-on-failure
```

**Do not copy CI's gradle line into a session with no Android SDK.** It
fails in about thirty seconds with

```
Cannot locate excluded tasks that match ':app:test' as project 'app'
not found in root project 'snipsnap'.
```

which is a red build that says nothing about your change. `-x :app:test`
needs `:app` to be *in* the graph, and without an SDK
`settings.gradle.kts` leaves it out. Drop the `-x` there; the exclusion has
nothing to exclude.

Run the native block whenever the change touches anything under
`app/src/main/cpp` — the engine sources, the JNI bridge, or the test
tree's own `CMakeLists.txt` and stubs (`oboe_stubs.cpp`, `stub/jni.h`). A
build-configuration edit there breaks the suite as effectively as a DSP
one.

**But it does not cover `app/src/main/cpp/CMakeLists.txt`.** There are two
CMake files and they share nothing: the production one is what
`app/build.gradle.kts` points `externalNativeBuild` at, while the test one
is standalone and names the engine sources itself
(`${ENGINE_DIR}/SurfaceEngine.cpp`, `PadEngine.cpp`, `jni.cpp`). So a new
`.cpp` has to be added to *both* or the host suite silently stops covering
it, and a production-CMake edit can leave `native-tests` green while
`:app:assembleDebug` breaks. That one is proved only by `android-build`,
or by an SDK-backed `./gradlew :app:assembleDebug` — the local run is not
available in a cloud session, so read that job's log rather than trusting
green native tests.

This is the repo's recurring defect shape (one quantity in two places)
wearing a build system: the source list exists twice, and nothing checks
that the copies agree.

There is **no linter and no formatter** in this build — no ktlint, no
detekt, no spotless. Don't go looking for a `check` task that does more
than `test`; the one other root task is `dependencyCheckAggregate`, the
weekly CVE scan, which is not part of what gates a PR.

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
showing "cancelled" after you pushed again is expected.

**One comment in that workflow is stale — don't reason from it.** It says
the Android SDK is required "from the moment `:app` exists" because AGP
resolves it at configuration time, and that without it every module fails
to configure. That was true when it was written (`a0a8fb0`, 2026-08-24),
when `:app` was included unconditionally. The conditional include landed
five days later (`982f103`) and inverts it: with no SDK, `settings.gradle.kts`
simply leaves `:app` out and the nine JVM modules configure perfectly well
— which is what a cloud session does every day. What `setup-android` buys
`jvm-tests` is that `:app` *is* in the graph there, which is why that job
can exclude it. A no-SDK run is not a configuration failure.

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
