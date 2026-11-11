# SnipSnap agent entry points

Engine completion follows the owner's
[acceptance gates](docs/ENGINE_ACCEPTANCE_GATES.md). Apply the melodic,
articulation, percussion and drum gates where relevant, and verify velocity
and round robin through rendering, saving, playback and export for every
instrument. Smaller prototype rounds remain in progress.

For DSP and sound-design work, including an explicit Luthier request, read
[Luthier](.claude/skills/luthier/SKILL.md). The complete, versioned bundle lives
in `.claude/skills/luthier/`; its `references/`, `scripts/`, and `evals/` paths
are relative to that directory. Codex discovers the same folder through
`.agents/skills/luthier`.

If a cloud-installed Luthier skill cannot return a reference, read that file
from this repository bundle before reporting it unavailable. Resolve paths
from the repository checkout, not from a temporary chat attachment or a
machine-specific home directory. Verify the bundle with:

```sh
python3 .claude/skills/luthier/scripts/check_resources.py
```

For pull-request and CI work, also read
[Steward](.claude/skills/steward/SKILL.md).

## Engine completion gates

Apply the owner's [engine acceptance gates](docs/ENGINE_ACCEPTANCE_GATES.md) to every engine. A listening preview or passing render test does not satisfy the end-to-end velocity/round-robin gate.

For instrument development, apply the owner's
[instrument acceptance gates](docs/INSTRUMENT_ACCEPTANCE_GATES.md) alongside
Luthier. A disclosed export fallback does not close a velocity/round-robin gate.
