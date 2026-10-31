package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.kit.MidiWire
import kotlin.math.abs

/**
 * One learned mapping: a control on a keyboard drives one parameter.
 *
 * A record, not screen state (`controller-profiles.md` §5): [source] says
 * which message from which device, [target] names the parameter in words
 * the screen chose ("surface.grain", "keys.gain"), and the rest says how
 * the message becomes a value. Values at this layer are 0..1; [min] and
 * [max] narrow that range (a knob that only sweeps the useful half), and
 * the screen turns 0..1 into its own units.
 */
data class MidiBinding(
    val source: Source,
    val target: String,
    val mode: Mode,
    val takeover: Takeover = Takeover.PICKUP,
    val min: Float = 0f,
    val max: Float = 1f,
) {
    init {
        require(target.isNotBlank()) { "a binding needs a target" }
        require(min in 0f..1f && max in 0f..1f && min <= max) { "range $min..$max is not inside 0..1" }
        require(mode in modesFor(source.kind)) { "$mode does not apply to a ${source.kind}" }
    }

    /**
     * Which message: [device] is the sender's `MidiFingerprint.key`, or null
     * for any device; [number] is the controller, note or program number,
     * and 0 for the kinds that have none (pitch bend, channel pressure).
     */
    data class Source(val device: String?, val kind: Kind, val channel: Int, val number: Int) {
        /** This source, from any device; what a binding learned with no device known is keyed by. */
        fun anyDevice(): Source = copy(device = null)
    }

    enum class Kind { NOTE, CC, PITCH, CHANNEL_PRESSURE, POLY_PRESSURE, PROGRAM }

    enum class Mode {
        /** The control's position is the value (a knob, a fader, a strip). */
        ABSOLUTE,

        /** An endless encoder sending 1..63 up and 65..127 down (two's complement). */
        RELATIVE_TWOS,

        /** An endless encoder sending 65.. up and ..63 down, 64 at rest (binary offset). */
        RELATIVE_BINOFFSET,

        /** Each press flips the value between [min] and [max]. */
        TOGGLE,

        /** [max] while held, [min] when let go. */
        MOMENTARY,

        /** Each press fires the target once; it has no value. */
        TRIGGER,
    }

    /**
     * What an absolute control does when its position and the parameter
     * disagree, as after a project loads.
     */
    enum class Takeover {
        /** The parameter jumps to the control. */
        JUMP,

        /** The control does nothing until it passes the parameter's value, then follows (soft takeover). */
        PICKUP,
    }

    companion object {
        /** The modes that make sense for each kind of message. */
        fun modesFor(kind: Kind): Set<Mode> = when (kind) {
            Kind.CC -> Mode.entries.toSet()
            Kind.NOTE -> setOf(Mode.TRIGGER, Mode.TOGGLE, Mode.MOMENTARY)
            Kind.PROGRAM -> setOf(Mode.TRIGGER)
            Kind.PITCH, Kind.CHANNEL_PRESSURE, Kind.POLY_PRESSURE -> setOf(Mode.ABSOLUTE)
        }

        /** The mode a freshly learned control gets: a press fires, anything continuous follows. */
        fun defaultMode(kind: Kind): Mode = when (kind) {
            Kind.NOTE, Kind.PROGRAM -> Mode.TRIGGER
            else -> Mode.ABSOLUTE
        }
    }
}

/**
 * MIDI learn: tap a parameter, move a control, and from then on that
 * control drives it.
 *
 * Every keyboard puts its knobs on different numbers, and the MiniLab 3's
 * own are user-editable, so a fixed list would be wrong for most players.
 * This learns instead, and holds the rules every screen shares:
 *
 *  - **One to one.** Learning a control for a target replaces any binding
 *    that control or that target already had, so one knob drives one thing
 *    and moving it never surprises two parameters.
 *  - **Soft takeover.** An absolute control bound with [MidiBinding.Takeover.PICKUP]
 *    does nothing until it passes the parameter's current value, so loading
 *    a project, or a finger moving the same parameter on screen, does not
 *    make the next knob turn jump. [uncatch] says the two have drifted apart.
 *  - **Relative encoders** add their steps to the current value, so they
 *    never need takeover.
 *  - **A bound message is consumed**: a pad learned as a trigger stops playing
 *    its note. The panics (CC 120 and 123) are never learned, so they always
 *    reach the screens' own rules.
 *
 * The control the player just moved to learn it is live at once: learning
 * applies its value and marks it caught, the way the player expects after
 * turning it. Not here yet: 14-bit CC and NRPN pairs, curves, value feedback
 * to the controller, per-project profiles.
 *
 * Main thread only, like the screens that feed it.
 */
class MidiLearn(bindings: List<MidiBinding> = emptyList()) {

    sealed interface Action {
        val target: String
    }

    /** Set [target] to [value], 0..1. */
    data class SetValue(override val target: String, val value: Float) : Action

    /** A trigger fired: do [target]'s action once. */
    data class Fire(override val target: String) : Action

    /**
     * What one event did: the [actions] to apply, whether it was [consumed]
     * (bound or used to learn, so the screen's own rules must not also play
     * it), and the binding it [learned], if it completed a learn.
     */
    data class Result(val actions: List<Action>, val consumed: Boolean, val learned: MidiBinding? = null) {
        companion object {
            val NONE = Result(emptyList(), consumed = false)
        }
    }

    private val list = ArrayList(bindings)

    /** Sources whose control has met its parameter since the last [uncatch]. */
    private val caught = HashSet<MidiBinding.Source>()

    /** Each source's last position as a target value, for pickup's crossing test. */
    private val lastValue = HashMap<MidiBinding.Source, Float>()

    /** Which source last pressed (CC at or above 64), so a held button toggles once, not on every repeat. */
    private val pressed = HashSet<MidiBinding.Source>()

    val bindings: List<MidiBinding> get() = list.toList()

    /** The target waiting for a control, or null when not learning. */
    var armed: String? = null
        private set

    /** The mode [armed]'s control gets, where its kind allows it. */
    private var prefer: MidiBinding.Mode? = null

    /**
     * The next learnable message binds to [target]. [prefer] is the mode the
     * target wants (a HOLD wants MOMENTARY, so a key holds rather than fires);
     * a control whose kind cannot have it gets its kind's default instead.
     */
    fun arm(target: String, prefer: MidiBinding.Mode? = null) {
        require(target.isNotBlank()) { "a binding needs a target" }
        armed = target
        this.prefer = prefer
    }

    fun disarm() {
        armed = null
        prefer = null
    }

    /** Drop [target]'s binding, if it has one. */
    fun forget(target: String) {
        list.removeAll { it.target == target }
    }

    /** Replace [target]'s binding with [binding] (a changed mode, range or takeover). */
    fun update(binding: MidiBinding) {
        list.removeAll { it.target == binding.target || it.source == binding.source }
        list += binding
        caught -= binding.source
    }

    /** [target] was set by something else (a finger, a loaded project): its controls pick it up again. */
    fun uncatch(target: String) {
        list.filter { it.target == target }.forEach { caught -= it.source }
    }

    /** Every control picks up again; after a project loads. */
    fun uncatchAll() {
        caught.clear()
    }

    /**
     * One event from [device] (its fingerprint key, or null when unknown).
     * [current] reads a target's value, 0..1, or null when the screen has no
     * such parameter showing; pickup and the relative and toggle modes need it.
     */
    fun handle(event: MidiWire.Event, device: String?, current: (target: String) -> Float?): Result {
        val heard = sourceOf(event, device) ?: return Result.NONE
        val armedTarget = armed
        if (armedTarget != null && learnable(event)) return learn(armedTarget, heard, event)
        val binding = list.firstOrNull { it.source == heard } ?: list.firstOrNull { it.source == heard.anyDevice() }
            ?: return Result.NONE
        return Result(apply(binding, heard, event, current), consumed = true)
    }

    private fun learn(target: String, source: MidiBinding.Source, event: MidiWire.Event): Result {
        val mode = prefer?.takeIf { it in MidiBinding.modesFor(source.kind) } ?: MidiBinding.defaultMode(source.kind)
        val binding = MidiBinding(source, target, mode)
        list.removeAll { it.target == target || it.source == source }
        list += binding
        disarm()
        caught += source
        val actions = when (binding.mode) {
            MidiBinding.Mode.TRIGGER -> emptyList()
            else -> listOfNotNull(position(event)?.let { SetValue(target, scale(binding, it)) })
        }
        position(event)?.let { lastValue[source] = scale(binding, it) }
        return Result(actions, consumed = true, learned = binding)
    }

    private fun apply(binding: MidiBinding, source: MidiBinding.Source, event: MidiWire.Event, current: (String) -> Float?): List<Action> {
        val target = binding.target
        return when (binding.mode) {
            MidiBinding.Mode.ABSOLUTE -> {
                val raw = position(event) ?: return emptyList()
                val value = scale(binding, raw)
                val previous = lastValue.put(source, value)
                if (binding.takeover == MidiBinding.Takeover.JUMP || source in caught) {
                    caught += source
                    return listOf(SetValue(target, value))
                }
                val now = current(target)
                val met = now == null || abs(value - now) <= PICKUP_TOLERANCE ||
                    (previous != null && (previous - now) * (value - now) <= 0f)
                if (!met) return emptyList()
                caught += source
                listOf(SetValue(target, value))
            }
            MidiBinding.Mode.RELATIVE_TWOS, MidiBinding.Mode.RELATIVE_BINOFFSET -> {
                val raw = (event as? MidiWire.Event.Control)?.value ?: return emptyList()
                val steps = if (binding.mode == MidiBinding.Mode.RELATIVE_TWOS) twosSteps(raw) else raw - BINOFFSET_REST
                if (steps == 0) return emptyList()
                val now = current(target) ?: binding.min
                listOf(SetValue(target, (now + steps * RELATIVE_STEP).coerceIn(binding.min, binding.max)))
            }
            MidiBinding.Mode.TOGGLE -> {
                if (!press(source, event)) return emptyList()
                val now = current(target) ?: binding.min
                val middle = (binding.min + binding.max) / 2f
                listOf(SetValue(target, if (now >= middle) binding.min else binding.max))
            }
            MidiBinding.Mode.MOMENTARY -> when (val down = isDown(event)) {
                null -> emptyList()
                else -> listOf(SetValue(target, if (down) binding.max else binding.min))
            }
            MidiBinding.Mode.TRIGGER -> if (event is MidiWire.Event.ProgramChange || press(source, event)) listOf(Fire(target)) else emptyList()
        }
    }

    /** A press edge: a note-on, or a CC rising through 64; a held CC button's repeats do not count again. */
    private fun press(source: MidiBinding.Source, event: MidiWire.Event): Boolean = when (event) {
        is MidiWire.Event.NoteOn -> true
        is MidiWire.Event.Control -> if (event.value >= MidiWire.CC_SWITCH_ON) pressed.add(source) else {
            pressed -= source
            false
        }
        else -> false
    }

    /** Held (true), let go (false), or not a button message (null). */
    private fun isDown(event: MidiWire.Event): Boolean? = when (event) {
        is MidiWire.Event.NoteOn -> true
        is MidiWire.Event.NoteOff -> false
        is MidiWire.Event.Control -> event.value >= MidiWire.CC_SWITCH_ON
        else -> null
    }

    companion object {
        /** How far a relative encoder moves its target per step: 128 steps from end to end. */
        const val RELATIVE_STEP = 1f / 128f

        /** How close an absolute control must come to the parameter to pick it up without crossing it. */
        const val PICKUP_TOLERANCE = 1.5f / 127f

        private const val BINOFFSET_REST = 64

        /** Two's complement in seven bits: 1..63 up, 65..127 down, 0 and 64 no move. */
        fun twosSteps(raw: Int): Int = when {
            raw in 1..63 -> raw
            raw in 65..127 -> raw - 128
            else -> 0
        }

        /**
         * The source an event would bind, or null for messages nothing can
         * learn or drive (real-time, a note-off has a source only for the
         * buttons it lets go).
         */
        fun sourceOf(event: MidiWire.Event, device: String?): MidiBinding.Source? = when (event) {
            is MidiWire.Event.NoteOn -> MidiBinding.Source(device, MidiBinding.Kind.NOTE, event.channel, event.note)
            is MidiWire.Event.NoteOff -> MidiBinding.Source(device, MidiBinding.Kind.NOTE, event.channel, event.note)
            is MidiWire.Event.Control -> MidiBinding.Source(device, MidiBinding.Kind.CC, event.channel, event.controller)
            is MidiWire.Event.PitchBend -> MidiBinding.Source(device, MidiBinding.Kind.PITCH, event.channel, 0)
            is MidiWire.Event.ChannelPressure -> MidiBinding.Source(device, MidiBinding.Kind.CHANNEL_PRESSURE, event.channel, 0)
            is MidiWire.Event.PolyPressure -> MidiBinding.Source(device, MidiBinding.Kind.POLY_PRESSURE, event.channel, event.note)
            is MidiWire.Event.ProgramChange -> MidiBinding.Source(device, MidiBinding.Kind.PROGRAM, event.channel, event.program)
            is MidiWire.Event.RealTime -> null
        }

        /**
         * Whether a message can complete a learn: anything with a source
         * except a note-off (the learn already happened on its note-on) and
         * the two panics, which must always reach the screens.
         */
        fun learnable(event: MidiWire.Event): Boolean = when (event) {
            is MidiWire.Event.NoteOff, is MidiWire.Event.RealTime -> false
            is MidiWire.Event.Control ->
                event.controller != MidiWire.CC_ALL_NOTES_OFF && event.controller != MidiWire.CC_ALL_SOUND_OFF
            else -> true
        }

        /** A continuous message's position, 0..1; null for the ones that have none. */
        fun position(event: MidiWire.Event): Float? = when (event) {
            is MidiWire.Event.Control -> event.value / 127f
            is MidiWire.Event.PitchBend -> (event.value + MidiWire.BEND_CENTRE) / 16383f
            is MidiWire.Event.ChannelPressure -> event.value / 127f
            is MidiWire.Event.PolyPressure -> event.value / 127f
            else -> null
        }

        /**
         * A source as the learn panel names it: `CC 74 CH 1`, `NOTE C3 CH 10`,
         * `BEND CH 1`. Channels count from 1, as a keyboard's own screen does.
         */
        fun describe(source: MidiBinding.Source): String {
            val ch = "CH ${source.channel + 1}"
            return when (source.kind) {
                MidiBinding.Kind.NOTE -> "NOTE ${Scales.nameOf(source.number)} $ch"
                MidiBinding.Kind.CC -> "CC ${source.number} $ch"
                MidiBinding.Kind.PITCH -> "BEND $ch"
                MidiBinding.Kind.CHANNEL_PRESSURE -> "PRESSURE $ch"
                MidiBinding.Kind.POLY_PRESSURE -> "PRESSURE ${Scales.nameOf(source.number)} $ch"
                MidiBinding.Kind.PROGRAM -> "PROGRAM ${source.number + 1} $ch"
            }
        }

        /**
         * The last message heard, as the learn panel shows it live: the
         * source and, for a continuous one, where it sits (`CC 74 CH 1  64`);
         * null for the messages nothing can learn.
         */
        fun heard(event: MidiWire.Event): String? {
            val source = sourceOf(event, null) ?: return null
            val name = describe(source)
            return when (event) {
                is MidiWire.Event.Control -> "$name  ${event.value}"
                is MidiWire.Event.PitchBend -> "$name  ${event.value}"
                is MidiWire.Event.ChannelPressure -> "$name  ${event.value}"
                is MidiWire.Event.PolyPressure -> "$name  ${event.value}"
                is MidiWire.Event.NoteOff -> "$name  OFF"
                else -> name
            }
        }

        private fun scale(binding: MidiBinding, position: Float): Float =
            binding.min + position.coerceIn(0f, 1f) * (binding.max - binding.min)
    }
}
