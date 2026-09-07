#pragma once

#include <cmath>

namespace snipsnap {

/**
 * A one-pole lowpass that de-zippers a control value at audio rate:
 * `y += k * (x - y)` per sample. The UI already smooths at screen rate
 * (TouchSurface.Smoother in :shell, the same equation), but screen rate
 * is 60-120 steps a second and a filter cutoff stepping 120 times a
 * second still clicks; this runs per sample so the DSP never sees a step
 * at all.
 *
 * No allocation, no branches in the hot path, and `snap` for the cases
 * where a glide would be wrong (a mode change, the first frame).
 */
class ParameterSmoother {
public:
    void configure(float cutoffHz, float sampleRate) {
        // 1 - e^(-2*pi*fc/fs): the fraction of the remaining distance
        // closed per sample for a first-order lowpass at fc.
        k_ = 1.0f - std::exp(-2.0f * 3.14159265f * cutoffHz / sampleRate);
        if (k_ < 1e-6f) k_ = 1e-6f;
        if (k_ > 1.0f) k_ = 1.0f;
    }

    void setTarget(float target) { target_ = target; }

    void snap(float value) { value_ = target_ = value; }

    /** One sample's worth of glide; returns the current value. */
    inline float next() {
        value_ += k_ * (target_ - value_);
        return value_;
    }

    float value() const { return value_; }
    float target() const { return target_; }

private:
    float k_ = 0.01f;
    float value_ = 0.0f;
    float target_ = 0.0f;
};

}  // namespace snipsnap
