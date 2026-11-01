# WICK RATTLE: connected-body revision

The characteristic gesture is one pitched body opening → internal rattle
motion → depletion and recovery within that body → a clean ring-out.

Status: measured listening candidate. The owner found the first RATTLE response
too much like one voice being hit repeatedly and asked for a more cohesive
character. The previous five voices remain accepted; this revision awaits
listening. Repository baseline: `b6248f426833e2ada17615b2bb4f9d219207f20d`.

This is Luthier's REVIEW followed by CONCEIVE of the revised articulation.
The repository's complete local skill bundle and conventions were available;
its resource checker passed. The supplied WICK specification is unchanged.

## Finding and choice

The earlier primary gate loses most of its audible body before another supply
pulse opens it. Its fast attack and separate answering accents make that new
opening read as another strike. A denser event count demonstrated the network,
but did not establish the cohesive sound the owner wanted.

The nearest existing mechanisms are WICK BREATH's overlapping openings and
TIDE's complex source through a low-pass gate. The useful change is inside
RATTLE's articulation; it needs no new engine or resonant-body model.

| Direction | Gesture clarity | Difference | Risk | Cost |
|---|---|---|---|---|
| MUTATE RATTLE opening, quiet-tail response and stage balance | One body with internal movement | Preserves its reserve and actual release chain | Smoothing can erase the rattle or prolong the tail | Existing three gates, no new state |
| STACK a resonant body after the gates | A shared ringing body | Adds another resonance model | Extra state, coloration and HOLD constraints | Another filter/body stage |
| JUXTAPOSE a sustained carrier with the old hits | Immediate continuity | Adds a parallel sustaining layer | The carrier can conceal the causal articulation | Another audio path |

The first direction carries the requested gesture with the smallest change.
“Connected body” is a perceptual target, not a physical-object simulation.

```text
same requested note + same source ─┬→ primary recovering LPG ─────┐
                                  ├→ quieter answering LPG 1 ──┼→ sum → existing output
                                  └→ quieter answering LPG 2 ──┘
                                       ↑                  │
                  reserve-limited charge ← delayed pulse ← real closing edge

Each gate's residual audio remains audible below its actual closing edge.
The source does not bypass those gates.
```

## Controls and implementation

Controls, defaults, source ratio, charge lifetimes, reserve laws, feed clock and
transmission delays stay fixed for a useful same-controls comparison. Closing
and opening smoothing still affect the measured time of a real release.

| Control | Default | Quantity and interaction retained | Proof |
|---|---:|---|---|
| SPARK | .65 | Initial/recurrent charge and opening hardness; pairs with CASCADE | Finite/held control and velocity checks |
| FEED | .50 | Supply density and strength; pairs with RECOVERY | Actual repeated admissions and normalized audio changes |
| CASCADE | .65 | Real release transmission and answering balance | Causal ancestry and disconnected comparison |
| TIMBRE | .45 | Shared PM/fold complexity and opening-dependent brightness | Spectral/root and macro checks |
| RECOVERY | .65 | Reserve refill, charge lifetime and delayed articulation | Actual admitted/requested ratios and event cadence |
| HOLD | 0 | Settled periodic audio and full existing state | Independent predecessor PCM and state recurrence |

The revised attack is `.060 − .035 × SPARK` seconds, 37.25 ms at default,
with 25 ms closing smoothing. Primary/answer VCA powers are .45/.85 rather
than 1.02/1.35. The quieter tail therefore carries more of the note through
the release-to-answer interval. Answer weights are .42/.30 of the shared
balance, and the brightness exponent is `.42 + .05 × stage`.

The more audible tail needs additional real ring-out. Finite duration adds
`.20 + .20 × RECOVERY + .50 × (registerRelease − 1)` seconds while retaining
the previous admission deadline. Default C4/C3 durations are 4.53/6.33 seconds.
The extension admits no additional supply or cascade events. An isolated
zero-feed, zero-cascade render still decays away.

`Wick.Options.legacyRattleEnvelope=true` guards the complete former RATTLE
path, including duration. It is a diagnostic comparison recipe, not a normal
patch macro. Ordinary RATTLE patches use the revised response. All changed
arithmetic is guarded by the RATTLE identity and that option; the other five
voices retain their arithmetic and audio.

There is no new state, solver, effect, free-running sustaining layer or cache.
HOLD retains the existing free-convergence search and explicitly labeled
causal-schedule replay approximation. Existing gate, reserve and filter state
still have to recur, with audio compared against a real preceding period.

## Audition and measurements

The first section compares **Earlier separate hits** and **Connected body** at
C3/C4/C5. Every pair shares SPARK .65, FEED .50, CASCADE .65, TIMBRE .45,
RECOVERY .65, HOLD 0 and velocity one. Reference revision is
`3b77d8bd34fdab803dee0504911ac95e32430896`. The reference WAVs must match the
previous register exports byte-for-byte. The 25-recipe bank and 16-pad selection
are unchanged. There are 357 clips, 70 held loops and seven musical phrases.

Actual Kotlin probe measurements over contiguous 20 ms RMS windows from .35 to
2.30 seconds, each normalized by its own median, give:

| Requested note | Earlier body floor/median | New body floor/median | Earlier contrast | New contrast | Earlier positive-rise measure | New positive-rise measure |
|---|---:|---:|---:|---:|---:|---:|
| C3 | .466 | .715 | 14.23 dB | 7.27 dB | .709 | .217 |
| C4 | .393 | .643 | 15.85 dB | 6.85 dB | 1.093 | .421 |
| C5 | .389 | .626 | 16.07 dB | 7.16 dB | 1.137 | .418 |

Floor is the RMS 10th percentile; contrast is 20 log10(P90/P10). Positive-rise
is the 95th percentile of positive adjacent-bin RMS changes divided by median
RMS. These comparisons cannot be passed by changing overall gain alone. Their
residual contrast also demonstrates movement rather than a flat sustaining tone.

The same Kotlin probe detects requested pitch within .49 cents with confidence
at least .972. Actual primary admissions remain present, and the live/constant
reserve envelopes remain different. Four held probes close with zero seam
error; dense/slow recovery uses the existing labeled replay approximation.
After the added ring-out, measured pre-fade RMS is below .76% of central-body
RMS across the low/high notes and recovery settings probed. The isolated
zero-feed/zero-cascade tail is below .0003% of its earlier body.

The coordinated suite and independent PCM artifact report provide the final
export evidence. Metrics are evidence for continuity and preserved mechanism;
they cannot establish that the new response meets the owner's musical target.

## Limits and evidence ledger

- Verified: `Wick.inspect`, its `Options`, the diagnostic recipe serialization,
  `WickPatch` and the existing audition/kit routes on the named repository base.
- Measured: the Kotlin probe comparisons above. Exported PCM, legacy hashes,
  causal ancestry, macro activity and genuine held boundaries are independently
  checked alongside the published audition.
- Proposed: retaining the primary tail and softening the new openings should
  make the movement feel internal to one body. It may also move the character
  closer to BREATH; listening decides the useful distinction.
- Heard: the owner judged the earlier RATTLE too much like repeated hits. The
  revised response has not yet received a listening verdict.

Longer finite buffers cost more to render. The manifest reports actual finite
and held timings; cloud results are not a phone benchmark. The existing
tens-of-ms interactive target and hardware acceptance remain open. Higher-rate
reference agreement does not isolate alias power. RECOVERY remains a coupled
timing/reserve control, and difficult held cases retain labeled event replay.

## Why it is built this way

- A more audible primary tail keeps the requested note present between openings.
- Softer attacks make later admissions read as movement inside that body.
- Quieter answering gates keep the release chain active without dominating it.
- Extra ring-out lets the gentler body finish without an abrupt cutoff.
- The risky balance is continuity versus too little articulation. Matching old
  and new controls makes that choice easier to judge by listening.
