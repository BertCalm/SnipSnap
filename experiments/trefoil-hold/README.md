# Trefoil: held-state proof

Status: rendered experimental **Python harness, not Kotlin**. Owner listening
pending. Finite Trefoil hardware listening was accepted by Josh on 2026-11-01
("Hardware is fine proceed as recommended"). Device models, firmware and
instrumented phone render times were not supplied.

The characteristic gesture is **contact → energy keeps circulating → release →
natural decay**.

Repository truth: SnipSnap `2f909395feee9962e15fcf60ea0feee0c16f1615`.
Read `TrefoilModelTwo`, `TrefoilTrajectory`, `OverlapHeldPrototype`, Mercury's
`planLoop`, and the luthier prototype, conditioning and probe helpers.
This is a HOLD extension to Trefoil. It changes no finite engine or saved recipe.

## Three directions

Scores use 1–5, higher clarity/novelty is better; lower risk/cost is better.

| Direction | Operator and result | Clarity / novelty / risk / cost |
|---|---|---|
| Strong: powered exchange | COUPLE. Bounded recurring contact powers the existing three-body modal exchange; withdrawing contact leaves stored energy to decay. This keeps Trefoil's defining circulation and gives a tractable periodic-state proof. | 5 / 3 / 2 / 2 |
| Alternate A: captured geometry | INVERT. Capture a moment in the trajectory and make that static contact the instrument. Cheaper and stable, but removes travelling geometry. | 4 / 1 / 1 / 1 |
| Alternate B: closing chaos | MUTATE. Steer a perturbed trajectory back into its initial condition. A useful longer-term experiment, but it changes the chaotic mechanism and needs a new stability/closure argument. | 3 / 4 / 5 / 5 |

Nearest siblings: MERCURY already plans note-related modal loops; OVERLAP's held
experiment already uses bounded periodic power and withdrawal. Neither uses
Trefoil's figure-eight descriptors to rotate three complete modal body states.
The landing is an experimental Trefoil mode, not a new engine or preset roster.

## Signal flow

```text
WEAVE ─▶ unsoftened Newtonian reference orbit ─▶ periodic descriptor table
         [cache: mechanics version]             [period, WEAVE]
                                                     │ contact / exchange
note + velocity + seed + THREAD ─▶ bounded contact ─▶ three modal bodies
                                 external power      ◀─ orthogonal exchange ─▶
                                                          │ upper-mode send
                                                          ▼
                                                   sympathetic memory
                                                          │ no feedback
                                   dry pickups + memory ──┘
                                            ▼
                        4x band-limit → decimate → continuous DC blocker
                                            ▼
                             one gain per recording; mono PCM24 / 44.1 kHz
```

Literal mechanics: equal-mass planar Newtonian gravity, G=m=1, one reference
figure-eight orbit using DOP853. The audio is a driven adaptation: the descriptor
table repeats and external bounded power continues. It does not simulate acoustic
forces acting back on the celestial bodies. The integrated endpoint differs from
the start by 4.72e-12; projecting the descriptor endpoint onto its start changes it
by at most 4.94e-12. The periodic cubic table makes that approximation explicit.

The finite model's nonlinear body-dependent friction and total work cap are
replaced by a periodic external contact source. Contact pressure, attachments,
eight modes per body, pickup colors, damping, upper-mode memory send and
orthogonal rotation follow model 2. Shape constants remain listening hypotheses.
Velocity changes drive and contact saturation. Seed selects a repeating PCG64
texture; this does not reproduce Kotlin Random or finite model-2 samples.

## Controls in this proof

| Control | Base | Quantity | Interaction / numerical check |
|---|---:|---|---|
| WEAVE | .42 | descriptor traversal speed | Moving versus stationary, with the same carrier grid and source seed |
| NUDGE | 0 | reference orbit only | Nonzero values refuse; chaos recurrence remains open |
| EXCHANGE | .55 | orthogonal rotation rate of full modal states | Single driven body transfers energy to receivers; exactly zero transfer with EXCHANGE=0 |
| THREAD | .28 | contact spectrum, body loss, upper-mode detuning and pickups | Brighter C5 and bass C3 candidates; audible usefulness pending |
| MEMORY | .25 | feedforward upper-mode send/return and decay time | Compare .25 and .80, especially after power stops |
| HOLD | experimental | periodic external drive | Release withdraws drive over 25 ms; no output release envelope |

The motion period is snapped to an integer number of carrier cycles and a multiple
of eight output frames. Source tuning deviation in the six cases is below .05
cents; body frequencies stay at the requested note. At WEAVE=0, the loop uses the
same period as the base comparison. Only MIDI 36–84, motion periods at most six
seconds and captures at most 60 seconds are supported. Unsupported inputs refuse.

## Recurrence and energy

For each partial, z = omega*q + i*v has three body coordinates. A sample applies
bounded drive, a real orthogonal rotation R to both coordinates, then positive
modal damping and phase advance D: `z_next = D R (z + i e force)`.
Every unforced sample contracts the Euclidean norm because `||D R|| < 1`.
This proves a unique periodic forced state without a limiter. A 32-sample affine
block product gives the cycle map `z_end = M z_start + b`; solve
`(I - M) z_start = b` for each of the eight three-body blocks. Solve the seven
feedforward memory states afterward. Memory cannot increase dry-body energy;
its own energy can briefly rise after note-off as remaining dry energy enters it.

The return recording independently evolves every cycle. Output filters run
continuously through a genuine preceding cycle. No crossfade, end fade, copied
loop, state reset or limiter establishes the seam. Note-on starts from zero with
a 35 ms power ramp. Note-off happens 371 ms beyond two complete periods, then
the 25 ms source withdrawal ends. Eight further seconds expose the natural tail.

## Measured, proposed and heard

`report.json` contains the six recipes, WAV hashes, timings and 75 passing checks.
Numbers are from the Python harness, not Kotlin or a physical phone.

| Claim | Status / evidence |
|---|---|
| Finite bank and hardware accepted | Owner feedback, 2026-11-01; no new hardware measurements |
| Six loop states close | Measured relative error below 3e-14; real preceding-cycle seam below 1e-26 |
| Root survives | Measured detector error below .24 cents in these six cases |
| Exchange transfers stored state | Measured receiver energy 1.17e-7, exactly zero with exchange off |
| No unforced core growth | Measured 0; diagnostic catches an intentionally growing matrix |
| High-note rate comparison | Noiseless C6 at THREAD=1: aligned 4x/8x relative error energy 2.07e-7; includes step/filter differences, not an isolated alias bound |
| Source/renderer determinism | Recreated source and internal samples byte-identical; PCM hashes recorded |
| Useful sustained character | Proposed; owner listening pending |
| Android HOLD / MPC held export | Deferred until listening acceptance and Kotlin port |

## Cost, cache and risks

Warm desktop generation takes roughly 2–5 seconds per complete case including
loops, independently rendered returns and note-off. First Numba compilation adds
several seconds. Timings are in each report; they are not phone benchmarks.
The present Python proof does not meet SnipSnap's tens-of-ms knob-turn target.
Cache the reference orbit by mechanics version; descriptors by period/WEAVE;
audio and solved state by the entire recipe, source/model/filter versions and
sample rate. Pitch must not rebuild the reference orbit. Downloaded WAV playback
is already baked. A Kotlin implementation needs measured phone setup/render cost
and bounded cache invalidation before app integration.

Listening risks: perpetual rubbing could flatten the finite gesture; repeated
texture could become obvious; strong memory could mask circulation; fixed-gain
comparisons can change level sharply. The page isolates moving geometry, exchange
and memory and offers equal-RMS and common-gain recordings. It provides bass/high
notes, eight genuine returns and explicit power-stop markers. No new sound is
called approved before Josh listens.

## Replay

From the repository root (Python 3.12):

```sh
python3 -m venv .venv-trefoil-hold
. .venv-trefoil-hold/bin/activate
python3 -m pip install -r experiments/trefoil-hold/requirements.txt
python3 experiments/trefoil-hold/prototype.py testkit/trefoil-held-proof
python3 experiments/trefoil-hold/verify.py testkit/trefoil-held-proof
```

The standalone source download includes this directory and the repository's
`snipkit.py`/`probes.py` under their original paths. The browser derives fixed-gain
PCM24 copies and listening ZIPs on demand from the same conditioned audio. This
keeps duplicate audio out of the host's 256 MiB expanded archive limit. Existing finite WAVs and
both MPC programs are preserved byte-for-byte. This round is a listening proof,
with no changes to the production synth or app and no new Kotlin test claims.

## Why it is built this way

- Continuing contact gives the bodies real energy to exchange. Turning the source
  off lets their own damping make the ending.
- Solving the repeating state avoids hiding a bad join with a crossfade.
- Restricting this first proof to the ordered orbit makes the repeating geometry
  honest and leaves the chaotic finite instrument intact.
- Separate equal-level and fixed-gain comparisons expose both color and energy
  differences. Clear power-stop markers make the memory tail easier to judge.
