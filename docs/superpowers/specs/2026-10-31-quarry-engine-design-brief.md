# QUARRY: design brief

Status: concept, prototyped in Python. Nothing rendered in Kotlin, nothing heard.
Erratum 2026-11-01: the harness's pitch probe (`.claude/skills/luthier/scripts/probes.py`)
normalized its NSDF wrongly (one energy term subtracted from the total instead of used as
is), which halved the denominator at short lags and clipped every confidence to 1.0. Found in
review of PR 796, fixed in the shared script, every number below regenerated. The root claims
held except one: the bark's C4 reading moved from an octave low to the target, so the "octave
low at C4" risk was the probe's, and the confidence claims were the probe's too. Both corrected below.
Repository truth: commit `2b3e1688`, read 2026-10-31. APIs grepped before being named:
`Grains` (`synth/.../Grains.kt`, renders a cloud from a source `Snip`, no `Patch`),
`SnapPatch` (`Patches.kt:401`, a patch that carries 256 table ints inline),
`Spectral` / `Pghi` / `Pitch` / `Stretch` / `Retune` / `Body` (`:audio`),
`PadRecipe` (a captured pad stores only its `FxChain`; the raw capture is the source of truth),
`KitPad.source` (a `Map<String, String>` of provenance), `Keys.*Pad` (nine keygroup-capable engines),
`Velocity.macroSpecsFor` / `touchedRender` (`Velocity.kt:196`, `:384`), `PatchTypes.ROSTERLESS`,
`DrumClass` (`Classifier.kt:8`), `PadFromAnything`, `Desample`, `Dsp.seedFor` (`Dsp.kt:147`),
`Dsp.MELODIC_LOUDNESS_TARGET` (`Dsp.kt:573`).

Prototype: [`2026-10-31-quarry-engine-prototype.py`](2026-10-31-quarry-engine-prototype.py),
results in [`2026-10-31-quarry-engine-prototype-results.json`](2026-10-31-quarry-engine-prototype-results.json).
Every number below marked *measured* comes from that harness (snipkit's RMS-proxy level,
probes' NSDF pitch), not from Kotlin.

The characteristic gesture is **the source's own first milliseconds → its one cycle sung at the
note → its grain field (or its sliding excerpt) breathing in behind → its colour pressed onto
the whole → held, or let go**.

## Where this came from

Josh's KIT FROM ANYTHING idea (one captured sound becomes four bass notes, four tones and
eight drum slots, in a chosen key and genre) needs a fallback for the slots a source cannot
yield well on its own: a bass from a sound with no low end, a tone from a click. Josh's
decision: purist by default, the fallback a *blend*, never a replacement, and the fallback
itself should still be made of the source: "a synth engine that uses a sound and pushes it
through oscillators, grain field, etc." QUARRY is that engine. The kit assembler would hand it
the same capture and ask for a note; what comes back is still the dog bark, in the key.

## What it is

A pitched engine whose only material is one captured `Snip`. The source is quarried three
ways at once. **CYCLE**: one period of a pitched source (eight consecutive periods averaged,
2048 points) or, for an unpitched one, a 46 ms excerpt at the arrival forced to wrap; either
way a mip-mapped wavetable sung at the note, the way SNAP sings a photo's line. **FIELD**: for a
pitched source a pitch-synchronous grain cloud (one grain per period of the note, the content
transposed with it, the scrub walking from the arrival to the end); for an unpitched source
the forced cycle *sliding* through the source, twelve excerpt tables crossfaded as the note
proceeds. **STAIN**: the source's long-term spectral envelope (Spectral's own 1024/256
geometry) pressed onto the mix as a fixed per-bin gain, so whatever the transposition did to
the colour, the source's own colour comes back on top. ATTACK splices the real head in front.
HOLD freezes the scrub and loops whole periods.

Which path a source takes is the classifier's verdict, not a pitch confidence: a `TONAL` snip
has a period to find, every drum class is forced. The first prototype run used raw confidence
and read a formant burst as a note at 1075 Hz with confidence 0.94; the classifier already
knows better, and the engine should ask it.

## Why it earns a pad

Nearest siblings and the one thing each cannot do:

- **GRAINS** (`:synth`, capture-side): a cloud from a source, but no `Patch`, no root note, no
  keygroup path; PITCH is a semitone offset, not a note. QUARRY's cloud is pitch-synchronous
  and carries the root (*measured*, pluck: within 4 cents at C2, C3, C4).
- **SNAP**: a wavetable from a photo's line, 256 points, the data inline in the patch. It cannot
  read audio, and nothing moves inside its cycle. QUARRY's table comes from the sound and the
  unpitched FIELD slides it.
- **ORPHICA**, **CONTOUR**, **SPOOL**: grains of a synth's own history, a scan over generated
  tables, strips of fixed recorded sources. None takes the user's capture as the whole
  instrument.
- **PAD FROM ANYTHING** (`:kit`): one hit held forever in every note by stretching. It makes a
  pad, not a playable one-shot, and it has no colour or attack story.

The inventory lists granular and spectral engines as thin and the sample side as the place
where capture and synthesis still fail to meet. This engine is that meeting, and it lands on
the keygroup path on day one, which only nine engines do.

## Signal flow

```
source Snip (44.1 kHz), class from Classifier
        │
        ▼
[Analyse: arrival, period or forced excerpt, colour envelope]   [cache: source hash, model v]
        │
        ├──▶ [CYCLE: mip-mapped table at f0 (drop harmonics above 20 kHz at output)]──┐
        │           [cache: source hash, midi]                                         │
        │                                                                              ▼
        ├──▶ [FIELD: pitched = PSOLA cloud, transposed, scrub arrival→end;      [BLEND, equal power]
        │            unpitched = 12 excerpt tables, slid across the render]           │
        │                                                                              ▼
        └──▶ [STAIN: colour envelope ^ (0..1.5) as per-bin gain, Spectral STFT]◀───────┘
                                                                                       │
                                            [ATTACK: real head 0..60 ms, 10 ms crossfade]
                                                                                       │
                     [envelope: 5 ms in, T60 one-shot | 0.3 s in, flat under HOLD]      │
                                                                                       ▼
                     [Output: Tide.bandLimit → Dsp.decimate → DC → levelTo → fadeTail]
```

All three readers are pure functions of (source hash, midi, macros); nothing feeds back.

## Macro contract

| Control | Default | What it changes in the model | Required interaction | Test |
|---|---:|---|---|---|
| TUNE | .50 | the note, C1 to C5 over the house two octaves either side of C3 | | root within 5 cents at every third note, both source classes |
| BLEND | .50 | the equal-power mix of CYCLE and FIELD | with GRAIN: what the FIELD is made of | root holds at BLEND 0, .5 and 1 (*measured*, see below) |
| GRAIN | .50 | pitched: grain length, 2 to 8 periods; unpitched: how far the slide travels into the source | with BLEND: inaudible at BLEND 0 by design | activity ≥ 3 dB on a source with an arc; disclosed low on a stationary one |
| STAIN | .50 | the power the colour envelope is raised to, 0 (bypass) to 1.5 | with BLEND at 1 on a pitched source: restores the formant the transpose moved | activity ≥ 10 dB |
| ATTACK | .30 | how much of the real head is spliced in front, 0 to 60 ms | with the drum guard: a kick head must not make the render a KICK | classifier keeps every render TONAL or LOOP |
| HOLD | 0 | top step: scrub frozen at the arrival, whole-period loop | | `Keys.seamError` < 1e-3 against a real preceding cycle |

Five macros plus HOLD. No knob shares the engine's name. All "shape" ranges until a listen.

## The three directions

**Strong (SPLICE + STACK): the three readers above.** SNAP's table reader spliced onto a
capture, GRAINS' cloud made pitch-synchronous, Spectral's gain door used as a colour press, in
series. Gesture clarity: high, and the root is guaranteed by construction on both paths
(*measured*). Novelty: the capture becomes a keygroup instrument, which nothing in the roster
does. Risk: low; every part exists. Cost: two rounds to a kit. It wins because the kit idea
needs a fallback that never loses the note, and this is the only direction where the note is a
property of the construction rather than of the source.

**Alternate A (INVERT): the source as the exciter.** The capture strikes the key's chord-tone
resonators (BODY grown into an engine) and seeds a Karplus-Strong loop. Gesture: strike → ring
at the key. Novelty: low, BODY and PLUCK cover most of it. Risk: lowest. Cost: one round. Worth
keeping as a *voice* of QUARRY later (RING), not as the engine.

**Alternate B (CROSS, spectral): the source's frames spoken at the note.** The capture's
frame-by-frame magnitudes imposed on a synthetic carrier with PGHI phases, a vocoder as an
instrument, which the inventory lists as absent. Gesture: the source's time structure, sung.
Novelty: highest, the only "third sound" of the three. Risk: PGHI cost per knob turn needs a
cache story, and an unpitched source with no spectral motion gives a static vowel. Cost: three
rounds. The riskier reading; its best idea (the time structure) is what ATTACK keeps cheaply.

## What the prototype measured

Four stand-in sources synthesized in the harness: a Karplus-Strong pluck at A3 (pitched), a
swept-sine kick, a two-formant noise "bark", a noise-plus-180 Hz snare (all forced).
Root at C2, C3, C4 on the finished 44.1 kHz render, NSDF pitch, window 0.3 s:

| Source | CYCLE (BLEND 0) | FIELD (BLEND 1) | BLEND .5 |
|---|---|---|---|
| PLUCK | within 0.1 cent, confidence .997 to .999 | −3.4 / +0.4 / +3.0 cents, confidence .59 to .73 | within 1 cent, .85 to .89 |
| KICK | within 0.1 cent, .997 to .999 | within 1.7 cents, .99 | within 3.4 cents, .99 |
| BARK | within 0.3 cent, .81 (C4) to .99 | within 0.2 cent, .81 to .99 | within 0.3 cent, .81 to .99 |
| SNARE | within 0.3 cent, .98 to .997 | within 0.2 cent, .97 to .996 | within 0.2 cent, .97 to .996 |

Every row clears the Kotlin tests' confidence floor of 0.3; the pluck's FIELD is the least
sure (0.59 at C4), which is the pitch-synchronous cloud's transposition smearing the period.
Energy never grows after the attack in any row. Aliasing at C5
on the two brightest sources: 0.0 share above the output Nyquist before band-limiting, 0
partials above Nyquist after it (the mip-map works). HOLD at C3, scrub frozen, grain schedule
periodic in the loop, 1 s warm-up: seam 5e-9 (pluck) and 2e-7 (bark), both under 1e-3.
Macro activity (log-spectral dB between extremes, C3): BLEND 6.0 / 2.4, GRAIN 5.7 / 1.6,
STAIN 11.3 / 15.7, ATTACK 19.0 / 24.1 (pluck / bark). Drum guard, kick source, ATTACK 1, C2:
the head holds 61 % of the energy, the note reads at C2 with confidence 0.97, −60 dB at 1.44 s.

Two designs were abandoned on numbers before the one above: a free-running cloud (GRAINS'
own schedule, re-pitched) lost the root on every forced source and at C2 on the pluck; a
strobed cloud at the source's native rate read as the source's own formant (around 1050 Hz
for the bark) at C3 and C4 whatever the strobe rate. Both runs are in the prototype's history
and the free-running one is still reported as `FIELD_FREE` for comparison.

## Risks

1. **Weak fundamental on forced sources at high notes.** The first run reported the bark's
   CYCLE at C4 an octave low; that was the probe's normalization bug (erratum above) and with
   the fix it reads within 0.3 cent at confidence 0.81, the lowest confidence in the table. The
   mechanism behind the lower confidence is real: a 46 ms excerpt sped up to a 3.8 ms period
   carries its energy around the 40th harmonic. Two mitigations stay on the list for Kotlin if
   a real capture shows it: tie the excerpt length to the period (a native-rate single-cycle
   rip, formants kept, with the formant-lag risk the strobe run showed), or raise STAIN's floor
   for forced sources at high TUNE. The probe is the C4 root and confidence on a drum source.
2. **GRAIN near-dead on a stationary source** (*measured* 1.6 dB on the bark against 5.7 dB on
   the pluck). A slide through stationary noise changes nothing. Disclosed; the test must use a
   source with an arc, and the audition page should show both.
3. **Thin bass from a source with no low end.** The forced cycle at C2 slows the excerpt
   twelve times, which manufactures a fundamental, but STAIN at 1 presses the source's bright
   envelope back on. Probe: the energy share below 200 Hz at C2 with STAIN 1.
4. **The head makes a drum of a note.** 61 % of the energy in 60 ms is a kick's shape. The
   classifier guard is a test over real renders; if it files KICK, cap the head at 30 ms or
   scale it to the tail's level. The engine's `drumClassFor` must agree with the render length
   to the frame: a one-shot under 1.5 s is TONAL, the HOLD top is LOOP (the prototype rendered
   2 s; the Kotlin one-shot should land under `Classifier.LOOP_MIN_SECONDS`).
5. **Peak versus loudness on forced cycles** (*measured* peaks 0.75 and 0.78 after levelling on
   the kick's cycle at C3 and C4): a clicky excerpt has a high crest factor. `Dsp.levelTo` handles
   it; the probe is the loudness floor.
6. **Interpolation.** The prototype reads its table linearly; the catalog asks for cubic above
   2 kHz. The Kotlin should follow SNAP's reader.
7. **Render time**, *proposed*: analysis cached per source hash, tables per (hash, midi); the
   oscillator is one read per sample, the field one grain per period, STAIN one STFT pass over
   the render. Tens of milliseconds per knob turn should hold; the harness took about 0.2 s per
   render in numpy with nothing cached, which says nothing about Kotlin.

No maker names anywhere. No external code reused; the pitch-synchronous grain is PSOLA
(Moulines and Charpentier, 1990), a published technique, cited not copied.

## The one integration decision: how a recipe reaches its source

A `Patch` today is engine, voice, macros, and renders from nothing. SNAP carries its 256 table
ints inline. QUARRY cannot inline its source: the FIELD needs the audio itself. The captured-pad
rule in `PadRecipe` already says the raw capture is the source of truth, so the proposal is:
the patch JSON carries the source's kit-relative file name and a content hash (SPOOL keeps
`sourceHashes`; `KitPad.source` is the provenance map), the `:kit` layer resolves the `Snip`
when it loads the kit and hands it to the patch, and a missing or changed source refuses in
words, never renders silence. `Patches.fromJsonValue` keeps its signature; what changes is that
a QUARRY patch is decoded in two steps (shape, then source), and `PadRecipe.render` must know
which. This touches `Patches.kt`, `PadRecipe.kt`, and the kit store; the implementing session
should settle it before any DSP, because every test fixture depends on it.

## Cheapest proof, and the rounds

- **R0**, the decision above, plus a fixture: two real captures checked into the test
  resources (one TONAL, one drum), 44.1 kHz mono, under a second each.
- **R1**, CYCLE and STAIN only (BLEND fixed at 0), `QuarryPatch`, determinism, the root probe at
  C2/C3/C4 on both fixtures with `Pitch.detect`'s confidence recorded (risk 1), the classifier
  guard, the keygroup path via a `Keys.quarryPad`. The number that says R1 works: root within
  5 cents at confidence 0.3 or better on the drum fixture at C4.
- **R2**, FIELD on both paths and HOLD; seam against a real preceding cycle.
- **R3**, the KIT FROM ANYTHING hook: `KitAssembler` asks QUARRY for the bass and tone slots
  from the kit's own source at the key's degrees, blended under the purist pad at a stated
  share. Presets only after the listen.

## Verified / measured / proposed / heard

| Claim | Status | Evidence |
|---|---|---|
| `Grains.render(source, macros, seconds, seed)` exists and takes a source `Snip` | verified | `Grains.kt`, `SynthKits.kt:258` |
| `SnapPatch` carries table data inline and validates it | verified | `Patches.kt:401` |
| A captured pad's recipe stores only its `FxChain` | verified | `PadRecipe.kt` KDoc |
| Nine engines reach the keygroup path | verified | `Keys.kt` `*Pad` functions, lines 216 to 578 |
| The forced cycle holds the root at C2, C3 and C4 on every source | measured | results JSON, `root`; confidence .81 to .999 |
| The pitch-synchronous transposed cloud holds the root on a pitched source | measured | within 3.4 cents, confidence .59 to .73 |
| The sliding excerpt holds the root on forced sources | measured | within 1.7 cents, all notes, confidence .81 to .997 |
| The harness pitch probe is normalized correctly | verified | `probes.py`, fixed 2026-11-01. With the fix every reading in the table is within 3.4 cents of its target; the one reading that changed materially is the bark's CYCLE and BLEND at C4, from an octave low (130.8 Hz) to the target (261.6 Hz); every confidence fell from a clipped 1.0 to its real value |
| A free-running cloud loses the root on forced sources | measured | `FIELD_FREE` rows |
| HOLD closes | measured | seam 5e-9 and 2e-7 |
| Tens of ms per knob turn in Kotlin | proposed | cache story above, no Kotlin yet |
| A bark at C2 reads as a bass and not as an EQ'd buzz | heard? | not heard; audition section EDGES |
| The head splice keeps the capture's identity | heard? | not heard; audition section ATTACK sweep |

## Why it is built this way

- **The note is built in, not found.** A wavetable read at a frequency is periodic at that
  frequency whatever is in the table, so the engine can promise a root for a dog bark, which
  no analysis of the bark could. That promise is what makes it a safe fallback for the kit.
- **The field breathes, the cycle holds.** One table alone is a buzz that never moves; grains
  alone lost the note twice in the harness. Blending the two gives motion with a floor under
  it, and the measured rule is that the motion must be periodic at the note too (one grain per
  period, or a slide between tables) or the root goes.
- **Colour is pressed back on at the end** because transposing a sound moves its formants (a
  bark an octave down is a bigger dog). STAIN puts the original envelope back over whatever
  pitch did, which is what keeps it sounding like *that* sound in every key.
- **The real head goes in front** because the first few milliseconds are most of what an ear
  uses to recognise a source. It is also the one risky decision: a kick's head on a note can
  make the classifier file the pad as a kick, so it is capped and tested.
- **Everything is cached by the source's hash** so a knob turn re-reads tables and does not
  re-analyse the capture. Without that cache the design breaks the instant-loop rule.
