/** Four procedural scores on a common 16-row tracker grid. All instruments are synthesized. */
class EarthTracker {
  public:
    static constexpr int RATE = 44100;
    enum Mode { TEA, WORKSHOP, WEATHER, CITY };

    struct Weather {
        float rain, clouds, wind;
    };

  private:
    enum class Tone { SINE, TRIANGLE, PLUCK, ORGAN, PULSE, GLASS, KICK, TAP, BRUSH, STEAM };
    inline static constexpr std::array DORIAN = {0, 2, 3, 5, 7, 9, 10};
    inline static constexpr std::array MAJOR = {0, 2, 4, 5, 7, 9, 11};
    inline static constexpr std::array TEA_ROOTS = {0, 0, 3, 3, 5, 5, 1, 1};
    inline static constexpr std::array WEATHER_ROOTS = {0, 3, 5, 1};
    inline static constexpr std::array CITY_ROOTS = {0, 0, 5, 3, 0, 2, 5, 3};
    inline static constexpr std::array WORK_ACCENTS = {0, 3, 6, 8, 11, 14};
    inline static const auto SINE = [] {
        std::array<float, 2048> table{};
        for (size_t i = 0; i < table.size(); i++)
            table[i] = std::sin(2 * pi * i / table.size());
        return table;
    }();

    class Voice {
        Tone tone = Tone::SINE;
        double phase = 0, rate = 0;
        int age = 0, frames = 0, rise = 0, tail = 0;
        float amp = 0, last = 0, old = 0, low = 0;

      public:
        float left = 0, right = 0;
        void trigger(Tone tone, int midi, float level, float attack, float duration, float pan);
        float sample(float noise);
    };

    Mode mode;
    Random arrangement;
    std::array<Voice, 10> voices{};
    std::array<float, 17627> echoL{};
    std::array<float, 23319> echoR{};
    size_t leftIndex = 0, rightIndex = 0;
    uint32_t noise = 0x1dcb38a7;
    double ticks = 1;
    int64_t row = 0;
    float bpm, air = 0, airLow = 0, wet = 0, cloud = 0, wind = 0, gain = 0;
    std::array<int, 5> motif{};
    int root = 0;
    float cityActivity = 0;

  public:
    EarthTracker(Mode mode, uint64_t seed) : mode(mode), arrangement(seed) {
        bpm = tempo(0, 0);
        varyMotif();
    }

    void render(float *left, float *right, int frames, int localMinute, Weather weather,
                bool shiftingAccents) {
        float activity = commute(localMinute);
        float nextTempo = tempo(activity, weather.rain);
        for (int i = 0; i < frames; i++) {
            bpm += (nextTempo - bpm) * .000027f;
            wet += (weather.rain - wet) * .000047f;
            cloud += (weather.clouds - cloud) * .000047f;
            wind += (weather.wind - wind) * .000032f;
            gain += (1 - gain) * .00022f;
            if (ticks >= 1) {
                ticks -= 1;
                step(localMinute, activity, shiftingAccents);
            }
            ticks += bpm / (15 * RATE);
            float grain = this->grain();
            air += (grain - air) * .085f;
            airLow += (air - airLow) * .009f;
            float bed = 0;
            switch (mode) {
            case TEA:
                bed = (air - airLow) * .033f;
                break;
            case WORKSHOP:
                bed = airLow * .038f;
                break;
            case WEATHER:
                bed = air * (.035f + .11f * wet) + airLow * (.05f + .18f * wind);
                break;
            case CITY:
                bed = airLow * (.07f + cityActivity * .20f);
                break;
            }
            float l = bed, r = bed;
            for (Voice &voice : voices) {
                float sample = voice.sample(grain);
                l += sample * voice.left;
                r += sample * voice.right;
            }
            float delayedL = echoL[leftIndex], delayedR = echoR[rightIndex];
            float send = mode == Mode::WORKSHOP ? .15f : mode == Mode::CITY ? .18f : .29f;
            echoL[leftIndex] = l * send + delayedR * .29f;
            echoR[rightIndex] = r * send + delayedL * .32f;
            if (++leftIndex == echoL.size())
                leftIndex = 0;
            if (++rightIndex == echoR.size())
                rightIndex = 0;
            left[i] = sample(l + delayedL);
            right[i] = sample(r + delayedR);
        }
    }

    int currentTempo() const { return int(std::lround(bpm)); }

  private:
    float sample(float sample) {
        float value = sample * gain * 1.55f;
        return value / (1 + std::abs(value) * .6f);
    }

    float tempo(float activity, float rain) {
        switch (mode) {
        case TEA:
            return 54;
        case WORKSHOP:
            return 104;
        case WEATHER:
            return 62 + 16 * rain;
        case CITY:
            return 63 + 49 * activity;
        }
        return 60;
    }

    static float commute(int minute) {
        if (minute < 0 || minute >= 24 * 60)
            return .4f;
        float morning = maxValue(0, 1 - std::abs(minute - 8 * 60.f) / 150);
        float evening = maxValue(0, 1 - std::abs(minute - 18 * 60.f) / 150);
        return minValue(1, .13f + (minute >= 420 && minute < 1320 ? .22f : 0) +
                               .65f * maxValue(morning, evening));
    }

    void step(int minute, float activity, bool shiftingAccents) {
        int beat = (int)(row & 15), bar = (int)(row / 16 % 8);
        int64_t phrase = row / 128;
        if (beat == 0 && bar == 0 && phrase > 0)
            varyMotif();
        switch (mode) {
        case TEA:
            tea(beat, bar, minute, phrase);
            break;
        case WORKSHOP:
            workshop(beat, bar, phrase, shiftingAccents);
            break;
        case WEATHER:
            weather(beat, bar, phrase);
            break;
        case CITY:
            city(beat, bar, activity, phrase);
            break;
        }
        row++;
    }

    void varyMotif() {
        motif[0] = 0;
        motif[1] = 2 + arrangement.index(2);
        motif[2] = 4 + arrangement.index(2);
        motif[3] = arrangement.index(2) != 0 ? 1 : 6;
        motif[4] = arrangement.index(2) != 0 ? -1 : 2;
    }

    void tea(int beat, int bar, int minute, int64_t phrase) {
        bool evening = minute < 360 || minute >= 1140;
        int key = evening ? 53 : 55;
        if (beat == 0 && (bar & 1) == 0) {
            root = TEA_ROOTS[(bar + (int)(phrase & 3) * 2) & 7];
            int pad = (bar & 2) == 0 ? 0 : 3;
            hit(pad, Tone::TRIANGLE, note(key - 12, root, MAJOR), .23f, .72f, 5.4f, -.18f);
            hit(pad + 1, Tone::SINE, note(key, root + 2, MAJOR), .125f, 1.1f, 5.9f, .31f);
            hit(pad + 2, Tone::TRIANGLE, note(key, root + 6, MAJOR), .075f, 1.4f, 5.5f, -.39f);
        }
        if ((bar & 1) == 0 && (beat == 3 || beat == 11) || (bar & 1) == 1 && beat == 7) {
            int degree = motif[(bar + beat / 3) % motif.size()];
            hit(6 + ((bar + beat) & 1), Tone::PLUCK, note(key + 12, root + degree, MAJOR), .17f, .016f, 1.45f,
                beat < 8 ? .3f : -.31f);
        }
        if (beat == 8 && (bar & 1) == 1)
            hit(8, Tone::STEAM, 71, .095f, .38f, 1.6f, -.22f);
        if (beat == 14 && arrangement.index(3) != 0)
            hit(9, Tone::GLASS, note(key + 24, root + 4, MAJOR), .060f, .004f, .27f, .53f);
    }

    void workshop(int beat, int bar, int64_t phrase, bool shiftingAccents) {
        int key = 40 + (int)(phrase % 3);
        int accent = shiftingAccents ? WORK_ACCENTS[(bar + (int)(phrase & 3)) % WORK_ACCENTS.size()] : 6;
        if (beat == 0 || beat == 8 || beat == accent)
            hit(0, Tone::KICK, beat == 8 ? 36 : 33, beat == accent ? .34f : .47f, .004f, .27f, 0);
        if (beat == 3 || beat == 6 || beat == 11 || beat == 14)
            hit(1, Tone::TAP, 70 + (beat & 3) * 3, beat == 6 || beat == 14 ? .23f : .14f, .003f, .11f,
                beat < 8 ? -.53f : .57f);
        if (beat == 2 || beat == 7 || beat == 10 || beat == 13 || beat == 15 && (bar & 1) == 1)
            hit(2, Tone::BRUSH, 88, .115f, .003f, .09f, beat < 8 ? .28f : -.3f);
        if (beat == 0 || beat == 5 || beat == 12)
            hit(3, Tone::PULSE,
                note(key,
                     beat == 5    ? 3
                     : beat == 12 ? 4
                                  : 0,
                     DORIAN),
                .20f, .009f, .31f, -.09f);
        if (beat == 10 && (bar & 1) == 0 || beat == 15 && bar % 4 == 3)
            hit(4, Tone::GLASS, note(key + 24, motif[bar % motif.size()], DORIAN), .11f, .007f, .48f, .40f);
        if (beat == 0 && bar % 4 == 0)
            hit(5, Tone::ORGAN, key - 12, .095f, .62f, 5.5f, 0);
    }

    void weather(int beat, int bar, int64_t phrase) {
        int key = 50;
        const auto &scale = cloud > .56f ? DORIAN : MAJOR;
        if (beat == 0) {
            root = WEATHER_ROOTS[(bar + (int)(phrase & 3)) & 3];
            int pad = (bar & 1) * 3;
            hit(pad, Tone::TRIANGLE, note(key - 12, root, scale), .23f, .64f, 4.3f, -.20f);
            hit(pad + 1, Tone::SINE, note(key, root + (cloud > .8f ? 1 : 2), scale), .12f, .95f, 3.8f, .4f);
            hit(pad + 2, Tone::TRIANGLE, note(key, root + 4, scale), .105f, 1.4f, 3.2f, -.33f);
        }
        if (beat == 5 || beat == 12 && cloud < .62f)
            hit(6, Tone::GLASS, note(key + 19, root + motif[bar % motif.size()], scale), .13f - .04f * wet,
                .028f, 1.75f, -.4f + .8f * wind);
        if (wet > .18f && ((float)arrangement.unit()) < .09f + wet * .69f)
            hit(7 + (beat & 1), Tone::TAP, note(key + 28, motif[beat % motif.size()], scale),
                .055f + .12f * wet, .003f, .16f, (((float)arrangement.unit()) - .5f) * 1.6f);
        if (beat == 0 && wind > .27f)
            hit(9, Tone::STEAM, 64, .075f + wind * .16f, .58f, 2.0f, (bar & 1) == 0 ? -.43f : .43f);
    }

    void city(int beat, int bar, float activity, int64_t phrase) {
        cityActivity = activity;
        int key = activity > .65f ? 46 : 43;
        if (beat == 0) {
            root = CITY_ROOTS[(bar + (int)(phrase & 3) * 2) & 7];
            int pad = (bar & 1) * 2;
            hit(pad, Tone::ORGAN, note(key, root + 7, DORIAN), .11f, .17f, 2.0f, -.42f);
            hit(pad + 1, Tone::TRIANGLE, note(key + 12, root + 2, DORIAN), .11f, .27f, 2.5f, .45f);
        }
        if (beat == 0 || beat == 10 || beat == 8 && activity > .48f || beat == 6 && activity > .72f) {
            hit(4, Tone::KICK, 35, .22f + .23f * activity, .004f, .31f, 0);
            hit(5, Tone::TRIANGLE, note(key - 8, root + (beat == 10 ? 4 : 0), DORIAN), .22f, .022f, .55f,
                -.08f);
        }
        if (beat == 4 || beat == 12)
            hit(6, Tone::BRUSH, 69, .17f + .09f * activity, .006f, .15f, .13f);
        if (beat == 2 || beat == 9 || beat == 14 || activity > .65f && beat % 2 == 1)
            hit(7, Tone::BRUSH, 89, .075f + activity * .045f, .003f, .095f, (beat & 2) == 0 ? .51f : -.39f);
        if (beat == 3 || beat == 11 && (bar & 1) == 0 || beat == 15 && activity > .45f)
            hit(8, Tone::PULSE, note(key + 19, root + motif[(bar + beat / 3) % motif.size()], DORIAN),
                .11f + .08f * activity, .012f, .47f, -.28f);
        if (beat == 7 && bar % 4 == 2)
            hit(9, Tone::GLASS, note(key + 24, root + 4, DORIAN), .095f, .02f, .61f, .5f);
    }

    void hit(int channel, Tone tone, int note, float level, float attack, float length, float pan) {
        voices[channel].trigger(tone, note, level, attack, length, pan);
    }

    static int note(int key, int degree, const std::array<int, 7> &scale) {
        return key + 12 * floorDiv(degree, 7) + scale[floorMod(degree, 7)];
    }

    float grain() {
        noise ^= noise << 13;
        noise ^= noise >> 17;
        noise ^= noise << 5;
        return (int32_t(noise) >> 8) * (1.f / 8388608);
    }

    static float sine(double phase) {
        double p = (phase - (long)phase) * SINE.size();
        int i = (int)p;
        float a = SINE[i & (SINE.size() - 1)];
        return a + (SINE[(i + 1) & (SINE.size() - 1)] - a) * (float)(p - i);
    }
};

void EarthTracker::Voice::trigger(Tone tone, int midi, float level, float attack, float duration, float pan) {
    old = last;
    tail = 300;
    this->tone = tone;
    rate = 440 * std::pow(2, (midi - 69) / 12.0) / RATE;
    phase = 0;
    amp = level;
    age = 0;
    frames = (int)(RATE * duration);
    rise = maxValue(1, (int)(RATE * attack));
    left = .707f - .48f * pan;
    right = .707f + .48f * pan;
}

float EarthTracker::Voice::sample(float noise) {
    if (age >= frames)
        return 0;
    float life = (float)age / frames;
    float envelope = minValue(1, (float)age / rise) * (1 - life);
    envelope *= 1 - life;
    float pure = sine(phase);
    low += (noise - low) * .075f;
    float raw = 0;
    switch (tone) {
    case Tone::SINE:
        raw = pure;
        break;
    case Tone::TRIANGLE:
        raw = pure - .10f * sine(phase * 3) + .034f * sine(phase * 5);
        break;
    case Tone::PLUCK:
        raw = pure * .8f + (1 - life) * (sine(phase * 2) * .28f + sine(phase * 3) * .10f);
        break;
    case Tone::ORGAN:
        raw = pure * .56f + sine(phase * 2) * .20f + sine(phase * 4.005) * .10f;
        break;
    case Tone::PULSE:
        raw = pure * .66f + sine(phase * 2) * .24f + sine(phase * 3) * .16f + sine(phase * 5) * .07f;
        break;
    case Tone::GLASS:
        raw = pure * .72f + sine(phase * 2.76) * (.28f - .20f * life) + sine(phase * 5.13) * .11f;
        break;
    case Tone::KICK:
        raw = pure * .92f + noise * (age < 180 ? .15f : 0);
        break;
    case Tone::TAP:
        raw = noise * .53f + sine(phase * 1.7) * .26f + sine(phase * 2.38) * .19f;
        break;
    case Tone::BRUSH:
        raw = (noise - low) * .73f + low * .12f + pure * .09f;
        break;
    case Tone::STEAM:
        raw = low * .72f + (noise - low) * .13f + pure * .026f;
        break;
    }
    phase += rate * (tone == Tone::KICK ? 1 + 2.8 / (1 + age * .0022) : 1);
    age++;
    last = raw * amp * envelope + (tail > 0 ? old * (tail-- / 300.f) : 0);
    return last;
}
