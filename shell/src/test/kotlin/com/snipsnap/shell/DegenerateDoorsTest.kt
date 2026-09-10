package com.snipsnap.shell

import com.snipsnap.audio.Pcm
import com.snipsnap.audio.SilenceWatch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Similar
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.ThumpPatch
import com.snipsnap.synth.ThumpVoice
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hardening matrix, round two (wave BBB): every door the phone grew
 * since the SS2 matrix meets every degenerate shape a tape can arrive in.
 * The contract is the same binary, named one: a door either returns a
 * valid result - finite audio, a selection inside the tape, a sheet whose
 * loop is inside its sample - or refuses with an IllegalArgumentException
 * that says why. No other throwable, and silence never invents a beat.
 */
class DegenerateDoorsTest {

    private val rate = 44_100
    private val temp: File = java.nio.file.Files.createTempDirectory("doors").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun fixtures(): List<Pair<String, Snip>> {
        val square = FloatArray(rate / 2) { i -> if ((i / 100) % 2 == 0) 1f else -1f }
        return listOf(
            "one sample" to Snip(floatArrayOf(0.5f), 1, rate),
            "tiny" to Snip(FloatArray(44) { i -> (0.5 * Math.sin(2.0 * Math.PI * 440 * i / rate)).toFloat() }, 1, rate),
            "silence" to Snip(FloatArray(rate / 2), 1, rate),
            "pure DC" to Snip(FloatArray(rate / 2) { 0.5f }, 1, rate),
            "full-scale square" to Snip(square, 1, rate),
            "low rate" to Snip(FloatArray(2400) { i -> (0.5 * Math.sin(2.0 * Math.PI * 200 * i / 8000)).toFloat() }, 1, 8000),
            "stereo" to Snip(
                FloatArray(rate) { i -> (0.4 * Math.sin(2.0 * Math.PI * (if (i % 2 == 0) 300.0 else 500.0) * (i / 2) / rate)).toFloat() },
                2, rate,
            ),
            "white noise" to Snip(Random(7).let { r -> FloatArray(rate) { (r.nextFloat() * 2 - 1) * 0.8f } }, 1, rate),
        )
    }

    private val kit = Kit(
        "Doors",
        listOf(
            KitPad(slot = 1, sampleFile = "k.wav", drumClass = DrumClass.KICK),
            KitPad(slot = 2, sampleFile = "s.wav", drumClass = DrumClass.SNARE),
            KitPad(slot = 3, sampleFile = "h.wav", drumClass = DrumClass.HAT_CLOSED),
        ),
    )

    /** Each door: a valid result, checked in words, or an IllegalArgumentException with a reason. */
    private fun doors(): List<Pair<String, (Snip) -> Unit>> = listOf(
        "ReadGroove.read" to { s ->
            val r = ReadGroove.read(s, kit, "x")
            assertTrue(r.hits > 0 && r.bars in 1..64 && r.bpm.isFinite(), "a reading with hits, bars and a tempo")
        },
        "ReadGroove.feel" to { s ->
            val dir = File(temp, "feel-${s.hashCode()}").apply { mkdirs() }
            // A straight base to pour on, so the door's own refusal is what is tested.
            val base = com.snipsnap.mpc3.Mpc3Clip("Base", 1, listOf(com.snipsnap.mpc3.Mpc3Note(36, 0, 0.9f)))
            com.snipsnap.kit.GrooveStore.save(dir, listOf(base))
            val f = ReadGroove.feel(s, dir, "x")
            assertTrue(f.covered in 2..16)
        },
        "Dig.best" to { s ->
            val found = Dig.best(s)
            if (found != null) {
                assertTrue(found.startFrame >= 0 && found.endFrame <= s.frameCount && found.startFrame < found.endFrame, "a find inside the tape")
                assertTrue(found.score.isFinite())
            }
        },
        "SnipStore.import" to { s ->
            val dir = File(temp, "import-${s.hashCode()}").apply { mkdirs() }
            val landed = SnipStore.import(s, dir, 1_000L)
            val back = WavReader.read(landed.file)
            assertTrue(back.frameCount > 0 && back.samples.all { it.isFinite() })
        },
        "InstantKit.build" to { s ->
            val dir = File(temp, "instant-${s.hashCode()}")
            val r = InstantKit.build(s, "Instant", dir)
            assertTrue(r.sliceCount > 0 && r.kit.pads.isNotEmpty())
        },
        "SilenceWatch.feed" to { s ->
            val watch = SilenceWatch.forSeconds(0.1, s.sampleRate)
            var ticks = 0
            val block = FloatArray(512)
            var i = 0
            while (i < s.samples.size) {
                val n = minOf(512, s.samples.size - i)
                System.arraycopy(s.samples, i, block, 0, n)
                if (watch.feed(block, n)) ticks++
                i += n
            }
            val silent = s.samples.all { it == 0f }
            if (!silent) assertTrue(ticks == 0 || s.frameCount > watch.holdFrames, "sound never ticks the watch by itself")
        },
    )

    @Test
    fun `every door returns a valid result or refuses by name - across every degenerate shape`() {
        for ((fixtureName, snip) in fixtures()) {
            for ((doorName, door) in doors()) {
                val result = runCatching { door(snip) }
                result.exceptionOrNull()?.let { e ->
                    if (e is AssertionError) throw e
                    assertTrue(
                        e is IllegalArgumentException,
                        "$doorName on $fixtureName threw ${e::class.simpleName}: ${e.message}",
                    )
                    assertTrue(!e.message.isNullOrBlank(), "$doorName on $fixtureName refused without saying why")
                }
            }
        }
    }

    @Test
    fun `silence never becomes a beat, a break, or a kit`() {
        val silence = Snip(FloatArray(rate * 2), 1, rate)
        assertTrue(runCatching { ReadGroove.read(silence, kit, "s") }.isFailure, "the ear hears no beat in silence")
        assertTrue(Dig.best(silence) == null, "no break in silence")
        assertTrue(runCatching { InstantKit.build(silence, "S", File(temp, "silent-kit")) }.isFailure, "no hits, no kit")
    }

    @Test
    fun `PadPeaks shrugs at junk, truncated and missing pad files`() {
        val dir = File(temp, "peaks").apply { mkdirs() }
        val rnd = Random(11)
        File(dir, "junk.wav").writeBytes(ByteArray(300) { rnd.nextInt(256).toByte() })
        val good = File(dir, "good.wav").also { WavWriter.write(it, Snip(FloatArray(2000) { 0.3f }, 1, rate)) }
        File(dir, "trunc.wav").writeBytes(good.readBytes().copyOf(40))
        File(dir, "empty.wav").writeBytes(ByteArray(0))
        val k = Kit(
            "P",
            listOf(
                KitPad(slot = 1, sampleFile = "junk.wav"),
                KitPad(slot = 2, sampleFile = "good.wav"),
                KitPad(slot = 3, sampleFile = "trunc.wav"),
                KitPad(slot = 4, sampleFile = "empty.wav"),
                KitPad(slot = 5, sampleFile = "gone.wav"),
            ),
        )
        val peaks = PadPeaks.forKit(k, dir)
        assertTrue(2 in peaks.keys, "the good pad draws")
        assertTrue(peaks.values.all { cols -> cols.all { it.min.isFinite() && it.max.isFinite() } })
    }

    @Test
    fun `Pcm turns any bytes into finite samples inside the rails`() {
        val rnd = Random(5)
        val bytes = ByteArray(4_000) { rnd.nextInt(256).toByte() }
        val out16 = FloatArray(2_000)
        val n16 = Pcm.int16ToFloat(bytes, 0, bytes.size, out16, 0)
        assertTrue(n16 == 2_000 && out16.all { it.isFinite() && it >= -1f && it <= 1f })
        val out32 = FloatArray(1_000)
        val n32 = Pcm.floatToFloat(bytes, 0, bytes.size, out32, 0)
        assertTrue(n32 == 1_000 && out32.all { it.isFinite() && it >= -1f && it <= 1f })
    }

    // ---- the Hardening, round five: the Spec Sheet II doors -------------------

    /**
     * Real recipes, written by the doors themselves on a sound they accept,
     * so the replay meets exactly the JSON it will meet in the field - plus
     * the two shapes no door leaves on a pad the same way (a synth patch, a
     * round-robin deal).
     */
    private fun seedRecipes(): List<Pair<String, JsonValue.Obj>> {
        val m = KitBuilderModel.create("Seeds", File(temp, "seeds"))
        val tone = Snip(FloatArray(rate / 2) { i -> (0.5 * Math.sin(2.0 * Math.PI * 220 * i / rate)).toFloat() }, 1, rate)
        m.assign(1, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(2, tone, DrumClass.UNKNOWN)
        val era = m.eraPad(1, com.snipsnap.synth.Eras.names.first(), 0.5f).recipe!!
        val character = m.characterPad(1, com.snipsnap.synth.Treatments.names.first(), 0.5f).recipe!!
        val smear = m.smearPad(1, 0.5f).recipe!!
        val keyed = m.keyedPad(2, "bodied", 0.5f).recipe!!
        val patch = PadRecipe(patch = ThumpPatch("Seed", ThumpVoice.KICK, mapOf("TUNE" to 0.2f))).toJsonValue()
        val robin = JsonValue.Obj(
            linkedMapOf<String, JsonValue>(
                "robin" to JsonValue.Obj(linkedMapOf<String, JsonValue>("takes" to JsonValue.Num(3.0), "seed" to JsonValue.Num(1.0))),
            ),
        )
        return listOf("era" to era, "character" to character, "smear" to smear, "keyed" to keyed, "patch" to patch, "robin x3" to robin)
    }

    @Test
    fun `the sheet-two doors return a valid pad or refuse by name - across every degenerate shape`() {
        val recipes = seedRecipes()
        val doors: List<Pair<String, (KitBuilderModel) -> Unit>> = recipes.map { (name, recipe) ->
            "replay $name" to { m: KitBuilderModel ->
                val done = RecipeReplay.apply(m, 1, recipe, "PAD")
                val wav = File(m.kitDir, done.pad.sampleFile)
                assertTrue(wav.isFile, "the replayed pad's file is there")
                val back = WavReader.read(wav)
                assertTrue(back.frameCount > 0 && back.samples.all { it.isFinite() }, "finite audio came back")
                assertTrue(done.toast.isNotBlank(), "a word for what happened")
            }
        } + ("stack the takes" to { m: KitBuilderModel ->
            m.treatPad(1, "reversed")
            val takes = m.priorTakes(1)
            assertTrue(takes.isNotEmpty(), "a treatment leaves a take in the bin")
            val soft = takes.take(StackTakes.MAX_SOFT)
            val stacked = m.stackTakes(1, soft)
            assertEquals(soft.size + 1, stacked.velocityLayers.size)
            m.save()
            assertEquals(stacked.velocityLayers, KitStore.load(m.kitDir).pad(1)!!.velocityLayers, "the stacked kit round-trips")
        })
        for ((fixtureName, snip) in fixtures()) {
            for ((doorName, door) in doors) {
                val dir = File(temp, "sheet2-${fixtureName.hashCode()}-${doorName.hashCode()}")
                val result = runCatching {
                    val m = KitBuilderModel.create("D", dir)
                    m.assign(1, snip, DrumClass.KICK)
                    door(m)
                }
                result.exceptionOrNull()?.let { e ->
                    if (e is AssertionError) throw e
                    assertTrue(
                        e is IllegalArgumentException,
                        "$doorName on $fixtureName threw ${e::class.simpleName}: ${e.message}",
                    )
                    assertTrue(!e.message.isNullOrBlank(), "$doorName on $fixtureName refused without saying why")
                }
            }
        }
    }

    @Test
    fun `the chart draws any valid clip - every row the same width, every off-grid hit footnoted once`() {
        // Mpc3Clip's own init is the type's fence (bars 1..64, notes inside
        // the loop, velocity 0..1, note 0..127); this is every shape inside
        // it: dense and empty, grid-tight and everywhere, hits on the exact
        // half-cell, hits at the loop's last pulses, notes with no pad.
        val rnd = Random(21)
        val cell = Mpc3Clip.PULSES_PER_16TH
        repeat(300) { round ->
            val bars = 1 + rnd.nextInt(64)
            val limit = bars * Mpc3Clip.PULSES_PER_BAR
            val count = if (round % 25 == 0) 0 else rnd.nextInt(1, 300)
            val notes = List(count) {
                val t = when (rnd.nextInt(4)) {
                    0 -> rnd.nextLong(limit / cell) * cell
                    1 -> rnd.nextLong(limit)
                    2 -> rnd.nextLong(limit / cell) * cell + cell / 2
                    else -> limit - 1 - rnd.nextInt(3)
                }.coerceIn(0L, limit - 1)
                Mpc3Note(rnd.nextInt(128), t, rnd.nextFloat(), 1L + rnd.nextInt(960))
            }
            val clip = Mpc3Clip("Fuzz $round", bars, notes)
            val program = if (rnd.nextBoolean()) "PROG A" else null
            val text = try {
                Chart.render(clip, kit, 92f, rnd.nextBoolean(), program)
            } catch (t: Throwable) {
                throw AssertionError("chart round $round ($bars bars, $count notes): threw ${t::class.simpleName}: ${t.message}", t)
            }
            assertEquals(Chart.render(clip, kit, 92f, false, program), Chart.render(clip, kit, 92f, false, program), "same clip, same chart")
            val summary = Chart.summary(clip)
            val lines = text.lines()
            assertEquals(summary.offGrid, lines.count { it.startsWith("OFF-GRID ") }, "round $round: one footnote per off-grid hit\n$text")
            if (count == 0) {
                assertTrue("NO HITS. AN EMPTY CHART IS STILL A CHART." in text, "round $round")
                return@repeat
            }
            val grid = lines.filter { '|' in it }
            for (line in grid) {
                val width = line.length - line.indexOf('|') - 1
                assertTrue(width % 17 == 0 && width / 17 in 1..Chart.BARS_PER_SYSTEM, "round $round: a grid line of the wrong width: '$line'")
            }
            val systems = (bars + Chart.BARS_PER_SYSTEM - 1) / Chart.BARS_PER_SYSTEM
            val rows = grid.count { "|1...2...3...4..." !in it }
            assertEquals(summary.pads * systems, rows, "round $round: one row per pad per system\n$text")
            val glyphs = grid.filter { "|1...2...3...4..." !in it }.sumOf { line -> line.substring(line.indexOf('|')).count { it in "Xxo<>" } }
            assertTrue(glyphs in 1..count, "round $round: $glyphs glyphs for $count notes")
        }
    }

    @Test
    fun `doubles group any index shape without inventing a match`() {
        fun entry(kit: String, slot: Int, file: String, vararg v: Float) = Crate.Entry(
            kitDir = kit, kitName = kit, slot = slot, padName = "p$slot", file = file, mtime = 0L, size = 0L,
            storedClass = DrumClass.KICK, heardClass = DrumClass.KICK, confidence = 1f, vector = v.toList(),
        )
        val rnd = Random(33)
        fun nine(seed: Float) = FloatArray(Similar.DIMENSIONS) { seed + it * 0.001f }
        val odd = listOf(
            entry("Odd", 1, "Odd/empty.wav"),
            entry("Odd", 2, "Odd/short.wav", 0.1f, 0.2f, 0.3f),
            entry("Odd", 3, "Odd/nan.wav", *FloatArray(Similar.DIMENSIONS) { Float.NaN }),
            entry("Odd", 4, "Odd/inf.wav", *FloatArray(Similar.DIMENSIONS) { Float.POSITIVE_INFINITY }),
            entry("Odd", 5, "Odd/neg.wav", *FloatArray(Similar.DIMENSIONS) { -1f }),
        )
        val twins = listOf(entry("A", 1, "A/k.wav", *nine(0.5f)), entry("B", 1, "B/k.wav", *nine(0.5f)), entry("B", 2, "B/k2.wav", *nine(0.5f)))
        val crowd = List(400) { i -> entry("K${i / 16}", i % 16 + 1, "K${i / 16}/p${i % 16}.wav", *FloatArray(Similar.DIMENSIONS) { rnd.nextFloat() }) }
        val indexes = listOf(
            "empty" to Crate.Index(emptyList(), 0, 0),
            "one" to Crate.Index(twins.take(1), 0, 1),
            "odd vectors only" to Crate.Index(odd, 0, odd.size),
            "twins among odd vectors" to Crate.Index(odd + twins, 0, odd.size + twins.size),
            "a crowd" to Crate.Index(crowd + twins + odd, 0, crowd.size),
        )
        for ((name, index) in indexes) {
            val start = System.nanoTime()
            val measured = Doubles.measure(index)
            for (within in Doubles.WITHIN_STEPS) {
                val clusters = try {
                    Doubles.clusters(measured, within)
                } catch (t: Throwable) {
                    throw AssertionError("doubles '$name' within $within: threw ${t::class.simpleName}: ${t.message}", t)
                }
                val seen = mutableSetOf<String>()
                for (c in clusters) {
                    assertTrue(c.entries.size >= 2, "'$name': a cluster of one")
                    assertTrue(c.within.isFinite() && c.within >= 0f, "'$name': a spread that is not a number: ${c.within}")
                    assertTrue(c.within <= within || c.entries.size > 2, "'$name': a pair wider than the ring")
                    for (e in c.entries) assertTrue(seen.add(e.file), "'$name': ${e.file} in two clusters")
                    for (e in c.entries) assertTrue(e !in odd, "'$name': ${e.file} has no measurement this code makes, and was called a double")
                    Doubles.headline(c)
                    c.entries.forEach { Doubles.memberLine(it) }
                }
                assertEquals(clusters.map { it.within }, clusters.map { it.within }.sorted(), "'$name': tightest first")
                if (twins.all { it in index.entries }) {
                    assertTrue(clusters.any { c -> twins.all { it in c.entries } }, "'$name' within $within: the planted twins are one cluster")
                }
            }
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue(ms < 5_000, "'$name': measuring and regrouping took ${ms}ms")
        }
    }
}
