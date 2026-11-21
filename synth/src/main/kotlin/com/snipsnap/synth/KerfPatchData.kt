package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * KERF's fitted numbers (docs/superpowers/specs/2026-11-19-kerf-synthetic-chops-design.md, section 6).
 *
 * A KERF hit is not a voice plus a handful of macros: it is a measurement of one chopped drum hit, a few
 * kilobytes of numbers that [KerfRender] turns back into sound. This file is that measurement's shape and its
 * JSON. No audio is stored in it.
 *
 * The field names in JSON are the Python prototype's (`prototype/kerf/resynth.py`), because the fitter that
 * writes them is still Python.
 */

/** One sine oscillator: the drum's body, or any steady tone. It glides from `hzEnd + glideHz` down to [hzEnd]. */
data class KerfPartial(
    val hzEnd: Double, val glideHz: Double, val glideS: Double, val amp: Double,
    /** Where the oscillator begins; a tone that enters late must not ramp from the start of the slice. */
    val startS: Double, val attackS: Double, val t60: Double, val phase: Double,
)

/** A later hit inside the same slice, as seen by one noise band: it starts at [atS] with its own crack and ring. */
data class KerfLayer(val atS: Double, val snap: Double, val level: Double)

/**
 * One band of the shaped noise, in power. [snap] is the fast crack and [level] the slower ring, each with its own
 * decay; [floor] is the part that never dies inside the slice (hat wash, room, hiss), which is most of what makes a
 * chop sound chopped.
 */
data class KerfBand(
    val snap: Double, val snapT60: Double, val level: Double, val t60: Double, val floor: Double, val attackS: Double,
    val layers: List<KerfLayer>,
)

/** One narrow resonance the noise is passed through: centre, height above the base, and width. */
data class KerfResonance(val hz: Double, val gain: Double, val bandwidthHz: Double)

/** The resonance bank. A cymbal rings as noise through many narrow resonances, not as steady tones. */
data class KerfColour(val base: Double, val resonances: List<KerfResonance>)

/** The stick or beater landing: a burst of about a millisecond at an exact time. */
data class KerfTick(val atS: Double, val amp: Double, val hz: Double)

data class KerfPatchData(
    val seconds: Double,
    /** The fitter's seed. It is up to 56 bits wide, so it is carried as a Long and hashed for [Dsp.Noise]. */
    val seed: Long,
    val partials: List<KerfPartial>,
    val bands: List<KerfBand>,
    val colour: KerfColour?,
    val ticks: List<KerfTick>,
    val events: List<Double>,
) {
    init {
        require(bands.size == KerfRender.BAND_COUNT) { "a KERF patch has ${KerfRender.BAND_COUNT} bands, got ${bands.size}" }
        require(seconds > 0.0 && seconds.isFinite()) { "seconds must be positive and finite: $seconds" }
    }

    fun toJsonValue(): JsonValue.Obj = obj(
        "seconds" to n(seconds), "seed" to n(seed.toDouble()),
        "partials" to JsonValue.Arr(partials.map {
            obj("hz_end" to n(it.hzEnd), "glide_hz" to n(it.glideHz), "glide_s" to n(it.glideS), "amp" to n(it.amp),
                "attack_s" to n(it.attackS), "start_s" to n(it.startS), "t60" to n(it.t60), "phase" to n(it.phase))
        }),
        "bands" to JsonValue.Arr(bands.map { b ->
            obj("snap" to n(b.snap), "snap_t60" to n(b.snapT60), "level" to n(b.level), "t60" to n(b.t60),
                "floor" to n(b.floor), "attack_s" to n(b.attackS),
                "layers" to JsonValue.Arr(b.layers.map { obj("at_s" to n(it.atS), "snap" to n(it.snap), "level" to n(it.level)) }))
        }),
        "events" to JsonValue.Arr(events.map(::n)),
        "ticks" to JsonValue.Arr(ticks.map { obj("at_s" to n(it.atS), "amp" to n(it.amp), "hz" to n(it.hz)) }),
        "colour" to (colour?.let { c ->
            obj("base" to n(c.base), "res" to JsonValue.Arr(c.resonances.map { JsonValue.Arr(listOf(n(it.hz), n(it.gain), n(it.bandwidthHz))) }))
        } ?: obj("base" to n(1.0), "res" to JsonValue.Arr(emptyList()))),
    )

    companion object {
        private fun n(v: Double) = JsonValue.Num(v)
        private fun obj(vararg e: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*e))

        fun fromJsonValue(value: JsonValue): KerfPatchData {
            val o = value.obj()
            fun d(m: Map<String, JsonValue>, k: String) = m.getValue(k).num()
            val colour = (o["colour"] as? JsonValue.Obj)?.entries?.let { c ->
                KerfColour(d(c, "base"), c.getValue("res").arr().map { r -> r.arr().let { KerfResonance(it[0].num(), it[1].num(), it[2].num()) } })
            }
            return KerfPatchData(
                seconds = d(o, "seconds"),
                seed = d(o, "seed").toLong(),
                partials = o.getValue("partials").arr().map { it.obj() }.map { p ->
                    KerfPartial(d(p, "hz_end"), d(p, "glide_hz"), d(p, "glide_s"), d(p, "amp"),
                        p["start_s"]?.num() ?: 0.0, d(p, "attack_s"), d(p, "t60"), d(p, "phase"))
                },
                bands = o.getValue("bands").arr().map { it.obj() }.map { b ->
                    KerfBand(d(b, "snap"), d(b, "snap_t60"), d(b, "level"), d(b, "t60"), d(b, "floor"), d(b, "attack_s"),
                        (b["layers"]?.arr() ?: emptyList()).map { it.obj() }.map { KerfLayer(d(it, "at_s"), d(it, "snap"), d(it, "level")) })
                },
                colour = colour?.takeIf { it.resonances.isNotEmpty() },
                ticks = (o["ticks"]?.arr() ?: emptyList()).map { it.obj() }.map { KerfTick(d(it, "at_s"), d(it, "amp"), d(it, "hz")) },
                events = (o["events"]?.arr() ?: emptyList()).map { it.num() },
            )
        }
    }
}

/** Where one pad's knife landed: three numbers in 0..1, fixed per pad so a pad always chops the same way. */
data class KerfDraw(val start: Double, val end: Double, val which: Double)

/**
 * One pad of a KERF kit: the hit, and the two hits either side of it in the loop it came from. The neighbours are
 * what BLEED and SLOP read, so a pad is three patches, not one.
 */
data class KerfPad(
    val pad: Int, val drumClass: String, val patch: KerfPatchData, val prev: KerfPatchData?, val next: KerfPatchData?,
    val bleed: Double, val slop: Double, val draw: KerfDraw?,
)

/** The four kits the owner heard on 2026-11-18, as fitted numbers (`synth/src/main/resources/kerf/v1`). */
object KerfBank {
    const val VERSION = 1

    val kits: Map<String, List<KerfPad>> by lazy { load("kit_patches.json") }

    /**
     * The second bank (round 5, 2026-11-21): four genre kits' hits the first bank does not hold, keyed by genre
     * ([KerfKits.Genre.name]), written by `prototype/kerf/genre_bank.py`; round 6 (2026-11-21) adds eight more from
     * `genre_bank_r6.py` (`genre_patches_r6.json`), from 31 new loops and the first loops' leftovers. Only those kits
     * read it; the engine's pools
     * ([Kerf.pool]) stay on [kits], so a saved patch's HIT keeps its hit.
     */
    val genreKits: Map<String, List<KerfPad>> by lazy { load("genre_patches.json") + load("genre_patches_r6.json") }

    private fun load(file: String): Map<String, List<KerfPad>> {
        val text = requireNotNull(javaClass.getResourceAsStream("/kerf/v$VERSION/$file")) { "Missing KERF bank $file" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
        return Json.parse(text).obj().mapValues { (_, pads) ->
            pads.arr().map { it.obj() }.map { p ->
                KerfPad(
                    pad = p.getValue("pad").int(), drumClass = p.getValue("cls").str(),
                    patch = KerfPatchData.fromJsonValue(p.getValue("patch")),
                    prev = p["prev"]?.let(KerfPatchData::fromJsonValue), next = p["next"]?.let(KerfPatchData::fromJsonValue),
                    bleed = p.getValue("bleed").num(), slop = p.getValue("slop").num(),
                    draw = p["draw"]?.arr()?.let { KerfDraw(it[0].num(), it[1].num(), it[2].num()) },
                )
            }
        }
    }
}
