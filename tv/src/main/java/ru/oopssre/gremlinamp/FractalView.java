package ru.oopssre.gremlinamp;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.util.Arrays;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class FractalView extends GLSurfaceView {
    static final String[] SCENES = {"Астероидное поле", "Tennis for Two",
            "Орбиты Жюлиа", "Погружение Мандельброта", "Зеркальная Жюлиа"};
    private static final String[] DESCRIPTIONS = {
            "Векторная заставка · корабль и осколки под музыку",
            "Запись розыгрыша · осциллограф 1958 года",
            "Фрактал · спирали и орбиты",
            "Фрактал · приближение к границе",
            "Фрактал · симметричные узоры"
    };
    private final Scene renderer;
    private long previousTick;
    private float phase;
    private int scene;

    FractalView(Context context, Runnable unavailable) throws IOException {
        super(context);
        setEGLContextClientVersion(2);
        String fragment;
        try (InputStream input = context.getAssets().open("fractal.frag");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[2048];
            for (int n; (n = input.read(chunk)) >= 0;) output.write(chunk, 0, n);
            fragment = output.toString(StandardCharsets.UTF_8.name());
        }
        renderer = new Scene(fragment, new TennisReplay(context), () -> post(unavailable));
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        getHolder().setFixedSize(640, 360);
    }

    void selectScene(int value) {
        scene = value;
        phase = 0;
        previousTick = 0;
        frame(false, AudioEnergy.SILENCE);
    }

    String sceneName() { return SCENES[scene]; }
    String sceneDescription() { return DESCRIPTIONS[scene]; }

    void frame(boolean moving, AudioEnergy.Levels levels) {
        long now = SystemClock.uptimeMillis();
        if (moving && previousTick != 0) phase += Math.min(now - previousTick, 80) / 1000f;
        previousTick = now;
        Frame frame = new Frame(phase, scene, levels);
        if (frame.equals(renderer.frame)) return;
        renderer.frame = frame;
        requestRender();
    }

    private record Frame(float time, int scene, AudioEnergy.Levels levels) {}

    // Ball coordinates sampled from the Laboratory's recorded SPICE match.
    private static final class TennisReplay {
        private final float duration;
        private final float[] times, x, y;

        TennisReplay(Context context) throws IOException {
            try (DataInputStream data = new DataInputStream(context.getAssets().open("tennis-replay.bin"))) {
                duration = data.readFloat();
                int count = data.readInt();
                if (duration <= 0 || count < 2 || count > 10_000) throw new IOException("Неверная запись матча");
                times = new float[count];
                x = new float[count];
                y = new float[count];
                for (int i = 0; i < count; i++) {
                    times[i] = data.readFloat();
                    x[i] = data.readFloat() * .0073f;
                    y[i] = data.readFloat() * .0042f - .19f;
                }
            }
        }

        int index(float seconds) {
            int found = Arrays.binarySearch(times, seconds % duration);
            return found >= 0 ? found : Math.max(0, -found - 2);
        }

        void draw(int location, float seconds) {
            int current = index(seconds);
            int previous = index(seconds - .07f + duration);
            // The replay contains resets between serves. Avoid drawing a line across them.
            if (Math.abs(x[current] - x[previous]) > .17f || Math.abs(y[current] - y[previous]) > .13f) {
                previous = current;
            }
            GLES20.glUniform4f(location, x[current], y[current], x[previous], y[previous]);
        }
    }

    private static final class Scene implements GLSurfaceView.Renderer {
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
        private final String fragment;
        private final Runnable unavailable;
        private final TennisReplay replay;
        private int program, position, resolution, spectrum, time, scene, ball, width, height;
        volatile Frame frame = new Frame(0, 0, AudioEnergy.SILENCE);

        Scene(String fragment, TennisReplay replay, Runnable unavailable) {
            this.fragment = fragment;
            this.replay = replay;
            this.unavailable = unavailable;
            vertices.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        }

        @Override public void onSurfaceCreated(GL10 ignored, EGLConfig config) {
            GLES20.glClearColor(.035f, .05f, .09f, 1);
            try {
                int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
                int pixel = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
                program = GLES20.glCreateProgram();
                GLES20.glAttachShader(program, vertex);
                GLES20.glAttachShader(program, pixel);
                GLES20.glLinkProgram(program);
                GLES20.glDeleteShader(vertex);
                GLES20.glDeleteShader(pixel);
                int[] linked = new int[1];
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
                if (linked[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
                position = GLES20.glGetAttribLocation(program, "position");
                resolution = GLES20.glGetUniformLocation(program, "resolution");
                spectrum = GLES20.glGetUniformLocation(program, "spectrum");
                time = GLES20.glGetUniformLocation(program, "time");
                scene = GLES20.glGetUniformLocation(program, "scene");
                ball = GLES20.glGetUniformLocation(program, "ball");
            } catch (RuntimeException e) {
                android.util.Log.e("GremlinAmpTV", "Fractal shader failed", e);
                if (program != 0) GLES20.glDeleteProgram(program);
                program = 0;
                unavailable.run();
            }
        }

        private static int compile(int type, String source) {
            int shader = GLES20.glCreateShader(type);
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

        @Override public void onSurfaceChanged(GL10 ignored, int width, int height) {
            this.width = width;
            this.height = height;
            GLES20.glViewport(0, 0, width, height);
        }

        @Override public void onDrawFrame(GL10 ignored) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (program == 0 || height == 0) return;
            Frame current = frame;
            GLES20.glUseProgram(program);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices);
            GLES20.glEnableVertexAttribArray(position);
            GLES20.glUniform2f(resolution, width, height);
            GLES20.glUniform1f(time, current.time());
            GLES20.glUniform1i(scene, current.scene());
            if (current.scene() == 1) replay.draw(ball, current.time());
            GLES20.glUniform3f(spectrum, current.levels().bass(), current.levels().middle(), current.levels().treble());
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
    }
}
