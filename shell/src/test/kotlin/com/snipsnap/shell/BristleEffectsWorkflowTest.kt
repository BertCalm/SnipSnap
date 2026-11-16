package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.*
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.*

class BristleEffectsWorkflowTest {
    @Test fun `effects survive drafts presets promotion pad recipes root edits and chord planning`() {
        val root=createTempDirectory("bristle-effects-save").toFile()
        try {
            val patch=BristlePresets.forVoice(Bristle.Route.ROUND).single().copy(name="MY BASS",midi=32,
                velocity=.65f,take=1,effects=BristleEffects.Settings(.63f,.71f))
            val settings=mutableMapOf<String,String>()
            SynthDrafts.write(settings,listOf(patch))
            assertEquals(patch,SynthDrafts.read(settings).single())
            UserPresets.save(root,patch,1234)
            val saved=UserPresets.forVoice(UserPresets.read(root),patch.engine,patch.voiceName).single().patch
            assertEquals(patch,saved)
            val call=KotlinLiteral.parse(UserPresets.rosterLine(saved).removeSuffix(",")) as KotlinLiteral.Call
            val promoted=BristlePatch.fromJsonText((call.positional.single() as KotlinLiteral.Str).value)
            assertEquals(patch,promoted)
            assertContentEquals(patch.render().samples,PadRecipe.fromJsonText(PadRecipe(saved).toJsonText()).render().samples)
            assertEquals(patch.effects,(assertNotNull(SynthRoot.of(saved)).at(30) as BristlePatch).effects)
            assertTrue(BristleChordPlan.fromNotes(patch,listOf(24,27,31)).patches().all { it.effects==patch.effects })
            assertContentEquals(patch.render().samples,BristlePadMaker.preview(BristlePadMaker.Spec(patch)).samples)
        } finally { root.deleteRecursively() }
    }

    @Test fun `effected finite and held banks export actual wet audio and retain both takes per touch`() {
        val root=createTempDirectory("bristle-effects-bank").toFile()
        try {
            for (voice in Bristle.Route.entries) for (held in listOf(false,true)) {
                val patch=BristlePresets.forVoice(voice).single().copy(effects=BristleEffects.Settings(.65f,.7f))
                val spec=BristleKeygroupMaker.Spec(patch,36,36,held=held,takes=2)
                val notes=BristleKeygroupMaker.render(spec)
                assertEquals(6,notes.size)
                assertTrue(notes.values.all { it.recipe.effects==patch.effects })
                val name="FX $voice $held"
                val folder=File(root,name)
                val assembly=BristleKeygroupMaker.writeNewPackage(name,spec,notes,folder)
                val restored=BristleKeygroupMaker.validate(name,folder,spec)
                assertEquals(assembly.recipes,restored.recipes)
                val data=File(folder,Mpc3TrackWriter.trackDataDirName(name))
                val metadata=Json.parse(File(data,"bristle-source-recipes.json").readText()).obj()
                assertEquals(patch,BristlePatch.fromJsonValue(metadata.getValue("sourcePatch")))
                val instrument=InstrumentStore.read(InstrumentStore.sidecar(folder,name))
                val pcm=instrument.sampleFiles.associateWith { WavReader.read(File(folder,it)).samples }
                for ((file,x) in pcm) {
                    val expected=assembly.samples.getValue(File(file).nameWithoutExtension).samples
                    assertEquals(expected.size,x.size)
                    assertTrue(x.indices.maxOf { abs(x[it]-expected[it]) } < .000001f)
                }
                val cursor=KeyHit.CycleCursor()
                repeat(4) { repeatIndex -> for ((touch,velocity) in BristleKeygroupMaker.TOUCHES.withIndex()) {
                    val hit=KeyHit.resolveAll(instrument,36,velocity,cursor) { pcm[it]?.size?.toLong() }.single().hit
                    assertEquals(instrument.zones.single().layers[touch*2+repeatIndex%2].sample,hit.sampleFile)
                } }
                val player=InstrumentEngine(InstrumentEngine.Loaded(instrument,44100,pcm),44100)
                val attacks=(0..3).map {
                    assertTrue(player.noteOn(36,1f))
                    val attack=FloatArray(2048);player.render(attack,1024)
                    if(held)player.render(FloatArray(44100*4),44100*2)
                    player.noteOff(36);player.render(FloatArray(44100),22050)
                    assertTrue(player.activeNotes.isEmpty())
                    attack
                }
                assertContentEquals(attacks[0],attacks[2]);assertContentEquals(attacks[1],attacks[3])
                assertFalse(attacks[0].contentEquals(attacks[1]))
                val dry=BristleKeygroupMaker.prepareNote(patch.copy(effects=BristleEffects.Settings()),1f,held).snip.samples
                val wet=notes.getValue(BristleKeygroupMaker.Cell(36,2,0)).snip.samples
                assertTrue(wet.indices.maxOf { abs(wet[it]-dry[it]) } > .001f)
            }
        } finally { root.deleteRecursively() }
    }
}
