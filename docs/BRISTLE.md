# Bristle, finite and held bass prototype

The gesture is blunt onset → buzzing low note → darkening body → tight stop.
The owner accepted both Python routes on 2026-11-14: “I like both versions.”
The following “Proceed” authorized the Kotlin prototype, playable notes and velocity response.
After the Kotlin audition delivery, “Sounds good, proceed” authorized app and instrument integration.
No separate verdict on each soft-touch note, phone playback or MPC playback is recorded.

## What changed

`Bristle` is a standalone study renderer in `:synth`, with two explicit routing choices and one
shared oscillator/envelope core. A drives the saw before the filter; B drives it after. The sine
remains outside both. Neither route uses samples from the reference recording. Hard touch keeps
R0's source parameters, shared-phase polarity, finite gate and baked slide. Soft touch changes
filter cutoff, drive and onset time as well as final level. Velocity zero renders silence.

Round 1 used a standalone versioned `Bristle.Recipe`. Round 2 registers `BristlePatch`,
the BRISTLE picker entry and the two accepted anchors, Filtered Edge (A) and Driven Edge (B).
The DSP stays unchanged. FATHOM and KEEL use different controls and envelope assumptions;
a separate entry keeps the accepted mappings intact. Two routing choices are not six distinct
musical voices, so this remains an in-progress instrument under the owner acceptance gates.

## Reproduce and play

```sh
./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.BristleTest --tests 'com.snipsnap.synth.DeterminismTest.Bristle*'
./gradlew --no-daemon :synth:generateBristleAudition
python3 prototype/bristle/kotlin_audition.py testkit/bristle-kotlin
```

Open `testkit/bristle-kotlin/listen.html`. The keyboard triggers actual JVM-rendered C1–C3 notes
at three touch levels, selected by the route/touch controls. It is a finite-note browser audition:
key-up does not release a held voice, and this is not SnipSnap or MPC keygroup playback evidence.
The A/B phrases repeat R0's original test phrase. Matched Python/Kotlin C2 comparisons separate
source preservation from output loudness. The page is self-contained with embedded WAVs.

Each native note has its complete JSON recipe and a WAV hash in `manifest.json`. The generator
reads back every saved recipe and verifies sample-exact regeneration. These are standalone
study recipes; `Patches.fromJsonText` does not accept them. Route, model, MIDI, touch and macros
are explicit. Unknown versions, notes outside MIDI 24–48 and invalid persisted controls are
refused. The interactive render convenience clamps known macros and defaults non-finite input.

## DSP and controls

The mappings are retained from `prototype/bristle/README.md`: TUNE, WEIGHT, GRIT, DARK, BITE,
SLIDE and finite HOLD. Explicit MIDI is the note input for `Recipe`; TUNE in the convenience
renderer maps C1–C3. No randomness or solver is needed. State and work scale with sample count.
There is no geometry/precompute cache. HOLD's top is a finite .72-second gate, not a loop.

Touch 1 preserves the accepted source. At lower touch v:

- cutoff multiplier = .35 + .65 v;
- drive multiplier = .65 + .35 v;
- saw-path level multiplier = .25 + .75 v;
- attack = .004 + .004 (1 − v) seconds;
- amplitude multiplier = v, applied after the house loudness stage.

These touch laws are engineering-tested shapes; no separate soft-touch listening verdict is recorded. Applying amplitude before leveling
would erase touch dynamics. Both oscillators follow the same baked pitch slide. The sine's
polarity follows the rising saw's fundamental; filter phase can still change their reinforcement.

The Kotlin renderer uses `Dsp.TptSvf`, `Tide.bandLimit`, `Dsp.decimate`, `Dsp.FixedOnePole`,
`Dsp.levelTo` and `Dsp.fadeTail`, verified on source commit
`23cde0d1c107039c23fd74ff5c3d2b359a7f6e75`. The raw source has Float filter-state differences from
Python. The output differs more: the prototype decimator is approximate, its DC blocker is a
different discretization and its level target is RMS rather than `Loudness.of`. Therefore raw
fixtures and matched output auditions serve different purposes; output byte identity is not claimed.

## Evidence and remaining gates

`BristleTest` checks accepted Python source fixtures, finite/bounded notes and parameter corners,
root periodicity, pitch above the detector's 40 Hz floor, soft-touch brightness and level,
zero-touch silence, saved-route replay and macro activity. `DeterminismTest` has a separate
saved-recipe canary for both routes. `kotlin_audition.py` also compares entire raw C2 buffers.
The R0 fixtures retain every sixteenth raw sample, independently of output conditioning.

Target latency remains tens of milliseconds per finite note on a phone. Generator timings are
warmed workstation timings only. This round does not change Android playback or add Oboe synthesis.

| Gate | Current scope |
| --- | --- |
| A/B source preservation | Python fixture and full-buffer comparisons; registered anchors retain the exact JVM render |
| Timbral velocity rendering | Native soft/medium/hard source renders; no distinct per-layer listening verdict |
| Recipe saving and replay | Registered Patch, user presets, drafts and pad recipes; standalone R1 study codec retained |
| Playable keygroups | Finite and opt-in held C1–C3, every semitone, three touch layers; software checks below |
| Picker | BRISTLE in BASS and SYNTH; Android tests added, SDK/device verification open |
| Sustained articulation | Opt-in SUSTAIN KEYS holds the settled tone and releases on note-off; finite source recipes remain unchanged |
| Six voices, premade kit | Open; two accepted routing anchors, no starter kit in this round |
| Chord tools | Polyphonic program; C-minor triad reference playback covered, full chord-tool range/device behavior open |
| Round robin | Open; no take pool or playback selection claimed |
| MPC export | Native MPC3 and legacy MPC2 packages; physical loading and playback open |
| STRUM, drum genre kits | Not applicable to this synthesized bass |

## Why it is built this way

- One shared core keeps the accepted routes comparable and avoids two implementations drifting.
- Independent clean fundamental support gives the grit controls room without forcing the low end through saturation.
- Saved routes and a model version make each audition note reproducible.
- Hard touch is the preservation anchor; new soft touch is judged separately.
- The remaining risk is listening equivalence after the real output stage, not just numerical source agreement.

## As built: round-1 measurements

On the working tree based on `23cde0d1c107039c23fd74ff5c3d2b359a7f6e75`, the targeted Gradle
run passed all six tests (five `BristleTest` methods and the `DeterminismTest` canary), then the
audition generator completed. A local Temurin 21.0.13 JDK was needed because the workspace's
initial Java installation provided a runtime without `javac`. Proxy/JDK configuration was
kept outside the repository.

- **Source preservation, Kotlin test:** the worst sparse-fixture sample difference across A/B
  and slides 0/.65 was 0.00000604 full scale. Hard-touch source parameters are unchanged.
- **Source preservation, full C2 buffers:** Python/JVM relative RMS error was −128.35 dB for A
  and −126.12 dB for B. This compares raw synthesis, not the output-stage audio files.
- **Velocity, Python probes over JVM WAVs:** soft/hard spectral-centroid ratios across C1–C3
  were .889–.929 for A and .845–.889 for B. RMS rose soft → medium → hard at every note for
  both routes. The original cutoff/drive-only touch map failed the 5% brightness-change test;
  reducing the saw-path level at soft touch fixed that while retaining hard-touch fixtures.
- **Recipes:** all 152 native note/slide recipes re-rendered sample-exactly after reading their
  saved JSON. Manifest WAV hashes and all mono 44.1 kHz PCM16 headers were validated.
- **Time:** warmed median 66.12 ms per one-second buffer, including the house output stage,
  measured by the JVM generator on this workstation. This is not a phone result.
- **Audition packaging:** the self-contained keyboard uses 150 native note/touch WAVs; the page
  also has two slide clips, two phrases and four matched Python/Kotlin source comparisons.
  A headless Chromium check verified audio-context startup, decoded note duration, route/touch
  selection, rapid retriggers, Stop and a 390-pixel layout without page errors. It ran over a local
  HTTP server because this workspace blocks file navigation. Audio was not heard by the agent;
  owner listening remains open. The player fades interrupted notes over 5 ms.

Machine-readable measurements are in `prototype/bristle/kotlin-report.json`; generated WAV
recipes/hashes are in the audition's `manifest.json`. The original R0 report remains historical
and says “not heard” because it was generated before the owner's acceptance.

Browser behavior can be rechecked with `node prototype/bristle/check-browser.cjs URL`, with
Playwright and Chromium installed. WAV output had a maximum 4×-interpolated sample peak of
0.8722 and maximum absolute mean of 0.0000301 (Python/SciPy probes over JVM PCM16 files).

### Repository-wide regression status

The round-1 `./gradlew --no-daemon test --fail-fast` run ended without a completion result.
Its log ends during an unrelated Origami test; it is not a passing regression gate.
Round-2 focused results are below. The subsequent `./gradlew --no-daemon test` run also
ended without a completion result. Its log, `/workspace/bristle-round2-regression.log`,
contains 2,999 passing results and no reported failure, but is not a complete regression pass.
Focused XML reports are preserved separately in each delivery bundle. No full-suite success is claimed.

## Round 2: saved patches and finite instruments

The SYNTH picker has A and B, all seven controls, the accepted factory anchors, saved user
presets and independent drafts. Editing a macro retains route, renderer model, authored MIDI
and velocity. The shared ROOT control edits the authored centre; TUNE shifts it by up to one
octave, clamped to C1–C3. This is deliberately separate from R1 `Recipe`, where explicit MIDI
remains absolute and TUNE is ignored. Existing study JSON and audio still replay unchanged.

MAKE INSTRUMENT renders 75 mono PCM24 samples per route: MIDI 24–48 at touch .35, .65 and 1.
Every key has its own source sample, so the accepted slide and envelope are never transposed.
The authored root retains its entire source recipe; other notes neutralize TUNE once. HOLD
still sets a .12–.72-second gate plus a 45 ms baked release, with no loop. The finite sampler
program also has a 45 ms note-off release and defaults to POLY.

Touch selects a recorded spectrum, then live key velocity sets amplitude. Prepared samples
remove only the sampled touch gain; they retain the authored source velocity. Reapplying the
nominal live touch reconstructs the accepted native note within PCM24 quantization. Velocity
windows are 1–63, 64–104 and 105–127 (the file's soft zone starts at 0); these boundaries are
between the three sampled touch values, so .35 selects soft rather than medium.

The package includes `.xty`, its `_[TrackData]` folder with the legacy `.xpm` and WAVs,
a phone instrument sidecar and source-recipe metadata. Validation binds every WAV and recipe
by SHA-256 and checks root notes, absent loops and all three descriptors. Fresh publication
uses the existing shelf transaction with rollback. No existing instrument is overwritten.

Reproduce:

```sh
./gradlew --no-daemon :synth:test --tests '*Bristle*' :shell:test --tests '*Bristle*'
./gradlew --no-daemon :shell:generateBristleInstruments
```

For MPC3, keep each `.xty` beside its complete `_[TrackData]` folder. For MPC2, open the
`.xpm` inside that folder alongside its WAVs. The folder names are part of the package.
This is software export evidence; no physical MPC load or audition is claimed.

### Round-2 validation

The focused Gradle run completed with exit code 0: **74 tests passed**, 13 in `:synth` and
61 in `:shell`. Fifteen are Bristle-specific (including the determinism canary); the rest cover
preset dispatch, engine catalog, draft handling and root controls. Both generated packages
then passed validation: 25 keygroups and 75 WAVs each.

- Registered anchors render sample-identically to R1 recipes; Python fixtures still pass.
- All 150 exported note/touch samples reconstruct their native source within 0.000001 full scale
  after nominal touch gain, including PCM24 quantization.
- Phone layer selection was checked at 1, 63, 64, 104, 105 and 127; MPC3 layers and MPC2
  descriptors retain the same windows, note roots and absent loops.
- User presets, drafts and pad recipes preserve the route, note, macros, velocity and model.
- Reference playback starts a C-minor triad with independent finite voices. This is not an
  unrestricted chord-tool sweep: the program is intentionally bounded to C1–C3.
- Cancellation, incomplete grids, changed WAVs, changed native descriptors and attempts to
  overwrite an existing instrument were exercised.

Android UI tests cover route selection, macro editing, engine navigation,
draft restoration and the finite instrument sheet; the workspace has no Android SDK, so these
are added for CI and have not compiled or run here. Sustained HOLD, distinct round-robin takes,
six musical voices, a premade kit and physical phone/MPC checks remain open.


## Round 3: opt-in sustain (2026-11-15)

The owner authorized continued development with “Proceed”. Round 3 adds sustained instruments;
the new articulation has not received a listening verdict. A/B finite sounds remain the preservation anchors.

In SYNTH, **FINITE KEYS / SUSTAIN KEYS** selects the instrument-building mode. The choice is
remembered separately from the saved finite patch. MAKE INSTRUMENT in sustain mode exposes a
10 ms–1.5 s RELEASE control, default 200 ms, and appends HELD to the suggested name. Its preview
plays the actual reference sampler for three seconds, then releases using that control. CANCEL
and MAKE stop the current preview. The saved finite note, factory anchors and old packages
are unaffected. The source HOLD macro sets the settling time; it is not silently reinterpreted
as an infinite gate in existing saved patches.

`BristleHeld` reuses the oscillator, filter, drive, touch and output-stage code. It retains the
opening pitch slide and filter bite, lets the body droop until the original finite gate time,
then holds that level. After one second of settling, it loops approximately half a second of
whole waveform periods. Quantizing many periods rather than one keeps the pitch adjustment
below 0.04 cents. A guard after the cut prevents decimator end padding from entering the loop;
the held path omits the finite tail fade. The loop is measured against independently rendered
preceding audio, not against a copied or crossfaded cycle. A failed seam is refused.

The source Patch remains model 1. Held instrument metadata uses schema 2, an explicit HELD
playback tag, held-renderer version 1 and loop positions; the schema-1 finite metadata is
unchanged. PCM24 WAV `smpl` loops, phone sidecar, native MPC3 and legacy MPC2 descriptors all
carry the same loop and release. Loading a held package as finite, changing its renderer or
moving its loop is rejected by package validation.

### Round-3 completed checks

- **165 focused JVM tests passed** (14 synth, 151 shell), including the original finite-source
  fixtures and a wider app-convention check. A subsequent **95-test shell run passed** after
  the preview was changed to demonstrate the chosen note-off release. These overlap; they
  are not 260 distinct tests.
- All 150 held note/touch cells closed. Worst seam error: **1.5304 × 10⁻¹³**, against the house
  threshold 10⁻³. Maximum planned tuning adjustment: **0.0388935 cents**. Independent pitch
  detection checked hard-touch notes above its 40 Hz floor within 15 cents.
- Soft held notes retain a lower spectral centroid and level than hard notes. Maximum slide,
  bite, grit and darkness at both HOLD extremes stay bounded and close; repeated rendering
  is deterministic. Zero strength and cancelled rendering are refused.
- Both held routes survive actual package export/readback. Reference playback remains active
  after four seconds and multiple wraps; note-off fades over the stored release and reaches
  silence. Three touch windows and prepared-sample gain remain intact. Invalid source mode,
  renderer and loop metadata are refused before shelf publication.
- All **158 files** in the two regenerated finite packages are **byte-identical** to round 2,
  including WAVs, descriptors and source manifests.
- Each complete held package has 25 keys and 75 samples. Rendering, writing and validating
  took **5.53–5.64 seconds per package** on this workstation. No phone timing is claimed.
- The wider convention checks found and fixed the catalogue order and app README picker
  count/list missed in round 2. The catalogue now follows the 86-entry picker exactly.
- `listen.html` contains eight 3.3-second examples from the exported samples through the JVM
  reference sampler, with matched playback gain: soft/medium/hard held notes and the original
  finite hard note for each route. A headless Chromium check verified decoding, playback,
  pause, a 390-pixel layout and no page errors. This is not agent listening or device evidence.

Generate and check the audition after `:shell:generateBristleInstruments`:

```sh
python3 prototype/bristle/sustain_audition.py testkit/bristle-instruments
node prototype/bristle/check-sustain.cjs http://localhost:8767/listen.html
```

The latter expects an HTTP server serving the generated folder and local Playwright/Chromium.
Android tests now cover the sustain choice and RELEASE sheet as well as finite draft retention,
but remain uncompiled/unrun here because no Android SDK is installed. Full repository regression,
phone timing, physical MPC playback, round robin, six musical voices and a starter kit remain open.

### Why the sustain is built this way

- A separate instrument choice preserves the finite sound already accepted.
- The shared core keeps the clean fundamental and both grit routes identical in design.
- Whole-period looping holds a stable bass note without repeating the opening pitch scoop.
- Release belongs to the sampler, so letting go ends the note at the chosen speed.
- Loop checks guard the join numerically; the new sustained character still needs the owner's listen.

## Round 4: optional round robin

ROUND ROBIN builds two deterministic takes at each of the three touch levels, for 150
samples across C1–C3. SINGLE TAKE remains the default. Both finite and sustained modes
support the option, stored separately from the source patch. The instrument-sheet preview
plays two attacks at each touch through the reference sampler's actual cycling cursor.

Take 0 preserves the accepted source. Take 1 starts both oscillators at phase 0.19, lengthens
attack by 12% and adds a 12% filter opening that decays with a 45 ms time constant. It does
not detune the note or apply random gain. The explicit take index survives patch JSON;
old patches omit it and keep their original serialization. Round-robin instrument manifests
use schema 3 and round-robin model 1, with the held-renderer version retained when applicable.

The saved phone instrument cycles independently within each velocity window. The current
MPC keygroup writer retains only the first take per window when cycling and velocity
switching are combined. The main MPC program therefore remains a three-touch first-take
program. Three companions under `TrackData/MPC Soft`, `MPC Medium`, and `MPC Hard` each
cycle both takes using a fixed source touch. Their names include the parent instrument name.
Their sample gain restores the chosen touch level; live velocity still controls volume.
These companions are an explicit alternative, not a pass for combined velocity × round robin
in one MPC keygroup. All descriptors, source bindings, PCM, roots and loops are checked
before fresh shelf publication, including companion files.

Reproduce the packages and listening page:

```sh
./gradlew --no-daemon :shell:generateBristleRoundRobin
python3 prototype/bristle/roundrobin_audition.py testkit/bristle-roundrobin
node prototype/bristle/check-roundrobin.cjs http://localhost:8768/listen.html
```

The page plays actual exported PCM in the sequence original, variation, original, variation
at each touch for both routes and articulations. Each note lasts half a second with a 300 ms
release window. These are reference-sampler renders, not recordings from Android or MPC.

### Why round robin is built this way

- Two takes preserve all three touch levels within the existing eight-layer limit.
- Shared oscillator phase variation changes the attack without detuning the bass fundamental.
- A fixed sequence makes the alternation reproducible after saving and loading.
- Keeping take 0 intact protects existing sounds and makes direct comparison possible.
- Separate MPC touch programs expose the writer's actual capability without hiding dropped takes.

The owner subsequently reported the current prototype tested and approved; see the acceptance
record below. Android UI automation remains unrun here without an SDK. Combined MPC velocity ×
round robin, six musical voices, a starter kit, full chord-tool verification and full repository
regression remain open.

### Round-4 completed checks

- **114 focused JVM checks passed**: 16 synth and 98 shell, including app convention checks.
  The new take passes all 150 route/note/touch combinations for finite bounds and held seams.
  Gain-matched waveform residuals reject a gain-only duplicate; replay is deterministic.
- Saved patches, presets, pad recipes and root edits retain the explicit take. Saved instrument
  readback selects 0, 1, 0, 1 independently per touch; actual reference playback reproduces
  those two attacks. Missing companion audio prevents shelf publication, and modified native
  companion descriptors fail package validation.
- Four complete packages validated, each with 150 main samples and three fixed-touch
  cycling companions. Generation plus initial validation took **11.06–13.56 seconds per package**
  on this workstation. Phone timings remain unmeasured.
- The listening page's twelve 3.2-second clips passed Chromium decode, playback and pause
  checks, a 390-pixel layout check and a no-page-errors check. This is not listening evidence.
- The full repository regression is **not green**: the earlier run stopped after 2,999 passed
  results without a completion/exit result. The focused checks above are the completed scope.
- All **316 files** in the four regenerated single-take packages are byte-identical to round 3,
  including both finite and held PCM, descriptors and source manifests. Reproduce with
  `prototype/bristle/compare_packages.py <round-3-folder> <regenerated-single-take-folder>`.


## Owner acceptance: current prototype

After the listening page was delivered and Android/MPC testing was recommended, the owner
reported: **“Tested and approved.”** This records owner-reported testing and acceptance of the
current finite, sustained and round-robin prototype. It supersedes the earlier pending-listen
status and pending owner device check. Device models, Android/MPC versions and individual
check results were not supplied; no specific measurements or automated device-test pass are
inferred. The 114 focused JVM checks remain the separately verified software evidence.

The accepted MPC behavior still uses separate fixed-touch cycling programs. Approval does
not implement combined timbral velocity switching and round robin in one MPC keygroup or
waive that gate. At that approval point, six distinct musical voices, a starter kit, full chord-tool
verification and completion of the full regression run remained outstanding; round 5 follows below. Existing delivered archives retain
their historical validation reports; this repository record captures the later owner approval.

## Round 5: six source voices and a starter

The characteristic gesture remains **opening bite → gritty body → darkening sustain → release**.
This is a voice-family extension of the accepted bass, not another engine. FATHOM already
covers oscillator/drive/filter bass and KEEL covers a bright attack settling into a low body;
Bristle keeps its protected fundamental, reproducible touch/take grid and accepted A/B chain.
Repository reference: `23cde0d1c107039c23fd74ff5c3d2b359a7f6e75` plus the local approved prototype.

Three implementation directions were considered: **SPLICE** six periodic source shapes into
the shared core (chosen: clear contrasts, bounded cost and compatible sustain); **STACK** six
post-effect recipes (less useful source diversity); **COUPLE** oscillators through feedback
(more evolving character, but a new stability and loop problem). No feedback solver is added.

A/B remain the exact accepted routing anchors. Six additional entries in the existing saved
voice enum are musical voices, subsequently retained by the owner. Their preset names and constants are authored
sound-design choices retained after the six-voice listening round; the earlier device approval
does not supply device-specific verification for these additions.

| Voice | Preset | Source mechanism | Intended role |
| --- | --- | --- | --- |
| ROUND | Round Floor | Sine plus a touch-dependent second harmonic, gently saturated before filtering | Rounded sub |
| PULSE | Pulse Bite | PolyBLEP pulse at 23% duty, saturated after filtering | Narrow, biting bass |
| HOLLOW | Hollow Box | PolyBLEP square with gentler post-filter saturation | Hollow odd-harmonic body |
| REED | Reed Throat | Saw plus third harmonic into asymmetric saturation and low/band-pass mix | Nasal midrange |
| WIRE | Wire Knock | Integer-ratio phase modulation with a decaying index and protected root | Metallic onset |
| FOLD | Fold Rasp | Smooth sine folding mixed with a protected fundamental | Folded rasp |

These are literal oscillator/filter/waveshaper equations, not simulations of reeds or wires.
Both pulse edges receive PolyBLEP correction. Integer-related phases preserve the held loop;
FM motion settles before the existing one-second loop start. All paths use the house 4× output
stage, and all keep the original clean fundamental in parallel. The shared seven macros retain
their existing meanings; GRIT also controls the folded source's depth. No new macro is added.

```
note + touch + take → shared phase → selected source → saturation/filter → edge
                         └────────→ clean fundamental ──────────────────┤
                                                   envelope → house output
```

The BRISTLE FRESH TAPE starter contains eight C-minor-pentatonic bass notes followed by the
six new voices and both approved anchors. Each of its sixteen pads has three touch levels
and two native takes. Touch gain is removed from stored samples and supplied once by live
velocity. Starter touch values (.25, .65, 1) sit inside the kit writer's evenly divided windows;
the keygroup's existing touch values/windows remain unchanged. Export/readback exposed a further existing limit: the general Slice Motion writer lacks the
embedded WAV slice map and the importer does not reconstruct its chain windows. Accordingly,
`BristleStarterExport` writes a portable MPC pack with three individual first-take velocity WAVs
per pad plus separate Soft/Medium/Hard `.xpm` programs, each cycling two real WAVs. The native
velocity program is tested through actual import; cycle descriptors and both WAVs are checked.
The portable exporter is used by the delivery generator. The app's general chain export remains
limited; neither that path nor the separate alternatives close the combined MPC cycling gate.

`BristleChordPlan` adapts the actual `ChordStabs` major/minor, modal and borrowed layouts by
choosing a single whole-octave transposition into C1–C3. It reports that shift, retains every
tone and refuses an unfit span or more than eight unique notes. Of the 960 generated layouts,
956 fit; four B-root add-nine voicings straddle both register edges at every whole-octave shift
and must be refused. Root edits neutralize TUNE
once, retaining source velocity and take. This is structured/offline chord-tool support;
it does not claim new live chord controls or independently tested native chord admission.

### Why it is built this way

- Changing the source creates useful musical contrasts while preserving the approved bass architecture.
- A parallel fundamental keeps the low note clear when upper harmonics become aggressive.
- Integer-related oscillators let the existing sustain planner hold each new voice without detuning.
- The phone starter preserves the full touch/take grid; portable MPC alternatives use separate WAVs because the generic chain export lacks its slice map.
- Chord fitting moves the whole harmony by octaves; clamping individual tones would change the chord.

The six new voices still require listening approval. The prior owner test/approval applies to
the delivered A/B prototype. Combined MPC keygroup velocity × round robin still requires validated Bristle selection and
cycling on MPC; drum-pad evidence alone does not establish that support.

### Round-5 delivery and verification

- Existing owner-private listening URL: <https://bristle-bass-listening.bertcalm.chatgpt.site>.
  The eighteen new finite/held/chord clips and portable MPC starter are published there;
  `approved.html` preserves the twelve A/B comparisons. Publication succeeded from Site source
  commit `ca663a820aa2dc76b9e7a322c202485002c2a84e` (separate from the SnipSnap checkout).
- Twelve new keygroup packages validate: six voices × finite/held, each with 25 roots,
  three timbral touches and two takes (150 main samples), plus three fixed-touch cycling
  companions. Render plus initial validation took **11.83–15.95 seconds per package** here.
  These are workstation measurements, not phone timings.
- All **316 files** in the four original single-take A/B finite/held packages remain byte-identical.
- The final focused export/chord/convention/shelf/diversity run passed **96 JVM tests**
  (93 shell, three synth). The full repository run is recorded separately below when complete.
- The eighteen stereo reference-player WAVs contain no clipped PCM samples. Finite clips
  last 4.8 seconds, held clips 13.8 seconds, and chord clips 3.3 seconds. All local page links,
  WAV headers and lossless ZIP integrity checks pass. New-page browser QA was not run because
  the Sites managed browser interface was unavailable; successful deployment is verified.
- Android instrumentation remains unrun because this workspace has no Android SDK.
  The six new voices and starter still need owner listening/device acceptance.

Reproduce in a **fresh output directory**:

```sh
./gradlew --no-daemon :shell:generateBristleVoices -PbristleVoicesOutput=/absolute/fresh/output
./gradlew --no-daemon test
python3 prototype/bristle/compare_packages.py /path/to/approved/sustain /path/to/regenerated/baseline
```

The first round-5 broad run found two preset-promotion contract failures and was stopped
with exit 130 to fix them. Bristle's complete JSON roster lines now have a voice-filtered
`promoted` destination beside the factory anchors. The generic roster tests decode that
complete constructor instead of assuming the older macro-only helper; custom saved patches
also prove exact recipe and rendered PCM round-trips. The corrected promotion-focused run
completed successfully before restarting the unfiltered suite. No oscillator or preset values
changed in this correction.

Upstream check during review preparation: `f1842eb48f175fb5fc77e02efeeda97c42c15d6a`
includes `3a824258`, which adds an explicit `mpcVelocityCycle` opt-in for HALOBAR's
owner-tested matching-window Cycle layout. This prototype remains based on `23cde0d1`;
that newer opt-in has not been applied to Bristle or independently tested with its three
windows × two takes. It is a concrete next integration route, not evidence that this
prototype's combined MPC gate has passed. Keep the safe first-take/fixed-touch delivery
until that behavior is verified.

The restarted broad run also found a pre-existing `PersonalityTest` sentence-case failure:
`App.kt` contains `${made.name.uppercase(java.util.Locale.ROOT)} IS READY IN INSTRUMENTS.`
and `CAIRN INSTRUMENT CANCELLED.`. Neither `App.kt` nor the checker is changed here.
The identical compiled checker was invoked against a detached `23cde0d1` source tree,
with its original shell/app scan roots; it fails on exactly the same two messages (exit 1).
That isolates a baseline defect without treating it as a Bristle regression or weakening
the test. The final broad-run exit and totals are recorded below once available.

## Owner decision and optional compression/fuzz

In response to the six-voice listening page, the owner said **“we can keep all”** and asked
for **“bass compressor … maybe … fuzz.”** This retains all six source voices alongside A/B;
it does not imply a new physical-device test. Compression and fuzz are new optional candidates.

Both controls default to zero and live in a versioned `effects` recipe object, separate from
the existing seven voice controls. The object is omitted from untouched recipes. This keeps
the approved source JSON and finite/held PCM exact. Frozen hashes for all eight dry voices
were captured from the unchanged pre-effects build before editing the sound engine.

| Control | Default | What it changes |
| --- | --- | --- |
| COMP | 0 | Parallel blend with the existing `Compressor`: RMS detection, lookahead, fast attack, roughly 46 ms recovery, and peak-matched body lift. |
| FUZZ | 0 | Adds a biased clipped upper-path signal; the independent clean fundamental bypasses the clipper. |

The fuzz uses a 120 Hz high-pass into a biased hard clipper with first-order antiderivative
antialiasing, DC removal and two 3.5 kHz low-pass poles, inside the existing 4× render. It is
added to the edge path before the amplitude envelope. COMP processes the conditioned note
and preserves its input peak, so it does not erase authored touch gain. A 5 Hz wet-path DC
filter removes the low-note offset that time-varying gain can create; the final fade remains
last. Held notes receive the same processors before the real preceding-cycle seam check.

Why it is built this way:
- Exact bypass preserves all eight retained sounds and their saved recipes.
- Reusing the existing compressor gives body lift without another dynamics engine to maintain.
- Keeping the clean fundamental outside fuzz protects the bass note as rasp increases.
- Oversampling and antiderivative antialiasing control the clipper's folded high-frequency energy.
- Saving the effects with the source carries the same sound through presets, pads and instruments.

The long broad regression continues on the unchanged pre-effects snapshot. New effects are
verified separately with focused DSP, persistence, export and playback checks; those results
must not be described as a full regression of the new effects version. Android instrumentation
is provided but remains unrun here because there is no SDK.

Effects validation: the final focused JVM command completed with exit 0: **24 synth tests
and 183 shell tests, zero failures/errors/skips**. This includes frozen dry PCM/JSON hashes,
full-strength effects stability, wet held seams, complete settings persistence and actual
exported sampler playback. The final tail check treats positive and negative floating-point
zero as the same silence. Earlier diagnostic runs caught a wet-path DC offset (fixed above)
and an assertion that incorrectly distinguished the sign of zero; neither failure is hidden
by the final passing report.

```sh
./gradlew --no-daemon :synth:test --tests '*Bristle*' \
  :shell:test --tests '*Bristle*Test*' --tests '*UserPresetsTest*' \
  --tests '*ConventionTest*' --max-workers=1
./gradlew --no-daemon :shell:generateBristleEffects \
  -PbristleEffectsOutput=/absolute/fresh/output --max-workers=1
```

The effects generator completed with exit 0: **16 finite/held banks**, each with 150 main
samples and three fixed-touch cycling companions, validated after writing. The listening
page offers 40 clips: dry, COMP 70%, FUZZ 70%, both at 70%, and actual exported held playback
for all eight voices. No extra preview gain is applied. These new effects await the owner's
listening judgment. The existing private Site is reused at `/effects.html`.

## Parameter integration and review build

The owner subsequently said “Sounds great. Can we add as parameters” and authorized proceeding.
COMP and FUZZ are adjustable 0–1 controls on every BRISTLE voice, default off, persisted with the
complete source patch. The listening clips' 70% setting is an example, not a fixed app setting.

The pre-effects broad regression finished across nine JVM modules: 10,117 tests, three failures
and no errors/skips. Two failures were Bristle integration omissions: the explicit preset sum
omitted the eight Bristle presets, and the shared macros test needed Bristle's strict saved-map
contract. The roster expectation now includes Bristle, the strict-format test covers its missing
map refusal, and malformed maps produce the common dispatcher diagnostic. The third failure
was the unchanged baseline CAIRN sentence-case issue documented above. This is a failed historical
run, not a claim that the current branch has a green full regression.

The prototype branch now incorporates upstream `9d6ad1a5` through a merge, retaining the newer
engine and starter registrations alongside Bristle. Current validation and Android build results
will be recorded in the review PR. Physical MPC velocity/cycle acceptance remains open.

Current merged-branch focused validation completed with exit 0: synth: 16 tests, shell: 90 tests, zero failures. This includes both shared integration fixes and the effects save/export tests. A fresh unfiltered JVM regression is being run on this merged source.
