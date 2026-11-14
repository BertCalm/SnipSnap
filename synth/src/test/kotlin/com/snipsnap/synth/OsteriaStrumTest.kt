package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.test.*

class OsteriaStrumTest {
    private fun source(): OsteriaPatch {
        val text = requireNotNull(javaClass.getResourceAsStream("/osteria/approved-strum-down.json"))
            .bufferedReader().use { it.readText() }
        return OsteriaPatch.fromJsonValue(Json.parse(text).obj().getValue("patch")) as OsteriaPatch
    }
    private fun strum() = OsteriaStrum.fromChord(source(), ChordStabs.Stab("Cm",48,listOf(0,3,7)))
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun changed(patch: OsteriaPatch, block: (MutableMap<String,JsonValue>) -> Unit): JsonValue =
        JsonValue.Obj(patch.toJsonValue().entries.toMutableMap().also(block))

    @Test fun legacyMissingAndNullKeepCanonicalV1AndExactSourceAudio() {
        val golden = requireNotNull(javaClass.getResourceAsStream("/osteria/legacy-factory-v1.json"))
            .bufferedReader().use { Json.parse(it.readText()).arr() }
        assertEquals(golden.size, OsteriaPresets.all().size)
        for ((patch, original) in OsteriaPresets.all().zip(golden)) {
            assertEquals(Json.write(original), patch.toJsonText(), "Pinned old factory JSON must remain byte-identical")
            assertNull(patch.strum)
            assertEquals(1, patch.toJsonValue().entries.getValue("version").int())
            assertFalse("strum" in patch.toJsonValue().entries)
            assertEquals(patch.toJsonText(), Patches.fromJsonValue(changed(patch) { it["strum"] = JsonValue.Null }).toJsonText())
        }
        val finite = source()
        assertContentEquals(Osteria.render(finite.voice,finite.macros,finite.midi,finite.velocity,finite.config).samples,
            OsteriaPatch.fromJsonText(finite.toJsonText()).render().samples)
        val legacy = OsteriaPatch("Historic",OsteriaVoice.QUARTET,Osteria.defaults(OsteriaVoice.QUARTET),
            config=OsteriaConfig(model=1,seconds=5.0))
        assertContentEquals(Osteria.render(legacy.voice,legacy.macros,legacy.midi,legacy.velocity,legacy.config).samples,
            OsteriaPatch.fromJsonText(legacy.toJsonText()).render().samples)
    }

    @Test fun optInVersionGateRefusesSilentDowngradesAndUnknownState() {
        val patch = strum()
        assertEquals(2,patch.toJsonValue().entries.getValue("version").int())
        assertEquals(patch,Patches.fromJsonText(patch.toJsonText()))
        assertEquals(patch,PadRecipe.fromJsonText(PadRecipe(patch=patch).toJsonText()).patch)
        // Actual unchanged decoder used by the previous OSTERIA reader; its builder must never run.
        var built = false
        assertFailsWith<JsonException> {
            Patches.decode(patch.toJsonValue(),OsteriaPatch.ENGINE,{ OsteriaVoice.valueOf(it) }) { _,_,_ ->
                built=true;source()
            }
        }
        assertFalse(built)
        for (version in listOf(1,3)) assertFailsWith<JsonException> {
            Patches.fromJsonValue(changed(patch) { it["version"]=JsonValue.Num(version.toDouble()) })
        }
        for (remove in listOf(false,true)) assertFailsWith<JsonException> {
            Patches.fromJsonValue(changed(patch) { if(remove)it.remove("strum") else it["strum"]=JsonValue.Null })
        }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed(patch) { it["future"]=JsonValue.Bool(true) }) }
        for ((field,value) in listOf("version" to JsonValue.Num(2.0),"direction" to JsonValue.Str("SIDEWAYS"),
            "future" to JsonValue.Bool(true),"sweepSeconds" to JsonValue.Num(.25),
            "intervals" to JsonValue.Arr(listOf(JsonValue.Num(0.0),JsonValue.Num(0.0))))) {
            assertFailsWith<JsonException>(field) { Patches.fromJsonValue(changed(patch) {
                it["strum"]=JsonValue.Obj(it.getValue("strum").obj()+(field to value))
            }) }
        }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed(patch) {
            it["config"]=JsonValue.Obj(it.getValue("config").obj()+("future" to JsonValue.Num(1.0)))
        }) }
        assertFailsWith<IllegalArgumentException> { patch.copy(voice=OsteriaVoice.BOWED) }
        assertFailsWith<IllegalArgumentException> { patch.copy(midi=84) }
        assertFailsWith<IllegalArgumentException> { OsteriaStrum.Articulation(listOf(-30,0,30)) }
        assertFailsWith<IllegalArgumentException> { OsteriaStrum.Articulation(listOf(1,4,7)) }
        assertEquals(36..36,OsteriaStrum.Articulation(listOf(0,48)).rootRange)
    }

    @Test fun existingChordToolVoicingsKeepTheirRootAndOneStrikePerTone() {
        var checked=0
        for (key in 0..11) for (scale in listOf(Scale.MAJOR,Scale.MINOR)) {
            for (chord in ChordStabs.stabs(KeySpec(key,scale))) for (direction in OsteriaStrum.Direction.entries) {
                val patch=OsteriaStrum.fromChord(source(),chord,direction=direction)
                assertEquals(chord.rootMidi,patch.midi)
                assertEquals(chord.intervals,patch.strum!!.intervals)
                val events=OsteriaStrum.events(patch)
                assertEquals(chord.notes.toSet(),events.map { it.midi }.toSet())
                assertEquals(chord.notes.size,events.size)
                assertTrue(events.zipWithNext().all { (a,b) -> b.startFrame>a.startFrame })
                assertEquals(0,events.first().startFrame)
                assertEquals(3969,events.last().startFrame)
                assertEquals(patch,OsteriaPatch.fromJsonText(patch.toJsonText()))
                checked++
            }
        }
        assertEquals(768,checked)
    }

    @Test fun holdChangesRingLengthWithoutAddingOnsetsAndHasBoundedRealOutput() {
        for (hold in listOf(0f,.5f,1f)) {
            val patch=strum().copy(macros=strum().macros+("HOLD" to hold))
            val edited=Patches.edited(patch,"HOLD $hold",patch.macros) as OsteriaPatch
            val events=OsteriaStrum.events(edited)
            assertEquals(listOf(0,1985,3969),events.map { it.startFrame })
            val impulse=Snip(floatArrayOf(1f,1f,0f,0f),2,44100)
            val mix=OsteriaStrum.mix(edited,events.associate { it.midi to impulse })
            assertEquals(listOf(0,1985,3969),mix.samples.indices.filter { mix.samples[it]!=0f }.map { it/2 }.distinct())
            assertEquals(patch.strum,edited.strum)
            if (hold>0f) {
                val began=System.nanoTime()
                val audio=edited.render()
                println("OSTERIA STRUM HOLD=$hold render_ms=${(System.nanoTime()-began)/1e6} frames=${audio.frameCount}")
                assertEquals(OsteriaStrum.totalFrames(edited),audio.frameCount)
                assertTrue(audio.frameCount<=363384) // 8.24 seconds at 44.1 kHz.
                assertTrue(audio.samples.all { it.isFinite() } && audio.peak()<1f)
                if(hold==.5f) assertContentEquals(audio.samples,
                    PadRecipe.fromJsonText(PadRecipe(patch=edited).toJsonText()).render().samples)
            }
        }
        val longest=strum().copy(macros=strum().macros+("HOLD" to 1f),strum=strum().strum!!.copy(sweepSeconds=.24))
        assertEquals(363384,OsteriaStrum.totalFrames(longest))
        assertFailsWith<CancellationException> { OsteriaStrum.render(strum()) { true } }
    }

    @Test fun approvedHoldZeroStudyMatchesExactPublishedWavsAndSavedReplay() {
        val expected=mapOf(OsteriaStrum.Direction.DOWN to "b3927232f47f489d5fb29a0e8e38ef074ac11b7f7fdbb8abe018a391cda2cd58",
            OsteriaStrum.Direction.UP to "0a2c63cca16f2060b4d83624ec6d4afc7be646b79d223657d211e28c9884c896")
        val root=Files.createTempDirectory("osteria-approved-strum").toFile()
        try {
            for((direction,hash) in expected) {
                val patch=strum().copy(strum=strum().strum!!.copy(direction=direction))
                val began=System.nanoTime()
                val audio=patch.render();val wav=root.resolve("${direction.name}.wav")
                println("OSTERIA approved $direction render_ms=${(System.nanoTime()-began)/1e6}")
                WavWriter.write(wav,audio)
                assertEquals(hash,sha(wav.readBytes()),"Owner-approved study WAV must remain exact")
                assertEquals(OsteriaStrum.totalFrames(patch),audio.frameCount)
                if(direction==OsteriaStrum.Direction.DOWN) assertContentEquals(audio.samples,
                    OsteriaPatch.fromJsonText(patch.toJsonText()).render().samples)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun nativeVelocityPathRetainsTheSavedChordGesture() {
        val patch=strum()
        val soft=Velocity.atVelocity(patch,.35f)
        assertContentEquals(patch.copy(velocity=.35f).render().samples,soft.samples)
        assertEquals(OsteriaStrum.totalFrames(patch),soft.frameCount)
        assertEquals(2,soft.channels)
    }
}
