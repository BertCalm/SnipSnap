package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.*
import kotlin.test.*

class RimeCompleteTest {
    @Test fun `new voices cannot silently enter a historical model and accepted finite recipes stay exact`() {
        for (voice in RimeVoice.entries - Rime.LEGACY_VOICES.toSet()) for (model in 1..5) {
            assertFailsWith<IllegalArgumentException> { RimePatch("Unsupported old voice", voice, model = model) }
        }
        assertEquals(9, RimePresets.legacyEntries().size)
        for (entry in RimePresets.legacyEntries()) {
            assertEquals(4, entry.patch.model)
            assertContentEquals(entry.patch.render().samples, entry.patch.copy(model = 6).render().samples, entry.id)
        }
        assertEquals(6, RimePresets.all().count { Rime.isLoop(it.macros) })
        assertEquals(6, RimeKits.completePatches().map { it.voice }.toSet().size)
        assertEquals(16, RimeKits.completePatches().size)
        assertTrue(RimeKits.completePatches().all { it.model == 6 && !Rime.isLoop(it.macros) })
    }

    @Test fun `model five published held openings and eight cycles retain their PCM hashes`() {
        fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/rime-model5/$name")).bufferedReader().use { it.readText() }
        for ((id, expected) in Json.parse(resource("raw-wav-sha256.json")).obj()) {
            val patch = RimePatch.fromJsonText(resource("$id.json"))
            assertEquals(5, patch.model)
            val render = RimeHeld.render(patch)
            val head = render.note.snip.samples.copyOf(render.note.loopStartFrame.toInt())
            val samples = FloatArray(head.size + render.cycle.size * 8)
            head.copyInto(samples)
            repeat(8) { render.cycle.copyInto(samples, head.size + it * render.cycle.size) }
            Dsp.fadeTail(samples)
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, Snip(samples, 1, Dsp.RATE)) }.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(expected.str(), hash, "$id must retain the published model-5 attack and static sustain")
        }
    }

    @Test fun `instrument asymmetries are centered normalized deterministic contours extracted before projection`() {
        val reference = RimeContour.generate()
        for (variant in RimeContour.Variant.entries.filter { it != RimeContour.Variant.REFERENCE }) {
            val path = RimeContour.instrumentPath(variant)
            val again = RimeContour.instrumentPath(variant)
            assertContentEquals(path.x, again.x); assertContentEquals(path.y, again.y)
            assertTrue(abs(path.x.average()) < 1e-12 && abs(path.y.average()) < 1e-12)
            assertEquals(1.0, path.x.indices.maxOf { hypot(path.x[it], path.y[it]) }, 1e-12)
            val difference = sqrt(path.x.indices.sumOf { (path.x[it]-reference.x[it]).pow(2)+(path.y[it]-reference.y[it]).pow(2) }/path.x.size)
            assertTrue(difference > .02, "$variant must be an actual contour variant: $difference")
            val c = RimeContour.extract(path)
            assertEquals(0.0, c.single { it.n == 0 }.energy)
            assertTrue(c.any { it.n < 0 && it.energy > 1e-6 })
        }
    }

    @Test fun `new contour and body profiles give distinct spectra at identical performance and controls`() {
        val voices = listOf(RimeVoice.GLINT, RimeVoice.REED, RimeVoice.FILIGREE, RimeVoice.CRUST)
        val macros = mapOf("DETAIL" to .95f, "EDGE" to .7f, "ANGLE" to .1f, "BODY" to .6f, "UNFOLD" to 0f)
        val notes = voices.map { RimePatch("Profile $it", it, macros, 60, .8f, .7f).render().samples }
        for (a in voices.indices) for (b in a+1 until voices.size) {
            val distance = RimeControlMeasure.distance(notes[a], notes[b], .7)
            println("RIME profile ${voices[a]}/${voices[b]} spectral distance=$distance")
            assertTrue(distance > .08, "${voices[a]}/${voices[b]} should not be duplicated voicings: $distance")
        }
    }

    @Test fun `both held adaptations close independent cycles retain pitch and preserve complete recipes`() {
        for (voice in RimeVoice.entries) for (unfold in listOf(.25f, .85f)) {
            val patch = RimePatch("Held $voice", voice, Rime.defaults(voice) + mapOf("DETAIL" to .95f,
                "EDGE" to .7f, "ANGLE" to .1f, "BODY" to .5f, "UNFOLD" to unfold, "HOLD" to 1f), gateSeconds = .3f)
            val restored = assertIs<RimePatch>(Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, restored)
            val moving = unfold >= .6f
            assertEquals(moving, Rime.movingHold(restored.macros, restored.model))
            val r = RimeHeld.render(restored)
            assertTrue(r.seamError < 1e-3)
            assertEquals(r.seamError, Keys.seamError(r.previousCycle + r.cycle, r.cycle.size), 1e-12)
            assertContentEquals(r.cycle, r.note.snip.samples.copyOfRange(r.note.loopStartFrame.toInt(), r.note.snip.samples.size))
            assertTrue(r.note.snip.samples.all { it.isFinite() && abs(it) <= .94001f })
            assertTrue(abs(r.cycle.average()) < .002)
            val hz = Keys.midiHz(patch.midi).toDouble()
            assertTrue(abs(FineTuning.cents(r.actualHz, hz)) < .08)
            val measured = assertNotNull(FineTuning.measuredHz(Snip(r.cycle,1,Dsp.RATE),hz.toFloat(),fromSec=.05f,bodySeconds=.35f))
            assertTrue(abs(FineTuning.cents(measured.toDouble(),hz)) < 8)
            if (moving) {
                val duration = r.cycle.size.toDouble()/Dsp.RATE
                val fine = listOf(0.0,.25,.5,.75).map { RimeControlMeasure.fineShare(r.cycle,hz,it*duration) }
                val span = fine.max()-fine.min()
                println("RIME moving $voice fine-power span=$span, seam=${r.seamError}, pitch=${r.actualHz}")
                assertTrue(span > .01, "$voice moving HOLD must change resolved overtone power: $fine")
            }
        }
    }

    @Test fun `moving hold remains bounded and pitched at extreme roots and maximum timbre controls`() {
        for (voice in RimeVoice.entries) for (midi in listOf(24,96)) {
            val macros=Rime.defaults(voice)+Rime.TIMBRAL.associateWith { 1f }+("HOLD" to 1f)
            val p=RimePatch("Extreme held $voice",voice,macros,midi,1f,.3f)
            val r=RimeHeld.render(p)
            assertTrue(r.seamError<1e-3,"$voice/$midi seam ${r.seamError}")
            assertTrue(r.internalPeak<8 && r.maxBodyEnergy<1e8)
            assertTrue(r.cycle.all { it.isFinite() && abs(it)<=.94001f })
            assertTrue(abs(r.cycle.average())<.002)
            val hz=Keys.midiHz(midi)
            val measured=assertNotNull(FineTuning.measuredHz(Snip(r.cycle,1,Dsp.RATE),hz,fromSec=.05f,bodySeconds=.35f))
            assertTrue(abs(FineTuning.cents(measured.toDouble(),hz.toDouble()))<8,"$voice/$midi loses its root")
        }
    }
}
