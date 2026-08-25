# Reference kits

**MPC 3 on the Live III is the only target.** Everything MPC 2 moved to
[Backlog](#backlog-mpc-2) — not deleted, not broken, just off the critical path
until there is hardware to verify it on.

One clarification before the backlog reads as bigger than it is: the `:xpm`
module is still the **only thing that produces loadable output today**, and MPC
3 loads MPC 2 content — that is Akai's own documented interop route. So `:xpm`
stays live as the shipping path. What is deprioritised is *verifying it against
MPC 2 hardware*, which nobody here owns.

## Harvesting is done

The corpus in [`golden/`](golden/) answers the format questions. Seventeen
commercial packs, five vendors, 2017–2026, six exporter builds, both
generations — all of them products that are sold and that load. That is what
establishes what MPC accepts; a file saved on the hardware would only show what
the firmware *writes*, which is a different and much less useful fact.

Earlier revisions of this file led with "the one file that matters: a drum
program saved by the Live III," and listed values to push off-default so the
save would be informative. Both are obsolete. Every one of those values turned
up in shipping content:

| Was wanted from a save | Found in |
|---|---|
| pad colours | Masada 2017, Platinum Percussion, Ambient Box — `ProgramPads` decoded |
| non-zero tune | Masada (`TuneCoarse` −4…+2), MPC 3 (`fineTune` −10) |
| `triggerMode` `1` | Pro Studio Kit — snare rolls |
| velocity splits | Acoustic Drum Tools, DFH, Upright Bass — 8-way, both containers |
| empty-vs-filled encoding | Classic Drum Machines |

**Nothing further needs harvesting.** The few fields still unexplained —
`keyTrackEnable`'s meaning, `poliphony`, `layersv[].pitch` — would not be
settled by a save either; they need a deliberate toggle-and-diff probe, and
none of them block work, because a writer can copy the values real content
uses.

## The only test left: does *our* output load?

Not a harvest — an acceptance test, and the distinction matters. The corpus
shows what MPC reads. It cannot show whether what we write is inside that set.
Runs **today**, since `:xpm` output is MPC 2 content and MPC 3 loads it:

**Passed, 2026-08-23, on a Live III — both generations.**

- **Native `.xtd`** (`SnipSnap MPC3 Kit`): loaded and played, pads on their
  assigned slots, class colours lit, A03 choking A04, the embedded
  "SnipSnap Groove" clip playing from the clip list.
- **MPC 2 `.xpm`** (diag kit): loaded, and pad A01 gave **one beep** —
  0-based instrument numbering confirmed, despite twenty of twenty vendor
  programs numbering from 1. The parser takes both; ours is in the set.

Still queued for a card session, in value order:

1. **Keys** — `SnipSnap MPC3 Keys.xty` (native) and `SnipSnap Keys`
   (MPC 2): do keygroups load and play in tune chromatically? This is the
   S4/S5 acceptance.
2. **One-file sharing** — import `testkit/SnipSnap_Factory.xpn`; the layout
   now matches all four real archives (root `Expansion.xml`, bare names).
3. **Expansion tile** — copy `testkit/Expansions/` to the card root, check
   the Expansion browser.
4. **Velocity feel and bank B** — the velocity kit's soft hits, the shuffle
   kit's B-bank treatments.
5. **The one save the corpus can't supply** — build any drum program on the
   Live III, save it, drop the file in `golden/liveiii-36/`. It is the last
   word on what firmware itself writes (container, defaults, dialects).

## What's in `golden/`

See [`golden/README.md`](golden/README.md) for per-pack provenance and the
extraction recipes. Five folders:

| Folder | Holds |
|---|---|
| `mpc3-track/` | 13 `.xtd`/`.xty` containers — the MPC 3 program schema, six exporter builds |
| `keygroup/` | 5 MPC 2 keygroup programs, including an 8-way velocity split and a chromatic bass |
| `drum/` | 5 MPC 2 drum programs + three `.xpn` archive listings |
| `expansion/` | 13 `Expansion.xml` across both deployment dialects |
| `mpc3-project/` | `.xpj` projects in both generations, plus `.mpcsample` and `.sxq` |

**No audio, ever.** The `.gitignore` here enforces it by extension — and note
it only catches audio *extensions*, so check anything without one before
committing it. The WAVs are large and they are the vendors' to sell; programs
are metadata and are what a writer needs.

## Backlog: MPC 2

Parked until there's an MPC One or Live II to test against. Nothing here is
wrong or broken — it is unverified, and staying unverified is an accepted cost
while MPC 3 is the only target.

- **A drum `.xpm` off MPC 2 hardware.** The MPC 2 XPM structure came from a
  program saved by standalone firmware 2.9.1.2 (see
  [`../docs/XPM_STRUCTURE.md`](../docs/XPM_STRUCTURE.md)) and `:xpm` writes it
  today — but that structure is second-hand. A real export would turn every
  open question in [Unverified](../docs/XPM_STRUCTURE.md#unverified) —
  instrument numbering base, gap handling, version header, element naming —
  from a debate into a diff. Goes in `golden/one-2x/` or `golden/liveii-2x/`.
- **A keygroup `.xpm` off MPC 2 hardware.** Much less urgent than it was.
  `KeygroupWriter` was built from second-hand vocabulary and had never seen a
  real keygroup program; [`golden/keygroup/`](golden/keygroup/) now holds
  three, and the writer has since been corrected against them (all the
  definite defects in
  [`../docs/XPM_STRUCTURE.md`](../docs/XPM_STRUCTURE.md#what-keygroupwriter-gets-wrong)
  are fixed), so its *structure* is referenceable. What a hardware save would still add is
  firmware provenance — those three are one vendor's output, and a vendor can
  be idiosyncratic without being wrong.
