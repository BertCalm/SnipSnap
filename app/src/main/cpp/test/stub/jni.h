// Just enough JNI to compile `jni.cpp` on the host, in the same spirit as
// `stub/android/log.h` beside it: the real header comes from the NDK when
// the app is built, and `android-build` in CI is what proves these
// signatures still match it. What this buys is the ability to *run*
// `jni.cpp` — 31 entry points and the marshalling every pad hit crosses —
// which no test could do while the file needed a JVM to link.
//
// Only what `jni.cpp` uses is here. A function it does not call is a
// function this header does not declare, so a new call site fails to
// compile rather than silently going untested.
#pragma once

#include <cstdint>

typedef unsigned char jboolean;
typedef int32_t jint;
typedef int64_t jlong;
typedef float jfloat;
typedef double jdouble;
typedef jint jsize;

// The real header makes these distinct classes so a jfloatArray cannot be
// passed where a jintArray is wanted. Keeping that here means the stub
// catches the same mix-ups the NDK header would.
class _jobject {};
class _jarray : public _jobject {};
class _jbooleanArray : public _jarray {};
class _jintArray : public _jarray {};
class _jfloatArray : public _jarray {};

typedef _jobject* jobject;
typedef _jarray* jarray;
typedef _jbooleanArray* jbooleanArray;
typedef _jintArray* jintArray;
typedef _jfloatArray* jfloatArray;

#define JNI_FALSE 0
#define JNI_TRUE 1
#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL

struct JNIEnv_;
typedef JNIEnv_ JNIEnv;

struct JNINativeInterface_ {
    jsize (*GetArrayLength)(JNIEnv*, jarray);
    void (*GetFloatArrayRegion)(JNIEnv*, jfloatArray, jsize, jsize, jfloat*);
    void (*GetIntArrayRegion)(JNIEnv*, jintArray, jsize, jsize, jint*);
    void (*GetBooleanArrayRegion)(JNIEnv*, jbooleanArray, jsize, jsize, jboolean*);
    jfloatArray (*NewFloatArray)(JNIEnv*, jsize);
    jintArray (*NewIntArray)(JNIEnv*, jsize);
    void (*SetFloatArrayRegion)(JNIEnv*, jfloatArray, jsize, jsize, const jfloat*);
    void (*SetIntArrayRegion)(JNIEnv*, jintArray, jsize, jsize, const jint*);
};

// C++ callers reach the table through member functions, exactly as they do
// with the real header, so `jni.cpp` reads the same either way.
struct JNIEnv_ {
    const JNINativeInterface_* functions;

    jsize GetArrayLength(jarray a) { return functions->GetArrayLength(this, a); }
    void GetFloatArrayRegion(jfloatArray a, jsize s, jsize n, jfloat* b) { functions->GetFloatArrayRegion(this, a, s, n, b); }
    void GetIntArrayRegion(jintArray a, jsize s, jsize n, jint* b) { functions->GetIntArrayRegion(this, a, s, n, b); }
    void GetBooleanArrayRegion(jbooleanArray a, jsize s, jsize n, jboolean* b) { functions->GetBooleanArrayRegion(this, a, s, n, b); }
    jfloatArray NewFloatArray(jsize n) { return functions->NewFloatArray(this, n); }
    jintArray NewIntArray(jsize n) { return functions->NewIntArray(this, n); }
    void SetFloatArrayRegion(jfloatArray a, jsize s, jsize n, const jfloat* b) { functions->SetFloatArrayRegion(this, a, s, n, b); }
    void SetIntArrayRegion(jintArray a, jsize s, jsize n, const jint* b) { functions->SetIntArrayRegion(this, a, s, n, b); }
};
