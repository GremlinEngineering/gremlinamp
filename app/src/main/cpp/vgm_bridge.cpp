#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <stdexcept>
#include <vector>

#include "player/playera.hpp"
#include "player/s98player.hpp"
#include "player/vgmplayer.hpp"
#include "utils/MemoryLoader.h"
#include "utils/StrUtils.h"

// Track tags come from the manifest. Android 26/27 do not provide iconv.
extern "C" UINT8 CPConv_Init(CPCONV** converter, const char*, const char*) {
    *converter = nullptr;
    return 0x80;
}
extern "C" void CPConv_Deinit(CPCONV*) {}
extern "C" UINT8 CPConv_StrConvert(CPCONV*, size_t*, char**, size_t, const char*) { return 0x80; }

namespace {
constexpr uint32_t sampleRate = 44100;
constexpr uint32_t blockFrames = 2048;
constexpr uint32_t maxInput = 256 * 1024 * 1024;

uint32_t read32(const uint8_t* p) {
    return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24;
}

struct TmcEvent {
    uint32_t frame;
    uint32_t action;
};

// TMC adds a K007232 sample stream to a VGM recording. Keep the mix and its
// timing from the matching libvgm 0.9 site adapter.
class TmcMixer {
public:
    void load(const uint8_t* bytes, size_t size, uint32_t& vgmSize) {
        if (size < 32) throw std::runtime_error("Incomplete TMC header");
        vgmSize = read32(bytes + 12);
        uint32_t romSize = read32(bytes + 16);
        uint32_t events = read32(bytes + 20);
        uint32_t end = read32(bytes + 24);
        if (read32(bytes + 4) != 1 || read32(bytes + 8) != sampleRate ||
            read32(bytes + 28) != 20000 || romSize != 524288 || vgmSize < 64 ||
            events > 100000 || 32ull + vgmSize + romSize + 12ull * events != size) {
            throw std::runtime_error("Invalid TMC input");
        }
        const uint8_t* rom = bytes + 32 + vgmSize;
        samples.reserve(romSize / 2);
        for (size_t i = 0; i < romSize; i += 2) {
            uint16_t fp = (uint16_t(rom[i]) | uint16_t(rom[i + 1]) << 8) >> 3;
            fp ^= 0x1e00;
            int16_t value = int16_t(uint16_t(fp << 6));
            samples.push_back(int16_t(int32_t(value) >> ((fp >> 10) & 7)));
        }
        const uint8_t* event = rom + romSize;
        uint32_t last = 0;
        for (uint32_t i = 0; i < events; ++i, event += 12) {
            uint32_t frame = read32(event);
            uint32_t action = read32(event + 4);
            if (frame < last || frame > end || action > 1 || read32(event + 8))
                throw std::runtime_error("Invalid TMC event");
            schedule.push_back({frame, action});
            last = frame;
        }
        endFrame = end;
    }

    void seek(uint32_t frame) {
        next = 0;
        playing = false;
        advance(frame);
    }

    float sample(uint32_t frame) {
        if (samples.empty()) return 0;
        advance(frame);
        if (!playing) return 0;
        uint64_t position = uint64_t(frame - started) * 20000;
        size_t index = position / sampleRate;
        uint32_t fraction = position % sampleRate;
        int a = samples[index];
        int b = index + 1 < samples.size() ? samples[index + 1] : 0;
        return float(int64_t(a) * (sampleRate - fraction) + int64_t(b) * fraction)
            / float(sampleRate * 32768ull) * 0.25f;
    }

    uint32_t endFrame = 0;

private:
    void advance(uint32_t frame) {
        while (next < schedule.size() && schedule[next].frame <= frame) {
            const auto& event = schedule[next++];
            finishSample(event.frame);
            if (event.action == 1) playing = false;
            else if (!playing) {
                playing = true;
                started = event.frame;
            }
        }
        finishSample(frame);
    }

    void finishSample(uint32_t frame) {
        if (playing && uint64_t(frame - started) * 20000 / sampleRate >= samples.size())
            playing = false;
    }

    std::vector<int16_t> samples;
    std::vector<TmcEvent> schedule;
    size_t next = 0;
    uint32_t started = 0;
    bool playing = false;
};

struct LoaderCloser {
    void operator()(DATA_LOADER* loader) const { DataLoader_Deinit(loader); }
};

class NativeVgm {
public:
    explicit NativeVgm(size_t size) : bytes(size) {}

    ~NativeVgm() {
        player.Stop();
        player.UnloadFile();
    }

    void open(uint32_t prefixFrames) {
        const uint8_t* data = bytes.data();
        auto size = uint32_t(bytes.size());
        if (size >= 4 && std::memcmp(data, "TMC1", 4) == 0) {
            mixer.load(data, size, size);
            data += 32;
        }
        if (size < 64 || (std::memcmp(data, "Vgm ", 4) != 0 && std::memcmp(data, "S983", 4) != 0))
            throw std::runtime_error("Invalid VGM/S98 header");
        loader.reset(MemoryLoader_Init(data, size));
        if (!loader || DataLoader_Load(loader.get())) throw std::runtime_error("Unable to load VGM");

        player.RegisterPlayerEngine(new VGMPlayer());
        player.RegisterPlayerEngine(new S98Player());
        if (player.SetOutputSettings(sampleRate, 2, 32, blockFrames))
            throw std::runtime_error("Unable to configure VGM output");
        auto options = player.GetConfiguration();
        options.loopCount = 0; // The transport applies the catalogue's loop/fade policy.
        options.fadeSmpls = 0;
        options.endSilenceSmpls = 0;
        player.SetConfiguration(options);
        if (player.LoadFile(loader.get()) || player.Start())
            throw std::runtime_error("libvgm rejected the track");
        PlayerBase& engine = *player.GetPlayer();
        std::vector<PLR_DEV_INFO> devices;
        if (engine.GetSongDeviceInfo(devices) != 1 || devices.empty())
            throw std::runtime_error("No running VGM sound devices");
        for (const auto& device : devices) {
            if (device.parentIdx == uint32_t(-1) && (!device.devDecl || !device.smplRate))
                throw std::runtime_error("Unsupported VGM sound chip");
        }
        uint32_t ticks = engine.GetTotalTicks();
        if (engine.GetLoopTicks() == 0 && ticks != 0 && ticks != uint32_t(-1))
            end = engine.Tick2Sample(ticks);
        if (mixer.endFrame > 0 && end >= 0) end = std::max(end, int64_t(mixer.endFrame));

        while (position < prefixFrames && !ended()) {
            uint32_t rendered = render(nullptr, std::min(blockFrames, prefixFrames - position));
            if (rendered == 0) break;
        }
        prefix = position;
    }

    int64_t duration() const { return end < 0 ? -1 : std::max(int64_t(0), end - prefix); }

    bool ended() const {
        if (end >= 0 && position >= uint64_t(end)) return true;
        return position >= mixer.endFrame && (player.GetState() & PLAYSTATE_END);
    }

    uint32_t render(int16_t* output, uint32_t frames) {
        if (ended()) return 0;
        if (end >= 0) frames = uint32_t(std::min(int64_t(frames), end - position));
        std::fill_n(buffer, frames * 2, 0.0f);
        uint32_t rendered = 0;
        if (!(player.GetState() & PLAYSTATE_END))
            rendered = player.Render(frames * 2 * sizeof(float), buffer) / (2 * sizeof(float));
        if (position < mixer.endFrame)
            rendered = std::max(rendered, std::min(frames, mixer.endFrame - position));
        for (uint32_t i = 0; i < rendered; ++i) {
            float overlay = mixer.sample(position + i);
            if (output) {
                output[i * 2] = pcm(buffer[i * 2] + overlay);
                output[i * 2 + 1] = pcm(buffer[i * 2 + 1] + overlay);
            }
        }
        position += rendered;
        return rendered;
    }

    uint32_t seek(uint32_t frame) {
        int64_t target = int64_t(prefix) + frame;
        if (end >= 0) target = std::min(end, target);
        if (target > std::numeric_limits<uint32_t>::max())
            throw std::runtime_error("VGM seek position is too large");
        if (player.Seek(PLAYPOS_SAMPLE, uint32_t(target)))
            throw std::runtime_error("libvgm seek failed");
        position = uint32_t(target);
        mixer.seek(position);
        return position - prefix;
    }

    std::vector<uint8_t> bytes;

private:
    static int16_t pcm(float value) {
        if (!std::isfinite(value)) return 0;
        return int16_t(std::lround(std::clamp(value, -1.0f, 32767.0f / 32768.0f) * 32768.0f));
    }

    std::unique_ptr<DATA_LOADER, LoaderCloser> loader;
    PlayerA player;
    TmcMixer mixer;
    float buffer[blockFrames * 2];
    int64_t end = -1;
    uint32_t position = 0;
    uint32_t prefix = 0;
};

NativeVgm& decoder(jlong handle) { return *reinterpret_cast<NativeVgm*>(handle); }

void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/io/IOException"), message);
}
}

extern "C" JNIEXPORT jlong JNICALL Java_ru_oopssre_gremlinamp_NativeVgm_open(
        JNIEnv* env, jclass, jbyteArray input, jint prefix) {
    jsize size = env->GetArrayLength(input);
    if (size < 0 || uint32_t(size) > maxInput || prefix < 0 || prefix > 7938000) {
        fail(env, "Invalid VGM size or preroll");
        return 0;
    }
    try {
        auto vgm = std::make_unique<NativeVgm>(size);
        env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(vgm->bytes.data()));
        if (env->ExceptionCheck()) return 0;
        vgm->open(prefix);
        return reinterpret_cast<jlong>(vgm.release());
    } catch (const std::exception& error) {
        fail(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL Java_ru_oopssre_gremlinamp_NativeVgm_render(
        JNIEnv* env, jclass, jlong handle, jshortArray samples, jint frames) {
    if (frames < 0 || uint32_t(frames) > blockFrames || frames * 2 > env->GetArrayLength(samples)) {
        fail(env, "Invalid VGM output buffer");
        return 0;
    }
    try {
        int16_t buffer[blockFrames * 2];
        uint32_t count = decoder(handle).render(buffer, frames);
        if (count) env->SetShortArrayRegion(samples, 0, count * 2, buffer);
        return count;
    } catch (const std::exception& error) {
        fail(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jlong JNICALL Java_ru_oopssre_gremlinamp_NativeVgm_seek(
        JNIEnv* env, jclass, jlong handle, jlong frame) {
    try {
        if (frame < 0 || uint64_t(frame) > std::numeric_limits<uint32_t>::max())
            throw std::runtime_error("Invalid VGM seek position");
        return decoder(handle).seek(uint32_t(frame));
    } catch (const std::exception& error) {
        fail(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jlong JNICALL Java_ru_oopssre_gremlinamp_NativeVgm_duration(
        JNIEnv*, jclass, jlong handle) { return decoder(handle).duration(); }

extern "C" JNIEXPORT jboolean JNICALL Java_ru_oopssre_gremlinamp_NativeVgm_ended(
        JNIEnv*, jclass, jlong handle) { return decoder(handle).ended(); }

extern "C" JNIEXPORT void JNICALL Java_ru_oopssre_gremlinamp_NativeVgm_close(
        JNIEnv*, jclass, jlong handle) { delete reinterpret_cast<NativeVgm*>(handle); }
