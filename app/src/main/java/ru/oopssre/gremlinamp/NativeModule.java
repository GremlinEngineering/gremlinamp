package ru.oopssre.gremlinamp;

import java.io.IOException;
import java.io.InterruptedIOException;

final class NativeModule {
    static { System.loadLibrary("gremlinamp-openmpt"); }

    static native long open(byte[] input, int subsong) throws IOException;
    static native int render(long handle, short[] output, int frames) throws IOException;
    static long seek(long handle, long frame) throws IOException {
        long duration = duration(handle);
        long target = Math.max(0, duration >= 0 ? Math.min(duration, frame) : frame);
        long cursor = position(handle);
        if (target < cursor || target - cursor > NativeVgm.RATE * 2L) cursor = seekFloor(handle, target);
        while (cursor < target) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Перемотка отменена");
            int rendered = render(handle, null, (int) Math.min(2048, target - cursor));
            if (rendered == 0) break;
            cursor += rendered;
        }
        return cursor;
    }
    private static native long position(long handle);
    private static native long seekFloor(long handle, long frame) throws IOException;
    static native long duration(long handle);
    static native boolean ended(long handle);
    static native void close(long handle);
}
