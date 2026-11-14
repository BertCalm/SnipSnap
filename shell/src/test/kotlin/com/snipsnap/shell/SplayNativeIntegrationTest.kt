package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Splay
import com.snipsnap.synth.SplayAcceptedBank
import com.snipsnap.synth.SplayPatch
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Source-state contracts only; native audio replay lives in SplayAcceptedBankTest. */
class SplayNativeIntegrationTest {
    @Test fun `root and spread trust the authored note while preserving complete saved performance`() {
        for (entry in SplayAcceptedBank.entries) {
            val patch = entry.patch.copy(velocity = .37f, seed = Int.MIN_VALUE)
            val root = assertNotNull(SynthRoot.of(patch))
            assertEquals(Splay.MIDI_MIN..Splay.MIDI_MAX, root.range)
            for (midi in listOf(36, 48, 60, 72, 84)) {
                val moved = root.at(midi) as SplayPatch
                assertEquals(patch.copy(midi = midi), moved)
                assertEquals(midi, assertNotNull(SynthRoot.of(moved)).midi)
                assertEquals(midi.toFloat(), Spread.detect(Snip(FloatArray(1), 1, 44_100), moved))
            }
            assertEquals(patch.copy(midi = 36), root.at(35))
            assertEquals(patch.copy(midi = 84), root.at(85))
        }
        assertEquals(listOf(EngineCategory.BELLS, EngineCategory.PADS),
            assertNotNull(EngineCatalog.entry(SplayPatch.ENGINE)).categories,
            "The finite picker entry is catalogued without inventing additional voices")
    }

    @Test fun `shelf recall and generated constructor preserve model signed seed note touch and exact controls`() {
        val shelf = Files.createTempDirectory("splay-presets-").toFile()
        try {
            for (model in listOf(1, 2)) {
                val patch = SplayAcceptedBank.entries[2].patch.copy(
                    name = "\"SEAMS\" \$TAKE", midi = 57, velocity = .37f,
                    seed = if (model == 1) Int.MIN_VALUE else Int.MAX_VALUE, model = model)
                UserPresets.save(shelf, patch, nowMillis = model.toLong())
                val back = UserPresets.read(shelf).single().patch
                assertEquals(patch, back)
                val call = KotlinLiteral.parse(UserPresets.rosterLine(back).removeSuffix(",")) as KotlinLiteral.Call
                assertEquals("fromJsonText", call.name)
                assertEquals("SplayPatch", (call.receiver as KotlinLiteral.Call).name)
                assertEquals(patch, SplayPatch.fromJsonText((call.positional.single() as KotlinLiteral.Str).value))
            }
        } finally {
            shelf.deleteRecursively()
        }
    }
}
