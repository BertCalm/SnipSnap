package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.Mpc3TrackWriter
import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PadShapeTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("shape").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val rate = 44_100

    private fun tone(seconds: Float, hz: Double = 220.0): Snip =
        Snip(
            FloatArray((seconds * rate).toInt()) { i ->
                (0.5 * Math.sin(2.0 * Math.PI * hz * i / rate)).toFloat()
            },
            1, rate,
        )

    private fun buildKit(dir: File, shaped: Boolean): Kit {
        dir.mkdirs()
        WavWriter.write(File(dir, "A01_Kick_01.wav"), tone(1.0f, 80.0))
        WavWriter.write(File(dir, "A02_Snare_01.wav"), tone(0.3f, 900.0))
        val kit = Kit(
            "Shaped Kit",
            listOf(
                KitPad(
                    slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK,
                    attack = if (shaped) 0.1f else null,
                    decay = if (shaped) 0.3f else null,
                    cutoff = if (shaped) 0.6f else null,
                    resonance = if (shaped) 0.2f else null,
                ),
                KitPad(slot = 2, sampleFile = "A02_Snare_01.wav", drumClass = DrumClass.SNARE),
            ),
        )
        KitStore.save(kit, dir)
        return kit
    }

    @Test
    fun `the shape round-trips through kit json and null never gets written down`() {
        val dir = File(temp, "store")
        val kit = buildKit(dir, shaped = true)
        assertEquals(kit, KitStore.load(dir))
        val text = File(dir, KitStore.FILE_NAME).readText()
        assertTrue("\"decay\"" in text, "the shaped pad's decay persists")
        // The unshaped pad carries no shape keys at all.
        val padTexts = text.substringAfter("\"pads\"")
        assertEquals(1, Regex("\"decay\"").findAll(padTexts).count(), "null shape is absent, not zero")
        assertFailsWith<IllegalArgumentException> { KitPad(1, "a.wav", decay = 1.5f) }
    }

    @Test
    fun `both generations carry the shape in their own fields`() {
        val dir = File(temp, "gen")
        val kit = buildKit(dir, shaped = true)

        // MPC 2: the .xpm text carries the values on the shaped instrument.
        val (slots, _) = KitExporter.buildSlots(kit, dir, File(temp, "gen-samples"))
        val xpm = com.snipsnap.xpm.XpmWriter().write(
            com.snipsnap.xpm.DrumProgram(kit.name, slots),
        )
        assertTrue("<VolumeDecay>0.300000</VolumeDecay>" in xpm, "decay lands in the .xpm")
        assertTrue("<VolumeAttack>0.100000</VolumeAttack>" in xpm)
        assertTrue("<Cutoff>0.600000</Cutoff>" in xpm)
        assertTrue("<Resonance>0.200000</Resonance>" in xpm)

        // MPC 3: the .xtd instrument's ampEnvelope and filter slot carry them.
        val payload = Json.parse(
            Mpc3TrackWriter().payloadText(com.snipsnap.xpm.DrumProgram(kit.name, slots)),
        ) as JsonValue.Obj
        val drum = findDrum(payload)
        val inst = ((drum["instruments"] as JsonValue.Arr).items[0] as JsonValue.Obj).entries
        val synth = (inst["synthSection"] as JsonValue.Obj).entries
        val amp = ((synth["ampEnvelope"] as JsonValue.Obj).entries)
        fun v0(name: String): Double =
            ((((amp[name]) as JsonValue.Obj).entries["value0"]) as JsonValue.Num).value
        assertTrue(abs(v0("Decay") - 0.3) < 1e-5, "decay lands in the .xtd amp envelope")
        assertTrue(abs(v0("Attack") - 0.1) < 1e-5)
        val filter = (((synth["filterData"] as JsonValue.Obj).entries["value0"]) as JsonValue.Obj).entries
        assertTrue(abs((filter["filterCutoff"] as JsonValue.Num).value - 0.6) < 1e-5)
        assertTrue(abs((filter["filterResonance"] as JsonValue.Num).value - 0.2) < 1e-5)

        // An unshaped kit writes the exact defaults - the goldens' promise.
        val plainDir = File(temp, "plain")
        val plain = buildKit(plainDir, shaped = false)
        val (plainSlots, _) = KitExporter.buildSlots(plain, plainDir, File(temp, "plain-samples"))
        val plainXpm = com.snipsnap.xpm.XpmWriter().write(com.snipsnap.xpm.DrumProgram(plain.name, plainSlots))
        assertTrue("<VolumeDecay>0.047244</VolumeDecay>" in plainXpm, "unshaped keeps the format default")
        assertTrue("<Cutoff>1.000000</Cutoff>" in plainXpm)
    }

    @Test
    fun `the shape survives a round trip through both native containers`() {
        val dir = File(temp, "rt")
        val kit = buildKit(dir, shaped = true)

        // Out through .xpn and back.
        val xpn = File(temp, "rt.xpn")
        XpnPackager.write(kit, dir, xpn, Exporters.defaultMeta(kit))
        val viaXpn = XpnImporter.import(xpn, File(temp, "rt-xpn-in")).kit
        val xpnPad = viaXpn.pad(1)!!
        assertTrue(abs(xpnPad.decay!! - 0.3f) < 1e-4f, "decay back from .xpn: ${xpnPad.decay}")
        assertTrue(abs(xpnPad.cutoff!! - 0.6f) < 1e-4f)
        assertEquals(null, viaXpn.pad(2)!!.decay, "default-shaped reads back as unshaped")

        // Out through .xtd and back.
        val card = File(temp, "rt-card")
        Mpc3Exporter.exportTrack(kit, dir, card)
        val xtd = card.walkTopDown().first { it.extension == "xtd" }
        val viaXtd = Mpc3Importer.import(xtd, File(temp, "rt-xtd-in")).kit
        val xtdPad = viaXtd.pad(1)!!
        assertTrue(abs(xtdPad.decay!! - 0.3f) < 1e-4f, "decay back from .xtd: ${xtdPad.decay}")
        assertTrue(abs(xtdPad.resonance!! - 0.2f) < 1e-4f)
        assertEquals(null, viaXtd.pad(2)!!.cutoff, "default-shaped reads back as unshaped")
    }

    @Test
    fun `humanize rides the MPC 3 random fields and the MPC 2 honestly ignores it`() {
        // GG4 probe verdict, pinned: neither generation has a layer
        // round-robin field, but the .xtd layer carries real per-hit
        // randomization (all zero on every commercial layer) - that IS the
        // format's "no two hits alike" mechanism. The .xpm has nothing.
        val dir = File(temp, "hum")
        val kit = buildKit(dir, shaped = false).let { k ->
            k.copy(pads = k.pads.map { if (it.slot == 1) it.copy(humanize = 0.5f) else it })
        }
        KitStore.save(kit, dir)
        assertEquals(kit, KitStore.load(dir), "humanize round-trips through kit.json")

        val (slots, _) = KitExporter.buildSlots(kit, dir, File(temp, "hum-samples"))
        // MPC 3: layer randoms scaled from the macro; empty layers stay zero.
        val payload = Json.parse(
            Mpc3TrackWriter().payloadText(com.snipsnap.xpm.DrumProgram(kit.name, slots)),
        ) as JsonValue.Obj
        val drum = findDrum(payload)
        val inst = ((drum["instruments"] as JsonValue.Arr).items[0] as JsonValue.Obj).entries
        val layers = (inst["layersv"] as JsonValue.Arr).items.map { (it as JsonValue.Obj).entries }
        fun rand(l: Map<String, JsonValue>, k: String) = (l[k] as JsonValue.Num).value
        assertTrue(abs(rand(layers[0], "VolumeRandom") - 0.1) < 1e-6, "volume random = h * 0.2")
        assertTrue(abs(rand(layers[0], "pitchRandom") - 0.025) < 1e-6, "pitch random = h * 0.05")
        assertTrue(abs(rand(layers[0], "PanRandom") - 0.05) < 1e-6, "pan random = h * 0.1")
        assertTrue(rand(layers[1], "VolumeRandom") == 0.0, "empty layer slots keep the corpus's zeros")

        // MPC 2: no such fields exist - the .xpm is identical with or without.
        val xpmWith = com.snipsnap.xpm.XpmWriter().write(com.snipsnap.xpm.DrumProgram(kit.name, slots))
        val plainSlots = slots.map { it?.copy(humanize = null) }
        val xpmWithout = com.snipsnap.xpm.XpmWriter().write(com.snipsnap.xpm.DrumProgram(kit.name, plainSlots))
        assertEquals(xpmWithout, xpmWith, "the MPC 2 generation honestly ignores humanize")

        // And it comes back from the .xtd.
        val card = File(temp, "hum-card")
        Mpc3Exporter.exportTrack(kit, dir, card)
        val xtd = card.walkTopDown().first { it.extension == "xtd" }
        val back = Mpc3Importer.import(xtd, File(temp, "hum-in")).kit
        assertTrue(abs(back.pad(1)!!.humanize!! - 0.5f) < 1e-4f, "humanize back from .xtd: ${back.pad(1)!!.humanize}")
        assertEquals(null, back.pad(2)!!.humanize)
    }

    @Test
    fun `the preview approximates the shape - a tightened pad dies early`() {
        val dir = File(temp, "pv")
        buildKit(dir, shaped = false)
        val plain = KitStore.load(dir)
        val clip = com.snipsnap.mpc3.Mpc3Clip(
            "One", 1, listOf(com.snipsnap.mpc3.Mpc3Note(36, 0, 0.9f)),
        )
        val loose = KitPreview.render(plain, dir, clip = clip)
        val tightKit = plain.copy(
            pads = plain.pads.map { if (it.slot == 1) it.copy(decay = 0.15f) else it },
        )
        val tight = KitPreview.render(tightKit, dir, clip = clip)

        // The kick is a 1s tone; at decay 0.15 it must be silent well before
        // the loose render is.
        fun rmsAt(s: Snip, fromSec: Float, toSec: Float): Double {
            val from = (fromSec * KitPreview.RATE).toInt() * 2
            val to = minOf(s.samples.size, (toSec * KitPreview.RATE).toInt() * 2)
            var acc = 0.0
            for (i in from until to) acc += s.samples[i] * s.samples[i].toDouble()
            return Math.sqrt(acc / (to - from).coerceAtLeast(1))
        }
        assertTrue(rmsAt(loose, 0.5f, 0.9f) > 0.01, "the loose kick still rings at half a second")
        assertTrue(rmsAt(tight, 0.5f, 0.9f) < 1e-4, "the tightened kick is gone by then")
    }

    private fun findDrum(node: JsonValue): Map<String, JsonValue> {
        val stack = ArrayDeque<JsonValue>()
        stack.add(node)
        while (stack.isNotEmpty()) {
            when (val cur = stack.removeLast()) {
                is JsonValue.Obj -> {
                    if ("instruments" in cur.entries) return cur.entries
                    cur.entries.values.forEach { stack.add(it) }
                }
                is JsonValue.Arr -> cur.items.forEach { stack.add(it) }
                else -> Unit
            }
        }
        error("no drum program in the payload")
    }
}
