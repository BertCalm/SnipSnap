# PATIO performance kits

Current release: three genre-inspired starter kits (Cumbia, Samba and Latin dub),
18 pads each, three velocity levels and four cycling takes per level. B01 is the
authored hand-drum roll; B02 is the cuíca-inspired friction voice accepted by Josh
on 14 November. The riser is retired from the current kits; old recipes replay.
Both guacharaca pads remain available individually and are omitted from grooves.

Generate the current kits and audition with:

```sh
./gradlew --no-daemon :shell:generatePatioPerformance
```

Output: `testkit/patio-performance/`, including native MPC 3 ZIPs, source kit
metadata and a compact listening page. Native MPC 3 and SFZ support the combined
velocity/cycle grid; no equivalent MPC 2 XPM is claimed. Hardware loading and
browser playback remain unverified until recorded below. The ordinary patch
editor does not edit these custom source recipes; they replay through
`PatioPerformance.fromJson`/`render` and the explicit export sidecar loader.

The sections below retain the dated study history and its original limitations;
they do not describe the current kit unless marked as a later delivery update.

## Original first listening study

PATIO is a sixteen-pad Central/South American-inspired percussion study with a kick, snare,
closed hat and open hat, followed by tape wear and an amp in that order. It is not yet a factory
engine or a calibrated recreation of the named acoustic instruments.

Design: [engineering specification](superpowers/specs/2026-11-07-patio-kit-design.md).

## Run

```sh
./gradlew --no-daemon :synth:test --tests '*PatioStudyTest' --tests '*DeterminismTest.PATIO*'
./gradlew --no-daemon :synth:generatePatioAudition
./gradlew --no-daemon :synth:generatePatioAudition \
  -PpatioReplay=testkit/patio-audition/recipes/scrape-long.json \
  -PpatioReplayWav=testkit/patio-replay.wav
```

Open `testkit/patio-audition/index.html` for level-matched dry/tape/amp comparisons.
`native/` retains native levels, `recipes/` stores complete experimental recipes, and
`metrics.csv` contains actual Kotlin measurements. `testkit/patio-audition.zip` packages
the page, WAVs, recipes and `SnipSnap Patio Study/` MPC XPM folder.

The XPM is a sample kit. Its study JSON is replayed by the generator, not by the app's
patch editor. Source metadata links to sidecars copied into the program folder. No new
engine is registered and existing rack order remains unchanged. No Android SDK is present.

## First-build limitations

- SKIN supplies the caixa-inspired snare and hat pair; no explicit string/cymbal collision model.
- TERRA supplies hand drums and bells as initial approximations. Alegre open/slap share tuning.
- Cajón uses TERRA BOX with wires disabled.
- Marimba uses TERRA TUNED_BAR's buzz proxy; resonator-driven charleo is still outstanding.
- Guacharaca and guache use deterministic finite contact schedules, not particle simulation.
- Tape saturation is a bounded static approximation, not a magnetic hysteresis model.
- Pad effects have independent state; the shared-chain roll call demonstrates the alternative.
- No listening verdict, instrument-specific spectral calibration, velocity zones or hardware load yet.

## Validation

Measured on the cloud JVM, 2026-11-07, using a local Temurin 21 JDK:

- Seven `PatioStudyTest` tests and the PATIO `DeterminismTest` canary passed (8 total).
- All sixteen complete study recipes reproduce their processed float arrays exactly after JSON replay.
- Texture and processing corners remain finite, bounded and cleanly ended; controls change the audio.
- The tape-order test distinguishes tape-before-amp from the reverse order and checks input immutability.
- At a 5003 Hz sine, WEAR=1 and WOBBLE=0, the selected fifth/seventh folded components measured
  -74.89/-80.59 dB relative to the root at 4x; the 8x reference measured -77.89/-72.79 dB.
  This is a selected-harmonic probe, not proof of alias freedom for every input or modulation setting.
- The generator completed and wrote 51 native clips plus 51 matched copies, 16 replay recipes,
  and a 16-pad XPM kit. XML parsing, all exported mono/44.1 kHz/24-bit WAV headers, HTML asset links
  and ZIP integrity were checked. No MPC hardware load is claimed.
- Warmed source-plus-processing medians (two warmups, median of five runs per pad): **6.56–78.73 ms**.
  These are cloud timings, not phone measurements. Processed pad sample peaks were at most .824;
  absolute DC was at most 0.00000193. Dry source levels retain their existing renderer contracts.
- The broader `./gradlew --no-daemon test --fail-fast` gate was launched separately; result pending.

The cloud initially had a JRE without `javac`. Validation used a downloaded local JDK with the
environment's existing Java CA trust; repository build settings and TLS verification were preserved.

## Revision two: contact studies

Josh's first listening report: “Mostly sounds like basic drum machine sounds.”
The original sixteen-pad kit is retained for comparison, not accepted as the target.

### Fix now

- [A] Five regional hand-drum slots used `COMPOUND_MEMBRANE`; open/slap differed
  mainly in preset parameters. The model reuse passed replay checks but did not
  establish the requested instrument identity in Josh's listening.
- [A] The scraper/shaker modal rings were too prominent a design choice to accept
  without a dry comparison. Revision two shortens their resonator tails to 9/4 ms
  and gives more weight to surface contact, with slower scraper rib crossings and
  two seed-collision clouds per gesture.
- [A] The first marimba had contact-noise buzz. The new bar drives a separate air
  resonance, then a smooth amplitude threshold generates a buzz that stops as the
  cavity decays. This is a feedforward analogy, not a charleo membrane simulation.

### Try next: five bounded studies

`PatioCharacter.render` is a separate experimental renderer, not a replacement of
version-one recipes. Its OPEN and SLAP use different finite contact patterns and
modal damping. There is no pitch-sweep oscillator. Ratios for the hand head follow
an ideal circular membrane approximation; gains, damping, finger timings, scraper
motion, seed-cloud shape, and bar/cavity coupling are authored hypotheses.
Nearest siblings are TERRA and SKIN. This remains a source study rather than a
registered engine because those already supply the broader drum architecture.

Signal: finite contact → damped body modes → contact/body balance (or bar-driven
cavity and threshold buzz) → 4× band limit/decimation → DC removal/level/tail →
existing PATIO tape → amp. The five revised dry/wet pairs sit above the old kit
on the audition page. The downloadable MPC program still uses version-one sounds.

Controls are `tune=.5`, `touch=.6`, `hold=.45` in this audition; seed is
`Dsp.seedFor("PATIO_CHARACTER", voice)`. TUNE moves root/body resonance, TOUCH
changes contact duration or density and buzz threshold, HOLD extends gesture or
body decay. HOLD is finite. Cost is bounded to eight body modes, at most 1.2 s at
4×, with no solver or precompute cache. Target: under 100 ms per study on the
cloud JVM; actual warmed medians are in audition `metrics.csv`.

### Why it is built this way

- Hand contact changes excitation and damping together, so a slap can lose its
  low body earlier than an open stroke instead of merely being brighter.
- Short resonances and noisy collisions let the scrape and shaker expose their
  movement rather than sustain a small set of pitches.
- Cavity-driven buzz follows the bar's energy, so it can end before the note.
- Old/new dry comparisons keep pedal coloration from hiding a source failure.

### Disclosed, not fixed

No acoustic instrument recordings were fitted; regional authenticity is unproven.
The kick, caixa, hats, other drum presets and bells have not been revised in this
round. The effect chain is unchanged. No revised listening approval is implied.
The revised renderer is not registered in the Android picker or v1 recipe parser.

### Verification ledger

Reviewed repository base `cc47036c43e36d3eca78bd6e70cb15994b08d469` plus local PATIO
changes. Measured test and render results are recorded after generation below.
Only Josh has heard the original; revised source character remains a hypothesis.

Targeted Kotlin run passed with exit 0: 12 tests (three character probes, seven
v1 study tests, two determinism canaries), including 40 new control corners.
The same command generated five revised dry/wet pairs. Dry render medians after
two warmups and five timed runs: 6.736–9.329 ms on this cloud JVM. Revised dry
peak maximum .99, absolute mean maximum .00009104. The ZIP integrity check passes.
Full `./gradlew --no-daemon test --fail-fast` was launched separately; no complete
suite pass is claimed. Browser playback was not verified in this environment.

## Revision three: alegre open and slap

Heard ledger: Josh reported the second pass closer overall, then clarified that
alegre remained “Blah” while the other revised voices were better. This pass
changes only the two hand strokes, in a new `PatioHand` study. The previous
`PatioCharacter` implementation and its rendered comparisons remain intact.

### Fix now [A]

The previous open stroke's broad 2.3 ms force pulse suppressed upper head modes.
Its body had no separate air/shell response. The slap's texture was concentrated
in very short finger pulses, leaving the low modes to define most of the sound.
Its full-file centroid was 358.99 Hz; open was 238.05 Hz (previous Kotlin metrics).
Those numbers support a limited spectrum, not a diagnosis of authenticity.

### Built hypothesis

The gesture is palm/finger contact → head and brief skin texture → hollow air
response for open, or choked body for slap → finite decay. Twelve head modes now
use compensation for `Modes.ring`'s inverse-sine frequency gain. A close upper-mode
pair supplies modest beating; head motion drives three air/shell resonances in a
feedforward path. Two broad noise bands follow a short hand-contact envelope.
Slap gives the high band more weight and much shorter modal damping; open retains
head and cavity after its surface texture ends. There is no pitch sweep or sample.

This is still a study alongside TERRA and SKIN, not a new registered engine.
The shared modal/filter infrastructure is adequate; the missing part was the
contact/body relationship. All weights, doublet spacing, shell ratios, skin bands,
contact timing and damping laws are authored shape constants, not measurements
of an alegre drum or a simulation of hand mechanics.

Controls: TUNE (.5 default) maps head root 150–310 Hz; TOUCH (.6) narrows palm and
finger contacts, raises the upper skin band, and changes skin/head balance; HOLD
(.45) changes finite head/cavity decay and render duration. Same tuning is used
for both strokes. No loops, UI registration or v1 recipe migration are introduced.
`PatioAuditionGenerator --hand OPEN|SLAP tune touch hold output.wav` replays dry
strokes with their stable default seed. Manifest records renderer/version/settings.

Signal: contact pulses → head modes → air/shell response, mixed with finite
band-filtered skin contact → 4× lowpass/decimation → DC removal/level/tail → existing
tape → amp. Modal poles are damped, band filters use positive Q, there is no
feedback between the layers and no nonlinear waveshaper in this source model.
Fixed work: 15 resonators, two filters, at most 1.2 s of 4× audio; no solver/cache
required. Target under 100 ms on the cloud JVM; measured cost is recorded below.

### Why it is built this way

- Palm and narrow finger pulses excite different parts of the same head, retaining
  upper-head detail that the previous broad pulse filtered away.
- Air and shell continue responding to the head after contact, giving the open
  stroke a separate hollow body rather than another isolated sine tail.
- The slap's brief high-band contact and rapid body damping create a crack/choke
  contrast. Its noise envelope ends quickly to avoid becoming a snare tail.
- Soft-to-hard sequences change both tone and level. Dry A/B comparisons expose
  the source before tape and amp can mask a weak result.

### Measured / not yet heard

Targeted Kotlin checks passed with exit 0 (17 tests across hand, character, study,
and PATIO determinism canaries). Sixteen new hand control corners remain finite,
bounded, low-DC, and end at zero. In the first 40 ms, measured spectral centres
were open 433.64 Hz and slap 2679.06 Hz. Energy fraction after 100 ms was .01868
for open and 1.18e-9 for slap. These prove a larger attack/decay contrast; they do
not prove the intended acoustic identity. Controls and seeded replay pass.
The new renders await Josh's listening. Browser playback remains unverified here.

Generated dry render medians (two warmups, five measured runs): open 19.077 ms,
slap 37.102 ms on this cloud JVM. Full-file centroids were 388.05/2629.89 Hz
respectively; the 40 ms figures above describe only the attack. Peak max .99,
absolute mean max .00000887 for the two new dry files. All ten previous revision-two
WAVs compare byte-identical against the deployed page. ZIP integrity and local
page links pass. Full regression was launched separately; it is not a reported pass.

## Revision four: reference-guided alegre and guacharaca

The user supplied screen recordings of an alegre and a guacharaca performance.
Those recordings were analyzed locally, not sampled into these renders or uploaded
to the audition site. Their compression, microphone response, other sounds and
stroke overlap mean the results are guides rather than calibrated instrument data.

Measured reference clues: recurring alegre spectral peaks around 230, 370 and
485 Hz; sampled 80 ms attack windows had median .3% energy above 1.5 kHz, maximum
5.8%. The previous synthetic slap had 36.2% in a comparable first-80-ms window.
Guacharaca whole-clip Welch energy was 86.57% in 1.5–10 kHz; the earlier dry
scraper had 93.84% below 1.5 kHz. The measurements use different windowing for
drums and continuous scraping, not interchangeable centroid or pitch estimates.

`PatioHand.render(..., reference=true)` selects a separately versioned reference
branch; default false preserves the previous model. Default TUNE centres the
head at 230 Hz, with prominent modes near 370 and 485 Hz. A smooth additional
loss envelope after contact models the resting hand's damping as an analogy;
it only removes energy. Slap emphasizes upper body modes with much less skin
noise. The previous sources remain in the page for comparison.

`PatioScrape` adds FORWARD and RETURN. Three offset prong contacts produce short,
bipolar noisy pulses; broad 2.9/3.4 and 6.2 kHz bands with additional low rejection
replace the old low modal ring. The return stroke is shorter and differently
coloured. Speed, prong offsets, pressure and duration laws are authored hypotheses,
not recovered physical rib spacing. TOUCH changes pulse width and event density;
HOLD changes finite duration. All states are seeded, rendered at 4× and conditioned
with the house output sequence. No nonlinear feedback or iterative solver.

The audition has four prior/new dry/wet comparisons, plus an authored two-bar
100 BPM groove using these voices, the retained shaker/bar and original kick,
snare/hats. Groove processing is shared tape then amp. This is not a traditional
rhythm claim or a new MPC kit: the download explicitly retains the original
program alongside reference-* WAVs. Djembe remains outside this pass.

### Why it is built this way

- Stronger head resonances and less noise address the supplied alegre spectrum,
  rather than assuming a convincing slap must be exceptionally bright.
- Hand loss acts after impact, making open/slap contrast about damping as well
  as excitation. This is a controlled approximation of the observed gesture.
- Bipolar prong contacts avoid the DC-rich excitation that made the old scrape
  mostly low frequency; broad bands keep the texture from becoming a pitched bell.
- Separate dry and shared-effect groove renders let Josh judge both identity
  and how the instruments interact with tape and amp.

Verification and remaining limits: source checks and measured output figures are
recorded below. The new voices are hypotheses awaiting listening, not authentic
replicas. No app picker, v1 recipe format or original kit replacement is implied.

Final targeted Kotlin run passed, exit 0: 20 tests, including 24 reference-source
control corners, seeded replay, loss-envelope contrast and spectral-direction
checks. Previous 67 listening WAVs were byte-identical before the final reference-
only skin-balance adjustment; legacy code path remains covered by existing tests.
Final 80 ms high-band energy (>1.5 kHz): open .063%, slap .166%, versus 36.2%
for the previous slap. Guacharaca 1.5–10 kHz Welch energy: forward 87.08%, return
86.78%. These are limited spectral comparisons, not listening acceptance.
The source render target remains under 100 ms; this round has no separate warmed
benchmark, and reference rows' timing field is 0 (not measured), not zero cost.
ZIP integrity and all page asset links pass. Browser playback is unverified.
Full regression is separate and not claimed as passed.

## Scrape direction revision (model 2)

Heard: Josh reported forward and return did not sound different. Review found
identical pressure/speed laws, a .72 duration multiplier and a modest filter shift.
The tests proved unequal lengths, not a useful playing contrast.

PatioScrape VERSION 2 preserves FORWARD exactly and makes RETURN an authored
short catch: duration multiplier .44, fast pressure rise followed by exponential
release, rib-contact rate slowing toward the end. This is a deliberate difference
in playing gesture, not a claim that simply reversing direction creates it.
`modelVersion=1` still replays the previous forward/return pair. New checks compare
50% and 90% cumulative-energy times after audition level matching across three
HOLD settings, plus existing replay/corner/spectral checks. The generator emits
both old dry strokes and a forward-return sequence repeated three times; the
shared-chain groove uses the new return. Other voices are unchanged.

Why it is built this way: a pressure-envelope and contact-speed difference survives
level matching; the original small filter difference was insufficient in Josh's
listening. The return's early release and slower final catches provide a rhythmic
contrast while retaining the guacharaca's high-frequency contact bands. Authenticity
and whether the contrast is useful remain listening questions.

Targeted Kotlin run exited 0 with 21 tests. At default HOLD, energy90 after audition
level matching was forward .23726 s, return .03535 s; all three tested HOLD values
pass the timing contrast checks. This confirms a temporal contrast, not whether
it remains perceptually a scrape instead of a short tick. ZIP and page links pass;
browser playback unverified. Full-suite success is not claimed.

## Scrape weight and bite (model 3)

Josh requested more “oomph and pizzaz” and authorized a fuller source revision.
Model 3 gives the forward stroke an initial catch and accelerating rib crossings,
adds a fourth prong, and restores the return to .80 of forward duration with a
broad pressure envelope and decelerating contacts. Two broad shell bands at 850
and 1450 Hz add woody contact weight under the existing bright texture. Paths are
normalized before mixing so the body cannot dominate merely because its filter
has greater gain. No nonlinear source stage or new feedback is introduced.
The existing tape/amp receives the changed transient/body balance; its settings
are unchanged so the comparison isolates the source revision.

Why it is built this way: the forward catch establishes attack, accelerating
contacts add motion, and the return's sustained energy prevents the old tick-like
ending. Short broad body resonances add weight without turning the scraper into a
low pitched ratchet. These are authored playing gestures and shape constants,
not a claim that the source video establishes this exact behaviour.

Versions 1 and 2 remain replayable through modelVersion. The page's previous-dry
column now uses model 2; the alternating clip and groove use model 3. Earlier
model-2 timing tests explicitly select version 2, while a new check requires the
new return's 90%-energy time to exceed the old catch by 2.5× at three HOLD values.
Spectral checks require added body while retaining a centroid above 2 kHz.
All other source voices remain unchanged. Listening acceptance is pending.

Final targeted run passed with exit 0: 22 tests and audition generation. The new
return reaches 90% energy at .1773 s at default HOLD, versus .03535 s for model 2.
An initial body mix was too weak to lower the forward spectral centre; the final
mix raises the broad body contribution while both strokes remain above 2 kHz.
The old-vs-new page uses verified byte-identical model-2 WAVs as its prior column;
alegre, shaker and marimba comparisons also remain byte-identical. These checks
verify signal changes, not that the requested character is perceptually achieved.

## Processing audition: Pushed / Chewed / Overloaded

Josh approved the stronger scraper direction and requested more processing and
amp character. This pass leaves sources and groove arrangement unchanged and
adds three `PatioTreatments` recipes. Current chain remains the first comparison.
Each treatment uses the identical six-second dry groove and seed 729.

| Treatment | Tape input | Wear | Wobble | Amp drive | Sag | Tone | Cab | Extra loss |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Pushed | 1.7× | .55 | .25 | .72 | .28 | .62 | .35 | 0 |
| Chewed | 2× | .95 | .95 | .80 | .55 | .38 | .55 | .80 |
| Overloaded | 2.4× | .70 | .35 | .94 | .85 | .52 | .60 | 0 |

The current chain's DRIVE .35 is low on Valve's gain law. Valve normalizes its
input peak internally, so adding gain before Valve alone cannot increase its
breakup character. These recipes increase DRIVE directly and vary sag/tone/cab.
Tape input gain instead precedes tape saturation and its output protection.
Chewed additionally uses three smooth 70 ms losses at 1.02, 2.73 and 4.15 seconds
before the amp. These fixed performance edits are deterministic, not a simulation
of mechanical tape faults; the tape's original seeded loss remains active too.

Why it is built this way: Pushed emphasizes breakup without extreme transport
motion; Chewed emphasizes transport damage and darkening; Overloaded emphasizes
amp saturation and supply sag. All use the existing oversampled nonlinear stages.
Each listening file is matched using AuditionLevel (loudest 200 ms RMS = .03),
not integrated LUFS. Native files retain processor output. The download contains
treatment-* WAVs and the manifest records all numeric controls, order and seed.
The original MPC program is unchanged. This is a listening comparison, not a
claim that the treatments preserve all instrument identity or emulate one pedal.

Targeted Kotlin run passed with exit 0: 24 tests and generation. Treatment checks
cover deterministic replay, input immutability, finite/peak/DC/tail bounds, matched
level, pairwise differences after matching, silence, and the effect of the extra
loss schedule. Earlier source/groove listening WAVs are byte-identical. Browser
playback is unverified; a full-suite pass is not claimed. New treatment character
and instrument intelligibility still require Josh's listening.

## Chewed working selection and MPC program

Josh selected Chewed as the working default and authorized proceeding. The new
`PatioWorkingKit` VERSION 1 selects the reference alegre pair, model-3 scrape
return/forward, revised seed shaker and buzz bar. Other slots retain their study
sources. It renders every pad through Chewed using the base pad's deterministic
effect seed. A11 is now Guacharaca Return; A12 is Guacharaca Forward. Kick/snare/
hats remain A01–A04, with the hats sharing choke group 1.

The generator exports `SnipSnap Patio Chewed/SnipSnap Patio Chewed.xpm` with 16
baked mono WAVs, source/treatment provenance in working-kit.json, a processed-pad
roll call and individual listening files. The original study kit remains separately
available. `PatioAuditionGenerator --working pad-id output.wav` replays a selected
working pad. This is not the old PadRecipe JSON parser or an Android picker entry.

Why it is built this way: individual baked pads are portable to MPC without a
custom plugin; the shared-bus groove retains the exact processing Josh selected
for comparison. Those paths are deliberately identified because nonlinear mixing
and fixed dropout times do not commute with per-pad rendering. In short one-shots,
the three later Chewed loss events often lie outside the sample; the underlying
tape's seeded early dropout still applies. Pushed and Overloaded remain audition
alternatives. Hardware loading and balance of the new per-pad kit await listening.

Targeted Kotlin run exited 0: 25 tests plus generation. The working-kit test checks
all 16 rendered pads for replay, selected processing and finite/peak/DC health,
and checks the replaced scrape and alegre source paths. The new XPM parses; all
16 WAVs are mono 44.1 kHz/24-bit, working manifest selects Chewed, and ZIP integrity
passes. Hosted kit-only ZIP is a copy of the exported folder. Hardware loading
remains unverified. Full regression was launched separately, not reported passed.

## Chewed mix trim (working-kit version 2)

Josh found the scrape overbearing in the mix. Both guacharaca pad levels and
both groove-placement gains are now multiplied by 0.7079458 (-3 dB). Source WAVs,
Chewed settings and all other pad levels are unchanged. In the MPC program this
is an instrument-volume adjustment; in the shared-chain audition it occurs before
tape/amp so the changed balance feeds the bus naturally. Working roll call uses
the same selected pad levels. Solo clips remain audition-level-matched and are
not the place to judge this mix adjustment. Working manifest now records pad levels.

Validation: working-kit test plus generation passed with exit 0. Exported A11/A12
volumes are .205304 (previous .29), a verified -3 dB change. Other 14 pad levels
and all 16 sample WAV bytes remain unchanged. XPM volume fields agree with the
manifest; page links and both ZIP archives pass checks. Existing broad regression
was already running; this mix-only change does not claim a new full-suite pass.

## Core-kit voicing pass (working-kit version 3)

Josh requested tuning up the other kit sounds. An optional priority question was
left open; the stated default was kick/snare/hats first. Review measured current
Chewed energy90 at kick .162 s, snare .077 s, closed hat .029 s, open hat .269 s;
surdo .182 s, cajon .235 s, llamador/repicador around .06 s. These measure temporal
roles, not perceived quality. The lower drum overlap motivates a tighter kick
before revising the remaining support percussion.

`PatioCore` is a versioned preset family on existing SKIN, not a new engine.
Kick narrows the decay, increases beater and shell overtones, and uses less pitch
drop. Snare has a slightly higher head with more head/wire balance and longer
papery wire decay. Hats share a lower resonator tuning with tighter metallic
resonances; closed is slightly longer, open shorter. Exact source patch JSON is
stored in each working-manifest entry and replayed through Patches.

Why it is built this way: kick should mark the beat while surdo owns more of the
low tail; snare gains material response instead of a tiny noise tick; the paired
hats establish a metal voice beside the scraper without lengthening the open wash.
These are design hypotheses, not claims of acoustic caixa authenticity. The new
page compares old/new sources through identical Chewed with matched listening
level. The working export and groove use the revision. All existing mix gains,
including the scrape -3 dB trim, are retained.

Scope: only A01–A04 change in this pass. Surdo, llamador, repicador, cajon and bells
were reviewed but not revised; they remain the next voicing candidates. Approved
alegre, scraper, shaker, marimba and processing are retained. No picker integration
or new engine registration is introduced. Hardware/load and listening acceptance
of the four new voicings remain open.

Validation: 27 targeted tests and generator passed, exit 0. Chewed energy90 kick
.16234 → .12891 s; snare .07741 → .08626 s. Patch JSON replays exactly; old/new
remain numerically different after Chewed and level matching. Export check confirms
only A01–A04 WAVs changed, all pad levels preserved, previous-comparison clips
match the old release, XPM parses and ZIP integrity passes. This is not a claim
of heard improvement or a complete regression pass; browser playback unverified.

### Cleaner comparison and compact listening room (2026-11-08)

Josh reported harshness and mud and asked to remove the endless scroll. The landing
page now has two players and a selector for the groove, roll call, or one of 16 pads.
Earlier studies live in `archive.html`. Downloads distinguish the selected Chewed
kit from the Cleaner candidate; the selected kit is unchanged.

`PatioCleanup` version 1 is a recipe over existing oversampled tape and Valve.
It retains wobble .95, uses wear .78, tape input 1.35, drive .64, sag .22,
tone .52, cab .42 and dropout .60. Before tape, a 310 Hz bell (-3.5 dB,
Q .75) and 6500 Hz one-pole reduce congestion and edge. An 8000 Hz one-pole
follows the amp. Surdo/cajon attacks remain unchanged for 100 ms, then a
200 ms exponential envelope reduces their tails. Scrape mix trim stays -3 dB.
The shared groove contains neither low drum, so tail changes are judged in the
roll call or solo selector. Per-pad processing differs from shared-bus processing.

Why it is built this way:
- Lower nonlinear drive reduces newly generated edge while keeping tape motion.
- Removing low mids before saturation leaves more room for drum attacks.
- The separate candidate preserves the exact prior comparison and chosen kit.
- The two-player page keeps the listening decision visible on a phone.

Verified on base commit cc47036c43e36d3eca78bd6e70cb15994b08d469 plus local
changes: existing comparison WAV is byte-identical to the published version;
static asset paths, all 18 selection pairs, JS syntax and both 16-pad mono
44.1 kHz/24-bit downloads pass checks. Python Welch analysis of matched Kotlin
grooves measures about -1.4 dB at 180–500 Hz and -3.9 dB at 3–10 kHz.
These band measurements are not a listening judgment. Browser playback and MPC
hardware loading remain unverified. No acoustic reference audio was sampled.

### Supporting-pad refinement (2026-11-09)

Josh preferred the cleaner direction and authorized a focused pad pass. `PatioDetail`
version 1 changes only llamador, repicador and the two agogô source patches. It
uses the existing TERRA models and the unchanged `PatioCleanup` processor. Exact
patches are recorded in `detail-kit.json`, alongside the complete cleaner base.
All other pad samples, levels, tuning, choke groups and processing are retained.

Why it is built this way:
- Existing cleaner renders concentrate 90% of the supporting drum energy in
  18–20 ms and the bell energy in 28–40 ms; longer body decay gives these four
  voices more room to articulate their identity.
- Modest strike-hardness and position changes accompany the decay adjustment;
  no new high-frequency exciter or amp drive is added.
- The same Cleaner chain on both sides isolates the source voicing decision.
- A four-pad sequence is the first comparison, with solos and the complete kit
  available in the same selector. The previous mix comparison has its own page.

Measured from level-matched Kotlin WAVs with Python cumulative squared amplitude:
llamador energy90 20.2 → 44.5 ms; repicador 17.8 → 47.6 ms;
low agogô 39.5 → 90.0 ms; high agogô 27.8 → 76.0 ms. All previously
published cleaner WAVs remain byte-identical. Targeted tests verify deterministic
healthy output, exact patch serialization, longer energy decay, and unchanged
renders for the twelve untouched pads. These are synthetic voicing hypotheses,
not a claim of measured acoustic authenticity or a listening judgment.

The full local audition ZIP includes every revision. Hosting retains the previous
cleanup audition ZIP on its previous comparison page because the expanded full
archive exceeds the hosting per-file limit. The new page offers the complete
16-pad Detail kit and the previous Cleaner kit separately. Browser playback and
MPC hardware loading remain unverified.

### Articulation replacement for four supporting voices (2026-11-09)

Josh heard the Detail pass as lacking distinct character. The prior pass changed
mostly decay; its processed spectra still heavily favored low tones. Python Welch
analysis of the prior rendered WAVs found only 0.01% and 0.07% of llamador and
repicador power above 1 kHz, respectively. This is evidence of limited upper-body
content, not a numerical definition of musical character.

`PatioArticulation` v1 replaces those four source recipes, leaving `PatioDetail` v1
replayable. It is a small study renderer over `Modes.ring`, `Dsp` and `Tide`, not
another registered engine. The source is rendered at 4x with the house bandlimit,
decimation, DC removal, level and tail stages, then uses unchanged Cleaner processing.

Why it is built this way:
- Llamador uses a broad palm pulse, strong fundamental and short upper-body decay.
  Repicador uses three short finger contacts, a weaker fundamental, stronger upper
  head modes and a finite skin-contact burst. Their contact and body balances differ.
- Low agogô emphasizes a hollow mid mode and hand damping after 85 ms. High agogô
  uses a narrower strike, stronger inharmonic upper modes and a split mode pair that
  beats during the tail. They are more than differently tuned copies.
- Modal gain compensates the resonator's low-frequency bias so the intended upper
  weights are not silently lost. Noise is confined to a finite contact envelope.
- An equal-pitch test checks spectral/decay differences after Cleaner processing.
  Listening remains the acceptance gate; a test cannot establish musical identity.
- The same compact A/B compares the rejected Detail pass with new articulations.
  A dry-source toggle exposes whether the common effects obscure their differences.

Verified against base cc47036c43e36d3eca78bd6e70cb15994b08d469 plus local changes:
only four pads use the new renderer; the other twelve use identical Cleaner renders.
The new modal ratios, weights and contact timings are authored sound-design shapes,
not measurements of acoustic llamador, repicador or agogô. No reference audio was
sampled. Both earlier source sets, their exports and their comparison pages remain.

Measured processed spectral centroids (Python Welch, prior → new): llamador
211 → 207 Hz, repicador 294 → 564 Hz, low agogô 611 → 879 Hz, high agogô
863 → 1574 Hz. The muted supporting role stays low; the answering stroke and
bell pair gain different upper-body distributions. All 33 targeted tests exited
successfully. Node event stubs verified selector/dry-toggle paths, exclusive
playback event handling, reset and repeat; this is not browser playback testing.
All previous Detail processed WAVs are byte-identical. ZIP, XPM, WAV format,
static links and hosting size limits pass. The earlier broad repository suite is
still running; no full-suite pass is claimed. Browser and MPC playback are unverified.

### Lower hollow bell with a damped ring (2026-11-09)

Josh requested a slightly deeper hollow bell with a damped ring resonance.
`PatioHollow` v1 changes only agogo-low. It calls the existing articulation source
with pitch 490 Hz (previously 580 Hz, about three semitones lower) and explicit
`hollowRevision=2`. All earlier calls default to revision 1 and remain replayable.

Why it is built this way:
- More fundamental weight supplies depth without boosting bass on the whole kit.
- A small companion to the 1.48-ratio mode adds a softly beating ring.
- The main mid mode's RT60 is 0.50 s; hand damping begins at 100 ms with a
  60 ms loss constant. This lets the ring emerge before it settles away.
- Reduced upper-mode weights keep the bell hollow rather than sharp.
- The Cleaner chain and other fifteen pads are unchanged. The compact page opens
  to the old/new bell, retaining dry listening, supporting-voice and full-kit options.

These remain authored source shapes, awaiting Josh's listening judgment. No hardware
or acoustic authenticity claim is made. The previous full repository test run ended
with XML-report write failures after overlapping targeted runs; it is not a pass.
Future broad checks use a separate report and binary-results directory to avoid
shared output collisions. No test assertion failure was reported in that broad log.

Measured from Kotlin processed WAVs with Python Welch and cumulative squared
amplitude: dominant resonance 855.9 → 726.7 Hz; spectral centroid 879.0 → 776.1 Hz;
90%-energy time 79.6 → 107.5 ms. Only 0.0002% of new energy remains after 300 ms.
All prior articulation WAVs are byte-identical. The 35 targeted tests and generator
completed with exit code 0. Node event checks, static asset paths, ZIP integrity,
XPM parsing and the 16 mono/44.1 kHz/24-bit WAV formats pass. Browser playback and
MPC loading remain unverified. A full regression rerun uses isolated report outputs.

### Kit-in-context groove audition (2026-11-09)

Josh accepted a sparse/full/fill listening round. No pad renderer, processing
recipe, kit level or exported MPC sample changes in this round. `PatioGrooveStudy`
is an audition-only arrangement helper: 100 BPM, four bars per take. Sparse uses
kick, llamador, closed hats and alternating guacharaca directions. Full adds
snare, alegre, repicador, guache and bells. Fill keeps the full opening then removes
some texture for hand-drum replies and an open-hat lift in the last two bars.
These are authored patterns, not transcribed traditional rhythms.

Why it is built this way:
- It triggers the current Patio Hollow kit's individually processed samples so
  balance judgments apply to that download, rather than a different shared bus.
- Amplitude accents sit on top of fixed kit levels; no velocity-dependent source
  model changes or extra mix processing are implied.
- Open/closed hats share a choke group with a 2 ms fade before the next hat.
- The run-through places sections at 0, 9.6 and 19.2 seconds without inserted gaps,
  keeping one gain for natural density changes. Each standalone take matches the
  established loudest-200-ms bass-weighted RMS listening target independently.
- A single player and take selector replace the previous comparison on the front
  page. Prior bell and individual-voice comparisons remain linked separately.

Individual files are 10.6 seconds including one second for release; the run-through
is 29.8 seconds. Repeat replays the release too and is not a gapless-loop claim.
`patio-grooves.zip` includes native and matched-listening WAVs plus event data;
it is an audition bundle, not an MPC sequence file. The existing kit ZIP remains.
Targeted tests cover event boundaries, exact run-through offsets, reproducibility,
source immutability and hat choking. Full repository regression remains unverified;
the previous isolated broad run ended without a recorded completion status.

Verification: arrangement/choke tests and audition generation exited successfully.
A Python reproduction of the house level meter (including its gentle bass weighting
and 50 ms hop) measures 0.03000 for each listening take. Peaks remain below 0.24.
All current kit sample files are byte-identical to the published kit. Node event
checks verify take selection, section changes at 9.6/19.2 seconds, restart and repeat.
The groove bundle contains eight WAVs and the pattern descriptor; ZIP integrity
passes. Browser audio playback and MPC loading have not been verified.


## Performance kits — 9 November 2026

PATIO now ships as three starter-shelf recipes: Patio Cumbia, Patio Samba and
Patio Latin Dub. Each contains the sixteen core pads plus an authored hand-drum
roll (B01) and percussion riser (B02). Samba changes the processing, decay and
balance toward caixa/bells; dub changes processing and low-drum tuning/balance.
Cumbia retains the approved Cleaner/Hollow hard first take exactly.

Every pad renders three velocity zones with four distinct deterministic takes
per zone. Variations change source excitation/contact/settings; softer strikes
change both timbre and level. `PatioPerformance.Recipe` saves the genre, voice,
version and phrase length (`hold`). Individual hits remain finite. Roll/riser
HOLD changes authored phrase duration; these are finite sample phrases, not
live indefinite sustains. Melodic keygroups/chord tools and string STRUM do not
apply to this percussion kit family.

`ArrangedPad.velocityTakes` carries the grid into chain WAVs, KitStore, PadHit
and KitPreview. Native MPC 3 exports preserve all three zones and four-slice
cycles. `PATIO-source.json` accompanies the exported samples and is restored
by `PatioPrograms.reloadExport`; the generic MPC 3 importer does not reconstruct
this Slice Motion grid. SFZ export also preserves velocity and sequence regions.
No lossy MPC 2 XPM substitute is supplied.

Targeted tests verify deterministic recipe replay, distinct takes, velocity
levels, phrase length, approved hard-anchor preservation, shelf coverage, saved
kit equality, playback windows at velocity boundaries, native export fields,
exported WAV identity, SFZ cycling and identical source/export preview PCM.
The audition is generated from saved performance kits, with twelve hits per
pad demo (four soft, four medium, four hard). MPC 3 uses its own chromatic
clip note map, not MPC 2's legacy pad map.

Physical MPC loading, browser audio playback and a complete repository test
suite remain unverified. The downloadable tracks target MPC 3.

Delivery verification completed: the corrected all-genre delivery test and
`:shell:generatePatioPerformance` exited 0. All 54 pads produce audio through
the MPC 3 note map. All 57 listening WAVs are valid PCM16; the three native
ZIPs pass integrity checks and remain below the hosting file limit. Node event
checks cover all 57 selections, three downloads, links, restart and repeat.


10 November 2026: removed both guacharaca directions (pads 11/12) from the
Cumbia performance groove and its embedded MPC 3 clip. Scrape voices, solo
auditions and velocity/round-robin grids remain available. Samba and dub
patterns already omit these pads.
Verification: the audition generator exited 0; exported clip comparison
confirms exactly 32 removed scrape events and identical remaining events. All
exported sample WAVs are byte-identical. Page selection/link checks passed.


## Riser revision — 12 November 2026

Josh heard the initial riser as a mess. Version 2 removes the alternating guache
layer and uses only repicador strokes in four equal sections (2, 3, 4, 6 hits),
followed by a landing hit. Each strike is damped and gated before the next;
the complete phrase then passes through one processing chain. Drive, sag and
wobble are capped and dropout is disabled for this phrase. HOLD still selects
1.2–3.6 seconds of build; a short release follows the landing.

The intended result is an intelligible acceleration and ending. Listening
acceptance is pending. Three velocity levels and four source-varied takes per
level remain. Version 1 recipe JSON retains the old renderer; the fixed original
source seed keeps the other 17 pads stable under the new version.

Measured from the rebuilt Cumbia hard first take: RMS over successive build
quarters is 0.01729, 0.02687, 0.03569, 0.04946; peak 0.33796. This confirms
increasing energy, not a listening judgment. Targeted performance tests cover
all three genres' revised grids, recipe versions, dynamics, variation and tails.


## Cuíca replacement — 13 November 2026

B02 now holds a cuíca-inspired friction drum, replacing the riser. The gesture
is contact → rising pressure bend → slower release bend → short body tail.
A bounded ten-harmonic slip source and seeded friction noise feed fixed 720/1280 Hz
body bands; the pitch gesture spans roughly 155–455 Hz. This is an authored
acoustic analogy, not a validated physical model or recording of a cuíca.

HOLD selects 0.20–0.80 seconds of active motion plus release. Velocity changes
rasp, harmonic balance and level; four takes change contact roughness and bend
timing. Rendering is mono 44.1 kHz, internally 4× oversampled, band-limited and
decimated using the existing house pipeline. The existing genre tape/amp follows,
with drive/wobble caps and no dropout to retain the pressure gesture.

Performance recipe v3 adds the cuica ID; legacy rise IDs and v1/v2 JSON still
replay their original risers. The factory kit retains 18 pads, the hand roll on
B01 and three velocity zones × four takes on every pad. The cuíca is finite PERC,
not a melodic engine; it does not introduce a new engine registration surface.
The audition opens on its twelve-hit demo and also offers short/long examples.

Why it is built this way: continuous friction makes a different gesture from
the kit's struck skins; fixed body bands give the moving pitch a woody vowel;
controlled processing preserves that bend. The main listening risk is a voice
that reads as a synthetic whoop rather than a friction drum. Josh's listening
judgment is pending. A warmed dry render measured 22.29 ms in the Kotlin test.
Repository base: cc47036c43e36d3eca78bd6e70cb15994b08d469 plus the working PATIO changes.

Verification: targeted performance and all-genre delivery tests plus generation
exited 0. Exported packages retain all 12 cuíca slices and omit the replaced riser
WAV. The other 17 samples are byte-identical in every genre. All 63 audition
selections, download links, restart and repeat passed Node event checks.
Short/long processed examples last 0.345/0.945 seconds; no listening judgment
is claimed. Browser audio and physical MPC loading remain unverified.
Publication blocked: Sites now returns codex_auth Git access; its endpoint
returns HTTP 400 missing_account_user_id with the available configured identity.
The previous live page remains unchanged; a standalone offline audition with
all three downloads is packaged as PATIO-cuica-audition.zip.


## Release completion — 14 November 2026

Josh accepted the cuíca replacement. The approved audition and all three native
MPC 3 downloads are now published at https://patio-drum-audition.bertcalm.chatgpt.site.
The Sites credential issue cleared; deployment of site commit
`e487785b8427d479ea429c902257aa3910a9a644` succeeded. Page controls and all 63
listening targets passed the Node checks. Browser audio playback could not be
verified because this managed session exposes no browser-control tool; physical
MPC loading remains outstanding.

JVM regression is complete: the final reports across all nine modules contain
9,079 tests, zero failures, zero errors and zero skipped tests. The first broad
run found two stale starter-roster expectations (71 rather than 74 starters,
and an ordered ID list missing the three PATIO kits). Both were corrected;
the focused correction run passed, followed by the entire shell suite (2,235
tests, exit 0). The complete synth/XPM run also exited 0, including all 4,909
synth tests; the other module suites passed in the broad run. No tests were
disabled or filtered from those full module runs. Android compilation and
device tests were not run because this environment has no Android SDK.

The implementation and these verification results are committed on
`claude/patio-first-build`.


## Integration and hardware acceptance — 14 November 2026

Draft PR [#1109](https://github.com/BertCalm/SnipSnap/pull/1109) integrates PATIO
with default-branch commit `210c839802c2eafb7d84aaa9af109a5e6ec9b45e`.
The newer base already implements the velocity/take grid in `KitAssembler`,
including its first-hard-take consistency guard. PATIO uses that shared path;
the shelf now contains 129 starters. The 9,079-test result above applies to the
pre-integration branch, not this newer combined tree.

The current [acceptance gates](ENGINE_ACCEPTANCE_GATES.md) identify a concrete
MPC 3 limitation: chain WAV slice-map capture remains bench-blocked (HH1.4).
PATIO's tests prove saved windows and exported cycle metadata, but do not prove
that MPC firmware locates and cycles every take. Hardware may play only the
first take of each velocity zone. This remains an open gate. The owner has
parked the shared slice-map capture; do not infer completion from a successful
file load. MPC 2 velocity-plus-round-robin export is outside the owner's gate,
and PATIO does not ship an MPC 2 substitute.

When an MPC 3 is available, record its model and exact software version, then:

1. Extract each genre download with its `.xtd` and corresponding TrackData
   folder together. Load it and verify all 18 pads, with the roll on B01 and
   cuíca on B02. Check for missing samples and unintended long chain playback.
2. Program eight evenly spaced hits on each pad at each fixed MIDI velocity
   20, 64 and 110. Each four-take cycle should repeat on hit five while the
   three velocity groups retain distinct touch character. Fixed velocity is
   necessary to distinguish cycling from dynamics.
3. Probe velocity boundaries 41/42 and 84/85, and confirm that velocity zero
   does not trigger a new hit. Check closed/open hat choking in both orders.
4. Play the supplied groove and confirm that neither scrape direction is
   sequenced. Save the project, reload it and repeat the fixed-velocity checks.
5. Record pass/fail per kit and any pad that plays only one take. A successful
   load alone does not close the round-robin gate.

Browser acceptance remains separate: play the default cuíca example, compare
short/long, switch kits and pads during playback, restart and repeat, then
check all three downloads. Node event checks establish wiring, not audible
browser playback. This managed session does not expose `cua_repl.js`; the
Sites workflow requires skipping browser QA in that case.
