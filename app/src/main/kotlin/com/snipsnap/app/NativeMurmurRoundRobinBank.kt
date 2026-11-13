package com.snipsnap.app

/** The owned-player backend. Each prepare call creates a fresh dedicated native engine. */
internal object NativeMurmurRoundRobinBank : MurmurRoundRobinOwnedPlayer.Backend {
    override fun prepare(rate: Int) = NativePads.prepareRoundRobinBank(rate)
    override fun add(handle: Long, pcm: FloatArray) = NativePads.addRoundRobinSample(handle, pcm)
    override fun commit(handle: Long) = NativePads.commitRoundRobinBank(handle)
    override fun start(handle: Long) = NativePads.startRoundRobinBank(handle)
    override fun needsRestart(handle: Long) = NativePads.needsRestart(handle)
    override fun port(handle: Long): MurmurRoundRobinAdmission.Port = NativeMurmurRoundRobinPort(handle)
    override fun destroy(handle: Long) = NativePads.destroy(handle)
}
