package ru.oopssre.gremlinamp;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Activity observes on the main thread. One worker owns the update files. */
final class TvUpdates {
    public static final class Provider extends FileProvider {}

    enum Stage { IDLE, CHECKING, DOWNLOADING, CANCELLING }
    record State(Stage stage, TvRelease offer, boolean ready, String detail, int percent) {}
    private record Manifest(String body, String etag) {}
    private static final class Download {
        final TvRelease release;
        volatile boolean cancelled;
        Download(TvRelease release) { this.release = release; }
    }

    private static TvUpdates instance;

    static TvUpdates get(Context context) {
        if (instance == null) instance = new TvUpdates(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Path directory, partial, apk;
    private final long installedBuild;
    private final String installedVersion;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable morning = this::schedule;
    private Stage stage = Stage.CHECKING;
    private Manifest manifest;
    private TvRelease offer;
    private Download download;
    private Consumer<State> observer;
    private boolean ready, installerActive;
    private String detail = "Загрузка сведений об обновлении…";
    private int percent = -1;

    private TvUpdates(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("tv_updates", Context.MODE_PRIVATE);
        directory = context.getFilesDir().toPath().resolve("tv-updates");
        partial = directory.resolve("download.part");
        apk = directory.resolve("update.apk");
        PackageInfo installed;
        try {
            installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException(error);
        }
        installedBuild = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
        installedVersion = installed.versionName;
        io.execute(this::restore);
    }

    long installedBuild() { return installedBuild; }
    String installedVersion() { return installedVersion; }
    State state() { return new State(stage, offer, ready, detail, percent); }

    void observe(Consumer<State> observer) {
        this.observer = observer;
        main.removeCallbacks(morning);
        if (observer != null) {
            notifyObserver();
            schedule();
        }
    }

    private void restore() {
        Manifest cached = null;
        TvRelease saved = null;
        boolean downloaded = false;
        try {
            Files.deleteIfExists(partial);
            String body = prefs.getString("manifest", "");
            if (!body.isEmpty()) {
                saved = TvRelease.parse(body);
                cached = new Manifest(body, prefs.getString("etag", ""));
            }
            downloaded = saved != null && saved.build() > installedBuild && Files.isRegularFile(apk)
                    && saved.apkUrl().equals(prefs.getString("downloaded_url", ""));
        } catch (IOException ignored) {
            // A damaged local cache can be rebuilt by checking the site.
        }
        if (!downloaded) discardApk();
        Manifest previous = cached;
        TvRelease release = saved;
        boolean keep = downloaded;
        main.post(() -> {
            manifest = previous;
            offer = release != null && release.build() > installedBuild ? release : null;
            ready = keep;
            idle(release != null && offer == null ? "Установлена актуальная версия" : "");
        });
    }

    private void schedule() {
        if (observer == null) return;
        long now = System.currentTimeMillis();
        String day = TvRelease.checkDay(now);
        if (stage == Stage.IDLE && !day.isEmpty() && !day.equals(prefs.getString("checked_day", ""))) check();
        main.removeCallbacks(morning);
        main.postDelayed(morning, SyncRules.untilMorning(now));
    }

    void check() {
        if (stage != Stage.IDLE || installerActive) return;
        String day = TvRelease.checkDay(System.currentTimeMillis());
        if (!day.isEmpty()) prefs.edit().putString("checked_day", day).apply();
        Manifest previous = manifest;
        TvRelease oldOffer = offer;
        stage = Stage.CHECKING;
        detail = "Проверяем обновление…";
        notifyObserver();
        io.execute(() -> {
            try {
                Manifest current = fetch(previous);
                TvRelease release = TvRelease.parse(current.body());
                TvRelease next = release.build() > installedBuild ? release : null;
                boolean same = oldOffer != null && next != null && oldOffer.build() == next.build()
                        && oldOffer.apkUrl().equals(next.apkUrl());
                if (!same) discardApk();
                prefs.edit().putString("manifest", current.body()).putString("etag", current.etag()).apply();
                main.post(() -> {
                    manifest = current;
                    offer = next;
                    ready &= same;
                    idle(next == null ? "Установлена актуальная версия" : "");
                });
            } catch (IOException error) {
                main.post(() -> idle(problem(error)));
            }
        });
    }

    void download() {
        if (stage != Stage.IDLE || offer == null || ready) return;
        Download job = new Download(offer);
        download = job;
        stage = Stage.DOWNLOADING;
        detail = "Скачиваем обновление…";
        notifyObserver();
        io.execute(() -> {
            try {
                downloadApk(job);
                main.post(() -> finish(job, ""));
            } catch (IOException error) {
                main.post(() -> finish(job, problem(error)));
            }
        });
    }

    void cancel() {
        if (stage != Stage.DOWNLOADING) return;
        download.cancelled = true;
        stage = Stage.CANCELLING;
        detail = "Отменяем скачивание…";
        notifyObserver();
    }

    private void finish(Download job, String error) {
        download = null;
        ready = error.isEmpty() && !job.cancelled;
        if (ready) {
            prefs.edit().putString("downloaded_url", job.release.apkUrl()).apply();
        } else {
            io.execute(this::discardApk);
        }
        idle(job.cancelled ? "Скачивание отменено" : error);
    }

    Uri installerUri() {
        if (stage != Stage.IDLE || !ready) return null;
        if (!Files.isRegularFile(apk)) {
            ready = false;
            idle("Файл обновления недоступен. Скачай его ещё раз.");
            return null;
        }
        return FileProvider.getUriForFile(context, context.getPackageName() + ".updates", apk.toFile());
    }

    void installerActive(boolean active) {
        installerActive = active;
    }

    private void discardApk() {
        prefs.edit().remove("downloaded_url").apply();
        try {
            Files.deleteIfExists(apk);
            Files.deleteIfExists(partial);
        } catch (IOException ignored) {
            // The next download replaces leftovers in the private update directory.
        }
    }

    private void idle(String message) {
        stage = Stage.IDLE;
        detail = message;
        percent = -1;
        notifyObserver();
        schedule();
    }

    private void notifyObserver() {
        if (observer != null) observer.accept(state());
    }

    private static HttpURLConnection connect(String url) throws IOException {
        HttpURLConnection connection = NetworkPolicy.openHttp(new URL(url));
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(12_000);
        connection.setUseCaches(false);
        return connection;
    }

    private static Manifest fetch(Manifest previous) throws IOException {
        HttpURLConnection connection = connect(TvRelease.MANIFEST);
        connection.setRequestProperty("Cache-Control", "no-cache");
        if (previous != null && !previous.etag().isEmpty()) {
            connection.setRequestProperty("If-None-Match", previous.etag());
        }
        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED && previous != null) return previous;
            if (status == HttpURLConnection.HTTP_NOT_FOUND) throw new IOException("Канал обновлений ТВ пока недоступен");
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("Обновление недоступно · HTTP " + status);
            try (var in = connection.getInputStream(); var out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 128 * 1024) throw new IOException("Слишком большой манифест обновления");
                    out.write(buffer, 0, count);
                }
                String etag = connection.getHeaderField("ETag");
                return new Manifest(new String(out.toByteArray(), StandardCharsets.UTF_8), etag == null ? "" : etag);
            }
        } finally {
            connection.disconnect();
        }
    }

    private void downloadApk(Download job) throws IOException {
        if (job.cancelled) return;
        Files.createDirectories(directory);
        HttpURLConnection connection = connect(job.release.apkUrl());
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("APK недоступен · HTTP " + status);
            long total = connection.getContentLengthLong();
            try (var in = connection.getInputStream(); var out = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                long received = 0, lastUpdate = 0;
                int count;
                while (!job.cancelled && (count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                    received += count;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastUpdate >= 300) {
                        lastUpdate = now;
                        int progress = total > 0 ? (int) Math.min(100, 100.0 * received / total) : -1;
                        String statusText = total > 0 ? "Скачиваем обновление"
                                : String.format(Locale.ROOT, "Скачиваем обновление · %.1f МБ", received / 1_000_000d);
                        main.post(() -> {
                            if (download == job && stage == Stage.DOWNLOADING) {
                                detail = statusText;
                                percent = progress;
                                notifyObserver();
                            }
                        });
                    }
                }
            }
            if (!job.cancelled) {
                Files.move(partial, apk, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String problem(IOException error) {
        if (error instanceof SocketTimeoutException) return "Сайт не ответил вовремя. Повтори попытку.";
        if (error instanceof java.net.UnknownHostException) return "Нет связи с сайтом. Проверь сеть.";
        return error.getMessage() == null ? "Не удалось получить обновление" : error.getMessage();
    }
}
