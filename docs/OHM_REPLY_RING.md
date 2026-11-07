# OHM Reply and Ring

The owner accepted the [Reply and Ring listening study](https://ohm-snipsnap-audition.bertcalm.chatgpt.site/reply-ring/)
and asked to continue integration. Production renderer 5 keeps its complete
seven-second pluck, companion timing, damping, bridge and source seeds.
Its five raw PCM16 WAVs are pinned to the actual approved study, rather than
to newly generated expectations.

The characteristic gesture is **pluck → companions answer → shared resonance fades**.

## Playing it

Choose **OHM → Natural Replies** for the approved middle sound. A fresh OHM
bench starts there. An existing bench, pad, draft or saved preset retains its
original recipe; selecting a new named preset is the explicit way to change
the instrument.

| Control | Middle | What changes |
| --- | --- | --- |
| REPLY | .5 | Spacing of the five replies, from a tight cluster to separated answers |
| RING | .5 | The two companions' damping, from short responses to a ringing tail |

The five additional factory recipes are Natural Replies, Tight Replies,
Spaced Replies, Short Ring and Long Ring. They retain the audition's C4,
velocity .8, seed 731, TOUCH/BODY .5, LENGTH .65, MEDDLING .9 and COMMUNE 1.
The original Solo, Argument, Communion, Quiet Company and Gather recipes,
and the original sixteen-pad starter kit, remain on renderer 2.

REPLY/RING are saved performance fields alongside note, velocity and seed.
Changing a macro, switching engines, saving/reopening, transposing, applying
native velocity or sending a pad retains them. Legacy recipes missing a
renderer still decode as renderer 1. Existing renderer-1–4 JSON and audio
retain their own paths; the generic `Ohm.DEFAULT_MODEL` remains 2.

## Finite instruments and export

The new voice is finite and plucked, with STEADY articulation. It does not
reinterpret source controls, GATHER or HOLD. Those capabilities stay with
the older saved instruments, and source/sustain upgrades explicitly target
renderer 4 through `Ohm.SOURCE_MODEL`.

SEND creates a normal tonal pad with its complete editable recipe. MAKE
INSTRUMENT uses native C3–C5 zones through the existing keygroup player.
Each sample plays to its end or fades on key release; it has no sustain
loop. RELEASE controls that real note-off fade, not the generated seven-second
waveform. The MPC 3 and MPC 2 programs use note-on playback with no loop,
and finite samples carry no fabricated sampler loop markers.

The five-program handoff is generated with
`./gradlew :shell:generateOhmReplyRingHandoff`. Keep each `.xty` or `.xpm`
with its sample folders when loading it on the MPC. It is a keygroup package,
not a drum-kit import.

CLI controls are `--ohm-reply` and `--ohm-ring`, each 0..1. New controls or
an explicit renderer-5 selection choose the approved defaults when no saved
patch is supplied. Saved patches remain authoritative; incompatible source,
gesture, HOLD and downgrade requests fail before output is created. Ordinary
legacy command defaults stay on renderer 2. `--all` includes all ten factory
recipes; selecting a renderer restricts the corresponding factory family.

## Why it is built this way

- The unchanged study signal path keeps the approved sound as the reference.
- Reply changes entry timing; Ring changes physical loss with a fixed
  excitation calibration. Neither adds a new memory layer or output-gain knob.
- A separate saved renderer protects older patches and keeps their source and
  sustain semantics intact.
- Finite keygroups reuse real note-off behavior without inventing a held
  version of a sound that was approved as a finite pluck.

## Evidence and limits

Production raw hashes match the five immutable approved WAVs. The study's
earlier 36-render checks established exact solo/uncoupled-primary retention,
bounded pressure, note tuning, and concrete timing/decay changes. Its normalized
post-onset temporal-energy changes were 10.5–11.6% between Reply levels and
14.8–22.0% between Ring levels. The owner supplied the listening approval.

The companion decay values remain model targets. The inherited feedback
ceiling shortens high-register tails, and late mixed pitch estimates can
follow a higher harmonic as the pluck fades. This behavior was already present
in the accepted depth reference; foreground tuning and mixed harmonic register
are checked separately. Arbitrary transpositions and physical MPC playback
still require hardware listening.

Focused integration, full JVM, Android build and export results are recorded
in the build receipt accompanying the installation and instrument downloads.
