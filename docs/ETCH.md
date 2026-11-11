# ETCH: design brief and round-1 audition

Status: **round 1, Python prototype, not heard.** No Kotlin exists. The engine stays in
progress until every applicable owner gate has evidence (see "Gates" below).

Prototype: [`prototype/etch/`](../prototype/etch/) (`etch_proto.py` is the model,
`gen_audition.py` renders and measures, `build_page.py` writes the audition page).
Every number below is from that Python harness, not from Kotlin.

## What it is

A squelching step-line bass, through a drive that glues the steps, into a tempo delay and a
reverb. The owner supplied two reference clips on 2026-11-11: a minimal, chilled groovebox jam
whose track 11 is the bass, and a 303-style box with a hand riding cutoff, resonance,
envelope and decay. The owner named the processing: a bass overdrive or transparent boost
first, to gently saturate the filter peaks and glue the steps, then a stereo delay and reverb
pedal.

**Gesture line.** The characteristic gesture is a sixteen-step line → accents bite and build
→ slides carry the pitch and keep the filter open → the drive evens out the peaks →
dotted-eighth repeats fall into a room behind it.

Working name ETCH (acid etches metal). The words "acid" and "303" are on the preset trademark
blocklist (`PresetTestSupport.kt`), so neither can name the engine, a voice or a preset.

## References, distilled (gear-modeling.md §1)

| Reference | Mechanism (no brand words) | Source |
|---|---|---|
| Clip A (groovebox jam) | about 112 BPM, low notes F1 and C2, darker; centroid 150 Hz to 5 kHz about 0.9 to 1.5 kHz with drums and chords in the mix | measured, `analyze_ref.py` in the session scratchpad |
| Clip B (303-style box) | about 130 BPM, C2 with octave jumps to C3/C#3; centroid rises from about 1.0 to 2.6 kHz while the hand rides the cutoff | measured, same |
| Bass overdrive | a dry/overdrive balance control, gain, and a two-band EQ after the clip | [manual](https://www.adorama.com/col/productManuals/BSODB3.pdf) |
| Delay and reverb pedal | delay up to ~2 s with repeats to near-infinite; tone tilt in the delay line only; reverb follows delay in the signal path; tap divisions including dotted eighth; a reverse-delay mode | [manual](https://manuals.plus/earthquake/avalanche-run-r2-manual.pdf) |
| The original slide | an RC portamento, 100 kΩ and 220 nF: τ = 22 ms, settled in about 66 ms | [synth-diy thread](https://synth-diy.org/pipermail/synth-diy/2012-May/140040.html) |
| The original accent sweep | a capacitor charged by accents, more prominent as resonance rises, so consecutive accents climb | [x0x notes](https://ladyada.net/wiki/x0x/envelopemods) |

Not confirmed by a source and therefore proposed: that a slid note does not retrigger the
filter envelope.

## Why it earns a pad (sibling check)

- **VELVET SQUELCH** (`Velvet.kt`): one stab through a Chamberlin SVF, with SQUEEZE moving
  resonance and envelope together. It renders one note at a time, so nothing carries from one
  step to the next.
- **FATHOM** (`Fathom.kt`): drive before a resonant low-pass and GLIDE on every voice, but no
  accent, no accent memory and no step line.

What neither can do: state that crosses note boundaries (an accent capacitor that climbs over
a run, a slide that holds the gate and the envelope), a line as the unit of sound, and a tempo
delay whose tail wraps around a loop pad.

## Signal flow

```
16-step line ─► VOICE (4x) ───────────► BITE (4x) ──────► bandLimit ─► SPACE (1x) ─────► finish
                polyBLEP saw / square    pre-emphasis      decimate     delay ─► reverb    DC, level,
                4-pole ladder, tanh      soft clip,                     (tempo, tone in    fade
                accent cap, RC slide     de-emphasis,                   the loop)
                                         blend with clean
```

BITE runs before decimation because a clipper makes new harmonics (the VALVE and FORGE rule).
SPACE runs after it because delay and reverb are linear apart from a bounded feedback.

## Macro contract

| Control | Default | Changes | Measured (Python, LINE B) |
|---|---|---|---|
| TUNE | 0.5 | ±12 semitones around C2 | |
| CUTOFF | 0.3 | base cutoff 110 Hz to 1.2 kHz (exp), soft ceiling 3.5 kHz on the opened filter | energy above 2 kHz −18.7, −12.5, −10.9 dB at 0.1, 0.4, 0.75 |
| PEAK | 0.7 | ladder feedback (k = 4.15 × PEAK), accent-cap discharge time | −25.2, −17.7, −10.5 dB at 0.3, 0.65, 0.95 |
| SWEEP | 0.5 | filter-envelope depth (0.6 to 4.2 octaves) and decay (0.12 to 1.72 s) together | −27.7, −15.3, −10.8 dB at 0.15, 0.5, 0.9 |
| BITE | 0.45 | drive 2 to 24 dB into the clip, drive share 0.3 to 0.85; 0 is an exact bypass | accent lift 5.9, 4.6, 3.6, 2.6 dB and crest 13.6, 11.8, 10.0, 7.4 dB at 0, 0.25, 0.5, 0.8 |
| SPACE | 0.4 | delay level 0.25 to 0.6 and feedback 0.2 to 0.62 together; reverb T60 0.5 to 3.2 s and level; 0 is an exact bypass | |
| Velocity | | the accent: deeper, shorter filter envelope, louder VCA, charges the accent capacitor | level-matched attack centroid 781, 816, 1112, 1324 Hz at 0.35, 0.6, 0.8, 1.0 |

The first CUTOFF map (110 Hz to 3.2 kHz, ceiling 16 kHz) was not monotonic. Upper-band energy
peaked around CUTOFF 0.4 and fell about 2 dB above it, because the resonant peak climbed past
where the saw has energy. The narrower map above rises across the whole knob at SWEEP 0.2 and
0.6. The first BITE high-passed its drive branch at 180 Hz; it left accent lift unchanged
(5.9 to 5.6 dB) because the fundamental bypassed the clip. Both were fixed before publishing.

## The three directions

| | Operator | Idea | Gesture | Novelty | Risk | Cost |
|---|---|---|---|---|---|---|
| **Strong** | STACK | ETCH: line voice + BITE + SPACE inside one engine, rendering notes and loops | clear | new (state across notes) | low | an engine (26 surfaces) |
| Alternate A | DISTIL | a VELVET voice with accent and slide, plus a PEDALS recipe over VALVE → ECHO → ROOM | stab only | low | low | small |
| Alternate B | COUPLE | the delay's feedback runs back through BITE and the ladder, so repeats are re-squelched | strong but busy | high | feedback stability, render time | engine plus solver care |

Strong wins because the reference gesture is the line, not the note: accent memory and slide
need state across steps, and a dotted-eighth tail must wrap around a loop. ECHO cannot do that
(60 to 350 ms, not tempo-synced, 1.1 s tail cap). Alternate B is proposed as the DUB voice.

## Landing

An engine. Loops render one pass beyond two warm-up passes, so delay and reverb tails from
the end of the loop already sound at its start. Seam error, measured from the end of pass 2
into pass 3 of one continuous render, is at most 1.7e-12 (SPACE 0.9) against `Keys.MAX_SEAM_ERROR` (1e-3).
The tempo comes from the line (`render` gets no tempo; the line documents its BPM).

## Round-1 audition

54 clips, all finite; repeated renders byte-identical. The page has the chain one stage at a
time for both lines, a four-bar cutoff ride, BITE and SPACE sweeps, the three filter macros,
accents in a run against spread out, slide off and on, single notes, velocity and round robin.

Measured highlights (Python harness):
- Accents in a run climb: 1,199 → 1,321 → 1,345 → 1,351 Hz; the same accents spread out stay
  at 1,229 Hz.
- The ride, starting from CUTOFF 0.05 and SWEEP 0.35 and climbing to 0.8: centroid by bar 805,
  1,114, 1,302, then back to about 1,100 so the loop closes; energy above 2 kHz −28 → −12 dB.
- BITE at 0.5 takes accented steps from 5.9 to 3.6 dB above plain ones (crest 13.6 → 10.0 dB).
- Round robin is subtle: attack spread across four takes 4.7% at velocity 0.6, 1.0% at 1.0.

## Gates (docs/ENGINE_ACCEPTANCE_GATES.md)

| Gate | Status |
|---|---|
| Six distinct melodic voices with presets | open: two waves built; six proposed (SAW LINE, SQUARE LINE, RUBBER, WHISTLE, GRIT, DUB) for the owner to choose |
| Playable keygroups | not yet verified |
| Premade kit | open |
| Chord-tool compatibility | owner's call: the line voice is monophonic by design |
| Appropriate HOLD | open: proposed held note up to a seamless sustain loop, top step plays the line as LOOP |
| STRUM | not applicable: not a picked or thumbed string |
| Percussion roll/riser | not applicable: a bass engine |
| Drum genre kits | not applicable: not a drum engine |
| Velocity, rendering | pass in the Python harness only (timbral, level-matched) |
| Round robin, rendering | partial in the Python harness only: distinct takes, accented takes differ little |
| Velocity/RR saving, playback, export | not yet verified: no Kotlin, no kit, no MPC export |
| Sound character | not heard |

**Overall: incomplete.** Round 1 is a prototype.

## Render budget

Python with numba: a two-bar loop at three passes renders in 0.4 to 1.9 s; all 54 clips in
about 21 s. Proposed for Kotlin: the voice is one 4x per-sample loop with four one-pole stages,
the same order of work as VELVET at 4x, so a two-bar loop (3.7 to 4.3 s, three passes) should
stay inside the tens-of-ms one-shot rule for notes and around 100 ms for loops. Not measured.

## Sonic risks

- BITE's level match is global (whole-render RMS), so a very sparse line drives the clip
  differently from a busy one at the same knob. Offline, so acceptable, but heard only by ear.
- BITE darkens the top slightly: energy above 2 kHz falls from −11.7 to −13.7 dB between
  BITE 0 and 0.8 on LINE B, from its post-clip low-pass. If the owner hears it as dulling, open
  that low-pass.
- Round robin at full accent is close to repeated copies by the attack measure. If the owner
  hears them as one strike, a take needs a larger change (accent depth, envelope shape).
- The ride is baked in only for its audition clip. Baking motion into loop pads is the owner's
  call.
- Mono: the reference pedal is stereo; the house output is mono, so the width is lost.

## Ledger

- **Verified:** sibling engines and FX order read at `bf889e41`; ECHO's 60 to 350 ms and 1.1 s
  tail cap (`Echo.kt`); the blocklist entries for "acid" and "303".
- **Measured:** every number on this page and the audition page, Python harness.
- **Proposed:** every constant marked "shape" in `etch_proto.py`; the six voices; HOLD; the
  slide-does-not-retrigger behaviour.
- **Heard:** nothing yet.

## Why it is built this way

- **Velocity is the accent.** On this kind of bass the accent is the dynamic: it opens the
  filter further and shortens its decay. So a harder pad hit changes the tone, which is what
  the velocity gate asks for, instead of only changing the volume.
- **The drive comes before the delay**, as you asked, and it carries the whole signal with the
  mids pushed into it. The loudest moments, accents and resonant peaks, hit the clipper hardest,
  so it acts as the compressor that glues the steps. The first version left the bass out of the
  drive and measured no glue at all.
- **The delay repeats lose lows and highs every pass**, so they sit above the bass instead of
  muddying it, and the reverb follows the delay, as the reference pedal does.
- **Loops are rendered three times and the last pass kept**, so the echoes from the end of the
  loop are already ringing at its start. Without that, every loop pad would restart dry.
- **The risky decision is the cutoff range.** The knob stops at a fairly dark ceiling (3.5 kHz
  on the opened filter) because above it the measured brightness fell instead of rising. Your
  ears decide whether the top of the knob is open enough.
