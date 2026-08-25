# Loop Player 01 — Audio I/O Primitives Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `:audio` the ability to read sample data off disk and convert it between sample rates, so the loop player can bake blocks.

**Architecture:** Two new objects in the existing `:audio` module, both pure functions over `Snip`. `WavReader` parses RIFF itself and decodes 8/16/24/32-bit PCM and 32-bit float into interleaved floats. `Resampler` does band-limited interpolation with a windowed-sinc kernel, offline only. Neither touches a thread, a file handle beyond `readBytes()`, or any other module.

**Tech Stack:** Kotlin 2.0.21, JVM 17, `kotlin.test` on the JUnit Platform, Gradle 8.14.3.

**Spec:** `docs/superpowers/specs/2026-08-22-loop-player-design.md` (sections 02, 04, 06, 09, 10)

## Global Constraints

- **Kotlin 2.0.21, `jvmTarget` JVM_17.** Already set in `audio/build.gradle.kts`; do not change it.
- **`:audio` must keep zero compile-time dependencies.** `dependencies {}` in `audio/build.gradle.kts` may gain nothing. `WavInfo` in `:xpm` is `testImplementation` only and must stay that way — parse RIFF locally instead.
- **`Snip(samples: FloatArray, channels: Int, sampleRate: Int)`** — `channels` must be 1 or 2, `sampleRate` > 0, `samples.size % channels == 0`. Its `init` block enforces all three and throws `IllegalArgumentException`.
- **`Snip.equals` compares `channels`, `sampleRate` and `samples.size` only — never sample values.** Never assert audio correctness with `assertEquals(snipA, snipB)`. Compare `samples[]` element-wise.
- **Test style:** `kotlin.test` imports, backtick-quoted test names, private helper factories at the top of the class. Match `audio/src/test/kotlin/com/snipsnap/audio/CleanupTest.kt`.
- **Commit style:** the repo does not use Conventional Commits. Write a short evocative sentence, e.g. `EQ joins the rack: three plain words, RBJ cookbook underneath`. No `feat:` / `fix:` prefixes.
- **Test command:** `./gradlew :audio:test --tests 'com.snipsnap.audio.<ClassName>'`

## Prerequisite — read this before Task 1

**There is no Java runtime on the development machine.** Not on `PATH`, not in `/Library/Java/JavaVirtualMachines`, no Homebrew `openjdk`, no SDKMAN. `./gradlew` cannot run: the wrapper is a shell script that invokes `java -jar gradle-wrapper.jar`, so it dies before downloading anything. `~/.gradle/wrapper/dists` does not exist.

Install **JDK 17** (Temurin 17 matches CI) before Task 1. Verify with:

```bash
java -version          # expect: openjdk version "17.x"
./gradlew :audio:test   # expect: BUILD SUCCESSFUL, 384 existing tests pass
```

Do not start Task 1 until that baseline is green. A failing baseline makes every later failure ambiguous.

**Never pipe gradle through `tail` without `set -o pipefail`.** `./gradlew test | tail -60` reports `tail`'s exit code, turning a hard build failure into a silent exit 0.

---

### Task 1: WavReader — RIFF parsing and 16-bit PCM

**Files:**
- Create: `audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt`
- Test: `audio/src/test/kotlin/com/snipsnap/audio/WavReaderTest.kt`

**Interfaces:**
- Consumes: `Snip` from `audio/src/main/kotlin/com/snipsnap/audio/Cleanup.kt:12`; `WavWriter.write(out: OutputStream, snip: Snip, depth: WavWriter.BitDepth)` from `WavWriter.kt`.
- Produces: `WavReader.read(bytes: ByteArray): Snip` and `WavReader.read(file: File): Snip`. Task 4 onward and all of Plan 02 depend on these two signatures.

- [ ] **Step 1: Write the failing test**

Create `audio/src/test/kotlin/com/snipsnap/audio/WavReaderTest.kt`:

```kotlin
package com.snipsnap.audio

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WavReaderTest {

    /** Round-trip helper: encode with the writer we already trust, decode with the new reader. */
    private fun roundTrip(snip: Snip, depth: WavWriter.BitDepth): Snip {
        val out = ByteArrayOutputStream()
        WavWriter.write(out, snip, depth)
        return WavReader.read(out.toByteArray())
    }

    private fun ramp(frames: Int, channels: Int = 1) =
        Snip(FloatArray(frames * channels) { (it % 200) / 200f - 0.5f }, channels, 44_100)

    /**
     * Snip.equals compares format and length only, never sample values — so a
     * naive assertEquals would pass on completely different audio.
     */
    private fun assertSamplesClose(expected: Snip, actual: Snip, tolerance: Float) {
        assertEquals(expected.channels, actual.channels, "channels")
        assertEquals(expected.sampleRate, actual.sampleRate, "sampleRate")
        assertEquals(expected.frameCount, actual.frameCount, "frameCount")
        for (i in expected.samples.indices) {
            val d = abs(expected.samples[i] - actual.samples[i])
            assertTrue(d <= tolerance, "sample $i: expected ${expected.samples[i]}, got ${actual.samples[i]}")
        }
    }

    @Test
    fun `reads back a 16 bit mono wav`() {
        val original = ramp(512)
        val decoded = roundTrip(original, WavWriter.BitDepth.PCM_16)
        // 16-bit quantisation step is 1/32768; allow one step.
        assertSamplesClose(original, decoded, tolerance = 1f / 32768f)
    }

    @Test
    fun `reads back a 16 bit stereo wav preserving channel order`() {
        // Left rail high, right rail low: a channel swap or interleave bug shows immediately.
        val original = Snip(FloatArray(400) { if (it % 2 == 0) 0.75f else -0.75f }, 2, 44_100)
        val decoded = roundTrip(original, WavWriter.BitDepth.PCM_16)
        assertSamplesClose(original, decoded, tolerance = 1f / 32768f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :audio:test --tests 'com.snipsnap.audio.WavReaderTest'`
Expected: FAIL — compilation error, `Unresolved reference: WavReader`.

- [ ] **Step 3: Write minimal implementation**

Create `audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt`:

```kotlin
package com.snipsnap.audio

import java.io.File

/**
 * Reads PCM and float WAVs back into a [Snip].
 *
 * The counterpart to [WavWriter], which only ever wrote. Nothing in SnipSnap
 * read sample data off disk until the loop player needed to bake blocks —
 * capture produced buffers and export consumed them, and the MPC did the
 * playing.
 *
 * Parses its own RIFF header rather than borrowing xpm's WavInfo: :audio has no
 * compile-time dependencies and keeping it that way is the point.
 */
object WavReader {

    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3

    fun read(file: File): Snip = read(file.readBytes())

    fun read(bytes: ByteArray): Snip {
        require(bytes.size >= 12) { "not a WAV: only ${bytes.size} bytes" }
        require(tag(bytes, 0) == "RIFF") { "not a WAV: missing RIFF header" }
        require(tag(bytes, 8) == "WAVE") { "not a WAV: missing WAVE tag" }

        var format = -1
        var channels = -1
        var sampleRate = -1
        var bits = -1
        var dataAt = -1
        var dataLen = -1

        var p = 12
        while (p + 8 <= bytes.size) {
            val id = tag(bytes, p)
            val size = leInt(bytes, p + 4)
            val body = p + 8
            if (size < 0 || body + size > bytes.size + 1) break
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "fmt chunk is $size bytes, need at least 16" }
                    format = leShort(bytes, body)
                    channels = leShort(bytes, body + 2)
                    sampleRate = leInt(bytes, body + 4)
                    bits = leShort(bytes, body + 14)
                }
                "data" -> {
                    dataAt = body
                    dataLen = minOf(size, bytes.size - body)
                }
            }
            // RIFF chunks are word-aligned: an odd size is followed by a pad byte.
            p = body + size + (size and 1)
        }

        require(format != -1) { "no fmt chunk" }
        require(dataAt >= 0) { "no data chunk" }
        require(format == FORMAT_PCM) { "unsupported WAV format code $format" }
        require(bits == 16) { "unsupported bit depth $bits" }

        val bytesPerSample = bits / 8
        val stride = bytesPerSample * channels
        val frames = dataLen / stride
        val out = FloatArray(frames * channels)
        for (i in out.indices) {
            out[i] = leShort(bytes, dataAt + i * bytesPerSample).toShort() / 32768f
        }
        return Snip(out, channels, sampleRate)
    }

    private fun tag(b: ByteArray, at: Int): String = String(b, at, 4, Charsets.US_ASCII)

    private fun leShort(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun leInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :audio:test --tests 'com.snipsnap.audio.WavReaderTest'`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt audio/src/test/kotlin/com/snipsnap/audio/WavReaderTest.kt
git commit -m "The reader arrives: audio finally reads back what it wrote"
```

---

### Task 2: WavReader — 8, 24, 32-bit integer and 32-bit float

**Files:**
- Modify: `audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt`
- Test: `audio/src/test/kotlin/com/snipsnap/audio/WavReaderTest.kt`

**Interfaces:**
- Consumes: `WavReader.read(bytes: ByteArray): Snip` from Task 1.
- Produces: same signature, now accepting `bits` of 8, 16, 24 or 32 and format code 1 (PCM) or 3 (IEEE float). Task 6 and Plan 02 rely on 24-bit specifically, because `WavWriter`'s default depth is `PCM_24`.

- [ ] **Step 1: Write the failing test**

Append to `WavReaderTest.kt`, inside the class:

```kotlin
    /** Hand-build a WAV so we can produce depths WavWriter cannot emit. */
    private fun wav(
        formatCode: Int,
        bits: Int,
        channels: Int,
        sampleRate: Int,
        payload: ByteArray,
    ): ByteArray {
        val blockAlign = channels * (bits / 8)
        val out = ByteArrayOutputStream()
        fun tag(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        tag("RIFF"); le32(36 + payload.size); tag("WAVE")
        tag("fmt "); le32(16)
        le16(formatCode); le16(channels); le32(sampleRate)
        le32(sampleRate * blockAlign); le16(blockAlign); le16(bits)
        tag("data"); le32(payload.size); out.write(payload)
        return out.toByteArray()
    }

    @Test
    fun `reads back a 24 bit wav`() {
        val original = ramp(512)
        val decoded = roundTrip(original, WavWriter.BitDepth.PCM_24)
        assertSamplesClose(original, decoded, tolerance = 1f / 8_388_608f)
    }

    @Test
    fun `reads 8 bit unsigned pcm`() {
        // 8-bit WAV is unsigned with 128 as silence.
        val payload = byteArrayOf(128.toByte(), 255.toByte(), 0, 192.toByte())
        val decoded = WavReader.read(wav(1, 8, 1, 44_100, payload))
        assertEquals(4, decoded.frameCount)
        assertTrue(abs(0f - decoded.samples[0]) <= 1f / 128f, "silence")
        assertTrue(decoded.samples[1] > 0.9f, "full positive")
        assertTrue(decoded.samples[2] < -0.9f, "full negative")
        assertTrue(decoded.samples[3] > 0.4f, "half positive")
    }

    @Test
    fun `reads 32 bit float pcm`() {
        val values = floatArrayOf(0f, 0.5f, -0.25f, 1f)
        val payload = ByteArray(values.size * 4)
        for ((i, v) in values.withIndex()) {
            val b = v.toRawBits()
            payload[i * 4] = (b and 0xFF).toByte()
            payload[i * 4 + 1] = ((b ushr 8) and 0xFF).toByte()
            payload[i * 4 + 2] = ((b ushr 16) and 0xFF).toByte()
            payload[i * 4 + 3] = ((b ushr 24) and 0xFF).toByte()
        }
        val decoded = WavReader.read(wav(3, 32, 1, 48_000, payload))
        assertEquals(48_000, decoded.sampleRate)
        for (i in values.indices) {
            assertEquals(values[i], decoded.samples[i], "sample $i")
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :audio:test --tests 'com.snipsnap.audio.WavReaderTest'`
Expected: FAIL — `reads back a 24 bit wav`, `reads 8 bit unsigned pcm` and `reads 32 bit float pcm` all throw `IllegalArgumentException: unsupported bit depth` or `unsupported WAV format code 3`.

- [ ] **Step 3: Write minimal implementation**

In `WavReader.kt`, replace the two validation lines and the decode loop.

Replace:

```kotlin
        require(format == FORMAT_PCM) { "unsupported WAV format code $format" }
        require(bits == 16) { "unsupported bit depth $bits" }
```

with:

```kotlin
        require(format == FORMAT_PCM || format == FORMAT_FLOAT) {
            "unsupported WAV format code $format (want 1 PCM or 3 float)"
        }
        if (format == FORMAT_FLOAT) {
            require(bits == 32) { "float WAVs must be 32-bit, was $bits" }
        } else {
            require(bits == 8 || bits == 16 || bits == 24 || bits == 32) {
                "unsupported bit depth $bits"
            }
        }
```

Replace the decode loop:

```kotlin
        for (i in out.indices) {
            out[i] = leShort(bytes, dataAt + i * bytesPerSample).toShort() / 32768f
        }
```

with:

```kotlin
        for (i in out.indices) {
            val at = dataAt + i * bytesPerSample
            out[i] = when {
                format == FORMAT_FLOAT -> Float.fromBits(leInt(bytes, at))
                bits == 8 -> ((bytes[at].toInt() and 0xFF) - 128) / 128f
                bits == 16 -> leShort(bytes, at).toShort() / 32768f
                bits == 24 -> le24(bytes, at) / 8_388_608f
                else -> leInt(bytes, at) / 2_147_483_648f
            }
        }
```

Add this helper next to the other private functions:

```kotlin
    /** 24-bit little-endian, sign-extended into an Int. */
    private fun le24(b: ByteArray, at: Int): Int {
        val v = (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16)
        return if (v and 0x800000 != 0) v or -0x1000000 else v
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :audio:test --tests 'com.snipsnap.audio.WavReaderTest'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt audio/src/test/kotlin/com/snipsnap/audio/WavReaderTest.kt
git commit -m "Every depth the wild throws at us: 8, 16, 24, 32 and float"
```

---

### Task 3: WavReader — unknown chunks, extensible format, and honest errors

**Files:**
- Modify: `audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt`
- Test: `audio/src/test/kotlin/com/snipsnap/audio/WavReaderTest.kt`

**Interfaces:**
- Consumes: `WavReader.read(bytes: ByteArray): Snip` from Task 2.
- Produces: same signature. Now skips unrecognised chunks (`LIST`, `fact`, `bext`, …) and resolves `WAVE_FORMAT_EXTENSIBLE` (`0xFFFE`) to its SubFormat code. Rejects malformed input with `IllegalArgumentException` and a message naming the problem.

Captured audio from a phone, a video app or a commercial pack routinely carries `LIST`/`INFO` metadata ahead of `data`, and 24-bit files from professional tools are frequently `WAVE_FORMAT_EXTENSIBLE` rather than plain PCM. Without this task the reader works only on files SnipSnap wrote itself.

- [ ] **Step 1: Write the failing test**

Append to `WavReaderTest.kt`, inside the class:

```kotlin
    /** Build a WAV with a metadata chunk sitting between fmt and data. */
    private fun wavWithChunkBefore(
        chunkId: String,
        chunkBody: ByteArray,
        formatCode: Int = 1,
        fmtExtra: ByteArray = ByteArray(0),
        bits: Int = 16,
        payload: ByteArray = byteArrayOf(0, 0, 0, 64),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun tag(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        val fmtSize = 16 + fmtExtra.size
        tag("RIFF"); le32(4 + 8 + fmtSize + 8 + chunkBody.size + 8 + payload.size); tag("WAVE")
        tag("fmt "); le32(fmtSize)
        le16(formatCode); le16(1); le32(44_100)
        le32(44_100 * 2); le16(2); le16(bits)
        out.write(fmtExtra)
        tag(chunkId); le32(chunkBody.size); out.write(chunkBody)
        if (chunkBody.size % 2 == 1) out.write(0) // word-align pad
        tag("data"); le32(payload.size); out.write(payload)
        return out.toByteArray()
    }

    @Test
    fun `skips a LIST metadata chunk before data`() {
        val decoded = WavReader.read(wavWithChunkBefore("LIST", "INFOsome tag".toByteArray()))
        assertEquals(2, decoded.frameCount)
        assertEquals(44_100, decoded.sampleRate)
    }

    @Test
    fun `skips an odd length chunk and stays word aligned`() {
        // A 5-byte chunk is followed by a pad byte; miss it and every later
        // chunk id reads one byte off and the data chunk is never found.
        val decoded = WavReader.read(wavWithChunkBefore("fact", byteArrayOf(1, 2, 3, 4, 5)))
        assertEquals(2, decoded.frameCount)
    }

    @Test
    fun `resolves extensible format to its subformat`() {
        // cbSize=22, validBits=16, channelMask=3, then a 16-byte GUID whose
        // first two bytes carry the real format code.
        // fmt body: 16 cbSize, 18 validBits, 20..23 channelMask, 24..39 GUID.
        // extra[] starts at body+16, so the GUID begins at extra[8].
        val extra = ByteArray(24)
        extra[0] = 22; extra[2] = 16; extra[4] = 3
        extra[8] = 1 // SubFormat GUID's first two bytes = PCM
        val decoded = WavReader.read(
            wavWithChunkBefore("LIST", "INFO".toByteArray(), formatCode = 0xFFFE, fmtExtra = extra)
        )
        assertEquals(2, decoded.frameCount)
    }

    @Test
    fun `rejects a file that is not a wav`() {
        val e = assertFailsWith<IllegalArgumentException> {
            WavReader.read("this is plainly not audio".toByteArray())
        }
        assertTrue(e.message!!.contains("RIFF"), "message should name the problem: ${e.message}")
    }

    @Test
    fun `rejects a wav with no data chunk`() {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray()); out.write(byteArrayOf(20, 0, 0, 0))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(byteArrayOf(16, 0, 0, 0))
        out.write(ByteArray(16))
        val e = assertFailsWith<IllegalArgumentException> { WavReader.read(out.toByteArray()) }
        assertTrue(e.message!!.contains("data"), "message should name the problem: ${e.message}")
    }
```

Add the import at the top of the file:

```kotlin
import kotlin.test.assertFailsWith
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :audio:test --tests 'com.snipsnap.audio.WavReaderTest'`
Expected: FAIL — `resolves extensible format to its subformat` throws `unsupported WAV format code 65534`. The `LIST` and odd-length tests may already pass; leave them, they are regression cover for the chunk walker.

- [ ] **Step 3: Write minimal implementation**

In `WavReader.kt`, add the constant beside the other two:

```kotlin
    private const val FORMAT_EXTENSIBLE = 0xFFFE
```

In the `"fmt "` branch, after `bits = leShort(bytes, body + 14)`, add:

```kotlin
                    if (format == FORMAT_EXTENSIBLE) {
                        require(size >= 40) {
                            "extensible fmt chunk is $size bytes, need 40"
                        }
                        // The real format code is the first two bytes of the
                        // SubFormat GUID, 24 bytes into the fmt body.
                        format = leShort(bytes, body + 24)
                    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :audio:test --tests 'com.snipsnap.audio.WavReaderTest'`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt audio/src/test/kotlin/com/snipsnap/audio/WavReaderTest.kt
git commit -m "Real files are messy: skip the metadata, unwrap the GUID"
```

---

### Task 4: Resampler — passthrough and output length

**Files:**
- Create: `audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt`
- Test: `audio/src/test/kotlin/com/snipsnap/audio/ResamplerTest.kt`

**Interfaces:**
- Consumes: `Snip` from `Cleanup.kt:12`.
- Produces: `Resampler.resample(snip: Snip, targetRate: Int): Snip`. Task 5, Task 6 and Plan 02's `BlockBaker` all call exactly this.

- [ ] **Step 1: Write the failing test**

Create `audio/src/test/kotlin/com/snipsnap/audio/ResamplerTest.kt`:

```kotlin
package com.snipsnap.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResamplerTest {

    private fun dc(frames: Int, level: Float, channels: Int = 1, rate: Int = 44_100) =
        Snip(FloatArray(frames * channels) { level }, channels, rate)

    @Test
    fun `returns the same snip when the rate already matches`() {
        val input = dc(100, 0.25f)
        val output = Resampler.resample(input, 44_100)
        assertTrue(output === input, "matching rate should not copy")
    }

    @Test
    fun `scales frame count by the rate ratio when upsampling`() {
        val input = dc(44_100, 0.1f)
        val output = Resampler.resample(input, 48_000)
        assertEquals(48_000, output.sampleRate)
        // One second in, one second out, within a frame of rounding.
        assertTrue(abs(output.frameCount - 48_000) <= 1, "got ${output.frameCount} frames")
    }

    @Test
    fun `scales frame count by the rate ratio when downsampling`() {
        val input = dc(48_000, 0.1f, rate = 48_000)
        val output = Resampler.resample(input, 44_100)
        assertEquals(44_100, output.sampleRate)
        assertTrue(abs(output.frameCount - 44_100) <= 1, "got ${output.frameCount} frames")
    }

    @Test
    fun `preserves channel count`() {
        val output = Resampler.resample(dc(1_000, 0.2f, channels = 2), 48_000)
        assertEquals(2, output.channels)
        assertEquals(0, output.samples.size % 2, "stereo output must be whole frames")
    }

    @Test
    fun `rejects a non positive target rate`() {
        assertFailsWith<IllegalArgumentException> { Resampler.resample(dc(10, 0.1f), 0) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :audio:test --tests 'com.snipsnap.audio.ResamplerTest'`
Expected: FAIL — compilation error, `Unresolved reference: Resampler`.

- [ ] **Step 3: Write minimal implementation**

Create `audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt`:

```kotlin
package com.snipsnap.audio

import kotlin.math.floor

/**
 * Sample-rate conversion for bake time.
 *
 * SnipSnap is 44.1 kHz everywhere because that is what the MPC takes natively,
 * but a phone's output is commonly 48 kHz. Conversion happens once, when a
 * block is baked, never inside an audio callback.
 *
 * Placeholder linear interpolation — Task 5 replaces the kernel.
 */
object Resampler {

    fun resample(snip: Snip, targetRate: Int): Snip {
        require(targetRate > 0) { "targetRate must be positive, was $targetRate" }
        if (snip.sampleRate == targetRate) return snip

        val channels = snip.channels
        val srcFrames = snip.frameCount
        val ratio = targetRate.toDouble() / snip.sampleRate
        val dstFrames = floor(srcFrames * ratio).toInt()
        val out = FloatArray(dstFrames * channels)

        for (o in 0 until dstFrames) {
            val srcPos = o / ratio
            val i = floor(srcPos).toInt()
            val frac = (srcPos - i).toFloat()
            for (c in 0 until channels) {
                val a = snip.samples[i * channels + c]
                val b = if (i + 1 < srcFrames) snip.samples[(i + 1) * channels + c] else a
                out[o * channels + c] = a + (b - a) * frac
            }
        }
        return Snip(out, channels, targetRate)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :audio:test --tests 'com.snipsnap.audio.ResamplerTest'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt audio/src/test/kotlin/com/snipsnap/audio/ResamplerTest.kt
git commit -m "Forty-four one meets forty-eight: the shape of the conversion"
```

---

### Task 5: Resampler — band-limited kernel

**Files:**
- Modify: `audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt`
- Test: `audio/src/test/kotlin/com/snipsnap/audio/ResamplerTest.kt`

**Interfaces:**
- Consumes: `Resampler.resample(snip: Snip, targetRate: Int): Snip` from Task 4.
- Produces: same signature, same output length. Only the interpolation changes.

Linear interpolation is a poor lowpass: at the 44.1→48 ratio it dulls the top octave and, when downsampling, folds everything above the new Nyquist back into the audible band as aliasing. A windowed-sinc kernel is the standard offline fix and costs nothing in the real-time path, because nothing here runs in the real-time path.

- [ ] **Step 1: Write the failing test**

Append to `ResamplerTest.kt`, inside the class:

```kotlin
    private fun sine(frames: Int, hz: Double, rate: Int, amplitude: Float = 0.5f) =
        Snip(
            FloatArray(frames) {
                amplitude * kotlin.math.sin(2.0 * Math.PI * hz * it / rate).toFloat()
            },
            1,
            rate,
        )

    /** Root-mean-square level, ignoring the kernel's ramp-in at the edges. */
    private fun rms(snip: Snip, skip: Int = 64): Float {
        var sum = 0.0
        var n = 0
        for (i in skip until snip.samples.size - skip) {
            sum += snip.samples[i] * snip.samples[i]
            n++
        }
        return kotlin.math.sqrt(sum / n).toFloat()
    }

    @Test
    fun `preserves dc level`() {
        // A kernel that is not normalised shows up here first: constant in,
        // same constant out, or the gain is wrong.
        val output = Resampler.resample(dc(4_000, 0.4f), 48_000)
        for (i in 200 until output.samples.size - 200) {
            assertTrue(abs(output.samples[i] - 0.4f) < 0.005f, "sample $i was ${output.samples[i]}")
        }
    }

    @Test
    fun `preserves the level of a mid band tone`() {
        val input = sine(44_100, hz = 1_000.0, rate = 44_100)
        val output = Resampler.resample(input, 48_000)
        val before = rms(input)
        val after = rms(output)
        assertTrue(abs(after - before) / before < 0.02f, "rms $before -> $after")
    }

    @Test
    fun `does not alias a high tone when downsampling`() {
        // 20 kHz at 48 kHz has nowhere to go at 22.05 kHz: it must be filtered
        // out, not folded back down into the audible band as a loud artefact.
        val input = sine(48_000, hz = 20_000.0, rate = 48_000, amplitude = 0.9f)
        val output = Resampler.resample(input, 22_050)
        assertTrue(rms(output) < 0.1f, "aliased energy: rms ${rms(output)}")
    }

    @Test
    fun `keeps stereo channels independent`() {
        val samples = FloatArray(2_000) { if (it % 2 == 0) 0.6f else -0.6f }
        val output = Resampler.resample(Snip(samples, 2, 44_100), 48_000)
        for (f in 100 until output.frameCount - 100) {
            assertTrue(output.samples[f * 2] > 0.55f, "left at frame $f")
            assertTrue(output.samples[f * 2 + 1] < -0.55f, "right at frame $f")
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :audio:test --tests 'com.snipsnap.audio.ResamplerTest'`
Expected: FAIL on `does not alias a high tone when downsampling` — linear interpolation has no anti-alias filter, so the 20 kHz tone folds down and the RMS stays near 0.6.

- [ ] **Step 3: Write minimal implementation**

In `Resampler.kt`, replace the whole file body with:

```kotlin
package com.snipsnap.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Sample-rate conversion for bake time.
 *
 * SnipSnap is 44.1 kHz everywhere because that is what the MPC takes natively,
 * but a phone's output is commonly 48 kHz. Conversion happens once, when a
 * block is baked, never inside an audio callback — so this can afford a real
 * band-limited kernel instead of the linear interpolation that would dull the
 * top octave going up and fold aliases back down coming down.
 *
 * Windowed sinc, evaluated per output sample. When downsampling the cutoff
 * follows the new Nyquist, which is what stops the aliasing.
 */
object Resampler {

    /** Taps either side of the centre. 16 is the usual quality/cost knee. */
    private const val HALF_TAPS = 16

    fun resample(snip: Snip, targetRate: Int): Snip {
        require(targetRate > 0) { "targetRate must be positive, was $targetRate" }
        if (snip.sampleRate == targetRate) return snip

        val channels = snip.channels
        val srcFrames = snip.frameCount
        val ratio = targetRate.toDouble() / snip.sampleRate
        val dstFrames = floor(srcFrames * ratio).toInt()
        val out = FloatArray(dstFrames * channels)

        // Going down, pull the passband to the destination's Nyquist so nothing
        // above it survives to fold back. Going up, the source is already band-
        // limited and the kernel just interpolates.
        val cutoff = if (ratio < 1.0) ratio else 1.0

        for (o in 0 until dstFrames) {
            val srcPos = o / ratio
            val centre = floor(srcPos).toInt()
            val lo = centre - HALF_TAPS + 1
            val hi = centre + HALF_TAPS

            for (c in 0 until channels) {
                var acc = 0.0
                var norm = 0.0
                for (n in lo..hi) {
                    if (n < 0 || n >= srcFrames) continue
                    val w = kernel(srcPos - n, cutoff)
                    acc += snip.samples[n * channels + c] * w
                    norm += w
                }
                out[o * channels + c] = if (norm != 0.0) (acc / norm).toFloat() else 0f
            }
        }
        return Snip(out, channels, targetRate)
    }

    /**
     * Sinc at [cutoff] of Nyquist, under a Blackman window.
     *
     * Normalising by the summed weights at the call site keeps DC gain at unity
     * even where the window is truncated at the buffer edges.
     */
    private fun kernel(x: Double, cutoff: Double): Double {
        val ax = abs(x)
        if (ax >= HALF_TAPS) return 0.0
        val sinc = if (ax < 1e-9) cutoff else sin(PI * cutoff * x) / (PI * x)
        val t = (x + HALF_TAPS) / (2.0 * HALF_TAPS)
        val window = 0.42 - 0.5 * cos(2 * PI * t) + 0.08 * cos(4 * PI * t)
        return sinc * window
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :audio:test --tests 'com.snipsnap.audio.ResamplerTest'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt audio/src/test/kotlin/com/snipsnap/audio/ResamplerTest.kt
git commit -m "Sinc under a Blackman window: no folded ghosts on the way down"
```

---

### Task 6: End-to-end round trip and the golden corpus

**Files:**
- Create: `audio/src/test/kotlin/com/snipsnap/audio/AudioIoRoundTripTest.kt`

**Interfaces:**
- Consumes: `WavReader.read(file: File): Snip` (Task 3), `Resampler.resample(snip: Snip, targetRate: Int): Snip` (Task 5), `WavWriter.write(file: File, snip: Snip, depth: WavWriter.BitDepth, allowNonMpcRate: Boolean): File`.
- Produces: nothing new. This task proves the pair works together, which is the contract Plan 02's `BlockBaker` is written against.

- [ ] **Step 1: Write the failing test**

Create `audio/src/test/kotlin/com/snipsnap/audio/AudioIoRoundTripTest.kt`:

```kotlin
package com.snipsnap.audio

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioIoRoundTripTest {

    private fun tone(frames: Int, hz: Double, rate: Int, channels: Int = 1) =
        Snip(
            FloatArray(frames * channels) { i ->
                0.5f * kotlin.math.sin(2.0 * Math.PI * hz * (i / channels) / rate).toFloat()
            },
            channels,
            rate,
        )

    @Test
    fun `writes reads and resamples a file to the device rate`() {
        val source = tone(44_100, hz = 440.0, rate = 44_100, channels = 2)
        val file = File.createTempFile("snipsnap-roundtrip", ".wav")
        file.deleteOnExit()

        WavWriter.write(file, source, WavWriter.BitDepth.PCM_24)
        val decoded = WavReader.read(file)
        assertEquals(2, decoded.channels)
        assertEquals(44_100, decoded.sampleRate)
        assertEquals(source.frameCount, decoded.frameCount)

        val baked = Resampler.resample(decoded, 48_000)
        assertEquals(48_000, baked.sampleRate)
        assertEquals(2, baked.channels)
        assertTrue(abs(baked.frameCount - 48_000) <= 1, "got ${baked.frameCount} frames")
        // A 440 Hz tone at half scale should still peak near 0.5 after conversion.
        assertTrue(abs(baked.peak() - 0.5f) < 0.02f, "peak ${baked.peak()}")
    }

    @Test
    fun `reads every wav in the golden corpus if any are present`() {
        // reference/golden/.gitignore blocks audio, so this is empty on a clean
        // clone and populated on a machine that has harvested packs. Skip rather
        // than fail when there is nothing to read.
        val root = File("../reference/golden")
        val wavs = root.walkTopDown().filter { it.isFile && it.extension.lowercase() == "wav" }.toList()
        if (wavs.isEmpty()) return

        for (wav in wavs) {
            val snip = WavReader.read(wav)
            assertTrue(snip.frameCount > 0, "${wav.name} decoded to no frames")
            assertTrue(snip.sampleRate > 0, "${wav.name} has sampleRate ${snip.sampleRate}")
            assertTrue(snip.peak() <= 1.001f, "${wav.name} peaks above full scale at ${snip.peak()}")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :audio:test --tests 'com.snipsnap.audio.AudioIoRoundTripTest'`
Expected: PASS if Tasks 1–5 are complete. This task is integration cover, not a new behaviour — if it fails, the failure is a real defect in the pair and must be fixed before committing.

- [ ] **Step 3: Run the whole module suite**

Run: `./gradlew :audio:test`
Expected: PASS. All pre-existing `:audio` tests plus the 21 added across this plan (10 in `WavReaderTest`, 9 in `ResamplerTest`, 2 here).

- [ ] **Step 4: Run the full build**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 384 pre-existing tests plus the new ones, zero failures.

- [ ] **Step 5: Commit**

```bash
git add audio/src/test/kotlin/com/snipsnap/audio/AudioIoRoundTripTest.kt
git commit -m "Write it, read it, rate-convert it: the bake path end to end"
```

---

## Done when

- `WavReader.read(File)` and `WavReader.read(ByteArray)` decode 8/16/24/32-bit PCM and 32-bit float, mono and stereo, skipping unknown chunks and resolving `WAVE_FORMAT_EXTENSIBLE`.
- `Resampler.resample(Snip, Int)` converts between any two positive rates with a band-limited kernel and no aliasing on downward conversion.
- `./gradlew test` is green across all six modules.
- `audio/build.gradle.kts` `dependencies {}` is unchanged.

## Next

Plan 02 (`:loop` engine) consumes exactly two signatures from this plan:

```kotlin
WavReader.read(file: File): Snip
Resampler.resample(snip: Snip, targetRate: Int): Snip
```

`BlockBaker` calls them in that order, then fits the result to `intervalFrames`.
