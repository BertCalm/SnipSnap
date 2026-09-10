package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Similar
import com.snipsnap.audio.Features
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ChainInfo
import com.snipsnap.kit.ChainZone
import com.snipsnap.kit.GrooveFeel
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.audio.KeySpec
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitLayer
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.PocketStore
import com.snipsnap.audio.Scale
import com.snipsnap.kit.WearLedger
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.synth.FxChain
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.ThumpPatch
import com.snipsnap.synth.ThumpVoice
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Hardening, round three: every JSON sidecar the phone writes — the
 * kit, the grooves, the instrument, the pocket, the teach log, the pad
 * recipe — read back under two kinds of damage:
 *
 *  - **byte-level** (a flipped run, a truncation, a zero run, a 0xFF run):
 *    the torn-file shape a killed process or a bad SD card leaves;
 *  - **structural** (a random node swapped for null, a string, a huge or
 *    negative or fractional number, an empty array, an empty object, a
 *    boolean; or a key dropped): the shape a newer build, another tool,
 *    or a hand edit leaves — still valid JSON, wrong for the reader.
 *
 * The contract is the same as [com.snipsnap.kit.FuzzTest]'s: a valid
 * parse or a typed refusal — [IllegalArgumentException], [java.io.IOException],
 * [JsonException]. A `ClassCastException`, an NPE or a `StackOverflow` is
 * a reader trusting a byte it has not checked. The teach log is stricter
 * still: it never throws at all, because one torn line must not lose the
 * training set.
 *
 * Deterministic: a fixed seed per reader, so a failure reproduces exactly.
 */
class SidecarFuzzTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("sidecar-fuzz").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun isTypedRefusal(t: Throwable): Boolean = when (t) {
        is IllegalArgumentException,
        is java.io.IOException,
        is JsonException,
        -> true
        else -> false
    }

    private val ROUNDS = 1_500

    /**
     * Half the rounds tear bytes, half rewrite the tree; both halves must
     * end in a valid parse or a typed refusal, inside the hang bound.
     */
    private fun fuzz(name: String, valid: String, seed: Int, read: (String) -> Unit) {
        val rnd = Random(seed)
        val tree = Json.parse(valid)
        val start = System.nanoTime()
        for (i in 0 until ROUNDS) {
            val (kind, mutant) = if (i % 2 == 0) {
                "bytes" to String(mutateBytes(valid.toByteArray(Charsets.UTF_8), rnd), Charsets.UTF_8)
            } else {
                "tree" to Json.write(mutateTree(tree, rnd))
            }
            try {
                read(mutant)
            } catch (t: Throwable) {
                if (!isTypedRefusal(t)) {
                    fail("$name round $i ($kind): untyped ${t::class.simpleName}: ${t.message}\n$mutant")
                }
            }
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 30_000, "$name: $ROUNDS rounds took ${ms}ms - a reader may be hanging")
    }

    private fun mutateBytes(valid: ByteArray, rnd: Random): ByteArray {
        val bytes = valid.copyOf()
        when (rnd.nextInt(4)) {
            0 -> repeat(1 + rnd.nextInt(8)) {
                if (bytes.isNotEmpty()) bytes[rnd.nextInt(bytes.size)] = rnd.nextInt(256).toByte()
            }
            1 -> return bytes.copyOf(if (bytes.isEmpty()) 0 else rnd.nextInt(bytes.size))
            2 -> if (bytes.isNotEmpty()) {
                val at = rnd.nextInt(bytes.size)
                for (j in at until minOf(bytes.size, at + rnd.nextInt(32))) bytes[j] = 0
            }
            3 -> if (bytes.isNotEmpty()) {
                val at = rnd.nextInt(bytes.size)
                for (j in at until minOf(bytes.size, at + 4)) bytes[j] = 0xFF.toByte()
            }
        }
        return bytes
    }

    /** The replacement values a structural mutation swaps in. */
    private fun junk(rnd: Random): JsonValue = when (rnd.nextInt(10)) {
        0 -> JsonValue.Null
        1 -> JsonValue.Str("")
        2 -> JsonValue.Str("../../escape")
        3 -> JsonValue.Num(-1.0)
        4 -> JsonValue.Num(1e300)
        5 -> JsonValue.Num(2147483648.0) // Int.MAX_VALUE + 1: an int() that must refuse, not wrap
        6 -> JsonValue.Num(0.5)
        7 -> JsonValue.Arr(emptyList())
        8 -> JsonValue.Obj(emptyMap())
        else -> JsonValue.Bool(rnd.nextBoolean())
    }

    /**
     * Walk to a random node and replace it, or drop a random key from a
     * random object. Every path through the tree is reachable so the
     * deeper readers (notes inside clips, zones inside chains) get hit too.
     */
    private fun mutateTree(v: JsonValue, rnd: Random): JsonValue {
        val paths = mutableListOf<List<Any>>()
        fun walk(node: JsonValue, path: List<Any>) {
            paths += path
            when (node) {
                is JsonValue.Obj -> node.entries.forEach { (k, c) -> walk(c, path + k) }
                is JsonValue.Arr -> node.items.forEachIndexed { i, c -> walk(c, path + i) }
                else -> Unit
            }
        }
        walk(v, emptyList())
        val target = paths[rnd.nextInt(paths.size)]
        val drop = target.isNotEmpty() && target.last() is String && rnd.nextInt(3) == 0
        fun rebuild(node: JsonValue, depth: Int): JsonValue {
            if (depth == target.size) return junk(rnd)
            val step = target[depth]
            return when (node) {
                is JsonValue.Obj -> {
                    val key = step as String
                    if (drop && depth == target.size - 1) {
                        JsonValue.Obj(node.entries.filterKeys { it != key })
                    } else {
                        JsonValue.Obj(node.entries.mapValues { (k, c) -> if (k == key) rebuild(c, depth + 1) else c })
                    }
                }
                is JsonValue.Arr -> {
                    val idx = step as Int
                    JsonValue.Arr(node.items.mapIndexed { i, c -> if (i == idx) rebuild(c, depth + 1) else c })
                }
                else -> node
            }
        }
        return rebuild(v, 0)
    }

    // ---- the seeds: one rich, valid file per reader ------------------------

    private fun kitJson(): String {
        val dir = File(temp, "seed-kit")
        val pads = listOf(
            KitPad(
                slot = 1, sampleFile = "kick.wav", drumClass = DrumClass.KICK, colorHex = "#FF0000",
                attack = 0.01f, decay = 0.5f, cutoff = 0.8f, resonance = 0.2f, humanize = 0.1f,
                source = mapOf("from" to "tape", "at" to "0"),
                recipe = PadRecipe(
                    patch = ThumpPatch("Seed", ThumpVoice.KICK, mapOf("TUNE" to 0.2f)),
                    fx = FxChain(eq = mapOf("BASS" to 0.5f), echo = mapOf("TIME" to 0.3f)),
                    treatment = "crushed", amount = 0.5f,
                ).toJsonValue(),
            ),
            KitPad(
                slot = 2, sampleFile = "snare.wav",
                velocityLayers = listOf(KitLayer("snare-soft.wav", 0, 63), KitLayer("snare.wav", 64, 127)),
                level = 0.9f, pan = 0.4f, tuneCoarse = -2, tuneFine = 10, muteGroup = 1, oneShot = false,
            ),
            KitPad(
                slot = 3, sampleFile = "loop.wav",
                chain = ChainInfo(
                    boundaries = listOf(0L, 1_000L, 2_000L, 3_000L), cycle = 2,
                    zones = listOf(ChainZone(0, 63, 0, 2), ChainZone(64, 127, 1, 2)),
                ),
            ),
        )
        val kit = Kit("Seed", pads, KeySpec(2, Scale.MINOR), tempoBpm = 92f, wear = WearLedger(mileage = 3.0))
        return KitStore.save(kit, dir).readText()
    }

    private fun grooveJson(): String {
        val dir = File(temp, "seed-groove")
        val clips = listOf(
            Mpc3Clip("Chorus", 2, listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(38, 480, 0.7f, 120))),
            Mpc3Clip("Verse", 1, listOf(Mpc3Note(42, 240, 0.4f))),
        )
        return GrooveStore.save(dir, clips).readText()
    }

    private fun instrumentJson(): String {
        val dir = File(temp, "seed-instrument")
        val program = KeygroupProgram(
            "Seed",
            listOf(
                Keygroup(36, 47, 40, listOf(VelocityLayer("lo", 4_000, 0, 127, 1_000))),
                Keygroup(48, 59, 52, listOf(VelocityLayer("hi", 3_000, 0, 127))),
            ),
        )
        InstrumentStore.write(dir, "Seed", program, "Seed")
        return InstrumentStore.sidecar(dir, "Seed").readText()
    }

    private fun pocketJson(): String {
        val f = File(temp, "seed.pocket")
        val offsets = List<Long?>(GrooveFeel.POSITIONS) { i -> if (i % 4 == 3) null else (i - 8).toLong() }
        val accents = List<Float?>(GrooveFeel.POSITIONS) { i -> if (i % 4 == 3) null else 0.5f + i / 32f }
        PocketStore.save(PocketStore.Pocket("Seed", GrooveFeel.Template(offsets, accents)), f)
        return f.readText()
    }

    private fun teachLine(): String = TeachLog.toJsonl(
        listOf(
            TeachLog.Example(
                label = DrumClass.SNARE,
                machineSaid = DrumClass.CLAP,
                features = Features(
                    centroidHz = 1800f, rolloffHz = 5000f, flatness = 0.3f, zeroCrossingRate = 0.1f,
                    lowRatio = 0.2f, midRatio = 0.5f, highRatio = 0.3f, durationSeconds = 0.4f,
                    decayMs = 120f, peak = 0.8f, attackBursts = 1,
                ),
            ),
        ),
    ).trim()

    private fun recipeJson(): String = PadRecipe(
        patch = ThumpPatch("Seed", ThumpVoice.KICK, mapOf("TUNE" to 0.2f, "DRIVE" to 0.8f)),
        fx = FxChain(reverse = true, eq = mapOf("BASS" to 0.5f), echo = mapOf("TIME" to 0.3f), spring = mapOf("MIX" to 0.2f)),
        treatment = "crushed",
        amount = 0.5f,
    ).toJsonText()

    // ---- the doors -----------------------------------------------------------

    @Test
    fun `kit json survives mutation`() {
        val f = File(temp, "kit.json")
        fuzz("KitStore.read", kitJson(), seed = 31) { text ->
            f.writeText(text)
            for (b in KitStore.read(f).pads.mapNotNull { it.chain }.flatMap { it.boundaries }) {
                assertTrue(b != Long.MAX_VALUE, "a clamped chain boundary read as valid")
            }
        }
    }

    @Test
    fun `groove json survives mutation`() {
        val dir = File(temp, "groove").also { it.mkdirs() }
        val f = File(dir, GrooveStore.FILE_NAME)
        fuzz("GrooveStore.load", grooveJson(), seed = 32) { text ->
            f.writeText(text)
            // A number past 2^63 must refuse, never clamp to Long.MAX_VALUE and
            // hand the sequencer a note at the end of time.
            for (n in GrooveStore.load(dir).flatMap { it.notes }) {
                assertTrue(n.timePulses != Long.MAX_VALUE && n.lengthPulses != Long.MAX_VALUE, "a clamped pulse count read as valid")
            }
        }
    }

    @Test
    fun `instrument sidecar survives mutation`() {
        val f = File(temp, "fuzz${InstrumentStore.SUFFIX}")
        fuzz("InstrumentStore.read", instrumentJson(), seed = 33) { text ->
            f.writeText(text)
            InstrumentStore.read(f)
        }
    }

    @Test
    fun `pocket survives mutation`() {
        val f = File(temp, "fuzz.${PocketStore.EXTENSION}")
        fuzz("PocketStore.read", pocketJson(), seed = 34) { text ->
            f.writeText(text)
            PocketStore.read(f)
        }
    }

    @Test
    fun `teach log never throws at all`() {
        // Stricter than the others: a torn line is skipped, never fatal, so
        // the harness fails on *any* throwable here.
        val valid = teachLine()
        val rnd = Random(35)
        val tree = Json.parse(valid)
        for (i in 0 until ROUNDS) {
            val mutant = if (i % 2 == 0) {
                String(mutateBytes(valid.toByteArray(Charsets.UTF_8), rnd), Charsets.UTF_8)
            } else {
                Json.write(mutateTree(tree, rnd)).replace("\n", "")
            }
            try {
                TeachLog.fromJsonl(valid + "\n" + mutant + "\n" + valid)
            } catch (t: Throwable) {
                fail("TeachLog.fromJsonl round $i: threw ${t::class.simpleName}: ${t.message}\n$mutant")
            }
        }
    }

    @Test
    fun `pad recipe survives mutation`() {
        fuzz("PadRecipe.fromJsonText", recipeJson(), seed = 36) { PadRecipe.fromJsonText(it) }
    }

    @Test
    fun `fx chain survives mutation`() {
        val fx = Json.write(Json.parse(recipeJson()).obj().getValue("fx"))
        fuzz("FxChain.fromJsonText", fx, seed = 37) { FxChain.fromJsonText(it) }
    }

    @Test
    fun `the pad sheets read any recipe without throwing`() {
        // The sheet readers are the phone's own defensive walk over a pad's
        // recipe: a shape they do not know is "nothing applied", never a
        // crash on the KIT screen. So the bar here is no throwable at all.
        val valid = Json.parse(
            """
            {"recipe":1,"era":"1993","amount":0.5,"treatment":"crushed","keyed":"in key",
             "mutate":{"mode":"morph","with":["Kit:A03","Other:B02"],"drift":true,
                       "outside":{"move":"hop","lagMs":23,"confidence":0.87,"inverted":true}},
             "outside":{"move":"lean","lagMs":12,"confidence":0.4,"inverted":false}}
            """.trimIndent(),
        )
        val rnd = Random(38)
        for (i in 0 until ROUNDS) {
            val mutant = mutateTree(valid, rnd)
            val recipe = mutant as? JsonValue.Obj
            try {
                PadSheet.read(recipe)
                MutateSheet.read(recipe)
                OutsideSheet.read(recipe)
            } catch (t: Throwable) {
                fail("sheet readers round $i: threw ${t::class.simpleName}: ${t.message}\n${Json.write(mutant)}")
            }
        }
    }

    @Test
    fun `the replay planner and the diff read any recipe without throwing`() {
        // DO IT AGAIN plans from a recipe before any byte moves, and the
        // takes diff names recipes on the TAKES screen: both are the
        // phone's defensive walk over a pad's recipe, so, like the pad
        // sheets, the bar is no throwable at all - a Plan (Refused counts)
        // and a word, whatever the tree says. Seeds are the doors' own
        // recipes plus one of each refusal shape, fuzzed separately so a
        // dropped key exercises every branch, not only the first refusal.
        val m = KitBuilderModel.create("Replay", File(temp, "replay-seed"))
        m.assign(1, DrumSynth.snare(), DrumClass.SNARE)
        val era = m.eraPad(1, com.snipsnap.synth.Eras.names.first(), 0.5f).recipe!!
        val character = m.characterPad(1, com.snipsnap.synth.Treatments.names.first(), 0.5f).recipe!!
        val smear = m.smearPad(1, 0.4f).recipe!!
        val keyedExtras = """{"keyed":"bodied","amount":0.6,"seed":7,"decay":0.8,"division":"1/8","tail":1.5,"knee":0.02,"key":"A minor"}"""
        val robinAndCo = """{"robin":{"takes":3,"seed":2,"zones":2},"splice":{"head":"a.wav"},"clean":{"declip":true},"doctor":"tight","sculpt":{"mode":"carve"}}"""
        val seeds = listOf(
            "era" to Json.write(era), "character" to Json.write(character), "smear" to Json.write(smear),
            "patch" to recipeJson(), "keyed" to keyedExtras, "robin and the measured ones" to robinAndCo,
        )
        val untouched = KitPad(slot = 1, sampleFile = "a.wav", recipe = era)
        for ((i, seed) in seeds.withIndex()) {
            val tree = Json.parse(seed.second)
            val rnd = Random(60 + i)
            for (round in 0 until ROUNDS / seeds.size) {
                val mutant = mutateTree(tree, rnd)
                val recipe = mutant as? JsonValue.Obj
                try {
                    RecipeReplay.plan(recipe)
                    if (recipe != null) {
                        KitDiff.recipeName(recipe)
                        RecipeReplay.clip(recipe, "K", 1)
                        val changed = KitPad(slot = 1, sampleFile = "a.wav", recipe = recipe)
                        KitDiff.headline(KitDiff.changes(Kit("A", listOf(untouched)), Kit("A", listOf(changed))))
                        KitDiff.headline(KitDiff.changes(Kit("A", listOf(changed)), Kit("A", listOf(untouched))))
                    }
                } catch (t: Throwable) {
                    fail("replay/diff seed '${seed.first}' round $round: threw ${t::class.simpleName}: ${t.message}\n${Json.write(mutant)}")
                }
            }
        }
    }

    @Test
    fun `the crate index survives mutation and never hands back a vector it could not have written`() {
        // The crate index is a cache, so a torn one may simply rebuild -
        // but a structurally valid, wrong one (a hand edit, another tool)
        // must not be believed either: every vector that comes back is
        // one the extractor could have written, or the pad was measured
        // again. DOUBLES and the roulette both read this.
        val root = File(temp, "crate-fuzz").apply { mkdirs() }
        val m = KitBuilderModel.create("One", File(root, "One"))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save()
        val indexFile = File(root, Crate.INDEX_NAME)
        Crate.index(root)
        val valid = indexFile.readText()
        val tree = Json.parse(valid)
        val rnd = Random(53)
        val start = System.nanoTime()
        for (i in 0 until ROUNDS / 5) {
            val (kind, mutant) = if (i % 2 == 0) {
                "bytes" to String(mutateBytes(valid.toByteArray(Charsets.UTF_8), rnd), Charsets.UTF_8)
            } else {
                "tree" to Json.write(mutateTree(tree, rnd))
            }
            indexFile.writeText(mutant)
            val idx = try {
                Crate.index(root)
            } catch (t: Throwable) {
                fail("crate index round $i ($kind): threw ${t::class.simpleName}: ${t.message}\n$mutant")
            }
            assertEquals(2, idx.entries.size, "round $i ($kind): both pads are in the index")
            for (e in idx.entries) {
                assertTrue(
                    e.vector.size == Similar.DIMENSIONS && e.vector.all { it in 0f..1f },
                    "round $i ($kind): an implausible vector was believed: ${e.vector}\n$mutant",
                )
            }
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 60_000, "crate index: ${ROUNDS / 5} rounds took ${ms}ms")
    }

    @Test
    fun `the label survives mutation`() {
        val root = File(temp, "label").also { it.mkdirs() }
        Label.init(root, "Seed Label", "SL")
        val f = File(root, Label.FILE_NAME)
        val valid = f.readText()
        fuzz("Label.load", valid, seed = 39) { text ->
            f.writeText(text)
            Label.load(root)
        }
    }

    @Test
    fun `the surface settings survive mutation`() {
        val dir = File(temp, "surface").also { it.mkdirs() }
        SurfaceStore.save(dir, SurfaceStore.Settings(padSlot = 3))
        val f = File(dir, SurfaceStore.FILE_NAME)
        val valid = f.readText()
        fuzz("SurfaceStore.load", valid, seed = 40) { text ->
            f.writeText(text)
            SurfaceStore.load(dir)
        }
    }

    @Test
    fun `the parser refuses a nesting attack in words`() {
        // Deep enough to blow a default JVM stack if the parser recursed
        // unguarded; the depth ceiling must turn it into a JsonException.
        for (depth in listOf(66, 1_000, 100_000)) {
            val open = "[".repeat(depth)
            val close = "]".repeat(depth)
            for (text in listOf(open + close, open, "{\"a\":".repeat(depth) + "1" + "}".repeat(depth))) {
                try {
                    Json.parse(text)
                    fail("depth $depth parsed")
                } catch (e: JsonException) {
                    // named refusal
                }
            }
        }
    }
}
