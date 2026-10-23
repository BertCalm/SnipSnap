# midiwright: install and use

A skill folder: `SKILL.md` (the workflow), `references/` (the knowledge, distilled from a
research pass dated 2026-10-22 plus the repo at commit `b429ec2`), `scripts/` (two small
Python helpers), `evals/` (three test prompts). Nothing here needs a network except the
research steps the workflow tells the model to do (a controller's current programmer
reference, a library's current license).

It is the sibling of `luthier` (DSP and sound design), `steward` (pull request conventions) and `foreman` (repo upkeep). The
boundary: luthier owns what a voice sounds like; midiwright owns how a note reaches it, from
a finger, a key, a cable or a clock, and what the surface can say about that note. When a job
needs both (MPE voice architecture, sample-accurate retriggering), the skill says to call
luthier for the voice-level half.

## Claude Code

Personal (every project):
```
cp -r midiwright ~/.claude/skills/midiwright
```
Per repository (lands beside SnipSnap's existing `.claude/skills/luthier`, `steward` and, once committed, `foreman`):
```
cp -r midiwright <SnipSnap>/.claude/skills/midiwright
```
Both is fine: the repo copy is the one that ships with the code and gets reviewed; the
personal copy is for sessions opened outside the repo. Keep them the same by copying the
repo's version outward, not the other way round, so the repo stays the source.

Then in a session: `/midiwright review app/src/main/kotlin/com/snipsnap/app/MidiInput.kt` or
just describe the job; the description is written so it triggers on the vocabulary.

## Codex

Codex reads `AGENTS.md`. Either paste the body of `SKILL.md` into the SnipSnap `AGENTS.md`
under a "Midiwright" heading and keep the `references/` and `scripts/` folders in the repo so
the paths resolve, or, if the Codex version in use supports a skills directory with the same
`SKILL.md` format, drop the folder there. Check the current Codex docs for which it is; this
file does not assert a Codex path.

## The scripts

```
cd scripts
python3 wire_fixtures.py                  # prints the fixture set as JSON (hex bytes + expected events)
python3 wire_fixtures.py --kotlin         # prints the same set as a Kotlin test table
python3 latency_budget.py usb             # the touch-free chain: USB in, parse, one buffer, output
python3 latency_budget.py touch --buffer 2 --burst 96 --rate 48000
python3 latency_budget.py ble --measured output=22
```
Both need only Python 3.10+. `wire_fixtures.py` also validates itself: it runs a reference
parser over every fixture and refuses to print a set that does not round-trip. The budgets
are built from the cited numbers in `references/timing-and-scheduling.md`; the script prints
each term's label (cited, measured, proposed) so the result can go straight into a ledger.

## Keeping it true

`references/snipsnap-midi-state.md` was read from the repo at commit `b429ec2` on
2026-10-22. The tree moves fast. When MIDI code lands, update that file first (the skill
tells the model to grep before naming any API, so a stale line costs a grep, not a wrong
spec). The Android API facts are dated inside each reference; Android 16 and 17 shipped
without MIDI API changes the research could find, which is a claim to re-check on each new
platform release. `references/unverified.md` is the list of things nobody confirmed; shrink
it as you learn.
