// The two Oboe entry points the engines reference outside their callbacks.
// Neither is reached by the tests (start() is never called); they exist
// so the engines link on a host with no Oboe library.
#include <oboe/Oboe.h>

namespace oboe {

Result AudioStreamBuilder::openStream(std::shared_ptr<oboe::AudioStream>&) {
    return Result::ErrorUnavailable;
}

template <>
const char* convertToText<Result>(Result) {
    return "stubbed";
}

}  // namespace oboe
