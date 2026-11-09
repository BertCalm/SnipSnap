# Velour Kit: engineering specification and R0 audition

Version 0.1, 2026-11-08. Status: proposed integration, Python audition rendered; owner listening pending.
Repository truth: `7ccdbe713f3ec502e2978674c1820cf15d623770`.

The characteristic gesture is **muffled drum hit → scattered echoes of the hit → soft ambient bloom → gritty speaker decay**.

## 1. Intent and scope

A damped acoustic-style kit whose selected sends become short sample fragments and dark ambience before a shared amp. The dry transient remains identifiable. The initial source pair is kick and snare; a closed hat exists only to establish the audition groove. No new pitched engine is required. Product name is Velour, with no maker names in voice or preset identifiers.

The reference is the user's screen recording of a mic'd acoustic kit, visibly damped, captioned “Vintage Soul Drum Tones.” The file contains mono 44.1 kHz audio. Its audio was not auditioned by the agent. The prototype uses synthetic sources only and makes no measured timbral-match claim. The 84 BPM two-bar rhythm is an original demonstration, not a transcription or tempo estimate.

R0 is a Python sound-design experiment. R1 should reuse SKIN source voices and introduce a compound processing board if the audition justifies it. A rendered phrase demonstrates shared memory and sag; individually treated pads cannot reproduce that shared interaction when played together.

## 2. Reference mechanisms and trade

Research reference: Walrus Audio Qi Etherealizer. Official manual: https://cdn.shopify.com/s/files/1/0906/8480/files/Qi_Digital_Manual_7_14_26.pdf?v=1784317029 . Its parallel paths, onset-based fragment recall, wet-only space, and tone shaping inform the proposal. R0 retains a parallel dry path and restrained fragment recall; stereo imaging, live freeze, all playback modes, tap control and exact proprietary algorithms are excluded.

Amp reference: generic small combo behavior, not a named circuit. Pre-filtering, asymmetric saturation, envelope-dependent gain reduction and speaker filtering are literal DSP operations. They are behavioral analogies for tube stages, supply sag and a cabinet; no circuit solution or measured cabinet IR is claimed.

Muted membrane source: decaying partials and filtered noise are computed literally; cloth contact and microphone geometry are not simulated. All source frequencies, decay constants, gains and effect settings below are **shape values**, awaiting owner listening.

## 3. Siblings and landing

SKIN and ONSET already cover the source roles. SKIN supplies modal drums; ONSET blends physical and electronic sources. Neither alone supplies shared grain recall followed by amp compression. GRAINS accepts source audio but scans it into a normalized cloud rather than preserving a dry attack with onset-directed recall.

Preferred landing: a reusable compound board plus a kit recipe, not a new engine registration. `BoardRecipe.kt` already dispatches WIDE, RUSH and OBLITERATE. A proposed VELOUR board can keep its own explicit internal routing and settings. This is a future format addition, not an API that exists today.

The ordinary `FxChain` remains fixed with VALVE ahead of ENSEMBLE/ECHO/ROOM. `PedalBoard` offers PRE/AMP/POST passes, but the current DELAY and ROOM entries are POST-only. The one-second rack/board tail cap cannot be silently bypassed by padding inputs. Velour must explicitly own a documented render duration and fade in its compound-board implementation, with compatibility tests and metadata.

## 4. Directions considered

* STACK, selected: muted kit → parallel atmosphere → amp. High gesture clarity; medium novelty; medium integration risk; two implementation rounds after audition.
* JUXTAPOSE: dry drums plus an independently amplified cloud. High clarity; lower novelty; low sonic risk; two rounds. Keeps more punch but weakens whole-kit compression.
* INVERT: the frozen drum cloud becomes the instrument. Medium clarity; high novelty; high sustain/seam risk; at least three rounds. Moves away from the requested drum setup.

## 5. Signal flow and separation

```text
voice + velocity + TUNE/DAMP
    → source @176.4 kHz → low-pass → decimate → DC cleanup @44.1 kHz
    → dry mix ---------------------------------------------------------+
    → per-voice send → [chorus | finite echoes | onset grains]          |
                         → wet-only finite room → tone LP → wet level --+
                                      → amp upsample @176.4 kHz
                                      → pre-EQ → asymmetric soft clip
                                      → envelope sag → cabinet
                                      → decimate @44.1 kHz → DC/fade
                                      → delivery gain → PCM WAV
```

Dry and send remain distinct buffers. Only snare onsets trigger grain events in the demonstration. The supplied onset list is synthesis-event metadata, not an audio transient detector. An arbitrary captured input needs a separate detector with documented threshold/refractory behavior before this board can support it.

Mono 44.1 kHz output for R0. Source and amp nonlinearities use 4x internal processing. Linear atmosphere runs at 44.1 kHz. The current rack can preserve stereo, but R0 does not exercise it.

## 6. Source and energy model

R0 source is a Python stand-in, not a port or output of `Skin.render`:

* Kick: 57 Hz default root, small 15 Hz exponentially falling pitch offset with 12 ms time constant, short secondary membrane partial and filtered contact noise.
* Snare: 178 Hz root, ratios 1, 1.59, 2.14, 2.30, individually decaying amplitudes, band-shaped wire noise and brief contact noise.
* Hat: filtered noise with short decay, only a groove scaffold.
* Damping multiplier `1.5 - 1.15*DAMP` scales source T60 values. Envelope `exp(-ln(1000)*t/T60)`.
* Velocity scales excitation and changes onset rise time; snare wire bandwidth also changes. Do not normalize individual hits after velocity is applied.

Sources are band-limited before decimation. R0 uses the skill's approximate decimation taps. It deliberately omits per-source RMS leveling to retain velocity and source balance. Production must respect the drum engine's own output contract rather than automatically applying the melodic target to every hit.

## 7. Atmosphere model and bounds

Chorus: three interpolated delay reads, 14 ms base, 1.4 ms excursion, 0.32 Hz modulation with phase offsets. Their average has bounded gain. This is mono motion, not a stereo-width claim.

Echo: four finite eighth-note repeats at the explicitly chosen 84 BPM, with gain sequence `1, .32, .32^2, .32^3` and a .16 send coefficient. Absolute gain sum is finite. No feedback loop exists in R0. Production phrase rendering receives BPM from recipe metadata; a per-hit board must store delay milliseconds or an explicit tempo. Do not assume source render receives kit BPM.

Grains: up to eight candidates per snare onset; deterministic acceptance probability `.06 + .84*SCATTER`. First onset offset 140 ms, candidate separation 125 ms, bounded jitter 0–25 ms. Durations 75–125 ms, normal or half-speed reads, Hann windows, per-event gain `.55*.82^j`. Overlap divides by `max(1, sum of windows)`; adding events cannot multiply peaks without bound. Candidate RNG draws remain fixed when a density control changes. No reverse or octave-up mode in R0.

Room: seeded low-pass noise impulse with 22 ms predelay and exponential decay. T60 law `.45 + 2.4*SPACE` seconds, four-second finite support. Normalize impulse L1 norm, then apply fixed gain 12. This is a cheap dense FIR room surrogate, not a hall-model accuracy claim. It has a finite convolution gain bound and no feedback. Cache the impulse by SPACE, rate, model version and seed.

Wet branches mix at chorus .14, echo .16, grains .65; room gain `.3 + 1.7*SPACE`. The combined wet return is low-passed at 4.8 kHz and scaled by `.55 + 1.3*SPACE`. Source send levels: kick .12, snare 1, ghost snare .7, hat .25. Fixed coefficients are shape values, not calibrated pedal knob positions.

## 8. Amp and output

R0 upsample uses SciPy polyphase FIR at 4x. Pre-high-pass 48 Hz. Drive gain `1 + 7*DRIVE`. Positive half `tanh(z)`; negative half `.85*tanh(z/.85)`. A 35 ms one-pole follower of absolute tube output drives `y = tube/(1 + 1.25*DRIVE*env)`. The denominator is at least one; sag cannot add energy. Cabinet uses 55 Hz high-pass and fourth-order 5.3 kHz low-pass. Decimate, remove mean and high-pass at 20 Hz, apply 12 ms end fade.

This is not the shipped VALVE law. VALVE currently peak-normalizes its input and peak-matches its output, whereas this prototype uses fixed calibration so input velocity affects breakup. R1 must compare the existing VALVE on the entire bus against a fixed-calibration option before changing any existing contract. Never change existing presets or goldens silently.

R0 comparison files are matched by whole-file RMS within each equal-duration group, with a common target reduced until every file stays below .92 peak. This is an audition presentation gain only. It is not the Kotlin `Loudness.of` metric and is not a production per-event normalization rule. No hard limiter is used to conceal unstable internal behavior.

## 9. Macro contract

| Control | Default | Quantity | Interaction | Required test |
|---|---:|---|---|---|
| TUNE | .50 | Source pitch ±6 semitones | DAMP affects pitch measurability | Source modal frequency follows map; hats exempt from root detection |
| DAMP | .70 | Source T60 multiplier | SPACE separates short attack and long shadow | Source late-energy decreases; compare low/mid/high |
| SCATTER | .25 | Candidate acceptance density | DRIVE compresses overlapping fragments | Candidate set grows deterministically; overlap bound holds |
| SPACE | .30 | FIR T60 and wet return gains | DAMP and SCATTER | IR decay increases, late energy changes |
| DRIVE | .35 | Tube gain and sag strength | SCATTER raises bus complexity | Harmonics change at fixed source level; velocity remains meaningful |
| HOLD | Off | Reserved | None in R0 | Refuse enabled HOLD, never silently pretend a tail is seamless |

R0 exposes five numeric parameters in source, not a phone UI. Finite 0..1 validation and unknown-key handling are required in R1; the Python audition uses authored values only. Top-level defaults are not final presets. The two initial source roles are kick and snare; add toms, stick, open hats and velocity takes only after audition.

## 10. Render budget and cache plan

Target for R1: p95 under 50 ms for a cached short-source edit on the chosen target phone; under 150 ms for a four-second wet-hit edit. These are proposed gates, not measurements. Long phrase renders are explicit exports with progress, not knob-triggered synchronous UI work.

Source cache key: voice, TUNE, DAMP, velocity, take, source model version, sample rate. Atmosphere key: source/send hash, event list, SCATTER, SPACE, BPM or delay time, seed, model version. Amp key adds DRIVE and calibration version. A DRIVE edit reuses the atmosphere; a SPACE edit reuses dry sources and grain schedule. Cache FFT of the room impulse and overlap-save partitions if measured convolution cost requires it. No numerical solver exists. Bounds: eight candidate grains per onset, four echoes, fixed four-second impulse; phrase event count and duration must be validated.

## 11. Integration checklist for R1, not implemented

Verified existing files and roles:

| Existing surface | Proposed action |
|---|---|
| `Skin.kt`, `SkinPatch.kt`, `SkinKits.kt` | Reuse source patch and kit patterns; compare source pair against R0 before substituting |
| `Onset.kt` | Optional alternate source; no new drum engine registration |
| `Grains.kt` | Study window/read helper; do not call its normalized whole-cloud render as an equivalent onset-grain branch |
| `BoardRecipe.kt` | Add versioned VELOUR settings/dispatch after format review; preserve exact old decode/render behavior |
| `PadRecipe.kt` | Verify compound-board serialization and re-render; no untyped hidden recipe state |
| `Valve.kt` | Audition whole-bus reuse; keep old peak-relative contract unless separately versioned |
| `FxChain.kt`, `PedalBoard.kt`, `Pedals.kt` | Preserve fixed order, placement permissions and existing tail cap |
| `Dsp.kt`, `Tide.kt` | Use verified seed, output-rate and oversampling helpers for Kotlin |
| `synth/src/test/kotlin/com/snipsnap/synth/` | Determinism, zero-input, bounds, macro activity, JSON, old-recipe compatibility and audition generator |

R1 adds a board and kit, so engine `Patches`/velocity exhaustive arms and a fictitious new `drumClassFor` are not required. Preserve dry drum classes. Long atmospheric samples must follow the current classifier/export contract; do not relabel every long tail as a dry SNARE without checking it. App UI, XPM packaging, phone performance and MPC hardware loading remain later gates. Run `./gradlew --no-daemon test` for Kotlin integration and judge exit status. No Kotlin changes or Gradle test claims in R0.

## 12. Probes, risks and build gates

R0 checks: deterministic source and full chain; exact silence; finite samples; peak and DC bounds; every macro's extremes after RMS leveling; 4x amp vs 8x reference. Generic monotonic-envelope checks are inappropriate for phrases with new hits and deliberately scheduled late grains. Stability instead follows finite operator bounds and positive sag denominator. A 4x/8x difference includes filter differences and must not be described as isolated alias energy.

R1 required: low/mid/high macro activity, DAMP×SPACE and SCATTER×DRIVE corners, bypass identity, seed variation, malformed settings, long-tail truncation before terminal fade, source root tests only where meaningful, alias comparison on steady probes, velocity response at fixed calibration, full phrase vs independent-pad comparison. HOLD is refused until a real continuation seam test exists.

Sonic risks: (1) the source may lack the reference's microphone/body character; compare dry first; (2) dense grains may obscure the backbeat; lower density before shortening all ambience; (3) cabinet filtering can thin the kick; compare amp bypass and bass energy; (4) increased drive may make noise tails hiss; inspect late spectrum and listen; (5) RMS matching is imperfect perceptual matching; owner must compare at comfortable level; (6) finite room may sound diffuse rather than spacious; replace only after the source/amp order is accepted.

Build gates: R0 this audition; R1 source matching plus versioned board implementation and tests; R2 velocity takes and full kit after listening; R3 sustained/freeze textures only if requested and seam validated. Do not author a large preset roster before source acceptance.

## 13. R0 evidence ledger and as rendered

Verified: APIs above grepped/read on the named commit; skill resource checker passed. The source and amp Python implementations are approximations, not bit-identical Kotlin ports. The skill's template, snipkit and probes were used as the harness foundation.

Measured in Python on this cloud executor: deterministic source and chain passed; silence preserved. All delivered files passed finite, peak < .93 and |DC| < .005 checks. Default groove emitted 11 grains and used 1.17 s IR T60. Macro extreme log-spectral distances: TUNE 2.897 dB, DAMP 12.712, SCATTER 1.940, SPACE 2.775, DRIVE 4.349. These distances prove activity, not audible quality.

Measured runtime: four-second kick with all stages 260.0 ms; snare 277.9 ms; two-bar default and expanded-bloom processing including source generation 5256.0 ms. No phone claim. Amp 4x versus 8x relative RMS error on the chosen bus at DRIVE .8: -67.14 dB; not isolated alias energy.

Proposed: drum voicing, all shape laws, integration, mobile budgets, full kit and stereo behavior. Heard: no agent listening claim; owner audition pending. No sample content from the uploaded video is in the output. No perpetual HOLD or seamless loop is delivered; groove WAVs have a release tail and are not bar-length loops.

Audition files: four equal-duration groove comparisons plus kick/snare dry, atmosphere and amp one-shots. Original two-bar groove at 84 BPM, followed by four seconds of release. Reproduction: `python3 source/velour.py` from the downloaded audition bundle with NumPy/SciPy/Matplotlib installed. `report.json` records measurements. The bundle contains the source and harness for review.

## Why it is built this way

1. Short source envelopes let the hit remain separate from the cloud it creates.
2. Selective sends prevent the kick from continually exciting low-frequency ambience.
3. The amp after the wet/dry sum lets a new drum hit push down the surrounding tail.
4. Finite echoes, windowed grains and a finite room make the first experiment bounded and easy to inspect.
5. The risky choice is the source approximation: establish that the dry drums are close enough before spending effort on a faithful pedal-style processor.


## Owner decision and round 3

The owner selected round-2 C for both kick and snare, then authorized the next effects audition. Keep the exact C source implementation and constants recorded in the round-3 bundle. The original R0 source settings above are superseded for subsequent auditions. C kick root 62 Hz / head T60 .29 s; C snare root 195 Hz / head T60 .17 s / wire T60 .11 s. These source candidates were selected, not measured as an exact match to the video. Round 3 restores the original proposed atmosphere and amp with the C sources; effect acceptance and Kotlin integration remain pending. The pre-presentation dry groove identity check passed against round-2 C. Full details and source fingerprints: published round3-notes.md and round3-report.json.


## Native implementation ledger — 2026-11-08

The owner accepted both round-3 treatments and asked to proceed. Model 1 now
keeps the C kick/snare as versioned synthetic source assets and ports the
approved atmosphere/amp to the Kotlin Velour compound board. No new synth
engine is registered. VELOUR and VELOUR BLOOM are fixed sixteen-pad starters
with three touch zones; dry C kick/snare remain on A05/A06. Hats, toms,
stick, shaker and ride are SKIN-based candidates with local DC cleanup and
explicit touch-level scaling. Printed pads carry source provenance, not a
recipe which would process already-wet samples twice.

Independent Python/native full-bus comparisons passed at -151.09 and -150.45 dB
relative RMS error. Both MPC 3 / MPC 2 exports read back, with all 96 PCM24
zone files within 2e-7 of their render. Five Velour tests passed. See
[implementation notes](../../VELOUR.md) for the APIs, limits and reproduction.

Printed per-pad effects are not the same as shared-bus amp/grain interaction.
Android compilation, phone performance, physical MPC playback and approval of
the added voices remain outstanding.
