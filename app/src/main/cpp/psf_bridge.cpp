#include <jni.h>
#include <cmath>
#include <limits>
#include <memory>
#include <mutex>

#include "psf_files.h"
#include "psflib.h"
#include "sega.h"
#include "usf.h"
#include "ssfplay/ssfplay.h"

extern "C" {
#include "driver.h"
#include "Spu.h"
void psxShutdown(void);
int stop_sexy_execute = 0;
void SPUirq(void) {}
}

namespace {
using namespace PsfFiles;
constexpr jint pcmRate = 44100;
constexpr jint blockFrames = 2048;

enum class Mode { PlayStation, Dreamcast, Nintendo64, Saturn };

class Psf {
public:
    explicit Psf(Mode mode) : mode(mode) {}
    ~Psf() {
        stopPlayStation();
        if (usfStarted) usf_shutdown(usf.data());
        if (saturn) ssfplay_close(saturn);
    }

    void restart() {
        position = 0;
        ended = false;
        switch (mode) {
            case Mode::PlayStation: loadPlayStation(); break;
            case Mode::Dreamcast: loadDreamcast(); break;
            case Mode::Nintendo64: loadNintendo64(); break;
            case Mode::Saturn: loadSaturn(); break;
        }
    }

    int render(int16_t* output, int request) {
        if (ended || request == 0) return 0;
        int count = request;
        if (duration >= 0) count = static_cast<int>(std::min<int64_t>(count, duration - position));
        int generated = 0;
        if (count > 0) {
            switch (mode) {
                case Mode::PlayStation: generated = renderPlayStation(output, count); break;
                case Mode::Dreamcast: generated = renderDreamcast(output, count); break;
                case Mode::Nintendo64: generated = renderNintendo64(output, count); break;
                case Mode::Saturn: generated = renderSaturn(output, count); break;
            }
        }
        position += generated;
        ended = generated < request || (duration >= 0 && position == duration);
        return generated;
    }

    InputFiles files;
    std::string path;
    std::vector<int16_t> pcm;
    int rate = pcmRate;
    int64_t duration = -1;
    int64_t position = 0;
    bool ended = false;
    bool stopped = false;
    static Psf* playStation;

private:
    static int upload(void* context, const uint8_t* program, size_t length, const uint8_t*, size_t) {
        if (!length) return 0;
        if (length < 5 || length > 8 * 1024 * 1024) return -1;
        auto& player = *static_cast<Psf*>(context);
        std::vector<uint8_t> mutableProgram(program, program + length);
        return sega_upload_program(player.sega.data(), mutableProgram.data(), length);
    }

    static double seconds(const std::string& text) {
        try {
            auto colon = text.find(':');
            double value = colon == std::string::npos
                ? std::stod(text)
                : std::stod(text.substr(0, colon)) * 60 + std::stod(text.substr(colon + 1));
            if (std::isfinite(value) && value > 0 && value < 86400) return value;
        } catch (const std::exception&) {}
        return 0;
    }

    static int tag(void* context, const char* name, const char* value) {
        auto& state = *static_cast<Psf*>(context);
        if (strcasecmp(name, "length") == 0) state.length = seconds(value);
        if (strcasecmp(name, "fade") == 0) state.fade = seconds(value);
        if (strcasecmp(name, "_enablecompare") == 0) state.compare = *value && *value != '0';
        if (strcasecmp(name, "_enablefifofull") == 0) state.fifoFull = *value && *value != '0';
        return 0;
    }

    static int uploadUsf(void* context, const uint8_t*, size_t, const uint8_t* reserved, size_t size) {
        if (!size) return 0;
        return usf_upload_section(static_cast<Psf*>(context)->usf.data(), reserved, size);
    }

    void loadNintendo64() {
        if (usfStarted) usf_restart(usf.data());
        else {
            usf.assign(usf_get_state_size(), 0);
            usf_clear(usf.data());
            usf_set_hle_audio(usf.data(), 1);
            psf_file_callbacks callbacks{"\\/:|", &files, openFile, readFile, seekFile, closeFile, tellFile};
            if (psf_load(path.c_str(), &callbacks, 0x21, uploadUsf, this, tag, this, 0, nullptr, nullptr) < 0)
                throw std::runtime_error("USF rejected the track or its dependencies");
            usf_set_compare(usf.data(), compare);
            usf_set_fifo_full(usf.data(), fifoFull);
            usfStarted = true;
        }
        const char* error = usf_render(usf.data(), nullptr, 0, &rate);
        if (error || rate < 4000 || rate > 192000)
            throw std::runtime_error(error ? error : "USF sample rate is out of range");
        duration = length > 0 ? std::llround((length + fade) * rate) : -1;
    }

    int renderNintendo64(int16_t* output, int frames) {
        const char* error = usf_render(usf.data(), output, frames, nullptr);
        if (error) throw std::runtime_error(error);
        applyFade(output, frames);
        return frames;
    }

    void loadSaturn() {
        if (saturn) {
            if (ssfplay_reset(saturn) != SSFPLAY_OK) throw std::runtime_error(ssfplay_error(saturn));
        } else {
            ssfplay_config config;
            ssfplay_config_init(&config);
            config.sample_rate = rate;
            if (ssfplay_open(path.c_str(), &config, &saturn) != SSFPLAY_OK)
                throw std::runtime_error(ssfplay_last_error());
            auto lengthMs = ssfplay_length_ms(saturn);
            auto fadeMs = ssfplay_fade_ms(saturn);
            if (lengthMs >= 0 && fadeMs >= 0 && lengthMs + fadeMs < 86400000)
                duration = (lengthMs + fadeMs) * rate / 1000;
        }
    }

    int renderSaturn(int16_t* output, int frames) {
        size_t count = 0;
        auto result = ssfplay_render(saturn, output, frames, &count);
        if (result < 0) throw std::runtime_error(ssfplay_error(saturn));
        return count;
    }

    void loadDreamcast() {
        static std::once_flag initialized;
        std::call_once(initialized, [] {
            if (sega_init() != 0) throw std::runtime_error("Highly Theoretical initialization failed");
        });
        sega.assign(sega_get_state_size(2), 0);
        if (sega.empty()) throw std::runtime_error("Dreamcast decoder has no state");
        sega_clear_state(sega.data(), 2);
        sega_enable_dry(sega.data(), 1);
        sega_enable_dsp(sega.data(), 1);
        sega_enable_dsp_dynarec(sega.data(), 0);
        length = fade = 0;
        psf_file_callbacks callbacks{"\\/:|", &files, openFile, readFile, seekFile, closeFile, tellFile};
        if (psf_load(path.c_str(), &callbacks, 0x12, upload, this, tag, this, 0, nullptr, nullptr) < 0)
            throw std::runtime_error("DSF rejected the track or its dependencies");
        duration = length > 0 ? std::llround((length + fade) * rate) : -1;
    }

    int renderDreamcast(int16_t* output, int frames) {
        int total = 0;
        for (int empty = 0; total < frames && empty < 8;) {
            uint32_t count = frames - total;
            int cycles = sega_execute(sega.data(), count * 128, output + total * 2, &count);
            if (cycles < 0 || count > uint32_t(frames - total)) break;
            empty = count ? 0 : empty + 1;
            total += count;
        }
        applyFade(output, total);
        return total;
    }

    void applyFade(int16_t* output, int frames) const {
        if (length <= 0 || fade <= 0) return;
        for (int i = 0; i < frames; ++i) {
            double time = double(position + i) / rate;
            if (time < length) continue;
            double gain = std::clamp((length + fade - time) / fade, 0.0, 1.0);
            output[i * 2] = std::lround(output[i * 2] * gain);
            output[i * 2 + 1] = std::lround(output[i * 2 + 1] * gain);
        }
    }

    void loadPlayStation() {
        stopPlayStation();
        if (playStation) throw std::runtime_error("PlayStation decoder already in use");
        playStation = this;
        pcm.clear();
        offset = 0;
        stopped = false;
        auto* info = sexy_load(path.data());
        if (!info) {
            playStation = nullptr;
            throw std::runtime_error("PSF rejected the track or its dependencies");
        }
        duration = info->length > 0 && info->length < 86400000 ? int64_t(info->length) * rate / 1000 : -1;
        sexy_freepsfinfo(info);
    }

    int renderPlayStation(int16_t* output, int request) {
        if (offset) {
            pcm.erase(pcm.begin(), pcm.begin() + offset);
            offset = 0;
        }
        if (!stopped && pcm.size() / 2 < size_t(request)) {
            stop_sexy_execute = 0;
            sexy_execute();
            stopped = !stop_sexy_execute;
        }
        int count = std::min<size_t>(request, pcm.size() / 2);
        std::copy_n(pcm.begin(), count * 2, output);
        offset = count * 2;
        return count;
    }

    void stopPlayStation() {
        if (playStation == this) {
            if (!stopped) psxShutdown();
            playStation = nullptr;
        }
    }

    Mode mode;
    std::vector<uint8_t> sega;
    std::vector<uint8_t> usf;
    bool usfStarted = false;
    bool compare = false;
    bool fifoFull = false;
    ssfplay_decoder* saturn = nullptr;
    size_t offset = 0;
    double length = 0;
    double fade = 0;
};

Psf* Psf::playStation = nullptr;
Psf& player(jlong handle) { return *reinterpret_cast<Psf*>(handle); }

void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), message);
}
}

extern "C" FILE* gremlin_psf_fopen(const char* path, const char* mode) {
    if (!Psf::playStation || std::strcmp(mode, "rb") != 0) return nullptr;
    std::string name(path);
    std::replace(name.begin(), name.end(), '\\', '/');
    while (name.rfind("./", 0) == 0) name.erase(0, 2);
    auto input = Psf::playStation->files.find(name);
    if (input == Psf::playStation->files.end()) return nullptr;
    return fmemopen(input->second.data(), input->second.size(), "rb");
}

extern "C" void sexyd_update(unsigned char* samples, long size) {
    if (!Psf::playStation || size <= 0) return;
    auto& pcm = Psf::playStation->pcm;
    auto* data = reinterpret_cast<const int16_t*>(samples);
    pcm.insert(pcm.end(), data, data + size / sizeof(int16_t));
    if (pcm.size() >= 4096 * 2) stop_sexy_execute = 1;
}

extern "C" JNIEXPORT jlong JNICALL Java_ru_oopssre_gremlinamp_NativePsf_open(
        JNIEnv* env, jclass, jint mode, jobjectArray names, jobjectArray sources, jstring path) {
    try {
        if (mode < 0 || mode > int(Mode::Saturn)) throw std::runtime_error("Unknown PSF decoder");
        auto state = std::make_unique<Psf>(Mode(mode));
        if (path) {
            UtfChars entry(env, path);
            if (!entry.chars) return 0;
            state->path = entry.chars;
        } else if (!PsfFiles::copy(env, names, sources, state->files, state->path)) return 0;
        state->restart();
        return reinterpret_cast<jlong>(state.release());
    } catch (const std::exception& failure) {
        fail(env, failure.what());
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL Java_ru_oopssre_gremlinamp_NativePsf_render(
        JNIEnv* env, jclass, jlong handle, jshortArray output, jint frames) {
    if (frames < 0 || frames > blockFrames || (output && frames > env->GetArrayLength(output) / 2)) {
        fail(env, "Invalid PSF output");
        return 0;
    }
    try {
        int16_t pcm[blockFrames * 2];
        int count = player(handle).render(pcm, frames);
        if (output && count) env->SetShortArrayRegion(output, 0, count * 2, pcm);
        return count;
    } catch (const std::exception& failure) {
        fail(env, failure.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL Java_ru_oopssre_gremlinamp_NativePsf_restart(JNIEnv* env, jclass, jlong handle) {
    try { player(handle).restart(); }
    catch (const std::exception& failure) { fail(env, failure.what()); }
}

extern "C" JNIEXPORT jint JNICALL Java_ru_oopssre_gremlinamp_NativePsf_rate(
        JNIEnv*, jclass, jlong handle) { return player(handle).rate; }

extern "C" JNIEXPORT jlong JNICALL Java_ru_oopssre_gremlinamp_NativePsf_duration(
        JNIEnv*, jclass, jlong handle) { return player(handle).duration; }

extern "C" JNIEXPORT jboolean JNICALL Java_ru_oopssre_gremlinamp_NativePsf_ended(
        JNIEnv*, jclass, jlong handle) { return player(handle).ended; }

extern "C" JNIEXPORT void JNICALL Java_ru_oopssre_gremlinamp_NativePsf_close(
        JNIEnv*, jclass, jlong handle) { delete &player(handle); }
