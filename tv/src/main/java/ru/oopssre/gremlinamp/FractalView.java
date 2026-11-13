package ru.oopssre.gremlinamp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.GLES32;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.TextureView;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

final class FractalView extends FrameLayout implements TextureView.SurfaceTextureListener {
    private static final String TAG = "GremlinAmpTV";
    private static boolean preferSoftware;
    static final String[] SCENES = {"Астероидное поле", "Tennis for Two",
            "Орбиты Жюлиа", "Погружение Мандельброта", "Зеркальная Жюлиа"};
    private static final String[] DESCRIPTIONS = {
            "Векторный бой · корабль уворачивается и сбивает астероиды",
            "Живой розыгрыш · темп и свечение под музыку",
            "Фрактал · спирали и орбиты",
            "Фрактал · приближение к границе",
            "Фрактал · симметричные узоры"
    };
    private final String fragment;
    private final Runnable unavailable;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect bitmapBounds = new Rect();
    private volatile Frame latestFrame;
    private volatile Bitmap softwareFrame;
    private TextureView output;
    private SurfaceTexture surfaceTexture;
    private RenderWorker worker;
    private RenderWorker softwareWorker;
    private boolean softwareMode;
    private boolean resumed;
    private AsteroidField asteroids = new AsteroidField();
    private TennisRally tennis = new TennisRally();
    private long previousTick;
    private float phase;
    private int scene;

    FractalView(Context context, Runnable unavailable) {
        super(context);
        this.unavailable = unavailable;
        fragment = preferSoftware ? null : loadFragment(context);
        latestFrame = new Frame(0, 0, AudioEnergy.SILENCE, asteroids.frame(), null);
        setWillNotDraw(false);
        if (fragment == null) {
            useSoftware();
        } else {
            output = new TextureView(context);
            output.setSurfaceTextureListener(this);
            addView(output, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
    }

    private static String loadFragment(Context context) {
        try (InputStream input = context.getAssets().open("fractal.frag");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[2048];
            for (int n; (n = input.read(chunk)) >= 0;) output.write(chunk, 0, n);
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (IOException error) {
            Log.w(TAG, "Cannot read visualization shader", error);
            return null;
        }
    }

    void onResume() {
        resumed = true;
        if (isAttachedToWindow() && !isHardwareAccelerated()) {
            useSoftware();
        }
        if (softwareMode) {
            resumeSoftware();
        } else {
            resumeWorker();
        }
    }

    void onPause() {
        resumed = false;
        if (worker != null) worker.pause();
        if (softwareWorker != null) softwareWorker.pause();
    }

    private void resumeWorker() {
        if (!resumed || softwareMode || surfaceTexture == null) return;
        if (worker == null) worker = new RenderWorker(surfaceTexture);
        worker.resume();
    }

    private void useSoftware() {
        if (softwareMode) return;
        preferSoftware = true;
        softwareMode = true;
        if (output != null) {
            removeView(output);
            output = null;
        }
        invalidate();
        if (resumed) resumeSoftware();
    }

    private void resumeSoftware() {
        if (softwareWorker == null) softwareWorker = new RenderWorker(null);
        if (!softwareWorker.running) softwareWorker.resume();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (resumed && softwareMode) resumeSoftware();
    }

    @Override protected void onDetachedFromWindow() {
        if (softwareWorker != null) {
            softwareWorker.destroy();
            softwareWorker = null;
        }
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!softwareMode) return;
        Bitmap bitmap = softwareFrame;
        if (bitmap == null) {
            canvas.drawColor(0xff080f12);
        } else {
            bitmapBounds.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(bitmap, null, bitmapBounds, bitmapPaint);
        }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        surfaceTexture = surface;
        surface.setDefaultBufferSize(640, 360);
        resumeWorker();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        surface.setDefaultBufferSize(640, 360);
        if (resumed && worker != null) worker.requestRender();
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        surfaceTexture = null;
        RenderWorker previous = worker;
        worker = null;
        if (previous == null) return true;
        previous.destroy();
        return false;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    void selectScene(int value) {
        scene = value;
        if (scene == 0) asteroids = new AsteroidField();
        if (scene == 1) tennis = new TennisRally();
        phase = 0;
        previousTick = 0;
        frame(false, AudioEnergy.SILENCE);
    }

    String sceneName() { return SCENES[scene]; }
    String sceneDescription() { return DESCRIPTIONS[scene]; }

    void frame(boolean moving, AudioEnergy.Levels levels) {
        long now = SystemClock.uptimeMillis();
        if (moving && previousTick != 0 && now > previousTick) {
            float seconds = Math.min(now - previousTick, 80) / 1000f;
            phase += seconds;
            if (scene == 0) asteroids.advance(seconds, levels);
            if (scene == 1) tennis.advance(seconds, levels);
        }
        previousTick = now;
        Frame frame = new Frame(phase, scene, levels,
                scene == 0 ? asteroids.frame() : null, scene == 1 ? tennis.frame() : null);
        if (frame.equals(latestFrame)) return;
        latestFrame = frame;
        RenderWorker active = softwareMode ? softwareWorker : worker;
        if (resumed && active != null) active.requestRender();
    }

    record Frame(float time, int scene, AudioEnergy.Levels levels,
                 AsteroidField.Frame asteroids, TennisRally.Frame tennis) {}

    private final class RenderWorker {
        private final SurfaceTexture texture;
        private final HandlerThread thread;
        private final Handler handler;
        private final FractalEgl egl;
        private final Scene renderer;
        private final FractalSoftware software;
        private final Runnable draw = this::draw;
        private volatile boolean running;
        private volatile int generation;
        private int failedGeneration = -1;

        RenderWorker(SurfaceTexture texture) {
            this.texture = texture;
            egl = texture != null ? new FractalEgl() : null;
            renderer = texture != null ? new Scene() : null;
            software = texture == null ? new FractalSoftware() : null;
            thread = new HandlerThread(texture != null ? "GremlinAmpTV-GL" : "GremlinAmpTV-Canvas");
            thread.start();
            handler = new Handler(thread.getLooper());
        }

        void resume() {
            generation++;
            running = true;
            requestRender();
        }

        void requestRender() {
            handler.removeCallbacks(draw);
            handler.post(draw);
        }

        void pause() {
            running = false;
            generation++;
            handler.removeCallbacks(draw);
            if (egl != null) handler.post(this::releaseEgl);
        }

        void destroy() {
            pause();
            // Returning false from the TextureView callback transfers release to this thread.
            handler.post(() -> {
                try {
                    if (texture != null) texture.release();
                } finally {
                    thread.quitSafely();
                }
            });
        }

        private void draw() {
            int currentGeneration = generation;
            if (!running || failedGeneration == currentGeneration) return;
            try {
                if (software != null) {
                    Bitmap image = software.draw(latestFrame);
                    if (running && generation == currentGeneration) {
                        softwareFrame = image;
                        postInvalidate();
                    }
                    return;
                }
                int result = drawFrame();
                if (result == EGL14.EGL_CONTEXT_LOST && running && generation == currentGeneration) {
                    releaseEgl();
                    result = drawFrame();
                }
                if (result != EGL14.EGL_SUCCESS) throw FractalEgl.failure("eglSwapBuffers", result);
            } catch (RuntimeException | LinkageError error) {
                if (egl != null) releaseEgl();
                if (!running || generation != currentGeneration) return;
                failedGeneration = currentGeneration;
                Log.w(TAG, texture != null ? "OpenGL visualization failed, switching to Canvas"
                        : "Software visualization failed", error);
                post(() -> {
                    RenderWorker active = softwareMode ? softwareWorker : worker;
                    if (!resumed || active != this || generation != currentGeneration) return;
                    if (texture != null) useSoftware();
                    else unavailable.run();
                });
            }
        }

        private int drawFrame() {
            if (!egl.isOpen()) {
                egl.open(texture);
                renderer.initialize(fragment, egl.width(), egl.height());
            }
            renderer.draw(latestFrame);
            int result = egl.swap();
            if (result != EGL14.EGL_SUCCESS) return result;
            int error = GLES20.glGetError();
            if (error == GLES32.GL_CONTEXT_LOST) return EGL14.EGL_CONTEXT_LOST;
            if (error != GLES20.GL_NO_ERROR) {
                throw new IllegalStateException("Drawing failed (GL 0x" + Integer.toHexString(error) + ")");
            }
            return EGL14.EGL_SUCCESS;
        }

        private void releaseEgl() {
            try {
                egl.close();
            } catch (RuntimeException error) {
                Log.w(TAG, "Visualization EGL cleanup failed", error);
            }
        }
    }

    private static final class Scene {
        private static final String VERTEX = """
                attribute vec2 position;
                varying vec2 uv;
                void main() {
                    uv = position * .5 + .5;
                    gl_Position = vec4(position, 0.0, 1.0);
                }
                """;
        private final FloatBuffer vertices = ByteBuffer.allocateDirect(8 * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        private int program, position, resolution, spectrum, time, scene, ball, impact, ship, rocks, shots, width, height;

        Scene() {
            vertices.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        }

        void initialize(String fragment, int width, int height) {
            this.width = width;
            this.height = height;
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(.035f, .05f, .09f, 1);
            int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
            int pixel = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
            program = GLES20.glCreateProgram();
            if (program == 0) throw new IllegalStateException("glCreateProgram failed");
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, pixel);
            GLES20.glLinkProgram(program);
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(pixel);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
            position = GLES20.glGetAttribLocation(program, "position");
            if (position < 0) throw new IllegalStateException("Missing vertex position attribute");
            resolution = GLES20.glGetUniformLocation(program, "resolution");
            spectrum = GLES20.glGetUniformLocation(program, "spectrum");
            time = GLES20.glGetUniformLocation(program, "time");
            scene = GLES20.glGetUniformLocation(program, "scene");
            ball = GLES20.glGetUniformLocation(program, "ball");
            impact = GLES20.glGetUniformLocation(program, "impact");
            ship = GLES20.glGetUniformLocation(program, "ship");
            rocks = GLES20.glGetUniformLocation(program, "rocks[0]");
            shots = GLES20.glGetUniformLocation(program, "shots[0]");
        }

        private static int compile(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            if (shader == 0) throw new IllegalStateException("glCreateShader failed");
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new IllegalStateException(log);
            }
            return shader;
        }

        void draw(Frame current) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices);
            GLES20.glEnableVertexAttribArray(position);
            GLES20.glUniform2f(resolution, width, height);
            GLES20.glUniform1f(time, current.time());
            GLES20.glUniform1i(scene, current.scene());
            if (current.asteroids() != null) {
                AsteroidField.Frame field = current.asteroids();
                GLES20.glUniform4f(ship, field.x(), field.y(), field.heading(), field.thrust());
                GLES20.glUniform4fv(rocks, field.rocks().length / 4, field.rocks(), 0);
                GLES20.glUniform4fv(shots, field.shots().length / 4, field.shots(), 0);
            }
            if (current.tennis() != null) {
                TennisRally.Frame rally = current.tennis();
                GLES20.glUniform4f(ball, rally.x(), rally.y(), rally.previousX(), rally.previousY());
                GLES20.glUniform3f(impact, rally.impactX(), rally.impactY(), rally.impact());
            }
            GLES20.glUniform3f(spectrum, current.levels().bass(), current.levels().middle(), current.levels().treble());
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
    }
}
