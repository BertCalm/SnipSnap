package com.snipsnap.synth

import java.util.concurrent.ConcurrentHashMap

/**
 * Every MERCURY pad zone at its voice's defaults, rendered once per test JVM and shared: a zone is about 1.6 s of
 * rendering and the 54 of them (six voices, nine keys each) are wanted by `MercuryHeldTest` and the audition generator;
 * `InstrumentSuiteTest` (the instruments and their sidecar) reads the 27 of the three shipped voices. The renders are deterministic, so sharing one changes nothing a test sees.
 * The notes' arrays are shared too: read them, do not write to them.
 */
internal object MercuryPadZones {

    private val cache = ConcurrentHashMap<Pair<MercuryVoice, Int>, KeyNote>()

    fun note(voice: MercuryVoice, midi: Int): KeyNote = cache.computeIfAbsent(voice to midi) { Keys.mercuryPad(voice, emptyMap(), midi) }

    /** All 54 zones, rendering any that have not been, across the cores (each zone is independent). */
    fun all(): Map<Pair<MercuryVoice, Int>, KeyNote> =
        MercuryVoice.entries.flatMap { v -> Keys.mercuryPadMidis(v).map { m -> v to m } }
            .parallelStream().map { (v, m) -> (v to m) to note(v, m) }.toList().toMap()
}
