package ru.oopssre.gremlinamp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class AudioEnergy {
    record Levels(float bass, float middle, float treble) {}
    static final Levels SILENCE = new Levels(0, 0, 0);
    private volatile Levels levels = SILENCE;
    private int rate;
    private float slow, fast, slowAlpha, fastAlpha;

    Levels levels() { return levels; }

    synchronized void reset() {
        slow = fast = 0;
        levels = SILENCE;
    }

    synchronized void pcm(short[] stereo, int count, int sampleRate) {
        if (count < 2) return;
        configure(sampleRate);
        float bass = 0, middle = 0, treble = 0;
        for (int i = 0; i + 1 < count; i += 2) {
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
            int sum = 0, magnitude = 0;
            short first = data.getShort();
            sum += first;
            magnitude += Math.abs((int) first);
            for (int channel = 1; channel < channels; channel++) {
                short value = data.getShort();
                sum += value;
                magnitude += Math.abs((int) value);
            }
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
}
