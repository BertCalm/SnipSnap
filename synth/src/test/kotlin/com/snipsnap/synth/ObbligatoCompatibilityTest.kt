package com.snipsnap.synth

import com.snipsnap.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Golden audio captured before optimization, independent of subsequent renderer changes. */
class ObbligatoCompatibilityTest {
    @Test
    fun `models one through five retain original float audio and PCM24 exports`() {
        val stream = assertNotNull(javaClass.getResourceAsStream(ObbligatoCompatibilityFixtureGenerator.RESOURCE),
            "Original model1–5 fixture resource must be captured before the DSP is changed")
        val fixture = stream.bufferedReader().use { Json.parse(it.readText()).obj() }
        assertEquals(1, fixture.getValue("schemaVersion").int())
        assertEquals(ObbligatoCompatibilityFixtureGenerator.ORIGINAL, fixture.getValue("originalCommit").str())
        val blobs = fixture.getValue("sourceGitBlobs").obj()
        assertTrue(blobs.size >= 7 && blobs.values.all { it.str().matches(Regex("[a-f0-9]{40}")) },
            "Fixture must retain the original DSP and export source provenance")
        val cases = fixture.getValue("cases").arr()
        assertEquals(30, cases.size)
        assertEquals((1..5).toSet(), cases.map { it.obj().getValue("recipe").obj().getValue("model").int() }.toSet())
        for (value in cases) {
            val case = value.obj()
            val patch = ObbligatoPatch.fromJsonValue(case.getValue("recipe"))
            val raw = Obbligato.probe(patch.macros, patch.midi, patch.velocity, patch.seed, model = patch.model)
            assertEquals(case.getValue("sampleRate").int(), raw.snip.sampleRate, patch.name)
            assertEquals(case.getValue("channels").int(), raw.snip.channels, patch.name)
            assertEquals(case.getValue("frames").int(), raw.snip.frameCount, patch.name)
            assertEquals(case.getValue("rawFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(raw.snip.samples), "${patch.name}: prelevel sum changed")
            assertEquals(case.getValue("airFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(raw.air), "${patch.name}: air source changed")
            assertEquals(case.getValue("reedFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(raw.reed), "${patch.name}: reed source changed")
            assertEquals(case.getValue("finalPcm24WavSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.pcmHash(patch), "${patch.name}: final MPC export changed")
        }
    }
}
