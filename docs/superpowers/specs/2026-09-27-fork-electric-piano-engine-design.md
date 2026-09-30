# FORK — the modal electric piano engine

**Status:** R1 built 2026-09-27 (engine, both voices, the striker, presets,
keys instrument, the phone picker, the testkit kit — see "As built" at the
end). Question 3 (residual and stereo) is still open, R2 waits on it and
on U4's stereo audit.
**Date:** 2026-09-27
**Plan:** to be written per round (`docs/superpowers/plans/2026-09-27-fork-round-N.md`)
**Related:** [`2026-09-27-silk-string-engine-design.md`](2026-09-27-silk-string-engine-design.md)
made the same home decision the same day and for the same reasons; its
`Modes.stiffString` and t60-not-Q rule are reused here.
**Roadmap:** the `SYNTH_ROADMAP.md` row is added when implementation
starts, not now — the rule FATHOM, RESIN, GLINT and SILK followed.

## Why FORK

The ask (2026-09-27) arrived as an engineering specification for a
*real-time* modal electric piano: a C++ voice under Oboe, a lock-free
telemetry bridge from Kotlin, a 16-voice pool with a stealing policy, a
modal filter bank at stiff-bar ratios, an asymmetric electromagnetic
pickup stage, and harmonic / percussive / residual output streams. The one
decision taken in conversation is the one that reshapes all of it: **this
is a `:synth` engine** — Kotlin, offline one-shots — like the fourteen
before it. The reasons are the roadmap's own ("synthesis here is offline
rendering — a pure function from parameters to a `Snip`") and SIREN's
("not proposed: a new native live voice"), and they hold here:

- The product is WAVs that go into MPC kits and keygroups. An engine that
  cannot render to a file is not an engine of this app.
- CI measures the rendered sound. A live voice can only be listened to.
- A kit regenerates bit-for-bit from `kit.json`. A live voice has no
  recipe to regenerate from.
- The FX rack, keygroup export, the instant loop and U6's 4× oversample
  all exist for offline renders and none of them for a native voice.

What that costs is the spec's *real-time material morphing*: a knob moving
while a note rings. The house substitute is playability rule 5, the
instant loop — knob, re-render in tens of milliseconds, retrigger. If a
morph *during* a held note ever turns out to be the point, that is the
one reason to build a native voice, it would be the first synth voice in
`app/src/main/cpp`, and it gets its own spec.

### The specification, as reviewed

Most of the spec was infrastructure, and most of that infrastructure is
already in the repository. Read against the code:

| Spec section | In the repository today | Verdict |
|---|---|---|
| SPSC lock-free ring, drop when full | `SpscRing.h` — the same acquire/release ring, 256 deep, drops rather than blocks | built; unused by an offline engine |
| One-pole parameter smoother, 10–20 ms glide | `ParameterSmoother.h` — `y += k·(x − y)`, configured by cutoff (8–16 Hz is that glide) | built; unused here |
| 16 pre-allocated voices, steal the quietest in release, never in attack | `PadEngine` (native) holds 32 pre-allocated voices; the JVM `VoiceAllocator` steals the oldest, under test | built, differently. The spec's refinement is a `VoiceAllocator` change, not C++, and not this engine's |
| Modal bank at 1 / 2.756 / 5.404 / 8.933 | `Modes.tableFor(METAL_BAR)` is exactly those four ratios; every `Modes.Mode` carries its own t60 and its own pan | built |
| Q per mode (2000 down to 50) | `Modes` take t60, not Q. SILK's review of the santur prototype says why: `t60 ≈ 2.2·Q/f`, so a fixed Q ties ring time to pitch and the top of a bank dies first | t60, per mode, as the code does |
| Global inharmonicity 0..1 scaling modes 2–4 | `Modes.stiffString(partials, B)` from SILK's research for the string end; the bar tables for the bar end | partly built; the morph is new |
| Hammer: 1–3 ms band-passed noise, velocity → low-pass cutoff | `Dsp.Noise`, `Dsp.Env`, `Dsp.OnePole`, `Dsp.Biquad.bandpass`; `Keys.ep` already makes velocity darker-not-just-quieter | built |
| Asymmetric pickup `v / (d − x)²` with clamp and DC filter | nothing. `Dsp.drive` is a *symmetric* tanh; no engine has an asymmetric nonlinearity between resonator and output | **new — the reason to build** |
| HPR streams with independent pans and FX sends | per-mode pan exists in `Modes.Mode`; the rack is per pad and has no sends | partly; see "Streams" |
| No locks, no `malloc`, no JNI in the callback | not applicable to an offline render | dropped |

And one physics correction, which is the most audible thing in this
document:

**The spec's ratios are a vibraphone bar, not a tine.** `1 : 2.756 :
5.404 : 8.933` are the free-free Euler–Bernoulli bar, the eigenvalues
`βL = 4.7300, 7.8532, 10.9956, 14.1372` squared and normalised. A tine is
screwed to a tonebar at one end and free at the other — a **cantilever**,
clamped-free, `βL = 1.8751, 4.6941, 7.8548, 10.9955`, whose partials are
`1 : 6.267 : 17.55 : 34.39`. `Tines.KALIMBA_PARTIALS` already holds those
three numbers with the same citation (Fletcher & Rossing, bars), and
`Modes.Material.METAL_BAR`'s own KDoc says it is a *free* bar. The
consequence is not academic: a cantilever's overtones are far from the
fundamental, weakly excited, and dead within tens of milliseconds, so
**nearly all the harmonics a listener hears in an electric piano come from
the pickup, not the bar**. Build the spec as written and the result is a
vibes bar rung through a pickup — a good sound, and not the one the spec
names. FORK ships both bars as voices (below) and lets the audition say
which one the ear calls an electric piano.

### Against the fleet

| Engine or effect | Why FORK is not it |
|---|---|
| **TINES** (and `Keys.ep`) | The FM electric piano: a ratio-1 carrier whose index *is* the velocity, plus a ratio-14 ping for the bell. Its bite is an *envelope* on the index — a fake, and a good one. FORK's bite is the pickup's curvature shrinking as the tine's swing decays: the same effect, from the physics, and it changes with how close the pickup sits, which no FM index can do. |
| **SKIN** | Modal, but drums: membranes and cymbals, with PUNCH and a classifier gate per voice. No pickup, no keys path, no sustained fundamental. |
| **PLUCK / SILK** | Waveguide strings. A string's stiffness bends its partials by a few percent (`B` ~ 10⁻⁴); a bar's puts them at six times the fundamental. Different physics, different table, and neither has a nonlinearity *after* the resonator. |
| **GLINT** | Phase distortion: a formant that sweeps while the pitch holds. A bark is even-harmonic distortion of a *decaying* signal; the spectrum thins on its own. |
| **WOBBLE** (the rack) | The electric piano's tremolo *is* WOBBLE — a tempo-locked amplitude LFO baked onto a hit. FORK renders dry and the rack does what it already does. The CRUNCH rule. |
| **GRAINS** | The engine that eats captures. FORK's excite-from-pad is the other way round: a capture *strikes* a tine rather than being smeared by one — and unlike a GRAINS pad, which lands as audio with no recipe, a FORK pad keeps its striker in the recipe (below). |

So FORK is worth building for exactly one thing none of that does: **an
asymmetric magnetic pickup reading a decaying bar**, where velocity,
closeness and time each move the harmonics the way the instrument does.

### The name

TINES is taken, and it is the FM engine. The spec says the maker's name
throughout; a design document may, a product surface never may
(`SYNTH_ROADMAP.md`, "sound yes, names never"). `PresetTestSupport.
trademarkBlocklist` does not yet know the electric-piano makers;
implementation adds `rhodes`, `wurlitzer` and `fender`, and model names
as they come up in review.

**FORK.** A tine and its tonebar are an asymmetric tuning fork — the
tonebar is tuned to the tine and is why the fundamental sustains for
seconds — and it is a generic word. BARK was the runner-up and is better
spent on the macro that makes one.

## Architecture

```
STRIKE ──▶ hammer burst ──▶ Modes.ring (4–6 modes at the voice's ratios × STIFF) ──▶ swing scale
                                                                                        │
                       ┌────────────────────────────────────────────────────────────────┘
                       ▼
                 pickup: v[n] / (1 − x[n])²  ──▶ mean removed ──▶ 20 Hz high-pass ──▶ decimate ──▶ levelTo ──▶ fadeTail
```

Rendered at `Dsp.RATE * Dsp.OVERSAMPLE` and decimated (U6), like every
other engine — and not optionally here: the pickup makes harmonics above
Nyquist by construction. Mono in round one, like PLUCK; stereo is round two.

### The exciter

The spec's hammer, in house primitives: `Dsp.Noise` seeded from
`Dsp.seedFor("FORK", voice, …)` (determinism is the recipe promise),
through `Dsp.Biquad.bandpass` centred two octaves above the note, under a
`Dsp.Env` with a 0.2 ms attack and a 1–3 ms t60. STRIKE moves three
things together, because a harder hammer is brighter, shorter and bigger:
the burst's `Dsp.OnePole` cutoff (1.5 kHz → 9 kHz, `Dsp.expMap`), its
t60 (3 ms → 1 ms), and the tine's **swing** (below). On the keys path
velocity *is* STRIKE, which is how `Keys.ep` already treats it.

**Excite-from-pad** is the spec's second exciter mode — an external audio
buffer — and offline it is a better idea than live: any captured snip's
head can strike the tine. In round one, by decision. The rules:

- **The striker is the head, and short.** `Fork.striker(snip)` takes the
  source's first `STRIKER_MS` (20 ms, 882 frames at `Dsp.RATE`), mono,
  peak-normalised to the noise burst's own level, with a 2 ms raised-cosine
  fade at its end so a cut mid-waveform is not a click. Longer would not
  be a hammer: a source that keeps going through a 10 s mode is a drone,
  and the classifier would file it LOOP.
- **STRIKE still means what it means.** The striker goes through the same
  `Dsp.OnePole` cutoff and sets the same swing, so velocity on the keys
  path darkens and softens a captured strike the way it does the noise.
  Only the burst's own t60 has no meaning for it.
- **The striker lives in the recipe.** A GRAINS pad lands "as audio with
  no recipe — every GRAINS pad is its own truth" (`SYNTH_ROADMAP.md`,
  S7), because a 2.5 s source is not a recipe. 882 numbers are: SNAP keeps
  256 in its recipe and DRAW 64, so `ForkPatch` carries an optional
  `striker` field of that shape, and a kit with a FORK pad struck by a
  drum hit regenerates bit-for-bit from `kit.json` like every other pad.
  The source snip itself is not kept, as SNAP does not keep the photo.
- **The picker is GRAINS'.** The SYNTH screen's source chooser for GRAINS
  is reused as STRIKE FROM ▸ on FORK; the default, no source chosen, is
  the noise hammer.

### The resonator

`Modes.ring` with the voice's table. Two voices, because the physics
question above is a listening question:

```kotlin
enum class ForkVoice { TINE, BAR }
```

| Voice | Table | Ratios | The sound |
|---|---|---|---|
| **TINE** | cantilever (`Tines.KALIMBA_PARTIALS` extended by the fourth eigenvalue) | 1 · 6.267 · 17.55 · 34.39 | the electric piano: a near-sine fundamental, a short glassy "tink" far above it, everything else from the pickup |
| **BAR** | free-free (`Modes.tableFor(METAL_BAR)`) | 1 · 2.756 · 5.404 · 8.933 | the spec as written: a vibes bar through a pickup; more overtone, more clang |

Four modes on both. A fifth and sixth on TINE would sit at 57× and 85×
the fundamental — above Nyquist from the bottom of the range — and
`Modes.ring` already skips a mode past Nyquist rather than folding it, so
the count is four by physics, not by budget. Mode gains fall with the
ratio (`1 / ratio`, the bar's own spectral tilt under a short hammer);
the audition may reshape them.

**Decay.** DECAY sets the fundamental's t60; each higher mode's is
derived, `t60_k = t60_1 / ratio_k^SLOPE`, one constant per voice, so the
ear's rule ("higher modes die faster") is structural, never a per-mode
knob. The spec's two Q figures (2000 at mode 1, 50 at mode 4 of the
free-free bar) imply `SLOPE ≈ 2.7`; the damping physics of a metal bar
sits nearer 2. FORK starts at **2**, which at a 10 s fundamental gives:

| Mode | TINE (cantilever) | BAR (free-free) |
|---|---|---|
| 1 | 10 s | 10 s |
| 2 | 255 ms | 1.3 s |
| 3 | 32 ms | 342 ms |
| 4 | 8 ms | 125 ms |

That is the "tink" on TINE and the clang on BAR, and the slope is a
constant to move at the gate, with the spec's 2.7 as the other candidate.

**STIFF.** The spec's inharmonicity: taking modes 2–4 from harmonic to
the bar, and past it. Pinned at three stops: at STIFF 0.5 the ratios are
the voice's table exactly; at 0 they are `1, 2, 3, 4` (a string); at 1
each is `1 + 1.5·(table_k − 1)` (the spec's "extremely inharmonic rigid
bar"). Mode 1's `table_1` is always 1, so every stop collapses to 1 for
it — the fundamental never moves. Piecewise-linear *in the ratio itself*
between consecutive stops (not in some multiplier on top of it — see "As
built": the draft's first formula collapsed every mode onto the
fundamental at STIFF 0, the opposite of the string it was meant to be),
because the ear reads a mode's *position* and a straight line between two
positions is the morph with no surprise in the middle. `Modes.stiffString`'s
`B` was considered for the string end and rejected: at `B` small enough
to be a string the partials sit within cents of harmonic anyway, and a
second family of curve on one knob buys nothing audible.

### The pickup

This is the new DSP, and for a reader new to it, the mechanism in one
paragraph. A sine wave passed through a *straight* function comes out a
sine. Passed through a *curved* one it comes out with harmonics; a
symmetric curve adds odd harmonics, an asymmetric curve adds even ones
too, and that even-harmonic growl is the bark. A magnetic pickup's output
is the rate of change of the flux through its coil, and the flux rises
steeply as the tine approaches the pole — so the curve is asymmetric
(closer is *much* louder than farther) and it gets more curved the closer
the pickup sits. As the tine's swing decays the signal explores less of
the curve, and the tone cleans up by itself. TINES fakes exactly that with
BITE; here it is free.

The spec's per-sample formula, kept:

```
x[n]   = the bank's output, scaled (below)
v[n]   = x[n] − x[n − 1]
out[n] = v[n] / (1 − x[n])²
```

which is the time derivative of `1 / (1 − x)` — the standard reluctance
model with the gap normalised to 1. Four rules around it:

1. **The bank is scaled to the gap, never clamped in anger.** `Modes.ring`'s
   onset peak depends on pitch and rate (its own KDoc measures a 156×
   swing), so the bank is peak-normalised to 1 first — `Thump.snare`'s
   `bodyPeak` move — and then multiplied by the **swing**, `c · s`, where
   `c` is BARK's closeness and `s` is STRIKE's 0.5 → 1. The resonator is
   linear, so normalising a unit-hammer render and scaling afterwards is
   exact, not approximate. The spec's safety clamp (`1 − x ≥ 0.01`) stays
   as a guard the tests prove is never reached at any legal knob value.
2. **BARK's top is set by the band, not by taste.** At closeness 0.85 the
   harmonic series of `1/(1 − x)²` puts the *twentieth* harmonic 20 dB
   above the fundamental; at 0.7 it is 12 dB below and the band edge at
   4× (the 84th harmonic of C6) is 186 dB down. BARK maps closeness
   `0.05 → 0.7`, and the aliasing test (below) is what moves that top if
   the ear wants more.
3. **The DC goes twice.** The formula's output has a mean (the spec says
   so). Offline the whole buffer is known, so the mean is subtracted
   outright, then a 20 Hz one-pole high-pass (`Dsp.OnePole` "subtract
   from input") takes the slow residue. `DC within 0.05` is the existing
   test.
4. **The derivative is a tilt.** A first difference is +6 dB per octave.
   It is part of the sound (it is why a pickup reads bright), and it is
   why the exciter's own cutoff sits where it does; no extra filter.

**Loudness.** The pickup makes level depend on BARK and STRIKE. On pads,
`Dsp.levelTo(Dsp.MELODIC_LOUDNESS_TARGET)` after the fact, as every
melodic engine does — so BARK changes tone, not loudness, the promise
`Dsp.drive` makes with its unity make-up. On keys the layers
are velocity-true, as `Keys.ep`'s are, so a note is *not* levelled per
render: the hard layer's gain is fixed relative to the soft one at the
instrument level.

### Streams

The spec splits the output into harmonic (modes 1–2), percussive (the
exciter's first 15 ms plus modes 3–4) and residual (undefined), with
independent pans and FX sends. Offline, the honest version of that:

- **Per-mode pan is already there** (`Modes.Mode.pan`), so a stereo render
  with the fundamental centred and the overtones and hammer spread is a
  table change, not DSP. It is round two because stereo is U4's opt-in
  (`docs/SYNTH_UPGRADE.md`): WAV size doubles and the rack has a
  stereo-safety audit to pass first.
- **FX sends do not exist and are not proposed.** The rack is per pad by
  design. The sampler's way to send two streams to two effects is two
  pads, and that is round two's `SPLIT TO PADS`: land the harmonic render
  and the percussive render on two pads, each with its own rack — the
  spec's "ping-pong delay on the transient, fundamental anchored centre"
  becomes ECHO on one pad and nothing on the other. On the MPC that is a
  layered pad.
- **Residual** is defined here as *what the other two leave*: the hammer
  burst after its first 15 ms. It is a few milliseconds of filtered
  noise; it rides with the percussive pad unless the audition finds a
  reason to separate it.

## Macros

Five, inside the roadmap's 3–6 budget. Plain words, bounded ranges.

| Macro | Moves | Range / mapping |
|---|---|---|
| **TUNE** | the note | 24 semitones from C3, snapped (every melodic engine's rule; SPREAD and in-key need it). C3 rather than SIREN's C4 because the bark lives in the bass and tenor |
| **STRIKE** | the hammer | burst cutoff 1.5 → 9 kHz (`expMap`), burst t60 3 → 1 ms, swing ×0.5 → ×1. Default 0.5 |
| **BARK** | the pickup's closeness | closeness 0.05 → 0.7, linear. The identity knob. Default 0.4 |
| **STIFF** | harmonic → the voice's bar → past it | `Dsp.around`: 0 a string, 0.5 the table exactly, 1 stretched 1.5×. Default 0.5 |
| **DECAY** | the fundamental's t60; the rest follow by SLOPE | 0.4 → 5 s, `expMap`. Default 0.5 (≈1.4 s, a pad-length note under the classifier's TONAL line) |

SCRAMBLE uses `Dsp.scrambleNear` around the voice's presets once they
exist (the SKIN lesson: around one default is a worse dice than around
sixteen points). No PUNCH: it is a drum-engine macro and FORK is melodic.

## Data flow and compatibility

- `ForkPatch` in `Patches.kt`, the common shape: voice + the five macros,
  plus the optional `striker` (882 samples, the shape SNAP's `table`
  set); `Patches.fromJsonValue` round-trips it, and a wrong-length or
  non-finite striker is a `JsonException` like a wrong-length SNAP table.
  A recipe replays bit-for-bit.
- `Keys.fork(midi, velocity)` renders at exact MIDI pitch for the
  instrument: velocity-true soft and hard layers, STRIKE from velocity,
  per-note t60 like `Keys.ep`'s but capped at 5 s (a 110 Hz fundamental
  at the spec's Q rings for 40 s, and that is file size); multisampled
  every minor third; `MAKE INSTRUMENT ▸` and `snipsnap synth FORK TINE
  --instrument`, dual-generation keygroup.
- `Presets.kt` gains a FORK branch; `ForkPresets.kt` holds 8–12 per voice,
  named for the sound (DINNER JAZZ, GLASS TINE, HARD BARK). SUITCASE and
  STAGE are model nicknames and are not names here; the near-miss check
  gets the new terms.
- `SynthScreen`'s picker: … → SIREN → FORK → THUMP; README's engine
  count moves up one. FORK's defaults file as TONAL; a DECAY past 1.5 s
  files LOOP by the classifier's length rule, which is what SIREN
  documented and is correct.
- The CLI: `snipsnap synth FORK TINE --all --out <dir>` renders the roster.
- `SynthKits`: a `SnipSnap Fork Kit` under `testkit/`
  (`./gradlew :synth:generateForkKit`), the same shape as SIREN's.

## Failure handling

- A macro outside 0..1 is coerced, as every engine does.
- The pickup clamp is a `require`-free guard (never a thrown error in a
  render); the tests prove the guard is dead code at legal values.
- A mode above Nyquist is skipped by `Modes.ring`, never folded; at the
  top of TUNE on TINE that leaves two modes, and the pickup still makes
  the sound.

## Testing

### The ones that carry the claims

1. **In tune.** Every snapped TUNE step, both voices, all five macros at
   default and at their corners: the rendered fundamental within 5 cents.
   The pickup adds harmonics; it must not move the fundamental. On the
   keys path, every MIDI note in range, both layers.
2. **The bar is where the table says.** At STIFF 0.5 the spectral peaks
   of a long-DECAY render sit on the voice's ratios (within 1 %); at
   STIFF 0 within 5 cents of `2, 3, 4 × f0`; at STIFF 1 above the table
   on every mode. Monotonic in STIFF for mode 2.
3. **Higher modes die first.** Band-limited decay measurement per mode:
   each mode's measured t60 shorter than the one below it, on both
   voices, and mode 1's within 10 % of what DECAY asked for.
4. **BARK barks.** The second harmonic's level relative to the
   fundamental rises monotonically with BARK at fixed STRIKE, and with
   STRIKE at fixed BARK — the even-harmonic signature of an asymmetric
   curve. At BARK 0 the second harmonic is at least 40 dB down (a clean
   pickup is a clean sine).
5. **The bite is real.** The spectral centroid of the first 50 ms is
   higher than that of the 500–550 ms window, at every BARK above 0, and
   the gap widens with BARK. This is the claim that the physics does what
   TINES' BITE envelope fakes.
6. **A striker is a hammer, not a drone.** Struck from a captured kick's
   head and from a captured hat's: the fundamental within 5 cents either
   way (test 1 holds for any striker); the first 20 ms's spectral centroid
   differs between the two (the striker is heard); past 200 ms the two
   renders' spectra agree within 1 dB per band (the striker is *only*
   heard at the start). A striker of 882 samples of silence renders
   silence, not a crash.

### The rest

- **Aliasing floor:** at BARK 1, STRIKE 1, STIFF 1 and the top TUNE,
  energy between harmonics at least 45 dB under the harmonic energy — the
  TIDE bar. This is the test that sets BARK's ceiling if 0.7 is wrong.
- **The clamp is dead:** the largest `|x|` before the pickup at every
  legal corner is ≤ 0.7; the `1 − x ≥ 0.01` guard never fires.
- **DC within 0.05**; peak ≤ 0.99; loudness within the melodic band.
- **Determinism:** same recipe, same bytes; every seed from `Dsp.seedFor`.
- **Fuzz:** every macro at 0 / 0.5 / 1 across both voices — finite,
  bounded, finishes.
- **Identity:** both defaults classify TONAL; 200 SCRAMBLEs audible and
  unclipped.
- **Recipe:** `ForkPatch` round-trips through JSON, with and without a
  striker; a kit with a struck FORK pad regenerates bit-for-bit.
- **Keys:** the instrument's zones cover the range with no gap; the hard
  layer is louder and brighter than the soft at every zone; the sustain
  cap holds at 5 s.
- **Names:** the roster passes the blocklist with `rhodes`, `wurlitzer`
  and `fender` added; `ThumpPresetsTest`'s near-miss and clean-name checks
  extended (`ELECTRIC PIANO` and `DINNER JAZZ` allowed).

## Phasing and gates

Every phase ends the house way: stop and listen. A listening page renders
the phase's voices at defaults and at each macro's extremes, and the chips
are the gate.

| Round | Ships | Gate |
|---|---|---|
| **R1** | `synth/Fork.kt` (both voices, the hammer, the striker, the bank, the pickup), `ForkPatch` with its optional striker, `ForkPresets.kt` and its `Presets` branch, `Keys.fork` and `MAKE INSTRUMENT ▸`, the tests above, the blocklist terms, the testkit kit; the phone: FORK in the SYNTH picker, SEND TO PAD, STRIKE FROM ▸. Mono | **the physics question:** TINE or BAR is the electric piano; SLOPE 2 or 2.7; BARK's ceiling; the instrument under two hands; a drum hit rung through a tine |
| **R2** | stereo: per-mode pan (fundamental centred, overtones and hammer spread), U4 opt-in per patch; `SPLIT TO PADS` landing harmonic and percussive renders on two pads | the split against the mono |

The first draft staged this as four phases — engine, then keys, then
stereo, then excite-from-pad last as the least certain to be musical.
The owner chose scope over the staging for keys and the striker (the
roadmap's THUMP round made the same kind of call, range over the cap),
so R1 carries all three and one audition hears them together. Inside R1
the order of *work* is still engine, keys, striker, since each is built
on the one before, and a plan may cut a round-one PR at any of those
seams. R2 waits on U4's stereo audit regardless.

## Out of scope

- **Real-time / native.** Settled above. No telemetry bridge, no voice
  pool, no callback constraints: they exist for the sample players and are
  not this engine's.
- **A live external-audio exciter.** The striker is the offline form.
- **FX sends per stream.** Two pads are the sends.
- **A tremolo or a "suitcase" stereo pan.** That is WOBBLE and the rack.
- **A reed voice.** The other classic electric piano reads its reed with an
  electrostatic pickup, a different nonlinearity; if wanted it is a third
  voice with its own pickup law, after the audition.

## Decisions taken in conversation — 2026-09-27

| Question | Decision | By |
|---|---|---|
| Home | a `:synth` engine, Kotlin, offline | conversation |
| Which bar sits at STIFF's centre | both, as voices: TINE (cantilever) and BAR (free-free); the audition picks the default | conversation |
| Residual, and stereo in round one | **open** — the owner does not know yet. The default stands until R1's gate: residual is the hammer after 15 ms; R1 is mono; per-mode pan and SPLIT TO PADS are R2 | default, this document |
| Scope | keys and one-shots together, in R1 | conversation |
| Excite-from-pad | R1 | conversation |

### Open for review

1. **SLOPE** — 2 (bar physics) or 2.7 (the spec's Q pair). Set at R1's
   gate by ear; the test only asks that higher modes die first.
2. **BARK's ceiling** — 0.7 by the harmonic-series estimate; the aliasing
   test decides, the ear may ask for less.
3. **Mode gains** — `1 / ratio` is a guess with the right shape; the gate
   may want TINE's second mode louder for the tink.
4. **The keys t60 cap** — 5 s is a file-size number, not a musical one.
5. **Residual and stereo** — question 3 above. What to listen for at
   R1's gate: whether the mono render sounds *small* (U4's word for the
   thing stereo fixes), and whether anyone reaches for the hammer's tail
   as a thing of its own. If neither, R2 is per-mode pan and the split
   as written; if the tail matters, residual becomes a third pad in the
   split.

## As built — 2026-09-27

R1 shipped whole: `Fork.kt` (both voices), `ForkPatch` with the optional
striker, `ForkPresets.kt` (8 per voice, 16 total), `Keys.fork` and
`InstrumentSuite.renderFork` (a real dual-generation keygroup, 9 zones,
`./gradlew :synth:generateInstrumentSuite`), the SYNTH picker (`… → SIREN
→ FORK → THUMP`), `SynthKits.fork()` and its testkit kit
(`./gradlew :synth:generateForkKit`), the blocklist terms, and the test
suite the spec named. Where the build departs from the design, and why:

- **STIFF's formula was wrong in the draft, not just under-specified.**
  `r_k(s) = 1 + m(s)·(table_k − 1)` with `m(0) = 0` gives `r_k(0) = 1` for
  every mode — every mode at the fundamental, not the harmonic string the
  same sentence promised. The shipped formula is piecewise-linear in the
  ratio itself between three pinned stops (harmonic → table → stretched),
  corrected in the "The synthesis" section above rather than left wrong
  there with the fix only here — a plain arithmetic error is not a design
  decision worth preserving in place.

- **STRIKE needed a fourth mechanism, not just three.** The spec's
  excitation-cutoff channel (1.5 → 9 kHz) only helps a mode that sits
  *above* the darker end of that range. Measured: BAR's four modes, at a
  typical note, all sit under 1.5 kHz already — the cutoff sweep has
  nothing to give any of them, and STRIKE's audible brightening (real,
  and confirmed by the second-harmonic test) came entirely from the
  pickup's own swing, which turned out to not reliably dominate on its
  own. The fix, `STRIKE_BRIGHT_BOOST` (`Fork.kt`): each mode's gain gets
  an extra `1 + 2.2·k·STRIKE` factor, `k` its index (0 for the
  fundamental, which therefore never moves). This is not a patch over a
  test — it is real hammer physics (a harder strike excites higher
  partials disproportionately more) that the excitation-filter channel
  alone does not carry, measured directly: at STRIKE 0→1, BAR's own mode
  2/3/4 energy (isolated, pre-pickup) rose 6×/67×/34× while mode 1 barely
  moved. `STRIKE`'s own macro-table entry above still reads "three
  things together"; read it as four, this one on the resonator's gain
  rather than the exciter.

- **`Keys.fork` takes one continuous `strike`, not two baked layers** —
  exactly the shape `Keys.ep`'s own `bright: Float` already takes, and for
  the same reason: velocity-true soft/hard rendering is an *instrument
  builder's* move (`InstrumentSuite.renderFork` calls `Keys.fork` twice,
  at `strike` 0.3 and 0.8, the same way `InstrumentSuite.renderEp` calls
  `Keys.ep` at `bright` 0.35 and 0.8), not something the engine itself
  bakes in. The spec's "velocity-true soft/hard renders" language was
  right about the *result*; the mechanism sits one level up.

- **There is no general `--instrument` CLI flag to hang FORK's keys off
  of.** `SynthCommand`'s `--instrument` is hardcoded to RESIN
  (`ResinPadMaker`, the *held-note* instrument S8.1 built) and refuses
  every other engine by name. FORK's keys instrument ships instead
  through the same one-off generator infrastructure EP, Organ, Harp,
  Music Box and the RESIN pad itself already use —
  `InstrumentSuite.renderFork` plus a `RECIPES` entry in
  `InstrumentSidecar` (a `when`-exhaustive-shaped map that throws by name
  on an unregistered instrument, the same shape `Velocity.kt`'s
  `macroSpecsFor`/`brightnessOverride` take for `Patch` subtypes — FORK
  needed an entry in both). No CLI flag was added or promised for this;
  `./gradlew :synth:generateInstrumentSuite` is the door, same as the
  other five.

- **FORK never reads strict TONAL, at any DECAY.** Measured across the
  full preset roster and confirmed with a swept-DECAY test: below the
  classifier's 1.5 s length line it reads PERC (the pickup's brightest
  content sits at the attack and the peak-to-−20dB decay-shape gate
  does not clear 500 ms before the render itself would have to grow past
  1.5 s), above it LOOP. There is no gap. `Fork.drumClassFor(voice,
  macros)` is the honest version of this — a cheap, macro-only predictor
  (the picker's own mute-group filing has to run before a render exists)
  verified against the real classifier across ten DECAY steps — rather
  than a static per-voice property (`ThumpVoice.drumClass`'s own shape)
  that cannot see DECAY, or a wished-for TONAL this engine does not
  produce. `SynthScreen`'s own `FORK -> Fork.drumClassFor(...)` line and
  both `ForkTest`/`ForkPresetsTest` hold the measurement.

- **STRIKE is now `Velocity.kt`'s brightness macro for FORK** (a
  `brightnessOverride` entry, PLUCK's PICK and THUMP SNARE's own
  precedent), proven monotonic — within a small, physically-explained
  ripple (a few percent, where the swept excitation cutoff crosses one of
  a voice's own mode frequencies; not a reversal, and TINE has none of it
  since its modes sit far apart) — by `ForkTest`'s own centroid sweep.

- **The classifier verdict aside, everything else in "The synthesis",
  "The pickup" and "Streams" shipped as designed**: the render length is
  `t60 + a small pad`, not the draft's separate `1.3×` tail factor (the
  same fix that pushed the classifier reading down from LOOP to PERC at
  low DECAY — t60 is already −60 dB, and `Dsp.fadeTail` is the
  truncation's own safety net, so the extra 30% bought nothing). The
  pickup's clamp is dead at every legal macro corner exactly as
  predicted; BARK's ceiling (0.7) and the base `1/ratio` mode-gain shape
  are untouched, both still open for the audition gate.

- **Not shipped in R1, by scope**: the phone's `STRIKE FROM ▸` picker
  affordance for the striker (the engine and the recipe format —
  `Fork.striker`, `ForkPatch.striker` — are built and tested; only the
  Compose screen's own source-chooser button is not wired, since this
  session has no Android SDK to build or see `:app` with, and a picker
  button guessed blind is worse than one left for a session that can
  verify it). `SEND TO PAD`'s landing toast and any FX-chain-on-landing
  behavior, which FORK does not need yet (no ECHO-on-landing decision
  like SIREN's, since FORK has no LOOP render to distinguish from a
  one-shot). Both are small, and neither blocks the audition: presets,
  the kit, and the keys instrument all render and export today.

## Round two — 2026-09-28

R1's own audition (16 presets, both voices) came back: closer on TINE, but
neither read as "piano" outright, and BAR read as its own thing — a
marimba, worth keeping as a voice rather than chasing toward piano. Asked
for at least one more voice option, aimed at the piano gap specifically.

**`ForkVoice.NODE`** — the same cantilever tine as TINE (`TINE_RATIOS`
unchanged), read at a different spot: its own second mode's internal
node. A pickup there cannot see mode 2 at all, and modes 3 and 4 are
strongly reduced too (their own shape function is simply small in that
region) — a purer, more fundamental-forward tone than TINE's own tip
read, without inventing a new physical claim to get there or touching the
bar itself.

The mechanism, not asserted: a cantilever's mode shape is derived from the
Euler-Bernoulli fixed-free boundary conditions (`Fork.cantileverModeShape`
— fixed end gives `φ(0)=φ'(0)=0`, collapsing the general solution to
`A[cosh(βξ)−cos(βξ)] + B[sinh(βξ)−sin(βξ)]`; the free end's own two
conditions then fix `B/A` and, requiring both to agree, *reproduce* the
textbook characteristic equation `cosh(β)cos(β) = −1` — not assumed, a
derivation that happened to land on the independently-known answer, which
is the actual verification). The pickup position (`NODE_PICKUP_XI =
0.783445`) is mode 2's own root of that shape function, found by
bisection — not a chosen or fitted number, and it matches the value beam-
vibration references already tabulate for a cantilever's second mode, a
cross-check the derivation did not have to pass but did.

`ForkTest` carries the check twice: once on the derivation itself (the
tabulated eigenvalues satisfy `cosh(β)cos(β) = −1`, the mode shape is zero
at the clamped root), and once on the audible claim (mode 2's own energy,
measured on the clean resonator before the pickup's nonlinearity, is
suppressed to under 15% of TINE's own at the identical macros).

**What round two deliberately did not do**: touch `BARK`'s closeness
mechanism, add a `POSITION` macro letting a player move the pickup
themselves (the shape function crosses zero repeatedly across 0..1 — an
exposed macro would wander through a landscape of arbitrary phase
flips between modes, not a clean bright/warm dial, so this round bakes in
the one physically-motivated spot rather than exposing the whole
unpredictable range), or claim NODE "solves" the piano question — that is
still the audition's own call, now with a third, purpose-built candidate
to make it against. Eight presets shipped for NODE (`ForkPresets.kt`),
named for the tone the mechanism gives, not for having arrived.

## Round three — 2026-09-28

Round two's own A/B (nine matched settings, NODE against TINE, blind,
level-matched) came back too close to call: real per the measurements above,
but largely masked in listening. The reason, on reflection, is the pickup's
own nonlinearity — the thing that actually carries most of FORK's audible
character — operates on the summed time-domain signal and does not care
much how that sum was built mode by mode, so a change to the pre-pickup
modal balance alone has less to work with than hoped.

**NODE's own pitch glide**: every mode reads `GLIDE_CENTS` sharp right at
the strike and settles to its tuned ratio over `GLIDE_TIME_SECONDS`,
scaled by STRIKE (harder strike, bigger swing, bigger glide — the same
lever every other STRIKE-linked mechanism here already uses, and zero at
STRIKE 0). This is a real, if not precisely sourced, amplitude-dependent
effect: large-amplitude vibration briefly stiffens a struck bar or string,
raising its effective frequency, before it settles as the swing dies down.
`GLIDE_CENTS = 15` and `GLIDE_TIME_SECONDS = 0.06` are a plausible
starting point, not a measured figure — open for the audition gate to
move, the same way `DECAY_SLOPE` started.

**One real finding, the same testing-philosophy failure this project keeps
catching early rather than late**: the first implementation modelled the
glide as two full static-pitch renders (one a few cents sharp, one at the
tuned pitch) crossfaded together. That broke an existing test —
`higher modes die first`, which measures the composite decay's own
envelope slope — because two near-identical frequencies briefly coexisting
during a crossfade beat against each other, distorting the measured decay
shape. The fix ships instead as a single continuously-swept two-pole
resonator (`Fork.ringModes`, the pole angle recomputed every sample from
the instantaneous, gliding frequency rather than held fixed for the whole
call) — one coherent signal, never two overlapping ones. `Fork.bank`'s own
KDoc carries the full account.

Measuring the glide itself needed the same isolation the STIFF tests
already lean on: on the full four-mode bank, `STRIKE_BRIGHT_BOOST` boosts
the upper modes hardest at exactly the STRIKE that also maximises the
glide, and their own fast zero-crossings dominate a naive pitch read in
the first ~20ms regardless of what the fundamental is doing — even TINE's
own glide-free onset read several cents "sharp" by this artifact alone.
`ForkTest` measures the glide on the isolated fundamental instead (via
`Fork.ringModes` directly, one mode, no competing partials), plus a
bit-exact check that the no-glide path is untouched for TINE, BAR, and
NODE at STRIKE 0.

**Not yet answered** (at round three's own close): whether the glide is
what NODE (or TINE) was actually missing — that was the next listening
question, not something this round asserted.

## Round three-B — 2026-09-29

`GLIDE_CENTS` pushed from 15 to 80 (most of a semitone), as a diagnostic:
rule out "15 cents over a noisy 60ms attack just isn't perceptually
salient" before concluding FORK's pickup-and-bar architecture has a
character ceiling small mechanism tweaks can't cross. Both listening
pages regenerated at the new value.

**The gate's own answer**: still too close to call by ear, even
overcooked past any reasonable "is it real" threshold. That settles the
open question two rounds up — not a measurement gap, a genuine ceiling.
The pickup's own nonlinearity carries most of what a listener calls
FORK's character, and it operates on the summed time-domain signal
downstream of both mechanisms tried here (NODE's pickup position, then
the glide); neither one gives it materially different raw material to
work with.

**The owner's call, not a further round**: keep 80 cents as NODE's
shipped default rather than dial back toward the physically-motivated 15,
or strip the mechanism. FORK is done chasing "closer to piano" for now —
TINE and NODE stand as close cousins, BAR as its own marimba-adjacent
voice, and all three ship as FORK's own instrument rather than an
emulation of one. If the piano question is worth reopening, the reed
voice noted under "Out of scope" (a different nonlinearity entirely, not
a further tweak to this one) is the next real candidate, not another turn
on `GLIDE_CENTS` or `NODE_PICKUP_XI`.

## Round four — 2026-09-30

The reed voice, reopened the same day: not "closer to a Rhodes" (settled
above) but a second real electric-piano family — the Wurlitzer, read by
an electrostatic (capacitive) pickup rather than a magnetic one.

**The finding that changed the plan before any code was written**: a
naive derivation of the electrostatic pickup gives FORK's own existing
formula straight back. A magnetic pickup's flux and a capacitive
pickup's charge both scale as `1/gap`, so differentiating either produces
the same `v/(1-x)²` curve — "swap the pickup law" is not a new mechanism,
it is TINE again under a different name. Sourced instead (Wikipedia's
"Electrostatic pickup" article, and independent descriptions of the two
instruments' own reported sound): real units read the reed through a
comb-shaped electrode, not one flat plate, and the reported tone is
sharper, closer to a sawtooth, odd-harmonic-dominant — against a magnetic
pickup's even-harmonic bark. A comb electrode does not skew asymmetric
the way a single flat plate does, so the physically appropriate model is
a *symmetric* saturating curve, genuinely different raw material for the
pickup to work with rather than the same curve reshaped.

**Prototyped before it was built**: a throwaway generator
(`ReedPrototype.kt`, never committed — a temporary Gradle task and one
test-scope file, removed after each listening round) rendered candidates
straight to the owner as files rather than through a published audition
page, twelve rounds in one sitting. The house testing philosophy —
measure, don't guess — caught two real bugs this way that a first
listen alone would not have separated from taste:

- **The onset's own harshness was two unrelated causes, found one at a
  time.** [Fork.bank]'s own peak sits in the excitation's broadband
  click, not the settled swing (measured directly: peak in the first 2ms
  equalled the peak overall). Pushing that straight through full
  saturation read as static; [Fork.reedPickup]'s own
  [Fork.REED_DRIVE_RAMP_MS] fixed it. The contact rattle was *still*
  harsh afterward — isolating curve-only against with-contact (two
  renders, one question) proved the base pickup was already clean and
  the persistent stop-clip nobody had throttled (every over-threshold
  sample, not just the trigger instant — a near-square wave running for
  100ms+ on a hard strike) was the real remaining source. Removing it
  fixed the harshness and silenced the rattle at the same time.
- **The rattle going silent was a genuine logic bug, not a level.** Every
  round had scaled the knock's own impulse by "overshoot" —
  `abs(raw) - threshold`, read at the exact sample the rising edge
  crosses the threshold, which by construction is a hair above zero, not
  the swing's own size (confirmed directly in an old diagnostic log
  without registering it at the time: `0.60049` at trigger against a
  `0.6` threshold). The knock was only ever audible because it rode on
  top of the persistent clip's own distortion floor; once that clip was
  removed for the harshness fix above, the knock's true near-zero
  amplitude had nothing left to hide behind. Fixed by scaling the
  impulse to a fixed strength instead — contact already only fires when
  the swing clears the threshold at all, which already is the "hard
  enough" signal.

**The shipped mechanism, three parts, not one**:

1. **A comb-tap modal color** ([Fork.REED_POSITION_GAIN]): the same
   [Fork.cantileverModeShape] derivation [ForkVoice.NODE] uses for one
   tap position, extended to two (the tip and a point partway down the
   reed) and blended — the sourced "several plates at different
   positions" detail, not NODE's single relocated read repeated.
2. **A symmetric, STRIKE-coupled drive** ([Fork.reedPickup]): [Dsp.drive]
   on the resonator's own rate of change, ramping in over
   [Fork.REED_DRIVE_RAMP_MS] rather than applying instantly, its own
   ceiling set by BARK. Confirmed odd-harmonic-dominant directly
   (measured against TINE's own -2.4dB odd/even ratio: REED reads +40 to
   +60dB depending on drive, essentially all odd).
3. **A discrete mechanical-contact rattle** ([Fork.reedContact]): past a
   BARK-set threshold, an impulse excites two short resonant rings (a
   "click" and a lower "body," both drifting slightly per event) rather
   than an enveloped noise burst — a real rattle has pitch and
   resonance, not noise shaped by a volume curve — gated by a grace
   window (so it cannot land on the hammer's own onset click) and a
   refractory period (so a hard strike's whole swing does not retrigger
   it every half-cycle, which measured as 40-50 events and read as a
   sustained buzz rather than an occasional knock).

**Why three parts and not the first one that measured as "different"**:
round one (the symmetric curve alone) already measured as genuinely
odd-harmonic-dominant, a real spectral difference from TINE — and still
came back "different tone, not different character" by ear. That is the
same verdict NODE and the pitch glide got from static or smoothly-varying
tweaks to a memoryless curve. The contact rattle is a genuinely different
*kind* of thing — a discrete, non-smooth event, not a reshaped
continuum — and reads as one: "I hear it now," confirmed after the two
bugs above were fixed, is what shipped.

**What round four deliberately did not do**: give REED its own macro for
the contact threshold or the comb's own tap position — both ride on BARK
and a fixed derivation respectively, the same "one physically-motivated
spot, not the whole unpredictable range" call round two made for NODE's
own pickup position. `ForkPresets.kt` ships eight presets spread across
both sides of the contact threshold, some staying clean of it entirely.
