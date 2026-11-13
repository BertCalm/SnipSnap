#pragma once
#include <memory>
#include <vector>
#include <exception>
#include <cstdint>

// Small exception boundaries shared with deterministic host fakes. No audio-thread work.
// A staged failure is terminal: callers destroy that dedicated handle, never reuse its bank.
namespace snipsnap::rr_loading {
template<class Engine> Engine* prepare(int32_t rate) noexcept {
    try {
        auto owned = std::make_unique<Engine>(rate);
        owned->beginBank();
        return owned.release();
    } catch (...) { return nullptr; }
}
template<class Engine> int32_t add(Engine& engine, std::vector<float>&& pcm) noexcept {
    try { return engine.addSample(std::move(pcm), 1, 44100); }
    catch (...) { return -1; }
}
template<class Engine> bool commit(Engine& engine) noexcept {
    try { engine.commitBank(); return true; }
    catch (...) { return false; }
}
template<class Engine> bool start(Engine& engine) noexcept {
    try { if (engine.start()) return true; }
    catch (...) { }
    // stop() closes any partially opened stream and releases prepared DSP scratch.
    // PadEngine::stop and its destructor are nonthrowing lifecycle operations.
    engine.stop();
    return false;
}
}
