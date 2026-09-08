package com.snipsnap.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

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
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    /**
     * Reads [file] in full via [File.readBytes] and decodes it.
     *
     * The whole file is loaded into memory at once — there is no streaming
     * path, so this is unsuitable for files too large to fit in the heap.
     * Can throw [java.io.IOException] from the read itself, in addition to
     * every [IllegalArgumentException] contract of [read] below.
     */
    fun read(file: File): Snip = read(file.readBytes())

    /** What [readCapped] did: the audio it decoded, and whether [file] ran longer than its cap (in duration OR bytes) and got cut short. */
    data class CappedRead(val snip: Snip, val truncated: Boolean)

    /**
     * The real ceiling [readCapped] enforces: how many bytes it will ever
     * decode into a single [Snip]'s backing `FloatArray`. A duration cap
     * alone (seconds) does NOT bound memory — [read] always decodes to
     * float32 regardless of source bit depth, and nothing about "seconds"
     * bounds `sampleRate` or `channels`. Decoded size is exactly
     * `frames * channels * 4` bytes, and — because [locateAudio] yields
     * `sampleRate`, `channels` and `dataLen` from the header alone, no
     * decode required — that size is computable before a single sample is
     * read, so it can be capped directly instead of hoping a duration cap
     * happens to bound it too.
     *
     * 128 MiB (134,217,728 bytes) chosen as a conservative slice of a
     * typical Android app heap (default heaps commonly sit in the
     * 192-512 MB range depending on device; this assumes the low end),
     * scoped explicitly to the decoded `FloatArray` — NOT the true peak
     * a caller sees. The actual peak is higher: [readCapped] also holds
     * the raw byte slice live while [read] decodes it (for a 32-bit
     * float source, that slice is the same size as the decoded array,
     * since both are 4 bytes/sample — so momentarily ~2x this ceiling),
     * and a caller that mono-mixes right after (as every call site in
     * this app does) briefly holds a third, smaller buffer alongside
     * whichever of the first two it hasn't released yet. 128 MiB leaves
     * enough headroom under a 192-512 MB heap for that multiplier and
     * the rest of the app besides.
     *  - 44.1 kHz mono (the common legitimate case — a phone mic or a
     *    simple field recording): `134_217_728 / 4 = 33_554_432` frames
     *    `= 761 s` (~12.7 min) before this ceiling would bind — well
     *    above the app's existing 600s (10 min) duration cap
     *    (`TapeScreen.TAPE_LOAD_MAX_SEC`), so an ordinary mono file is
     *    still bound by duration, not bytes, and a full 10-minute
     *    recording loads whole exactly as before.
     *  - 192 kHz stereo (the pathological case seconds cannot bound):
     *    `134_217_728 / (2 * 4) = 16_777_216` frames `≈ 87.4 s`. Where a
     *    600s duration-only cap would have cost
     *    `600 * 192_000 * 2 * 4 = 921.6 MB`, this ceiling instead loads
     *    a correspondingly shorter ~87-second prefix — the byte ceiling
     *    is the binding constraint exactly where duration alone fails.
     */
    const val MAX_DECODE_BYTES: Long = 128L * 1024 * 1024

    /**
     * Default ceiling on how much leading file content (everything before
     * `data`'s body) [readCapped] — and [peekSeconds], so the two agree on
     * what counts as resolvable — will read into memory to reach a file's
     * audio. See [readCapped]'s own `maxHeaderBytes` parameter doc for why
     * this matters and why 1 MiB is generous for real WAV headers.
     */
    const val DEFAULT_HEADER_BYTES: Long = 1L * 1024 * 1024

    /**
     * Like [read], but never decodes more than the tighter of
     * [maxDurationSec] of audio or [maxDecodeBytes] worth of decoded
     * float32 samples — and, unlike [read], never reads more than that
     * much of [file] off disk either. [read] always loads the *whole*
     * file via [File.readBytes] before decoding a single sample, so a
     * caller that cannot vouch for [file]'s length (an arbitrary
     * user-picked file, not one this app wrote and already capped) can
     * OOM on the raw byte read alone, before decode even starts — or, if
     * the duration cap alone let it through, on the decode itself for a
     * high-rate or multichannel file (see [MAX_DECODE_BYTES]'s KDoc for
     * the arithmetic). A cap applied only *after* calling [read] would be
     * too late for exactly that reason, and a cap expressed only in
     * seconds would be too loose.
     *
     * A lightweight scan ([locateAudio]) finds the `fmt ` and `data`
     * chunks by seeking past everything else — metadata chunks
     * (LIST/bext/smpl/etc.) are skipped, never read into memory, however
     * large they are — then only `[0, dataAt + min(actual, cap))` of the
     * file is read off disk (always a slice, never the whole file, even
     * when nothing needs cutting — trailing chunks after `data`, if any,
     * are simply never read, and [read] never needed them: it only
     * requires `fmt` and `data`, wherever they end). That slice is handed
     * to [read] exactly as any other byte array: its `data` chunk header
     * still declares the file's true (larger) length, so [read]'s own
     * truncated-tail handling — the same path a capture killed mid-write
     * takes — decodes precisely the bytes present and no more. No decode
     * logic is duplicated here; this only ever changes how many bytes
     * reach [read].
     *
     * Two distinct fallback cases when the scan can't cleanly resolve a
     * fmt+data pair:
     *  - [file] isn't RIFF/WAVE at all (the common case for an arbitrary
     *    picker — a video, an archive, anything): [locateAudio] already
     *    proves this from a 12-byte prefix, so only that prefix is read
     *    and handed to [read], which throws its ordinary
     *    [IllegalArgumentException] for it — same accept/reject contract
     *    as a plain [read], near-zero cost, no unbounded read.
     *  - [file] IS RIFF/WAVE but its chunks don't cleanly resolve (a
     *    malformed or unusually shaped file, or one whose `fmt ` chunk
     *    never precedes its `data` chunk — see [locateAudio]'s KDoc):
     *    this refuses honestly, via the same [IllegalArgumentException]
     *    contract, whenever [file] is over a quarter of [maxDecodeBytes]
     *    (the scan couldn't determine bit depth, so a quarter — 8-bit
     *    PCM's worst-case 4x expansion into float32 — is the only bound
     *    that's still safe) rather than guessing it's safe; only when
     *    [file] is within that reduced ceiling does it fall back to the
     *    ordinary, unbounded [read].
     *
     * A `fmt+data` pair that DOES resolve is still gated on
     * [maxHeaderBytes]: [locateAudio] seeks past leading metadata chunks
     * without reading their bodies, but the actual read afterwards must
     * still start at byte 0 ([read] needs RIFF+`fmt` present), so a file
     * with a pathologically fat chunk ahead of `data` could otherwise
     * size that read by its own leading-byte count alone — a dimension
     * [maxDecodeBytes] doesn't cover at all.
     *
     * Either way [read]'s own decode and error contract apply completely
     * unchanged; this never rejects a file [read] would accept, and never
     * accepts one [read] would reject.
     */
    fun readCapped(
        file: File,
        maxDurationSec: Float,
        maxDecodeBytes: Long = MAX_DECODE_BYTES,
        /**
         * How much leading file content (everything before `data`'s body —
         * RIFF/WAVE, `fmt `, and any metadata chunks ahead of `data`) this
         * will read into memory to reach the audio. [locateAudio] *seeks*
         * past that content without reading it during the scan — but the
         * scan only locates `dataAt`; the actual read afterwards still has
         * to start at byte 0 ([read] parses sequentially and needs
         * RIFF+`fmt` present), so a pathologically fat leading chunk
         * (embedded art in a LIST/INFO, a large `bext`, JUNK padding) would
         * otherwise size that read by `dataAt` alone — unbounded, and not
         * something [maxDecodeBytes] governs at all. 1 MiB is generous
         * next to real-world header content (a `bext` chunk is usually a
         * few hundred bytes; LIST/INFO tags rarely reach a few KB) while
         * still refusing a file that tries to hide megabytes ahead of its
         * audio.
         */
        maxHeaderBytes: Long = DEFAULT_HEADER_BYTES,
    ): CappedRead {
        val scan = try {
            locateAudio(file)
        } catch (e: IOException) {
            // A genuine I/O failure mid-scan (permissions, the file
            // vanishing) proves nothing about whether this is a WAV —
            // treat it the same as "RIFF but unresolvable" below, which
            // either refuses honestly or retries the ordinary path (and
            // if the same I/O problem recurs there, [read] surfaces it
            // exactly as [read](file) always could).
            Scan.Unresolved
        }
        return when (scan) {
            is Scan.NotWav -> {
                // Proven from a 12-byte prefix already — read only that
                // much and let [read] produce its identical rejection.
                val prefixLen = minOf(file.length(), 12L).toInt()
                val prefix = ByteArray(prefixLen)
                RandomAccessFile(file, "r").use { it.readFully(prefix) }
                CappedRead(read(prefix), truncated = false)
            }
            is Scan.Unresolved -> {
                requireResolvableWithinDecodeCeiling(file, maxDecodeBytes, "WAV chunks could not be resolved")
                CappedRead(read(file.readBytes()), truncated = false)
            }
            is Scan.Found -> {
                val loc = scan.location
                val stride = (loc.bits / 8) * loc.channels
                if (stride <= 0 || loc.sampleRate <= 0) {
                    // fmt resolved but declared nonsense (zero channels,
                    // rate, or bits) — same "can't trust this scan"
                    // handling as Unresolved above.
                    requireResolvableWithinDecodeCeiling(
                        file,
                        maxDecodeBytes,
                        "fmt chunk declared an unusable format",
                    )
                    return CappedRead(read(file.readBytes()), truncated = false)
                }
                // The leading content the read below must still cover
                // (offset 0 through dataAt) is bounded on its own —
                // separately from cappedDataBytes below — so a fat
                // leading chunk can't blow the allocation past what
                // maxDecodeBytes alone would suggest.
                require(loc.dataAt <= maxHeaderBytes) {
                    "audio data begins ${loc.dataAt} bytes into the file, over the " +
                        "$maxHeaderBytes-byte header allowance — refusing to read that much " +
                        "leading metadata into memory"
                }
                val durationFrames = (maxDurationSec.toDouble() * loc.sampleRate).toLong().coerceAtLeast(0)
                // Decoded size is frames * channels * 4 bytes (float32) —
                // see MAX_DECODE_BYTES's KDoc — so this is the frame count
                // at which THAT bound bites, independent of source bit depth.
                val byteCapFrames = maxDecodeBytes / (loc.channels * 4L)
                val cappedFrames = minOf(durationFrames, byteCapFrames)
                val cappedDataBytes = minOf(loc.dataLen, cappedFrames * stride)
                val sliceLen = loc.dataAt + cappedDataBytes
                // dataAt and cappedDataBytes are both bounded above, but
                // guard the narrowing conversion explicitly rather than
                // let a caller-supplied maxDecodeBytes/maxHeaderBytes
                // silently wrap ByteArray's Int size into a negative one.
                require(sliceLen <= Int.MAX_VALUE) {
                    "computed slice length $sliceLen exceeds Int.MAX_VALUE"
                }
                RandomAccessFile(file, "r").use { raf ->
                    val bytes = ByteArray(sliceLen.toInt())
                    raf.seek(0)
                    raf.readFully(bytes)
                    CappedRead(read(bytes), truncated = cappedDataBytes < loc.dataLen)
                }
            }
        }
    }

    /**
     * Guards the two "scan couldn't resolve this cleanly" fallbacks to an
     * ordinary, unbounded [read]: since the scan couldn't determine bit
     * depth, the only safe bound is the worst-case expansion [read] can
     * ever produce — 8-bit PCM decodes 1 source byte into 4 decoded bytes
     * (float32), so [file]'s raw size is gated at a quarter of
     * [maxDecodeBytes], not the whole of it. Without the `/ 4`, a large
     * 8-bit file could pass a byte-for-byte gate and still decode to four
     * times [maxDecodeBytes].
     */
    private fun requireResolvableWithinDecodeCeiling(file: File, maxDecodeBytes: Long, reason: String) {
        val worstCaseCeiling = maxDecodeBytes / 4
        require(file.length() <= worstCaseCeiling) {
            "$reason and file is ${file.length()} bytes, over the $worstCaseCeiling-byte ceiling " +
                "(a quarter of the $maxDecodeBytes-byte decode ceiling, assuming worst-case 8-bit " +
                "expansion since the true bit depth is unknown) — refusing an unbounded read"
        }
    }

    /**
     * Reads just enough of [file] to learn its duration, without decoding
     * a single sample — the same header-only [locateAudio] scan
     * [readCapped] uses to compute its byte-precise cap. Lets a caller
     * comparing many candidate files (a kit's pads, say) find the longest
     * one first and decode only that one, instead of decoding every
     * candidate just to measure it. Returns null in exactly the cases
     * [readCapped] would refuse or fall back for — not a WAV, chunks that
     * don't cleanly resolve, or (see [maxHeaderBytes]) a fat leading chunk
     * — leaving the caller to skip that candidate the same way it would
     * skip an undecodable one. This alignment matters: if this returned a
     * duration for a file [readCapped] would later refuse, that file could
     * win a "longest pad" scan and then fail to decode at all, even though
     * a perfectly healthy pad was available — [maxHeaderBytes] here must
     * match what a caller will pass to [readCapped] for the same file.
     */
    fun peekSeconds(file: File, maxHeaderBytes: Long = DEFAULT_HEADER_BYTES): Float? {
        val scan = try {
            locateAudio(file)
        } catch (e: IOException) {
            return null
        }
        val loc = (scan as? Scan.Found)?.location ?: return null
        if (loc.dataAt > maxHeaderBytes) return null
        val stride = (loc.bits / 8) * loc.channels
        if (stride <= 0 || loc.sampleRate <= 0) return null
        return (loc.dataLen / stride).toFloat() / loc.sampleRate
    }

    /** What [locateAudio] needs from a file to compute a byte-precise cap without decoding it. */
    private data class AudioLocation(
        val sampleRate: Int,
        val channels: Int,
        val bits: Int,
        /** Byte offset of the `data` chunk's body — where audio bytes actually begin. */
        val dataAt: Long,
        /** The `data` chunk's declared length, clamped to what the file physically holds — same reasoning as [read]'s own truncated-tail handling. */
        val dataLen: Long,
    )

    /** [locateAudio]'s answer: distinguishes "not a WAV at all" from "is a WAV but couldn't be scanned" so [readCapped] can treat each correctly (see its KDoc). */
    private sealed class Scan {
        /** The first 12 bytes don't declare RIFF/WAVE — proven cheaply, no chunk walk needed. */
        object NotWav : Scan()
        /** RIFF/WAVE, but the chunk walk didn't cleanly resolve a fmt-then-data pair. */
        object Unresolved : Scan()
        class Found(val location: AudioLocation) : Scan()
    }

    /**
     * Scans [file]'s chunks by seeking, reading only chunk headers (and
     * the tiny `fmt ` body) into memory — never a chunk's full content
     * unless it IS the `fmt ` body.
     *
     * Chunk order matters here, unlike in [read]: a `data` chunk is only
     * ever recognized once `fmt ` has already been seen in this same
     * forward pass, so a `data`-before-`fmt` file resolves to
     * [Scan.Unresolved] even though [read]'s own full parse would
     * happily resolve it (it records `fmt` and `data` independently as
     * it meets each, in whatever order, and only checks that both were
     * seen at the very end). That asymmetry is deliberate, not a bug to
     * fix: this scan exists purely to make a fast, single forward pass
     * with no backtracking, and [readCapped]'s [Scan.Unresolved] handling
     * still resolves such a file correctly via the ordinary [read] path
     * whenever it's within [MAX_DECODE_BYTES] — the fast path just isn't
     * the one that does it.
     */
    private fun locateAudio(file: File): Scan {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len < 12) return Scan.NotWav
            val riff = ByteArray(12)
            raf.readFully(riff)
            if (tag(riff, 0) != "RIFF" || tag(riff, 8) != "WAVE") return Scan.NotWav

            var sampleRate = -1
            var channels = -1
            var bits = -1
            var pos = 12L
            val header = ByteArray(8)
            while (pos + 8 <= len) {
                raf.seek(pos)
                raf.readFully(header)
                val id = tag(header, 0)
                val size = leInt(header, 4)
                if (size < 0) return Scan.Unresolved
                val bodyAt = pos + 8
                if (id == "data") {
                    if (sampleRate <= 0 || channels <= 0 || bits <= 0) return Scan.Unresolved
                    val declaredLen = minOf(size.toLong(), len - bodyAt).coerceAtLeast(0)
                    return Scan.Found(AudioLocation(sampleRate, channels, bits, bodyAt, declaredLen))
                }
                if (id == "fmt " && size >= 16 && bodyAt + 16 <= len) {
                    val body = ByteArray(16)
                    raf.seek(bodyAt)
                    raf.readFully(body)
                    channels = leShort(body, 2)
                    sampleRate = leInt(body, 4)
                    bits = leShort(body, 14)
                }
                if (bodyAt + size > len) return Scan.Unresolved
                pos = bodyAt + size + (size.toLong() and 1L)
            }
            return Scan.Unresolved
        }
    }

    /**
     * Decodes [bytes] as a WAV file.
     *
     * Throws only [IllegalArgumentException] — never any other exception —
     * for any malformed, truncated, or unsupported input.
     */
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
            // Long arithmetic here: `size` is untrusted input (a corrupt or
            // hostile header can set it near Int.MAX_VALUE), and `body + size`
            // in Int would silently wrap negative and slip past a bounds
            // check meant to catch exactly this.
            if (id == "data" && size >= 0 && body.toLong() + size > bytes.size) {
                // The declared data size runs past what the file actually
                // holds — a capture killed mid-write leaves exactly this
                // shape, since WavWriter writes the size up front while
                // streaming. Decode the frames that ARE present instead of
                // falling through to the generic bounds check below, which
                // would `break` before dataAt is ever set and report "no
                // data chunk" for a file that plainly has one.
                dataAt = body
                dataLen = bytes.size - body
                break
            }
            if (size < 0 || body.toLong() + size > bytes.size + 1) break
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "fmt chunk is $size bytes, need at least 16" }
                    require(bytes.size >= body + 16) {
                        "fmt chunk is truncated: only ${bytes.size - body} of a required 16 " +
                            "fmt body bytes are present"
                    }
                    format = leShort(bytes, body)
                    channels = leShort(bytes, body + 2)
                    sampleRate = leInt(bytes, body + 4)
                    bits = leShort(bytes, body + 14)
                    if (format == FORMAT_EXTENSIBLE) {
                        require(size >= 40) {
                            "extensible fmt chunk is $size bytes, need 40"
                        }
                        // The real format code is the first two bytes of the
                        // SubFormat GUID, 24 bytes into the fmt body.
                        format = leShort(bytes, body + 24)
                    }
                }
                "data" -> {
                    // The truncated-tail case is caught above before this
                    // point is reached, so `size` here is always fully
                    // present in `bytes`.
                    dataAt = body
                    dataLen = size
                }
            }
            // RIFF chunks are word-aligned: an odd size is followed by a pad byte.
            p = body + size + (size and 1)
        }

        require(format != -1) { "no fmt chunk" }
        require(dataAt >= 0) { "no data chunk" }
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
        require(channels in 1..2) { "fmt chunk declares $channels channels, must be 1 or 2" }

        val bytesPerSample = bits / 8
        val stride = bytesPerSample * channels
        val frames = dataLen / stride
        val out = FloatArray(frames * channels)
        for (i in out.indices) {
            val at = dataAt + i * bytesPerSample
            out[i] = when {
                // Float WAVs are the one format that can carry NaN or +-Inf -
                // a corrupt or hostile file, or a decoder that emitted them.
                // A non-finite sample poisons every downstream sum, peak and
                // normalization, so it becomes silence right here at the door.
                format == FORMAT_FLOAT -> Float.fromBits(leInt(bytes, at)).let { if (it.isFinite()) it else 0f }
                // Integer PCM divides by the largest POSITIVE code - the same
                // scale the writer multiplies by - so a write then a read is
                // the identity on every code the writer can emit, and a file
                // that goes through the bin and back is the same file. The one
                // code the writer never emits (the most negative) clamps to -1.
                bits == 8 -> (((bytes[at].toInt() and 0xFF) - 128) / 127f).coerceAtLeast(-1f)
                bits == 16 -> (leShort(bytes, at).toShort() / 32767f).coerceAtLeast(-1f)
                bits == 24 -> (le24(bytes, at) / 8_388_607f).coerceAtLeast(-1f)
                // 2^31 - 1 is not a Float, so this one divides in Double first.
                else -> (leInt(bytes, at) / 2_147_483_647.0).toFloat().coerceAtLeast(-1f)
            }
        }
        return Snip(out, channels, sampleRate)
    }

    /**
     * The sampler sheet, if the file carries one: the `smpl` chunk's root
     * note and first forward loop, or null when there is none or it is
     * malformed — a missing sheet is the ordinary case for a drum hit, not
     * an error, and a loop that runs past the audio the file actually
     * holds is malformed, not a loop. The audio is [read]'s business;
     * this walks the chunks on its own, reading only the headers it needs
     * to size the audio, so a caller that only wants the sheet pays only
     * for the sheet.
     */
    fun readSmpl(file: File): SmplChunk? = readSmpl(file.readBytes())

    fun readSmpl(bytes: ByteArray): SmplChunk? {
        if (bytes.size < 12 || tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") return null
        var sheet: SmplChunk? = null
        var blockAlign = -1
        var dataLen = -1L
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = tag(bytes, p)
            val size = leInt(bytes, p + 4)
            val body = p + 8
            if (size < 0) return null
            if (id == "data") {
                // A truncated tail (a capture killed mid-write) still holds
                // the frames that are present; the loop is checked against
                // those, not the declared size.
                dataLen = minOf(size.toLong(), (bytes.size - body).toLong())
            } else if (body.toLong() + size > bytes.size) {
                return null
            } else if (id == "fmt " && size >= 16) {
                blockAlign = leShort(bytes, body + 12)
            } else if (id == SmplChunk.TAG) {
                if (size < SmplChunk.HEADER_BYTES) return null
                val root = leInt(bytes, body + 12)
                if (root !in 0..127) return null
                val loops = leInt(bytes, body + 28)
                val loop = if (loops >= 1 && size >= SmplChunk.HEADER_BYTES + SmplChunk.LOOP_BYTES) {
                    val start = leInt(bytes, body + 44).toLong() and 0xFFFFFFFFL
                    val endInclusive = leInt(bytes, body + 48).toLong() and 0xFFFFFFFFL
                    if (endInclusive >= start) SmplChunk.Loop(start, endInclusive + 1) else null
                } else {
                    null
                }
                sheet = SmplChunk(root, loop)
            }
            if (id == "data" && body.toLong() + size > bytes.size) break
            p = body + size + (size and 1)
        }
        val found = sheet ?: return null
        val loop = found.loop ?: return found
        if (blockAlign <= 0 || dataLen < 0) return null
        val frames = dataLen / blockAlign
        return if (loop.endFrameExclusive <= frames) found else null
    }

    private fun tag(b: ByteArray, at: Int): String = String(b, at, 4, Charsets.US_ASCII)

    private fun leShort(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun leInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    /** 24-bit little-endian, sign-extended into an Int. */
    private fun le24(b: ByteArray, at: Int): Int {
        val v = (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16)
        return if (v and 0x800000 != 0) v or -0x1000000 else v
    }
}
