// SPDX-License-Identifier: MIT
// Host callback-policy timing only: no JNI, voice mixing, Oboe or PRINT copying.
#include "aquarium/PerformanceBus.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <vector>

int main() {
    using namespace snipsnap::aquarium;
    constexpr int rate = 48000, frames = rate * 5, block = 256, runs = 5;
    constexpr double pi = 3.14159265358979323846;
    volatile double checksum = 0;
    for (int mode = 0; mode < 4; ++mode) {
        std::vector<double> totals, callbacks;
        totals.reserve(runs);
        callbacks.reserve(runs * ((frames + block - 1) / block));
        for (int run = 0; run < runs; ++run) {
            PerformanceSettings settings;
            if (mode != 0) settings.parameters = parametersForPreset(Preset::Full);
            settings.enabled = mode == 1 || mode == 3;
            settings.bpm = 123;
            settings.outputGain = 0.125f;
            PerformanceBus bus;
            if (!bus.publish(settings) || !bus.prepare(rate)) return 1;
            std::vector<float> audio(frames * 2, 0);
            for (int i = 0; i < rate * 3; ++i) {
                audio[2 * i] = 0.2f * static_cast<float>(std::sin(2 * pi * 220 * i / rate));
                audio[2 * i + 1] = 0.15f * static_cast<float>(std::sin(2 * pi * 337 * i / rate));
            }
            const auto start = std::chrono::steady_clock::now();
            for (int offset = 0; offset < frames; offset += block) {
                const int count = std::min(block, frames - offset);
                if (mode == 3 && offset > 0 && (offset / block) % 32 == 0) {
                    settings.bpm = settings.bpm == 123 ? 117 : 123;
                    if (!bus.publish(settings, true)) return 4;
                }
                const auto before = std::chrono::steady_clock::now();
                bus.beginCallback();
                bus.process(audio.data() + offset * 2, count);
                const auto after = std::chrono::steady_clock::now();
                callbacks.push_back(std::chrono::duration<double, std::micro>(after - before).count());
            }
            totals.push_back(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count());
            for (float x : audio) {
                if (!std::isfinite(x)) return 2;
                checksum += x;
            }
            if (bus.faulted()) return 3;
        }
        std::sort(totals.begin(), totals.end());
        std::sort(callbacks.begin(), callbacks.end());
        const char* name = mode == 0 ? "dry" : mode == 1 ? "full-wet" :
                           mode == 2 ? "full-globally-bypassed" : "full-with-resets";
        std::printf("%s: median_5s_ms=%.3f range_ms=%.3f..%.3f callback_p95_us=%.3f callback_max_us=%.3f\n",
                    name, totals[runs / 2], totals.front(), totals.back(),
                    callbacks[callbacks.size() * 95 / 100], callbacks.back());
    }
    std::printf("checksum=%.9g\n", static_cast<double>(checksum));
}
