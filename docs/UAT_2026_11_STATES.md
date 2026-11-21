# UAT by user state, November 2026

Follow-up to [`UAT_2026_11.md`](UAT_2026_11.md) and [`UX_PASS_2026_11.md`](UX_PASS_2026_11.md).
Run on 2026-11-19 at `fbfda867d`. Three layers:

1. **JVM sim**: `./gradlew :shell:test --tests '*UatSimTest*'`. Green. It reports the same findings
   as the November pass and nothing new, so they are not repeated here.
2. **Source reading**: four read-only passes, one lens each: the new user and zero-data states,
   failures and interruptions, accessibility, and whether the words match the code.
3. **Emulator**: CI's debug APK for that commit (`snipsnap-debug-<sha>` from the `tests` workflow, so
   no local app build), `snipsnap_pixel` AVD, 1080x2400, Android 14, driven with `adb` taps,
   `uiautomator dump` and screenshots. The screenshots are not committed.

A finding marked **device** was seen on the emulator. One marked **source** was read end to end in
the code and not run. The last column is the owner's, ruled on 2026-11-20: **Fix** is to be done,
**Later** is real and parked, **Discuss** needs a design call before any code. The three Discuss rows were ruled on 2026-11-21 and are Fix now; "Fixed in #N" names the pull request that closed a row.

## States walked on the emulator

| State | Result |
|---|---|
| Fresh install, no permissions | One filled button, one outline button, a plain line. Fine. |
| Mic denied, then denied again | A dialog says why and offers OPEN SETTINGS. Fine. |
| Notifications denied, mic allowed | Listening starts; the bubble ask shows as a standing line with ALLOW. See S-5. |
| First snip | Lands on PROJECT > PADS with A01 lit and "Your first sound is on A01. Tap it." Fine. |
| System text at 1.3x | Fine. |
| System text at 2.0x | The frame breaks. See D-5. |
| Text size changed while a pad is open | The pad editor closes. See D-4. |
| A project's only kit deleted, then restored | Two defects. See D-2 and D-3. |
| Process killed in the background | Reopens on the project's PADS. Fine. |
| Damaged `groove.json` | Crash on every open of BEAT. See D-1. |
| 60 rows in the LIBRARY | Usable, slow to search by eye. See D-8. |

## Seen on the device

| ID | Severity | Finding | Ruling |
|---|---|---|---|
| D-1 | S1 | **A damaged `groove.json` crashes the app each time BEAT opens for that kit.** `GrooveScreen`'s load runs in a `LaunchedEffect` with no catch (`JsonException: unterminated object`). The dock's play button reads the same file and says "PLAY failed. Try again.", which is safe but gives advice that cannot work. Fixed separately; see the PR that cites this row. | Fix. First. Fixed in #1260. |
| D-2 | S2 | **Deleting a project's only kit hides the project.** The LIBRARY falls back to the cold panel: "Nothing recorded yet.", no project row and no NEW PROJECT, while the title bar still names the open project and a sample is saved. The delete confirm does not say a project uses the kit. | Fix, with D-3. The delete confirm names the projects that use the kit. Fixed in #1265. |
| D-3 | S2 | **RESTORE from TRASH does not reach the open project.** `project.json` still lists the kit and the folder is back, but PADS and BEAT say "This project has no kit yet" until the project is reopened. The app also forgets it as the last-open project, so the next start is the LIBRARY. | Fix, with D-2. Fixed in #1265. |
| D-4 | S2 | **A system text-size change throws the user out of the pad editor.** `MainActivity` handles only `orientation`, `screenSize` and `keyboardHidden`, so the Activity is recreated; the place, the open pad, the selection and the dock line are plain `remember` and are lost. A dark-mode switch was tried and was inconclusive. | Fix. |
| D-5 | S2 | **At 2.0x text the frame breaks.** The place tabs split mid-word (LIBRA/RY, SAMPL/E, PROJE/CT, CREAT/E), the kit switcher reads "TU…", BANK B reads "BANK B · E…", and the pad hint and the dock line are cut off. | Fix. |
| D-6 | S2 | **Three zero-data screens have no way forward.** SAMPLE's only button opens SAVED SAMPLES, which on a new install says "No samples yet." and offers nothing. CREATE says "Open a kit for new pads." with no door to a kit. INSTRUMENTS says "No instruments yet…" with no door to SYNTH. The `SnipsEmpty` KDoc rules that LISTEN belongs to the dock; the finding is that SAMPLE sends a new user to that empty list. | Fix (ruled 2026-11-21): each zero-data screen gets a way forward, even where that is LISTEN outside the dock; the `SnipsEmpty` ruling gives way. |
| D-7 | S2 | **The LIBRARY does not change while listening.** After LISTEN FOR YOUR FIRST SOUND the panel still reads "Nothing recorded yet." with the same filled button; only the dock shows "Listening." with SNIP and STOP. The next step (make a sound, then SNIP) is not named where the user is looking. | Fix. Fixed in #1269. |
| D-8 | S3 | **At 60 rows the list shows about six at a time.** The four fixed buttons under it take a quarter of the screen, every project sits above every kit, there is no search, and the open project is not pinned. | Later. |
| D-9 | S3 | **On a one-pad kit the step editor shows seven lanes.** KICK plays the one pad; SNARE, CLAP, HAT C, HAT O, TOM and PERC point at empty pads and make no sound. This may be the "drum word as fallback" from the BEAT-LANES ruling; the question is whether a lane on an empty pad should say so. | Fix (ruled 2026-11-21): a lane whose pad is empty is hidden, not drawn silent. |
| D-10 | S3 | **"…because the ear needs pads to play on."** BEAT's no-kit line names "the ear", a word no other screen uses. | Fix. Fixed in #1267. |

## Read in the source, not run

| ID | Severity | Finding | Ruling |
|---|---|---|---|
| S-1 | S1 | **Listening can fail after the dock has said "Listening."** The permission callback arms the service and speaks at once (`App.kt` ~1136). If another app holds the mic or `AudioRecord` refuses, `MicSessionService` (~200-239, ~276-330) logs, stops its foreground state and returns; nothing reaches the user. | Fix. Reproduce on a device first. Fixed in #1261, not yet reproduced on a device; its PR names the check. |
| S-2 | S2 | **ON CARD goes stale.** `DubStamp.status` never compares the stamp's time with the kit's last edit, so a kit exported weeks ago and changed today still reads ON CARD. Its own KDoc says the chip must never claim work is safely on a card when it is not. | Fix. |
| S-3 | S2 | **A project's EDITED time is `project.json`'s.** Pad and beat edits write other files, so a project worked on all day can read "EDITED 3 WEEKS AGO" and sink in a newest-first list. | Fix. |
| S-4 | S2 | **A card that refuses the copy reads as a failed export.** The phone write and the stamp finish first; then `CardWriter.copy` throws and the user sees "EXPORT failed. Check your storage and try again." Nothing checks that the folder grant still stands, and no retry door appears. | Fix. |
| S-5 | S2 | **Notifications denied and the bubble not allowed leaves no SNIP surface in another app.** The service keeps listening; no line says the notification is the way to SNIP, or that it is off. | Fix: a line saying the notification is the SNIP surface, or that it is off. Fixed in #1269. |
| S-6 | S2 | **CHOP edits are not kept on leaving CHOP.** The review model is plain `remember`, rebuilt from the sample on return; there is no Back guard. Relabelling is up to nine taps a slice (J3-CYCLE). | Fix (ruled 2026-11-21): CHOP keeps its edits on leaving; no Back guard. |
| S-7 | S2 | **Kit RENAME says "Renamed to X." before the projects are known to have followed.** A project whose `project.json` fails to rewrite is only logged. | Later. |
| S-8 | S2 | **SPLICE's cut point is drag-only.** `TapeSpliceScreen` has no semantics, no nudge buttons and no accessibility action; a TalkBack or switch user can only keep the default cut. | Fix. |
| S-9 | S2 | **SYNTH's SEND TO PAD is dimmed with no kit and no reason.** SNAP and SURFACE each have a line for this; SYNTH has none. | Fix. Fixed in #1267. |
| S-10 | S3 | **The contrast law has a gap.** It checks chrome inks on chrome and LCD inks on LCD, never `ink2`, `ink3` or `accent` on the LCD. In the five paper schemes those would be 2.1 to 3.2:1. No drawing site was confirmed on the LCD. | Fix the law to cover these pairs. |
| S-11 | S3 | **Limits are stated only after they are hit.** SONG's 32 sections, the 32-pattern cap and ORBIT's 8 sections (the same word, a different ceiling). | Later. |
| S-12 | S3 | **Old words.** "original in bin" on the pad's provenance line, "the shelf wouldn't read" in FIND SIMILAR SOUNDS; `DEVICE_TEST_GUIDE.md` and `app/README.md` still name NEW KIT ▸, TAKES+BIN, SHELF and SETUP. | Fix. Fixed in #1267. |
| S-13 | S3 | **Small targets.** Six rows are 44 dp tall (SETTINGS, pad velocity, record offset); two more have a 44 dp minimum. `Layout.MIN_HIT_TARGET` is 48 and no law enforces it. | Fix, with a law that enforces `MIN_HIT_TARGET`. Fixed in #1268. |
| S-14 | S3 | **Two sites still skip the click when disabled**: the count-in box in `GrooveScreen` (~1930) and the current take's row in `TakesBinScreen` (~718). | Fix. Fixed in #1268. |
| S-15 | S3 | **A kit with a damaged `kit.json` disappears from the LIBRARY** with no row and no line (`KitShelf` ~213), and a pad whose WAV is missing is silent until export's preflight. | Fix, with D-1: a damaged file is reported, not hidden. Fixed in #1266. |

## Checked and fine

- Every hold has a visible twin; the ▸ and ▾ marks follow their law.
- The LIBRARY and PADS screens have no unlabeled clickable node in the accessibility tree.
- Contrast passes on every pair the law covers, in all eight schemes.
- Deletes go to TRASH with a confirm and 30 days; EMPTY THE TRASH is a two-tap arm.
- MediaProjection declined, the picker cancelled, the mic dying mid-session and a muted mic each say so.
- One project tempo; a kit rename follows into open state on the path that succeeds.
- Reduce-motion and the haptics switch exist; haptics are silent while the mic is armed.

## Not covered

APP AUDIO, the SD card and the folder picker, a real export, spoken TalkBack, anything heard, and a
physical phone. A multi-kit project's switcher after one of its kits is deleted was read in source
(the count and the list keep the deleted kit) and not run. Sixty rows were seeded by copying kit
folders, which the app turned into sixty projects; only the layout was judged from that.

## Method notes

- Read the comment beside the code before filing. The source passes were told what is already ruled
  and to mark a clash as a challenge to a ruling, not a defect (D-6 is the one case).
- The device found D-2 and D-3, which no source pass did: each path is right alone, and the open
  project and the disk drift apart after a LIBRARY action.
- The installed debug package is `com.snipsnap.app.brassgrow`; `run-as` reaches `files/Kits`.
