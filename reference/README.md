# Reference kits — the exporter's ground truth

The XPM writer is built by **substituting into a known-good file**, not by
generating XML from a spec. The `ProgramPads-v2.10` JSON blob inside an `.xpm`
(pad→MIDI-note map, pad colours) is not reliably documented, and a
plausible-looking guess produces a file that loads but misbehaves.

So the exporter is blocked until there's a real kit in `golden/`.

## The procedure (~5 minutes, per device)

1. On the MPC, start a new project and add a **Drum** program.
2. Assign a sample to **all 16 pads of bank A**. Any samples — factory content
   is fine. All 16 matters: a partially-filled program won't show how empty vs
   filled pads are encoded.
3. Give a few pads distinct **colours** and **names**, and nudge a couple of
   **tune** / **level** / **pan** values off default. This makes it obvious
   which XML field maps to which parameter.
4. Save the program: `Save As` → name it `SnipSnapRef` → save to SD or USB.
5. Copy the resulting `.xpm` off the card.

Do this on whichever devices you can. **MPC One first** — it's the acceptance
target, and a program that loads there loads on all three.

## Where to put them

```
reference/golden/
├── one-2x/
│   └── SnipSnapRef.xpm
├── liveii-2x/
│   └── SnipSnapRef.xpm
└── liveiii-36/
    └── SnipSnapRef.xpm
```

Name the folder `<device>-<firmware>` so we can tell later which file came from
where — that matters when firmware changes the format under us.

**`.xpm` files only.** Don't commit the WAVs; they're large, they're likely
Akai factory content, and the writer doesn't need them. A `.gitignore` in
`golden/` enforces this.

## Also useful, if easy

A second export of the *same* kit with one single parameter changed (say, pad
A05's tune) — diffing two near-identical files is the fastest way to locate a
field with certainty.

## What happens next

With a golden file in place:

1. Extract it into a template + a substitution model
2. Build the pure-Kotlin writer against it
3. Golden-file test: fixed input kit → byte-identical expected output
4. Round-trip test on hardware — generate 16 pads, load on the One, confirm
   every pad fires the right sample
