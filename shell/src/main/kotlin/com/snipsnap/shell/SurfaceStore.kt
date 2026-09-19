package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import java.io.File

/**
 * `surface.json` beside `kit.json`: what the SURFACE plays from this kit,
 * the four corner states its MORPH and VECTOR modes blend between, and (since
 * [Settings.secondPadSlot]/[Settings.thirdPadSlot]/[Settings.fourthPadSlot])
 * up to three more pads the engine barycentrically blends toward - the
 * pad's sample area, independent of mode or corners. Small, typed, and
 * under the same rules as every sidecar - unknown fields ignored, unknown
 * versions refused, a torn file a [JsonException] in words.
 *
 * The corners are the same seven macros the engine runs (pitch, cutoff,
 * resonance, drive, crush, echo, spring, each 0..1), so a corner *is* a
 * position on the pad: [Corner.from] turns the current reading in any mode
 * into one, which is how SET A..D captures the sound under the finger.
 */
object SurfaceStore {

    const val FILE_NAME = "surface.json"
    const val VERSION = 1

    /** One entry of [Corner.LIBRARY]: a [Corner] worth stepping onto a pad corner by its name, not its position. */
    data class NamedCorner(val name: String, val corner: Corner)

    data class Corner(
        val pitch: Float,
        val cutoff: Float,
        val resonance: Float,
        val drive: Float,
        /** 0 = transparent, 1 = heavily bit/rate-reduced - see SurfaceEngine.cpp's renderMono. */
        val crush: Float = 0f,
        /** 0 = dry, 1 = fully wet - a fixed-time delay's own mix, never its time (see SurfaceEngine.cpp). */
        val echo: Float = 0f,
        /** 0 = dry, 1 = fully wet - a fixed-room reverb's own mix, never its size or tone (see SurfaceEngine.cpp). */
        val spring: Float = 0f,
    ) {
        init {
            for ((name, v) in listOf(
                "pitch" to pitch, "cutoff" to cutoff, "resonance" to resonance,
                "drive" to drive, "crush" to crush, "echo" to echo, "spring" to spring,
            )) {
                require(v.isFinite() && v in 0f..1f) { "$name is 0..1, got $v" }
            }
        }

        companion object {
            /** The engine's own defaults: A clean, B dark, C low and thick, D hot. */
            val CLEAN = Corner(0.5f, 1.0f, 0.0f, 0.0f)
            val DARK = Corner(0.5f, 0.25f, 0.3f, 0.1f)
            val LOW = Corner(0.25f, 0.6f, 0.5f, 0.4f)
            val HOT = Corner(0.75f, 0.85f, 0.2f, 0.9f)
            val DEFAULTS: List<Corner> = listOf(CLEAN, DARK, LOW, HOT)

            /**
             * A named library - `design/surface-vector`'s boards sketch a
             * MORPH/VECTOR pad whose corners read LBP +/ECHO +/ECHO -/LBP -,
             * a preset library to *step* onto a corner instead of always
             * capturing one live. LBP leans mellow and rounded (a pure
             * filter pair, [crush]/[echo] both 0); ECHO brighter and more
             * resonant, and - now that the engine actually has a delay
             * stage (see [Corner.echo]) - genuinely echoing, [echo]'s wet
             * mix the one thing telling its own +/- pair apart from LBP's.
             *
             * CRUSH and GLITCH (PR #197's stage 6) extend the same idea
             * once [Corner.crush] existed to reach for: CRUSH is a pure
             * bitcrush pair, [echo] left at 0, the same way LBP is a pure
             * filter pair; GLITCH stacks crush *and* echo together, a
             * territory neither LBP nor ECHO alone can reach - a heavily
             * bitcrushed signal bouncing through its own repeats. +/- is
             * still each pair's own brighter/lighter vs. darker/heavier
             * sibling, same convention as LBP/ECHO.
             *
             * SPRING (stage 7, once [Corner.spring] existed to reach for)
             * is a pure reverb pair, the same shape as CRUSH but for the
             * engine's own fixed-room Schroeder network - [crush]/[echo]
             * both left at 0, [spring]'s wet mix the only thing moving.
             */
            val LIBRARY: List<NamedCorner> = listOf(
                NamedCorner("LBP +", Corner(0.5f, 0.55f, 0.1f, 0.0f)),
                NamedCorner("ECHO +", Corner(0.65f, 0.7f, 0.5f, 0.1f, echo = 0.25f)),
                NamedCorner("ECHO -", Corner(0.35f, 0.4f, 0.45f, 0.2f, echo = 0.5f)),
                NamedCorner("LBP -", Corner(0.5f, 0.2f, 0.15f, 0.0f)),
                NamedCorner("CRUSH +", Corner(0.5f, 0.6f, 0.15f, 0.1f, crush = 0.3f)),
                NamedCorner("CRUSH -", Corner(0.5f, 0.3f, 0.2f, 0.15f, crush = 0.65f)),
                NamedCorner("GLITCH +", Corner(0.5f, 0.65f, 0.3f, 0.25f, crush = 0.45f, echo = 0.2f)),
                NamedCorner("GLITCH -", Corner(0.4f, 0.35f, 0.35f, 0.35f, crush = 0.75f, echo = 0.4f)),
                NamedCorner("SPRING +", Corner(0.5f, 0.6f, 0.15f, 0.05f, spring = 0.35f)),
                NamedCorner("SPRING -", Corner(0.5f, 0.3f, 0.2f, 0.1f, spring = 0.6f)),
            )

            /**
             * The macro state under the finger, by the same map the engine
             * applies: XY is pitch across, cutoff up, half the roll as
             * resonance; XYZ adds the pinch as drive and the whole roll as
             * resonance; MORPH and VECTOR are the weighted blend of
             * [corners] with that same half-roll nudge layered onto
             * resonance (see `SurfaceEngine.cpp`'s `morphed()`, which this
             * mirrors) - a flat phone (tilt 0.5) is a no-op, so a corner
             * blend still captures exactly what its four corners say.
             * VECTOR shares MORPH's formula exactly: it is the same corner
             * blend, just also driving the sample triangle at once - there
             * is nothing about the sample side for a *corner* to capture.
             *
             * The weights come from [reading]'s own a/b/c/d, not recomputed
             * from x/y: [reading] is screen-rate *independently* smoothed
             * axis by axis (`TouchSurface.SmoothedReading`), and morph
             * weights are a nonlinear (bilinear) function of position, so a
             * fresh `morphWeights(reading.x, reading.y)` can disagree with
             * the a/b/c/d the engine is actually blending with right now,
             * particularly while the touch is still moving - the callers of
             * this function are responsible for handing it a [reading]
             * whose a/b/c/d are trustworthy for the mode being captured
             * (see `SurfaceScreen`'s `lastHeld`, reset on every mode
             * switch), not for this function to second-guess them.
             */
            fun from(mode: TouchSurface.Mode, reading: TouchSurface.Reading, tilt: Float, corners: List<Corner>): Corner {
                val t = tilt.coerceIn(0f, 1f)
                return when (mode) {
                    TouchSurface.Mode.XY -> Corner(reading.x, reading.y, t * 0.5f, 0f)
                    TouchSurface.Mode.XYZ -> Corner(reading.x, reading.y, t, reading.z)
                    // A corner is the seven macros, and GRAIN's finger drives
                    // none of them - it is the cloud's position and pitch,
                    // which no corner can hold. What the chain under the
                    // cloud actually runs is XY's rest: as recorded, wide
                    // open, the roll's half-resonance (`SurfaceEngine.cpp`'s
                    // `applyControl`, case 4), so that is what SET captures.
                    TouchSurface.Mode.GRAIN -> Corner(0.5f, 1f, t * 0.5f, 0f)
                    TouchSurface.Mode.MORPH, TouchSurface.Mode.VECTOR -> {
                        require(corners.size == 4) { "a morph or vector blends four corners, got ${corners.size}" }
                        val w = listOf(reading.a, reading.b, reading.c, reading.d)
                        fun blend(pick: (Corner) -> Float) =
                            corners.indices.sumOf { (w[it] * pick(corners[it])).toDouble() }.toFloat().coerceIn(0f, 1f)
                        val resonance = (blend { it.resonance } + (t - 0.5f) * 0.5f).coerceIn(0f, 1f)
                        Corner(
                            blend { it.pitch }, blend { it.cutoff }, resonance, blend { it.drive },
                            blend { it.crush }, blend { it.echo }, blend { it.spring },
                        )
                    }
                }
            }
        }
    }

    /**
     * GRAIN mode's three knobs, each 0..1, the mode's texture rather than
     * its performance: SIZE is each grain's length (10 ms to a quarter
     * second), DENSITY how many start a second (2 to 64), SPRAY how far
     * each one's start wanders from the finger's POSITION (none, to half
     * the sample either way). What each value means in the engine's own
     * units lives in `app/src/main/cpp/Grain.h` and nowhere else - the
     * screen shows these as percentages rather than carry a second copy
     * of that arithmetic.
     */
    data class Grain(
        val size: Float = 0.5f,
        val density: Float = 0.5f,
        val spray: Float = 0.15f,
    ) {
        init {
            for ((name, v) in listOf("size" to size, "density" to density, "spray" to spray)) {
                require(v.isFinite() && v in 0f..1f) { "$name is 0..1, got $v" }
            }
        }

        companion object {
            /** Mid-length, mid-rate, a little scatter: a cloud, not a buzz, before any knob is touched. */
            val DEFAULT = Grain()
        }
    }

    data class Settings(
        /** The pad the surface plays, by slot; null = the kit's lowest. */
        val padSlot: Int?,
        val corners: List<Corner> = Corner.DEFAULTS,
        /** The pad in the engine's second source slot (the sample area's base-left vertex); null = none loaded. */
        val secondPadSlot: Int? = null,
        /** The pad in the engine's third source slot (the sample area's base-right vertex); null = none loaded. */
        val thirdPadSlot: Int? = null,
        /** The pad in the engine's fourth source slot (the sample area's base-mid vertex); null = none loaded. */
        val fourthPadSlot: Int? = null,
        /** GRAIN mode's knobs; the defaults until the GRAIN row has been stepped. */
        val grain: Grain = Grain.DEFAULT,
        /** The two modulator slots ([Modulator]); every slot at depth 0 until the MOD row has been stepped. */
        val mods: List<Modulator.Slot> = Modulator.OFF,
        /**
         * KEY: the loop's pitch snapped to the kit's key (a semitone ladder
         * with no key), the way GRAIN's always is - see `SurfaceEngine.
         * setKeySnap`. Off until KEY is tapped, so a kit from before plays
         * as it did.
         */
        val keySnap: Boolean = false,
    ) {
        init {
            require(corners.size == 4) { "four corners, got ${corners.size}" }
            require(mods.size == Modulator.SLOTS) { "${Modulator.SLOTS} modulator slots, got ${mods.size}" }
            padSlot?.let { require(it in 1..128) { "slot out of range: $it" } }
            secondPadSlot?.let { require(it in 1..128) { "slot out of range: $it" } }
            thirdPadSlot?.let { require(it in 1..128) { "slot out of range: $it" } }
            fourthPadSlot?.let { require(it in 1..128) { "slot out of range: $it" } }
        }

        companion object {
            val DEFAULT = Settings(padSlot = null)
        }
    }

    fun save(kitDir: File, settings: Settings): File {
        kitDir.mkdirs()
        val file = File(kitDir, FILE_NAME)
        AtomicFile.writeText(file, Json.write(toJson(settings)) + "\n")
        return file
    }

    /** The kit's surface settings; the defaults when it has none. */
    fun load(kitDir: File): Settings {
        val file = File(kitDir, FILE_NAME)
        if (!file.isFile) return Settings.DEFAULT
        return fromJson(Json.parse(file.readText(Charsets.UTF_8)))
    }

    private fun toJson(s: Settings): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "version" to JsonValue.Num(VERSION.toDouble()),
            "pad" to (s.padSlot?.let { JsonValue.Num(it.toDouble()) } ?: JsonValue.Null),
            "secondPad" to (s.secondPadSlot?.let { JsonValue.Num(it.toDouble()) } ?: JsonValue.Null),
            "thirdPad" to (s.thirdPadSlot?.let { JsonValue.Num(it.toDouble()) } ?: JsonValue.Null),
            "fourthPad" to (s.fourthPadSlot?.let { JsonValue.Num(it.toDouble()) } ?: JsonValue.Null),
            "grain" to JsonValue.Obj(
                linkedMapOf(
                    "size" to JsonValue.Num(s.grain.size.toDouble()),
                    "density" to JsonValue.Num(s.grain.density.toDouble()),
                    "spray" to JsonValue.Num(s.grain.spray.toDouble()),
                ),
            ),
            "mods" to JsonValue.Arr(
                s.mods.map { m ->
                    JsonValue.Obj(
                        linkedMapOf(
                            "target" to JsonValue.Str(m.target.name),
                            "shape" to JsonValue.Str(m.shape.name),
                            "rate" to JsonValue.Num(m.rateIndex.toDouble()),
                            "depth" to JsonValue.Num(m.depth.toDouble()),
                        ),
                    )
                },
            ),
            "keySnap" to JsonValue.Bool(s.keySnap),
            "corners" to JsonValue.Arr(
                s.corners.map { c ->
                    JsonValue.Obj(
                        linkedMapOf(
                            "pitch" to JsonValue.Num(c.pitch.toDouble()),
                            "cutoff" to JsonValue.Num(c.cutoff.toDouble()),
                            "resonance" to JsonValue.Num(c.resonance.toDouble()),
                            "drive" to JsonValue.Num(c.drive.toDouble()),
                            "crush" to JsonValue.Num(c.crush.toDouble()),
                            "echo" to JsonValue.Num(c.echo.toDouble()),
                            "spring" to JsonValue.Num(c.spring.toDouble()),
                        ),
                    )
                },
            ),
        ),
    )

    private fun fromJson(root: JsonValue): Settings {
        val obj = root.obj()
        val version = obj["version"]?.int() ?: throw JsonException("surface.json has no version")
        if (version != VERSION) {
            throw JsonException("surface.json version $version is not supported (this build reads $VERSION)")
        }
        val pad = obj["pad"]?.takeUnless { it is JsonValue.Null }?.int()
        // Absent on a file saved before secondPad/thirdPad/fourthPad
        // existed, same as an explicit null - both mean "no sample loaded
        // there".
        val secondPad = obj["secondPad"]?.takeUnless { it is JsonValue.Null }?.int()
        val thirdPad = obj["thirdPad"]?.takeUnless { it is JsonValue.Null }?.int()
        val fourthPad = obj["fourthPad"]?.takeUnless { it is JsonValue.Null }?.int()
        val corners = obj["corners"]?.arr()?.map { c ->
            val o = c.obj()
            fun macro(name: String) = o[name]?.num()?.toFloat() ?: throw JsonException("corner has no $name")
            // crush/echo default to 0 (transparent, dry) only when the key
            // is truly absent - a file saved before they existed, the same
            // backward-compatible shape as secondPad/thirdPad/fourthPad.
            // `o[name] != null` here means the key is *present* (even as
            // an explicit JSON null): a malformed or wrong-typed present
            // value still goes through macro()'s own throwing path rather
            // than being silently swallowed into "off" (Copilot review,
            // PR #197).
            fun newMacro(name: String) = if (o[name] != null) macro(name) else 0f
            Corner(
                macro("pitch"), macro("cutoff"), macro("resonance"), macro("drive"),
                newMacro("crush"), newMacro("echo"), newMacro("spring"),
            )
        } ?: Corner.DEFAULTS
        if (corners.size != 4) throw JsonException("surface.json has ${corners.size} corners, not 4")
        // Absent on a file saved before GRAIN existed: the defaults, the
        // same backward-compatible shape as secondPad and crush. Present,
        // it has to be whole - a knob that is there but not a number is
        // refused, not read as its default (the crush/echo rule).
        val grain = obj["grain"]?.let { g ->
            val o = g.obj()
            fun knob(name: String) = o[name]?.num()?.toFloat() ?: throw JsonException("grain has no $name")
            Grain(knob("size"), knob("density"), knob("spray"))
        } ?: Grain.DEFAULT
        // Same rule as grain: absent is a file from before the MOD row,
        // present has to be whole - the right count, names this build
        // knows (a target from a newer build is refused in words, not
        // quietly re-aimed at something else), numbers where numbers go.
        val mods = obj["mods"]?.arr()?.map { m ->
            val o = m.obj()
            fun word(name: String) = o[name]?.str() ?: throw JsonException("modulator has no $name")
            val target = Modulator.Target.entries.firstOrNull { it.name == word("target") }
                ?: throw JsonException("modulator target '${word("target")}' is not one this build knows")
            val shape = Modulator.Shape.entries.firstOrNull { it.name == word("shape") }
                ?: throw JsonException("modulator shape '${word("shape")}' is not one this build knows")
            val rate = o["rate"]?.int() ?: throw JsonException("modulator has no rate")
            val depth = o["depth"]?.num()?.toFloat() ?: throw JsonException("modulator has no depth")
            Modulator.Slot(target, shape, rate, depth)
        } ?: Modulator.OFF
        if (mods.size != Modulator.SLOTS) throw JsonException("surface.json has ${mods.size} modulators, not ${Modulator.SLOTS}")
        // Absent is a file from before KEY: off. Present, it is a boolean or
        // the file is torn - the same rule as every field above.
        val keySnap = obj["keySnap"]?.bool() ?: false
        return Settings(pad, corners, secondPad, thirdPad, fourthPad, grain, mods, keySnap = keySnap)
    }
}
