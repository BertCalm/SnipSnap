# BRINE selected drum kit

Current build: **R13**, with the listener-kept R10 ring ride, R9 focused crashes,
trimmed sample endings, and four MPC programs (before/full pedals × take 1/2).
Snare and ride have three velocity layers. MPC hardware playback remains unverified.
The sections below record the listening revisions; earlier claims describe those builds.

## Reproduce the current build

Use JDK 21, Python with numpy/scipy, ffmpeg, and network access for the credited CC0
listening references. Runtime spectral profiles are already included in the repository.
Generated audio and kit folders are local outputs, not source files to commit.

```sh
python3 experiments/brine/download_reference_cymbals.py testkit/brine-references
./gradlew --no-daemon :synth:generateBrineAudition \
  -PbrineReferences=testkit/brine-references \
  -PbrineOutput=testkit/brine-reference-audition
./gradlew --no-daemon :synth:generateBrineCymbalReset \
  -PbrinePrevious=testkit/brine-reference-audition \
  -PbrineOutput=testkit/brine-r13-audition
python3 experiments/brine/verify_mpc.py testkit/brine-r13-audition
```

Open `testkit/brine-r13-audition/index.html`; its MPC ZIP contains both MPC 2 and MPC 3
program files. Use a fresh output directory when rebuilding a different kit revision.

## R1: first listening build

Acoustic SKIN drums through a shared recording, dispersive spring, slow phaser and combo.
The voicing is a candidate. Start with the dry phrase before judging the processing.

[Engineering specification](superpowers/specs/2026-11-08-brine-drum-board-design.md).

```sh
./gradlew --no-daemon :synth:test --tests '*BrineTest'
./gradlew --no-daemon :synth:generateBrineAudition
```

Open `testkit/brine-audition/index.html`. The folder works offline after extraction.
Use Matched for tone comparisons and Raw for gain differences. Notes save locally in the
browser; Download listening notes exports them as JSON. Listen to stages in order, then
single hits, controls and spring/drive corners. All audio is the Kotlin renderer, not a
browser approximation. The source performance is original, not sampled from the reference.

The processor is `Brine.process` with immutable `Brine.Settings`; settings have JSON replay.
Pass a complete mono 44.1 kHz phrase to share effect state across hits. Separate calls reset
all state. R1 is finite with a one-second tail and has no seamless HOLD or app picker entry.
The page includes 37 comparisons, raw/matched WAVs, settings, source manifest and measurements.

## Reference refinement (R2)

The owner asked for a closer dry recording match after the first audition. Reference PCM
analysis found R1's recording had 91.38% of its 30 Hz–20 kHz power below 100 Hz versus
46.89% in the reference, with crest factors 18.48 and 12.77 dB respectively. These are
whole-phrase measurements, not listening judgements or an identification of the original gear.

`BrineKit` supplies a separate revised source, preserving R1's events, tempo and take policy.
It reduces kick dominance, extends snare/wire and hat decay, restores low-mid recording
body, limits cymbal bandwidth, and adds shared compression and oversampled saturation.
The three candidates are BALANCED, WIRES and ROOM. Their numerical recipe, source settings
and processing constants are recorded in the audition manifest; the original 37 comparisons
remain for reference. The board's version-1 settings and rendering law remain unchanged.

The page now starts with five source-refinement comparisons and four revised single hits,
followed by the original audition. No reference audio is sampled or published. Spectral
balance and crest factor guide the refinement; they cannot establish perceptual fidelity.

Why it is built this way:

- Correcting the source first stops spring reverb and amp distortion from hiding the mismatch.
- Keeping the same event clock makes the old/new comparison about timbre and dynamics.
- A broad low-mid contour restores the missing recording body; the high shelf restrains hiss.
- Matched playback and preserved old renders let the owner judge the change without a loudness advantage.

## R3 kit expansion

The R2 source remains the listener-approved baseline. R3 adds five candidate articulations:
open hat, floor tom, cross-stick, ghost snare, and rimshot. These are SKIN recipes and a
simultaneous snare/stick layer, not a new engine or new recordings. The same R2 bandwidth,
shared compression, contour, saturation, and room treatment follow the source mix.

The audition opens with an expanded 100 BPM two-bar groove, then a ghost/main/ghost/rimshot
phrase and isolated before-pedal/full-chain pairs. The 46 prior comparisons remain intact.
Use the phrase or raw files to judge dynamics: matching isolated ghost notes raises their
level. Closed hats choke the open source with a 6 ms cosine release; room and pedal tails
continue after the choke. These are finite renders, not seamless loops or a live hat engine.

Why it is built this way:
- Reusing the approved recording keeps the new instruments in the same kit.
- The floor tom has lower tuning and longer decay than the existing tom, giving fills a destination.
- Cross-stick supplies a woody backbeat; the rimshot adds a simultaneous rim attack to the snare.
- Ghost notes use the existing velocity-sensitive snare excitation, preserving a quieter strike.
- Open-hat sustain is the main sonic risk: the full-chain comparison exposes excess wash.

Implementation: `BrineKitExpansion.kt`. No app picker or MPC kit export is added in this round.

## R4: thumpy, resonant floor tom

The requested tom refinement targets the new floor tom. The candidate lowers TUNE from .12
to .06, extends DECAY from .58 to .98, raises DROP from .08 to .22 and PUNCH from .40 to .60,
and reduces CLICK from .12 to .055 and TONE from .58 to .38. Source gain and velocity are
unchanged. This changes the membrane's weight and sustain before the shared recording and
pedals. `renderThumpyTom` supplies the new isolated hit and expanded-groove variant; R3
recipes remain available for reproducible comparisons. Eight new audition clips lead the page.

Why it is built this way: the longer modal decay supplies resonance in the drum itself;
the stronger pitch bend supplies the initial thump; reducing click and upper-mode weight
keeps the attack rounded. The risk is that the longer body crowds the spring tail, which is
why both isolated and in-groove full-chain comparisons are provided. All constants remain
proposed voicing choices until heard. No other instrument or board setting changes.

## R5: cymbals, balance comparisons, and MPC test programs

R5 adds half-open hat, dark ride, thin crash and choked crash. The half-open hat uses the
same SKIN hat family as the closed/open pair, with an intermediate decay. The ride blends
90% low-tuned SKIN RIDE with 10% short STICK for definition. The crash averages three tuned
SKIN ride banks, with a 6 ms swelling edge; its choked sibling releases source vibration at
170 ms over 6 ms. These are synthesized recipe candidates, not measured bronze models.

Two 100 BPM grooves carry forward the approved R2 recording and R4 floor tom: hat-led and
ride-led, each before pedals, full-chain, and full-chain with cymbal input gains at .75
(-2.50 dB). The alternative changes only metallic-source gains before the shared bus.
It gives the listener a controlled way to judge snare/tom space, not an automatic masking
verdict. Four single-hit pairs follow. All 68 earlier comparisons remain available.

The downloadable MPC test kit supplies two 13-pad programs (before-pedal and baked full-chain),
MPC 3 .xtd plus MPC 2 .xpm, with raw level relationships retained and fixed -3 dB pad gain.
A01 kick, A02 snare, A03 closed hat, A04 open hat, A05 half-open hat, A06 resonant floor tom,
A07 rack tom, A08 cross-stick, A09 ghost snare, A10 rimshot, A11 ride, A12 crash, A13 choked
crash; A14–A16 empty. Hats use mute group 1, crash pair group 2, ride group 0. MPC mute groups
cut the whole baked sample including its tail; separate wet hits also cannot reproduce the
shared nonlinear bus of the phrase render. These are rendered-audio test programs with
provenance, not editable BRINE patches. New cymbals and hardware loading await user testing.

Why it is built this way:
- One shared recording keeps cymbals in the approved acoustic setting.
- Intermediate hat decay adds an articulation without another instrument engine.
- The ride's small stick component separates pulse from wash.
- Multiple tuned banks broaden the crash; source choking leaves the room response intact in grooves.
- Two balance options expose excess cymbal wash without changing the snare or the approved tom.

## R6: replace noise-like cymbals with struck metal

Listener feedback: R5 choked crash sounded like static; ride lacked ring/resonance. The R5
sources were continuously noise-driven banks, and the recording bandwidth/shelf reduced much
of their high-frequency identity. R6 replaces ride/crash sources with short impacts exciting
persistent inharmonic modes. This is a modal sound-design model, not a measured cymbal plate.

Ride: 34 modes in slightly detuned clusters from approximately 735 to 7,273 Hz, plus a 1,640 Hz
bell mode within that count; T60 around 1.1–2.8 seconds. Crash: 56 denser modes from 650 to
12,282.5 Hz with shorter high-mode decay. Excitation lasts 1.2 ms / 4 ms respectively; no
continuous noise is mixed into the sustain. Choked crash uses the same full-crash onset and
releases its source at 230 ms over 25 ms. Shared room and pedal tails follow the source choke.
Ride/crash source gains are .18/.25 to accommodate stronger midrange resonance in the existing
recording contour. Drum, hat and approved tom sources remain unchanged.

R6 comparisons lead with old/new ride, choked crash and full crash, before pedals and through
the board, followed by two revised grooves. Earlier comparisons remain reproducible. The MPC
test programs now use these candidate replacements; no hardware or listening approval is implied.

Why it is built this way: a short strike lets the resonator own the tail; clustered modes give
metallic beating instead of filtered hiss; lower modes survive the dark recording treatment;
a slower source choke lets the metallic onset establish itself; cached source templates avoid
recomputing the modal bank on every groove hit. Risks: excess bell character and midrange ring
can crowd the snare, so audition the isolated and in-groove versions at matched levels.

R6 mode weights include a frequency tilt, min(12, (Hz/1600)^2.4), to keep the shared recording
contour from turning metallic ring into an overwhelmingly low-mid bell. This was added after
measuring the first candidate, before publication; it is a voicing adjustment rather than
measured hardware behavior.

## R7: real recordings become the benchmark

Josh rejected R6 as not close enough and asked for examples to compare and tune against.
The new benchmark uses real CC0 recordings, not another synthetic target:
- Virtuosity Drums mid-mic normal ride and crash (Versilian Studios / Karoryfer Samples;
  Austin McMahon performance), velocity 2/take 1. The repository revision is pinned in SOURCES.json.
- ankealtd's Freesound 696202, an actual hand-choked 16-inch extra-dry thin crash. The public
  HQ MP3 preview is used; its uploader notes historical tail fades. It is not an unprocessed
  anechoic measurement. Alternate take 696204 is withheld from fitting.
- A second Virtuosity ride take is withheld from fitting; bell, flat-ride and soft samples
  were also inspected as context, not conflated with normal ride articulation.

The recordings reveal why R6's numerical tests were insufficient: it had a few isolated
ringing modes, while the recorded ride carries dense, evolving spectral energy. R6's choke
was a uniform gain stop, not the measured frequency-dependent hand damping.

R7 uses reference-derived spectral resynthesis: 2048-point STFT magnitudes are RMS-smoothed
and reduced to 21 time snapshots; phases are discarded. Runtime generates new deterministic
phases in overlapping inverse-FFT frames. This is a measured-envelope reconstruction, not
sample playback and not a general physical cymbal simulator. The dense ringing/wash balance,
attack evolution and band-dependent choke come from recorded measurements. CC0-derived
profiles and provenance live in synth/src/main/resources/brine; no original PCM is packaged
in the runtime. The reference audio is clearly labelled on the audition page.

Source comparison comes first: real recording, held-out take, previous synth, fitted synth.
Then both reference and fitted synth pass through identical pickup/recording and full-board
paths. This distinguishes source error from processing coloration. All older comparisons
remain. The MPC test kit uses synthesized candidates, not the comparison recordings.

Reproduce: run experiments/brine/download_reference_cymbals.py with a download directory,
then fit_reference_cymbals.py <directory> synth/src/main/resources/brine. These need ffmpeg,
numpy and scipy. Generate the audition with -PbrineReferences=<directory>. Compare the actual
Kotlin WAVs with compare_reference_cymbals.py <audition directory>. Held-out takes are reported
separately; matching training measurements is not proof of convincing cymbal sound.

Why it is built this way: references constrain the target; dense spectral envelopes replace
invented sparse mode lists; new phases preserve synthesis while discarding the waveform;
measured band damping replaces a generic choke fade; equal processing makes the A/B fair.
The risk is phase/transient smearing from the short spectral windows. Audition decides it.

## R8: whole-kit balance and touch (2026-11-10)

Approved R7 cymbal benchmarks are retained. New six-bar comparisons move from hats to ride
and then crash accents, comparing current levels, cymbals attenuated by 2.5 dB before the
recording bus, and that same attenuation with SPLASH .20 / SWEEP .16. These remain listening
candidates, not a claimed improvement. The MPC programs retain the current board balance.

Snare and ride get soft (.35), medium (.65), and hard (.95) strikes and two deterministic takes.
SKIN supplies the snare's existing velocity/excitation behavior and seeded wire variation.
Ride takes use independent phases under the approved measured spectral envelope. A proposed
velocity-dependent treble blend darkens soft strikes; this law is not fitted to soft/hard
recordings. Source strength changes before recording compression and saturation, rather than
scaling an already finished hit. Complete dynamic phrases share their audition matching gain.

Four MPC programs cover before/full pedals × take 1/2. Snare and ride have zones 1–41,
42–84, 85–127; the other eleven pads keep their approved single layers. Takes are selected
by loading separate programs, not automatically cycled. Existing ArrangedPad explicitly
forbids combining its softVariants and takes; this build does not change the general exporter.
MPC hardware playability and perceived dynamic range remain for user testing.

Why it is built this way:
- Whole-kit phrases reveal cymbal masking and effect buildup that isolated hits cannot.
- Lower cymbal gain enters the shared bus so compression responds to the changed balance.
- New ride phases vary strikes without abandoning the approved measured decay.
- Velocity reaches the recording stage, allowing harder hits to drive it differently.
- Separate take programs preserve working MPC velocity exports without claiming automatic cycling.

Site packaging uses experiments/brine/package_site.py: omit the redundant unpacked MPC
folder (ZIP remains), and convert four long R8 matched listening WAVs to lossless FLAC.
Decoded 24-bit PCM equality is checked; raw WAV downloads and all prior comparison files
remain unchanged. This keeps the expanded Site below the hosting 256 MiB limit.

## R9: cymbal reset after listener rejection (2026-11-12)

The listener now describes the cymbal as overbearing and muddy. Earlier approval of the R7
source is not approval of its present kit balance. Review at base 7ccdbe7 plus local BRINE
work found that all cymbals pass through the drum-body recording contour: +14 dB at 540 Hz,
Q .8, followed by a -6.2 dB high shelf at 2 kHz and strong recording saturation.
Python measurement of actual R7/R8 WAVs showed ride 200–1000 Hz / 2–8 kHz energy balance
moving from -2.9 dB at the source to +16.4 dB after recording; 90% energy time moved from
.881 s to 2.437 s. This is evidence for excess low-mid energy and sustained energy, not a
claim that a metric fully explains the perceived problem.

BrineCymbalReset introduces a local cymbal pickup correction, leaving the drum recipes and
shared nonlinear recording/pedal chain intact. Three candidates use the same R7 source:
current; lean (-14 dB at 540 Hz Q .8 and two 240 Hz one-pole high passes); focused (lean plus
additional exponential damping after 35 ms, T60 1.8 s ride / 3 s crash). The measured choke
keeps its existing envelope. No bright noise exciter, makeup gain, or source peak normalization
is introduced. Filtering and damping run linearly at 44.1 kHz; existing nonlinear stages
remain oversampled. The source cache stays in R7; each candidate copies and conditions that
cached signal. Constants are proposed listening values, not a measured physical cymbal model.
SKIN and ONSET are the nearest inventory siblings; this remains a BRINE recording recipe,
not a new engine or duplicate modal architecture.

The focused R9 page separates ride and crash grooves, isolated full-chain comparisons, and
original CC0 recorded benchmarks. Groove variants share one playback gain per group; solo
hits are independently matched to compare tone. Raw always retains true output level.
The previous R8 MPC ZIP is explicitly labeled as containing old cymbals. R9 remains an
audition until the listener selects a direction. Earlier full audition archives remain local
in testkit and brine-delivery; they are not silently rewritten by this generator.

Why it is built this way:
- Fix cymbal energy before the shared saturation can turn low mids into a sustained wall.
- Counter the drum contour locally so the approved kick, snare and tom keep their treatment.
- Compare contour alone against contour plus damping so the listener can distinguish them.
- Keep the first 35 ms and smoothly damp the tail instead of replacing ring with hiss or a gate.
- Lock groove playback gain so the audition does not amplify away the intended reduction.

Verification uses BrineCymbalResetTest and experiments/brine/measure_cymbal_reset.py over
actual Kotlin renders. Listening realism, browser playback and MPC hardware remain separate
checks. The complete repository test suite is not claimed green.

R9 verification result: focused Gradle build exited 0 (19 tests); 18 comparisons / 36
listening WAVs verified for hashes, mono 44.1 kHz, finite headroom and DC. Original recorded
references and the previous MPC ZIP match R8 byte-for-byte. In full-chain Kotlin renders,
200–1000 Hz / 2–8 kHz balance changed current→lean→focused as follows: ride +14.6/+2.7/-1.1 dB,
crash +6.7/-3.7/-3.0 dB, choke -0.4/-11.8/-11.8 dB. Energy90 seconds: ride 2.410/1.916/.448;
crash 2.029/2.275/.707; choke .418/.383/.383. Contour alone does not shorten the crash:
this is why the separate damping candidate matters. These measurements are not listening
approval. Page JS syntax and asset integrity pass; browser playback is unverified.

## R10: restore a little ride ring

Listener selected R9's less-body/shorter-wash direction, but found the ride choked.
RING retains the identical inverse body contour, high passes and first 35 ms, and relaxes
only the ride's added exponential T60 from 1.8 to 3.2 seconds. The original reference
envelope still multiplies that decay, so 3.2 seconds is not the final audible tail length.
Crash and hand-choked-crash output remains identical to FOCUSED. The previous short ride
stays available alongside the new ride; common groove gain is retained. R8 MPC download
remains explicitly previous, while the new audition awaits confirmation of the ring amount.

Why it is built this way: relaxing only damping gives the existing metal ring more time
without reintroducing the low-mid contour the listener rejected. Keeping the original short
ride and the first 35 ms unchanged makes the decay difference directly comparable.

R10 verification: six focused tests pass; all 18 R9 raw/matched comparison pairs retain
identical SHA-256 hashes. Forty listening WAVs verified; ride grooves still share one gain.
Full-chain ride Energy90 rises from .448 to .635 s (about 42%) versus 2.410 s for the old
heavy ride. Low-mid/definition ratio stays .5 dB versus 14.6 dB for the old heavy ride.
Page script syntax passes; browser playback remains unverified. Full-suite status unchanged.

## R11: accepted sounds and trailing-silence cleanup (2026-11-13)

Imported listener notes verbatim into docs/listening/BRINE-R10-notes.json. Kept: R10 ring
ride solo/groove and R9 focused crash groove; focused crash/choke solos kept with a request
to fix excess file length. Only those two isolated full-chain exports are shortened.
The export scans for the last sample above -90 dB relative to that hit's peak, retains
120 ms beyond it, and fades the final already-quiet 10 ms. No leading trim or source,
recording, pedal, ride, groove, or gain change is made. Scanning the entire rendered signal
preserves later effect returns. Clip IDs stay stable so saved listening notes remain attached.

Why it is built this way: trimming after the complete board protects the effect decay;
the conservative threshold and padding avoid turning a file-length fix into a choke.
The previous R8 kit remains explicitly labeled previous; kept audition WAVs are current.

R11 verification: generator build exited 0. Crash 10.600→3.419 s; choke 10.600→1.904 s.
Both matched/raw files preserve exact prior PCM until the final 10 ms near-silent fade;
discarded tail energy is below 3e-10 of original total energy. All 18 other comparison pairs
are byte-identical, and WAV hashes/format/headroom/DC plus unchanged previous MPC ZIP pass.
Page script parses; browser playback remains unverified. No source DSP changes or new tests.

R11 publication blocked: Sites now returns auth_mode codex_auth and a new chatgpt.com Git
URL. The executor has no configured Sites Git credential helper; the supplied new URL
returns HTTP 400 on push. R11 is built and verified locally, with an unpushed Site commit
and deployment archive in brine-delivery. The live Site remains R10. Do not claim R11 live.

## R12: audit remaining file lengths

User requested checking the others. Audit covered all 20 current listening previews and
all 68 WAVs in the explicitly previous R8 MPC download. The 15 other synthesized previews
had removable near-silence; the two R11 crash trims were already clean. Apply the same
peak-relative -90 dB / 120 ms pad / final quiet 10 ms fade to every synthesized preview,
including grooves, only after the whole board finishes. Groove common gains remain fixed.
The three recorded-reference previews contained exactly one appended second of zeros;
remove only that second, keeping every original reference frame. Different preview lengths
now restart an A/B comparison if its old position falls beyond the new clip's end.

All 68 old MPC WAVs have more than .25 s removable padding. That artifact contains previous
cymbals and is audited, not silently presented as the selected kit. The per-file audit is
brine-delivery/verification/r12-previous-mpc-length-audit.json. A current selected-sound MPC
export remains separate work. The live Site remains R10: refreshed official Git credentials
still return codex_auth and the supplied Git URL still returns HTTP 400 in this executor.

Why it is built this way: measure the complete decay rather than assume a fixed duration;
keep rhythm timing and early samples exact; retain actual reference recordings intact.

R12 verification: generator exited 0. Forty preview WAVs pass hash/format/headroom/DC
checks. Synthesized PCM matches R11 exactly until the last quiet 10 ms (two existing
crash trims remain fully byte-identical); removed energy is below 1e-7 of total.
All reference PCM is unchanged; only verified final zero padding is removed.
Kept ride 10.60→3.68 s; kept ride groove 11→7.34 s; kept crash groove 11→6.08 s
(rounded; exact values are in verification/r12-preview-lengths.json).
Page JS syntax passes; browser playback unverified. Previous MPC ZIP is byte-identical.

## R13: current MPC rebuild

User authorized rebuilding after the length audit. Four revision-named programs retain
13 pads each, separate before-pedals/full-chain versions and take 1/2, plus three snare/ride
velocity zones. Ride uses selected R10 RING conditioning on each of the two deterministic
phase realizations. Velocity drives the shared recording stage; the old R8 extra low-pass
shading is not reapplied over the selected sound. At .78 velocity and take zero it matches
the accepted ride render exactly. Crashes use selected R9 FOCUSED, including the hand choke.

BrineExportTail is shared between audition and MPC generation. Every WAV is trimmed after
its complete recording/board processing, before KitAssembler builds metadata. Thus native
MPC 3 slice endpoints and MPC 2 SliceEnd values are generated from the shortened samples,
not left pointing past a shortened file. No leading cut, loops, gain matching, or change
to hat/crash mute groups is introduced. R13 names distinguish these from old imported kits.

Why it is built this way: use one selected cymbal conditioning path for audition and export;
let strikes drive the bus rather than scale the finished sample; compute program endpoints
after tail cleanup so the sampler loads the same durations as the WAV files. Separate take
programs remain explicit, not a claim of automatic round robin. Hardware playback unverified.

R13 verification result: Gradle exited 0; eight focused tests passed. All 68 samples are
shorter than the previous exports and end within .14 s of their last sample above peak
-90 dB; maximum duration 3.719 s. All 48 non-cymbal sample prefixes are unchanged before
the final quiet fade. Full-chain crash/choke PCM exactly equals the selected audition.
All 20 audition raw/matched pairs retain their R12 hashes. MPC 2/3 references, one-shot
flags, mute groups, three-layer velocity boundaries, and slice endpoints matching actual
WAV frame counts pass. All files are mono 44.1 kHz PCM24; ZIP integrity passes.

Current kit and offline audition are packaged as BRINE-MPC-R13.zip and
BRINE-audition-R13.zip in brine-delivery. Publishing retry still returns HTTP 400 from the
supplied Sites Git endpoint, so the live Site is still R10. Hardware loading and browser
playback remain unverified; no full-repository suite claim is made.

## Pre-push validation (2026-11-14)

All 29 focused BRINE tests pass (Gradle exit 0), as do the MPC 2/3 program and selected-audio
verification scripts. The complete `./gradlew --no-daemon test` run was attempted and found
an existing compile error at `shell/src/test/kotlin/com/snipsnap/shell/BeatScheduleTest.kt:223`:
`forEachNoteDue` is called without its required `lap` argument. Both that test and the
scheduling API match starting commit 7ccdbe7 exactly and are outside this change. Remaining
full-suite work was stopped after that blocking error (exit 143), so the full suite is not
claimed green. No test was disabled or changed to conceal the error.
