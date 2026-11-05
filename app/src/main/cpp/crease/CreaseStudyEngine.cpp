#include "CreaseStudyEngine.h"
#include "../OboeOutput.h"
namespace snipsnap::crease {
bool Engine::start() {
    stop(); failed.store(false);
    const auto output=openStereoFloatOutput(44100,this,this,stream_,"CreaseStudy");
    if (!output.ok) return false;
    rate_=output.sampleRate;
    if(stream_->requestStart()==oboe::Result::OK) return true;
    stop(); return false;
}
void Engine::stop() {
    if(stream_) { stream_->stop(); stream_->close(); stream_.reset(); }
    reader.clear();
}
}
