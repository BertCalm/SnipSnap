# Neuron-Z1 handoff snapshot

Start with [the handoff document](../../docs/NEURON_Z1_HANDOFF.md).

This folder preserves the finite prototype and SPACE revision without registering
a production engine. The init script opts the sources into JVM tests only when
explicitly supplied. Generated audio and packages go under `build/neuron-z1/`.

From the repository root:

```sh
bash prototypes/neuron-z1/run.sh
python3 -m http.server 8765 --directory build/neuron-z1/site
```

Open `http://localhost:8765`. Requires Java, the repository's Gradle wrapper,
Python 3, and access to Gradle dependencies on a cold machine. Preserve the
environment's proxy and CA configuration. Never run overlapping Gradle builds
in this checkout.

The runner executes the original test first, verifies its 86 WAVs against saved
published hashes, then runs six revision tests plus the 36 StringsTest tests.
It verifies all 146 original/revised WAVs before creating the local page.
Existing version-1 recipes and new version-2 comparison recipes have separate
replay tasks; see the handoff for commands.

`evidence/` contains the actual publication-time results. A later run writes fresh
results under `build/`; it does not rewrite the committed evidence. Regenerated
ZIP checksums can change because timestamps and measurements change. The published
ZIP hashes are recorded in the handoff and evidence.
