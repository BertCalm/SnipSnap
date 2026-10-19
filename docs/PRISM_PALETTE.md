# PRISM source-palette audition — round 2

The first listening verdict confirmed audible motion but rejected the underlying
source palette. This round chooses source character before adding further
motion variants. It uses eight existing synthesized presets in four directions:
rounded electric keys, wood and strings, breath and friction, and darker
saturated tones. They are candidates for listening, not approved sounds.

The candidate roster is:

| Direction | Source presets |
| --- | --- |
| Rounded electric keys | FORK: SOFT KEYS, MELLOW REED |
| Wood and strings | TESSERA: Wooden Arrival, Paired Wire |
| Breath and friction | EMBOUCHURE: Soft Entry, Relaxed Bow |
| Darker saturated tones | FATHOM: HOLLOW GROWL, DRIVEN SUB |

Each candidate has three stages:

1. **Original source** — its actual engine render and saved recipe, including
   the source's own articulation and engine character.
2. **Static PRISM imprint** — the source's averaged spectral colour sampled
   onto a stable harmonic carrier with a common finite PRISM articulation.
3. **Moving PRISM imprint** — the same carrier, destination coefficients,
   articulation and velocity as stage 2, travelling from a neutral harmonic
   spectrum toward the destination over 0.8 seconds.

The source and its imprint have different synthesis models and envelopes.
The static/moving pair isolates the effect of the spectral trajectory. The
original is a palette reference; it is not presented as a reconstruction target
that this static-envelope transfer can reproduce.

```text
./gradlew :synth:generatePrismPaletteAudition
```

The generator writes 24 cases with native and matched PCM16 WAVs, complete
replayable recipes and a source manifest under
`testkit/prism-palette-audition/`. Matching uses the shared loudest-200-ms RMS
target of .03 with a peak guard. Native means normal engine output before
extra audition matching. Original presets are saved without a separate FX rack;
their engines' own drive, resonances and gestures remain part of the sound.

The listening page stores stage-specific notes in a new browser namespace.
The first round and its original review namespace remain available at
`round-1/` on the hosted audition. Export notes from either round as JSON.

No PRISM synthesis or imprint algorithm changes in this round. The imprint
still borrows a fixed spectral envelope and omits source attacks, noise,
detuning and evolving material behavior. The 1024-point analyzer blurs low bass
and closely spaced partial detail. PRISM's own carrier, root protection and
spectral controls remain audible. Prefer sources that work on their own, then
judge which characteristics survive and which call for a different transfer.

These are finite samples. Held spectral motion, factory-preset expansion and
new phone controls remain outside this audition.

The [opening comparison](PRISM_OPENING.md) tries a body derived from each
destination's lower harmonics in place of the generic harmonically rich start.
