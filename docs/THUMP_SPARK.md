# THUMP SPARK and related hit families

Status: revision 3 is the working sound-design baseline. The owner reported
“Much better” on 2026-10-31 and requested the recommended sweet-spot refinement
on 2026-11-01. R3's 119 focused tests and encoded asset audit passed; PR
[#729](https://github.com/BertCalm/SnipSnap/pull/729) also passed full JVM,
Android and native CI and was merged by the owner. The local full run ended
without a terminal result; it is not counted as a pass.

This round keeps the R3 source and control ranges intact and adds six proposed
recipe neighborhoods for individual listening. Repository baseline for this
round: `9c100267` (2026-11-01). Phone timing and MPC hardware loading remain pending.

The characteristic gesture is soft contact or hard strike → metallic material
→ falling pitch → settled body. SPARK remains one provisional THUMP voice with
five controls and finite mono one-shots. The pitch fall is now independent of
METAL and COLOR, so those controls can be judged as material and brightness.

## Why revision 3 exists

The owner heard no useful PUNCH movement in R1, with weak METAL and COLOR changes
except at their joint maximum. After R2, the owner reported: “Revision 2 sounds
different than one for each, but the range of each sounds the same across.”
The working interpretation is that the steps within each control still lack a
useful perceived range. A different base timbre and passing spectral checks did
not settle that problem.

The R2 PCM audit found distinct audio paths and hashes at every quarter step;
audition matching was a single gain, with only PCM quantization residual after
rescaling. There was no duplicated-player-path fault in the checked files.
However, 90% of the energy in the default-context control clips arrived within
16–54 ms, and the page emphasized R1/R2 comparisons at a fixed setting. R3
extends the source changes through more of the hit and puts within-R3 repeated
control sweeps first. Those are design responses to the report, not a listening
verdict on R3. The later “Much better” report supports keeping R3 as the working
baseline; it does not approve every subsequently proposed recipe.

## Current source and signal flow

```text
TUNE, DECAY, PUNCH, METAL, COLOR (constant during each hit)
                         |
       fixed pitch-fall plan + PUNCH contact envelope
                         |
       +-----------------+-------------------------+
       |                                           |
       v                                           v
  31-partial harmonic body               phase-modulated edge
  fundamental / harmonic decay          + sine × finite-triangle ring pair
       |                                material persists into the body
       v                                           |
  COLOR body low-pass                              v
       |                                   COLOR edge low-pass
       +--------------------+----------------------+
                            |
                            v
       sum at 176.4 kHz → band-limit → DC removal / 20 Hz high-pass
                            |
                            v
       normalize → decimate with shared PUNCH amount 0
               → SPARK loudness level → peak ceiling → tail fade / 44.1 kHz mono
```

TUNE maps exponentially from 110 to 440 Hz; 0.5 is 220 Hz. DECAY maps
exponentially from 0.16 to 1.0 seconds for the body's t60. Render duration is
1.4 times body t60, approximately 0.224–1.4 seconds. There is no HOLD or seamless
loop. The one-way oscillator model has no recursive feedback or event solver.
All ranges below are bounded sound-design choices in that working baseline.

PUNCH owns source articulation. Its raised-cosine rise moves exponentially with
`PUNCH²` from 60 ms at 0 to 0.60 ms at 1. Both branches receive that rise times
`0.10 × (1 + 9 × PUNCH² × exp(-time / 0.018))`. The soft endpoint spreads contact
over tens of milliseconds; the hard endpoint reaches the first cycle and
emphasizes the strike. SPARK passes amount 0 to the shared `Punch` finish,
avoiding a second, independent 2 ms transient spike. Other THUMP voices retain
their existing PUNCH path.

The body has 31 finite harmonics with weights `1 / sqrt(harmonic number)`,
divided by `sqrt(sum(weights²))`. RMS normalization keeps the body present beside
the edge as its harmonic count grows. The fundamental decays at body t60; upper harmonics decay
at 0.90 times body t60. The extra partials give COLOR material to remove or
reveal through more of its range, including METAL 0. The largest initial body
partial is 18.414 kHz at TUNE 1, before filtering and delivery band-limiting.

METAL sets body gain `0.52 - 0.18 × METAL` and edge gain
`0.95 × sqrt(METAL)`. The edge combines 0.65 sine phase modulation and 0.35
sine × triangle ring product. The ring term also receives the modulation
envelope so its irrational sidebands do not outlast the settled root. The modulator ratio is √2; the triangle contains
seven finite odd harmonics. The phase-modulation index is
`(1.5 + 6 × METAL) × modulation envelope`.
Edge t60 is now `body t60 × (0.80 + 0.15 × METAL)`, and modulation t60 is
`min(1.25 × body t60, 0.45 seconds)`. This keeps the material change present
past the initial transient, while the capped modulation time allows a settled
pitched body later. METAL is not promised to increase brightness monotonically.

Separate `Dsp.TptSvf` low-pass filters shape body and edge. COLOR moves body
cutoff exponentially from 0.9 times the root to `min(28 × root, 14000 Hz)`;
edge cutoff runs from the root to `min(16 × root, 16000 Hz)`.
The edge cutoff is multiplied by `0.60 + 0.40 × modulation envelope`.
Both cutoff maps use `0.5 × (2 × COLOR)^0.85` as their position when COLOR
is at or below 0.5, and COLOR itself above 0.5. This opens the first quarter
while preserving the center and upper-half settings. COLOR changes spectral
balance without changing oscillator pitch or the PUNCH rise time.

The pitch excess is fixed at 0.35 above the root, decaying with an 18 ms time
constant. Phase uses the exact integral of that falling frequency. Removing
METAL's bend-depth and COLOR's bend-time assignments separates the timbre
controls from the shared pitch gesture. A uniform 0.20 source scale applies to
both branches before filtering, bounding their unfinished sum without changing
the body/edge balance. Measured corner behavior is a separate test.

Only SPARK receives its scoped pre-decimation band-limit and DC conditioning.
After the shared amount-0 decimation path, SPARK alone uses
`Dsp.levelTo(buf, RATE, Dsp.MELODIC_LOUDNESS_TARGET)`: the house perceived-level
target is 0.1834, with the helper's default 0.99 peak ceiling. This is one
uniform gain, preserving the source articulation while keeping ordinary PUNCH
positions from becoming much quieter. Other THUMP voices retain their finish.
The source and filters do the voicing; final limiting is a delivery safety net.

## Controls and regression contracts

| Control | Default | Changes | What the checks must establish |
|---|---:|---|---|
| TUNE | 0.50 | Body root and harmonics, edge/modulator frequencies, root-relative cutoffs | A measurable settled root at TUNE × METAL positions. |
| DECAY | 0.35 | Body, harmonic, edge and modulation decay; render duration | Delivered energy lasts longer, rather than merely gaining silent padding. |
| PUNCH | 0.50 | Source contact time and 18 ms strike emphasis | A soft-to-hard energy-timing range after delivery, plus movement between quarter steps. |
| METAL | 0.25 | Body/edge balance, phase-modulation depth and material decay | Inharmonic material remains measurable after the strike; low and middle settings move with moderate COLOR. |
| COLOR | 0.50 | Separate body/edge low-pass cutoffs | Spectral movement persists beyond the attack and works at METAL 0 as well as ordinary METAL. |

These are numerical regression contracts, not proofs of audibility or musical
sufficiency. Non-finite or missing controls fall back to the voice defaults;
finite values clamp to 0..1. Unknown controls do not add hidden behavior.

## Related families are separate hits

`ThumpFamilies.patches` accepts a SPARK `ThumpPatch` and 2–16 finite, unique,
strictly ascending positions in 0..1. Default positions are 0, 1/3, 2/3 and 1.
For offset `position - 0.5`, the bounded shape law remains:

```text
METAL += 0.70 × offset
COLOR += 0.70 × offset
DECAY -= 0.30 × offset
TUNE and PUNCH remain fixed
each value is clamped to 0..1
```

Position 0.5 preserves the source's resolved settings. Clamping intentionally
reduces travel near a control boundary. Each ordinary patch stores its complete
macro map and replays independently; no continuously moving control is added
inside the hit. `ThumpFamilies.render` classifies each actual rendered hit.

The SYNTH picker has a nominal PERC tint. Normal app SEND classifies delivered
SPARK audio before a new assignment or fresh replacement, including saved
processing. A reopened pad keeps its existing identity under the app policy.
SPARK has no fixed drum-class promise across its full range.

## Audition and integration

SPARK remains appended to `ThumpVoice`. It has no factory presets while
individual starting points are still being auditioned; defaults and SCRAMBLE
provide editable starting points.
DE-SAMPLE excludes this prototype from its candidate grid. No Android or native
playback code changes are part of R3. Android compilation, phone timing and
MPC hardware loading remain separate from synthesis tests.

Generate into a fresh output directory, using the preserved R2 bundle as input:

```sh
./gradlew --no-daemon :synth:generateThumpSparkAudition \
  -PsparkOutput=/absolute/new-r3-folder \
  -PsparkBaseline=/absolute/preserved-r2-audition
```

The default destination remains `testkit/thump-spark-audition/`. The R3 generator
puts repeated PUNCH, METAL and COLOR quarter-step sweeps and alternating endpoint
trains before the collapsed old/new comparisons. It renders 61 settings: the
previous 46-setting matrix plus 15 quarter-step settings with DECAY 0.7.

For each of PUNCH, METAL and COLOR, both default and longer-decay contexts have
a 15-hit sweep, three hits each at 0/0.25/0.5/0.75/1, and a six-hit endpoint
train, 0/1/0/1/0/1. Those 12 trains produce 24 native/matched WAVs. Their onset
interval is the greater of 600 ms and the longest complete hit plus 50 ms, so
there is no overlap. Exact onset frames, held controls, source clip IDs, recipes
and source/train hashes are recorded in `controlTrains`.

Current paths use `r3-` identifiers: `audio/r3-…`, `recipes/r3-…`,
`strips/r3-…`, `family-r3-native.wav` and `family-r3-matched.wav`. Downloads are
`thump-spark-r3-audition.zip` and `thump-spark-r3-mpc-kit.zip`. The manifest
schema and audition revision are 3. The hosted R2 baseline is copied byte for
byte under `previous/`, retaining R1 under `previous/previous/`. Replaying an old
recipe through R3 would produce a new sound and is not a preserved comparison.

The R3 listening ZIP contains the current revision only: clips, trains, recipes,
family strips, measurements and kit. Its offline manifest and page omit old/new
players and the ZIP self-download, and link to the hosted audition for preserved
revisions. The complete old bundles remain unchanged on the site. Keeping each
current pack separate avoids repeatedly nesting large old ZIPs and keeps the
individual static download within the hosting size limit.

Native audio is the finished THUMP output before audition matching. Matched
audio applies one gain per complete clip through `AuditionLevel`, targeting
0.03 RMS in the loudest 200 ms window with a 0.99 peak guard. Matching does not
change source recipes, filters or envelopes. Repeated comparison trains assemble
those encoded hits with silence between onsets; they do not animate a macro
inside a hit.

Recipes remain independently replayable `PadRecipe` JSON. The manifest records
renderer source hashes, held controls, WAV paths and measured classes; the CSV
records durations and local render observations. The 16-pad XPM audition kit
uses four TUNE rows, 0.15/0.40/0.65/0.90, by four expression columns, with actual
rendered classes and complete recipes. MPC3/XTD export is outside this proof.

Cost is linear in sample count with a fixed oscillator count. Harmonic recurrence
avoids one sine call per additional partial; there is no solver or geometry
cache. The target is a warm one-shot render in tens of milliseconds on the
phone. Generator timings are single warmed local JVM observations, not a phone
benchmark. A family should render as a batch, not in the playback callback.

MOVE remains deferred. An intra-hit trajectory would require its own time law,
phase/envelope contract, deterministic recipe and listening comparisons.

## R3 sweet-spot study

The six centers below are proposed editable examples on the unchanged R3
renderer. Each neighborhood varies only the named control; TUNE and every
other setting stay fixed within that comparison. Lower, center and higher
settings are separate hits, not a trajectory inside a hit.

| Example | TUNE | DECAY | PUNCH | METAL | COLOR | Nearby comparison |
|---|---:|---:|---:|---:|---:|---|
| R3 START | 0.50 | 0.35 | 0.50 | 0.25 | 0.50 | PUNCH 0.40 / 0.50 / 0.60 |
| WARM KNOCK | 0.25 | 0.25 | 0.60 | 0.08 | 0.28 | COLOR 0.18 / 0.28 / 0.38 |
| SOFT MALLET | 0.45 | 0.60 | 0.25 | 0.12 | 0.48 | PUNCH 0.15 / 0.25 / 0.35 |
| HOLLOW RING | 0.50 | 0.55 | 0.45 | 0.45 | 0.32 | METAL 0.35 / 0.45 / 0.55 |
| BRIGHT CHIP | 0.80 | 0.12 | 0.78 | 0.25 | 0.72 | COLOR 0.62 / 0.72 / 0.82 |
| OPEN CLANG | 0.60 | 0.70 | 0.62 | 0.75 | 0.78 | METAL 0.65 / 0.75 / 0.85 |

R3 START anchors the study to the existing default. WARM KNOCK explores a
shorter, lower body with little metallic material. SOFT MALLET opens the
rounded-contact part of PUNCH over a longer body. HOLLOW RING tests material
movement behind a darker filter; BRIGHT CHIP tests brightness on a shorter,
higher hit. OPEN CLANG tests the more metallic, longer-decay territory without
requiring either timbre control's maximum. These are intended roles, not heard
descriptions or fixed classifier assignments.

Generate this study separately from the preserved range audition:

```sh
./gradlew --no-daemon :synth:generateThumpSparkSweetSpots \
  -PsparkSweetSpotOutput=/absolute/new-sweet-spots \
  -PsparkSweetSpotBaseline=/absolute/preserved-r3-audition
```

The study contains 18 complete recipes with native and matched single hits.
Six neighborhood trains play lower twice, center twice and higher twice; a
separate playlist plays each of the six centers twice. Trains copy the encoded
hits with silence between onsets and add no processing. Source hashes and an
exact native/matched PCM comparison of R3 START against the preserved default
guard the baseline. The existing range audition and historical assets remain
available unchanged.

The page allows a preference to be recorded for each neighborhood and exported
with its complete macro map. Preferences are stored in the browser; they do
not change SnipSnap's factory bank. Individual recipe choices still need the
owner's listen. The study generator and all 13 `ThumpSparkTest` checks completed
with exit code 0. Independent audits verified 18 recipes, 50 WAVs, all seven
train pairs, 70 asset references and the 71-entry listening ZIP. Its size is
6,891,678 bytes. Native and matched R3 START files are byte-identical to the
preserved default, and all four renderer hashes match R3. Every train retains
the exact source PCM with zero-filled gaps.

The generated page's preference logic passed a Node DOM/storage harness:
saved and undecided choices, unavailable storage, complete five-control JSON
exports, and pausing other players. This is not a browser playback or listening
test. The current source's full JVM regression run is in progress; R3's earlier
completed CI result is recorded separately below.

## Revision 3 verification ledger

The final focused Gradle run and generator exited 0 in 2 min 03 s. All 119 tests
passed with zero failures, errors or skips: SPARK 13, families 5, THUMP 38,
presets 6, DE-SAMPLE 5, PUNCH 6 and determinism 46. Report:
`spark-r3-validation.json`. The full nine-module JVM suite is running on
Temurin 17; focused success does not establish a full-suite pass.

| R3 measured probe | Result | Scope |
|---|---:|---|
| PUNCH median-energy time, soft → hard | 49.57 → 6.92 ms | Default METAL 0.25, COLOR 0.5; uniform gain cancels from energy timing. |
| PUNCH attack/body range | 30.92 dB; smallest quarter 1.866 dB | METAL 0.25, COLOR 0.5; 0–10 ms versus 10–50 ms RMS. METAL 0 also passed. |
| METAL off-harmonic power, 0 → 1 | 0.00161 → 0.30365 | 60–160 ms, DECAY 0.35, COLOR 0.5; increase 30.2 percentage points. DECAY 0.7 gives 0.00059 → 0.41997. |
| COLOR complete centroid span | 5.81–6.06× early; 3.67–5.94× later | METAL 0/0.25, DECAY 0.35/0.7; 0–80 ms and 60–160 ms. Smallest quarter ratio 1.1355×. |
| Maximum raw peak / delivered absolute DC | 0.237465 / 0.00052811 | All 32 corners. |
| Settled root | Maximum error 1.7642 cents; minimum confidence 0.92684 | Nine TUNE × METAL positions, DECAY 1, PUNCH 0; 0.20–0.45 s. |
| Maximum 4×/8× residual | 0.035006 relative RMS | TUNE 0/0.5/1, METAL 1, COLOR 1, PUNCH 0/1; PUNCH 1 includes onset. |
| Current native PCM peak / maximum absolute DC | 0.99000013 / 0.00005944 | The 61 encoded settings. |
| Warmed local render observation | Median 16.050 ms; range 7.815–51.413 ms | One observation per setting; not a phone benchmark. |

The asset audit verified 148 current WAVs: 122 single-hit files, 24 control-train
files and two family strips. All 61 recipes replayed; four renderer source
hashes match. The 15 R2/R3 rows hold the other controls equal, and all 260 R2
baseline files, including nested R1, remain byte-identical. The 473 relative
references resolve across 474 total files. All three hosted pages and the
offline page passed JavaScript syntax checks. Train PCM samples equal their
encoded source hits, with zero samples in the gaps.

Default train onsets are 600 ms apart; DECAY 0.7 trains use 857.891 ms. The
current-only listening ZIP has 213 entries and is 22,920,110 bytes, within the
25 MiB individual static-file limit. The kit ZIP has 19 entries. ZIP contents
and CRC checks passed.

| Claim | Status | Evidence / limit |
|---|---|---|
| Source articulation, persistent material, broader spectrum and fixed pitch fall | Built; numerically checked | Current `Thump.spark`, SPARK-only shared-PUNCH amount 0 and final uniform loudness level. |
| Focused regression checks, recipes and encoded audition integrity | Passed | Successful Gradle exit and the source/WAV/baseline/train/ZIP audit above. |
| R3 full JVM, Android and native CI | Passed | PR #729 checks at `5704db39`; the local incomplete full run is not counted as a pass. |
| R3 control-range improvement | Heard by owner | “Much better,” 2026-10-31; retained as the working baseline. Individual sweet-spot examples remain proposed. |
| Phone performance and MPC loading | Pending | Local JVM and file checks do not establish these. |

The convergence residual includes filter phase, integration and resampling
differences; it is not a pure alias-power measurement. Numerical movement does
not establish useful audible control range, as the R2 listening result showed.

## What carries over from the references

The research has two distinct parts. The Yamaha RY30 contributes per-hit
expression: wheel positions alter pitch, decay, filter cutoff or the balance
between two wave layers as notes are entered or encountered in parameter
recording. Its filters belong to the separate wave layers, with independent
cutoff and envelope controls. They are not a pair of generic serial filters.
See the [Yamaha operating manual](https://usa.yamaha.com/files/download/other_assets/0/317230/RY30E.pdf),
printed pp23, 28–30, 41–42 and 85–86.

The original Korg ER-1 contributes simple synthesized percussion sources,
pitch modulation extending into audio rates, ring modulation and the distinction
between smooth motion and values held at each trigger. Its four synthesized
parts coexist with PCM parts. Its dedicated interpart ring modulation differs
from the later mkII's interpart frequency cross modulation; the original manual
also calls its fast internal pitch modulation cross-modulation effects. See the
[original ER-1 manual](https://cdn.korg.com/us/support/download/files/77b10f93dbdbf465c8ad01e62467f42e.pdf),
pp6, 18, 24–25 and 28, and the
[mkII manual](https://cdn.korg.com/us/support/download/files/ad483aaec5378de168bfd3ff25d3b36a.pdf),
pp9 and 25.

SPARK distils those behaviors into one new THUMP voice. Oscillators,
multiplication, phase modulation and filters are literal DSP. The coordinated
family offsets are a sound-design analogy for expression, not a simulation of
either machine's wheel, firmware or analog circuitry. No original samples,
factory names or interface artwork are used.

The nearest siblings remain THUMP's oscillator drums and TANGLE's longer
interacting-oscillator gesture. Within the roster, COWBELL and TINES provide
useful metallic-percussion comparisons. SPARK keeps a bounded one-way source
and editable related hits; a THUMP voice is the lighter landing than a new
engine or a general modulation matrix.

## Revision 2 historical validation and listening result

R2 added an 18 ms source articulation, seven-partial harmonic body and a more
prominent edge. Its 113 focused tests in seven classes passed with zero failures,
errors or skips; its 46-setting generator completed with exit code 0. The 92
current WAV hashes, 46 recipes, four renderer hashes, 115 preserved R1 files,
15 held-control comparisons and both ZIPs were verified. These remain R2 facts,
not evidence of R3 behavior.

The local full-suite run encountered an internal Temurin 21 C2 compiler failure
in unchanged `CausticInstrument::synthesize` and was stopped without a passing
result. Subsequent PR [#690](https://github.com/BertCalm/SnipSnap/pull/690) CI on
Temurin 17 passed JVM, Android and native checks, and the owner merged that PR.
Those CI results supersede the old “still running” status for R2; they do not
claim R3 validation or device performance.

| R2 probe | Measured result | Tested scope |
|---|---:|---|
| Maximum unfinished source peak | 0.9199232 | All 32 corners of the five-control space. |
| Maximum absolute delivered mean | 0.00240994 | The same 32 corners, after THUMP's finish. |
| Maximum absolute root error | 5.34122 cents | Nine TUNE × METAL conditions: 0/0.5/1 each, DECAY 1 and PUNCH 0; body read from 0.20 s over a 0.25 s window. |
| Minimum pitch confidence | 0.8065 | The same nine root measurements. |
| Maximum 4×/8× body residual, PUNCH 0 | 0.0171150 relative RMS | TUNE 0/0.5/1, METAL 1, COLOR 1; first 10 ms excluded. |
| Maximum 4×/8× residual, PUNCH 1 | 0.0243008 relative RMS | Same tuning/timbre positions, including the onset. This includes filter/resampling differences, not pure alias power. |
| PUNCH complete attack/body range | 19.384 / 20.014 dB | METAL 0 / 0.25, COLOR 0.5; 0–10 ms versus 10–50 ms RMS. Every quarter step exceeds 1 dB. |
| COLOR complete early-centroid ratio | 2.356× / 2.289× | METAL 0 / 0.25, PUNCH 0.5; normalized first-80-ms power spectra. |
| Minimum adjacent early METAL spectral distance | 0.3019 | METAL 0/0.25/0.5/0.75 at COLOR 0.25 and 0.5, PUNCH 0.5; normalized first-80-ms spectral amplitudes. |

| R2 delivered audition observation | Measured result | Limit |
|---|---:|---|
| Current settings / matched R1–R2 comparisons | 46 / 15 | Preserved baseline included; two current family strips and 16-pad XPM kit. |
| Maximum native PCM sample peak | 1.0 | Finished output after THUMP's peak ceiling. |
| Maximum absolute native PCM mean | 0.00073667 | Across the 46 PCM24 settings. |
| Local render observation median | 15.900 ms | Single warmed local JVM observation per setting, not a phone benchmark. |
| Local render observation range | 10.001–54.701 ms | Same limited run. |


The post-attack METAL probe excluded frequencies below 40 Hz and bins within
±8% of an integer harmonic in its 60–160 ms window. Its required quarter-step
increase was only 0.002 in normalized off-harmonic power. COLOR's tested
upper-band increments exceeded 0.075. These scoped checks established numerical
movement, while the owner's R2 report established that the range still felt too
similar. The saved historical report is `spark-r2-validation.json`.

An independent PCM24 audit after that report measured the following default
quarter-step sequences. These measurements explain the time/spectrum distinction
without overriding the owner's listening result:

| R2 control audit, 0 / 25 / 50 / 75 / 100% | Measured sequence |
|---|---|
| PUNCH median-energy time, ms | 24.2 / 15.2 / 8.4 / 7.9 / 0.7 |
| PUNCH first-80-ms power centroid, Hz | 319 / 321 / 321 / 322 / 320 |
| METAL first-80-ms power centroid, Hz | 385 / 321 / 353 / 455 / 543 |
| COLOR first-80-ms power centroid, Hz | 197 / 252 / 321 / 394 / 453 |

The METAL centroid was nonmonotonic in its lower quarter. PUNCH primarily changed
energy timing while keeping a similar spectral balance. Neither low waveform
correlation nor a distinct file hash establishes a useful audible knob range.

## Revision 1 historical validation

These results describe R1, not R3. Its focused JVM run and generator exited 0:
90 tests in six classes, zero failures/errors/skips, including SPARK 7, families
5, THUMP 38, presets 6, DE-SAMPLE 5 and determinism 29. Production/test Kotlin
compiled; this was not an Android compilation result.

The R1 bundle had 36 settings, 72 PCM24 WAVs, two family strips, recipes and a
16-pad XPM kit. Recipe replay/readback, four source hashes, all 110 audio/recipe
hashes and both ZIP CRC checks passed. R1 renderer hashes are historical and do
not match R3. Report: `thump-spark-validation.json`.

Eight modules outside `synth` completed 2,896 tests without failures/errors/skips.
Two environment restarts interrupted the full synth runs, leaving that session's
full JVM suite unverified. R1 measurements do not establish useful audible
control range or phone performance.

Revision 1 bundle observations from its `manifest.json` and `metrics.csv`:

| Observation | Measured result | Limit of the claim |
|---|---:|---|
| Individual settings rendered | 36 | The audition matrix, not the full macro space. |
| Maximum native PCM sample peak | 1.0 | Finished output after THUMP's peak ceiling. |
| Maximum absolute native PCM mean | 0.00113048 | DC after PCM24 encoding, across the 36 delivered settings. |
| Local render observation median | 14.969 ms | One warmed JVM observation per setting, after the focused tests completed. |
| Local render observation range | 8.152–49.529 ms | Same limited run; not a benchmark or target-phone result. |
| Default family classes | PERC, PERC, PERC, PERC | Other settings can classify differently. |
| Default family durations | 0.560, 0.466, 0.388, 0.323 s | Successive default expression positions. |

Revision 1 Kotlin probe observations from `ThumpSparkTest`:

| Probe | Measured result | Tested scope |
|---|---:|---|
| Maximum unfinished source peak | 0.8880578 | All 32 corners of the five-control space, before output conditioning. |
| Maximum absolute delivered mean | 0.00254330 | The same 32 corners, after THUMP's finish. |
| Maximum absolute root error | 2.948 cents | Nine TUNE × METAL conditions: 0/0.5/1 each, DECAY 1 and PUNCH 0; body read from 0.20 s over a 0.25 s window. |
| Minimum pitch confidence | 0.9107 | The same nine body-root measurements. |
| Maximum 4×/8× relative RMS residual | 0.00597116 | TUNE 0/0.5/1, METAL 1, COLOR 1 and PUNCH 0; render-rate convergence includes filter and resampling differences. |

The owner heard no useful PUNCH movement and weak METAL/COLOR travel in R1.
The matched audit below compares RMS over 0–10 ms with 10–50 ms; the ratio
cancels a single gain applied to the whole clip.

| PUNCH | 0 | 0.25 | 0.5 | 0.75 | 1 |
|---|---:|---:|---:|---:|---:|
| R1 attack/body ratio, dB | 3.442 | 3.647 | 3.748 | 4.064 | 4.835 |

The total measured range was only 1.393 dB. The first 40 ms power centroid also
showed how much the two timbre controls depended on their combined extreme:

| Revision 1 setting | Early power centroid |
|---|---:|
| Default: METAL 0.25, COLOR 0.5 | 246 Hz |
| METAL 1, COLOR 0.5 | 313 Hz |
| METAL 0.25, COLOR 1 | 254 Hz |
| METAL 1, COLOR 1 | 397 Hz |

## Why it is built this way

- PUNCH changes contact duration once, in the source. A rounded rise and a hard
  strike need different energy timing after matching, without a second endpoint
  spike from the shared transient processor.
- The edge keeps inharmonic material present beyond the first transient. An
  edge that disappears immediately leaves most control positions sharing the
  same body.
- COLOR has more finite harmonics to reveal, even with METAL at zero. Fixed
  pitch fall keeps brightness from doubling as a pitch control.
- Repeated within-revision sweeps reduce the memory burden of comparing short
  isolated hits. Preserved WAVs keep old/new comparisons honest.
- The risky decision is the stronger bright, metallic endpoint. Numerical
  conditioning and pitch probes bound it; the owner's listen decides whether
  the range is musically useful.
