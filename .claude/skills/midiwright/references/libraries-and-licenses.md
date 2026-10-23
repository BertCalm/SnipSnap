# Libraries, licenses, and what SnipSnap's own license needs to be

Josh's rule: adapt code into SnipSnap's own shape rather than vendoring libraries. This file
says which libraries are worth reading, what each license allows, and what the missing
LICENSE file means for every MIDI decision. Licenses marked "verified" were read from the
project page on the date given; the rest are general knowledge and must be re-checked
before anything is copied. None of this is legal advice; it is the map a developer needs
before asking a lawyer the one question that matters.

## 0. The one fact first: SnipSnap has no LICENSE file

Checked at commit `b429ec2` on 2026-10-22: no `LICENSE`, `LICENSE.md` or `COPYING` at the
repo root; only font licenses under `app/licenses/`. Until a license is committed the code
is "all rights reserved" by default, which contradicts Josh's stated intent that the tool
will always be open source, and it blocks one concrete MIDI decision: whether Ableton Link
(GPLv2+ or a commercial license) can ever be bundled. Flag this once per conversation when
reuse comes up, then stop.

### What the choice decides

| If SnipSnap picks | Link | GPL synth and sequencer code (Surge XT, Vital, Dexed, Seq66, VCV) | Permissive code (ktmidi, Oboe, MTS-ESP, Mutable firmware) | Forks must stay open? | Matches Omnibus (MIT)? |
|---|---|---|---|---|---|
| **MIT** or **BSD** | only with Ableton's commercial license | read-only; reimplement from the ideas | yes, with attribution | no | yes |
| **Apache-2.0** | same as MIT | read-only (and GPL-2.0-only code is incompatible even to combine) | yes; adds an explicit patent grant | no | compatible (Apache code can be used in MIT projects, not the reverse without keeping notices) |
| **GPL-3.0** | yes (GPLv2 "or later" is compatible with GPL-3.0) | yes, as derivative work under GPL-3.0 | yes (MIT, BSD, Apache-2.0 all combine into GPL-3.0) | yes | Omnibus code can be used; SnipSnap code cannot flow back to Omnibus without relicensing |
| **AGPL-3.0** | yes | yes, except AGPL adds the network clause nothing here needs | yes | yes, including hosted use | same as GPL-3.0 |
| **MPL-2.0** | no (file-level copyleft does not satisfy Link's GPL) | read-only | yes | per file | compatible |

Things the choice does not change: reading GPL projects and reimplementing a published
algorithm in your own structure is allowed under any choice; copying a function body is a
derivative work under every choice; SnipSnap's existing dependency on Oboe (Apache-2.0) is
compatible with every row except a GPL-2.0-only choice, which nobody should pick.

### The question for Josh

The intent "anyone can explore making music, free and open, an alternative to a service"
is satisfied by every row. The decision turns on two things: whether SnipSnap wants to pull
from GPL synth and sequencer code directly (Surge XT's tuning library and MPE handling,
Seq66's Launchpad control, VCV's polyphony handling are the ones this skill would reach for)
and whether forks must remain open. If both, GPL-3.0; the cost is that SnipSnap code can no
longer be lifted back into MIT projects like Omnibus. If neither, MIT keeps everything
symmetrical with Omnibus and the two luthier skills' assumptions, and Link stays off the
table until Ableton is asked for a license (they grant them to free apps on request, per
their site; VERIFY current terms). Either way, commit the file; the MIDI roadmap's P1 clock
work needs the answer.

## 1. MIDI and audio libraries (the parts bin)

| Library | What it gives | License | Status (2026-10-22) | Use |
|---|---|---|---|---|
| **ktmidi** (atsushieno) | KMP `MidiAccess` (Android platform API; JVM via RtMidi, libremidi or ALSA; Kotlin/Native; iOS; Wasm), UMP types, `UmpTranslator`, SMF and SMF2 reader and writer, `ktmidi-ci` for MIDI-CI | MIT (verified) | active; 0.12.0 on Maven Central built with Kotlin 2.3.21 (release dates differ between trackers; check Maven Central) | **top pick**: adapt its UMP model and translator rules into `:kit`; its Android `MidiAccess` is the reference for what `MidiInput` should grow into |
| ResidentMIDIKeyboard, kmmk, compose-audio-controls (atsushieno) | a virtual keyboard app, a Compose keyboard, UMP bootstrap patterns | check each repo | research code | patterns for MIDI 2.0 on Android |
| AAP, Audio Plugins for Android (atsushieno) | instruments as `MidiDeviceService` and `MidiUmpDeviceService`, a plugin host model | check repo | experimental | the only Android precedent for a UMP virtual device |
| Google android-midi samples (MidiScope, MidiKeyboard, MidiSynthExample, MidiBtlePairing, MidiTools with `MidiFramer`) | API usage, BLE pairing flow, framing | Apache-2.0 | older, authoritative | copy patterns |
| android/ndk-samples native-midi | AMidi in a callback | Apache-2.0 | maintained | reference |
| Oboe | low-latency audio, `LatencyTuner`, OboeTester | Apache-2.0 | active; 1.9.0 in the build | the audio layer |
| RtMidi | C++ MIDI I/O, Android backend fixed October 2026 | MIT-style | active | desktop and iOS fallback; its PR #417 is a bug list to copy |
| libremidi | C++ MIDI I/O with MIDI 2.0 | BSD-style (**unverified**) | active | native cross-platform layer |
| kshoji USB-MIDI-Driver | a userspace USB MIDI driver for Android (pre-API 23 era) | **unverified** | legacy | the fallback idea G-Stomper still ships ("MIDI fallback driver") |
| MTS-ESP (ODDSound) | microtuning client, MTS SysEx parsing, local tuning table | 0BSD (verified) | active | easy win for microtonality |
| Ableton Link | tempo, beat, phase, start/stop sync over LAN | GPLv2+ or commercial (verified) | active | after the license decision |
| libpd (pd-for-android), Csound for Android | embeddable DSP environments | BSD-3-Clause, LGPL-2.1 | active | idea sources; not for the realtime path |
| FluidSynth | SoundFont synth with Android builds | LGPL-2.1 | active | SF2 playback if ever wanted (LGPL dynamic linking is fine under any choice) |

## 2. Synths, sequencers and firmware to learn from

| Project | Why it matters here | License |
|---|---|---|
| Surge XT | MPE and per-note modulation architecture, Scala and MTS tuning library (the readable reference), MIDI learn | GPL-3.0 |
| Vital, Dexed, Helm, Odin2 | MPE voice handling, modulation matrices | GPL-3.0 |
| ZynAddSubFX | MIDI learn and NRPN handling at scale | GPL-2.0+ |
| Hydrogen | drum sequencer patterns, MIDI action mapping | GPL-2.0+ |
| LMMS, Ardour, Zrythm | DAW MIDI models (clips, lanes, controller surfaces) | GPL-2.0, GPL-2.0, AGPL-3.0 |
| OpenMPT | tracker MIDI macros, a permissive DAW-class codebase | BSD-3-Clause |
| Seq66 | live pattern sequencer with Launchpad control in and out | GPL-3.0 |
| Orca | live-coding sequencer emitting MIDI; a grammar for generative patterns | MIT |
| TidalCycles, SuperCollider | pattern languages; mini-notation worth stealing as a UI idea | GPL-3.0 |
| VCV Rack, Cardinal | MIDI-to-CV and polyphony handling, MPE in a modular setting | GPL-3.0 |
| Mutable Instruments firmware | Grids (drum pattern map), Marbles (quantized random), Yarns (MIDI to CV, arps) | MIT (code) |
| dvhdr/launchpad-pro | open Launchpad Pro firmware: standalone grid behaviour | **unverified** |
| JUCE | `MidiBuffer`, `MPEInstrument`, `MPEZoneLayout`, `ump::` classes | AGPLv3 or commercial |
| Sunvox, Caustic | influential closed mobile workflows; the ideas are free, the code is not | proprietary |

## 3. The reuse procedure

1. Check SnipSnap's license (or its absence) against the table in §0. Flag once.
2. Read the source's license page on the day; record the date and the commit hash read.
3. Permissive source: adapt into SnipSnap's shape, credit in the KDoc the way `Bellows.kt`
   does for Omnibus (luthier's pattern), keep the notice the license requires.
4. Copyleft source and a permissive SnipSnap: read for the idea, cite the paper or manual
   it implements, write your own implementation with your own structure. Keep the line
   clear and record what you read.
5. Never vendor a library wholesale; `MidiWire` and `MidiGroove` are the house precedent
   (a parser and an SMF reader written to the app's own needs, tested in `:kit`).
6. Any SysEx table copied from a vendor's programmer reference is fact, not code; cite the
   document.
