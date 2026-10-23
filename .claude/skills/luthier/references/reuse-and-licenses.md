# Open-source reuse and licenses

Josh's rule: adapt code into SnipSnap's own shape rather than vendoring libraries. SnipSnap is
Kotlin and offline; most DSP libraries are C/C++ and real-time, so adaptation is also the only
practical route. This file says what is safe to read, what is safe to translate, and what needs
a decision first. Licenses were checked on 2026-10-21 (the ones marked "verified" were read
from the project page that day; the rest are from general knowledge and should be re-checked
before anything is copied).

## 0. One fact about SnipSnap first

**The SnipSnap repository has no LICENSE file at its root** (only font licenses under
`app/licenses/`). Josh intends it to be open source, but until a license is committed the code
is "all rights reserved" by default, and it is not yet clear whether copyleft code (GPL) could
ever be mixed in. Two consequences for this skill:
- Translating an **algorithm** from a GPL source into your own code is fine when you take the
  idea and write your own implementation with your own structure; copying a function body is
  a derivative work. Keep the line clear and record what you read.
- When Josh picks a license (MIT like Omnibus is the natural choice), GPL-only sources
  (SigLib's free tier, KFR, GNU Radio, most of Faust's `physmodels.lib`) stay read-only
  references; MIT/BSD/Apache/MPL sources can be adapted with attribution.
Flag the missing LICENSE in the first review that touches reuse; do not keep flagging it.

## 1. The libraries Josh named

| Library | License | What it is good for here | How to use it |
|---|---|---|---|
| ThinkDSP (Allen Downey) | code MIT; book text CC BY-NC-SA 4.0 (verified) | Python intuition for spectra, filters, convolution, autocorrelation; the friendliest entry for someone newer to code | read the notebooks; use its approach in prototypes; never paste book prose into docs |
| arduino-audio-tools DSP wiki (pschatzmann) | the wiki page lists STK, Maximilian, Faust, Mozzi with no licenses stated (verified) | a pointer page, not a library | follow the links; check each library's own license |
| liquid-dsp (jgaeddert) | MIT/X11 (verified); note FFTW is GPL if linked | SDR-grade filter design (windowed sinc, Parks-McClellan), resampling, polyphase filterbanks, AGC, PLLs, NCOs | adapt filter-design routines when `Dsp.decimate`'s fixed kernels are not enough; its modems are irrelevant |
| SigLib (Numerix / Delta Numerix) | GPLv2 for personal and educational use only; commercial license for everything else (verified) | a very broad classical DSP catalogue (filters, FFTs, modulation, control) | read-only reference for algorithm names and structure; do not adapt code |
| GNU Radio | GPL-3.0 (general knowledge; verify) | block-diagram thinking, filter design tools (`gr-filter`), polyphase resamplers | read-only reference; the architecture idea (flowgraph of blocks) is free to borrow |
| Eigen | MPL-2.0 (general knowledge; verify) | linear algebra: eigen-decomposition for modal analysis, sparse solvers for coupled networks | adaptation is not practical (C++ templates); use NumPy/SciPy in prototypes, and Kotlin's `Modes.symmetricEigen` exists for small symmetric problems |

## 2. Libraries worth knowing for synthesis (the parts bin)

| Library | License | Why it matters |
|---|---|---|
| Omnibus / XOceanus (Josh's own) | MIT (verified; SPDX headers) | the primary idea mine; credit the way `Bellows.kt` does |
| STK (Stanford) | MIT-style permission notice (verified) | canonical physical models: plucked, bowed, blown, brass, modal, FM; the reference implementations many others copy |
| DaisySP | MIT (verified) | clean C++ versions of KS, modal, resonators, drum models, grains, wavefolder, overdrive, DC blocker |
| Mutable Instruments firmware | MIT (general knowledge; verify per module) | Plaits/Rings/Clouds/Elements: modal and string resonators, granular, the best macro laws in the business |
| ChowDSP (`chowdsp_utils`) | BSD-3-Clause (general knowledge; verify) | tape (hysteresis), ADAA waveshaping, filters; the TAPE section already cites it |
| Faust standard libraries | mixed: compiler GPL-2+, `physmodels.lib` and `filters.lib` GPL-2+, `oscillators.lib` LGPL-2.1+, `instruments.lib` STK-4.3 (verified from the Debian copyright file) | the most complete readable catalogue of physical-model one-liners; read it for structure, adapt only from the permissive files or reimplement from the paper it cites |
| KFR | dual GPLv2+ / commercial (verified) | fast FFT and filter design; read-only unless SnipSnap goes GPL |
| Maximilian | MIT (general knowledge) | simple oscillators, filters, envelopes, grains |
| Mozzi | relicensed to LGPL in version 2 (general knowledge; verify) | integer DSP tricks for cheap synthesis |
| CMSIS-DSP | Apache-2.0 (general knowledge) | fixed-point and fast float kernels |
| SciPy / NumPy | BSD-3 | the prototyping harness (`scipy.signal` filter design, resampling, spectrograms) |
| Airwindows | MIT (general knowledge) | hundreds of small, opinionated effects; good for "what makes this character" reading |
| Surge XT, Vital, Dexed, ZynAddSubFX, Cardinal/VCV | GPL-3 (general knowledge) | read-only references for how a shipping synth structures oscillators, filters and modulation |
| JUCE | AGPL-3 or commercial (general knowledge) | Omnibus's framework; irrelevant to SnipSnap's Kotlin side |

## 3. The reuse procedure

1. **Read before you write.** Find the implementation, read it, name the paper it cites.
2. **Classify**: mechanism (port), parameter law (often the valuable part), scaffolding (drop).
3. **Check the license against the table.** Permissive: adapt with a credit line in the KDoc
   (`<Author>, <file>, <copyright>, <license>`), the Bellows pattern. Copyleft: reimplement from
   the underlying paper, cite the paper, and note that you read the GPL code for understanding.
   Unclear: ask Josh before copying anything.
4. **Adapt to the house**: `Dsp.seedFor` for randomness, `Modes`/`Strings` for resonators,
   the house output stage, Float where the goldens expect Float, determinism.
5. **Record provenance** in the spec's references section with the commit hash you read.
6. **Never vendor a library into `synth/`.** Gradle dependencies are a repo-level decision with
   a CVE scan attached (`dependencyCheckAggregate`).

## 4. Trademarks and trade dress (restated because reuse prompts trip it)

Product research may name products. Engine, voice and preset names may not, and the test
`PresetTestSupport.trademarkBlocklist` grows with every brief that named a maker (add the
terms). No faceplate recreation; no samples of the original. Circuits whose patents expired
are fair to emulate as sound.
