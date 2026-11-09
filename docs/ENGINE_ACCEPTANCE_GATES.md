# Engine acceptance gates

Owner-specified gates, recorded 2026-11-09. Apply these when designing, reviewing and declaring an engine complete. These requirements supplement the Luthier conventions; this owner's instruction takes precedence where older guidance differs.

| Engine / instrument type | Required gate |
| --- | --- |
| Melodic | At least six distinct musical voices with presets, playable keygroups, a premade kit and chord-tool compatibility. |
| Articulation | Appropriate HOLD. Picked/thumbed string voices use STRUM, with the owner's intended-character exception for distinctive repeats. |
| Percussion | Authored roll/riser where appropriate; individual voices may remain finite. |
| Drums | Multiple distinct genre kits on the shelf. |
| All instruments | Working velocity levels and round robin through rendering, saving, playback and export. |

## Evidence required

Record the applicable gates and their status for each engine. Use pass, fail, not yet verified, or not applicable with a concrete reason. Do not treat an exception as permission to omit unrelated requirements.

Velocity evidence must distinguish changes in recorded or synthesized strike character from volume-only changes. Round-robin evidence must demonstrate distinct strikes, selection/repeat behavior, preservation of the variant pool and settings across save/load, correct playback selection, and exported variants and ordering/selection behavior. State platform or target-format limitations directly. Repeated copies of one strike are not round robin.

Genre kits must be authored musical choices with distinct roles, balance and character; different effect strengths on the same kit are not sufficient evidence of distinct genre kits.

## BACKWASH status at the acoustic-source audition

- Melodic/keygroup/chord-tool gate: not applicable to this drum engine.
- HOLD/articulation: finite drum voices are appropriate; authored percussion roll/riser coverage remains unfinished.
- Drum shelf: not passed. The existing reference and pushed-processing kits are not evidence of multiple distinct genre kits.
- Velocity and round robin: not passed end to end. Acoustic candidates provide recorded dynamics and alternate takes, but this audition does not implement them in the Kotlin renderer, recipe persistence, Android playback, or MPC export.
- Sound character: awaiting owner approval of the acoustic dry-source audition.

This status prevents source selection or a website demo being reported as a completed engine.
