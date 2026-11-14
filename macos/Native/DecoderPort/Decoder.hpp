#pragma once
#include <cstdint>
#include <string>
#include <memory>
struct GDDecoder {
    virtual ~GDDecoder() = default;
    int rate = 44100;
    int64_t length = -1; // sample frames
    int64_t cursor = 0;
    virtual int render(short*, int) = 0;
    virtual bool ended() const = 0;
    virtual bool seek(int64_t) = 0;
};
std::unique_ptr<GDDecoder> gd_open(const char* engine, const char* path, int song, int preroll,
    int region, const char* const* paths, const char* const* names, int count,
    const int32_t* config, int configCount);
