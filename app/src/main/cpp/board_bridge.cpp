#include <jni.h>
#include <dlfcn.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

extern "C" {
void gremlin_vgm_close();
int gremlin_vgm_load(const uint8_t*, uint32_t, uint32_t);
uint32_t gremlin_vgm_render(float*, uint32_t);
}

namespace {
constexpr uint32_t blockFrames = 2048;
constexpr uint32_t sampleRate = 44100;
constexpr uint32_t maxInput = 256 * 1024 * 1024;

enum class Interface { Float, FloatNoSubsong, Atari, Spt, Snes, Wsr, Dcs, G0, K0 };

struct BoardSpec {
    const char* name;
    const char* prefix;
    Interface api;
};

constexpr BoardSpec boards[] = {
    {"acm", "gremlin_acm", Interface::Float}, {"bms", "gremlin_bms", Interface::Float},
    {"cps3", "gremlin_cps3", Interface::Float}, {"dk", "gremlin_dk", Interface::Float},
    {"e6m", "gremlin_e6m", Interface::Float}, {"f3", "gremlin_f3", Interface::Float},
    {"ki", "gremlin_ki", Interface::Float}, {"m2s", "gremlin_m2s", Interface::Float},
    {"mario", "gremlin_mario", Interface::Float},
    {"lsm", "gremlin_lsm", Interface::FloatNoSubsong}, {"ngl", "gremlin_ngl", Interface::FloatNoSubsong},
    {"nws", "gremlin_nws", Interface::FloatNoSubsong},
    {"a26", "gremlin_atari", Interface::Atari}, {"a78", "gremlin_atari", Interface::Atari},
    {"spt", "spt", Interface::Spt}, {"theme-snes", "theme_snes", Interface::Snes},
    {"wsr", "", Interface::Wsr},
    {"dcs", "gremlin_dcs", Interface::Dcs},
    {"g0", "gremlin_g0", Interface::G0}, {"k0", "gremlin_k0", Interface::K0},
};

uint32_t read32(const uint8_t* p) {
    return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24;
}

struct LibraryCloser {
    void operator()(void* library) const { dlclose(library); }
};

class Board {
public:
    Board(const std::string& name, size_t size, uint32_t subsong, int region, std::vector<jint> config)
        : bytes(size), subsong(subsong), region(region), config(std::move(config)) {
        const auto* spec = std::find_if(std::begin(boards), std::end(boards),
            [&](const auto& entry) { return name == entry.name; });
        if (spec == std::end(boards)) throw std::runtime_error("Unknown native decoder");
        api = spec->api;
        prefix = spec->prefix;
        library.reset(dlopen(("libgremlinamp-" + name + ".so").c_str(), RTLD_NOW | RTLD_LOCAL));
        if (!library) throw std::runtime_error(dlerror());
        if (api == Interface::Wsr) {
            close = required<void (*)()>("Close_WSR");
            updateWsr = required<int (*)(void*, unsigned, unsigned)>("Update_WSR");
        } else if (api == Interface::Snes || api == Interface::Spt) {
            renderShort = required<int (*)(int16_t*, uint32_t)>(prefix + "_render");
            rate = api == Interface::Spt ? 32040 : 32000;
        } else {
            close = required<void (*)()>(prefix + "_close");
            error = required<const char* (*)()>(prefix + "_error");
            if (api == Interface::Dcs || trace()) {
                boardRender = required<int (*)(uint32_t)>(prefix + "_render");
                if (api == Interface::Dcs) {
                    if (this->config.size() != 5) throw std::runtime_error("Invalid DCS configuration");
                    boardAudio = required<const int16_t* (*)()>(prefix + "_audio");
                    rate = 31250;
                    duration = this->config[4];
                } else {
                    if (this->config.size() != 4) throw std::runtime_error("Invalid trace configuration");
                    traceAudio = required<const float* (*)()>(prefix + "_audio");
                    writeRegister = required<int (*)(uint32_t, uint32_t)>(prefix + "_write");
                    rate = 48000;
                    duration = this->config[2];
                }
            } else if (api == Interface::Atari) renderAtari = required<int (*)(float*, unsigned)>(prefix + "_render");
            else renderFloat = required<uint32_t (*)(float*, uint32_t)>(prefix + "_render");
        }
    }

    ~Board() {
        if (close && started) close();
        if (api == Interface::K0 && started) gremlin_vgm_close();
    }

    void restart() {
        if (close && started) close();
        finished = false;
        rendered = 0;
        started = true;
        int result = 0;
        switch (api) {
            case Interface::Float:
                result = required<int (*)(const uint8_t*, uint32_t, uint32_t, uint32_t)>(prefix + "_load")(
                    bytes.data(), bytes.size(), rate, subsong);
                break;
            case Interface::FloatNoSubsong:
                if (subsong) throw std::runtime_error("Decoder accepts one song per file");
                result = required<int (*)(const uint8_t*, uint32_t, uint32_t)>(prefix + "_load")(
                    bytes.data(), bytes.size(), rate);
                break;
            case Interface::Atari:
                if (subsong) throw std::runtime_error("Atari file contains one song");
                result = required<int (*)(const uint8_t*, size_t, int, int)>(prefix + "_load")(
                    bytes.data(), bytes.size(), rate, region);
                break;
            case Interface::Spt:
                if (subsong) throw std::runtime_error("SPT file contains one song");
                result = required<int (*)(const uint8_t*, int)>("spt_load")(bytes.data(), bytes.size());
                if (result == 0) duration = (uint64_t(read32(bytes.data() + 16)) + 31) / 32;
                break;
            case Interface::Snes:
                if (subsong) throw std::runtime_error("SNES stream contains one song");
                result = required<int (*)(const uint8_t*, uint32_t)>("theme_snes_load")(bytes.data(), bytes.size());
                if (result == 0) duration = read32(bytes.data() + 12);
                break;
            case Interface::Dcs:
                result = required<int (*)(const uint8_t*, uint32_t, uint32_t, uint32_t)>(prefix + "_load")(
                    bytes.data(), bytes.size(), config[0], config[1]);
                if (!result)
                    result = required<int (*)(uint32_t, uint32_t)>(prefix + "_select")(config[2], config[3]);
                break;
            case Interface::G0:
            case Interface::K0:
                restartTrace();
                break;
            case Interface::Wsr: {
                auto setFrequency = required<unsigned (*)(unsigned)>("Set_Frequency");
                setFrequency(rate);
                if (!required<int (*)(const void*, unsigned)>("Load_WSR")(bytes.data(), bytes.size()))
                    throw std::runtime_error("Invalid WSR file");
                required<void (*)(unsigned)>("Reset_WSR")(subsong);
                if (setFrequency(rate) != rate) throw std::runtime_error("Invalid WSR sample rate");
                required<void (*)(unsigned)>("Set_ChannelMuting")(0);
                break;
            }
        }
        if (result != 0) throw std::runtime_error(reason("Decoder rejected audio"));
    }

    int render(int16_t* pcm, uint32_t frames) {
        if (finished || !frames) return 0;
        if (duration >= 0) frames = std::min<int64_t>(frames, duration - rendered);
        if (!frames) { finished = true; return 0; }
        int count = frames;
        if (trace()) {
            uint32_t done = 0;
            while (done < frames) done += renderTrace(pcm + done * 2, frames - done);
        } else if (api == Interface::Dcs) {
            if (boardRender(frames)) throw std::runtime_error(reason("DCS playback failed"));
            std::copy_n(boardAudio(), frames * 2, pcm);
        } else if (renderFloat || renderAtari) {
            float buffer[blockFrames * 2] = {};
            if (renderAtari) {
                if (renderAtari(buffer, frames)) throw std::runtime_error(reason("Atari playback failed"));
            } else {
                count = renderFloat(buffer, frames);
                if (count == 0 && error && *error()) throw std::runtime_error(error());
            }
            if (count < 0 || uint32_t(count) > frames) throw std::runtime_error("Invalid decoder frame count");
            for (int i = 0; i < count * 2; ++i) {
                float value = buffer[i];
                if (!std::isfinite(value)) throw std::runtime_error("Invalid decoder sample");
                pcm[i] = int16_t(std::lround(std::clamp(value, -1.0f, 32767.0f / 32768.0f) * 32768.0f));
            }
        } else if (renderShort) {
            count = renderShort(pcm, frames);
            if (count < 0 || uint32_t(count) > frames) throw std::runtime_error("Invalid decoder frame count");
        } else if (!updateWsr(pcm, frames * 4, frames)) {
            throw std::runtime_error("WSR playback failed");
        }
        rendered += count;
        if (uint32_t(count) < frames || (duration >= 0 && rendered == duration)) finished = true;
        return count;
    }

    std::vector<uint8_t> bytes;
    uint32_t rate = sampleRate;
    int64_t duration = -1;
    bool finished = false;

private:
    bool trace() const { return api == Interface::G0 || api == Interface::K0; }

    void restartTrace() {
        tracePosition = 0;
        eventOffset = 0;
        if (api == Interface::G0) {
            if (required<int (*)(uint32_t)>(prefix + "_reset")(config[0]))
                throw std::runtime_error(reason("Galaxian reset failed"));
        } else {
            uint32_t vgmSize = config[3];
            if (bytes.size() < uint64_t(vgmSize) + 512)
                throw std::runtime_error("Invalid Konami PROM");
            if (required<int (*)(const uint8_t*, uint32_t)>(prefix + "_reset")(bytes.data() + vgmSize, 512)
                    || gremlin_vgm_load(bytes.data(), vgmSize, rate))
                throw std::runtime_error(reason("Konami reset failed"));
            eventOffset = vgmSize + 512;
        }
        if ((bytes.size() - eventOffset) % 8) throw std::runtime_error("Invalid trace event length");
        while (tracePosition < uint32_t(config[1]))
            renderTrace(nullptr, std::min<uint32_t>(blockFrames, config[1] - tracePosition));
    }

    uint32_t renderTrace(int16_t* output, uint32_t frames) {
        while (eventOffset < bytes.size()) {
            const uint8_t* event = bytes.data() + eventOffset;
            uint32_t time = read32(event);
            if (time < tracePosition) throw std::runtime_error("Invalid register event position");
            if (time > tracePosition) {
                frames = std::min(frames, time - tracePosition);
                break;
            }
            uint32_t value = api == Interface::G0 ? event[5] : uint32_t(event[6]) | uint32_t(event[7]) << 8;
            if (writeRegister(event[4], value)) throw std::runtime_error(reason("Register write failed"));
            eventOffset += 8;
        }
        if (!frames || boardRender(frames)) throw std::runtime_error(reason("Register trace playback failed"));
        float ay[blockFrames * 2] = {};
        if (api == Interface::K0) gremlin_vgm_render(ay, frames);
        if (output) {
            const float* source = traceAudio();
            for (uint32_t i = 0; i < frames * 2; ++i) {
                float sample = api == Interface::K0 ? ay[i] + 0.35f * source[i] : source[i];
                if (!std::isfinite(sample)) throw std::runtime_error("Invalid trace sample");
                output[i] = int16_t(std::lround(std::clamp(sample, -1.0f, 32767.0f / 32768) * 32768));
            }
        }
        tracePosition += frames;
        return frames;
    }

    template<typename T> T required(const std::string& name) {
        auto function = reinterpret_cast<T>(dlsym(library.get(), name.c_str()));
        if (!function) throw std::runtime_error("Missing native decoder entry point: " + name);
        return function;
    }

    const char* reason(const char* fallback) const {
        const char* message = error ? error() : nullptr;
        return message && *message ? message : fallback;
    }

    Interface api;
    std::string prefix;
    uint32_t subsong;
    int region;
    std::vector<jint> config;
    uint32_t tracePosition = 0;
    size_t eventOffset = 0;
    int64_t rendered = 0;
    int (*boardRender)(uint32_t) = nullptr;
    int (*writeRegister)(uint32_t, uint32_t) = nullptr;
    const int16_t* (*boardAudio)() = nullptr;
    const float* (*traceAudio)() = nullptr;
    std::unique_ptr<void, LibraryCloser> library;
    bool started = false;
    void (*close)() = nullptr;
    const char* (*error)() = nullptr;
    uint32_t (*renderFloat)(float*, uint32_t) = nullptr;
    int (*renderAtari)(float*, unsigned) = nullptr;
    int (*renderShort)(int16_t*, uint32_t) = nullptr;
    int (*updateWsr)(void*, unsigned, unsigned) = nullptr;
};

Board& decoder(jlong handle) { return *reinterpret_cast<Board*>(handle); }
void fail(JNIEnv* env, const char* text) { env->ThrowNew(env->FindClass("java/io/IOException"), text); }
}

extern "C" JNIEXPORT jlong JNICALL Java_ru_oopssre_gremlinamp_NativeBoard_open(
        JNIEnv* env, jclass, jstring engine, jbyteArray data, jint subsong, jint region, jintArray config) {
    jsize size = env->GetArrayLength(data);
    if (size <= 0 || uint32_t(size) > maxInput || subsong < 0) {
        fail(env, "Invalid decoder input");
        return 0;
    }
    const char* name = env->GetStringUTFChars(engine, nullptr);
    if (!name) return 0;
    try {
        std::vector<jint> settings(env->GetArrayLength(config));
        if (!settings.empty()) env->GetIntArrayRegion(config, 0, settings.size(), settings.data());
        auto board = std::make_unique<Board>(name, size, subsong, region, std::move(settings));
        env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte*>(board->bytes.data()));
        if (env->ExceptionCheck()) {
            env->ReleaseStringUTFChars(engine, name);
            return 0;
        }
        board->restart();
        env->ReleaseStringUTFChars(engine, name);
        return reinterpret_cast<jlong>(board.release());
    } catch (const std::exception& e) {
        env->ReleaseStringUTFChars(engine, name);
        fail(env, e.what());
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL Java_ru_oopssre_gremlinamp_NativeBoard_render(
        JNIEnv* env, jclass, jlong handle, jshortArray output, jint frames) {
    if (frames < 0 || uint32_t(frames) > blockFrames || (output && frames > env->GetArrayLength(output) / 2)) {
        fail(env, "Invalid decoder output");
        return 0;
    }
    try {
        // The SNES DSP may retain a few oversampled values after the last requested frame.
        int16_t pcm[blockFrames * 2 + 32];
        int count = decoder(handle).render(pcm, frames);
        if (output && count) env->SetShortArrayRegion(output, 0, count * 2, pcm);
        return count;
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL Java_ru_oopssre_gremlinamp_NativeBoard_restart(
        JNIEnv* env, jclass, jlong handle) {
    try { decoder(handle).restart(); }
    catch (const std::exception& e) { fail(env, e.what()); }
}

extern "C" JNIEXPORT jint JNICALL Java_ru_oopssre_gremlinamp_NativeBoard_rate(
        JNIEnv*, jclass, jlong handle) { return decoder(handle).rate; }

extern "C" JNIEXPORT jlong JNICALL Java_ru_oopssre_gremlinamp_NativeBoard_duration(
        JNIEnv*, jclass, jlong handle) { return decoder(handle).duration; }

extern "C" JNIEXPORT jboolean JNICALL Java_ru_oopssre_gremlinamp_NativeBoard_ended(
        JNIEnv*, jclass, jlong handle) { return decoder(handle).finished; }

extern "C" JNIEXPORT void JNICALL Java_ru_oopssre_gremlinamp_NativeBoard_close(
        JNIEnv*, jclass, jlong handle) { delete reinterpret_cast<Board*>(handle); }
