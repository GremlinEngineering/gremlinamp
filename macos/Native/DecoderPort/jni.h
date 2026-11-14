#pragma once
// Adapter for the shared decoder frontends. These types represent borrowed
// native buffers; there is no Java runtime, object allocation or JNI lookup.
#include <cstdint>
#include <cstring>
#include <string>
using jint=int32_t; using jlong=int64_t; using jsize=int32_t;
using jbyte=int8_t; using jshort=int16_t; using jboolean=bool;
using jclass=void*; using jobject=void*; using jstring=const char*;
template<class T> struct Borrowed { T* data; jsize size; };
using jbyteArray=Borrowed<jbyte>*; using jshortArray=Borrowed<jshort>*;
using jintArray=Borrowed<jint>*; using jobjectArray=Borrowed<jobject>*;
#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL
#define JNI_ABORT 2
struct JNIEnv {
    std::string error;
    template<class T> jsize GetArrayLength(Borrowed<T>* a) { return a ? a->size : 0; }
    template<class T> void read(Borrowed<T>* a, jsize i, jsize n, T* dst) {
        if (!a || i < 0 || n < 0 || i > a->size || n > a->size-i) { error="Invalid decoder buffer"; return; }
        if (n) std::memcpy(dst,a->data+i,sizeof(T)*n);
    }
    void GetByteArrayRegion(jbyteArray a, jsize i, jsize n, jbyte* d) { read(a,i,n,d); }
    void GetIntArrayRegion(jintArray a, jsize i, jsize n, jint* d) { read(a,i,n,d); }
    void SetShortArrayRegion(jshortArray a, jsize i, jsize n, const jshort* d) {
        if (!a || i < 0 || n < 0 || i > a->size || n > a->size-i) { error="Invalid decoder output"; return; }
        if (n) std::memcpy(a->data+i,d,sizeof(jshort)*n);
    }
    jobject GetObjectArrayElement(jobjectArray a, jsize i) { return a && i>=0 && i<a->size ? a->data[i] : nullptr; }
    const char* GetStringUTFChars(jstring s, jboolean*) { return s; }
    void ReleaseStringUTFChars(jstring, const char*) {}
    jbyte* GetByteArrayElements(jbyteArray a, jboolean*) { return a ? a->data : nullptr; }
    jshort* GetShortArrayElements(jshortArray a, jboolean*) { return a ? a->data : nullptr; }
    void ReleaseByteArrayElements(jbyteArray, jbyte*, jint) {}
    void ReleaseShortArrayElements(jshortArray, jshort*, jint) {}
    void DeleteLocalRef(const void*) {}
    bool ExceptionCheck() const { return !error.empty(); }
    jclass FindClass(const char*) { return nullptr; }
    void ThrowNew(jclass, const char* message) { error=message ? message : "Decoder error"; }
};
