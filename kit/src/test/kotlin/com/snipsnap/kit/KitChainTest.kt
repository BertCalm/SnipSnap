package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.mpc3.Mpc3TrackWriter
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * HH1.1 — chain pads (MPC 3 Slice Motion). The corpus's PSK kit is the
 * spec: one chain WAV, layers stepping slices per hit via
 * `sliceIncrement`/`sliceCycleLength`. We write chain pads that way; the
 * MPC 2 generation windows to slice 0; the preview steps audibly.
 */
class KitChainTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("chain").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val rate = 44_100

    /** 3 takes of 0.1s each at amplitudes 0.2 / 0.5 / 0.8 — a chain whose slices are tellable apart by level. */
    private val segFrames = 4_410
    private val amps = floatArrayOf(0.2f, 0.5f, 0.8f)

    private fun chainKit(dir: File, cycle: Int = 3): Kit {
        dir.mkdirs()
        val samples = FloatArray(segFrames * amps.size)
        for (seg in amps.indices) {
            for (i in 0 until segFrames) samples[seg * segFrames + i] = amps[seg]
        }
        WavWriter.write(File(dir, "A01_Chain_01.wav"), Snip(samples, 1, rate))
        WavWriter.write(File(dir, "A02_Snare_01.wav"), Snip(FloatArray(segFrames) { 0.4f }, 1, rate))
        val kit = Kit(
            "Chain Kit",
            listOf(
                KitPad(
                    slot = 1, sampleFile = "A01_Chain_01.wav", drumClass = DrumClass.KICK,
                    chain = ChainInfo(
                        boundaries = listOf(0L, segFrames.toLong(), 2L * segFrames),
                        cycle = cycle,
                    ),
                ),
                KitPad(slot = 2, sampleFile = "A02_Snare_01.wav", drumClass = DrumClass.SNARE),
            ),
        )
        KitStore.save(kit, dir)
        return kit
    }

    @Test
    fun `the chain round-trips through kit json and validation holds`() {
        val dir = File(temp, "store")
        val kit = chainKit(dir)
        assertEquals(kit, KitStore.load(dir), "chain block round-trips")
        val text = File(dir, KitStore.FILE_NAME).readText()
        assertTrue("\"chain\"" in text && "\"boundaries\"" in text, "the chain persists")
        assertEquals(1, Regex("\"chain\"").findAll(text).count(), "the plain pad carries no chain key")

        // The model refuses the shapes the hardware can't play.
        assertFailsWith<IllegalArgumentException>("one slice is no chain") {
            ChainInfo(boundaries = listOf(0L), cycle = 2)
        }
        assertFailsWith<IllegalArgumentException>("the first slice starts at 0") {
            ChainInfo(boundaries = listOf(10L, 20L), cycle = 2)
        }
        assertFailsWith<IllegalArgumentException>("boundaries ascend") {
            ChainInfo(boundaries = listOf(0L, 300L, 200L), cycle = 2)
        }
        assertFailsWith<IllegalArgumentException>("cycle can't exceed the slice count") {
            ChainInfo(boundaries = listOf(0L, 100L), cycle = 3)
        }
        assertFailsWith<IllegalArgumentException>("a chain pad is single-zone") {
            KitPad(
                slot = 1, sampleFile = "b.wav",
                chain = ChainInfo(listOf(0L, 100L), 2),
                velocityLayers = listOf(KitLayer("a.wav", 0, 60), KitLayer("b.wav", 61, 127)),
            )
        }
    }

    @Test
    fun `the MPC 3 writes chain pads the PSK way`() {
        val dir = File(temp, "xtd")
        val kit = chainKit(dir)
        val (slots, _) = KitExporter.buildSlots(kit, dir, File(temp, "xtd-samples"))
        val payload = Json.parse(
            Mpc3TrackWriter().payloadText(com.snipsnap.xpm.DrumProgram(kit.name, slots)),
        ) as JsonValue.Obj
        val drum = findDrum(payload)
        fun layers(padIndex: Int): List<Map<String, JsonValue>> {
            val inst = ((drum["instruments"] as JsonValue.Arr).items[padIndex] as JsonValue.Obj).entries
            return (inst["layersv"] as JsonValue.Arr).items.map { (it as JsonValue.Obj).entries }
        }
        fun num(l: Map<String, JsonValue>, k: String) = (l[k] as JsonValue.Num).value

        // The chain pad's filled layer steps: base slice 0, +1 per hit,
        // cycling 3, sliceInfo windowing slice 0 (boundaries stay in the
        // WAV — HH1.4). Exactly the PSK layers' shape.
        val chainLayer = layers(0)[0]
        assertEquals(0.0, num(chainLayer, "sliceIndex"), "base slice 0")
        assertEquals(1.0, num(chainLayer, "sliceIncrement"), "step 1 per hit")
        assertEquals(3.0, num(chainLayer, "sliceCycleLength"), "cycling all 3 takes")
        val sliceInfo = (chainLayer["sliceInfo"] as JsonValue.Obj).entries
        assertEquals(segFrames.toDouble(), (sliceInfo["End"] as JsonValue.Num).value, "windowed to take one")

        // Empty layer slots keep the corpus's shape: 128 / 0 / 1.
        val empty = layers(0)[1]
        assertEquals(128.0, num(empty, "sliceIndex"))
        assertEquals(0.0, num(empty, "sliceIncrement"))
        assertEquals(1.0, num(empty, "sliceCycleLength"))

        // The ordinary pad doesn't step and plays its full length.
        val plain = layers(1)[0]
        assertEquals(0.0, num(plain, "sliceIncrement"))
        assertEquals(1.0, num(plain, "sliceCycleLength"))
        assertEquals(
            segFrames.toDouble(),
            ((plain["sliceInfo"] as JsonValue.Obj).entries["End"] as JsonValue.Num).value,
        )
    }

    @Test
    fun `the MPC 2 windows a chain to take one`() {
        val dir = File(temp, "xpm")
        val kit = chainKit(dir)
        val (slots, _) = KitExporter.buildSlots(kit, dir, File(temp, "xpm-samples"))
        val xpm = com.snipsnap.xpm.XpmWriter().write(com.snipsnap.xpm.DrumProgram(kit.name, slots))
        // Pad 1's filled layer ends at the first boundary, not the chain's
        // full length; the empty layer slots keep their zeros.
        val pad1Layers = xpm.substringAfter("<Instrument number=\"0\">").substringBefore("</Instrument>")
        assertTrue("<SliceEnd>$segFrames</SliceEnd>" in pad1Layers, "take one, not the whole chain")
        assertTrue("<SliceEnd>${segFrames * 3}</SliceEnd>" !in pad1Layers)
        assertEquals(3, Regex("<SliceEnd>0</SliceEnd>").findAll(pad1Layers).count(), "empty slots stay zero")
    }

    @Test
    fun `the preview steps through the slices hit by hit`() {
        val dir = File(temp, "pv")
        val kit = chainKit(dir, cycle = 3)
        // Four quarter-note hits on the chain pad: slices 0,1,2 then back to 0.
        val q = Mpc3Clip.PULSES_PER_16TH * 4L
        val clip = Mpc3Clip("Steps", 1, (0 until 4).map { Mpc3Note(36, it * q, 1.0f) })
        val out = KitPreview.render(kit, dir, clip = clip, tempoBpm = 92f)

        val framesPerPulse = 60.0 / 92f * KitPreview.RATE / 960.0
        fun peakAt(hit: Int): Float {
            val start = Math.round(hit * q * framesPerPulse).toInt()
            var peak = 0f
            for (f in start until minOf(start + segFrames, out.frameCount)) {
                val s = Math.abs(out.samples[f * 2])
                if (s > peak) peak = s
            }
            return peak
        }
        val peaks = (0 until 4).map { peakAt(it) }
        // The three takes have levels 0.2 / 0.5 / 0.8 - the preview must
        // play them in order and wrap.
        assertTrue(peaks[0] < peaks[1] && peaks[1] < peaks[2], "slices ascend across hits: $peaks")
        assertTrue(Math.abs(peaks[3] - peaks[0]) < peaks[0] * 0.05f, "hit 4 wraps to take one: $peaks")
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
