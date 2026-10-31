# Keygroup voice mode and full-keyboard zones

Owner ruling, 2026-10-31, after playing the shipped instruments on an MPC
Live III.

## The two defects

**Every keygroup chokes.** The MPC 3 track writer stamps every keygroup
program `monophonic: true, poliphony: 1` at the program's drum level
(`Mpc3TrackWriter.keygroupDrumObj`), templated from one factory bass track.
Akai's own poly keygroups (Kalimba, Singing Bowl in `reference/golden/keygroup`)
carry `Program_Polyphony 0`, `Mono False`. The MPC 2 `.xpm` twin omits the
tags entirely. The app has no notion of voice mode.

**The playable range stops at the outermost rendered root.** Edge zones end
at `root ± 9` (the suite) or at the root itself (several shell makers), so an
instrument spanning notes 32..74 is silent outside them. Akai's convention
is to run the lowest zone down to note 0 and the highest up to 127 and let
the hardware pitch-shift the edge sample. The Live III's four pad banks then
play end to end.

## What the owner wants

1. A per-instrument **voice mode** the user chooses, with three values:
   - **POLY** — chords ring. The default, and the right value for all nine
     shipped instruments (EP, Organ, Harp, Music Box, Resin Pad, Fork,
     Mercury Ping / Sing / Blade).
   - **LEAD** — one voice; a new note takes over; re-pressing a held note
     restarts it.
   - **GLIDE** — LEAD, and an overlapping note slides to the new pitch
     instead of restarting.
2. Both MPC descriptors (`.xty` and the `.xpm` twin) and the phone's
   sidecar carry it, and the phone's own KEYS player honours it.
3. Edge zones reach 0 and 127 on every exported keygroup, in one place, not
   in fifteen makers.
4. Drum kits are untouched: their tonal pads already ring.

## Format mapping

| Mode  | `.xty` drum block (`monophonic`, `poliphony`, `portamentoTime`, `portamentoLegato`, `monoRetrigger`) | `.xpm` program tags (`Mono`, `Program_Polyphony`, `PortamentoTime`, `PortamentoLegato`, `PortamentoQuantized`) |
|-------|------|------|
| POLY  | false, 0, 0.0, false, false | False, 0, 0.000000, False, False |
| LEAD  | true, 1, 0.0, false, true   | True, 1, 0.000000, False, False |
| GLIDE | true, 1, 0.1, true, false   | True, 1, 0.100000, True, False |

The portamento time `0.1` is a single named constant. Its unit on the
hardware is unverified (seconds or a normalised 0..1); the owner checks it on
the Live III after merge and the constant moves if needed.

## Where the choice lives

On the KEYS screen, a VOICE button cycling POLY → LEAD → GLIDE. Choosing
rewrites the instrument's three descriptors in place (`.xty`, the `.xpm`
twin, the sidecar) without re-rendering or copying WAVs, and the player
switches immediately. Makers keep producing POLY; there is no per-maker
setting.

## Phone player semantics

LEAD and GLIDE both make the phone's player monophonic: a new note chokes
every sounding voice over the choke fade. The phone does not slide pitch for
GLIDE; that is a hardware behaviour. Documented, not hidden.

## Out of scope

- KEEL and BALLAST programs live on `claude/keel-r1` and
  `claude/ballast-keygroup`, not on the default branch. Once this merges,
  those branches set LEAD (and GLIDE for the "Reduce" and "Night Drive"
  variants) in their own generators.
- Rendering extra octaves where pitch-shifting past a few semitones sounds
  poor.
- Reading voice mode back from imported XPN / `.xty` files.
- Pad colouring on drum kit export (a separate owner report, same day).
