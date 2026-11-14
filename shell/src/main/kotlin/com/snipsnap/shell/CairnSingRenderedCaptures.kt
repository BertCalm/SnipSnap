package com.snipsnap.shell

import com.snipsnap.kit.AtomicFile
import com.snipsnap.synth.CairnAcceptedSingContext
import com.snipsnap.synth.CairnAcceptedSingSources
import com.snipsnap.synth.CairnSamplerDelivery
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/**
 * The nine retained SING captures, rendered on this machine instead of shipped.
 *
 * The repository does not carry the nine native Float captures (705,600 bytes
 * each). The CAIRN engine reproduces every one of them bit for bit, so the
 * phone renders a capture the first time a CAIRN SING study needs it. It
 * keeps the render only if its SHA-256 matches the pin in
 * [CairnSingInstrumentMaker]. Anything else is refused with [Mismatch] and is
 * never cached or admitted.
 *
 * Cached files are checked again on every use: a cached file that no longer
 * matches its pin is rendered again. [CairnSingInstrumentMaker.readCaptures]
 * then re-reads every pair and applies its own Float, metadata and PCM24 checks,
 * so nothing here can widen what the maker admits. The nine small metadata
 * files ship as resources next to this class.
 */
object CairnSingRenderedCaptures {
    const val METADATA_ROOT = "/cairn-retained-sing/"

    /** A render that does not reproduce its pinned capture on this machine. */
    class Mismatch(val sourceId: String, cause: Throwable? = null) : IllegalStateException(
        "CAIRN SING $sourceId did not render to its pinned capture on this machine, so it was not used", cause)

    /** The engine's own render of one accepted source: the full native Float context. */
    fun nativeRender(source: CairnAcceptedSingSources.Source): FloatArray = try {
        CairnAcceptedSingContext.prepare(source).fullNativeContext().samples
    } catch (e: IllegalArgumentException) {
        // prepare() already refuses a render whose PCM24 anchors differ.
        throw Mismatch(source.id, e)
    }

    /**
     * A complete pinned capture set for [program], rendering into [cache] only
     * what is missing or no longer matches. [progress] reports (rendered, needed)
     * once before the first render and after each one.
     */
    fun readCaptures(program: CairnSingInstrumentMaker.Program, cache: File,
        cancelled: () -> Boolean = { false },
        progress: (Int, Int) -> Unit = { _, _ -> },
        render: (CairnAcceptedSingSources.Source) -> FloatArray = ::nativeRender,
    ): Map<String, CairnSamplerDelivery.Capture> {
        fun stop() {
            if (cancelled() || Thread.currentThread().isInterrupted)
                throw CancellationException("CAIRN SING render cancelled")
        }
        stop()
        val ids = CairnSingInstrumentMaker.sourceIds(program)
        val missing = ids.filterNot { cached(it, cache) }
        if (missing.isNotEmpty()) progress(0, missing.size)
        missing.forEachIndexed { index, id ->
            stop()
            renderInto(id, cache, render)
            progress(index + 1, missing.size)
        }
        stop()
        return CairnSingInstrumentMaker.readCaptures(program, open = { name -> open(name, cache) }, cancelled)
    }

    /** Whether [cache] already holds a capture for [sourceId] that matches its pin. */
    fun cached(sourceId: String, cache: File): Boolean {
        val file = captureFile(sourceId, cache)
        return file.isFile && file.length() == CairnSamplerDelivery.CAPTURE_BYTES.toLong() &&
            CairnSamplerDelivery.sha(file.readBytes()) == CairnSingInstrumentMaker.floatSha256(sourceId)
    }

    fun captureFile(sourceId: String, cache: File): File {
        val source = requireNotNull(CairnAcceptedSingSources.byId(sourceId)) { "Unknown retained CAIRN source" }
        return File(cache, source.recordingId + ".full.f32le")
    }

    /** Little-endian IEEE 754 binary32, the retained captures' byte layout. */
    fun encode(samples: FloatArray): ByteArray =
        ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .also { out -> samples.forEach { out.putInt(it.toRawBits()) } }.array()

    private fun renderInto(sourceId: String, cache: File,
                           render: (CairnAcceptedSingSources.Source) -> FloatArray) {
        val source = requireNotNull(CairnAcceptedSingSources.byId(sourceId))
        val bytes = encode(render(source))
        if (bytes.size != CairnSamplerDelivery.CAPTURE_BYTES ||
            CairnSamplerDelivery.sha(bytes) != CairnSingInstrumentMaker.floatSha256(sourceId)) {
            // A stale cached copy must not survive a failed repair either.
            captureFile(sourceId, cache).delete()
            throw Mismatch(sourceId)
        }
        cache.mkdirs()
        AtomicFile.writeBytes(captureFile(sourceId, cache), bytes)
    }

    private fun open(name: String, cache: File): InputStream =
        if (name.endsWith(".f32le")) File(cache, name).inputStream()
        else requireNotNull(CairnSingRenderedCaptures::class.java.getResourceAsStream(METADATA_ROOT + name)) {
            "Missing CAIRN SING metadata $name"
        }
}
