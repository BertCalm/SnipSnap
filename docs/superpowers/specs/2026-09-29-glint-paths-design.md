# GLINT paths — a voice is how the formant moves

**Status:** design approved section by section, 2026-09-28/29. Supersedes the
voice list in `2026-09-25-glint-phase-distortion-design.md` and shelves
`2026-09-28-glint-voice-identity-design.md` (D4) entirely.

## 1. Why this redefinition

Three passes (D1, D2, and the Phase 2 TRACE design) worked the *window* —
the shape that fades each cycle to zero — as the thing that tells voices
apart. D2 measured what the ear can resolve on a same-run, energy-weighted
third-octave distance (0–2 scale):

| change | distance |
|---|---|
| swap one window for another | 0.10 – 0.22 |
| one semitone of pitch | 0.61 |
| move the formant one harmonic | 1.51 – 1.56 |
| sweep down vs sweep up, both landing on the same PEAK (whole note) | 1.79 |

The sweep row was first read as 1.70 from a probe whose up-sweep rested on a
different formant than its down-sweep. In the engine both gestures settle on
PEAK, so the two notes differ only in the approach, about 0.3 s of a 0.9 s note:
1.79 over the whole note, 1.82 in its first quarter, 0.00 in its last (measured
in Task 4, 2026-09-30; equal-quarter path form averages the approach with three
near-identical quarters and reads 0.51, which is why this pair is gated whole-note
plus first and last quarter).

The window slot is nearly inert. What GLINT can do that is audible is *where*
the formant sits (k) and *how it moves*. Josh named the priorities: **captured
material** and **pads/keys**.

**What was tried for captured material, and why it is out.** TRACE — k
follows a capture's brightness over time — was gated on 8 session captures
(2026-09-28, throwaway probe). Pitched captures (kick, tom, tonal hit, EP)
all trace as "bright then dark" and land 0.05–0.39 from a plain downward
sweep: indistinguishable from what GLINT already does. Only noisy captures
with internal motion (snare, clap, hats) traced distinctly. Josh chose to
replace recording-in-the-sound with something interesting: VOWEL.

**VOWEL's gate.** A throwaway renderer placed GLINT's two bursts at adult
vowel formants and sent the WAV to Josh's phone (2026-09-29). Verdict: "It
works."

## 2. The engine after this change

One window: the saw ramp `w(φ) = 1 − φ` (today's REED). Every voice runs the
same carrier, burst and BODY machinery; a voice is **the path k takes**.

| voice | path | replaces |
|---|---|---|
| `SWEEP` | glides into PEAK from above or below, on the `BLOOM_T60` clock | REED, BOTTLE, KAZOO, CICADA |
| `STEP` | the same journey, one whole harmonic per `RATCHET_STEP_SECONDS` | RATCHET |
| `BRASS` | brightness follows loudness: k rides the amp envelope | PLATE |
| `VOWEL` | two bursts at mouth formants in Hz; the path runs through vowel space | new |

`BRASS` is PLATE's mechanism under a new name. The approved section called it
FOLLOW, but `FOLLOW` is already a GLINT macro (key tracking), so the voice
takes the name of the instrument whose defining acoustic trait it copies —
brass gets brighter as it gets louder.

**Deleted:** `BOTTLE` and `KAZOO` windows, `KAZOO_FLAT`, CICADA and
`CICADA_SUBCYCLES`, `kCeilingFor`'s CICADA branch, the TRACE plan (and
`GlintPatch`'s doc paragraph promising a `window` field), and D4's carrier
warp. **Not in this pass:** DEPTH (a sine endpoint for softer pads) — named
as the next step, nothing built.

### 2.1 Macros

| macro | SWEEP / STEP / BRASS | VOWEL |
|---|---|---|
| `TUNE` | unchanged | unchanged |
| `PEAK` | resting formant (unchanged mapping, snap unchanged) | resting vowel along the line OO–OH–AH–EH–EE |
| `FOLLOW` | unchanged key tracking | **absent** — formants are fixed Hz by definition |
| `BODY` | second burst level, unchanged | vocal-tract size: scales both formants together |
| `BLOOM` | **bipolar**, neutral 0.5 — see 2.2 | bipolar — start vowel offset along the line |
| `DECAY` | unchanged | unchanged |

`macrosFor(VOWEL)` omits `FOLLOW`. `MacroSpec("BLOOM", default, neutral = 0.5f)`.

**Defaults:** SWEEP, STEP and BRASS keep today's defaults except BLOOM, which
moves to `0.675` (today's 0.35 through the remap in section 4). VOWEL:
PEAK `0.5` (AH), BODY `0.5`, BLOOM `0.6` (a short glide down into AH), DECAY
as every voice.

### 2.2 BLOOM becomes signed

`s = 2·BLOOM − 1` in −1..1. `a = |s| · BLOOM_MAX`.

- `s > 0`: the path **starts above** PEAK and falls into it (today's gesture).
- `s < 0`: the path **starts below** PEAK and rises into it.
- `s = 0`: static formant.

Start ratio is symmetric in log: `kStart = kBase · (1 + a)^sign(s)`, clamped
to `K_MIN..K_MAX`. At low PEAK the rising side hits `K_MIN` early and has
less travel than the falling side — accepted, and documented on the constant
as the old BLOOM-at-high-PEAK clamp was.

- **SWEEP:** `k(t) = kBase · (kStart/kBase)^envAt(t, BLOOM_T60)` — the same
  exponential clock, now in log-ratio so up and down are mirror images.
- **STEP:** the ladder runs from `kStart` to `kBase` in whole harmonics —
  descending for `s > 0`, ascending for `s < 0` — landing on PEAK. The
  bottom-rung/`snapRatio` rules in `ratchetLadder`'s doc carry over.
- **BRASS:** `k(t) = kBase · (kStart/kBase)^e(t)` (kStart is the clamped start ratio, as in SWEEP), where
  `e(t) = (amp(t) − rest) / (1 − rest)` and `rest` is the level the note
  settles at — 0 for a one-shot, which decays to silence, so `e = amp` and
  the note lands on PEAK as it dies. `s > 0` louder is brighter (brass);
  `s < 0` louder is darker (a muted, choked strike).

k changes only at phase wraps for every voice, including SWEEP, where it is
per-sample today. One rule for all paths; the window is zero there, so the
change is free by construction.

### 2.3 VOWEL

**The vowel line** (adult formants in Hz, the audition's table):

| position | vowel | F1 | F2 |
|---|---|---|---|
| 0 | OO | 300 | 870 |
| 1 | OH | 570 | 840 |
| 2 | AH | 730 | 1090 |
| 3 | EH | 530 | 1840 |
| 4 | EE | 270 | 2290 |

`PEAK 0..1` maps linearly to position `0..4`; between neighbours, F1 and F2
interpolate in log2(Hz). Ordered dark to bright so PEAK keeps meaning "how
bright" — velocity scales PEAK (`Velocity.BRIGHTNESS_MACROS`), so a soft key
sings a darker vowel.

**Measured 2026-09-29 (Task 3):** the line is ordered on *F2 presence*, the
share of power above 1.5 kHz, which is non-decreasing at every one of nine PEAK
points at TUNE 0 and TUNE 0.4 (116x and 58x end to end). Spectral centroid is
not: it rises OO to AH and falls at EH and EE, because EE has the lowest F1 of
the five while its F2 is what makes it bright. OO and OH are not inverted, so no
swap applies. Centroid stays the repo's brightness proxy for every other voice;
VOWEL's PEAK and its velocity are gated on F2 presence (`GlintVowelTest`): a soft
key sings a vowel with less F2, at every PEAK.

**BLOOM:** start position = `pos + s · 4`, clamped to `0..4`; the path moves
from start to `pos` on the SWEEP clock: `p(t) = pos + (start − pos)·envAt(t, BLOOM_T60)`.
Full BLOOM travels the whole line.

**BODY — who is singing:** both formants × `2^(0.5·(BODY − 0.5))`
(≈0.84× big chest … 1.19× small bright). VOWEL's BODY default is 0.5.

**Rendering:** `k1 = F1/f0`, `k2 = F2/f0`, read at wraps, **no harmonic
snap** (formants are Hz, not harmonics). Burst levels 1.0 and 0.5 — the
audition's ratio — both on the amp envelope (one mouth, one source; not
BODY's separate envelope).

**The ceiling:** VOWEL uses its own floor `VOWEL_K_MIN = 1f`: when a formant
falls below the note it pins to the fundamental rather than vanishing. The
audition's Part D (AH→OO at 220 and 330 Hz) is this case. Root A2 (110 Hz),
two octaves of TUNE as every voice; above ~E4 the vowel thins toward "just
bright" — physics, as with a real voice.

### 2.4 Roots

`rootHz`: SWEEP, BRASS, VOWEL at A2 (110 Hz); STEP at A3 (220 Hz, RATCHET's
register). The window no longer differs per voice, so this is the only
per-voice constant left besides the path.

## 3. BREATHE — the held note

A new held path for every voice, feeding MAKE INSTRUMENT the way RESIN and
SIREN do.

**Shape of the render:**

1. **Onset:** the voice's path plays and lands on PEAK. The amp envelope's
   decay is ignored in the held render — a held key sustains; the 2 ms attack
   stays. Onset length is `BLOOM_T60` rounded up to whole cycles. BRASS's
   held onset is a fortepiano accent: amp peaks at 1 and settles to
   `BRASS_REST = 0.5` on the `BLOOM_T60` clock, so with `rest = 0.5` its
   exponent `e` runs from 1 to 0 and k lands on PEAK exactly as the level
   settles. BODY's own envelope sustains too.
2. **Loop:** from the landing, the formant **breathes** — a sine in
   log-ratio around PEAK, one full breath per loop:
   `k(t) = kBase · 2^(d · sin(2π·t/Lsec))`, `d = BREATHE_SHARE · log2(1 + a)`,
   `BREATHE_SHARE = 0.25` (a quarter of the onset's travel). BLOOM at 0.5
   gives a still pad. VOWEL breathes its position: `pos ± BREATHE_SHARE · |s| · 4 · sin(…)`.
   STEP's breath is quantised to whole harmonics at wraps. BRASS breathes its
   *amplitude* around `BRASS_REST` (±`BREATHE_SHARE · BRASS_REST`) and its k
   follows through `e(t)` — loudness and brightness together, like a player.
3. **Exact closure:** choose N whole cycles so `L = round(N·rate/f0) ≈
   BREATHE_SECONDS (3.0 s)`, then run the carrier at `f0' = N·rate/L`
   (pitch error well under a hundredth of a cent). N cycles and one breath
   both end exactly at L, so phase, k and amplitude all return to their
   start values: the seam is zero by construction, no seam search.

**Oversampling caveat.** GLINT renders at `Dsp.OVERSAMPLE`× and decimates
through `Resampler`, a filter with memory. A single decimated loop would
carry the filter's start-up transient into its first samples. The held
render therefore synthesises **three** copies of the loop at the oversampled
rate (loop length exactly `OVERSAMPLE·L`), decimates, and keeps the middle
copy. `Keys.requireSeam` (`MAX_SEAM_ERROR = 1e-3`) is the acceptance gate.

**Plumbing** (mirrors SIREN, `Keys.kt:295`):

- `Glint.renderHeld(voice, macros, cancelled): Pair<FloatArray, FloatArray>`
  — onset and loop, leveled with **one** gain.
- `Keys.glintPad(voice, macros, midi, cancelled): KeyNote` — onset + loop +
  loop, `loopStartFrame` at the second loop's start; `glintPadMidis()` every
  3 semitones across TUNE.
- `shell/…/GlintPadMaker.kt` — `spec / zoneMidis / renderZone / preview /
  export`, RELEASE knob as SIREN's.
- `SynthScreen.heldSpec()` gains `Engine.GLINT -> HeldSpec.Glint(…)`.

## 4. Saved patches

GLINT is on the main branch, so kits can hold GLINT patches under the old
voice names. `Patches.decode` throws on an unknown voice, and `VERSION` is
shared by every engine, so it cannot mark GLINT alone.

**The old voice name is the migration marker.** Every legacy name decodes;
no new name collides with an old one:

| saved voice | loads as | BLOOM remap |
|---|---|---|
| REED, BOTTLE, KAZOO, CICADA | SWEEP | `0.5 + b/2` (old BLOOM only fell) |
| PLATE | BRASS | `0.5 + b/2` |
| RATCHET | STEP | `0.5 − b/2` (old ladder only climbed) |

Legacy patches load and play; they are not bit-identical (BOTTLE, KAZOO and
CICADA lose their window/register, RATCHET's ladder now lands on PEAK instead
of leaving it). There is no factory GLINT preset roster, so only Josh's own
kits are affected.

## 5. Testing

- **Legacy decode:** each of the six old names round-trips through
  `fromJsonText` to the table above, with BLOOM remapped.
- **Paths separate:** a test helper holds the probe's measure (4-segment,
  energy-weighted third-octave L1, 4096-sample frames tiled, the FFT applying its
  own Hann window — *not* a single `Fft.magnitudeSpectrum` call over the buffer,
  which reads only its first 4096 samples).
  Same-run anchor = the saw window vs a triangle window at k = 8, both
  rendered by the helper itself (BOTTLE's window no longer exists in
  `Glint.kt`). Bar: ≥ 0.55 and
  ≥ 2× anchor for SWEEP-up vs SWEEP-down (whole note and first quarter; the last
  quarter must sit within the anchor, because both land on PEAK), SWEEP vs STEP
  (path form), and each adjacent pair of whole vowels at the root note (TUNE 0)
  and at the default TUNE 0.5.
- **Clicks:** k changes only at wraps, all four voices (the existing RATCHET
  guard, generalised).
- **Held:** `requireSeam` passes for every voice at BLOOM 0.2, 0.5, 0.8 and
  the lowest and highest zone; pitch of `f0'` within 0.01 cent of MIDI;
  loop length within one cycle of `BREATHE_SECONDS`.
- **Velocity:** `velocity always changes the render, at every PEAK` covers
  VOWEL; the PEAK-monotone sweep includes VOWEL (see 2.3).
- **Audition gate before merge:** one WAV per voice (SWEEP up and down,
  STEP up and down, BRASS, VOWEL gliding), plus a held pad per voice at 10 s
  so the loop repeats three times — sent to Josh's phone.

## 6. Out of scope

DEPTH; factory presets (authored by ear after the audition, per the synth
depth reversal); registering GLINT with DE-SAMPLE; stereo.

## 7. Housekeeping in the same branch

- D4 spec gets a one-line "Shelved 2026-09-29 — see
  2026-09-29-glint-paths-design.md" header.
- `docs/SYNTH_ROADMAP.md` S10 row gains a clause for this redefinition.
- Throwaway probes (`GlintTracePathProbe`, `GlintVowelAudition`) are not
  committed.
