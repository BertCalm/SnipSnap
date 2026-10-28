# OBBLIGATO: a conversation that develops

## Overlapping handoffs

The next listening revision, model 9, keeps model 8 available. Genuine changes
of player receive a 60 ms incoming pickup, the existing 140 ms central power
crossover, and a 40 ms outgoing tail. A nominal .35 supporting presence makes
the join and tail audible before returning to the normal .18 background floor.
One common power correction bounds the overlap against the interpolated old/new
gesture budget. It preserves the players' relative balance and introduces no
new nonlinear shaping.

Opening gestures, same-player renewals and settling retain their original
curves. The 240 ms pass stays below the 320 ms ordinary dwell. The memory's
body-contour window waits until that pass and 40 ms of meter settling have
finished, so it does not copy the compulsory handoff as expressive intent.
All earlier renderer recipes retain their original paths; five pre-edit model-8
sum/stem and complete PCM24 receipts accompany the prior compatibility fixtures.

The reason for the staged pass is musical: the answer joins while its partner
still has a phrase to finish, with a brief central exchange that keeps the lead
clear. The curve is a listening proposal; real mixed energy and actual source
overlap must be checked in the rendered comparison.

The overlap revision passed 30 focused checks, including exact raw sum/stem and
PCM24 receipts for 43 earlier recipes across models 1–8, continuous replay,
retained lead, the power budget, 18 rendered note/HOLD cases, Protect's opening
and physical-response negative controls. All twelve model-8 comparison WAVs also
match the previous published recordings byte for byte.

Python analysis of the C4 recordings measured both actual stems above half their
local foreground for an average 43.2 → 60.0 ms at Play passes and 44.7 → 62.0 ms
at Fight passes. Main duet loudness remains .12 and roots remain within 1.5 cents.
Mixed coda energy stays healthy. Brief mono interference remains a listening
tradeoff: the first Fight crossing reaches .313 mixed/source RMS versus .413
previously, with 22 ms below 35% of body-median level. The preceding Play crossing
is slightly shallower. Longer simultaneous presence does not guarantee a more
connected impression for every phase relationship.

The 24-recording comparison includes both versions' actual stems, complete note
and melody recipes, a portable page and a 15,066,676-byte ZIP. Browser checks cover
playback, downloads, notes, mobile layout and offline playback; the 197 earlier
non-navigation Site files retain their bytes. The broader suite is reported
separately. No listening verdict is claimed for model 9.

Status: model 8 rendered listening proof; broader checks and listening verdict pending.
Baseline: `122c7c0c6486521951db33ffbf78aef32d7fb31f`. The owner described the
gentler model-7 direction as better, then authorized this audition. Model 5
remains the factory and missing-model default.

The characteristic gesture is **statement → remembered answer → changed counter
→ shared settling**. Play, Fight and Protect are the first relationships.
Social behavior is a control analogy. The independently seeded `Bore.Player`
pipes remain the physical sound sources; each render begins fresh.

## Scope and choice

BORE supplies one wind source; FLOCK supplies interacting oscillator agents.
OBBLIGATO's distinction is what this particular air/reed pair remembers and does
to the other. This lands as a renderer revision of the existing engine.

| Direction | Gesture, risk and cost |
| --- | --- |
| COUPLE, chosen | Heard effort shapes a remembered answer and ending. Clear duet development; bounded control state and small output filters. |
| MUTATE, later | A shared sympathetic body carries energy through handoffs. New resonator tuning and energy tests. |
| INVERT, later | A partner answers at a deliberate musical interval. New frequency-range support and explicit musical context. |

This round retains gentler voicing and close tuning. It adds no oscillator,
audio-rate cross-feedback, sympathetic body or harmonic counterpoint. The finite
held gesture remains 2–5 seconds plus .75-second release, including at HOLD 1.
Exported samples have no memory across pad presses and claim no sustain loop.

## Signal flow and controls

```text
note, strike, seed, macros, renderer revision
                  |
[two Bore.Player sources @ 176.4 kHz] <--- bounded articulation
                  |                              ^
        actual outgoing activity                 |
                  v                              |
[smoothed meters] ---> [conversation @ 176400/64 Hz]
                              | recorded gestures/memory
                              v
[gentler conditioning -> band limit -> decimate -> DC removal]
                              |
[audio-rate articulation/tone -> fixed weights -> sum -> house level/tail]
```

| Macro | Default | Quantity and interaction |
| --- | ---: | --- |
| BREATH | .65 | Pipe operating point; actual effort affects readiness and the remembered answer. |
| BOND | 0 | Relationship and depth: Play imitates, Fight contests, Protect supports. |
| MISCHIEF | .2 | Bounded variation of response shape and entry inflection. |
| HOLD | .45 | Time for finite development, effort recovery and another turn before settling. |

The note remains explicit C4–C5. The proof adds no macro, factory preset, kit or
picker control.

## Behavior and bounds

Play remembers a turn's duration, intensity and rising/falling shape, then imitates
or shortens it. Fight can renew an assertion before yielding. Protect retains the
flute-led opening and supports a previously heard air source that weakens or spends
its reserve. Foreground effort drains a bounded reserve; quieter turns restore it.
If both sources have been heard, the final settling is authored in the last 22%
of held time, capped at .72 seconds. Disabling responses bypasses all activity,
social memory, reserve updates and social events, including that settling.

Model 8 selects Play where call depth is positive, Fight where rivalry is positive,
and Protect where flute-lead depth is positive. Those existing linear BOND depths
blend expression toward the neighboring relationship; Protect divides its depth
by .65. The reader replaces scheduled call/rival radiation there. Play's old
pressure trade fades by one minus depth. Full Protect preserves its .77-second
reed entry and earlier air release.

The controller reads the preceding complete activity snapshot and records the
heard values and memory behind decisions in `Report.conversationEvents`.
Duration and intensity summarize the complete turn. Slope starts after the
.14-second crossover plus .04 seconds of meter settling, so an entrance ramp is
not mistaken for the player's own rising phrase.
`performanceReader()` replays those curves at audio rate after conditioning.
Presence stays within .18–1, articulation pressure within 1–1.02, entry pitch
within ±2.2 cents, turbulence within .72–1.08, and tone within .40–.94. Reserves are
clipped to .12–1 and readiness to 0–1. Ordinary turns have at least .32 seconds
of dwell; settling requires .14 seconds since the previous gesture. Raised-cosine
transitions take .14 seconds and interpolate squared gains before taking the
square root. These are audition shapes, not acoustically calibrated constants.

Settling softens both players while retaining the current leader: air power share
is .94 for an air lead or .06 for a reed lead; Protect finishes at .05. This avoids
forcing two nearly unison pipes into equal gain when their phases oppose. Actual
mixed coda energy remains a separate rendered check.

Stable mouth pressure preserves source capture while radiation changes prominence.
The tone stage low-passes each source at 2.5 times its root, then scales the
residual by `mix(1, .45 + .55*tone, depth)`. `Dsp.FixedOnePole` caches the root's
filter coefficient. This adds no nonlinear waveshaping or
inter-bore feedback. Finite state, dwell and release rules bound event count and memory;
the final peak ceiling is output hygiene, not the internal stability argument.

## Proof and listening pack

Required checks cover immutable model-1–7 raw/stem and PCM24 receipts, recipe
replay, production/probe parity, root clarity, finite output and real handoffs.
A changed heard contour must change a later answer descriptor, excluding the
compulsory handoff rise. Physical source
weakening must change the survivor's response; with responses disabled, that
survivor must remain bit-identical. Protect must expose its initial flute and
finish without inventing another entry in the release.

The generator writes 24 mono PCM24 recordings: six normal duets, six melodies,
six actual model-8 stems and six Protect diagnostics. Models 7 and 8 share notes,
strikes, seeds and macros. Complete normal duets/melodies match .12 house loudness
before one common headroom attenuation. Stems inherit their duet's gain; all
Protect diagnostics inherit normal Protect's gain. C4, E-flat4, G4 and F4 start
two seconds apart with complete release tails. The intervention scales only air
pressure to .1 during .95–1.45 seconds, with smooth 10 ms edges. Full patches,
phrase onsets and separate diagnostic flags retain replay inputs.
The causal comparison uses entry time, actor, kind, strength and duration;
different heard-meter metadata alone is not counted as a changed answer.

At the common C4/.8/seed-0/HOLD-.45 recipe, Play records four ANSWER gestures and
SETTLE; Fight records four HOLD gestures, three COUNTER gestures and SETTLE;
Protect records SUPPORT and SETTLE. The air-pressure intervention moves Protect's
SUPPORT from 1.284 to 1.067 seconds, about 217 ms earlier. The response-disabled
unweakened reed stays bit-identical. These are recorded decisions, not a musical
quality score.

Rendered coda checks exposed a cancellation dip when Fight ended at equal power
shares. After retaining the leader during settling, the minimum 50 ms coda
mix/independent-source-energy ratio is .694, up from .240 in that first candidate.
The corner-case coda guard covers 18 note/relationship/HOLD cases; the common
HOLD-.45 Fight recipe also has a dedicated regression guard.

## Cost and evidence

Exploratory warm Temurin 17.0.21 measurements used C4, strike .8, seed 0 and HOLD
.45, with four warmups and five timed renders per cell:

| Relationship | Model 7 / 8 median | Model 7 / 8 allocation |
| --- | ---: | ---: |
| Play | 319.39 / 318.04 ms | 13.825 / 13.827 MiB |
| Fight | 317.67 / 318.44 ms | 13.832 / 13.834 MiB |
| Protect | 304.38 / 307.20 ms | 13.767 / 13.768 MiB |

Allocation is bytes allocated by the render thread, not peak or retained memory.
These few observations are not phone measurements. The added bounded state and
cached output filters have little measured cost here; the house target of tens
of milliseconds remains unmet. A whole-render cache needs note, strike, seed,
all macros and revision in its key. There is no geometry or iterative solver.

| Claim | Status and evidence |
| --- | --- |
| Baseline APIs/macros | Verified in `Obbligato.kt` and `Bore.Player` at the baseline above. |
| New controller/integration | Source reviewed: `ObbligatoConversation`, event ledger and model-8 branch. Shape constants remain proposed. |
| First focused proof | 44 tests and the 24-WAV generator passed before contour/crossover/coda hardening. |
| Hardened compatibility/causal/output checks | 23 unique focused checks passed, including 38 legacy recipes across models 1–7, the 18-case coda guard and the dedicated common-HOLD Fight regression. |
| Browser and portable pack | 24 players, downloads, mobile layout and offline playback pass. ZIP is 15,002,122 bytes; 120 previously published Site files verify unchanged. |
| Broader checks | Full JVM and Android results are tracked separately from the focused evidence. |
| Model-8 time/allocation | Measured in the exploratory JVM comparison above. |
| Musical improvement | Proposed. The owner favored model 7's direction; model 8 has no verdict. |

## Why it is built this way

- Articulation gives the returning player a different expressive intent.
- Remembered effort gives later gestures a cause within the note.
- Relationship rules give Play, Fight and Protect different purposes.
- Stable pressure and smooth radiation preserve continuity through handoffs.
- Recipes and earlier revisions make the listening judgment repeatable.
