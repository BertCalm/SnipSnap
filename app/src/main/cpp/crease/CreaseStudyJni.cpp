#include <jni.h>
#include "CreaseStudyEngine.h"
#include <memory>
namespace {
snipsnap::crease::Engine* engine(jlong h) { return reinterpret_cast<snipsnap::crease::Engine*>(h); }
}
extern "C" {
JNIEXPORT jlong JNICALL Java_com_snipsnap_app_NativeCreaseStudy_create(JNIEnv* env,jobject,jintArray meta,jshortArray pcm) {
    if(!meta || !pcm || env->ExceptionCheck()) return 0;
    const auto n=env->GetArrayLength(meta);
    if(env->ExceptionCheck() || n<9 || n>408) return 0;
    try {
        std::vector<int> values(n);
        env->GetIntArrayRegion(meta,0,n,values.data());
        if(env->ExceptionCheck()) return 0;
        snipsnap::crease::Layout m{values[0],values[1],values[2],values[3],values[4],values[5],values[6],values[7],{}};
        m.cuts.assign(values.begin()+8,values.end());
        if(!m.valid()) return 0;
        const auto samples=env->GetArrayLength(pcm);
        if(env->ExceptionCheck() || samples<0 || std::size_t(samples)!=m.samples()) return 0;
        std::vector<int16_t> data(samples);
        env->GetShortArrayRegion(pcm,0,samples,data.data());
        if(env->ExceptionCheck()) return 0;
        return reinterpret_cast<jlong>(new snipsnap::crease::Engine(std::move(m),std::move(data)));
    } catch(const std::exception&) { return 0; }
}
JNIEXPORT void JNICALL Java_com_snipsnap_app_NativeCreaseStudy_destroy(JNIEnv*,jobject,jlong h) { delete engine(h); }
JNIEXPORT jboolean JNICALL Java_com_snipsnap_app_NativeCreaseStudy_start(JNIEnv*,jobject,jlong h) { return h && engine(h)->start() ? JNI_TRUE:JNI_FALSE; }
JNIEXPORT void JNICALL Java_com_snipsnap_app_NativeCreaseStudy_stop(JNIEnv*,jobject,jlong h) { if(h) engine(h)->stop(); }
JNIEXPORT jlong JNICALL Java_com_snipsnap_app_NativeCreaseStudy_press(JNIEnv*,jobject,jlong h,jboolean unfold) { return h && (unfold==JNI_TRUE || unfold==JNI_FALSE) ? jlong(engine(h)->reader.press(unfold==JNI_TRUE)):0; }
JNIEXPORT void JNICALL Java_com_snipsnap_app_NativeCreaseStudy_release(JNIEnv*,jobject,jlong h,jlong token) { if(h && token>0) engine(h)->reader.release(uint64_t(token)); }
JNIEXPORT void JNICALL Java_com_snipsnap_app_NativeCreaseStudy_panic(JNIEnv*,jobject,jlong h) { if(h) engine(h)->reader.panic(); }
JNIEXPORT jboolean JNICALL Java_com_snipsnap_app_NativeCreaseStudy_failed(JNIEnv*,jobject,jlong h) { return !h || engine(h)->failed.load() ? JNI_TRUE:JNI_FALSE; }
}
