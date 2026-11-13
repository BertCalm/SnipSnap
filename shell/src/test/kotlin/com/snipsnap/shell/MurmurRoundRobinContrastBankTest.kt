package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.Keys
import com.snipsnap.synth.MurmurKeptSources
import com.snipsnap.synth.MurmurRoundRobinInstrumentSource
import com.snipsnap.synth.MurmurRoundRobinInstrumentSource.RoundRobinPolicy
import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The opt-in V2 repeat policy as a saved instrument. The format checks use synthetic PCM;
 * the last test renders the real bank and plays it from its saved files.
 */
class MurmurRoundRobinContrastBankTest {
    @field:TempDir(cleanup = CleanupMode.ON_SUCCESS)
    lateinit var temporary: Path
    private fun directory(name: String) = File(temporary.toFile(), name)
    private val steady get() = MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2).factoryPatch()

    private fun synthetic(recipe: MurmurRoundRobinInstrumentSource.CellRecipe): MurmurRoundRobinInstrumentSource.Capture {
        val raw = FloatArray(512) { (.01 * (recipe.take + 1) * (recipe.touchIndex + 1) * sin(2 * PI * it / 64)).toFloat() }
        val samples = raw.copyOf().also { it[it.lastIndex] = 0f }
        return MurmurRoundRobinInstrumentSource.Capture(recipe, Snip(samples, 1, WavWriter.MPC_SAMPLE_RATE), 0, samples.size,
            MurmurRoundRobinInstrumentSource.floatSha256(raw), MurmurRoundRobinInstrumentSource.floatSha256(floatArrayOf()),
            MurmurRoundRobinInstrumentSource.floatSha256(samples), 0.0, Keys.midiHz(recipe.midi).toDouble())
    }

    private fun build(policy: RoundRobinPolicy, folder: String): File = directory(folder).also { root ->
        MurmurRoundRobinInstrumentBank.writeNewUsing(NAME, MurmurRoundRobinInstrumentBank.Spec(steady, policy = policy), root,
            { false }, { _, _ -> }, ::synthetic)
    }
    private fun manifest(root: File) = Json.parse(File(root, MurmurRoundRobinInstrumentBank.MANIFEST).readText()).obj()

    @Test fun `a default bank keeps the version 1 format and never names a policy`() {
        val root = build(RoundRobinPolicy.BREATH_V1, "v1")
        val manifest = manifest(root)
        assertEquals(1, manifest.getValue("version").int())
        assertFalse("roundRobinPolicy" in manifest)
        val packet = MurmurRoundRobinInstrumentBank.validate(NAME, root)
        assertEquals(RoundRobinPolicy.BREATH_V1, packet.spec.policy)
        for (take in packet.takes) for (row in take.rows) {
            val cell = Json.parse(row.recipeJson).obj()
            assertEquals(MurmurRoundRobinInstrumentSource.RoundRobinPolicy.BREATH_V1.json, cell.getValue("roundRobinPolicy").str())
        }
    }

    @Test fun `a V2 bank names its policy at every level and reloads as V2`() {
        val root = build(RoundRobinPolicy.CONTRAST_V2, "v2")
        val manifest = manifest(root)
        assertEquals(2, manifest.getValue("version").int())
        assertEquals("NATIVE_OPERATOR_CONTACT_V2", manifest.getValue("roundRobinPolicy").str())
        val packet = MurmurRoundRobinInstrumentBank.validate(NAME, root)
        assertEquals(RoundRobinPolicy.CONTRAST_V2, packet.spec.policy)
        assertEquals(84, packet.cellCount)
        for (take in packet.takes) {
            assertEquals(RoundRobinPolicy.CONTRAST_V2, take.spec.policy)
            for (row in take.rows) {
                val cell = MurmurRoundRobinInstrumentSource.CellRecipe.fromJsonText(row.recipeJson)
                assertEquals(RoundRobinPolicy.CONTRAST_V2, cell.policy)
                assertEquals("NATIVE_OPERATOR_CONTACT_V2", cell.toJsonValue().obj().getValue("rendererBinding").obj().getValue("roundRobinPolicy").str())
            }
        }
        val shelf = directory("shelf")
        val saved = MurmurRoundRobinShelf.admitSaved(NAME, root, shelf)
        assertEquals(RoundRobinPolicy.CONTRAST_V2, saved.roundRobinPolicy)
        assertEquals(RoundRobinPolicy.CONTRAST_V2, MurmurRoundRobinShelf.list(shelf).banks.single().roundRobinPolicy)
    }

    @Test fun `neither policy's bank can be read as the other`() {
        val v1 = build(RoundRobinPolicy.BREATH_V1, "v1")
        val v2 = build(RoundRobinPolicy.CONTRAST_V2, "v2")
        val asV1 = MurmurRoundRobinInstrumentBank.Spec(steady)
        val asV2 = MurmurRoundRobinInstrumentBank.Spec(steady, policy = RoundRobinPolicy.CONTRAST_V2)
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinInstrumentBank.validate(NAME, v2, asV1) }
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinInstrumentBank.validate(NAME, v1, asV2) }

        // Relabel a V2 manifest as version 1: its cells still name V2, so it is refused.
        val file = File(v2, MurmurRoundRobinInstrumentBank.MANIFEST)
        val relabelled = LinkedHashMap(manifest(v2)).apply { remove("roundRobinPolicy"); put("version", JsonValue.Num(1.0)) }
        file.writeText(Json.write(JsonValue.Obj(relabelled)))
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinInstrumentBank.validate(NAME, v2) }

        // A version-2 manifest may not name the breath policy: version 1 is its only spelling.
        val v1File = File(v1, MurmurRoundRobinInstrumentBank.MANIFEST)
        val named = LinkedHashMap(manifest(v1)).apply {
            put("version", JsonValue.Num(2.0)); put("roundRobinPolicy", JsonValue.Str("NATIVE_BREATH_CONTACT_V1"))
        }
        v1File.writeText(Json.write(JsonValue.Obj(named)))
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinInstrumentBank.validate(NAME, v1) }
    }

    @Test fun `a V2 cell is refused for held sources and does not share the V1 cache key`() {
        val held = MurmurKeptSources.byId(MurmurKeptSources.HELD_WIRE).factoryPatch()
        assertFailsWith<IllegalArgumentException> {
            MurmurRoundRobinInstrumentSource.cellRecipe(held, held.midi, 1, 1, policy = RoundRobinPolicy.CONTRAST_V2)
        }
        val v1 = MurmurRoundRobinInstrumentSource.cellRecipe(steady, 36, 1, 2)
        val v2 = MurmurRoundRobinInstrumentSource.cellRecipe(steady, 36, 1, 2, policy = RoundRobinPolicy.CONTRAST_V2)
        assertTrue(v1.toJsonText() != v2.toJsonText())
        assertEquals(v2, MurmurRoundRobinInstrumentSource.CellRecipe.fromJsonText(v2.toJsonText()))
        assertEquals(v1, MurmurRoundRobinInstrumentSource.CellRecipe.fromJsonText(v1.toJsonText()))
    }

    /**
     * The whole path with real native cells: render, save, admit, reload from PCM24 and play.
     * Take 0 must be V1's take 0 exactly; the four takes must be distinct strikes in spectrum,
     * not only in phase or level; the player must deal them in order and start again at take 0.
     */
    @Test fun `the saved V2 C2 bank plays four distinct strikes in order from its saved files`() {
        val work = directory("native")
        val saved = MurmurRoundRobinPlayback.savedBank(RoundRobinPolicy.CONTRAST_V2, work, NAME)
        val shelf = File(work, "shelf")
        val strikes = MurmurRoundRobinPlayback.isolatedStrikes(saved, shelf, 5)
        assertEquals(listOf(0, 1, 2, 3, 0), strikes.map { it.take })
        assertTrue(strikes.all { s -> s.samples.all { it.isFinite() } && s.samples.any { it != 0f } })
        assertTrue(strikes[0].samples.contentEquals(strikes[4].samples), "the cycle returns to the identical take 0")

        val v1Take0 = MurmurRoundRobinInstrumentSource.renderCell(
            MurmurRoundRobinInstrumentSource.cellRecipe(MurmurRoundRobinPlayback.source(), 36, 1, 0))
        val packet = MurmurRoundRobinInstrumentBank.validate(NAME, File(File(File(shelf, MurmurRoundRobinShelf.DIRECTORY), NAME), "bank"))
        val row = packet.takes[0].rows.single { it.cell == MurmurRoundRobinTakePackage.Cell(36, 1, 0) }
        assertEquals(v1Take0.finishedFloatSha256, row.finishedFloatSha256, "V2 take 0 is the accepted V1 take 0, sample for sample")

        val four = strikes.take(4).map { it.samples }
        val residual = MurmurRoundRobinPlayback.pairwiseResiduals(four)
        val spectral = MurmurRoundRobinPlayback.pairwiseSpectral(four)
        println("MURMUR V2 saved-player C2 residuals=$residual spectral=$spectral")
        assertTrue(residual.all { it > MIN_RESIDUAL }, "every pair of takes differs after gain is removed: $residual")
        assertTrue(spectral.all { it > MIN_SPECTRAL }, "every pair of takes differs in spectrum, not only phase: $spectral")
    }

    private companion object {
        const val NAME = "MURMUR RR TEST"
        // Regression floors set from the first measured run, recorded in docs/MURMUR_RR_CONTRAST_V2.md.
        const val MIN_RESIDUAL = .025
        const val MIN_SPECTRAL = .01
    }
}
