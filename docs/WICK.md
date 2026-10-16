# WICK — first listening prototype

WICK is a pitched, offline instrument built around one shared complex source
and three parallel low-pass gates. Actual gate releases schedule later
excitation in the next gate. Each stage has separate charge and available
excitation; repeated pulses deplete that availability, which recovers over
time. This is an articulation model, not a claim of physical energy conservation.

The supplied [engineering specification](WICK_SPEC.md) is preserved as the
proposal. This first listening round implements **PLUCK and BUBBLE**, eight
provisional recipes and a dry sixteen-pad kit. WOOD, BREATH, FLICKER, RATTLE
and the proposed 36-preset library await the owner's listening verdict.
Numerical checks do not establish that the cascade is musically convincing.

## Controls and recipes

SPARK changes initial and recurrent excitation hardness. FEED supplies bounded
recurrent pulses. CASCADE transmits release events between gates; zero disables
that transmission. TIMBRE changes the shared oscillator's harmonic complexity.
RECOVERY changes depletion memory and associated tail behavior. HOLD's top
step returns settled repeating material.

The six controls are normalized 0–1. Requested MIDI root (24–84) and velocity
(0–1) are saved separately in `WickPatch`. Internal oscillator phases, gate
states and event queues are rebuilt deterministically, not serialized. Zero
velocity is silence, including held operation.

`WickPresets` is deliberately outside the shared factory roster, Android picker
and starter menu pending listening. The CLI, patch/recipe decoder and native
velocity renderer support the prototype. `SynthKits.wick()` supplies sixteen
dry pads with regenerable recipes.

```sh
./gradlew --no-daemon :synth:generateWickAudition
./gradlew --no-daemon :synth:generateWickKit
./gradlew --no-daemon :cli:run --args='synth WICK BUBBLE --all --midi 48 --velocity .65 --out /tmp/wick'
./gradlew --no-daemon test
```

## Listening evidence

The audition renders the real Kotlin engine with an empty FX chain. It contains
native and matched audio, the two starting voices, provisional recipes,
five-step macro sweeps, pitch/velocity probes, interactions and held examples.
Mechanism comparisons disconnect the cascade, force constant availability, or
replace correlated brightness/gain with gain-only gating. Diagnostic JSON
records event parent stages, release and admission times, requested/admitted
strengths, availability and bounded-state measurements. Ablation recipes retain
their diagnostic options separately from normal production patch JSON.

The [private listening page](https://wick-snipsnap-audition.bertcalm.chatgpt.site)
asks whether the spark starts an audible chain, feeding exposes depletion,
RECOVERY changes later articulation, and TIMBRE stays audible through the gates.
Feedback stays in the listener's browser and can be downloaded as JSON.

## HOLD and playback

HOLD must retain recurrent SPARK activity and a declared excitation floor when
FEED is zero. The renderer checks independently generated consecutive audio
and complete network state within a bounded search. The manifest identifies
the actual strategy used by each clip, including any periodic event replay
derived from a causal run. Such replay is an explicit periodic constraint;
live gate charge, depletion, recovery and filtering remain active.

The seam is measured using genuine preceding-cycle context, including PCM16
quantization. Repeating one copied buffer is not convergence evidence.

A held pad contains sustain material only. The audition's Repeat control loops
it in the browser; a normal exported kit pad plays its buffer once. An initial
strike followed by a sustaining keygroup needs a separate instrument adapter.
No Android device or MPC hardware acceptance is claimed in this round.

## Acceptance status

Implementation measurements and checks are recorded beside the generated
manifest and on the audition page. The owner's listening verdict remains open.
The source names, constants, voice settings and proposed release identity are
provisional.
