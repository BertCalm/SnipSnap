#include "../aquarium/OfflineRender.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <iostream>
#include <vector>

int main() {
    using namespace snipsnap::aquarium;
    constexpr int rate = 44100;
    std::vector<float> source(rate * 3);
    for (std::size_t i = 0; i < source.size(); ++i)
        source[i] = .2f * std::sin(6.283185307179586 * 317 * i / rate);
    OfflineRenderOptions options;
    options.bpm = 123;
    options.tailFrames = rate * 2;
    options.fadeFrames = 221;
    options.outputGain = .05f;
    options.peakCeiling = .99f;
    std::vector<double> times;
    std::size_t frames = 0;
    for (int repeat = 0; repeat < 5; ++repeat) {
        std::vector<float> output;
        const auto start = std::chrono::steady_clock::now();
        const auto status = renderOffline(source.data(), source.size(), 1, rate,
            parametersForPreset(Preset::Full), 1337, options, output);
        const auto stop = std::chrono::steady_clock::now();
        if (status != OfflineRenderStatus::Ok) {
            std::cerr << offlineRenderStatusMessage(status) << '\n';
            return 1;
        }
        times.push_back(std::chrono::duration<double, std::milli>(stop - start).count());
        frames = output.size() / 2;
    }
    std::sort(times.begin(), times.end());
    std::cout << "{\"platform\":\"Linux host, g++ -O2, no JNI or WAV IO\","
              << "\"preset\":\"full\",\"rate\":44100,\"sourceSeconds\":3,\"tailSeconds\":2,"
              << "\"gain\":0.05,\"bpm\":123,\"repeats\":5,\"frames\":" << frames
              << ",\"minimumMs\":" << times.front() << ",\"medianMs\":" << times[2]
              << ",\"maximumMs\":" << times.back() << "}\n";
}
