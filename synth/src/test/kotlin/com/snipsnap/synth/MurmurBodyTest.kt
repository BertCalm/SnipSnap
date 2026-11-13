package com.snipsnap.synth

import com.snipsnap.json.*
import kotlin.test.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class MurmurBodyTest {
    private fun pcmHash(x: FloatArray): String {
        val bytes=ByteBuffer.allocate(x.size*4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach(bytes::putFloat)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }
    @Test fun `kept finite recipes retain exact raw listening identity`() {
        val fixtures=listOf(
            MurmurSelectedPlucks.shortAnswerHollowWire() to "6ccd1ecab8e2124f0aca17e9b6769a6cbda8edea060bf51f12eaae6b64a8b8e5",
            MurmurSelectedPlucks.shortAnswerRootSupported() to "3e1eccc0f7e1ee9aad90852b1eae9b730deedd7f5e8baaf3c2044fde216468b6")
        for((p,hash) in fixtures) {
            assertEquals(hash,pcmHash(Murmur.renderRaw(p.voice,p.macros,p.midi,p.velocity,p.config)))
            assertContentEquals(p.copy(velocity=.35f).render().samples,Velocity.atVelocity(p,.35f,null).samples)
        }
    }
    private fun configJson(p: MurmurPatch, edits: Map<String, JsonValue?>): JsonValue.Obj {
        val root=p.toJsonValue().entries.toMutableMap()
        val config=root.getValue("config").obj().toMutableMap()
        edits.forEach { (key,value) -> if(value==null) config.remove(key) else config[key]=value }
        root["config"]=JsonValue.Obj(config)
        return JsonValue.Obj(root)
    }
    @Test fun `model four requires an explicit known body and refuses other voices`() {
        val p=MurmurSelectedPlucks.shortAnswerHollowWire()
        for(value in listOf(null,JsonValue.Null,JsonValue.Str("LEGACY"),JsonValue.Str("FUTURE_BODY")))
            assertFails { MurmurPatch.fromJsonValue(configJson(p,mapOf("body" to value))) }
        for(model in 1..3) assertFails { p.config.copy(model=model) }
        for(v in MurmurVoice.entries.filter { it!=MurmurVoice.PLUCK }) {
            assertFails { p.copy(voice=v) }
            assertFails { Murmur.probe(v,p.macros,config=p.config) }
        }
        assertFails { Murmur.configurationFor(MurmurVoice.PLUCK,4) }
    }
    @Test fun `old missing null and explicit legacy bodies retain recipe and output`() {
        for(model in 1..3) {
            val p=MurmurPatch("old",MurmurVoice.PLUCK,Murmur.defaults(MurmurVoice.PLUCK),config=Murmur.configurationFor(MurmurVoice.PLUCK,model))
            for(value in listOf(null,JsonValue.Null,JsonValue.Str("LEGACY"))) {
                val decoded=MurmurPatch.fromJsonValue(configJson(p,mapOf("body" to value))) as MurmurPatch
                assertEquals(p,decoded)
                assertEquals(p.toJsonText(),decoded.toJsonText())
            }
        }
        assertEquals(3,Murmur.configurationFor(MurmurVoice.PLUCK).model)
        assertEquals(MurmurBody.LEGACY,Murmur.configurationFor(MurmurVoice.PLUCK).body)
    }
    @Test fun `missing and null model remain model one and missing config retains legacy default`() {
        val p=MurmurPatch("old",MurmurVoice.PLUCK,Murmur.defaults(MurmurVoice.PLUCK),config=Murmur.configurationFor(MurmurVoice.PLUCK,1))
        for(value in listOf(null,JsonValue.Null)) {
            assertEquals(p,MurmurPatch.fromJsonValue(configJson(p,mapOf("model" to value))))
            val root=p.toJsonValue().entries.toMutableMap()
            if(value==null) root.remove("config") else root["config"]=value
            assertEquals(p,MurmurPatch.fromJsonValue(JsonValue.Obj(root)))
        }
    }
    @Test fun `opt in recipes survive dispatcher and common edit operations`() {
        for(p in listOf(MurmurSelectedPlucks.openStringHollowWire(),MurmurSelectedPlucks.shortAnswerHollowWire(),MurmurSelectedPlucks.shortAnswerRootSupported())) {
            assertEquals(p,Patches.fromJsonText(p.toJsonText()))
            assertEquals(p.config,(Patches.edited(p,"renamed",p.macros+("EDGE" to .3f)) as MurmurPatch).config)
            assertEquals(p.config,p.copy(midi=minOf(72,Murmur.rootRange(p.config).last),velocity=.35f).config)
        }
        // Independent saved fixtures keep the original 36 and selected six unchanged.
        // Only the three explicitly kept aliases opt into the new model-4 bodies.
        fun resource(name: String) = checkNotNull(javaClass.getResource("/murmur-kept/$name")).readText()
        fun recipeHash(patch: MurmurPatch) = MessageDigest.getInstance("SHA-256")
            .digest(patch.toJsonText().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val original = MurmurPresets.original()
        val selected = MurmurPresets.selected()
        assertEquals(36, original.size)
        assertEquals(6, selected.size)
        val legacy = original + selected
        val legacyFixtures = Json.parse(resource("legacy42-hashes.json")).obj()
        assertEquals(42, legacyFixtures.size)
        assertEquals(legacyFixtures.keys.sortedBy { it.substringBefore('-').toInt() },
            legacy.mapIndexed { index, patch -> "$index-${patch.name}" })
        for ((index, patch) in legacy.withIndex()) {
            assertEquals(MurmurBody.LEGACY, patch.config.body, patch.name)
            assertEquals(if (index < 36) 2 else 3, patch.config.model, patch.name)
            assertEquals(legacyFixtures.getValue("$index-${patch.name}").obj().getValue("recipe_sha256").str(),
                recipeHash(patch), "${patch.name} changed its saved legacy recipe")
        }
        val expectedKept = listOf("finite-hollow-wire", "held-hollow-wire", "approved-c2")
            .map { MurmurPatch.fromJsonText(resource("$it-alias.json")) }
        val kept = MurmurPresets.kept()
        assertEquals(listOf("HOLLOW WIRE NOTE", "HOLLOW WIRE HOLD", "STEADY ROOT C2"), kept.map { it.name })
        assertEquals(listOf(MurmurBody.HOLLOW_WIRE_V1, MurmurBody.HOLLOW_WIRE_V1, MurmurBody.STEADY_ROOT_V1),
            kept.map { it.config.body })
        for ((expected, patch) in expectedKept.zip(kept)) {
            assertEquals(MurmurVoice.PLUCK, patch.voice)
            assertEquals(4, patch.config.model)
            assertEquals(expected, patch, "${patch.name} changed its complete kept source recipe")
        }
        val all = MurmurPresets.all()
        assertEquals(45, all.size)
        assertEquals(45, all.map { it.name }.toSet().size)
        assertEquals(legacy, all.take(42))
        assertEquals(expectedKept, all.drop(42))
    }
    @Test fun `held wire remains deterministic with an independently simulated predecessor`() {
        val p=MurmurSelectedPlucks.openStringHollowWire()
        val a=Murmur.probe(p.voice,p.macros,p.midi,p.velocity,p.config)
        val b=Murmur.probe(p.voice,p.macros,p.midi,p.velocity,p.config)
        assertEquals("29912f67bf0ed9ca1aaa17d404a73040160ad4aefc786dcb9fbbbd910d887974",pcmHash(a.samples))
        assertEquals("465dc002628b7be13aa676d2f2aecdc4c1f9d8fb7639906475293cf66687416a",pcmHash(a.previousCycle))
        assertContentEquals(a.samples,b.samples)
        assertContentEquals(a.previousCycle,b.previousCycle)
        assertTrue(a.previousCycle !== a.samples)
        assertTrue(Keys.seamError(a.previousCycle+a.samples,a.samples.size)<Keys.MAX_SEAM_ERROR)
    }
    @Test fun `supported low body refuses the first failed root without changing other ranges`() {
        val p=MurmurSelectedPlucks.shortAnswerRootSupported()
        assertEquals(36..54,Murmur.rootRange(p.config))
        assertEquals(54,p.copy(midi=54).midi)
        for(note in listOf(35,55,56,59,60,96)) {
            assertFailsWith<IllegalArgumentException> { p.copy(midi=note) }
            assertFailsWith<IllegalArgumentException> { Murmur.probe(p.voice,p.macros,note,p.velocity,p.config) }
            val root=p.toJsonValue().entries+("midi" to JsonValue.Num(note.toDouble()))
            assertFails { Patches.fromJsonValue(JsonValue.Obj(root)) }
        }
        for(c in listOf(MurmurSelectedPlucks.shortAnswerHollowWire().config,Murmur.configurationFor(MurmurVoice.PLUCK)))
            assertEquals(36..96,Murmur.rootRange(c))
    }
    @Test fun `zero velocity is silence for both new bodies and both gestures`() {
        for(p in listOf(MurmurSelectedPlucks.shortAnswerHollowWire(),MurmurSelectedPlucks.shortAnswerRootSupported()))
            for(hold in listOf(0f,1f)) assertTrue(p.copy(velocity=0f,macros=p.macros+("HOLD" to hold)).render().samples.all { it==0f })
    }
}
