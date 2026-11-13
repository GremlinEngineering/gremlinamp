package ru.oopssre.gremlinamp;

import java.util.Random;

/** A continuous six-channel tracker. One source frame is pitch, energy, and high-band energy. */
final class SpaceTracker {
    static final int RATE = 44_100;
    enum Mode {
        VOYAGER("Вояджер", "Voyager 1 · плазменные волны", "voyager", 45, 68, 0),
        STARS("Звёзды", "Kepler · колебания яркости KIC 12268220", "stars", 50, 63, 1),
        SOLAR("Солнечная система", "Voyager · Юпитер / Cassini · Сатурн", "solar", 43, 74, 2),
        CITY("Москва", "Городской трекер · по мотивам Sonic City", "", 48, 72, 3),
        ORBITS("Орбиты", "Луна · солнечный ветер · вспышки", "", 41, 48, 4);

        final String title, source, asset;
        final int key, restTempo, arrangement;
        Mode(String title, String source, String asset, int key, int restTempo, int arrangement) {
            this.title = title;
            this.source = source;
            this.asset = asset;
            this.key = key;
            this.restTempo = restTempo;
            this.arrangement = arrangement;
        }
    }

    record Motion(float cadence, float movement, float tilt) {
        static final Motion STILL = new Motion(0, 0, 0);
    }

    record Street(float level, float brightness, short[] sound) {
        static final Street QUIET = new Street(0, 0, new short[0]);
        Street(float level, float brightness) { this(level, brightness, QUIET.sound); }
    }

    private static final int[] MINOR = {0, 2, 3, 5, 7, 8, 10};
    private static final int[] ROOTS = {0, 5, 2, 6};
    private static final int ROWS_PER_BAR = 16;
    private final Mode mode;
    private final PlasmaLayer plasma;
    private final byte[] source;
    private final Random variation;
    private final Voice bass = new Voice();
    private final Voice lowPad = new Voice(), highPad = new Voice();
    private final Voice lead = new Voice(), response = new Voice(), spark = new Voice();
    private final float[] delayLeft = new float[18_947], delayRight = new float[24_311];
    private final int[] motif = new int[6];
    private final int stride, cityShift;
    private int leftIndex, rightIndex, frameIndex;
    private long row, phrase;
    private int motifOrigin, root;
    private int noiseState = 0x52b6d1;
    private double rowPhase = 1;
    private float bpm, movement, pan, effect, brightness, energy, noiseBed, percussion, dust, drumPhase;
    private float leftFilter, rightFilter, streetLevel, streetBrightness;
    private OrbitSnapshot.Signal orbit = OrbitSnapshot.Signal.EMPTY;
    private long playedFlare;
    private int windRows;
    private float orbitPan, orbitBend = 1, cityGate, cityAir, citySample;
    private Street streetPacket = Street.QUIET;
    private double streetFrame;

    SpaceTracker(Mode mode, byte[] source, long seed, short[] plasmaSamples) {
        if (!mode.asset.isEmpty() && (source.length < 24 || source.length % 3 != 0)) throw new IllegalArgumentException("Invalid plasma feature data");
        this.mode = mode;
        this.source = source;
        plasma = mode == Mode.VOYAGER ? new PlasmaLayer(plasmaSamples) : null;
        long fingerprint = 0xcbf29ce484222325L;
        for (byte value : source) fingerprint = (fingerprint ^ (value & 255)) * 0x100000001b3L;
        variation = new Random(fingerprint ^ seed);
        stride = 1 + mode.arrangement;
        cityShift = (int) Math.floorMod(seed, 12L);
        motifOrigin = (int) Math.floorMod(fingerprint, (long) frameCount());
        bpm = mode.restTempo;
        updateMotif();
    }

    private int frameCount() { return Math.max(1, source.length / 3); }

    private float feature(int offset, int band) {
        if (mode == Mode.ORBITS) return band == 1 ? orbit.density() : orbit.xray();
        if (mode == Mode.CITY) return switch (band) {
            case 0 -> (.12f + .28f * streetBrightness + .15f * Math.floorMod(offset + cityShift + (int) (phrase % 4), 4));
            case 1 -> .36f + .52f * streetLevel;
            default -> .35f + .64f * streetBrightness;
        };
        return (source[Math.floorMod(offset, frameCount()) * 3 + band] & 255) / 255f;
    }

    private void updateMotif() {
        int first = Math.round(feature(motifOrigin, 0) * 5);
        for (int i = 0; i < motif.length; i++) {
            int point = motifOrigin + i * (2 + mode.arrangement);
            int degree = Math.round(feature(point, 0) * 7) - first;
            motif[i] = Math.max(-2, Math.min(6, degree + (i == 0 ? 0 : i / 2)));
        }
    }

    private int note(int tonic, int degree) {
        return tonic + 12 * Math.floorDiv(degree, 7) + MINOR[Math.floorMod(degree, 7)];
    }

    private void step() {
        int beat = (int) (row % ROWS_PER_BAR);
        int bar = (int) ((row / ROWS_PER_BAR) % 8);
        if (beat == 0 && bar == 0 && row != 0) {
            phrase++;
            motifOrigin = Math.floorMod(motifOrigin + 5 + variation.nextInt(15), frameCount());
            updateMotif();
        }
        int sample = motifOrigin + frameIndex;
        energy = feature(sample, 1);
        brightness = feature(sample, 2);
        switch (mode) {
            case VOYAGER -> telemetry(sample);
            case STARS -> starfield(sample);
            case SOLAR -> planets(beat, bar);
            case CITY -> city(beat, bar);
            case ORBITS -> orbits();
        }
        frameIndex = (frameIndex + stride) % frameCount();
        row++;
    }

    private void telemetry(int sample) {
        if (row % 53 == 0) {
            bass.trigger(mode.key - 17, .31f, 2.3f, 19);
            lowPad.trigger(mode.key - 5, .093f, 3.4f, 22);
        }
        if (row % 37 == 9) highPad.trigger(mode.key + 2, .059f, 2.7f, 16);
        int burst = (int) (row % 31);
        if (burst == 4 || burst == 5 || burst == 8 && movement > .17f) {
            int band = Math.round(feature(sample, 0) * 11);
            lead.trigger(mode.key + 28 + band, burst == 5 ? .087f : .15f, .008f, .18f);
            lead.slide(burst == 5 ? -4 : 3);
            if (burst == 4) dust = .24f;
        }
        if (row % 43 == 18) {
            response.trigger(mode.key + 19 + Math.round(brightness * 7), .094f, .04f, .51f);
            response.slide(-5);
        }
    }

    private void starfield(int sample) {
        if (row % 79 == 0) bass.trigger(mode.key - 14, .17f, 4.5f, 27);
        if (row % 61 == 0) lowPad.trigger(mode.key - 2, .21f, 3.9f, 24);
        if (row % 47 == 12) highPad.trigger(mode.key + 5, .14f, 3.5f, 19);
        if (row % 23 == 3 && energy > .18f) {
            int degree = Math.round(feature(sample, 0) * 4);
            response.trigger(note(mode.key + 19, degree), .10f + .035f * energy, .35f, 2.8f);
        }
        if (row % 37 == 14) spark.trigger(mode.key + 31, .052f, .13f, 2.9f);
    }

    private void planets(int beat, int bar) {
        if (beat == 0) {
            root = ROOTS[(bar / 2 + (int) (phrase % 4)) % ROOTS.length];
            lowPad.trigger(note(mode.key, root + 7), .084f, .17f, 2.4f);
        }
        if (beat == 0 || beat == 6 || beat == 8 || beat == 13) {
            bass.trigger(note(mode.key - 8, root + (beat == 13 ? 7 : 0)), .28f, .006f, .49f);
            percussion = beat == 6 ? .24f : .52f;
        }
        if (beat == 4 || beat == 12) dust = .63f;
        else if (beat % 2 == 1 && (movement > .17f || brightness > .5f)) dust = .17f;
        if (beat == 2 || beat == 7 || beat == 10 || beat == 14) {
            int degree = motif[(beat / 3 + bar / 2) % motif.length];
            lead.trigger(note(mode.key + 11, root + degree), .16f + energy * .075f, .005f, .35f);
            lead.arpeggio((bar & 1) == 0 ? 3 : 5, 7);
        }
        if (beat == 15 && (bar & 1) == 1) {
            response.trigger(note(mode.key + 12, root + 4), .10f, .01f, .35f);
            response.slide(-7);
        }
    }

    private void city(int beat, int bar) {
        float activity = Math.min(1, movement * 1.65f + streetLevel * .45f);
        if (beat == 0) {
            updateMotif();
            root = ROOTS[(bar / 2 + cityShift / 3 + (int) (phrase % 4) + (streetLevel > .4f ? 1 : 0)) % ROOTS.length];
            lowPad.trigger(note(mode.key - 5, root), .16f - .05f * activity, .7f, 5.2f);
        }
        if (beat == 0 || beat == 8 && activity > .17f || (beat == 5 || beat == 11) && activity > .38f) {
            bass.trigger(note(mode.key - 13, root + (beat == 5 ? 7 : 0)), .16f + .16f * activity, .009f, .48f);
            percussion = activity * (.45f + streetLevel * .28f);
        }
        if ((beat == 4 || beat == 12) && activity > .12f) dust = .5f * activity + streetBrightness * .24f;
        if (beat == 3 && (activity > .17f || (bar & 1) == 0)
                || beat == 10 && streetBrightness > .45f || beat == 14 && activity > .42f) {
            lead.trigger(note(mode.key + 6, root + motif[beat % motif.length]), .13f, .009f, .28f);
            if (beat == 14) lead.slide(-3);
        }
        if (beat == 9 && streetLevel > .30f) spark.trigger(mode.key + 17 + Math.round(streetBrightness * 5), .095f, .004f, .14f);
    }

    void setOrbit(OrbitSnapshot.Signal data) { orbit = data; }

    private void orbits() {
        if (row % 28 == 0) lowPad.trigger(mode.key - 8, .19f, 2.4f, 24);
        if (row % 41 == 7) highPad.trigger(mode.key + 4, .11f, 2.1f, 17);
        if (--windRows <= 0) {
            windRows = 7 + Math.round(19 * (1 - orbit.speed()));
            bass.trigger(mode.key - 17, .10f + .11f * orbit.density(), .14f, 2.0f);
            dust = .08f + .36f * orbit.density();
        }
        if (row % 19 == 6) {
            response.trigger(mode.key + 12 + Math.round(orbit.xray() * 7), .035f + .085f * orbit.xray(), .19f, 2.9f);
        }
        if (orbit.flare() > playedFlare) {
            playedFlare = orbit.flare();
            spark.trigger(mode.key + 27, .12f + .12f * orbit.flareLevel(), .014f, 1.4f);
            spark.slide(7);
        }
    }

    void render(short[] pcm, int frames, Motion motion, Street street, boolean cosmicEffects, boolean plasmaEnabled) {
        boolean orbits = mode == Mode.ORBITS, city = mode == Mode.CITY;
        float targetTempo = !orbits && motion.cadence() > 0 ?
                Math.max(56, Math.min(106, motion.cadence() * .62f)) : mode.restTempo;
        float targetEffect = cosmicEffects ? 1 : 0;
        if (city && streetPacket != street) {
            streetPacket = street;
            streetFrame = 0;
        }
        for (int frame = 0; frame < frames; frame++) {
            streetLevel += (street.level() - streetLevel) * .00004f;
            streetBrightness += (street.brightness() - streetBrightness) * .00004f;
            bpm += (targetTempo - bpm) * .000022f;
            movement += (Math.max(motion.movement(), motion.cadence() > 0 ? .27f : 0) - movement) * .00018f;
            pan += (motion.tilt() - pan) * .00021f;
            effect += (targetEffect - effect) * .00030f;
            if (rowPhase >= 1) {
                rowPhase -= 1;
                step();
            }
            rowPhase += bpm / (15.0 * RATE);
            float noise = noise();
            float drift = 1 + effect * (noiseBed * .019f + pan * .003f);
            float low = bass.wave(0, .5f, 1);
            if (orbits) {
                orbitPan += (orbit.pan() - orbitPan) * .000035f;
                orbitBend += (.981f + .036f * orbit.distance() + .11f * orbit.elevation() - orbitBend) * .000035f;
            }
            float pad = lowPad.wave(0, .5f, orbitBend) + highPad.wave(0, .5f, orbitBend * 1.0015f);
            float melody = lead.wave(1, .26f + .13f * brightness + .09f * pan, drift);
            float answer = response.wave(0, .5f, drift) + spark.wave(1, .25f, 1);
            drumPhase += (.0014f + percussion * .0022f);
            drumPhase -= (int) drumPhase;
            percussion *= .99944f;
            dust *= .9988f;
            float rhythm = (1 - 4 * Math.abs(drumPhase - .5f)) * percussion * .19f + noise * dust * .13f;
            float left = low + rhythm + pad * (.96f - orbitPan * .37f) + melody * (.91f - pan * .25f) + answer * .48f;
            float right = low + rhythm + pad * (1.04f + orbitPan * .37f) + melody * (.91f + pan * .25f) + answer * .86f;
            if (city) {
                float texture = cityTexture(street);
                left += texture * (.8f - .22f * pan);
                right += texture * (.8f + .22f * pan);
                melody += texture * .7f; // The gated street texture shares the tracker echo.
            }
            float echoLeft = delayLeft[leftIndex], echoRight = delayRight[rightIndex];
            delayLeft[leftIndex] = melody * .52f + pad * .11f + echoRight * (mode == Mode.VOYAGER ? .58f : .40f);
            delayRight[rightIndex] = answer * .57f + melody * .31f + echoLeft * (mode == Mode.VOYAGER ? .62f : .42f);
            if (++leftIndex == delayLeft.length) leftIndex = 0;
            if (++rightIndex == delayRight.length) rightIndex = 0;
            noiseBed += (noise - noiseBed) * .028f;
            float cosmic = effect * (noiseBed * (.055f + .073f * energy) + noise * .008f * brightness);
            left += echoLeft * .72f + cosmic;
            right += echoRight * .72f - cosmic * .57f;
            left += effect * (Math.round(left * 45) / 45f - left) * .7f;
            right += effect * (Math.round(right * 45) / 45f - right) * .7f;
            leftFilter += (left - leftFilter) * .28f;
            rightFilter += (right - rightFilter) * .28f;
            // The archival signal is mixed after the synthetic effects.
            float recorded = plasma == null ? 0 : plasma.next(plasmaEnabled);
            pcm[frame * 2] = toPcm(leftFilter + recorded * .88f);
            pcm[frame * 2 + 1] = toPcm(rightFilter + recorded);
        }
    }

    int tempo() { return Math.round(bpm); }

    private float cityTexture(Street street) {
        int sample = (int) streetFrame;
        float input = sample < street.sound.length ? street.sound[sample] / 32768f : 0;
        streetFrame += 16000.0 / RATE;
        citySample += (input - citySample) * .11f;
        cityAir += (citySample - cityAir) * (.009f + .043f * streetBrightness + .039f * movement);
        boolean open = row % 4 == 1 || movement > .37f && row % 2 == 1;
        cityGate += ((open ? Math.min(1, .18f + movement + streetLevel) : 0) - cityGate) * .0009f;
        return Math.max(-.18f, Math.min(.18f, (citySample - cityAir * .91f) * 1.9f)) * cityGate;
    }

    private static short toPcm(float sample) {
        return (short) Math.round(Math.max(-1, Math.min(1, sample)) * 30_400);
    }

    private float noise() {
        noiseState ^= noiseState << 13;
        noiseState ^= noiseState >>> 17;
        noiseState ^= noiseState << 5;
        return (noiseState >>> 8) * (2f / 16777216) - 1;
    }

    private static final class PlasmaLayer {
        private static final int SOURCE_RATE = 16_000;
        private static final int FADE_FRAMES = RATE / 4;
        private static final int SILENCE_FRAMES = 13 * RATE;
        private final short[] samples;
        private final int clipFrames;
        private int frame = -3 * RATE;
        private float gain;

        PlasmaLayer(short[] samples) {
            if (samples.length < SOURCE_RATE) throw new IllegalArgumentException("Invalid Voyager plasma recording");
            this.samples = samples;
            clipFrames = (int) ((samples.length - 1L) * RATE / SOURCE_RATE);
        }

        float next(boolean enabled) {
            gain += ((enabled ? .34f : 0f) - gain) * .0004f;
            int offset = frame++;
            if (frame == clipFrames) frame = -SILENCE_FRAMES;
            if (offset < 0) return 0;
            double sourceFrame = offset * (SOURCE_RATE / (double) RATE);
            int index = (int) sourceFrame;
            float fraction = (float) (sourceFrame - index);
            float sample = samples[index] + (samples[index + 1] - samples[index]) * fraction;
            float edge = Math.min(1f, Math.min(offset, clipFrames - 1 - offset) / (float) FADE_FRAMES);
            return sample * (gain / 32768f) * edge;
        }
    }

    private static final class Voice {
        private float phase, volume, peak, attack, decay, increment, filtered;
        private boolean rising;
        private float slideRatio = 1, bendOffset;
        private float arpFirst = 1, arpSecond = 1;
        private int arpFrame;

        void slide(int semitones) { slideRatio = (float) Math.pow(2, semitones / 12.0); }
        void arpeggio(int first, int second) {
            arpFirst = (float) Math.pow(2, first / 12.0);
            arpSecond = (float) Math.pow(2, second / 12.0);
        }


        void trigger(int midi, float level, float attackSeconds, float releaseSeconds) {
            increment = (float) (440 * Math.pow(2, (midi - 69) / 12.0) / RATE);
            slideRatio = 1;
            bendOffset = 1;
            arpFirst = arpSecond = 1;
            arpFrame = 0;
            peak = level;
            attack = 1 - (float) Math.exp(-4 / (RATE * attackSeconds));
            decay = (float) Math.exp(-3.2 / (RATE * releaseSeconds));
            rising = true;
        }

        float wave(int shape, float duty, float bend) {
            if (rising) {
                volume += (peak - volume) * attack;
                if (Math.abs(peak - volume) < .002f) rising = false;
            } else {
                volume *= decay;
                if (volume < .00005f) return 0;
            }
            bendOffset += (slideRatio - bendOffset) * .00021f;
            int tick = arpFrame++ / 735;
            if (arpFrame == 2205) arpFrame = 0;
            float arpeggio = tick == 1 ? arpFirst : tick == 2 ? arpSecond : 1;
            phase += increment * bend * bendOffset * arpeggio;
            if (phase >= 1) phase -= 1;
            float value = shape == 0 ? 1 - 4 * Math.abs(phase - .5f) : (phase < duty ? 1 - duty : -duty) * 1.45f;
            filtered += (value - filtered) * (shape == 0 ? .34f : .22f);
            return filtered * volume;
        }
    }
}
