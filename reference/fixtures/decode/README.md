# Decode-contract fixtures

The six WAVs here are the canonical sources for verifying the app's media
decode (`MediaExtractor`/Media3) against ground truth. They are generated —
`./gradlew :audio:generateDecodeFixtures` — from the signal defined in
`DecodeContract` (`:audio`): a sharp alignment click at 0.1 s, then
per-channel sines (440 Hz left / 554 Hz right) with 10 ms fades, one second
total, at 44.1 k / 48 k / 22.05 k, mono and stereo.

## The workflow (desktop / app session)

1. **Encode the twins** on any machine with an encoder:

   ```
   for f in decode_*.wav; do
     ffmpeg -i "$f" -c:a aac  -b:a 128k "${f%.wav}.m4a"
     ffmpeg -i "$f" -c:a libmp3lame -b:a 128k "${f%.wav}.mp3"
   done
   ```

   Commit the twins beside the WAVs.

2. **In the app's instrumentation test**: decode each twin to a `Snip`
   through the real import path, then

   ```kotlin
   val report = DecodeContract.verify(decoded, DecodeContract.fixture(rate, channels))
   assertTrue(report.pass, report.issues.toString())
   ```

`verify` tolerates what lossy codecs legitimately do (encoder delay and
padding via click alignment, gentle spectral loss via correlation) and
fails what they must never do: wrong duration, wrong channel count, or
content that isn't the signal. See `DecodeContractTest` for the exact
tolerance behaviour, demonstrated.
