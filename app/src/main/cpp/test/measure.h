// Shared by engine_tests.cpp and jni_tests.cpp: a sine to load, and a way
// to read a pitch back out of what an engine rendered. GRAIN's tests need
// both in each file, and the two must agree on what "the pitch" means.
#pragma once

#include <cmath>
#include <cstdint>
#include <vector>

namespace measure {

/** A mono sine at [hz], [frames] long, full scale. */
inline std::vector<float> sine(float hz, int32_t frames, int32_t fs) {
    std::vector<float> v(static_cast<size_t>(frames));
    for (int32_t i = 0; i < frames; ++i) {
        v[static_cast<size_t>(i)] = std::sin(2.0f * 3.14159265f * hz * static_cast<float>(i) / static_cast<float>(fs));
    }
    return v;
}

/**
 * The frequency of a sinusoid in [mono] between [from] and [to], from the
 * spacing of its upward zero crossings, each placed by linear
 * interpolation for sub-sample precision - so a frequency comes back to
 * well under a percent, which is what telling one semitone from the next
 * (six percent) takes with room to spare. -1 with fewer than two
 * crossings to measure between.
 */
inline double hz(const std::vector<float>& mono, size_t from, size_t to, int32_t fs) {
    double first = -1.0, last = -1.0;
    int32_t crossings = 0;
    for (size_t i = from + 1; i < to && i < mono.size(); ++i) {
        const float a = mono[i - 1];
        const float b = mono[i];
        if (a < 0.0f && b >= 0.0f) {
            const double t = static_cast<double>(i - 1) + static_cast<double>(-a) / static_cast<double>(b - a);
            if (first < 0.0) first = t;
            last = t;
            ++crossings;
        }
    }
    if (crossings < 2) return -1.0;
    return static_cast<double>(fs) * static_cast<double>(crossings - 1) / (last - first);
}

/** The left channel of an interleaved stereo render. */
inline std::vector<float> left(const std::vector<float>& stereo) {
    std::vector<float> mono(stereo.size() / 2);
    for (size_t i = 0; i < mono.size(); ++i) mono[i] = stereo[2 * i];
    return mono;
}

}  // namespace measure
