package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.kit.ArrangedPad
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * FACSIMILE: four drum kits replicated, not sampled, from nine reference loops (`docs/FACSIMILE.md`). A kit family in
 * the [LooseCrateKits] shape, not a Patch engine: every hit is a [Facsimile] strike rendered from a fitted model, and
 * every groove re-performs a reference loop with those strikes, hit for hit and as hard as each hit was.
 *
 * The family's gesture: **a drum is struck → its pitch falls or holds and its noise decays band by band as the
 * reference's did → the groove lands each hit where the reference did, on the groove's own average timing per place
 * in the bar, and prints it through the bus compressor fitted to the reference.**
 *
 * - [Kit.SHUFFLE] (90): heavy-swing boom bap from the Alley and Swing loops.
 * - [Kit.KNOCK] (113): the knocker's whole kick and roomy snare, with the Fusion loop's kit.
 * - [Kit.HALF_TIME] (127): punchy kicks, 808s and claps from the Doves and Mover loops.
 * - [Kit.TEXTURE] (134): pitched toms, 808 notes, the World loop's hat and tambourine, and three grooves.
 *
 * The layout (pad names, classes, layers and grooves) is the prototype's `kits.json`, as the owner heard it through
 * rounds 0 to 0m. Every pad has three velocity zones of four takes. A zone is its own strike ([Facsimile.render]: a
 * softer strike is darker, shorter and falls less far in pitch), and a take is a fresh strike. A groove's soft zone
 * plays every note softer ([GROOVE_SOFTEN]), so its hits change character too, not only level; its takes rotate which
 * of the four strikes each note deals.
 *
 * Chokes: hats cut hats ([HAT_GROUP]); grooves cut grooves ([PHRASE_GROUP]). Inside a groove the loop's own choke
 * rules hold (an 808 cut by its next note, `kits.json` "choke", and the models' self-choke).
 */
object FacsimileKits {
    /** Bump when a pad's audio changes; saved kits carry the version they were printed with. */
    const val VERSION = 1

    enum class Kit(val id: String, val title: String, val jsonName: String) {
        SHUFFLE("facsimile-shuffle", "Shuffle", "FACSIMILE SHUFFLE"),
        KNOCK("facsimile-knock", "Knock", "FACSIMILE KNOCK"),
        HALF_TIME("facsimile-half-time", "Half-Time", "FACSIMILE HALF-TIME"),
        TEXTURE("facsimile-texture", "Texture", "FACSIMILE TEXTURE");

        val bpm: Int get() = layout(this).tempo
    }

    val velocities = listOf(.30f, .65f, 1f)
    const val TAKES = Facsimile.TAKES
    const val PADS = 16
    const val HAT_GROUP = 1
    const val PHRASE_GROUP = 2

    /** Where every zone's first take peaks, as CYPHER's and LOOSE CRATE's do. */
    const val PAD_PEAK = .92f

    /** A groove zone's notes play at velocity x sqrt(zone): the soft zone's at .55 of the hard one's, the middle's at .81. */
    fun grooveSoften(zone: Float): Float = sqrt(zone)

    /** For the KDoc: the soft zone's note scale. */
    val GROOVE_SOFTEN: Float get() = grooveSoften(velocities.first())

    /** One pad as `kits.json` lays it out: a stack of fitted drums ([layers], struck at [velocity] of the zone's) or a groove ([phrase], a loop key). */
    class Pad(val name: String, val cls: DrumClass, val layers: List<String>, val phrase: String?, val velocity: Float)

    class Layout(val name: String, val tempo: Int, val references: List<String>, val character: String, val pads: List<Pad>)

    /** A loop's fitted bus compressor (`fit_glue.py`), or none where the reference was no tighter than the raw replica. */
    class Glue(val thresholdDb: Double, val ratio: Double, val attackMs: Double, val makeupDb: Double)

    private class Book(val kits: Map<String, Layout>, val choke: Map<String, Set<String>>, val glue: Map<String, Glue>)

    private val book: Book by lazy {
        val root = Json.parse(Facsimile.resource("kits.json")).obj()
        val kits = root.getValue("kits").arr().map { kv ->
            val k = kv.obj()
            Layout(
                name = k.getValue("name").str(),
                tempo = k.getValue("tempo").int(),
                references = k.getValue("references").arr().map { it.str() },
                character = k.getValue("character").str(),
                pads = k.getValue("pads").arr().map { pv ->
                    val p = pv.obj()
                    Pad(
                        name = p.getValue("name").str(),
                        cls = DrumClass.valueOf(p.getValue("class").str()),
                        layers = p["layers"]?.arr()?.map { it.str() }.orEmpty(),
                        phrase = p["phrase"]?.str(),
                        velocity = p["velocity"]?.num()?.toFloat() ?: 1f,
                    )
                },
            )
        }.associateBy { it.name }
        val choke = root.getValue("choke").obj().mapValues { (_, v) -> v.arr().map { it.str() }.toSet() }
        val glue = root.getValue("glue").obj().mapValues { (_, v) ->
            val g = v.obj()
            Glue(g.getValue("threshold_db").num(), g.getValue("ratio").num(), g.getValue("attack_ms").num(), g.getValue("makeup_db").num())
        }
        Book(kits, choke, glue)
    }

    fun layout(kit: Kit): Layout = requireNotNull(book.kits[kit.jsonName]) { "kits.json has no ${kit.jsonName}" }

    fun roles(kit: Kit): List<String> = layout(kit).pads.map { it.name }

    fun classOf(kit: Kit, role: Int): DrumClass = layout(kit).pads[role].cls

    fun isGroove(kit: Kit, role: Int): Boolean = layout(kit).pads[role].phrase != null

    fun muteGroup(kit: Kit, role: Int): Int = when {
        isGroove(kit, role) -> PHRASE_GROUP
        classOf(kit, role) == DrumClass.HAT_CLOSED || classOf(kit, role) == DrumClass.HAT_OPEN -> HAT_GROUP
        else -> 0
    }

    /** A groove's length in frames: its reference loop's, bar-exact at the reference's tempo. */
    fun grooveFrames(key: String): Int = Math.round(Facsimile.loop(key).seconds * Dsp.RATE).toInt()

    /** The kit's sixteen pads, each with its whole grid. [renderer] is [render]; a test passes its cache of the same renders. */
    fun build(kit: Kit, renderer: (Kit, Int, Float, Int) -> Snip = ::render): List<ArrangedPad> {
        val pads = layout(kit).pads
        require(pads.size == PADS) { "${kit.jsonName}: ${pads.size} pads" }
        return List(PADS) { role ->
            val grid = velocities.map { v -> List(TAKES) { t -> renderer(kit, role, v, t) } }
            ArrangedPad(
                grid.last().first(), classOf(kit, role), displayName = pads[role].name, velocityTakes = grid,
                muteGroup = muteGroup(kit, role),
                source = mapOf(
                    "engine" to "FACSIMILE", "kitVersion" to VERSION.toString(), "assetVersion" to Facsimile.ASSET_VERSION.toString(),
                    "kit" to kit.name, "role" to role.toString(), "bpm" to kit.bpm.toString(),
                    "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
                    "articulation" to if (isGroove(kit, role)) "loop" else "finite",
                    "layers" to (pads[role].phrase ?: pads[role].layers.joinToString("+")),
                ),
            )
        }
    }

    /** The finished pad at one velocity zone and take. Pure: the same arguments give the same bytes. */
    fun render(kit: Kit, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        val x = raw(kit, role, velocity, take)
        // the first take sets its zone's gain from itself: a groove pass is the costly part, so it is made once
        val gain = if (take == 0) gains.getOrPut(Triple(kit, role, velocity)) { gainFor(kit, role, x) } else zoneGain(kit, role, velocity)
        return if (isGroove(kit, role)) looped(x, gain) else KitStrikes.finish(x, gain)
    }

    /** The pad before levelling: a stack of strikes, or a groove printed through its loop's glue. */
    internal fun raw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray {
        val pad = layout(kit).pads[role]
        if (pad.phrase != null) return groove(pad.phrase, velocity, take)
        val strikes = pad.layers.map { Facsimile.render(Facsimile.model(it), (velocity * pad.velocity).coerceIn(.05f, 1f), take) }
        val out = FloatArray(strikes.maxOf { it.size })
        for (s in strikes) for (i in s.indices) out[i] += s[i]
        return out
    }

    /** A loop's groove at a zone and take, through its glue: the zone softens every note, the take rotates the strikes. */
    fun groove(key: String, zone: Float, take: Int): FloatArray {
        val raw = Facsimile.groove(Facsimile.loop(key), book.choke[key].orEmpty(), take, grooveSoften(zone))
        val g = book.glue[key] ?: return raw
        return Facsimile.glue(raw, g.thresholdDb, g.ratio, g.attackMs, g.makeupDb)
    }

    fun glueOf(key: String): Glue? = book.glue[key]

    /** A loop's finish: the mean out and the zone's gain, nothing trimmed or faded, so it wraps. */
    private fun looped(x: FloatArray, gain: Float): Snip {
        val mean = x.average().toFloat()
        return Snip(FloatArray(x.size) { ((x[it] - mean) * gain).coerceIn(-.98f, .98f) }, 1, Dsp.RATE)
    }

    private val gains = ConcurrentHashMap<Triple<Kit, Int, Float>, Float>()

    /** One gain per velocity zone, from its first take, shared by its four: every zone peaks at [PAD_PEAK]. */
    private fun zoneGain(kit: Kit, role: Int, velocity: Float): Float = gains.getOrPut(Triple(kit, role, velocity)) {
        gainFor(kit, role, raw(kit, role, velocity, 0))
    }

    private fun gainFor(kit: Kit, role: Int, first: FloatArray): Float {
        val peak = if (isGroove(kit, role)) KitStrikes.peakOf(first.let { y -> val m = y.average().toFloat(); FloatArray(y.size) { y[it] - m } })
        else KitStrikes.finish(first.copyOf(), 1f, ceiling = Float.MAX_VALUE).peak()
        return if (peak > 1e-9f) PAD_PEAK / peak else 1f
    }
}
