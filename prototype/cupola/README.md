# CUPOLA prototype: hand-struck tuned shells and slapped open tubes

Python harness for the design brief
[`docs/superpowers/specs/2026-11-21-cupola-hand-percussion-design-brief.md`](../../docs/superpowers/specs/2026-11-21-cupola-hand-percussion-design-brief.md).
CUPOLA is a working name. There is no Kotlin yet; every number here is "Python harness, not
Kotlin", and the brief's ledger keeps measured and heard apart.

The idea: one body tuned to a scale. A strike on one note field makes the fields that share its
overtones ring too (a coupled modal network, the system `Modes.Bank` steps in Kotlin), so a
phrase leaves a halo in key. Five voices so far: HANDPAN, DING, TONGUE, TANK (steel shells) and
TUBES (open air columns slapped with a paddle, in a bundle).

## What the owner has heard

| Voice | Verdict |
|---|---|
| TUBES v5 (`tubes_v5.py`) | approved: "Is better both ways" over v4, high and low |
| TUBES register | a key names the tube's loudest partial (render with `midi = key - 12`) |
| HANDPAN, DING, TONGUE, TANK | "decent", on the first phrases; not yet compared closely with the references |

## Running it

Needs numpy and scipy, and the Luthier harness in `.claude/skills/luthier/scripts` (found
automatically). The reference recordings come from commercial sample packs and are not in the
repository; point `CUPOLA_REFS` at the folder(s) that hold them (`refs.py`). Output goes to
`testkit/cupola-listen/` (gitignored).

```sh
export CUPOLA_REFS=/path/to/references          # os.pathsep-separated for several folders
python3 prototype/cupola/cupola_proto.py        # all five voices, the probe set, phrases (~8 min)
python3 prototype/cupola/ab_v5.py               # the last tube listening pack (v4 against v5)
python3 prototype/cupola/register_probe.py      # the owner's tube hits against v5, by register
```

To render the approved tube from your own code:

```python
import cupola_proto as cp, tubes_v5
tubes_v5.install()                               # TUBES now renders v5
raw, diag = cp.render("TUBES", midi=60 - 12)     # key C4: loudest partial C4
audio = cp.finish(raw)                           # the house output stage, 44.1 kHz mono
```

## Files

- `cupola_proto.py`: the engine (steel voices on one coupled shell), the probe set, phrases.
  Its built-in TUBES is round 3 and is superseded; `tubes_v5.install()` replaces it.
- `tubes_v5.py`: **the approved tube**. Open at both ends, one strike, the same paddle on every
  tube, both ends radiating (odd harmonics partly cancel), a puff of air into the column.
- `tubes_v4.py`: the first approved tube, fitted to one clean one-shot; superseded by v5.
- `tubes_v2.py`, `tubes_v3.py`: rejected, kept for the record. Both began as a closed tube (a
  sealing paddle); the owner heard them as closed, and the measurements agreed once read again.
- `search_v5.py`, `fit_v4.py`: the fits behind the tube constants.
- `register_probe.py`, `hits.py`, `env_probe.py`, `house_distance.py`, `analyze_refs.py`,
  `ltas.py`: the measurements (single-hit anatomy, envelopes, a port of
  `CalabashTest.distanceDb`, whole-loop spectra).
- `refs.py`: where references and output live.

## What this round taught

Two tube versions failed because a measurement was explained by adding a separate mechanism on
top of the tube. The approved version explains the same measurement with the tube alone. And
spectra read from whole loops mislead when two tubes play in each loop: measure isolated hits.
