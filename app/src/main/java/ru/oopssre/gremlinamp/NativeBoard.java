package ru.oopssre.gremlinamp;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Locale;

final class NativeBoard implements AutoCloseable {
    static { System.loadLibrary("gremlinamp"); }

    private final long handle;
    private long position;

    NativeBoard(String board, byte[] bytes, JSONObject track) throws IOException {
        JSONObject playback = track.optJSONObject("playback");
        JSONObject atari = track.optJSONObject("atari");
        if (atari == null && playback != null) atari = playback.optJSONObject("atari");
        String region = atari == null ? "auto" : atari.optString("region", "auto").toLowerCase(Locale.ROOT);
        int regionCode = switch (region) {
            case "ntsc" -> 1;
            case "pal" -> 2;
            case "pal60" -> 3;
            case "secam" -> 4;
            default -> 0;
        };
        int subsong = track.optInt("subsong");
        BoardCapsule capsule = switch (board) {
            case "dcs", "g0", "k0" -> BoardCapsule.parse(board, bytes, subsong);
            default -> new BoardCapsule(bytes, new int[0]);
        };
        handle = open(board, capsule.data(), subsong, regionCode, capsule.config());
    }

    int rate() { return rate(handle); }
    long duration() { return duration(handle); }
    boolean ended() { return ended(handle); }

    int render(short[] samples, int frames) throws IOException {
        int count = render(handle, samples, frames);
        position += count;
        return count;
    }

    long seek(long frame) throws IOException {
        if (frame < position) {
            restart(handle);
            position = 0;
        }
        while (position < frame && !ended()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Перемотка отменена");
            render(null, (int) Math.min(2048, frame - position));
        }
        return position;
    }

    @Override public void close() { close(handle); }

    private static native long open(String board, byte[] bytes, int subsong, int region, int[] config) throws IOException;
    private static native int render(long handle, short[] pcm, int frames) throws IOException;
    private static native void restart(long handle) throws IOException;
    private static native int rate(long handle);
    private static native long duration(long handle);
    private static native boolean ended(long handle);
    private static native void close(long handle);
}
