#pragma once

#include <android/log.h>
#include <oboe/Oboe.h>

#include <cstdint>
#include <memory>

namespace snipsnap {

struct OpenedOutput {
    bool ok = false;
    /** The device refused an exclusive stream; the shared fallback is open. */
    bool shared = false;
    int32_t sampleRate = 0;
};

/**
 * The one way this app opens an output: float stereo, low latency,
 * Exclusive first and Shared on an outright refusal (another app holds
 * the exclusive path, or the HAL never offered it - one mixer stage more
 * latency, still playing), the buffer at two bursts. Both engines - the
 * SURFACE's and the pads' - go through here so they cannot drift apart.
 */
inline OpenedOutput openStereoFloatOutput(
    int32_t preferredRate,
    oboe::AudioStreamDataCallback* data,
    oboe::AudioStreamErrorCallback* error,
    std::shared_ptr<oboe::AudioStream>& stream,
    const char* logTag) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setSampleRate(preferredRate)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(data)
        ->setErrorCallback(error);

    oboe::Result opened = builder.openStream(stream);
    if (opened != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_WARN, logTag, "exclusive openStream failed: %s - trying shared",
                            oboe::convertToText(opened));
        stream.reset();
        builder.setSharingMode(oboe::SharingMode::Shared);
        opened = builder.openStream(stream);
    }
    if (opened != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_WARN, logTag, "openStream failed: %s", oboe::convertToText(opened));
        stream.reset();
        return {};
    }
    // Two bursts is the usual low-latency sweet spot: one in flight, one
    // being filled. Oboe clamps to what the device allows.
    stream->setBufferSizeInFrames(stream->getFramesPerBurst() * 2);
    OpenedOutput out;
    out.ok = true;
    out.shared = stream->getSharingMode() == oboe::SharingMode::Shared;
    out.sampleRate = stream->getSampleRate();
    return out;
}

/**
 * The stream's own round-trip latency in milliseconds, or -1 when there
 * is no stream or the device declines to say (not every HAL implements
 * it). **Call from the UI thread only** - Oboe's own note says not to
 * call this from a data callback on Android before R.
 */
inline double latencyMillisOf(const std::shared_ptr<oboe::AudioStream>& stream) {
    if (!stream) return -1.0;
    const oboe::ResultWithValue<double> latency = stream->calculateLatencyMillis();
    return latency ? latency.value() : -1.0;
}

}  // namespace snipsnap
