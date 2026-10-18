# CHAMBER — first listening prototype

CHAMBER is a finite pitched quartet whose room participates in its decay.
A membrane, bar, cowbell and triangle receive one shared strike timestamp.
Their positions determine direct arrivals, first reflections, boundary
excitation and energy returning to the instruments. Material names describe
designed musical responses, rather than measured building materials.

This stage implements **HYBRID and AQUARIUM**, six provisional presets, complete
patch replay and a sixteen-pad prototype kit. The supplied
[engineering specification](CHAMBER_SPEC.md) remains the unchanged proposal.
Its other four voices, 36-preset library, expanded room editor and sustained
HOLD operation remain later work, subject to the owner's listening verdict.

The planned [private audition](https://chamber-snipsnap-audition.bertcalm.chatgpt.site)
is pending deployment verification. Test and numerical results will be recorded
from the completed run; implementation alone does not establish sonic acceptance.

## Controls and complete recipes

STRIKE changes contact hardness and excitation. BALANCE moves through overlapping
instrument weights, retaining a presence floor for enabled strikes. MATERIAL
continuously traverses WOOD, CONCRETE, METAL, GLASS and SUBMERGED_GLASS. DIVIDE
crossfades a uniform global response toward independently assigned regions.
PLACEMENT moves the four instruments along a continuous, bounded path while
the listener stays fixed. All controls use 0–1 values.

HOLD is present in the recipe contract at zero. **Any positive HOLD is rejected.**
The first prototype renders a finite strike and decay. An audition page's Repeat
control repeats that complete clip, including its attack; it does not demonstrate
a sustained instrument, settled loop or held keygroup.

`ChamberPatch` saves the requested MIDI root (36–84), velocity (0–1), model version,
signed 64-bit seed, controls and complete `ChamberConfig`. The seed is serialized
as canonical decimal text to preserve every bit through JSON. Zero velocity is
silence. Tuning defaults are membrane 1×, bar 1×, cowbell 2× and triangle 4× the
requested root; the four ratios and per-instrument strike-enable flags are saved.
An unstruck instrument can still respond sympathetically through the room.
With all four strikes disabled, a fresh room stays at rest. Velocity changes
physical excitation effort, hardness and the upper modes it excites. Raw audition
files preserve the resulting energy differences; production rendering levels the
finished sound to SnipSnap's shared melodic target.

Configuration also saves physical room dimensions, normalized listener and source
positions, placement endpoints and mode, material mode and policy, layout,
contrast palette, split height, blend width and all ten region targets. Macro
edits preserve that authored configuration. Derived delays, filters, modal states
and traveling energy are rebuilt deterministically.

`ChamberPresets` contains **Four Answers**, **Soft Split**, **Glass Corners**,
**Aquarium Ceiling**, **Quiet Canopy** and **High Water**. The prototypes are
available to the CLI, patch and recipe decoders, user presets, native velocity
adapter and `SynthKits.chamber()`. They remain outside the shared factory roster,
Android engine picker and FRESH TAPE menus while listening is open.

## Geometry and material policy

The rectangular room has four walls, a floor and a ceiling. Each wall has lower
and upper assignments, giving ten stable regions. HYBRID explicitly assigns two
wood walls and two glass walls, with independent concrete floor and glass ceiling.
AQUARIUM assigns concrete to lower walls and floor, submerged glass to upper walls
and ceiling. Its horizontal split is editable.

There are four direct paths and 24 first-order image-source reflections. Each
reflection selects its material at the actual boundary intersection. A narrow
physical band blends adjacent lower and upper responses at the split. Forty
source-to-region connections supply propagation delays and position-dependent
weights to boundary excitation and returning energy. Placement therefore changes
more than the listener's short delay taps.

At DIVIDE=0 all regions follow MATERIAL. At higher DIVIDE, each region crossfades
the five parallel material responses toward its own target. In `MACRO_LAYOUT`
mode, saved low/high palette values and `UNIFORM`, `OPPOSING_WALLS`,
`ALTERNATING_WALLS` or `LOWER_UPPER` derive those targets. In `EXPLICIT_TARGETS`
mode, the ten saved values supply them. Both required voices use explicit targets.
The serialized `COMMON_OFFSET` policy shifts each target by
`(MATERIAL - materialReference) * materialOffsetScale`, bounded to 0–1, before
the DIVIDE response crossfade. Defaults preserve the named targets at each voice's
reference material setting. No global material average replaces early paths or
surface assignments; averaging is a diagnostic comparison only.

`MACRO_PATH` placement interpolates between saved source endpoints in physical
space; `EXPLICIT` uses the saved starting positions directly. Physical positions
keep at least 0.18 m clearance from boundaries. Authored paths that pass within
0.25 m of another source or the fixed listener are rejected. Attenuation uses a
minimum 1 m distance, preventing singular gain near a pickup.

## Feedback and listener branches

The feedback model includes instrument modes, boundary modes, both directions of
all forty source/region connections, region/late-field connections and eight
shared late delay lines. Each modal bank evolves through damped rotations in
energy coordinates. Unit-normalized modal and wave ports exchange energy through
orthogonal rotations; an orthogonal shared scattering junction mixes the late
field. Causal integer delays and losses at most one complete the passive network.
Between finite primary strike increments, its modal and traveling energy cannot
grow apart from floating-point storage rounding. Diagnostics account for the
complete network and the finite strike-work budget.

Direct pickups, filtered first reflections and boundary radiation are observer
branches. SUBMERGED_GLASS adds a delayed, gently modulated secondary boundary
response there. This time-varying branch is excluded from feedback and its energy
proof. Its wavering character belongs to the canopy response; the quartet keeps
its own dry identity. Listening comparisons disable returns, average boundaries
or freeze the secondary modulation to expose each mechanism.

## Playback and reproduction

Each rendered note contains its own room. A chord made from several clips plays
several independent room recordings. Rendering another requested note keeps
the saved room's physical resonances and dimensions. Transposing an already
rendered sample shifts the room along with the instruments. A shared room across
live notes would require a different playback adapter.

From the repository root:

```sh
./gradlew --no-daemon test
./gradlew --no-daemon :synth:generateChamberAudition
./gradlew --no-daemon :cli:run --args='synth CHAMBER HYBRID --all --midi 60 --velocity .75 --seed 0 --out /tmp/chamber-hybrid'
./gradlew --no-daemon :cli:run --args='synth CHAMBER AQUARIUM --preset "Aquarium Ceiling" --midi 48 --velocity .65 --seed 41 --out /tmp/chamber-aquarium'
./gradlew --no-daemon :cli:run --args='synth CHAMBER HYBRID --patch /absolute/path/to/chamber-patch.json --out /tmp/chamber-replay'
```

`--patch` requires the requested voice to match the saved recipe. Optional
`--midi`, `--velocity` and `--seed` overrides retain its complete room. The audition
task writes generated audio, recipes, diagnostics and a listening page under
`testkit/chamber-audition/`; those outputs and the prototype kit are ignored by
Git. The page source is retained with the test resources so regeneration can
reproduce the listening interface. No phone or MPC hardware acceptance is claimed.
