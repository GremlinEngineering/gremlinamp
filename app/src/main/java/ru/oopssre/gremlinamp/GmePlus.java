package ru.oopssre.gremlinamp;

import java.io.IOException;

final class GmePlus {
    static { System.loadLibrary("gremlinamp-gme-plus"); }

    static native long open(byte[] data, int subsong) throws IOException;
    static native void render(long handle, short[] output, int frames) throws IOException;
    static native void seek(long handle, int milliseconds) throws IOException;
    static native boolean ended(long handle);
    static native void close(long handle);
}
