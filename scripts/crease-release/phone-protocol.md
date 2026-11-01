# Crease C4 phone release protocol

Date: 2026-11-01. Baseline: `9c10026772232018674a233d4e5108876631faf7`.
Job: Luthier REVIEW followed by the one recommended implementation direction.
Owner acceptance: Josh said "Works for me, proceed as recommended" after the
published press-and-hold study. This accepts its audible character; it does
not erase the previous three measured peak-error failures.

The characteristic gesture is **press the actual onset → sustain its motion →
release at any point → follow the cached unfolding → silence**.

## Accuracy refinement, specified before measuring

Keep Reed Mark C4, velocity .8, model 3, the actual compiled 4x Kotlin circuit,
the same public-prefix and complete-state fork checks, common gain, 221-frame
blend, carrier alignment, linear neighbour weights and Float 1.2-second fade.
Keep every original threshold: normalized RMS error <= .031623; absolute peak
error <= .025; harmonic TV <= .02; added key-up discontinuity <= .001; no
non-finite/clipped output; PCM residency <=32 MiB and delivery <=12 MiB.

The one candidate is the previous 32-position-per-period grid plus captures
every 2 ms from 0 through 280 ms. This densifies the region containing the
three old failures while preserving later captures. For the measured original
layout this gives 304 tails and 33,016,638 PCM bytes (31.49 MiB). These are
predicted size calculations, not a fidelity result.

Retain all 201 original positions. Add 100 unseen prefix and 100 unseen held
positions from a separate fixed seed 0x4e4f5631, plus 320 early positions at
(i+.37) ms, i=0..319, rounded to output frames. Publish every distinct position
and the original/holdout membership. Do not tune the capture grid to holdout
results. A failure remains a failure under the original limits.

The revised data uses a new output directory and a clean named capture commit.
Old recordings, metrics, sources and failures remain available. No synthesis
equation, accepted patch or ordinary instrument changes to improve the score.

## First phone landing

One native, single-C4 study imports an explicit study ZIP with its manifest
and lossless PCM cache. It is a listening/development preview, not a new MPC
program or a full keyboard instrument. Reuse the existing native sample audio
callback, bank handshake and command queue. Keep the cache as shared PCM16,
with no solver, allocation, blocking decode or file access in the callback.
Decode and validate dimensions, limits and hashes off the audio thread.

The phone offers the same Fade only/Unfold choice and press/release/stop
gesture. A new press replaces the previous gesture through a short fade.
Normal pads, instruments, captures, shelf imports and MPC exports keep their
existing semantics. Study import is explicit and bounded; it cannot masquerade
as a normal keygroup bank. A phone APK and study download make the result
reviewable on Josh's device; physical-device timing is not claimed here.

Before enabling the new study, compare the actual native and JVM readers to
the measured PCM transport at every validation position. Exercise 44.1/48 kHz,
early and repeated releases, retriggers, cleanup, bank swaps, stale commands,
bad manifests/archives and JNI refusal. Check callback allocation behavior.
Run the applicable native/JNI suite, focused JVM tests, complete repository JVM
gate, Android assembly and available UI checks. Report exact exit codes and
separate host/emulator observations from physical phone/MPC evidence.

## Why it is built this way

1. The accepted voicing stays fixed; extra early captures address the measured
   transient error rather than disguising it with a different sound.
2. Unseen hold positions test whether the improvement generalizes beyond the
   three failures we already know.
3. A one-key phone study proves the native transport before expanding pitch,
   velocity and cache cost into an instrument feature.
4. Shared PCM16 keeps residency bounded and simulation out of live playing.
5. A distinct study package preserves existing instrument/export contracts
   while giving the owner an installable, testable next build.
