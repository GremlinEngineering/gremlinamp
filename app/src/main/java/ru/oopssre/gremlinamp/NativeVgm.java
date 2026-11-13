package ru.oopssre.gremlinamp;

import java.io.File;
import java.io.IOException;

final class NativeVgm {
    static final int RATE = 44100;

    static { System.loadLibrary("gremlinamp"); }

    static long openFile(File file, int preroll) throws IOException {
        return open(AudioInput.read(file), preroll);
    }

    static long openBuffer(byte[] bytes, int preroll) throws IOException {
        return open(AudioInput.unpack(bytes), preroll);
    }

    private static native long open(byte[] data, int preroll) throws IOException;
    static native int render(long handle, short[] output, int frames) throws IOException;
    static native long seek(long handle, long frame) throws IOException;
    static native long duration(long handle);
    static native boolean ended(long handle);
    static native void close(long handle);
}
