#include "crease/CreaseStudyEngine.h"
#include "callback_allocations.h"
#include <jni.h>
#include <fstream>
#include <iostream>
#include <string>
#include <cstring>
#include <chrono>
#include <limits>

using snipsnap::crease::Layout;
using snipsnap::crease::Reader;
static void check(bool ok,const char* why) { if(!ok) throw std::runtime_error(why); }
static Layout layout() { return {2729,32,2697,16,89,53106,221,52925,{0,32}}; }
static std::vector<int16_t> pcm(const Layout& m) {
    std::vector<int16_t> p(m.samples());
    for(std::size_t i=0;i<p.size();++i) p[i]=int16_t(12000*std::sin(i*.037));
    return p;
}
extern "C" {
jlong Java_com_snipsnap_app_NativeCreaseStudy_create(JNIEnv*,jobject,jintArray,jshortArray);
jlong Java_com_snipsnap_app_NativeCreaseStudy_createShared(JNIEnv*,jobject,jintArray,jshortArray,jfloatArray);
void Java_com_snipsnap_app_NativeCreaseStudy_destroy(JNIEnv*,jobject,jlong);
jboolean Java_com_snipsnap_app_NativeCreaseStudy_start(JNIEnv*,jobject,jlong);
void Java_com_snipsnap_app_NativeCreaseStudy_stop(JNIEnv*,jobject,jlong);
jlong Java_com_snipsnap_app_NativeCreaseStudy_press(JNIEnv*,jobject,jlong,jboolean);
void Java_com_snipsnap_app_NativeCreaseStudy_release(JNIEnv*,jobject,jlong,jlong);
void Java_com_snipsnap_app_NativeCreaseStudy_panic(JNIEnv*,jobject,jlong);
jboolean Java_com_snipsnap_app_NativeCreaseStudy_failed(JNIEnv*,jobject,jlong);
}
namespace bridge {
struct Ints : _jintArray { std::vector<jint> v; };
struct Shorts : _jshortArray { std::vector<jshort> v; };
struct Floats : _jfloatArray { std::vector<jfloat> v; };
Ints ints; Shorts shorts; Floats floats; bool pending=false,throwRead=false;
jsize length(JNIEnv*,jarray p) { if(p==&ints) return ints.v.size();if(p==&shorts) return shorts.v.size();if(p==&floats) return floats.v.size();pending=true;return 0; }
void readInts(JNIEnv*,jintArray,jsize start,jsize n,jint* out) { std::copy_n(ints.v.data()+start,n,out); }
void readShorts(JNIEnv*,jshortArray,jsize start,jsize n,jshort* out) { if(throwRead) pending=true;else std::copy_n(shorts.v.data()+start,n,out); }
void readFloats(JNIEnv*,jfloatArray,jsize start,jsize n,jfloat* out) { if(throwRead) pending=true;else std::copy_n(floats.v.data()+start,n,out); }
jboolean exception(JNIEnv*) { return pending; }
void run() {
    JNINativeInterface_ table{};table.GetArrayLength=length;table.GetIntArrayRegion=readInts;
    table.GetShortArrayRegion=readShorts;table.ExceptionCheck=exception;JNIEnv env{&table};
    table.GetFloatArrayRegion=readFloats;
    check(Java_com_snipsnap_app_NativeCreaseStudy_create(&env,nullptr,nullptr,nullptr)==0,"null JNI arrays accepted");
    ints.v={2729,32,2697,16,89,53106,221,52925,0,32};shorts.v=pcm(layout());
    pending=true;check(!Java_com_snipsnap_app_NativeCreaseStudy_create(&env,nullptr,&ints,&shorts),"pending exception accepted");pending=false;
    ints.v[6]=220;check(!Java_com_snipsnap_app_NativeCreaseStudy_create(&env,nullptr,&ints,&shorts),"bad native layout accepted");ints.v[6]=221;
    shorts.v.pop_back();check(!Java_com_snipsnap_app_NativeCreaseStudy_create(&env,nullptr,&ints,&shorts),"short JNI PCM accepted");shorts.v.push_back(0);
    throwRead=true;check(!Java_com_snipsnap_app_NativeCreaseStudy_create(&env,nullptr,&ints,&shorts),"failed region read accepted");throwRead=false;pending=false;
    auto h=Java_com_snipsnap_app_NativeCreaseStudy_create(&env,nullptr,&ints,&shorts);check(h!=0,"valid JNI cache refused");
    check(!Java_com_snipsnap_app_NativeCreaseStudy_start(&env,nullptr,h),"host stream pretended to open");
    check(!Java_com_snipsnap_app_NativeCreaseStudy_press(&env,nullptr,h,2),"invalid JNI mode accepted");
    const auto token=Java_com_snipsnap_app_NativeCreaseStudy_press(&env,nullptr,h,JNI_TRUE);check(token>0,"JNI press failed");
    auto* e=reinterpret_cast<snipsnap::crease::Engine*>(h);float block[256]{};
    e->onAudioReady(nullptr,block,128);check(e->reader.active()==1,"JNI press not played");
    Java_com_snipsnap_app_NativeCreaseStudy_release(&env,nullptr,h,token);
    e->onAudioReady(nullptr,block,128);
    Java_com_snipsnap_app_NativeCreaseStudy_panic(&env,nullptr,h);
    for(int i=0;i<3;++i) e->onAudioReady(nullptr,block,128);
    check(e->reader.active()==0,"JNI panic stuck");
    e->onErrorAfterClose(nullptr,oboe::Result::ErrorDisconnected);
    check(Java_com_snipsnap_app_NativeCreaseStudy_failed(&env,nullptr,h),"route closure hidden");
    Java_com_snipsnap_app_NativeCreaseStudy_stop(&env,nullptr,h);
    Java_com_snipsnap_app_NativeCreaseStudy_destroy(&env,nullptr,h);
    ints.v={2729,32,2697,16,89,53106,221,52925,2,89,0,32,0,32};floats.v={1,0,0,1};
    auto shared=layout();shared.components=2;shared.pad=89;shared.offsets={0,32};shared.coefficients=floats.v;shorts.v=pcm(shared);
    check(!Java_com_snipsnap_app_NativeCreaseStudy_createShared(&env,nullptr,&ints,&shorts,nullptr),"null shared coefficients accepted");
    floats.v[0]=std::numeric_limits<float>::quiet_NaN();
    check(!Java_com_snipsnap_app_NativeCreaseStudy_createShared(&env,nullptr,&ints,&shorts,&floats),"NaN shared coefficients accepted");floats.v[0]=1;
    floats.v.pop_back();check(!Java_com_snipsnap_app_NativeCreaseStudy_createShared(&env,nullptr,&ints,&shorts,&floats),"short coefficient table accepted");floats.v.push_back(1);
    throwRead=true;check(!Java_com_snipsnap_app_NativeCreaseStudy_createShared(&env,nullptr,&ints,&shorts,&floats),"failed shared region read accepted");throwRead=false;pending=false;
    h=Java_com_snipsnap_app_NativeCreaseStudy_createShared(&env,nullptr,&ints,&shorts,&floats);check(h!=0,"valid shared JNI cache refused");
    Java_com_snipsnap_app_NativeCreaseStudy_destroy(&env,nullptr,h);
    check(!Java_com_snipsnap_app_NativeCreaseStudy_press(&env,nullptr,0,JNI_TRUE),"null handle played");
}
static void sharedTests() {
    auto full=layout(),shared=full;
    shared.components=2;shared.pad=89;shared.offsets={-20,20};shared.coefficients={1,0,0,1};
    check(shared.valid(),"shared layout invalid");
    auto bad=shared;bad.pad=std::numeric_limits<int>::max();check(!bad.valid(),"overflowing shared pad accepted");
    bad=shared;bad.offsets[0]=-90;check(!bad.valid(),"outside basis offset accepted");
    bad=shared;bad.coefficients[0]=std::numeric_limits<float>::infinity();check(!bad.valid(),"infinite basis weight accepted");
    auto original=pcm(full),basis=pcm(shared);
    std::copy_n(original.begin(),full.held,basis.begin());
    for(int k=0;k<full.tail+178;++k) for(int j=0;j<2;++j)
        basis[full.held+k*2+j]=int16_t(10000*std::sin(k*(j?.021:.037)));
    for(int j=0;j<2;++j) for(int k=0;k<full.tail;++k)
        original[full.held+j*full.tail+k]=basis[full.held+(k+shared.pad+shared.offsets[j])*2+j];
    Reader reference(full,std::move(original)),reader(shared,std::move(basis));
    float a[256]{},b[256]{};auto t=reference.press(true),u=reader.press(true);
    performance_allocation::enabled=true;
    reference.process(a,17,48000);reader.process(b,17,48000);reference.release(t);reader.release(u);
    for(int n=0;n<460;++n) {
        reference.process(a,128,48000);reader.process(b,128,48000);
        for(int i=0;i<256;++i) check(std::abs(a[i]-b[i])<1e-8,"shared integer alignment differs from full tails");
    }
    performance_allocation::enabled=false;
    check(reader.active()==0,"shared reader did not end");
    check(performance_allocation::allocations==0 && performance_allocation::frees==0,"shared callback allocated or freed");
    check(sizeof(Reader)+sizeof(Layout)<8192,"shared resident overhead allowance exceeded");
}
}
static void unitTests() {
    auto m=layout();check(m.valid(),"test layout invalid");
    auto bad=m;bad.cuts={0,32,31};check(!bad.valid(),"unsorted grid accepted");
    bad=m;bad.tail=44100;check(!bad.valid(),"missing interpolation padding accepted");
    Reader r(m,pcm(m));float block[512]{};
    performance_allocation::enabled=true;
    auto a=r.press(true);r.process(block,128,44100);r.release(a);
    for(int i=0;i<420;++i) r.process(block,128,44100);
    check(r.active()==0,"44.1k release stuck");
    a=r.press(true);r.release(a);r.process(block,128,48000);
    for(int i=0;i<460;++i) r.process(block,128,48000);
    check(r.active()==0,"zero-length 48k gesture stuck");
    for(int i=0;i<100;++i) { a=r.press(i%2);r.process(block,128,48000);r.release(a);r.process(block,128,48000); }
    r.panic();for(int i=0;i<4;++i) r.process(block,128,48000);
    check(r.active()==0,"rapid retrigger cleanup stuck");
    auto stale=r.press(true);auto current=r.press(false);r.release(stale);r.process(block,128,44100);
    for(int i=0;i<430;++i) r.process(block,128,44100);
    check(r.active()==1,"stale key-up released new note");r.release(current);
    r.panic();r.process(block,256,44100);check(r.active()==0,"panic during release stuck");
    // Down and up can arrive between callbacks. It must release from onset.
    a=r.press(true);r.release(a);r.process(block,128,44100);r.panic();r.process(block,256,44100);
    performance_allocation::enabled=false;
    check(performance_allocation::allocations==0 && performance_allocation::frees==0,"callback allocated or freed");
    bridge::run();
}
static void parity(const std::string& directory) {
    const auto raw=directory+"/raw/";
    std::ifstream meta(raw+"native-layout.txt");Layout m{};int count=0;
    meta>>m.held>>m.loopStart>>m.loopFrames>>m.rootCycles>>m.lead>>m.tail>>m.blend>>m.release>>count;
    check(bool(meta) && count>0 && count<=400,"missing native layout");m.cuts.resize(count);for(auto& v:m.cuts) meta>>v;
    if(meta>>m.components) {
        meta>>m.pad;m.offsets.resize(count);for(auto& v:m.offsets) meta>>v;
        check(m.components>0 && m.components<=64 && m.pad==89,"bad shared fixture dimensions");
        m.coefficients.resize(std::size_t(count)*m.components);
        std::ifstream weights(raw+"shared-coefficients.f32",std::ios::binary);
        weights.read(reinterpret_cast<char*>(m.coefficients.data()),m.coefficients.size()*4);check(bool(weights),"missing shared coefficients");
    }
    check(m.valid(),"parity layout invalid");std::vector<int16_t> data(m.samples());
    std::ifstream pcmFile(raw+"native-pcm.i16",std::ios::binary);pcmFile.read(reinterpret_cast<char*>(data.data()),data.size()*2);check(bool(pcmFile),"missing parity PCM");
    const auto resident=m.residentBytes();
    Reader reader(std::move(m),std::move(data));
    double processMilliseconds=0,audioSeconds=0,releaseMilliseconds=0,releaseSeconds=0;
    bool measuringRelease=false;
    auto process=[&](float* out,int frames,int rate) {
        performance_allocation::enabled=true;
        const auto before=std::chrono::steady_clock::now();reader.process(out,frames,rate);
        const auto after=std::chrono::steady_clock::now();performance_allocation::enabled=false;
        processMilliseconds+=std::chrono::duration<double,std::milli>(after-before).count();audioSeconds+=double(frames)/rate;
        if(measuringRelease) { releaseMilliseconds+=std::chrono::duration<double,std::milli>(after-before).count();releaseSeconds+=double(frames)/rate; }
    };
    std::ifstream cases(raw+"native-cases.txt");int rows=0,frames=0;cases>>rows>>frames;check(rows>0 && frames>0,"missing parity cases");
    std::ifstream fixture(raw+"reader-fixtures.f32",std::ios::binary);check(bool(fixture),"missing Python fixture");
    std::vector<float> expected(frames);std::array<float,512> block{};double maxError=0;
    for(int row=0;row<rows;++row) {
        int cut=0;cases>>cut;check(bool(cases),"truncated case list");fixture.read(reinterpret_cast<char*>(expected.data()),frames*4);check(bool(fixture),"truncated fixture");
        reader.clear();const auto token=reader.press(true);measuringRelease=false;
        for(int at=0;at<cut;) { const int n=std::min(256,cut-at);process(block.data(),n,44100);at+=n; }
        reader.release(token);
        measuringRelease=true;
        for(int at=0;at<frames;) {
            const int n=std::min(256,frames-at);process(block.data(),n,44100);
            for(int i=0;i<n;++i) { check(std::isfinite(block[2*i]) && block[2*i]==block[2*i+1],"invalid stereo output");maxError=std::max(maxError,std::abs(double(block[2*i])-expected[at+i])); }
            at+=n;
        }
        check(reader.active()==0,"native parity release stuck");
    }
    check(maxError<1e-7,"native/Python transport mismatch");
    std::ifstream rates(raw+"native-48k-fixtures.bin",std::ios::binary);
    auto integer=[&]() { int32_t x=0;rates.read(reinterpret_cast<char*>(&x),4);check(bool(rates),"truncated 48k fixture");return x; };
    const int rateCases=integer();check(rateCases==24,"missing 48k references");
    for(int row=0;row<rateCases;++row) {
        const int rate=integer(),hold=integer(),length=integer(),unfold=integer();
        check(rate==48000 && hold>=0 && length==60000,"invalid 48k case");
        expected.resize(length);rates.read(reinterpret_cast<char*>(expected.data()),length*4);check(bool(rates),"truncated 48k PCM");
        reader.clear();const auto token=reader.press(unfold!=0);measuringRelease=false;
        for(int at=0;at<hold;) { const int n=std::min(256,hold-at);process(block.data(),n,rate);at+=n; }
        reader.release(token);
        measuringRelease=true;
        for(int at=0;at<length;) {
            const int n=std::min(256,length-at);process(block.data(),n,rate);
            for(int i=0;i<n;++i) maxError=std::max(maxError,std::abs(double(block[2*i])-expected[at+i]));
            at+=n;
        }
        check(reader.active()==0,"48k native release stuck");
    }
    check(maxError<1e-7,"native/JS 48k transport mismatch");
    check(performance_allocation::allocations==0 && performance_allocation::frees==0,"actual shared callback allocated or freed");
    std::ofstream receipt(directory+"/native-verification.json");
    receipt<<"{\"result\":\"passed\",\"cases\":"<<rows<<",\"output48kCases\":"<<rateCases<<",\"maxNativePythonSampleError\":"<<maxError<<",\"residentBytes\":"<<resident<<",\"hostProcessMillisecondsPerAudioSecond\":"<<processMilliseconds/audioSeconds<<",\"hostReleaseMillisecondsPerAudioSecond\":"<<releaseMilliseconds/releaseSeconds<<",\"callbackAllocations\":0,\"callbackFrees\":0,\"scope\":\"Actual native reader and JNI on host; no physical phone timing\"}\n";
    std::cout<<"Native parity: "<<rows<<" cases; max sample error "<<maxError<<"\n";
}
int main(int argc,char** argv) {
    try { unitTests();bridge::sharedTests();if(argc==2) parity(argv[1]);std::cout<<"Crease study tests passed\n";return 0; }
    catch(const std::exception& e) { performance_allocation::enabled=false;std::cerr<<e.what()<<"\n";return 1; }
}
