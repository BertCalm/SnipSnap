# Code review — October 2026

A whole-app review across cleanness, quality, security, expandability,
efficiency, sound quality and design thoughtfulness. Method: read of the
module graph and the security/audio boundaries, targeted scans, the JVM
suite as baseline, and three specialist passes (security, DSP, Android
architecture). Findings are split into **fixed in this change** and
**recorded** (real, but the right fix is a decision or a device session,
not a drive-by edit). Severity is honest, not theatrical: nothing found
approaches the import path's existing hardening for drama, because that
hardening is genuinely good.

## The short version

This is an unusually disciplined codebase. The untrusted-input surface —
ZIPs, `.xpn`s, MPC programs, `kit.json` — is defended in depth
(`SafePath`, `LimitedRead` budgets shared across a whole restore,
`SafeXml` with DOCTYPE off, staging folders with rollback, fuzz suites).
Pure-Kotlin modules carry ~5k tests; the native engines are lock-free,
allocation-free in the callback, and NaN-guarded at the JNI seam. The
issues below are mostly the kind a codebase earns by *growing*: one
model that predates a guard its younger sibling has, defaults set when
the app held less sensitive data, files that grew past what one reader
can hold, and a release configuration that has not yet been asked to be
one.

## Fixed in this change

### 1. A kit's name could walk an export out of its destination (security)

`Kit`'s only name rule was "not blank", yet the name is a folder/file
stem at every landing site: `KitExporter` (`File(destRoot, kit.name)`),
`Mpc3Exporter` (`.xtd`, track-data dir), `ExportFormats` (`.xpn`, `.xpj`),
`PackBuilder`, `SfzWriter`, `DecentSamplerWriter`, `KitBackup`. A
`kit.json` from a shared folder naming itself `../../…` would steer
those writes outside the chosen destination — overwriting whatever the
process may write on the card. `KitPad.sampleFile` and `KitLayer`
already refuse separators, and the younger `Project` model already
holds its name to `SafePath.isSafe` (`Project.kt:45`); `Kit` simply
predates the rule. It holds it now, at construction, so a hostile
sidecar fails to load rather than failing at one of a dozen call sites.
Ordinary names — spaces, unicode, dots — are untouched.

### 2. Recordings rode along in cloud backup (security / privacy)

`android:allowBackup="true"` stood alone, with no rules. The shelf is
`filesDir/Kits` and raw captures live in `filesDir/snips`, so Auto
Backup and device-to-device transfer silently carried mic and
other-app recordings (Auto Backup's cap makes it a *partial* copy of a
shelf, which is worse than none: it looks like insurance and isn't).
The app already has a designed exit — `KitBackup`'s one explicit file
through the share chooser, "the new phone story". New
`res/xml/backup_rules.xml` (pre-12) and `res/xml/data_extraction_rules.xml`
(12+) exclude `Kits/`, `snips/` and `sessions/` from both cloud backup
and device transfer; small state still backs up.

## Recorded — security & platform

- **`LoopActivity` is `exported="true"` with no permission.** Any app
  can launch the loop grid UI. Impact is small (it draws a session or
  says there is none; it shares nothing), and the manifest comment
  keeps it exported deliberately for `adb` during development. On a
  release build this should flip to `false`; the dev path survives via
  a debug manifest override. Not flipped here because `:app` cannot be
  assembled in this session and the comment records an active workflow.
- **No release configuration yet.** `isMinifyEnabled = false`, no
  signing config, `versionCode = 1` / `versionName = "0.1"`. Fine for
  CI debug APKs — which is all that exists — but a store or sideload
  release needs R8 rules proven against the JNI entry points and the
  hand-rolled JSON (both are reflection-light, so this should be
  mostly verification), plus version discipline. Recorded, not started:
  enabling minification blind, without a device run, is how a sampler
  ships silently broken.
- **`SYSTEM_ALERT_WINDOW` and broad share-sheet MIME intake**
  (`application/octet-stream`, `gzip`) are wide doors, but both are
  mitigated where it matters: overlay is optional and never required,
  and `ShelfImport.sniff` decides by bytes, never by label. No change.
- Everything else probed held: `FileProvider` is non-exported with two
  narrow paths, services are non-exported or permission-bound, the
  share-cache write checks canonical containment, XML is DOCTYPE-off,
  JSON caps depth at 64, WAV/zip readers carry byte budgets.

## Recorded — sound quality

- **`PadEngine` ends in a hard clamp** (`PadEngine.cpp`, end of
  `render()`). Thirty-two voices sum past full scale exactly when a kit
  is played hardest, and a clamp turns that into flat-topped hash on
  the phone's speaker. `SurfaceEngine` already shows the house style —
  a `tanh` soft clip with make-up gain. A gentle soft clip (or a
  lookahead-free limiter) on the pad bus would cost a few cycles per
  sample and no latency. Not changed here: it alters the sound of every
  pad audition, the native suite asserts levels in places, and "how
  hot should the bus run" is a listening decision for the owner, not a
  review edit. The same linear-interpolation read that serves pads
  well at unity pitch aliases when a pad is pitched far up; a
  band-limited read is the known cure and the known cost.
- **`WavWriter` quantizes without dither.** At the default 24-bit this
  is below any room's floor and a non-issue. The 16-bit path truncates
  (rounds, but no TPDF), so a quiet tail written at 16-bit carries
  quantization distortion correlated with the signal — audible on a
  faded reverb tail through good headphones, invisible on a kick. One
  seeded TPDF addition in `toPcm16` closes it; deferred only because
  byte-stability tests pin current 16-bit output in places and the
  change deserves its own before/after listen.
- **Normalisation is sample-peak, not true-peak** (`Cleanup`, −0.3 dBFS
  target). Deliberate and documented; inter-sample peaks on the MPC's
  DAC can still tick over 0 dBFS on hot tonal material. A 4× oversampled
  true-peak read is available in-house (`Dsp` does this elsewhere) if a
  bench session ever hears it clip.
- The synth side is the opposite story: 4× oversampling with PolyBLEP
  in the newer engines, windowed-sinc resampling with honest bandwidth
  limits written down, DC removal, click fades, NaN scrubbing on the
  way into the WAV. The deliberate lo-fi (`Crunch`, `Eras`) is labelled
  as the sound, not confused with accident.

## Recorded — cleanness, expandability, efficiency

- **Four files past 3,000 lines** — `PadSheetScreen.kt` (4,149),
  `App.kt` (3,686), `SynthScreen.kt` (3,133), `GrooveScreen.kt` (3,073),
  with `Personality.kt` (2,728) behind them. The logic is *not* in the
  wrong layer — `:shell` holds the state machines and the screens bind
  them, which is exactly right — but `App.kt` in particular is a
  3,686-line composition root where navigation, session wiring and a
  dozen sheets meet; a reader cannot hold its blast radius. Splitting
  by place (the four-place shape already names the seams) is mechanical
  and should be its own change with the emulator suite watching.
- **No linter or formatter** (no detekt/ktlint/spotless; the steward
  skill says so too). The codebase compensates with convention tests —
  including one that greps itself — which is charming and fragile.
  Adopting detekt with the current style as baseline would freeze
  today's discipline instead of relying on every session re-deriving it.
- **Thirty-nine engines is the expandability stress test.** A new engine
  touches ~17 files (measured on `PITCHWHEEL`): engine, patch, presets,
  voice enum, picker, CLI, starter kits, user presets, audition
  generator, docs. The per-engine shape (Patch/Presets/render, saved
  model versions, classifier-as-harness) is consistent enough that this
  is breadth, not chaos — but a registry that let an engine declare
  itself once (voices, macros, presets, kit) would cut the wiring to
  the genuinely new parts. This is the largest single leverage point in
  the review, and the riskiest to retrofit while engines are still
  landing weekly; it wants a design pass, not an edit.
- **`:app` Kotlin has no host test set** (known, recorded in the steward
  skill): Compose is proven by compilation, the emulator suite, and
  `:shell`'s tests of the logic underneath. That remains the right
  trade until a piece of app-only logic grows teeth; `KitShelf` and
  `ShareInbox` are the two files to watch.
- **Efficiency is in good shape where it costs**: `PeaksPyramid` makes
  the tape canvas flat-cost, the capture ring is fixed-capacity, render
  loops preallocate, IO sits behind `Dispatchers.IO` with comments
  naming the rule, and offline synth renders may allocate freely because
  they are offline. The one standing cost is `RingBuffer.snapshot`
  copying up to ~21 MB per snip — correct and simple at a button-press
  cadence; not worth a zero-copy API until profiling says so.
- **Design thoughtfulness** reads well: refusals in words everywhere
  (unpitched, silent room, clipped return), copy centralised in
  `Personality` with laws that gate for real, the two-surface LCD rule
  as a unit test, accessibility labels threaded through custom-drawn
  controls (199 `semantics`/`contentDescription` sites) rather than
  bolted on. The open design debt is the recorded one in
  `DESIGN_GAP.md` — screens that grew past their boards — not new
  findings here.

## What was run

- Baseline JVM suite (`./gradlew test`, no SDK in this session, so
  `:app` is out of the graph by design): the modules that completed
  before the session's time ceiling — `:json :xpm :audio :kit :mpc3
  :loop :cli` — all green, 1,481 tests, 0 failures.
- `:kit:test` again after the `Kit` guard: green, including the new
  `KitNameTest` (hostile names refused, ordinary names untouched).
- `:app` was not assembled (no Android SDK reachable here, per
  `settings.gradle.kts`); the manifest/resource change is declarative,
  and CI's `android-build` is its proof.
