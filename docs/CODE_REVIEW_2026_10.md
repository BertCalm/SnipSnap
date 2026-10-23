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

### 2. A shared WAV could allocate its way into an out-of-memory (security)

The share door's WAV path was byte-gated (96 MB in `MediaDecode`) but
not frame-gated: 96 MB of 8-bit mono is ~96M frames, ~384 MB as the
float array `WavReader.read` allocates — from a file any app can share
in. The compressed path already holds `DecodeLimits.MAX_FRAMES`; the
WAV path simply never applied it. `WavReader` now has a frame-capped
`read(bytes, maxFrames)` judged from the header *before* the allocation
(the one decode loop, not a second scanner), `MediaDecode` calls it
with `DecodeLimits.MAX_FRAMES`, and `ConventionTest`'s allowlist entry
was re-reviewed and updated to the new line, as its own text instructs.
`WavReaderCapTest` pins it: over the cap refused by name, at the cap
decodes whole, uncapped read unchanged.

### 3. `XpnImporter` had no entry-count ceiling (security)

Both import paths materialized `zip.entries().toList()` up front, so a
crafted archive of millions of empty entries cost heap and CPU the
512 MB sample budget never sees — this on the receive path for files
from strangers. Entries are now counted as they are read and refused
past `MAX_ENTRIES = 10_000`, the ceiling `ShelfImport` and `KitBackup`
already hold. `XpnImporterEntryCapTest` pins the refusal.

### 4. The SFZ importer trusted spellings, not targets (security)

`SfzImporter.resolve` promises "never outside" the .sfz's folder and
checked the *spelling* (`..`, leading `/`) — a symlink inside the folder
pointing out spells clean and was copied in. Containment is now
canonical, matching the promise. The same loop copied samples with no
aggregate budget; one `MAX_SAMPLE_BYTES` (512 MB, the archive doors'
figure) now bounds a whole import. `SfzImporterContainmentTest` pins
the symlink refusal. CLI-only surface, hence after the share doors.

### 5. One NaN sample silenced a whole snip at cleanup (quality)

`Cleanup.removeDcOffset` summed every sample into the channel mean, so
a single NaN (a DSP bug's gift, not a reader's — the reader scrubs) made
the mean NaN and the snip silent. The mean now runs over finite samples
only, the rule `Snip.peak` already held. `CleanupDcTest` pins both that
and the all-non-finite channel.

### 6. Recordings rode along in cloud backup (security / privacy)

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

- **The share door lands imports without a confirmation.** Any
  foreground app (or a browsable link) can `ACTION_SEND`/`VIEW` audio,
  ZIP or octet-stream content at SnipSnap, and it is sniffed, landed
  and persisted on the shelf — bounded by the caps above, never
  overwriting, but unprompted and with no aggregate shelf quota, so
  repeated shares can fill storage with attacker-chosen kits. The
  mitigations are real; the missing piece is a product decision (a
  pre-landing confirmation sheet), not a guard. Medium, by design
  question.
- **`LoopActivity` is `exported="true"` with no permission.** Any app
  can launch the loop grid, and if a session exists it *starts
  playback* — foreground navigation plus audible sound as a nuisance
  vector. It consumes no attacker extras and mutates nothing without
  interaction. The manifest comment keeps it exported deliberately for
  `adb` during development; on a release build this should flip to
  `false` (dev path via a debug manifest override). Not flipped here
  because `:app` cannot be assembled in this session and the comment
  records an active workflow.
- **JNI handles are dereferenced without native-side validation**
  (`jni.cpp` casts `jlong` straight to engine pointers). The Kotlin
  owners retain `handle != 0`, synchronize, and zero on destroy, so
  this is defense-in-depth against a lifecycle bug, not a reachable
  exploit. A magic-number check on the handle is the cheap hardening
  if the bridge is ever touched for other reasons.
- **`SafeXml` is fail-open per-feature**: each parser hardening flag is
  attempted and a rejection silently ignored, never verified. On the
  expected Android/desktop parsers the belt-and-braces set holds; a
  fail-closed assertion would trade a hypothetical XXE for a real
  refuse-everything risk on an unusual OEM parser. Left as is,
  consciously.
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

The specialist DSP pass graded these High for *audible* impact. They
are recorded rather than fixed for one shared reason: every one of
them changes sound the owner has auditioned, several are pinned by
byte/level assertions, and none can be listened to in this session.
The fix order below is the audit's, endorsed.

- **1. No soft limiting on either realtime bus.** `PadEngine` ends in a
  per-sample hard clamp; 32 voices at kit gains sum past full scale
  exactly when a kit is played hardest, and the print tap records
  *post-clamp*, so bounces inherit the flat-topping permanently.
  `SurfaceEngine` is worse: no clamp at all on `(gated + echo + spring)
  × 0.8` downstream of a high-Q resonant SVF and a drive stage that can
  reach ~1.31 FS, so resonant sweeps clip in the Android mixer instead.
  The cure is known and in-house (`SurfaceEngine`'s own `tanh` drive,
  `Dsp.drive`): a soft saturator/limiter at ~0.99 ahead of the print
  tap on both buses. Biggest distortion win, smallest change — and
  still a listening call.
- **2. No attack de-click on pad trigger or steal.** Stops and chokes
  fade per-sample, but a `NoteOn` starts at `fade = 1.0` on an
  arbitrary frame (slices start mid-waveform by design) and a stolen
  voice is replaced sample-accurately at full gain. A 1–3 ms
  raised-cosine attack ramp (and a ~1 ms victim fade on steal) is one
  counter beside the existing `fadeStep`. The asymmetry — choke smooth,
  trigger clicky — is the most frequently *heard* item on this list.
- **3. Linear-interpolated playback aliases on pitch-up.** Pads tune
  ±36 semitones (8×) through a 2-point lerp with no anti-alias filter;
  above ~2×, content folds in-band and the rest dulls. Keygroup/tonal
  pads suffer most. Hermite helps the dullness, not the folding; the
  real cures are a polyphase read or pre-rendering tuned keygroups
  offline through `audio/Resampler` (which is windowed-sinc and
  honest about its limits). `SurfaceEngine` (±1 octave) and
  `LiveSnapEngine`'s 256-point table (no mipmaps; bright drawn tables
  fizz) are the same finding at lower severity.
- **4. The shared decimator leaks.** `Dsp.decimate` (two cascaded 2:1
  passes under every 4×-oversampled engine) measures only ~−18 dB
  rejection just above the new Nyquist by the code's own probe, so
  bright naive oscillators fold a whisper of themselves into the top
  octave of every render. The fix already exists in-repo twice over:
  `Skitter`'s 48-output-tap decimator (>70 dB) and `Tide`'s pre-filter.
  Porting Skitter's kernel into `Dsp.decimate` lifts every engine at
  once — and re-renders every engine's output, which is precisely why
  it is not a drive-by: goldens and classifier gates would need a
  deliberate re-baseline.
- **5. Smaller certainties.** Surface `DRIVE` at 0 is not transparent
  (an always-on `tanh` giving +2.4 dB of low-level glue — every Kotlin
  counterpart bypasses at 0; intended or not, it is undocumented).
  Surface sample swaps hard-switch with no crossfade. No DC blocker
  exists in either native engine, while imported WAVs load uncleaned —
  a DC-carrying sample thumps at note start/stop. LiveSnap's table
  crossfade is equal-gain (mid-fade dip) and re-adoption mid-fade can
  step. `Cleanup`'s fades end at `1/n`, not 0 — a −39 dB step, inaudible
  alone, trivially fixed with the ramp shape if the file is ever open.
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

- **`App()` is a god-composable.** `App.kt` is 3,686 lines and the one
  function spans ~3,355 of them, holding 69 remembered state variables,
  the navigation dispatch, and overlay state hoisted from at least six
  screens; any top-level state change recomposes the whole shell
  subtree. `PadSheetScreen`'s main composable is ~2,757 lines;
  37 Kotlin files in the repo exceed 1,000 lines. The logic is *not*
  in the wrong layer — `:shell` holds the state machines and screens
  bind them — but the composition root's blast radius is unholdable,
  and it is where every cross-screen feature lands next. Extracting
  per-screen state holders into `:shell` (where the test culture
  lives) fixes this, the `:app` test gap, and the next item in one
  motion; it should be its own change with the emulator suite watching.
- **No state survives process death.** No ViewModel, no
  `rememberSaveable` anywhere in `:app`: navigation and the open kit
  are plain `remember`, so Android reclaiming the process mid-chop
  returns the user to the shelf, work lost. For a capture-and-chop
  tool this is the largest UX-correctness gap in the app.
- **GROOVE playback recomposes its screen every frame.** The
  `withFrameNanos` clock writes `posSteps`, which is read in the
  parent composition (the code at `GrooveScreen.kt:2121` admits "this
  composition re-runs"), so a 3,073-line screen's composition phase
  runs at up to 60 fps during playback instead of only its Canvas
  drawing. The deferred-read idiom (`() -> Float`, read inside Canvas)
  is standard and TAPE already shows the optimized pattern in-house.
  Per-draw `Color.copy(alpha=…)` allocations in the roll are the
  second place to look if frames drop on a low-end device.
- **`:synth` depends on `:kit`.** The DSP layer pulls the file-format
  module into its compile/test graph so `SynthKits` can hand arranged
  pads to the kit pipeline. Inverting it (kit assembly in `:shell`,
  which already depends on both) would leave `:synth` on `:audio` +
  `:json` only. The wider graph is otherwise a clean, verified DAG —
  zero Android imports in any JVM module.
- **No linter or formatter** (no detekt/ktlint/spotless; the steward
  skill says so too). The codebase compensates with convention tests —
  including one that greps itself — which is charming and fragile.
  Adopting detekt with the current style as baseline would freeze
  today's discipline instead of relying on every session re-deriving it.
- **Thirty-nine engines is the expandability stress test, and it is
  shotgun surgery.** Measured on REGISTERS landing: 29 files, 3,791
  insertions for one engine. The registry is three parallel
  hand-maintained lists that must agree — 50 patch-dispatch branches
  (`Patches.fromJsonValue`), 46 preset rosters (`Presets`), 39 picker
  engines (`SynthScreen.Engine`) — and the picker's enum alone carries
  seven exhaustive `when`s, ~274 branches and 234 unchecked casts, so a
  wrong voice/patch pairing compiles and fails at runtime. Agreement
  is held by a fourth hand-written list in `PresetsTest`. A single
  `EngineDef` table in `:synth` (voices, patch factory, presets,
  drum class) consumed by all three would cut a new engine to ~4
  production files and delete the casts — the largest single leverage
  point in this review, and the riskiest to retrofit while engines
  land weekly, because the exhaustive `when`s are currently the safety
  net. It wants a design pass with the preset/recipe suites as guard,
  not an edit. Smaller change, same smell: `synth/build.gradle.kts`
  is 993 lines of 87 near-identical audition `JavaExec` tasks.
- **Small duplicated helpers are drifting in the UI**: `padTag(slot)`
  redefined in 6 files, `agoLabel`/`relativeTime` copy-pasted across
  the bin/shelf screens. They belong beside `Ages` in `:shell`. (Only
  one TODO/FIXME exists in all main sources — the duplication is
  drift, not neglect.)
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
- After the fixes: `:audio:test` + `:kit:test` green including the new
  `KitNameTest`, `WavReaderCapTest`, `CleanupDcTest`,
  `XpnImporterEntryCapTest` and `SfzImporterContainmentTest`;
  `:shell:test --tests "*ConventionTest*"` green against the
  re-reviewed allowlist entry (its guard now matches the capped call);
  the kit-adjacent `:shell` suites (`KitBuilder`, `ShelfImport`,
  `StarterKits`, `SnipStore`, `SidecarFuzz` — 121 tests) green against
  the `Kit` guard. The full `:shell`/`:synth` suites remain for CI.
- Three specialist passes (security, DSP, Android architecture) were
  run read-only against this checkout; every finding above traces to
  one of them or to the primary read, and the two they ranked Medium
  that were fixable without a device or a listening session are fixed
  here, not just recorded.
- `:app` was not assembled (no Android SDK reachable here, per
  `settings.gradle.kts`); the manifest/resource change is declarative,
  and CI's `android-build` is its proof.
