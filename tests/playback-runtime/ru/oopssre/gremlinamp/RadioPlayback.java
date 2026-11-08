package ru.oopssre.gremlinamp;

import android.content.Context;
import android.os.Handler;
import java.util.function.BooleanSupplier;

final class RadioPlayback {
    interface Callback { void state(TransportState.Event event, String error); }
    static Callback callback;
    static int starts, stops, releases;
    RadioPlayback(Context context, Handler main, BooleanSupplier allowed, AudioEnergy energy) {}
    void play(String url, Callback result) { callback = result; starts++; }
    void stop() { stops++; }
    void release() { releases++; }
}
