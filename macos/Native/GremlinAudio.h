#ifndef GREMLIN_AUDIO_H
#define GREMLIN_AUDIO_H
#include <stdint.h>
#include "TrackerAudio.h"
#ifdef __cplusplus
extern "C" {
#endif
typedef struct GAStream GAStream;
GAStream *ga_open(const char *path, int subsong, int64_t play_ms, int64_t fade_ms, char *error, int error_size);
GAStream *ga_open_decoder(const char *engine, const char *path, int subsong, int64_t play_ms, int64_t fade_ms,
    int preroll, int region, const char *const *paths, const char *const *names, int count,
    const int32_t *config, int config_count, char *error, int error_size);
unsigned char *ga_unpack_file(const char *path, int *length);
uint32_t ga_crc32(const unsigned char *bytes, int length);
void ga_close(GAStream *stream);
int ga_rate(GAStream *stream);
int64_t ga_duration(GAStream *stream);
int64_t ga_position(GAStream *stream);
int ga_ended(GAStream *stream);
int ga_failed(GAStream *stream);
int ga_seek(GAStream *stream, int64_t ms);
void ga_render(GAStream *stream, float *stereo, int frames);
// -1: invalid buffer/decode failure, 0: silence, 1: audio samples present.
int ga_render_planar(GAStream *stream, float *left, int left_capacity, float *right, int right_capacity, int frames);
int ga_track_count(const char *path);
int ga_measure(const char *path, int subsong, int limit_ms, int64_t *sound_ms, int64_t *ended_ms);
#ifdef __cplusplus
}
#endif
#endif
