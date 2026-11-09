# BACKWASH

BACKWASH is a drum engine for the SYNTH picker, under DRUMS & PERCUSSION.
A damped kick, snare, tom or hat feeds a moving double, parallel compression,
then amp coloration. Select a voice and use TUNE, DAMP, SWAY, TRAIL, PRESS and
DRIVE. There is no HOLD mode and no interaction between separate pads.

The initial conversational name UNDERTOW was already used by an unrelated
pressure-reservoir engine. BACKWASH leaves that instrument and its saves alone.

[Engineering specification](superpowers/specs/2026-11-07-backwash-drum-engine-design.md).
Baseline inspected: `cc47036c43e36d3eca78bd6e70cb15994b08d469`.

## Current build

The owner-approved sound is a dedicated model-3 acoustic-style drum source,
followed by model-4 chorus, parallel compression and amp processing. Nine voices
supply 36 factory presets: twelve original recipes, four new percussion voices,
and Seasick, Clamped, Overloaded and Pushed for each of the original five voices. Select BACKWASH → SNARE → Pushed Snare
to start with all three stages pushed. TUNE and DAMP shape the drum; SWAY,
TRAIL, PRESS and DRIVE shape its processing.

Old recipes without a version remain on the frozen model-3 renderer. New
recipes explicitly save their renderer version. The approved stronger presets
are pinned against twenty published WAV checksums, and the original 48 kit
strikes remain pinned separately. Both MPC formats have a 16-pad, three-layer
export; the isolated Android preview includes the same factory presets.

Use `:synth:generateBackwashPushedKit` for the stronger kit or
`:synth:generateBackwashKit` for the original kit. The portable preview build
commands are at the end of this document. Device timing, on-device UI execution
and MPC hardware loading remain separate from the automated checks.

The records below describe the successive listening and validation rounds;
their candidate status and measurements apply to the revision named there.

## Historical first build and audition

```
./gradlew --no-daemon :synth:generateBackwashAudition
```

Open `testkit/backwash-audition/index.html`. Each output comparison has a saved
PadRecipe JSON; `metrics.csv` records render time, length, peak, mean,
classification and tail RMS. Stage clips are loudness matched. These are
historical listening candidates. After owner listening, the sixteen MPC-kit
recipes are now in the factory roster; see the promotion record below.

The dedicated source and amp use 4x paths. The chorus and compressor run
at 44.1 kHz. Feedback is at most .58, with convex interpolation and filtering;
its decay does not depend on a limiter. Render state is local and deterministic.
The source and processors keep their existing normalization, so this is an
independent-hit instrument, not an absolute-level live bus model.

## Verification

Kotlin first-build checks passed: all 320 binary macro corners (five voices ×
64 settings), low/middle control response, finite/bounded PCM, DC, duration,
source damping, velocity timbre, effect order, feedback decay and saved-patch
determinism. PatchesTest (3 tests) and PadRecipeTest (25 tests) also passed.
EngineCatalogTest (42 tests) and SynthRootTest (7 tests) passed.
An initial tail assertion confused IEEE negative zero with positive zero; the
corrected test accepts either zero sign and the full corner sweep passes.

The generated audition contains 175 output/recipe pairs and 20 matched stage
clips (195 WAVs). Desktop Kotlin measurements, with other build work active:
median 52.3 ms per output, worst 164.3 ms; defaults 46.2–75.6 ms. These are not
phone timings and the worst corner exceeds the 80 ms target. Longest measured
output 1.3031 s, sample peak <= .990001, |mean| <= 5.62e-8, last-10-ms RMS
<= 4.03e-8. All WAV headers are mono 44.1 kHz PCM24; all page links resolve.

The default classifier reports KICK and TOM as named, SNARE as PERC, and both
hats as SNARE after the dark speaker chain. Picker/pad roles are explicitly
nominal drum roles, preserving hat choking; they are not claims that the audio
classifier agrees. Owner listening must decide whether the dark snare and hats
retain enough identity. Do not promote these candidates as factory presets
based on numerical safety alone.
Android `:app:compileDebugKotlin` and `:app:compileDebugAndroidTestKotlin`
both succeeded (exit 0). The added restoration/control test compiles but has
not run on a device. The full `./gradlew --no-daemon test` regression was started
at this first-build handoff; it is not yet a passing full-suite certification.
The work is on the local `claude/backwash-drum-engine` branch.

Phone render timing, device UI execution, owner listening and MPC load checks
remain separate from desktop compilation and numerical tests.

## Why it is built this way

- SKIN supplies established drum bodies, leaving the new work in their treatment.
- The short source lets its moving return remain distinguishable from the attack.
- Compression follows chorus so the hit and return share its gain envelope.
- The amp colors both together. Its effect on kick weight and hat brightness
  needs listening before approving factory sounds.

## Source revision 2: owner listening correction

The owner heard the original dry stage as too much like a basic drum machine.
That is the listening result; the original numerical checks did not establish
acoustic character. Revision 2 replaces the damped SKIN mapping with
`BackwashDrums`: 12 uneven head/shell resonances for drums, 16 plate resonances
for hats, a finite contact pulse, and snare texture driven by head amplitude.
Mode spacing, gains and loss constants are design hypotheses, not measurements
of a particular kit. These are independent resonators, not a coupled shell solver.
The modal recursion uses double state for low drum frequencies at 4x rate;
its gain cancels the low-frequency sine denominator. Cost is bounded by a
fixed mode count and a maximum .71-second dry buffer; no solver/cache is needed.
The chorus trajectory and all effect mappings are preserved for comparison.
Existing saved BACKWASH recipes now render the revised source; original WAVs
are retained as the listening reference. No factory bank has been approved.

Why it is built this way:
- Uneven nearby resonances allow beating instead of a single clean pitched tail.
- Finite contact spreads the strike over time; velocity changes its width and
  modal balance, so softer hits differ in timbre.
- Snare-wire energy follows the head, tying its texture to the same hit.
- Frequency-dependent decay lets the cloth kill ringing while leaving an attack.
- Acoustic plausibility remains a listening risk. A larger mode count alone
  does not prove realism.

Revision-2 measured evidence: `BackwashTest` passed all six tests, including
320 macro corners, and the 195-clip audition generator completed (exit 0).
175 output renders: median 50.9 ms, max 228.2 ms on the shared cloud host;
longest 1.360 s, sample peak <= .990001, |mean| <= 3.29e-8.
Defaults classify KICK, SNARE, TOM, PERC, PERC respectively. Hat identity and
phone timing remain open. The earlier full regression failed in CLI tests
with missing-class errors while concurrent compilation was occurring; this
is not evidence of a clean full suite or an established DSP regression.
Revision-2 deterministic replay, PatchesTest and PadRecipeTest completed with
exit 0. Published audition: https://backwash-drum-audition.bertcalm.chatgpt.site
The page keeps original dry WAVs beside revised dry WAVs and regenerates all
195 current clips. Asset links, PCM24 headers and the download ZIP were checked;
browser playback was unavailable in this environment.

## Revision 3: reference-led dry kit

Owner rejected revision 2, then supplied a screen recording of Rolling Stone's
“Ringo Starr Plays Drums on Miami Beach,” around 0:17–0:32. The clip is a mixed
performance, so it guides overall attack, body, damping and kit balance; it
does not establish isolated drum spectra or a unique acoustic model.
No audio from that recording is embedded in the generated voices.

Verified source changes: a separate band-limited beater/stick response and a
broad shell-coloured knock now carry the initial strike. The previous contact
layer was far too quiet beside the modal body. Low-mode dominance is reduced,
upper head modes retain more of their short ring, and snare wires follow a
slower head-energy envelope. Hats move into a higher register with a filtered
broadband wash. The effect mappings stay fixed. DAMP still changes ringing
length; it does not remove the entire attack spectrum. Version 3 replaces the
sound of existing BACKWASH recipes, as expected for this unapproved candidate.

Why it is built this way:
- A separate short strike preserves skin and stick information even with a
  damped body; darkening the whole waveform had erased that distinction.
- Body and wire decay must outlast the first transient or the result becomes
  a click/noise burst instead of a short acoustic-style drum.
- A band-limited cymbal wash supports the hats' role without relying on a few
  low metallic tones or unfiltered high-frequency hiss.
- A four-bar 94 BPM groove exposes balance, touch and choking in context.
  It is an original test pattern, not a transcription. Effects run per hit,
  with hat choking, so it does not promise shared compressor pumping.

The audition generator now adds dry/processed grooves and three touch levels
per voice. `scripts/measure_backwash.py` compares first-80-ms spectral bands
and energy decay with the archived rejected version. Those measurements are
regression evidence, not an acoustic-realism score. `scripts/build_backwash_page.py`
builds the listening page and download pack from the actual Kotlin renders.
The source remains bounded, deterministic, mono and 4x internally; finite
buffers now reach at most .76 s before the .65 s effect tail.

Revision-3 verification: six BackwashTest methods (including all 320 binary
macro corners) and the saved-patch deterministic replay canary passed; the
202-clip Kotlin generator completed, exit 0. New dry first-80-ms midrange
energy fractions (250–2000 Hz) are .103 kick and .481 tom, versus .0035/.048
in the rejected revision. Snare/tom 90%-energy times are .047/.046 seconds.
The hats' midrange fraction fell below .04. These are windowed measurements,
not a match score. The explicit attack/decay rejection guards pass.
175 measured processed outputs: median 66.1 ms, worst 481.6 ms on the shared
cloud host. Longest 1.410 s, peak <= .990001, |mean| <= 3.82e-8. Worst-case
render latency still exceeds the target; phone performance is not established.
WAV headers, page asset links and ZIP integrity passed. Browser playback
verification remains unavailable. Full regression was launched serially after
the focused build, with no simultaneous source changes or compilation.

## Owner-preferred direction and nearby candidates

Owner feedback on revision 3: “Much better, proceed in this direction.” Preserve
that source and its defaults as the reference. This approves a direction, not
every corner or a factory preset bank. MODEL_VERSION remains 3; synthesis and
all effect algorithms are unchanged in this follow-up.

`generateBackwashNeighborhood` creates four editable families: the exact
reference, Tighter (DAMP .90), More ring (DAMP .60), and Moving double (SWAY .45,
TRAIL .40). Everything else stays at the accepted defaults. Each family has
five dry taps, five processed voices, five touch comparisons and two grooves.
Twenty saved recipes are decoded and rendered again by the generator, which
requires exact sample equality. Groove timing, velocity, balance and choking
are unchanged. `scripts/build_backwash_neighborhood_page.py` compares all ten
reference one-shots and both reference grooves against their previously
published WAVs byte for byte before building the page.

Why it is built this way:
- Preserve the heard reference so improvement is judged against a stable sound.
- Change damping alone for the body/decay comparisons, preserving strike design.
- Change only chorus movement/regeneration for the treatment comparison.
- Keep all variants editable and unapproved until listening, rather than making
  speculative replacements for the source the owner preferred.

The preceding full test run exited 1 after 50m15s at :shell:test: its 2,234
shell tests had one failure, the picker documentation convention. The two
README engine lists omitted BACKWASH and the app count was stale. Those lists
now match the 77-engine picker; the focused convention suite is rerun for this
fix. The full suite had not completed :synth:test, so it is not certified green.

Follow-up results: all 68 ConventionTest checks passed and the candidate
render task completed, exit 0. All 20 recipes replayed sample-exactly. All 12
reference WAVs (five dry, five processed, two grooves) matched the previously
published revision-3 files byte for byte. All 68 new PCM24 WAV headers, page
links and ZIP integrity passed. Browser playback was not available for QA.

## Sixteen-pad MPC playing trial

`./gradlew --no-daemon :synth:generateBackwashKit` builds
`testkit/backwash-kit/backwash-mpc-kit.zip`, containing native MPC 3 `.xtd`,
its MPC 2 `.xpm` twin, 48 PCM24 strike WAVs, saved recipes, pad map, checksums
and previews rendered from the exported WAVs. Model 3 and its defaults stay
unchanged. The four rows are core drums/hats, tighter variants, toms, and the
moving-double variants. A09/A11 use the low/high tom tunings already present
in the audition fill. A10 retains the default tom.

Every pad uses source velocities .3/.6/1 at MIDI zones 1–41/42–84/85–127.
No separate sampling attenuation is baked in; MPC velocity-to-volume is applied
once at playback. All hats share mute group 1. Other pads have no choke group.
Pad mixer levels preserve the groove's kick/snare/tom/hat balances. Samples
include BACKWASH's internal effect chain; no extra sampler effects are added.

Hard layers use ordinary PadRecipe sidecars. Each layer also has an explicit
`snipsnap-backwash-strike` wrapper carrying model, source velocity and the
nested PadRecipe. These wrappers are not interchangeable with plain PadRecipe:
regenerate them with Backwash.render using the saved voice/macros/velocity and
model-3 source. The generator decodes and checks exact replay for every layer.
This avoids presenting the soft-layer WAV as a default full-velocity recipe.

The MPC 3 export carries a four-bar pattern; set session tempo to 94 BPM.
MPC 2 carries the program only, with WAV groove evidence. Browser and WAV
previews select actual exported velocity zones and apply saved pad levels,
a linear preview velocity gain and a 4 ms choke fade; they do not emulate a
particular MPC's velocity curve. The existing KitPreview does not select
ordinary velocity layers, so this generator uses an explicit delivered-layer
preview instead of misrepresenting its hard-layer-only output.

Verification: generation completed with exit 0; all 48 decoded layer recipes
replayed exactly; all exported WAV bytes matched the expected PCM24 renders.
Both exporters passed read-back, plus direct checks for MIDI maps, velocity
coverage (including no velocity-zero zone), sample ends, one-shot flags,
absence of loops, tuning and mute groups. All 14 hard layers drawn directly
from the previous audition bank matched its published WAVs byte for byte.
ZIP integrity, checksums, WAV headers and all page asset paths passed.
Archive size: 4,880,672 bytes. Browser playback and MPC hardware loading,
velocity transitions, balance and tail behaviour remain unverified.

Why it is built this way:
- The accepted source remains fixed while the sampler arrangement is tested.
- Three native strike timbres preserve soft-hit character beyond level changes.
- A single hat choke group makes the open/closed variants behave as one kit.
- The audition plays delivered samples so export changes cannot hide behind a
  separate synthesizer render. Factory promotion waits for the playing trial.


## Factory promotion after owner feedback

The owner heard the kit audition, said “Sounds good,” and authorized proceeding
with factory promotion and app validation. This is sound approval; no MPC
hardware test was reported. `BackwashPresets` now owns exactly the sixteen kit
recipes and their bank order. `Presets.forVoice`, `Presets.all` and therefore
the ordinary phone factory strip and CLI expose them. BACKWASH is removed from
the prototype exception. Core recipes remain first for each voice, including
Mid Tom for TOM. The exporter now draws from this same production table.

No synthesis, default macro values, effect settings or velocity logic changed.
A checked-in evidence fixture records the 48 source-velocity wrappers and
SHA-256 hashes from the shipped kit. Tests require the promoted recipes to
match those saved patches and all delivered PCM24 WAV hashes. The app test
now selects Tight Snare, verifies DAMP .90, and checks voice/control restoration.
This test can be compiled here, but there is no attached Android device for
execution or phone render-time measurements. Factory promotion does not imply
a published app release, completed hardware acceptance or a clean full suite.

Why it is built this way:
- The factory table comes from heard kit settings, not a new speculative bank.
- One table supplies both picker and kit export, preventing future divergence.
- The first preset preserves each voice's familiar default.
- Delivered-WAV hashes protect the sound through registry and UI changes.
The factory registry and each voice list share the same ordering, while
`kitOrder()` separately preserves bank A's low/mid/high tom pad layout.
This keeps Mid Tom first in the picker without moving any exported pad.

Promotion verification completed with exit 0: 2 BackwashPresetsTest checks
(including all 48 delivered-WAV hashes), 8 cross-engine PresetsTest checks,
and all 68 ConventionTest checks passed. Android main and Android-test Kotlin
compilation both passed. Regenerating the complete MPC kit from the production
factory table produced a ZIP byte-identical to the published download, so the
existing kit and audition remain the correct artifacts. No website republish
or audio replacement is needed. `adb devices` reported no attached device;
on-device UI execution, render timing and confirmed MPC playback remain open.

## Installable phone preview

The opt-in `backwashPreview` variant builds the full app for ARM64 / Android
10+, under `com.snipsnap.app.backwashpreview` with its own launcher label and
app data. Build and instrumentation APK assembly passed (exit 0, 4m58s).
The package is development-signed, not a store release. APK verification
confirmed both signatures, matching app/test certificates, the separate
application ID, API 29 minimum, ARM64 audio library, factory class and all
sixteen names, and the packaged BACKWASH UI test method.

The delivered APK is 65,402,999 bytes; SHA-256:
`130d79dc0c280c5632f06093c4da329a24d7f7d0732cb957b8e1b4e6fbc8a329`.
`scripts/verify_backwash_preview.py` repeats the package checks and writes a
verification report. Build and connected-device commands are in app/README.md.
No Android device, emulator or MPC is attached. UI execution, sound playback
and render latency on the target hardware remain unverified; packaging and
signing checks do not stand in for those tests.
The hosting source service enforces a 32 MiB single-object limit. The phone
page therefore serves three smaller APK pieces. Its download button assembles
them, checks the complete SHA-256 against the verified APK, and saves one APK.
Local reassembly is byte-exact and the download script passes Node syntax
checking. The browser download flow itself has not been executed here.
The complete APK is also retained at `/workspace/library-files/snipsnap-backwash-preview.apk`.

## Stronger processing study 1

Owner requested pushing the processing after accepting the dry kit. The
`generateBackwashProcessing` task renders the same sixteen model-3 sources
and the same four-bar groove through five treatments: unchanged Reference,
Seasick, Clamped, Overloaded, and Combined. This is a test-source listening
study, not a revision of factory recipes or the published phone/MPC builds.

Seasick replaces the chorus with a 28 ms centre, 14 ms modulation depth at
1.15 Hz, .95 wet gain and .74 feedback through the same 5.5 kHz one-pole,
with .9 seconds of additional tail. Linear interpolation and convex filtering
keep feedback a peak-norm contraction; no limiter establishes stability.
Clamped uses Compressor AMOUNT .88, ATTACK .58, RECOVER .84 at a .90 wet blend.
Overloaded uses Valve DRIVE .84 and SAG .35, preserving TONE .48/CAB .55;
its output is .80 amp plus .20 of a pre-amp 160 Hz low band. Combined uses
all three. Constants are audition hypotheses, not measured pedal settings.
Unchanged stages retain model-3 mappings from the saved base recipe.

Why it is built this way:
- Separate treatments identify what each stage contributes before combining them.
- Dry source, velocity, pad balance, timing and choking stay fixed for comparison.
- A small low-band blend supports drum weight beneath harder amp coloration.
- One linear comparison gain per whole clip reduces loudness bias without
  changing compression or applying another limiter. The meter is SnipSnap's
  loudest weighted 200 ms window, not integrated LUFS or a perceptual guarantee.

Verified: 16 raw reference hits match the delivered kit WAVs byte for byte;
the reference groove matches the earlier audition; all five dry grooves and
all event lists are identical. All 80 one-shots replay deterministically.
All 85 matched comparisons meet their shared group target within 1e-5 and
stay below .90001 sample peak. Two feedback/character tests and two factory
preservation tests passed; build/generation exit 0. Measurements: median
60.2 ms, max 222.8 ms per one-shot on the cloud JVM; max duration 1.36 s,
peak <= .990001, |mean| <= 1.36e-6. Snare energy after 100 ms rises from
.020 reference to .264 Seasick, .405 Clamped, .403 Overloaded and .880 Combined.
These show stronger tails, not owner approval or a claim that they sound better.

The download includes native and matched WAVs, metrics/matching CSVs, exact
study parameters, per-pad study manifests and the Kotlin study source.
Study manifests are not ordinary PadRecipe imports: they require the study
renderer and model-3 source. Main engine/model, factory bank, MPC exports and
Android APK are unchanged. Browser playback remains unavailable for QA.

## Approved processing promotion (2026-11-08)

Owner response to the stronger processing study: “That's is great. Let's proceed
in this direction.” The four treatments are now editable production presets:
Seasick, Clamped, Overloaded and Pushed (all three), for each of the five voices.
The original sixteen presets remain available, giving 36 factory presets.

Processing model 4 extends the upper half of SWAY, TRAIL, PRESS and DRIVE.
The lower half retains the original mapping. At maximum, SWAY/TRAIL reach the
study's 28 ms centre, 14 ms modulation depth, 1.15 Hz rate, .74 feedback and
.95 delayed return; PRESS reaches .88 amount, .58 attack, .84 recovery and .90
compressed blend; DRIVE reaches .84 valve drive, .35 sag and a .20 pre-amp
low-band blend. TUNE/DAMP and the model-3 drum source are unchanged.
The longest open-source corners cap the processing buffer at 1.49 s, then use
the existing output fade, so BACKWASH stays a one-shot. The accepted preset
settings do not reach that cap.

Saved patches now carry `model`. JSON without this field loads model 3 using
`BackwashV3`, a frozen renderer, including its full original macro range.
New patches use model 4. The original factory bank explicitly uses model 3;
its controls retain the original behavior. Select a new processing preset to
edit the extended ranges. Model selection survives recipe edits and native
source-velocity rendering; unknown versions are rejected.

The new MPC kit has 16 pads and three strike layers per pad. A01–A04 use Pushed,
A05–A08 Clamped, A09–A12 the four tom treatments, A13–A16 Seasick. This is a
new performance arrangement; its hardware balance and feel still need playing.
The original kit and processing comparison page remain available.

Why it is built this way:
- Extending the existing controls lets a preset be reduced stage by stage.
- A delayed return below unity feedback sustains motion while still decaying.
- Parallel compression retains attack, and the low band supports drum weight
  beneath the stronger amp stage.
- Explicit recipe versions keep previously saved sounds reproducible.

Verification is recorded below after the build. The user heard the processing
study; the new pad arrangement and Android build are not hardware-tested.

Promotion validation: focused DSP run passed 12 tests, including all 320 macro
corners and exact playback at three strike strengths. Follow-up integration
passed 12 synth tests and 68 shell convention tests. The new frozen-WAV fixture
pins all 20 approved hard strikes; the original fixture still pins all 48 old
kit strikes. The new kit generator verified all 48 layer wrappers, exact PCM24
exports, both MPC formats, velocity zones, note maps and choke groups.

Both preview APKs compiled successfully. App signature and test signature match
the previous preview certificate. Packaged audio library, five-voice factory
registry naming parts, and the updated UI test are present. The verified APK is
77032179 bytes; SHA256 `b6dac3a436eb55dbf06bbbbfaadb796c2ee32a15619f1a18d94ab78305be65d1`.
Ten download parts reconstruct the exact APK; download JavaScript syntax and
all five page link sets pass. No Android device is attached, so UI execution,
phone timing, MPC hardware playback and browser QA remain unverified. The full
repository regression was not repeated; these are focused/integration results.

## Reproduce the preview outside this workspace

The packaging helpers accept paths instead of depending on this session's
filesystem. With an Android SDK installed:

```sh
./gradlew --no-daemon :synth:generateBackwashPushedKit
./gradlew --no-daemon -PbackwashPreviewBuild=true -PsnipsnapTestBuildType=backwashPreview :app:assembleBackwashPreview :app:assembleBackwashPreviewAndroidTest
python scripts/verify_backwash_preview.py --sdk "$ANDROID_HOME" --output testkit/backwash-preview
```

The verifier also accepts `--build-tools` if 35.0.0 is not installed. Site
packaging takes an existing site directory and an APK path:

```sh
python scripts/package_backwash_phone_download.py /path/to/site/dist testkit/backwash-preview/snipsnap-backwash-preview.apk --page kit-and-phone.html
```

Copy the matching verification JSON into the site's `android-preview` directory
first. The helper verifies the complete APK hash before splitting it, checks
reassembly, and updates the existing download page. The checksum check does not
replace APK signing verification or an on-device installation test.

## Percussion replacement row

The current 36-preset factory roster spans nine voices. Cross Stick, Tambourine, Cowbell and Ride Cymbal replace the four Double presets; both 16-pad exports put them on A13–A16. The first twelve pads retain their existing recipes. Saved Double recipes still replay exactly through their versioned renderer. New voices require model 4.

### Why it is built this way

The extra row now adds distinct physical gestures: a short woody cross-stick contact, repeated paired-jingle collisions, a two-dominant-mode metal bell, and an inharmonic cymbal plate with sustained noisy wash. These are designed modal models, not measured replicas. All four pass through the established chorus → compressor → amp chain. Velocity changes contact and spectral excitation; TUNE and DAMP change their source character. Four-times oversampling and decaying modal poles bound the sources before the existing output treatment.

The updated four-bar audition introduces cross-stick in bar two, tambourine in bar three, and cowbell plus ride in bar four. The new timbres still need listening approval; numerical dissimilarity is not an assessment by ear. Targeted checks cover deterministic JSON replay at three velocities, finite bounded output, factory placement, and unchanged historical audio fixtures.

### Dry / lower-drive / previous percussion comparison

`./gradlew :synth:generateBackwashPercussionComparison` renders the same hard strike through three paths: source only, full processing with DRIVE 0.15, and previous full processing with DRIVE 0.65. The source-only path bypasses all three effects, retaining source output conditioning. SWAY 0.55, TRAIL 0.55 and PRESS 0.75 stay fixed. Lower drive still includes amp coloration. The factory defaults and existing exports remain at the previous settings while the comparison is auditioned.

Each instrument's three files share a peak-safe loudness target, reached by scalar gain only, without extra limiting. The generator verifies finite output, headroom, matching tolerance and exact replay of the four downloadable candidate recipes. This distinguishes source texture from processing without changing multiple effect controls at once. Listening approval is pending.
