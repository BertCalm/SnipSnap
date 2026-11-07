# ARCO round two research: the sarangi's tarab, the erhu, the buzzing bridge, and the held keygroup

**Date:** 2026-11-04
**For:** [`2026-09-29-arco-bowed-string-engine-design.md`](2026-09-29-arco-bowed-string-engine-design.md),
its R2 row ("the research pass in SILK's form, between R1's gate and this round") and its
decisions 9 to 12.
**Form:** SILK's research pass
([`../plans/2026-09-27-silk-research.md`](../plans/2026-09-27-silk-research.md)): every outside
fact tied to a source that was opened and read, with its status; every repo fact grepped at a
named commit; every number labelled with where it came from.
**Repo read at:** `e6ca59ae` (the instruments work branch, merged with the default branch).
**Scope:** research only. The owner asked on 2026-11-04 for the full round two with BUZZ as the
sixth macro, then changed the plan the same day to findings, not code. No engine, maker, test or
app file was written for this round. Nothing here has been heard by anyone.

## 0. How to read this file

Status labels for outside facts, SILK's legend:

- **confirmed**: the source was opened directly and the quoted words match it.
- **secondary**: opened directly, but the source is a practitioner's site, a hobbyist survey or an
  encyclopedia, not a measurement. Usable for descriptive facts, not for constants.
- **unsupported**: seen only in a search snippet or an abstract, or the full text could not be
  opened. **Not to be used in code.**
- **computed**: this file's own arithmetic on sourced or verified inputs, the working named.

Labels for repo and engine facts, the house ledger:

- **verified**: grepped or read in the repo at `e6ca59ae`, file and line named.
- **measured (Kotlin)**: a number from the ARCO design record's "as built" paragraphs, with the
  test that produced it. Not re-run in this pass (no Gradle runs, by the owner's instruction).
- **measured (Python harness, not Kotlin)**: from this pass's throwaway probe (section 6). It
  uses a stand-in string, not `Strings.Bow`.
- **proposed**: a design claim, a shape constant or a hypothesis.
- **heard**: only the owner hears. Where a verdict is quoted it is from the repo's own record.

Access in this pass. Wikipedia, chandrakantha.com, ragajunglism.org, the ULB repository record,
the Forum Acusticum 2005 archive (euracoustics.org) and the Transilvania University of Brasov
bulletin answered and were read in full (the two PDFs through `pdftotext`). The HAL full text of
Demoucron and Weisser 2012 (`hal-00810608`, both the `hal.archives-ouvertes.fr` record and
`hal.science/.../document`) returned a bot check page and was **not** bypassed; only the abstract,
from the ULB record, was read. The Institute of Acoustics PDF of Dobbins's hurdy-gurdy
introduction returned 404. No measured sarangi tarab decay, no measured sarangi body mode and no
measured chien buzz spectrum were found in anything that could be opened.

## 1. The answers, in one table

| Item | Finding | Recommendation | Cost (proposed) | Risk | Owner decides |
|---|---|---|---|---|---|
| **1. SARANGI and WASH** | A sarangi has 3 bowed gut strings and up to about 35 to 37 metal tarab, one row chromatic and the others tuned to the raga (confirmed, §2). Its Sa sits near D#3 for solo playing (secondary, §2), a fifth or more below ERHU's D4 root. SANTUR's bank (`Silk.washModesFor`, CHROMATIC, three octaves) builds exactly 37 fixed modes (computed) and settles inside ARCO's 2.0 s warm-up at its shipped t60 of 0.3 to 1.1 s (seam 3e-12 at 1.1 s; a 5 s tarab fails the bar at 1.2e-3, Python harness). A steady loop rings the bank only where the note's harmonics land within its narrow bandwidth: 4 cents of bow error costs 2.1 dB of tarab at t60 1.1 s and 7.4 dB at 3 s (Python harness). | WASH on SANTUR's bank (decision 11's default), CHROMATIC, t60 kept short (at most about 2 s), driven by the string after the box and cut where the string ends, as the box is. Hold `Pluck.sympathetic`'s lift into `Strings` for the gate's "resonators, not strings" verdict. Root: an owner choice (§7.3). | WASH about 60 lines; the voice's arms about 25 (12 `when` arms in `Arco.kt`); presets 4 to 8; tests about 150. Every ARCO test that walks `ArcoVoice.entries` (verified: 87 uses of `ArcoVoice.entries` across 12 test files) gains a third voice and its run time. A root at C3 adds one R1b-style window map. | WASH heard as "absent" (SANTUR's own history, heard), or as "resonators, not strings" (the spec's own gate question). The sixth-macro budget (§7.4). | root (ERHU's D4 or a sarangi C3/D3); WASH's slot under the 3 to 6 rule; tarab tuning (CHROMATIC or a raga row) |
| **2. BUZZ** | The real chien is a loose bridge whose free foot knocks the soundboard, **synchronised with the string's period**, armed by accelerating the wheel (confirmed, §4). Jawari is a different mechanism: the string itself grazes a curved bridge (confirmed, §4). `Terra.applyBuzz` is threshold-gated noise scaled by the excess, a no-op below 0.001 (verified). On a periodic stand-in, Terra's shape with **free-running** noise fails the seam bar from about BUZZ 0.7 (1.9e-3 at 1); the same buzz with **noise that repeats with the loop** closes to float precision at every BUZZ (Python harness). It is monotone (+6.0 dB of between-harmonic energy per doubling) and at BUZZ 0.25 puts about -43 dB between the harmonics, near BORE's one-shot breath (-42 dB, measured Kotlin, verified in the roadmap). | BUZZ as specified: Terra's shape on the raw string at 176.4 kHz before the box, threshold referenced to the render's own RMS, exactly a no-op at 0 (return the same buffer, so `ArcoFrozenR1cTest` and every preset are untouched by construction), the noise indexed modulo the loop's raw length in LOOP and held renders, BUZZ kept out of SCRAMBLE's random stream. A period-locked chien (an impact model) is WHEEL's R3 research, not the sixth slot. | about 40 lines in the engine; about 120 lines of tests (the no-op sample for sample, monotone, bounded corners, every fuzz loop's seam with BUZZ on). | "audible at low settings" is a proxy until heard; the hiss of a 2 s noise loop repeating could itself be heard as a loop. | the threshold law (fixed, or falling with BUZZ); whether BUZZ may be a modest "life" for frozen loops |
| **3. The held keygroup** | The R1 LOOP plan (`Bore.planLoop`, `Bore.measureLoopSamples`, retune until 3e-7, warm-up 2.0 s and 200 periods, `requireSeam` at 1e-3) closed every loop R1 measured, the worst 2.5e-4 (measured Kotlin, 225 loops plus the 90-loop fuzz). The spec's table that warns about ERHU's high notes is the half-frame rounding **before** the retune; after it, the risk at ERHU's top is not the seam but whether the string speaks: ERHU stops at A5, B5 is outside its window and D6 has no single-slip cell (measured Kotlin). Of nine minor-third zones from D4, **B5 and D6 fall outside ERHU's range** (computed). The sibling periodic loop in BORE was heard twice as "a cheap keyboard" (heard). A tape vibrato snapped to whole cycles per loop closes to float precision; at the free 6.1 Hz it misses the bar by 50 to 270 times (Python harness). | Build the zone as the one-shot's own stroke (BOW's attack and bite) on an endless gate, the retune and warm-up as the LOOP has them, cut at a zero crossing, `requireSeam`, one retry at 2x settle, never a crossfade. CELLO: nine zones C2 to C4. ERHU: eight zones, D4 to G#5 every minor third plus A5, with B5 and D6 refused by name. Two layers at the patch's macros and at 0.28 of BOW and GRIP (`atVelocity`'s factor). Put R1's existing LOOPs in front of the owner **before** building, because the held key will sound like them. | `ArcoKeys.arcoPad` about 120 lines (with one small internal hook in `Arco.kt`), `ArcoPadMaker` about 150, tests about 350. 0.3 to 1.0 s per zone-layer on the JVM (R1's LOOP render, measured), 18 zone-layers about 5 to 18 s; phone 2 to 4 times that (proposed, BORE's factor). About 10 MB of 24-bit WAV for CELLO and 8.5 MB for ERHU (computed). | the frozen loop heard as cheap; a soft layer that barely differs at ERHU's top (GRIP moves ERHU A5's corner only from 2828 to 3024 Hz, computed); the BORE R2 session changing `Bore.planLoop` or `Bore.measureLoopSamples`. | whether to hear R1's loops first; vibrato in the loop (against the organ's rule); ERHU's top zone; the soft layer's factor |
| **4. One-shot instrument, withheld LOOPs** | R1 withheld **no** LOOP: every one closed and `renderLoop` refuses by name if one ever does (verified, measured Kotlin). `Arco.render` already drives the bow at `Keys.midiHz(midiFor(...))`, the exact key, so a one-shot keygroup needs no new core; each zone plays within 4.0 cents of its key (the bow's own limit cycle, measured Kotlin), where the held zone's loop is retuned exact. | `ArcoKeys.arco(midi, voice, macros, bow)` in `Keys.fork`'s shape, shipped only with its consumer: a two-layer stab keygroup (a staccato instrument is not a sustained one, so rule 6 does not bite) or nothing. Nothing to restore for LOOPs; SARANGI's loops must be measured when it exists. | about 20 lines plus a test; a stab instrument about 30 more. | a stab keygroup of CELLO's low notes is a scrape (CELLO below G#2 locks late, measured Kotlin). | whether a stab keygroup is wanted at all |

The rest of this file is the evidence for each cell.

## 2. The sarangi and its tarab

| # | Fact | Kind | Status | Src |
|---|---|---|---|---|
| S1 | Three bowed playing strings of gut, "bowed with a heavy horsehair bow". | descriptive | confirmed | 1 |
| S2 | Stopped "not with the fingertips but with the nails, cuticles, and surrounding flesh", the fingers sliding on ivory or bone platforms with talc. So the left hand slides and does not press to a board: glides are the idiom. | descriptive | confirmed | 1 |
| S3 | The lower chamber is "covered with parchment made out of goat skin", which carries the bridge. A skin body, as the erhu's is (S9). No measured sarangi body mode was found. | descriptive | confirmed (no measurement) | 1 |
| S4 | The tarab number "up to around 35-37, divided into four choirs"; "On the inside is a chromatically tuned row of 15 tarabs", "on the right a diatonic row of nine tarabs", and the rest "are tuned to the important tones (swaras) of the raga". | descriptive | confirmed | 1 |
| S5 | Courtney: "one normal approach is to tune one bank of the side sympathetics chromatically, the other bank of side strings to the rag"; the playing strings are most basically "Sa, lower Pa, and low Sa". | descriptive | secondary | 3 |
| S6 | Sympathetic strings resonate most at unison and octaves; "Other harmonic combinations will cause sympathetic resonance at the fifth, fourth and major third", and they give "a lingering halo of sound" once a note sounds. | descriptive | confirmed | 2 |
| S7 | Where Sa sits: "Most exponents tune to around D# for solo recitals" (14 recordings: D 1, D#8, E 5, listed as D3 to E3), and "relocate Sa" to the Pa string to accompany female singers, nearer A#. | descriptive (a survey of recordings) | secondary | 4 |
| S8 | Demoucron and Weisser analysed sarangis recorded with and without their tarab and describe the sound with them as reverberant. Only the abstract could be read. | measurement (abstract only) | unsupported | 5 |

**What S1 to S8 mean for WASH and SARANGI (proposed unless labelled).**

- **The bank's size and tuning have a sourced default.** `Silk.washModesFor(root, SilkScales.CHROMATIC, 1f, t60)`
  builds 12 degrees times 3 octaves plus the top note, **37 fixed modes** (computed from
  `Silk.kt:841-850`, verified), the top of S4's count, and S4/S5 make a chromatic row the one
  tuning every sarangi has. A raga row is SilkScales' modal tables at best (none is a raga); a
  sarangi raga table would be new and sourced, or not built.
- **A tarab is a string, a mode is not.** Each real tarab has its own harmonic series, so a tarab
  on Pa answers the note's third harmonic and also rings its own octave; one fixed mode per tarab
  answers once. That is the gate's "resonators, not strings" question, and the reason
  `Pluck.sympathetic`'s loops (each a Karplus-Strong string, every partial) are the named fallback.
- **The halo is an onset and release effect.** S6's halo is what a tarab does after a note
  changes or stops. A LOOP or a held zone is a frozen note: in steady state a linear bank answers
  only at the note's harmonics, so in the loop WASH is a fixed colouring of the partials that
  land near a tarab (measured, §6 D). The halo lives in the attack of each zone and, in a
  one-shot, in the release, which ARCO cuts where the stopped string ends (`Arco.withBody`,
  verified). A keygroup's notes are separate samples, so a tarab never rings across a note change
  the way it does on the instrument. Disclosed, not fixable in a sampler.
- **The register.** S5 and S7 put a sarangi's playing range around lower Pa to two octaves above
  Sa, roughly A#2 to D#5. ARCO's ERHU is D4 to A5 (verified, `Arco.ERHU_ROOT_MIDI`,
  `ERHU_TUNE_SEMITONES = 19`), a fifth or more above it. The design's own line is "SARANGI is
  ERHU's body at C3 plus WASH".
- **The skin.** ERHU's box is the house MEMBRANE table at 293.66 Hz, labelled shape: modes at
  293.7, 467.9, 627.1, 674.1 and 856.7 Hz (computed from `Modes.kt:168`). The one erhu
  measurement read in this pass (S10) puts the first membrane mode at 820 Hz over a 120 Hz
  Helmholtz mode. Nothing measured exists for a goat-skin sarangi belly, so SARANGI's skin stays
  shape; reusing ERHU's box is the honest default and the owner's ERHU box verdict ("B, the box
  as shipped, unsure", heard, R1c) is the only ear on it.

## 3. The erhu

| # | Fact | Kind | Status | Src |
|---|---|---|---|---|
| S9 | "The inside string (nearest to player) is generally tuned to D4 and the outside string to A4"; range "from D4 up to A7"; "There is no fingerboard"; the bow hair passes "between them as opposed to over them"; "The python skin gives the erhu its characteristic sound." | descriptive | confirmed | 6 |
| S10 | Scanning laser vibrometry of one erhu's python-skin membrane (0.5 to 0.6 mm, 93.53 cm2) in a hexagonal box (L 132 mm, W 53 mm): "the fundamental mode (120 Hz) that occurred was the plane mode, which is the Helmholtz resonance of the soundbox"; "the first mode (1st), as a bowl, occurred at 820 Hz"; further modes at 1,570, 1,870, 2,080, 2,220 and 2,550 Hz; mode patterns grouped at 100-1,000, 1,100-1,900, 2,000-2,300, 2,400-3,000 and 3,000-3,700 Hz; "the mode shapes and frequencies ... depend on the exact boundary conditions". Acoustic excitation, one instrument, no damping values printed. | measurement | confirmed | 7 |

**What S9 and S10 mean here (proposed).** ERHU's D4 root is the erhu's open inner string (S9), so
the R1 voice is in its real register. S10 is the first measured erhu skin in the house's research
(the shamisen's, SILK §1, had its fundamental unresolved): a candidate replacement for the
membrane shape is a box of 120 Hz plus 820 and 1,570 Hz, which sits close to where R1e's scouts
put ERHU's BODY lift by ear (shelf 600 Hz, bell 800 Hz, `Arco.ERHU_LIFT_*`, verified). Changing
ERHU's box changes every ERHU sound and is decision 12's business, not R2's; it is recorded here
because SARANGI's skin would inherit whichever box ERHU has.

## 4. The buzzing bridge: chien, tromba marina and jawari

| # | Fact | Kind | Status | Src |
|---|---|---|---|---|
| B1 | "The buzzing bridge (commonly called the dog) is an asymmetrical bridge that rests under a drone string on the sound board." "When the wheel is accelerated, one foot of the bridge lifts from the soundboard and vibrates, creating a buzzing sound." | descriptive | confirmed | 8 |
| B2 | The tirant "adjusts the lateral pressure on the trompette", so a bridge is "sec" (hard to trigger) or "gras" (easy); the wrist stroke is the "coup-de-poignet"; "A long buzz can also be achieved by accelerating the wheel"; "More accomplished players are able to achieve six, eight, or even twelve buzzes within one turn of the wheel." | descriptive | confirmed | 8 |
| B3 | The hurdy-gurdy's and the tromba marina's moving bridge is "a moving or drumming bridge that regularly synchronised by the movement of the bowed string, knocks the harmony table as for the hurdy gurdy"; modelled as "a mass-spring system connected to the bowed string", "free in one direction it is stopped by the table in the other"; a good adjustment shows "phase ruptures synchronised with the knocks", and the nonlinearity "may lead to chaotic features as period doublings". | measurement and model | confirmed | 9 |
| B4 | Jawari: "the strings run over a wide bridge with a very flat parabolic curve" and the string "will make a periodic grazing contact with the curved surface of the bridge", sweeping the harmonics as it decays (tanpura, sitar, surbahar, rudra veena, Sarasvati veena; sawari in Japan). | descriptive | confirmed | 10 |

**What B1 to B4 mean for BUZZ (proposed).**

- **Two mechanisms, not one.** Jawari (B4) is the playing string's own contact with a curved
  bridge: it shapes the string's partials inside its loop, and the house already has it as
  `Strings.Loop`'s `jawari` fold-back (verified, `Strings.kt`, SITAR). The chien (B1 to B3) is a
  separate loose bridge under a drone string whose foot knocks the soundboard. Putting jawari
  inside a bowed loop would act on the friction junction's limit cycle and could move the lock
  the R1 window was drawn around; it is not this macro.
- **A real chien is periodic, not hiss.** B3 says the knocks are synchronised with the string,
  one per period when adjusted, and can period-double. TERRA's shape (excess into noise) is the
  cheaper analogy the spec chose for the sixth slot. A period-locked impact model would be truer
  and would repeat with a whole-period loop by itself when it does not period-double, but B3's
  doubling and chaos are exactly what a whole-period loop cannot hold. So: noise for the sixth
  slot on every voice, an impact model as WHEEL's research in R3.
- **The arming.** B1 and B2: the buzz is triggered by the wheel's acceleration. ARCO's only
  acceleration is BOW's bite (the design's own reading for WHEEL). A sixth-slot BUZZ that follows
  the note's level is not armed by the stroke; that difference is what the spec's audition item 11
  was to put side by side, and it stays R3's.

## 5. What the repo already has (verified at `e6ca59ae`)

- **SANTUR's bank.** `Silk.washModesFor(root, scale, gain, t60)` is `internal` (`Silk.kt:841`), so
  `Arco` can call it; `Silk.withWash` is `private` (`:657`) and is three lines over
  `Strings.bodyRing(string, modes, amount, rate, ceiling)` (`Strings.kt:1321`): t60
  `lin(WASH, 0.3, 1.1)` s and amount `lin(WASH, 0, 8)` times the string, RMS-matched. Its levels
  and ranges came from listening: "WASH's sympathetic bank is too quiet/absent" twice in SILK's
  Phase 4 pass (heard, `Silk.kt:145-157`), and t60 was cut to 1.1 s to keep SANTUR under the
  classifier's 1.5 s line.
- **PLUCK's tarab.** `Pluck.sympathetic` (`Pluck.kt:927`) is a private Karplus-Strong loop fed
  continuously by the played string, at feedback 0.995 under a 4 kHz low-pass, "a decay of about
  five seconds at C#4's fundamental and ten an octave below" (its KDoc), tuned to ratios of the
  played note (`SYMPATHETIC_SERIES` 0.5, 1.5, 2, 3; `SYMPATHETIC_SCALE` four scale degrees), four
  loops. It follows TUNE, which a sarangi's tarab do not.
- **TERRA's buzz.** `Terra.applyBuzz` (`Terra.kt:560`): `if (amount <= 0.001f) return raw`; then
  for every sample over `BUZZ_THRESHOLD` 0.12 (absolute), `out += (|s| - 0.12) * noise * amount *
  0.45`, `Dsp.Noise(seed)` free-running, applied at the oversampled rate before the output chain.
  `Tines.kalimba`'s BUZZ is the same shape (Terra's KDoc says so).
- **ARCO's LOOP.** `Arco.renderLoopMeasured` (`Arco.kt`): plan by `Bore.planLoop` (K = ceil(2 s
  times f) periods in whole frames), a steady stretch with a fixed 63 ms stroke and no bite, warm-up
  `max(2.0 s, 200 periods)`, the played period measured by `Bore.measureLoopSamples` and the whole
  stretch re-rendered at `tuned * ratio` up to five times until the ratio is within 3e-7, the seam
  read on the kept stretch against itself one loop later, cut at `Siren.bestCut`, levelled;
  `renderLoop` refuses by name above `Keys.MAX_SEAM_ERROR` (1e-3). `Bore.kt:981-990` keeps the
  plan and the estimator "because ARCO uses it": BORE's own loop no longer closes by period.
- **The one-shot's pitch.** `Arco.frequencyFor` is `Keys.midiHz(midiFor(voice, tune))`, "exact, no
  pin" (verified); the bow plays within 4.0 cents of it (measured Kotlin, R1b's tuning table).
- **The held makers.** `KeelPadMaker` (one `KeyNote` per zone), `BellowsPadMaker` (a list of
  three per zone, `VELOCITY_WINDOWS`) and `CreasePadMaker.renderLayers` (a list per zone, soft
  first) are the shapes; `LoopPadMaker.QUALIFIED` offers ARCO CELLO and ERHU today as loop-only
  keys with no attack (verified).
- **The app.** `SynthScreen.kt` reads `ArcoVoice.entries`, `Arco.macrosFor`, `Arco.defaults`,
  `Arco.scramble` and `Arco.render` (lines 5409 to 5888, verified), so a new voice and a sixth
  macro would appear on the phone without an `:app` edit, unheard.

## 6. Measurements (Python harness, not Kotlin)

The probe (`arco_r2_probe.py`, a throwaway in the session scratchpad, not committed) renders a
stand-in for the settled, retuned bow: a band-limited sawtooth with harmonics at 1/k under a
one-pole roll-off at the bridge corner (3023.6 Hz), **exactly periodic** with K periods in the
planned frames (`Bore.planLoop`'s formula), at 176.4 kHz, for the warm-up (`max(2 s, 200
periods)`), the loop and 2048 frames of pad. The output stage is snipkit's stand-in (8th-order
Butterworth at 20 kHz, two 2:1 windowed-sinc steps, mean and a 20 Hz one-pole). The seam is
`Keys.seamError`'s as ARCO reads it: the 256 frames at the start of the kept loop against the 256
one loop later. CELLO's box (104 Hz and 220 Hz, `Arco.CELLO_BODY`) is rung through a
`Strings.bodyRing` stand-in at 0.5. Noise is NumPy's seeded generator, not `Dsp.Noise`. Because
the stand-in is exactly periodic, every seam below is what a stage does to a perfect loop; the
real bow's own residual (up to 2.5e-4, measured Kotlin) comes on top.

**A. BUZZ and the seam.** Terra's shape, threshold 1.0 times the raw string's RMS, gain 0.45:

| BUZZ | CELLO C3, free noise | CELLO C3, loop-periodic noise | ERHU A5, free noise | ERHU A5, loop-periodic noise |
|---|---|---|---|---|
| 0 | 1.7e-24 | 1.7e-24 | 1.3e-23 | 1.3e-23 |
| 0.1 | 1.9e-5 | 1.8e-24 | 2.3e-5 | 1.2e-23 |
| 0.25 | 1.2e-4 | 1.4e-24 | 1.4e-4 | 1.1e-23 |
| 0.5 | 4.8e-4 | 2.5e-24 | 5.7e-4 | 1.0e-23 |
| 1 | **1.9e-3** | 1.7e-24 | **2.3e-3** | 1.1e-23 |

Loop-periodic means the noise sample at raw index i is a fixed table at `(i - warm) mod
loopRaw`, so the whole stretch repeats with the loop, warm-up included. Free noise fails the bar
of 1e-3 from about BUZZ 0.7 and would add its share to the bow's own 2.5e-4 well before.

**B. BUZZ is monotone; how much it adds.** Energy between the harmonics of the kept loop (the FFT
bins that are not multiples of K), and above 5 kHz, against the whole loop, dB:

| BUZZ | C3 between harmonics | C3 above 5 kHz | A5 between harmonics | A5 above 5 kHz |
|---|---|---|---|---|
| 0 | -244 (float floor) | -29.4 | -235 | -19.2 |
| 0.1 | -51.2 | -29.4 | -49.0 | -19.2 |
| 0.25 | -43.2 | -29.3 | -41.1 | -19.2 |
| 0.5 | -37.2 | -28.9 | -35.1 | -19.1 |
| 1 | -31.2 | -27.8 | -29.0 | -19.0 |

Exactly 6.0 dB per doubling of BUZZ (the energy goes as BUZZ squared at a fixed threshold). The
threshold sets the scale: at C3, 1.5 times RMS reads -67.8 / -59.8 / -53.8 / -47.8 dB at
BUZZ 0.1 / 0.25 / 0.5 / 1 (free-noise seam at most 4.6e-5), and 0.5 times RMS reads
-44.3 / -36.3 / -30.3 / -24.3 (free-noise seam up to 9.0e-3). The loop's peak over its RMS goes
from 2.06 to 2.23 at BUZZ 1 (threshold 1.0). The whole-loop energy grows by under 0.01 dB at
BUZZ 1 (threshold 1.0), so `Dsp.levelTo` would not audibly duck the string under the buzz at these
gains (an RMS proxy, not `Loudness.of`). For scale only: BORE's one-shot carried -42 dB between
its harmonics and its frozen loop -80 dB (measured Kotlin, `docs/SYNTH_ROADMAP.md` S19), and that
frozen loop is the one heard as "a cheap keyboard".

**C. A vibrato on the loop.** ARCO's tape vibrato (the finished string read back through a
swinging delay with a four-point cubic, `Arco.vibrato`'s form, 10 cents, onset 0.35 s, rise
0.2 s), applied to the whole stretch:

| note (loop length) | no vibrato | 6.1 Hz, free | snapped to whole cycles per loop |
|---|---|---|---|
| CELLO C3 (2.0029 s) | 1.4e-24 | **4.9e-2** | 1.1e-24 (5.9913 Hz, 12 cycles) |
| ERHU D5 (2.0006 s) | 4.1e-24 | **2.7e-1** | 5.0e-24 (5.9982 Hz, 12 cycles) |

A delay that swings with the loop's own period reads the same places of a periodic wave on both
sides of the wrap, so a vibrato whose rate fits the loop is as seamless as the string under it.
ERHU's plain vibrato is one sine and fits by moving 6.1 Hz to about 6.0 Hz. CELLO's human drift
(`VIBRATO_HUMAN`, three slow sines that repeat only after 100 s) would have to repeat every 2 s
to fit, which may itself be heard as a repeat. Both are against the design's "off in the LOOP
render" and the R2 brief's "vibrato off in the loop region": a proposal for the owner, §8.

**D. WASH: the warm-up, and the bow's tuning.** A 37-mode CHROMATIC bank from D#3 (155.56 Hz, a
sarangi's Sa by S7), every mode at gain 1, rung by the `bodyRing` stand-in at amount 1 on a D#4
note (311.13 Hz), ERHU's 2.0 s warm-up:

| bank t60 | 0.3 s | 1.1 s | 3 s | 5 s |
|---|---|---|---|---|
| seam | 4.9e-25 | 3.2e-12 | 2.9e-5 | **1.2e-3** |

The steady tarab level (the bank's output alone, measured over its last second of four) against
how far the note sits from the tarab's tuning:

| bow error | 0 c | 2 c | 4 c | 10 c | 50 c |
|---|---|---|---|---|---|
| t60 1.1 s | 0 dB | -0.73 | -2.13 | -6.38 | -21.87 |
| t60 3 s | 0 dB | -3.53 | -7.35 | -14.03 | -30.70 |

A tarab long enough to be a halo is narrow enough that the bow's own few cents decide how loud it
is. ARCO's one-shot plays within 4.0 cents of its key and its LOOP is retuned to a fraction of a
cent (measured Kotlin), so a one-shot and its own loop would not ring the same tarab equally, and a
vibrato sweeps the note across them (a real sarangi does this; whether it is wanted is a listen).

## 7. Item 1: SARANGI and WASH

### 7.1 Finding

S4 and S5 give the tarab a sourced count and a sourced default tuning (a chromatic row), and the
house bank builds exactly that (37 modes, computed). The bank settles inside ARCO's warm-up at
SANTUR's t60 range (§6 D: 3e-12 at 1.1 s), so a SARANGI LOOP and a held zone close as CELLO's and
ERHU's do, with no new loop machinery; past about 4 to 5 s of t60 the warm-up would have to grow
with it (1.2e-3 at 5 s). In a frozen loop the bank is a fixed colouring (§2), so the tarab's halo
is heard in each zone's attack and a one-shot's release, and the release is cut where the stopped
string ends unless the voice is allowed a longer render.

### 7.2 Options

1. **SANTUR's bank (decision 11's default).** `Strings.bodyRing(afterBox, Silk.washModesFor(root,
   CHROMATIC, 1f, t60), amount, rate, BODY_CEILING_SECONDS)`, cut to the string's length as
   `withBody` is. Cost about 60 lines. At 176.4 kHz it is 37 two-pole resonators per raw sample;
   run after the decimator at 44.1 kHz it is a quarter of that (the bank's top note, three octaves
   over the root, is about 1.2 kHz for a D#3 root). Not measured on the JVM.
2. **`Pluck.sympathetic` lifted into `Strings`** as its own no-audio-change PR (PLUCK's frozen
   renders held bit for bit), then 37 loops fed by the string. Truer (each tarab has partials, so
   it answers fifths and fourths through its own harmonics, S6) and the gate's named fallback. As it
   ships it rings 5 to 10 s, which fails the seam at ARCO's warm-up (§6 D) unless a loop render
   shortens its feedback or the warm-up grows; and it follows the note, which a sarangi's tarab do
   not. Cost: the lift about 80 lines with its frozen test, then the bank as option 1.
3. **No tarab, a plate**: rejected. A reverb is not a sympathetic bank and the rack already has
   ROOM and SPRING.

### 7.3 The root

- **A. ERHU's string and window, D4 to A5, plus WASH.** No new window, the R1 maps hold as drawn,
  and the voice is literally "ERHU plus WASH". It sits a fifth or more above a sarangi (S7).
- **B. A sarangi's register, C3 (or D3) for 24 semitones.** The design's "ERHU's body at C3". The
  GRIP window is a function of pitch on the same bow (the same position 0.133 and sustain
  velocity in both voices, verified), and R1b drew it at C3 to C4 (CELLO's semitones 12 to 24) and
  D4 to A5 (ERHU's), so only C#4 is undrawn; but the two voices' laws differ at the seam between
  them (CELLO's pressure floor is 0.85, ERHU's 0.94 for pitch's sake, verified), so GRIP would
  change its travel at D4 unless one law is drawn across. Cost: one R1b-style dense map (R1b's was
  3,330 cells) and the GRIP-is-not-a-pitch-knob test across the new span.

### 7.4 The macro budget

CELLO and ERHU with BUZZ have TUNE, BOW, GRIP, BODY, HOLD and BUZZ: six. SARANGI with WASH has
seven. The design counts HOLD among its five ("Five, inside the 3–6 budget", verified, the spec's
Macros section); the house conventions read "3 to 6 macros ... plus HOLD"; the roadmap's rule 1
says "3–6 macros, never a patchbay" (`docs/SYNTH_ROADMAP.md:1087`); GLINT already ships seven on
its voices other than VOWEL (DEPTH, "a seventh macro (VOWEL's sixth)", `docs/SYNTH_ROADMAP.md`,
S10). Three ways: seven under the conventions'
reading; WASH in BODY's slot on SARANGI (BODY fixed at its knee, the skin box at ERHU's default,
and no lift); or WASH baked at one amount with no knob.

### 7.5 Recommendation, cost, risk

**Recommended (proposed):** option 1 with CHROMATIC, WASH's t60 at most about 2 s, root A for this
round (no new window), and WASH in BODY's slot so SARANGI keeps six. Presets: four to six,
provisional and unheard, by ArcoPresets' rules (the real classifier, the 1.5 s line, the head
window), at least two of them loops. Cost about 250 lines with tests; the test suite's ARCO time
grows by about half (every walk over `ArcoVoice.entries` gains a voice). **Risks:** WASH heard as
absent (SANTUR, heard) or as resonators (the spec's gate); a register a fifth above the instrument
it is named for (root A); ERHU F5 and up already read as a snare to the classifier for slow bows and
short holds (measured Kotlin, R1b), and a bank can only add ring there.

## 8. Item 2: BUZZ, the sixth macro

**Recommended (proposed):**

- **Where:** on the raw string at 176.4 kHz, after the bow and the vibrato and before the box,
  so the box colours the buzz (a chien knocks the soundboard, B1) and the band limit and decimator
  take its top (TERRA runs it oversampled for the same reason, verified).
- **Shape:** Terra's. `excess = max(|s| - threshold, 0)`, `out = s + excess * noise * gain * BUZZ`.
  The threshold referenced to the render's own level: the RMS of the raw string over the bow-on
  part of a one-shot, over the kept loop window of a LOOP or a held zone. §6 B gives the scale; a
  starting point is 1.0 times that RMS and Terra's 0.45, or a threshold that falls from 1.5 to
  0.5 times RMS as BUZZ rises (a fainter bottom, a stronger top), the owner's choice by ear.
- **A true no-op at 0:** `if (buzz <= 0f) return raw`, the same buffer, not a copy and not a 0 dB
  pass, and gate at 0, not at TERRA's 0.001, so the knob is monotone from its first step. Every
  saved preset has no BUZZ key, `settled` fills 0, and `ArcoFrozenR1cTest`, every preset and the
  kit are unchanged sample for sample by construction; the proof is a test that renders every
  preset and every R1 corner with BUZZ absent and at 0 and compares with today's renders.
- **SCRAMBLE:** keep BUZZ out of `Dsp.scrambleNear`'s map and put the seed's value back after, so
  the random stream every existing scramble test reads is the same stream (`scrambleNear` maps
  every key it is given, verified, `Dsp.kt:149`); the design's "a no-op at 0 so SCRAMBLE never
  lands on it".
- **The loop and the held zone:** the noise read from a fixed table at `(i - warmRaw) mod
  loopRaw`, applied after the pitch iteration has converged (the estimator must not see the buzz),
  so the stretch is periodic with the loop and the seam is the plain's (§6 A). The box, band
  limit, decimator and high-pass are linear and settle in the warm-up. `Siren.bestCut` rotating the
  kept window changes nothing, the stretch being periodic with the loop.
- **Bounded:** the excess is at most |s|, so the raw peak is at most (1 + 0.45) times the
  string's own (R1a measured at most 1.169 over its 31 cells, under `Strings.Bow.RAW_PEAK_CEILING`
  1.25) under any threshold; the finish limits the rest.

**Cost:** about 40 engine lines and 120 test lines. **Risks:** "audible at low and middle
settings" is a number until heard (§6 B gives the proxy); a 2 s noise loop repeating may be heard
as a loop; the threshold reference differs a little between a one-shot (bow-on RMS, scratch
included) and its loop (steady RMS). **The owner decides:** the threshold law, and whether BUZZ
is also offered as the "life" a frozen loop lacks (§9).

## 9. Item 3: the held keygroup

### 9.1 The zone plan

Nine zones every minor third from each root, at `Bore.planLoop` and ARCO's warm-up (computed):

| voice | zones | loop | warm-up | zone length |
|---|---|---|---|---|
| CELLO | C2 D#2 F#2 A2 C3 D#3 F#3 A3 C4 (all in range) | 2.000 to 2.006 s, K 131 to 524 | 3.06 s at C2, 2.57 at D#2, 2.16 at F#2, 2.00 from A2 | 5.06 s at C2 falling to 4.00 s; 37.8 s a layer |
| ERHU | D4 F4 G#4 B4 D5 F5 G#5 in range; **B5 and D6 out of range** | 2.000 to 2.002 s, K 588 to 1662 | 2.00 s | 4.00 s |

The spec's per-note cents budget (a slip of 0.34 % of a period at the wrap) runs from 0.045 c at C2
to 0.0033 c at A5 and 0.0025 c at D6 (computed, its own formula). The retune's stop at 3e-7 of
the loop is about 0.0005 c and inside every one of them, which is why R1 measured every loop
closed: 225 loops at the default and four other corners at every step of both voices, worst
2.5e-4, and the 90-loop fuzz at 2.47e-4; with BODY above the knee 1.09e-4 (CELLO) and 8.25e-6
(ERHU), and 2.37e-4 / 8.51e-5 on random GRIP, BOW and BODY rows (all measured Kotlin, the design's
R1b and R1g paragraphs).

**Which zones are likely to fail, and why.** In range, none are expected to fail the seam: the
spec's warning table is the half-frame rounding without the retune (e of 3.0e-3 at C5 and 1.5e-2 at
D6), and the retune removes it. The real refusals are B5 and D6: ERHU's window stops at A5 because
"the first maps found no cell at D6 that slips once a period at any pressure the grip can reach,
B5 (21 semitones) the last step that does" (measured Kotlin, R1b), and the engine never drew a GRIP
window at B5. So a nine-zone ERHU refuses those two by name, or ERHU takes eight zones with A5 as
its top root (a semitone over G#5, Crease's "exact roots at clipped range boundaries", verified,
`CreasePadMaker.zoneMidis`) and the top keygroup repitches up to about F#6. Not measured on a held
zone: the attack before the warm-up is now the real stroke with BOW's bite, where R1's LOOP used a
fixed 63 ms stroke with none. A string that locks late "is still settling after it has locked"
(R1b), and R1c's lock grid reached 1.47 s across BOW (measured Kotlin), inside the 2.0 to 3.06 s
warm-up but with less room at CELLO's lowest zones; the one retry at 2x settle covers it, and a
test over every zone and both layers is what proves it.

### 9.2 The render

`ArcoKeys.arcoPad(voice, macros, midi, layer, cancelled): KeyNote` (proposed): TUNE from the
zone; the bow driven by `Arco`'s own `play` on an **endless gate** (the bow-on length is the
whole render, the attack and bite from BOW, no ramp, no stop, no vibrato), which needs one
small `internal` hook in `Arco.kt` because `play` and `Gate` are private; the raw string's first
samples are then the one-shot's own, bit for bit, at the driven frequency (verified by reading
`Arco.play`: `release` is 1 while `i < holdN`, and the lift is never reached). The whole render
re-rendered at `tuned * ratio` until the ratio is within 3e-7 (BORE's route, as the LOOP). The
attack therefore plays at the corrected pitch, within a fraction of a cent of the key, where the
one-shot plays within 4.0 cents of it: the same stroke, not the same samples. The loop start after
the warm-up, at `Siren.bestCut`; `Keys.requireSeam` at 1e-3; one retry at twice the warm-up; never
a crossfade; levelled on the loop region; `Dsp.limitPeak(0.99)` (`Keys.resinPad`'s ending,
verified, `Keys.kt:228-280`). BODY above the knee (the lift) and BUZZ run as in the LOOP.

**Cost and size (proposed, from measured parts).** R1's LOOP renders in 0.3 to 1.0 s on the JVM
(measured Kotlin), and a held zone is the same stretch: 18 zone-layers per instrument is about 5
to 18 s, a phone 2 to 4 times that (BORE's factor, a guess the design itself carries). 24-bit mono
at 44.1 kHz: CELLO two layers about 10.0 MB, ERHU eight zones two layers about 8.5 MB (computed).
The retry at 2x warm-up lengthens a zone to about 6 to 8 s and is expected never to fire.

### 9.3 The two layers

House rule: velocity is timbre, the MPC's velocity curve owns level ("peak-matched, timbre only",
`Velocity.peakMatch`'s KDoc, verified), so both layers level on their loop region to the same target.
The hard layer is the patch's macros; the soft layer is BOW and GRIP at 0.28 of their values
(`atVelocity`'s factor, the design's "0.28 x 0.6 is a 215 ms stroke or a light grip that still
speaks"). Every GRIP from 0 to 1 locks at every TUNE step at BOW 0.5 (0 of 495 cells fail, measured
Kotlin), so the soft layer's grip is inside the single-slip window by construction; its slower
stroke is covered by R1c's lock across BOW (at most 1.47 s against a 2.0 s bar, measured Kotlin),
not yet by a held-zone test. What it changes (computed from
the mappings at the defaults):

| | stroke | CELLO C3 pressure | CELLO C3 corner | ERHU A5 corner |
|---|---|---|---|---|
| hard (BOW 0.5, GRIP 0.6) | 63 ms | 0.940 | 1942 Hz | 2944 Hz |
| soft (BOW 0.14, GRIP 0.168) | 239 ms | 0.875 | 1204 Hz | 2860 Hz |

On CELLO the soft loop is clearly darker. At ERHU's top GRIP's corner only travels from 2828 to
3024 Hz (the corner floor rises with the note, verified, `ERHU_CORNER_RISE_SEMITONES` 21), so the
soft layer there differs mostly in its attack: a velocity layer that is nearly the same held
sound. Whether that is enough is a listen.

### 9.4 The risk that matters most

BORE built this exact plan, a whole-period loop with no noise and no vibrato, and the owner heard
it twice: "Solo loop sounds like a cheap keyboard", then "Still sound cheap" (heard, the BORE
design's Round 1.6). BORE then moved to a loop cut from the one-shot and closed by a blend
(Round 1.7), which the ARCO R2 brief rules out ("never a crossfade"). ARCO's own loops have not been
heard ("The owner has not heard the presets, the kit or the loops", `Arco.kt`'s header, verified).
A held keygroup sounds like its loop for every second after the first two to three. So the
cheapest step that can save the most work is to put R1's LOOPs (ENDLESS DRAW, ENDLESS CRY, a
default loop of each voice) in front of the owner before the keygroup is built. If they read as
cheap, two remedies keep the seam exact and need no crossfade (§6): a vibrato snapped to whole
cycles per loop, and BUZZ at a low setting as loop-periodic breath. Both are proposals; the first
overturns a rule the owner's brief restated.

Two practical notes. `Bore.planLoop` and `Bore.measureLoopSamples` are BORE's code kept for ARCO;
the BORE R2 session must not retire them, or ARCO takes them into its own file in the same change
(one quantity in one place). And a dedicated ARCO maker replaces `LoopPadMaker`'s ARCO row, so
`QUALIFIED`'s `"ARCO"` entry leaves in the same change, which touches another session's file.

## 10. Item 4: the one-shot instrument and the withheld LOOPs

**Withheld LOOPs: none.** "The record's 'withheld per voice by TUNE' is not built: no loop failed, so
`renderLoop` throws by name if one ever does" (measured Kotlin, the design's R1b paragraph); and
`renderLoop`'s `require` is in the code (verified). Nothing to restore. SARANGI's loops, if it is
built, are new and are measured then.

**The one-shot.** `ArcoKeys.arco(midi, voice, macros, bow): Snip` in `Keys.fork`'s shape
(`Keys.kt:647`, verified) is `Arco.render` at the TUNE that lands on the key, because
`Arco.frequencyFor` is already the exact `Keys.midiHz` of the snapped note; a range `require` naming
the voice's span; `Dsp.normalize` or the engine's own level, the owner's choice (FORK normalizes).
The design says it ships "with its consumer and never alone", because a keygroup of 0.3 to 4 s
notes is a string that stops under a held key (rule 6). Its honest consumer is a **stab
instrument**: two layers in `Keys.fork`'s shape (BOW at the patch's value and at 0.28 of it). Two
measured limits: below G#2 a CELLO stab is a scrape (it locks 0.5 to 0.9 s in, measured Kotlin),
and each zone plays within 4.0 cents of its key where the held zone's loop is retuned exact.

## 11. Decisions for the owner

Each with this file's default; none is built.

1. **Hear R1's loops before R2 is built.** Default: yes, the cheapest test of the riskiest
   assumption (§9.4).
2. **Vibrato in the loop.** Off, as the brief says (default), or a vibrato snapped to whole cycles
   per loop (seamless, §6 C) if the loops read as cheap.
3. **ERHU's held range.** Eight zones with A5 as the top root (default) or nine with B5 and D6
   refused by name.
4. **The soft layer.** BOW and GRIP at 0.28 of the patch (default) or a stronger soft grip; at
   ERHU's top either is mostly an attack difference.
5. **BUZZ's threshold law.** Fixed at 1.0 times the render's RMS with Terra's 0.45 (default) or
   falling from 1.5 to 0.5 times as BUZZ rises.
6. **SARANGI's register.** ERHU's D4 to A5 (default for R2, no new window) or a sarangi's C3 or D3
   for 24 semitones (one window map first). **Answered (the owner, 2026-11-07): D3, 24 semitones, D3
   to D5.** The window map was drawn first and every step speaks along GRIP (the design record's "R2:
   SARANGI, as built").
7. **SARANGI's knobs.** WASH in BODY's slot, six macros (default); seven under the conventions'
   reading; or WASH baked. **Answered (2026-11-07): WASH in BODY's slot.** TUNE, BOW, GRIP, WASH, HOLD
   and BUZZ; BODY held at its default with no lift, the box ERHU's skin.
8. **The tarab's tuning.** CHROMATIC, 37 modes (default, S4/S5) or a raga row, which would need a
   sourced table SilkScales does not have. **Answered (2026-11-07): CHROMATIC, 37 strings, D3 to D6,**
   driven by the string after the box and cut where the string ends, t60 at most about 2 s.
9. **SANTUR's bank or PLUCK's strings for the tarab.** The bank (default, decision 11's); the strings
   only if the gate hears "resonators, not strings". **Answered (2026-11-07): the bank.** PLUCK's
   sympathetic strings stay the fallback for a "resonators, not strings" verdict, unbuilt.
10. **A stab keygroup.** Build the one-shot only with it (default) or not at all.
11. **ERHU's box.** Unchanged (default, decision 12 and the owner's "B, unsure"); S10's measured
    120 / 820 / 1,570 Hz skin is on record for whoever reopens it.

## 12. Ledger

- **Verified (read in the repo at `e6ca59ae`):** `Arco.kt` (roots, spans, the window's laws, the
  stroke, `play`'s gate, the LOOP route, `renderLoop`'s refusal, the header's "not heard" line);
  `Silk.washModesFor` (internal) and `withWash` (private), SANTUR's WASH ranges and its listening
  notes; `Pluck.sympathetic` and its constants; `Terra.applyBuzz` and its gate; `Bore.planLoop`,
  `Bore.measureLoopSamples` and their "ARCO uses it" note; `Keys.resinPad`, `Keys.fork`,
  `Keys.seamError`, `Keys.requireSeam`, `MAX_SEAM_ERROR`; `KeelPadMaker`, `BellowsPadMaker`,
  `CreasePadMaker`, `LoopPadMaker.QUALIFIED`; `Dsp.scrambleNear`; `Velocity.peakMatch`;
  `Modes.kt`'s MEMBRANE ratios; `SynthScreen.kt`'s ARCO arms; `InstrumentSuite`,
  `InstrumentSidecar`, `InstrumentSuiteGenerator` (test-side), and that `testkit/Instruments` is
  committed.
- **Measured (Kotlin, quoted from the design record, not re-run):** every R1 loop closed (225 plus
  90, worst 2.5e-4; BODY rows 1.09e-4 and 8.25e-6; random rows 2.37e-4 and 8.51e-5); LOOP render
  0.3 to 1.0 s; 0 of 495 window cells fail; the one-shot within 4.0 cents; CELLO below G#2 locks at
  0.5 to 0.9 s; R1c's lock grid 1.47 s; D6 has no single-slip cell and B5 is the last step that
  locks; ERHU F5 and up read as a snare for slow bows and short holds; BORE's between-harmonic
  energy -42 dB (one-shot) against -80 dB (frozen loop).
- **Measured (Python harness, not Kotlin, a stand-in string):** §6 A to D: BUZZ seam with free
  and loop-periodic noise, BUZZ's between-harmonic and high-band energy at three thresholds, the
  vibrato seam free and snapped, WASH's seam against t60 and its level against the bow's error.
- **Computed:** the zone plans, lengths and cents budgets; the 37-mode bank; ERHU's box modes; the
  soft layer's stroke, pressure and corners; the WAV sizes.
- **Confirmed outside facts:** S1 to S4, S6, S9, S10, B1 to B4. **Secondary:** S5, S7.
  **Unsupported:** S8 beyond its abstract; the sarangi's range as "almost two octaves" (a search
  snippet only, not used above as a number).
- **Proposed:** every recommendation, the threshold laws, the cost and phone-time estimates, the
  hook in `Arco.kt`, the loop-periodic noise, the snapped vibrato as a remedy, WASH's slot.
- **Heard:** only what the repo records the owner heard: ARCO R1's held notes, stab, knob ends and
  BODY pages (not its presets, kit or loops); SANTUR's WASH "too quiet/absent"; BORE's periodic
  loop "a cheap keyboard". Nothing in this file has been heard by anyone.

## 13. Why it is built this way

1. **The held zone is the one-shot's own stroke, then a frozen bow.** The owner's pass rule is that
   a bow is a bow in its held note and its first few hundred milliseconds; the attack is the part
   no loop can fake, so it is rendered, not stitched. Everything after the warm-up must repeat
   exactly, because a sampler plays the loop forever.
2. **Anything added to a loop must repeat with it.** The measurement is blunt: free noise breaks
   the seam bar from about BUZZ 0.7 and a free vibrato misses it by 50 to 270 times, while the same
   noise or vibrato fitted to the loop's length is as seamless as the string. That one rule is what
   lets BUZZ, and maybe a vibrato, live in a loop with no crossfade.
3. **The tarab is short and fixed on purpose.** A long tarab sounds more like a sarangi but needs a
   warm-up as long as its ring (a 5 s tarab fails at ARCO's 2 s) and is so narrow that the bow's
   few cents decide its level. SANTUR's 0.3 to 1.1 s bank settles and forgives; if it is heard as
   resonators rather than strings, PLUCK's strings are the next step.
4. **The chien in the sixth slot is an analogy, labelled.** A real chien knocks once a period and
   can period-double; noise above a threshold is the cheap, bounded, loopable stand-in the design
   chose. The period-locked knock belongs to WHEEL, where a drone string carries it.
5. **The one risky decision is building R2 before anyone has heard an ARCO loop.** BORE's identical
   plan was heard as a cheap keyboard. Listening to two existing loops costs minutes; building the
   keygroup costs a round.

## Sources

| # | Source | Read | Status |
|---|---|---|---|
| 1 | Wikipedia, "Sarangi", https://en.wikipedia.org/wiki/Sarangi | 2026-11-04 | confirmed |
| 2 | Wikipedia, "Sympathetic string", https://en.wikipedia.org/wiki/Sympathetic_string | 2026-11-04 | confirmed |
| 3 | D. Courtney, "Sarangi", chandrakantha.com, https://chandrakantha.com/?p=15658 | 2026-11-04 | secondary |
| 4 | "Survey of Sa Tunings", ragajunglism.org, https://ragajunglism.org/ragas/instruments/sa-survey/ | 2026-11-04 | secondary |
| 5 | M. Demoucron and S. Weisser, "Bowed strings and sympathy, from violins to indian sarangis", Acoustics 2012 Nantes; record https://difusion.ulb.ac.be/vufind/Record/ULB-DIPOT:oai:dipot.ulb.ac.be:2013/336902/TOC; full text https://hal.science/hal-00810608 refused by a bot check, not bypassed | 2026-11-04 | abstract only, unsupported |
| 6 | Wikipedia, "Erhu", https://en.wikipedia.org/wiki/Erhu | 2026-11-04 | confirmed |
| 7 | W. Long and C.-C. Chien, "Use of scanning laser technology to obtain the erhu vibroacoustic", Bulletin of the Transilvania University of Brasov, Series II, 15(64) no. 2, 2022, doi 10.31926/but.fwiafe.2022.15.64.2.7, https://webbut.unitbv.ro/index.php/Series_II/article/download/2840/2523 | 2026-11-04, full text | confirmed |
| 8 | Wikipedia, "Hurdy-gurdy", https://en.wikipedia.org/wiki/Hurdy-gurdy | 2026-11-04 | confirmed |
| 9 | A. Padilla and V. Gibiat, "The 'trumpet marine', an instrument between a trumpet and a violin", Forum Acusticum 2005, Budapest, https://dael.euracoustics.org/confs/acoustics2008/data/fa2005-budapest/paper/306-0.pdf | 2026-11-04, full text | confirmed |
| 10 | Wikipedia, "Jivari", https://en.wikipedia.org/wiki/Jivari | 2026-11-04 | confirmed |
| 11 | P. F. Dobbins, "Introducing the hurdy-gurdy", Institute of Acoustics, https://www.ioa.org.uk/system/files/publications/PF%20DOBBINS%20INTRODUCING%20THE%20HURDY-GURDY.pdf | 2026-11-04 | not read (HTTP 404) |
