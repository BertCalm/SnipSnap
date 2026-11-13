package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Keys
import com.snipsnap.synth.MurmurKeptSources
import com.snipsnap.synth.MurmurRoundRobinInstrumentSource
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** New transfer coverage; fixture PCM is synthetic and is never an owner audition. */
class MurmurRoundRobinPcmTransferTest {
    @field:TempDir(cleanup = CleanupMode.ON_SUCCESS) lateinit var temporary: Path
    private fun fixture(): Pair<File, MurmurRoundRobinShelf.SavedBank> {
        val source = File(temporary.toFile(), "source")
        val spec = MurmurRoundRobinInstrumentBank.Spec(MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2).factoryPatch())
        MurmurRoundRobinInstrumentBank.writeNewUsing("RR", spec, source, { false }, { _, _ -> }, { recipe ->
            val samples = FloatArray(17) { if (it == 16) 0f else (recipe.take + 1) * (recipe.touchIndex + 1) * .01f }
            val hash = MurmurRoundRobinInstrumentSource.floatSha256(samples)
            MurmurRoundRobinInstrumentSource.Capture(recipe, Snip(samples, 1, 44100), 0, samples.size,
                hash, MurmurRoundRobinInstrumentSource.floatSha256(floatArrayOf()), hash, 0.0, Keys.midiHz(recipe.midi).toDouble())
        })
        val shelf = File(temporary.toFile(), "shelf")
        return shelf to MurmurRoundRobinShelf.admitSaved("RR", source, shelf)
    }
    private fun root(shelf: File) = File(shelf, "${MurmurRoundRobinShelf.DIRECTORY}/RR/bank")
    private fun wavs(shelf: File) = root(shelf).walkTopDown().filter { it.isFile && it.extension == "wav" }.toList()
    @Test fun streamsAllCellsWithExactHashesFramesAndTakeRootTouchMapping() {
        val (shelf, saved) = fixture(); val seen = mutableListOf<MurmurRoundRobinShelf.PcmCell>()
        var planned = false
        val result = MurmurRoundRobinShelf.streamPcm(saved, shelf, 1234, onPlan = { plan ->
            planned = true; assertEquals(saved.identitySha256, plan.saved.identitySha256)
            assertTrue(plan.peakPcmWorkingBytes <= MurmurRoundRobinShelf.MAX_TRANSFER_PCM_BYTES)
        }, onCell = { cell, pcm ->
            assertTrue(planned); assertEquals(seen.size, cell.ordinal); assertEquals(17, pcm.size)
            assertEquals(cell.decodedSha256, MurmurRoundRobinInstrumentSource.floatSha256(pcm)); seen += cell
        })
        assertEquals(84, seen.size); assertEquals(7, seen.map { it.root }.distinct().size)
        assertEquals((0..3).toList(), seen.map { it.take }.distinct()); assertEquals((0..2).toList(), seen.map { it.touch }.distinct())
        assertEquals(5712L, result.saved.decodedPcmBytes)
    }
    @Test fun finalWavMutationPreventsTransferSuccessAfterAllCells() {
        val (shelf, saved) = fixture(); var count = 0
        assertFailsWith<IllegalArgumentException> {
            MurmurRoundRobinShelf.streamPcm(saved, shelf, onCell = { _, _ ->
                if (++count == 84) wavs(shelf).first().appendBytes(byteArrayOf(9))
            })
        }
        assertEquals(84, count)
    }
    @Test fun finalAdmissionMutationCannotReturnTheOldIdentity() {
        val (shelf, saved) = fixture(); var count = 0
        assertFailsWith<IllegalArgumentException> {
            MurmurRoundRobinShelf.streamPcm(saved, shelf, onCell = { _, _ ->
                if (++count == 84) File(root(shelf).parentFile, "admission.json").appendText(" ")
            })
        }
        assertEquals(84, count)
    }
    @Test fun laterCellMutationFailsBeforeThatPcmReachesConsumer() {
        val (shelf, saved) = fixture(); var count = 0
        assertFailsWith<IllegalArgumentException> {
            MurmurRoundRobinShelf.streamPcm(saved, shelf, onCell = { _, _ ->
                if (++count == 1) wavs(shelf).filter { it.path.contains("take-4") }.first().appendBytes(byteArrayOf(9))
            })
        }
        assertTrue(count in 1..83)
    }
    @Test fun cancellationBeforeAndDuringTransferDoesNotReportSuccess() {
        val (shelf, saved) = fixture(); var plans = 0; var cells = 0
        assertFailsWith<CancellationException> {
            MurmurRoundRobinShelf.streamPcm(saved, shelf, cancelled = { true }, onPlan = { plans++ }, onCell = { _, _ -> cells++ })
        }
        assertEquals(0, plans); assertEquals(0, cells)
        assertFailsWith<CancellationException> {
            MurmurRoundRobinShelf.streamPcm(saved, shelf, cancelled = { cells == 3 }, onCell = { _, _ -> cells++ })
        }
        assertEquals(3, cells)
    }
    @Test fun oldResidentPcmBudgetRefusesBeforePlanAndNativeAllocation() {
        val (shelf, saved) = fixture(); var plan = false
        assertFailsWith<IllegalArgumentException> {
            MurmurRoundRobinShelf.streamPcm(saved, shelf, MurmurRoundRobinShelf.MAX_TRANSFER_PCM_BYTES,
                onPlan = { plan = true }, onCell = { _, _ -> fail("Must refuse before PCM delivery") })
        }
        assertFalse(plan)
    }
    @Test fun metadataCapsRefuseBeforeParsingWithAnUpdatedAdmissionSeal() {
        val (shelf, saved) = fixture()
        val bank = root(shelf)
        val files = bank.walkTopDown().filter { it.isFile }.toList()
        val metadata = files.filter { it.extension != "wav" }
        val original = metadata.associateWith { it.readBytes() }
        val admission = File(bank.parentFile, "admission.json")
        val oldSeal = Json.parse(admission.readText()).obj()
        for (aggregate in listOf(false, true)) {
            for ((file, bytes) in original) file.writeBytes(bytes)
            if (aggregate) metadata.forEach { it.writeBytes(ByteArray(300 * 1024) { 32 }) }
            else metadata.first().writeBytes(ByteArray(512 * 1024 + 1) { 32 })
            val rows = oldSeal.getValue("files").arr().map { value ->
                val row = value.obj().toMutableMap(); val file = File(bank, row.getValue("path").str())
                row["bytes"] = JsonValue.Num(file.length().toDouble())
                row["sha256"] = JsonValue.Str(MurmurRoundRobinPcm.sha256(file.readBytes()))
                JsonValue.Obj(row)
            }
            val seal = Json.write(JsonValue.Obj(oldSeal.toMutableMap().apply { put("files", JsonValue.Arr(rows)) }))
            admission.writeText(seal)
            val changed = MurmurRoundRobinShelf.SavedBank(saved.name, MurmurRoundRobinPcm.sha256(seal.toByteArray()),
                saved.decodedPcmBytes, saved.cellCount, saved.supportedMidi)
            val error = assertFailsWith<IllegalArgumentException> {
                MurmurRoundRobinShelf.streamPcm(changed, shelf, onCell = { _, _ -> fail("Metadata must be refused first") })
            }
            assertTrue(error.message.orEmpty().contains("metadata transfer cap"))
        }
    }

    @Test fun consumerFailureStopsImmediatelyWithoutChangingSavedBank() {
        val (shelf, saved) = fixture(); var count = 0
        assertFailsWith<IllegalStateException> {
            MurmurRoundRobinShelf.streamPcm(saved, shelf, onCell = { _, _ -> count++; error("copy failed") })
        }
        assertEquals(1, count); assertEquals(saved.identitySha256, MurmurRoundRobinShelf.list(shelf).banks.single().identitySha256)
    }
    @Test fun boundedReadRefusesGrowthShrinkSymlinkAndHashMismatch() {
        val file = File(temporary.toFile(), "bytes").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val hash = MurmurRoundRobinPcm.sha256(file.readBytes())
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinPcm.read(file, 2, hash) { false } }
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinPcm.read(file, 4, hash) { false } }
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinPcm.read(file, 3, "0".repeat(64)) { false } }
        val link = File(temporary.toFile(), "link"); java.nio.file.Files.createSymbolicLink(link.toPath(), file.toPath())
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinPcm.read(link, 3, hash) { false } }
    }
    @Test fun snapshotParserRejectsRateDepthAndTruncatedRiffBeforeDecode() {
        val file = File(temporary.toFile(), "sample.wav")
        WavWriter.write(file, Snip(floatArrayOf(.1f, .2f, 0f), 1, 44100))
        val bytes = file.readBytes(); assertEquals(3L, MurmurRoundRobinPcm.info(bytes).frameCount)
        for (offset in listOf(4, 20, 22, 24, 28, 32, 34)) {
            val bad = bytes.copyOf(); bad[offset] = (bad[offset].toInt() xor 1).toByte()
            assertFailsWith<IllegalArgumentException> { MurmurRoundRobinPcm.info(bad) }
        }
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinPcm.info(bytes.copyOf(bytes.size - 1)) }
    }
}
