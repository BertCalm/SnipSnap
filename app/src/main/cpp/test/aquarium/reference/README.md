# Frozen accepted Aquarium audition reference

Frozen on 2026-10-18 from `/workspace/aquarium-audition/native` after the accepted
audition corrections. This fixture is independent of SnipSnap's production
processor so host tests can detect changes against that accepted behavior.

Original upstream: [XO_OX-XOmnibus](https://github.com/BertCalm/XO_OX-XOmnibus),
commit `a9bdc7803d899f0b1688fe0c0abc4ce481a76c3e`, MIT licensed. `LICENSE` is
copied unchanged. The original pinned-file hashes from the audition's
`provenance.json`, accepted corrected-source hashes, and frozen-fixture hashes
are recorded in this directory's `provenance.json`.

The only header transformation is the global namespace-token replacement
`xoceanus` → `accepted_audition`. All processing, defaults, and preset values
remain as accepted. `Presets.h` retains namespace `audition` and targets the
renamed reference processor. Relative header layout is preserved.

Tests may include `reference/Presets.h` from `test/aquarium/`. Do not include
these files from production code, update them to mirror production changes,
or link their reference processor into the Android audio path. Intentional
future baseline changes require a separately accepted audition and refreshed
provenance.
