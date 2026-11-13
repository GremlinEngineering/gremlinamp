package ru.oopssre.gremlinamp;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.util.function.Consumer;

final class CityInput {
    private static final int RATE = 16000;
    private final Consumer<SpaceTracker.Street> receiver;
    private Capture current;

    CityInput(Consumer<SpaceTracker.Street> receiver) { this.receiver = receiver; }

    synchronized void start() {
        if (current != null) return;
        current = new Capture();
        new Thread(current, "GremlinAmp city input").start();
    }

    synchronized void stop() {
        Capture previous = current;
        current = null;
        if (previous != null && previous.record != null) {
            try { previous.record.stop(); } catch (IllegalStateException ignored) {}
        }
        receiver.accept(SpaceTracker.Street.QUIET);
    }

    private final class Capture implements Runnable {
        AudioRecord record;

        @Override public void run() {
            AudioRecord input = null;
            try {
                int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min < 0) return;
                input = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 4096));
                synchronized (CityInput.this) {
                    if (current != this || input.getState() != AudioRecord.STATE_INITIALIZED) return;
                    input.startRecording();
                    record = input;
                }
                short[] samples = new short[640];
                float low = 0, middle = 0;
                while (true) {
                    synchronized (CityInput.this) { if (current != this) break; }
                    int read = input.read(samples, 0, samples.length);
                    if (read <= 0) break;
                    float lows = 0, highs = 0, total = 0;
                    for (int i = 0; i < read; i++) {
                        float sample = samples[i] / 32768f;
                        low += (sample - low) * .068f;
                        middle += (sample - middle) * .48f;
                        lows += low * low;
                        highs += (sample - middle) * (sample - middle);
                        total += sample * sample;
                    }
                    float level = (float) Math.sqrt(total / read);
                    float brightness = total > 1e-6f ? Math.min(1, (float) Math.sqrt(highs / (highs + lows + .000001f))) : 0;
                    synchronized (CityInput.this) {
                        if (current == this) receiver.accept(new SpaceTracker.Street(
                                Math.min(1, level * 7), brightness, java.util.Arrays.copyOf(samples, read)));
                    }
                }
            } catch (RuntimeException ignored) {
                // The tracker continues with motion and the local clock when capture is unavailable.
            } finally {
                synchronized (CityInput.this) {
                    if (current == this) {
                        current = null;
                        receiver.accept(SpaceTracker.Street.QUIET);
                    }
                }
                if (input != null) input.release();
            }
        }
    }
}
