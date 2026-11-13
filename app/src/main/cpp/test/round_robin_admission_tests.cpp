// Focused tests of the new per-note admission boundary, not a rerun of all engines.
#include <algorithm>
#include <cmath>
#include <limits>
#include <vector>
#include "PadEngine.h"
#include "check.h"
#include "jni.h"
using namespace snipsnap;
extern "C" {
jboolean Java_com_snipsnap_app_NativePads_admitRoundRobinChord(JNIEnv*, jobject, jlong, jintArray, jintArray,
    jlongArray, jlongArray, jlongArray, jfloatArray, jdoubleArray);
}
namespace {
void bank(PadEngine& e) {
    e.beginBank();
    std::vector<float> a(32), b(48);
    for (size_t i=0;i<a.size();++i) a[i]=static_cast<float>(i)/1000;
    for (size_t i=0;i<b.size();++i) b[i]=static_cast<float>(i)/100;
    e.addSample(std::move(a),1,48000); e.addSample(std::move(b),1,48000); e.commitBank();
}
std::vector<float> render(PadEngine& e,int frames) {
    std::vector<float> out(static_cast<size_t>(frames)*2);e.onAudioReady(nullptr,out.data(),frames);return out;
}
std::vector<int32_t> ended(PadEngine& e) { int32_t ids[256]; auto n=e.drainEnded(ids,256);return {ids,ids+n}; }
bool silent(const std::vector<float>& a) { return std::all_of(a.begin(),a.end(),[](float x){return x==0;}); }
PadCommand note(int id,int sample,int start,int end,double pitch=1) {
    PadCommand c;c.voiceId=id;c.sample=sample;c.start=start;c.end=end;c.pitch=pitch;c.gainL=c.gainR=.25f;return c;
}
struct Array { std::vector<jint> i;std::vector<jlong> l;std::vector<jfloat> f;std::vector<jdouble> d;jsize size=2; };
bool exception=false;int copyNumber=0,failCopy=0;
Array& arr(jarray a){return *reinterpret_cast<Array*>(a);}
jsize length(JNIEnv*,jarray a){return arr(a).size;}
template<class T> void copy(const std::vector<T>& v,jsize s,jsize n,T* b){
    if (++copyNumber==failCopy || s<0 || n<0 || static_cast<size_t>(s+n)>v.size()){exception=true;return;}
    std::copy_n(v.begin()+s,n,b);
}
void ints(JNIEnv*,jintArray a,jsize s,jsize n,jint* b){copy(arr(a).i,s,n,b);}
void longs(JNIEnv*,jlongArray a,jsize s,jsize n,jlong* b){copy(arr(a).l,s,n,b);}
void floats(JNIEnv*,jfloatArray a,jsize s,jsize n,jfloat* b){copy(arr(a).f,s,n,b);}
void doubles(JNIEnv*,jdoubleArray a,jsize s,jsize n,jdouble* b){copy(arr(a).d,s,n,b);}
jboolean hasException(JNIEnv*){return exception?JNI_TRUE:JNI_FALSE;}
JNINativeInterface_ table(){JNINativeInterface_ t{};t.GetArrayLength=length;t.GetIntArrayRegion=ints;t.GetFloatArrayRegion=floats;
    t.GetLongArrayRegion=longs;t.GetDoubleArrayRegion=doubles;t.ExceptionCheck=hasException;return t;}
const auto iface=table();
struct JniChord {
    JNIEnv env{&iface};Array ids,samples,starts,ends,loops,gains,pitches;
    JniChord(){exception=false;copyNumber=failCopy=0;ids.i={11,12};samples.i={0,1};starts.l={2,3};ends.l={6,11};
        loops.l={-1,-1};gains.f={.25f,.25f};pitches.d={1,2};}
    jboolean call(PadEngine& e){return Java_com_snipsnap_app_NativePads_admitRoundRobinChord(&env,nullptr,
        reinterpret_cast<jlong>(&e),reinterpret_cast<jintArray>(&ids),reinterpret_cast<jintArray>(&samples),
        reinterpret_cast<jlongArray>(&starts),reinterpret_cast<jlongArray>(&ends),reinterpret_cast<jlongArray>(&loops),
        reinterpret_cast<jfloatArray>(&gains),reinterpret_cast<jdoubleArray>(&pitches));}
};
}
TEST(rr_chord_uses_each_samples_window_pitch_and_loop) {
    PadEngine e(48000);bank(e);PadCommand notes[]={note(1,0,2,6),note(2,1,3,11,2)};
    notes[1].loopStart=7;CHECK(e.admitRoundRobinChord(notes,2));auto out=render(e,6);
    const float expected[]={.008f,.01325f,.0185f,.02375f,.0175f,.0225f};
    for(size_t i=0;i<6;++i){CHECK_NEAR(out[i*2],expected[i],1e-6);CHECK_NEAR(out[i*2+1],expected[i],1e-6);}
    CHECK(ended(e)==std::vector<int32_t>{1});
}
TEST(rr_invalid_member_refuses_the_entire_chord) {
    for(int bad=0;bad<16;++bad){PadEngine e(48000);bank(e);PadCommand n[]={note(1,0,2,6),note(2,1,3,11,2)};
        switch(bad){case 0:n[1].sample=2;break;case 1:n[1].start=-1;break;case 2:n[1].end=49;break;
        case 3:n[1].end=4;break;case 4:n[1].loopStart=10;break;case 5:n[1].pitch=0;break;
        case 6:n[1].pitch=std::numeric_limits<double>::quiet_NaN();break;case 7:n[1].pitch=65;break;
        case 8:n[1].gainL=std::numeric_limits<float>::infinity();break;case 9:n[1].gainR=-1;break;
        case 10:n[1].reverse=true;break;case 11:n[1].frameOffset=1;break;case 12:n[1].atFrame=4;break;
        case 13:n[1].voiceId=1;break;case 14:n[1].voiceId=0;break;case 15:n[1].type=PadCommand::Type::Stop;break;}
        CHECK(!e.admitRoundRobinChord(n,2));CHECK(silent(render(e,16)));CHECK(ended(e).empty());}
}
TEST(rr_chord_has_bounded_count_and_no_partial_ring_publication) {
    PadEngine e(48000);bank(e);PadCommand n[9];for(int i=0;i<9;++i)n[i]=note(i+1,0,2,6);
    CHECK(!e.admitRoundRobinChord(nullptr,1));CHECK(!e.admitRoundRobinChord(n,0));CHECK(!e.admitRoundRobinChord(n,9));
    PadCommand filler;filler.type=PadCommand::Type::Stop;filler.voiceId=999;
    for(int i=0;i<254;++i)CHECK(e.pushCommand(filler));
    CHECK(!e.admitRoundRobinChord(n,2));CHECK(silent(render(e,16)));CHECK(ended(e).empty());
    CHECK(e.admitRoundRobinChord(n,8));CHECK(!silent(render(e,16)));CHECK_EQ(ended(e).size(),8u);
}
TEST(rr_chord_bank_generation_and_stop_report_all_admitted_ids) {
    PadEngine e(48000);bank(e);PadCommand n[]={note(1,0,2,6),note(2,1,3,11,2)};
    CHECK(e.admitRoundRobinChord(n,2));bank(e);CHECK(silent(render(e,16)));CHECK(ended(e)==std::vector<int32_t>({1,2}));
    CHECK(e.admitRoundRobinChord(n,2));e.stop();CHECK(ended(e)==std::vector<int32_t>({1,2}));CHECK(silent(render(e,16)));
}
TEST(rr_jni_copies_independent_windows_and_pitches) {
    PadEngine e(48000);bank(e);JniChord c;CHECK_EQ(c.call(e),JNI_TRUE);CHECK_EQ(copyNumber,7);
    auto out=render(e,5);const float expected[]={.008f,.01325f,.0185f,.02375f,0};
    for(size_t i=0;i<5;++i)CHECK_NEAR(out[i*2],expected[i],1e-6);
    CHECK(ended(e)==std::vector<int32_t>({11,12}));
}
TEST(rr_jni_refuses_each_copy_exception_before_publication) {
    for(int fail=1;fail<=7;++fail){PadEngine e(48000);bank(e);JniChord c;failCopy=fail;
        CHECK_EQ(c.call(e),JNI_FALSE);CHECK(exception);CHECK_EQ(copyNumber,fail);CHECK(silent(render(e,16)));CHECK(ended(e).empty());}
    PadEngine e(48000);bank(e);JniChord c;exception=true;CHECK_EQ(c.call(e),JNI_FALSE);CHECK_EQ(copyNumber,0);CHECK(silent(render(e,16)));
}
TEST(rr_jni_rejects_mismatched_arrays_null_and_invalid_numbers) {
    for(int bad=0;bad<8;++bad){PadEngine e(48000);bank(e);JniChord c;
        switch(bad){case 0:c.ids.size=0;break;case 1:c.samples.size=1;break;case 2:c.starts.size=1;break;
        case 3:c.ends.size=1;break;case 4:c.loops.size=1;break;case 5:c.gains.size=1;break;case 6:c.pitches.size=1;break;
        case 7:c.pitches.d[1]=std::numeric_limits<double>::quiet_NaN();break;}
        CHECK_EQ(c.call(e),JNI_FALSE);CHECK(silent(render(e,16)));CHECK(ended(e).empty());}
    PadEngine e(48000);bank(e);JniChord c;
    CHECK_EQ(Java_com_snipsnap_app_NativePads_admitRoundRobinChord(&c.env,nullptr,0,nullptr,nullptr,nullptr,nullptr,nullptr,nullptr,nullptr),JNI_FALSE);
}
int main(){return check::runAll();}
