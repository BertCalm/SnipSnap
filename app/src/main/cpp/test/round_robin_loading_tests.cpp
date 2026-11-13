#include "PadEngine.h"
#include "RoundRobinLoading.h"
#include "check.h"
#include "jni.h"
#include <algorithm>
#include <limits>
#include <stdexcept>
#include <vector>
using namespace snipsnap;
extern "C" {
jlong Java_com_snipsnap_app_NativePads_prepareRoundRobinBank(JNIEnv*,jobject,jint);
jint Java_com_snipsnap_app_NativePads_addRoundRobinSample(JNIEnv*,jobject,jlong,jfloatArray);
jboolean Java_com_snipsnap_app_NativePads_commitRoundRobinBank(JNIEnv*,jobject,jlong);
jboolean Java_com_snipsnap_app_NativePads_startRoundRobinBank(JNIEnv*,jobject,jlong);
void Java_com_snipsnap_app_NativePads_destroy(JNIEnv*,jobject,jlong);
}
namespace {
bool pending=false,failCopy=false,failLength=false; int copies=0;
struct Array {std::vector<float> pcm{.1f,.2f,.3f,0};jsize n=4;};
jsize length(JNIEnv*,jarray a){if(failLength)pending=true;return reinterpret_cast<Array*>(a)->n;}
void copy(JNIEnv*,jfloatArray a,jsize,jsize n,jfloat* out){++copies;if(failCopy){pending=true;return;}auto& p=reinterpret_cast<Array*>(a)->pcm;std::copy_n(p.begin(),n,out);}
jboolean exception(JNIEnv*){return pending?JNI_TRUE:JNI_FALSE;}
JNINativeInterface_ table(){JNINativeInterface_ t{};t.GetArrayLength=length;t.GetFloatArrayRegion=copy;t.ExceptionCheck=exception;return t;}
auto iface=table();
struct Calls {
 JNIEnv env{&iface};Array a;
 Calls(){pending=failCopy=failLength=false;copies=0;}
 jlong prepare(){return Java_com_snipsnap_app_NativePads_prepareRoundRobinBank(&env,nullptr,44100);}
 int add(jlong h){return Java_com_snipsnap_app_NativePads_addRoundRobinSample(&env,nullptr,h,reinterpret_cast<jfloatArray>(&a));}
 bool commit(jlong h){return Java_com_snipsnap_app_NativePads_commitRoundRobinBank(&env,nullptr,h);}
};
struct FaultEngine {
 static int live,phase,stops; explicit FaultEngine(int){if(phase==1)throw std::bad_alloc();++live;}
 ~FaultEngine(){--live;}
 void beginBank(){if(phase==2)throw std::bad_alloc();}
 int addSample(std::vector<float>&&,int,int){if(phase==3)throw std::bad_alloc();return 7;}
 void commitBank(){if(phase==4)throw std::bad_alloc();}
 bool start(){if(phase==5)throw std::bad_alloc();return phase!=6;}
 void stop()noexcept{++stops;}
};int FaultEngine::live=0;int FaultEngine::phase=0;int FaultEngine::stops=0;
}
TEST(rr_loading_prepare_failure_destroys_partial_owner) {
 for(int phase:{1,2}){FaultEngine::phase=phase;CHECK(rr_loading::prepare<FaultEngine>(44100)==nullptr);CHECK_EQ(FaultEngine::live,0);}
 FaultEngine::phase=0;auto* e=rr_loading::prepare<FaultEngine>(44100);CHECK(e);CHECK_EQ(FaultEngine::live,1);delete e;CHECK_EQ(FaultEngine::live,0);
}
TEST(rr_loading_add_commit_and_start_failures_are_contained) {
 FaultEngine::phase=0;FaultEngine e(44100);
 FaultEngine::phase=3;CHECK_EQ(rr_loading::add(e,std::vector<float>{.1f}),-1);
 FaultEngine::phase=4;CHECK(!rr_loading::commit(e));
 FaultEngine::stops=0;for(int phase:{5,6}){FaultEngine::phase=phase;CHECK(!rr_loading::start(e));}
 CHECK_EQ(FaultEngine::stops,2);FaultEngine::phase=0;CHECK(rr_loading::start(e));CHECK_EQ(FaultEngine::stops,2);
}
TEST(rr_loading_jni_preserves_pending_exceptions_and_has_no_partial_add) {
 Calls c;jlong h=c.prepare();CHECK(h);failCopy=true;CHECK_EQ(c.add(h),-1);CHECK(pending);CHECK_EQ(copies,1);
 pending=false;failCopy=false;CHECK_EQ(c.add(h),0);CHECK(c.commit(h));
 pending=true;CHECK_EQ(c.prepare(),0);CHECK_EQ(c.add(h),-1);CHECK(!c.commit(h));
 CHECK_EQ(Java_com_snipsnap_app_NativePads_startRoundRobinBank(&c.env,nullptr,h),JNI_FALSE);CHECK(pending);
 pending=false;Java_com_snipsnap_app_NativePads_destroy(&c.env,nullptr,h);
}
TEST(rr_loading_jni_refuses_bad_lengths_numbers_nulls_and_rates) {
 Calls c;jlong h=c.prepare();CHECK(h);
 for(int n:{0,-1,33554433}){c.a.n=n;CHECK_EQ(c.add(h),-1);}CHECK_EQ(copies,0);
 c.a.n=4;failLength=true;CHECK_EQ(c.add(h),-1);CHECK(pending);CHECK_EQ(copies,0);pending=failLength=false;
 c.a.pcm[0]=std::numeric_limits<float>::quiet_NaN();CHECK_EQ(c.add(h),-1);c.a.pcm[0]=.1f;CHECK_EQ(c.add(h),0);
 CHECK_EQ(Java_com_snipsnap_app_NativePads_addRoundRobinSample(&c.env,nullptr,h,nullptr),-1);
 CHECK_EQ(c.add(0),-1);CHECK(!c.commit(0));CHECK_EQ(Java_com_snipsnap_app_NativePads_startRoundRobinBank(&c.env,nullptr,0),JNI_FALSE);
 CHECK_EQ(Java_com_snipsnap_app_NativePads_prepareRoundRobinBank(&c.env,nullptr,0),0);
 CHECK_EQ(Java_com_snipsnap_app_NativePads_prepareRoundRobinBank(&c.env,nullptr,192001),0);
 Java_com_snipsnap_app_NativePads_destroy(&c.env,nullptr,h);
}
TEST(rr_loading_new_bank_is_native_playable_and_handles_have_independent_ended_ids) {
 Calls c;auto first=c.prepare(),second=c.prepare();CHECK(first&&second&&first!=second);
 CHECK_EQ(c.add(first),0);CHECK_EQ(c.add(second),0);CHECK(c.commit(first));CHECK(c.commit(second));
 auto& a=*reinterpret_cast<PadEngine*>(first);auto& b=*reinterpret_cast<PadEngine*>(second);
 PadCommand n;n.voiceId=1;n.sample=0;n.start=0;n.end=4;n.gainL=n.gainR=1;n.pitch=1;
 CHECK(a.admitRoundRobinChord(&n,1));a.stop();int32_t ended[8];CHECK_EQ(a.drainEnded(ended,8),1u);CHECK_EQ(b.drainEnded(ended,8),0u);
 CHECK(b.admitRoundRobinChord(&n,1));float output[16]{};b.onAudioReady(nullptr,output,8);CHECK_NEAR(output[0],.1f,1e-6);CHECK_EQ(b.drainEnded(ended,8),1u);
 Java_com_snipsnap_app_NativePads_destroy(&c.env,nullptr,first);Java_com_snipsnap_app_NativePads_destroy(&c.env,nullptr,second);
}
int main(){return check::runAll();}
