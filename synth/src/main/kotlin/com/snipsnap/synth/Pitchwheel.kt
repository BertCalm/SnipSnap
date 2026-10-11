package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

enum class PitchwheelVoice { CLUNK, PLUCK, DRAW, RECOIL, THAWED, TURN }

/**
 * A finite push into a shared wooden resonator. Angular coordinates are radians,
 * time is seconds, inertia is .08 in normalised mechanical units. Spring work,
 * wheel kinetic energy and mass-normalised modal energy share that energy unit.
 * Contacts and filaments run on the 4x audio clock. Their elastic work is debited
 * before exciting modes; friction pays for bowing and heating. A small conservative
 * projection removes integration error BEFORE audio conditioning, never adds power.
 * Constants describe an imaginary instrument, not measured resin or wood.
 */
object Pitchwheel {
    const val RATE = Dsp.RATE
    const val MODEL_VERSION = 2
    const val DEFAULT_MIDI = 48
    const val MIDI_MIN = 24
    const val MIDI_MAX = 96
    const val HOLD_LOOP = .99f
    const val TUNE_SEMITONES = 24
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val INERTIA = .08
    private const val TAU = 2.0 * PI
    private const val TEETH = 10
    private const val CLEARANCE = .012
    private const val MAX_SECONDS = 8f

    // A gentle push must still clear a finger. This passive launch-energy floor
    // tapers smoothly to zero at the lowest default PUSH; the curve is monotonic.
    private fun launchSpeed(push: Double): Double {
        val t = (push / .45f.toDouble()).coerceIn(0.0, 1.0)
        return 1.0 + 3.0 * push + .6 * (1.0 - t * t * (3.0 - 2.0 * t))
    }

    private val settings = arrayOf(
        floatArrayOf(.45f, .25f, .30f, .40f, .75f),
        floatArrayOf(.55f, .70f, .35f, .55f, .40f),
        floatArrayOf(.45f, .40f, .65f, .50f, .60f),
        floatArrayOf(.45f, .50f, .85f, .20f, .55f),
        floatArrayOf(.65f, .45f, .55f, .80f, .60f),
        floatArrayOf(.55f, .45f, .50f, .55f, .55f),
    )
    fun macrosFor(voice: PitchwheelVoice): List<MacroSpec> {
        val s = settings[voice.ordinal]
        return listOf(MacroSpec("TUNE", .5f, .5f), MacroSpec("PUSH", s[0], .45f),
            MacroSpec("TOOTH", s[1], .40f), MacroSpec("ADHESION", s[2], .35f),
            MacroSpec("HEAT", s[3], .45f), MacroSpec("BODY", s[4], .45f), MacroSpec("HOLD", 0f, 0f))
    }
    fun defaults(voice: PitchwheelVoice) = macrosFor(voice).associate { it.name to it.default }
    internal fun settled(voice: PitchwheelVoice, macros: Map<String, Float>) =
        defaults(voice).toMutableMap().also { m ->
            for ((k, v) in macros) if (k in m && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        }
    fun scramble(voice: PitchwheelVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> =
        Dsp.scrambleNear(settled(voice, if (near is PitchwheelPatch) near.macros else emptyMap()), temperature, random)
            .toMutableMap().also { it["HOLD"] = it.getValue("HOLD").coerceAtMost(.95f) }
    fun isLoop(hold: Float) = hold >= HOLD_LOOP
    fun midiFor(@Suppress("UNUSED_PARAMETER") voice: PitchwheelVoice, tune: Float, midi: Int = DEFAULT_MIDI) =
        midi + ((tune.coerceIn(0f, 1f) - .5f) * TUNE_SEMITONES).roundToInt()
    fun frequencyFor(midi: Int) = Keys.midiHz(midi)
    fun frequencyFor(voice: PitchwheelVoice, tune: Float, midi: Int = DEFAULT_MIDI) = frequencyFor(midiFor(voice, tune, midi))
    fun drumClassFor(voice: PitchwheelVoice, macros: Map<String, Float> = emptyMap()) =
        if (isLoop(settled(voice, macros).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL

    internal enum class EventKind { CONTACT, RELEASE, ATTACH, SNAP, DETACH }
    internal data class Event(val time: Double, val kind: EventKind, val tooth: Int,
        val direction: Int, val energy: Double, val finger: Int = -1)
    internal data class Trace(val time: Double, val angle: Double, val speed: Double,
        val temperature: Double, val mechanicalEnergy: Double, val acousticEnergy: Double,
        val inputWork: Double, val dissipated: Double, val attachments: Int)
    internal data class Diagnostics(val events: List<Event>, val trace: List<Trace>,
        val initialEnergy: Double, val inputWork: Double, val dissipated: Double,
        val maxEnergyError: Double, val finalEnergy: Double, val maxAttachments: Int,
        val passiveCorrection: Double, val bowedEnergy: Double, val snappedEnergy: Double,
        val releasedEnergy: Double)
    internal data class Probe(val samples: FloatArray, val diagnostics: Diagnostics,
        val previousCycle: FloatArray = FloatArray(0), val seamError: Double = 0.0,
        val stateError: Double = 0.0, val prerollCycles: Int = 0)

    private data class Finger(var tooth: Int = -1, var anchor: Double = 0.0,
        var low: Double = 0.0, var high: Double = 0.0, var direction: Int = 1)
    private data class Filament(var tooth: Int = -1, var anchor: Double = 0.0,
        var born: Double = 0.0, var stiffness: Double = 0.0, var limit: Double = 0.0,
        var direction: Int = 1)

    private enum class Transfer { RELEASE, SNAP, SLIP, CREAK }

    /** One ten-mode wooden body, with work-conserving contact transfers. Release
     * work waits in a short contact reservoir; it is part of the stored energy.
     * Resin slip pumps a broader subset of these same modes, never a noise layer. */
    private class Wood(hz: Double, body: Double, tooth: Double, voice: PitchwheelVoice, period: Int = 0) {
        val x = DoubleArray(10)
        val v = DoubleArray(10)
        val pending = DoubleArray(10)
        val pendingDirection = DoubleArray(10) { 1.0 }
        private val c = DoubleArray(10)
        private val s = DoubleArray(10)
        private val d = DoubleArray(10)
        private val pickup = DoubleArray(10)
        private val release = DoubleArray(10)
        private val snap = DoubleArray(10)
        private val slip = DoubleArray(10)
        private val creak = DoubleArray(10)
        private val transferRate: Double
        init {
            val ratios = doubleArrayOf(1.0, 2.0, 3.0, 3.9, 6.2, 1.42, 1.86, 2.72, 4.12, 6.43)
            val rootDecay = when (voice) {
                PitchwheelVoice.CLUNK -> .16 + .34 * body
                PitchwheelVoice.PLUCK -> .40 + .80 * body
                PitchwheelVoice.DRAW -> .22 + .42 * body
                PitchwheelVoice.RECOIL -> .25 + .45 * body
                PitchwheelVoice.THAWED -> .12 + .30 * body
                PitchwheelVoice.TURN -> .25 + .60 * body
            }
            val bodyDecay = when (voice) {
                PitchwheelVoice.CLUNK -> .07 + .19 * body
                PitchwheelVoice.PLUCK -> .06 + .10 * body
                PitchwheelVoice.DRAW -> .14 + .32 * body
                PitchwheelVoice.RECOIL -> .08 + .28 * body
                PitchwheelVoice.THAWED -> .06 + .16 * body
                PitchwheelVoice.TURN -> .12 + .28 * body
            }
            val edge = when (voice) {
                PitchwheelVoice.CLUNK -> .38
                PitchwheelVoice.PLUCK -> 1.55
                PitchwheelVoice.DRAW -> .62
                PitchwheelVoice.RECOIL -> 1.25
                PitchwheelVoice.THAWED -> .72
                PitchwheelVoice.TURN -> .95
            }
            val bodyTransfer = when (voice) {
                PitchwheelVoice.CLUNK -> 2.2
                PitchwheelVoice.PLUCK -> .45
                PitchwheelVoice.DRAW -> 1.1
                PitchwheelVoice.RECOIL -> 1.35
                PitchwheelVoice.THAWED -> 1.6
                PitchwheelVoice.TURN -> 1.0
            }
            val attack = (.00045 + .004 * (1 - tooth).pow(2)) * when (voice) {
                PitchwheelVoice.CLUNK -> 1.4
                PitchwheelVoice.PLUCK -> .55
                PitchwheelVoice.DRAW -> 1.3
                PitchwheelVoice.RECOIL -> .65
                PitchwheelVoice.THAWED -> 1.5
                PitchwheelVoice.TURN -> 1.0
            }
            transferRate = 1 - exp(-1 / (INTERNAL_RATE * attack))
            for (j in x.indices) {
                var f = hz * ratios[j] * if (j >= 5) 1.15 - .30 * body else 1.0
                // A held wheel repeats one settled body state, including modal phase.
                if (period > 0) f = round(f * period / RATE) * RATE / period
                val a = TAU * f / INTERNAL_RATE
                c[j] = cos(a); s[j] = sin(a)
                val t60 = if (j < 5) rootDecay / (1 + .36 * j)
                    else bodyDecay / (1 + .19 * (j - 5))
                d[j] = exp(-ln(1000.0) / (INTERNAL_RATE * t60))
                pickup[j] = if (f > RATE * .42) 0.0 else when (j) {
                    0 -> 1.0; 1 -> .70; 2 -> .55; 3 -> .42; 4 -> .30
                    else -> (.62 + .50 * body) / (1 + .18 * (j - 5))
                }
                release[j] = when (j) {
                    0 -> 1.0
                    1 -> (.30 + .75 * tooth) * edge
                    2 -> (.16 + .65 * tooth) * edge
                    3 -> (.10 + .70 * tooth) * edge
                    4 -> (.06 + .58 * tooth) * edge
                    else -> (.18 + .65 * body) * bodyTransfer / (1 + .24 * (j - 5))
                }
                snap[j] = if (j == 0) .45 else if (j < 5) (.55 + .40 * tooth) / (1 + .10 * j)
                    else (.45 + .35 * body) / (1 + .12 * (j - 5))
                slip[j] = when (j) {
                    0 -> .75; 1 -> .90; 2 -> .65; 3 -> .30; 4 -> .18
                    else -> (.45 + .45 * body) / (1 + .25 * (j - 5))
                }
                creak[j] = if (j < 5) .16 / (j + 1) else .70 / (1 + .18 * (j - 5))
                if (pickup[j] == 0.0) { release[j] = 0.0; snap[j] = 0.0; slip[j] = 0.0; creak[j] = 0.0 }
            }
        }
        fun energy() = x.indices.sumOf { .5 * (x[it] * x[it] + v[it] * v[it]) + pending[it] }
        private fun pump(j: Int, work: Double, direction: Double) {
            if (work <= 0.0) return
            val existing = .5 * (x[j] * x[j] + v[j] * v[j])
            if (existing > 1e-22) {
                val gain = sqrt(1 + work / existing); x[j] *= gain; v[j] *= gain
            } else v[j] = direction * sqrt(2 * work)
        }
        fun excite(energy: Double, transfer: Transfer = Transfer.RELEASE, direction: Int = 1,
            variation: Double = 1.0, strain: Double = 0.0, temperature: Double = .5) {
            if (energy <= 0.0) return
            val p = when (transfer) { Transfer.RELEASE -> release; Transfer.SNAP -> snap; Transfer.SLIP -> slip; Transfer.CREAK -> creak }
            val weights = DoubleArray(10)
            var norm = 0.0
            for (j in p.indices) {
                val colour = if (j == 0) 1.0 else variation * (if (direction < 0) 1.0 + .16 * j else 1.0)
                val texture = if (transfer == Transfer.SLIP && j > 0)
                    (1.0 + .55 * strain + .30 * (1 - temperature)) * (1 + .22 * tanh(v[j] * 12 - v[0] * 4)) else 1.0
                val w = p[j] * colour * texture
                weights[j] = w * w; norm += weights[j]
            }
            if (norm == 0.0) { pending[0] += energy; return }
            for (j in weights.indices) {
                val work = energy * weights[j] / norm
                if (transfer == Transfer.RELEASE || transfer == Transfer.SNAP) {
                    pending[j] += work; pendingDirection[j] = direction.toDouble()
                } else pump(j, work, direction.toDouble())
            }
        }
        fun tick(): Double {
            var loss = 0.0
            for (j in x.indices) {
                val work = pending[j] * transferRate
                pending[j] -= work; pump(j, work, pendingDirection[j])
                val old = .5 * (x[j] * x[j] + v[j] * v[j])
                val xx = (c[j] * x[j] + s[j] * v[j]) * d[j]
                v[j] = (c[j] * v[j] - s[j] * x[j]) * d[j]; x[j] = xx
                loss += old * (1 - d[j] * d[j])
                if (abs(x[j]) + abs(v[j]) < 1e-24) { x[j] = 0.0; v[j] = 0.0 }
            }
            return loss
        }
        fun sample() = x.indices.sumOf { x[it] * pickup[it] }.toFloat()
    }

    private class Engine(val voice: PitchwheelVoice, val m: Map<String, Float>, val midi: Int,
        velocity: Float, val held: Boolean, val period: Int, val record: Boolean,
        val resin: Boolean, val contacts: Boolean, val bowSound: Boolean, val snapSound: Boolean,
        val solverRate: Int) {
        val push = m.getValue("PUSH").toDouble()
        val tooth = m.getValue("TOOTH").toDouble()
        val adhesion = if (held) min(.65, m.getValue("ADHESION").toDouble()) else m.getValue("ADHESION").toDouble()
        val heat = m.getValue("HEAT").toDouble()
        val body = m.getValue("BODY").toDouble()
        val width = .11 - .06 * tooth
        val stiffness = 4.0 + 18.0 * tooth
        val phases = DoubleArray(TEETH)
        val attach = DoubleArray(TEETH)
        val variation = DoubleArray(TEETH)
        val fingers = Array(2) { Finger() }
        val filaments = Array(4) { Filament() }
        val armed = BooleanArray(2 * TEETH) { true }
        val offsets = doubleArrayOf(0.0, .27)
        val hz = frequencyFor(voice, m.getValue("TUNE"), midi).toDouble()
        val loopHz = if (held) round(hz * period / RATE) * RATE / period else hz
        val wood = Wood(loopHz, body, tooth, voice, if (held) period else 0)
        var angle = -.025
        var speed = launchSpeed(push) * sqrt(velocity.toDouble())
        var temperature = heat
        var time = 0.0
        var inputWork = 0.0
        var dissipated = 0.0
        var correction = 0.0
        var maxError = 0.0
        var bowedEnergy = 0.0
        var snappedEnergy = 0.0
        var releasedEnergy = 0.0
        var maxAttachments = 0
        var sampleIndex = 0L
        val events = ArrayList<Event>()
        val traces = ArrayList<Trace>()
        val initialEnergy = .5 * INERTIA * speed * speed
        private val stride = INTERNAL_RATE / solverRate
        private val dt = 1.0 / solverRate
        private val powered = held && velocity > 0f
        init {
            // Geometry varies with voice/root but remains fixed across macro comparisons.
            val r = Random(Dsp.seedFor("PITCHWHEEL", 1, voice, midi))
            for (j in phases.indices) {
                phases[j] = j * TAU / TEETH + if (j == 0) 0.0 else r.nextDouble(-.016, .016)
                attach[j] = r.nextDouble(); variation[j] = r.nextDouble(.83, 1.17)
            }
        }
        private fun event(kind: EventKind, j: Int, dir: Int, energy: Double = 0.0, finger: Int = -1) {
            if (record) events.add(Event(time, kind, j, dir, energy, finger))
        }
        private fun fingerEnergy() = fingers.filter { it.tooth >= 0 }.sumOf { .5 * stiffness * (angle - it.anchor).pow(2) }
        private fun filamentEnergy() = filaments.filter { it.tooth >= 0 }.sumOf { .5 * it.stiffness * (angle - it.anchor).pow(2) }
        fun mechanicalEnergy() = .5 * INERTIA * speed * speed + fingerEnergy() + filamentEnergy()
        fun totalEnergy() = mechanicalEnergy() + wood.energy()
        private fun crossing(old: Double, now: Double, base: Double, action: (Double) -> Unit) {
            if (now > old) {
                var at = base + (floor((old - base) / TAU) + 1) * TAU
                while (at <= now) { action(at); at += TAU }
            } else if (now < old) {
                var at = base + (ceil((old - base) / TAU) - 1) * TAU
                while (at >= now) { action(at); at -= TAU }
            }
        }
        private fun geometry(old: Double) {
            val dir = if (angle >= old) 1 else -1
            for (f in fingers.indices) {
                val finger = fingers[f]
                if (finger.tooth >= 0) {
                    val x = angle - finger.anchor
                    if (x * finger.direction >= width || x * finger.direction <= -1e-8) {
                        val e = .5 * stiffness * x * x
                        val release = x * finger.direction >= width
                        val send = if (release) e * (.62 + .18 * tooth) else 0.0
                        if (release) {
                            wood.excite(send, direction = finger.direction, variation = variation[finger.tooth], temperature = temperature)
                            releasedEnergy += send; event(EventKind.RELEASE, finger.tooth, finger.direction, send, f)
                        }
                        dissipated += e - send
                        armed[f * TEETH + finger.tooth] = false
                        finger.tooth = -1
                    }
                }
                for (j in phases.indices) {
                    val low = phases[j] + offsets[f]
                    val q = low + round((angle - low - width * .5) / TAU) * TAU
                    if (angle < q - CLEARANCE || angle > q + width + CLEARANCE) armed[f * TEETH + j] = true
                    if (!contacts || finger.tooth >= 0 || !armed[f * TEETH + j]) continue
                    crossing(old, angle, low + if (dir < 0) width else 0.0) { at ->
                        if (finger.tooth < 0) {
                            finger.tooth = j; finger.anchor = angle; finger.direction = dir
                            finger.low = if (dir > 0) at else at - width; finger.high = finger.low + width
                            event(EventKind.CONTACT, j, dir, finger = f)
                        }
                    }
                }
            }
            for (fil in filaments) if (fil.tooth >= 0) {
                val stretch = (angle - fil.anchor) * fil.direction
                val age = time - fil.born
                val snap = stretch >= fil.limit || age > (if (held) .65 else 1.1 + 1.3 * adhesion)
                if (snap || stretch < -.006) {
                    val e = .5 * fil.stiffness * (angle - fil.anchor).pow(2)
                    val send = if (snap && snapSound) .36 * e else 0.0
                    if (send > 0) wood.excite(send, transfer = Transfer.SNAP, direction = fil.direction, variation = 1.0 + adhesion, strain = stretch / fil.limit, temperature = temperature)
                    snappedEnergy += send; dissipated += e - send
                    event(if (snap) EventKind.SNAP else EventKind.DETACH, fil.tooth, dir, send)
                    fil.tooth = -1
                }
            }
            if (resin) for (j in phases.indices) crossing(old, angle, phases[j] + .43) { at ->
                if (dir > 0 && attach[j] < (.08 + .91 * adhesion) * (.85 + .15 * temperature) && filaments.none { it.tooth == j }) {
                    val fil = filaments.firstOrNull { it.tooth < 0 }
                    if (fil != null) {
                        fil.tooth = j; fil.anchor = angle; fil.born = time; fil.direction = dir
                        fil.stiffness = (.3 + 5.2 * adhesion * adhesion) * (if (held) .55 else 1.0)
                        fil.limit = (if (held) .17 + .10 * adhesion else .08 + .40 * adhesion * adhesion) * (1 - .12 * temperature)
                        event(EventKind.ATTACH, j, dir)
                    }
                }
            }
            maxAttachments = max(maxAttachments, filaments.count { it.tooth >= 0 })
        }
        private fun mechanics() {
            val before = mechanicalEnergy()
            var elasticTorque = 0.0
            for (f in fingers) if (f.tooth >= 0) elasticTorque -= stiffness * (angle - f.anchor)
            for (f in filaments) if (f.tooth >= 0) elasticTorque -= f.stiffness * (angle - f.anchor)
            val viscous = (.024 + if (resin) .034 * adhesion else 0.0) * (1 - .67 * temperature)
            val fingerLoss = fingers.count { it.tooth >= 0 } * .16 * speed / (1 + (speed / .5).pow(2))
            val filamentLoss = filaments.count { it.tooth >= 0 } * .02 * speed
            val drag = viscous * speed + (.0045 + .004 * adhesion) * (1 - .55 * temperature) * tanh(speed / .035) + fingerLoss + filamentLoss
            val nfil = filaments.count { it.tooth >= 0 }
            // Regularised slip loss: restoring force remains separate from coloration.
            val slipDrag = if (resin) nfil * (.004 + .016 * adhesion) * tanh(speed / (.13 + .20 * temperature)) else 0.0
            val target = TAU * RATE / period
            val reference = -.025 + target * time
            val drive = if (powered) (.65 * (reference - angle) + .30 * (target - speed) + .09)
                .coerceIn(-.9, .9) else 0.0
            val old = angle
            val proposedSpeed = (speed + (elasticTorque + drive - drag - slipDrag) * dt / INERTIA).coerceIn(-12.0, 12.0)
            // Midpoint drift keeps spring-energy truncation small at either solver rate.
            val step = (speed + proposedSpeed) * .5 * dt
            angle += step; speed = proposedSpeed
            val driveWork = drive * step
            if (driveWork > 0) inputWork += driveWork else dissipated -= driveWork
            val frictionWork = max(0.0, (drag + slipDrag) * step).coerceAtMost(before + max(0.0, driveWork))
            val bowBudget = if (nfil > 0) min(frictionWork * .80,
                max(0.0, (slipDrag + filamentLoss) * step) * .85) else 0.0
            val creakBudget = max(0.0, fingerLoss * step) * (.22 + .30 * tooth)
            val allowed = max(0.0, before + driveWork - frictionWork)
            val after = mechanicalEnergy()
            if (after > allowed + 1e-14) {
                val potential = fingerEnergy() + filamentEnergy()
                if (allowed >= potential) {
                    speed = sign(speed) * sqrt(2 * (allowed - potential) / INERTIA)
                } else {
                // Scale displacement about this step's start and velocity together;
                // bisection projects onto the positive elastic+kinetic energy shell.
                val end = angle; val endSpeed = speed
                var lo = 0.0; var hi = 1.0
                for (k in 0 until 48) {
                    val a = (lo + hi) * .5
                    angle = old + (end - old) * a; speed = endSpeed * a
                    if (mechanicalEnergy() > allowed) hi = a else lo = a
                }
                angle = old + (end - old) * lo; speed = endSpeed * lo
                }
                val removed = max(0.0, after - allowed)
                correction += removed
            }
            val retained = mechanicalEnergy()
            // All work lost from mechanics is either heat/loss or acoustic bow work.
            val lost = max(0.0, before + driveWork - retained)
            val bowWork = min(bowBudget, lost)
            val creakWork = min(creakBudget, max(0.0, lost - bowWork))
            val send = if (bowSound) bowWork else 0.0
            if (send > 0.0) {
                val strain = filaments.filter { it.tooth >= 0 }.sumOf {
                    abs(angle - it.anchor) / it.limit
                } / nfil
                wood.excite(send, transfer = Transfer.SLIP, direction = if (speed >= 0) 1 else -1,
                    strain = strain, temperature = temperature)
                bowedEnergy += send
            }
            if (creakWork > 0.0) wood.excite(creakWork, transfer = Transfer.CREAK,
                direction = if (speed >= 0) 1 else -1, temperature = temperature)
            dissipated += lost - send - creakWork
            val resinDrag = if (resin) .034 * adhesion * (1 - .67 * temperature) * speed +
                .004 * adhesion * (1 - .55 * temperature) * tanh(speed / .035) + slipDrag + filamentLoss else 0.0
            val resinWork = min(frictionWork, max(0.0, resinDrag * step))
            // A diagnostic mute sends the same allocated work to a silent sink;
            // it must not change temperature, encounters or wheel trajectory.
            val warming = max(0.0, resinWork - bowWork) * 2.5
            temperature = (temperature + min(.6 * dt, warming) - (temperature - heat) * dt / 2.2).coerceIn(0.0, 1.0)
            geometry(old)
            time += dt
            maxError = max(maxError, max(0.0, totalEnergy() + dissipated - initialEnergy - inputWork))
        }
        fun tick(): Float {
            if (sampleIndex % stride == 0L) mechanics()
            dissipated += wood.tick()
            if (record && sampleIndex % (INTERNAL_RATE / 100) == 0L) traces.add(
                Trace(time, angle, speed, temperature, mechanicalEnergy(), wood.energy(), inputWork, dissipated, filaments.count { it.tooth >= 0 }))
            sampleIndex++
            return wood.sample()
        }
        fun diagnostics() = Diagnostics(events.toList(), traces.toList(), initialEnergy, inputWork, dissipated,
            maxError, totalEnergy(), maxAttachments, correction, bowedEnergy, snappedEnergy, releasedEnergy)
        fun state(): DoubleArray {
            val a = ArrayList<Double>()
            a.add(angle - TAU * time * RATE / period); a.add(speed); a.add(temperature)
            for (f in fingers) { a.add(f.tooth.toDouble()); a.add(if (f.tooth < 0) 0.0 else angle - f.anchor); a.add(f.direction.toDouble()) }
            for (f in filaments) {
                a.add(f.tooth.toDouble())
                a.add(if (f.tooth < 0) 0.0 else angle - f.anchor)
                a.add(if (f.tooth < 0) 0.0 else time - f.born)
                // Limit remembers temperature at attachment, rather than the
                // current temperature. Include that retained history explicitly.
                a.add(if (f.tooth < 0) 0.0 else f.limit)
                a.add(if (f.tooth < 0) 0.0 else f.stiffness)
                a.add(if (f.tooth < 0) 0.0 else f.direction.toDouble())
            }
            for (b in armed) a.add(if (b) 1.0 else 0.0)
            a.addAll(wood.x.toList()); a.addAll(wood.v.toList())
            a.addAll(wood.pending.toList()); a.addAll(wood.pendingDirection.toList())
            return a.toDoubleArray()
        }
    }

    internal fun finish(raw: FloatArray, normalize: Boolean = false, fade: Boolean = false): FloatArray {
        val filtered = raw.copyOf()
        Tide.bandLimit(filtered, INTERNAL_RATE)
        val out = Dsp.decimate(filtered, RATE)
        val mean = out.sumOf { it.toDouble() } / out.size.coerceAtLeast(1)
        val hp = Dsp.OnePole(RATE)
        for (i in out.indices) { val x = out[i] - mean.toFloat(); out[i] = x - hp.lp(x, 20f) }
        if (normalize) Dsp.levelTo(out, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        if (fade) Dsp.fadeTail(out)
        return out
    }

    /** One contact with no wheel motion, for hearing and measuring attack colour. */
    internal fun acousticProbe(voice: PitchwheelVoice, macros: Map<String, Float> = emptyMap(),
        midiNote: Int = DEFAULT_MIDI, kind: EventKind = EventKind.RELEASE, direction: Int = 1,
        seconds: Double = 1.0): FloatArray {
        val m = settled(voice, macros)
        val wood = Wood(frequencyFor(voice, m.getValue("TUNE"), midiNote).toDouble(),
            m.getValue("BODY").toDouble(), m.getValue("TOOTH").toDouble(), voice)
        val transfer = if (kind == EventKind.SNAP) Transfer.SNAP else Transfer.RELEASE
        wood.excite(.025, transfer, direction, temperature = m.getValue("HEAT").toDouble())
        val raw = FloatArray((seconds * INTERNAL_RATE).roundToInt()) { wood.tick(); wood.sample() }
        return finish(raw)
    }

    internal fun renderProbe(voice: PitchwheelVoice, macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = 1f, seconds: Float? = null,
        normalize: Boolean = false, resin: Boolean = true, bowSound: Boolean = true,
        snapSound: Boolean = true, solverRate: Int = INTERNAL_RATE, contacts: Boolean = true): Probe {
        require(midi in MIDI_MIN..MIDI_MAX) { "PITCHWHEEL midi out of $MIDI_MIN..$MIDI_MAX: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "PITCHWHEEL velocity out of 0..1: $velocity" }
        require(seconds == null || seconds.isFinite() && seconds in .1f..30f)
        require(solverRate in listOf(INTERNAL_RATE, INTERNAL_RATE / 2))
        val m = settled(voice, macros)
        val held = isLoop(m.getValue("HOLD"))
        val hz = frequencyFor(voice, m.getValue("TUNE"), midi).toDouble()
        val duration = TAU / launchSpeed(m.getValue("PUSH").toDouble())
        val period = (round(hz * duration).coerceAtLeast(16.0) * RATE / hz).roundToInt().coerceAtLeast(2048)
        val engine = Engine(voice, m, midi, velocity, held, period, true, resin, contacts, bowSound, snapSound, solverRate)
        if (velocity == 0f) {
            val frames = seconds?.let { (it * RATE).roundToInt() } ?: if (held) period else RATE / 4
            return Probe(FloatArray(frames), engine.diagnostics(),
                previousCycle = if (held && seconds == null) FloatArray(frames) else FloatArray(0))
        }
        if (held && seconds == null && velocity > 0f) {
            var previous = FloatArray(period * Dsp.OVERSAMPLE)
            var current = previous
            var oldState = engine.state()
            var stateError = Double.POSITIVE_INFINITY
            var cycles = 0
            while (cycles < 20) {
                previous = current
                current = FloatArray(period * Dsp.OVERSAMPLE) { engine.tick() }
                val state = engine.state()
                stateError = state.indices.maxOf { abs(state[it] - oldState[it]) }
                oldState = state; cycles++
                if (cycles >= 6 && stateError < 2e-5) break
            }
            check(stateError < 1e-3) { "PITCHWHEEL $voice held object failed to settle: $stateError" }
            val buffer = finish(previous + current)
            // Unity-sum wrap after convergence, using genuinely preceding material.
            val need = min(1280, period / 3)
            val fade = need - 256
            for (i in 0 until need) {
                val w = if (i >= fade) 1.0 else (i.toDouble() / fade).let { it * it * (3 - 2 * it) }
                val src = period - need + i; val dst = buffer.size - need + i
                buffer[dst] = ((1 - w) * buffer[dst] + w * buffer[src]).toFloat()
            }
            if (normalize) Dsp.levelTo(buffer, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
            val seam = Keys.seamError(buffer, period)
            check(seam < Keys.MAX_SEAM_ERROR) { "PITCHWHEEL $voice held seam $seam" }
            return Probe(buffer.copyOfRange(period, buffer.size), engine.diagnostics(),
                buffer.copyOfRange(0, period), seam, stateError, cycles)
        }
        val frames = ((seconds ?: MAX_SECONDS) * INTERNAL_RATE).toInt()
        val raw = FloatArray(frames)
        var length = frames
        for (i in raw.indices) {
            raw[i] = engine.tick()
            if (seconds == null && !held && i > 2 * INTERNAL_RATE && i % 1024 == 0 && engine.totalEnergy() < 1e-10) {
                length = i + 1; break
            }
        }
        val out = finish(if (length == frames) raw else raw.copyOf(length), normalize)
        return Probe(out, engine.diagnostics())
    }

    fun render(voice: PitchwheelVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI, velocity: Float = 1f): Snip {
        val p = renderProbe(voice, macros, midi, velocity, normalize = true)
        val out = p.samples
        if (!isLoop(settled(voice, macros).getValue("HOLD"))) Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
