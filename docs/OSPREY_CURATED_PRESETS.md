# OSPREY · sixteen curated presets

These named starting points use the approved R3 engine with authored note,
velocity, duration, seed and six macro values. They change the musical role
through register, water movement, depth, SPACE and finite gesture length.
The sound engine is unchanged. All sixteen are finite SWELL recipes, including
the short gestures; no new articulation or seamless sustain loop is claimed.

The bank is available in **SYNTH → OSPREY** after the original fifteen
coast/weather presets. Existing CLI preset positions 1–15 retain their recipes;
the curated recipes occupy 16–31. Choose **NEW KIT → STARTERS → OSPREY CURATED**
on a build containing this change for the complete sixteen-pad bank in C minor
pentatonic. **OSPREY** remains the original dry C4 coast/weather starter.

## The bank

| Role | Preset | Source note | Seconds |
| --- | --- | --- | --- |
| Warm foundations | LOW TIDE | C2 | 5.5 |
| Warm foundations | NORDIC EMBER | G2 | 6 |
| Warm foundations | NIGHT MOOR | E♭3 | 7 |
| Warm foundations | LOW LANTERN | C3 | 6 |
| Short, soft gestures | TIDE PEBBLE | C4 | 1.75 |
| Short, soft gestures | DRIFTWOOD | G3 | 2.5 |
| Short, soft gestures | SEA GLASS | E♭4 | 2.75 |
| Short, soft gestures | SHELL ANSWER | B♭3 | 3.25 |
| Moving textures | SLOW CURRENT | C4 | 8 |
| Moving textures | CROSSWATER | G3 | 8 |
| Moving textures | KELP LANTERN | E♭4 | 8 |
| Moving textures | WARM WAKE | B♭3 | 7.5 |
| Long, spacious swells | BLUE HOUR | C3 | 11 |
| Long, spacious swells | HARBOR HAZE | E♭3 | 12 |
| Long, spacious swells | OPEN HORIZON | G3 | 12 |
| Long, spacious swells | BETWEEN LIGHTS | C4 | 11 |

Every recipe explicitly retains model 3, seed 731 and all six macros. Built-in
SPACE is part of each authored render; no additional pad FX or pitch treatment
is applied. The factory bank remains editable and regenerable. The original
coast/weather references, their sixteen-pad starter and the earlier audition
remain preserved.

## Audition and export

```sh
./gradlew --no-daemon :shell:generateOspreyCuratedAudition
```

This writes `testkit/osprey-curated-audition/`: the raw PCM24 sixteen-pad kit,
native MPC 3 XTD with an XPM compatibility twin, complete patch and pad recipes,
and full-length level-matched WAV previews. One WAV set is shared by the exports
and editable `kit.json`; source WAVs are not normalized. Preview matching uses
one common achievable listening target across the bank with per-preview fixed
gains, leaving the raw kit unchanged.

The [audition page](https://snipsnap-osprey-audition.bertcalm.chatgpt.site/#presets)
serves full-length MP3 previews and recipe downloads beside the complete PCM24
kit ZIP. The previews are listening copies; the ZIP contains the original PCM24
samples. Keep the XTD beside its `[TrackData]` folder, or open the XPM inside
that folder. Android XPM ZIP import loads samples without the saved recipes;
use the new starter for the editable bank in app.

## Review boundary

On 2026-10-19, after the published sixteen-preset bank was offered for listening,
the owner said, “Sounds great”. This records listening approval of the curated
bank. The earlier in-app confirmation applies to the original R3 bank; curated
in-app/device listening, save/reopen, recipe replay and MPC playback remain
unconfirmed.

Source and artifact checks cover saved recipes, exact regeneration, export
read-back and finite headroom. They do not establish subjective distinctions,
MPC playback or the remaining phone persistence checks.
