package ru.oopssre.gremlinamp;

import java.io.IOException;

interface PcmSource extends AutoCloseable {
    int sampleRate();
    int render(short[] pcm, int frames) throws IOException;
    default long duration() { return -1; }
    default boolean reportsDuration() { return false; }
    default boolean ended() { return false; }
    default long seek(long frames) throws IOException { return 0; }
    @Override default void close() throws IOException {}
}
