# WICK BREATH listening round

The characteristic gesture is a soft swell → a stable note → overlapping gate
answers → a gradual release. This is a new voice inside WICK, keeping the
accepted PLUCK, WOOD and revised BUBBLE render paths intact.

The Luthier job is CONCEIVE followed by REVIEW of the resulting Kotlin voice.
Source inspected: `6990bfde7867da438a57323f15d45cceeaa3a62b`.
The supplied WICK specification remains unchanged. The skill's main instructions
were available; its bundled reference files could not be retrieved. API and
integration claims below come from the repository itself.

## Choice and scope

“Breath” is an analogy for the gate gesture. There is no lung, reed, turbulence
or sampled air simulation. The nearest existing instruments are BELLOWS, whose
reed and pressure behavior define its sound, and MURMUR, whose operators follow
formant targets. BREATH instead keeps WICK's shared source and real gate-release
transmission. This belongs in a voice rather than another engine.

| Direction | Gesture clarity | Difference within WICK | Risk | Cost |
|---|---|---|---|---|
| MUTATE the gate timing and quiet-tail response | Clear swell and overlapping answers | Sustained articulation | Release can become too slow to trigger answers | Same bounded three-gate renderer |
| STACK a noise layer on the existing voice | Air texture, weak change in gesture | Mostly spectral | Noise can mask the requested note | Another source and noise-loop closure |
| COUPLE a pressure/reed model into WICK | Strong pressure gesture | Large mechanism change | Duplicates BELLOWS and expands this listening round | Additional solver and state |

The first direction keeps the intended gesture with the smallest change.

```text
requested note → shared sine/PM/fold source ─┬→ recovering LPG 0 ─┐
                                          ├→ recovering LPG 1 ─┼→ sum → DC removal
                                          └→ recovering LPG 2 ─┘         → decimate → level
                                             ↑            │
                      admitted charge ← delayed pulse ← actual closing edge
```

Each low-pass gate owns its filter, charge, opening and recovering availability.
The carrier remains tied to the requested root. There is no separate drone path.

## Control contract

| Control | Default | BREATH behavior | Interaction / proof |
|---|---:|---|---|
| SPARK | .20 | Changes initial/recurrent charge, attack from 70 to 20 ms, and bounded effort | With FEED/HOLD changes later swells as well as the onset; endpoint shape test |
| FEED | .60 | Recurrent root pulses wait for the root's real closing threshold | With RECOVERY changes admission strength and density; finite and held sweeps |
| CASCADE | .35 | Real releases schedule delayed neighbor excitation and change stage balance | Zero disconnects transmission while leaving the first gate audible; causal lineage and ablation |
| TIMBRE | .20 | Changes the shared complex source and quiet harmonic colors | Opening-dependent filtering keeps the root exposed; endpoint spectral/shape evidence |
| RECOVERY | .70 | Changes reserve recovery, charge lifetime and delay | With FEED exposes depletion and overlap; held and finite endpoint tests |
| HOLD | 0 | Top step returns a settled periodic pattern | Independent complete-state/audio recurrence and exported PCM seam checks |

BREATH's charge bases are .24/.35/.45 seconds before recovery and modest register
tracking. Default attack is 60 ms; closing smoothing is 25 ms. A shallower VCA
curve keeps the actual residual tail audible below the release threshold, so
one stage can close causally while its tail meets the next opening. The first
gate still has to close before accepting another root pulse. Longer secondary
charges permit overlap without maintaining a permanently open root.

Default MIDI is 60. Default finite duration is 5.4 seconds at C4 and 6.2 at C3,
including an extra .9-second decay tail that admits no additional excitation.
The finite duration remains below eight seconds at supported control/register
edges. HOLD uses a declared .70 recurrent supply floor for BREATH, needed because
its soft attack reduces peak opening while charge decays. FEED zero can still
arm a real release at default SPARK; velocity zero remains silence.

## Listening and integration

The page leads with BREATH at C3/C4/C5, then the same eight-note, two-bar score
used for the accepted voices. It adds a BREATH solo and retains the old three
solos and three-voice arrangement. Each note has an independent recovery state;
this is a sequence of pad renders, with one fixed assembly gain and no effects.

The provisional bank has seventeen recipes: four PLUCK, four WOOD, four BREATH
and five BUBBLE. The sixteen-pad kit selects C3/C4 for every voice and eight
alternate/held examples. It cannot contain the entire seventeen-recipe bank.
WICK stays outside the factory roster and Android picker during listening.

No geometry, solver or cache is added. Cost remains linear in rendered samples
at four times 44.1 kHz with bounded queues. HOLD can require several synthesized
cycles; its render time is measured separately from finite notes. A tens-of-ms
interactive render budget is a target, not a claim established by this round.

## Evidence ledger

- Verified: repository APIs `Wick.inspect`, `Wick.defaults`, `WickPatch`,
  `WickPresets.forVoice`, `SynthKits.wick` and the existing audition/kit tasks.
- Proposed: quiet residual tails plus longer secondary gates will make the
  clear pitched swell feel continuous and distinct from BUBBLE.
- Measured in Kotlin: isolated onset/body RMS .375 for BREATH versus .502
  for BUBBLE and 1.356 for WOOD under identical controls. At MIDI 24/36,
  velocity .7, the first 1.2 seconds peak near .807 seconds and never fall
  20 dB afterward; the remaining envelope stays above 53% of that peak.
  See the generated manifest, independent artifact report and build information.
  Checks cover swell shape, sustained root,
  control authority, true causal release identity and independent held seams.
- Heard: the owner accepted the previous WOOD and revised BUBBLE direction.
  BREATH awaits listening. Numerical evidence does not establish its character.

## Sonic risks

The later gates can remain open through several root openings. Default BREATH
therefore need not produce an audible three-stage chain on every pulse.
Increasing CASCADE/FEED changes this behavior; it must remain useful by ear.
Longer tails can blur the eight-note phrase, especially in the bass. This is
why the page includes the same score alongside isolated and held examples.
The house classifier can label a cropped BREATH swell KICK/TOM/PERC: if its
maximum occurs near the window end, its reported decay is only the remaining
window, even when no 20 dB decay occurred. Raw classifier results are retained
with direct peak-position and post-peak envelope evidence. The existing
`Wick.drumClassFor` assigns finite WICK recipes TONAL and held recipes LOOP;
no classifier or production routing is changed here.

HOLD may use the existing, explicitly labeled causal-schedule replay when free
recurrence does not settle; it preserves live gate/recovery state but constrains
the event roster. Hardware playback and attack-plus-loop instruments remain
separate acceptance work.

## Why it is built this way

- The sine carrier carries the note while the complex source supplies color.
- Slow openings give the swell; real quiet tails bridge successive openings.
- The root must close so a real release can cause an answer and another pulse.
- Longer secondary gates carry continuity without adding an always-open layer.
- The risky choice is how much overlap to allow: too little returns to separated
  puffs; too much hides WICK's answering gesture. Listening decides the balance.
