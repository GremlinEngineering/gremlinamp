#include <jni.h>
#include <dlfcn.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
class Machine {
public:
    explicit Machine(const std::string& name) {
        static const std::string libraries[] = {
            "gba", "gb", "iv", "jag", "md", "st", "sv", "vx", "ws", "ws-headroom", "nf", "a5"
        };
        if (std::find(std::begin(libraries), std::end(libraries), name) == std::end(libraries))
            throw std::runtime_error("Unknown native music capsule");
        library = dlopen(("libgremlinamp-" + name + ".so").c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error(dlerror());
        prefix = "gremlin_" + (name == "nf" ? "nes" : name == "ws-headroom" ? "ws" : name);
    }

    ~Machine() {
        if (close) close();
        dlclose(library);
    }

    void start(const std::string& name, const std::string& directory, const std::vector<uint8_t>& rom, int rate, bool fds) {
        close = get<void (*)()>("close");
        error = get<const char* (*)()>("error");
        advance = get<int (*)(uint32_t)>("step");
        audio = get<const int16_t* (*)()>("audio");
        audioFrames = get<uint32_t (*)()>("audio_frames");
        if (!directory.empty()) get<void (*)(const char*)>("system_directory")(directory.c_str());
        get<void (*)(int)>("capture_video")(0);
        int status = name == "nf"
            ? get<int (*)(const uint8_t*, uint32_t, int)>("load")(rom.data(), rom.size(), fds)
            : get<int (*)(const uint8_t*, uint32_t)>("load")(rom.data(), rom.size());
        if (status) throw std::runtime_error(*error() ? error() : "Original machine could not load the cartridge");
        if (name != "jag" && std::abs(get<double (*)()>("source_rate")() - rate) > 0.5)
            throw std::runtime_error("Original machine sample rate differs");
    }

    int step(JNIEnv* env, uint32_t mask, jshortArray output) {
        if (advance(mask)) throw std::runtime_error(*error() ? error() : "Original machine frame failed");
        const auto frames = audioFrames();
        if (frames > 4096 || (output && frames > uint32_t(env->GetArrayLength(output) / 2)))
            throw std::runtime_error("Original machine frame size differs");
        if (output && frames) env->SetShortArrayRegion(output, 0, frames * 2, audio());
        return frames;
    }

private:
    template<class T> T get(const char* name) const {
        auto result = reinterpret_cast<T>(dlsym(library, (prefix + "_" + name).c_str()));
        if (!result) throw std::runtime_error(dlerror());
        return result;
    }

    std::string prefix;
    void* library = nullptr;
    void (*close)() = nullptr;
    const char* (*error)() = nullptr;
    int (*advance)(uint32_t) = nullptr;
    const int16_t* (*audio)() = nullptr;
    uint32_t (*audioFrames)() = nullptr;
};

struct UtfChars {
    JNIEnv* env;
    jstring string;
    const char* value;
    UtfChars(JNIEnv* env, jstring string) : env(env), string(string), value(env->GetStringUTFChars(string, nullptr)) {}
    ~UtfChars() { if (value) env->ReleaseStringUTFChars(string, value); }
};

void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_oopssre_gremlinamp_NativeMachine_openCore(JNIEnv* env, jclass, jstring library,
        jbyteArray bytes, jint rate, jboolean fds, jstring directory) {
    try {
        UtfChars name(env, library);
        UtfChars path(env, directory);
        if (!name.value || !path.value) return 0;
        std::vector<uint8_t> rom(env->GetArrayLength(bytes));
        env->GetByteArrayRegion(bytes, 0, rom.size(), reinterpret_cast<jbyte*>(rom.data()));
        if (env->ExceptionCheck()) return 0;
        auto machine = std::make_unique<Machine>(name.value);
        machine->start(name.value, path.value, rom, rate, fds);
        return reinterpret_cast<jlong>(machine.release());
    } catch (const std::exception& failure) {
        fail(env, failure.what());
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_oopssre_gremlinamp_NativeMachine_step(JNIEnv* env, jclass, jlong handle, jint mask, jshortArray output) {
    try {
        return reinterpret_cast<Machine*>(handle)->step(env, mask, output);
    } catch (const std::exception& failure) {
        fail(env, failure.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_ru_oopssre_gremlinamp_NativeMachine_closeCore(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Machine*>(handle);
}
