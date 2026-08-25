package com.snipsnap.shell

/**
 * Voice allocation for play mode — every decision the audio engine needs,
 * none of the audio. The app's Oboe layer asks this "what happens when pad
 * N is hit?" and gets back exactly which voices to start and stop; latency
 * work stays in native land, correctness lives here under test.
 *
 * Rules, matching how the MPC treats pads (and what the exported kits
 * assume):
 * - Pads in the same non-zero **mute group** choke each other — including
 *   a pad re-triggering itself. That's what makes a closed hat cut a
 *   ringing open one.
 * - Pads outside any group **overlap** on re-trigger (natural for cymbals
 *   and toms), up to the voice cap.
 * - **One-shot** pads ignore note-off; gate pads stop on it.
 * - At the cap the **oldest voice is stolen** — the ear misses old tails
 *   least.
 */
class VoiceAllocator(private val maxVoices: Int = DEFAULT_MAX_VOICES) {

    init {
        require(maxVoices > 0) { "maxVoices must be positive: $maxVoices" }
    }

    /** A sounding voice, identified to the audio engine by [id]. */
    data class Voice(
        val id: Int,
        val padSlot: Int,
        val muteGroup: Int,
        val oneShot: Boolean,
        val velocity: Float,
        /** Monotonic trigger order — allocation age, not wall time. */
        val serial: Long,
    )

    /** What the audio engine must do about one pad hit. */
    data class Allocation(
        /** Start this voice. */
        val started: Voice,
        /** Choked by the mute group: stop with a short fade, not a cut. */
        val choked: List<Voice>,
        /** Stolen for the voice cap: steal-fade and reuse. */
        val stolen: List<Voice>,
    )

    private val active = LinkedHashMap<Int, Voice>() // id -> voice, insertion = age order
    private var nextId = 1
    private var nextSerial = 1L

    val activeVoices: List<Voice> get() = active.values.toList()
    val activeCount: Int get() = active.size

    /**
     * Pad hit. [muteGroup] 0 = none; [oneShot] false = gate (stops on
     * [noteOff]).
     */
    fun noteOn(
        padSlot: Int,
        velocity: Float = 1f,
        muteGroup: Int = 0,
        oneShot: Boolean = true,
    ): Allocation {
        require(velocity in 0f..1f) { "velocity 0..1, got $velocity" }

        val choked = if (muteGroup != 0) {
            active.values.filter { it.muteGroup == muteGroup }
        } else {
            emptyList()
        }
        choked.forEach { active.remove(it.id) }

        val stolen = mutableListOf<Voice>()
        while (active.size >= maxVoices) {
            val oldest = active.values.first()
            active.remove(oldest.id)
            stolen += oldest
        }

        val voice = Voice(nextId++, padSlot, muteGroup, oneShot, velocity, nextSerial++)
        active[voice.id] = voice
        return Allocation(voice, choked, stolen)
    }

    /**
     * Pad released. Returns the voices to stop — only this pad's gate
     * voices; one-shots play out.
     */
    fun noteOff(padSlot: Int): List<Voice> {
        val stopping = active.values.filter { it.padSlot == padSlot && !it.oneShot }
        stopping.forEach { active.remove(it.id) }
        return stopping
    }

    /** The audio engine reports a sample played to its end. */
    fun voiceEnded(id: Int) {
        active.remove(id)
    }

    /** Stop everything (leaving play mode, panic). Returns what was sounding. */
    fun allOff(): List<Voice> {
        val all = active.values.toList()
        active.clear()
        return all
    }

    companion object {
        /**
         * 32 covers a two-handed player with long tails to spare; the MPC
         * hardware itself allocates far more, but a phone's mixer budget
         * is the constraint here.
         */
        const val DEFAULT_MAX_VOICES = 32
    }
}
