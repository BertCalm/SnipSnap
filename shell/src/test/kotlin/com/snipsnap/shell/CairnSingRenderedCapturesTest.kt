package com.snipsnap.shell

import com.snipsnap.shell.CairnSingInstrumentMaker.Program
import com.snipsnap.synth.CairnAcceptedSingSources
import com.snipsnap.synth.CairnSamplerDelivery
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The phone's render-and-verify path in place of the nine shipped Float captures. */
class CairnSingRenderedCapturesTest {
    @field:TempDir(cleanup = CleanupMode.ON_SUCCESS)
    lateinit var temporary: Path

    private val allIds = Program.entries.flatMap(CairnSingInstrumentMaker::sourceIds)

    private fun decoded(id: String): FloatArray {
        val bytes = CairnSingRenderedCaptures.captureFile(id, CairnSingTestCache.dir).readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }

    @Test fun nativeRendersReproduceAllNinePinnedFloatCaptures() {
        assertEquals(9, allIds.distinct().size)
        for (id in allIds) {
            val file = CairnSingRenderedCaptures.captureFile(id, CairnSingTestCache.dir)
            assertEquals(CairnSamplerDelivery.CAPTURE_BYTES.toLong(), file.length())
            assertEquals(CairnSingInstrumentMaker.floatSha256(id), CairnSamplerDelivery.sha(file.readBytes()), id)
            assertTrue(CairnSingRenderedCaptures.cached(id, CairnSingTestCache.dir))
        }
        assertEquals(allIds.map { CairnSingRenderedCaptures.captureFile(it, CairnSingTestCache.dir).name }.toSet(),
            CairnSingTestCache.dir.listFiles()!!.map { it.name }.toSet())
        for (program in Program.entries) {
            val captures = CairnSingTestCache.load(program) { false }
            assertEquals(CairnSingInstrumentMaker.sourceIds(program), captures.keys.toList())
            for ((id, capture) in captures) assertEquals(CairnSingInstrumentMaker.floatSha256(id), capture.captureSha256)
        }
    }

    @Test fun aCorruptedRenderIsRefusedAndNeverCached() {
        val cache = File(temporary.toFile(), "cache")
        val silent = assertFailsWith<CairnSingRenderedCaptures.Mismatch> {
            CairnSingRenderedCaptures.readCaptures(Program.GROOVE_0, cache,
                render = { FloatArray(CairnSamplerDelivery.FULL_FRAMES) })
        }
        assertEquals("cairn-groove-0", silent.sourceId)
        assertFalse(CairnSingRenderedCaptures.captureFile("cairn-groove-0", cache).exists())
        // One low bit in one sample is enough to refuse.
        val nearly = decoded("cairn-groove-0").also { it[100_000] = Float.fromBits(it[100_000].toRawBits() xor 1) }
        assertFailsWith<CairnSingRenderedCaptures.Mismatch> {
            CairnSingRenderedCaptures.readCaptures(Program.GROOVE_0, cache, render = { nearly })
        }
        assertFalse(CairnSingRenderedCaptures.captureFile("cairn-groove-0", cache).exists())
        // A wrong length is refused too.
        assertFailsWith<CairnSingRenderedCaptures.Mismatch> {
            CairnSingRenderedCaptures.readCaptures(Program.GROOVE_0, cache, render = { FloatArray(16) })
        }
        assertFalse(CairnSingRenderedCaptures.captureFile("cairn-groove-0", cache).exists())
    }

    @Test fun aCorruptedOrTruncatedCachedCaptureIsRenderedAgain() {
        val cache = File(temporary.toFile(), "cache").apply { mkdirs() }
        val good = CairnSingRenderedCaptures.captureFile("cairn-groove-50", CairnSingTestCache.dir).readBytes()
        val target = CairnSingRenderedCaptures.captureFile("cairn-groove-50", cache)
        for (damaged in listOf(good.copyOf().also { it[4321] = (it[4321].toInt() xor 0x10).toByte() },
                               good.copyOf(good.size - 4))) {
            target.writeBytes(damaged)
            assertFalse(CairnSingRenderedCaptures.cached("cairn-groove-50", cache))
            var renders = 0
            val captures = CairnSingRenderedCaptures.readCaptures(Program.GROOVE_50, cache,
                render = { source -> renders++; assertEquals("cairn-groove-50", source.id); decoded(source.id) })
            assertEquals(1, renders)
            assertContentEquals(good, target.readBytes())
            assertEquals(CairnSingInstrumentMaker.floatSha256("cairn-groove-50"),
                captures.getValue("cairn-groove-50").captureSha256)
        }
        // A cached copy that a failed repair could not fix is removed, not kept.
        target.writeBytes(good.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        assertFailsWith<CairnSingRenderedCaptures.Mismatch> {
            CairnSingRenderedCaptures.readCaptures(Program.GROOVE_50, cache,
                render = { FloatArray(CairnSamplerDelivery.FULL_FRAMES) })
        }
        assertFalse(target.exists())
    }

    @Test fun aGoodCacheIsUsedWithoutRenderingAndProgressCountsOnlyRenders() {
        val cache = File(temporary.toFile(), "cache").apply { mkdirs() }
        val ids = CairnSingInstrumentMaker.sourceIds(Program.MOVING)
        CairnSingRenderedCaptures.captureFile(ids[0], CairnSingTestCache.dir)
            .copyTo(CairnSingRenderedCaptures.captureFile(ids[0], cache))
        val progress = mutableListOf<Pair<Int, Int>>()
        val rendered = mutableListOf<String>()
        CairnSingRenderedCaptures.readCaptures(Program.MOVING, cache, progress = { done, total -> progress += done to total },
            render = { source -> rendered += source.id; decoded(source.id) })
        assertEquals(ids.drop(1), rendered)
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
        rendered.clear(); progress.clear()
        CairnSingRenderedCaptures.readCaptures(Program.MOVING, cache, progress = { done, total -> progress += done to total },
            render = { source -> rendered += source.id; decoded(source.id) })
        assertTrue(rendered.isEmpty() && progress.isEmpty())
    }

    @Test fun cancellationBeforeOrBetweenRendersStopsWithoutCachingTheRest() {
        val cache = File(temporary.toFile(), "cache")
        var renders = 0
        assertFailsWith<CancellationException> {
            CairnSingRenderedCaptures.readCaptures(Program.EARLIER, cache, cancelled = { true },
                render = { renders++; decoded(it.id) })
        }
        assertEquals(0, renders)
        assertFailsWith<CancellationException> {
            CairnSingRenderedCaptures.readCaptures(Program.EARLIER, cache, cancelled = { renders >= 1 },
                render = { renders++; decoded(it.id) })
        }
        assertEquals(1, renders)
        assertEquals(1, cache.listFiles()!!.size)
    }

    @Test fun aRefusedRenderNeverReachesTheShelf() {
        val destination = File(temporary.toFile(), "shelf")
        val cache = File(temporary.toFile(), "cache")
        assertFailsWith<CairnSingRenderedCaptures.Mismatch> {
            CairnSingInstrumentPicker.make(Program.GROOVE_100.id, destination, { program, cancelled ->
                CairnSingRenderedCaptures.readCaptures(program, cache, cancelled,
                    render = { FloatArray(CairnSamplerDelivery.FULL_FRAMES) })
            })
        }
        assertFalse(destination.exists())
    }

    @Test fun everyMetadataResourceShipsWithTheShell() {
        for (id in allIds) {
            val source = requireNotNull(CairnAcceptedSingSources.byId(id))
            assertTrue(CairnSingRenderedCaptures::class.java
                .getResource(CairnSingRenderedCaptures.METADATA_ROOT + source.recordingId + ".full.json") != null, id)
        }
    }
}
