#include "TrackerAudio.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <memory>
#include <mutex>
#include <optional>
#include <vector>

namespace {
constexpr double pi = 3.14159265358979323846;

template <class A, class B> auto minValue(A a, B b) {
    using Result = std::common_type_t<A, B>;
    return std::min<Result>(a, b);
}
template <class A, class B> auto maxValue(A a, B b) {
    using Result = std::common_type_t<A, B>;
    return std::max<Result>(a, b);
}
int floorMod(int n, int d) {
    return (n % d + d) % d;
}
int floorDiv(int n, int d) {
    return (n - floorMod(n, d)) / d;
}
int roundJava(float value) {
    return int(std::floor(value + .5f));
}

class Random {
    uint64_t state;

  public:
    explicit Random(uint64_t seed) : state(seed) {}
    double unit() {
        auto z = (state += 0x9e3779b97f4a7c15ULL);
        z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL;
        z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL;
        return double((z ^ (z >> 31)) >> 11) * 0x1.0p-53;
    }
    int index(int size) { return int(unit() * size); }
};

#include "EarthTracker.inc"
#include "SpaceTracker.inc"
} // namespace

struct GTTracker {
    std::unique_ptr<EarthTracker> earth;
    std::unique_ptr<SpaceTracker> space;
    int mode;
    std::atomic<int> options{11}, minute{0}, bpm{0};
    std::mutex inputs;
    EarthTracker::Weather weather{0, .48f, .15f}, lastWeather = weather;
    GTOrbit orbit{0, .5f, 0, .25f, .2f, .1f, 0, 0};
    std::array<int16_t, 1280> street{};
    int streetCount = 0;
    float streetLevel = 0, streetBrightness = 0;
    bool streetReady = false;
};

GTTracker *gt_create(int mode, uint64_t seed, const uint8_t *profile, int profileCount,
                     const int16_t *recording, int recordingCount) {
    if (mode < GT_TEA || mode > GT_MOSCOW || profileCount < 0 || recordingCount < 0)
        return nullptr;
    if (mode >= GT_VOYAGER && mode <= GT_SOLAR && (!profile || profileCount < 24 || profileCount % 3))
        return nullptr;
    if (mode == GT_VOYAGER && (!recording || recordingCount < 16000))
        return nullptr;
    auto result = std::make_unique<GTTracker>();
    result->mode = mode;
    if (mode <= GT_CITY) {
        result->earth = std::make_unique<EarthTracker>(EarthTracker::Mode(mode), seed);
        result->bpm = result->earth->currentTempo();
    } else {
        std::vector<uint8_t> data;
        std::vector<int16_t> plasma;
        if (profile && profileCount)
            data.assign(profile, profile + profileCount);
        if (recording && recordingCount)
            plasma.assign(recording, recording + recordingCount);
        result->space = std::make_unique<SpaceTracker>(mode, std::move(data), seed, std::move(plasma));
        result->bpm = result->space->currentTempo();
    }
    return result.release();
}

void gt_destroy(GTTracker *tracker) {
    delete tracker;
}
void gt_options(GTTracker *tracker, int clock, int accents, int noise, int plasma) {
    tracker->options.store((clock != 0) | ((accents != 0) << 1) | ((noise != 0) << 2) | ((plasma != 0) << 3),
                           std::memory_order_relaxed);
}
void gt_clock(GTTracker *tracker, int minute) {
    tracker->minute.store(minute, std::memory_order_relaxed);
}
void gt_weather(GTTracker *tracker, float rain, float cloud, float wind) {
    std::lock_guard lock(tracker->inputs);
    tracker->weather = {rain, cloud, wind};
}
void gt_orbit(GTTracker *tracker, GTOrbit orbit) {
    std::lock_guard lock(tracker->inputs);
    tracker->orbit = orbit;
}
void gt_street(GTTracker *tracker, const float *samples, int count) {
    std::lock_guard lock(tracker->inputs);
    tracker->streetCount = std::clamp(count, 0, int(tracker->street.size()));
    float lows = 0, highs = 0, sum = 0, low = 0, middle = 0;
    for (int i = 0; i < tracker->streetCount; ++i) {
        float sample = std::clamp(samples[i], -1.f, 1.f);
        low += (sample - low) * .068f;
        middle += (sample - middle) * .48f;
        lows += low * low;
        highs += (sample - middle) * (sample - middle);
        sum += sample * sample;
        tracker->street[i] = int16_t(sample * 32767);
    }
    tracker->streetLevel = count > 0 ? std::min(1.f, 7 * std::sqrt(sum / tracker->streetCount)) : 0;
    tracker->streetBrightness = sum > 1e-6f ? std::sqrt(highs / (lows + highs + .000001f)) : 0;
    tracker->streetReady = true;
}

int gt_render(GTTracker *tracker, float *left, float *right, int frames) {
    if (!tracker || !left || !right || frames < 0)
        return 0;
    const auto flags = tracker->options.load(std::memory_order_relaxed);
    {
        std::unique_lock lock(tracker->inputs, std::try_to_lock);
        if (lock.owns_lock()) {
            if (tracker->mode == GT_WEATHER)
                tracker->lastWeather = tracker->weather;
            if (tracker->mode == GT_ORBITS)
                tracker->space->setOrbit(tracker->orbit);
            if (tracker->mode == GT_MOSCOW && tracker->streetReady) {
                tracker->space->input(tracker->street.data(), tracker->streetCount, tracker->streetLevel,
                                      tracker->streetBrightness);
                tracker->streetReady = false;
            }
        }
    }
    if (tracker->earth) {
        int minute = flags & 1 ? tracker->minute.load(std::memory_order_relaxed) : 0;
        tracker->earth->render(left, right, frames, minute, tracker->lastWeather, flags & 2);
        tracker->bpm.store(tracker->earth->currentTempo(), std::memory_order_relaxed);
    } else {
        tracker->space->render(left, right, frames, flags & 4, flags & 8);
        tracker->bpm.store(tracker->space->currentTempo(), std::memory_order_relaxed);
    }
    return frames;
}

int gt_bpm(const GTTracker *tracker) {
    return tracker->bpm.load(std::memory_order_relaxed);
}
