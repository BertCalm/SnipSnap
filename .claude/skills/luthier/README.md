# luthier: install and use

A skill folder: `SKILL.md` (the workflow), `references/` (the knowledge), `scripts/` (a Python
prototyping and measurement harness). Nothing here needs a network except the research steps
the workflow tells the model to do.

## Claude Code

Personal (every project):
```
cp -r luthier ~/.claude/skills/luthier
```
Per repository (lands beside SnipSnap's existing `.claude/skills/steward`):
```
cp -r luthier <SnipSnap>/.claude/skills/luthier
```
Then in a session: `/luthier review synth/src/main/kotlin/com/snipsnap/synth/Murk.kt` or
just describe the job; the description is written so it triggers on the vocabulary.

## Codex

The repository includes `.agents/skills/luthier`, a relative symlink to the complete
`.claude/skills/luthier` folder. Codex discovers the skill there; Claude Code uses the
canonical folder. There is one copy of the references and scripts to maintain.
If discovery is unavailable, read `.claude/skills/luthier/SKILL.md` directly and
resolve its resources from that folder.

Both [Codex](https://learn.chatgpt.com/docs/build-skills#where-codex-loads-local-skills)
and [Claude Code](https://code.claude.com/docs/en/skills#choose-where-skills-load)
document repository skill discovery and symlinked skill folders. These paths were
checked on 2026-10-22. Restart a session if a newly installed skill is not listed.

## Verify access

From the repository root:

```sh
python3 .claude/skills/luthier/scripts/check_resources.py
```

The checker needs only Python's standard library. It reads all nine reference
documents and the three DSP scripts, validates the evaluation JSON and Python
syntax, checks paths named in `SKILL.md`, and verifies that the Codex entry point
resolves to the canonical bundle. It works from any working directory when given
the script's absolute path. A standalone copy of the folder can run the same
checker without a repository discovery link.

Run this check after changing the bundle or its discovery link. It does not
execute DSP examples or install NumPy/SciPy.

## Cloud copies and complete exports

Import the entire `luthier/` folder, keeping `SKILL.md`, `references/`, `scripts/`,
and `evals/` together. To create a ZIP from this checked-in copy:

```sh
cd .claude/skills
python3 -m zipfile -c /tmp/luthier.zip luthier
```

Installing a cloud skill is separate from committing the repository copy. If the
cloud skill returns `SKILL.md` but cannot return another resource, use the
corresponding file under `<SnipSnap>/.claude/skills/luthier/`. All resource paths
are relative to that skill root; they do not depend on an uploaded attachment
directory or a developer's home directory.

After importing the full ZIP, start a fresh session and ask:

> Use Luthier. Read `references/snipsnap-conventions.md` and
> `references/review-rubric.md`, and report each document's first heading.

Confirm that actual file contents are returned. A passing local check verifies
the repository/exported files; it does not establish that a cloud importer has
made those files available. The current skill tools offer listing and reading,
so this repository repair does not directly replace an installed cloud skill.

## The scripts

```
cd scripts
python3 proto_template.py out_dir      # renders the example engine: WAVs, spectrogram, report.json
python3 probes.py out_dir/BELL_tune0.50_midi48.wav
python3 probes.py a.wav b.wav          # adds the macro-activity distance between two renders
```
Needs Python 3.10+, NumPy, SciPy; matplotlib for the spectrogram (optional). The harness
mirrors SnipSnap's output stage and tests closely enough to rank designs; it is not the Kotlin,
and the workflow says to report its numbers as "Python harness".

## Keeping it true

`references/snipsnap-conventions.md`, `engine-inventory.md` and `omnibus-mine.md` were read
from the repos on 2026-10-21. When the roster or the toolkit moves, re-run the inventory (a
session can regenerate it from `Patches.kt` and each engine's `macrosFor`) and update the
dates in the headers. The skill tells the model to grep before naming any API, so a stale
line here costs a grep, not a wrong spec.
