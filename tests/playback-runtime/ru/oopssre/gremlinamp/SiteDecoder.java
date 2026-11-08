package ru.oopssre.gremlinamp;
import android.content.Context;
import java.io.File;
import java.util.List;
import org.json.JSONObject;
final class SiteDecoder implements AutoCloseable {
    static final int RATE = 44100;
    SiteDecoder(Context c, List<File> files, JSONObject info) { throw new AssertionError("Requires WebView"); }
    void render(short[] pcm) { throw new AssertionError("Requires WebView"); }
    void seek(int ms) { throw new AssertionError("Requires WebView"); }
    public void close() {}
}
