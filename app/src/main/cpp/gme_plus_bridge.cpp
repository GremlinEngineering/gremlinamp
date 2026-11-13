#include <jni.h>
#include "gme.h"
#include "Gme_File.h"
#include "Music_Emu.h"

namespace {
gme_t* decoder(jlong handle) { return reinterpret_cast<gme_t*>(handle); }

void fail(JNIEnv* env, const char* message) {
    if (message) env->ThrowNew(env->FindClass("java/io/IOException"), message);
}

const char* open(const void* data, long size, gme_t*& gme) {
    auto type = gme_identify_extension(gme_identify_header(data));
    if (!type) return "Unsupported GME+ file";
    gme = type->new_emu();
    if (!gme) return "GME+ allocation failed";
    // Set headroom before the core initializes its mixer at the output rate.
    gme->set_gain(type == gme_sgc_type || type == gme_gbs_type ? 0.5 : 1.0);
    if (const char* error = gme->set_sample_rate(44100)) return error;
    return gme_load_data(gme, data, size);
}
}

extern "C" JNIEXPORT jlong JNICALL Java_ru_oopssre_gremlinamp_GmePlus_open(
        JNIEnv* env, jclass, jbyteArray bytes, jint song) {
    jsize size = env->GetArrayLength(bytes);
    if (size < 4 || size > 64 * 1024 * 1024) {
        fail(env, "Invalid GME+ input size");
        return 0;
    }
    jbyte* data = env->GetByteArrayElements(bytes, nullptr);
    if (!data) return 0;
    gme_t* gme = nullptr;
    const char* error = open(data, size, gme);
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    if (!error) {
        gme_ignore_silence(gme, 1);
        error = song >= 0 && song < gme_track_count(gme)
            ? gme_start_track(gme, song) : "Invalid subsong";
    }
    if (error) {
        gme_delete(gme);
        fail(env, error);
        return 0;
    }
    return reinterpret_cast<jlong>(gme);
}

extern "C" JNIEXPORT void JNICALL Java_ru_oopssre_gremlinamp_GmePlus_render(
        JNIEnv* env, jclass, jlong handle, jshortArray output, jint frames) {
    if (frames < 0 || frames > env->GetArrayLength(output) / 2) {
        fail(env, "Invalid GME+ output buffer");
        return;
    }
    jshort* data = env->GetShortArrayElements(output, nullptr);
    if (!data) return;
    const char* error = gme_play(decoder(handle), frames * 2, data);
    env->ReleaseShortArrayElements(output, data, 0);
    fail(env, error);
}

extern "C" JNIEXPORT void JNICALL Java_ru_oopssre_gremlinamp_GmePlus_seek(
        JNIEnv* env, jclass, jlong handle, jint milliseconds) {
    fail(env, gme_seek(decoder(handle), milliseconds));
}

extern "C" JNIEXPORT jboolean JNICALL Java_ru_oopssre_gremlinamp_GmePlus_ended(
        JNIEnv*, jclass, jlong handle) { return gme_track_ended(decoder(handle)); }

extern "C" JNIEXPORT void JNICALL Java_ru_oopssre_gremlinamp_GmePlus_close(
        JNIEnv*, jclass, jlong handle) { gme_delete(decoder(handle)); }
