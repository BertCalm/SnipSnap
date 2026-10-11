package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** Original synthesized recording families, rather than imported or historical tapes. */
enum class SpoolSourceFamily { STRING, CHOIR, FLUTE, BRASS }

/** A finite deterministic imprint is prepared, then writes are frozen for the exported loop. */
enum class SpoolHoldMode { FROZEN_SNAPSHOT }

/** One strip's source, mix level, and contact/loading calibration. */
data class SpoolStrip(
    val family: SpoolSourceFamily,
    val gain: Float = 1f,
    val tension: Float = 1f,
    val drag: Float = 0.08f,
    val contact: Float = 0.82f,
    val doubling: Float = 0.18f,
) {
    init {
        require(gain.isFinite() && gain in 0f..1f) { "SPOOL strip gain out of 0..1: $gain" }
        require(tension.isFinite() && tension in 0.2f..2f) { "SPOOL tension out of .2..2: $tension" }
        require(drag.isFinite() && drag in 0f..1f) { "SPOOL drag out of 0..1: $drag" }
        require(contact.isFinite() && contact in 0f..1f) { "SPOOL contact out of 0..1: $contact" }
        require(doubling.isFinite() && doubling in 0f..1f) { "SPOOL doubling out of 0..1: $doubling" }
    }
}

/** Both values are fractions of the source recording's duration. */
data class SpoolSplice(val boundary: Float, val displacement: Float) {
    init {
        require(boundary.isFinite() && boundary in 0.05f..0.95f) { "SPOOL splice boundary out of .05...95: $boundary" }
        require(displacement.isFinite() && displacement in -0.4f..0.4f) { "SPOOL splice displacement out of -.4...4: $displacement" }
    }
}

/** A delayed, filtered return-head write into another strip. */
data class SpoolRoute(val source: Int, val destination: Int, val gain: Float = 0.42f) {
    init {
        require(source in 0..2 && destination in 0..2 && source != destination) { "SPOOL route must join two different strips 0..2" }
        require(gain.isFinite() && gain in 0f..0.55f) { "SPOOL route gain out of 0...55: $gain" }
    }
}

/**
 * Serializable machine geometry. Every render rebuilds original recordings and fresh overlays;
 * transient buffers, positions, motor and spring energy never travel between pads.
 * Empty [roster] requests the documented voice roster; patches save that roster explicitly.
 * [headOffsets] are recorded seconds. [writeDelaySamples] uses the 44.1 kHz output timebase.
 */
data class SpoolMachine(
    val roster: List<SpoolStrip> = emptyList(),
    val gateSeconds: Float = 1.35f,
    val sourceSeconds: Float = 3f,
    val motorTorque: Float = 1f,
    val returnSeconds: Float = 1.35f,
    val headOffsets: List<Float> = listOf(0f, -0.12f, 0.16f),
    val spliceSchedule: List<SpoolSplice> = listOf(
        SpoolSplice(0.22f, 0.14f), SpoolSplice(0.44f, -0.17f),
        SpoolSplice(0.67f, 0.12f), SpoolSplice(0.85f, -0.20f),
    ),
    val routes: List<SpoolRoute> = listOf(SpoolRoute(0, 1), SpoolRoute(1, 2), SpoolRoute(2, 0)),
    val holdMode: SpoolHoldMode = SpoolHoldMode.FROZEN_SNAPSHOT,
    val seed: Int = 26,
    val writeDelaySamples: Int = 32,
    val reengage: Boolean = true,
) {
    init {
        require(roster.isEmpty() || roster.size == 3) { "SPOOL needs three source strips" }
        require(roster.isEmpty() || roster.sumOf { it.gain.toDouble() } in 0.1..1.8) { "SPOOL roster gain sum must be .1..1.8" }
        require(sourceSeconds.isFinite() && sourceSeconds in 2f..4f) { "SPOOL source duration out of 2..4 seconds: $sourceSeconds" }
        require(gateSeconds.isFinite() && gateSeconds in 0.15f..2.5f && gateSeconds <= sourceSeconds - 0.1f) {
            "SPOOL gate must be .15..2.5 seconds and fit inside the source"
        }
        require(returnSeconds.isFinite() && returnSeconds in 0.3f..2f) { "SPOOL return duration out of .3..2 seconds: $returnSeconds" }
        require(motorTorque.isFinite() && motorTorque in 0.4f..1.5f) { "SPOOL motor torque out of .4..1.5: $motorTorque" }
        require(headOffsets.size == 3 && headOffsets.all { it.isFinite() && it in -0.6f..0.6f }) { "SPOOL needs three head offsets in -.6...6 seconds" }
        require(spliceSchedule.size <= 4) { "SPOOL allows at most four splice boundaries" }
        require(spliceSchedule.zipWithNext().all { (a, b) -> b.boundary - a.boundary >= 0.06f }) { "SPOOL splice boundaries must be ordered and at least .06 apart" }
        require(routes.size <= 6 && routes.distinctBy { it.source to it.destination }.size == routes.size) { "SPOOL routes must be sparse and unique" }
        // Incoming row sums bound the routed/filter/interpolation feedback contribution at .55
        // in L-infinity. The DSP separately retains .40 of a prior overlay at the start of a
        // write generation; causal convex local updates preserve its complete contraction bound.
        require((0..2).all { dest -> routes.filter { it.destination == dest }.sumOf { it.gain.toDouble() } <= 0.550001 }) {
            "SPOOL complete incoming write gain must not exceed .55"
        }
        require(writeDelaySamples in 1..4410) { "SPOOL write delay out of 1..4410 samples: $writeDelaySamples" }
    }

    companion object {
        fun rosterFor(voice: SpoolVoice): List<SpoolStrip> {
            val families = when (voice) {
                SpoolVoice.STRING -> listOf(SpoolSourceFamily.STRING, SpoolSourceFamily.CHOIR, SpoolSourceFamily.FLUTE)
                SpoolVoice.CHOIR -> listOf(SpoolSourceFamily.CHOIR, SpoolSourceFamily.STRING, SpoolSourceFamily.FLUTE)
                SpoolVoice.FLUTE -> listOf(SpoolSourceFamily.FLUTE, SpoolSourceFamily.CHOIR, SpoolSourceFamily.STRING)
                SpoolVoice.BRASS -> listOf(SpoolSourceFamily.BRASS, SpoolSourceFamily.STRING, SpoolSourceFamily.CHOIR)
                SpoolVoice.PALIMPSEST -> listOf(SpoolSourceFamily.STRING, SpoolSourceFamily.CHOIR, SpoolSourceFamily.FLUTE)
                SpoolVoice.CUT -> listOf(SpoolSourceFamily.BRASS, SpoolSourceFamily.CHOIR, SpoolSourceFamily.STRING)
            }
            val gains = if (voice == SpoolVoice.PALIMPSEST || voice == SpoolVoice.CUT) listOf(0.65f, 0.23f, 0.12f) else listOf(0.82f, 0.12f, 0.06f)
            return families.mapIndexed { i, family ->
                SpoolStrip(family, gains[i], listOf(1f, 1.07f, 0.93f)[i], 0.06f + i * 0.025f)
            }
        }

        fun forVoice(voice: SpoolVoice): SpoolMachine = SpoolMachine(roster = rosterFor(voice))
    }
}

/** A saved recording machine, including its gate, source roster, delayed routes and HOLD mode. */
data class SpoolPatch(
    override val name: String,
    val voice: SpoolVoice,
    override val macros: Map<String, Float>,
    val machine: SpoolMachine = SpoolMachine.forVoice(voice),
    val velocity: Float = 1f,
    val model: Int = MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Spool.macrosFor(voice))
        require(velocity.isFinite() && velocity in 0f..1f) { "SPOOL velocity out of 0..1: $velocity" }
        require(model == MODEL_VERSION) { "unsupported spool model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Spool.render(voice, macros, machine, velocity)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        fun number(n: Number) = JsonValue.Num(n.toDouble())
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = number(model)
        obj["velocity"] = number(velocity)
        obj["machine"] = JsonValue.Obj(linkedMapOf(
            "sourceSeconds" to number(machine.sourceSeconds),
            "gateSeconds" to number(machine.gateSeconds),
            "returnSeconds" to number(machine.returnSeconds),
            "motorTorque" to number(machine.motorTorque),
            "roster" to JsonValue.Arr(machine.roster.map { strip ->
                JsonValue.Obj(linkedMapOf(
                    "family" to JsonValue.Str(strip.family.name), "gain" to number(strip.gain),
                    "tension" to number(strip.tension), "drag" to number(strip.drag),
                    "contact" to number(strip.contact), "doubling" to number(strip.doubling),
                ))
            }),
            "headOffsets" to JsonValue.Arr(machine.headOffsets.map(::number)),
            "spliceSchedule" to JsonValue.Arr(machine.spliceSchedule.map { splice ->
                JsonValue.Obj(linkedMapOf("boundary" to number(splice.boundary), "displacement" to number(splice.displacement)))
            }),
            "routes" to JsonValue.Arr(machine.routes.map { route ->
                JsonValue.Obj(linkedMapOf("source" to number(route.source), "destination" to number(route.destination), "gain" to number(route.gain)))
            }),
            "holdMode" to JsonValue.Str(machine.holdMode.name),
            "seed" to number(machine.seed),
            "writeDelaySamples" to number(machine.writeDelaySamples),
            "reengage" to JsonValue.Bool(machine.reengage),
        ))
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "SPOOL"
        const val VERSION = Patches.VERSION
        const val MODEL_VERSION = 1

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val model = obj["model"]?.int() ?: MODEL_VERSION
            if (model != MODEL_VERSION) throw JsonException("unsupported spool model $model")
            val velocity = obj["velocity"]?.num()?.toFloat() ?: 1f
            return Patches.decode(value, ENGINE, { n -> SpoolVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                val defaults = SpoolMachine.forVoice(voice)
                val m = obj["machine"]?.obj()
                fun f(key: String, fallback: Float) = m?.get(key)?.num()?.toFloat() ?: fallback
                val machine = if (m == null) defaults else SpoolMachine(
                    roster = m["roster"]?.arr()?.map { v ->
                        val strip = v.obj()
                        val family = strip["family"]?.str() ?: throw JsonException("SPOOL strip has no family")
                        SpoolStrip(
                            SpoolSourceFamily.entries.firstOrNull { it.name == family } ?: throw JsonException("unknown spool source $family"),
                            strip["gain"]?.num()?.toFloat() ?: 1f, strip["tension"]?.num()?.toFloat() ?: 1f,
                            strip["drag"]?.num()?.toFloat() ?: 0.08f, strip["contact"]?.num()?.toFloat() ?: 0.82f,
                            strip["doubling"]?.num()?.toFloat() ?: 0.18f,
                        )
                    } ?: defaults.roster,
                    gateSeconds = f("gateSeconds", defaults.gateSeconds), sourceSeconds = f("sourceSeconds", defaults.sourceSeconds),
                    motorTorque = f("motorTorque", defaults.motorTorque), returnSeconds = f("returnSeconds", defaults.returnSeconds),
                    headOffsets = m["headOffsets"]?.arr()?.map { it.num().toFloat() } ?: defaults.headOffsets,
                    spliceSchedule = m["spliceSchedule"]?.arr()?.map { v ->
                        val splice = v.obj()
                        SpoolSplice(splice["boundary"]?.num()?.toFloat() ?: throw JsonException("SPOOL splice has no boundary"),
                            splice["displacement"]?.num()?.toFloat() ?: throw JsonException("SPOOL splice has no displacement"))
                    } ?: defaults.spliceSchedule,
                    routes = m["routes"]?.arr()?.map { v ->
                        val route = v.obj()
                        SpoolRoute(route["source"]?.int() ?: throw JsonException("SPOOL route has no source"),
                            route["destination"]?.int() ?: throw JsonException("SPOOL route has no destination"),
                            route["gain"]?.num()?.toFloat() ?: 0.42f)
                    } ?: defaults.routes,
                    holdMode = m["holdMode"]?.str()?.let { mode ->
                        SpoolHoldMode.entries.firstOrNull { it.name == mode } ?: throw JsonException("unsupported spool HOLD mode $mode")
                    } ?: defaults.holdMode,
                    seed = m["seed"]?.int() ?: defaults.seed,
                    writeDelaySamples = m["writeDelaySamples"]?.int() ?: defaults.writeDelaySamples,
                    reengage = m["reengage"]?.bool() ?: defaults.reengage,
                )
                SpoolPatch(name, voice, macros, machine, velocity, model)
            }
        }

        fun fromJsonText(text: String): SpoolPatch = fromJsonValue(Json.parse(text)) as SpoolPatch
    }
}
