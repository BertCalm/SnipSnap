package com.snipsnap.app

/**
 * Bridge for a dedicated RR PadEngine handle with a validated, committed bank.
 * Each controller requires its own freshly created native handle. The future
 * loader must destroy the old handle on bank reload and create a fresh handle;
 * it must not reuse an old ended-ID queue with a new controller. Keep the handle
 * alive and serialize all bank/stream operations with this controller. Legacy
 * InstrumentPlayer and scheduled pad producers must use different handles.
 */
internal class NativeMurmurRoundRobinPort(private val handle: Long) : MurmurRoundRobinAdmission.Port {
    init { require(handle != 0L) }

    override fun admit(chord: List<MurmurRoundRobinAdmission.Command>): Boolean = NativePads.admitRoundRobinChord(
        handle, chord.map { it.voiceId }.toIntArray(), chord.map { it.sample }.toIntArray(),
        chord.map { it.start }.toLongArray(), chord.map { it.end }.toLongArray(),
        chord.map { it.loopStart }.toLongArray(), chord.map { it.gain }.toFloatArray(),
        chord.map { it.pitch }.toDoubleArray(),
    )
    override fun stopVoice(voiceId: Int, fadeMs: Float): Boolean = NativePads.stopVoice(handle, voiceId, fadeMs)
    override fun drainEnded(): IntArray = NativePads.drainEnded(handle)
}
