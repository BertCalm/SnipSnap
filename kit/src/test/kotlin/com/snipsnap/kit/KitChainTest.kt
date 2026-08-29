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
    fun `a grid round-trips through kit json and bad grids are refused`() {
        val dir = File(temp, "grid-store")
        dir.mkdirs()
        // 6 takes: 3 zones x 2 robins, dynamics-graded soft->hard.
        val samples = FloatArray(segFrames * 6)
        for (seg in 0 until 6) {
            for (i in 0 until segFrames) samples[seg * segFrames + i] = 0.1f + seg * 0.1f
        }
        WavWriter.write(File(dir, "A01_Grid_01.wav"), Snip(samples, 1, rate))
        val boundaries = (0 until 6).map { it.toLong() * segFrames }
        val zones = listOf(
            ChainZone(velStart = 0, velEnd = 50, baseSlice = 0, cycle = 2),
            ChainZone(velStart = 51, velEnd = 100, baseSlice = 2, cycle = 2),
            ChainZone(velStart = 101, velEnd = 127, baseSlice = 4, cycle = 2),
        )
        val kit = Kit(
            "Grid Kit",
            listOf(
                KitPad(
                    slot = 1, sampleFile = "A01_Grid_01.wav", drumClass = DrumClass.SNARE,
                    chain = ChainInfo(boundaries, cycle = 2, zones = zones),
                ),
            ),
        )
        KitStore.save(kit, dir)
        assertEquals(kit, KitStore.load(dir), "the grid survives the sidecar")
        assertTrue("\"zones\"" in File(dir, KitStore.FILE_NAME).readText())

        // The projection resolves each zone's anchor window in frames.
        val play = kit.pads.single().chain!!.toPlay(samples.size.toLong())
        assertEquals(3, play.zones!!.size)
        assertEquals(2L * segFrames, play.zones!![1].windowStart)
        assertEquals(3L * segFrames, play.zones!![1].windowEnd)

        // And velocity finds its zone.
        val chain = kit.pads.single().chain!!
        assertEquals(0, chain.zoneFor(30)!!.baseSlice)
        assertEquals(2, chain.zoneFor(75)!!.baseSlice)
        assertEquals(4, chain.zoneFor(127)!!.baseSlice)

        // The shapes the grid refuses.
        fun zonesOf(vararg z: ChainZone) = z.toList()
        assertFailsWith<IllegalArgumentException>("one zone is no grid") {
            ChainInfo(boundaries, 2, zonesOf(ChainZone(0, 127, 0, 2)))
        }
        assertFailsWith<IllegalArgumentException>("five zones exceed the cap") {
            ChainInfo(
                boundaries, 2,
                zonesOf(
                    ChainZone(0, 20, 0, 1), ChainZone(21, 40, 1, 1), ChainZone(41, 60, 2, 1),
                    ChainZone(61, 80, 3, 1), ChainZone(81, 127, 4, 1),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException>("a velocity gap") {
            ChainInfo(boundaries, 2, zonesOf(ChainZone(0, 50, 0, 2), ChainZone(52, 127, 2, 2)))
        }
        assertFailsWith<IllegalArgumentException>("a velocity overlap") {
            ChainInfo(boundaries, 2, zonesOf(ChainZone(0, 50, 0, 2), ChainZone(50, 127, 2, 2)))
        }
        assertFailsWith<IllegalArgumentException>("zones must reach 127") {
            ChainInfo(boundaries, 2, zonesOf(ChainZone(0, 50, 0, 2), ChainZone(51, 100, 2, 2)))
        }
        assertFailsWith<IllegalArgumentException>("a window past the chain") {
            ChainInfo(boundaries, 2, zonesOf(ChainZone(0, 50, 0, 2), ChainZone(51, 127, 5, 2)))
        }
    }

    private fun gridKit(dir: File): Kit {
        dir.mkdirs()
        // 6 takes: 3 zones x 2 robins, graded soft->hard by level.
        val samples = FloatArray(segFrames * 6)
        for (seg in 0 until 6) {
            for (i in 0 until segFrames) samples[seg * segFrames + i] = 0.1f + seg * 0.1f
        }
        WavWriter.write(File(dir, "A01_Grid_01.wav"), Snip(samples, 1, rate))
        val kit = Kit(
            "Grid Kit",
            listOf(
                KitPad(
                    slot = 1, sampleFile = "A01_Grid_01.wav", drumClass = DrumClass.SNARE,
                    chain = ChainInfo(
                        boundaries = (0 until 6).map { it.toLong() * segFrames },
                        cycle = 2,
                        zones = listOf(
                            ChainZone(velStart = 0, velEnd = 50, baseSlice = 0, cycle = 2),
                            ChainZone(velStart = 51, velEnd = 100, baseSlice = 2, cycle = 2),
                            ChainZone(velStart = 101, velEnd = 127, baseSlice = 4, cycle = 2),
                        ),
                    ),
                ),
            ),
        )
        KitStore.save(kit, dir)
        return kit
    }

    @Test
    fun `both generations write the grid - PSK shapes and slice windows`() {
        val dir = File(temp, "grid-gen")
        val kit = gridKit(dir)
        val (slots, _) = KitExporter.buildSlots(kit, dir, File(temp, "grid-samples"))

        // MPC 3: one layer per zone, loudest first, each anchoring its own
        // base slice with its own cycle - the PSK field pattern exactly.
        val payload = Json.parse(
            Mpc3TrackWriter().payloadText(com.snipsnap.xpm.DrumProgram(kit.name, slots)),
        ) as JsonValue.Obj
        val drum = findDrum(payload)
        val inst = ((drum["instruments"] as JsonValue.Arr).items[0] as JsonValue.Obj).entries
        val layers = (inst["layersv"] as JsonValue.Arr).items.map { (it as JsonValue.Obj).entries }
        fun num(l: Map<String, JsonValue>, k: String) = (l[k] as JsonValue.Num).value
        fun info(l: Map<String, JsonValue>, k: String) =
            ((l["sliceInfo"] as JsonValue.Obj).entries[k] as JsonValue.Num).value

        // L0 = loudest zone (101-127, base 4); L2 = softest (0-50, base 0).
        val expect = listOf(
            Triple(101 to 127, 4, 4 * segFrames to 5 * segFrames),
            Triple(51 to 100, 2, 2 * segFrames to 3 * segFrames),
            Triple(0 to 50, 0, 0 to segFrames),
        )
        expect.forEachIndexed { li, (vel, base, window) ->
            val l = layers[li]
            assertEquals(vel.first.toDouble(), num(l, "velocityStart"), "L$li velStart")
            assertEquals(vel.second.toDouble(), num(l, "velocityEnd"), "L$li velEnd")
            assertEquals(base.toDouble(), num(l, "sliceIndex"), "L$li anchors its zone's base")
            assertEquals(1.0, num(l, "sliceIncrement"))
            assertEquals(2.0, num(l, "sliceCycleLength"))
            assertEquals(window.first.toDouble(), info(l, "Start"), "L$li window start")
            assertEquals(window.second.toDouble(), info(l, "End"), "L$li window end")
            assertEquals("A01_Grid_01", (l["sampleName"] as JsonValue.Str).value, "one chain, every layer")
        }
        assertEquals(128.0, num(layers[3], "sliceIndex"), "slots past the zones stay empty")

        // MPC 2: the same zones as slice windows into the one WAV - real
        // velocity switching, no robin, that generation's honest ceiling.
        val xpm = com.snipsnap.xpm.XpmWriter().write(com.snipsnap.xpm.DrumProgram(kit.name, slots))
        val pad1 = xpm.substringAfter("<Instrument number=\"0\">").substringBefore("</Instrument>")
        assertTrue("<VelStart>0</VelStart>" in pad1 && "<VelEnd>50</VelEnd>" in pad1)
        assertTrue("<SliceStart>0</SliceStart>" in pad1 && "<SliceEnd>$segFrames</SliceEnd>" in pad1)
        assertTrue("<SliceStart>${2 * segFrames}</SliceStart>" in pad1)
        assertTrue("<SliceEnd>${3 * segFrames}</SliceEnd>" in pad1)
        assertTrue("<SliceStart>${4 * segFrames}</SliceStart>" in pad1)
        assertEquals(
            3,
            Regex("<SampleName>A01_Grid_01</SampleName>").findAll(pad1).count(),
            "three layers, one chain WAV",
        )
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
    fun `the preview's grid - velocity picks the zone and hits cycle within it`() {
        val dir = File(temp, "grid-pv")
        val kit = gridKit(dir)
        // Two soft hits then two hard: the soft lane cycles takes 1-2
        // (amps 0.1/0.2), the hard lane takes 5-6 (amps 0.5/0.6).
        val q = Mpc3Clip.PULSES_PER_16TH * 4L
        val clip = Mpc3Clip(
            "GridSteps", 1,
            listOf(
                Mpc3Note(36, 0, 0.2f),
                Mpc3Note(36, q, 0.2f),
                Mpc3Note(36, 2 * q, 1.0f),
                Mpc3Note(36, 3 * q, 1.0f),
            ),
        )
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
        val p = (0 until 4).map { peakAt(it) }
        // Same velocity within each pair, so peak ratios are take ratios.
        assertTrue(Math.abs(p[1] / p[0] - 2f) < 0.1f, "the soft lane steps 0.1 -> 0.2: $p")
        assertTrue(Math.abs(p[3] / p[2] - 0.6f / 0.5f) < 0.05f, "the hard lane steps 0.5 -> 0.6: $p")
        assertTrue(p[2] > p[1], "the hard zone plays harder takes: $p")
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
