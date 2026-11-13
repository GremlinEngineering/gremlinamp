#include <jni.h>
#include "libopenmpt/libopenmpt.h"

#include <algorithm>
#include <cmath>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {
constexpr int rate = 44100;
constexpr int blockFrames = 2048;

struct Module {
    Module(jsize size, int subsong) : input(size), subsong(subsong) {}

    void restart() {
        source.reset(openmpt_module_create_from_memory2(input.data(), input.size(), openmpt_log_func_silent,
            nullptr, nullptr, nullptr, nullptr, nullptr, nullptr));
        if (!source) throw std::runtime_error("OpenMPT could not load the tracker module");
        if (!openmpt_module_select_subsong(source.get(), subsong))
            throw std::runtime_error("Tracker subsong is out of range");
        openmpt_module_set_repeat_count(source.get(), 0);
        position = 0;
        ended = false;
    }

    size_t render(int16_t* output, size_t frames) {
        if (ended) return 0;
        const auto count = openmpt_module_read_interleaved_stereo(source.get(), rate, frames, output);
        position += count;
        ended = count < frames;
        return count;
    }

    void seekFloor(jlong frame) {
        if (!frame) {
            restart();
            return;
        }
        const double seconds = openmpt_module_set_position_seconds(source.get(), frame / double(rate));
        if (!std::isfinite(seconds) || seconds < 0 || seconds > frame / double(rate)) {
            // Some modules report the end of a sequence for a time within it.
            // Reopen the original subsong before replaying to the requested frame.
            restart();
            return;
        }
        position = std::llround(seconds * rate);
        ended = false;
    }

    std::vector<jbyte> input;
    int subsong;
    std::unique_ptr<openmpt_module, decltype(&openmpt_module_destroy)> source{nullptr, openmpt_module_destroy};
    jlong position = 0;
    jlong length = -1;
    bool ended = false;
};

Module& module(jlong handle) { return *reinterpret_cast<Module*>(handle); }

void ioError(JNIEnv* env, const char* error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), error);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_oopssre_gremlinamp_NativeModule_open(JNIEnv* env, jclass, jbyteArray input, jint subsong) {
    try {
        const jsize size = env->GetArrayLength(input);
        if (size <= 0) throw std::runtime_error("Empty tracker module");
        auto result = std::make_unique<Module>(size, subsong);
        env->GetByteArrayRegion(input, 0, size, result->input.data());
        if (env->ExceptionCheck()) return 0;
        result->restart();
        const double seconds = openmpt_module_get_duration_seconds(result->source.get());
        if (std::isfinite(seconds) && seconds > 0 && seconds < 86400)
            result->length = std::llround(seconds * rate);
        return reinterpret_cast<jlong>(result.release());
    } catch (const std::exception& error) {
        ioError(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_oopssre_gremlinamp_NativeModule_render(JNIEnv* env, jclass, jlong handle, jshortArray output, jint frames) {
    if (frames < 0 || frames > blockFrames || (output && frames > env->GetArrayLength(output) / 2)) {
        ioError(env, "Invalid tracker output buffer");
        return 0;
    }
    if (!frames) return 0;
    int16_t pcm[blockFrames * 2];
    const auto count = module(handle).render(pcm, frames);
    if (output && count) env->SetShortArrayRegion(output, 0, count * 2, pcm);
    return static_cast<jint>(count);
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_oopssre_gremlinamp_NativeModule_position(JNIEnv*, jclass, jlong handle) { return module(handle).position; }

extern "C" JNIEXPORT jlong JNICALL
Java_ru_oopssre_gremlinamp_NativeModule_seekFloor(JNIEnv* env, jclass, jlong handle, jlong frame) {
    try {
        auto& state = module(handle);
        state.seekFloor(frame);
        return state.position;
    } catch (const std::exception& error) {
        ioError(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_oopssre_gremlinamp_NativeModule_duration(JNIEnv*, jclass, jlong handle) { return module(handle).length; }

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_oopssre_gremlinamp_NativeModule_ended(JNIEnv*, jclass, jlong handle) { return module(handle).ended; }

extern "C" JNIEXPORT void JNICALL
Java_ru_oopssre_gremlinamp_NativeModule_close(JNIEnv*, jclass, jlong handle) { delete &module(handle); }
