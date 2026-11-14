// YM2413 adapter for Game Music Emu. Local GremlinAmp implementation, MIT.
// Uses bundled emu2413 (Mitsutaka Okazaki and contributors, MIT; ext/LICENSE).
#include "Ym2413_Emu.h"
#include "ext/emu2413.h"
#include <algorithm>
#include <cmath>
#include <cstdint>

Ym2413_Emu::Ym2413_Emu() : opll(nullptr) { }
Ym2413_Emu::~Ym2413_Emu() { if (opll) OPLL_delete(opll); }
int Ym2413_Emu::set_rate(double sample_rate, double clock_rate) {
    if (!std::isfinite(sample_rate) || !std::isfinite(clock_rate) ||
        sample_rate < 1 || clock_rate < 1 || sample_rate > UINT32_MAX || clock_rate > UINT32_MAX)
        return 1;
    OPLL* next = OPLL_new(static_cast<uint32_t>(clock_rate), static_cast<uint32_t>(sample_rate));
    if (!next) return 1;
    OPLL_resetPatch(next, 0); // YM2413 instrument ROM, not the VRC7 patch set.
    if (opll) OPLL_delete(opll);
    opll = next;
    return 0;
}
void Ym2413_Emu::reset() { if (opll) OPLL_reset(opll); }
void Ym2413_Emu::write(int addr, int data) { if (opll) OPLL_writeReg(opll, addr & 63, data & 255); }
void Ym2413_Emu::mute_voices(int mask) {
    if (!opll) return;
    OPLL_setMask(opll, static_cast<uint32_t>(mask));
    // This emu2413 version skips updates for muted slots; clear held output.
    for (int i = 0; i < 14; ++i) opll->ch_out[i] = 0;
}
void Ym2413_Emu::run(int pair_count, sample_t* out) {
    if (!opll || !out) return;
    for (int i = 0; i < pair_count; ++i) {
        int32_t sample[2];
        OPLL_calcStereo(opll, sample);
        // GME shares this buffer between chips; mix rather than overwrite.
        for (int channel = 0; channel < 2; ++channel) {
            int mixed = static_cast<int>(*out) + sample[channel];
            *out++ = static_cast<sample_t>(std::max(-32768, std::min(32767, mixed)));
        }
    }
}
