# WICK — first listening prototype

WICK is a pitched, offline instrument built around one shared complex source
and three parallel low-pass gates. Actual gate releases schedule later
excitation in the next gate. Each stage has separate charge and available
excitation; repeated pulses deplete that availability, which recovers over
time. This is an articulation model, not a claim of physical energy conservation.

The supplied [engineering specification](WICK_SPEC.md) is preserved as the
proposal. This listening round implements **PLUCK, WOOD and BUBBLE**, thirteen
provisional recipes and a dry sixteen-pad kit. BREATH, FLICKER, RATTLE
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

BUBBLE's listening-driven default now uses FEED **.25**, giving the answering
gates more space after feedback that the original sounded chaotic. Its other
controls remain SPARK .40, CASCADE .70, TIMBRE .45, RECOVERY .50 and HOLD 0.
The provisional **Busy Bubbles** recipe retains FEED .65. **Spent Bubbles** and
**Held Bubbles** also retain their explicit, denser settings. These recipes use
the current gate response. Saved patches keep their recorded controls; the
supplied engineering specification remains the original proposal.

After feedback that the note was masked by rapid pops, BUBBLE's primary gate
now rings longer, its attacks round off more slowly, and the answering stages
sit below the primary tone. Primary pitch tracking keeps the longer midrange
ring bounded in the bass. Its finite render has another .4 seconds of decay;
excitation admission still ends at the previous time. PLUCK keeps its response.

The opening audition section compares **Earlier pops** and **Longer pitched
tone** at MIDI 48, 60 and 72, with the same pitch, velocity and all six controls.
Diagnostic `legacyBubbleEnvelope=true` reproduces the response from source
revision `140d951a72aaca5f3a42af6b33c484bf4b645d16` for those references. Its
reference recipes save that option explicitly; the candidate uses a normal
Wick patch. Short-window energy and requested-root measurements help compare
the note's body after the attack. The owner preferred this new BUBBLE direction,
which remains the working baseline while WOOD is developed.

WOOD aims for a dry pitched wooden knock with smaller answering rebounds. It
uses the same causal gate network with its own source ratio, body harmonics,
gate timing, darker opening response and stage balance. Its default is SPARK
.60, FEED .15, CASCADE .40, TIMBRE .40, RECOVERY .40 and HOLD 0, rooted at MIDI 48.
The four provisional recipes are **Dry Wood**, **Soft Body**, **Small Rebounds**
and **Held Wood**. The wood character is a listening target rather than an
acoustic-body simulation.

`WickPresets` is deliberately outside the shared factory roster, Android picker
and starter menu pending listening. The CLI, patch/recipe decoder and native
velocity renderer support the prototype. `SynthKits.wick()` supplies sixteen
dry pads with regenerable recipes: each voice at C3/C4 plus ten other recipes.
Every one of the thirteen library recipes is represented in the kit.

```sh
./gradlew --no-daemon :synth:generateWickAudition
./gradlew --no-daemon :synth:generateWickKit
./gradlew --no-daemon :cli:run --args='synth WICK BUBBLE --all --midi 48 --velocity .65 --out /tmp/wick'
./gradlew --no-daemon :cli:run --args='synth WICK WOOD --all --midi 60 --velocity .75 --out /tmp/wood'
./gradlew --no-daemon test
```

## Listening evidence

The audition renders the real Kotlin engine with an empty FX chain. It contains
native and matched audio, the three starting voices, provisional recipes,
five-step macro sweeps, pitch/velocity probes, interactions and held examples.
Mechanism comparisons disconnect the cascade, force constant availability, or
replace correlated brightness/gain with gain-only gating. Diagnostic JSON
records event parent stages, release and admission times, requested/admitted
strengths, availability and bounded-state measurements. Ablation recipes retain
their diagnostic options separately from normal production patch JSON.

Four dry musical phrases use a two-bar score at 100 BPM: the same notes,
onsets and velocities rendered separately by PLUCK, WOOD and BUBBLE, followed
by one phrase sharing those events across the three voices. Each note is a
fresh pad render; recovery state is internal to that note. One fixed assembly
gain preserves differences in strike strength. Loudness matching applies to
the completed phrase. Sequence recipes save the score and every note's exact
patch so the arrangement can be regenerated.

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
