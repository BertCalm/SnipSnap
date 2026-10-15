# OVERLAP — first listening prototype

OVERLAP adapts the [XOmnibus synthesis guide](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/overlap_synthesis_guide.md)
as a deterministic, finite, six-delay resonator in `:synth`. A colored noise strike
excites the network; routing changes its resonant structure, six slow phases
control breathing, and the output can generate its own upper harmonics. The
[private audition](https://overlap-snipsnap-audition.bertcalm.chatgpt.site) is the
first listening gate. Numerical checks do not establish musical acceptance.

This is an audition prototype with saved `Patch`/`PadRecipe` support. It is not yet
registered in the phone engine picker or factory preset/kit roster. Six provisional
starting points live in `OverlapPresets` for the audition. Captured-snip
excitation and sustained-loop export are later work. All first-listen clips are
real JVM-engine renders; the browser only plays the resulting WAVs.

## Signal path

A seeded colored-noise hammer feeds six unequal delay lines with asymmetric
injection and pickup weights. Reads pass through a two-tap damping filter,
then `M = (1 - KNOT) I + KNOT P`, then per-line feedback. Every feedback stage
is contractive or non-expansive in the infinity norm; feedback remains below
one. No limiter, normalization or nonlinear feedback is needed for stability.

The permutations are explicit:

- SELF: six self loops.
- TREFOIL: two independent three-line cycles, 0 → 1 → 2 → 0 and 3 → 4 → 5 → 3.
- MIRROR: pairs 0 ↔ 5, 1 ↔ 4, 2 ↔ 3.
- TORUS: one six-line cycle. The name is a creative routing metaphor; no
  mathematical-knot invariant or automatic p:q harmonic lock is claimed.

Slow Kuramoto phase oscillators modulate pickup amplitude outside the feedback
path. LOCK changes their synchronization, never delay length or audio pitch.
PULSE zero disables audible modulation. GLOW follows the actual resonator output,
derives bounded even/odd harmonics and removes their DC; it is also outside the
feedback path. Synthesis and nonlinear processing run at 4× and use the shared
antialias decimator. The output is mono 44.1 kHz.

## Pitch and controls

MIDI 24–84 and velocity 0–1 are saved beside six macros. The excitation seed is
independent of topology and macros, allowing isolated comparisons. Zero velocity
is digital silence. The regular renderer uses house melodic leveling and retains
velocity dynamics; the raw audition path precedes that leveling.

| Control | First-prototype mapping |
| --- | --- |
| KNOT | Identity → selected permutation, with delay geometry moving between calibrated endpoints |
| PULSE | Zero is off; otherwise slow-to-faster pickup breathing |
| LOCK | Independent slow phases → stronger Kuramoto synchronization |
| GLOW | Dry network → output-derived upper harmonics |
| RING | Nominal low-frequency T60 of `.32 × 22^RING` seconds |
| HOLD | Finite excitation duration `.008 + 2.8 × HOLD²` seconds |

At KNOT zero, all topology selections produce identical self-resonance, with
lines tuned to the root and integer harmonics. At full depth, each complete
permutation cycle has one root-period phase transit, including fractional-delay
and damping phase. This avoids accidentally tuning a six-line cycle to root/6.
Unequal phase shares preserve different transit structures within a cycle.

Intermediate depths combine paths with different lengths. They can shift the
strongest pitch as well as color. This prototype does not promise pitch-invariant
morphing, a concert-tuned full MIDI range, or equal decay for every partial.
The tests measure endpoint tuning at C3 and C4; further registers need listening
and measurement before product integration. The nominal RING T60 also excludes
extra high-frequency loss from interpolation and damping.

HOLD always returns finite audio, including at one. It adds gated rubbing
excitation and a bounded tail; it does not export a sustained or seamless loop.

## Regeneration and first listen

```text
./gradlew :synth:test --tests com.snipsnap.synth.OverlapTest
./gradlew :synth:generateOverlapAudition
```

The task writes `testkit/overlap-audition/`, which is generated and ignored by Git.
The page source lives in `prototype/overlap-audition/`. Twelve clips cover four
topologies, two intermediate depth conditions, independent/locked breathing,
glow off/on, and two musical candidates. Every clip has raw and matched PCM24
WAVs plus a complete replayable recipe. A manifest records hashes, root estimates,
raw levels, loop gain, internal peak and phase synchronization; diagnostics retain
the phase-order history and calibrated delay lengths.

Raw files retain one common fixed engine gain. Matched files use the existing
`AuditionLevel` loudest-200-ms target `.03`, with a `.99` peak guard. No rack
effects or independently synthesized root anchor mask the resonator. Listen to
the raw comparisons as well when judging gain or decay. Source recipes regenerate
the regular engine output; the generator reproduces the raw/matched audition
variants from those same saved parameters.

The listening page starts silent, plays one clip at a time, and supports Escape
to stop. Keep/revise/pass decisions and notes stay in that browser's local
storage and can be downloaded as JSON; they are not submitted to a server.
