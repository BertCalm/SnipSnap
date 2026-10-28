# WICK FLICKER listening round

The characteristic gesture is a pitched strike → a brief metallic opening →
a release-triggered answer → a quieter pitched tail. FLICKER is a voice inside
WICK, following the accepted PLUCK, WOOD, revised BUBBLE and BREATH voices.

This is Luthier's CONCEIVE job followed by a REVIEW of the Kotlin result.
Repository source inspected: `7f11f6b7fb8ecc0874d02aba1877c5fa11d4af9a`.
The supplied engineering specification remains unchanged. Its FLICKER row is
the starting hypothesis, not a listening verdict.

## Choice and scope

“Metallic” describes partials exposed by brief openings. It does not imply a
simulated metal object. TIDE GONG already supplies noninteger PM/fold color
through a low-pass gate; WICK BUBBLE supplies release-triggered articulation.
FLICKER combines bright fractional color with WICK's existing recovering
three-gate network. The lightest useful landing is another voice.

| Direction | Gesture clarity | Difference within WICK | Risk | Cost |
|---|---|---|---|---|
| MUTATE source ratio and primary/answer gate timing | Clear body followed by flashes | Fractional color with short answers | Half-root pitch ambiguity | Same bounded source and three gates |
| STACK a modal metal resonator after the gates | Strong metallic decay | Adds a second resonant body | Ringing can obscure the gate gesture | More state, modes and loop constraints |
| JUXTAPOSE short metallic notes on a fixed clock | Clear rhythm | Mostly a preset sequence | Loses the actual release-to-answer connection | Cheap clock but weaker mechanism |

The first direction keeps the instrument's cause-and-answer gesture.

```text
requested note → sine carrier + 3:2 PM/fold source ─┬→ primary LPG ───┐
                                                 ├→ short LPG 1 ──┼→ sum → DC removal
                                                 └→ short LPG 2 ──┘       → decimate → level
                                                    ↑          │
                            admitted charge ← delayed pulse ← real closing edge
```

All stages share the requested note. Quiet 2.5× and 3.5× answering partials
supply additional color. Availability, charge, opening and filter state remain
separate. A release must occur before its delayed answering excitation.

## Control contract

| Control | Default | FLICKER behavior | Interaction / proof |
|---|---:|---|---|
| SPARK | .55 | Changes admitted charge, 4–2 ms opening time and bounded effort | With CASCADE changes the answering gesture; finite/held endpoint shapes |
| FEED | .45 | Supplies bounded recurrent primary pulses | With RECOVERY changes reserve and repetition; finite/held sweeps |
| CASCADE | .65 | Transmits actual releases and changes answering balance | Zero removes transmission while leaving the primary audible; lineage and ablation |
| TIMBRE | .80 | Increases the shared PM/fold color and quiet half-order partials | Must retain the root while exposing metallic color; same-control root and sideband measurements |
| RECOVERY | .35 | Changes reserve memory, charge lifetime and transmission delay | With FEED exposes depletion and recovery; endpoint and held tests |
| HOLD | 0 | Top step returns a settled periodic pattern | Independent audio/full-state recurrence and actual exported PCM boundary checks |

The source keeps a strong direct carrier beneath a complex-source blend of
.10 + .12 × TIMBRE, about .196 at the default and capped at .22.
The 3:2 modulator introduces half-order sidebands. The existing HOLD plan uses
an even number of root cycles, so that ratio and the answering 2.5×/3.5× partials
close without a second pitch grid.

Primary charge base is .205 seconds; answering bases are .065/.045 seconds,
before the existing recovery and register laws. Primary VCA power is 1.10,
answering powers 1.55. The primary carries the note while the shorter, steeper
answers expose brief flashes. Brightness still follows each opening. Default
attack is 2.9 ms. Default finite duration is about 2.595 seconds at C4 and
3.395 seconds at C3, with admissions ending before the real decay tail.

## Listening and integration

The page leads with FLICKER at C3/C4/C5, then its solo rendering of the same
eight-note, two-bar score at 100 BPM. Four previous solos and the accepted
PLUCK/WOOD/BUBBLE arrangement remain available. Notes have independent recovery
state; the phrase uses one fixed assembly gain and no effects.

Four recipes, Metal Flash, Single Glint, Bright Chain and Held Flicker, bring
the provisional library to twenty-one. The sixteen-pad kit compares all five
voices at C3/C4, one held recipe per voice and Single Glint. It holds eleven
exact library recipes plus five transposed defaults. WICK remains outside the
factory roster and Android picker during listening.

There is no solver, geometry or additional simulation cache. Cost remains
linear in the number of four-times-oversampled frames, with bounded queues.
The manifest records finite and held render times separately. The tens-of-ms
interactive target requires measurement; this round establishes offline
listening behavior rather than phone performance acceptance.

## Evidence ledger

- Verified: the repository's `Wick.inspect`, `Wick.defaults`, `WickPatch`,
  `WickPresets.forVoice`, `SynthKits.wick` and audition/kit task routes.
- Proposed: a long enough primary body and strong carrier will preserve the
  requested note while brief fractional-color answers distinguish FLICKER.
- Measured in a Kotlin source-balance probe at C3/C4/C5: the .196 blend gives
  detected pitch within 1.12 cents, confidence .925–.948, coherent root shares
  about .82–.93 and half-order shares about .010–.013. Full Kotlin tests, the
  export manifest and independent artifact checks accompany the audition.
  Higher-rate comparisons report differences;
  they do not establish an isolated alias-rejection pass by themselves.
- Heard: the owner accepted the four preceding voices. FLICKER awaits listening.

## Sonic risks

Half-order sidebands can suggest a pitch an octave below the requested note.
The default bright register checks therefore include both coherent root power
and a pitch detector, rather than only finding some energy at the requested
frequency. A quiet secondary flash can be spectrally different yet too weak
musically; the page must let the owner judge that balance. Upper-register
folding/PM needs the retained higher-rate evidence and listening.

HOLD may use the existing, explicitly labeled causal-schedule replay when free
recurrence does not settle. It retains live gate and reserve state while
constraining the event roster. Hardware acceptance and attack-plus-loop sampler
instruments remain separate work.

## Why it is built this way

- The direct carrier carries the note beneath the metallic color.
- Fractional PM gives each opening color beyond ordinary harmonic brightness.
- The longer primary gate leaves a body; shorter answering gates create flashes.
- Real releases connect the flashes to WICK's recovering network.
- The risky choice is how much fractional color to expose before the ear starts
  following a lower implied pitch. The measurements narrow that risk; listening
  decides the useful balance.
