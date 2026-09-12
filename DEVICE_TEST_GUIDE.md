# SnipSnap device-test guide — the nine-screen wave

Everything in this build passed compile, 1002 JVM tests, and code review — but
zero seconds on real hardware. This guide is keyed to exactly what desk review
could NOT verify. Test in this order; each item names what "wrong" looks like.

## First five minutes
1. **FRESH TAPE → any starter → tap pads.** Do hits feel instant? SoundPool
   latency varies wildly per device; if taps feel spongy everywhere, say so —
   that's an engine-swap conversation, not a bug fix.
2. **KIT → tap the closed hat, then the open hat, then closed again.**
   The open hat must CUT OFF when the closed hat fires (choke group). If both
   ring together, chokes are broken on your device.
3. **Rotate your phone on every screen.** Nothing should restart or lose state
   (the manifest suppresses recreation — verify it holds on your OEM's skin).

## TAPE (the feel screen — judgement, not correctness)
4. Drag the tape. Does it track your finger 1:1? Release mid-drag — does the
   coast feel like tape, and does it glide onto a hit (SNAPPED toast)?
5. Play, then hit Home mid-play. **Audio must stop.** Reopen — the needle must
   be where you left it, not teleported to the end.
6. Long-press the LEFT reel: pencil rewind gag + toast.
7. Look at the needle and waveform: crisp lines, or hairlines? (We converted
   px→dp by review; your screen is the proof.)

## CHOP
8. TAPE: set IN/OUT, COMMIT → CHOP. The chopped region must be YOUR selection.
9. Tap chips to cycle classes; the row shows "YOU ✓". Long-press clears it.
10. MELODIC: pitch labels appear per row after a beat (busy "…" first).
11. SEND TO GRID → lands on a new kit, pads play.

## PAD SHEET
12. Long-press an assigned pad (~half a second). The press should STILL fire
    the hit, then the sheet opens. Unassigned pads: nothing.
13. Nudge LEVEL, immediately switch tabs, come back: the edit must have stuck
    (teardown flush). Nudge again and hit RE-TRIM immediately: same.
14. TREATMENT: pick CRUSH, listen (auditions after applying). Then GHOSTS ON,
    then try changing treatment — expect the polite refusal toast, not a crash.
15. EJECT → the pad empties; TAKES+BIN shows it with a countdown.

## GROOVE (needs a kit made by CHOP's SEND TO GRID)
16. The needle should be AMBER/ORANGE, distinct from the cyan readouts. If
    they look alike, the warn/amber split failed on your panel.
17. PLAY: notes light as they cross the needle; pads audibly trigger in sync.
    If you see "+N OFF-LANE" in the footer, those hits are heard, not drawn —
    by design.
18. FEEL is a −/+ stepper like SWING beside it, not a slider — five taps from
    centre to either end. Tap − toward TIGHT: EVERY note moves proportionally
    closer to the grid on each tap (not one note snapping at a time), and at
    TIGHT 100% they all sit on it. Tap the readout itself: it snaps back to
    AS PLAYED, and the capture plays back untouched — that centre must be
    exactly what you chopped, with no jitter. Tap + toward LOOSE: notes lean
    by the rolled template. RESEED is greyed out at centre and anywhere on
    the TIGHT side, live only on the LOOSE side. PROG B never moves, at any
    FEEL setting — the readout says "RIDES A · C · D" and that is literal.
19. EDIT STEPS: toggle cells (each auditions), CLEAR BAR, DONE, re-enter —
    your edits must persist. Kill the app, reopen: still there.
20. MIDI ▸ → check Files app: Android/data/com.snipsnap.app/files/exports/.

## PLAY
21. Mash pads fast with several fingers. Watch VOICES n/16 — it should track
    reality. Choke test again here at speed.
22. Top-of-pad taps should sound SOFTER (darker) than bottom taps.
23. ⟳ → landscape 8×2. **Then exit. Then leave PLAY. The app must return to
    portrait and STAY portrait.** (This was the wave's one Critical — verify
    the fix on hardware.) Also: back-gesture inside fullscreen should exit
    fullscreen only.
24. Background the app mid-mash: all sound stops.

## EXPORT
25. DUB a kit in a couple of formats; DONE shows the path; find the files in
    the Files app. Switch tabs mid-dub, come back: DONE state + toast must
    have survived.

## TAKES + BIN
26. Save-ish actions (treatments, edits) accumulate takes; RESTORE an old one
    and confirm the kit audibly reverts. EJECT a pad, find it in the bin,
    BACK restores it. EMPTY THE BIN NOW needs a second tap (armed confirm).

## LOOP
27. From SNIPS, → LOOP on a few snips of DIFFERENT lengths — the toast names
    the track and how many blocks it became. The shelf then shows LOOP ▸ n OF
    6 TRACKS; open it. Listen for the tracks pulling apart and coming back
    together rather than repeating in lockstep; that only happens when the
    block counts differ. Tap a track name to mute it, HOLD a block to clear
    that track, then send a snip again: it fills the leftmost empty track, so
    with one track cleared that is the one it lands on.
28. BOUNCE (bottom right) says how many bars it will render — that is the
    grid's full cycle when the cycle is short enough to be a snip, and a
    stated part of it when it isn't. Press it: the button reads BOUNCING…
    while it works, then the toast names the bars and the result is waiting in
    SNIPS. Mute a track first and confirm the bounce is missing it — what is
    heard is what is rendered. Then → LOOP the bounce back onto a free track:
    the grid can eat what it makes.

## Known blind spots (listen for these specifically)
- One-shot samples LONGER than ~6s stop responding to chokes after 6s (ledgered).
- LOOP has a door now (SNIPS → LOOP fills a track, then a LOOP row appears on
  the shelf), but it is the newest one in the app and the least walked: no
  tempo control, and a block tap that does nothing yet.
- SYNTH and HELP are stubs; capture doesn't exist yet.

Report anything that feels wrong even if you can't name why — "the tape drag
feels floaty" is a fully actionable bug report here.
