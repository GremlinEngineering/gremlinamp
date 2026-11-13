#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

#include "lazygsf.h"
#include "psflib.h"
#include "psf_files.h"

namespace {
using namespace PsfFiles;
constexpr size_t maxRom = 32 * 1024 * 1024;
constexpr uint32_t rate = 44100;
constexpr jint blockFrames = 2048;

uint32_t read32(const uint8_t* bytes) {
    return uint32_t(bytes[0]) | uint32_t(bytes[1]) << 8 | uint32_t(bytes[2]) << 16 | uint32_t(bytes[3]) << 24;
}

struct StateCloser {
    void operator()(gsf_state_t* state) const {
        gsf_shutdown(state);
        std::free(state);
    }
};

int upload(void* state, const uint8_t* data, size_t size, const uint8_t*, size_t) {
    if (size < 12) return -1;
    uint32_t entry = read32(data);
    uint32_t offset = read32(data + 4);
    uint32_t length = read32(data + 8);
    if (offset < entry || length > size - 12 || uint64_t(offset - entry) + length > maxRom) return -1;
    return gsf_upload_section(static_cast<gsf_state_t*>(state), data, size);
}

gsf_state_t* decoder(jlong handle) { return reinterpret_cast<gsf_state_t*>(handle); }

void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), message);
}
}

extern "C" JNIEXPORT jlong JNICALL Java_ru_oopssre_gremlinamp_NativeGsf_open(
        JNIEnv* env, jclass, jobjectArray names, jobjectArray sources) {
    try {
        InputFiles files;
        std::string entry;
        if (!copy(env, names, sources, files, entry)) return 0;
        static std::once_flag init;
        std::call_once(init, gsf_init);
        std::unique_ptr<gsf_state_t, StateCloser> state(static_cast<gsf_state_t*>(std::malloc(gsf_get_state_size())));
        if (!state) throw std::bad_alloc();
        gsf_clear(state.get());
        psf_file_callbacks callbacks{"\\/:|", &files, openFile, readFile, seekFile, closeFile, tellFile};
        if (psf_load(entry.c_str(), &callbacks, 0x22, upload, state.get(), nullptr, nullptr, 0, nullptr, nullptr) < 0)
            throw std::runtime_error("GSF rejected the track or its dependencies");
        gsf_set_sample_rate(state.get(), rate);
        if (gsf_render(state.get(), nullptr, 0)) throw std::runtime_error("Unable to start GSF");
        return reinterpret_cast<jlong>(state.release());
    } catch (const std::exception& error) {
        fail(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL Java_ru_oopssre_gremlinamp_NativeGsf_render(
        JNIEnv* env, jclass, jlong handle, jshortArray output, jint frames) {
    if (frames < 0 || frames > blockFrames || (output && frames > env->GetArrayLength(output) / 2)) {
        fail(env, "Invalid GSF output buffer");
        return;
    }
    int16_t pcm[blockFrames * 2];
    if (gsf_render(decoder(handle), output ? pcm : nullptr, frames))
        fail(env, "GSF render failed");
    else if (output)
        env->SetShortArrayRegion(output, 0, frames * 2, pcm);
}

extern "C" JNIEXPORT void JNICALL Java_ru_oopssre_gremlinamp_NativeGsf_restart(
        JNIEnv*, jclass, jlong handle) { gsf_restart(decoder(handle)); }

extern "C" JNIEXPORT void JNICALL Java_ru_oopssre_gremlinamp_NativeGsf_close(
        JNIEnv*, jclass, jlong handle) { StateCloser{}(decoder(handle)); }
