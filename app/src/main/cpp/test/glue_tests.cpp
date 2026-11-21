// SPDX-License-Identifier: MIT
// The whole-kit glue (GlueBus.h) on its own: the compressor's times, the
// limiter's ceiling and release, the input trim and saturator in front of
// them (round 3.1), bypass, the crossfades, hostile input and the callback's
// allocation contract. PadEngine's placement and the JNI
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

/** [on] with round 3.1's front end: a trim, and the saturator at [satDb] when [satDb] is not negative. */
GlueSettings fronted(float trimDb, float satDb, float thresholdDbfs = -10.0f, float ratio = 4.0f, float driveDb = 0.0f,
                     float ceilingDbfs = -1.0f) {
    GlueSettings s = on(thresholdDbfs, ratio, 0.0f, driveDb, ceilingDbfs);
    s.inputTrimDb = trimDb;
    s.saturate = satDb >= 0.0f;
    s.saturationDb = satDb >= 0.0f ? satDb : 0.0f;
    return s;
}

double curve(double x, double satDb) {
    const double g = std::pow(10.0, satDb / 20.0);
    return std::tanh(g * x) / std::tanh(g);
}

TEST(glue_front_end_settings_refuse_out_of_range_and_default_to_the_old_chain) {
    GlueBus g;
    CHECK(g.publish(fronted(-2.5f, 6.0f)));
    for (const float trim : {-24.5f, 24.5f, std::numeric_limits<float>::quiet_NaN(), std::numeric_limits<float>::infinity()})
        CHECK(!g.publish(fronted(trim, 6.0f)));
    CHECK(!g.publish(fronted(0.0f, 12.5f)));
    GlueSettings nan = fronted(0.0f, 6.0f);
    nan.saturationDb = std::numeric_limits<float>::quiet_NaN();
    CHECK(!g.publish(nan));
    GlueSettings negative = fronted(0.0f, 6.0f);
    negative.saturationDb = -0.5f;
    CHECK(!g.publish(negative));
    // Validated even while the saturator is off: a stored value is always one the stage could play.
    GlueSettings offButBad = fronted(0.0f, -1.0f);
    offButBad.saturationDb = 40.0f;
    CHECK(!g.publish(offButBad));
    CHECK_EQ(g.controlSettings().inputTrimDb, -2.5f);
    CHECK(g.publish(fronted(24.0f, 12.0f)));
    CHECK(g.publish(fronted(-24.0f, 0.0f)));
    const GlueSettings d;
    CHECK_EQ(d.inputTrimDb, 0.0f);
    CHECK(!d.saturate);
    CHECK_EQ(d.saturationDb, 0.0f);
}

TEST(glue_a_default_front_end_is_the_chain_it_was_sample_for_sample) {
    // The same hot groove through the old settings and through ones that
    // spell out a 0 dB trim and no saturation (with a stored drive of 9 dB):
    // not one sample may differ, so every setting made before round 3.1
    // still plays exactly as it did.
    GlueBus a, b;
    readyOn(a, on(-20.0f, 4.0f, 6.0f, 12.0f, -1.0f), 44100.0);
    GlueSettings spelled = on(-20.0f, 4.0f, 6.0f, 12.0f, -1.0f);
    spelled.inputTrimDb = 0.0f;
    spelled.saturate = false;
    spelled.saturationDb = 9.0f;
    readyOn(b, spelled, 44100.0);
    std::vector<double> mono = kickSnare();
    for (double& v : mono) v *= 2.0;
    Audio x = stereo(mono), y = x;
    run(a, x, 256);
    run(b, y, 256);
    for (size_t i = 0; i < x.size(); ++i) CHECK_EQ(x[i], y[i]);
}

TEST(glue_the_saturator_is_tanh_g_x_over_tanh_g_on_each_channel) {
    // Compressor and limiter out of the way (ratio 1, 0 dBFS threshold and
    // ceiling; tanh(g x)/tanh(g) <= 1 for |x| <= 1), so the stage is the
    // curve three milliseconds late. Left and right carry different levels.
    for (const float satDb : {0.0f, 2.7f, 6.0f, 12.0f}) {
        GlueBus g;
        readyOn(g, fronted(0.0f, satDb, 0.0f, 1.0f, 0.0f, 0.0f), 48000.0);
        const std::vector<double> levels = {0.0, 0.05, 0.25, 0.5, -0.5, 0.9, 1.0, -1.0};
        const int hold = 400;
        Audio a(2 * levels.size() * hold);
        for (size_t k = 0; k < levels.size(); ++k)
            for (int i = 0; i < hold; ++i) {
                a[2 * (k * hold + i)] = static_cast<float>(levels[k]);
                a[2 * (k * hold + i) + 1] = static_cast<float>(levels[k] * 0.3);
            }
        run(g, a, 128);
        const int L = g.latencyFrames();
        for (size_t k = 0; k < levels.size(); ++k) {
            const size_t frame = k * hold + hold / 2 + static_cast<size_t>(L);  // well inside the hold
            const double l = static_cast<float>(levels[k]), r = static_cast<float>(levels[k] * 0.3);
            CHECK_NEAR(a[2 * frame], curve(l, satDb), 2e-6);
            CHECK_NEAR(a[2 * frame + 1], curve(r, satDb), 2e-6);
        }
    }
    // The figures the docs quote: 0 dB is g = 1, a small-signal lift of 1/tanh(1) (+2.4 dB); full scale stays put.
    CHECK_NEAR(curve(1e-6, 0.0) / 1e-6, 1.0 / std::tanh(1.0), 1e-6);
    CHECK_NEAR(20.0 * std::log10(1.0 / std::tanh(1.0)), 2.36, 0.01);
    CHECK_NEAR(curve(1.0, 6.0), 1.0, 1e-12);
}

TEST(glue_the_trim_scales_the_input_before_everything) {
    // Below threshold, no saturation: the trim alone, 3 ms late.
    for (const float trimDb : {-12.0f, -3.0f, 6.0f}) {
        GlueBus g;
        readyOn(g, fronted(trimDb, -1.0f, -10.0f, 4.0f, 0.0f, -1.0f), 48000.0);
        std::vector<double> mono(4800);
        for (size_t i = 0; i < mono.size(); ++i) mono[i] = 0.1 * std::sin(2 * kPi * 220.0 * static_cast<double>(i) / 48000.0);
        Audio a = stereo(mono);
        run(g, a);
        const int L = g.latencyFrames();
        const double k = std::pow(10.0, trimDb / 20.0);
        double worst = 0.0;
        for (int i = L; i < static_cast<int>(mono.size()); ++i)
            worst = std::max(worst, std::fabs(a[2 * i] - k * mono[static_cast<size_t>(i - L)]));
        CHECK(worst < 1e-6);
    }
    // In front of the saturator: a trim of -6 dB then 6 dB of drive is the curve at half the input.
    GlueBus h;
    readyOn(h, fronted(-6.0206f, 6.0f, 0.0f, 1.0f, 0.0f, 0.0f), 48000.0);
    Audio c(2 * 2000, 0.8f);
    run(h, c);
    CHECK_NEAR(c[2 * 1500], curve(0.4, 6.0), 1e-5);
}

TEST(glue_trim_and_saturation_glide_in_db_over_20_ms) {
    constexpr double kRate = 48000.0;
    const int n = static_cast<int>(std::ceil(kRate * GlueBus::kTransitionSeconds));  // 960
    // The trim, 0 -> -12 dB, on a constant 0.1 (below the -10 dBFS threshold, the limiter idle).
    GlueBus g;
    readyOn(g, fronted(0.0f, -1.0f), kRate);
    Audio warm(2 * 4800, 0.1f);
    run(g, warm, 64);
    CHECK(g.publish(fronted(-12.0f, -1.0f)));
    Audio a(2 * 4800, 0.1f);
    run(g, a, 64);  // the publication lands on this run's first callback
    const int L = g.latencyFrames();
    auto db = [&](int frame) { return 20.0 * std::log10(a[2 * (frame + L)] / static_cast<double>(0.1f)); };
    CHECK_NEAR(db(0), -12.0 / n, 1e-3);
    CHECK_NEAR(db(n / 2 - 1), -6.0, 0.02);   // a line in dB: halfway at 10 ms
    CHECK_NEAR(db(n / 4 - 1), -3.0, 0.02);
    CHECK_NEAR(db(n - 1), -12.0, 1e-4);
    CHECK_NEAR(db(n + 100), -12.0, 1e-4);
    for (int i = 1; i < n; ++i) CHECK(db(i) < db(i - 1));
    // The saturator's drive, 6 -> 12 dB while saturating, on a constant 0.2.
    GlueBus s;
    readyOn(s, fronted(0.0f, 6.0f, 0.0f, 1.0f, 0.0f, 0.0f), kRate);
    Audio pre(2 * 4800, 0.2f);
    run(s, pre, 64);
    CHECK(s.publish(fronted(0.0f, 12.0f, 0.0f, 1.0f, 0.0f, 0.0f)));
    Audio b(2 * 4800, 0.2f);
    run(s, b, 64);
    const double x = static_cast<double>(0.2f);
    CHECK_NEAR(b[2 * (n / 2 - 1 + L)], curve(x, 9.0), 1e-4);
    CHECK_NEAR(b[2 * (n + 100 + L)], curve(x, 12.0), 1e-6);
}

TEST(glue_switching_the_saturator_while_on_crossfades_without_a_click) {
    // A 100 Hz sine at 0.2 with compressor and limiter out of the way. At 12
    // dB the curve multiplies it by about four, so a hard switch would jump
    // by about half full scale at a peak; the crossfade must stay near the
    // signal's own largest frame-to-frame step (about 0.011 saturated).
    constexpr double kRate = 48000.0;
    const int n = static_cast<int>(std::ceil(kRate * GlueBus::kTransitionSeconds));
    GlueBus g;
    readyOn(g, fronted(0.0f, -1.0f, 0.0f, 1.0f, 0.0f, 0.0f), kRate);
    std::vector<double> mono(48000);
    for (size_t i = 0; i < mono.size(); ++i) mono[i] = 0.2 * std::sin(2 * kPi * 100.0 * static_cast<double>(i) / kRate + 0.4);
    Audio a = stereo(mono);
    const int block = 240;
    double worst = 0.0;
    float last = 0.0f;
    int onAt = -1;
    for (int at = 0, k = 0; at < static_cast<int>(mono.size()); at += block, ++k) {
        if (k == 30) { CHECK(g.publish(fronted(0.0f, 12.0f, 0.0f, 1.0f, 0.0f, 0.0f))); onAt = at; }  // on, mid-sine
        if (k == 100) CHECK(g.publish(fronted(0.0f, -1.0f, 0.0f, 1.0f, 0.0f, 0.0f)));                 // off again
        if (k == 150) CHECK(g.publish(fronted(-3.0f, 6.0f, 0.0f, 1.0f, 0.0f, 0.0f)));                 // on with a new trim
        g.beginCallback();
        g.process(a.data() + 2 * at, block);
        for (int i = 0; i < block; ++i) {
            const float v = a[2 * (at + i)];
            // From the frame after the sine first leaves the 3 ms delay line (it starts mid-cycle).
            if (at + i > g.latencyFrames()) worst = std::max(worst, static_cast<double>(std::fabs(v - last)));
            last = v;
        }
    }
    CHECK(worst < 0.015);
    // And the crossfade really is 20 ms: halfway through, the output is halfway between the two paths.
    const int L = g.latencyFrames();
    const int mid = onAt + n / 2 - 1;
    const double x = static_cast<float>(mono[static_cast<size_t>(mid)]);
    CHECK_NEAR(a[2 * (mid + L)], x + 0.5 * (curve(x, 12.0) - x), 2e-3);
    const int after = onAt + n + 50;
    CHECK_NEAR(a[2 * (after + L)], curve(static_cast<float>(mono[static_cast<size_t>(after)]), 12.0), 1e-5);
}

TEST(glue_off_with_a_front_end_set_still_touches_not_one_sample) {
    GlueBus g;
    GlueSettings s = fronted(12.0f, 12.0f, -30.0f, 8.0f, 12.0f);
    s.enabled = false;
    CHECK(g.publish(s));
    CHECK(g.prepare(44100.0));
    Audio a(2 * 4410);
    for (size_t i = 0; i < a.size(); ++i) a[i] = static_cast<float>(0.6 * std::sin(0.017 * static_cast<double>(i)));
    const Audio before = a;
    run(g, a);
    for (size_t i = 0; i < a.size(); ++i) CHECK_EQ(a[i], before[i]);
    // On with saturation, then off: once the 20 ms fade is out, bypass is bit-exact again.
    GlueBus h;
    readyOn(h, fronted(6.0f, 6.0f), 44100.0);
    Audio warm(2 * 4410, 0.4f);
    run(h, warm);
    CHECK(h.publish(GlueSettings{}));
    Audio fade(2 * 4410, 0.4f);
    run(h, fade);
    Audio after = before;
    run(h, after);
    for (size_t i = 0; i < after.size(); ++i) CHECK_EQ(after[i], before[i]);
}

TEST(glue_a_hot_trimmed_saturated_mix_holds_the_ceiling_and_the_guard_never_acts) {
    // The BOROUGH shape at its extremes: +24 dB trim, 12 dB of saturation,
    // 24 dB into the limiter, on a kick and snare already four times full scale.
    for (const double rate : {44100.0, 48000.0}) {
        GlueBus g;
        readyOn(g, fronted(24.0f, 12.0f, -10.0f, 4.0f, 24.0f, -1.0f), rate);
        std::vector<double> mono = kickSnare();
        for (double& v : mono) v *= 4.0;
        Audio a = stereo(mono);
        run(g, a, 256);
        const double ceiling = std::pow(10.0, -1.0 / 20.0);
        double peak = 0.0;
        for (float v : a) {
            CHECK(std::isfinite(v));
            peak = std::max(peak, static_cast<double>(std::fabs(v)));
        }
        CHECK(peak <= ceiling + 1e-6);
        CHECK(peak > 0.8 * ceiling);
        CHECK_EQ(g.clampedSamples(), uint64_t{0});
    }
    // And each BOROUGH kit's own recipe (recipes.json kits[].glue, the app's +3 dB live calibration added) on the
    // check signal at full scale.
    struct Recipe { float trim, sat, drive; };
    for (const Recipe r : {Recipe{0.79f, 2.7f, 7.13f}, Recipe{-1.29f, 0.0f, 7.67f}, Recipe{-1.81f, 6.0f, 7.0f},
                           Recipe{0.63f, 6.0f, 7.0f}, Recipe{-2.67f, 6.0f, 7.0f}, Recipe{-2.37f, -1.0f, 7.0f}}) {
        GlueBus g;
        readyOn(g, fronted(r.trim + 3.0f, r.sat, -10.0f, 4.0f, r.drive, -1.0f), 44100.0);
        Audio a = stereo(kickSnare());
        run(g, a, 192);
        double peak = 0.0;
        for (float v : a) peak = std::max(peak, static_cast<double>(std::fabs(v)));
        CHECK(peak <= std::pow(10.0, -1.0 / 20.0) + 1e-6);
        CHECK(peak > 0.85);
        CHECK_EQ(g.clampedSamples(), uint64_t{0});
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
        if (n == 80) g.publish(fronted(-3.0f, 6.0f));   // the saturator in: a crossfade, no allocation
        if (n == 110) g.publish(fronted(2.0f, 12.0f));  // a drive glide
        if (n == 130) g.publish(fronted(2.0f, -1.0f));  // and out
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
