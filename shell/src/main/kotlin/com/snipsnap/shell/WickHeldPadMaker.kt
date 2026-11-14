package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.kit.Names
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Wick
import com.snipsnap.synth.WickPatch
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs

/**
 * Prototype, one captured root only. The genuine preceding settled period is
 * pre-roll, never a natural attack. The sampler's release is not WICK's decay.
 * No UI, export route, range qualification or factory eligibility is added here.
 */
object WickHeldPadMaker {
    data class Spec(val patch: WickPatch, val releaseSeconds: Float = .35f) {
        init {
            require(patch.velocity > 0f && Wick.isLoop(Wick.settled(patch.macros, patch.voice).getValue("HOLD")))
            require(releaseSeconds.isFinite() && releaseSeconds in .01f..1.5f)
        }
    }

    data class Captured internal constructor(
        val source: WickPatch,
        val note: KeyNote,
        /** Exact native returned cycle, distinct from the near-equal context tail. */
        val acceptedCycle: FloatArray,
        /** Unmodified witness of both genuine periods, not the assembled buffer. */
        val originalSeamContext: FloatArray,
        val contextTailMaxAbsError: Double,
        val originalContextSeam: Double,
        val joinedSeam: Double,
        val sourceSha256: String,
        val acceptedCycleSha256: String,
        val originalContextSha256: String,
        val assembledSha256: String,
    ) {
        val onsetPolicy: String get() = "SETTLED_NATIVE_PREDECESSOR"
        val naturalAttack: Boolean get() = false
        val naturalRelease: Boolean get() = false
    }

    /** Never rerenders or changes either native input representation. */
    internal fun capture(spec: Spec, probe: Wick.Probe): Captured {
        val loop = requireNotNull(probe.diagnostics.loop) { "WICK held diagnostics missing" }
        val p = loop.periodFrames
        require(loop.converged && p >= 256 && p <= Int.MAX_VALUE / 2)
        require(probe.samples.size == p && probe.raw.size == p) { "WICK cycle coordinates differ" }
        require(loop.seam.isFinite() && loop.seam < Keys.MAX_SEAM_ERROR)
        val context = requireNotNull(probe.seamContext) { "WICK genuine predecessor missing" }
        require(context.size == 2 * p) { "WICK context must contain exactly two periods" }
        require(probe.samples.all { it.isFinite() } && probe.raw.all { it.isFinite() } && context.all { it.isFinite() })
        val tailError = probe.samples.indices.maxOf { abs(probe.samples[it].toDouble() - context[p + it]) }
        // The existing native context contract is near equality, not bit equality.
        require(tailError < 1e-6) { "WICK context tail differs from native cycle" }
        val originalSeam = checkedSeam(context, p)
        val joined = FloatArray(2 * p)
        context.copyInto(joined, endIndex = p)
        probe.samples.copyInto(joined, destinationOffset = p)
        // Near equality alone cannot prove a quiet join; measure the actual body.
        val joinedSeam = checkedSeam(joined, p)
        return Captured(spec.patch, KeyNote(Snip(joined, 1, WavWriter.MPC_SAMPLE_RATE), p.toLong()),
            probe.samples.copyOf(), context.copyOf(), tailError, originalSeam, joinedSeam,
            textHash(spec.patch.toJsonText()), floatHash(probe.samples), floatHash(context), floatHash(joined))
    }

    /** Future maker entrypoint; only the exact spec root is requested, HOLD is not forced. */
    fun renderZone(spec: Spec, cancelled: () -> Boolean = { false }): Captured {
        fun stop() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("WICK held context cancelled") }
        stop()
        val source = spec.patch
        val probe = Wick.inspect(source.voice, source.macros, source.midi, source.velocity, rattleSource = source.rattleSource)
        stop()
        return capture(spec, probe)
    }

    /** Existing KeyNote -> VelocityLayer -> KeygroupProgram consumer, bounded to one root. */
    fun assemble(name: String, spec: Spec, captured: Captured): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name) && captured.source == spec.patch)
        val p = captured.acceptedCycle.size
        require(textHash(spec.patch.toJsonText()) == captured.sourceSha256)
        require(floatHash(captured.acceptedCycle) == captured.acceptedCycleSha256 &&
            floatHash(captured.originalSeamContext) == captured.originalContextSha256 &&
            floatHash(captured.note.snip.samples) == captured.assembledSha256) { "WICK capture changed after binding" }
        require(captured.note.loopStartFrame == p.toLong() && captured.note.snip.frameCount == 2 * p)
        val stem = "WickHeld_${spec.patch.midi}"
        val layer = VelocityLayer(stem, captured.note.snip.frameCount.toLong(), 0, 127, captured.note.loopStartFrame)
        return KeygroupProgram(name, listOf(Keygroup(spec.patch.midi, spec.patch.midi, spec.patch.midi, listOf(layer))),
            volumeRelease = spec.releaseSeconds, explicitNoteOn = true) to mapOf(stem to captured.note.snip)
    }

    private fun checkedSeam(samples: FloatArray, start: Int): Double = Keys.seamError(samples, start).also {
        require(it.isFinite() && it < Keys.MAX_SEAM_ERROR) { "WICK held context join exceeds the existing seam bar: $it" }
    }

    private fun textHash(text: String): String = hash(text.toByteArray(Charsets.UTF_8))
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun floatHash(samples: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(4)
        for (sample in samples) {
            val bits = sample.toRawBits()
            for (i in bytes.indices) bytes[i] = (bits ushr (8 * i)).toByte()
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
