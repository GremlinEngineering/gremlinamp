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
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import java.nio.ByteBuffer;

import static ru.oopssre.gremlinamp.TransportState.Event;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLException;
import okhttp3.OkHttpClient;

@androidx.annotation.OptIn(markerClass=UnstableApi.class)
final class RadioPlayback {
    interface Callback { void state(Event event, String error); }

    private static final long CONNECT_TIMEOUT_MS = 25_000;
    private final Context context;
    private final Handler main;
    private final BooleanSupplier networkAllowed;
    private final AudioEnergy energy;
    private ExoPlayer player;
    private Player.Listener listener;
    private AnalyticsListener loads;
    private Runnable monitor;
    private long bufferingSince;
    private String loadProblem = "";

    RadioPlayback(Context context, Handler main, BooleanSupplier networkAllowed, AudioEnergy energy) {
        this.context = context;
        this.main = main;
        this.networkAllowed = networkAllowed;
        this.energy = energy;
    }

    // All commands and listener callbacks run on the service's main looper.
    void play(String url, Callback callback) {
        stop();
        if (!networkAllowed.getAsBoolean()) {
            callback.state(Event.FAIL, "Радио ждёт разрешённую сеть");
            return;
        }
        try {
            // Each attempt reads the current system route and starts with a fresh connection pool.
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(7, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
                    .followSslRedirects(false).build();
            OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(client).setUserAgent("GremlinAmp");
            if (player == null) {
                ExoPlayer.Builder builder = energy == null ? new ExoPlayer.Builder(context)
                        : new ExoPlayer.Builder(context, visualizerRenderers());
                player = builder.setLooper(main.getLooper())
                        .setLoadControl(new DefaultLoadControl.Builder()
                                .setBufferDurationsMs(5_000, 16_000, 350, 900).build())
                        .build();
                // The service shares one audio focus request with game music.
                player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false);
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build());
                player.setWakeMode(C.WAKE_MODE_LOCAL);
            }
            listener = new Player.Listener() {
                @Override public void onEvents(Player source, Player.Events events) {
                    if (listener != this) return;
                    if (source.getPlaybackState() == Player.STATE_ENDED) {
                        fail(callback, "Станция завершила эфир");
                    } else if (source.isPlaying()) {
                        bufferingSince = 0;
                        loadProblem = "";
                        callback.state(Event.READY, "");
                    } else {
                        if (bufferingSince == 0) bufferingSince = SystemClock.elapsedRealtime();
                        callback.state(Event.BUFFER, "");
                    }
                }

                @Override public void onPlayerError(PlaybackException error) {
                    if (listener == this) fail(callback, streamProblem(error));
                }
            };
            loads = new AnalyticsListener() {
                @Override public void onLoadError(EventTime time, LoadEventInfo info, MediaLoadData data,
                                                  IOException error, boolean canceled) {
                    if (loads == this && !canceled) loadProblem = streamProblem(error);
                }
            };
            player.addListener(listener);
            player.addAnalyticsListener(loads);
            bufferingSince = SystemClock.elapsedRealtime();
            player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(MediaItem.fromUri(url)));
            player.prepare();
            player.play();
            monitor = new Runnable() {
                @Override public void run() {
                    if (monitor != this) return;
                    if (!networkAllowed.getAsBoolean()) {
                        fail(callback, "Радио ждёт разрешённую сеть");
                    } else if (bufferingSince > 0 && SystemClock.elapsedRealtime() - bufferingSince >= CONNECT_TIMEOUT_MS) {
                        fail(callback, loadProblem.isEmpty() ? "Поток не набрал буфер" : loadProblem);
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

    private static String streamProblem(Throwable error) {
        boolean opening = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException http) {
                if (http.responseCode == HttpURLConnection.HTTP_PROXY_AUTH) return "Прокси требует авторизацию";
                return "Сервер станции вернул HTTP " + http.responseCode;
            }
            if (cause instanceof HttpDataSource.HttpDataSourceException http) {
                opening = http.type == HttpDataSource.HttpDataSourceException.TYPE_OPEN;
            }
            if (cause instanceof IOException && cause.getMessage() != null) {
                String message = cause.getMessage();
                if (message.equals("Failed to authenticate with proxy")) return "Прокси требует авторизацию";
                if (message.startsWith("Unexpected response code for CONNECT:")) {
                    return "Прокси отклонил подключение к станции";
                }
            }
            if (cause instanceof ConnectException) return "Не удалось подключиться. Проверьте сеть и прокси";
            if (cause instanceof SocketTimeoutException) {
                return opening ? "Нет ответа при подключении. Проверьте сеть и прокси"
                        : "Поток перестал передавать данные";
            }
            if (cause instanceof SSLException) return "Не удалось установить защищённое соединение";
            if (cause instanceof UnknownHostException) return "Не найден адрес станции или прокси";
        }
        if (error instanceof PlaybackException playback
                && (playback.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                    || playback.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
                    || playback.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)) {
            return "Формат потока не поддерживается";
        }
        return "Не удалось воспроизвести поток";
    }

    private DefaultRenderersFactory visualizerRenderers() {
        return new DefaultRenderersFactory(context) {
            @Override protected AudioSink buildAudioSink(Context context, boolean floatOutput, boolean playbackParams) {
                TeeAudioProcessor tap = new TeeAudioProcessor(new TeeAudioProcessor.AudioBufferSink() {
                    private int rate, channels;
                    @Override public void flush(int rate, int channels, int encoding) {
                        this.rate = rate;
                        this.channels = encoding == C.ENCODING_PCM_16BIT ? channels : 0;
                        energy.reset();
                    }
                    @Override public void handleBuffer(ByteBuffer buffer) {
                        if (channels > 0) energy.pcm(buffer, rate, channels);
                    }
                });
                return new DefaultAudioSink.Builder(context).setAudioProcessors(new AudioProcessor[]{tap})
                        .setEnableAudioTrackPlaybackParams(playbackParams).build();
            }
        };
    }

    private void fail(Callback callback, String error) {
        stop();
        callback.state(Event.FAIL, error);
    }

    void stop() {
        if (monitor != null) main.removeCallbacks(monitor);
        monitor = null;
        bufferingSince = 0;
        loadProblem = "";
        Player.Listener previous = listener;
        listener = null;
        if (player != null) {
            if (previous != null) player.removeListener(previous);
            if (loads != null) player.removeAnalyticsListener(loads);
            player.stop();
            player.clearMediaItems();
        }
        loads = null;
    }

    void release() {
        stop();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
