package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Maps the held SING API's two coordinate spaces without altering any audio.
 *
 * Cairn.Render.samples is only the accepted later cycle. Its diagnostic marker
 * addresses Cairn.Render.full, which also retains a real driven predecessor.
 * That predecessor starts after convergence, so it is context, not an attack.
 * This object deliberately has no instrument/export route or note-off envelope.
 */
object CairnAcceptedSingContext {
    enum class OnsetPolicy { SETTLED_NATIVE_PREDECESSOR }

    class Context internal constructor(
        val sourceId: String,
        val recipeSha256: String,
        val effectiveMidi: Int,
        private val full: FloatArray,
        private val cycle: FloatArray,
        val fullLoopStartFrame: Int,
        val fullOutputStartFrame: Int,
        val seamError: Double,
    ) {
        val onsetPolicy: OnsetPolicy get() = OnsetPolicy.SETTLED_NATIVE_PREDECESSOR
        val hasNaturalAttack: Boolean get() = false
        val hasNaturalRelease: Boolean get() = false
        val fullEndFrameExclusive: Int get() = full.size
        val croppedEndFrameExclusive: Int get() = cycle.size
        /** Valid cycle-space marker; zero means no loop in the shared keygroup schema. */
        val croppedLoopStartFrame: Int get() = fullLoopStartFrame - fullOutputStartFrame

        /** Both methods return independent copies; consumers cannot damage the preserved body. */
        fun fullNativeContext(): Snip = Snip(full.copyOf(), 1, Cairn.RATE)
        fun acceptedCycle(): Snip = Snip(cycle.copyOf(), 1, Cairn.RATE)
        fun precedingNativeContext(): Snip = Snip(full.copyOfRange(0, fullLoopStartFrame), 1, Cairn.RATE)
    }

    /** Render only the exact saved recipe, including its model, cue, velocity and effective root. */
    fun prepare(source: CairnAcceptedSingSources.Source): Context {
        CairnAcceptedSingSources.requireMember(source)
        val patch = source.patch()
        val native = Cairn.inspect(patch.voice, patch.macros, patch.midi, patch.velocity,
            patch.startAtContact, model = patch.model)
        return fromNative(source, native)
    }

    /** Kept internal so an unrelated caller-supplied render cannot masquerade as accepted provenance. */
    internal fun fromNative(source: CairnAcceptedSingSources.Source, native: Cairn.Render): Context {
        CairnAcceptedSingSources.requireMember(source)
        val patch = source.patch()
        val effectiveMidi = Cairn.midiFor(patch.voice, patch.macros.getValue("TUNE"), patch.midi)
        val diagnostics = native.diagnostics
        require(diagnostics.internalRate == Cairn.RATE * Dsp.OVERSAMPLE &&
            diagnostics.requestedHz == Cairn.frequencyFor(effectiveMidi).toDouble()) {
            "CAIRN accepted context must retain its native rate and effective root"
        }
        val context = mapBuffers(source, effectiveMidi, native.full, native.samples,
            diagnostics.loopStartFrame, diagnostics.outputStartFrame)
        // Geometry alone cannot establish provenance: even matching fake prefix/body audio
        // must fail unless the house encoder reproduces both immutable native PCM24 references.
        require(pcm24Sha256(native.samples) == source.cyclePcmS24leSha256 &&
            pcm24Sha256(native.full) == source.fullPcmS24leSha256) {
            "CAIRN native replay differs from its accepted cycle or genuine predecessor PCM"
        }
        return context
    }

    /** Pure representation boundary, separately testable without a synthesis render. */
    internal fun mapBuffers(source: CairnAcceptedSingSources.Source, effectiveMidi: Int,
        full: FloatArray, cycle: FloatArray, fullLoopStartFrame: Int, fullOutputStartFrame: Int): Context {
        CairnAcceptedSingSources.requireMember(source)
        require(effectiveMidi == source.midi) { "CAIRN accepted bank root changed" }
        // These are the published native dimensions, not a made-up duplicated prefix.
        require(full.size == CairnAcceptedSingSources.FULL_FRAMES &&
            cycle.size == CairnAcceptedSingSources.CYCLE_FRAMES) {
            "CAIRN needs the genuine full predecessor plus its separately kept cycle"
        }
        require(fullLoopStartFrame == CairnAcceptedSingSources.CYCLE_FRAMES &&
            fullOutputStartFrame == fullLoopStartFrame && fullLoopStartFrame < full.size - 1) {
            "CAIRN loop and output markers must address full, not the cropped cycle"
        }
        require(full.all { it.isFinite() && it in -1f..1f } &&
            cycle.all { it.isFinite() && it in -1f..1f }) { "CAIRN context contains invalid audio" }
        require(cycle.indices.all { full[fullOutputStartFrame + it].toRawBits() == cycle[it].toRawBits() }) {
            "CAIRN accepted cycle must remain the exact unmodified full-buffer suffix"
        }
        // Uses the genuine preceding native window. Repeating a crop cannot supply this evidence.
        val seam = Cairn.silentBoundarySeam(full, fullLoopStartFrame)
        require(seam.isFinite() && seam < Keys.MAX_SEAM_ERROR) { "CAIRN preceding context does not close" }
        return Context(source.id, source.originalRecipe.sha256, effectiveMidi,
            full.copyOf(), cycle.copyOf(), fullLoopStartFrame, fullOutputStartFrame, seam)
    }

    /** Let the house writer own quantization; this fixed-size, metadata-free WAV has a 44-byte header. */
    private fun pcm24Sha256(samples: FloatArray): String {
        val wav = ByteArrayOutputStream(44 + samples.size * 3).also {
            WavWriter.write(it, Snip(samples, 1, Cairn.RATE), WavWriter.BitDepth.PCM_24)
        }.toByteArray()
        check(wav.size == 44 + samples.size * 3) { "CAIRN reference encoding layout changed" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(wav, 44, wav.size - 44)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
