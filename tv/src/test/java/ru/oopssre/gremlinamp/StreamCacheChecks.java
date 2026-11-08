package ru.oopssre.gremlinamp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class StreamCacheChecks {
    private static int passed;
    private static void check(boolean result) {
        if (!result) throw new AssertionError("Check " + (passed + 1));
        passed++;
    }

    public static void main(String[] args) throws Exception {
        StreamCache cache = new StreamCache(30);
        byte[] current = new byte[13];
        try (StreamCache.Track playing = cache.track(true)) {
            check(playing.get("same", 13, () -> current) == current);
            try (StreamCache.Track upcoming = cache.track(false)) {
                check(upcoming.get("same", 13, () -> { throw new AssertionError("duplicate fetch"); }) == current);
                upcoming.get("next", 17, () -> new byte[17]);
                check(cache.usage().bytes() == 30);
                try {
                    upcoming.get("full", 1, () -> new byte[1]);
                    throw new AssertionError("overflow");
                } catch (StreamCache.Full expected) { check(cache.usage().bytes() == 30); }
            }
            check(cache.usage().pinned() == 13);
        }
        try (StreamCache.Track playing = cache.track(true); StreamCache.Track preload = cache.track(false)) {
            playing.get("next", 17, () -> { throw new AssertionError("cached file reloaded"); });
            preload.get("later", 13, () -> new byte[13]);
            check(cache.usage().bytes() == 30);
            cache.clear();
            check(cache.usage().bytes() == 30 && cache.usage().pinned() == 30);
        }
        check(cache.usage().bytes() == 0);
        try (StreamCache.Track error = cache.track(false)) {
            try {
                error.get("broken", 17, () -> { throw new IOException("broken transfer"); });
                throw new AssertionError("transfer error ignored");
            } catch (IOException expected) { check(cache.usage().bytes() == 0); }
            try {
                error.get("oversize", 31, () -> { throw new AssertionError("oversize requested"); });
                throw new AssertionError("oversize accepted");
            } catch (StreamCache.Full expected) { check(cache.usage().bytes() == 0); }
        }

        AudioEnergy energy = new AudioEnergy();
        int rate = 44100;
        short[] stereo = new short[4096];
        for (int i = 0; i < stereo.length / 2; i++) {
            short sample = (short) (Math.sin(i * 2 * Math.PI * 75 / rate) * 18000);
            stereo[i*2] = sample;
            stereo[i*2+1] = (short)-sample;
        }
        energy.pcm(stereo, stereo.length, rate);
        check(energy.levels().bass() > energy.levels().treble() * 2);
        energy.reset();
        check(energy.levels().equals(AudioEnergy.SILENCE));
        ByteBuffer data = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 2048; i++) {
            short sample = (short) (Math.sin(i * 2 * Math.PI * 6400 / rate) * 18000);
            data.putShort(sample).putShort(sample);
        }
        data.flip();
        energy.pcm(data, rate, 2);
        check(energy.levels().treble() > energy.levels().bass() * 4);
        check(data.position() == 0);
        System.out.println(passed + " cache and PCM checks passed");
    }
}
