// Header-only Oboe host ABI. No stream can open here. The base virtual
// symbols emit AudioStream's RTTI so UBSan can check the engines' real
// callback objects without linking an Android audio driver. These are
// unavailable operations, never a simulated working device.
#include <oboe/Oboe.h>

namespace oboe {

AudioStream::~AudioStream() = default;
Result AudioStream::close() { return Result::ErrorUnavailable; }
Result AudioStream::start(int64_t) { return Result::ErrorUnavailable; }
Result AudioStream::pause(int64_t) { return Result::ErrorUnavailable; }
Result AudioStream::flush(int64_t) { return Result::ErrorUnavailable; }
Result AudioStream::stop(int64_t) { return Result::ErrorUnavailable; }
int64_t AudioStream::getFramesWritten() { return 0; }
int64_t AudioStream::getFramesRead() { return 0; }
ResultWithValue<FrameTimestamp> AudioStream::getTimestamp(clockid_t) { return Result::ErrorUnavailable; }
Result AudioStream::waitForStateTransition(StreamState, StreamState, int64_t) { return Result::ErrorUnavailable; }

Result AudioStreamBuilder::openStream(std::shared_ptr<oboe::AudioStream>&) {
    return Result::ErrorUnavailable;
}

template <>
const char* convertToText<Result>(Result) {
    return "stubbed";
}

}  // namespace oboe
