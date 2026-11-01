package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtisRangeStudyTest {
    private val roots = mapOf("low" to 48, "middle" to 60, "high" to 72)

    @Test
    fun `matrix covers every requested family register and gesture exactly once`() {
        val cases = OtisRangeStudy.cases()
        val expected = buildSet {
            for (voice in OtisPrototypeVoice.entries) for (register in roots.keys) {
                for (group in listOf("Soft note", "Hard note", "Phrase")) add(Triple(voice.name, register, group))
                if (voice in listOf(OtisPrototypeVoice.ORGAN, OtisPrototypeVoice.SQUEEZE))
                    add(Triple(voice.name, register, "Chord"))
            }
        }
        assertEquals(42, cases.size, "musical range study changed its listening budget")
        assertEquals(42, cases.map { it.id }.distinct().size, "range case IDs collide")
        assertEquals(expected, cases.map { Triple(it.family, it.register, it.group) }.toSet())
        assertTrue(cases.all { it.family == it.request.voice.name }, "case family mislabels its source")
        assertTrue(cases.filter { it.family == "HARP" }.all { "Blues harmonica" in it.title },
            "harmonica cases lost their instrument name")
    }

    @Test
    fun `soft and hard notes keep pitch gate and onset comparable across all sources`() {
        val cases = OtisRangeStudy.cases()
        for ((register, root) in roots) for ((group, velocity) in listOf("Soft note" to .35f, "Hard note" to .90f)) {
            val probes = cases.filter { it.register == register && it.group == group }
            assertEquals(4, probes.size)
            val expected = listOf(OtisPrototypeNote(midi = root, startSeconds = 0.0, gateSeconds = 1.5, velocity = velocity))
            for (case in probes) assertEquals(expected, case.request.notes, "${case.id} changed a single-note probe")
        }
    }

    @Test
    fun `phrases transpose the same C minor gesture without overlapping gates`() {
        for ((register, root) in roots) {
            val phrases = OtisRangeStudy.cases().filter { it.register == register && it.group == "Phrase" }
            assertEquals(4, phrases.size)
            assertTrue(phrases.all { it.request.notes == phrases.first().request.notes }, "sources do not share a phrase at $register")
            for (case in phrases) {
                val notes = case.request.notes
                assertEquals(listOf(root, root + 3, root + 7, root + 5), notes.map { it.midi }, "${case.id} changed the melody")
                assertEquals(4, notes.size)
                for ((index, note) in notes.withIndex()) {
                    assertEquals(listOf(0.0, .52, 1.04, 1.56)[index], note.startSeconds, 1e-12)
                    assertEquals(.40, note.gateSeconds)
                    assertEquals(.8f, note.velocity)
                }
                assertTrue(notes.zipWithNext().all { (a, b) -> a.startSeconds + a.gateSeconds < b.startSeconds },
                    "${case.id} obscures rearticulation by overlapping phrase notes")
            }
        }
    }

    @Test
    fun `chords share a complete simultaneous Cm7 gate only in organ and squeeze`() {
        val chords = OtisRangeStudy.cases().filter { it.group == "Chord" }
        assertEquals(6, chords.size)
        assertEquals(setOf("ORGAN", "SQUEEZE"), chords.map { it.family }.toSet())
        for ((register, root) in roots) {
            val pair = chords.filter { it.register == register }
            assertEquals(2, pair.size)
            assertEquals(pair[0].request.notes, pair[1].request.notes, "chord sources do not share the same voicing")
            for (case in pair) {
                val notes = case.request.notes
                assertEquals(listOf(root, root + 3, root + 7, root + 10), notes.map { it.midi })
                assertTrue(notes.all { it.startSeconds == 0.0 && it.gateSeconds == 1.5 && it.velocity == .68f },
                    "${case.id} does not hold the entire Cm7 voicing together")
            }
        }
    }

    @Test
    fun `range probes change notes alone while leaving the current model source settings and seed intact`() {
        val cases = OtisRangeStudy.cases()
        assertEquals(cases, OtisRangeStudy.cases(), "the study changes between enumerations")
        for (case in cases) {
            val defaults = OtisPrototypeRequest(case.request.voice, model = 3)
            assertEquals(3, case.request.model, "${case.id} changed the frozen musical study's model")
            assertEquals(defaults.model, case.request.model)
            assertEquals(defaults.seed, case.request.seed, "${case.id} changed its source seed")
            assertEquals(defaults.organ, case.request.organ, "${case.id} retuned the organ source")
            assertEquals(defaults.air, case.request.air, "${case.id} retuned the air source")
            assertEquals(false, case.request.organ.rotary, "${case.id} added an organ effect")
            assertTrue(case.request.notes.all { it.midi in 36..84 }, "${case.id} exceeds the supported note range")
            assertTrue("MIDI" in case.description && case.request.notes.all { it.midi.toString() in case.description },
                "${case.id} does not describe its actual MIDI notes")
        }
    }
}
