package ru.oopssre.gremlinamp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

final class AudioEnergy {
    record Levels(float bass, float middle, float treble) {}
    static final Levels SILENCE = new Levels(0, 0, 0);
    static final int SPECTRUM_BANDS = 18;
    private static final float[] QUIET_SPECTRUM = new float[SPECTRUM_BANDS];
    private record SpectrumFrame(float[] bands, long timeNanos) {}
    private final Spectrum spectrum = new Spectrum();
    private volatile SpectrumFrame spectrumFrame;
    private volatile Levels levels = SILENCE;
    private int rate;
    private float slow, fast, slowAlpha, fastAlpha;

    Levels levels() { return levels; }

    float[] spectrum() {
        SpectrumFrame frame = spectrumFrame;
        return frame == null || System.nanoTime() - frame.timeNanos() > 350_000_000L
                ? QUIET_SPECTRUM : frame.bands();
    }

    synchronized void reset() {
        slow = fast = 0;
        levels = SILENCE;
        spectrumFrame = null;
        spectrum.reset();
    }

    synchronized void pcm(short[] stereo, int count, int sampleRate) {
        if (count < 2) return;
        configure(sampleRate);
        float bass = 0, middle = 0, treble = 0;
        for (int i = 0; i + 1 < count; i += 2) {
            spectrum.add(stereo[i] / 32768f, stereo[i + 1] / 32768f);
            // Rectify each channel before downmixing so out-of-phase stereo remains visible.
            float sample = (Math.abs((int) stereo[i]) + Math.abs((int) stereo[i + 1])) / 65536f;
            float sign = stereo[i] + stereo[i + 1] == 0 ? stereo[i] : stereo[i] + stereo[i + 1];
            sample = Math.copySign(sample, sign);
            slow += slowAlpha * (sample - slow);
            fast += fastAlpha * (sample - fast);
            bass += slow * slow;
            middle += (fast - slow) * (fast - slow);
            treble += (sample - fast) * (sample - fast);
        }
        publish(bass, middle, treble, count / 2);
    }

    synchronized void pcm(ByteBuffer buffer, int sampleRate, int channels) {
        ByteBuffer data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (channels < 1) return;
        int frames = data.remaining() / (channels * 2);
        if (frames == 0) return;
        configure(sampleRate);
        float bass = 0, middle = 0, treble = 0;
        for (int i = 0; i < frames; i++) {
            int left = 0, right = 0, sum = 0, magnitude = 0;
            short first = data.getShort();
            left += first;
            sum += first;
            magnitude += Math.abs((int) first);
            for (int channel = 1; channel < channels; channel++) {
                short value = data.getShort();
                if ((channel & 1) == 0) left += value;
                else right += value;
                sum += value;
                magnitude += Math.abs((int) value);
            }
            float leftSample = left / (32768f * ((channels + 1) / 2));
            spectrum.add(leftSample, channels == 1 ? leftSample : right / (32768f * (channels / 2)));
            float sample = Math.copySign(magnitude / (32768f * channels), sum == 0 ? first : sum);
            slow += slowAlpha * (sample - slow);
            fast += fastAlpha * (sample - fast);
            bass += slow * slow;
            middle += (fast - slow) * (fast - slow);
            treble += (sample - fast) * (sample - fast);
        }
        publish(bass, middle, treble, frames);
    }

    private void configure(int sampleRate) {
        if (rate == sampleRate) return;
        rate = sampleRate;
        slowAlpha = (float) -Math.expm1(-2 * Math.PI * 150 / rate);
        fastAlpha = (float) -Math.expm1(-2 * Math.PI * 1800 / rate);
        reset();
        spectrum.configure(sampleRate);
    }

    private void publish(float bass, float middle, float treble, int frames) {
        Levels previous = levels;
        levels = new Levels(smooth(previous.bass(), bass, frames, 3),
                smooth(previous.middle(), middle, frames, 5),
                smooth(previous.treble(), treble, frames, 7));
    }

    private static float smooth(float previous, float sum, int frames, int gain) {
        float target = Math.min(1, (float) Math.sqrt(sum / frames) * gain);
        return previous + (target - previous) * (target > previous ? .45f : .18f);
    }

    private final class Spectrum {
        private static final int SIZE = 4096;
        private static final int MASK = SIZE - 1;
        private static final int HOP = SIZE / 2;
        private final float[] left = new float[SIZE], right = new float[SIZE];
        private final float[] real = new float[SIZE], imaginary = new float[SIZE];
        private final float[] window = new float[SIZE];
        private final float[] cosine = new float[SIZE / 2], sine = new float[SIZE / 2];
        private final int[] reversed = new int[SIZE];
        private final int[] edges = new int[SPECTRUM_BANDS + 1];
        private final float[] levels = new float[SPECTRUM_BANDS];
        private int write, untilTransform = SIZE;

        Spectrum() {
            for (int i = 0; i < SIZE; i++) {
                window[i] = .5f - .5f * (float) Math.cos(2 * Math.PI * i / SIZE);
                reversed[i] = Integer.reverse(i) >>> (Integer.SIZE - Integer.numberOfTrailingZeros(SIZE));
            }
            for (int i = 0; i < SIZE / 2; i++) {
                double angle = -2 * Math.PI * i / SIZE;
                cosine[i] = (float) Math.cos(angle);
                sine[i] = (float) Math.sin(angle);
            }
        }

        void reset() {
            write = 0;
            untilTransform = SIZE;
            Arrays.fill(levels, 0);
        }

        void configure(int sampleRate) {
            double highest = Math.min(16_000, sampleRate / 2d);
            for (int i = 0; i < edges.length; i++) {
                double hz = 40 * Math.pow(highest / 40, (double) i / SPECTRUM_BANDS);
                edges[i] = Math.max(1, Math.min(SIZE / 2 - 1, (int) Math.ceil(hz * SIZE / sampleRate)));
            }
        }

        void add(float l, float r) {
            left[write] = l;
            right[write] = r;
            write = (write + 1) & MASK;
            if (--untilTransform != 0) return;
            untilTransform = HOP;
            transform();
            publishBands();
        }

        private void transform() {
            // Pack the two real channels into one complex FFT.
            for (int i = 0; i < SIZE; i++) {
                int sample = (write + i) & MASK;
                real[reversed[i]] = left[sample] * window[i];
                imaginary[reversed[i]] = right[sample] * window[i];
            }
            for (int span = 2; span <= SIZE; span *= 2) {
                int half = span / 2, step = SIZE / span;
                for (int base = 0; base < SIZE; base += span) {
                    for (int i = 0; i < half; i++) {
                        int a = base + i, b = a + half, w = i * step;
                        float re = cosine[w] * real[b] - sine[w] * imaginary[b];
                        float im = sine[w] * real[b] + cosine[w] * imaginary[b];
                        real[b] = real[a] - re;
                        imaginary[b] = imaginary[a] - im;
                        real[a] += re;
                        imaginary[a] += im;
                    }
                }
            }
        }

        private void publishBands() {
            for (int band = 0; band < SPECTRUM_BANDS; band++) {
                int end = Math.max(edges[band] + 1, edges[band + 1]);
                float peak = 0;
                for (int bin = edges[band]; bin < end; bin++) {
                    // Symmetric bins give the combined power without cancelling out-of-phase channels.
                    int mirror = SIZE - bin;
                    float power = real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
                            + real[mirror] * real[mirror] + imaginary[mirror] * imaginary[mirror];
                    peak = Math.max(peak, power);
                }
                double amplitude = 2 * Math.sqrt(peak) / SIZE;
                double decibels = 20 * Math.log10(Math.max(1e-8, amplitude));
                float target = (float) Math.max(0, Math.min(1, (decibels + 54) / 48));
                levels[band] += (target - levels[band]) * (target > levels[band] ? .75f : .32f);
            }
            spectrumFrame = new SpectrumFrame(levels.clone(), System.nanoTime());
        }
    }
}
