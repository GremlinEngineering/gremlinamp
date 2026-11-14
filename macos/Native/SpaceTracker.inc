// PCM tracker patterns ported from the Android player.
class SpaceTracker {
  public:
    static constexpr int RATE = 44100;
    enum { VOYAGER = GT_VOYAGER, STARS = GT_STARS, SOLAR = GT_SOLAR, CITY = GT_MOSCOW, ORBITS = GT_ORBITS };

  private:
    inline static constexpr std::array MINOR = {0, 2, 3, 5, 7, 8, 10};
    inline static constexpr std::array ROOTS = {0, 5, 2, 6};
    static constexpr int ROWS_PER_BAR = 16;
    struct Mode {
        int type, key, restTempo, arrangement;
        bool operator==(int expected) const { return type == expected; }
        bool operator!=(int expected) const { return type != expected; }
    };

    class PlasmaLayer {
        static constexpr int SOURCE_RATE = 16000;
        static constexpr int FADE_FRAMES = RATE / 4;
        static constexpr int SILENCE_FRAMES = 13 * RATE;
        std::vector<int16_t> samples;
        int clipFrames;
        int frame = -3 * RATE;
        float gain = 0;

      public:
        explicit PlasmaLayer(std::vector<int16_t> samples);
        float next(bool enabled);
    };

    class Voice {
        float phase = 0, volume = 0, peak = 0, attack = 0, decay = 0, increment = 0, filtered = 0;
        bool rising = false;
        float slideRatio = 1, bendOffset = 0;
        float arpFirst = 1, arpSecond = 1;
        int arpFrame = 0;

      public:
        void slide(int semitones);
        void arpeggio(int first, int second);
        void trigger(int midi, float level, float attackSeconds, float releaseSeconds);
        float wave(int shape, float duty, float bend);
    };

    Mode mode;
    std::optional<PlasmaLayer> plasma;
    std::vector<uint8_t> source;
    Random variation;
    Voice bass, lowPad, highPad, lead, response, spark;
    std::array<float, 18947> delayLeft{};
    std::array<float, 24311> delayRight{};
    std::array<int, 6> motif{};
    int stride, cityShift;
    size_t leftIndex = 0, rightIndex = 0;
    int frameIndex = 0;
    int64_t row = 0, phrase = 0;
    int motifOrigin = 0, root = 0;
    uint32_t noiseState = 0x52b6d1;
    double rowPhase = 1;
    float bpm, effect = 0, brightness = 0, energy = 0, noiseBed = 0, percussion = 0, dust = 0, drumPhase = 0;
    float leftFilter = 0, rightFilter = 0, streetLevel = 0, streetBrightness = 0;
    GTOrbit orbit{0, .5f, 0, .25f, .2f, .1f, 0, 0};
    int64_t playedFlare = 0;
    int windRows = 0;
    float orbitPan = 0, orbitBend = 1, cityGate = 0, cityAir = 0, citySample = 0;
    std::array<int16_t, 1280> streetSamples{};
    int streetCount = 0;
    double streetFrame = 0;

    static Mode parameters(int type) {
        switch (type) {
        case VOYAGER:
            return {type, 45, 68, 0};
        case STARS:
            return {type, 50, 63, 1};
        case SOLAR:
            return {type, 43, 74, 2};
        case CITY:
            return {type, 48, 72, 3};
        default:
            return {ORBITS, 41, 48, 4};
        }
    }

  public:
    SpaceTracker(int type, std::vector<uint8_t> profile, uint64_t seed, std::vector<int16_t> recording)
        : mode(parameters(type)), source(std::move(profile)), variation(seed), stride(1 + mode.arrangement),
          cityShift(int(seed % 12)), bpm(mode.restTempo) {
        if (type == VOYAGER)
            plasma.emplace(std::move(recording));
        uint64_t fingerprint = 0xcbf29ce484222325ULL;
        for (uint8_t value : source)
            fingerprint = (fingerprint ^ value) * 0x100000001b3ULL;
        variation = Random(fingerprint ^ seed);
        motifOrigin = int(fingerprint % frameCount());
        updateMotif();
    }

    void input(const int16_t *samples, int count, float level, float brightness) {
        streetCount = std::clamp(count, 0, int(streetSamples.size()));
        if (streetCount > 0)
            std::copy_n(samples, streetCount, streetSamples.begin());
        streetFrame = 0;
        targetStreetLevel = level;
        targetStreetBrightness = brightness;
    }

    void setOrbit(GTOrbit value) { orbit = value; }

  private:
    float targetStreetLevel = 0, targetStreetBrightness = 0;

    int frameCount() { return maxValue(1, int(source.size() / 3)); }

    float feature(int offset, int band) {
        if (mode == ORBITS)
            return band == 1 ? orbit.density : orbit.xray;
        if (mode == CITY)
            switch (band) {
            case 0:
                return (.12f + .28f * streetBrightness +
                        .15f * floorMod(offset + cityShift + (int)(phrase % 4), 4));
            case 1:
                return .36f + .52f * streetLevel;
            default:
                return .35f + .64f * streetBrightness;
            }
        if (source.empty())
            return 0;
        return (source[floorMod(offset, frameCount()) * 3 + band] & 255) / 255.f;
    }

    void updateMotif() {
        int first = roundJava(feature(motifOrigin, 0) * 5);
        for (int i = 0; i < int(motif.size()); i++) {
            int point = motifOrigin + i * (2 + mode.arrangement);
            int degree = roundJava(feature(point, 0) * 7) - first;
            motif[i] = maxValue(-2, minValue(6, degree + (i == 0 ? 0 : i / 2)));
        }
    }

    int note(int tonic, int degree) { return tonic + 12 * floorDiv(degree, 7) + MINOR[floorMod(degree, 7)]; }

    void step() {
        int beat = (int)(row % ROWS_PER_BAR);
        int bar = (int)((row / ROWS_PER_BAR) % 8);
        if (beat == 0 && bar == 0 && row != 0) {
            phrase++;
            motifOrigin = floorMod(motifOrigin + 5 + variation.index(15), frameCount());
            updateMotif();
        }
        int sample = motifOrigin + frameIndex;
        energy = feature(sample, 1);
        brightness = feature(sample, 2);
        switch (mode.type) {
        case VOYAGER:
            telemetry(sample);
            break;
        case STARS:
            starfield(sample);
            break;
        case SOLAR:
            planets(beat, bar);
            break;
        case CITY:
            city(beat, bar);
            break;
        case ORBITS:
            orbits();
            break;
        }
        frameIndex = (frameIndex + stride) % frameCount();
        row++;
    }

    void telemetry(int sample) {
        if (row % 53 == 0) {
            bass.trigger(mode.key - 17, .31f, 2.3f, 19);
            lowPad.trigger(mode.key - 5, .093f, 3.4f, 22);
        }
        if (row % 37 == 9)
            highPad.trigger(mode.key + 2, .059f, 2.7f, 16);
        int burst = (int)(row % 31);
        if (burst == 4 || burst == 5) {
            int band = roundJava(feature(sample, 0) * 11);
            lead.trigger(mode.key + 28 + band, burst == 5 ? .087f : .15f, .008f, .18f);
            lead.slide(burst == 5 ? -4 : 3);
            if (burst == 4)
                dust = .24f;
        }
        if (row % 43 == 18) {
            response.trigger(mode.key + 19 + roundJava(brightness * 7), .094f, .04f, .51f);
            response.slide(-5);
        }
    }

    void starfield(int sample) {
        if (row % 79 == 0)
            bass.trigger(mode.key - 14, .17f, 4.5f, 27);
        if (row % 61 == 0)
            lowPad.trigger(mode.key - 2, .21f, 3.9f, 24);
        if (row % 47 == 12)
            highPad.trigger(mode.key + 5, .14f, 3.5f, 19);
        if (row % 23 == 3 && energy > .18f) {
            int degree = roundJava(feature(sample, 0) * 4);
            response.trigger(note(mode.key + 19, degree), .10f + .035f * energy, .35f, 2.8f);
        }
        if (row % 37 == 14)
            spark.trigger(mode.key + 31, .052f, .13f, 2.9f);
    }

    void planets(int beat, int bar) {
        if (beat == 0) {
            root = ROOTS[(bar / 2 + (int)(phrase % 4)) % ROOTS.size()];
            lowPad.trigger(note(mode.key, root + 7), .084f, .17f, 2.4f);
        }
        if (beat == 0 || beat == 6 || beat == 8 || beat == 13) {
            bass.trigger(note(mode.key - 8, root + (beat == 13 ? 7 : 0)), .28f, .006f, .49f);
            percussion = beat == 6 ? .24f : .52f;
        }
        if (beat == 4 || beat == 12)
            dust = .63f;
        else if (beat % 2 == 1 && brightness > .5f)
            dust = .17f;
        if (beat == 2 || beat == 7 || beat == 10 || beat == 14) {
            int degree = motif[(beat / 3 + bar / 2) % motif.size()];
            lead.trigger(note(mode.key + 11, root + degree), .16f + energy * .075f, .005f, .35f);
            lead.arpeggio((bar & 1) == 0 ? 3 : 5, 7);
        }
        if (beat == 15 && (bar & 1) == 1) {
            response.trigger(note(mode.key + 12, root + 4), .10f, .01f, .35f);
            response.slide(-7);
        }
    }

    void city(int beat, int bar) {
        float activity = minValue(1, streetLevel * .45f);
        if (beat == 0) {
            updateMotif();
            root = ROOTS[(bar / 2 + cityShift / 3 + (int)(phrase % 4) + (streetLevel > .4f ? 1 : 0)) %
                         ROOTS.size()];
            lowPad.trigger(note(mode.key - 5, root), .16f - .05f * activity, .7f, 5.2f);
        }
        if (beat == 0 || beat == 8 && activity > .17f || (beat == 5 || beat == 11) && activity > .38f) {
            bass.trigger(note(mode.key - 13, root + (beat == 5 ? 7 : 0)), .16f + .16f * activity, .009f,
                         .48f);
            percussion = activity * (.45f + streetLevel * .28f);
        }
        if ((beat == 4 || beat == 12) && activity > .12f)
            dust = .5f * activity + streetBrightness * .24f;
        if (beat == 3 && (activity > .17f || (bar & 1) == 0) || beat == 10 && streetBrightness > .45f ||
            beat == 14 && activity > .42f) {
            lead.trigger(note(mode.key + 6, root + motif[beat % motif.size()]), .13f, .009f, .28f);
            if (beat == 14)
                lead.slide(-3);
        }
        if (beat == 9 && streetLevel > .30f)
            spark.trigger(mode.key + 17 + roundJava(streetBrightness * 5), .095f, .004f, .14f);
    }

    void orbits() {
        if (row % 28 == 0)
            lowPad.trigger(mode.key - 8, .19f, 2.4f, 24);
        if (row % 41 == 7)
            highPad.trigger(mode.key + 4, .11f, 2.1f, 17);
        if (--windRows <= 0) {
            windRows = 7 + roundJava(19 * (1 - orbit.speed));
            bass.trigger(mode.key - 17, .10f + .11f * orbit.density, .14f, 2.0f);
            dust = .08f + .36f * orbit.density;
        }
        if (row % 19 == 6) {
            response.trigger(mode.key + 12 + roundJava(orbit.xray * 7), .035f + .085f * orbit.xray, .19f,
                             2.9f);
        }
        if (orbit.flare > playedFlare) {
            playedFlare = orbit.flare;
            spark.trigger(mode.key + 27, .12f + .12f * orbit.flareLevel, .014f, 1.4f);
            spark.slide(7);
        }
    }

  public:
    void render(float *leftOutput, float *rightOutput, int frames, bool cosmicEffects, bool plasmaEnabled) {
        bool orbits = mode == ORBITS, city = mode == CITY;
        const float targetEffect = cosmicEffects ? 1 : 0;
        for (int frame = 0; frame < frames; frame++) {
            streetLevel += (targetStreetLevel - streetLevel) * .00004f;
            streetBrightness += (targetStreetBrightness - streetBrightness) * .00004f;
            bpm += (mode.restTempo - bpm) * .000022f;
            effect += (targetEffect - effect) * .00030f;
            if (rowPhase >= 1) {
                rowPhase -= 1;
                step();
            }
            rowPhase += bpm / (15.0 * RATE);
            float noise = this->noise();
            float drift = 1 + effect * (noiseBed * .019f);
            float low = bass.wave(0, .5f, 1);
            if (orbits) {
                orbitPan += (orbit.pan - orbitPan) * .000035f;
                orbitBend += (.981f + .036f * orbit.distance + .11f * orbit.elevation - orbitBend) * .000035f;
            }
            float pad = lowPad.wave(0, .5f, orbitBend) + highPad.wave(0, .5f, orbitBend * 1.0015f);
            float melody = lead.wave(1, .26f + .13f * brightness, drift);
            float answer = response.wave(0, .5f, drift) + spark.wave(1, .25f, 1);
            drumPhase += (.0014f + percussion * .0022f);
            drumPhase -= (int)drumPhase;
            percussion *= .99944f;
            dust *= .9988f;
            float rhythm = (1 - 4 * std::abs(drumPhase - .5f)) * percussion * .19f + noise * dust * .13f;
            float left = low + rhythm + pad * (.96f - orbitPan * .37f) + melody * .91f + answer * .48f;
            float right = low + rhythm + pad * (1.04f + orbitPan * .37f) + melody * .91f + answer * .86f;
            if (city) {
                float texture = cityTexture();
                left += texture * .8f;
                right += texture * .8f;
                melody += texture * .7f; // The gated street texture shares the tracker echo.
            }
            float echoLeft = delayLeft[leftIndex], echoRight = delayRight[rightIndex];
            delayLeft[leftIndex] = melody * .52f + pad * .11f + echoRight * (mode == VOYAGER ? .58f : .40f);
            delayRight[rightIndex] =
                answer * .57f + melody * .31f + echoLeft * (mode == VOYAGER ? .62f : .42f);
            if (++leftIndex == delayLeft.size())
                leftIndex = 0;
            if (++rightIndex == delayRight.size())
                rightIndex = 0;
            noiseBed += (noise - noiseBed) * .028f;
            float cosmic = effect * (noiseBed * (.055f + .073f * energy) + noise * .008f * brightness);
            left += echoLeft * .72f + cosmic;
            right += echoRight * .72f - cosmic * .57f;
            left += effect * (roundJava(left * 45) / 45.f - left) * .7f;
            right += effect * (roundJava(right * 45) / 45.f - right) * .7f;
            leftFilter += (left - leftFilter) * .28f;
            rightFilter += (right - rightFilter) * .28f;
            // The archival signal is mixed after the synthetic effects.
            float recorded = plasma ? plasma->next(plasmaEnabled) : 0;
            leftOutput[frame] = toSample(leftFilter + recorded * .88f);
            rightOutput[frame] = toSample(rightFilter + recorded);
        }
    }

    int currentTempo() const { return int(std::lround(bpm)); }

  private:
    float cityTexture() {
        int sample = (int)streetFrame;
        float input = sample < streetCount ? streetSamples[sample] / 32768.f : 0;
        streetFrame += 16000.0 / RATE;
        citySample += (input - citySample) * .11f;
        cityAir += (citySample - cityAir) * (.009f + .043f * streetBrightness);
        bool open = row % 4 == 1;
        cityGate += ((open ? minValue(1, .18f + streetLevel) : 0) - cityGate) * .0009f;
        return maxValue(-.18f, minValue(.18f, (citySample - cityAir * .91f) * 1.9f)) * cityGate;
    }

    static float toSample(float sample) { return std::clamp(sample, -1.f, 1.f) * (30400.f / 32768.f); }

    float noise() {
        noiseState ^= noiseState << 13;
        noiseState ^= noiseState >> 17;
        noiseState ^= noiseState << 5;
        return (noiseState >> 8) * (2.f / 16777216) - 1;
    }
};

SpaceTracker::PlasmaLayer::PlasmaLayer(std::vector<int16_t> input)
    : samples(std::move(input)), clipFrames(int((samples.size() - 1) * RATE / SOURCE_RATE)) {
}

float SpaceTracker::PlasmaLayer::next(bool enabled) {
    gain += ((enabled ? .34f : 0.f) - gain) * .0004f;
    int offset = frame++;
    if (frame == clipFrames)
        frame = -SILENCE_FRAMES;
    if (offset < 0)
        return 0;
    double sourceFrame = offset * (SOURCE_RATE / (double)RATE);
    int index = (int)sourceFrame;
    float fraction = (float)(sourceFrame - index);
    float sample = samples[index] + (samples[index + 1] - samples[index]) * fraction;
    float edge = minValue(1.f, minValue(offset, clipFrames - 1 - offset) / (float)FADE_FRAMES);
    return sample * (gain / 32768.f) * edge;
}
void SpaceTracker::Voice::slide(int semitones) {
    slideRatio = (float)std::pow(2, semitones / 12.0);
}
void SpaceTracker::Voice::arpeggio(int first, int second) {
    arpFirst = (float)std::pow(2, first / 12.0);
    arpSecond = (float)std::pow(2, second / 12.0);
}

void SpaceTracker::Voice::trigger(int midi, float level, float attackSeconds, float releaseSeconds) {
    increment = (float)(440 * std::pow(2, (midi - 69) / 12.0) / RATE);
    slideRatio = 1;
    bendOffset = 1;
    arpFirst = arpSecond = 1;
    arpFrame = 0;
    peak = level;
    attack = 1 - (float)std::exp(-4 / (RATE * attackSeconds));
    decay = (float)std::exp(-3.2 / (RATE * releaseSeconds));
    rising = true;
}

float SpaceTracker::Voice::wave(int shape, float duty, float bend) {
    if (rising) {
        volume += (peak - volume) * attack;
        if (std::abs(peak - volume) < .002f)
            rising = false;
    } else {
        volume *= decay;
        if (volume < .00005f)
            return 0;
    }
    bendOffset += (slideRatio - bendOffset) * .00021f;
    int tick = arpFrame++ / 735;
    if (arpFrame == 2205)
        arpFrame = 0;
    float arpeggio = tick == 1 ? arpFirst : tick == 2 ? arpSecond : 1;
    phase += increment * bend * bendOffset * arpeggio;
    if (phase >= 1)
        phase -= 1;
    float value = shape == 0 ? 1 - 4 * std::abs(phase - .5f) : (phase < duty ? 1 - duty : -duty) * 1.45f;
    filtered += (value - filtered) * (shape == 0 ? .34f : .22f);
    return filtered * volume;
}
