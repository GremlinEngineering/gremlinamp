package ru.oopssre.gremlinamp;

import java.io.IOException;
import java.io.InterruptedIOException;

final class NativeStream implements AutoCloseable {
    static { System.loadLibrary("gremlinamp-streams"); }

    private final long handle;
    private long position;

    NativeStream(String engine, byte[] bytes, int subsong) throws IOException {
        handle = open(engine.equals("xa"), bytes, subsong);
    }

    int rate() { return rate(handle); }
    long duration() { return duration(handle); }
    boolean ended() { return ended(handle); }

    int render(short[] output, int frames) throws IOException {
        int count = render(handle, output, frames);
        position += count;
        return count;
    }

    long seek(long frame) throws IOException {
        long actual = seek(handle, frame);
        if (actual >= 0) {
            position = actual;
            return actual;
        }
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

    private static native long open(boolean xa, byte[] bytes, int subsong) throws IOException;
    private static native int render(long handle, short[] output, int frames) throws IOException;
    private static native long seek(long handle, long frame);
    private static native void restart(long handle) throws IOException;
    private static native int rate(long handle);
    private static native long duration(long handle);
    private static native boolean ended(long handle);
    private static native void close(long handle);
}
