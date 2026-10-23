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

Codex reads `AGENTS.md`. Either paste the body of `SKILL.md` into the SnipSnap `AGENTS.md`
under a "Luthier" heading and keep the `references/` and `scripts/` folders in the repo so the
paths resolve, or, if the Codex version in use supports a skills directory with the same
`SKILL.md` format, drop the folder there. Check the current Codex docs for which it is; this
file does not assert a Codex path.

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
