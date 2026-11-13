#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <vector>

#include "sidplayfp/SidConfig.h"
#include "sidplayfp/SidTune.h"
#include "sidplayfp/SidTuneInfo.h"
#include "sidplayfp/sidplayfp.h"
#include "builders/sidlite-builder/sidlite.h"
extern "C" {
#include "libvgmstream.h"
}

namespace {
class Stream {
public:
    virtual ~Stream() = default;
    virtual void restart() = 0;
    virtual int render(int16_t* output, int frames) = 0;
    virtual int64_t seek(int64_t) { return -1; }

    int rate = 44100;
    int64_t length = -1;
    bool ended = false;
};

class Sid final : public Stream {
public:
    Sid(const uint8_t* data, size_t bytes, unsigned subsong)
        : tune(data, bytes), builder("GremlinAmp SIDLite") {
        if (!tune.getStatus()) throw std::runtime_error(tune.statusString());
        tune.selectSong(subsong && subsong < tune.getInfo()->songs() ? subsong + 1 : 0);
        restart();
    }

    void restart() override {
        engine.reset();
        engine = std::make_unique<sidplayfp>();
        SidConfig config;
        config.frequency = rate;
        config.samplingMethod = SidConfig::INTERPOLATE;
        config.sidEmulation = &builder;
        if (!engine->config(config)) throw std::runtime_error(engine->error());
        engine->setRoms(nullptr, nullptr, nullptr);
        if (!engine->load(&tune)) throw std::runtime_error(engine->error());
        engine->initMixer(true);
        pending.clear();
        ended = false;
    }

    int render(int16_t* output, int frames) override {
        while (pending.size() < size_t(frames) * 2) {
            int count = engine->play(20000);
            if (count <= 0) throw std::runtime_error(engine->error());
            scratch.resize(size_t(count) * 2 + 64);
            auto samples = engine->mix(scratch.data(), count);
            if (!samples || samples > scratch.size()) throw std::runtime_error("Invalid SID audio block");
            pending.insert(pending.end(), scratch.begin(), scratch.begin() + samples);
        }
        std::copy_n(pending.begin(), frames * 2, output);
        pending.erase(pending.begin(), pending.begin() + frames * 2);
        return frames;
    }

private:
    SidTune tune;
    SIDLiteBuilder builder;
    std::unique_ptr<sidplayfp> engine;
    std::vector<int16_t> pending;
    std::vector<int16_t> scratch;
};

class Xa final : public Stream {
public:
    Xa(std::vector<uint8_t> input, int subsong) : bytes(std::move(input)) {
        libstreamfile_t* file = open(&bytes, "track.xa");
        libvgmstream_config_t config{};
        config.disable_config_override = true;
        config.ignore_loop = true;
        config.force_sfmt = LIBVGMSTREAM_SFMT_PCM16;
        config.auto_downmix_channels = 2;
        source.reset(libvgmstream_create(file, subsong + 1, &config));
        libstreamfile_close(file);
        if (!source || source->format->channels < 1 || source->format->channels > 2)
            throw std::runtime_error("vgmstream rejected the XA file or subsong");
        channels = source->format->channels;
        rate = source->format->sample_rate;
        length = source->format->play_samples;
        if (length < 1 || rate < 4000 || rate > 192000) throw std::runtime_error("Invalid XA stream clock");
    }

    void restart() override { seek(0); }

    int64_t seek(int64_t frame) override {
        frame = std::clamp<int64_t>(frame, 0, length);
        libvgmstream_seek(source.get(), frame);
        ended = false;
        return frame;
    }

    int render(int16_t* output, int frames) override {
        if (ended) return 0;
        int result = libvgmstream_fill(source.get(), output, frames);
        if (result < 0) throw std::runtime_error("XA decoding failed");
        int count = source->decoder->buf_samples;
        if (count < 0 || count > frames) throw std::runtime_error("Invalid XA audio block");
        if (channels == 1) {
            for (int i = count - 1; i >= 0; --i) {
                auto mono = output[i];
                output[2 * i] = output[2 * i + 1] = mono;
            }
        }
        ended = source->decoder->done;
        return count;
    }

private:
    static int read(void* data, uint8_t* destination, int64_t offset, int length) {
        auto& bytes = *static_cast<std::vector<uint8_t>*>(data);
        if (offset < 0 || uint64_t(offset) >= bytes.size() || length <= 0) return 0;
        int count = std::min<size_t>(length, bytes.size() - offset);
        std::memcpy(destination, bytes.data() + offset, count);
        return count;
    }

    static int64_t size(void* data) { return static_cast<std::vector<uint8_t>*>(data)->size(); }
    static const char* name(void*) { return "track.xa"; }
    static void close(libstreamfile_t* file) { delete file; }

    static libstreamfile_t* open(void* data, const char* filename) {
        if (std::strcmp(filename, "track.xa") != 0) return nullptr;
        return new (std::nothrow) libstreamfile_t{data, read, size, name, open, close};
    }

    std::vector<uint8_t> bytes;
    struct Deleter { void operator()(libvgmstream_t* state) { libvgmstream_free(state); } };
    std::unique_ptr<libvgmstream_t, Deleter> source;
    int channels = 0;
};

Stream& stream(jlong handle) { return *reinterpret_cast<Stream*>(handle); }

void fail(JNIEnv* env, const char* error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), error ? error : "Decoder failed");
}
}

extern "C" JNIEXPORT jlong JNICALL Java_ru_oopssre_gremlinamp_NativeStream_open(
        JNIEnv* env, jclass, jboolean xa, jbyteArray input, jint subsong) {
    try {
        jsize size = env->GetArrayLength(input);
        if (size <= 0 || subsong < 0) throw std::runtime_error("Invalid stream input");
        std::vector<uint8_t> data(size);
        env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(data.data()));
        if (env->ExceptionCheck()) return 0;
        Stream* result = xa ? static_cast<Stream*>(new Xa(std::move(data), subsong)) : new Sid(data.data(), data.size(), subsong);
        return reinterpret_cast<jlong>(result);
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL Java_ru_oopssre_gremlinamp_NativeStream_render(
        JNIEnv* env, jclass, jlong handle, jshortArray output, jint frames) {
    if (frames < 0 || frames > 2048 || (output && frames > env->GetArrayLength(output) / 2)) {
        fail(env, "Invalid stream output");
        return 0;
    }
    try {
        int16_t buffer[4096];
        int count = frames ? stream(handle).render(buffer, frames) : 0;
        if (output && count) env->SetShortArrayRegion(output, 0, count * 2, buffer);
        return count;
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL Java_ru_oopssre_gremlinamp_NativeStream_restart(
        JNIEnv* env, jclass, jlong handle) {
    try { stream(handle).restart(); }
    catch (const std::exception& e) { fail(env, e.what()); }
}

extern "C" JNIEXPORT jlong JNICALL Java_ru_oopssre_gremlinamp_NativeStream_seek(
        JNIEnv*, jclass, jlong handle, jlong frame) { return stream(handle).seek(frame); }

extern "C" JNIEXPORT jint JNICALL Java_ru_oopssre_gremlinamp_NativeStream_rate(
        JNIEnv*, jclass, jlong handle) { return stream(handle).rate; }

extern "C" JNIEXPORT jlong JNICALL Java_ru_oopssre_gremlinamp_NativeStream_duration(
        JNIEnv*, jclass, jlong handle) { return stream(handle).length; }

extern "C" JNIEXPORT jboolean JNICALL Java_ru_oopssre_gremlinamp_NativeStream_ended(
        JNIEnv*, jclass, jlong handle) { return stream(handle).ended; }

extern "C" JNIEXPORT void JNICALL Java_ru_oopssre_gremlinamp_NativeStream_close(
        JNIEnv*, jclass, jlong handle) { delete &stream(handle); }
