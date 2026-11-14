# SPLAY AIR, BAR and SKIN (model 3, kept)

These are the last three bodies from the SPLAY voices design. They build on
[SPLAY_WIRE_RUB.md](SPLAY_WIRE_RUB.md) and work the same way: the pendulums and catch times are exactly
model 2's (`Splay.prepareModel2Excitation`), and only the body the catches strike changes. They live in
`SplayBodies` as `SplayBody.AIR`, `BAR` and `SKIN`.

Like WIRE and RUB, they are not yet in the picker, patch format, starters or keygroups.

## Owner listening

Round `splay-voices-3` (14 Nov 2026): **KEEP on all ten clips** (S13–S22), no notes. So AIR, BAR and SKIN
are heard as new bodies, not TONGUE settings. `SplayBodiesTest` pins every take behind them (the
same-settings take, the soft, medium and hard takes at C4, the six phrase notes, and AIR's held render)
and rebuilds the ten clip WAVs byte for byte:

| Clip | PCM16 WAV SHA-256 |
| --- | --- |
| S13 AIR, 16 mechanisms at C4 | `b774386e18a5673329fcf29c9a0becfb078cfa4efbb50f7c40722e9787822f6e` |
| S14 AIR, soft, medium, hard | `be8b4afe8a5a2d172788d5a396ef7b730c3cfdcf20cd7fa23577fb3209efe3ca` |
| S15 AIR, phrase | `ecc4b98c537552f97c16c67891e57f787030cc97defcd686b0cf469e3eb04e2f` |
| S16 AIR, held loop | `1925e1d2558944bec12cd9d5aaccf0e1ea8a43e8413e85f7628aedc731d2a34f` |
| S17 BAR, 16 mechanisms at C4 | `712f5d5bdc2fa04ce29a3f87041264a78ea01f1f575e700daca7f2b13a3d4caa` |
| S18 BAR, soft, medium, hard | `8bd0d5fcdede72d8bad5ee55dd9d9029539fb527b61f28ee2b88b488aab9af52` |
| S19 BAR, phrase | `2b17fb320c62b5c42fc1896d31cf5bdd8e02abb25a2310dc36c9aa5e4b83aa09` |
| S20 SKIN, 16 mechanisms at C4 | `d6f5c0607c2b1a5e9b1f68eaec8806f941bc01b36b068a59266d7ec67750dc45` |
| S21 SKIN, soft, medium, hard | `d5c538d806e337ddc38f22ff1f0656644596afbe20c384212774f8fda827ecc5` |
| S22 SKIN, phrase | `72ac5e560a1ae95169d8e15b24faf010c0da39d56b7d28e9f884d5153a3303b4` |

## What stays exactly as it was

- **TONGUE:** its models 1 and 2 are untouched (`Splay.kt` is not changed), and `SplayAcceptedBankTest`
  passes unchanged.
- **WIRE (kept):** `WIRE renders exactly the takes the owner kept` still passes.
- **RUB (kept in round 2):** `RUB renders exactly the takes the owner kept` pins the S9–S12 renders as
  they were before these bodies were added (hashes captured at dd2974194), and their four desk WAVs.
- **The one shared change:** the catch schedule takes a contact-width scale that is exactly 1.0 for
  WIRE and RUB, so their bytes cannot move.

## The bodies

### AIR: breath through a stopped tube

- **Body:** three narrow resonances at 1×, 3× and 5× the note. A tube closed at one end has only odd
  harmonics, which gives the hollow sound. The fundamental rings .55 s at C4, longer for lower notes.
- **Breath:** one shared seeded noise stream, low-passed (700 Hz plus up to 3.3 kHz with touch).
  - The upper arm's pressure keeps a low breath going between catches, as it keeps RUB's bow going.
  - **Each catch opens a valve:** a puff that opens in 3 ms (hard) to 12 ms (soft) and closes over
    75 ms. A tongued pop at its front is struck into the tube.
  - A little of the breath is heard outside the tube, with its rumble below 300 Hz removed.
- **Touch:** a harder touch blows brighter breath and overblows a little into the upper harmonics.
- **HOLD:** AIR holds, by the same circular-window method as RUB.

### BAR: a free mallet bar

- **Body:** the modes of a free bar, at 1 : 2.756 : 5.404 : 8.933 : 13.344.
  - The fundamental rings 1.25 s at C4, scaled by (C4/note)^.35.
  - Each higher mode rings 1/ratio^.6 as long.
- **Where it is struck:** the upper arm moves each stroke from the bar's middle, where the fundamental and
  the third mode speak, toward a third along it, where the 2.756 mode joins in. The mode gains are the
  real free-free beam mode shapes at that point.
- **Touch:** the catch's contact is the mallet. BAR's is 1.4 − .6 × velocity times the shared contact
  width, so a soft touch is a wider, duller stroke.

### SKIN: a damped drum head

- **Body:** membrane modes at 1 : 1.594 : 2.136 : 2.296 : 2.653 : 2.918, the Bessel zeros of the first
  six modes. The (0,1) mode rings .5 s at C4 and the others shorter: heavily damped.
- **Where it is struck:** the upper arm moves each stroke from near the centre, a round boom from the
  (0,1) and (0,2) modes, toward the edge, a ringing knock from the asymmetric modes. The gains are the real
  Bessel mode shapes at that radius.
- **Pitch bend:** a catch tightens the head for a moment, so its pitch jumps up and falls back over 20 ms,
  as a struck drum's does. A harder touch bends more, by up to about 20 cents.
- **Touch:** a hand or felt beater stays on longer than a pendulum catch. SKIN's contact is
  3 × (1.5 − .8 × velocity) times the shared width.

## How distinct they measure

C4, 16 mechanisms (the S1 setting), level-matched output, from one second in:

| Body | Brightness (centroid) | Harmonic share | Even overtones | Above 4 kHz | Swing around a catch | Soft/hard brightness |
| --- | --- | --- | --- | --- | --- | --- |
| TONGUE (kept) | 441 Hz | .84 | .87 | 0.00% | | |
| WIRE (kept) | 1194 Hz | .95 | .72 | 0.00% | 3.1 dB | .78 |
| RUB (round 2) | 3443 Hz | .70 | .55 | 1.12% | 6.7 dB | .54 |
| **AIR** | 2608 Hz | .80 | .06 | 0.33% | 6.7 dB | .35 |
| **BAR** | 825 Hz | .63 | .04 | 0.00% | 3.8 dB | .63 |
| **SKIN** | 353 Hz | .63 | .06 | 0.00% | 6.8 dB | .69 |

What the columns mean:

- **Harmonic share:** how much of the 50 Hz–10 kHz energy sits within 2% of a whole multiple of the
  note.
- **Even overtones:** of the near-harmonic energy above the fundamental, the share on even harmonics.
  TONGUE's overtones are mostly its second harmonic; none of the three new bodies has one.
- **Swing:** the median rise from the quietest point just before a catch to the loudest just after (the
  test RUB's re-tune introduced).
- **Soft/hard brightness:** velocity .3 against 1, after level matching.

The design's risk was that BAR and SKIN might read as TONGUE settings. By these numbers:

- **BAR:** it is 1.9× brighter, and its overtones are inharmonic where TONGUE's are a second harmonic.
- **SKIN:** it is darker than TONGUE (.80×), just as inharmonic, and bends in pitch.

Only listening decides it, which is why the round asks the question directly.

## Pitch

- **Measured once the catches stop:** for every body, the lowest resonance rings out within 0.2 cents of
  the note at MIDI 36–84 (3 cents for AIR at 84). The test requires 12.
- **AIR during the gesture:** it is harmonic, so it also reads within 4.4 cents during the gesture from
  MIDI 48 up (autocorrelation). At MIDI 36 its breath noise blurs that reading (−15 cents), so it is not
  asserted there.
- **SKIN during the gesture:** the pitch bend lifts it on busy flams, by design. Autocorrelation does not
  read inharmonic bodies well, so BAR and SKIN are tested on the ring-out only.

## Tests (`SplayBodiesTest`)

- **All five bodies:**
  - they strike exactly TONGUE's model-2 catches;
  - TONGUE renders the same bytes before and after them;
  - a softer touch is duller after level matching, and loudness still follows velocity;
  - renders are deterministic, under the .94 peak and under the 3 s render budget;
  - they refuse what they can't play.
- **Noise and HOLD:** AIR's breath follows the seed; BAR and SKIN have no noise. AIR and RUB hold, and
  WIRE, BAR and SKIN refuse HOLD.
- **AIR's held loop:** it closes (seam 0 at MIDI 36, 60 and 84), and before the window it is the finite
  note sample for sample.
- **Pitch:** AIR, BAR and SKIN ring out at the note (above).
- **New bodies:** AIR, BAR and SKIN are new bodies, not TONGUE settings, and differ from each other and
  from WIRE and RUB, by the numbers above. All keep under 3% of their energy above 4 kHz.
- **AIR's catches:** they swing more than 5 dB.
- **Kept takes:** `AIR, BAR and SKIN render exactly the takes the owner kept`, plus the WIRE and RUB pins.

## Still open

- **Registration:** none of the three is registered, and nothing here reaches the phone.
- **Render cost:** a warm C4 render measured 77 ms (BAR), 141 ms (SKIN) and 367 ms (AIR) on this
  machine. That is a JVM number, not a phone measurement.

## Why it is built this way

1. **The same catches, a new body.** Every body reads model 2's catch list and never changes it, so a new
   voice can't move TONGUE's sound, and the SPLAY gesture is the same in all six.
2. **Real mode shapes, not guesses.** BAR's gains are a free bar's actual vibration shapes, and SKIN's
   are a drum head's Bessel shapes. That is what makes "where the arm points" change the tone the way
   striking a real bar or drum in a different place does.
3. **Each catch must be heard.** The owner's RUB note was that catches need to be gestures. AIR gives each
   catch a valve puff with a pop; BAR and SKIN are struck bodies, so each catch is a stroke. SKIN's pitch
   bend makes every stroke a drum stroke.
4. **No hiss.** The owner found GRIND's bright top static-y. All three keep under 0.4% of their energy
   above 4 kHz.
5. **Touch changes the tone.** A softer touch is a wider contact (a softer mallet or hand) or darker
   breath, not just a quieter one.
