package ru.oopssre.gremlinamp;
final class Gme {
 static {System.loadLibrary("gremlinamp");}
 static native long open(String path,int subsong) throws java.io.IOException;
 static native void render(long handle,short[] samples) throws java.io.IOException;
 static native void seek(long handle,int milliseconds) throws java.io.IOException;
 static native boolean ended(long handle);
 static native long[] measure(String path,int subsong,int limitMs) throws java.io.IOException;
 static native void close(long handle);
}
