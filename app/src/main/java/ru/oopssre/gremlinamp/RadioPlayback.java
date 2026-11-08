package ru.oopssre.gremlinamp;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.function.BooleanSupplier;

@androidx.annotation.OptIn(markerClass=UnstableApi.class)
final class RadioPlayback {
    interface Callback { void state(String state, String error); }

    private static final long CONNECT_TIMEOUT_MS = 20_000;
    private final Context context;
    private final Handler main;
    private final BooleanSupplier networkAllowed;
    private ExoPlayer player;
    private Player.Listener listener;
    private Runnable monitor;
    private long bufferingSince;

    RadioPlayback(Context context, Handler main, BooleanSupplier networkAllowed) {
        this.context = context;
        this.main = main;
        this.networkAllowed = networkAllowed;
    }

    // All commands and listener callbacks run on the service's main looper.
    void play(String url, Callback callback) {
        stop();
        if (!networkAllowed.getAsBoolean()) {
            callback.state("error", "Радио ждёт разрешённую сеть");
            return;
        }
        try {
            if (player == null) {
                DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                        .setUserAgent("GremlinAmp").setConnectTimeoutMs(7000).setReadTimeoutMs(8000);
                player = new ExoPlayer.Builder(context).setLooper(main.getLooper())
                        .setMediaSourceFactory(new DefaultMediaSourceFactory(http)).build();
                // The service shares one audio focus request with game music.
                player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false);
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build());
                player.setWakeMode(C.WAKE_MODE_LOCAL);
            }
            listener = new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (listener != this) return;
                    if (state == Player.STATE_BUFFERING) {
                        if (bufferingSince == 0) bufferingSince = SystemClock.elapsedRealtime();
                        callback.state("loading", "");
                    } else if (state == Player.STATE_READY) {
                        bufferingSince = 0;
                        callback.state("playing", "");
                    } else if (state == Player.STATE_ENDED) {
                        fail(callback, "Станция завершила эфир");
                    }
                }

                @Override public void onPlayerError(PlaybackException error) {
                    if (listener == this) fail(callback, "Не удалось подключиться к станции");
                }
            };
            player.addListener(listener);
            bufferingSince = SystemClock.elapsedRealtime();
            player.setMediaItem(MediaItem.fromUri(url));
            player.prepare();
            player.play();
            monitor = new Runnable() {
                @Override public void run() {
                    if (monitor != this) return;
                    if (!networkAllowed.getAsBoolean()) {
                        fail(callback, "Радио ждёт разрешённую сеть");
                    } else if (bufferingSince > 0 && SystemClock.elapsedRealtime() - bufferingSince >= CONNECT_TIMEOUT_MS) {
                        fail(callback, "Станция не ответила вовремя");
                    } else {
                        main.postDelayed(this, 1000);
                    }
                }
            };
            main.postDelayed(monitor, 1000);
        } catch (RuntimeException e) {
            fail(callback, "Не удалось запустить радио");
        }
    }

    private void fail(Callback callback, String error) {
        stop();
        callback.state("error", error);
    }

    void stop() {
        if (monitor != null) main.removeCallbacks(monitor);
        monitor = null;
        bufferingSince = 0;
        Player.Listener previous = listener;
        listener = null;
        if (player != null) {
            if (previous != null) player.removeListener(previous);
            player.stop();
            player.clearMediaItems();
        }
    }

    void release() {
        stop();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
