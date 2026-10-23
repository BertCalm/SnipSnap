# SnipSnap agent entry points

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
