package com.snipsnap.synth

/**
 * The four CALABASH kits as the owner heard and approved them on 2026-11-15 (round 0, `prototype/calabash/kits.py`
 * at 967f53f7f): sixteen pads each, A01 bottom left, every pad one CALABASH voice at the kit's settings, ported pad
 * for pad. Round 1 renders them for the A/B against the prototype; round 2 puts them on the shelf.
 *
 * - [Kit.AFROBEAT], 112 BPM: Lagos in the 1970s, a live rhythm section in one room, sixteen-track tape.
 * - [Kit.AFROBEATS], 104 BPM: today's Lagos and Accra pop, programmed, close and dry, a clean chain.
 * - [Kit.MBALAX], 126 BPM: Dakar's dance bands, a sabar ensemble and a tama over a kit, a bright live room.
 * - [Kit.AFROCUBAN], 176 BPM: New York in the 1950s, a Latin section beside a jazz drummer, valve and tape.
 *
 * Each pad renders three velocity zones (the audition's 0.35, 0.65, 1.0) of four takes; take t is engine take t.
 * The roll or riser on every kit's A16 is the percussion gate.
 */
object CalabashKits {
    /** Bump when a pad's audio changes; saved kits carry the version they were printed with. */
    const val VERSION = 1

    enum class Kit(val id: String, val title: String, val bpm: Int) {
        AFROBEAT("calabash-afrobeat", "Afrobeat", 112),
        AFROBEATS("calabash-afrobeats", "Afrobeats", 104),
        MBALAX("calabash-mbalax", "Mbalax", 126),
        AFROCUBAN("calabash-afrocuban", "Afro-Cuban Jazz", 176),
    }

    val velocities = listOf(.35f, .65f, 1f)
    const val TAKES = 4
    const val PADS = 16

    /** One pad: its name, the voice, and the voice's settings (the rest at the voice's defaults). */
    class Pad(val name: String, val voice: CalabashVoice, val macros: Map<String, Float>)

    fun pads(kit: Kit): List<Pad> = when (kit) {
        Kit.AFROBEAT -> afrobeatPads; Kit.AFROBEATS -> afrobeatsPads; Kit.MBALAX -> mbalaxPads; Kit.AFROCUBAN -> afrocubanPads
    }

    /** The hand-drum and sabar STROKE anchors the kits sit on: open (and the sabar's hand slap), muffled (open stick). */
    private const val O = 1f / 3f
    private const val M = 2f / 3f

    private val afrobeatPads = listOf(
        Pad("Kick", CalabashVoice.KICK, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "TUNE" to 0.45f, "DAMP" to 0.55f, "BEATER" to 0.45f)),
        Pad("Snare", CalabashVoice.SNARE, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "TUNE" to 0.7f, "DAMP" to 0.35f, "WIRES" to 0.45f)),
        Pad("Rimshot", CalabashVoice.RIMSHOT, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "TUNE" to 0.7f, "DAMP" to 0.4f, "WIRES" to 0.4f)),
        Pad("Hat Closed", CalabashVoice.HAT_CLOSED, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "TUNE" to 0.55f, "DAMP" to 0.35f)),
        Pad("Hat Half Open", CalabashVoice.HAT_OPEN, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "TUNE" to 0.55f, "DAMP" to 0.55f)),
        Pad("Hat Foot", CalabashVoice.HAT_FOOT, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "TUNE" to 0.55f, "DAMP" to 0.3f)),
        Pad("Rack Tom", CalabashVoice.TOM, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "TUNE" to 0.62f, "DAMP" to 0.45f)),
        Pad("Floor Tom", CalabashVoice.TOM, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "TUNE" to 0.3f, "DAMP" to 0.45f)),
        Pad("Shekere", CalabashVoice.SHEKERE, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "STROKE" to 0.0f, "DAMP" to 0.3f)),
        Pad("Shekere Slap", CalabashVoice.SHEKERE, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "STROKE" to 1.0f, "DAMP" to 0.3f)),
        Pad("Conga Open", CalabashVoice.CONGA, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "STROKE" to O)),
        Pad("Conga Slap", CalabashVoice.CONGA, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "STROKE" to 1.0f)),
        Pad("Tumba Open", CalabashVoice.TUMBA, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "STROKE" to O, "TUNE" to 0.45f)),
        Pad("Sticks", CalabashVoice.STICKS, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "TUNE" to 0.45f, "DAMP" to 0.45f)),
        Pad("Bell", CalabashVoice.BIG_BELL, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "STROKE" to 0.0f, "TUNE" to 0.45f)),
        Pad("Shekere Rush", CalabashVoice.SHEKERE_RUSH, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "SPEED" to 0.5f)),
    )
    private val afrobeatsPads = listOf(
        Pad("Deep Kick", CalabashVoice.DEEP_KICK, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "TUNE" to 0.45f, "DAMP" to 0.45f, "CLICK" to 0.4f)),
        Pad("Rim", CalabashVoice.RIM, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "TUNE" to 0.6f, "DAMP" to 0.4f)),
        Pad("Clap", CalabashVoice.CLAP, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "CROWD" to 0.35f, "TUNE" to 0.5f)),
        Pad("Snaps", CalabashVoice.SNAP, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "CROWD" to 0.4f, "TUNE" to 0.55f)),
        Pad("Shaker", CalabashVoice.SHAKER, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "STROKE" to 0.0f, "TUNE" to 0.55f)),
        Pad("Shaker Back", CalabashVoice.SHAKER, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "STROKE" to 0.5f, "TUNE" to 0.55f)),
        Pad("Hat", CalabashVoice.HAT_CLOSED, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "TUNE" to 0.65f, "DAMP" to 0.5f)),
        Pad("Open Hat", CalabashVoice.HAT_OPEN, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "TUNE" to 0.65f, "DAMP" to 0.65f)),
        Pad("Conga Slap", CalabashVoice.CONGA, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "STROKE" to 1.0f, "TUNE" to 0.6f)),
        Pad("Quinto", CalabashVoice.QUINTO, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "STROKE" to O, "TUNE" to 0.55f)),
        Pad("Tumba", CalabashVoice.TUMBA, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "STROKE" to O, "TUNE" to 0.4f)),
        Pad("Talking Drum", CalabashVoice.DUNDUN, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "SQUEEZE" to 0.4f, "CONTOUR" to 0.0f)),
        Pad("Talking Drum Fall", CalabashVoice.DUNDUN, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "SQUEEZE" to 0.6f, "CONTOUR" to 0.5f, "TUNE" to 0.55f)),
        Pad("Bell", CalabashVoice.AGOGO_HIGH, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "TUNE" to 0.55f, "DAMP" to 0.4f)),
        Pad("Shekere", CalabashVoice.SHEKERE, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "STROKE" to 0.5f, "TUNE" to 0.6f, "DAMP" to 0.45f)),
        Pad("Talking Call", CalabashVoice.TALKING_CALL, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "SPEED" to 0.5f, "SQUEEZE" to 0.6f)),
    )
    private val mbalaxPads = listOf(
        Pad("Kick", CalabashVoice.KICK, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "TUNE" to 0.55f, "DAMP" to 0.6f, "BEATER" to 0.8f)),
        Pad("Snare", CalabashVoice.SNARE, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "TUNE" to 0.85f, "DAMP" to 0.4f, "WIRES" to 0.6f)),
        Pad("Hat", CalabashVoice.HAT_CLOSED, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "TUNE" to 0.6f, "DAMP" to 0.4f)),
        Pad("Open Hat", CalabashVoice.HAT_OPEN, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "TUNE" to 0.6f, "DAMP" to 0.5f)),
        Pad("Nder Stick", CalabashVoice.NDER, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "STROKE" to M)),
        Pad("Nder Slap", CalabashVoice.NDER, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "STROKE" to O)),
        Pad("Nder Pressed", CalabashVoice.NDER, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "STROKE" to 1.0f)),
        Pad("Mbeng Open", CalabashVoice.MBENG, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "STROKE" to 0.0f)),
        Pad("Mbeng Stick", CalabashVoice.MBENG, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "STROKE" to M)),
        Pad("Col Bass", CalabashVoice.COL, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "STROKE" to 0.0f)),
        Pad("Col Stick", CalabashVoice.COL, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "STROKE" to M)),
        Pad("Tama", CalabashVoice.TAMA, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "SQUEEZE" to 0.3f, "CONTOUR" to 0.0f)),
        Pad("Tama Talk", CalabashVoice.TAMA, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "SQUEEZE" to 0.75f, "CONTOUR" to 1.0f, "TUNE" to 0.45f)),
        Pad("High Tom", CalabashVoice.TOM, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "TUNE" to 0.85f, "DAMP" to 0.5f)),
        Pad("Crash", CalabashVoice.CRASH, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "TUNE" to 0.55f, "DAMP" to 0.35f)),
        Pad("Sabar Roll", CalabashVoice.SABAR_ROLL, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "SPEED" to 0.6f)),
    )
    private val afrocubanPads = listOf(
        Pad("Kick", CalabashVoice.KICK, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "TUNE" to 0.65f, "DAMP" to 0.2f, "BEATER" to 0.15f)),
        Pad("Snare", CalabashVoice.SNARE, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "TUNE" to 0.5f, "DAMP" to 0.3f, "WIRES" to 0.55f)),
        Pad("Ride", CalabashVoice.RIDE, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "TUNE" to 0.5f, "DAMP" to 0.3f)),
        Pad("Hat Foot", CalabashVoice.HAT_FOOT, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "TUNE" to 0.45f, "DAMP" to 0.3f)),
        Pad("Conga Open", CalabashVoice.CONGA, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to O)),
        Pad("Conga Slap", CalabashVoice.CONGA, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to 1.0f)),
        Pad("Tumba Open", CalabashVoice.TUMBA, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to O)),
        Pad("Quinto Open", CalabashVoice.QUINTO, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to O)),
        Pad("Conga Heel", CalabashVoice.CONGA, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to 0.0f)),
        Pad("Bongo High", CalabashVoice.BONGO_HIGH, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to O)),
        Pad("Bongo Low", CalabashVoice.BONGO_LOW, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to O)),
        Pad("Timbale", CalabashVoice.TIMBALE, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to 0.0f, "TUNE" to 0.55f)),
        Pad("Paila", CalabashVoice.TIMBALE, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to 1.0f, "TUNE" to 0.55f)),
        Pad("Mambo Bell", CalabashVoice.COWBELL, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to 0.0f, "TUNE" to 0.45f)),
        Pad("Clave", CalabashVoice.CLAVE, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "TUNE" to 0.5f, "DAMP" to 0.3f)),
        Pad("Abanico", CalabashVoice.ABANICO, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "SPEED" to 0.55f, "TUNE" to 0.55f)),
    )
}
