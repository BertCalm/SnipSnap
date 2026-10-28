# WICK — first listening prototype

WICK is a pitched, offline instrument built around one shared complex source
and three parallel low-pass gates. Actual gate releases schedule later
excitation in the next gate. Each stage has separate charge and available
excitation; repeated pulses deplete that availability, which recovers over
time. This is an articulation model, not a claim of physical energy conservation.

The supplied [engineering specification](WICK_SPEC.md) is preserved as the
proposal. This listening round implements **PLUCK, WOOD, BREATH, BUBBLE, FLICKER and RATTLE**, twenty-five
provisional recipes and a dry sixteen-pad kit. RATTLE
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

The retained BUBBLE comparison section compares **Earlier pops** and **Longer pitched
tone** at MIDI 48, 60 and 72, with the same pitch, velocity and all six controls.
Diagnostic `legacyBubbleEnvelope=true` reproduces the response from source
revision `140d951a72aaca5f3a42af6b33c484bf4b645d16` for those references. Its
reference recipes save that option explicitly; the candidate uses a normal
Wick patch. Short-window energy and requested-root measurements help compare
the note's body after the attack. The owner preferred this new BUBBLE direction,
which remains the working baseline. The owner also accepted WOOD and the
three-voice musical phrases.

WOOD aims for a dry pitched wooden knock with smaller answering rebounds. It
uses the same causal gate network with its own source ratio, body harmonics,
gate timing, darker opening response and stage balance. Its default is SPARK
.60, FEED .15, CASCADE .40, TIMBRE .40, RECOVERY .40 and HOLD 0, rooted at MIDI 48.
The four provisional recipes are **Dry Wood**, **Soft Body**, **Small Rebounds**
and **Held Wood**. The wood character is a listening target rather than an
acoustic-body simulation.

BREATH targets a soft pitched swell with overlapping gate answers, rooted at MIDI 60.
Its default is SPARK .20, FEED .60, CASCADE .35, TIMBRE .20, RECOVERY .70 and HOLD 0.
The four recipes are **Open Air**, **Single Breath**, **Layered Air** and
**Held Breath**. “Breath” describes the opening gesture; it is not a simulated
wind instrument or a noise source. See the [design notes](WICK_BREATH.md).

FLICKER targets brief bright metallic openings around a pitched body, rooted
at MIDI 60. Its default is SPARK .55, FEED .45, CASCADE .65, TIMBRE .80,
RECOVERY .35 and HOLD 0. The four recipes are **Metal Flash**, **Single Glint**,
**Bright Chain** and **Held Flicker**. See the [design notes](WICK_FLICKER.md).

RATTLE targets a pitched strike followed by clustered pulses that weaken under
repeated feeding and recover between admissions. Its controlled default is
SPARK .65, FEED .50, CASCADE .65, TIMBRE .45, RECOVERY .65 and HOLD 0, rooted
at MIDI 60. **Recovering Rattle**, **Depleted Rattle**, **Small Rattle** and
**Held Rattle** are provisional listening recipes. The dense supplied setting
is retained as Depleted Rattle. See the [design notes](WICK_RATTLE.md).

`WickPresets` is deliberately outside the shared factory roster, Android picker
and starter menu pending listening. The CLI, patch/recipe decoder and native
velocity renderer support the prototype. `SynthKits.wick()` supplies sixteen
dry pads with regenerable recipes: each voice at C3/C4 plus Held Bubbles,
Held Breath, Held Flicker and Held Rattle. The twenty-five-recipe library is
larger than the kit; ten exact library recipes and six transposed default
variations occupy its sixteen pads.

```sh
./gradlew --no-daemon :synth:generateWickAudition
./gradlew --no-daemon :synth:generateWickKit
./gradlew --no-daemon :cli:run --args='synth WICK BUBBLE --all --midi 48 --velocity .65 --out /tmp/wick'
./gradlew --no-daemon :cli:run --args='synth WICK WOOD --all --midi 60 --velocity .75 --out /tmp/wood'
./gradlew --no-daemon test
```

## Listening evidence

The audition renders the real Kotlin engine with an empty FX chain. It contains
native and matched audio, all six voices, provisional recipes,
five-step macro sweeps, pitch/velocity probes, interactions and held examples.
Mechanism comparisons disconnect the cascade, force constant availability, or
replace correlated brightness/gain with gain-only gating. Diagnostic JSON
records event parent stages, release and admission times, requested/admitted
strengths, availability and bounded-state measurements. Ablation recipes retain
their diagnostic options separately from normal production patch JSON.

Seven dry musical phrases use a two-bar score at 100 BPM: the same notes,
onsets and velocities rendered separately by PLUCK, WOOD, BREATH, BUBBLE, FLICKER and RATTLE,
plus the retained phrase sharing those events across PLUCK, WOOD and BUBBLE. Each note is a
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
manifest and on the audition page. PLUCK, the revised BUBBLE direction, WOOD
and the three-voice phrases have received positive listening feedback. BREATH is now accepted as a working voice.
FLICKER is also accepted as a working voice. RATTLE's listening verdict remains open. The short-window classifier can
mislabel a late BREATH swell when the window ends before any real decay;
its raw label and direct envelope evidence are retained in diagnostics.
The source names, constants, voice settings and proposed release identity are
provisional.
