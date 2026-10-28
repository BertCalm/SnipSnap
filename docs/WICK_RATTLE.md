# WICK RATTLE listening round

The characteristic gesture is a pitched strike → clustered openings that spend
their reserve → weaker answers → recovered pulse strength. RATTLE completes
the six proposed WICK voices, following the owner's acceptance of PLUCK, WOOD,
the revised BUBBLE, BREATH and FLICKER.

This is Luthier's CONCEIVE job followed by REVIEW of the Kotlin result.
Repository baseline: `00d74a2b6ffd5a1f819f986224dfd33339780f78`.
The complete local Luthier bundle and its conventions, combination grammar,
inventory, catalog, review rubric and spec template were available. Its resource
checker passed. The supplied [specification](WICK_SPEC.md) remains unchanged.

## Choice and scope

“Rattle” describes the articulation. There is no collision solver, bead tray,
sampled object or noise bed. Reserve is a bounded articulation proxy, rather
than a claim of acoustic energy conservation. The nearest roster mechanisms
are TIDE's complex source and low-pass gate, and TREMOR's repeated strikers and
beads. This round keeps WICK's own release-triggered, recovering gate network;
it belongs in that existing engine as a voice.

| Direction | Gesture clarity | Difference within WICK | Risk | Cost |
|---|---|---|---|---|
| MUTATE gate lifetimes and source color | Clustered openings expose spent reserve | Denser articulation than the accepted BUBBLE body | Short gates can obscure pitch or become drum clicks | Existing three gates and bounded event queue |
| SPLICE a collision exciter into the gates | Strong contact/rattle gesture | Adds mechanical contacts | Duplicates TREMOR and needs a new energy model | Extra solver and HOLD state |
| JUXTAPOSE a fixed pulse pattern with a sustained root | Easy rhythm to hear | Mostly a preset sequence | Weak connection to actual recovery and releases | Cheap clock, another audio path |

The first direction changes the gesture with the smallest useful landing.
Its constants are sound-design choices, checked numerically and awaiting hearing.

```text
note, velocity, macros → sine + 2:1 PM/fold source ─┬→ primary LPG ──┐
                                                 ├→ short LPG 1 ─┼→ sum → DC removal
                                                 └→ short LPG 2 ─┘       → decimate → level
                                                    ↑          │
                   reserve-limited admission ← delayed pulse ← real closing edge
```

Each gate retains separate charge, opening, filter and recovering availability.
No separate drone carries the note. An open primary finishes its gesture before
another supply pulse can enter; actual releases schedule the answering gates.

## Control contract

| Control | Default | RATTLE behavior | Interaction / proof |
|---|---:|---|---|
| SPARK | .65 | Initial and recurrent requested charge; opening time from 20 to 8 ms | With CASCADE changes reach and articulation; finite/held endpoint and velocity checks |
| FEED | .50 | Requested recurrent supply and clock density | With RECOVERY changes actual admission strength and cadence; five-step finite and held sweeps |
| CASCADE | .65 | Release-triggered transmission and stage balance | Zero disconnects transmission; lineage and matched mechanism pairs |
| TIMBRE | .45 | PM/fold complexity and quiet integer answering harmonics | Root and general pitch-detector evidence; matched control sweeps |
| RECOVERY | .65 | Reserve recovery time, charge lifetime and answering delay | Admission/requested ratios and actual event times distinguish depletion from timing changes |
| HOLD | 0 | Independently settled repeating audio and full network state | Genuine predecessor PCM context and state/audio recurrence checks |

The default is deliberately below the supplied dense hypothesis. **Depleted
Rattle** retains SPARK .75, FEED .80, CASCADE .85, TIMBRE .65 and RECOVERY .65.
**Recovering Rattle**, **Small Rattle** and **Held Rattle** complete the four
provisional listening recipes. These are audition choices, not a final bank.

## As built

RATTLE uses integer 2:1 PM. Its complex-source blend is .14 + .28 × TIMBRE;
quiet second/third harmonics color the answering gates. Integer ratios close
under the existing note-preserving HOLD plan and avoid FLICKER's half-root risk.

Charge bases are .110/.067/.047 seconds before recovery and register scaling.
Register tracking is milder than the original bass-primary law. Default attack
is 12.2 ms and closing smoothing is 10 ms. Primary VCA power is 1.02; answering
power is 1.35. Secondary weights are reduced to .75/.60 of the shared balance.
Release delays are .020 + .014 × stage + .040 × RECOVERY seconds. The finite
duration is 3.2 + RECOVERY + .7 × FEED plus the existing bass extension.
Admissions stop before the real tail. Other voices retain their arithmetic.

The reserve model and event budgets remain shared. Higher RECOVERY also changes
timing, so the fast/slow demonstration is a complete control comparison. The
live/constant-availability pair holds all controls fixed and isolates reserve
behavior. Neither demonstration normalizes individual pulses.

The audition adds C3/C4/C5, reserve comparisons, mechanism pairs, finite/held
controls, a held default and the same eight-note, 100 BPM, two-bar phrase.
All five preceding solo phrases and the three-voice arrangement remain intact.
The library has 25 recipes. The 16-pad kit compares all six voices at C3/C4 plus
Held Bubbles, Held Breath, Held Flicker and Held Rattle: ten exact library
recipes and six transposed defaults. WICK remains outside the factory roster
and phone picker during listening.

## Render budget and sonic risks

There is no new solver, geometry or cache. Work is linear in the number of
four-times-oversampled frames with bounded event/state searches. The manifest
reports actual render time per clip; cloud timings are not a phone benchmark.
The existing implementation exceeds the tens-of-ms interactive target, so
performance work remains after this listening round.

The main risks are density masking the underlying note, short envelopes being
classified as drum hits, reserve changes being inaudible after leveling, and
HOLD hiding a copied cycle. Root/detector, cropped-classifier, normalized-shape,
causal-event and independent predecessor checks address those numerical risks.
Upper-register 4×/8× comparisons report agreement, not isolated alias power.
The existing labeled causal-schedule replay remains an explicit approximation
when free recurrence does not settle. Android and MPC hardware acceptance and
attack-plus-loop instruments remain separate work.

## Evidence ledger

- Verified: `Wick.inspect`, `Wick.defaults`, `WickPatch`, `WickPresets.forVoice`,
  `SynthKits.wick` and the audition/kit task routes in the repository baseline.
- Measured, actual Kotlin scratch probe: C3/C4/C5 default detected pitch errors
  +.34/−.41/−.58 cents with confidence .961/.970/.985. Opening-body root shares
  are .905/.735/.724. The C4 default's later root supply admits about .63 of
  requested charge after its initial reserve of one.
- Measured, actual Kotlin scratch probe: the denser test controls with RECOVERY
  zero/one give later root FEED admission ratios averaging about .99/.27;
  constant availability gives one. Event times are retained because cadence
  also changes. Default, zero-feed and disconnected held probes settle freely;
  dense/slow uses labeled causal-schedule replay. All measured seams are zero.
- Proposed: those differences should make a controlled pitched rattle whose
  weakening and recovery are audible. The coordinated Kotlin suites and
  independent exported-artifact reports provide the final numerical record.
- Heard: the owner accepted the five previous voices. RATTLE awaits listening.

## Why it is built this way

- The shared carrier keeps each pulse attached to the requested note.
- Shorter gates let repeated feeding reveal the reserve's memory.
- Real gate releases cause the answers, connecting rhythm to the instrument.
- A separate dense recipe lets the first listen start with a controlled gesture.
- The risky decision is the balance between a clear body and enough density to
  hear depletion. Measurements narrow the choice; listening decides it.
