package ru.oopssre.gremlinamp;

import java.io.File;
import java.io.IOException;

/** Platform codec coverage runs on Android. */
final class MediaAnalysisPcm implements PcmSource {
    MediaAnalysisPcm(File file) throws IOException { throw new IOException("Android media codec required"); }
    public int sampleRate() { return 44_100; }
    public int render(short[] pcm, int frames) { return 0; }
}
