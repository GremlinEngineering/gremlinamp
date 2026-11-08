package ru.oopssre.gremlinamp;

import android.content.Context;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;

final class ChipDecoder implements AutoCloseable {
    private final long handle;
    private final WavPcm wav;
    private final SiteDecoder site;

    ChipDecoder(Context context, String engine, File file, int subsong,
                List<File> files, JSONObject descriptor) throws IOException {
        wav = engine.equals("wav") ? new WavPcm(file) : null;
        site = engine.equals("site") ? new SiteDecoder(context, files, descriptor) : null;
        handle = wav == null && site == null ? Gme.open(file.toString(), subsong) : 0;
    }

    ChipDecoder(Context context, String engine, Library.StreamInputs inputs) throws IOException {
        wav = engine.equals("site") ? null : new WavPcm(inputs.buffers().get(0));
        site = engine.equals("site") ? SiteDecoder.streaming(context, inputs.buffers(), inputs.descriptor()) : null;
        handle = 0;
    }

    int sampleRate() { return wav == null ? SiteDecoder.RATE : wav.rate; }

    void render(short[] samples) throws IOException {
        if (wav != null) wav.render(samples);
        else if (site != null) site.render(samples);
        else Gme.render(handle, samples);
    }

    void seek(int milliseconds) throws IOException {
        if (wav != null) wav.seek(milliseconds);
        else if (site != null) site.seek(milliseconds);
        else Gme.seek(handle, milliseconds);
    }

    boolean ended() {
        if (wav != null) return wav.ended();
        return site == null && Gme.ended(handle);
    }

    @Override public void close() throws IOException {
        if (wav != null) wav.close();
        else if (site != null) site.close();
        else Gme.close(handle);
    }
}
