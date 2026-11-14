package ru.oopssre.gremlinamp;

import java.util.SplittableRandom;

/** Four procedural scores on a common 16-row tracker grid. All instruments are synthesized. */
final class EarthTracker {
    static final int RATE = 44_100;
    enum Mode { TEA, WORKSHOP, WEATHER, CITY }

    static final class Weather {
        static final Weather UNKNOWN = new Weather(-1, Double.NaN);
        final float rain, clouds, wind;

        Weather(int code, double windKmh) {
            this(code, windKmh, Double.NaN, Double.NaN);
        }

        Weather(int code, double windKmh, double cloudPercent, double precipitationMm) {
            rain = Math.max(precipitation(code), unit(precipitationMm / 4));
            clouds = Double.isFinite(cloudPercent) ? unit(cloudPercent / 100)
                    : code < 0 ? .48f : code < 3 ? code * .25f : code == 3 ? .78f : .94f;
            wind = Double.isFinite(windKmh) && windKmh >= 0 ? (float) Math.min(1, windKmh / 65)
                    : .15f + .4f * rain;
        }

        private static float unit(double value) {
            return Double.isFinite(value) ? (float) Math.max(0, Math.min(1, value)) : 0;
        }

        private static float precipitation(int code) {
            if (code >= 95) return 1;
            if (code >= 85) return code == 85 ? .38f : .70f;
            if (code >= 80) return .36f + .28f * Math.min(2, code - 80);
            if (code >= 71 && code <= 77) return .25f + .07f * (code - 71);
            if (code >= 61 && code <= 67) return .36f + .10f * (code - 61);
            if (code >= 51 && code <= 57) return .20f + .05f * (code - 51);
            return 0;
        }
    }

    private enum Tone { SINE, TRIANGLE, PLUCK, ORGAN, PULSE, GLASS, KICK, TAP, BRUSH, STEAM }
    private static final int[] DORIAN = {0, 2, 3, 5, 7, 9, 10};
    private static final int[] MAJOR = {0, 2, 4, 5, 7, 9, 11};
    private static final int[] TEA_ROOTS = {0, 0, 3, 3, 5, 5, 1, 1};
    private static final int[] WEATHER_ROOTS = {0, 3, 5, 1};
    private static final int[] CITY_ROOTS = {0, 0, 5, 3, 0, 2, 5, 3};
    private static final int[] WORK_ACCENTS = {0, 3, 6, 8, 11, 14};
    private static final float[] SINE = new float[2048];
    static {
        for (int i = 0; i < SINE.length; i++) SINE[i] = (float) Math.sin(2 * Math.PI * i / SINE.length);
    }

    private final Mode mode;
    private final SplittableRandom arrangement;
    private final Voice[] voices = new Voice[13];
    private final float[] echoL = new float[17_627], echoR = new float[23_319];
    private int leftIndex, rightIndex, noise = 0x1dcb38a7;
    private double ticks = 1;
    private long row;
    private float tempo, air, airLow, wet, cloud, wind, gain;
    private final int[] motif = new int[5];
    private int root;
    private float cityActivity;

    EarthTracker(Mode mode, long seed) {
        this.mode = mode;
        arrangement = new SplittableRandom(seed);
        for (int i = 0; i < voices.length; i++) voices[i] = new Voice();
        tempo = tempo(0, 0);
        varyMotif();
    }

    void render(short[] pcm, int frames, int localMinute, Weather weather) {
        render(pcm, frames, localMinute, weather, true);
    }

    void render(short[] pcm, int frames, int localMinute, Weather weather, boolean shiftingAccents) {
        float activity = commute(localMinute);
        float nextTempo = tempo(activity, weather.rain);
        for (int i = 0; i < frames; i++) {
            tempo += (nextTempo - tempo) * .000027f;
            wet += (weather.rain - wet) * .000047f;
            cloud += (weather.clouds - cloud) * .000047f;
            wind += (weather.wind - wind) * .000032f;
            gain += (1 - gain) * .00022f;
            if (ticks >= 1) {
                ticks -= 1;
                step(localMinute, activity, shiftingAccents);
            }
            ticks += tempo / (15 * RATE);
            float grain = noise();
            air += (grain - air) * .085f;
            airLow += (air - airLow) * .009f;
            float bed = switch (mode) {
                case TEA -> (air - airLow) * .033f;
                case WORKSHOP -> airLow * .038f;
                case WEATHER -> air * (.035f + .11f * wet) + airLow * (.05f + .18f * wind);
                case CITY -> airLow * (.07f + cityActivity * .20f);
            };
            float l = bed, r = bed;
            for (Voice voice : voices) {
                float sample = voice.sample(grain);
                l += sample * voice.left;
                r += sample * voice.right;
            }
            float delayedL = echoL[leftIndex], delayedR = echoR[rightIndex];
            float send = mode == Mode.WORKSHOP ? .15f : mode == Mode.CITY ? .18f : .29f;
            echoL[leftIndex] = l * send + delayedR * .29f;
            echoR[rightIndex] = r * send + delayedL * .32f;
            if (++leftIndex == echoL.length) leftIndex = 0;
            if (++rightIndex == echoR.length) rightIndex = 0;
            pcm[i * 2] = pcm(l + delayedL);
            pcm[i * 2 + 1] = pcm(r + delayedR);
        }
    }

    int tempo() { return Math.round(tempo); }

    private short pcm(float sample) {
        float value = sample * gain * 1.55f;
        return (short) (32760 * value / (1 + Math.abs(value) * .6f));
    }

    private float tempo(float activity, float rain) {
        return switch (mode) {
            case TEA -> 54;
            case WORKSHOP -> 104;
            case WEATHER -> 62 + 16 * rain;
            case CITY -> 63 + 49 * activity;
        };
    }

    private static float commute(int minute) {
        if (minute < 0 || minute >= 24 * 60) return .4f;
        float morning = Math.max(0, 1 - Math.abs(minute - 8 * 60f) / 150);
        float evening = Math.max(0, 1 - Math.abs(minute - 18 * 60f) / 150);
        return Math.min(1, .13f + (minute >= 420 && minute < 1320 ? .22f : 0) + .65f * Math.max(morning, evening));
    }

    private void step(int minute, float activity, boolean shiftingAccents) {
        int beat = (int) (row & 15), bar = (int) (row / 16 % 8);
        long phrase = row / 128;
        if (beat == 0 && bar == 0 && phrase > 0) varyMotif();
        switch (mode) {
            case TEA -> tea(beat, bar, minute, phrase);
            case WORKSHOP -> workshop(beat, bar, phrase, shiftingAccents);
            case WEATHER -> weather(beat, bar, phrase);
            case CITY -> city(beat, bar, activity, phrase);
        }
        row++;
    }

    private void varyMotif() {
        motif[0] = 0;
        motif[1] = 2 + arrangement.nextInt(2);
        motif[2] = 4 + arrangement.nextInt(2);
        motif[3] = arrangement.nextBoolean() ? 1 : 6;
        motif[4] = arrangement.nextBoolean() ? -1 : 2;
    }

    private void tea(int beat, int bar, int minute, long phrase) {
        boolean evening = minute < 360 || minute >= 1140;
        int key = evening ? 53 : 55;
        if (beat == 0 && (bar & 1) == 0) {
            root = TEA_ROOTS[(bar + (int) (phrase & 3) * 2) & 7];
            int pad = (bar & 2) == 0 ? 0 : 3;
            hit(pad, Tone.TRIANGLE, note(key - 12, root, MAJOR), .23f, .72f, 5.4f, -.18f);
            hit(pad + 1, Tone.SINE, note(key, root + 2, MAJOR), .125f, 1.1f, 5.9f, .31f);
            hit(pad + 2, Tone.TRIANGLE, note(key, root + 6, MAJOR), .075f, 1.4f, 5.5f, -.39f);
        }
        if ((bar & 1) == 0 && (beat == 3 || beat == 11) || (bar & 1) == 1 && beat == 7) {
            int degree = motif[(bar + beat / 3) % motif.length];
            hit(6 + ((bar + beat) & 1), Tone.PLUCK, note(key + 12, root + degree, MAJOR),
                    .17f, .016f, 1.45f, beat < 8 ? .3f : -.31f);
        }
        if (beat == 8 && (bar & 1) == 1)
            hit(8, Tone.STEAM, 71, .095f, .38f, 1.6f, -.22f);
        if (beat == 14 && arrangement.nextInt(3) != 0)
            hit(9, Tone.GLASS, note(key + 24, root + 4, MAJOR), .060f, .004f, .27f, .53f);
    }

    private void workshop(int beat, int bar, long phrase, boolean shiftingAccents) {
        int key = 40 + (int) (phrase % 3);
        int accent = shiftingAccents ? WORK_ACCENTS[(bar + (int) (phrase & 3)) % WORK_ACCENTS.length] : 6;
        if (beat == 0 || beat == 8 || beat == accent)
            hit(0, Tone.KICK, beat == 8 ? 36 : 33, beat == accent ? .34f : .47f, .004f, .27f, 0);
        if (beat == 3 || beat == 6 || beat == 11 || beat == 14)
            hit(1, Tone.TAP, 70 + (beat & 3) * 3, beat == 6 || beat == 14 ? .23f : .14f, .003f, .11f,
                    beat < 8 ? -.53f : .57f);
        if (beat == 2 || beat == 7 || beat == 10 || beat == 13 || beat == 15 && (bar & 1) == 1)
            hit(2, Tone.BRUSH, 88, .115f, .003f, .09f, beat < 8 ? .28f : -.3f);
        if (beat == 0 || beat == 5 || beat == 12)
            hit(3, Tone.PULSE, note(key, beat == 5 ? 3 : beat == 12 ? 4 : 0, DORIAN),
                    .20f, .009f, .31f, -.09f);
        if (beat == 10 && (bar & 1) == 0 || beat == 15 && bar % 4 == 3)
            hit(4, Tone.GLASS, note(key + 24, motif[bar % motif.length], DORIAN), .11f, .007f, .48f, .40f);
        if (beat == 0 && bar % 4 == 0)
            hit(5, Tone.ORGAN, key - 12, .095f, .62f, 5.5f, 0);
    }

    private void weather(int beat, int bar, long phrase) {
        int key = 50;
        int[] scale = cloud > .56f ? DORIAN : MAJOR;
        if (beat == 0) {
            root = WEATHER_ROOTS[(bar + (int) (phrase & 3)) & 3];
            int pad = (bar & 1) * 3;
            hit(pad, Tone.TRIANGLE, note(key - 12, root, scale), .23f, .64f, 4.3f, -.20f);
            hit(pad + 1, Tone.SINE, note(key, root + (cloud > .8f ? 1 : 2), scale), .12f, .95f, 3.8f, .4f);
            hit(pad + 2, Tone.TRIANGLE, note(key, root + 4, scale), .105f, 1.4f, 3.2f, -.33f);
        }
        if (beat == 5 || beat == 12 && cloud < .62f)
            hit(6, Tone.GLASS, note(key + 19, root + motif[bar % motif.length], scale),
                    .13f - .04f * wet, .028f, 1.75f, -.4f + .8f * wind);
        if (wet > .18f && ((float) arrangement.nextDouble()) < .09f + wet * .69f)
            hit(7 + (beat & 1), Tone.TAP, note(key + 28, motif[beat % motif.length], scale),
                    .055f + .12f * wet, .003f, .16f, (((float) arrangement.nextDouble()) - .5f) * 1.6f);
        if (beat == 0 && wind > .27f)
            hit(9, Tone.STEAM, 64, .075f + wind * .16f, .58f, 2.0f, (bar & 1) == 0 ? -.43f : .43f);
    }

    private void city(int beat, int bar, float activity, long phrase) {
        cityActivity = activity;
        int key = activity > .65f ? 46 : 43;
        if (beat == 0) {
            root = CITY_ROOTS[(bar + (int) (phrase & 3) * 2) & 7];
            int pad = (bar & 1) * 2;
            hit(pad, Tone.ORGAN, note(key, root + 7, DORIAN), .11f, .17f, 2.0f, -.42f);
            hit(pad + 1, Tone.TRIANGLE, note(key + 12, root + 2, DORIAN), .11f, .27f, 2.5f, .45f);
        }
        if (beat == 0 || beat == 10 || beat == 8 && activity > .48f || beat == 6 && activity > .72f) {
            hit(4, Tone.KICK, 35, .22f + .23f * activity, .004f, .31f, 0);
            hit(5, Tone.TRIANGLE, note(key - 8, root + (beat == 10 ? 4 : 0), DORIAN),
                    .22f, .022f, .55f, -.08f);
        }
        if (beat == 4 || beat == 12)
            hit(6, Tone.BRUSH, 69, .17f + .09f * activity, .006f, .15f, .13f);
        if (beat == 2 || beat == 9 || beat == 14 || activity > .65f && beat % 2 == 1)
            hit(7, Tone.BRUSH, 89, .075f + activity * .045f, .003f, .095f, (beat & 2) == 0 ? .51f : -.39f);
        if (beat == 3 || beat == 11 && (bar & 1) == 0 || beat == 15 && activity > .45f)
            hit(8, Tone.PULSE, note(key + 19, root + motif[(bar + beat / 3) % motif.length], DORIAN),
                    .11f + .08f * activity, .012f, .47f, -.28f);
        if (beat == 7 && bar % 4 == 2)
            hit(9, Tone.GLASS, note(key + 24, root + 4, DORIAN), .095f, .02f, .61f, .5f);
    }

    private void hit(int channel, Tone tone, int note, float level, float attack, float length, float pan) {
        voices[channel].trigger(tone, note, level, attack, length, pan);
    }

    private static int note(int key, int degree, int[] scale) {
        return key + 12 * Math.floorDiv(degree, 7) + scale[Math.floorMod(degree, 7)];
    }

    private float noise() {
        noise ^= noise << 13;
        noise ^= noise >>> 17;
        noise ^= noise << 5;
        return (noise >> 8) * (1f / 8_388_608);
    }

    private static float sin(double phase) {
        double p = (phase - (long) phase) * SINE.length;
        int i = (int) p;
        float a = SINE[i & (SINE.length - 1)];
        return a + (SINE[(i + 1) & (SINE.length - 1)] - a) * (float) (p - i);
    }

    private static final class Voice {
        private Tone tone;
        private double phase, rate;
        private int age, frames, rise, tail;
        private float amp, last, old, low, left, right;

        void trigger(Tone tone, int midi, float level, float attack, float duration, float pan) {
            old = last;
            tail = 300;
            this.tone = tone;
            rate = 440 * Math.pow(2, (midi - 69) / 12d) / RATE;
            phase = 0;
            amp = level;
            age = 0;
            frames = (int) (RATE * duration);
            rise = Math.max(1, (int) (RATE * attack));
            left = .707f - .48f * pan;
            right = .707f + .48f * pan;
        }

        float sample(float noise) {
            if (age >= frames) return 0;
            float life = (float) age / frames;
            float envelope = Math.min(1, (float) age / rise) * (1 - life);
            envelope *= 1 - life;
            float pure = sin(phase);
            low += (noise - low) * .075f;
            float raw = switch (tone) {
                case SINE -> pure;
                case TRIANGLE -> pure - .10f * sin(phase * 3) + .034f * sin(phase * 5);
                case PLUCK -> pure * .8f + (1 - life) * (sin(phase * 2) * .28f + sin(phase * 3) * .10f);
                case ORGAN -> pure * .56f + sin(phase * 2) * .20f + sin(phase * 4.005) * .10f;
                case PULSE -> pure * .66f + sin(phase * 2) * .24f + sin(phase * 3) * .16f + sin(phase * 5) * .07f;
                case GLASS -> pure * .72f + sin(phase * 2.76) * (.28f - .20f * life) + sin(phase * 5.13) * .11f;
                case KICK -> pure * .92f + noise * (age < 180 ? .15f : 0);
                case TAP -> noise * .53f + sin(phase * 1.7) * .26f + sin(phase * 2.38) * .19f;
                case BRUSH -> (noise - low) * .73f + low * .12f + pure * .09f;
                case STEAM -> low * .72f + (noise - low) * .13f + pure * .026f;
            };
            phase += rate * (tone == Tone.KICK ? 1 + 2.8 / (1 + age * .0022) : 1);
            age++;
            last = raw * amp * envelope + (tail > 0 ? old * (tail-- / 300f) : 0);
            return last;
        }
    }
}
