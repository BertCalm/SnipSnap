# Synth depth — from primitives to bodies

`docs/SYNTH_UPGRADE.md` closed with a non-goal: *"Not competing with desktop
synths on depth."* This document reverses that decision, and explains what
replaces it.

The engines sound thin, cheap and uninteresting when auditioned a pad at a
time. That complaint is three separate defects plus one methodology failure,
and only one of the four needs new DSP.

## The governing finding

Every technique that would fix this is already in the codebase — implemented
correctly, exactly once, and never generalized.

| Technique | Implemented at | Missing from |
|---|---|---|
| Filter saturation | `Velvet.kt:144` | the other seven engines |
| Loudness-matched level | `Punch.kt:147` ← `Thump.kt:132` | all five melodic engines |
| Velocity → timbre | `Keys.kt:48` (FM index) | everywhere else |
| 4× oversampling | eight engines | `Pluck.kt:96` |
| **Modal resonator bank** | **`audio/Body.kt`** | **all of `:synth`** |

This is not eight DSP projects. It is one discipline problem: proofs of concept
that never became policy. A large share of the complaint is recoverable by
promoting each technique from one site to all sites.

## The four defects

**1. Thin — nothing is in stereo.** Every engine returns
`Snip(..., channels = 1)`. There is no `channels = 2` anywhere in the
repository. The FX rack has sixteen sections and none widen; three
(`Phase.kt:61`, `Ring.kt:49`, `Vinyl.kt:86`) explicitly document that a stereo
pair is processed together, so even if stereo existed nothing would
decorrelate it.

**2. Cheap — every pad hits with identical weight.** `Dsp.normalize(target =
0.95f)` is the final stage for Velvet, Fathom, Vox, Tonewheel and Pluck: peak
only. THUMP alone is loudness-matched. A sine-heavy patch has a far higher
crest factor than a saw or noise patch, so at equal peak it is meaningfully
quieter in perception — and `SynthKits.melodic()` puts these on the same grid
as the loudness-matched drums, so tonal pads sit under the kit by construction.

**3. Uninteresting — every note is one static gesture.** `Tines.strike()` is a
single function generating all five TINES voices. One excitation, one amplitude
envelope, one decay rate. Nothing in a note disagrees with itself. There is no
resonator anywhere in `:synth` except PLUCK's Karplus-Strong loop.

**4. The presets were never heard.** Six preset files carry the same admission
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

A voice stops being one fused formula and becomes four stages.

```
EXCITE  ──→  BODY  ──→  DRIVE  ──→  SPACE
transient    modal       nonlinear   stereo
burst        resonator   saturation  decorrelation
```

### EXCITE

The onset, synthesized separately from the tone: noise burst, impulse, scrape,
or an existing engine's oscillator. Carries its own short envelope. This gives
every engine a second spectral layer independent of the body — the thing that
currently does not exist anywhere.

### BODY — `Dsp.Modes`

A bank of N two-pole resonators, each with its own frequency ratio, gain and
**independent t60**. Generalized from `audio/Body.kt`, which already implements
exactly this structure.

The difference is the tuning table. `Body.kt` uses *musical* modes — root,
fifth, third over three octaves (`Body.kt:30-32`) — because its job is making a
sampled hit ring in key. A synthesis body needs *physical* modes, which are
inharmonic:

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
> currently verifies them. Shipping them unchecked would reproduce, at the
> centre of this document, precisely the blind-authoring failure the document
> exists to correct. Source them — Fletcher & Rossing, *The Physics of Musical
> Instruments*, is the standard reference — or measure them, before any mode
> table reaches code.

Those irrational ratios are the character. A 2-op FM core cannot produce them
at any macro setting, which is the precise reason the engines sound
uninteresting.

Independent per-mode t60 is the second half: when high modes die faster than
low ones a sound reads as *struck* rather than *played*.

**Cost is the point.** A 200-mode bank is unthinkable in a realtime mobile
synth and free in an offline baker. `SYNTH_UPGRADE.md` names offline rendering
as the structural advantage; this is the first thing that actually spends it.

### DRIVE

Saturation with amplitude-dependent brightness — loud hits get brighter, not
merely louder.

`TptSvf(saturate = true)` goes to the engines that actually drive a resonant
filter, which is a smaller set than "everywhere". Tonewheel already ends in a
post-sum `tanh` (`Tonewheel.kt:121`) and would double-saturate; Vox is naive
waves through fixed-Q formants with no resonance to self-limit, so the flag
buys nothing audible there. Per-engine judgement, not a blanket policy.

Also the home of
*deliberate* grit (bit/rate reduction, noise floor) replacing the accidental
aliasing that U6's oversampling removed; the engines currently sit between
clean and lo-fi, committed to neither.

### SPACE

Per-mode stereo panning. Physically motivated — a real body radiates different
modes in different directions — and **mono-safe by construction**: summing L+R
returns the modes at full amplitude with no comb cancellation, which a
Haas/chorus widener cannot promise. Non-modal sources get width from
decorrelated excitation noise.

Per-voice opt-in. Kick and sub stay mono; hats, bells, pads, VOX and TONEWHEEL
take width. WAV size doubles only where it buys something.

### Level discipline

`Dsp.normalize` gains a loudness-matched path built on the existing
`Loudness.of()`, with a per-voice target table so a kick sits above a hat by
design, followed by a true-peak ceiling so 24-bit export cannot clip.

## Phasing

| Phase | Ships | Audio changes |
|---|---|---|
| 0 | **`synth` CLI render verb** (the audition path); phase randomization; detune scaled to note length; PLUCK fractional delay + oversampling; `saturate=true` where a resonant filter is driven; loudness normalize on melodic engines | Yes |
| 1 | `Dsp.Modes` + physical mode tables; per-mode stereo; TINES rebuilt on the spine | Yes |
| 2 | Spine rolled across the remaining engines | Yes |
| 3 | STRIKE (ninth engine); presets re-authored by ear | Yes |

Phase 0 is bug fixes and generalization — no new DSP, and the highest
impact-per-line in the document. **Phase 1 ends at an audition gate: nothing
proceeds to Phase 2 until the rebuilt TINES has been heard.**

Phases 0 and 1 are the scope of the first implementation plan. Phases 2 and 3
get their own plans, written after the audition gate — because what the
audition reveals should shape them, and writing them now would be guessing at
the answer to the question the gate exists to ask.

### The audition path is Phase 0's first deliverable

Nothing in the CLI renders a synth voice to a WAV today — it imports only the
synth's *effects* (`Wobble`, `Treatments`, `Eras`, `TapeWear`, `Desample`).
Auditioning currently means building and running the Android app. Since every
phase gate in this document is "stop and have him hear it", a
`synth <engine> <voice> [--preset N] --out <dir>` verb is a prerequisite for
the plan, not a convenience inside it. It ships first.

### When the version bumps

`PadRecipe.VERSION` bumps on **the first phase that reaches users after a
rendered-audio change** — not once at the end. Phase 0 changes rendered audio,
so if Phase 0 ships, Phase 0 carries the bump and regenerates `testkit/`.

This matters because `PadRecipe.kt:77` throws only on a version *mismatch*: if
Phase 0 lands while `VERSION` stays at 1, old kits are accepted and silently
re-rendered as different sounds. A silent change to saved user work is the one
failure mode here that is not a matter of taste.

Phases that land between releases accumulate under the same version number.

If Phases 0–3 all land before a release, they share one 1→2 bump and one
regeneration of the 369 `testkit/` WAVs. If a release falls between them, each
released phase bumps again — the rule above governs, not the convenience of a
single bump.

The existing 392 presets are treated as disposable — they were authored blind
and never heard, so they have no proven value to protect.

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
- **Stereo correlation** — below 1.0 for voices that opted into width
- **Loudness** — within tolerance of the per-voice target
- **Crest factor** — inside a per-class range
- **Tuning accuracy** — every pitched voice renders within ±5 cents of its
  requested frequency across its full TUNE range. This test would have caught
  the PLUCK bug and prevents its return.

Preset authoring becomes by-ear with the user in the loop. Tests guard
properties; they do not author sounds, and they no longer punish sounds for
resembling each other.

## Non-goals

- **No realtime engine.** Offline one-shot rendering is the advantage this
  document spends. It stays.
- **No patchbay.** Macros, never modular — `SYNTH_ROADMAP.md` rule 1 stands.
- **No tenth engine.** STRIKE is the exception the modal primitive earns, not
  a new policy.
- **No stereo by default.** Per-voice opt-in; mono is correct for kick and sub
  and safer on club systems.
