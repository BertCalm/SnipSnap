# Android capture, 2026 reality check

Research pass against [`ANDROID_CAPTURE.md`](./ANDROID_CAPTURE.md), September 2026. That doc is not
repeated here — read it first. This document exists to answer one question: **has anything it
asserts gone stale, and what's newly true that it doesn't know about?**

Confidence is marked per claim. Primary sources (developer.android.com, AOSP/CDD, Play Console
policy pages) are cited directly; where only blogs/forums/consumer sites were found, that's flagged
and dated, and treated as lower-confidence corroboration, not fact.

---

## What has drifted since the doc was written

**1. The system screen recorder is very likely NOT the universal escape hatch the doc describes.**
This is the single biggest correction. The doc says the built-in recorder "gets audio from some
apps that block third-party capture" and calls it a "universal escape hatch." Multiple 2026
consumer sources, independently and consistently, say the opposite: apps that set
`ALLOW_CAPTURE_BY_NONE` (Spotify, Netflix, Disney+ named repeatedly) are muted for **every**
recorder, system or third-party — "enforced at the platform level and cannot be bypassed by any
standard app" ([EaseUS, 2026](https://recorder.easeus.com/screen-recording-resource/android-screen-record-no-audio.html); [DevX, 2026](https://www.devx.com/how-tos/how-to-screen-record-on-android-guide/); general search synthesis, 2026). This is technically consistent with the platform docs:
the `av-capture` page states system components get an automatic capture exemption **unless** the
app has specifically set `ALLOW_CAPTURE_BY_NONE`, which blocks capture "by none" — including
system — full stop ([developer.android.com/media/platform/av-capture](https://developer.android.com/media/platform/av-capture), last updated 2026-08-14). Apps that want to block third parties but tolerate OS-level use (captions,
accessibility) would use `ALLOW_CAPTURE_BY_SYSTEM`; apps that go all the way to
`ALLOW_CAPTURE_BY_NONE` block the system recorder too. **Confidence: moderate.** No AOSP source
was found that states this explicitly for the built-in Recorder app specifically (it may or may
not run through the same public policy-gated path as third-party apps depend on OEM), but the
mechanism is documented and the empirical reports are consistent across independent 2026 sources
naming the same apps. Treat "the system recorder always works" as **false** going forward, and
"the system recorder sometimes works where third-party capture doesn't" as unverified optimism at
best.

**2. Android 14's per-session consent restriction is stricter than "cached token not reusable."**
The current docs describe a harder rule: on Android 14+, `createVirtualDisplay()` throws
`SecurityException` if an app calls `getMediaProjection()` with the same consent `Intent` more than
once, **or** calls `createVirtualDisplay()` more than once on the same `MediaProjection` instance
([developer.android.com/media/grow/media-projection](https://developer.android.com/media/grow/media-projection)). That second clause matters for a
long-lived-session design: the *session* is bounded not just by consent-token reuse but by a single
`createVirtualDisplay()` call. This is a video-capture-path constraint; confirm whether it has any
bearing on repeated `AudioRecord` construction against a single `MediaProjection` token before
committing to the "one consent → one long session → many snips" model — the existing doc's model is
still directionally right, but the exact API-level mechanics should be re-verified against the
audio path specifically, which the docs conflate with video.

**3. Android 15 QPR1 added an auto-stop the doc doesn't mention.** A persistent status-bar chip
lets the user tap to stop any in-progress projection, and **projection automatically stops when the
device locks** ([developer.android.com/media/grow/media-projection](https://developer.android.com/media/grow/media-projection)). For a long-lived
capture-session design (floating overlay bubble, Quick Settings tile arm), this means: locking the
phone silently kills the session. `MediaProjection.Callback.onStop()` must be handled, and product
copy/UX should expect "session ended because you locked your phone" as a normal, frequent event —
not an edge case. **Confidence: high** — this is documented platform behavior, not projection.

**4. Android 15 blocks starting the media-projection foreground service from a `BOOT_COMPLETED`
receiver.** Narrow but concrete: don't try to auto-resume a capture session on device boot.
([developer.android.com/about/versions/15/changes/foreground-service-types](https://developer.android.com/about/versions/15/changes/foreground-service-types)) **Confidence: high**, primary source, though full text of the linked behavior-changes page
(background-start restrictions more broadly, Quick Settings tile eligibility) wasn't independently
re-verified beyond this one clause — worth a direct read of
`developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed` before shipping the
QS tile flow, since the doc's design leans on the tile as an "arm without opening the app" path.

**5. "Single app capture" (Android 14 App Screen Sharing) is video-only and does not touch audio.**
Worth stating plainly since it's easy to conflate: restricting a *screen* share to one app window
(excluding status bar, notifications, other apps) has no bearing on `AudioPlaybackCaptureConfiguration`,
which already filters audio by usage/UID independent of what's on screen
([developer.android.com/about/versions/14/features/app-screen-sharing](https://developer.android.com/about/versions/14/features/app-screen-sharing)). No drift here relative
to the doc — noted because a blog claimed otherwise (see finding under Q1 below) and that claim did
not check out.

**6. A widely-repeated 2026 blog claim that "Android 16 lets apps mark audio as protected,
preventing capture" could not be verified and is likely a conflation.** The only Android 16 privacy
feature confirmed against multiple sources is **video-only**: `View.setContentSensitivity()` /
`accessibilityDataSensitive`, part of Sensitive Content Protection, which hides sensitive *view*
content from screen shares/recordings — nothing about audio streams
([Android Developers Blog](https://developer.android.com/blog/posts/enhancing-android-security-stop-malware-from-snooping-on-your-app-data); [Android Authority](https://www.androidauthority.com/android-15-apps-selectively-hide-sensitive-content-screen-sharing-3436855/); [Guardsquare](https://www.guardsquare.com/blog/android-15-screen-spying-protection)). **Confidence: the "new audio protection" claim is low-confidence/likely wrong**; treat
`ALLOW_CAPTURE_BY_NONE` (Android 10-era, unchanged) as still the operative mechanism for blocking
audio capture in 2026, not a new Android 16 API.

---

## Per-question findings

### 1. OS restrictions since Android 14

- Android 14: App Screen Sharing (single-app capture) ships as the default consent-dialog option;
  video-only, doesn't affect audio capture filtering (see drift #5). `SecurityException` on token
  reuse / repeat `createVirtualDisplay()` calls (see drift #2). Foreground service type
  `mediaProjection` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` required, matches the existing doc.
  **Confidence: high**, primary source.
- Android 15: `BOOT_COMPLETED`-launch restriction for mediaProjection FGS (drift #4). QPR1 adds the
  status-bar chip + lock-triggered auto-stop (drift #3). **Confidence: high** for both, primary
  source, though the broader Android 15 behavior-changes page wasn't read in full.
- Android 16: No confirmed *audio*-specific capture restriction found beyond the unverifiable blog
  claim in drift #6. The confirmed Android 16 change in this space is `accessibilityDataSensitive`
  (video/UI, not audio). No primary-source evidence of a new per-session consent tightening beyond
  what Android 14 already established. **Confidence: low-to-moderate that "nothing new for audio
  landed in Android 16"** — this is an absence-of-evidence finding, not a confirmed absence; a
  targeted read of the Android 16 official behavior-changes pages (not attempted here beyond
  search-result summaries) would raise confidence.
- Quick Settings tile / background start: not independently confirmed beyond the `BOOT_COMPLETED`
  clause. **Explicitly unresolved** — the doc's design depends on a QS tile "arming" a session, and
  whether Android 15/16 restricts *that specific* start path wasn't confirmed either way in this
  pass.

### 2. What actually blocks capture in 2026

No single reliable, current, per-app compatibility matrix exists — consistent with the existing
doc's warning not to hard-code one. What was found, all secondary/blog-sourced and all dated 2026,
converging independently on the same short list:

- **Netflix, Disney+, Spotify, Apple Music, Amazon Prime Video, banking apps**: repeatedly and
  consistently reported as blocking both playback capture and screen-recording audio, attributed to
  DRM/content-protection policy (`ALLOW_CAPTURE_BY_NONE`). Multiple independent sources agree.
  **Confidence: moderate** — consistent secondary sourcing, no primary confirmation from the apps
  themselves, and the doc's own caution applies: this can change per app version without notice.
- **Games and YouTube**: repeatedly reported as allowing capture (consistent with default
  `ALLOW_CAPTURE_BY_ALL` behavior for API 29+ targeting apps that haven't opted out).
  **Confidence: moderate**, same caveat.
- **Chrome, Firefox, SoundCloud, Bandcamp, Instagram, TikTok, YouTube Music**: no reliable current
  reporting found either way in this pass. **Confidence: none — genuinely unknown, don't guess.**
  Design must not assume any of these work or don't; runtime detection (as the existing doc already
  specifies) is the only sound approach.

### 3. The system screen recorder fallback

Covered above as drift #1. Restated as direct answer: current evidence suggests the built-in
recorder is **subject to the same per-app `ALLOW_CAPTURE_BY_NONE` policy**, not exempt from it.
**This likely invalidates the doc's framing of it as a universal fallback for DRM/policy-blocked
sources** (Spotify, Netflix, etc. — the exact apps a music-sampling user would most want to
capture from). It may still be a broader-compatibility path than raw `AudioPlaybackCapture` for
apps that block only third-party capture via `ALLOW_CAPTURE_BY_SYSTEM` rather than
`ALLOW_CAPTURE_BY_NONE` — but no evidence either confirms or denies that narrower claim.
**Confidence: moderate that the strong "universal escape hatch" claim is wrong; low confidence on
the exact boundary of what it still helps with.**

### 4. What other apps in this space actually do

- **Koala Sampler** (iOS/Android): does **not** use `AudioPlaybackCapture` at all. Its default and
  only documented input is the device **microphone** (with a note that it switches to a connected
  USB interface's line input when available). Sound is captured by playing the source through
  speakers/headphone leakage or an interface loop, then recording via mic, with built-in "mangle"
  FX (Fuzz, Reverb, Octave, etc.) applied on the way in
  ([manual.koalasampler.com](https://manual.koalasampler.com/mobile/4-sample/); [manual.koalasampler.com/mobile/3-quick-guide](https://manual.koalasampler.com/mobile/3-quick-guide.html)). This is a real, shipping precedent for **not** building on
  `AudioPlaybackCapture` at all, and instead treating the mic as the primary/only capture path with
  effects as the differentiator. **Confidence: moderate-high** — the app's own manual, a primary
  source for the app's behavior, though not independently verified against the current 2026 build.
- No other named Android internal-audio sampler/recorder app with documented `AudioPlaybackCapture`
  usage and public messaging about blocked-app handling was found in this pass. This is itself a
  data point: the search turned up generic soundboard apps (Sound Sampler/Sound Sampler Lite) with
  no indication they attempt system-audio capture at all. **Confidence: low** that this is
  exhaustive — a deeper Play Store / APK-teardown pass would be needed to say more, and wasn't done
  here.

### 5. Play Store policy risk

- The **Device and Network Abuse** policy ([support.google.com/googleplay/android-developer/answer/16559646](https://support.google.com/googleplay/android-developer/answer/16559646))
  has **no explicit language about `MediaProjection`, screen recording, or audio playback capture**.
  What it does explicitly require: apps must **respect `FLAG_SECURE`** and must not build
  "workarounds to bypass the `FLAG_SECURE` settings in other apps." It separately prohibits
  circumventing the Android sandbox "to derive user activity or user identity from other apps."
  **Quote:** "[Apps must] respect the FLAG_SECURE setting" and must not create "workarounds to
  bypass the FLAG_SECURE settings in other apps."
- Read together, this is good news for a design that uses the sanctioned public API and respects
  each app's `ALLOW_CAPTURE_BY_*` policy at face value: there's no policy text found that treats
  *using* `AudioPlaybackCapture` as a violation — the violation risk described is specifically about
  **bypassing** a source app's protection mechanisms (`FLAG_SECURE`, sandbox circumvention), which a
  policy-respecting implementation doesn't do by construction.
- **Nothing found** that specifically blesses or blesses-with-caveats "records other apps' audio" as
  a Play Store category, positively or negatively. **Confidence: moderate** that the sanctioned API
  path itself carries no special listing risk beyond ordinary review scrutiny of permission usage
  (foreground service justification, `RECORD_AUDIO` purpose declaration) — but this is an
  absence-of-a-prohibition finding, not a green light from Google. No search turned up developer
  reports of app rejections specifically for using `AudioPlaybackCapture` as intended.

### 6. The mic fallback's viability

- `MediaRecorder.AudioSource.UNPROCESSED` (API 24+) is **not guaranteed on all devices**. Per CDD,
  a device must report support via `AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)`;
  if unsupported, the property call returns `null` and the device **must not** claim support
  ([source.android.com CDD, 5.11](https://android.googlesource.com/platform/compatibility/cdd/+/refs/heads/nougat-dev/5_multimedia/5_11_unprocessed-audio.md)). Where supported, the CDD specifies a frequency-response
  tolerance band (±10dB 100Hz–7kHz, wider outside that), not perfect flatness. **Confidence:
  high** on the mechanism (primary CDD source); **untested** in this pass how many real 2026 devices
  actually report support — that number was historically low and no current figure was found.
  **Design implication: `UNPROCESSED` cannot be assumed available; must runtime-check the property
  and fall back to `VOICE_RECOGNITION` (which has its own flat-response requirement and is more
  broadly supported) or plain `MIC`.**
- Per AOSP's pre-processing config docs
  ([source.android.com/docs/core/audio/implement-pre-processing](https://source.android.com/docs/core/audio/implement-pre-processing)): AEC and NS are the documented
  defaults for `VOICE_COMMUNICATION`; AGC is the documented default for `CAMCORDER`. Defaults for
  `MIC`, `VOICE_RECOGNITION`, and `UNPROCESSED` specifically weren't stated in the fetched excerpt,
  **except** one explicit, unambiguous requirement: noise suppression **must not** be enabled by
  default for `VOICE_RECOGNITION` — doing so is a stated CDD compatibility failure. This is a real
  guarantee builders can rely on for `VOICE_RECOGNITION`: no NS. It says nothing directly about AEC
  or AGC on that source, and nothing was found confirming whether apps can programmatically force
  effects off on sources where the HAL applies them by default (e.g., can an app disable AEC on
  `VOICE_COMMUNICATION`, or does source choice fully determine it). **Confidence: moderate** on the
  NS/`VOICE_RECOGNITION` guarantee (direct AOSP doc text); **low/unknown** on AEC controllability
  more generally in 2026 — this needs either device-lab testing or a deeper AOSP HAL read before a
  design leans on it.
- Nothing found changes the fundamental physical problem with mic-as-fallback: it's an
  acoustic-path capture (speaker → mic, room noise, potential AEC suppressing the very signal you
  want if the source device is also in a voice call), not a guaranteed clean digital tap. No 2026
  platform change was found that improves this story — Koala's approach (own it, apply character FX
  rather than trying to make it "clean") is the closest working precedent (see Q4).

---

## What this means for SnipSnap

Constraints only — not a design. Any capture design must account for:

1. **The system-recorder "universal fallback" claim in the existing doc should be treated as
   unverified-to-likely-false for DRM/policy-blocked sources**, which are exactly the sources
   (Spotify, Netflix-adjacent audio, Apple Music) a user is most likely to reach for. Do not market
   or build around "just use the screen recorder" as a guaranteed unblock path. It may still be
   worth keeping as a *secondary* path (it's still useful for the many apps that never opted out at
   all), but it cannot be the safety net for the blocked-app case specifically.
2. **Runtime detection of a blocked/silent capture stays mandatory, not optional** — this was
   already the existing doc's position and nothing found here weakens it; if anything, the murkier
   system-recorder story strengthens the case for it, since there is no dependable API-level way to
   know in advance which apps and which recording paths will produce silence.
3. **A locked screen silently ends any active capture session** (Android 15 QPR1+). Session-lifetime
   UX and the "one consent → many snips" model must treat this as a routine termination path with
   its own resume/re-consent flow, not an error state.
4. **`UNPROCESSED` mic capture must be runtime-checked, not assumed.** Fall back through
   `VOICE_RECOGNITION` (documented NS-disabled guarantee) to plain `MIC` if unsupported.
5. **The mic-only path (no internal-audio capture at all) is a validated, shipping strategy**, not a
   fallback of last resort — Koala Sampler builds its entire capture story on it. A design that
   treats mic capture as a first-class creative input (with character-shaping effects) rather than
   an apology for missing internal-audio access has real precedent.
6. **No Play Store policy text was found prohibiting the sanctioned `AudioPlaybackCapture` path
   itself**; the documented risk area is specifically bypassing `FLAG_SECURE` or sandbox
   protections, which policy-respecting capture (reading and honoring each app's
   `ALLOW_CAPTURE_BY_*`) does not do. This is not a guarantee of smooth review, just an absence of a
   specific prohibition found in this pass.
7. **Per-app capture-policy behavior (which apps block, which don't) remains explicitly a moving
   target** with only moderate-confidence secondary sourcing even for the most commonly cited
   examples (Spotify/Netflix block; YouTube/games allow) — the existing doc's instruction not to
   hard-code a compatibility list is reinforced, not weakened, by this research pass.
