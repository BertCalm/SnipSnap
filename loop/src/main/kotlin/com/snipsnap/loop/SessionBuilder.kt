package com.snipsnap.loop

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.Names
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * How a session gets built in the first place.
 *
 * Until this existed nothing in the app ever wrote a session: `SessionStore.save`
 * had no caller, so `sessions/current` was never created and the loop grid
 * always loaded nothing. This is the missing half — the door a snip walks
 * through to become a track.
 *
 * **One snip, one track, several blocks.** A snip is not stretched to fit the
 * interval. It is cut into interval-length pieces at its own natural speed and
 * those pieces become the track's chain, so a snip that runs three intervals
 * long is a three-block chain and one that runs two is a two-block chain. That
 * is where the phasing comes from: chains of different lengths advance together
 * and only realign after the least common multiple of their lengths
 * ([Arrangement.cycleIntervals]). Fitting each snip to a single block instead
 * would give six tracks that all repeat every interval — a plain looper, with
 * nothing to drift.
 *
 * The last piece of a snip is zero-padded up to the interval, because every
 * block bakes to exactly [Session.intervalFrames]. A snip that does not divide
 * evenly therefore loops with a short silent tail, which is honest: it is the
 * length the player actually caught, not a length invented for it.
 *
 * Everything written here is a bare filename inside the session folder, the
 * same rule [SessionStore] and [KitSampleSource] already keep, so the folder
 * stays copyable as one unit.
 *
 * It does cost disk: pieces are written at [WavWriter]'s default depth, so a
 * full six-track grid of long snips is on the order of a few tens of MB. That
 * is the price of playing audio at its own length instead of stretching it,
 * and it is bounded — [Session.MAX_CHAIN] pieces per track, six tracks, one
 * folder — rather than growing with use.
 */
object SessionBuilder {

    /**
     * A fresh session's tempo, and the length of one interval in bars.
     *
     * One bar rather than the four `SessionStore` falls back to when a file
     * does not say: an interval is the unit a snip is cut into, and at 90 BPM
     * one bar is 2.7 seconds against four bars' 10.7. Most catches are shorter
     * than 10.7 seconds, so a four-bar interval would make almost every snip a
     * single padded block — one block per track, six tracks repeating in
     * lockstep, no drift. A one-bar interval is what lets an ordinary catch
     * become a chain of two or three.
     *
     * Neither is adjustable from the grid yet: there is no tempo control on
     * the screen, so these are what a session gets.
     */
    const val DEFAULT_BPM = 90f
    const val DEFAULT_BARS = 1

    /** What an unfilled track is called, so the grid's header has something true to print. */
    const val EMPTY_TRACK = "EMPTY"

    /** A session with all [Session.TRACK_COUNT] tracks empty and silent. */
    fun empty(
        sampleRate: Int,
        bpm: Float = DEFAULT_BPM,
        barsPerInterval: Int = DEFAULT_BARS,
    ): Session = Session(
        tracks = List(Session.TRACK_COUNT) { emptyTrack() },
        bpm = bpm,
        barsPerInterval = barsPerInterval,
        sampleRate = sampleRate,
    )

    private fun emptyTrack(): Track = Track(name = EMPTY_TRACK, chain = listOf(SilenceBlock), engaged = false)

    /** True when nothing has been sent to this track — it holds silence and nothing else. */
    fun isEmpty(track: Track): Boolean = track.chain.all { it is SilenceBlock }

    /** How many of the six tracks hold something. */
    fun filled(session: Session): Int = session.tracks.count { !isEmpty(it) }

    /** The first track with nothing in it, or -1 when all six are full. */
    fun nextEmpty(session: Session): Int = session.tracks.indexOfFirst { isEmpty(it) }

    /** What [send] did: the new session, the track it landed on, and what it cost. */
    data class Sent(
        val session: Session,
        val trackIndex: Int,
        /** Blocks in the new chain — how many intervals of the snip are on the grid. */
        val blocks: Int,
        /**
         * True when the snip ran longer than [Session.MAX_CHAIN] intervals and
         * the tail was left off. The caller has to say so: silently dropping
         * the back half of a take is exactly the kind of thing a screen must
         * not keep to itself.
         */
        val truncated: Boolean,
    )

    /**
     * Frames in one interval at [rate] — the length of every piece [send] cuts.
     *
     * Derived from [Session.intervalFrames] itself rather than repeating its
     * arithmetic, so a piece written at the snip's own rate can never disagree
     * with the interval the engine bakes to at the device's rate. The two
     * differ by at most a frame of rounding, which is far inside
     * [BlockBaker.FIT_TOLERANCE]: a piece is trimmed or padded by a frame, never
     * sliced and re-placed.
     */
    fun chunkFrames(session: Session, rate: Int): Int = session.copy(sampleRate = rate).intervalFrames

    /**
     * Cut [audio] into interval-length WAVs in [dir] and hand [trackIndex] the
     * chain that plays them.
     *
     * [stem] names the files and must identify this snip on its own — pass the
     * source file's own name without its extension (`snip_<millis>[_<name>]`),
     * whose capture time is unique per snip, rather than a display name two
     * snips could share. Re-sending the same snip rewrites the same filenames
     * with the same contents, so sending twice costs nothing extra on disk.
     *
     * Kept exactly, not run through `Names.sanitizeStem`, when [stem] is
     * already `Names.isMpcSafe` — which every stem SNIPS hands this is,
     * since a SNIPS filename is either auto-classified (never an unsafe
     * character) or a typed name `SnipStore.rename` already gated on that
     * same check. `sanitizeStem` is stricter than `isMpcSafe` — it also
     * collapses `__` and trims edge `_`/`.` — so running an already-safe
     * stem through it can CHANGE it, and [sourceOf] recovers the piece's
     * name by reversing this exact step: skipping it when it would be a
     * no-op keeps that recovery exact instead of merely close. Only a
     * stem `isMpcSafe` refuses falls back to the stricter form, for a
     * caller other than SNIPS itself.
     *
     * Returns null when there is nothing to send: an empty or unreadable decode.
     * A caller that gets null must say so rather than pretending a track was
     * filled.
     */
    fun send(
        session: Session,
        trackIndex: Int,
        name: String,
        stem: String,
        audio: Snip,
        dir: File,
    ): Sent? {
        require(trackIndex in session.tracks.indices) {
            "track $trackIndex is outside a ${session.tracks.size}-track session"
        }
        if (audio.frameCount <= 0 || audio.sampleRate <= 0) return null

        val frames = chunkFrames(session, audio.sampleRate)
        check(frames > 0) { "an interval of $frames frames at ${audio.sampleRate} Hz" }

        // Ceiling division without floating point: a snip one frame past a
        // boundary still gets the whole next block, padded.
        val wanted = (audio.frameCount + frames - 1) / frames
        val blocks = wanted.coerceAtMost(Session.MAX_CHAIN)

        dir.mkdirs()
        val safe = if (Names.isMpcSafe(stem)) stem else Names.sanitizeStem(stem)
        val chain = (0 until blocks).map { n ->
            val file = File(dir, "${safe}_${n + 1}.wav")
            writePiece(file, pieceAt(audio, n * frames, frames))
            LoopBlock(file.name)
        }

        val tracks = session.tracks.toMutableList()
        tracks[trackIndex] = Track(name = name, chain = chain, engaged = true)
        return Sent(
            session = session.copy(tracks = tracks.toList()),
            trackIndex = trackIndex,
            blocks = blocks,
            truncated = wanted > blocks,
        )
    }

    /**
     * The snip a piece was cut from, recovered from the name [send] gave it.
     *
     * Every piece is `<stem>_<block>.wav`, `<block>` a plain 1-based index
     * ([send]'s own contract, one always appended, even for a chain of
     * one) — so this only strips a trailing `_`-delimited segment that IS
     * one: all digits. That is the difference between undoing [send]'s own
     * suffix and guessing at one: `LoopBlock` only requires a bare
     * filename, nothing enforces that every value ever stored there went
     * through [send], and a stem that legitimately ends in a word after an
     * underscore (`snip_<millis>_kick`, with no piece suffix at all — not
     * a shape [send] writes today, but one a hand-edited sidecar or a
     * future caller could) must come back unchanged rather than losing
     * "kick" to a strip that assumed it was a piece index. A stem that
     * itself ends in digits after an underscore before [send] ever ran —
     * `snip_<millis>_take_2` — still recovers correctly: only the
     * PIECE's own trailing digits are stripped, whatever the stem already
     * ends with, because the check runs once, on the outermost segment,
     * not by scanning for the first place digits appear.
     */
    fun sourceOf(sampleFile: String): String {
        val base = sampleFile.removeSuffix(".wav")
        val cut = base.lastIndexOf('_')
        if (cut < 0) return base
        val suffix = base.substring(cut + 1)
        return if (suffix.isNotEmpty() && suffix.all(Char::isDigit)) base.substring(0, cut) else base
    }

    /**
     * Take a track back to empty.
     *
     * The pieces stay on disk on purpose: this drops the arrangement, not the
     * audio, and the snip itself was never moved out of SNIPS in the first
     * place. Sending it again writes the same filenames back — which is a
     * second send, not an undo, and the copy that announces this says so.
     */
    fun clear(session: Session, trackIndex: Int): Session {
        require(trackIndex in session.tracks.indices) {
            "track $trackIndex is outside a ${session.tracks.size}-track session"
        }
        val tracks = session.tracks.toMutableList()
        tracks[trackIndex] = emptyTrack()
        return session.copy(tracks = tracks.toList())
    }

    /**
     * One piece, written the way [SessionStore] writes the sidecar: into a
     * sibling temp file and renamed over the target.
     *
     * A re-send deliberately reuses the same filenames, so a piece is not
     * always a new file — it can be one a track already on the grid is
     * playing from. Writing straight to that path means an interrupted write
     * leaves that track pointing at a truncated WAV while its sidecar still
     * says everything is fine. Rename-into-place means the old piece survives
     * intact instead.
     *
     * Through the stream overload rather than `WavWriter.write(file, ...)`:
     * the bytes have to exist before the rename, and the stream form also has
     * no MPC-rate check to opt out of — a piece keeps the snip's own rate by
     * design.
     */
    private fun writePiece(file: File, piece: Snip) {
        val bytes = ByteArrayOutputStream().also { WavWriter.write(it, piece) }.toByteArray()
        AtomicFile.writeBytes(file, bytes)
    }

    /** Exactly [frames] frames from [at], zero-padded when the source runs out. */
    private fun pieceAt(audio: Snip, at: Int, frames: Int): Snip {
        val out = FloatArray(frames * audio.channels)
        val from = at * audio.channels
        val n = (audio.samples.size - from).coerceIn(0, out.size)
        if (n > 0) System.arraycopy(audio.samples, from, out, 0, n)
        return Snip(out, audio.channels, audio.sampleRate)
    }
}
