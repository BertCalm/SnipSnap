# ORIGAMI velocity expression

Status: implemented; coordinated verification and listening review. The depth law is an authored shape pending this round's listening. The owner called the preceding fold-gesture round "so far pretty good"; that feedback is the baseline, not a verdict on these new samples.

The characteristic gesture is **hit strength → a chosen amount of folded color → the existing movement and settled tone → release**.

## Ground truth and landing

Repository baseline: `df675d00426486ce301c7a742b4a7691baf30af8`, read on 2026-10-28. The held-key work in #612 and fold gestures in #633 are merged. Both the installed Luthier references and the repository bundle now resolve; the local resource checker passes. This is a CONCEIVE extension of the existing engine, followed by implementation and sound-oriented verification.

Verified integration points include `OrigamiPatch.render`, `OrigamiGestureRenderer.renderRaw`, `OrigamiHeld.probe`, `Velocity.atVelocity`, `Velocity.layerAt`, `OrigamiPadMaker`, `InstrumentStore.Zone`, `KeyHit.resolve`, and the native screen's `HeldSpec.Origami`. No new patch type, synth registry arm, voice, factory preset or seventh macro is needed.

The closest verified siblings are CREASE, whose velocity changes deformation force, and PRISM, whose upper groups receive velocity-dependent excitation while the low group remains anchored. This extension uses ORIGAMI's existing source-to-relocated-spectrum relationship and its once-only gesture, rather than introducing another instrument.

## Three directions

| Direction | Gesture, distinction and cost | Decision |
| --- | --- | --- |
| COUPLE velocity to maximum FOLD | A soft hit moves less material into the existing reflected bank; a hard hit reaches the authored fold. Fixed pitch, phases and destinations; one scalar calculation per render and reuse of the current gesture cache. | Strong: directly develops the accepted gesture with the smallest change to its palette. |
| STACK a velocity-controlled low-pass after the sound | Soft hits become darker, with the original folding underneath. Cheap, but less specific to ORIGAMI and can obscure the chosen upper interval. | Alternate. |
| MUTATE the source and gesture timing with hit strength | Harder attacks could have a brighter source and faster travel. More expressive dimensions, but changes the accepted source and introduces coupled timing and held-transition decisions. | Later round if listening asks for it. |

This is a literal control law over spectral amplitude shares, with an analogy of a stronger press making a deeper fold. It is not a physical contact or paper simulation. It adds no noise, oscillator frequencies, saturation or feedback.

## Expression contract

The selector is **VELOCITY: LEVEL / FOLD**. LEVEL is the default and follows the accepted arithmetic. FOLD requires geometric model 4 and adds independently versioned expression metadata. Existing factories stay LEVEL.

```text
depthScale(v) = 0.25 + 0.75 * v^1.25, v in [0, 1]
effective FOLD = authored FOLD * depthScale(v)
```

The quarter-depth floor and exponent are shape choices, not measured contact mechanics. Full velocity bypasses multiplication and retains the original macro values exactly. FOLD zero remains the accepted source; velocity zero remains silence. The root, source phases, CREASE/TURN/WARP destinations, amplitude attack and gesture clock are unchanged.

BLOOM reaches the effective fold. UNFOLD starts there and still settles at the source. STEADY retains its existing axis movement. The effective patch clears the expression flag once before synthesis; it is never written back to presets or recipes.

| Control | Existing default (CRANE / FAN / STILL) | Contract with expression | Evidence required |
| --- | --- | --- | --- |
| TUNE | .5 / .5 / .5 | Existing C3–C5 choice or explicit authored MIDI. | Same root at soft/mid/full and complete note recall. |
| CREASE | .55 / .5 / .5 | Existing fold position, unchanged by velocity. | Raw waveform reconstruction with the same geometry. |
| FOLD | .72 / .62 / .68 | Authored maximum; hit strength scales it down, never above it. | Folded-target/root power changes after gain matching. |
| TURN | .5 / .5 / .5 | Existing destination translation. | Same target frequencies at each strike strength. |
| WARP | .5 / .5 / .5 | Existing upper-bank spacing. | Geometry remains audible and independent of hit strength. |
| MOTION | .18 / .36 / .32 | STEADY axis amount; BLOOM/UNFOLD duration. Velocity does not edit it. | Same gesture start, finish and held joining margin. |

The six accepted factory references have their own saved values; selecting FOLD does not overwrite them. Their `.85` saved velocity remains the main sample's authored ceiling. The screen's SOFT/MID/FULL preview controls apply relative strengths only to the local audible preview. Save, SEND, maker construction and navigation retain the full authored recipe.

## Signal flow and sampler behavior

```text
authored patch + actual note velocity
                  |
                  v
       one expression control calculation
                  |
                  v
accepted root + dry upper bank + reflected upper bank
       [gesture basis cache: voice, MIDI, seed, CREASE, TURN, WARP]
                  |
                  v
accepted 4x mix, decimation, DC rejection, leveling and finite end guard
             /                                      \
finite note /                                        \ held key
           v                                          v
   native velocity render               complete attack/gesture -> settled periodic body
           |                                          |
   existing SOFT HITS layerAt              soft / mid / hard complete-note peak matching
           |                                          |
           +---------- MIDI velocity selects a sample-+
                              |
                              v
                  sampler gain and key-up release
```

Finite SEND continues to deliver one reference sample and its expression recipe. Use the existing **SOFT HITS** action for per-hit pad color; enabling FOLD alone does not add pad layers. The shared native `Velocity.atVelocity` call retains its absolute-strike API. `Velocity.layerAt` uses a relative strike below the saved ceiling only for opted-in ORIGAMI. This avoids a `.60` "soft" layer being stronger than a reference authored at `.35`. Other engines and LEVEL retain their existing behavior. Existing processed-pad fallback behavior also remains: the generic FX path softens the delivered recording instead of replaying an arbitrary rack per strike.

FAN/STILL MAKE INSTRUMENT supplies three layers per pitch zone when FOLD is selected. Relative strikes `.3`, `.6`, `1` map beneath the saved velocity, with MIDI windows 1–42, 43–84, 85–127. Nine pitch zones therefore write 27 samples. Each layer retains its own attack, frame count, sustain marker and release behavior. The complete soft and middle samples are peak-matched to the hard reference, so the player applies velocity loudness once. The hard layer is unchanged. LEVEL retains the original nine samples, names and 0–127 windows.

The new maker preview uses real layer selection and plays soft, middle and hard holds with note-off release. The phone and MPC sidecars already support velocity layers; no new native mixing DSP is required. Instrument sample memory and generation/storage cost triple in the selected mode and must be reported from actual exports.

## Compatibility and cost

Recipes add `expression: { model: 1, mode: "FOLD" }` only when selected. Missing/null expression means LEVEL. Unknown modes, non-integral or unsupported versions are rejected; frozen geometric models 1–3 refuse FOLD. Gesture version 1, held version 1 and geometric model 4 stay independent and unchanged. Rendering does not alter the serialized macro map, signed 64-bit seed, note, velocity or gesture.

Expression adds one control-rate power calculation. The gesture cache excludes FOLD and velocity already, so all strike strengths reuse the same two-entry oscillator basis. Its established maximum is 16,934,400 bytes for two STILL entries. A moving STEADY patch retains its existing uncached renderer. Host cold/warm rendering and actual full-bank resident float/PCM export sizes are measured by the new audition task; no phone-latency promise follows from a JVM benchmark.

Pitch zones and their layers render serially. Cancellation remains checked in the renderer, between layers and during preview playback. All layers are rendered before package writing, preserving the existing maker's cancellation boundary.

## Probes, listening and sonic risks

- Independent audio projection must recover the intended fold-depth scaling across voices and gestures. A helper-only curve test is insufficient.
- Gain-matched spectral tests compare folded-target/root power, not brightness alone: a geometric fold can move partials downward and become darker while its folded share increases.
- Full velocity, LEVEL and zero FOLD retain exact accepted paths. Legacy JSON, R1–R4 render fixtures and held hashes remain pinned.
- Root/source identity, no extra gesture restart, held prefix/body consistency, cache reuse, output finiteness, silence and cancellation remain required.
- Real SOFT HITS pad construction verifies the saved ceiling and selected samples. Held tests read layered exports and their per-layer loops, release and key selection.
- UI checks cover the six controls, full-state recall, same-frame edits, preview-only hit state and maker routing. CLI checks cover finite replay, strict options and layered instruments.
- The audition compares soft/mid/hard clips at fixed note/seed/geometry, including full-velocity identity, C3/C4/C5 and long held notes. Whole-clip loudness matching keeps color differences available for listening without flattening each endpoint separately.

The musical risks are too little contrast at a small authored FOLD, source/fold cancellation changing perceived level, and a velocity law whose middle range feels compressed. The listening decision is whether softer hits remain useful and hard hits retain the chosen palette. No extra brightness law is inferred from the name FOLD.

## As built and verification ledger

Verified: the APIs above were read at the recorded baseline, and the implementation follows the described one-time resolution and layered-maker routes. The local Luthier bundle passes its resource check. The previous fold-gesture round was accepted as a useful baseline by the owner.

Measured on the JVM host: the 33-clip study completed in 40.93 seconds and verified exact raw, conditioned, matched and WAV identity between LEVEL and full-strength FOLD for all three voices. Its real FAN/STILL exports contain 54 PCM24 WAVs across 18 pitch zones: 54,549,360 WAV bytes, 72,724,416 decoded float bytes, and 36,368,256 measured PCM16-equivalent bytes. These totals exclude object overhead, temporary render buffers and the geometry cache. FAN accounts for 17,414,016 resident float bytes and STILL for 55,310,400. This is the three-layer mode's actual storage cost, not a phone memory or latency promise.

Post-matrix cache-cold C4 BLOOM renders measured 388.60 / 103.30 / 95.85 ms for CRANE / FAN / STILL; the respective warm medians were 18.36 / 18.82 / 28.32 ms. Velocity/FOLD/MOTION edits reused one cache entry per geometry, with medians 19.61 / 18.47 / 25.94 ms. The JVM was already running and other verification shared the host; these are descriptive timings, not a benchmark guarantee. The 12-second held takes cross three FAN loop wraps and one STILL wrap before note-off, covering only 0.386 seconds beyond STILL's first wrap.

The initial coordinated checks passed 67 synth, 10 CLI and 23 workflow tests, plus Android application/test APK assembly. The standalone host native suite passed all 180 cases in four targets. Local Chromium checks passed nine embedded starter playbacks and all 66 native/matched playback starts, filtering, transport, error handling and 390-pixel touch layout. A representative held take played linearly through its wrap and release. Local WAV seeking was unavailable, so seeking is not claimed. The study is published as Sites version 7 at https://origami-snipsnap-audition.bertcalm.chatgpt.site/expression/ with the existing owner-private audience; native Sites APIs confirm the saved source and deployment. Browser QA used local generated/staged files; unauthenticated production HTTP access was denied by the private gate.

Local API31 software-emulator UI checks are incomplete with failures. The full 245-test attempt had eight passes and an APSIS delivery timeout before interruption. The focused 11-test ORIGAMI attempt had two passes, a legacy delivery timeout and eight incomplete cases, ending in a focus-event ANR. The two ORIGAMI passes cover control/expression routing and the existing FAN release sheet. No main-thread ANR trace identifies the stalled code; the new maker, persistence and preview cases therefore require accelerated head CI. Test timeouts and test source were not relaxed for the local failures. Final full-JVM and subsequent accelerated device results are recorded in the PR and handoff. No measurement is a listening verdict.

Proposed: the `.25` floor, `1.25` exponent and three-layer sampling policy are authored choices for this audition. Heard: awaiting owner feedback on this expression round.

## Why it is built this way

- Hit strength changes the chosen fold's share, keeping the source colors and pitch the owner already preferred.
- Timing stays independent so playing harder does not unexpectedly hurry BLOOM or hide STILL's movement beneath its attack.
- Full velocity and LEVEL keep the accepted arithmetic, so a saved sound does not change merely because the feature exists.
- Softer samples sit beneath the authored main velocity and leave playback loudness to the sampler, preventing inverted color steps or duplicate attenuation.
- Real velocity layers make the held instrument respond under the fingers; preview buttons and matched clips make that response inspectable before export.
