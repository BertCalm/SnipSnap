package com.snipsnap.synth

import com.snipsnap.json.Json
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MeridianSixBankSourceTest {
    @Test fun `frozen thirteen anchors match the original and kept finite source receipts`() {
        fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val fixture = Json.parse(resource("meridian-six-bank/finite-sources-v2.json").toString(Charsets.UTF_8)).obj()
        val originalBytes = resource("meridian-gestures/accepted-models-1-3.json")
        val keptBytes = resource("meridian-gestures/kept-model-4-source.json")
        assertEquals(fixture.getValue("historicalFixtureSha256").str(), hash(originalBytes))
        assertEquals(fixture.getValue("keptFixtureSha256").str(), hash(keptBytes))
        val original = Json.parse(originalBytes.toString(Charsets.UTF_8)).obj().getValue("recipes").arr().associate {
            it.obj().getValue("name").str() to it.obj().getValue("json").str()
        }
        val kept = Json.parse(keptBytes.toString(Charsets.UTF_8)).obj().getValue("clips").arr()
            .filter { it.obj().getValue("playback").str() == "FINITE" }.associate {
                it.obj().getValue("preset").str() to it.obj().getValue("canonicalPatchJson").str()
            }
        val expected = fixture.getValue("factoryAnchors").arr()
        assertEquals(13, expected.size)
        assertEquals(expected.map { it.obj().getValue("name").str() }, MeridianSixKits.factoriesV2().map { it.name })
        for ((index, value) in expected.withIndex()) {
            val row = value.obj(); val name = row.getValue("name").str(); val text = row.getValue("canonicalPatchJson").str()
            assertEquals((original + kept).getValue(name), text)
            assertEquals(row.getValue("sha256").str(), hash(text.toByteArray()))
            val patch = MeridianSixKits.factoriesV2()[index]
            assertEquals(text, patch.toJsonText())
            assertFalse(Meridian.isLoop(patch.macros, patch.model))
        }
    }
}
