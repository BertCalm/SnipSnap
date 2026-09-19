# Synth depth — from primitives to bodies

`docs/SYNTH_UPGRADE.md` closed with a non-goal: *"Not competing with desktop
synths on depth."* This document reverses that decision, and explains what
replaces it.

The complaint is that the engines sound **limited, thin, cheap and
uninteresting** when auditioned a pad at a time. Those are four separate
defects with four different causes, plus a methodology failure that produced
them. Only one needs genuinely new DSP.

| Word | Cause | Fix |
|---|---|---|
| **Limited** | Macro ranges under two octaves; four knobs per voice; each voice locked to one character | Range audit, spine macro layout, morphable mode tables |
| **Thin** | No stereo anywhere; no layering | SPACE stage |
| **Cheap** | Linear filters, uniform peak normalize, phase-locked starts | DRIVE stage, loudness discipline |
| **Uninteresting** | No resonator outside PLUCK; nothing moves over a note's life | BODY and MOTION stages |

## The governing finding

Nearly every technique that would fix this is already in the codebase —
implemented correctly, exactly once, and never generalized.

| Technique | Implemented at | Missing from |
|---|---|---|
| Filter saturation | `Velvet.kt:144` | the other seven engines |
| Loudness-*preserving* rescale | `Punch.kt:147` ← `Thump.kt:132` | all five melodic engines |
| Velocity → timbre | `Keys.kt:48` (FM index) | everywhere else |
| 4× oversampling | eight engines | `Pluck.kt:96` |
| Modal resonator bank | `audio/Body.kt` | all of `:synth` |
| Grain drift / per-grain pitch motion | `Grains.kt` | not even in `FxChain.SECTIONS` |
| Pitch LFO (wow + flutter) | `Tape.kt:18` | never applied to melodic voices |

This is not seven DSP projects. It is one discipline problem: proofs of concept
that never became policy. A large share of the complaint is recoverable by
promoting each technique from one site to all sites.

**`Grains.kt` and `Tape.kt` matter most here**, because they are the two
entries closest to the MOTION gap below. `Grains` already accepts *a synth
render* as its source and applies grain-position drift with per-grain pitch
variation. `Tape` already implements a ~1.3 Hz wow plus ~7.4 Hz flutter as a
modulated fractional delay. Motion is substantially cheaper than it looks.

## The five defects

**1. Limited — the knobs barely travel.** Every TINES TUNE macro spans less
than two octaves: BELL 220–740 Hz (1.75 oct), CHIME 520–1500 Hz (1.53),
BLOCK 380–950 Hz (1.32), ZAP 55–120 Hz (1.13). DECAY spans under 6× across
every engine. Turning a macro from 0 to 1 moves the sound a little.

This compounds with defect 5: the spread tests force twelve presets apart
inside a macro space too small to hold twelve meaningfully different sounds.
That is why 392 presets feel like thirty.

**2. Thin — nothing is in stereo.** Every engine returns
`Snip(..., channels = 1)`. There is no `channels = 2` anywhere in the
repository. The FX rack has sixteen sections and none widen; three
(`Phase.kt:61`, `Ring.kt:49`, `Vinyl.kt:86`) explicitly document that a stereo
pair is processed together, so even if stereo existed nothing would
decorrelate it.

**3. Cheap — every pad hits with identical weight.** `Dsp.normalize(target =
0.95f)` is the final stage for Velvet, Fathom, Vox, Tonewheel and Pluck: peak
only.

> **Correction to an earlier draft.** This document claimed "THUMP alone is
> loudness-matched." It is not. `Punch.applyOversampled` (`Punch.kt:209-224`)
> measures the loudness of the *peak-normalised* un-punched signal and rescales
> back to it, so PUNCH cannot change perceived level. That is loudness
> *preservation across one stage*, not loudness *targeting*. THUMP's absolute
> level still comes from `Dsp.normalize` at 0.95 peak like everything else.
> **No absolute loudness anchor exists anywhere in the codebase**, so Phase 0
> chooses one from scratch rather than matching an existing scale. A sine-heavy patch has a far higher
crest factor than a saw or noise patch, so at equal peak it is meaningfully
quieter in perception — and `SynthKits.melodic()` puts these on the same grid
as the loudness-matched drums, so tonal pads sit under the kit by construction.

**4. Uninteresting — every note is one static gesture.** `Tines.strike()` is a
single function generating all five TINES voices. One excitation, one amplitude
envelope, one decay rate. Nothing in a note disagrees with itself, and nothing
moves over its life: no LFO, no drift, no per-note randomization, no key
tracking in any engine. There is no resonator anywhere in `:synth` except
PLUCK's Karplus-Strong loop.

**5. The presets were never heard.** Six preset files carry the same admission
— *"Authored from that DSP, not by ear"* (`TinesPresets.kt:14`,
`VoxPresets.kt:9`, `FathomPresets.kt:11`, `PluckPresets.kt:10`,
`VelvetPresets.kt:9`, `TonewheelPresets.kt:13`). All 392 presets were written
by reasoning about code and validated by tests.

Worse, the tests enforce the wrong property.
`TinesPresetsTest.kt:76` — `presets within a voice do not cluster` — fails the
build when two presets are closer than ~0.07 RMS in macro space. Good presets
*cluster*: most of a synth's parameter space sounds bad and the sweet spots are
narrow. A test that forbids clustering forces presets out of the good regions.

## Three outright bugs

**FAT never beats.** `Velvet.kt:105` sets detune to 1.0005–1.012. At BASS
defaults (82.4 Hz, FAT 0.3 → 1.00395) the beat period is ≈3.07 s against a
≈0.47 s buffer (`t60` 0.15–0.9 s × 1.4, `Velvet.kt:96,118`) — the pair never
completes a quarter of one beat cycle. At maximum FAT and DECAY it is ≈1.01 s
against 1.26 s: barely one cycle. FAT is a static comb tint, not width.

**Every oscillator starts phase-locked.** `Velvet.kt:124`, `Fathom.kt:153`,
`Vox.kt:108`, `Tonewheel.kt:93` all begin at phase exactly 0.0. Detuned pairs
therefore always start in lockstep, and the attack is identical every render.

**PLUCK is out of tune with itself.** `Pluck.kt:127` uses
`n = (RATE / freq).toInt()` — an integer delay line with no fractional-delay
correction. HARP at maximum TUNE (660 Hz) renders ≈+21 cents; at its root
(165 Hz), ≈+1.8 cents. The error is non-monotonic across semitones, so adjacent
pads in a pentatonic `SynthKits.melodic()` run are out of tune *relative to each
other*. PLUCK is also the only engine that never oversamples.

## Architecture: the voice spine

A voice stops being one fused formula and becomes five stages.

```
EXCITE ──→ BODY ──→ MOTION ──→ DRIVE ──→ SPACE
onset      modal     drift &     nonlinear  stereo
           resonator variation              decorrelation
```

### The spine is also the macro panel

"Too few knobs" does not require going modular — `SYNTH_ROADMAP.md` rule 1
stands. Each stage exposes its own macro, so the panel grows from four knobs to
seven while becoming *easier* to learn, because five concepts organise them.

| Stage | Macro | What it does |
|---|---|---|
| EXCITE | **STRIKE** | hardness and position of the excitation |
| BODY | **MATERIAL** | morphs the mode table between body types |
| | **SIZE** | fundamental |
| | **DAMP** | decay tilt across modes — highs against lows |
| MOTION | **MOVE** | drift and per-note variation depth |
| DRIVE | **DIRT** | saturation and deliberate grit |
| SPACE | **WIDTH** | stereo spread |

### EXCITE

The onset, synthesized separately from the tone: noise burst, impulse, scrape,
or an existing engine's oscillator, with its own short envelope. This gives
every engine a second spectral layer independent of the body.

**STRIKE carries excitation position**, which on a real bar decides which modes
wake up — strike a node and that mode stays silent. As mode-gain weighting
that is approximately `gain_n *= sin(n · π · position)`: one line, and it takes
a voice from woody thunk to glassy ping across one knob. This is the kind of
reach defect 1 is asking for, and it exists only once there are modes to
address.

### BODY — `Dsp.Modes`

A bank of N two-pole resonators, each with its own frequency ratio, gain and
**independent t60**.

> **Correction to an earlier draft.** This document previously claimed
> `audio/Body.kt` "already implements exactly this structure." It does not.
> `Body.kt:88` computes the pole radius `r` **once, before** the mode loop, and
> every mode reuses it (`Body.kt:93-96`) — all modes decay at an identical
> rate and only their static weight varies. `Body.kt` supplies the resonator
> *structure* and is a genuine precedent for that; it does not supply
> per-mode decay independence, which remains unbuilt. Note also its
> `gain = weight * (1f - r)` normalisation: once `r` varies per mode, that
> compensation has to vary with it.

The second difference is the tuning table. `Body.kt` uses *musical* modes —
root, fifth, third over three octaves (`Body.kt:30-32`) — because its job is
making a sampled hit ring in key. A synthesis body needs *physical* modes,
which are inharmonic:

| Body | Mode ratios |
|---|---|
| Free–free metal bar | 1 : 2.756 : 5.404 : 8.933 |
| Circular membrane | 1 : 1.593 : 2.135 : 2.295 : 2.917 |
| Church bell | 0.5 : 1 : 1.2 : 1.5 : 2 |
| Stiff string | `fₙ = n·f₀·√(1 + B·n²)` |

> **Unverified — must be sourced before Phase 1 implementation.** These four
> ratio sets were written from recall. The DSP catalog at
> `~/.claude/skills/_shared/dsp-knowledge/INDEX.md` has no modal or
> physical-modeling entry (its Bessel references are *filters*, unrelated to the
> Bessel-function zeros that set membrane modes), so nothing in the toolchain
> currently verifies them. Source them — Fletcher & Rossing, *The Physics of
> Musical Instruments*, is the standard reference — or measure them, before any
> mode table reaches code.

Those irrational ratios are the character. A 2-op FM core cannot produce them
at any macro setting, which is the precise reason the engines sound
uninteresting.

Independent per-mode t60 is the second half: when high modes die faster than
low ones a sound reads as *struck* rather than *played*. **DAMP** exposes that
tilt as a macro.

**MATERIAL answers "each voice is stuck in one lane."** Mode ratios are just
numbers, so they interpolate. Sweeping MATERIAL morphs the body continuously
from metal bar through membrane to tuned wood, passing through bodies that do
not exist physically. One macro, a large range of genuinely different sounds,
over a table that has to be built anyway.

It is not quite plain arithmetic, and Phase 1 settles this first: the ratio
sets have *different lengths* (four for a bar, five for a membrane, three for
tuned wood), so the morph is undefined at the boundaries until the bank is
given a fixed mode count with a defined mapping — padded with silent modes,
or resampled onto a common index. Choose that before any mode table is
authored, because the choice determines how the tables are written.

**Cost is the point.** A 200-mode bank is unthinkable in a realtime mobile
synth and free in an offline baker. `SYNTH_UPGRADE.md` names offline rendering
as the structural advantage; this is the first thing that actually spends it.

### MOTION

Nothing in any engine currently moves over a note's life except its amplitude
envelope, and detune ratios are fixed for a note's whole duration. MOTION is
the stage that changes, carried by **MOVE**:

- **Intra-note drift** — detune and phase move within the note rather than
  sitting static. This is also the real fix for FAT: a beat cycle that
  completes inside the buffer.
- **Per-note randomization** — seeds derived per render, so a re-render or a
  round-robin layer differs rather than repeating bit-identically.
- **Velocity → timbre** — extend `Keys.ep`'s pattern (`Keys.kt:48`, velocity
  driving FM index) across every engine. Today `Velocity.soften()` low-passes
  the *same* frozen waveform, so every dynamic layer shares one attack, one
  phase, one noise burst. This is the cheapest real dynamics available.
- **Key tracking** — `Velvet.kt:115` and `Fathom.kt:120` map cutoff to absolute
  Hz independent of note pitch, so brightness drifts across a kit's run
  instead of staying proportional.

`Grains.kt` (grain drift, per-grain pitch) and `Tape.kt:18` (wow and flutter)
are existing implementations of this stage's ideas. Defaulting TAPE's WOBBLE on
for melodic voices is a zero-new-DSP stopgap available immediately, while the
rest is built.

### DRIVE

Saturation with amplitude-dependent brightness — loud hits get brighter, not
merely louder.

`TptSvf(saturate = true)` goes to the engines that actually drive a resonant
filter, which is a smaller set than "everywhere". Tonewheel already ends in a
post-sum `tanh` (`Tonewheel.kt:121`) and would double-saturate; Vox is naive
waves through fixed-Q formants with no resonance to self-limit, so the flag
buys nothing audible there. Per-engine judgement, not a blanket policy.

Also the home of *deliberate* grit (bit/rate reduction, noise floor) replacing
the accidental aliasing that U6's oversampling removed; the engines currently
sit between clean and lo-fi, committed to neither.

### SPACE

Per-mode stereo panning. Physically motivated — a real body radiates different
modes in different directions — and mono-safe against *sum cancellation*:
summing L+R returns the modes at full amplitude with no comb notching, which a
Haas/chorus widener cannot promise.

**That guarantee is not sufficient on its own.** The FX rack is mandatory and
runs after the engine, and `Squash.kt:58-74` runs an independent envelope
follower and gain *per channel*. A hand-built per-mode stereo image can be
pumped or partially collapsed downstream by dynamics that never see it as one
image. Phase 1 owes a rack audit: which of the sixteen sections process
channels independently, and which must be linked once stereo content exists.

Per-voice opt-in. Kick and sub stay mono; hats, bells, pads, VOX and TONEWHEEL
take width. WAV size doubles only where it buys something.

### Level discipline

`Dsp.normalize` gains a loudness-matched path built on the existing
`Loudness.of()`, with a per-voice target table so a kick sits above a hat by
design, followed by a true-peak ceiling so 24-bit export cannot clip.

## Struck bodies and sustained ones

The spine's vocabulary is struck: excite a body, let it ring. Three engines are
never struck, and Phase 2 must not pretend otherwise.

- `Vox.kt` is continuous formant synthesis over a buzz source, with no
  transient to excite.
- `Fathom.kt`'s own comment describes its path as source → drive → resonant
  low-pass → amp envelope, with GLIDE "a performance gesture, not a timbre" —
  a played and glided bass.
- `Tonewheel.kt` renders a gated stab of continuously-driven drawbars.

For these, BODY is **driven rather than struck**: the excitation is continuous
into the resonator instead of impulsive, which is the same machinery used for
bowed and blown models. EXCITE becomes the sustained source rather than an
onset; MOTION carries proportionally more of the character, because a sustained
sound has a long life in which nothing currently happens. STRIKE's position
weighting still applies — it shapes which modes the drive excites.

Phase 2 treats these three as their own group with their own audition gate,
not as a rollout of the struck model.

## Macro range audit

Defect 1 is the cheapest item in this document and belongs in Phase 0. Every
macro's range is re-examined against the question *"does the extreme sound
extreme?"* — TUNE toward three or four octaves, DECAY toward 20× and beyond,
and the same for index, cutoff and drive ranges.

Range widening interacts with the mode tables (a body an octave lower is not
merely a pitched-down body) and with preset re-authoring, which is why it lands
before Phase 3 rather than alongside it.

## Phasing

| Phase | Ships | Audio changes |
|---|---|---|
| 0 | `synth` CLI render verb; macro range audit; phase randomization; detune scaled to note length; velocity→timbre; key tracking; TAPE WOBBLE default for melodic voices; `saturate=true` where a resonant filter is driven; loudness normalize on melodic engines | Yes |
| 0b | PLUCK fractional delay + oversampling retrofit | Yes |
| 1 | **`PadRecipe.VERSION` 1→2 with migration**; `Dsp.Modes` with per-mode t60; physical mode tables; MATERIAL morph; STRIKE position; MOTION stage; per-mode stereo; FX-rack stereo audit; TINES rebuilt on the spine | Yes |
| 2 | Spine across remaining struck engines, then the sustained group (VOX, FATHOM, TONEWHEEL) as a separate gate | Yes |
| 3 | STRIKE engine; presets re-authored by ear | Yes |

**Phase 0 is generalization and range work — cheap, low-risk, and the highest
impact-per-line here.** It is *not* "no new DSP": that claim was wrong in an
earlier draft, which bundled PLUCK's fractional-delay fix into it. Retrofitting
a fractional-delay interpolator and 4× oversampling onto a feedback loop whose
delay length, loop-filter cutoff and feedback gain are all rate-dependent
(`Pluck.kt:127,143-147`) is real DSP with real integration risk. It is split
out as **Phase 0b** so it cannot hold up the cheap wins.

**Phase 1 ends at an audition gate: nothing proceeds to Phase 2 until the
rebuilt TINES has been heard.** Phase 2's sustained group gets its own gate.

Phases 0–1 are the scope of the first implementation plan. Phases 2 and 3 get
their own plans, written after the audition gate — because what the audition
reveals should shape them, and writing them now would be guessing at the answer
the gate exists to ask.

### The audition path is Phase 0's first deliverable

Nothing in the CLI renders a synth voice to a WAV today — it imports only the
synth's *effects* (`Wobble`, `Treatments`, `Eras`, `TapeWear`, `Desample`).
Auditioning currently means building and running the Android app. Since every
phase gate in this document is "stop and have him hear it", a
`synth <engine> <voice> [--preset N] --out <dir>` verb is a prerequisite for
the plan, not a convenience inside it. It ships first.

### When the version bumps

**Phases 0 and 0b do not bump. Phase 1 bumps, and migrates rather than
throwing.**

> **Correction to an earlier draft.** This document previously claimed that
> leaving `VERSION` at 1 through Phase 0 would cause old kits to be "silently
> re-rendered as different sounds." That is wrong. `KitStore` carries no
> `render()`, no `Snip` and no WAV handling — it is JSON metadata over audio
> already baked to disk. **Nothing re-renders a kit on load**, so saved kits
> sound identical regardless of any DSP change here.

The risk runs the other way. `RecipeReplay.kt:85` parses recipes as
`runCatching { PadRecipe.fromJsonValue(recipe) }.getOrNull()`, so
`PadRecipe.kt:77`'s throw is swallowed. A premature bump does not protect
anything — it makes every pre-bump recipe evaluate to `null`, and breed,
replay and remix report "carries no recipe" on pads that visibly have one.
**Bumping is the silent failure, not the guard against one.**

`PadRecipe.VERSION` is a *schema* version. Phase 0 changes rendering, not
schema: same fields, same macro names. Phase 1 changes the schema for real —
STRIKE, MATERIAL, SIZE, DAMP, MOVE and WIDTH are macros a v1 recipe does not
carry — and that is where 1→2 belongs.

At that bump, `if (version != VERSION) throw` becomes a migration: v1 recipes
parse, and the new macros fill from each voice's defaults. Old pads stay
breedable. A format version that rejects rather than migrates is what made
this decision costly in the first place.

`testkit/` regeneration is independent of the version number — golden files
regenerate whenever rendered output changes, which is every phase.

The one accepted consequence: after Phase 0, re-rendering an old recipe through
breed or replay yields audio that differs from the baked WAV beside it. That
inconsistency is strictly preferable to losing the recipe, and the re-render is
the improved one.

The existing 392 presets are treated as disposable — they were authored blind
and never heard, so they have no proven value to protect.

## Sample rate

`Dsp.RATE` is a hardcoded `44_100` (`Dsp.kt:18`), which the project's own
CLAUDE.md warns against. It is *mostly* benign here: mode ratios are
dimensionless and therefore rate-independent, and `MPC_EXPORT.md` requires
44.1 kHz WAV output regardless. The exposure is the absolute-Hz constants
scattered through the engines. Thread a rate parameter where the new code
touches them; do not treat it as a blocking refactor.

## The ninth engine — STRIKE

`SYNTH_UPGRADE.md` lists "no new engines" as a non-goal. That non-goal was
written when no engine could do anything a preset could not reach; the modal
primitive changes it.

STRIKE is nothing but excitation into a modal body: voices METAL, WOOD, GLASS,
SKIN, STONE. It showcases the primitive, reaches ground the existing eight
structurally cannot, and follows the PLUCK pattern of naming a voice for its
excitation. It ships last, after the primitive has proven itself inside an
existing engine.

Naming follows `SYNTH_ROADMAP.md:27` — no trademarks, no near-misses.

## Testing

`presets within a voice do not cluster` and its siblings are deleted. Even
distribution across macro space is not a quality; it is the absence of
curation.

They are replaced by assertions on properties that correlate with *interest*:

- **Inharmonicity** — modal voices show partials off the harmonic series
- **Decay spread** — per-partial t60 variance is non-zero
- **Macro reach** — each macro's extremes differ by a stated minimum in a
  measurable dimension (pitch, centroid, length), so defect 1 cannot return.
  **The threshold table is an output of Phase 0's range audit, not an input:**
  the minimums are a by-ear judgement made while widening the ranges, and
  cannot be derived from the current code. The implementation plan should
  expect to write this test last
- **Stereo width** — a *bounded* measure, not merely correlation < 1.0, which
  is true of almost any non-degenerate stereo signal and would not catch the
  Squash collapse described under SPACE. Assert a minimum side-to-mid energy
  ratio, measured **after** the FX rack
- **Loudness** — within tolerance of the per-voice target
- **Crest factor** — inside a per-class range
- **Tuning accuracy** — every pitched voice renders within ±5 cents of its
  requested frequency across its full TUNE range. This test would have caught
  the PLUCK bug and prevents its return

Preset authoring becomes by-ear with the user in the loop. Tests guard
properties; they do not author sounds, and they no longer punish sounds for
resembling each other.

## Non-goals

- **No realtime engine.** Offline one-shot rendering is the advantage this
  document spends. It stays.
- **No patchbay.** Macros, never modular — `SYNTH_ROADMAP.md` rule 1 stands.
  Seven macros organised by the spine is not a patchbay.
- **No tenth engine.** STRIKE is the exception the modal primitive earns, not
  a new policy.
- **No stereo by default.** Per-voice opt-in; mono is correct for kick and sub
  and safer on club systems.
