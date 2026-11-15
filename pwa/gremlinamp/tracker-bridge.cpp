#include "TrackerAudio.h"

extern "C" {

GTTracker *gwt_create(int mode, uint32_t seed, const uint8_t *profile, int profileCount,
                      const int16_t *recording, int recordingCount) {
    return gt_create(mode, seed, profile, profileCount, recording, recordingCount);
}

void gwt_orbit(GTTracker *tracker, float pan, float distance, float elevation, float speed,
               float density, float xray, double peak, float flareLevel) {
    gt_orbit(tracker, GTOrbit{pan, distance, elevation, speed, density, xray,
                             static_cast<int64_t>(peak), flareLevel});
}

}
