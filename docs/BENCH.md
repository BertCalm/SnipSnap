# The bench

> **Paused 2026-09-11.** Tabled by the phone's owner: the changes landing
> around it — the September UAT round in particular — move enough of the
> ground that a live pass now would measure the old app. Nothing here is
> withdrawn or answered; it is waiting. Take the pause off when the app
> settles and work it top to bottom as written below.

Everything the code cannot judge, in one order, so a session with the
phone in one hand answers as much as it possibly can.

Every other document in `docs/` is what the machine can prove. This one
is what only ears and hardware can. It exists because the questions were
scattered across fifteen hundred lines of `FEATURE_PLAN.md`, the app
plan's Part 2 queue and a growing list in `app/README.md`, which meant
one phone session answered three of them and forgot the rest.

**How to use it.** Work top to bottom inside a section; the sections are
sorted by what you need in your hands. Write the answer on the `→` line —
a word is enough, and "no" with the exact error text is worth more than
"yes". A pass ticks its plan row; a failure comes back here as a bug and
the row stays open. Nothing here is blocked on anything else here.

Row IDs in brackets are `docs/FEATURE_PLAN.md` rows, so a result has
somewhere to land.

---

## A · The phone alone

The native audio stack is the whole of sections A1–A4 and five merges
rest on it. **A1 is closed** (2026-09-08) and the engine is proven on
hardware; A2–A4 have still not been heard. Do the rest in this order:
each one leans on the last.

### A1 · PLAY feels like an instrument [EEE3, the M4 exit test]

Open a kit, tap PLAY, drum on it with two thumbs for a minute.

- Is it tight enough that you would actually play it? That is the
  milestone's exit test and the only question that matters here.
- **Write down the number in the header** next to VOICES — the device's
  own round-trip latency ("9 MS"). `— MS` means the device declined to
  measure; the word `SHARED` after it means it refused the exclusive path
  and this is the slower one by construction.
- Does VOICES count *down* as one-shots finish? (Endings are reported by
  the engine now, not guessed from a timer, so a stuck count is a bug.)
- Does a closed hat cut a ringing open one, with no click at the cut?
- Does a gate pad stop when you lift, and a one-shot play out?
- PANIC: everything gone in about 20 ms?
- Swap kits mid-roll. Silence is correct. A crash is not.
- Header reads NO STREAM? The device refused every open — grab the
  logcat line tagged `PadEngine`.

→ **2026-09-08, the exit test passes.** Tight enough to play. The header
read **8–11 MS** with no `SHARED` after it, so the device opened the
exclusive low-latency path rather than falling back to the shared one —
that is the number every later latency reading is measured against.
VOICES counted down as one-shots ended. PANIC clears.

The **mid-roll kit swap** passed on a second pass, the same day. Nothing
in a stock kit rings long enough to still be sounding when the bank is
adopted, so the sound was made first: a few seconds PRINTed on SURFACE
and sent → PAD. Swapping kits while that pad played gave **silence** —
not a crash, and not a voice that carried on into the new kit. That is
the check that matters: the callback silenced and reported every voice
before the old sample bank was released, which is the one path in the
native code where getting it wrong is a use-after-free rather than a
wrong noise.

The **open-hat choke** passed the same evening: a closed hat cuts a
ringing open one, and there is no click at the cut. That is EEE2's
5 ms choke fade doing its job on hardware — a click there would mean
the voice was being cut dead rather than faded out, which is the
difference between a hi-hat and a bug.

**Gate vs one-shot** passed, and with it **A1 is closed**: a gate pad
stops when the finger lifts, a one-shot plays out regardless. Every
line of A1 is answered.

What that adds up to: the native pad engine works on hardware. The
exit test, the latency baseline, the endings ring, the panic fade, the
bank handshake over a live voice, the choke fade, and the gate. Five
merges were resting on this and none of it had been heard before
2026-09-08.

### A2 · KEYS sustains and lets go [EEE6]

Open an instrument from the shelf (INSTRUMENTS on THE SHELF).

**A fresh install has none, and the whole INSTRUMENTS section is hidden
when the list is empty** — so on a new phone the shelf shows kits and
nothing else, and there is no clue this section exists. Nothing is
broken; make one first. There are two doors, both on a pad's own sheet
(open a kit, tap a pad):

- **PAD FROM ANYTHING · HOLD IT FOREVER ▸ MAKE PAD ▸ INSTRUMENT.** Any
  pad qualifies — a drum lands as a drone. It writes a keygroup with a
  one-second arrival, a four-second body that loops with a 0.75 s
  crossfade baked into the wrap, and a 0.6 s release. That is exactly
  what the first two checks below ask about, so this is the door to use
  for them. The source must be between 0.05 s and 30 s long.
- **MAKE INSTRUMENT.** Greyed unless the pad is classed TONAL — the grey
  is a hint, not a lock, and pressing it on an unpitched pad refuses in
  words rather than doing something strange. Use it on something pitched
  and it detects the real root, which is what makes the tuning check
  below mean anything: a drone from the other door sits at C3 by fiat,
  so it is in tune with itself and proves nothing.

- Hold a note on a looping zone: does it sustain indefinitely, without a
  seam or a click at the loop point? [X6.3]
- Let go: does it fade over the instrument's release rather than stop dead?
- Play chromatically up an octave from the root: in tune? [W2.4]
- Eight notes at once, then a ninth: the oldest should give way.
- OCT ± or a layout change mid-note: silence, never a stuck note.
- The first key after opening may be silent for a moment on a big
  instrument — the zones load off the main thread. Longer than a moment
  is a bug.

→

### A3 · SURFACE plays and prints [CCC4–CCC9]

Open a kit, tap SURFACE.

- The readout line under the pad opens with the latency; note it here too.
- XY: a finger loops the pad, pitch across, filter up. Smooth, or stepped?
- XYZ: a second finger's pinch opens the drive. The roll of the phone
  moves resonance.
- MORPH: do the four corners sound like four different pads?
- PRINT, play for a few seconds, STOP PRINT: the toast names the length
  and the print is on TAPE. Chop it — it should behave like any snip.
- LATCH on, lift your finger: does the loop hold where you left it?
- BARS to 2 on a kit with a tempo, then PRINT: it should stop *itself* on
  the bar (5.2 s at 92 BPM) and the toast should say so.
- → PAD, STOP PRINT: the slot chooser opens. An empty pad takes the
  print; a taken pad is replaced and its original is in the bin; CANCEL
  sends the print to TAPE instead of losing it.
- (2026-09-08, out of A1's kit-swap check: PRINT ran for a few seconds,
  → PAD landed it on an empty slot, and the pad played the print back
  on PLAY. The toast wording, the bin behaviour on a taken pad and
  CANCEL → TAPE are all still unread.)
- PAD ◄ ►, find a sound in XYZ, SET A, three more, MORPH between them.
  Leave the screen and come back: are the corners and the pad still there?
  (They live in `surface.json` beside the kit.)

→

### A4 · The two native survival checks [CCC2, CCC5]

- Pull the headphones out mid-gesture on SURFACE. The stream should come
  back on its own within a moment.
- Watch logcat for `SurfaceEngine` / `PadEngine`. The line "exclusive
  openStream failed - trying shared" means your device refused the
  low-latency path and the shared fallback took over — the toast says so
  too, and so does the readout, which appends `SHARED`. Note it either
  way: it changes what latency you should expect.
- Note the latency on both screens. If PLAY and SURFACE disagree by much
  on the same phone, that is worth telling me — they open streams the
  same way.

→

### A5 · Capture, the riskiest milestone [M1]

- TAPE JAM with the mic: does the room land on the tape?
- INSIDE (screen capture): play something in another app and capture it.
  Does silence trip the watch and stop the tape?
- A dozen real captured hits, labelled by ear, into the calibration
  corpus — the classifier has never been fed anything but renders. [F2.4]

→

### A6 · The doors things come in and out of [F3.1–F3.3]

- Share a WAV, an MP3 and a screen-recorded MP4 into SnipSnap from
  another app. Each should land on TAPE with a toast naming its length.
- Share one while the app is *already open*: it must land in the same
  window, not a second one.
- KIT ▸ SHARE gives `<Kit>.xpn`; KITS ▸ BACKUP gives a dated zip. Send
  one to yourself and share it back: the kit lands beside the original as
  "NAME 2".
- Zip an MPC-saved `.xtd` with its `_[TrackData]` folder, share it in: it
  should land. The bare `.xtd` alone should refuse in words.
- Share in a ZIP with one good kit and one broken one: a message box, not
  a toast — "1 KIT LANDED ON THE SHELF. 1 SKIPPED." with the reason.

→

### A7 · The rest of the phone, in one pass

Quick looks, each a minute:

- FRESH TAPE: how long does the first dub take on a real phone?
- SETUP scheme flip: does the whole window repaint at once?
- Pad sheet v2: five closed group boxes, one screen, ◄ ► pinned while
  EJECT scrolls away.
- OUTSIDE: speaker into the room reamps a pad with the room on it; the
  toast names the trip in ms. A wired jack into a pedal and back reamps
  through the pedal.
- KEEP ROOM, then the ROOMS section on THE SHELF: hold a row to bin it,
  RESTORE from the bin list.
- MUTATE ▸ ANOTHER KIT and ▸ A FILE: both should name the partner in the
  child's lineage. UNDO puts the parent back byte for byte.

→

---

## B · The phone and the Live III together

One card session. Copy `testkit/` to the card first; the artifacts are
already built and committed.

### B1 · The headliner [AA1.3]

`SnipSnap Session.xpj` — open it. This one file exercises the kit, all
four instruments, the organ sustain loops and the sequence at once.

- Does it open?
- Do the sequences flip verse to chorus?

→

### B2 · Keys in tune, held pads forever [W2.4, X6.3]

`SnipSnap MPC3 Keys.xty` + its `Instruments/`.

- Chromatic and in tune from one sample?
- Are the EP's soft layers darker than the hard ones?
- Do held organ pads sustain forever? (The loop-point work proving itself.)

→

### B3 · The compatibility twin

`SnipSnap Keys`, the MPC 2 keygroup path. Same three questions as B2.

→

### B4 · The expansion [F6.1, Y5.3]

- Does the Live III's expansion import accept `SnipSnap_Factory.xpn`?
  Yes or no, and the **exact error text** if no.
- Does the expansion browser play the preview? (The open WAV-vs-MP3
  question — note it either way.)

→

### B5 · Feel and banks

Velocity kit: does the velocity ladder feel like a ladder? Shuffle kit
bank B: does the shuffle land where your ear expects? [W1.3 — play the
reconstructed clip beside the source capture; does the rhythm match?]

→

### B6 · Clips [Y2.3]

Does the Live III's clip list show and play all four (Chorus / Verse /
Intro / Bridge)?

→

### B7 · A standalone zone WAV [AAA2]

The question: a zone WAV carries its own `smpl` sheet — a root note and a
sustain loop — so loaded **on its own**, from the card's browser and
outside any program, it should arrive tuned and looping instead of at C3
and one-shot. Does the Live III read it?

**Use `AAA2Tone` for the answer, then your own `Held Keys` for the
sanity check.** The tone is built to make both halves audible rather than
a judgement call:

- Its root is **A4 (MIDI 69)**. The MPC's default for a sheet-less
  sample is C3 (48). Twenty-one semitones is nearly two octaves, so a
  sheet that is being ignored does not sound *slightly* off — the pad an
  octave and a half out is the answer.
- Its loop is the **last quarter-second**, and the tone is a steady saw.
  Held, an honoured loop sings forever. Ignored, it stops after two
  seconds. There is no in-between to argue about.

Verified in the bytes before it ever reached the card: the `smpl` chunk
sits between `fmt ` and `data`, unity note 69, one forward loop (type 0)
over frames 76586–87685 inclusive. So a "no" here means the MPC ignores
the sheet — never that there was no sheet to ignore.

Rebuild it any time with:

```
./gradlew :cli:snipsnapJar
java -jar cli/build/libs/snipsnap.jar keys --loop --name AAA2Tone A4-tone.wav
```

(any steady pitched note works as the input; the detected root is what
lands in the sheet — the export prints it, so check it says A4 before you
copy the card)

Copy `AAA2Tone.xty` and `AAA2Tone_[TrackData]/` onto the card. In the
browser, open the **WAV inside the TrackData folder** — not the `.xty`,
which would load the program and prove nothing.

→ plays at A4, or an octave and a half out:

→ holds forever, or stops after two seconds:

→ and the same for a real `Held Keys` zone:

Note it either way. If the MPC ignores the sheet, the sheet still costs
nothing — it is a few dozen bytes and every other sampler reads it.

---

## C · The one save no corpus can supply [W3.4, Y6.3, HH1.4]

**This is the highest-value item in the whole document** and it takes
five minutes.

Build any drum program *on the Live III itself* — a few pads, whatever is
to hand. Save it. Drop the save into `reference/golden/liveiii-36/`.

That one file settles what the firmware itself writes, and with it every
remaining value question the corpus can only guess at: `fineTune` units,
per-pad colour dialects, firmware defaults, and the slice-chunk shape
that HH1.4 is blocked on. It also becomes the fixture the round-trip
claim is tested against, instead of our own output.

→

---

## Recording what you find

A pass: tick the plan row and say so here — I will move it in
`FEATURE_PLAN.md`.

A failure: paste the line you wrote plus, where there is one, the logcat
line (`PadEngine`, `SurfaceEngine`, `SoundPool`) or the MPC's exact error
text. Those two things are usually enough for a fix without a second
session.

A surprise on the hardware — anything the MPC does that we did not
predict — is worth more than a pass. It becomes a corpus guard [Y6.3].
