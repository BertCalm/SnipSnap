package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.*
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class TrefoilHeldIntegrationTest {
    private fun patch()=TrefoilPatch("WEAVE HELD",TrefoilVoice.WEAVE,
        TrefoilHeld.defaults(TrefoilVoice.WEAVE),velocity=.78f,seed=43012026L,playback=TrefoilPlayback.HELD)

    @Test fun `native three-root export retains absolute loop frames release and complete recipe`() {
        val directory=createTempDirectory("trefoil-held-export").toFile()
        try {
            val p=patch().copy(releaseSeconds=.43f);val spec=TrefoilPadMaker.Spec(p)
            val midis=TrefoilPadMaker.zoneMidis(spec);assertEquals(listOf(48,60,72),midis)
            val notes=midis.map { TrefoilPadMaker.renderZone(spec,it) }
            val program=TrefoilPadMaker.export("Trefoil Hold",spec,notes,directory)
            val sidecar=InstrumentStore.read(InstrumentStore.sidecar(directory,"Trefoil Hold"))
            assertEquals(3,program.keygroups.size);assertEquals(.43f,sidecar.release)
            assertEquals(p,TrefoilPatch.fromJsonText(TrefoilPadMaker.recipeFile(directory,"Trefoil Hold").readText()))
            for((i,zone) in sidecar.zones.withIndex()) {
                assertEquals(midis[i],zone.rootNote)
                assertEquals(notes[i].loopStartFrame,zone.loopStartFrame)
                val file=File(directory,zone.sample)
                assertEquals(SmplChunk(midis[i],SmplChunk.Loop(zone.loopStartFrame,zone.frameCount)),WavReader.readSmplStrict(file))
                assertEquals(notes[i].snip.frameCount,WavReader.read(file).frameCount)
            }
            assertTrue(directory.walkTopDown().any { it.extension=="xpm" })
        } finally { directory.deleteRecursively() }
    }
    @Test fun `one shot delivery fades only the ending and can be replayed`() {
        val p=patch();val native=TrefoilHeld.render(p).snip;val recipe=TrefoilPadMaker.deliveryRecipe(p)
        val restored=PadRecipe.fromJsonText(recipe.toJsonText());assertEquals(p,restored.patch)
        val delivered=restored.render();val before=native.frameCount-native.sampleRate/50
        assertContentEquals(native.samples.copyOf(before),delivered.samples.copyOf(before))
        assertEquals(0f,delivered.samples.last());assertTrue(native.samples.takeLast(100).any { it!=0f })
        assertContentEquals(native.samples,TrefoilHeld.render(p).snip.samples)
        assertEquals(p.midi.toFloat(),Spread.detect(delivered,p))
    }
    @Test fun `saved held patch keeps source fields through edits and shelf replay`() {
        val shelf=createTempDirectory("trefoil-held-shelf").toFile()
        try {
            val p=patch().copy(seed=Long.MIN_VALUE,midi=43,velocity=.31f,releaseSeconds=1f)
            val edited=assertIs<TrefoilPatch>(Patches.edited(p,"MINE",p.macros+("THREAD" to .6f)))
            UserPresets.save(shelf,edited,43L)
            assertEquals(edited,UserPresets.read(shelf).single().patch)
            assertEquals(p.seed,edited.seed);assertEquals(p.playback,edited.playback);assertEquals(p.releaseSeconds,edited.releaseSeconds)
            assertContentEquals(edited.render().samples,UserPresets.read(shelf).single().patch.render().samples)
        } finally { shelf.deleteRecursively() }
    }
    @Test fun `native spans retain selected roots at both pitch limits`() {
        for(root in listOf(36,37,60,83,84)) {
            val midis=TrefoilPadMaker.zoneMidis(TrefoilPadMaker.Spec(patch().copy(midi=root)))
            assertTrue(root in midis);assertTrue(midis.all { it in 36..84 });assertEquals(3,midis.size)
        }
    }
}
