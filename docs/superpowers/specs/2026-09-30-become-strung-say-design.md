# BECOME, STRUNG and SAY — three quick wins from parts the house already owns

**Status:** design; Group A of the CHIMERA brainstorm, the three sounds that
need no new engine: a MORPH that moves in time (BECOME), the clap's
sympathetic strings as a pad-sheet chip (a tappable treatment button, STRUNG),
and a vowel filter that makes any pad speak one of VOX SPEAK's eight words
(SAY). A1, BECOME's logic, is implemented, and its gate was answered on
2026-10-02 ("The A1 gate: BECOME"): A1b, BECOME's card row, follows under its
own plan. STRUNG and SAY are not implemented.
The brainstorm began with two outside documents proposing a meta-engine that
renders two engines and joins them; the six-lens review found that engine
does not compile and that five of its seven voices duplicate MUTATE or the
RING chip (synthesis). What survived as new were a handful of sounds the app
cannot make today; the owner heard nine of them in the "bold" round and kept
all three families. Each of the three here was built as a throwaway spike and
measured against the nearest thing the app can already make: normalised
cross-correlation (NCC, 1.0 = the same wave) 0.589 for BECOME against its
nearest MUTATE render (bold-talk §3), 0.487 for STRUNG (bold-ring clip 3) and
0.568 for SAY (bold-talk §2). All three are below the 0.9 bar the round set,
and all three carry an honest caveat, set out in "The sounds, measured". No
code is committed. This document lands as a docs-only PR (zero check runs by
design, `.github/workflows/tests.yml`; ARCO, BORE, FORK and MAGNET landed the
same way) beside its sibling, the Group B spec (the TERRA hook: HIT, BEND,
TALK), and the Phase-0 record.

**Date:** 2026-09-30
**Plan:** to be written per phase and named for it:
`docs/superpowers/plans/2026-09-30-a1-become.md`, `…-a3-strung.md` and
`…-a2-say.md`; the order is A1 BECOME, A3 STRUNG, A2 SAY ("Phasing and gates").
A1b, written after the A1 gate, is
`docs/superpowers/plans/2026-10-02-a1b-become-card.md`
**Related:** [`2026-09-29-magnet-valve-design.md`](2026-09-29-magnet-valve-design.md)
is the model for SAY: a rack section (the rack is the fixed-order chain of effect
sections any pad can carry) with a Phase 0, claims tests and a gate.
[`2026-09-29-arco-bowed-string-engine-design.md`](2026-09-29-arco-bowed-string-engine-design.md)
set this document's form and its ENSEMBLE section is the rack-section
precedent SAY follows. The Group B spec,
[`2026-09-30-terra-hit-bend-talk-design.md`](2026-09-30-terra-hit-bend-talk-design.md)
(the TERRA hook: HIT, BEND, TALK), shares
two pieces of groundwork with this one ("Architecture": the VoxSpeak guard and
the formant-track extraction; formants are the resonant peaks that make a vowel
a vowel). [`../plans/2026-09-30-chimera-phase-0-record.md`](../plans/2026-09-30-chimera-phase-0-record.md)
is the record of the CHIMERA experiments, kept outside the build as ARCO's
was; its numbers are the Group B spec's, and every number here is restated from
the bold round instead.
**Roadmap:** no `SYNTH_ROADMAP.md` row. BECOME is a knob on a shipped move,
STRUNG a chip on a shipped treatment family, and SAY a rack section; no
section has taken a row since S2.6 (`docs/SYNTH_ROADMAP.md:252`), the rack
is recorded in `README.md` and `docs/CLI.md`, and the Group B spec claims
S20 for the TERRA reach round.
**Evidence:** the bold round's two spike reports, read in full: the talk
family (`bold-talk`: clip 1 "the gong speaks", clip 2 "a kick becomes a bell")
and the ring family (`bold-ring`: clip 3 "a clap with sympathetic strings"); the
three design maps written against the tree at `1a2ec180` (`map mutate-morph`,
`map rack-sections`, `map body-treatment`) and their completeness review
(`critic`), which corrected several map cites and listed the surfaces the maps
missed; and a tree check done for this document at `75b550c1` (`tree check`:
every cite below was re-read there, and the diff from the evidence's pin
`5e3f5f3e` to `75b550c1` was listed file by file). Tags of the form
`bold-talk §2`, `bold-ring clip 3`, `map rack-sections §4`, `critic` name the
report and its section. The reports are local files in the evidence folder
(Appendix B), not in the tree, so every fact they back is restated in full and
the tag records only which report measured it; the spikes' own code and clips
are kept with them.

## Why Group A, and in this order

The owner asked whether SnipSnap could implement CHIMERA, heard 140 clips of
the review's candidates and said "they sound ok" (round one), then asked for
experiments to see what needed tweaking; round two (111 clips) was too many and
was cut to a nine-clip "start here". The owner's answer to how far a hybrid
should go was "I think we could go further/bolder", and the bold round (nine
clips, three families: steer, ring, talk) ended with **keep Steer, Ring and
Talk**. The owner then approved a regrouping by build seam:

- **Group A (this document), the quick wins:** MORPH over time, the clap's
  strings as a chip, and the gong-speaks section. Each reuses a part the house
  already owns and needs no new idea in an engine.
- **Group B (its own document), the TERRA hook:** HIT, BEND, TALK, where one
  pad's hit drives another engine's body.
- **Group C (later):** snare drives gong, word shapes bell, kick plucks string.
  "Snare rings the tines" is parked for FORK, whose 882-sample hammer rule it
  breaks; Ring dissolves into the other groups.

The owner chose the order **"A alongside B"** and approved this group's design
as one section. Inside the group the order is **BECOME, STRUNG, SAY**, smallest
first: BECOME is one loop in one function plus a second knob on a card that
exists (bold-talk §6 guessed about 40 lines of DSP and half a day); STRUNG is a
new keyed chip (a treatment that reads the kit's musical key) whose strings are
a loop the house already wrote and keeps private; SAY is a new rack section with a new file of shared groundwork under
it, and it is the one the Group B spec's TALK also leans on.

### What the owner hears

**BECOME, "a kick becomes a bell".** MUTATE's MORPH blends a pad and a parent
into one sound *between* them: one number for the whole hit, the same at the
attack as at the tail. BECOME is a second row under it, 0 to 2000 ms, where 0
is off and the move is today's. With it set, the hit starts as the pad and
turns into the MIX blend (MORPH's own amount knob) over BECOME milliseconds from
the aligned onset, so
the first beat is the kick and what rings is the bell. The result is as long as
the end of the blend: a kick that becomes a bell rings as long as the bell.

**STRUNG, "a clap with sympathetic strings".** A new chip on the pad sheet's
keyed row, next to BODY. Strings tuned to the kit's key chord ring out of any
hit, rung by the hit's own noise, with a slight shimmer from detuning, while
the strike itself is left alone. With no key set they follow BODY's rule: the
hit's own pitch class, else C. One AMT (the chip's single dial, 0 to 1), like
every chip; AMT 0 returns the same sound object.

**SAY, "the gong speaks".** A new rack section: a vowel filter whose
formants (the resonant peaks that make a vowel a vowel) move along the path of
one of VOX SPEAK's eight words, after the engine, on any pad. WORD picks the
word, SPEED stretches it, SIZE scales the throat from child to giant, MIX is
dry to wet. The strike is kept (a short dry lead), the word is spoken through
whatever the pad rings with, and when the word is over the filter holds where the word
ended, its final formant frame, as the clip the owner kept did ("The filter", step 3, names
that frame for each of the eight words; for ONE and SEVEN it is the closing n, a hum). It is
a filter, not a voice: the consonants' noise and bursts are not made, so a gong says
"wuh-n" through its own partials and then hums on the n.

### Tree check

The evidence was pinned at `5e3f5f3e`; the maps at `1a2ec180`; this document
reads `75b550c1`. Between the pin and `75b550c1` **no file the three sounds
are made from changed**: `git diff 5e3f5f3e 75b550c1` over `Mutate.kt`,
everything under `audio/` (Spectral, Pghi, Body, Transplant and the rest),
`VoxSpeak.kt`, `Vox.kt`, `Pluck.kt`, `Strings.kt`, `Tide.kt`, `Thump.kt`,
`Tines.kt`, `Keyed.kt` and `PadSheet.kt` is empty (tree check). The drift
the Group B spec re-measures (TERRA's reverted attack compressor, FORK's
REED family and WIDTH macro, a macro being one 0-to-1 knob that moves several parameters) touches none of them. The rack files that
changed are VALVE's own: `Valve.kt` (new), `FxChain.kt` (+6/−1, `valve`
appended to the constructor and its row) and `Treatments.kt` (+6, `amped`
appended to the extras). Other files this document cites also changed in that
range and were re-read: `Silk.kt` (+25/−2, the OUD and GUZHENG COURSE and BODY
defaults), `SynthScreen.kt` (+16/−4) and `SynthCommand.kt` (+12/−2) (the string
machine's landing), and `README.md`, `docs/CLI.md` and `docs/SYNTH_ROADMAP.md`;
every cite of them below is correct at `75b550c1`, and none of their changes
touches a part the three sounds are made from.
So **the three sounds' numbers need no re-measurement**, and none is taken from
the Phase-0 record, whose numbers are the Group B spec's. The code cites in this document were re-read at `75b550c1`; the
maps' cites for `PadSheetScreen.kt` had drifted 13 to 31 lines (critic §2), so
that file is cited by symbol with the line only as a pointer.
Rechecked at `129bc48e` just before landing: of the files this document names,
`PadSheet.kt`, `PadSheetTest.kt`, `Treatments.kt`, `TreatmentsTest.kt`, `SynthScreen.kt`,
`Ensemble.kt`, `Dsp.kt`, `Velocity.kt`, `README.md`, `docs/CLI.md`, `docs/SYNTH_ROADMAP.md`,
`synth/build.gradle.kts` and the ARCO design moved.
One change reaches this design: SECTION took the TIME row's last slot (commit `8fd72ae3`),
which moves SAY's chip to the anatomy row (Decision 17) and raises the chip counts by one.
In the others only lines moved, and the cites keep their `75b550c1` lines: `Treatments.kt`
by +9 after line 68 (SECTION's `sectioned` treatment, appended last to the extras, so
`TreatmentsTest.kt`'s names list now ends `amped`, `sectioned`, and `said` follows),
`Ensemble.kt` (the chorus's one clock per frame is now at `:305`, and the new players keep
the rule), `SynthScreen.kt` by +1 to +16 (GLINT's MAKE INSTRUMENT), `Velocity.kt` (a doc
comment only, after the lines cited), `Dsp.kt` (one comment), `README.md` and `docs/CLI.md`
(SECTION's lines, +1 to +4 and +3), `docs/SYNTH_ROADMAP.md` (the S10 row rewritten in place, so
the lines cited hold), `synth/build.gradle.kts` (−19 lines from line 80: the task cited at
`:336-343` is at `:317-324`) and the ARCO design (+82 lines at `:1474`; the ruling cited at
`:1947-1948` is at `:2029-2030`). `FxChain.kt`, `VoxSpeak.kt`, `Mutate.kt`, `Pluck.kt` and
the rest of what the three sounds are made from are byte-identical, so the three sounds'
numbers still need no re-measurement.

## The sounds, measured

The bold round built each sound as a spike (throwaway code, kept with the
evidence) and compared it with every render the app can already make that
might stand in for it. Appendix A restates each recipe in full; this section is
what the numbers say. The tables name MUTATE's moves (`Mutate.kt:9-35`): MORPH
blends two pads' spectra bin by bin, SPLICE joins the pad's attack to a parent's
body, STACK layers them, SPLIT takes the pad below a crossover frequency and the
parent above it, ROOM plays the pad inside the parent's tail as if it were a room,
and TRANSPLANT dresses the pad in the parent's long-term spectral colour.

**How to read them.** The NCC is the waveform's normalised cross-correlation:
both sounds folded to mono, each aligned to its own onset (the first sample at
1 % of its peak), compared from 20 ms after it, at the best lag within ±64
samples, over the overlap, scale-free (bold-talk §1; the ring family also takes
the better polarity, bold-ring). It mostly measures phase agreement, so every
table also carries a **phase-blind** figure, the Pearson correlation of the
log-magnitude spectrogram (1024-sample window, 512 hop; bold-talk: 40 to 8000
Hz from 20 ms, floor 80 dB under the peak; bold-ring: 80 Hz to 10 kHz, floor
70 dB). The two disagree usefully: the plain gong alone correlates only 0.458
with the gong that speaks, though it is the same gong, because a formant
filter changes every partial's phase (bold-talk §1). The bar was an NCC under
0.9 against the nearest thing the app can make.

| | **BECOME** (clip 2) | **STRUNG** (clip 3) | **SAY** (clip 1) |
|---|---|---|---|
| Nearest in the app, by NCC | **0.589**: MUTATE SPLICE at 100 ms, kick base, bell parent; MORPH .5 0.552 (either order), STACK 0.531, SPLICE at 40 ms 0.523, SPLIT at 200 Hz 0.510; best rack chip 0.364 (RING on the kick); the plain kick alone 0.446, the plain bell 0.285 (bold-talk §3) | **0.487**: MUTATE ROOM at 0.5 with a plucked A-minor chord as the room; the dry clap alone 0.470, SPLIT at 2.5 kHz 0.471, the SPRING chip 0.461, the RING chip 0.403 (bold-ring clip 3) | **0.568**: the CONTOUR chip on the plain gong (CUTOFF .5, CREAM .5, SWEEP .9); the best MUTATE render 0.554 (TRANSPLANT at 8 bands, gong base, VOX SPEAK ONE donor); the plain gong alone 0.458 (bold-talk §2) |
| Nearest, phase-blind | 0.92 to 0.93: SPLIT at 2 kHz 0.927, MORPH at 1.0 0.925, SPLICE at 40 ms 0.921 (bold-talk §3) | 0.94 to 0.96: STACK of the clap over the plucked chord 0.963 (NCC 0.28), MORPH 50 % 0.958, SPLIT at 2.5 kHz 0.955, ROOM 50 % 0.942; BODY on the clap 0.853 (bold-ring clip 3) | 0.907: MUTATE SPLICE at 40 ms of the gong onto a VOX SPEAK ONE, which is the spoken word itself in another voice (bold-talk §2) |
| What the number does not say | the sound is a hand-over between two spectra, so a spectrogram of "a kick, then a bell" looks alike whatever joins them | the strings are rung by the hit's own noise and shimmer; a spectrogram of any chord under a clap looks alike | the formants of the gong's partials follow the word; the NCC is low because phase moved, not because the gong changed |
| The honest risk, in the report's words | "the clip closest to what the app already does ... the owner may well hear it as MUTATE's splice with a longer seam" (bold-talk §3 and §7 item 3) | "an ear may hear 'a clap over a chord'; the spike does not claim otherwise" (bold-ring caveats) | "whether the gong is heard as saying 'one' is unverified"; the opening 0.04 to 0.14 s and the closing n are the weak parts (bold-talk §7 item 1, §2) |

The notes were written before anyone listened; the owner's **keep** of the
talk and ring families is the only ear evidence there is, and it was given
about the spikes' clips, which are not bare BECOME, STRUNG or SAY (below). The
gates exist to hear the built sounds.

### BECOME: what was measured

- **The spike at a constant amount is MORPH, exactly.** With the amount held at
  0.5 (no delay, no crossfade, the bell at the kick's loudness) against
  `Mutate.render` MORPH at 0.5: NCC 1.0000 and spectrogram correlation 1.0000
  (lengths 50 495 against 37 589 samples: MORPH interpolates the length, the
  spike kept the longer part). So clip 2 is exactly MORPH with its one number
  turned into a curve (bold-talk §0). This is the evidence for "BECOME 0 is
  today's MORPH".
- **The hand-over, in dB in 2048-point windows at 0, 20, ... 140 ms.** Below
  120 Hz: 56, 55, 53, 50, 44, 35, 16, 0 (the kick alone: 57, 57, 57, 56, 55,
  54, 51, 48). From 200 to 1500 Hz: 39, 39, 48, 52, 54, 55, 54, 53. The swap is
  done by about 120 ms; both bands are within 10 dB of each other from 40 to
  100 ms (bold-talk §3).
- **PGHI keeps the kick's body through the ramp.** PGHI (phase gradient
  integration, the way MORPH re-invents phases from magnitudes) matched the
  ideal "(1 − a) × kick" band level below 120 Hz to within 1 dB at 0, 20, 40,
  60, 80 and 100 ms (clip 56, 55, 53, 50, 44, 34 dB against the ideal 56, 55,
  53, 50, 44, 33; bold-talk §3). It does smear the head: up to one frame of
  pre-echo, where the amount is about 0 (bold-talk §3), which is why the spike
  crossfaded the kick's own first 15 ms back over it.
- **A short kick leaves a hole.** A kick of DECAY .42 without HOLD ended before
  the bell arrived and the morph dipped 8 dB at 50 ms; with HOLD .3 the two
  overlap and the envelope is smooth: −3, −4, −4, −7, −10 dB per 50 ms
  (bold-talk §3). The gate's sources must overlap in time.
- **Consonance is not automatic.** The spike tuned the bell so every partial
  sits on the kick's harmonic series (the carrier 220 Hz = 4 × 55 Hz, the first
  six partials within 6 cents, bold-talk §3). Two arbitrary pads will not; a
  retune of the parent is extra work (bold-talk §6) and out of scope here.

### STRUNG: what was measured

- **The attack is the clap's own.** The strings get no feed for the first 40
  ms; the first sample at which the clip differs from the dry clap is 1862 (42.2
  ms), so `max |clip − dry clap|` over the first 40 ms is exactly 0.0. The
  attack correlation of the total against the dry clap is 0.933 over the first
  50 ms and 0.635 over the first 100 ms, where the bloom overtakes the clap's
  tail by design. The alternatives tried, by the same first-50-ms correlation:
  no gate at level 1.0 0.836; a 30 ms gate at coupling .02, level 2.0 0.857 and
  level 3.0 0.740; a 40 ms gate at coupling 0, level 3.0 0.907; the chosen 40 ms
  gate at level 2.5 0.933 (bold-ring clip 3).
- **Feedback .98, not the house .995.** At .995 the first render was 1.795 s
  long and its envelope was still within 60 dB of the peak at the end (dur60, the time the
  envelope takes to fall 60 dB under its peak, 1.791 s); at .98 the clip ends inside 1.6 s (dur60 1.013 s). Each loop's DC
  (constant-offset) gain is 1/(1 − feedback) = 50 at .98, which is why the feed is high-passed (a
  30 Hz one-pole, the simplest filter, subtracted) and the wet sum is DC-blocked at 2 Hz (bold-ring
  clip 3).
- **The balance.** RMS (root-mean-square, the average level) per window in dB: the dry clap −16.8 (0 to 50 ms), −22.3
  (50 to 100), −37.0 (0.1 to 0.2 s), −63.7 (0.2 to 0.4 s); the wet before its
  ×2.5 gain −33.2, −22.4, −27.9, −39.4, then −51.6 (0.4 to 0.8 s) and −67.9 (0.8
  to 1.2 s). The strings are quieter than the clap in its first 50 ms and louder
  than the clap's own tail from about 50 ms; the bloom dies over about 1 s
  (bold-ring clip 3).
- **The chord is heard.** Tail partials in the 0.3 to 0.7 s window, dB re the
  loudest: E3 165.2 Hz (+4 cents) −4.1, A3 219.7 −2.0, C4 262.1 −5.7, E4 331.1
  −12.3, A4 439.1 0.0, with the 3rd and 5th harmonics of the E3 string (B4, G♯5)
  at −8.4 and −17.1: the G♯5 is the one partial that spells E major under the A
  minor, 17 to 20 dB under the top partial (bold-ring clip 3).
- **Cost.** Five loops over the clap at 44.1 kHz: 3.6 ms, against 16.8 ms for
  one plain PLUCK NYLON render (bold-ring, render time).

### SAY: what was measured

- **The formants follow the word.** LPC (linear-predictive) peaks at order 14 at
  11.025 kHz, 40 ms windows, clip against the word's F1 and F2 targets: Pearson
  r 0.99 and 0.84, mean error 39 Hz and 71 Hz; the plain gong, same test: r 0.31
  and −0.24, error 506 and 425 Hz. At 0.32 s the word's targets are 607, 1213
  and 2431 Hz; the clip reads 635, 1235, 1995, 2675; the plain gong 1245,
  1995, 2750, 3550 (bold-talk §2).
- **The weak spots.** From 0.04 to 0.14 s the word's F2 (618 to 850 Hz) sits
  close to F1 and the two merge, so the opening "w" is one dark blob. In the
  last stretch the measured F2 slot drifts from 1445 Hz at 0.48 s to 1285 Hz at
  0.64 s while the target holds 1500: the closing nasal's wide, damped
  resonances are not resolved by the gong's partials (bold-talk §2).
- **Why the bandwidth is widened.** F1's own bandwidth is 60 Hz and the gong's
  partials are 98 Hz apart (f0/2 of the folded tone), so a narrow formant
  sweeping across sparse partials is expected to pulse in level (an argument,
  not a measurement). Measured, F1 and F2 tracking by bandwidth scale: ×1.0
  0.99 and 0.89, ×1.5 0.99 and 0.84, ×2.0 0.99 and 0.80, ×2.5 0.99 and 0.75, ×3.5
  0.99 and 0.59. The clip took ×1.5 (bold-talk §2).
- **Inside the engine or after it: the same.** The same tract applied to the
  finished gong (after its gate and amplitude envelope, with the same dry lead
  and level-follow) against the inside-the-render version: NCC 0.956,
  spectrogram correlation 0.995, the 900 to 2600 Hz band 34, 37, 7, −7 dB
  against 34, 37, 11, −14 at 150, 300, 500, 800 ms, formant slots within 40 Hz.
  "Putting the tract inside the gong made no measurable difference. What the
  clip is, mechanically, is a time-varying formant filter, which the app does
  not have" (bold-talk §2). This is the evidence that SAY can be a rack section
  and TIDE need not change.
- **What the spike held, and what SAY holds.** The spike held the word's **final** formant
  frame past its end (`TalkWord.Track.at`: the index stops at `size − 2` and `x` clamps to
  1), which for ONE is the closing nasal, a hum (`VoxSpeak.kt:115`, nasal 1 and damp 2.5;
  SEVEN's is `:185`). Every figure above was measured so, the weak closing stretch
  included, and the owner kept that clip by ear. SAY's default does the same: after the
  word the track holds its final frame ("The filter", step 3), so what is measured above
  about the word and its ending holds as measured. The brief's "holds the last vowel" is a
  paraphrase of that clip, not a separate decision; read literally, as a return to the last
  open vowel, it is Decision 8's alternative, which Phase 0 measures beside the default
  (items 1 and 2) and the gate's clip 8 lets the owner hear.
- **Level and cost.** The wet level against the plain gong per 100 ms, dB: −1.4,
  1.0, −0.9, −0.6, −5.1, −2.6, −0.6, 0.9, −1.7, −2.1, 2.0, −1.5, −2.5; the −5.1
  is 400 to 500 ms, the closing n. Both paths together cost 146.5 ms against
  111.9 ms for the plain gong, ×1.31, at the engine's 176.4 kHz render rate
  (bold-talk §2).

## Against the fleet

| Candidate | Why it is not the new thing |
|---|---|
| **MORPH** (today) | One blend for the whole hit: `morph()` applies one `amount` to every spectrogram frame (`shell/src/main/kotlin/com/snipsnap/shell/Mutate.kt:357-361`), to the output length (`:362`) and to the peak target (`:364`). A kick MORPH 0.5 is a half-bell from the first millisecond (bold-talk §0). BECOME 0 is this, byte for byte. |
| **SPLICE** | The pad's own transient up to the split, crossfaded over `FADE_MS` = 10 ms into the parent's body (`Mutate.kt:49`, `:304-323`): a seam, not a glide. It is the nearest MUTATE render to the spike (NCC 0.589 at a 100 ms split) and the reason for the honest risk below (bold-talk §3). |
| **TRANSPLANT, the rack's MOTION and SPEED** | TRANSPLANT's own KDoc (doc comment): "Nothing moves in time — every frame of A gets the same colour" (`audio/src/main/kotlin/com/snipsnap/audio/Transplant.kt:12-14`); SPEED is a static pitch shift and MOTION a pitch and level fall over the end of the sound (bold-talk §4). None moves one sound's spectrum from one parent's to another's. |
| **BODY** | A bank of two-pole resonators struck by the hit and tuned to the key's chord tones, root loudest, over three octaves from C2 (`audio/src/main/kotlin/com/snipsnap/audio/Body.kt:27-36`, `:58-74`, `:88-108`). AMT crossfades the dry hit into the ringing body, peak-matched to the hit (scaled so the loudest sample matches; `:109-128`): at AMT 1 the hit is gone and replaced by sine rings, and the ring is a T60 knob (the time to fall 60 dB) the phone does not draw (`Keyed.kt:51-61`). STRUNG keeps the dry hit, rings plucked-string loops that the hit feeds sample by sample, darkens each pass and shimmers from detuning. BODY stays exactly as it is. |
| **PLUCK's tarab** | The same loop STRUNG uses, but private, tied to the sitar's played note, and heard only under DOUBLE (`synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt:254-283`, the private `sympathetic` at `:911`, fixed feedback 0.995 at `:858`). It is a Karplus-Strong loop (a delay line with a filter in the feedback path) fed by a copy of the played string at 4× the snip rate. |
| **SILK's WASH** | A different mechanism: a resonator bank on the current scale's degrees run by `Strings.bodyRing` (`Silk.kt:145`, `:601`; `Strings.kt:840`), mathematically closer to BODY than to the loops (map body-treatment §4). |
| **MUTATE ROOM or STACK with a plucked chord, the SPRING chip** | The nearest renders to STRUNG: NCC 0.487 (ROOM) and 0.461 (SPRING); phase-blind 0.963 for STACK and 0.942 for ROOM (bold-ring clip 3). A chord under a clap is *there*; what STRUNG adds is that the clap's own noise rings the strings, the detune shimmer, and the strings darkening as they decay. |
| **CONTOUR, EQ** | CONTOUR is one ladder low-pass swept from the onset (`synth/src/main/kotlin/com/snipsnap/synth/Contour.kt:26-31`, `:48-82`), the nearest rack chip to the speaking gong (NCC 0.568); EQ is three static bands (`Eq.kt:21-25`). Neither has formants that move. |
| **ARCO's note on formant filters** | The ARCO design ruled that the string machine's two *fixed* formant filters are the rack's EQ on the recipe, never a section (`2026-09-29-arco-bowed-string-engine-design.md:1947-1948`). SAY is a different thing: the formants *move* along a word's path, which EQ cannot do. |
| **VOX SPEAK** | A voice: a buzz and a breath through the tract (`VoxSpeak.kt:47`, `:409-567`). SAY has no source; it speaks through another pad's sound. They share the tract and the eight words, not the sound. |
| **The Group B spec's TALK** | TERRA's per-mode levels follow the same word path, baked into the pad's data. SAY runs the path as a live filter on any pad. Same track, two consumers ("Architecture"). |

## The names

- **SAY, not SPEAK.** VOX already has `VoxVoice.SPEAK` (`synth/src/main/kotlin/com/snipsnap/synth/Vox.kt:72`) and a preset named "SPEAK BOX" (`VoxPresets.kt:57`). The fx-rack design's rule is that one word must not mean two things on the card: SPIKE's macro is SUSTAIN because BODY is already a chip, and "two unrelated meanings of one word on the same screen is the problem that ruled out `HIT`" (`docs/superpowers/specs/2026-09-13-fx-rack-expansion-design.md:164-168`). A rack section, chip or macro called SPEAK would break it. Owner's choice, with the JSON key `say` permanent.
- **Checked clear.** No chip, macro, preset or card label is named SAY, BECOME or STRUNG. The SAYs in the tree are a private preset helper (`VoxPresets.kt:140`), the BEATBOX voice's own private `Say` class (`VoxBeatbox.kt:35`), its private `SAY` map (`:46`) and private `say` function (`:103`), and a verb in one empty-state sentence in `OrbitScreen.kt:1765`; `PadSheetScreen.kt:1068` also has a local `var said`. The new public `object Say` in the `synth` package will sit beside that differently shaped, same-named nested class. That does not break the build: the nested class is a different type and shadows the top-level object only inside `VoxBeatbox`, where nothing needs the new one (the plan adds no `Say` reference there). "becomes" appears only in prose KDocs. The card words are the chip ids themselves, BECOME the knob's label.
- **Macro words checked.** The one-word-one-meaning rule is checked for SAY's four macros too. WORD and SIZE are VOX SPEAK's own WORD and SIZE with the same meaning, the word and the throat (`Vox.kt:178-181`, `VoxSpeak.kt:52`), so a SPEAK preset's value names the same thing in SAY. SIZE also means a room in SPRING (`Spring.kt:21`) and a grain in GRAINS (`Grains.kt:37`), and SPEED is also the name of the rack's pitch-shift section (`FxChain.kt:185`, whose own macro is SEMITONES, `Speed.kt:31`). None of them can meet SAY's on one screen: no screen draws a rack section's macros (the app draws engine macros through `engine.macrosFor`, `SynthScreen.kt:1009`, `:1862-1874`, and the rack through chips), so the words meet only inside a recipe, as `"say": {"SIZE": …}` beside `"spring": {"SIZE": …}`, where the section name says which is meant. The brief fixed the four names, so no rename is offered and no Decision is needed. The move label on the status line (`MutateSheet.Applied.word`, "The words a pad carries") is a different thing and is called the move label in prose here, so WORD keeps one meaning.
- **Four permanent names.** The section name is a saved-recipe key and the JSON order is the rack order (`FxChain.kt:132-143` writes `sec.name` verbatim), so `say` cannot be renamed later. `"strung"` is the keyed name saved under `keyed` (`KitBuilder.kt:735-745`), `become` the key saved inside `mutate`, and `said` the treatment name saved under `treatment` (replay is by name, `Plan.Character("said", …)`; "Data flow and compatibility"). A rename of any of the four would orphan saved kits; each is one constant to choose now. The macro names inside the `say` block (`WORD`, `SPEED`, `SIZE`, `MIX`) are saved keys too.
- **The treatment's name is `said`.** Treatment names are past participles (`smeared`, `ringed`, `contoured`, `ensembled`, `amped`; `Treatments.kt:30-69`); `said` is the one that keeps SPEAK's root off the product surface.
- **Sound yes, names never** (`docs/SYNTH_ROADMAP.md:27`). No machine, product or maker name appears in any label, comment, preset, test or commit message this work adds, and none appears in this document; the method names it uses (Karplus-Strong, Klatt, Hann, Pearson) are the published names of techniques, not of machines or makers.

## Architecture

```
A1  BECOME   Mutate.morph (:shell)   per-frame amount  a_f = MIX · clamp(t_f / BECOME, 0, 1)
               length and peak follow the END amount (MIX); BECOME 0 = today's loop
               ↳ recipe  mutate.become  ·  MutateSheet 2nd knob  ·  MutateCard 2nd row  ·  CLI --become

A3  STRUNG   Keyed "strung" (:shell) → Strung.ring (:synth) → N sympathetic loops (:synth)
               the loops lifted out of Pluck.kt, one shared helper; BODY (:audio) untouched
               ↳ chip on the KEYED row  ·  CLI `strung`  ·  chord from Body.modes' degree rule

A2  SAY      FxChain section "say" (:synth) → Say.process → Formant.Track + Formant.Tract
               the track and the resonators extracted from VoxSpeak.kt, behind a frozen guard
               ↳ Treatments "said"  ·  chip on the ANATOMY row  ·  a selector flag on MacroSpec
```

**Modules.** `:synth` depends on `:audio` (`synth/build.gradle.kts:10`) and `:shell`
depends on both (`shell/build.gradle.kts:10`, `:15`); `:audio` depends on neither
(`audio/build.gradle.kts` has only a test dependency, on `:xpm`). Three
consequences. `Body` (`:audio`) cannot call a loop that lives in `:synth`, which
is why STRUNG is a new treatment beside BODY and not an option inside it.
STRUNG's DSP lives in `:synth`, as ROLL, GATE and WOBBLE do (`Keyed.kt:12-14`
imports them), and the keyed door in `:shell` calls it. And `internal` means
"inside `:synth`": the lifted sympathetic helper and the extracted `Formant`
object are `internal` and reached from outside `:synth` only through public
entry points: `Strung` and `Say` here, and the Group B spec's `Terra.bakeTalk`
for TALK.

**The card draws the rows it is given.** `PadSheetScreen.kt` hands
`PadSheet.ROWS` to the treatment card (`:2400`, drawn at `:3294-3331`), so a chip
is two edits in `:shell` and none in `:app`. STRUNG and SAY therefore touch no
`:app` file; BECOME's second row is the only edit in this document that the
Android compiler is needed for (see "Phasing and gates" for what that means
here: no Android SDK is available in this environment, so `:app` is checked only
by `ConventionTest`'s source-text laws (tests that read the app's source for house rules) and the owner's phone).

### Shared groundwork

Three pieces of groundwork sit under the features, two of them shared with
something else. Each is a change that moves no audio and is proved by a **frozen
copy**, the house's pattern for a
move: a verbatim copy of the old code kept in test sources, and a test that the
new code equals it sample for sample (`StringsTest.kt:23-45` against
`LegacyPluckLoop`; the ENSEMBLE commit's lift of `Dsp.tap` left the original
caller calling the lifted copy and relied on that caller's determinism test, which
is the weaker form; a frozen copy is the stronger one and is used here).

**G1 and F1: the VoxSpeak guard, then the formant extraction. Shared with
the Group B spec's TALK.**

- *Why.* SAY and the TERRA hook's TALK both need VOX SPEAK's word machinery, but
  different parts of it, and the shared seam is the per-block formant **track**,
  not the resonator: extracting only the resonators for SAY would force TALK to
  derive the track a second time, "one quantity computed twice", the house's named
  defect shape (critic, A2/B3 dependencies 1). The track is the retimed target
  list (`retime`, `VoxSpeak.kt:353-370`), the false starts (`stuttered`,
  `:381-407`) and the per-block linear interpolation with the jaw and throat
  scales (`:462-480`); it is not a stored table today, it is computed inside
  `synthesize`'s render loop (map rack-sections §4).
- *G1, the guard, lands first and alone.* There is none: `VoxSpeak` has no
  byte-exact test, only behavioural ones in `VoxGrainsTest.kt:781-829` (channels,
  frequency, class), and no `VoxSpeakTest` exists (critic, A2). G1 adds
  `LegacyVoxSpeak.kt` (the whole of `VoxSpeak.kt` at `75b550c1` copied into test
  sources under another object name) and a test that `VoxSpeak.synthesize` equals
  it with `assertContentEquals`, over **276 cases** (this spec's grid, printed
  and asserted like `StringsTest`'s `assertEquals(384, cases)`): the eight words
  × the 32 corners of (HUMAN, DECAY, SIZE, EFFORT, STUTTER) at 44.1 kHz, the
  eight words at their defaults at 176.4 kHz (the rate VOX renders SPEAK at,
  `Vox.kt:571`), and the twelve SPEAK presets (`VoxPresets.kt:163-175`) through `Vox.render` end to end. The
  corners reach every branch the extraction moves: no stutter and three
  (`stuttered`), chip-frame quantisation at HUMAN 0 (`:464-465`), the child and
  the giant throat (`:422-424`), the jaw at EFFORT 1 (`:434`). It passes against
  the unchanged source, which is the proof it guards something.
- *F1, the extraction, behind the guard.* The resonator, anti-resonator and tract
  (`:281-329`) and the track move into one `internal object Formant` in
  `Formant.kt`; `VoxSpeak` calls it. The constraint the guard enforces is that
  **float operation order moves verbatim**: the interpolation expression at
  `:466-474` returns all eleven target fields (the formants, nasal and damp the
  filter reads, and the voice, breath, hiss and burst fields only VOX SPEAK
  reads), so VOX SPEAK keeps using all eleven and SAY and TALK read five. The
  resonators stay in double precision (`:281`). **What stays out of `Formant`:** SAY's
  three extras are parameters at SAY's call site, so VOX SPEAK passes the neutral
  values and nothing inside the extracted code changes. The 1.5 bandwidth widening is
  the `ring` argument `Tract.set` already takes (`:319-322`; VOX SPEAK passes the giant's
  `ring`, SAY passes `ring × 1.5`); the 0.45 × rate clamp is applied by SAY to the
  formant array it hands `Tract.set`; the ε in the level follower is SAY's own
  arithmetic. The shared track function gains one argument, the chip-frame length (VOX
  SPEAK passes the value it computes at `:464`, SAY passes 0). The default hold needs
  nothing more: the track's own end-of-list clamp (`:466-469`: the index search stops one
  short of the end and `x` clamps to 1) holds the script's last target, so nothing is
  appended and nothing is added inside the loop. The alternative hold (Decision 8) would
  add a second argument, a list of targets to append after the retimed script (VOX SPEAK
  passing an empty list, SAY one target, its word's last vowel placed 80 ms after the
  script's last, "The filter", step 3), and F1 carries it only if Decision 8 takes that
  alternative.
  The size arithmetic (`:421-425`: from SIZE the `throat`, the `ring` and the
  `chest`) is extracted as one function both call, so VOX SPEAK and SAY cannot
  disagree about what SIZE means; SAY reads `throat` and `ring` and ignores `chest`
  ("The filter", step 2). The G1 guard depends on exactly this: its 32 corners
  hold SIZE at 0 and 1 (the child and the giant) and the eight words at their
  defaults hold it at 0.5, so every branch of the extracted size function runs.
  Signatures are the plan's to write; the constraint is not.
- *Who lands it.* Whichever of SAY (A2) and the Group B spec's R3 reaches it
  first lands G1 and F1; the other adds only its own function on top (TALK's
  analytic magnitude response; SAY's filter loop). The Group B spec says the same
  from its side. SAY uses the track and the filter live; TALK **bakes** the track
  into fixed-length data, so saved TALK pads never depend on a later change to
  VOX SPEAK, while a saved SAY recipe stores only the word, speed, size and mix
  and re-renders through whatever `Formant` is then ("Data flow and
  compatibility" says what that costs).

**S1: the sympathetic loop, lifted once.** PLUCK's private `sympathetic`
(`Pluck.kt:911`) is lifted into one `internal` helper in `:synth` (in
`Strings.kt`, or a small `Sympathy` object beside it; the plan picks) with the
two constants it hard-codes turned into parameters, `feedback` (0.995 at
`:858`) and `loopHz` (4000 at `:857`), and PLUCK passing exactly those two. Its
KDoc keeps the loop apart from `Strings.pluck` because its shape differs (no
burst, continuously fed, no stiffness or jawari), "so sharing the function would
need a third caller shape neither SILK nor PLUCK currently needs" (`Pluck.kt:891-910`,
the phrase at `:899`). That is the reason to lift the *loop* whole and not to merge
it into `Strings.pluck`: it becomes a second shared shape with two callers, PLUCK
and STRUNG. STRUNG's use differs in three ways: a different feedback (0.98), a feed
that is off for the first 40 ms, and a result that wants the ring without the
feed-through (the helper adds the fed signal to every output sample, `:926-932`,
so STRUNG subtracts the same per-sample feed it fed, in its own wrapper, and the
helper's output stays byte-identical for PLUCK). The tuning budget inside it is a
local copy of `Strings.tune`'s arithmetic (`:912-924`); unifying the two is a
different change that would move PLUCK's bytes if they disagree anywhere, and is
**not** part of this lift. The guard is `LegacySympathetic.kt` (the function
as it is at `75b550c1`, in test sources) and a test that the helper at feedback
0.995 and loop low-pass 4000 Hz equals it sample for sample over a printed grid:
both render rates (44.1 and 176.4 kHz) × the ratios PLUCK reaches (0.5, 1.125,
1.25, 1.333, 1.5, 1.667, 2 and 3 of the played note, `Pluck.kt:878` and `:888`)
over the notes SITAR's TUNE reaches (TUNE snaps to `round(TUNE × 24)` semitones
above the voice's 139 Hz root, `Pluck.kt:33`, `:171`, `:175-176`, so the grid takes
semitones 0, 12 (the default, TUNE 0.5) and 24: 139, 278 and 556 Hz) × the three feed
schedules (PLUCK's `SYMPATHETIC_SERIES` steady feed, `SYMPATHETIC_SCALE`'s 0.5
for 10 ms, and a 0 for 40 ms). That is 2 × 8 × 3 × 3 = **144 helper cases**,
printed and asserted as `StringsTest` asserts its 384. Beside them, **12
end-to-end renders**: PLUCK's SITAR at the same three notes × DOUBLE 0.5 and 1 ×
both tunings, equal to the frozen path, with the existing `PluckTest.kt:344` and
`TuningAccuracyTest.kt:200` and `ExportRegressionTest.kt:91` passing untouched. S1
is A3-R0 ("Phasing and gates"), as BORE's R0 was BORE's.

**BODY gets its guard too.** STRUNG is a *new* keyed name so that BODY is
untouched and every saved `bodied` recipe keeps replaying to the same bytes. That
promise has no test behind it today: `BodyTest.kt:82-94` compares two runs of the same code with
itself. R0 adds `LegacyBody.kt` (`Body.ring` and `Body.modes` as they are at
`75b550c1`) and a test that BODY equals it over a printed grid (the keys C
major, A minor, no key; decays 0.05, 0.6, 4 s; amounts 0.3 and 1; mono and
stereo; 44.1, 48 and 96 kHz: 3 × 3 × 2 × 2 × 3 = 108 cases, printed and asserted). It costs nothing and it lets the next person who
touches `Body.kt` find out.

**The chooser is not touched.** The shared "pick a pad" chooser (lifted from
MUTATE's partner picker for MUTATE, TERRA's STRUCK BY and BENT BY, and FORK's
STRIKE FROM) is the Group B spec's R4. BECOME adds a row *beside* the picker
(`MutateSheet.Partner`, `partners` and `source`, `MutateSheet.kt:98-113`, `:95`,
`:210-226`) and does not move it. Both groups edit `PadSheetScreen.kt` (3 963
lines) and `Copy`; the rule that keeps the merges small is the order: BECOME
lands first (it is first in the group and the smallest), and the R4 lift
rebases onto it.

## BECOME, the design (A1)

### The ramp

`morph()` takes the magnitude spectrogram of the pad and of the parent (a
Hann-windowed STFT, a short-time Fourier transform with one spectrum per overlapping frame, of 1024 samples every 256, 513 bins: `Spectral.FRAME`, `HOP`
and `BINS`, `audio/src/main/kotlin/com/snipsnap/audio/Spectral.kt:19-25`), mixes
them bin by bin and has PGHI re-invent the phases (`Mutate.kt:342-372`). BECOME
changes one thing: the mixing number, `amount`, becomes a function of the frame.

```kotlin
// Mutate.kt
const val MAX_BECOME_MS = 2000

/** MORPH's amount at STFT frame [frame]: 0 at the aligned onset, [amount] from [becomeMs] on. */
internal fun becomeAmount(frame: Int, amount: Float, becomeMs: Int, rate: Int): Float {
    if (becomeMs == 0) return amount                              // today's loop, untouched
    val centre = frame.toLong() * Spectral.HOP - Spectral.FRAME / 2   // samples
    val r = (centre * 1000.0 / (rate.toDouble() * becomeMs)).coerceIn(0.0, 1.0)
    return if (r >= 1.0) amount else (amount * r).toFloat()
}
```

- **Frame time.** `Spectral` pads the signal by one frame at the start, so frame
  `f` reads source samples `[f·HOP − FRAME, f·HOP)` (`Spectral.kt:87-90`) and its
  window is centred on sample `f·HOP − FRAME/2`, which is 512 samples (11.6 ms
  at 44.1 kHz) *before* the sound for frame 0 and the sound's own first sample
  for frame 2. The rate is the base pad's (`base.sampleRate`, `Mutate.kt:177`);
  the parent has already been resampled to it (`:179`). Nothing is hard-coded to
  44 100.
- **From the aligned onset.** Both parents go through `alignToOnset` before the
  move (`Mutate.kt:178-179`, `:443-453`), so each starts at its own hit and the
  ramp's zero is the pad's first sample. Frames centred before it get amount 0:
  the earliest frames are the pad alone.
- **Linear in amount per frame** is the default curve (a listening value; the
  spike used a smoothstep, "The spike's extras" below). It is *linear in
  amount*, not in dB, so the first frames are the pad almost exactly and the
  hand-over is spread evenly across BECOME.
- **A ramp shorter than a window is smeared.** One analysis window is 23.2 ms at
  44.1 kHz (`Spectral.kt:18`); a BECOME under about 23 ms reads as a step, not a
  ramp (critic, A1). The card's first non-zero step is 50 ms, which clears it;
  the CLI and a hand-written recipe may say 1 to 2000.
- **After BECOME, MIX.** From `becomeMs` on every frame is exactly `amount`
  (`r ≥ 1` returns `amount`, not `amount × 1`), so the late frames equal today's
  MORPH frames bit for bit; only the first `becomeMs` of the sound differ.
- **Length and peak follow the END amount.** `outFrames = round(la·(1−MIX) +
  lb·MIX)` (`:362`) and the peak target `peak(base)·(1−MIX) + peak(parent)·MIX`
  (`:364`) keep their scalar form with `amount` = MIX, the end of the blend.
  A kick that becomes a bell is as long as the bell, and the whole result is
  brought to the bell's peak. That is the owner's rule; its consequence is that a
  loud kick becoming a quiet bell is brought *down* to the bell's level over its
  whole length (the spike scaled its bell to 2.0× the kick's loudness, one of the
  extras below), so the gate listens for the level.
- **BECOME 0 is today's MORPH, byte for byte.** `becomeAmount` returns `amount`
  itself, the mixing expression is the same `a[i]·(1 − amount) + b[i]·amount`
  with the same operands, the length and peak lines are unchanged, and the recipe
  gains no key (below). The proof is a frozen copy of today's `morph()`
  ("Testing"), not a comparison of the new code with itself.
- **A parent longer or shorter than the pad.** The loop runs over
  `frames = max(ma.size, mb.size)` (`:354`), a missing frame is silence, and the
  ramp is on absolute time: a 2000 ms ramp is still under way at 1.5 s, so a bell's
  tail keeps turning toward MIX long after a 300 ms kick has ended. Nothing in the
  length arithmetic is new.

### The spike's extras (listening values, not defaults)

The measured clip (bold-talk §3) is not bare BECOME. It also had:

1. a **smoothstep** amount, `smoothstep((t − 10 ms) / (150 − 10 ms))`, 0 until 10
   ms and 1 from 150 ms;
2. the bell **delayed 45 ms**, so its bright attack partials land inside the
   hand-over and not before it;
3. the bell scaled to **2.0× the kick's loudness** (`Loudness.of`) before the mix;
4. the kick's own **first 15 ms crossfaded back** over PGHI's head (raised
   cosine), because PGHI smears the head by up to a frame of pre-echo;
5. a 12 Hz one-pole **DC blocker** (a whole-file mean subtraction was tried first
   and left a −56 dB step in the tail) and `Dsp.fadeTail`.

The owner approved BECOME as the brief states it: a linear ramp and nothing else.
These five are what the gate puts next to it. Each can be added later **only when
BECOME is on**, so none can move today's bytes; the gate decides which, if any,
earn a place (Decision 1). The honest risk stands: with the extras off, the
result may read as a longer splice (bold-talk §7 item 3).

### The recipe and the key namespace

`apply` writes, for MORPH only and only when BECOME is on, one more key beside
`amount`, in `Mutate.kt:226`'s `if (mode == …)` idiom, as a number of milliseconds
like `at` (`:224`):

```json
{"mutate": {"mode": "morph", "with": ["Kit:A03"], "amount": 0.5, "become": 400}}
```

The key order is `mode`, `with`, `amount`, `become`, then `flipped` and the
extras. With BECOME 0 the recipe is today's, key for key. **The `mutate`
namespace is shared by more writers than the maps listed**, and the new key must
not collide with any of them: `mode`, `with`, `at` (SPLICE), `hz` (SPLIT), `amount`
(MORPH), `mix` (ROOM), `bands`, `flipped`, `roulette`, `drift`, `room`, `otherKit`
and `outside`, the last written *inside* `mutate` through `extraRecipe` by
OUTSIDE's ROOM trip (`OutsideSheet.kt:151-156`, read back at `:196`; critic, A1).
`extraRecipe` is applied last with `putAll` (`Mutate.kt:232`), so an extra named
`become` would silently overwrite the ramp. `apply` therefore refuses one:
`require("become" !in extraRecipe)`, right after `requireParams`. No current caller
carries it (the extras in the tree are `roulette`, `drift`, `room`, `otherKit` and
`outside`: `Mutate.kt:260-268`, `MutateSheet.kt:296-309`, `MutateCommand.kt:90-97`,
`OutsideSheet.kt:154`), so the guard changes nothing today and makes a future
collision loud.

### The validation

`requireParams` is the one place both entry points ask (`Mutate.kt:124-144`; `apply`
asks before it looks the pad up, `render` asks again). It gains `becomeMs` and two
lines, worded as the others are (lowercase, flag-named, since the CLI prints them):

- `require(becomeMs in 0..MAX_BECOME_MS) { "--become wants 0..2000 ms, got $becomeMs" }`
- `require(becomeMs == 0 || mode == Mode.MORPH) { "--become rides on --morph - add it" }`

`render` and `apply` gain `becomeMs: Int = 0` (appended to `render`'s list;
before `extraRecipe` in `apply`). Every caller outside the tests names its optional
arguments, or passes the first eight positionally (`Mutate.kt:213-215`), so none
changes; `Mutate.drift` never passes it (`:254-271`), which is why DRIFT's bytes
cannot move.

### The card and the sheet

The MUTATE card has exactly one knob slot (`knobLabel`, `knobFraction`, `knobText`,
`onKnobChange`, passed at `PadSheetScreen.kt:2565-2568`; one `StepperSlider` in
`MutateCard`, `:3723`), and the design record calls one knob whose meaning changes
between moves a smell (`design/mutate-v2/README.md:26`) while keeping the move
*names* fixed (`:30`, `:42`). BECOME is the first move with a second knob:

- **A second `StepperSlider` row, for MORPH only.** Under the first row, label
  `BECOME`, value text `OFF` at 0 and `400 ms` otherwise. For every other move the
  row shows the same disabled `—` row the first slot already shows for STACK
  (`:3722` comment), so the card never jumps. The row is drawn the way the first slot is,
  with `enabled = !busy && knobLabel != null` (`PadSheetScreen.kt:3723-3732`); no law
  enforces that for a `StepperSlider` (ConventionTest's `enabled` laws, `:44-140`, read only
  `ChopScreen.kt` and `TapeScreen.kt`, for four other components), so the plan's review
  checks it by eye. *Recorded deviation (the A1 plan's closing note, carried by the A1b
  plan):* the row keys `enabled` on its own label, `becomeLabel != null`, not on
  `knobLabel`, because `knobLabel` is non-null for SPLICE, SPLIT, ROOM and TRANSPLANT,
  which ignore BECOME; and the A1b plan pins it with a ConventionTest law rather than
  by eye. Six-letter labels already fit the
  44 dp label column (the SHAPE card's `ATTACK` and `CUTOFF`, `ShapeKnob` labels
  drawn by the same `StepperSlider`: `PadSheetScreen.kt:2501`, `:2515`, `:3457`).
- **The knob is linear, 0 to 2000 ms.** `MutateSheet` gains
  `val BECOME = Knob("BECOME", 0f, 2000f, 0f, exponential = false)` and
  `becomeFor(mode)` (the knob for MORPH, null otherwise). It cannot be exponential:
  `Knob.value` is `lo × (hi/lo)^f` and `fraction` the inverse (`Knob.kt:23`, `:29`),
  and with `lo` = 0 that is `0 × ∞` = NaN for every fraction above 0. Linear snaps
  to 1/40 on the phone (`PadSheetScreen.kt:2568`), which is **50 ms steps**; a NaN
  fraction reads as the default, 0, OFF (`Knob.kt:21`). An exponential knob with
  an explicit OFF detent is Decision 2.
- **The readout needs its own branch.** `MutateSheet.label` falls to
  `"${(value × 100).roundToInt()}%"` for every knob it does not name
  (`MutateSheet.kt:80-85`), which would print 150 ms as "15000%". It gains
  `"BECOME" -> if (value <= 0f) "OFF" else "${value.roundToInt()} ms"`, the unit's
  lowercase `ms` being the AT row's own precedent (`:81`).
- **HEAR and KEEP read one mapping.** (HEAR plays what the move would write without writing it; KEEP writes it.) `Knobs` gains a sixth slot, `becomeMs`
  (`MutateSheet.kt:238-255`), filled for MORPH from `value(BECOME, becomeFraction)
  .roundToInt()` and 0 for every other move, exactly as the other five slots are;
  `preview` (`:270-286`) and `apply` (`:294-320`) both take
  `becomeFraction: Float = 0f` and both build `Knobs` through the same function,
  so what HEAR plays is what KEEP writes (`MutateSheetTest.kt:93`).
- **State, additive.** One more holder beside `mutateKnobs`:
  `pendingBecome`, a `remember(slot)` float, read by `onMutate`, `onHear` and the
  card call. `mutateKnobs` stays declared exactly as the J24 law reads it (a
  `val mutateKnobs = remember(slot) { mutableStateMapOf<String, Float>() }`
  matched by regex, with `remember(slot, mutateMode)` banned,
  `ConventionTest.kt:1823-1842`), and the DRIFT block it pins is untouched
  (`:1900-1912`).
- **DRIFT zeroes BECOME's memory.** DRIFT is a flat morph (`Mutate.drift` never
  takes BECOME; `DriftCommand` is unchanged). If the card went on showing a
  remembered `BECOME 400` after a DRIFT tap, it would show a value the drift did
  not use: J24's own bug, value shown against value used. So `onDrift` sets
  `pendingBecome = 0f` on its own line after the `if (!onMorph) { … }` block
  (not inside it, so the pinned block stays as the law reads it, and for every tap,
  because DRIFT from MORPH has the same divergence), and a **sibling law** in
  `ConventionTest` pins it by the same source-text method ("Testing").
- **The words a pad carries.** `MutateSheet.Applied` gains `becomeMs: Int = 0` as
  its last field (the tests build it positionally with two or three arguments:
  `PadSheetBoxesTest.kt:49`) and `read` fills it with `as?`, finite, 1 to 2000, and
  only when the mode is MORPH; anything else reads as 0. The move label, `Applied.word`, becomes `DRIFT`
  for a drift, `BECOME` for a MORPH with a ramp, else the mode. The status line
  under the card's title (`"${it.word}: parents"`), the strip
  (`PadSheetBoxes.kt:70`, "BECOME × SOUL A03"), the takes diff (`KitDiff.kt:111`,
  "MUTATED: BECOME") and the replay refusal (`RecipeReplay.kt:64-66`, "MUTATE (BECOME
  WITH KIT A03) NEEDS ITS PARENT - NOT CARRIED.", the form `RecipeReplayTest.kt:43-46` pins) all read that label, so they say BECOME for free.
  The keep toast is unchanged (it names the move the player tapped, MORPH:
  `Copy.mutated`, `Personality.kt:1503`), which keeps the edit out of `:app`. A plain
  MORPH reads "MORPH" as before: `KitDiffTest.kt:90`, `PadSheetBoxesTest.kt:49`.
  Whether the pad is called BECOME or MORPH is Decision 3.
- **Copy and PersonalityTest.** The label and the value text live in `MutateSheet`
  beside AT, HZ and BANDS, not in `Copy`, so the reflective shout-and-full-stop law
  (`PersonalityTest.kt:726`) does not see them; they follow the house style by hand
  (a label that shouts, `OFF`, `400 ms`). BECOME adds no refusal on the phone, since
  the row exists only for MORPH, and so adds no `Copy` string; if the gate adds one
  it must shout and stop, and "jokes never gate function" applies
  (`docs/PERSONALITY.md`, law 3).

### The CLI

`mutate <kit> <pad> --morph --with … --become <ms>` (`cli/src/main/kotlin/com/snipsnap/cli/MutateCommand.kt`):
`--become` joins the valued set (`:26`); after `--bands`'s own refusal (`:59-61`,
"`--bands rides on --transplant - add it`") comes
`--become rides on --morph - add it`, raised whenever the flag is present without
`--morph`, `--become 0` included; the number is read with `opts.int` (a non-number
is `--become wants a number, got 'x'`, exit 2: `Main.kt:446-448`) and checked
0..2000 with the same CliError wording as `requireParams`; it is passed to
`Mutate.apply` as `becomeMs` (`:106-114`). The report keeps its first line byte for
byte (`morphed 50% toward …`, which `CliTest.kt:3069` pins) and, only when BECOME is
on, adds one line after it: `  becomes it over 400 ms - the first beat is the pad`.
**DRIFT never takes BECOME**: `DriftCommand` is unchanged (`:44` calls
`Mutate.drift`), so `drift … --become 400` is the existing "unknown option" exit 2
(`Main.kt`'s `Options.parse`), and the ConventionTest DRIFT block is unchanged.

### Registration surfaces

| Surface | Where (75b550c1) | Change |
|---|---|---|
| The ramp | `Mutate.kt:342-372`, loop `:357-361` | `becomeAmount` per frame; length `:362` and peak `:364` unchanged |
| Validation | `Mutate.kt:124-144` | `becomeMs` and two requires |
| `render`, `apply` | `Mutate.kt:163-191`, `:193-242` | `becomeMs: Int = 0`; `render` passes it at `:186`; `apply` passes it at `:213-215` |
| Recipe key | `Mutate.kt:226` | `become`, MORPH and `> 0` only; guard against `extraRecipe` at `:209` |
| KDoc | `Mutate.kt:9-35`, `MutateSheet.kt:15-33` | one sentence each |
| Sheet knob and readout | `MutateSheet.kt:43-58`, `:80-85` | `BECOME`, `becomeFor`, the label branch |
| Sheet read and word | `MutateSheet.kt:170-185` | `Applied.becomeMs`, `word` |
| HEAR and KEEP | `MutateSheet.kt:238-255`, `:270-286`, `:294-320` | sixth `Knobs` slot; `becomeFraction` on both |
| Card | `PadSheetScreen.kt`: state at `mutateKnobs` (`:1240`), `onMutate` (`:1276`), `onHear` (`:1338`), `onDrift` (`:1431`), the card call (`:2565`), `MutateCard` (`:3489`, its `StepperSlider` `:3723`) | a second row; `pendingBecome`; the DRIFT line. Unverifiable here (no Android SDK) |
| CLI | `MutateCommand.kt:26`, `:59-61`, `:106-114`, `:117-127`; `Main.kt:139-150` (usage) | `--become`, its refusal, one report line, one usage clause |
| Docs | `docs/CLI.md:499-505`; `docs/FEATURE_PLAN.md` rows QQ3 (`:1172`) and XX1 (`:1345`) | the flag; a row beside them |
| Not touched | `DriftCommand.kt`; `RecipeReplay.kt:64-66` (mutate stays a refusal); `Lineage.kt:91` and `KitBuilder.LEFT_WITH_OLD_FILE` (`:1279`), which read and drop the `mutatedWith` stamp BECOME leaves as it is; `design/mutate-v2/*.dc.html` (proposals) | none |

## STRUNG, the design (A3)

### Why a new keyed name, not an option on BODY

BODY already rings the key's chord (root 1.0, fifth 0.5, third 0.3, three octaves
from C2; `Body.kt:58-74`), so "a chord" is not new; what is new is a different
*instrument*: strings fed by the hit, a shimmer, a ring that darkens as it
decays. Three facts decide the home (map body-treatment §5, §8):

1. **Module direction.** `Body` is in `:audio`, which cannot see `:synth`
   ("Architecture"), and the loop is in `:synth`.
2. **BODY's bytes.** A saved WAV is baked and does not move, but a replay (COPY
   LAST TREATMENT, PASTE) re-runs the keyed door (`RecipeReplay.kt:143-145`), so a
   changed `Body.modes` or `Body.ring` would make every saved `bodied` recipe sound
   different on its next paste: the recipe format would survive, its sound would
   not (map body-treatment §2; critic, A3). BODY has no byte guard today.
3. **One chip, one AMT.** A keyed chip is one treatment with one AMT and the dials
   the phone does not draw are the CLI's flags (`Keyed.kt:51-61`; map
   body-treatment §2); "chord and shimmer" as options on BODY would need a second
   control on the sheet that has no precedent.

So STRUNG is a new keyed treatment, `strung`, beside `bodied`, and BODY is
untouched. The keyed family is the right family and not the rack: it reads the
kit's key, and "keyed = reads the kit, the rack's characters read nothing but the
sound" is the house's own boundary (`Keyed.kt:17-21`; `2026-09-13-fx-rack-expansion-design.md:257-263`).

### The strings

`Strung.ring(snip, rootSemitone, scale, amount): Snip` in a new
`synth/src/main/kotlin/com/snipsnap/synth/Strung.kt`, public, beside `Roll`,
`Gate` and `Wobble`. Everything below is the spike's (bold-ring clip 3) unless
tagged *this spec*.

1. **Which strings.** The key's chord, from BODY's own degree rule, not a second
   copy of it: the degrees are read off `Body.modes(root, scale)`'s first octave
   (`midi − (Body.LOW_MIDI + root)`, giving `{0, 7, 3 or 4}` as `Body.kt:58-66`
   computes them: a fifth if the scale has a 7, a major third if it has a 4 and
   is not chromatic, else a minor third if it has a 3). The voicing (*this
   spec*, generalising the spike) is five strings: the chord's fifth an octave
   down (**the root minus 5 semitones**, a fourth under the root), the root, the third, the
   fifth, the **octave** above the root, with the root placed in MIDI 52 to 63 (`rootMidi = 52 + floorMod(root − 52, 12)`).
   A minor therefore rings E3 A3 C4 E4 A4 (MIDI 52, 57, 60, 64, 69), which is the
   spike's chord exactly; C major rings G3 C4 E4 G4 C5. A scale without a third
   (chromatic, which is what "no key" is) rings the open voicing: the low fifth
   (root − 5), the root, the fifth, the octave. A scale without a fifth drops the
   fifths. With no key set the root is `Body.rootFor(snip, key)` (`Body.kt:50-55`):
   the hit's own pitch class when the detector is sure, else C. What a keyless
   kit rings (open fifths, no third) is Decision 4.
2. **The shimmer.** Each string is detuned ±0.2 % alternately, lowest string +,
   (about 3.5 cents), the same alternation as PLUCK's `SYMPATHETIC_SCALE`
   (`Pluck.kt:267-268`; `SYMPATHETIC_SCALE`'s own detune is 0.3 %, `:888`); the spike used 0.2 %.
3. **The loops.** One shared sympathetic loop per string (S1 in "Architecture"):
   a delay line tuned by integer delay plus a fractional allpass (a filter that delays by part
   of a sample without changing the level) so it rings at the
   string's Hz, fed sample by sample by the hit, a 4 kHz low-pass in the loop so each
   pass darkens it. **Coupling 0.15** (the house value, `Pluck.kt:849`, the one the
   owner chose at the 2026-09-27 PLUCK gate) and **feedback 0.98** (the house value
   is 0.995; at 0.995 the clap's ring was still within 60 dB of its peak at the end
   of its buffer, bold-ring clip 3).
4. **The feed, filtered and late.** The feed is the hit with a 30 Hz one-pole
   subtracted (each loop's DC gain is 1/(1 − 0.98) = 50). It is **off for the first
   40 ms**, so the strings are rung by the clap's later bursts and tail, not its
   loudest hit, and the dry attack stays unblurred. The spike used 40 ms on a
   0.395 s clap; a hit shorter than 80 ms feeds from its midpoint instead
   (`min(40 ms, half the hit)`, *this spec*: the brief gives about 40 ms and is silent
   on a hit shorter than that, which at a literal 40 ms would ring nothing; Decision 15),
   so a click or a hat still rings its strings and the gate is never longer than the
   sound.
5. **Wet is the ring alone.** The helper adds the fed signal to every output sample
   (`Pluck.kt:926-932`), so STRUNG subtracts, per sample, the same fed signal it
   supplied (0 during the delay, then 0.15 × the filtered hit) from each loop's
   output, sums the strings and runs the sum through a **2 Hz** DC blocker (the
   spike's, and `Pluck.kt:272-289`'s lesson: near-unity loops turn a residual
   offset into audible DC).
6. **The mix.** `out = dry + g × AMT × 2.5 × wet`, g the level hold of item 8 (1 for the
   clap). The strings are added to a hit that
   is otherwise untouched; 2.5 is the level the spike's owner heard
   (`SYMPATHETIC_LEVEL` is 2.0 in PLUCK, `Pluck.kt:856`); AMT 1 is that clip, and
   the pad sheet's first tap, AMT 0.7 (`PadSheet.kt:113`), is 1.75×. This is not
   BODY's law (a crossfade that replaces the hit with the ring); Decision 5 asks
   whether to make it so.
7. **The length.** The wet buffer is the hit plus **1.5 s** (*this spec's* reading of the
   brief's "tail cap ~1.5 s": a ceiling on the ring, past the hit's end; Decision 6 gives the
   other reading), cut where the 5 ms RMS envelope of the *joint* result is 60 dB under its
   peak, **never shorter than the hit**, and every channel ends on the same frame.
   `Strings.trimToDecay` (`Strings.kt:754-784`) is the house's rule but cannot take a stereo
   snip as it is: it is a mono function over a bare `FloatArray`, its 5 ms block is
   `rate × 0.005` *floats* (`:755`), its floor and ceiling are `seconds × rate` compared with
   `buf.size` (`:768`, `:778`), and its cut `(last + 2) × block` (`:776`) can fall mid-frame
   whenever that block is odd (it is 55 at 11 025 Hz); fed an interleaved stereo snip it would
   count time at half speed. STRUNG therefore hands it a **mono envelope**, one value per
   frame, the RMS over the channels (`√(Σ sample² / channels)`; for a mono snip that is
   `|sample|`), so the block RMS it takes is the joint RMS, with the floor and the ceiling in
   seconds as before; its block, floor and ceiling are then counted in frames. The length it
   returns is the cut frame, and STRUNG cuts every channel at that frame: the channels end
   together, on a whole frame. `trimToDecay` fades only the buffer it was given (its fades,
   `fadeCeiling` included, are mono and would run on the envelope, not on the snip, and a cut
   at a quiet point comes back with no fade at all, `:777`), so the fade is STRUNG's own:
   `Dsp.fadeTail(out, ms, rate, channels = n)` (`Dsp.kt:728`, which is channel-aware), 4 ms
   (the caller fade `trimToDecay`'s KDoc expects) when the cut found a quiet point, and 400 ms
   when the returned length is the envelope's whole length, the ring still moving at the
   ceiling, where `trimToDecay` itself fades 400 ms. (Linear, where its mono ceiling fade is
   squared: a linear fade ends at zero too, and the test pins the end, not the shape.) The
   spike's buffer was the clap plus 1.1 s with `trimToDecay`'s floor at 0.4 s and ceiling 1.5
   s, which cut at 1.352 s total; for the clap the new rule cuts at the same −60 dB point (the
   clap's own dur60 was 1.013 s, bold-ring clip 3). Those two numbers were sized for a 0.395 s
   clap: the floor is the shortest the cut may leave, and the ceiling decides which fade the
   cut gets (`Strings.kt:778-782`), so on a 3 s pad they would mean the wrong thing. STRUNG
   therefore passes the hit's own length as the floor and the hit plus 1.5 s as the ceiling,
   and the pad itself is never cut.
8. **The level.** The keyed family's rule is "peak matched" (`Keyed.kt:38-39`); STRUNG's is
   **peak held, on the wet alone**: the strings are added to a hit that is otherwise untouched,
   and the hit is never scaled. One constant g in 0..1 scales the wet so that the sum never
   passes the hit's own peak P (every channel together): g = min(1, min over the samples where
   the wet w is not zero of (P − d·sgn(w)) / |w|), d being the dry sample at the same place.
   It is one pass over the samples, exact (the largest g for which no sample of d + g·w passes
   ±P), needs no search, and g = 0 always satisfies it. For the clap the bloom stays under the
   clap's peak, so g = 1 and the clip is the spike's (the clip's float peak is 1.000, the clap's own
   peak, bold-ring measurements table). So the claim is unconditional: **the first 40 ms are the dry hit, bit
   for bit** (the loops have no feed before the delay, so the wet is exactly zero there), and
   no sample of the dry is ever scaled (`max |clip − dry|` = 0.0 over the first 40 ms, the
   spike's own figure, bold-ring clip 3). The cost of the exact rule: g is set by the single
   worst sample, so a hit whose loud tail lines up with a loud wet loses strings, and a hit
   that sits at its own peak for a stretch (a full-scale square) can drive g to 0, in which
   case STRUNG returns the input itself, the same object. The plan prints g for each gate
   source. Scaling the sum instead, which is smoother and does scale the dry, is Decision 16.
9. **Rate, channels, determinism.** At the snip's own rate, no oversampling (running at a multiple of the sample rate so that distortion cannot fold
   back; the loops are linear; the spike ran at 44.1 kHz, "oversampling buys nothing",
   bold-ring clip 3). Per channel, with the same strings and the same schedule in
   each, so identical channels come out identical and a stereo clap keeps its
   image. No seed: the same hit, key and AMT give the same bytes whatever `seed`
   the pad sheet draws per press (`PadSheetScreen.kt:877`, which only RETUNE and ETERNAL use).
   Cost: five loops over the clap at 44.1 kHz was 3.6 ms (bold-ring, render time);
   it scales with channels, rate and length, and nothing here is measured on a
   phone.

### The keyed door

`Keyed.NAMES` gains `"strung"` at the end (`Keyed.kt:43`; nothing indexes the list
by position: its uses are `Keyed.require` at `:72-74`, the error message, and
`PadSheetTest.kt:172`'s membership check). `Keyed.apply` gains a branch beside
`bodied`'s (`:103-108`) that computes the root and the label the same way
(`Body.rootFor`, the key's label uppercased, else the note name plus ", THE HIT'S
OWN NOTE" when the hit is pitched), so the two share one private helper rather
than computing the label twice, and calls `Strung.ring`. `Keyed.refusal`
(`:80-89`) answers null for it, as for BODY: a clap is the point. The keyed KDoc
list (`:22-36`) gains its bullet. `KitBuilderModel.keyedPad` (`KitBuilder.kt:719-756`)
needs no change: it is generic over the name, rewrites every file the pad
references (velocity layers included), bins the original, refuses chained pads, and
writes the recipe

```json
{"keyed": "strung", "key": "A MINOR", "amount": 0.7, "seed": 123456, "decay": 0.6,
 "division": "1/8", "tail": 0.5, "knee": 0.02}
```

with every `Dials` field written whichever treatment ran (`:735-745`). STRUNG reads
none of them and adds no `Dials` field, so **RecipeReplay needs no change**: a
`strung` recipe replays through `Plan.KeyedPlan` with the dials it carries or their
defaults (`RecipeReplay.kt:88-106`), and a test proves it (`RecipeReplayTest.kt:118`
is the pattern). AMT 0 returns the same object, as BODY's does (`Body.kt:81`; `BodyTest.kt:84`
pins it), and `keyedPad` returns the pad untouched at `amount <= 0`
(`KitBuilder.kt:723`).

### The chip and the CLI

- **The chip.** `PadSheet.KEYED_SEGMENTS` becomes `TUNE, BODY, STRUNG, WOBBLE,
  ETERNAL` (`PadSheet.kt:91`, five of the row's six), `KEYED_FOR["STRUNG"] =
  "strung"` (`:211-224`); it draws as itself (no `DISPLAY_LABELS` entry,
  `:124-136`), and the card draws it with no `:app` change (`PadSheetScreen.kt:2400`,
  `:917`). When A3b lands the chip count gains one: 30 to **31** at `75b550c1`, and 31 to
  **32** at `129bc48e`, where SECTION already made it 31. `PadSheetTest.kt:116-119` and
  `:130-149` (the row lists, the inventory set, the count) change with it, and the
  inventory test's name, which carries the running count ("... ENSEMBLE thirty and
  SECTION thirty-one" at `129bc48e`), is reworded so it does not lie. The width law (`:189`, a row of at most 6) holds.
  The toast is the keyed one, `Copy.keyed` (`Personality.kt:1500`): "STRUNG ON <pad>,
  IN A MINOR. ORIGINAL SLEEPS IN THE BIN."
- **The CLI.** `snipsnap strung <kit-dir> <pad> [--key Am] [--amount 0..1] [--undo]`,
  a new `StrungCommand.kt` shaped like `BodyCommand.kt:22-59` (it sets the key first
  when `--key` is given, calls `model.keyedPad(slot, "strung", amount)`, prints the
  strings it rang, `  strings: E3 A3 C4 E4 A4`, and `--undo` pops the bin through
  `untreatPad`), dispatched at `Main.kt:359`'s neighbour and listed in the usage
  text beside `body` (`:118-119`). `docs/CLI.md` gains its section after `body`
  (`:680-695`) and the pad-sheet row list is updated (`:366-377`, row six). There
  is no `--decay`: the ring is the helper's, capped, and not a dial (Decision 6).

### Registration surfaces

| Surface | Where (75b550c1) | Change |
|---|---|---|
| The shared loop | `Pluck.kt:911` (and `:857-858`, the call at `:259-269`) | lifted to an `internal` helper with `feedback` and `loopHz` parameters; PLUCK passes `0.995` and `4000` |
| The new treatment | `synth/.../Strung.kt` (new) | `Strung.ring`, `Strung.strings` |
| Keyed name and door | `Keyed.kt:43`, `:22-36`, `:92-139` | `"strung"`, its branch, a shared label helper; `refusal` unchanged |
| The chip | `PadSheet.kt:91`, `:211-224`, KDoc `:79-98` | `STRUNG` in the keyed row; `KEYED_FOR` |
| The write door | `KitBuilder.kt:719-756` | none |
| The card | `PadSheetScreen.kt:2400`, `:860-917` | none |
| Replay | `RecipeReplay.kt:88-106` | none (a test) |
| CLI | `StrungCommand.kt` (new); `Main.kt:118`, `:359` | the command, its usage, its dispatch |
| Docs | `docs/CLI.md:366-377`, `:680-695`; `docs/FEATURE_PLAN.md` (a row beside WW4, `:1335`) | the chip, the command |
| Tests | `PadSheetTest.kt:116-149`; `KitBuilderTest.kt:790-825`; `RecipeReplayTest.kt:118`; `SidecarFuzzTest.kt:380`; `DegenerateDoorsTest.kt:195-202`; `CliTest.kt:1278-1289`; new `StrungTest`, `LegacySympathetic`, `LegacyBody` | see "Testing" |
| Auto-pass, confirm green | `UatSimTest.kt:357-382` (every chip through its door at AMT 0.7, failures reported as findings); `PersonalityTest.kt:199-203` (the `TREATMENT <label>…` busy line at most 40 characters for every chip); `PadSheetTest.kt:262-270` (every chip tappable); `Retrim.kt:71` (reads the segment's label) | none |
| Not touched | `Body.kt`; `KitBuilder.keyedPad`'s recipe map; `Keyed.Dials` | none |

## SAY, the design (A2)

SAY is the VALVE-weight part of this document: a new rack section with a Phase 0
behind it, a placement to argue, a rule the rack has never needed (a selector
macro), and a gate. Everything numeric below is the spike's (bold-talk §2) unless
tagged *this spec*.

### What a section is, and what SAY must fit

A section is a row in `FxChain.SECTIONS` (`FxChain.kt:175-204`): a name, its
macros, a getter and a copier for its nullable `Map<String, Float>` field on the
`FxChain` data class (`:45-68`), and a pure `(Snip, Map<String, Float>) -> Snip`.
It receives no pad id, no other pad, no kit, no tempo and no seed: anything else it
needs is a macro or lives in code (map rack-sections §1). Every macro is a float in
0..1, validated at construction (`:69-78`); a discrete choice is a float snapped in
code, the precedent being `VoxSpeak.wordFor` (`VoxSpeak.kt:52`). The contract
tests iterate `SECTIONS` and so catch SAY with no edit: deterministic, finite in
−1..1 and peak-matched within 0.05 at six random macro rolls (`FxTest.kt:268`),
identical channels stay identical (`:292`), table and fields agree (`:1059`),
JSON round trip and "process is not a no-op" at 0.7 (`:1093`), JSON in rack order
(`:1106`), AMT scaling (`:1118`) and AMT 0 on the neutrals (`:1143`).

### Placement: after CONTOUR, before SQUASH

`Section("say", Say.MACROS, { it.say }, { c, m -> c.copy(say = m) }, Say::process)`
goes between `contour` (`FxChain.kt:191`) and `squash` (`:192`), in the rack stage
like every section but SPEED and SWELL; the `say` field is **appended** to the constructor after `valve` (`:67`),
so every older recipe decodes with it absent, a hard bypass ("an absent section
keeps old recipes byte-stable", `FxTest.kt:695-712`). The order becomes
`… SPIKE → EQ → CONTOUR → SAY → SQUASH → VALVE → CRUNCH → …` (the KDoc line at
`:11`, `README.md:518-521`). The owner's rule is **shape, then drive**, and the
KDoc gains the rationale beside the CONTOUR and VALVE paragraphs (`:13-19`):

> say after contour and before squash: shape, then drive. A vowel is a shape, so
> the compressor and the tube that follow act on the vowel, as they act on a
> swept ladder, and what they do is not undone by it; it runs after EQ, so the
> bands shape what the word is spoken through, not what it came out as.

JSON order follows the rack order, and the section name is a permanent saved key
(`FxChain.kt:132-143`), so `say` is fixed once a kit carries it.

### The macros

Four, plain words, inside the 3 to 6 budget (`docs/SYNTH_ROADMAP.md:173-174`; plain words, `:175-177`).

| Macro | Moves | Mapping | Default / neutral |
|---|---|---|---|
| **WORD** | which word | `VoxSpeak.wordFor(WORD)`, snapped across the eight, ONE at 0 to EIGHT at 1, each at k/7 (`VoxSpeak.kt:52`; `VoxGrainsTest.kt:814` pins it). The same encoding as VOX SPEAK's WORD, so a SPEAK preset's value names the same word in SAY. A **selector** (a macro that names one of a fixed set; below). | 0 (ONE) / selector |
| **SPEED** | the word's stretch | the vowel intervals stretched by `5^(1 − SPEED)` through `retime` (`VoxSpeak.kt:353-370`; VOX's DECAY is `5^decay`, `:277`, `:350`): SPEED 1 is the word at natural speed, SPEED 0 holds every vowel five times longer. Consonant timing never moves, since only vowel intervals stretch. | 0.5 (×2.24; the spike's 2.2 gave a 0.640 s word) / 0.5 |
| **SIZE** | the throat, child to giant | `throatScale` for the child half (`Vox.kt:247`, 2.2 octaves) and VOX SPEAK's own giant half that stops at 0.7 of the formants, with the bandwidths narrowing as it grows (`VoxSpeak.kt:423-424`: the `throat` and the `ring`, from one function extracted for both, "Architecture"). VOX SPEAK's giant also has a chest resonator (`:425-426`, `:509-510`); SAY leaves it out ("The filter", step 2) | 0.5 (throat 1) / 0.5 |
| **MIX** | dry to wet | 0 is the pad untouched; 1 is the spike's mix (below) | 1.0 / 0 |

AMT 0 is a bypass through the usual door: `Treatments.chain` returns an empty
`FxChain()` at `amount <= 0` (`Treatments.kt:83`) and `Treatments.apply` returns the
snip itself for a bypass chain (`:116`). MIX 0 is a bypass inside the section: it
returns its input, the same object.

### The selector rule

WORD is the first selector macro in the rack. Two existing mechanisms treat every
macro as a continuous amount and both would corrupt a word (map rack-sections §1;
critic, A2):

- `Treatments.fade` slides **every** macro of every set section toward its neutral
  by AMT (`Treatments.kt:95-109`): at AMT 0.5 a WORD of ONE (0) stays ONE only by
  luck of its neutral, and a WORD of SIX (5/7) slides to a different word.
- `Breed.crossMacros` takes, per macro, A's, B's or the **mean** of the two
  (`Breed.kt:198-215`): a child of two pads that say ONE and SEVEN can say FOUR, a
  third word nobody chose.

The rule is one flag and two sites. `MacroSpec` gains `val selector: Boolean =
false` (`synth/.../Thump.kt:33`; a data class whose three existing parameters
and every construction in the tree are unchanged; neutral is read only by
`Treatments.fade` and two tests). Then:

- **`Treatments.fade`:** a selector macro is **unchanged for AMT above 0** and goes
  to its neutral at AMT 0. (The second clause is what keeps the generic test
  "AMT zero lands every macro on its neutral", `FxTest.kt:1143`, true with no
  edit; in practice AMT 0 never reaches `fade`, because `chain` returns `FxChain()`
  first.)
- **`Breed.crossMacros`:** takes the section's name and reads its specs (`pick` has no
  section or macro context, `Breed.kt:198-206`, so the selector decision is made in
  `crossMacros` and handed down as a flag), and for a selector `pick` still draws
  `rng.nextInt(3)` **once**, as it does for any macro, and maps the third outcome, the
  mean, to **A's value**: a child says A's word or
  B's, never a third (A's word comes up two times in three; a fair coin is a
  different draw count and Decision 9 offers it). The random stream is the same
  length, and since no recipe in the tree has a `say` section, `crossMacros`
  returns before drawing for every existing cross (`Breed.kt:209`): no existing
  breed moves a byte.

**A sibling hazard this does not touch.** The engines' own WORD macros (VOX SPEAK, SWARM
and WRAITH) are averaged today: `Breed.cross` crosses two pads of one engine and voice through
the same `pick` (`Breed.kt:251-253`), so a bred pair of VOX SPEAK pads can already say a third
word. The new flag marks SAY's WORD only; those engines are out of scope ("Out of scope").

And the treatment leaves WORD unset (below), so the other generic test, "AMT scales
every section a treatment sets" (`FxTest.kt:1118`), never meets an unmoving macro.
**No existing test is edited for the selector**; new tests prove the flag
("Testing").

### The filter

`Say.process(snip, macros)` in `synth/src/main/kotlin/com/snipsnap/synth/Say.kt`
(shaped like `Contour.kt`: `MACROS`, `defaults()`, `scramble`, `process`). Per
channel, with **one clock for the frame** so a stereo pair speaks together and
identical channels stay identical (the rule ENSEMBLE and CONTOUR follow,
`Ensemble.kt:224`, `Contour.kt:65`):

1. **The track.** `Formant.Track` for `wordFor(WORD)`, vowel stretch `5^(1 −
   SPEED)`, no stutter, **no chip frames** (always the glide VOX SPEAK plays at
   HUMAN 1: the shared function takes the chip frame length and SAY passes 0), and
   VOX SPEAK's throat and bandwidth scale from SIZE (`VoxSpeak.kt:423-424`, through the
   one extracted size function). The
   clock is the snip's own: `t = frame / rate` from frame 0. There is no onset
   search: a captured pad is already cut to its attack and the onset is frame 0
   (`Contour.kt:56-58`), and a synthesized hit starts at 0.
2. **The tract.** `Formant.Tract` (`VoxSpeak.kt:315-329`): the nasal zero (270 Hz
   sliding up to 450 Hz with the nasal amount) and pole, then five formants in
   cascade. F1 to F3 come from the track, F4 and F5 are fixed at 3300 and 3750 Hz
   times the throat (`:203-205`, `:477`), bandwidths 60, 90, 150, 250 and 200 Hz times
   the giant's narrowing (`ring`: 1 at and below SIZE 0.5, falling to 0.5 at SIZE 1) and
   **times 1.5**, the two multiplied, so at SIZE 1 they are 0.75 of the base (the spike's widening: F1's own 60 Hz is
   narrower than the gong's 98 Hz partial spacing, and ×1.5 kept F1 tracking at
   r 0.99 and F2 at 0.84, "The sounds, measured"), with the damp factor on F2 to
   F5. The 1.5 is a constant, not a macro: the owner approved four. Coefficients
   are refreshed every **16 samples** (`CONTROL_BLOCK`, `:279`), which at 44.1 kHz is
   0.36 ms, a 2.76 kHz control rate against targets 10 to 100 ms apart (map
   rack-sections §4); the resonators stay in **double** (`:281`). Every formant
   frequency is clamped under 0.45 × the rate (the `Hiss` precedent, `:336`) so a
   child-sized throat at a low rate never puts a pole past Nyquist (half the sample rate, the highest frequency it can hold).
   The jaw is fixed at 1 (EFFORT is VOX SPEAK's, not a macro). The giant's chest resonator
   (`VoxSpeak.kt:425-426`, `:509-510`) is left out: it is a low boom added to a *voiced
   source*, SAY has no source, and a low boost on a pad is the rack's EQ's job.
3. **The hold: after the word, its final frame.** The whole word plays as the script has it,
   closing frames included (the n, v, k, s and t are formant frames only: the filter makes no
   noise), and then the filter **holds the script's final frame**, as the approved gong-speaks
   clip does. Nothing is appended: the track's own end-of-list clamp (`VoxSpeak.kt:466-469`: the
   index search stops one short of the end and `x` clamps to 1) is the hold, and the loop gains
   nothing. For ONE and SEVEN that frame is the closing nasal, a hum; for TWO and THREE it is the
   vowel's own formants (the voice switching off, which a filter does not hear); for the other
   four it is the closing sound's frame. The brief says that after the word ends the filter
   "holds the last vowel". That is a paraphrase of the approved clip, whose hold is the final
   frame, so the clip wins; read literally, as a return to the last open vowel, it is Decision 8's
   alternative. The table gives both, read off the script at `75b550c1` (natural speed, before
   SPEED retimes the script):

   | Word | Final frame, the default's hold: line, F1 / F2 / F3 (Hz) | Last open vowel, the alternative's hold: script time, line, F1 / F2 / F3 (Hz) | Played between them |
   |---|---|---|---|
   | ONE | `:115`, 260 / 1500 / 2500, nasal 1, damp 2.5: the closing n, a hum | 0.28 s (`:112`), 600 / 1250 / 2450 | the closing n, 0.33 s on (`:113-115`) |
   | TWO | `:124`, 310 / 900 / 2250: the vowel's own | 0.33 s (`:123`), 310 / 900 / 2250 | the voice switching off (`:124`) |
   | THREE | `:134`, 270 / 2300 / 3000: the vowel's own | 0.42 s (`:133`), 270 / 2300 / 3000 | the voice switching off (`:134`) |
   | FOUR | `:144`, 430 / 1200 / 1600: the "r" | 0.30 s (`:142`), 520 / 950 / 2200 | the "r", F3 down to 1650 (`:143`), then the voice switching off (`:144`) |
   | FIVE | `:156`, 350 / 1500 / 2400, damp 1.5: the closing v | 0.42 s (`:153`), 420 / 1950 / 2550 | the closing v, 0.46 s on (`:154-156`) |
   | SIX | `:170`, 350 / 2200 / 2800: the closing s | 0.28 s (`:164`), 380 / 2050 / 2600 | the closing k and s, 0.31 s on (`:165-170`) |
   | SEVEN | `:185`, 280 / 1600 / 2500, nasal 1, damp 2.5: the closing n, a hum | 0.42 s (`:182`), 480 / 1450 / 2450 | the closing n, 0.45 s on (`:183-185`) |
   | EIGHT | `:197`, 350 / 1800 / 2700: the t's release | 0.28 s (`:192`), 330 / 2250 / 2850 | the closing t, 0.31 s on (`:193-197`) |

   **The alternative, a return to the last open vowel.** The word plays as above, then the filter
   **returns to the word's last open vowel over 80 ms** and holds it. The 80 ms is *this spec's*
   listening value (the brief gives no number; VOX SPEAK's own tail past a word's last target is
   the same 80 ms, `VoxSpeak.kt:436`). It is built as one more target appended after the retimed
   script, 80 ms after the script's last, carrying the vowel's frame (nasal 0, damp 1, full
   voice), so the same end-of-list clamp holds it. Which frame is "the last open vowel" is not
   derivable from the script by one rule that fits all eight words (a voice-level threshold of
   0.8 takes FOUR's "r", 0.40 s on, voice 0.9; the script's `stretch` mark takes a frame partway
   through the vowel of FIVE, SEVEN and EIGHT), so **for the alternative the table's third column
   is the definition**: eight constants, each the last open frame before the word's closing
   consonant. A test pins each row used (the final frames, and the vowels if Decision 8 takes the
   alternative) against VoxSpeak's script by time and formants, so a later change to a word fails
   there and is not a silent change to SAY ("Testing"). How the hold is reached is Decision 8.
4. **Consonants are ignored.** The filter has no excitation, so the noise and
   burst fields of the script (`ah`, `af`, `fc`, `fbw`, the bursts) mean nothing
   and are not made: SIX's "s" and EIGHT's "t" do not appear unless the pad already
   has energy there (map rack-sections §4).
5. **Dry lead, then wet, level-followed.** The wet is the tract's output.
   `ed` and `ew` are one-pole energy followers on the dry and the wet squared with
   a 30 ms time constant (`k = 1 − exp(−1/(0.030 × rate))`), and the wet is
   scaled by `g = clamp(√((ed + ε)/(ew + ε)), 0.25, 6)` so it follows the dry's
   envelope. The mix is `w(t) = smoothstep((t − 12 ms)/50 ms) × MIX` and
   `out = (1 − w) · dry + w · g · wet`: **the first 12 ms are the dry strike**,
   the hand-over takes the next 50, and from 62 ms the word is all wet at MIX 1
   (bold-talk §2). The spike's followers started at 1e-12 and decayed unfloored
   (`TalkGong.postHoc`); at 44.1 kHz a few seconds of trailing silence take both to
   zero and the ratio to 0/0 = NaN, so ε = 1e-12 is added to both and the arithmetic
   is in double (*this spec*; "Failure handling").
6. **Peak-matched.** The result is scaled to the input's **joint** peak (every
   channel together, `Valve.kt:23-25`'s rule, so a quieter channel stays quieter),
   in either direction as CONTOUR does (`Contour.kt:74-80`): one constant k = inPeak / outPeak, which is above 1 whenever the word
   comes out quieter than the input's loudest sample, as it does for a gong that peaks late
   (170.7 ms in the spike, bold-talk §2). The rack's contract
   is the peak match (`FxTest.kt:268-290`), so SAY needs no exclusion.
7. **Linear, at the snip's rate.** The tract is a linear time-varying filter, so
   it cannot fold and needs no oversampling (map rack-sections §1; VALVE's 4× is
   "the first section that oversamples" because its tube is nonlinear,
   `Valve.kt`'s KDoc). It runs at the snip's rate. The ablation ran at the
   engine's 176.4 kHz; that the rack pass at 44.1 kHz hears the same is **a Phase 0
   measurement**, below.
8. **Length.** The output is exactly as long as the input: the filter's ring past
   the end is not kept, and the section grows nothing for `capTail` to cut
   (`FxChain.kt:110-119`).

### The treatment and the chip

- **`said`** is appended to `Treatments.EXTRA` (`Treatments.kt:30-69`), never to
  `Shuffle.TREATMENTS`, which a seeded bank indexes (`:23-29`):
  `"said" to FxChain(say = mapOf("MIX" to 1f))`. It sets MIX only. WORD is a
  selector and SPEED and SIZE are centred macros, and "a macro set at its neutral is
  one AMT could never move" (`Treatments.kt:57-68`'s comments, `ensembled`'s RATE
  and `amped`'s TONE): the chip's word is therefore **ONE, by omission**, the
  section default, which `SayTest` pins beside the neutrals (the pattern is
  `ValveTest.kt:135`) so a saved pad cannot drift. AMT is MIX: the pad sheet's
  first tap, 0.7 (`PadSheet.kt:113`), is a 70 % wet word.
- **The chip.** `SAY` is appended to `PadSheet.ANATOMY_SEGMENTS` (`PadSheet.kt:74` at
  `129bc48e`: SWELL, TAIL, SKIM, GHOST, SPIKE, SAY, which is the row's **six-chip ceiling**,
  `PadSheetTest.kt:189`) and `"SAY" to "said"` to `CHARACTER_FOR`
  (`:169-209`); it draws as itself. The chip count gains one, with the same test edits as
  STRUNG's: it is 31 at `129bc48e`, so SAY makes it 32, or 33 if STRUNG's chip (A3b) landed
  first. The brief picked the TIME row ("it has room for one"), but SECTION took that slot
  (commit `8fd72ae3`, merged by `129bc48e`), and the character row was already at its
  ceiling (`:77-84`). The anatomy row ("what the hit *is*: its arrival, its attack, its
  body") is the one row with room whose grouping fits a filter that reshapes the hit's
  body over time. The row is Decision 17, with no chip as the alternative. The card needs
  no `:app` edit (`PadSheetScreen.kt:2400`, `:913`).
- **Reach: a second tap steps the word** (owner, 2026-10-01; Decision 7, taken).
  The first tap on SAY treats the pad with the word ONE at the AMT on screen, like
  every chip. A re-tap of the lit chip keeps the card's oldest gesture, re-treating
  at the AMT on screen (`PadSheet.kt:377-380`), with one addition for SAY alone:
  - **AMT unchanged since the last SAY tap:** the re-tap steps the word
    (ONE → TWO → … → EIGHT → ONE) and re-treats.
  - **AMT moved:** the re-tap re-treats at the new AMT and keeps the word.

  So a re-tap never changes both at once. The status line names the word on
  every SAY tap (a Copy line under the PersonalityTest laws). The chip label
  stays `SAY`, so the row's width and `PadSheetTest`'s labels do not move.
  - **Storage:** the word goes into the pad's recipe as an optional `word` key
    beside `treatment: "said"`. It is read with `as?`, and a missing key means
    ONE, so a replay, a paste, a take or a breed keeps the word instead of
    falling back to ONE by name. `RecipeReplay.kt:90-91` reads it, and a
    SidecarFuzzTest seed carries a hostile `word`.
  - **CLI parity:** `treat said --word 1..8` (`TreatCommand.kt:15`; refused for
    any other treatment) and the `docs/CLI.md` line.
  - **Precedent:** this is the first chip whose re-tap means more than "re-treat".
    The rule is pinned in `:shell` (a PadSheet function the screen calls, unit
    tested), so ConventionTest is not the only guard on an `:app` change that
    cannot be compiled here.

  Recipes and breeding still carry all eight words as before.

### Phase 0, what was done and what is open

**Done.** The bold-talk spike (clip 1) built the tract on TIDE's GONG and proved
the three things the design rests on: the formants follow the word (r 0.99 and
0.84), the strike can be kept under a 12 ms lead with the word level-followed, and
a rack pass after the engine hears the same as the tract inside it (NCC 0.956,
spectrogram 0.995). Its code, clips and the ablation are kept with the evidence
(Appendix A).

**Open, and run first in SAY's plan** (each is printed on every run of the plan's
Phase 0 and recorded in its own spike note, as VALVE V1.1's was, not in this
document), because the spike measured one word on one source:

1. **The rack pass at the snip's rate.** The same gong through `Say.process` at
   44.1 kHz against the ablation at 176.4 kHz: formant slots within the
   ablation's own 40 Hz and NCC at least 0.95 to it (*this spec's* bar, set at
   the ablation's 0.956). Run with the default hold (the final frame, which the ablation had) so
   the comparison is like for like; Decision 8's alternative, the return to the last open
   vowel, is then run beside it and the difference printed.
2. **Other words.** Only ONE was measured as a filter ("I did not compare words
   by measurement for this clip", bold-talk §2). The eight words through the
   same noise probe as the claims test: every word's F1 and F2 tracks against
   its own script. Run with the default hold: each word's F1 and F2 tracks are read through the
   word, and after it the held frame is checked against the script's final frame (the table's
   second column, "The filter", step 3). With the alternative, the return and the vowel it
   holds are read the same way, against the table's third column.
3. **Other sources.** The spike's gong is a fold of sparse partials 98 Hz apart.
   Phase 0 runs the prototype on a snare, a kick, a VOX choir line, a held RESIN
   pad and a TINES bell and records, per source: the formant tracking where the
   source is dense enough to show it, the level-follow gain actually used (the
   minimum and maximum of `g`, and whether the 0.25 and 6 clamps engage), and the
   result's first 12 ms against the input.
4. **Identity.** A THUMP kick and snare through the chip's chain: do they still
   classify KICK and SNARE (the rack's identity rule: the squash, echo, reverb, ensemble,
   spike, contour, phase and vinyl tests at `FxTest.kt:137`, `:211`, `:260`, `:488`, `:842`,
   `:900`, `:930` and `:1012`, and CRUNCH's own at `CrunchTest.kt:79`)? Printed, not
   asserted: GHOST and RING are already excused as "no longer the hit it was"
   (`FxTest.kt:779-787` and `:863`), and whether SAY joins them is Decision 10.
5. **Cost.** Milliseconds per rendered mono second on the JVM, printed on every
   run. The spike's tract and second filter cost 34.6 ms over 1.886 s at 4× (146.5 ms
   against 111.9, bold-talk §2), about 18 ms per rendered second at 4×; at the
   snip's rate the per-sample work falls to a quarter. **Target for V1 (*this spec*,
   unmeasured): at most 10 ms per rendered mono second**, gated at the listen, the
   phone's number recorded beside it; a stereo pad costs about twice.

### Registration surfaces

| Surface | Where (75b550c1) | Change |
|---|---|---|
| The shared groundwork | `VoxSpeak.kt:281-329`, `:353-407`, `:421-425`, `:462-480`; new `Formant.kt`, `LegacyVoxSpeak.kt` | G1 then F1 ("Architecture") |
| The section | new `Say.kt` | `MACROS`, `defaults`, `scramble`, `process` |
| The rack | `FxChain.kt:11`, `:13-19` (order and KDoc), `:67` (field), `:191-192` (row) | `say` appended; the row between `contour` and `squash`; the KDoc paragraph |
| The selector | `Thump.kt:33` (`MacroSpec`); `Treatments.kt:95-109`; `Breed.kt:198-215`, `:274-276` | `selector` flag; `fade`; `pick` and `crossMacros` |
| The character | `Treatments.kt:30-69` | `said` appended to `EXTRA` |
| The chip | `PadSheet.kt:74` at `129bc48e` (`ANATOMY_SEGMENTS`), `:169-209` (`CHARACTER_FOR`), KDoc `:73` | `SAY`, six on the row (Decision 17) |
| The card, `treat`, replay | `PadSheetScreen.kt:2400` (the SAY re-tap branch); `PadSheet.kt` (the re-tap rule as a tested function); `TreatCommand.kt:15` (`--word`); `RecipeReplay.kt:90-91` (reads `word`, default ONE) | the second tap that steps the word, the `word` key (Decision 7, taken) |
| Docs | `README.md:476-521` (the rack paragraph and the order line); `docs/CLI.md:341-377` (the `treat` list, row two, the phone sentence) | `said`, the order, SAY on row two |
| Listening | `SayAuditionGenerator.kt` (test), a `generateSayAudition` task beside `synth/build.gradle.kts:336-343`, a `.gitignore` line (`testkit/say-audition/`) | the gate's clips and page |
| Tests | `FxTest.kt` (a SAY block; the generic ones are free), `TreatmentsTest.kt:30-35` (the names list), `PadSheetTest.kt:116-149`, `BreedFxTest.kt`, `BreedTest.kt`, `SidecarFuzzTest.kt:335-337` (a `say` seed), new `SayTest`, `FormantTest`, `VoxSpeakFrozenTest` | see "Testing" |
| Auto-pass, confirm green | `UatSimTest.kt:357-382` (every chip through its door at AMT 0.7, failures reported as findings); `PersonalityTest.kt:199-203` (the `TREATMENT <label>…` busy line at most 40 characters for every chip); `PadSheetTest.kt:262-270` (every chip tappable); `Retrim.kt:71` (reads the segment's label) | none |
| Not touched | `Tide.kt` (the ablation says an engine need not change); `FxChain.VERSION` | none |

## Data flow and compatibility

Everything is additive. `FxChain.VERSION` stays 1 (`FxChain.kt:154`) and
`PadRecipe.VERSION` stays 2 (`PadRecipe.kt:93`; "not bumped, pre-launch; the
owner's standing call", `2026-09-29-magnet-valve-design.md:365`); `kit.json` gains
no field, because a pad's recipe is free-form JSON the kit layer round-trips
verbatim ("a recipe written by a newer build ... must survive a load-save cycle
here untouched", `kit/src/main/kotlin/com/snipsnap/kit/KitStore.kt:216-219`).
MPC export is untouched: the WAV is baked and recipes are not exported (critic,
compatibility summary).

### BECOME

```
card / CLI → MutateSheet.apply / MutateCommand → Mutate.apply(becomeMs) → render → morph
          → KitBuilderModel.replaceAudio(slot, recipe) { result }       (KitBuilder.kt:494-506)
          → the original into the bin, the WAV rewritten, recipe {"mutate": {…, "become": ms}}
```

- **Nothing regenerates.** A mutate is baked into the WAV and the bin, and the
  recipe is provenance and the card's label: `RecipeReplay` refuses a mutate
  (`:64-66`, pinned by `RecipeReplayTest.kt:43-46`), and `Mutate.undo` restores the
  parents out of the bin byte for byte (`Mutate.kt:274-277`). So a ramp's
  parameters in the recipe are for display, history and re-rendering from the card,
  not for any existing file's compatibility (map mutate-morph §2).
- **An older build** reads `mode`, `with` and `drift` only (the old
  `MutateSheet.read`, `:179-185`), shows "MORPH", plays the baked WAV, and keeps
  the recipe verbatim on re-save. No path re-renders it, so nothing silently
  changes; only the label loses BECOME.
- **A newer build, an older pad:** no `become` key reads as 0, "MORPH", as ever.
- **DRIFT's bytes cannot move**: it never passes `becomeMs`, and
  `MutateTest.kt:162-182` ("exactly a roulette then a morph") and `CliTest.kt:1425-1440`
  pin them.

### STRUNG

```
chip tap → PadSheetScreen.applyTreatment (:860) → withFreshKit → [unEraPad if the bin has the original]
         → keyedPad(slot, "strung", amount, seed) → Keyed.apply → Strung.ring
         → rewriteEveryFile (every file the pad references, the bin first) → recipe {"keyed": "strung", …}
```

- **The key is read at the tap.** `keyedContext` hands the kit's key to the door
  (`KitBuilder.kt:753`); the recipe records the key's
  *label* as a note, and a replay rings in the destination kit's own key and says
  so when it differs (`RecipeReplay.kt:146-155`, pinned at `RecipeReplayTest.kt:118`).
  Changing the kit's key later does not retune a STRUNG pad: it is baked.
- **Every layer, every file.** A velocity-layered pad has each of its files
  rewritten with the same strings, each layer on its own; a round-robin chain
  is refused, as for every keyed treatment (`KitBuilder.kt:760-776`, `:1188`).
- **Size.** A STRUNG clap is about 1.35 s (bold-ring clip 3), about 180 KB as
  24-bit mono at 44.1 kHz against 52 KB dry (*this spec*); the bin holds the
  original once.
- **An older build** shows no chip lit for it (`PadSheet.segmentForKeyed` answers
  null for a keyed name no segment draws, `:271`), plays the baked WAV, and **a
  paste refuses loudly**: the replay reaches `keyedPad`, whose `Keyed.require`
  throws "unknown keyed treatment 'strung' - one of: …" (`Keyed.kt:72-74`).
  Nothing is silently different.

### SAY

```
macros → Say.process (Formant.Track + Formant.Tract) → Snip
       → FxChain.process → PadRecipe.process / render → the WAV (when applied by `treat` or the chip)
       → recipe {"recipe": 2, "fx": {"fx": 1, "say": {"MIX": 0.7}}, "treatment": "said", "amount": 0.7, …}
```

- **What a saved SAY depends on.** For a synth pad (a patch and an `fx` block) the
  kit regenerates from its sidecar bit for bit, and for a captured pad the recipe
  re-treats the captured audio (`PadRecipe.kt:61-69`). Both now run through `Say`,
  `Formant` and **VOX SPEAK's script tables**: a later change to a word's targets, to
  `retime` or to the interpolation would change what a saved SAY recipe re-renders
  to. TALK avoids this by baking the track into data; SAY cannot, because the track
  is what it runs. The frozen VoxSpeak guard (G1) is the tripwire: changing a word
  is then a visible, deliberate change to SAY's saved sound as well.
- **An older build, traced by path** (it has no `say` section and no `said`
  treatment):
  1. *Open the kit:* the recipe is kept verbatim (`KitStore.kt:216-219`); nothing is lost.
  2. *Play and export:* the WAV is baked; it plays and exports correctly.
  3. *The card and the diff:* `PadSheet.read` finds the character `said`, no chip
     lights (`segmentForCharacter` answers null, the phone ruling), and the takes
     diff names it by its own word, "SAID 70%" (`KitDiff.kt:104-110`).
  4. *COPY LAST TREATMENT and PASTE of a `said` pad:* `Plan.Character("said", …)`
     reaches `Treatments.chain`, which throws "unknown treatment 'said' - try one of:
     …" (`Treatments.kt:77-80`; `RecipeReplay.kt:91`, `:142`). It **refuses by name**.
  5. *A pad whose `fx` block carries `say`, run again in an older build:*
     `FxChain.fromJsonValue` walks its own `SECTIONS` and ignores the unknown key
     (`FxChain.kt:226-232`). Every place a saved `fx` block is run again was traced
     (`recipeOf` and `PadRecipe.fromJsonValue` across `shell`, `app`, `cli` and `synth`):
     DO IT AGAIN on a synth pad re-renders it **plain** and writes the recipe back
     without `say` (`RecipeReplay.kt:84-87`, `:161-164`); a kit breed builds the
     child's chain from the sections it knows and renders it (`Breed.kt:274-276`,
     `:292`), dropping `say` the same way; both are silently different, and the first
     permanently strips the key. The other readers do not run the rack again:
     Robin's zone grid and the ghost layers parse the recipe (`Robin.kt:97`,
     `KitBuilder.kt:368`), but a recipe carrying an `fx` block makes the velocity
     layers fall back to `soften` in either build (`Velocity.kt:108-114`), so a SAY
     pad is not re-rendered there; the SYNTH screen and the factory kits render
     recipes the build itself writes (`SynthScreen.kt:517`, `SynthKits.kt:41`). In the
     new build the same two paths, DO IT AGAIN and a breed, run SAY. Only bumping
     `FxChain.VERSION` would make an older build refuse (`unsupported fx version`,
     `:225`), and it would refuse every plain recipe the new build writes too;
     the owner's standing call is not to bump (Decision 12). **This is the honest
     cost of an additive section**, the same one VALVE and ENSEMBLE took, and it
     bites only a user running two builds against one kit.
  6. *A newer build, an older kit:* no `say` key is a bypass; nothing changes.
- **Replay is by name and amount.** In the new build `Plan.Character("said", amount)`
  replays the chip's chain (`characterPad(slot, "said", amount)`, `RecipeReplay.kt:142`),
  not the chain stored in the recipe. The chip can only make ONE, so a chip-made
  pad always pastes as itself; a hand-edited `said` recipe carrying another WORD
  would paste as ONE. Decision 7 lists the fix (refuse a `said` whose stored chain
  is not the treatment's own) and its cost.

## Failure handling

### BECOME

- **A bad parameter refuses in words before anything is touched**, from both entry
  points (`requireParams`, `Mutate.kt:124-144`): BECOME outside 0..2000 or on a move
  that is not MORPH is an `IllegalArgumentException` (the CLI turns the flags into
  exit 2 with the same words). A knob fraction that is not a number reads as the
  default, 0, OFF (`Knob.kt:21`).
- **A hostile recipe never throws.** `become` that is not a number, is NaN or
  infinite, negative, over 2000, or sits on a non-MORPH mode reads as 0; the readers
  use `as?` and a finite check, and `SidecarFuzzTest`'s seed gains the key.
- **Silent or tiny inputs** go through the guards MORPH already has: `normalizeTo`
  returns on a peak under 1e-9 (`Mutate.kt:515-520`), `Spectral` pads a frame at each end
  so a one-sample sound still has frames, and the ramp divides only by
  `rate × becomeMs` on a path where `becomeMs ≥ 1`.
- **A ramp longer than the sound** is legal and described: the ramp is on absolute
  time and the output is as long as the end blend, so a parent's tail keeps
  becoming the parent.
- **HEAR and KEEP refuse alike**: a layered or chained pad is refused by
  `requireRewritable` in the same words in both (`MutateSheetTest.kt:139-164`).

### STRUNG

- **STRUNG never refuses** (`Keyed.refusal` answers null, as for BODY: a drum with
  strings is the point).
- **Silence** is returned unchanged, the same object (a silent hit has nothing to
  ring and `Strings.trimToDecay` would otherwise hand back the whole 1.5 s buffer).
- **Hits shorter than the feed delay**, down to one sample: the feed starts at
  `min(40 ms, half the hit)`, so something always rings and nothing is longer than
  the sound needs (*this spec*; Decision 15).
- **DC and offset:** the feed is high-passed at 30 Hz and the wet sum DC-blocked at
  2 Hz; each loop's DC gain at feedback 0.98 is 50.
- **Strings the rate cannot hold.** The shared helper requires a loop of at least
  `Strings.MIN_LOOP_SAMPLES` = 2.0 samples (`Strings.kt:40`; the helper's own
  `require`, `Pluck.kt:917`). STRUNG skips a string whose loop would be shorter, as
  BODY skips a mode at or above Nyquist (`Body.kt:91`), and returns the input
  unchanged if none remain: a very low sample rate, never a throw. The helper
  exposes the loop length it needs so STRUNG asks instead of catching.
- **Non-finite samples** feed as 0 and the peak scan ignores them (BODY's rule,
  `Body.kt:111`).
- **Hot input.** The wet is scaled by g so that the sum never passes the hit's own peak, and
  the dry is never touched; a full-scale square cannot clip, and where the dry sits at its
  peak against a wet of its own sign g can reach 0, which returns the input itself.
- **Bounded cost:** at most five loops per channel over the hit plus 1.5 s.
- **Chained and layered pads** meet the keyed door's own gates (`KitBuilder.kt:1188`).

### SAY

- **Bypass is exact:** MIX 0, a silent pad and an empty pad return the input
  object; AMT 0 never reaches the section (`Treatments.kt:83`).
- **Macros are read through one function** that maps a non-finite value to the
  default and coerces to 0..1, as every section does at entry (`Contour.kt:49-50`);
  `FxChain`'s constructor already refuses a stored value outside 0..1, NaN
  included (`FxChain.kt:75`).
- **The level follower cannot divide by zero.** The spike's followers could reach
  0/0 after a few seconds of silence at 44.1 kHz; ε is added to both and the
  arithmetic is double ("The filter", step 5), tested with a minute of trailing
  silence.
- **Poles stay inside the circle at any rate:** every formant is clamped under
  0.45 × the rate and every bandwidth is positive, so a child-sized throat at 8 kHz
  (the `DegenerateDoorsTest.kt:53` fixture) is a different, valid filter and never an
  unstable one.
- **Stereo:** per-channel state, one clock; a silent channel beside a live one stays
  silent and the live one keeps its level (joint peak).
- **A hit shorter than the 12 ms lead** comes out as itself (the wet weight is 0
  throughout) at the match gain, finite.
- **Truncation:** the word is cut by the sound's length; nothing waits for the word.
- **Determinism:** no randomness and no seed; the same pad and macros give the same
  bytes.

## Testing

Tests guard properties; they never author sounds. Thresholds that are *this spec's*
are set from the spike's numbers with margin; where the built code disagrees, the
house rule applies: print both numbers, record the measurement, set the threshold
from it, never loosen one without writing down why (`2026-09-29-magnet-valve-design.md`,
"Testing").

### The ones that carry the claims

**BECOME**

- **BECOME 0 is today's MORPH, bit for bit.** `LegacyMorph` is today's whole MORPH
  path frozen in test sources: `Mutate.render`'s alignment and resampling (`toStereo`,
  `resampled`, `alignToOnset` with its `HOT_OPEN_RATIO`, all private, `Mutate.kt:178-179`,
  `:443-489`) and then `morph()` with its `peak` and `normalizeTo` (`:342-372`,
  `:506-520`), so the test compares like with like. It runs against `Mutate.render`
  MORPH at amounts 0, 0.25, 0.5 and 1 over three pairs (a THUMP kick with a TINES bell,
  a snare with a kick, a tone with a tone at another rate), `assertContentEquals`. The recipe for MORPH with BECOME 0 has exactly the keys
  `mode`, `with`, `amount`. Not a comparison of the new code with itself.
- **The ramp function:** `becomeAmount` is 0 at and before the first frame centred
  at or before the onset, never decreases, equals the amount from `becomeMs` on, and
  is linear between (checked at the 0, 25, 50, 75 and 100 % points, at 44.1, 48 and
  96 kHz, since the frame time is a function of the rate).
- **Early frames are the pad, late frames the parent.** The construction of
  `CliTest.kt:3019-3074`: a 300 Hz decaying tone as the pad and a 1200 Hz one as the
  parent, BECOME 400 ms at MIX 1. In the first 30 ms the 300 Hz line is at least 5×
  the 1200 Hz line (the bound `:3054` uses); from 600 ms the 1200 Hz line is at least 5×
  the 300 Hz one; at 200 ms both are audible (the smaller above 0.2 of the larger,
  as `:3062`); one onset (`Transients.detect`, as `:3064`).
- **Length and level follow the end amount.** `frameCount` equals MORPH's at the same
  MIX, and the peak is the same interpolated target.
- **HEAR is KEEP:** `MutateSheetTest.kt:93`'s pattern for MORPH at BECOME fractions 0,
  0.25 and 1, within 2/8 388 607.
- **Recipe round trip and readers:** `become` is written as 400 for BECOME 400 and
  absent for 0; `MutateSheet.read` returns `becomeMs` 400 and `word` "BECOME"; the
  hostile values (a string, NaN, −5, 1e9, a non-MORPH mode) read as 0 and "MORPH";
  `extraRecipe` carrying `become` is refused; `SidecarFuzzTest`'s `mutate` seed
  (`:347`) gains `"become": 400`.
- **CLI pins** (beside `CliTest.kt:3019`): `--morph --become 400` exits 0 with the
  recipe key and the extra report line; the first report line is byte-identical to
  today's without `--become`; `--become 400` without `--morph` exits 2 with
  "--become rides on --morph"; `--become 3000` and `--become x` exit 2; `drift …
  --become 400` exits 2 as an unknown option; the DRIFT tests above are untouched.
- **The sheet:** the BECOME knob's range, default 0, 50 ms steps at the 1/40 snap,
  labels "OFF" and "500 ms" (never "50000%"); `knobFor(MORPH)` is still MIX.
- **The :app laws** (source text, `ConventionTest`): the existing J24 laws pass
  unedited, and a sibling law pins that `onDrift` resets `pendingBecome` and that
  `pendingBecome` is a `remember(slot)` holder.

**STRUNG**

- **The strings are the key's chord.** `Strung.strings(9, MINOR)` is MIDI 52, 57,
  60, 64, 69; `(0, MAJOR)` is 55, 60, 64, 67, 72; chromatic is the open voicing;
  a scale with no fifth drops the fifths; the degrees agree with `Body.modes` for
  every root and scale (the source of the rule). Through `Strung.ring`, a click's
  tail shows spectral peaks within 10 cents of every string (the spike's worst was
  +8 cents with ±3.5 cents of detune, bold-ring clip 3).
- **The hit rings the strings, late.** A 100 ms noise burst through STRUNG against
  the same 100 ms burst with samples 40 to 100 ms set to zero (the same length, so
  the feed delay stays 40 ms rather than shrinking to half the hit): the ring energy
  from 0.2 to 0.6 s is at least 30 dB lower for the gated burst (*this spec's* bar;
  the strings are fed only after the delay). This is the difference between "rung by
  the hit" and a chord laid under it.
- **The attack is the hit's.** The first `min(40 ms, half the hit)` equal the dry hit **bit
  for bit**, always (the wet is zero there); the output never passes the hit's peak; on the
  clap g is 1 and `max |clip − dry|` over the first 40 ms is 0.0, as the spike's was
  (bold-ring clip 3); on a full-scale square g is 0 and the result is the input object.
- **AMT:** 0 returns the same object; on the A-minor clap, where g is 1 at every AMT, the
  wet's RMS scales linearly with AMT within 1 %; AMT 1 on the clap is the spike's `dry + 2.5 × wet`.
- **Length:** at least the hit, at most the hit plus 1.5 s, ending at the −60 dB point
  of the joint envelope, which is found on the channels' RMS envelope counted in frames and
  not on the interleaved floats (the same clap as a stereo snip cuts at the same frame as the
  mono one, and an 11 025 Hz snip cuts on a whole frame). Every channel ends on the same
  frame, with no click at the cut: the last frame of every channel is 0 (`Dsp.fadeTail`) and
  the largest sample-to-sample step in the last 4 ms stays under a bound printed and pinned
  at the build.
- **No offset:** the mean of the output beyond the hit stays under a bound printed
  and pinned at the build (the DC lesson of `Pluck.kt:272-289`); a DC input and a
  full-scale square stay finite.
- **Determinism and stereo:** two different seeds give the same bytes; identical
  channels stay identical; a quiet channel beside a live one stays quieter.
- **BODY is untouched** (`LegacyBody` grid) and **PLUCK's loop is untouched**
  (`LegacySympathetic` grid, S1), both sample for sample.
- **The doors:** `DegenerateDoorsTest`'s eight fixtures (`:46-59`: one sample, tiny,
  silence, DC, full-scale square, 8 kHz, stereo, noise) × `Strung.ring` and
  `keyedPad("strung")`, each a finite result or an `IllegalArgumentException` in
  words; a `strung` seed beside `bodied` at `:195-202`, so the replay door meets it.
- **Registration:** `PadSheetTest.kt:116-149` (rows, inventory, 31 chips at `75b550c1` and 32 at
  `129bc48e`, rows at most 6); `KitBuilderTest.kt:790-825` (a strung block beside the BODY one: the
  recipe reads back as `Applied(Keyed("strung"), …, "STRUNG")`, undo is byte
  identical); `RecipeReplayTest.kt:118` (a strung recipe replays in the destination's
  key); `SidecarFuzzTest.kt:380` (a strung seed); `CliTest.kt:1278-1289`'s shape
  for `strung` (exit 0, `--amount 2` exit 2, `--undo` byte identical).

**SAY**

- **The frozen VoxSpeak guard (G1) and the extraction (F1):** 276 cases, sample for
  sample, green before and after ("Architecture").
- **The word is in the formants.** Seeded white noise (a source with energy
  everywhere, so the filter is not confounded by a source's own partials) through
  `Say` at MIX 1, SPEED 1, SIZE 0.5. The measure is the section's **effective
  response**: the ratio of the output's STFT magnitude to the input's, same window,
  averaged over the window's frames and a few adjacent bins (a raw peak read off 40 ms
  of noise has about two degrees of freedom per bin and is too noisy to threshold;
  the input is known, so the ratio is not). At five times across the word the peaks
  of that ratio near F1 and F2 sit within 40 Hz of the word's own track (widened as
  the section widens it); the same ratio for the unprocessed noise is flat at 1, which
  is the control (the spike's plain gong failed the same test: r 0.31 and −0.24). LPC
  peaks, as the spike used, are the alternative if the ratio proves too soft. Run for
  all eight words. The spike measured one word on a gong; this measures all eight on
  noise, where the source cannot hide the filter.
- **After the word, the filter holds.** On 2 s of noise at SPEED 1 (the script's last target falls
  at 0.42 to 0.63 s), the effective response (the same ratio) at 1.0 to 1.2 s equals
  the one at 1.6 to 1.8 s within a bound printed and pinned at the build, per
  third-octave band above 250 Hz: held, not looped and not bypassed. The held ratio matches the
  tract's own analytic response at that word's final frame within a bound printed and pinned at the
  build (compared whole: a nasal's wide, damped resonances blur an F1 and F2 peak read). The
  comparison is of ratios, not of absolute band levels, so the noise's own fluctuation cancels.
  If Decision 8 takes the alternative, the same test holds with the return ended 80 ms after the
  script's last target and the held ratio's F1 and F2 peaks within 40 Hz of that word's last open
  vowel (the table's third column).
- **The hold is the script's final frame:** for each of the eight words the track's value past its end
  equals the script's last target (formants, nasal, damp; the table's second column of step 3), so a
  later change to a word fails here, and nothing is appended. If Decision 8 takes the alternative:
  each of the eight vowels in the table's third column equals the script's target at that time
  (formants, nasal 0, damp 1, `av` 1); the appended target sits 80 ms after the script's last, and the
  track ends on it.
- **SPEED and SIZE:** the time the track reaches its final frame (the word's retimed end) scales as natural + vowel interval ×
  (`5^(1 − SPEED)` − 1), plus 80 ms to the last open vowel if Decision 8 takes the alternative; the peaks at SIZE 0.25 sit above those at 0.75 by the throat
  ratio, within 3 %; SIZE 0.5 is throat 1.
- **The strike is kept:** the first 12 ms equal the input times one constant, the k of step 6
  (k = inPeak / outPeak, which may be above 1: the word can come out quieter than the
  input's loudest sample, and the spike's gong peaks at 170.7 ms, bold-talk §2, so k above 1
  is the usual case there), for a THUMP snare, a THUMP kick and the gong. No claim is made
  about k's size; it is printed.
- **MIX 0 is a bypass, the same object;** MIX 0.5 sits between; `said` exists, sets
  only `say`, and is a bypass at AMT 0 (`FxTest.kt:714`'s pattern); `TreatmentsTest.kt:30-35`
  gains "said".
- **Placement:** SAY sits between CONTOUR and SQUASH in `SECTION_NAMES` (VALVE's
  adjacency test, `FxTest.kt:695-702`); the generic tests pick it up free
  (`:268`, `:292`, `:1059`, `:1093`, `:1106`, `:1118`, `:1143`).
- **The selector:** `Treatments.fade` leaves a WORD of 0.43 at 0.43 for AMT 0.3 and
  moves MIX; at AMT 0 it lands on the neutral; over 200 seeds `Breed.cross` of a chain
  saying ONE and one saying SEVEN gives ONE or SEVEN and never another word; and for
  64 seeds the cross of two chains with **no** `say` section equals a frozen copy of
  today's `crossMacros` loop (the stream is unchanged).
- **Defaults and neutrals pinned,** in `ValveTest.kt:135`'s form: WORD 0 (selector),
  SPEED 0.5 (neutral 0.5), SIZE 0.5 (0.5), MIX 1 (0).
- **No NaN after silence:** a hit followed by a minute of zeros at 44.1 kHz stays
  finite and ends at silence.
- **Stereo:** identical channels intact (generic) and a quiet channel beside a live
  one stays quieter.
- **The doors:** the eight `DegenerateDoorsTest` fixtures through `Say.process` and
  through `characterPad("said")`.
- **Printed on every run, not asserted:** the section's cost per rendered mono
  second (target at most 10 ms), the kick and snare class after SAY, and the
  minimum and maximum level-follow gain used.

### The rest

- `SidecarFuzzTest.kt:335-337`'s `FxChain.fromJsonText` fuzz gains a seed with a
  hostile `say` block (a string for WORD, a missing MIX, an extra key).
- `BreedFxTest`'s "keeps every rack section" passes unedited (every macro 0.6,
  WORD included).
- `FxTest`'s contract-excluded and stereo-excluded self-checks need no entry for
  SAY (it meets both clauses); if it did not, the exclusion would be written with
  its measurement.
- Docs: `README.md`'s rack paragraph and order line, `docs/CLI.md` (the `treat` list,
  row two for SAY, row six for STRUNG, the `strung` section, the `--become` paragraph), and one
  `docs/FEATURE_PLAN.md` row each for BECOME and STRUNG.

## Phasing and gates

Every phase ends the house way: stop and listen. **Every gate is at most ten clips
and at most three questions, each question one decision** (the owner's standing
preference, saved after round two's 111 clips were found too many). The order is A1
BECOME, A3 STRUNG, A2 SAY, and "A alongside B" means these rounds may run beside the
Group B spec's, with the shared groundwork (G1, F1) landing once, in whichever group
reaches it first.

**What a gate does.** A gate moves listening values (the numbers the brief marks as
such: BECOME's curve and first step, STRUNG's first tap, SAY's dry lead) and can stop a
phase; it never cancels an approved item by itself. The card row, the chip and the
section are rows of "Decisions already taken", and only the owner takes one out. So each
phase is built in the order the house uses for TERRA's roster: **the logic first** (the
DSP, the doors, the CLI, the recipe, the guards, the tests, the audition generator,
none of it drawn on the phone), the gate's clips rendered from it by the generators, and
**the phone-visible step last, after the owner's answer**: A1b is BECOME's card row, A3b
STRUNG's chip, A2b SAY's treatment `said` and its chip. Each gate has one *stop answer*;
hearing it holds back that last step and puts one question to the owner (Decision 14).
Nothing approved is dropped without the owner saying so.

| Phase | Ships | Gate |
|---|---|---|
| **0** (done) | the bold-round spikes (clips 1 to 3), recorded in the evidence; the tree check at `75b550c1` | none: the sounds are measured, the owner kept the families |
| **A1** | BECOME's logic: `Mutate` (ramp, validation, recipe key, the `extraRecipe` guard), `MutateSheet` (knob, readout, read, HEAR = KEEP), the CLI flag, `LegacyMorph`, tests, docs | the A1 gate, below |
| **A1b** | BECOME's card row (`MutateCard`, `pendingBecome`, the DRIFT line, the sibling law): the only `:app` edit in the group | after the A1 gate, unless its stop answer is given (Decision 14). **The A1 gate was answered 2026-10-02 without the stop answer; A1b is planned in `2026-10-02-a1b-become-card.md`, and the owner's phone check is its gate** |
| **A3-R0** | S1 (the sympathetic loop lifted, `LegacySympathetic`) and BODY's guard (`LegacyBody`): no audio change | both guards green; `PluckTest`, `TuningAccuracyTest` and `ExportRegressionTest` pass unedited |
| **A3** | STRUNG's logic: `Strung`, the keyed door (`Keyed.NAMES`), the CLI command, tests, docs | the A3 gate |
| **A3b** | STRUNG's chip: `KEYED_SEGMENTS`, `KEYED_FOR`, the `PadSheetTest` counts | after the A3 gate, unless its stop answer is given (Decision 14) |
| **G1 and F1** | G1, the VoxSpeak guard (green against the unchanged source), then F1, `Formant.kt` (green again): no audio change | the guard; shared with the Group B spec's R3 |
| **A2-V0** | SAY's Phase 0: the five open measurements ("SAY, the design") | printed and recorded in the SAY plan's own spike note; **stop** if the rack pass at the snip's rate misses the bar |
| **A2** | SAY's logic: `Say`, the section row, the selector flag, the audition generator, tests | the A2 gate |
| **A2b** | SAY's treatment `said`, its chip on the anatomy row (Decision 17), the second tap that steps the word with its `word` recipe key and `treat said --word` (Decision 7, taken), the `README.md` and `docs/CLI.md` lines | after the A2 gate, unless its stop answer is given (Decision 14) |

**Effort**, from footprints and the spikes' own estimates: BECOME is about 40 lines
of DSP, the recipe field and a slider (bold-talk §6, "half a day"), plus about
80 in the sheet and the CLI and about 40 in `:app` (*this spec's* count); STRUNG's
lift is about 20 lines and the treatment about 100 to 130 (bold-ring, "what a real
build needs", which put the treatment and its card together at 150 to 250 lines;
the chip needs no card edit); SAY is `Formant.kt` at about 150 lines and `Say.kt`
at about 120 plus the section row and the tests (bold-talk §6, "half a day").
For the section's size, CONTOUR was five files and +132/−2 (`git show --stat 298a7590`);
ENSEMBLE's first commit, with its audition page and generator, was 14 files and
+1521/−35 (`e0d35f77`); SAY sits between them.

**:app cannot be built or run in this environment** (no Android SDK, so `:app` is
not in the Gradle build, `settings.gradle.kts`). BECOME's card row is the only
Android code in the group; its logic (the knob, the readout, the ramp, HEAR = KEEP,
the CLI) is in `:shell` and `:synth` with tests, `ConventionTest`'s source-text laws
check what they can of `PadSheetScreen`, and **the owner's phone is the final
check**. STRUNG's and SAY's chips need no `:app` edit at all (A1b is the one `:app` step).

### The A1 gate: BECOME

Sources, the spike's (bold-talk §3): a THUMP KICK (TUNE .80, SWEEP .5, DECAY .6,
HOLD .3, CLICK .35, DRIVE .3: 55.00 Hz, 0.560 s) and a TINES BELL (carrier 220 Hz,
RATIO .67, BRIGHT .62, DECAY .85, BITE .3, CLANG .4915, so every partial sits on
the kick's harmonics). Ten clips:

1. the kick; 2. the bell (the two sources, for reference);
3. today's MORPH at 0.5 (BECOME off), the nearest MORPH;
4. SPLICE at 100 ms, the nearest by NCC (0.589);
5. to 8. BECOME 100, 250, 500 and 1000 ms at MIX 1;
9. BECOME 250 ms at MIX 0.5;
10. the spike's own clip 2, levelled the same way: what the owner heard in the bold
    round, with its delay, level, head fade and smoothstep.

Questions (three, one decision each):

1. Which of 5 to 9 is a kick *turning into* a bell, and not a kick *followed by* a
   bell? The pick sets the card's useful range and the first step's default.
2. Is the pick the same sound as 3 and 4 with a longer seam, or a different sound?
   (The honest risk, bold-talk §7 item 3.)
3. Is 10 better than your pick? If so the spike's extras are added behind BECOME,
   one listen each (Decision 1).

**Stop answer.** Question 2 answered "the same sound with a longer seam": A1b, the
card row, is not built, BECOME stays a CLI move, and the owner is asked whether the card
row stays anyway (Decision 14).

**Answered 2026-10-02.** The owner listened on the ten-clip BECOME page and answered:

1. "They all do." Every one of clips 5 to 9 is a kick turning into a bell, so BECOME's
   full range stands: linear, 0 to 2000 ms, 50 ms steps on the phone (Decision 2's
   default). `MutateSheet.BECOME` does not change.
2. "A different sound." This is not the stop answer, so A1b, the card row, is built
   (`docs/superpowers/plans/2026-10-02-a1b-become-card.md`).
3. "6 is as good or better." The question was asked again in plain words, with clips 6
   (BECOME 250 ms at MIX 1) and 10 (the spike's own clip) sent as files. None of the
   spike's five extras is added (Decision 1's default).

There is no listening gate after A1b. The sound is approved, and the owner's phone check
of the card row is A1b's gate.

### The A3 gate: STRUNG

Sources: the testkit's `A06_Clap_01.wav` (17 441 frames, mono, 44.1 kHz, 24-bit;
the file the spike read, byte-identical in `testkit/SnipSnap Velocity Kit/`,
`testkit/SnipSnap MPC3 Kit_[TrackData]/` and `testkit/SnipSnap Session_[ProjectData]/`),
a THUMP snare and a THUMP kick, in A minor, C major and no key. Ten clips:

1. the clap dry;
2. STRUNG at AMT 0.7 (the chip's first tap), A minor;
3. STRUNG at AMT 1 (the spike's level), A minor;
4. STRUNG at AMT 1, C major;
5. STRUNG at AMT 1, no key (C, open fifths);
6. BODY at AMT 1 on the clap, A minor (the nearest in the app);
7. MUTATE STACK of the clap over a plucked A-minor chord (the nearest by spectrogram,
   0.963; the chord is the five notes through PLUCK NYLON at DAMP .30, summed and
   peak-normalised, bold-ring clip 3);
8. the snare, STRUNG at 0.7, A minor; 9. the kick, STRUNG at 0.7, A minor;
10. the spike's own clip 3.

Questions (three, one decision each):

1. Is 3 closer to BODY (6), closer to a chord under the clap (7), or its own sound?
2. Which is the right first tap, 2 (AMT 0.7) or 3 (AMT 1)? Clips 8 and 9 are the same
   first tap on a snare and a kick, so the answer is judged on them too. Answering 3 raises
   STRUNG's own level from 2.5 to 2.5/0.7 (about 3.6) so that the shared first tap, AMT 0.7,
   sounds like clip 3; the shared tap (`PadSheet.kt:113`) is not touched.
3. Do 4 (C major) and 5 (no key) sound in tune with the kit? (Decision 4.)

**Stop answer.** Question 1 answered BODY (6) or the chord under the clap (7): A3b, the
chip, is not built, STRUNG stays a CLI move, S1's lift and the guards stand on their
own, and the owner is asked whether the chip stays anyway (Decision 14).

### The A2 gate: SAY

Sources: the spike's TIDE GONG (the TEMPLE GONG preset with GLOW 1, WARP .55, FOLD .3,
TUNE .29, DECAY .7, bold-talk §2), a THUMP snare and kick at defaults, and VOX
SPEAK's COUNT ONE. Every gong clip is cut to 1.5 s with a 250 ms raised-cosine fade,
as the spike's was (bold-talk §2), so the comparison with clip 7 is fair. Eight clips:

1. the gong dry;
2. SAY ONE at the defaults (SPEED .5, SIZE .5, MIX 1), the word then holding its final frame, the closing n's hum, as 7 does;
3. SAY FIVE at the same settings;
4. the snare and 5. the kick through the chip's chain at AMT 0.7 (the generator builds
   `FxChain(say = {MIX: 0.7})` directly: the treatment `said` lands only in A2b);
6. VOX SPEAK's COUNT ONE, the voice the word is read from;
7. the spike's own clip 1, the tract inside the engine, levelled the same way: what the
   owner heard in the bold round, which holds the word's final frame (the closing hum);
8. SAY ONE at the same settings with Decision 8's alternative hold: the word then returning
   to its last open vowel over 80 ms and holding it (the brief's "the last vowel" read literally).

Questions (three, one decision each):

1. Do 2 and 3 say their words ("one", "five") as clearly as 7 said "one"?
2. Does the gong's strike come through on 2 before it starts to speak? (The 12 ms dry
   lead, a listening value.)
3. Do 4 and 5 still sound like the hit you put in? (Decision 10.)

SPEED 1 and SIZE .85 are not on the page: the defaults stand unless the owner says
otherwise (the house's rule that every number marked *shape* is a listening value the
gate may move, `Valve.kt:48-50`), and Phase 0 measures them. The word's ending is the approved
clip's, the final frame, which clips 2, 3 and 7 share; clip 8 lets the owner hear what the brief's
"the last vowel", read literally, would do, and taking it is Decision 8's, the owner's to make.

**Stop answer.** Question 1 on 2 and 7 together: if 2 does not say "one" as clearly as 7
did, A2b (`said` and the chip) is not built and the owner is asked (Decision 14). G1
and F1 have landed by then and stay: they move no audio and the Group B spec's TALK
uses them.

## Out of scope

- **BECOME on SPLICE, SPLIT, ROOM or TRANSPLANT, and on DRIFT.** "A kick becomes a
  bell" is MORPH's gesture and the brief gives BECOME to MORPH alone; DRIFT never takes
  it (`DriftCommand` unchanged), so the one-tap move stays a flat blend.
- **A START amount other than 0, or an END amount other than MIX.** The brief fixes
  the hit to start as the pad and end at MIX; a movable start is a different
  gesture and a second stepper.
- **A consonance guarantee.** The spike tuned its bell to the kick's harmonics by
  hand; two arbitrary pads will not agree, and retuning the parent is extra work
  (bold-talk §6), not asked for.
- **A curve picker on the phone.** The curve is a listening value; the card has one
  more row and no more.
- **A waveform of the ramp on the card.** The "third waveform" in
  `design/mutate-v2/Heard.dc.html` is a proposal that was not built
  (`README.md:28` there); BECOME does not build it.
- **STRUNG as an option on BODY, or any change to BODY.** BODY stays byte for byte;
  the reasons are in "STRUNG, the design".
- **A chord that is not the key's.** No per-pad chord, no chord taken from another
  pad, no chosen quality: the kit's key is the source, and the hit's own pitch class
  stands in for a missing key as it does for BODY.
- **Strings that retune with the hit, or couple to each other.** Kick plucks string
  is Group C; the house's model couples each loop to the played string only
  (`Pluck.kt:891-910`'s KDoc).
- **A ring-time dial or `--decay` on STRUNG,** and any other new `Keyed.Dials` field
  (Decision 6).
- **SAY's consonants, a voiced source, and the rest of VOX SPEAK's macros.** SAY is a
  filter; HUMAN, EFFORT, STUTTER and TUNE are the voice's, and a talking voice is
  VOX SPEAK.
- **SAY inside an engine.** The ablation (NCC 0.956) says the rack pass hears the
  same, and TIDE's GONG and FLARE have no room for an eighth macro (bold-talk §6).
- **A word list or picker on the phone beyond the second-tap step, and words other than
  the eight** (Decision 7 is taken as the second tap; a list is a later card design).
- **Engine WORD macros under breed.** VOX SPEAK's, SWARM's and WRAITH's WORD macros are
  averaged by `Breed.cross` today (`Breed.kt:251-253`); SAY's flag does not touch that path,
  and marking those engines' specs is a change to shipped engines.
- **Oversampling for SAY,** a fixed formant filter as a section (ARCO's ruling stands),
  and a change to `FxChain.VERSION` (Decision 12).
- **The Group B spec's HIT, BEND and TALK, Group C, CHIMERA as an engine, and the
  three probable house bugs** the review found, which are offered separately. The
  Group B spec says why for each.
- **Compiling or running `:app` here.** There is no Android SDK in this environment;
  see "Phasing and gates".

## Decisions already taken

What this document treats as closed, and on whose authority. The owner's answers
were given in the brainstorm of 2026-09-29 to 2026-09-30, one section at a time; this
table does not date them more finely than the brief does.

| Question | Decision | By |
|---|---|---|
| BECOME: the A1 gate's question 1 (which of clips 5 to 9 turns a kick into a bell) | "They all do": the full range stands, linear 0 to 2000 ms in 50 ms steps (Decision 2's default); `MutateSheet.BECOME` is unchanged | owner, 2026-10-02 |
| BECOME: the A1 gate's question 2 (the same sound as clips 3 and 4 with a longer seam, or a different one) | "A different sound": not the stop answer, so A1b, the card row, is built | owner, 2026-10-02 |
| BECOME: the A1 gate's question 3 (is clip 10 better than the pick), asked again in plain words with clips 6 and 10 sent as files | "6 is as good or better": no extras; the linear ramp alone (Decision 1's default) | owner, 2026-10-02 |
| SAY's word on the phone | A second tap on the lit SAY chip steps the word when AMT has not moved since the last SAY tap; the word is stored as a `word` recipe key ("Reach") | owner, 2026-10-01 |
| Is CHIMERA built as an engine? | No. Group A reuses MUTATE, the keyed family and the rack; the Group B spec lists CHIMERA as an engine out of scope, with the measurement (post-render hybrids equal MUTATE or TERRA alone after 20 ms, NCC 0.9993 to 1.0000, `hybrid-r2-spec`) | the owner's approval of the grouping, 2026-09-29–30 |
| How far should a hybrid go | "I think we could go further/bolder"; the bold round ran | owner, 2026-09-29–30 |
| Which bold families | **Keep Steer, Ring and Talk** | owner, 2026-09-29–30, after the bold round |
| The grouping | A (quick wins: MORPH over time, the clap's strings as a chip, the gong-speaks section), B (the TERRA hook), C (later); "snare rings the tines" parked for FORK; Ring dissolves into the others | owner, 2026-09-29–30, regrouping by build seam |
| The order | "A alongside B"; inside A: BECOME, STRUNG, SAY | owner, 2026-09-29–30 |
| BECOME: the move | a second row under MORPH, 0 to 2000 ms, 0 = off; the hit starts as the pad (amount 0) and turns into the MIX blend over BECOME ms from the aligned onset; BECOME 0 is today's MORPH byte for byte; length and peak follow the end amount | owner, 2026-09-29–30 (Group A design, one section) |
| BECOME: curve, recipe, CLI, card | the curve is a listening value, default linear in amount per STFT frame (the spike used 0 to 1 over 10 to 150 ms); `become` (ms) inside `mutate`, written only for MORPH above 0, `amount` still written, readers tolerant (`as?`), `extraRecipe` must not collide; `--become` on `MutateCommand`, refused without `--morph`; DRIFT never takes it and its ConventionTest block is unchanged; a second `StepperSlider` for MORPH only, the `—` row for the other moves; ▶ HEAR = KEEP through `MutateSheet.knobs` | owner, 2026-09-29–30 (same section) |
| STRUNG: the chip | a new keyed chip beside BODY; strings tuned to the kit's key chord; with no key, `Body.rootFor`'s rule (the hit's pitch class, else C); one AMT, AMT 0 returns the same object | owner, 2026-09-29–30 |
| STRUNG: BODY and PLUCK | BODY untouched with a frozen-copy byte guard; PLUCK's `sympathetic()` lifted into one shared `:synth` helper with a frozen-copy guard on PLUCK's own sound (the `StringsTest` / `LegacyPluckLoop` pattern) | owner, 2026-09-29–30 |
| STRUNG: listening values | detune ±0.2 %, coupling 0.15, feedback 0.98 (house 0.995), fed from about 40 ms after the hit, tail cap about 1.5 s, a one-pole DC high-pass (`Pluck.kt:272-289`'s lesson) | owner, 2026-09-29–30 (the spike's) |
| STRUNG: module rule and surfaces | Keyed (`:shell`) may import `:synth`, `Body` (`:audio`) cannot; `Keyed.NAMES`, `PadSheet.KEYED_SEGMENTS` and `KEYED_FOR`, `PadSheetTest` counts, the CLI command and `docs/CLI.md`, RecipeReplay defaults; the degenerate-input doors meet BBB6 (`docs/FEATURE_PLAN.md:1509`, the plan row that runs every door over one-sample, tiny, silent, DC, square, low-rate, stereo and noise inputs) | owner, 2026-09-29–30 |
| SAY: the section | a time-varying formant filter after the engine on any pad, following one of VOX SPEAK's eight words; macros WORD (a selector, snaps to a word), SPEED (word stretch), SIZE (throat scale), MIX (dry/wet; MIX or AMT 0 is a bypass); after the word it holds where the word ended, the approved clip's final formant frame (the brief's "holds the last vowel" is a paraphrase of that clip: Decision 8); consonant energy ignored; a dry lead of about 12 ms keeps the strike; peak-matched; per channel; at the snip's rate | owner, 2026-09-29–30 |
| SAY: the name | **SAY, not SPEAK** (`VoxVoice.SPEAK` and the "SPEAK BOX" preset exist); the JSON key `say` is permanent | owner, 2026-09-29–30 |
| SAY: placement | after CONTOUR, before SQUASH, "shape, then drive"; the rationale goes in the `FxChain` KDoc | owner, 2026-09-29–30 |
| SAY: the selector | WORD is the rack's first selector macro and must be exempt from `Treatments.fade` sliding and from `Breed.crossMacros` averaging; the spec states the rule (a flag on `MacroSpec`) | owner, 2026-09-29–30 (a flag on `MacroSpec` is the brief's example form) |
| SAY: phone reach | a SAY chip on the pad sheet; also reachable through `treat` and recipes. The row is not owner-approved: the design shown to the owner named no row, the brief's TIME row filled before landing, and the row is Decision 17 | owner, 2026-09-29–30 |
| SAY: the groundwork | VoxSpeak's per-block formant track and the Klatt-style resonator, anti-resonator and tract extracted into one internal `Formant.kt`; a frozen byte-exact VOX SPEAK guard lands first (none exists); SAY uses track and filter, the Group B spec's TALK bakes the track into data | owner, 2026-09-29–30 |
| SAY: compatibility | an older build ignores an unknown `say` section and plays plain; the spec says so honestly | owner, 2026-09-29–30 |
| Names | "Sound yes, names never" (`docs/SYNTH_ROADMAP.md:27`); "one word must not mean two things on the card" (`2026-09-13-fx-rack-expansion-design.md:164-168`) | house rules |
| Writing the specs | "Yes, write the specs", after the Group A design (one section) and the Group B design (three sections) were approved | owner, 2026-09-29–30 |
| Gates | at most ten clips and three questions each | owner's standing preference |
| Landing | a docs-only PR, zero check runs by design (`.github/workflows/tests.yml`): this spec, the Group B spec and the Phase-0 record; no code | house, as ARCO, BORE and MAGNET |
| Where the logic lives | `:app` cannot be compiled here, so logic is in `:synth` and `:shell` with tests; `ConventionTest` and the owner's phone check `:app` | the environment |

## Decisions for the owner

Each with the default this document takes (in bold); a default stands until the owner or a
gate overturns it. Nothing here reopens a row of the table above except as Decision 14
says: a stop answer at a gate is the one thing that puts an approved item back to the
owner. Decisions 15 and 16 record places where this document fills a gap the brief leaves
or reads a brief value one way when it could be read two. Decision 17 records a chip row
that filled between the brief and landing, and Decision 8 a sentence of the brief that
paraphrases the clip the owner kept, where the clip wins.

| # | Decision | Default | Alternatives | What it costs |
|---|---|---|---|---|
| 1 | BECOME's curve and the spike's extras | **Linear in amount per STFT frame, none of the spike's five extras** | The smoothstep over 10 to 150 ms, the 45 ms bell delay, the 2× level match, the 15 ms head crossfade and the 12 Hz DC blocker, any of them, each added behind BECOME only once the gate's question 3 says it earns its place | No byte of today's MORPH moves either way; each extra is code that runs only when `becomeMs` > 0 **Taken 2026-10-02: the default. At the A1 gate the owner heard clip 6 (BECOME 250 ms, MIX 1) as "as good or better" than clip 10, the spike's clip with all five extras, so none is added (see "The A1 gate: BECOME" and "Decisions already taken").** |
| 2 | BECOME's knob | **Linear, 0 to 2000 ms, 50 ms steps on the phone** | An exponential knob with an explicit OFF at the bottom detent and 10 ms at the first step above it | Finer where a hand-over lives, and a little more code in `Knob` (a `lo` of 0 cannot be exponential) **Taken 2026-10-02: the default. At the A1 gate the owner heard every one of clips 5 to 9 as a kick turning into a bell ("They all do"), so the full linear range stands (see "The A1 gate: BECOME" and "Decisions already taken").** |
| 3 | What a BECOME pad is called | **BECOME on the status line, the strip, the takes diff and the replay refusal; the keep toast still names MORPH** | MORPH everywhere, the ramp visible only on the knob | One fewer word on the card, and the ramp is invisible once the pad leaves the card |
| 4 | STRUNG with no key | **The open voicing: the low fifth (root − 5), the root, the fifth, the octave; no third** (`Body.modes`'s chromatic rule, `BodyTest.kt:76-77`) | A major third; or a refusal that asks for a key | A third makes a keyless kit sound major; a refusal leaves the chip dead until a key is set |
| 5 | STRUNG's AMT | **The strings added to the untouched hit at AMT × 2.5** (AMT 1 is the clip the owner heard) | BODY's law: a crossfade from the dry hit to the ring, peak-matched, which replaces the hit at AMT 1 | The crossfade is what BODY already is; the added-strings law is what makes STRUNG a different thing |
| 6 | STRUNG's ring | **Up to 1.5 s past the hit, no dial** (the brief's "tail cap ~1.5 s" read as a cap on the tail; the spike's buffer was the clap plus 1.1 s, cut at 1.352 s total) | 1.5 s in total from the strike (the spike's ceiling as it was written; a pad already longer than that gets no ring, since the pad is never cut); or a CLI-only `--decay` on a new `Keyed.Dials` field | The total cap shortens the ring on long pads; a dial is an additive change to the recipe and to `RecipeReplay` |
| 7 | SAY's word on the phone | **The chip says ONE; the other seven words by recipe, landing chain or breed** | A `--word` flag on `treat` (CLI only; replay is by name, so a paste would then need the last alternative); a second tap on a lit SAY chip stepping the word (an `:app` design with no precedent on this card); PASTE refusing a `said` recipe whose stored chain is not the treatment's own | This is the question the brief's phone reach leaves open **Taken 2026-10-01: a second tap on the lit SAY chip steps the word (see "Reach" and "Decisions already taken").** |
| 8 | How SAY's hold is reached (the brief's "holds the last vowel" paraphrases the approved clip, which holds the word's final frame) | **The word plays through, then the filter holds its final formant frame**, as the approved clip does (the table's second column in "The filter", step 3; a nasal hum for ONE and SEVEN) | The brief's words read literally: return to the last open vowel over 80 ms and hold it (the table's third column; the gate's clip 8); or the track stops at the last vowel, so the word's closing frames (ONE's n, FIVE's v, SIX's k and s, EIGHT's t) are never reached | The default adds nothing to the track and is the sound the owner kept; the return is one more listening value (80 ms, *this spec's*), a second argument on the shared track function and a filter that moves after the word, and ONE then ends on its vowel and not on the hum; the stop is the simpler build, but ONE then says "wuh" and the word never ends |
| 9 | Breed's selector rule | **On the third draw (the mean) a child takes A's word** (the stream stays one draw long; A's word comes up two times in three) | A fair coin: one more draw, only for a section that has a selector | A fair coin changes the draw count for a selector section |
| 10 | SAY and identity | **SAY may change a hit's class, as GHOST and RING do; the kick and snare class is printed at the gate** | SAY is held to "a kick through it is still a kick" and the chip's MIX comes down until it is | Holding the rule weakens the word on kicks |
| 11 | SPEED's direction | **SPEED 1 is natural speed, 0 the slowest, resting at 0.5 (×2.24)** | SPEED 0 natural and 1 slowest, the direction VOX SPEAK's DECAY runs | An owner who knows SPEAK's presets reads it the other way round |
| 12 | The version | **`FxChain.VERSION` stays 1 and an older build silently drops `say` on a re-render** (the owner's standing pre-launch call; the cost is traced in "Data flow and compatibility") | Bump it so older builds refuse | An older build then refuses every recipe the new build writes |
| 13 | Roadmap rows | **None for A** | An S row each when implementation starts | Check for competing claims first; the Group B spec claims S20 |
| 14 | The gates' stop answers | **A stop answer holds back the phone-visible step and asks the owner:** A1b, BECOME's card row, if gate question 2 hears a longer splice; A3b, STRUNG's chip, if question 1 hears BODY or a chord under the clap; A2b, `said` and the chip, if SAY does not say the word as well as the spike did | Every phase ships whatever the gate says, and the gate only moves constants | Taking an approved item out is the owner's call, asked after the answer. **A1: the stop answer was not given (2026-10-02); question 2 heard "a different sound", so A1b is built** |
| 15 | STRUNG on short hits | **The feed starts at `min(40 ms, half the hit)`** (the brief's "about 40 ms" is silent on a hit shorter than 80 ms) | A literal 40 ms: a hit under 40 ms rings nothing and STRUNG returns it unchanged, and a hit between 40 and 80 ms is fed only for its last part | The literal rule makes the chip a no-op on clicks and hats |
| 16 | STRUNG's level hold | **Scale only the wet, by one constant g, so the sum never passes the hit's peak; the dry is never touched** | Scale the sum (dry and wet together by one constant, as the keyed family's peak match does): smoother, but the dry hit is scaled whenever the bloom out-peaks it | The exact wet rule can lose strings to one unlucky sample, and returns the input when g reaches 0; the sum rule gives up the unconditional "attack untouched" |
| 17 | SAY's chip row | **The anatomy row (row two), making six on it: the one row with room whose grouping fits** (the brief's TIME row filled at `129bc48e` when SECTION landed, commit `8fd72ae3`; the character row is full) | No chip: SAY is reached through `treat` and recipes only, as VALVE shipped | Check the rows again at A2b, because the pad sheet changes often |

## Appendices

### Appendix A: the spikes' recipes, restated

The spikes are throwaway code kept with the evidence, outside the build; each recipe
below is what they did, so a build can reproduce the sound and a gate can ask what
differs.

**A.1 BECOME (bold-talk §3).** The kick: `Thump.render(KICK)` with TUNE .80 (55.00 Hz
measured), SWEEP .5, DECAY .6, HOLD .3, CLICK .35, DRIVE .3, 0.560 s. The bell:
`Tines.render(BELL)`, carrier 220 Hz (`Tines.tuneFor` at TUNE 0), RATIO .67 (snaps to
3.5, a modulator at 770 Hz = 14 × 55), BRIGHT .62, DECAY .85, BITE .3, CLANG
0.4915 so the partner sits at exactly 2.0× (CLANG = 0.5·ln(2/1.5)/ln(2.01/1.5): the
house's `Dsp.around` is exponential below its pivot); measured bell partials 221 Hz
(×4.01 of 55, 6 cents), 439 (×7.98, −5), 549 (×9.98, −3), 991 (×18.01, 1), 1319
(×23.98, −2), 1760 (×32.00, 0). The morph: mono magnitudes from
`Spectral.forEachFrame` (1024/256 Hann) of both, padded with silence, the bell
delayed 45 ms and scaled to 2.0× the kick's `Loudness.of`; per frame at the
frame's centre time `t = (f × 256 − 512)/44100`:
`|X_f| = (1 − a(t))·|K_f| + a(t)·g·|B_f|` with
`a(t) = smoothstep((t − 10 ms)/(150 ms − 10 ms))` clamped to 0..1; phases from
`Pghi.invert(mixed, frames, 44100)`; the kick's first 15 ms crossfaded back over the
PGHI head with a raised cosine; a 12 Hz one-pole DC blocker; `Dsp.fadeTail`. The
faithfulness gate: the same code at a constant amount 0.5 with no delay or crossfade
against `Mutate.render` MORPH 0.5, NCC 1.0000 (lengths 50 495 against 37 589). Result:
1.190 s, float peak 2.09 (0.128 levelled; the peak is in the overlap at about 82 ms),
no clipping, DC 0.000000, click index 0.16 (kick 0.07, bell 0.29). Cost: parts 25.0 ms,
the morph 24.6 ms, whole 49.6 ms.

**A.2 STRUNG (bold-ring clip 3).** The clap: `A06_Clap_01.wav`, 17 441 frames, mono,
44.1 kHz, 24-bit. The bank: `Pluck.sympathetic` copied with the feedback made a
parameter (0.98); the 4 kHz in-loop low-pass, the integer delay plus fractional
allpass tuning and `SYMPATHETIC_LOOP_HZ` verbatim; at 44.1 kHz. The chord: A minor
as E3 A3 C4 E4 A4 (MIDI 52, 57, 60, 64, 69 through `Keys.midiHz`), each detuned ±0.2 %
alternately (+ − + − +, about 3.5 cents). The feed: the clap high-passed (a 30 Hz
one-pole subtracted), coupling 0.15, `onset = 0` for `onsetSamples` = 1764 (40 ms).
The wet: each loop's output minus its own feed-through (`coupling × input`), summed
over the five loops, a 2 Hz DC blocker. The output: dry clap + 2.5 × wet; length clap +
1.1 s, `Strings.trimToDecay(floor 0.4, ceiling 1.5)`, `Dsp.fadeTail`: 1.352 s. The
measurements are in "The sounds, measured".

**A.3 SAY (bold-talk §2).** The gong: TIDE's TEMPLE GONG preset (TUNE .4, FOLD .15,
WARP .45, RATIO .5, DECAY .6, WANDER .25) with GLOW 1, WARP .55, FOLD .3, TUNE .29
(snaps to MIDI 55, G3, 196.00 Hz; measured 195.9 Hz), DECAY .7; RATIO .5 snaps to 3.5,
so the fold's sidebands land on multiples of f0/2 = 98 Hz. The word: `scriptFor(ONE)`
retimed with vowel stretch 2.2 (the 0.18 to 0.28 s vowel interval ×2.2: word clock
0.640 s), interpolated linearly as `VoxSpeak.synthesize` does at HUMAN 1, formants F4
3300 and F5 3750 fixed, base bandwidths 60, 90, 150, 250, 200 Hz times the widening
1.5, F2 to F5 times the target's `damp`, the nasal pole at 270 Hz and the zero sliding
270 to 450 Hz with the nasal amount; coefficients refreshed every 16 samples at
176.4 kHz. The rack-style pass (the ablation, and what SAY builds), condensed from the
spike's `postHoc` (`TalkGong.kt`):

```kotlin
// per sample: the tract on the finished gong
wet[i] = tract.process(dry[i].toDouble()).toFloat()            // coefficients every 16 samples
// 30 ms energy followers, the level-follow, and the mix
val k = 1f - exp(-1f / (0.03f * rate))
var ed = 1e-12f; var ew = 1e-12f
for (i in dry.indices) {
    ed += k * (dry[i] * dry[i] - ed); ew += k * (wet[i] * wet[i] - ew)
    val g = Math.pow(Math.sqrt((ed / ew).toDouble()), agc.toDouble()).toFloat().coerceIn(0.25f, 6f)
    val t = i.toFloat() / rate
    val x = ((t - dryLead) / wetIn).coerceIn(0f, 1f)           // dryLead 12 ms, wetIn 50 ms
    val w = x * x * (3f - 2f * x)                              // smoothstep
    mixed[i] = (1f - w) * dry[i] + w * wet[i] * g              // agc = 1.0
}
```

SAY differs from it in four ways, each stated above: ε added to both followers and the
arithmetic in double (the 0/0 hazard); the mix weight times MIX; the final joint-peak
match; and per-channel state with one clock. The hold is the spike's: the word's final
frame past the end (`TalkWord.Track.at`: the index stops at `size − 2` and `x` clamps to 1),
which Decision 8's alternative, a return to the word's last open vowel 80 ms after the last
target, would change.

### Appendix B: where the numbers live

- `bold/talk/notes.md` (`bold-talk`): §0 the static moves, §1 method, §2 clip 1, §3
  clip 2, §4 clip 3 (Group B's), §5 the files read back, §6 what a real build needs,
  §7 problems; its code in `bold/talk/code/` (`TalkGong.kt`, `TalkKick.kt`,
  `TalkWord.kt`, `TalkNearest.kt`), the clips in `chimera-audition/BOLD/`
  (`talk_gong_speaks.wav`, `talk_kick_becomes_bell.wav`).
- `bold/ring/notes.md` (`bold-ring`): the measurements table, clip 3, the
  nearest-in-app tables, render time, what a real build needs, caveats; its code in
  `bold/ring/code/` and the clip `chimera-audition/BOLD/ring_clap_sympathetic.wav`.
- The maps and the critic (`map mutate-morph`, `map rack-sections`, `map
  body-treatment`, `critic`) are in `phase0/design-and-maps/` of the evidence folder, with the
  design brief and the Phase-0 report; their facts are restated in this document and their
  stale cites corrected.
- Everything is under `~/Documents/snipsnap-chimera-evidence-2026-09-29/` (the spikes'
  patches under `spikes/`, apply on `5e3f5f3e`; Phase 0's under `spikes/phase0/`, apply on
  `75b550c1`) except the tree check, a read of the tree at `75b550c1` that is restated in
  this document and was not kept as a file.
