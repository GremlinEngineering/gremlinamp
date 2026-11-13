package ru.oopssre.gremlinamp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class NativePsf implements AutoCloseable {
    static { System.loadLibrary("gremlinamp-psf"); }

    private final long handle;
    private final Path directory;
    private long position;

    NativePsf(String engine, List<byte[]> files, JSONObject descriptor) throws IOException {
        JSONArray paths = descriptor == null ? null : descriptor.optJSONArray("paths");
        if (paths == null || paths.length() != files.size()) throw new IOException("Неверные зависимости PSF");
        String[] names = new String[paths.length()];
        for (int i = 0; i < names.length; i++) names[i] = MusicContract.safePath(paths.optString(i));
        if (engine.equals("ssf")) {
            directory = Files.createTempDirectory("gremlinamp-ssf-");
            try {
                for (int i = 0; i < names.length; i++) {
                    Path path = directory.resolve(names[i]);
                    Files.createDirectories(path.getParent());
                    Files.write(path, files.get(i));
                }
                handle = open(3, null, null, directory.resolve(names[0]).toString());
            } catch (IOException | RuntimeException failure) {
                MusicArchive.remove(directory.toFile());
                throw failure;
            }
        } else {
            directory = null;
            int mode = engine.equals("dsf") ? 1 : engine.equals("usf") ? 2 : 0;
            handle = open(mode, names, files.toArray(new byte[0][]), null);
        }
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

    @Override public void close() {
        close(handle);
        if (directory != null) MusicArchive.remove(directory.toFile());
    }

    private static native long open(int mode, String[] names, byte[][] data, String path) throws IOException;
    private static native int render(long handle, short[] output, int frames) throws IOException;
    private static native void restart(long handle) throws IOException;
    private static native int rate(long handle);
    private static native long duration(long handle);
    private static native boolean ended(long handle);
    private static native void close(long handle);
}
