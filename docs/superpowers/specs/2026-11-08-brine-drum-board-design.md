# BRINE: acoustic drum recording and spring board

Version 1 · 2026-11-08. Status: first Kotlin listening build, voicing unapproved.
Repository baseline: `7ccdbe713f3ec502e2978674c1820cf15d623770`.

The characteristic gesture is **compact acoustic hit → spring splash → moving tail → compressed speaker decay**.

## 1. Summary and scope

Use existing SKIN drum voices to approximate a compact two-microphone acoustic recording,
then process their summed performance through a dedicated recording, spring, modulation and
amp chain. Working name BRINE. It is a compound processor and listening study, not a new
pitched engine. The user requested an engineering spec, a first build, and an audition page.

R1 includes finite original phrases, isolated hits, six controls, deterministic settings,
new spring-inspired dispersion, cumulative stage auditions and machine-readable evidence.
It does not include a factory preset bank, Android picker, a live bus, a physical microphone
simulation, a circuit clone, or a seamless HOLD loop. These exclusions define the first
listening milestone, not an approval requirement to continue technical work.

The reference video caption says “2 mic drum sound, 60’s saturated.” Decoded PCM is mono
44.1 kHz, 11.8654 seconds. Python whole-file measurements: sample peak -3.18 dBFS,
RMS -15.94 dBFS, crest factor 12.77 dB. These do not identify the original gear, mic
placement, EQ, tempo or degree of saturation. No reference audio is shipped or sampled.
The clip has not been auditioned by this implementation session.

## 2. Research distilled into mechanisms

Research names are permitted here; none become engine, voice or preset names.

* Catalinbread Topanga: driven spring excitation, dispersive return, wet-only tone,
  dry/wet balance and preamp output ahead of the amp. Dwell is excitation intensity,
  not simply decay time. [Manufacturer manual](https://cdn.shopify.com/s/files/1/0370/1873/0628/files/TopangaManual.pdf?v=1588317267).
* Electro-Harmonix The Worm: one selectable modulation mode with rate and range.
  [Manufacturer manual](https://www.ehx.com/wp-content/uploads/2020/11/worm.pdf).
  R1 distils slow phasing; it does not implement all four modes. Tremolo is a later
  audition alternative, not a simultaneous hidden stage.
* Unspecified amp: assume a small combo near breakup. Reuse VALVE's asymmetric curve,
  modest sag and speaker filtering. No claim that this models a particular amplifier.

These are behaviour adaptations. No manufacturer circuits, measured impulse responses,
third-party source, or demo samples were copied. Sound matching remains a listening task.

## 3. Sibling check and choice

SKIN owns acoustic-style drum sources. REVEL owns tuned membrane ensembles and moving
microphone geometry, which is unnecessary for fixed recording perspectives. Backroom
(`BoomBapKits`) already supplies a SKIN recording study; BRINE adds a distinct spring-first
board and a shared phrase audition, not a replacement acoustic engine.

Three directions considered:

| Operator | Gesture clarity / novelty | Risk / build scope |
|---|---|---|
| STACK, chosen | Strong; moderate novelty through shared saturation and order | Moderate; source/board audition, refinement, promotion |
| JUXTAPOSE dry kit and extreme wet copy | Strong attack; lower novelty | Lower; two rounds |
| INVERT spring into primary drum resonator | Strong new identity; higher novelty | Higher; three or more rounds, less faithful to reference |

The chosen path keeps the requested order with the lightest new architecture.

## 4. Signal flow and rates

```
SKIN hits, velocity, take @44.1 kHz (SKIN internally oversamples)
  -> source-specific near/far low-pass + fixed delay + balance
  -> mono phrase sum [cached once per audition]
  -> finite early reflections (ROOM) @44.1 kHz
  -> 4x interpolated recording saturation (GRIT) -> band-limit/decimate
  -> 4x driven spring feed -> band-limit/decimate
     -> 180 Hz feed high-pass -> 3 dispersive delay loops -> wet tone
     -> sum with dry (SPLASH) @44.1 kHz
  -> PHASE, continuous across phrase (SWEEP, RATE)
  -> VALVE at 4x internally (DRIVE, fixed modest sag and speaker)
  -> 20 Hz DC blocker -> 80 ms end fade -> attenuation-only peak ceiling
  -> raw WAV + separately loudness-matched audition WAV
```

One second of zero input is allocated once before processing. All downstream stages process
the spring tail. There is no per-hit reset within a phrase. A new render starts fresh state.
The dry comparison is padded only for aligned audition playback.

## 5. Source and recording model

The source uses SKIN kick, snare, closed hat and tom. Each source has two fixed pickup paths:
near low-pass 6.5 kHz; far low-pass 3.8 kHz; source-specific gains and delays of 79–161
frames. These numbers are **shape values**, not measurements of the video. The path models
arrival and tonal balance; it does not simulate microphone polar response or acoustic space.

Every event calls `Skin.render(voice, macros, velocity, take)` so kick/snare/tom contact
changes with velocity. A separate event gain restores performance dynamics after the source's
normalisation. Take varies deterministically by event index. The existing source uses its own
seed helpers; the board introduces no random noise. The manifest records all source macros,
positions, balances, hits, tempo and take indices. The 100 BPM, two-bar pattern is original,
not a transcription. A quiet ghost snare tests whether the processing preserves dynamics.

ROOM adds three low-pass-filtered early reflections at about 11, 23 and 37 ms, gains
.36/.23/.14 multiplied by ROOM. This FIR path has no feedback. GRIT drives a 4x tanh stage:
`g = 1 + 5*GRIT; y = tanh(g*x)/sqrt(g)`. Fixed gain, not per-event normalisation.

## 6. Spring model and stability

Three independent waveguide loops have delay lengths 1327, 1597, 1901 frames at 44.1 kHz.
Each contains twelve first-order allpasses with coefficients `.52 + .025*j`, a 4.8 kHz
one-pole loss filter and feedback .73/.77/.79. These are audition shapes. Coefficients and
delays remain fixed during a render; no retuning discontinuities occur.

```
allpass: y[n] = state - a*x[n]; state = x[n] + a*y[n]
loop: delay[write] = .18*feed[n] + feedback*loss(dispersed_return)
output: dry + 1.3*SPLASH*lowpass(sum(loop_returns), 4200 Hz)
feed: highpass(saturate(input, 1 + 7*SPLASH), 180 Hz)
```

Each allpass has unit magnitude for |a| < 1. Loss has magnitude <= 1. Feedback is <= .79.
Independent loops therefore have strictly sub-unity round-trip gain. Feed saturation is
outside feedback and is not a stabiliser. An impulse test measures unforced late energy
before output fading or peak control. A dispersion-off diagnostic proves the allpass chain
actually changes the response. This is a modest dispersive network; sufficient audible
spring character is the principal sonic risk, not yet an established result.

## 7. Modulation and amp

Reuse `Phase.process` with DEPTH=SWEEP, RATE=RATE, FEEDBACK=.12. Phase's rate law spans
.15–4 Hz; the default .2 maps to about .289 Hz. Start phase is deterministic. Its internal
peak matching operates over the whole phrase and is disclosed; it is not per-hit levelling.

Reuse `Valve.process`: DRIVE=.25+.35*DRIVE, SAG=.18, TONE=.43, CAB=.55. This stays below
VALVE's extreme gain region. DRIVE zero still includes speaker coloration; DRY is the actual
bypass. VALVE peak-matches its complete input and output, so absolute pedal-output-level
interaction is approximate. GRIT and SPLASH still change waveform/envelope into the amp.
Preserve these shared processors unchanged until listening evidence supports a new core.

## 8. Macro contract

All controls are 0..1. Defaults are unapproved shapes.

| Control | Default | Model quantity | Interaction and proof |
|---|---:|---|---|
| ROOM | .20 | Early-reflection gain | GRIT changes saturation of reflections; waveform delta test and source stage audition |
| GRIT | .25 | Recording feed gain 1..6 | SPLASH receives the altered transient; measure waveform and crest factor |
| SPLASH | .30 | Spring feed drive and return gain | DRIVE reshapes returned tail; four-corner audition and non-additive delta test |
| SWEEP | .25 | Phaser excursion | SPLASH makes sweep audible in decay; stage/sweep auditions |
| RATE | .20 | Phaser LFO frequency | SWEEP must be nonzero; long phrase comparison and waveform delta test |
| DRIVE | .30 | Amp gain within restrained range | SPLASH x DRIVE pair above; compare attack and tail |

Settings are immutable and serialize all six macros under a BRINE version. Invalid,
non-finite, missing or unknown saved controls are rejected. This board intentionally does
not take TUNE, root MIDI or a drum classifier: those belong to its existing drum sources.
There is no meaningless root-preservation requirement for a shared drum phrase.

## 9. HOLD and output contract

R1 has no HOLD control, sampler loop marker or loop claim. A browser replay is a finite
phrase replay. R2 may add periodic phrase rendering after measuring the kept cycle against
its independently generated continuation, with modulation and spring state both periodic.
A crossfade alone does not establish steady state.

Mono 44.1 kHz only; reject other inputs rather than silently fold/resample. Nonlinear new
stages use `Valve.upsample`, `Tide.bandLimit`, `Dsp.decimate`; existing VALVE oversamples.
Input must be finite. Processing does not mutate source samples. Output is finite, DC
blocked, ended smoothly and attenuated if above .99. No final gain boost. Audition matching
uses `AuditionLevel` separately: loudest 200 ms RMS target .03, not LUFS. Raw files preserve
the board's own output gain. Tests inspect spring decay before the final guard.

## 10. Render budget and caching

Target: warm one-hit board processing in tens of milliseconds on a phone. This is a target,
not measured evidence. Each finite frame costs three delay reads and 36 allpass steps plus
filters; saturation/amp have fixed 4x work. No geometry solver, count macro or unbounded loop.
Memory is O(input frames + fixed delay state). The audition caches the dry phrase once.

Future interactive cache key: source PCM identity, sample rate, model version, complete
settings for every upstream stage and requested cumulative stage. DRIVE-only changes can
reuse modulation output; RATE changes invalidate modulation onward; source/ROOM/GRIT changes
invalidate downstream stages. R1 does not add a global cache. Record warmed host timings in
metrics.csv and report phone performance as unmeasured. Avoid claiming a long phrase meets
a single-hit target.

## 11. Integration and build rounds

R1: `Brine.kt`, `BrineTest.kt`, a `DeterminismTest` canary,
`BrineAuditionGenerator.kt`, `brine-audition.html`, `generateBrineAudition`, this spec and
`docs/BRINE.md`. First milestone is a runnable and tested listening study.

R2: refine drum source, dispersion and wet balance from listening notes; compare tremolo
if phasing is not the desired motion. Add a higher-rate alias reference if new clipping
laws change. Test all macro pairs and target-device cost before promotion.

R3: integrate a versioned `BoardRecipe` branch and its serialization/shell tests after the
voicing is selected. Existing `BoardRecipe` supports dedicated identities; `PedalBoard`
keeps fixed within-side order, so it cannot directly express this chain. No global rack
reordering. Android picker requires an SDK-capable build. New engine registration,
Patches/Velocity arms, engine count and melodic kit conventions do not apply to R1.

R4: optional phrase HOLD and kit export. Individual processed pads cannot preserve shared
nonlinear bus interactions when retriggered; ship that limitation explicitly.

## 12. Probes and audition acceptance

Run `./gradlew --no-daemon :synth:test --tests '*BrineTest'` and the BRINE determinism canary,
then `./gradlew --no-daemon test`. Record exit codes; never infer success from log fragments.
Generate with `./gradlew --no-daemon :synth:generateBrineAudition`.

Numerical probes: determinism, JSON replay, invalid input/settings, finite output, bounded
peak, DC, end sample, fixed tail length, silence, source immutability, every macro changing
a long probe, spring/drive interaction, spring unforced decay and dispersion ablation.
Existing Phase/Valve tests remain the baseline for reused blocks. New saturation is a smooth
4x tanh approximation; no new quantified alias floor is claimed in R1.

The page contains 37 comparisons, raw and matched WAVs, complete source manifest, per-clip
settings, SHA-256 hashes, timing/level CSV, persistent notes and a notes download. It loads
without fetch so an extracted folder works offline. Play switches preserve position within
a comparison group; native controls permit seeking and pausing. Test page behavior in a
browser, check every file reference, and retain both stage and single-hit evidence.

Listening gates: Does the dry kit point toward the reference? Does the spring splash have
an identifiable dispersive attack? Is the ghost snare still a ghost? Does the amp retain kick
weight? Does the tail move without swallowing the groove? Factory presets wait for answers.

## 13. Sonic risks and evidence ledger

| Risk | Evidence/gate |
|---|---|
| Synthetic drum source misses acoustic recording character | Dry/source stage and single hits first |
| Allpass network reads as metallic echo, not spring | Spring stage, dispersion ablation, owner feedback |
| Amp thins kick or makes cymbals abrasive | Kick/hat solo and full phrase raw/matched |
| Tail masks rhythmic detail | SPLASH/DRIVE corners, ghost snare phrase |
| Fixed mic delay cancels body | Dry source audition, source gains/delays in manifest |
| Normalisation masks physical gain interaction | Raw files and explicit shared-block peak-matching disclosure |

Verified: baseline code APIs and routing read locally at the commit above; manufacturer
manuals read in the preceding concept turn. Measured: reference PCM statistics above.
Proposed: all voicing constants, fidelity, phone budget and final integration. Heard: none
in this session. Test and render evidence is appended below when the build completes.

## Why it is built this way

* A shared phrase lets drum hits interact through one saturation and reverb history.
* Fixed source perspectives provide recording colour without a costly room solver.
* Dispersion separates spring behaviour from a generic reverb wash.
* The spring precedes phasing and the amp so the decay itself moves and compresses.
* Raw alongside matched audio separates tonal preference from louder-is-better preference.

## As built

R1 implements the stated finite path and audition workflow. No factory registration or
acceptance claim. Focused verification: 4 BrineTest tests plus the BRINE DeterminismTest canary passed;
Gradle `:synth:test --tests '*BrineTest' --tests '*DeterminismTest.*BRINE*'
:synth:generateBrineAudition` exited 0. All 37 settings files decode and agree with the
manifest. All 74 WAVs have verified hashes, mono 44.1 kHz/24-bit headers and expected lengths.
Maximum absolute raw mean across the set is .001753; maximum raw peak is .472050.

Host full-chain render times (including finite tail, excluding source construction):
409–2099 ms, median 1614 ms. These measurements include differing source lengths and
settings, and are not a formal microbenchmark. The low control edge is fastest. The
single-hit clips include 1.4 seconds of source allocation plus 1 second of tail. The
instant knob-turn budget is NOT met in R1. This is an offline listening build; profile,
cache upstream stages and reduce redundant 4x round trips before interactive promotion.
Phone cost remains unmeasured.

Chromium 156 over local HTTP: all 37 cards, actual WAV playback, same-group position
switching, raw/matched switching, local note persistence, downloaded JSON notes and
390-pixel mobile width passed. No script errors. The browser test caught a preload-none
startup deadlock; explicit media loading now precedes the metadata/play callback.
The template embeds its manifest and uses relative files; managed-browser file URLs were
blocked, so direct file-mode playback is not claimed as tested.

Full repository JVM suite: prior run ended without a recorded exit status; not verified green.

## Reference refinement R2

Owner feedback: get closer to the reference video drum sound. Added `BrineKit` as a separate
version-2 source study; preserved the board's version-1 DSP and saved settings. The event
clock stays at the identical 100 BPM pattern. New source parameters are in the manifest.
Recording contour: broad 540 Hz / +14 dB / Q .8 body, 2 kHz / -6.2 dB high shelf,
4x tanh saturation gain 6.6. These rounded values were selected from a Python six-band and
crest-factor fit, then remeasured on actual Kotlin renders. They are not claims about the
reference recording's physical equipment, and aggregate spectral matching is not a listening
match. The body contour compensates for missing low-mid energy in the simple modal source.

The script `experiments/brine/measure_reference.py` measures Welch power in six bands with
4096-sample segments. Reference uses its full 11.865-second clip; generated comparisons use
the first 4.8-second performance, excluding added tails. Source performance is original,
not a transcription, so event balance also influences these summary statistics.

| Measurement | Reference | R1 recording | Revised balanced | Revised room |
|---|---:|---:|---:|---:|
| 30–100 Hz, % of measured band power | 46.89 | 91.38 | 44.05 | 46.12 |
| 100–250 Hz | 30.19 | 6.42 | 31.91 | 32.73 |
| 250–1000 Hz | 11.72 | 1.86 | 12.25 | 10.82 |
| 1–4 kHz | 7.85 | .08 | 8.30 | 7.23 |
| 4–10 kHz | 3.35 | .16 | 3.47 | 3.07 |
| Crest factor, dB | 12.77 | 18.48 | 13.80 | 13.08 |

Eight focused Kotlin tests passed (BrineKitTest, BrineTest, BRINE determinism canary),
combined test/render command exit 0. The page now has 46 comparisons with 92 raw/matched
WAVs, including the original 37 unchanged audio comparisons. No audio from the reference
is distributed. This session has not heard or accepted the result. The prior full repository
suite ended without a recorded exit code; it is not evidence of a green whole-repository run.

Why this revision is built this way: source balance is corrected before the pedal chain;
shared recording dynamics reduce the isolated-hit feel; same-pattern comparisons isolate
sound changes; original renders remain available so the owner can reject a numerical fit
that does not improve the sound.

## R3: complete the first articulation round

Scope authorized after R2 listening: open hat, floor tom, cross-stick, ghost snare and rimshot,
with a published audition. Gesture: varied stick strikes → shared recorded kit → spring →
modulation → amp. Landing is five SKIN-based recipes in `BrineKitExpansion`, not a new engine.
The reference remains a character target; no reference audio is included.

Read against base commit `7ccdbe713f3ec502e2978674c1820cf15d623770` plus the local BRINE work.
SKIN exposes HAT_OPEN, TOM, STICK and velocity-sensitive SNARE. THUMP is the nearest alternate
source; its electronic character would move away from the approved baseline. Reuse wins over
new synthesis here. There are no new public board macros or changes to board settings version.

Source choices (all provisional voicing constants):
- Open hat: same tuning/tone/ring as the closed hat; DECAY .50, source gain .48.
- Floor tom: TUNE .12, DECAY .58, DROP .08, source gain .34.
- Cross-stick: STICK TUNE .22, BODY .58, CLICK .12, source gain .60.
- Ghost: approved SNARE recipe at velocity .30.
- Rimshot: approved SNARE at velocity .98 plus simultaneous STICK at relative mix .32.

The expanded two-bar phrase replaces selected backbeats with cross-stick and rimshot, inserts
open-hat accents and ends on a floor tom. The closed-hat strike releases open-hat source
vibration over 6 ms before shared recording processing, allowing acoustic/effect tails to ring.
The isolated open hat is intentionally unchoked. All events and recipe controls are recorded
in the audition manifest. Before-pedal means the approved recording treatment is already on.

Implementation shares `BrineKit.finish` without changing its arithmetic. R2's fixed source
version remains 2; the expansion identifies as version 3. Existing WAV hash comparison is the
regression gate. Source synthesis remains offline, with no new solver or precomputation. No
claim of phone-interactive latency is made; the audition caches rendered WAVs. The generator's
render_ms measures board processing only, excluding source construction.

Why it is built this way: shared recording makes one kit; recipe reuse avoids a redundant
engine; soft strikes change excitation as well as level; simultaneous rim/body hits avoid a
flam; upstream hat choking preserves downstream spring decay. New additions await Josh's ears.

R3 verification: focused Kotlin tests and audition generation exited 0 (12 tests, 60 clips).
All 46 R2-era raw and matched WAV hashes remain identical after extracting the shared finish.
All 120 output WAV hashes and recipe paths verify; embedded JavaScript parses and preserves
the notes key. Browser QA was unavailable in this managed session, so playback/UI behavior
was not reverified. The complete repository test suite remains unverified. The owner approved
the R2 direction; the five R3 additions are proposed voicings awaiting listening.

## R4: floor-tom refinement (2026-11-09)

User requested more thump and resonance. The candidate changes only the floor-tom source
recipe: TUNE .06, DECAY .98, TONE .38, CLICK .055, DROP .22, PUNCH .60. Gain .34 and velocity
.88 stay fixed. SKIN's verified TOM implementation maps these to a lower modal fundamental,
longer T60, stronger settling pitch bend and less click/upper-mode energy. Shared recording
and board remain unchanged. The addition uses existing SKIN DSP, with no new solver/cache
requirement or real-time latency claim. Baseline source commit remains 7ccdbe713f3ec502e2978674c1820cf15d623770
plus the local BRINE build. Eight R4 clips compare old/new, isolated/in-groove, before/full chain.

Why it is built this way: lengthen the drum's own resonance, use the pitch fall for thump,
soften click rather than simply boosting level, preserve the older recipe for an honest A/B.
The long tail is the sonic risk, especially after spring/amp; listening remains the gate.

R4 measurement (actual Kotlin render, Python WAV energy check): before-pedal 200–600 ms
energy divided by 0–100 ms energy increased from .00547 to .20492. Peaks stayed .35370 and
.35498. This verifies added sustain relative to the attack, not perceived thump or approval.
The comparative automated test requires at least 1.5× this ratio and checks deterministic,
finite, bounded, low-DC output in isolation, in the groove, and through the board.

R4 validation: focused tests and generator exited 0 (13 tests; 68 comparisons). All 60
previous raw/matched pairs remain byte-identical. All 136 WAV hashes and recipes verify;
the embedded page script parses. Browser QA and the full repository suite were not rerun.

## R5: cymbal completion and kit test export (2026-11-09)

User authorized the next cymbal/balance/kit round. Gesture: closed/half/open metallic strikes
and crash accents → approved shared recording → spring → modulation → amp. Landing: SKIN
recipe family in BrineCymbals, not a new registered engine. SKIN and THUMP are the nearest
siblings; reuse of SKIN retains the existing material character and its oversampled filters.
No new external source code, references samples, board macros or settings version changes.

Half-open hat: HAT_OPEN TUNE .10, DECAY .18, TONE .20, RING .22, PUNCH .35; source gain .48.
Dark ride: RIDE TUNE 0, DECAY .48, TONE .18, SIZZLE 0, PUNCH .32; 90% ride + 10% short STICK;
source gain .34. Thin crash: equal-weight RIDE banks at TUNE 0/.28/.65, DECAY .88, TONE .72,
SIZZLE .08, PUNCH .20, 6 ms onset bloom; source gain .42. Choked crash: same attack, source
cut at 170 ms with the existing 6 ms cosine release. All constants are audition shapes.

Event.cymbal is optional and null for prior renders. BrineKitExpansion.renderEvents substitutes
only the new source buffers; existing pickup filtering, gain, hat choke and finish remain.
HAT_OPEN identity makes half-open hats respond to the existing closed-hat choke. Ride/crash
use RIDE identity, so closed hats do not silence them. R5 phrase variants substitute the
approved R4 floor tom. The alternative balance multiplies cymbal inputs by .75; every other
event stays unchanged. The signal-dependent bus can still affect final mix balance.

The audition adds 14 clips (two grooves × three states and four single-hit pairs) to 68 prior
clips. The complete recipe constants and event clocks are recorded in the manifest. Source
rendering remains finite/offline; all sources reuse oversampled SKIN. Three crash banks cost
more source work than a single ride, cached as WAVs on the audition page. Existing render_ms
continues to measure board-only time; no phone-interactive latency claim is made.

MPC export uses the verified ArrangedPad → KitAssembler.assembleArranged →
Mpc3Exporter.exportTrack(mpc2Twin=true) path. Two programs each contain 13 named one-shot
pads, native levels, -3 dB mixer gain, hats mute group 1/crashes 2/ride 0. No fake PadRecipe
is assigned: source provenance and the audition manifest identify rendered prototypes.
Preflight runs before and after export; sample bytes/formats, names, groups and one-shot
flags are checked. Baked wet hits have independent processing state; hardware choke stops
the complete sample including the return. Audition phrases keep a shared bus and choke
upstream, so the two playback contexts are intentionally disclosed as different.

Why it is built this way: reuse the approved recording and tom; place half-open decay between
existing hats; add restrained stick definition to ride; broaden crash without white-noise
hiss; use a balance A/B and a test kit to judge the real musical trade. Sonic risk: sustained
cymbal wash masks the snare through spring/amp. Listening and MPC hardware load remain gates.

Initial balance measurements (Kotlin raw rendered WAVs, Python RMS over the entire exported
file including tail): hat full-chain peak .4490, reduced-cymbal peak .4481; ride full-chain
peak .4484, reduced-cymbal peak .4482. RMS changes .0517 → .0509 for hats and .0552 → .0537
for ride. No mix overload is indicated; perceptual masking and preferred balance require
listening. Isolated cymbal peaks before pedals range .1012–.1490, with no individual matching
applied to kit export. The page's Matched mode remains available for timbre comparison.

R5 validation: focused tests + audition/kit generation exited 0 (17 tests, 82 comparisons).
All 68 prior raw/matched pairs are byte-identical. All 164 WAV hashes and recipes verify.
MPC export preflight passed in both staging and exported folders; 26 PCM24 mono 44.1 kHz
samples retain their bytes. The independent `experiments/brine/verify_mpc.py` reads both
ACVS MPC 3 payloads and MPC 2 XMLs, checks sample references, one-shot flags and groups, and
verifies ZIP integrity. Embedded audition JavaScript parses and preserves the notes key.
Browser QA is unavailable in this managed session; full-suite and MPC hardware tests remain
unverified. Josh approved the earlier recording direction and resonant tom; R5 cymbals and
balance preference await listening. No hardware acceptance or perceptual match is claimed.

## R6: listening-driven cymbal repair

Heard by Josh: choked crash resembles static and ride has no ride-like resonance. R5 source
inspection confirms continuous noise excitation rather than a finite impact driving persistent
modes; source placement was also predominantly above 3.2 kHz, where the recording shelf and
bandwidth reduce definition. This is the design diagnosis, not a claim of assistant listening.

R6 lands in BrineMetal, a source helper under the existing audition/kit, not a registered
engine. Verified Modes.ring API supplies modal filtering; SKIN remains the legacy comparison
and hat source. Impact → inharmonic free decay → approved recording → spring → phaser → amp.
34 ride modes: eleven three-mode detuned clusters and one bell contribution. 56 crash modes:
a denser inharmonic series. Frequencies/weights/T60s are provisional shapes, explicitly emitted
in the manifest. They are not inferred physical measurements. Noise exists only in 1.2 ms ride
and 4 ms crash contact, with an initial impulse; the sustain is entirely resonator response.

At 4× rate, mode drive is compensated by sin(theta) to remove Modes.ring's accidental inverse
frequency response. Band-limit, decimate, normalize the modal mix, remove DC, fade/limit; then
use existing pickup and recording path. Immutable lazy templates are cached once per voice
revision; callers receive copies. No per-hit bank recomputation or phone latency claim.
Choked crash shares the full-crash source until 230 ms, then a 25 ms cosine damping window
ends vibration. Source gains .18 ride/.25 crash account for midrange gain through the existing
recording contour. These constants are candidates awaiting ears, not calibrated hardware.

Event.metal defaults false, preserving all R1–R5 renders. Only revised cymbal events select
the new source, and the floor tom/hats/drums remain unchanged. 16 added comparisons bring the
page to 98: old/new isolated ride/crash/choked crash × before/full, plus two revised grooves
× before/full. The updated MPC test programs use the new ride/crash pair at A11–A13. The same
baked-tail and independent-processor limitations documented for R5 still apply.

Why it is built this way: impulse-excited ringing addresses the missing mechanism rather
than increasing reverb; slightly detuned modes supply metallic movement; lower ringing bands
survive the approved dark recording; slower damping lets the crash establish itself; old/new
clips preserve an honest listening gate. Main risk: a modal cymbal can become too bell-like.

R6 voicing correction before publication: the first modal render concentrated 91.8% of the
ride's 30–600 ms Welch power in 600–1,200 Hz after recording, a warning for excessive bell/body
character. Final modal weights additionally use min(12, (Hz/1600)^2.4) to compensate the dark
recording emphasis and retain upper-metal shimmer. This is an empirical voicing curve, not
a physical cymbal law. The final manifest carries it alongside the unscaled modal table.

Final R6 WAV measurement (`experiments/brine/measure_cymbal_revision.py`): ride tail energy
300–1,000 ms / attack energy 0–100 ms is 2.01993 vs R5 .01352. Ride 600–1,200 Hz power is now
15.42%, with 37.78% at 2.5–5 kHz and 19.73% at 5–12 kHz (Welch, 30–600 ms window), avoiding
the first candidate's low-mid concentration. Full-crash tail/attack ratio is 1.02718 vs
.17197. Choked crash has zero energy in the 300–1,000 ms before-pedal window after its source
stop and short room response. These verify sustained ring, broader metal balance and damping;
they do not prove that the cymbals sound convincing. Measurements are saved in the audition's
metal-comparison.json. Final isolated peaks .15603 ride/.14020 crash remain below the ceiling.

Final R6 validation: focused Kotlin tests + generation exited 0 (21 tests, 98 comparisons).
All 82 preceding raw/matched pairs remain byte-identical. All 196 WAV hashes/recipe paths
verify. The independent spectral check rejects a ride with >50% low-mid concentration or
<20% upper-metal power; final files pass (the discarded first candidate would fail both).
MPC 2/3 references, groups, one-shots, format/preflight and ZIP integrity pass for 26 samples.
Embedded page script parses. Browser playback, full repository suite and MPC hardware remain
unverified. R6 awaits the user's listening verdict; no cymbal realism claim is made.

## R7: benchmark against recordings before another listening claim

Josh's feedback: R6 remains far from real cymbals; find examples to compare and tune against.
The previous sparse-mode and noise-bank iterations were not perceptually validated. Tests
for finiteness, tail energy or broad band balance were never sufficient evidence of realism.

Primary reference sources (accessed 2026-11-09):
- https://versilian-studios.com/virtuosity-drums/ — CC0 contemporary jazz kit, Austin McMahon
  performance, Versilian Studios / Karoryfer Samples. The publisher identifies the mid mic
  as a stereo ribbon with gently rolled-off highs. Repository read/download pinned to
  9f04cf9a734527edfbb0a4eee1f674e45bbf71bc; license verified at that revision.
- https://freesound.org/people/ankealtd/sounds/696202/ — CC0 hand-choked 16-inch extra-dry
  thin crash. Analysis uses the public HQ MP3 preview (not the original 96 kHz WAV). The
  author describes mixed close/distant microphones and historical tail edits.
- https://freesound.org/people/ankealtd/sounds/696204/ — alternate hand-choked take, withheld
  from fitting. Virtuosity's normal ride velocity-2 take-2 is also withheld.

Eight examples were fetched for context (normal/soft/bell/flat ride, normal crash and two
hand chokes). The normal ride, normal crash and first hand choke are fitting targets; the
bell/flat articulations are not silently treated as a normal ride. SOURCES.json records all
URLs and hashes. No restricted Sampling+ example is redistributed or used in the fit.

Mechanism diagnosis: the reference ride has dense evolving spectral energy rather than a
few isolated long modes; the reference choke's band balance changes through damping, rather
than remaining fixed under a global cosine stop. R6 bare source flatness over 1–10 kHz,
100–500 ms was 0.000000572 for ride vs reference .20573; R6 choked crash .58444 vs reference
.08780. This explains why adding ring alone was not a useful acceptance criterion.

R7 is reference-derived spectral resynthesis, not a universal physical cymbal model. Onset
alignment is the first sample exceeding 1.5% peak, retaining 44 samples of preroll. Reference
WAVs are mono 44.1 kHz. Analysis uses 2048-point Hann windows/256 hop; power is Gaussian-smoothed
(.45 frequency-bin / 1 time-frame standard deviation), square-rooted, then sampled at 21
fixed time knots. Bins below 100 Hz and above 18 kHz are excluded. Normalized magnitudes are
rounded to seven decimal places. Original phases and PCM are discarded from runtime data.

Runtime generates new deterministic random phases per Hermitian FFT frame, interpolates the
measured magnitude snapshots, inverses and Hann-overlap-adds them. It starts with a 2 ms fade
and ends with a 50 ms fade, normalizing peak to .9 before the existing recording path. This
linear band-limited stage runs at 44.1 kHz; nonlinear board stages retain their established
oversampling. Three lazy source caches avoid repeated profile parsing and synthesis. There
is no claim of phone-interactive cold-render latency. Profiles occupy about 0.5 MB as JSON.

The optional renderEvents sourceOverride leaves all previous event paths untouched. R7
reference and fitted synth use identical gain/pickup/recording processing and full-board
settings. Source-only pairs precede processed pairs so processing coloration cannot be
mistaken for source quality. Two alternate recorded takes are explicitly marked as held-out.
The MPC test kit uses the new synth outputs, not the reference audio. CC0 reference audio
is published only as clearly credited comparison material on the audition page.

Measured final Kotlin source results (reference → R6 → R7):
- Ride centroid: 4559 → 4950 → 4691 Hz; 90% energy time: 1.146 → .243 → .881 s;
  spectral flatness: .2057 → .000000572 → .2120.
- Hand choke centroid: 4570 → 7437 → 4546 Hz; 90% energy time: .264 → .155 → .266 s;
  flatness: .0878 → .5844 → .0923.
- Full crash centroid: 2724 → 7341 → 2766 Hz; 90% energy time: 1.097 → .188 → 1.146 s;
  flatness: .1169 → .00313 → .1090.

These are agreement with selected training recordings, not independent perceptual validation.
Held-out ride: 5238 Hz / .748 s / .1918. Held-out hand choke: 4932 Hz / .312 s / .2291. The
variation between real takes matters; matching one does not make the synth generally correct.
The report and spectrogram figure are generated from actual Kotlin WAVs. The model can blur
transients or lose coherent beating because phases are newly generated; listening is still
required. Recordings include microphone/room coloration and codec/tail-edit limits.

Why it is built this way: use recorded exemplars as a target; retain a dense evolving spectrum;
discard original waveform phase to keep a synthesis path; learn band-dependent choke shape;
compare equally processed recordings and synth. This replaces unguided modal tuning with a
transparent measured reconstruction and audible references the owner can judge directly.

R7 validation: focused Kotlin tests and complete audition/kit generation exited 0 (24 tests,
125 comparisons). All 98 preceding raw/matched WAV pairs are byte-identical. All 250 WAV
hashes and recipe paths verify. Training-reference metric guards pass; held-out metrics are
reported without being tuned to. MPC 2/3 program/sample references, one-shot flags, choke
groups, preflight and ZIP integrity pass. The test kit contains 26 synthesized WAVs and CC0
source credits. Embedded page script parses; ended clips now restart when switching A/B.
Browser QA, whole-repository tests and MPC hardware playback remain unverified. No assistant
listening or perceptual acceptance is claimed. Real reference recordings and candidate synth
are deliberately separate cards, including identical-processing controls.

### R8 implementation addendum
See docs/BRINE.md R8 for the performance recipe and exporter contract. Six-bar balance
candidates preserve approved sounds. Snare/ride use three velocities and two takes; the
ride touch law is proposed, phase seeds are deterministic. Four MPC programs expose the
takes separately with velocity layers, not automatic round robin. Hardware and listening
approval remain pending; no claim of measured soft/hard cymbal excitation is made.

### R9 cymbal reset, 2026-11-12
Listener rejects current metal as overbearing/muddy. See docs/BRINE.md R9 for measured
recording-contour buildup, the source-specific inverse body contour, and optional smooth
shorter decay. Compare unchanged current, contour-only, and contour-plus-decay at fixed
groove playback gain. Retain recorded references. R8 kit stays explicitly labeled previous;
R9 candidates are audition-only. This supersedes treating R7/R8 cymbals as settled.

### R10 ride refinement
Listener selected R9 FOCUSED but requested more ride ring. New RING candidate changes only
ride additional T60 1.8→3.2 s; inverse body contour, first 35 ms, crash/choke and board remain
unchanged. FOCUSED remains a deterministic comparison. See docs/BRINE.md for scope.

### R11 listening acceptance and export length
R10 JSON notes keep the ring ride and R9 focused crashes, requesting shorter isolated
crash/choke files. Trim only their final full-chain exports below peak -90 dB with 120 ms
padding and a final 10 ms fade. Source and effect DSP remain unchanged. Notes are preserved
in docs/listening/BRINE-R10-notes.json; see docs/BRINE.md for verification.

### R12 complete length audit
Apply R11's conservative post-board trim to every synthesized audition preview, retaining
fixed group gain and onset timing. Reference recordings lose only verified appended zeros.
Audit previous MPC WAV lengths separately; do not imply their old cymbals are current.

### R13 current MPC export
Rebuild four 13-pad MPC programs using selected RING ride / FOCUSED crashes and shared
post-render tail cleanup. Ride retains two deterministic phase takes and three velocity
layers with intensity driving recording; snare layers remain unchanged in tone. Native
program frame ranges must match the new shortened WAVs. See docs/BRINE.md for rationale.
