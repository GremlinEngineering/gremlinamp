package ru.oopssre.gremlinamp;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class SiteDecoder implements AutoCloseable {
    private static final String HOST = "gremlin-engine.invalid";
    private static final String ORIGIN = "https://" + HOST;
    private static final byte[] EMPTY = new byte[0];
    static final int RATE = 44100;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final List<File> files;
    private final String descriptor;
    private WebView view;
    private volatile boolean closed;
    private volatile Session session = new Session(1);
    private long position;

    private static final class Session {
        final int id;
        final CountDownLatch loaded = new CountDownLatch(1);
        final ArrayBlockingQueue<byte[]> pcm = new ArrayBlockingQueue<>(1);
        volatile String error;
        Session(int id) { this.id = id; }

        void fail(String reason) {
            error = reason;
            loaded.countDown();
            pcm.offer(EMPTY);
        }
    }

    SiteDecoder(Context context, List<File> files, JSONObject descriptor) throws IOException {
        this.context = context.getApplicationContext();
        this.files = files;
        this.descriptor = JSONObject.quote(descriptor.toString());
        try {
            main.post(this::boot);
            awaitReady(session);
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    private void boot() {
        if (closed) return;
        try {
            PackageInfo provider = WebView.getCurrentWebViewPackage();
            if (provider == null) {
                session.fail("Установите Android System WebView для музыкальных декодеров");
                return;
            }
            String major = provider.versionName == null ? "" : provider.versionName.split("\\.", 2)[0];
            if (major.matches("[0-9]{1,4}") && Integer.parseInt(major) < 80) {
                session.fail("Обновите Android System WebView для музыкальных декодеров");
                return;
            }
            view = new WebView(context);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setAllowFileAccess(false);
            view.getSettings().setAllowContentAccess(false);
            view.getSettings().setSafeBrowsingEnabled(false);
            view.addJavascriptInterface(new Bridge(), "AndroidAudio");
            view.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView ignored, WebResourceRequest request) {
                    return true;
                }
                @Override public WebResourceResponse shouldInterceptRequest(WebView ignored, WebResourceRequest request) {
                    return local(request.getUrl(), request.getMethod());
                }
                @Override public boolean onRenderProcessGone(WebView failed, RenderProcessGoneDetail detail) {
                    session.fail("WebView завершил процесс декодера");
                    failed.destroy();
                    if (view == failed) view = null;
                    return true;
                }
            });
            view.loadUrl(ORIGIN + "/host/index.html");
        } catch (RuntimeException e) {
            session.fail("Не удалось запустить Android System WebView · " + e.getMessage());
        }
    }

    private WebResourceResponse local(Uri url, String method) {
        String path = url.getPath();
        if ("GET".equals(method) && "https".equals(url.getScheme()) && HOST.equals(url.getHost())
                && url.getPort() == -1 && url.getUserInfo() == null && path != null) {
            try {
                if (path.startsWith("/input/")) {
                    int index = Integer.parseInt(path.substring(7));
                    if (index >= 0 && index < files.size()) return response("application/octet-stream", new FileInputStream(files.get(index)));
                } else if (path.startsWith("/site/") || path.startsWith("/host/")) {
                    String name = MusicContract.safePath(path.substring(6));
                    boolean engine = path.startsWith("/site/");
                    String asset = (engine ? "site-engine/" : "decoder-host/") + name;
                    String mime = name.endsWith(".js") ? "text/javascript" : name.endsWith(".html") ? "text/html"
                            : name.endsWith(".wasm") ? "application/wasm" : "application/octet-stream";
                    return response(mime, context.getAssets().open(asset));
                }
            } catch (IOException | IllegalArgumentException ignored) { }
        }
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                Collections.singletonMap("Cache-Control", "no-store"), new ByteArrayInputStream(EMPTY));
    }

    private static WebResourceResponse response(String mime, InputStream stream) {
        String charset = mime.startsWith("text/") ? "UTF-8" : null;
        return new WebResourceResponse(mime, charset, 200, "OK",
                Collections.singletonMap("Cache-Control", "no-store"), stream);
    }

    private void command(String script) {
        main.post(() -> {
            if (closed) return;
            if (view == null) session.fail("Android System WebView недоступен");
            else view.evaluateJavascript(script, null);
        });
    }

    private void load(Session active) { command("gremlinLoad(" + active.id + "," + descriptor + ")"); }

    private void awaitReady(Session active) throws IOException {
        try {
            if (!active.loaded.await(120, TimeUnit.SECONDS)) throw new IOException("Декодер не ответил. Проверьте Android System WebView");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Подготовка композиции отменена");
        }
        if (active.error != null) throw new IOException(active.error);
    }

    void render(short[] samples) throws IOException {
        Session active = session;
        command("gremlinRender(" + active.id + "," + samples.length / 2 + ")");
        byte[] pcm;
        try { pcm = active.pcm.poll(20, TimeUnit.SECONDS); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Воспроизведение отменено");
        }
        if (active.error != null) throw new IOException(active.error);
        if (pcm == null || pcm.length != samples.length * 2) throw new IOException("Декодер вернул неполный блок PCM");
        for (int i = 0; i < samples.length; i++) samples[i] = (short) ((pcm[i * 2] & 255) | pcm[i * 2 + 1] << 8);
        position += samples.length / 2;
    }

    void seek(int ms) throws IOException {
        long target = (long) ms * RATE / 1000;
        if (target < position) {
            Session next = new Session(session.id + 1);
            session = next;
            position = 0;
            load(next);
            awaitReady(next);
        }
        short[] block = new short[8192];
        while (position < target) {
            int samples = (int) Math.min(block.length, (target - position) * 2);
            render(samples == block.length ? block : new short[samples]);
        }
    }

    @Override public void close() {
        closed = true;
        session.fail("Воспроизведение завершено");
        main.post(() -> {
            if (view != null) {
                view.removeJavascriptInterface("AndroidAudio");
                view.stopLoading();
                view.destroy();
                view = null;
            }
        });
    }

    public final class Bridge {
        @JavascriptInterface public void ready() { if (!closed) load(session); }
        @JavascriptInterface public void loaded(int id) {
            Session active = session;
            if (!closed && active.id == id) active.loaded.countDown();
        }
        @JavascriptInterface public void error(int id, String reason) {
            Session active = session;
            if (!closed && active.id == id) active.fail(reason);
        }
        @JavascriptInterface public void pcm(int id, String encoded) {
            Session active = session;
            if (!closed && active.id == id && encoded != null && encoded.length() <= 100_000) {
                try { active.pcm.offer(Base64.getDecoder().decode(encoded)); }
                catch (IllegalArgumentException e) { active.fail("Декодер вернул повреждённый PCM"); }
            }
        }
    }
}
