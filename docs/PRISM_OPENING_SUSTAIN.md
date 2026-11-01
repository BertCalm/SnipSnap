# PRISM opening and repeating sustain

Repository baseline: `fde661771fcbb6345a76f76e65537bfd9bb7c7ce`.
Status: implemented, with focused JVM checks and Android compilation verified.
The accompanying pull request records the full release checks. The new
performance has not received a listening approval.

The characteristic gesture is **body attack → opening colour → repeating colour
and body → release when the key is lifted**.

This extends PRISM's existing instrument delivery. The source remains the saved
32-partial A/B recipe; the performance uses continuous amplitude trajectories,
oscillators and filtering. The orbital inspiration remains an analogy.

## Playing and making it

Choose PRISM, enable REPEAT in MOTION, and raise HOLD into its loop region.
MAKE INSTRUMENT then offers the complete opening performance. PREVIEW starts
at the beginning and repeats the measured sustain region. MAKE exports nine
minor-third sample roots across the selected voice's two-octave range.

The instrument keeps the complete current patch: source coefficients and ID,
carrier template, velocity, group lag, starting blend, amount and time. Release
is a separate instrument setting, defaulting to 0.6 seconds. The saved note
can be previewed even when its pitch is between the nine export roots.
Sample transposition between those roots changes playback speed and colour-cycle
rate, as with the existing sampled keygroups.

The existing pad and main-audition routes retain their settled-sustain contract.
Main AUDITION and the MOTION panel's MOVING preview repeat an actual HOLD
render until stopped. SOURCE, STATIC and OPEN previews remain finite.
The new instrument follows the established separation used by other held
instruments: a keygroup carries the opening and an explicit internal loop start.
It does not reinterpret a whole-file drum-pad loop as an internal sustain loop.

The [browser audition](https://prism-motion-snipsnap-audition.bertcalm.chatgpt.site/opening-sustain/)
plays the opening once and repeats between the saved sample markers until
RELEASE. The release gain ramps while that sustain keeps looping. Instrument
comparisons use their saved release; pure HOLD references use a labeled
0.6-second preview release. The eight-loop option releases after the opening
and eight sustain regions. OPEN keeps its rendered finite decay.

Downloaded WAVs retain their loop markers and unchanged sustain samples.
Playing one as a plain audio file can end abruptly: the repeated sustain and
note-off envelope belong to the sampler or audition player, not a fade baked
into the loop. MAKE's CANCEL and lifecycle cleanup still stop immediately.

| Control | Instrument behavior |
| --- | --- |
| Source and main macros | Retain the authored A/B colour, carrier and tone controls. |
| AMOUNT | Controls travel from the saved starting blend toward B, then back again. |
| TIME | Sets the requested one-way opening and repeating colour period; group lag and attack gates can extend the opening. The repeating period is fitted to whole carrier cycles. |
| REPEAT + HOLD | Enables MAKE INSTRUMENT for this performance. Existing OPEN remains finite. |
| RELEASE | Sets the sampler's note-off fade, from 0.1 to 1.5 seconds. It does not fade the saved loop's end. |

## One continuous performance

```text
saved patch + note + velocity
            │
            ▼
shared, pitch-fitted carrier bank
            │
body A ──one opening──▶ colour B
                         │
                    introduce group lag over one colour cycle
                         │
                  B ↔ A repeated sustain
                         │
                  settle → evidence → kept loop
                         │
       one 4× band-limit/decimation pass, common DC offset and gain
                         │
            KeyNote(audio, positive loop start)
                         │
      WAV smpl + MPC 2/3 keygroups + InstrumentStore + phone player
```

The two nearest delivery references are PRISM's existing settled loop and the
attack-preserving held keygroups used by FILAMENT and BELLOWS. Their metadata
already supports the required beginning and sustain. This is a PRISM performance
and instrument maker, with no new engine, macro bank or general pad-file format.

`Prism.openingNote` returns the existing `KeyNote` type. Its explicit loop start
runs to the sample's end. `Prism.openingProbe` exposes measured evidence without
changing the ordinary renderer. The new performance has its own
`OPENING_MODEL_VERSION`; existing patch codecs and default sound paths are retained.

Each group first follows the existing one-way smooth opening. All groups reach
their opening destination before the first repeating cycle. A cosine beginning
at B then moves toward A and back. Group phase offsets are introduced smoothly
over that first cycle, so an all-B opening does not jump into a staggered spectrum.

The group envelopes use periodic DRIFT contours under one-time attack gates.
This is powered sustain, rather than a finite note's exponential decay abruptly
replaced by a held envelope. Carrier phase is retained through the handoff.

After the opening and phase introduction, the renderer synthesizes a settling
period, an independent preceding period and the kept period. The loop marker
follows the preceding period. Those audible prefix cycles continue the sustain;
they add no second attack or inserted silence.

Both evidence and kept audio pass through the same filter history, DC correction
and scalar output gain. There is no terminal fade or PCM crossfade at the loop
boundary. Seam measurements compare the actual preceding audio against the kept
cycle. Repeating a copied export is listening evidence, not the periodicity proof.

## Packaging and replay

`PrismPadMaker.Spec` captures the full `PrismPatch` and instrument release.
The versioned `.prism-opening-keys.json` companion records both the patch and
the selected held-performance model, so replay selects the same delivery.
Changing note for an export zone replaces TUNE only.

The shared `OneNote.writePackage` writer owns the WAV sampler chunk, MPC 2
keygroup, MPC 3 keygroup and local instrument manifest. Each projection uses
the same positive loop start. The MPC 2 package opts into explicit NoteOn;
releasing a key applies the instrument release instead of playing the entire
file as a one-shot. No new native playback ABI is required.

## Cost and verification

Let O be the opening length, C the fitted colour period and L the exported
sustain period. Synthesis is bounded by `O + C + 3L + postroll`. Long TIME values
therefore cost more than a short note. The source coefficients are already
stored in the patch; making this instrument does not analyze the donor again.
MAKE renders zones serially with cancellation between zones and during synthesis.
Phone timing remains a separate device measurement.

The probes cover pitch, raw headroom, DC, near-Nyquist energy, full-cycle
recurrence, independent seam evidence, handoff steps/slopes, velocity, silence,
lag/DRIFT/register extremes and cancellation. Workflow tests cover complete
delivery replay, WAV/MPC 2/MPC 3/internal manifest agreement, prefix-once phone
playback, note-off, register maps and validation. Android tests cover eligibility,
preview boundaries, export and cancellation.

The bounded audition includes the four accepted openings, their existing pure
HOLD references, the new performances, two time settings and two selected
sources. Long comparisons contain the opening prefix once followed by eight
sustain regions. Native and gain-matched files include recipes, hashes, loop
metadata and numerical evidence.

```text
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.Prism*'
./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.Prism*'
./gradlew --no-daemon :shell:generatePrismOpeningSustainAudition
```

## Measured and verified

At implementation commit `bae77f76e`, the new DSP test class passed its five
gesture, seam, register/lag, replay/velocity and cancellation tests on JDK 17.
After correcting public API use in the audition generator, commit `6437b3e4a`
passed all 134 selected PRISM, instrument-workflow and source-convention checks,
with zero failures or skips. Debug and Strike FX APK assembly and compilation
of all four new Android UI tests passed for the same production sources.

The clean 16-case audition at `6437b3e4a` replayed every complete recipe and
sampler marker exactly. All eight new instrument cases matched their independently
synthesized preceding sustain regions sample-for-sample; measured seam error
was zero. The four accepted finite recipes and captured sample hashes matched.

The default Soft Arrival performance contains 281,625 output frames. Its one-way
opening occupies 35,281 frames, its first phase-introduction cycle 35,192 frames,
and its marked sustain starts at frame 211,241. The final 70,384 frames form the
loop, containing two 0.79800-second colour cycles. The earlier sustain cycles in
the prefix continue audibly before the sampler reaches that marker.

Warmed public renders for the eight instrument cases took 124–310 ms on this
JVM, with a median of 189 ms. These are single-run container measurements,
excluding recipe replay, probes and file writes, and are not phone timing claims.

| Claim | Status | Evidence |
| --- | --- | --- |
| Opening, bridge and sustain close without a disguised seam | Measured | New DSP tests and eight independent preceding-cycle audition comparisons. |
| Delivery recipe and sampler marker replay exactly | Verified | Production Spec round trips, workflow tests and generated WAV readback. |
| Phone player keeps the prefix once and loops only the sustain | Verified in JVM playback model | InstrumentEngine workflow test; device execution remains separate. |
| Both APK variants and new UI tests compile | Verified | Android build exit 0 at the implementation commit. |
| New performance has the preferred musical character | Awaiting listening | The owner's earlier approval covers the body-opening direction, not this new held delivery. |
| Physical MPC behavior and phone response | Unverified on hardware | Actual-device checks remain outstanding. |

## Why it is built this way

- The opening plays once so a held note keeps its initial sense of arrival.
- An explicit internal loop marker lets the sampler repeat the sustain alone.
- Shared oscillator and filter history avoids a click where the opening reaches the loop.
- Real preceding-cycle evidence checks the transition without hiding it under a crossfade.
- Reusing the instrument writer carries the same boundaries to the phone and both MPC formats.
