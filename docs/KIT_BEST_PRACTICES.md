# Kit and pack best practices

Conventions the MPC world already agrees on. Following them is the difference
between a kit that feels like a kit and one that feels like a folder of WAVs.

## Documentation status

**Akai publishes no file-format specification.** There is no SDK, no XPM schema,
no XPJ schema. Everything in [`XPM_STRUCTURE.md`](XPM_STRUCTURE.md) is community
reverse-engineering, and the people who did that work say so themselves.

What Akai *does* publish is one level up — content packaging:

| Source | Covers |
|---|---|
| Support article: "MPC 2 Desktop And Standalone \| Create Your Own Expansion Packs" | official expansion-building walkthrough |
| **MPC Expansion Builder** (free, installs with the MPC software) | the reference implementation of pack packaging |
| MPC Software user guides (2.14, 3.7) | every parameter's meaning — but not the XML that stores it |

The Expansion Builder is worth downloading and pointing at a folder even though
we don't ship `.xpn`: it is Akai's own answer to "what is a well-formed pack,"
and its output is the closest thing to a spec that exists.

> Sourced from search-result summaries — Akai's own domains were unreachable
> when this was written. Verify against the Expansion Builder before relying on
> any specific value here.

## Sample hygiene

| | |
|---|---|
| Format | PCM WAV |
| Rate | 44.1 kHz |
| Depth | 16 or 24-bit |
| Names | ASCII, descriptive, zero-padded — `808_Kick_01.wav`, not `kick1.wav` |

No spaces-vs-underscore religion, but be consistent: browsing on a small
hardware screen is where sloppy naming actually hurts.

Everything the app captures is normalised at capture time, so export never has
to convert. See [`CONCEPT.md`](CONCEPT.md#trim).

## Pad layout

Two different things get called "the layout," and conflating them causes bugs:

1. **The pad→MIDI-note map** is fixed by the firmware. Pad A01 is note 37, A02 is
   36, A03 is 42. We reproduce it exactly in `PadNoteMap` and never deviate.
2. **Where you put your kick** is a kit-design choice, independent of (1).

The widely-used finger-drumming convention for (2):

```
A13  A14  A15  A16
A09  A10  A11  A12
A05  A06  A07  A08
A01  A02  A03  A04     ← kick, snare, closed hat, open hat
```

Kick on A01, snare A02, closed hat A03, open hat A04, and the rest of the kit
filling upward. Consistency matters more than the specific choice — muscle
memory is the whole point.

**This is what the v2 auto-place feature should target.** A classifier that drops
a detected kick on A01 and a detected snare on A02 lands the user somewhere
familiar; one that scatters them by capture order does not.

## Mute groups

Closed hat and open hat share a mute group (1-32) so triggering one chokes the
other. Without it, open hats ring through closed hats and the kit sounds wrong
in a way that is hard to diagnose by ear.

This is cheap to do automatically: any pad the classifier tags as a hat goes into
the same group. Model support is already in place — `Pad(muteGroup = 1)`.

## Pad colours

Colour by instrument family (kicks one colour, snares another, percussion a
third). It is how people find things at a glance on hardware.

Not implemented yet: colours live in the `ProgramPads` JSON blob, which the
writer currently emits as all-zero constants. See
[`XPM_STRUCTURE.md`](XPM_STRUCTURE.md#the-programpads-blob).

## Velocity layers

A pad can hold up to 4 layers with velocity ranges, so a hard hit triggers a
different sample than a soft one. This is what separates a convincing acoustic
kit from a static one.

Deliberately v2 — the writer emits all four layers but only fills layer 1. The
structure is already there, so this is a fill-in rather than a rewrite.

## One-shot vs note-off

Drums want **one-shot**: the whole sample plays regardless of when the pad is
released. Sustained or tonal material sometimes wants note-off behaviour instead.
Default to one-shot (`Pad(oneShot = true)`); expose it per-pad for anyone
sampling pads and textures.

## Expansion metadata (tier 2 only)

If we ever ship the browsable expansion format, `Expansion.xml` carries:

| Field | Note |
|---|---|
| Title | display name |
| Manufacturer | |
| Version | single digit |
| Identifier | reverse-domain notation, dots not spaces |
| Description | |

Plus a square artwork image, **1000×1000** PNG or JPEG, filename matching the
identifier; and optional MP3 previews in a `[Previews]` folder, each named to
match its `.xpm`.

See [`MPC_EXPORT.md`](MPC_EXPORT.md) for where these sit on disk.
