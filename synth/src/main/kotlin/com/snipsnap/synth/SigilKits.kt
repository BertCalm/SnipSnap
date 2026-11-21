package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * SIGIL: eight drum kits written after the owner's 23 reference loops (`docs/SIGIL.md`), a kit family in the
 * [FacsimileKits] shape rather than a Patch engine. Every pad is a [Sigil] voice through its kit's print; the kits are
 * `prototype/sigil/kits.py` as the owner heard them, exported to `/sigil/kits.json` so the prototype and the app read
 * one table.
 *
 * The gesture: **a sub that drops an octave under you → a snare that lands as hard as the kick → a whisper of hats →
 * percussion that is half wood, glass and breath and half sine, FM and crushed grain, walking A minor over the top.**
 *
 * - [Kit.IRON] (74): sub-drop kicks, a dark snare, through a blown speaker.
 * - [Kit.NEON] (90): an 808-style sub, a crack snare, ratcheting hats, slammed clean.
 * - [Kit.ASH] (115): four on the floor, log drums in A minor, swung shakers, on tape.
 * - [Kit.KILN] (100): a heavy triplet shuffle, bongos and conga, low tines, through an old sampler.
 * - Each has a DRUMLESS twin (the owner's pick, 2026-11-21): the same percussion pads, no kick, snare or clap, the
 *   freed pads given more A-minor notes, and a percussion-only groove.
 *
 * Sixteen pads: fourteen hits, a roll ([ROLL]) and a two-bar groove ([GROOVE]). Every pad has three velocity zones
 * ([velocities]) of four takes. A zone is its own strike, darker and less driven when softer (the voice's own law,
 * [Sigil.touchTone] under it, and the print fed softer); a take is a fresh strike from its own seed. A groove's soft
 * zone plays every note softer ([grooveSoften]) and its takes rotate which strike each note deals.
 */
object SigilKits {
    /** Bump when a pad's audio changes; saved kits carry the version they were printed with. */
    const val VERSION = 1

    enum class Kit(val id: String, val jsonId: String, val title: String) {
        IRON("sigil-iron", "iron", "SIGIL IRON"),
        NEON("sigil-neon", "neon", "SIGIL NEON"),
        ASH("sigil-ash", "ash", "SIGIL ASH"),
        KILN("sigil-kiln", "kiln", "SIGIL KILN"),
        IRON_DRUMLESS("sigil-iron-drumless", "iron-drumless", "SIGIL IRON DRUMLESS"),
        NEON_DRUMLESS("sigil-neon-drumless", "neon-drumless", "SIGIL NEON DRUMLESS"),
        ASH_DRUMLESS("sigil-ash-drumless", "ash-drumless", "SIGIL ASH DRUMLESS"),
        KILN_DRUMLESS("sigil-kiln-drumless", "kiln-drumless", "SIGIL KILN DRUMLESS");

        val bpm: Int get() = book(this).bpm
        val drumless: Boolean get() = book(this).parent != null
    }

    val velocities = listOf(.40f, .70f, 1f)
    const val TAKES = 4
    const val HITS = 14
    const val ROLL = 14
    const val GROOVE = 15
    const val PADS = 16
    const val HAT_GROUP = 1
    const val PHRASE_GROUP = 2

    /** A groove's notes play at velocity x sqrt(zone): the soft zone's at .63 of the hard one's. */
    fun grooveSoften(zone: Float): Double = sqrt(zone.toDouble())

    internal class Pad(val name: String, val voice: String, val gain: Double, val params: Sigil.P, val raw: Map<String, Any>)

    internal class Roll(val name: String, val pad: Int, val altPad: Int?, val seconds: Double, val fromDiv: Double, val toDiv: Double,
               val velFrom: Double, val velTo: Double, val rise: Double?)

    internal class Book(val id: String, val title: String, val parent: String?, val bpm: Int, val swing: Double, val print: String,
               val printAmount: Double, val pads: List<Pad>, val roll: Roll, val bars: Int, val mix: Map<Int, Double>,
               val steps: Map<Int, String>)

    private fun plain(v: JsonValue): Any = when (v) {
        is JsonValue.Num -> v.num()
        is JsonValue.Str -> v.str()
        is JsonValue.Arr -> v.arr().map { plain(it) }
        else -> error("SIGIL kits.json: unexpected $v")
    }

    private val books: Map<String, Book> by lazy {
        val text = requireNotNull(SigilKits::class.java.getResourceAsStream("/sigil/kits.json")) { "Missing /sigil/kits.json" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
        Json.parse(text).obj().getValue("kits").arr().associate { kv ->
            val k = kv.obj()
            val pads = k.getValue("pads").arr().map { pv ->
                val p = pv.obj()
                val raw = p.getValue("params").obj().mapValues { (_, v) -> plain(v) }
                Pad(p.getValue("name").str(), p.getValue("voice").str(), p.getValue("gain").num(), Sigil.P(raw), raw)
            }
            val r = k.getValue("roll").obj()
            val g = k.getValue("groove").obj()
            val id = k.getValue("id").str()
            id to Book(
                id = id, title = k.getValue("title").str(), parent = (k["parent"] as? JsonValue.Str)?.str(),
                bpm = k.getValue("bpm").int(), swing = k.getValue("swing").num(), print = k.getValue("print").str(),
                printAmount = k.getValue("printAmount").num(), pads = pads,
                roll = Roll(r.getValue("name").str(), r.getValue("pad").int(), r["alt_pad"]?.int(), r.getValue("seconds").num(),
                    r.getValue("from_div").num(), r.getValue("to_div").num(), r.getValue("vel_from").num(),
                    r.getValue("vel_to").num(), r["rise"]?.num()),
                bars = g.getValue("bars").int(),
                mix = g.getValue("mix").obj().entries.associate { (i, v) -> i.toInt() to v.num() },
                steps = g.getValue("steps").obj().entries.associate { (i, v) -> i.toInt() to v.str() },
            )
        }
    }

    internal fun book(kit: Kit): Book = requireNotNull(books[kit.jsonId]) { "kits.json has no ${kit.jsonId}" }

    fun roles(kit: Kit): List<String> = book(kit).pads.map { it.name } + book(kit).roll.name + "Groove"

    fun classOf(kit: Kit, role: Int): DrumClass {
        if (role == ROLL || role == GROOVE) return DrumClass.LOOP
        val pad = book(kit).pads[role]
        return when (pad.voice) {
            "kick" -> DrumClass.KICK
            "snare" -> DrumClass.SNARE
            "clap", "snap" -> DrumClass.CLAP
            "hat" -> if (pad.params.d("decay") >= .1) DrumClass.HAT_OPEN else DrumClass.HAT_CLOSED
            "log_drum", "tine", "glass_bell", "rod" -> DrumClass.TONAL
            else -> DrumClass.PERC
        }
    }

    fun muteGroup(kit: Kit, role: Int): Int = when {
        role == ROLL || role == GROOVE -> PHRASE_GROUP
        classOf(kit, role) == DrumClass.HAT_CLOSED || classOf(kit, role) == DrumClass.HAT_OPEN -> HAT_GROUP
        else -> 0
    }

    /** The kit's sixteen pads, each with its whole grid. [renderer] is [render]; a test passes its cache of the same renders. */
    fun build(kit: Kit, renderer: (Kit, Int, Float, Int) -> Snip = ::render): List<ArrangedPad> {
        val names = roles(kit)
        return List(PADS) { role ->
            val grid = velocities.map { v -> List(TAKES) { t -> renderer(kit, role, v, t) } }
            ArrangedPad(
                grid.last().first(), classOf(kit, role), displayName = names[role], velocityTakes = grid,
                muteGroup = muteGroup(kit, role),
                source = mapOf(
                    "engine" to "SIGIL", "kitVersion" to VERSION.toString(), "kit" to kit.name, "role" to role.toString(),
                    "bpm" to kit.bpm.toString(), "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
                    "articulation" to when (role) { ROLL -> "roll"; GROOVE -> "loop"; else -> "finite" },
                    "voice" to if (role < HITS) book(kit).pads[role].voice else names[role],
                ),
            )
        }
    }

    /** The finished pad at one velocity zone and take. Pure: the same arguments give the same bytes. */
    fun render(kit: Kit, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        val x = when (role) {
            ROLL -> roll(kit, velocity, take)
            GROOVE -> groove(kit, velocity, take)
            else -> cachedHit(kit, role, zoneIndex(velocity.toDouble()), take)
        }
        return Snip(x.copyOf(), 1, Dsp.RATE)
    }

    /**
     * One kit's hits, kept while that kit is being built: its twelve groove renders deal the same 168 strikes the pads
     * just made, so they are made once (about 5 MB a kit). Asking for another kit drops the last one's.
     */
    private var cacheKit: Kit? = null
    private val hitCache = HashMap<Triple<Int, Int, Int>, FloatArray>()

    @Synchronized private fun cachedHit(kit: Kit, role: Int, zone: Int, take: Int): FloatArray {
        if (cacheKit != kit) { hitCache.clear(); cacheKit = kit }
        return hitCache.getOrPut(Triple(role, zone, take)) { hit(kit, role, velocities[zone].toDouble(), take) }
    }

    /** The house touch law (CypherKits.touch): a soft zone keeps .35 of the level, so velocity is character first. */
    fun touch(v: Double): Double = .35 + .65 * v

    private fun zoneIndex(v: Double): Int = velocities.indices.minBy { abs(velocities[it] - v) }

    /** One hit: the voice, the touch tone, the kit's print fed at the hit's own strength, the output stage, levelled. */
    fun hit(kit: Kit, role: Int, v: Double, take: Int): FloatArray {
        val b = book(kit)
        val pad = b.pads[role]
        val rng = Sigil.rngFor(kit.jsonId, role, zoneIndex(v), take)
        val raw = Sigil.touchTone(Sigil.VOICES.getValue(pad.voice)(pad.params, v, rng), v)
        val printed = Sigil.print(b.print, Sigil.norm(raw, .9 * touch(v)), pad.params.d("print_amount", b.printAmount))
        return levelled(Sigil.finish(printed, pad.params.len), pad.gain * touch(v))
    }

    private fun levelled(x: FloatArray, peak: Double): FloatArray {
        var m = 0f
        for (s in x) m = max(m, abs(s))
        val k = if (m > 1e-9f) (peak / m).toFloat() else 0f
        return FloatArray(x.size) { x[it] * k }
    }

    /** A15: strokes accelerating from `from_div` to `to_div` per beat, each a fresh strike, louder as it goes. */
    fun roll(kit: Kit, zone: Float, take: Int): FloatArray {
        val b = book(kit)
        val r = b.roll
        val v = zone.toDouble()
        val beat = 60.0 / b.bpm
        val total = Sigil.nOf(r.seconds + 0.8)
        val out = DoubleArray(total)
        var t = 0.0
        var k = 0
        while (t < r.seconds) {
            val prog = t / r.seconds
            val div = r.fromDiv * Math.pow(r.toDiv / r.fromDiv, prog)
            val vel = (r.velFrom + (r.velTo - r.velFrom) * prog) * v
            val idx = if (r.altPad == null || k % 2 == 0) r.pad else r.altPad
            val pad = b.pads[idx]
            val params = if (r.rise != null) pad.params.with("ratio", pad.params.d("ratio") * (1 + r.rise * prog)) else pad.params
            val sv = max(vel, .05)
            val h = Sigil.touchTone(Sigil.VOICES.getValue(pad.voice)(params, sv, Sigil.rngFor(kit.jsonId, "roll", zoneIndex(v), take, k)), sv)
            val at = Sigil.nOf(t)
            val g = .35 + .65 * vel
            for (i in 0 until min(h.size, total - at)) out[at + i] += h[i] * g
            t += beat / div
            k++
        }
        val printed = Sigil.print(b.print, Sigil.norm(out, .9 * touch(v)), b.printAmount)
        return levelled(Sigil.finish(printed), b.pads[r.pad].gain * touch(v))
    }

    /** A groove note: when (seconds), which pad, how hard (a zone velocity before softening). */
    class Note(val seconds: Double, val pad: Int, val velocity: Double)

    /** The written steps: X 1.0, x .7, o .4, r a 32nd ratchet, t a triplet ratchet; swing on the off sixteenths, 2 ms jitter. */
    fun grooveNotes(kit: Kit): List<Note> {
        val b = book(kit)
        val s16 = 60.0 / b.bpm / 4
        val swingDelay = s16 * (2 * b.swing / 100 - 1)
        val rng = Sigil.rngFor(kit.jsonId, "groove-jitter")
        val notes = ArrayList<Note>()
        for ((idx, line) in b.steps.entries.sortedBy { it.key }) {
            for ((i, ch) in line.withIndex()) {
                if (ch == '.') continue
                var t0 = i * s16 + if (i % 2 == 1) swingDelay else 0.0
                val pad = b.pads[idx]
                if (pad.voice == "shaker") t0 -= Sigil.shakerLead(pad.params)
                when (ch) {
                    'r', 't' -> {
                        val n = if (ch == 'r') 2 else 3
                        for (j in 0 until n) notes += Note(t0 + j * s16 / n, idx, doubleArrayOf(.7, .4, .7)[j])
                    }
                    else -> notes += Note(t0 + rng.normal(0.0, 0.002), idx, when (ch) { 'X' -> 1.0; 'x' -> .7; 'o' -> .4; else -> error("step '$ch'") })
                }
            }
        }
        return notes.sortedWith(compareBy({ it.seconds }, { it.pad }))
    }

    fun grooveSeconds(kit: Kit): Double = book(kit).bars * 4 * 60.0 / book(kit).bpm + 1.2

    /**
     * A16: the written groove played on the kit's own pads. Each note's velocity is softened by the zone and snapped to
     * the nearest zone; pad i's n-th note deals take (n + [take]) mod 4. Then the kit's bus: a lighter blown speaker,
     * the slam, tape, or the sampler, with the tape's or the record's motion after it.
     */
    fun groove(kit: Kit, zone: Float, take: Int): FloatArray {
        val b = book(kit)
        val seconds = grooveSeconds(kit)
        val n = (seconds * Dsp.RATE).toInt()
        val out = DoubleArray(n)
        val turn = HashMap<Int, Int>()
        val cache = HashMap<Triple<Int, Int, Int>, FloatArray>()
        val soften = grooveSoften(zone)
        for (note in grooveNotes(kit)) {
            val z = zoneIndex(note.velocity * soften)
            val dealt = turn.getOrDefault(note.pad, 0)
            turn[note.pad] = dealt + 1
            val t = (dealt + take) % TAKES
            val h = cache.getOrPut(Triple(note.pad, z, t)) { cachedHit(kit, note.pad, z, t) }
            val g = b.mix[note.pad] ?: .5
            val at = max(0, (note.seconds * Dsp.RATE).toInt())
            for (i in 0 until min(h.size, n - at)) out[at + i] += h[i] * g
        }
        val up = CalabashDsp.upsample4(out, n * 4)
        val bused = when (b.print) {
            "blown" -> Sigil.blownSpeaker(up, .25)
            "slam" -> Sigil.slam(up, .45)
            "sampler" -> Sigil.sampler(up, .3)
            else -> Sigil.tape(up, .3)
        }
        var y = Sigil.finish(bused, seconds)
        val seed = Dsp.seedFor("SIGIL", kit.jsonId, "motion", take)
        if (b.print == "tape") y = Sigil.tapeMotion(y, seed)
        else if (b.print == "sampler") y = Sigil.tapeMotion(y, seed, depth = .6, cracklePerS = 40.0)
        return levelled(y, .95 * touch(zone.toDouble()))
    }
}
