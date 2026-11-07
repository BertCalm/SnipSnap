# Approved Alloy MATERIAL body mapping

This package preserves the three-preset body comparison that Josh approved on
2026-11-07 with “New body is good.” It is a handoff for implementing that response
in SnipSnap. **The live MATERIAL mapping is not implemented by this package.**

The approved audition is at
<https://snipsnap-alloy-factory-bank.bertcalm.chatgpt.site/material-body/>.
It used the unchanged Kotlin renderer at commit
`b04496ece7770903549c23ea913bad90bb57f45e`; its published Site source commit is
`a06d0b6999c0d80aeea19811e0a2c090b5e77407` (Site version 3).

## Start here

- `model.py` is the portable, standard-library-only authoring law. Its arithmetic
  and route ordering match the approved snapshot generator; baseline lookup is
  relative to this package rather than the original workspace.
- `baselines/` contains the three complete original patches. Their original
  MATERIAL values are .05 for Rounded Plug, .5 for Long Prism, and .2 for Velvet
  Rise. Do not replace them with current voice defaults.
- `final-designs.json` records the authored left endpoints.
- `response-law.json` describes the lower-half interpolation and fixed fields.
- `patches/` contains all 36 exact, complete model-1 snapshots: three roles ×
  finite/HOLD × five MATERIAL positions plus each original reference.
- `fixtures.json` records all 36 listening and native WAV fingerprints, including
  their sample-data hashes and the accepted patch-file hashes. The bulk audio
  remains downloadable from the published comparison.
- `reference/` contains twelve native PCM24 goldens: the new left endpoint and
  original midpoint, for all three roles in finite and held form. These are
  public `AlloyPatch.render()` exports, not edited A/B demonstrations.
- `reports/` holds the captured verification, source/class provenance, independent
  audits and browser results. These reports predate the approval; `approval.json`
  records the later owner feedback without rewriting historical evidence.
- `captured/` holds the original workspace diagnostic scripts and process-exit
  records. They retain their original absolute paths and are **not** a turnkey
  portable build. Use them to inspect how the full 264-input study was generated.

The captured provenance's `mappingSourceSha256` identifies `captured/model.py`.
The portable top-level copy changes only baseline lookup/import constants; the
snapshot validator requires its generated JSON bytes to remain identical.

## Portable checks

From any working directory, run:

```sh
python3 /path/to/SnipSnap/experiments/alloy-material-body/validate_fixtures.py
```

This regenerates all 36 snapshot inputs and checks their exact JSON bytes,
unchanged ratios/envelopes/air-half inputs, fixture coverage, and twelve native
golden headers and file/sample-data fingerprints. It does not claim to synthesize
audio. It uses only Python's standard library.

To check a Kotlin implementation against the sound, compile `ReplayReferences.java`
with Java 17 and the existing SnipSnap JVM module classpath, then invoke:

```text
java -cp <compiled-helper>:<SnipSnap-module-classes>:<kotlin-stdlib> \
  ReplayReferences /path/to/experiments/alloy-material-body /path/to/replay-output
```

The classpath needs `synth`, `audio`, `json`, `xpm`, `kit`, `mpc3` and `loop`
classes, plus the Kotlin standard library. Compile the existing modules first in
a fresh checkout. Keep replay output outside this committed package. The helper
loads each complete snapshot with `AlloyPatch.fromJsonText`, calls the actual
public renderer, writes native PCM24 and requires all twelve WAVs to match
byte-for-byte. Run it against the recorded source commit to establish the
baseline before adapting the mapping to a newer checkout.

## Response to implement

At MATERIAL ≥ .5, return the exact original configuration. Do not pass that
branch through interpolation: it preserves original route order and absent
edges as well as numeric fields. At MATERIAL < .5, the original weight is:

```text
Rounded Plug: (2 × MATERIAL)²
Long Prism and Velvet Rise: 2 × MATERIAL
value = leftEndpoint + (original - leftEndpoint) × originalWeight
```

Preserve the prototype's numeric order when porting: the Python law blends
authored Double endpoint decimals against decoded Float baseline values, then
the Kotlin configuration parser narrows each result to Float. Do not pre-round
the endpoint decimals into a Float configuration before the blend. The exact
JSON fixtures and all 36 native sample hashes provide the comparison contract.

The mapped fields are operator audible gains, sustain and decay; route depths;
wood losses and gains; and output cutoff. Operator ratios, wood ratios, tine and
breath projections, global attack/duration/release, held period/drive/roughness,
and `fuseRadians` stay original. Routes below .5 use the sorted union of endpoint
and original edges, treating an absent depth as zero. Existing presets and
complete old saves must keep their current behavior unless explicitly opted in.

Importing one of the snapshots into the current app **does not** install this
response: turning its current MATERIAL knob only exercises the existing engine
macro over that fixed configuration. A future implementation must persist the
optional body mapping and resolve it when rendering macro edits.

## Scope and acceptance

The three approved roles are Rounded Plug (BASS), Long Prism (KEY), and Velvet
Rise (SOFT). The other 15 authored factory roles have not received body endpoints
in this study. The six factory reference presets and six retained held references
also remain unchanged. Do not promote a new full bank based only on this package.

The previous wood-only study was rejected by the owner: no audible difference
for Prism/Velvet, barely any for Plug. Do not substitute it for these body-linked
inputs. Small waveform differences alone did not establish useful timbral change.

The captured stronger study passed 3,027 numerical/integrity checks; six midpoint
PCM16/PCM24 identities; 18 unchanged upper-half PCM24 comparisons; and 18 real
preceding-cycle cut seams. Independent review added fresh replay of the changed
left endpoints, midpoints and original patches. Phone responsiveness and physical
MPC import/playback remain device acceptance work. No new measurements are implied
by copying the captured reports here.

## Why it is built this way

1. Changing the body itself gives MATERIAL leverage after the opening contact.
2. Fixed ratios preserve the requested pitch through intermediate positions.
3. The exact original branch keeps the approved sound at .5 and the old air half.
4. The bass square law spreads its colour change over the lower range rather
   than crowding most of it near zero.
5. Complete input snapshots and native goldens preserve the approved work without
   pretending that app integration or physical-device acceptance has happened.
