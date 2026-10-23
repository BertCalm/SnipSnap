# Phased plan and open decisions

Status: staged sequence. R0 is complete; the owner subsequently authorized R1. The [As built ledger](r1-implementation-plan.md#as-built-r1) records actual desktop validation and remaining phone/listening gates. Later stages are proposals, not instructions to implement them now.

## R0: repository verification and engineering decisions

Inspect instructions, module dependencies, BODY call sites, modal toolkit users, rack ordering/serialization, pitch detection, source resynthesis, existing tests, and audition generators. Record commit and exact declarations. Check current UI and kit integration separately from DSP feasibility.

Resolve these questions in the review:

1. **Placement:** new rack section, extended capture BODY operation, or shared processor with adapters? Prefer shared behavior, but do not introduce a cyclic `:audio`/`:synth` dependency or widen internal APIs casually.
2. **Compatibility:** retain legacy BODY and serialized rack behavior exactly, or define a separately versioned model? Default proposal: opt-in addition, existing behavior unchanged.
3. **Models:** select two source-backed modal families that differ audibly and structurally. Describe ratios, gains, decay law, root/reference definition, and provenance. The owner's manuals describe behavior but are not a source for undisclosed filter tables.
4. **Morphing:** crossfade two running banks, or interpolate paired modal parameters? Crossfade is a simpler starting point but doubles bank work and is not physical interpolation. Parameter morphing needs mode correspondence, smooth state evolution, and stability tests.
5. **Gain:** define sustained-input gain behavior and peak headroom before reuse. The current strike-oriented modal implementation does not automatically provide safe relative levels for sustained resonant inputs.
6. **Channels:** mono-first proof versus per-channel stereo handling. Never silently downmix a stereo rack input. Avoid adding stereo width to the initial proof.
7. **Tail:** work within the current rack cap or choose a separate long-render operation. If policy changes, enumerate effects on sample length, classification, serialization, preview, and export.
8. **Timing:** the engine render contract does not supply tempo. Express initial motion in seconds/Hz or pass an explicit existing kit tempo through a verified adapter; never imply automatic host sync.
9. **Performance:** select a target Android device, build mode, source duration, and warm/cold measurement method. Proposed starting target: a warm rerender of a one-second mono source in at most 50 ms on that named device. This is a negotiation target, not achieved performance. Report actual latency and peak allocation.
10. **Scope:** determine whether the conventional parallel branch belongs in R1 or follows a bank-only proof. Document any omission from the reference topology.

## R1: smallest body-processing proof, after approval

The [R0 review](repository-review.md) selects the detailed [R1 implementation plan](r1-implementation-plan.md): bounded fixed-table resonance, two four-mode families, a static crossfade of independent banks, decay/damping, dry/wet, explicit sample-rate/channel policy, and exact bypass. Four modes per family are the proposed first proof; 16–32 modes remain a possible later expansion after listening and timing evidence. The conventional parallel filter is deferred. Add root preservation only where the table is intended to be pitched; do not promise clear pitch for every inharmonic body.

Suggested controls for planning only:

| Control | Proposed default | Quantity | Interaction / proof |
|---|---|---|---|
| TUNE | Center / documented root | Resonant reference frequency | TUNE × BODY: verify root or reference-mode location across the morph. |
| BODY | First model | Static output crossfade between two fixed banks in R1 | BODY × DAMP: compare spectral peaks after loudness matching. |
| RING | Short, documented T60 | Per-mode decay duration | RING × DAMP: high/low band decay tests. |
| DAMP | Midpoint | Frequency-dependent decay shortening | Distinguish from simple high-frequency attenuation. |
| MIX | 0.5 | Dry/wet gain law | MIX × RING: attack retention and tail budget; MIX=0 bypass. |

Codex must replace qualitative defaults with explicit normalized values and units in its plan, justified as initial hypotheses. Add branch blend only if the conventional filter branch is included; stay within the small macro budget.

Identify immutable tables by model version, family pair, mode count, and all table-defining parameters. Every coefficient-cache key must include that table identity, coefficient-algorithm version, sample rate, root, decay, and damping. Include morph only when it changes coefficients; R1's output crossfade does not. The detailed R1 plan defines bounded cache ownership and invalidation tests. Never cache mutable resonator state across independent renders. Avoid transcendental coefficient computation in the per-sample loop. Cost is O(frames × active modes × channels), doubled when crossfading two full banks. Measure at the intended internal rate; do not assume offline means free.

Deliver tests plus an audition set with original, wet, mixed, and level-matched examples. No large preset bank before the owner listens. Use named sourced or generated inputs with redistribution permission; the manuals provide no demo audio to reuse.

## R2: motion and integration

Add deterministic envelope-driven body motion only after the static morph works. Decide interpolation strategy from R1 evidence; test rapid motion and ringing-state transitions. Integrate the verified processing entry point and serialization. Propose UI changes only with a verifiable app environment. Add longer tails or loop behavior only through an explicit policy and independent seam test. Native stereo widening remains optional.

## R3: source transformation proof

Separate this from body DSP. Start with voiced monophonic material and known-pitch generated fixtures. Build/cache a pitch-and-confidence trajectory with cycle marks; key it by source content hash, sample rate, analysis version, detection range, and analysis settings. Reuse analysis when only output pitch/formant controls change. Do not assume the existing single-note detector is a robust framewise tracker.

Compare candidate time-domain pitch-synchronous overlap-add and spectral-envelope approaches on the same fixtures before committing. Specify handling of onset transients, unvoiced segments, octave errors, voiced/unvoiced transitions, duration, and end boundaries. Preserve consonants with a documented fallback and crossfade; do not force cycle processing onto low-confidence audio.

First prove independent pitch/formant behavior. Then add harmonic translation and cycle alternation as separate ablation-testable stages. An ordinary ring modulator is not an equivalent substitute for single-sideband frequency translation. Define sideband/image rejection, negative-frequency behavior, alias prevention, and neutral bypass. Grains need an antialiased resampler when playback ratios demand it.

Defer live MIDI, polyphonic source analysis, and four-voice harmonization. A later offline chord renderer is a distinct scope. Source motion and body motion should use a single documented render timeline.

## R4: combined recipes after listening

Combine the two stages in fixed order with deliberate gain staging and latency alignment. Test a spoken syllable, dry tap, bass note, and sustained vowel. These are candidate use cases, not promised timbres. Choose a small preset set from successful auditions. A new engine wrapper is justified only if a distinct gesture cannot be delivered through the processors.

## Stop conditions

The present task ends with review and implementation plan. Later rounds stop for a concrete owner audition when sound character determines the next choice. Any proposed thresholds remain proposals until approved and measured. No generated inference should be recorded as a test result.
