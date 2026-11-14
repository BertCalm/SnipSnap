package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.synth.*
import java.nio.file.Files
import kotlin.test.*

class OsteriaStrumPersistenceTest {
    private fun patch() = OsteriaStrum.fromChord(OsteriaPresets.forVoice(OsteriaVoice.PLUCK).first(),
        ChordStabs.Stab("Cm7",60,listOf(-5,0,3,10)), .17, OsteriaStrum.Direction.UP)

    @Test fun saveRenamePromotionDraftAndMacroEditsRetainCompleteStrum() {
        val root=Files.createTempDirectory("osteria-saved-strum").toFile()
        try {
            val source=patch().copy(name="MY STRUM",velocity=.37f)
            UserPresets.save(root,source,1234L)
            val loaded=UserPresets.read(root).single().patch as OsteriaPatch
            assertEquals(source,loaded)
            val edited=Patches.edited(loaded,"MY STRUM HOLD",loaded.macros+("HOLD" to 1f)) as OsteriaPatch
            assertEquals(source.strum,edited.strum)
            assertEquals(source.config,edited.config)
            UserPresets.save(root,edited,2345L)
            assertEquals(edited,UserPresets.read(root).single { it.name==edited.name }.patch)
            val line=UserPresets.rosterLine(edited)
            val literal=line.removePrefix("OsteriaPatch.fromJsonText(").removeSuffix("),")
            assertEquals(edited,OsteriaPatch.fromJsonText(Json.parse(literal).str()))
            val settings=mutableMapOf("unrelated" to "keep")
            SynthDrafts.write(settings,listOf(edited))
            assertEquals(listOf(edited),SynthDrafts.read(settings))
            assertEquals("keep",settings["unrelated"])
        } finally { root.deleteRecursively() }
    }

    @Test fun rootStepperTransposesEntireChordAndClampsOnlyToItsLegalRootRange() {
        val patch=patch()
        val root=requireNotNull(SynthRoot.of(patch))
        assertEquals(41..74,root.range)
        assertFalse(root.at(84).let { (it as OsteriaPatch).strum!!.notes(it.midi).any { note -> note>84 } })
        for(midi in listOf(0,41,60,74,127)) {
            val moved=root.at(midi) as OsteriaPatch
            assertEquals(midi.coerceIn(root.range),moved.midi)
            assertEquals(patch.strum,moved.strum)
            assertEquals(patch.macros,moved.macros)
            assertEquals(patch.config,moved.config)
            assertEquals(patch.velocity,moved.velocity)
            assertEquals(patch.strum!!.intervals,moved.strum!!.notes(moved.midi).map { it-moved.midi })
        }
        val wide=patch.copy(midi=36,strum=OsteriaStrum.Articulation(listOf(0,48)))
        assertEquals(36..36,SynthRoot.of(wide)!!.range)
        assertEquals(36,(SynthRoot.of(wide)!!.at(127) as OsteriaPatch).midi)
        assertEquals(36..84,SynthRoot.of(patch.copy(strum=null))!!.range)
    }

    @Test fun chordPatchesCannotSilentlyBecomeSingleNoteKeygroups() {
        val chord=patch()
        assertFalse(OsteriaPadMaker.supports(chord))
        assertFailsWith<IllegalArgumentException> { OsteriaPadMaker.Spec(chord) }
        assertFailsWith<IllegalArgumentException> { OsteriaKeygroupBank.recipes(chord) }
        val single=chord.copy(strum=null)
        assertTrue(OsteriaPadMaker.supports(single))
        assertEquals(39,OsteriaKeygroupBank.recipes(single).size)
        assertEquals(chord.config,single.config)
        assertEquals(chord.macros,single.macros)
    }
}
