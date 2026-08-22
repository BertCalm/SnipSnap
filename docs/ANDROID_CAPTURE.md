# Android capture — how it works and where it breaks

## The API

`AudioPlaybackCapture`, added in Android 10 (API 29). It lets an app record the
audio *other apps* are playing.

Requirements, all of them:

- `MediaProjection` consent from the user
- `RECORD_AUDIO` runtime permission
- A foreground service declaring `foregroundServiceType="mediaProjection"`
- `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission (apps targeting Android 14+
  crash with `MissingForegroundServiceTypeException` without it)

```kotlin
val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
    .addMatchingUsage(AudioAttributes.USAGE_GAME)
    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
    .build()

val record = AudioRecord.Builder()
    .setAudioPlaybackCaptureConfig(config)
    .setAudioFormat(
        AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(44_100)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
    )
    .build()
```

Only `USAGE_MEDIA`, `USAGE_GAME` and `USAGE_UNKNOWN` streams are capturable.
That is exactly what we want — calls, alarms and notifications are excluded by
the platform, so we never have to filter them ourselves and never accidentally
record a phone call.

## Where it breaks

Apps control whether they can be captured, via
`AudioManager.setAllowedCapturePolicy()` or the `android:allowAudioPlaybackCapture`
manifest attribute.

- Apps targeting API 29+ **default to `ALLOW_CAPTURE_BY_ALL`** — so most apps
  are capturable, which is better than it sounds.
- Some notable ones opt out with `ALLOW_CAPTURE_BY_NONE`. Spotify and Chrome
  have historically blocked capture. YouTube has generally allowed it.

**This drifts between app versions.** Do not hard-code a compatibility list as
truth. Detect it at runtime: if a capture session produces digital silence while
the system reports audio is playing, we are being blocked — say so plainly and
offer the fallback, rather than handing the user an empty waveform.

## Fallback path (must be first-class)

Android's built-in screen recorder captures internal audio, and in practice gets
audio from some apps that block third-party capture.

```
User records with the system screen recorder
  → shares the resulting MP4 into SnipSnap
  → we demux the audio track with MediaExtractor
  → it enters the snip library like anything else
```

Document this in onboarding. It is the universal escape hatch, and treating it
as an embarrassment rather than a feature is a mistake — it makes the app work
everywhere.

## Explicitly out of bounds

- **No root or Xposed capture-policy overrides.** Play Store removal risk, and
  it puts the app adjacent to DRM circumvention.
- **No URL-based downloading.** Not from YouTube, not from anywhere. The app
  captures what the device is playing or ingests files the user hands it.
- **No cloud storage of snips.** On-device only. Cheaper to run and keeps the
  project entirely out of DMCA territory.

Marketing language matters here too: "sample your own sources," never "record
<named streaming service>."

## Session model

Android 14+ requires fresh user consent for **every** capture session — a
cached projection token is not reusable. Android 15 further restricts the
contexts in which a projection may start.

This pushes the design toward long-lived sessions:

```
One consent dialog  →  one long session  →  many snips
```

Not:

```
One consent dialog per snip   ← unusable
```

So: an explicit "Start Snip Session" action, a persistent notification, and a
floating overlay button that lives for the duration of the session. The consent
friction happens once, at a moment the user has already decided to go sampling.

## The overlay

`SYSTEM_ALERT_WINDOW` (draw over other apps) gives a floating bubble that
survives app switches. One tap = snip the buffer. This is what makes capture
feel instant instead of feeling like operating a recorder.

Pair with a **Quick Settings tile** to arm a session without opening the app.

## Permissions summary

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | required for playback capture *and* mic |
| `FOREGROUND_SERVICE` | the capture service |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Android 14+ |
| `SYSTEM_ALERT_WINDOW` | floating snip bubble |
| `POST_NOTIFICATIONS` | the ongoing session notification (Android 13+) |

Storage: use the Storage Access Framework for SD-card export. No broad storage
permission needed, and it is the only way to write to removable volumes anyway.

## Testing note

MediaProjection audio capture does not work meaningfully on emulators. This
needs a physical device in the loop from day one.

## Real-time discipline for the capture callback

Pinned from the open-source DSP literature before the Android layer exists:
the audio callback that feeds `RingBuffer` runs on a high-priority realtime
thread where allocation, locks, file/network I/O and JNI churn are all
forbidden — miss the buffer deadline and the capture clicks. The proven
pattern is a lock-free single-producer/single-consumer ring (Oboe callback
writes, snip-commit reads), pre-allocated at service start. The JVM
`RingBuffer` is the model, not the implementation: its Android incarnation
must be allocation-free on the write path.
