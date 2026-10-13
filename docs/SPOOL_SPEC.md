# Spool — experimental engine engineering specification

Version 0.1 • 11 October 2026 • Working name; release naming checks pending

## 1. Product intent and reference

Spool is a tape-keyboard-inspired pitched engine whose recordings can be crossed, returned, spliced, and softly rewritten. Three virtual strips carry original string-like, choir-like, flute-like, or brass-like performances. They share a motor but have separate tension and transport state. Forward and return heads hear different journeys through the same material.

The signature is **warm recorded tone → diagonal head encounter → transport competition → backward return → quieter inherited trace**. Clean settings remain useful as a tape-keyboard tribute; high settings become an experimental recording instrument.

The reference mechanism is tape playback with spring return and contact-dependent tone/wow, documented in [Mellotron technical notes](https://www.mellotron.com/tech.htm). Diagonal heads, audible return, shared transport competition, and cross-strip rerecording are generated imaginary extensions. This is not a hardware reconstruction or a claim that an original Mellotron performs these operations.

No implementation repository was inspected. All numeric settings are prototype targets. The supplied edited Gyre spec provides SnipSnap's integration conventions.

## 2. SnipSnap contract and export semantics

Deterministic offline mono rendering, 44.1 kHz output through the standard 4× internal path, five timbral macros plus HOLD, velocity-sensitive excitation/contact, shared melodic loudness processing, patch serialization, presets, kits, auditions, and MPC-oriented export.

One render initializes a fresh machine at the requested root. Memory accumulates within that render and its preroll, not between independently exported notes. No external samples, factory tapes, live recording input, or persistent pad state are required.

For a one-shot, release occurs at a defined serialized gate time. The backward answer is baked into the generated sample. A key-release-triggered answer at playback requires a separate release-sample/playback feature; this spec does not assume it exists. External pitch transposition changes recorded gesture timing as well as pitch.

## 3. V1 architecture and state

| System | Initial budget | Function |
|---|---:|---|
| Original source performances | 3 generated mono buffers | Fresh note-specific recorded material |
| Mutable tape overlays | 3 buffers, separate from immutable originals | Accumulated inherited traces |
| Transport | One shared motor, 3 strip position/velocity states | Forward motion, loading, return |
| Return springs | 3 bounded displacement/energy states | History-dependent return journey |
| Pickup heads | One forward and one return path per strip | Position-dependent audible playback |
| Head trajectory | One longitudinal/lateral controller | Diagonal timeline/track traversal |
| Splice schedule | Up to 4 deterministic segment boundaries | Bounded reordered sections |
| Recording path | One bounded filtered cross-strip matrix | Soft rerecording |

Allocate finite buffers once per render. Start with source performances around 2–4 seconds, choosing lengths/gate times that fit the host output limit. Memory budgets must be measured from actual sample rates, buffer counts, and renderer concurrency.

## 4. Original recorded material

Generate performances using existing oscillator, additive, formant, modal, or waveguide infrastructure. String-like material uses restrained harmonic ensemble motion; choir-like material uses voiced formant color; flute-like material uses a clear root and soft airflow; brass-like material uses shaped harmonic onset.

Sources should include an attack and evolving sustained region so diagonal timeline traversal is musically meaningful. A repeated static waveform alone does not establish recorded-performance identity. Source generation is deterministic and note-specific, with pitch-range calibration.

Each patch selects a related three-strip roster, initially a dominant family plus two quieter companions. These are original synthesized recordings, not acoustic recordings or historical Mellotron tapes. Avoid implying literal realism.

The immutable base remains available for diagnostics and restoration. Mutable material is an overlay or separately maintained tape version; do not irreversibly overwrite the only original within a render.

## 5. Forward transport and shared loading

Use bounded motor speed regulation with finite torque/power limits. Each engaged strip loads the motor through tension and drag. Represent relative slip/compliance explicitly so strips can hesitate differently while sharing drive.

STRAIN increases strip-load differences, compliance/slip, and pressure-dependent speed variation. Low values retain nearly stable playback with subtle seeded imperfections. High values produce audible hesitation, stretching, and recovery. Transport motion changes sample-read coordinates; it is not merely pitch LFO applied after playback.

Use normalized recorded-time coordinates. Reference forward rate is 1 recorded second per output second for note-specific material at its calibrated pitch. At normal settings, constrain mean rate near 1 and drift to a small musical range. High STRAIN deliberately permits stronger bends, but must remain bounded and avoid unintended permanent root changes in standard melodic auditions.

Account for strip work, spring loading, motor input, and friction loss in the conceptual model. Heating or passive release cannot manufacture energy. Stable speed recovery may use powered motor work; spring return uses stored energy.

## 6. Spring return and audible answer

At the scheduled gate release, disengage forward drive smoothly. Return each strip toward its resting coordinate with a bounded damped spring. Initial displacement reflects actual tape travel. A short gate therefore produces a short return; a longer gate can produce a broader answer.

The proposed musical return law starts relatively quickly and decelerates toward rest. This is an explicit designed behavior, not a literal spring-transport measurement. Clamp maximum reverse rate and stop near the resting point using hysteresis; no indefinite jitter or repeated boundary collisions.

RETURN controls return-head gain, contact envelope, and a restrained amount of return-path detail. At zero, the strips still reset but their return is inaudible. At high values, they produce a prominent backward counterphrase. No fresh full-strength note is triggered by crossing a boundary.

The return head reads the portion actually traversed, through the same tape material used forward. It cannot reveal unrecorded future material or extend beyond valid buffers. Fade contact before speed reaches zero to avoid prolonged DC-like near-stationary reads.

## 7. HEAD: lateral and longitudinal travel

HEAD moves from aligned pickup through greater lateral blending and longitudinal displacement. Define a smooth patch trajectory for lateral weights and timeline offsets; do not merely blend three finished exports.

For each strip, derive a valid read coordinate from its transport position plus the head's bounded longitudinal offset. Cross-strip weights should be nonnegative and sum to a calibrated bounded value. The same head can meet an earlier string attack, a choir sustain, and a flute tail, but only within the generated recording's valid support.

At normal settings, retain the dominant source and its root. At high settings, allow more unusual temporal combinations. Smooth path derivatives and use matched overlap near limits rather than hard clipping read pointers. A diagnostic frozen-head render must remove the diagonal traversal while retaining transport behavior.

Phase cancellation is possible when related strips meet. Choose source phase/variation deterministically and calibrate mix levels; don't randomize unseeded phases to hide a fragile normal patch.

## 8. IMPRINT and rerecording

IMPRINT controls how much filtered return playback is written into a neighboring strip. The next forward or displaced-head encounter can hear that trace. Use a sparse cross-strip routing matrix, for example string → choir → flute → string, with an explicit nonzero causal delay.

Maintain separate previous/current tape versions or delayed reads so output cannot immediately read its own just-written sample. Define update order and interpolation at write coordinates. Write only where a simulated write head passes; don't globally blend the entire tape every audio sample.

A possible local update is `overlayNew = (1-e)*overlayOld + e*g*processedNeighbor`, where exposure `e` derives from time spent writing and `g` is bounded. Add this overlay to the immutable source through a bounded projection. IMPRINT=0 disables writes exactly.

Feedback safety must include the actual gains of routing, resampling interpolation, filtering, and rerecording. Require a strict contraction bound for the complete overlay feedback operator, not just `g < 1`. A proposed starting feedback-path bound is at most 0.55 in a documented norm; calibrate the musical range below it. If interpolation/filter kernels exceed unity in that norm, reduce write gain accordingly.

Soften inherited traces spectrally and bound write exposure, overlay level, and generation count. No instantaneous normalization of quiet tails into loud new material. Shared output leveling happens after the machine, not inside its recording feedback.

History must be inspectable: an imprint diagnostic should identify the source, destination, time, and gain. A second encounter with writes enabled should differ from the same encounter without writes. First-generation source material remains identical in both renders.

## 9. SPLICE

SPLICE introduces short reordered sections using a deterministic patch schedule. Low values preserve continuity; increasing it adds more audible segment displacement and shorter usable sections. Maximum V1 schedule: four splice boundaries within a gesture.

Implement overlapping read windows with smooth crossfades. Cuts may change timeline order but must not cause zero-time clicks, invalid coordinates, or uncontrolled gain during overlap. Keep a minimum segment duration established by probes. Do not use a free-running random glitch generator.

A splice changes which recorded section the head meets; STRAIN changes transport rate; HEAD changes spatial/timeline pickup; these controls need distinct audible roles. Apply the splice map consistently to read/write coordinates or explicitly document which path is exempt so inherited traces remain understandable.

## 10. Character, velocity, and pitch

Use restrained fixed tape coloration: bandwidth loss, contact smoothing, bounded saturation, and small seeded irregularity. Contact/tension calibration belongs to voices/patches under the revised macro set. Optional doubling becomes a strip configuration rather than an extra timbral macro.

Velocity changes initial contact/drive gesture, source articulation where supported, and relative attack energy. It must not bypass the bounds on transport or imprint. Inspect raw and normalized renders.

Forward moderate patches retain the requested perceived root. Reverse and strongly strained tails may bend or become less pitched as an intentional gesture. Evaluate pitch separately in the main forward region and the experimental return; don't mistake a deliberately slowing reverse tail for failed source tuning. Standard classifier cases still must satisfy the supplied melodic contract.

## 11. Macro set

| Macro | Low → high | Default | Neutral proposal |
|---|---|---:|---:|
| HEAD | Aligned pickup → diagonal track/time travel | .20 | .20 |
| STRAIN | Stable transport → competing loads and slip | .15 | .10 |
| RETURN | Silent reset → pronounced backward answer | .25 | .20 |
| IMPRINT | Original material → stronger inherited traces | .20 | .00 |
| SPLICE | Continuous timeline → reordered sections | .10 | .00 |
| HOLD | Finite travel/return → seamless repeating machine | 0 | 0 |

Defaults and neutral values are hypotheses. Confirm MacroSpec semantics. Required interactions: HEAD × IMPRINT, STRAIN × RETURN, RETURN × IMPRINT, HEAD × SPLICE, and STRAIN × SPLICE. All timbral controls need useful normal-voice activity; IMPRINT must be encountered within the finite render rather than written only after the audible tail ends.

## 12. Voices and presets

Proposed enum: `SpoolVoice { STRING, CHOIR, FLUTE, BRASS, PALIMPSEST, CUT }`.

| Voice | HEAD | STRAIN | RETURN | IMPRINT | SPLICE | Identity |
|---|---:|---:|---:|---:|---:|---|
| STRING | .15 | .10 | .20 | .15 | .05 | Warm recognizable recorded string |
| CHOIR | .25 | .15 | .30 | .20 | .05 | Voiced body and backward murmur |
| FLUTE | .20 | .10 | .25 | .15 | .10 | Clear airy tone with soft return |
| BRASS | .20 | .15 | .20 | .15 | .10 | Rounded defined attack and tail |
| PALIMPSEST | .65 | .40 | .65 | .70 | .25 | Material remembering its neighbors |
| CUT | .55 | .35 | .50 | .45 | .75 | Reordered recorded gestures |

Target 36 original presets: 8 strings, 8 choir, 6 flute, 6 brass, and 8 experimental. Names: Soft Frame, Returning Choir, Flute Memory, Worn Brass, Diagonal Head, Borrowed Attack, Three Springs, Ghost Copy, Folded Recording, Short Splice. Experimental voices retain recognizable material; clean voices retain restrained access to the signature controls.

## 13. Finite gesture and duration

Serialize gate duration and source length in the patch. Normal one-shots have one forward gesture and its return. To make IMPRINT audible within the same render, let selected write paths become available during return-head traversal, or schedule one quieter finite re-engagement after return. Document the chosen voice behavior; no endless replay chain.

A return-only recording after the final audible read would make IMPRINT a dead knob. Diagnostic tests must ensure a changed overlay is actually read before the render ends. Initially allow at most one re-engagement per one-shot.

Compute total duration from gate, bounded return, optional re-engagement, and acoustic/processing tail. Start around 3–7 seconds within host limits. If necessary, choose shorter gate/source/return parameters before rendering. End at the existing silence threshold or host limit with a smooth bounded termination.

## 14. HOLD strategy

HOLD uses a documented repeating forward/return transport cycle so RETURN remains meaningful. It is an imaginary cyclic machine, not literal unlimited playback of an original tape strip. Drive supplies fresh work each cycle; returns can be quieter counterphrases between forward regions.

Writing memory is the main loop challenge. First attempt bounded preroll until the contractive periodic read/write process reaches a repeatable state. Compare full overlays and transport/head/write state across cycles, not only output endpoints.

If the combined geometry/write process cannot settle reliably, create a finite imprint snapshot during deterministic preroll, then freeze writes for the exported loop. IMPRINT still controls that snapshot; the loop no longer accumulates history. This supported fallback must be disclosed in implementation/preset metadata, not described as continuous rewriting.

Make transport cycles, head trajectories, splice schedules, variation, and processing compatible with the loop period. Band-limit both forward/reverse resampling and nonlinear paths. Apply project-standard wrapping after selecting stable material; require `Keys.seamError < 1e-3` and repeated-loop listening. Check that crossfades do not double splice/return events.

A loop-only export does not provide a true performance-key release answer. Confirm attack-plus-loop/release support before adding separate regions.

## 15. Numerical, integration, and CPU requirements

Use interpolation/resampling that remains valid for both read directions, near-zero speed, and bounded high speed. Choose bandwidth based on absolute read rate; extreme reverse/forward rates require appropriate anti-alias filtering. Oversampling alone does not solve variable-rate playback artifacts.

Bound positions, velocities, work, write exposure, overlay gain, filters, and overlap counts. Prevent DC, non-finite state, denormals, zippering, buffer overrun, and silent transport stalls that consume the full render. Share immutable material where safe; never share mutable overlays between independent renders.

Use `Dsp.seedFor` or current infrastructure. Identical note/voice/macros/velocity/source/gate/machine/patch/seed must reproduce source buffers, transport traces, writes, and audio. Profile allocation, source preparation, resampling, and maximum-concurrency memory cost.

Implement `Spool.kt`, voice/patch/presets, `Patches`, `SynthKits`, `drumClassFor`, auditions, tests, and roadmap following actual interfaces. Serialize source roster, transport/gate/contact/doubling, head/splice/write routing, and HOLD snapshot mode using sibling conventions. Transient machine buffers need not be serialized.

Use shared `MELODIC_LOUDNESS_TARGET`, PadRecipe, and FxChain. Standard pitched cases avoid KICK/SNARE/CLAP/HAT/TOM classification. Reverse flicks, splices, and contact noise are risks; preserve main tonal identity and probe complete exports. Core behavior must stand without external tape FX.

## 16. Build rounds and acceptance

1. Build original material, stable forward playback, and scheduled reverse return. Probe pitch, level, rate, and duration before setting bounds.
2. Add coupled transport, diagonal head travel, and safe splices; approve moderate dry identity.
3. Add causal contractive imprint, finite audible reread, voices, and presets. Verify write-path gain mathematically and empirically before feedback-heavy auditions.
4. Complete repeatable or frozen-snapshot HOLD and integration. Run current required checks with supplied baseline `./gradlew --no-daemon test`.

Probe peak/RMS, DC, forward pitch, reverse-rate contour, spectrum, resampling aliasing, write norm/gain, convergence, source/overlay energy, cost/memory, determinism, macro distances, classifier, serialization, and seam. Audition every voice across register/velocity, five-point sweeps, interaction grids, all-high settings, short/long gates, tape endpoints, and difficult loops.

Acceptance requires useful clean string/choir/flute/brass-like presets; return dependent on actual travel; diagonal track/time encounters distinct from simple blending; transport competition tied to modeled loads; causal audible inherited traces; bounded complete write feedback; click-free deterministic splices; finite tails; patch/routing checks; and HOLD metric/listening success with its memory mode documented.

The owner approves dry sonic identity before expanding the preset library. Passing a seam or energy metric alone does not establish a compelling recorded instrument.

## 17. Open decisions and deferred scope

Confirm source-building reuse, note range, host duration/memory, velocity, attack/loop/release export support, APIs, and naming. Calibrate source performances, transport work, rate bounds, return law, head offsets, write/filter gains, and snapshot convergence.

Defer factory tape imports, literal mechanics/electronics emulation, live recording, persistent cross-pad memory, stereo, and playback-time release response without explicit host support. Spool succeeds when a familiar recorded tone becomes material that can return, cross, and quietly inherit its own history.
