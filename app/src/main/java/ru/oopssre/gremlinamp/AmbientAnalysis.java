package ru.oopssre.gremlinamp;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Extracts a note contour, bass and onset density from the decoded stereo PCM. */
final class AmbientAnalysis {
    private static final int RATE = 11_025, FFT = 4096, WINDOW = 2048, HOP = 220;
    private static final int MIN = 29, MAX = 93;
    private static final double FRAME_SECONDS = HOP / (double) RATE;
    private static final double[] WINDOW_WEIGHTS = new double[WINDOW];
    private static final double[] BINS = new double[MAX - MIN + 1];
    private static final double[] COS = new double[FFT / 2], SIN = new double[FFT / 2];
    static {
        for (int i = 0; i < WINDOW; i++) WINDOW_WEIGHTS[i] = .5 - .5 * Math.cos(2 * Math.PI * i / (WINDOW - 1));
        for (int i = 0; i < BINS.length; i++) BINS[i] = frequency(MIN + i) * FFT / RATE;
        for (int i = 0; i < COS.length; i++) {
            double angle = -2 * Math.PI * i / FFT;
            COS[i] = Math.cos(angle);
            SIN[i] = Math.sin(angle);
        }
    }

    record Note(double start, double end, int pitch, float level) {}
    record Theme(List<Note> melody, List<Note> bass, double bpm, double seconds, double attacks, long seed) {}
    private record Contour(List<Note> notes, double clarity) {}

    static double frequency(int midi) { return 440 * Math.pow(2, (midi - 69) / 12.0); }

    static Theme read(PcmSource source, long originalMs, long seed) throws IOException {
        int rate = source.sampleRate();
        if (rate < 8000 || rate > 384_000) throw new IOException("Неподдерживаемая частота для анализа");
        int seconds = 42, activeSeconds = 28;
        long known = source.duration();
        long limit = (long) rate * seconds;
        if (known > 0) limit = Math.min(limit, known);
        if (originalMs > 0) limit = Math.min(limit, originalMs * rate / 1000);
        float[] mono = new float[seconds * RATE + 1];
        short[] pcm = new short[4096];
        int count = 0, phase = 0, bucket = 0, firstSound = -1;
        float sum = 0;
        for (long read = 0; read < limit && !source.ended();) {
            checkInterrupted();
            int frames = source.render(pcm, (int) Math.min(pcm.length / 2, limit - read));
            if (frames <= 0) break;
            for (int i = 0; i < frames; i++) {
                sum += (pcm[i * 2] + pcm[i * 2 + 1]) / 65536f;
                bucket++;
                phase += RATE;
                while (phase >= rate && count < mono.length) {
                    float value = bucket == 0 ? mono[count - 1] : sum / bucket;
                    mono[count++] = value;
                    if (firstSound < 0 && Math.abs(value) > .004f) firstSound = count - 1;
                    phase -= rate;
                    sum = 0;
                    bucket = 0;
                }
            }
            read += frames;
            if (firstSound >= 0 && count - firstSound >= activeSeconds * RATE) break;
        }
        if (firstSound < 0) throw new IOException("В начале трека нет слышимого сигнала");
        int start = Math.max(0, firstSound - RATE / 5);
        int end = Math.min(count, start + activeSeconds * RATE);
        if (end - start < RATE / 5) throw new IOException("Недостаточно звука для аранжировки");
        return extract(Arrays.copyOfRange(mono, start, end), end - start, seed);
    }

    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Анализ прерван");
    }

    private static Theme extract(float[] mono, int count, long seed) throws IOException {
        int frames = 1 + count / HOP;
        float[][] scores = new float[frames][BINS.length];
        float[] rms = new float[frames], flux = new float[frames];
        double[] re = new double[FFT], im = new double[FFT], mag = new double[FFT / 2 + 1];
        double[] previous = new double[900];
        for (int frame = 0; frame < frames; frame++) {
            checkInterrupted();
            Arrays.fill(re, 0);
            Arrays.fill(im, 0);
            double energy = 0;
            int start = frame * HOP - WINDOW / 2;
            for (int i = 0; i < WINDOW; i++) {
                int sample = start + i;
                if (sample < 0 || sample >= count) continue;
                float value = mono[sample];
                energy += value * value;
                re[i] = value * WINDOW_WEIGHTS[i];
            }
            rms[frame] = (float) Math.sqrt(energy / WINDOW);
            fft(re, im);
            for (int i = 0; i < mag.length; i++) mag[i] = Math.hypot(re[i], im[i]);
            double rise = 0;
            for (int i = 12; i < previous.length; i++) {
                double now = Math.log1p(mag[i]);
                rise += Math.max(0, now - previous[i]);
                previous[i] = now;
            }
            flux[frame] = (float) (rise / (previous.length - 12));
            for (int note = 0; note < BINS.length; note++) {
                double bin = BINS[note], fundamental = band(mag, bin);
                double second = band(mag, bin * 2), third = band(mag, bin * 3);
                double fourth = band(mag, bin * 4), fifth = band(mag, bin * 5);
                double strength = fundamental + .42 * second + .25 * third + .13 * fourth + .08 * fifth;
                strength -= .24 * Math.max(band(mag, bin * .94), band(mag, bin * 1.06)) + .55 * band(mag, bin * .5);
                double strongest = Math.max(fundamental, Math.max(second, Math.max(third, Math.max(fourth, fifth))));
                scores[frame][note] = (float) (Math.max(0, strength) * Math.pow(Math.max(.1, fundamental / (strongest + 1e-7)), .35));
            }
        }
        float level = quantile(rms, .8);
        if (level < .00028f) throw new IOException("В начале трека нет слышимого сигнала");
        Contour melody = contour(scores, rms, 54, 90, level);
        Contour bass = contour(scores, rms, 32, 57, level);
        // When only a low melodic voice is audible, the arrangement can use it as its lead.
        List<Note> lead = bass.notes.isEmpty() || melody.notes.size() >= 3 && melody.clarity >= .045
                ? melody.notes : bass.notes;
        double seconds = count / (double) RATE;
        float threshold = Math.max(.002f, quantile(flux, .88));
        int onsets = 0;
        for (int i = 1; i + 1 < frames; i++) {
            if (flux[i] > threshold && flux[i] > flux[i - 1] && flux[i] >= flux[i + 1]) onsets++;
        }
        return new Theme(Collections.unmodifiableList(lead), Collections.unmodifiableList(bass.notes),
                tempo(flux), seconds, onsets / seconds, seed);
    }

    private static double band(double[] spectrum, double at) {
        int low = (int) at;
        if (low + 1 >= spectrum.length) return 0;
        return spectrum[low] + (at - low) * (spectrum[low + 1] - spectrum[low]);
    }

    private static float quantile(float[] values, double q) {
        float[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[(int) ((sorted.length - 1) * q)];
    }

    private static Contour contour(float[][] scores, float[] rms, int low, int high, float level) throws IOException {
        int rest = high - low + 1;
        double[] previous = new double[rest + 1], next = new double[rest + 1];
        double[][] penalty = new double[rest + 1][rest + 1];
        for (int a = 0; a <= rest; a++) for (int b = 0; b <= rest; b++) {
            if (a != b) penalty[a][b] = a == rest || b == rest ? .40 : .22 + Math.min(Math.abs(a - b), 18) * .047;
        }
        byte[][] parents = new byte[scores.length][rest + 1];
        double clarity = 0;
        for (int frame = 0; frame < scores.length; frame++) {
            checkInterrupted();
            float[] row = scores[frame];
            double peak = 0, sum = 0;
            for (int note = low; note <= high; note++) {
                float value = row[note - MIN];
                peak = Math.max(peak, value);
                sum += value;
            }
            double concentration = peak / (sum + 1e-6);
            boolean silence = rms[frame] < Math.max(.0012, level * .093) || peak < .12 || concentration < .049;
            if (!silence) clarity += concentration;
            for (int note = 0; note <= rest; note++) {
                int from = 0;
                double best = -Double.MAX_VALUE;
                for (int past = 0; past <= rest; past++) {
                    double candidate = previous[past] - penalty[past][note];
                    if (candidate > best) { best = candidate; from = past; }
                }
                parents[frame][note] = (byte) from;
                double emission = note == rest ? (silence ? 2.4 : .65)
                        : Math.log1p(12 * row[note + low - MIN] / Math.max(1e-6, peak)) - (silence ? 5 : 0);
                next[note] = best + emission;
            }
            double[] swap = previous;
            previous = next;
            next = swap;
        }
        int label = 0;
        for (int i = 1; i <= rest; i++) if (previous[i] > previous[label]) label = i;
        int[] notes = new int[scores.length];
        for (int frame = notes.length - 1; frame >= 0; frame--) {
            notes[frame] = label == rest ? -1 : low + label;
            label = parents[frame][label];
        }
        List<Note> result = new ArrayList<>();
        for (int begin = 0; begin < notes.length;) {
            int end = begin + 1;
            double energy = rms[begin];
            while (end < notes.length && notes[end] == notes[begin]) energy += rms[end++];
            if (notes[begin] >= 0 && end - begin >= 3) {
                float gain = (float) (.52 + .48 * Math.min(1., energy / ((end - begin) * level)));
                result.add(new Note(begin * FRAME_SECONDS, end * FRAME_SECONDS, notes[begin], gain));
            }
            begin = end;
        }
        return new Contour(result, clarity / scores.length);
    }

    private static double tempo(float[] input) {
        if (input.length < 140) return 100;
        float cutoff = quantile(input, .68);
        float[] flux = input.clone();
        for (int i = 0; i < flux.length; i++) flux[i] = Math.max(0, flux[i] - cutoff);
        int winner = 100;
        double best = 0;
        for (int bpm = 70; bpm <= 160; bpm++) {
            int lag = (int) Math.round(60 / (FRAME_SECONDS * bpm));
            double score = (corr(flux, lag) + .65 * corr(flux, 2 * lag) + .35 * corr(flux, 4 * lag))
                    * (1 - .26 * Math.abs(Math.log(bpm / 110.0) / Math.log(2)));
            if (score > best) { best = score; winner = bpm; }
        }
        return winner;
    }

    private static double corr(float[] values, int lag) {
        if (lag >= values.length) return 0;
        double total = 0;
        for (int i = lag; i < values.length; i++) total += values[i] * values[i - lag];
        return total / (values.length - lag);
    }

    private static void fft(double[] re, double[] im) {
        for (int i = 1, j = 0; i < FFT; i++) {
            int bit = FFT >> 1;
            while ((j & bit) != 0) { j ^= bit; bit >>= 1; }
            j ^= bit;
            if (i < j) { double value = re[i]; re[i] = re[j]; re[j] = value; }
        }
        for (int length = 2; length <= FFT; length *= 2) {
            int half = length / 2, stride = FFT / length;
            for (int begin = 0; begin < FFT; begin += length) {
                for (int i = 0; i < half; i++) {
                    int a = begin + i, b = a + half, k = i * stride;
                    double real = re[b] * COS[k] - im[b] * SIN[k];
                    double imag = re[b] * SIN[k] + im[b] * COS[k];
                    re[b] = re[a] - real;
                    im[b] = im[a] - imag;
                    re[a] += real;
                    im[a] += imag;
                }
            }
        }
    }
}
