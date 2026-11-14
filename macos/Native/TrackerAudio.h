#ifndef GREMLIN_TRACKER_AUDIO_H
#define GREMLIN_TRACKER_AUDIO_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum { GT_TEA, GT_WORKSHOP, GT_WEATHER, GT_CITY, GT_VOYAGER, GT_STARS, GT_SOLAR, GT_ORBITS, GT_MOSCOW };

typedef struct GTTracker GTTracker;
typedef struct {
    float pan, distance, elevation, speed, density, xray;
    int64_t flare;
    float flareLevel;
} GTOrbit;

GTTracker *gt_create(int mode, uint64_t seed, const uint8_t *profile, int profileCount,
                     const int16_t *recording, int recordingCount);
void gt_destroy(GTTracker *tracker);
void gt_options(GTTracker *tracker, int clock, int accents, int noise, int plasma);
void gt_clock(GTTracker *tracker, int minute);
void gt_weather(GTTracker *tracker, float rain, float cloud, float wind);
void gt_orbit(GTTracker *tracker, GTOrbit orbit);
void gt_street(GTTracker *tracker, const float *samples, int count);
int gt_render(GTTracker *tracker, float *left, float *right, int frames);
int gt_bpm(const GTTracker *tracker);

#ifdef __cplusplus
}
#endif
#endif
