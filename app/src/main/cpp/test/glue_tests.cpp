// SPDX-License-Identifier: MIT
// The whole-kit glue (GlueBus.h) on its own: the compressor's times, the
// limiter's ceiling and release, bypass, the crossfades, hostile input and
// the callback's allocation contract. PadEngine's placement and the JNI
// bridge are covered in engine_tests.cpp and jni_tests.cpp.
#include "GlueBus.h"
#include "check.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

#include "callback_allocations.h"

namespace {
using snipsnap::GlueBus;
using snipsnap::GlueSettings;
using Audio = std::vector<float>;
constexpr double kPi = 3.14159265358979323846;

GlueSettings on(float thresholdDbfs = -10.0f, float ratio = 4.0f, float makeupDb = 0.0f,
                float driveDb = 0.0f, float ceilingDbfs = -1.0f) {
    GlueSettings s;
    s.enabled = true;
    s.thresholdDbfs = thresholdDbfs;
    s.ratio = ratio;
    s.makeupDb = makeupDb;
    s.driveDb = driveDb;
    s.ceilingDbfs = ceilingDbfs;
    return s;
}

/** Published before preparation, so the stage starts fully in (no crossfade to wait out). */
void readyOn(GlueBus& g, const GlueSettings& s, double rate) {
    CHECK(g.publish(s));
    CHECK(g.prepare(rate));
}

Audio stereo(const std::vector<double>& mono) {
    Audio out(mono.size() * 2);
    for (size_t i = 0; i < mono.size(); ++i) out[2 * i] = out[2 * i + 1] = static_cast<float>(mono[i]);
    return out;
}

void run(GlueBus& g, Audio& a, int block = 192) {
    const int frames = static_cast<int>(a.size() / 2);
    for (int at = 0; at < frames; at += block) {
        g.beginCallback();
        g.process(a.data() + 2 * at, std::min(block, frames - at));
    }
}

/**
 * BOROUGH's synthetic check signal, generated the same way as in the Python
 * reference: six 55 Hz kicks every 14700 frames, three LCG-noise snares from
 * frame 7350 every 29400, two seconds at 44.1 kHz, mono.
 */
std::vector<double> kickSnare() {
    constexpr int kRate = 44100, n = 2 * kRate;
    std::vector<double> x(n, 0.0);
    for (int k = 0; k < 6; ++k) {
        const int s0 = k * 14700;
        for (int i = s0; i < std::min(n, s0 + static_cast<int>(0.3 * kRate)); ++i) {
            const double tau = static_cast<double>(i - s0) / kRate;
            x[static_cast<size_t>(i)] += 0.9 * std::sin(2 * kPi * 55 * tau) * std::exp(-tau / 0.15);
        }
    }
    uint64_t st = 12345;
    for (int k = 0; k < 3; ++k) {
        const int s0 = 7350 + k * 29400;
        for (int i = s0; i < std::min(n, s0 + static_cast<int>(0.25 * kRate)); ++i) {
            st = (st * 1664525u + 1013904223u) % (uint64_t{1} << 32);
            const double v = static_cast<double>(st) / 4294967296.0 * 2 - 1;
            const double tau = static_cast<double>(i - s0) / kRate;
            x[static_cast<size_t>(i)] += 0.7 * v * std::exp(-tau / 0.08);
        }
    }
    return x;
}

TEST(glue_settings_refuse_non_finite_and_out_of_range_without_changing_the_last_request) {
    GlueBus g;
    CHECK(g.publish(on(-12.0f)));
    GlueSettings bad = on();
    bad.ratio = std::numeric_limits<float>::quiet_NaN();
    CHECK(!g.publish(bad));
    bad = on(); bad.thresholdDbfs = 3.0f; CHECK(!g.publish(bad));
    bad = on(); bad.ceilingDbfs = -40.0f; CHECK(!g.publish(bad));
    bad = on(); bad.driveDb = -1.0f; CHECK(!g.publish(bad));
    bad = on(); bad.makeupDb = 30.0f; CHECK(!g.publish(bad));
    CHECK_EQ(g.controlSettings().thresholdDbfs, -12.0f);
    CHECK(!g.prepare(0.0));
    CHECK(!g.prepare(std::numeric_limits<double>::infinity()));
    CHECK(!g.prepare(400000.0));
}

TEST(glue_off_touches_not_one_sample_and_adds_no_delay) {
    GlueBus g;
    CHECK(g.prepare(48000.0));
    Audio a(2 * 4800);
    for (size_t i = 0; i < a.size(); ++i) a[i] = static_cast<float>(std::sin(0.013 * static_cast<double>(i)) * 3.0);
    a[17] = std::numeric_limits<float>::quiet_NaN();  // even corrupt samples pass untouched while off
    const Audio before = a;
    run(g, a);
    for (size_t i = 0; i < a.size(); ++i) {
        if (std::isnan(before[i])) CHECK(std::isnan(a[i]));
        else CHECK_EQ(a[i], before[i]);
    }
    CHECK_EQ(g.latencyFrames(), 144);  // the 3 ms the stage would add at 48 kHz once on
}

TEST(glue_returns_to_a_bit_exact_bypass_after_the_off_crossfade) {
    GlueBus g;
    readyOn(g, on(-20.0f), 48000.0);
    Audio warm(2 * 4800, 0.5f);
    run(g, warm);
    CHECK(g.publish(GlueSettings{}));
    Audio fade(2 * 4800, 0.5f);
    run(g, fade);  // 20 ms out, well inside 100 ms
    Audio after(2 * 960);
    for (size_t i = 0; i < after.size(); ++i) after[i] = static_cast<float>(0.7 * std::sin(0.02 * static_cast<double>(i)));
    const Audio before = after;
    run(g, after);
    for (size_t i = 0; i < after.size(); ++i) CHECK_EQ(after[i], before[i]);
}

TEST(glue_a_sine_below_threshold_comes_through_unchanged_three_ms_late) {
    GlueBus g;
    readyOn(g, on(-10.0f, 4.0f, 0.0f, 0.0f, -1.0f), 48000.0);
    const int L = g.latencyFrames();
    CHECK_EQ(L, 144);
    std::vector<double> mono(9600);
    for (size_t i = 0; i < mono.size(); ++i) mono[i] = 0.1 * std::sin(2 * kPi * 220.0 * static_cast<double>(i) / 48000.0);
    Audio a = stereo(mono);
    run(g, a);
    double worst = 0.0;
    for (int i = 0; i < static_cast<int>(mono.size()); ++i) {
        const double expected = i >= L ? mono[static_cast<size_t>(i - L)] : 0.0;
        worst = std::max(worst, std::fabs(a[2 * i] - expected));
        worst = std::max(worst, std::fabs(a[2 * i + 1] - expected));
    }
    CHECK(worst < 1e-6);
}

TEST(glue_the_limiter_never_passes_its_ceiling_on_a_hot_kick_and_snare) {
    for (const double rate : {44100.0, 48000.0, 96000.0}) {
        GlueBus g;
        readyOn(g, on(-20.0f, 4.0f, 6.0f, 12.0f, -1.0f), rate);
        std::vector<double> mono = kickSnare();
        for (double& v : mono) v *= 4.0;  // four times hotter than full scale, then 18 dB into the limiter
        Audio a = stereo(mono);
        run(g, a, 256);
        const double ceiling = std::pow(10.0, -1.0 / 20.0);
        double peak = 0.0;
        for (float v : a) peak = std::max(peak, static_cast<double>(std::fabs(v)));
        CHECK(peak <= ceiling + 1e-6);
        CHECK(peak > 0.8 * ceiling);  // and it is used, not merely obeyed by silence
        CHECK_EQ(g.clampedSamples(), uint64_t{0});  // the limiter itself held it; the guard never acted
    }
}

/** Gain the stage applied to a constant input, frame by frame, its lookahead taken out. */
std::vector<double> gainOn(GlueBus& g, double first, double second, int firstFrames, int secondFrames) {
    std::vector<double> mono(static_cast<size_t>(firstFrames + secondFrames));
    for (int i = 0; i < firstFrames + secondFrames; ++i) mono[static_cast<size_t>(i)] = i < firstFrames ? first : second;
    Audio a = stereo(mono);
    run(g, a, 64);
    const int L = g.latencyFrames();
    std::vector<double> gain(mono.size(), 1.0);
    for (int i = L; i < static_cast<int>(mono.size()); ++i) gain[static_cast<size_t>(i - L)] = a[2 * i] / mono[static_cast<size_t>(i - L)];
    return gain;
}

TEST(glue_the_compressor_attacks_in_10_ms_and_releases_in_120_ms) {
    constexpr double kRate = 48000.0;
    GlueBus g;
    // Ratio 4, threshold -10 dBFS (0.316), limiter out of the way (0 dBFS ceiling, nothing reaches it).
    readyOn(g, on(-10.0f, 4.0f, 0.0f, 0.0f, 0.0f), kRate);
    const int up = 24000, down = 48000;
    const std::vector<double> gain = gainOn(g, 0.9, 0.35, up, down);
    const double thr = std::pow(10.0, -0.5);
    // The detector's level, read back from the gain the curve gave: env = thr * gain^(1 / (1/ratio - 1)).
    auto env = [&](int i) { const double gi = gain[static_cast<size_t>(i)]; return gi < 1.0 ? thr * std::pow(gi, 1.0 / (0.25 - 1.0)) : 0.0; };
    // Attack: from 0 towards 0.9 with a 10 ms time constant -> 63.2% (0.569) at 10 ms.
    int attackAt = -1;
    for (int i = 0; i < up && attackAt < 0; ++i) if (env(i) >= 0.9 * (1 - std::exp(-1.0))) attackAt = i;
    CHECK_NEAR(attackAt / kRate * 1000.0, 10.0, 0.5);
    // Release: from 0.9 down towards 0.35 with 120 ms -> 63.2% of the way (0.552) at 120 ms.
    const double target = 0.9 - (0.9 - 0.35) * (1 - std::exp(-1.0));
    int releaseAt = -1;
    for (int i = up; i < up + down - 200 && releaseAt < 0; ++i) if (env(i) <= target) releaseAt = i - up;
    CHECK_NEAR(releaseAt / kRate * 1000.0, 120.0, 2.0);
}

TEST(glue_the_limiter_recovers_with_a_50_ms_release_and_starts_before_the_peak) {
    constexpr double kRate = 48000.0;
    GlueBus g;
    // Ratio 1 takes the compressor out; a 0 dBFS ceiling against a 2.0 input needs half gain.
    readyOn(g, on(0.0f, 1.0f, 0.0f, 0.0f, 0.0f), kRate);
    const int hot = 9600, after = 24000;
    const std::vector<double> gain = gainOn(g, 2.0, 0.4, hot, after);
    CHECK_NEAR(gain[static_cast<size_t>(hot - 10)], 0.5, 1e-6);
    // The lookahead: the gain is already coming up only after the hot part has left, never before.
    CHECK_NEAR(gain[static_cast<size_t>(hot - 1)], 0.5, 1e-6);
    // Recovery 1 - 0.5 e^(-t/50 ms), then a 3 ms average: 63.2% back (0.816) at 50 ms plus ~1.5 ms.
    int at = -1;
    for (int i = hot; i < hot + after - 200 && at < 0; ++i) if (gain[static_cast<size_t>(i)] >= 1 - 0.5 * std::exp(-1.0)) at = i - hot;
    CHECK_NEAR(at / kRate * 1000.0, 51.5, 2.0);
    // A peak arriving after quiet: the gain is down to what it needs before the peak leaves the delay line.
    GlueBus h;
    readyOn(h, on(0.0f, 1.0f, 0.0f, 0.0f, 0.0f), kRate);
    std::vector<double> mono(4800, 0.2);
    mono[2400] = 3.0;
    Audio a = stereo(mono);
    run(h, a, 128);
    const int L = h.latencyFrames();
    CHECK_NEAR(a[2 * (2400 + L)], 1.0, 1e-6);
    CHECK(a[2 * (2400 + L - L / 2)] < 0.2 - 1e-3);  // ducking ahead of it: the lookahead at work
}

TEST(glue_silence_stays_exactly_silent_with_no_subnormals_after_a_burst) {
    GlueBus g;
    readyOn(g, on(-30.0f, 8.0f, 12.0f, 12.0f, -1.0f), 48000.0);
    Audio burst(2 * 4800);
    for (size_t i = 0; i < burst.size(); ++i) burst[i] = static_cast<float>(0.9 * std::sin(0.05 * static_cast<double>(i)));
    run(g, burst);
    Audio quiet(2 * 48000 * 10, 0.0f);
    run(g, quiet, 512);
    for (size_t i = 2 * 4800; i < quiet.size(); ++i) {
        CHECK(std::fpclassify(quiet[i]) != FP_SUBNORMAL);
        if (quiet[i] != 0.0f) { CHECK_EQ(quiet[i], 0.0f); break; }
    }
}

TEST(glue_survives_32x_input_and_corrupt_samples_finite_and_under_its_ceiling) {
    GlueBus g;
    readyOn(g, on(-10.0f, 4.0f, 24.0f, 24.0f, -1.0f), 48000.0);
    Audio a(2 * 9600);
    for (size_t i = 0; i < a.size(); ++i) a[i] = static_cast<float>(32.0 * std::sin(0.031 * static_cast<double>(i)));
    a[100] = std::numeric_limits<float>::quiet_NaN();
    a[101] = std::numeric_limits<float>::infinity();
    a[5000] = -std::numeric_limits<float>::infinity();
    a[5001] = 1e30f;
    run(g, a);
    const float ceiling = static_cast<float>(std::pow(10.0, -1.0 / 20.0));
    for (float v : a) {
        CHECK(std::isfinite(v));
        if (!(std::fabs(v) <= ceiling + 1e-6f)) { CHECK(std::fabs(v) <= ceiling + 1e-6f); break; }
    }
}

TEST(glue_turning_on_and_off_and_moving_a_setting_does_not_click) {
    // A 100 Hz sine at 0.2: its largest frame-to-frame step is 0.0026. A
    // click is a jump far past that; the crossfades and glides stay near it.
    constexpr double kRate = 48000.0;
    GlueBus g;
    CHECK(g.prepare(kRate));
    std::vector<double> mono(48000);
    for (size_t i = 0; i < mono.size(); ++i) mono[i] = 0.2 * std::sin(2 * kPi * 100.0 * static_cast<double>(i) / kRate);
    Audio a = stereo(mono);
    const int block = 240;
    double worst = 0.0;
    float last = 0.0f;
    for (int at = 0, n = 0; at < static_cast<int>(mono.size()); at += block, ++n) {
        if (n == 20) CHECK(g.publish(on(-30.0f, 6.0f, 6.0f, 6.0f, -3.0f)));   // on, mid-sine
        if (n == 60) CHECK(g.publish(on(-12.0f, 2.0f, 0.0f, 12.0f, -1.0f)));  // a new sound while on
        if (n == 120) CHECK(g.publish(GlueSettings{}));                       // off again
        g.beginCallback();
        g.process(a.data() + 2 * at, block);
        for (int i = 0; i < block; ++i) {
            const float v = a[2 * (at + i)];
            if (at + i > 0) worst = std::max(worst, static_cast<double>(std::fabs(v - last)));
            last = v;
        }
    }
    // A 0.2 sine pushed 12 dB and held under -3 dBFS moves at most 0.0093
    // a frame; a switch with no crossfade would jump by tenths.
    CHECK(worst < 0.02);
}

TEST(glue_matches_the_borough_preview_chain_on_its_check_signal) {
    // prototype/borough/build.py, glue_bus(x) then limit(g, 6.0) on kickSnare():
    // the preview's threshold is 10 dB under the signal's peak (-10.191 dBFS)
    // and it normalises the compressor's output to a 1.0 peak (+1.1388 dB);
    // its limiter's ceiling is 1.0. Its output measured an RMS of -8.004 dB
    // (the compressor alone, -11.728 dB). The live limiter is causal where the
    // preview's windows are centred, so it is held to 0.25 dB, not to the sample.
    constexpr double kRate = 44100.0;
    for (const auto& [drive, expected] : {std::pair{0.0f, -11.728}, std::pair{6.0f, -8.004}}) {
        GlueBus g;
        readyOn(g, on(-10.1910f, 4.0f, 1.1388f, drive, 0.0f), kRate);
        std::vector<double> mono = kickSnare();
        mono.resize(mono.size() + static_cast<size_t>(g.latencyFrames()), 0.0);  // flush the lookahead
        Audio a = stereo(mono);
        run(g, a, 512);
        double sum = 0.0;
        for (size_t i = 0; i < a.size(); i += 2) sum += static_cast<double>(a[i]) * a[i];
        const double rms = 10.0 * std::log10(sum / (2.0 * kRate));  // over the preview's own two seconds
        CHECK_NEAR(rms, expected, 0.25);
    }
}

TEST(glue_processing_allocates_and_frees_nothing) {
    GlueBus g;
    readyOn(g, on(-20.0f, 4.0f, 6.0f, 9.0f, -1.0f), 48000.0);
    std::vector<double> mono = kickSnare();
    Audio a = stereo(mono);
    performance_allocation::allocations.store(0);
    performance_allocation::frees.store(0);
    performance_allocation::enabled = true;
    for (int at = 0, n = 0; at + 256 <= static_cast<int>(mono.size()); at += 256, ++n) {
        if (n == 50) g.publish(on(-10.0f));
        if (n == 150) g.publish(GlueSettings{});
        g.beginCallback();
        g.process(a.data() + 2 * at, 256);
    }
    performance_allocation::enabled = false;
    CHECK_EQ(performance_allocation::allocations.load(), size_t{0});
    CHECK_EQ(performance_allocation::frees.load(), size_t{0});
}
}  // namespace

int main() { return check::runAll(); }
