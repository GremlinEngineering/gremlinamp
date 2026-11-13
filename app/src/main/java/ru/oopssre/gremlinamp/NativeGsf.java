package ru.oopssre.gremlinamp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;

final class NativeGsf implements AutoCloseable {
    static { System.loadLibrary("gremlinamp-gsf"); }

    private final long handle;
    private long position;

    NativeGsf(List<byte[]> files, JSONObject descriptor) throws IOException {
        JSONArray paths = descriptor.optJSONArray("paths");
        if (paths == null || paths.length() != files.size()) throw new IOException("Неверные зависимости GSF");
        String[] names = new String[paths.length()];
        for (int i = 0; i < names.length; i++) names[i] = paths.optString(i);
        handle = open(names, files.toArray(new byte[0][]));
    }

    int render(short[] samples, int frames) throws IOException {
        render(handle, samples, frames);
        position += frames;
        return frames;
    }

    long seek(long frame) throws IOException {
        if (frame < position) {
            restart(handle);
            position = 0;
        }
        while (position < frame) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Перемотка отменена");
            render(null, (int) Math.min(2048, frame - position));
        }
        return position;
    }

    @Override public void close() { close(handle); }

    private static native long open(String[] names, byte[][] data) throws IOException;
    private static native void render(long handle, short[] output, int frames) throws IOException;
    private static native void restart(long handle);
    private static native void close(long handle);
}
