package ru.oopssre.gremlinamp;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** Draws the same scene state on the CPU when the OpenGL renderer is unavailable. */
final class FractalSoftware {
    private static final int WIDTH = 256, HEIGHT = 144;
    private static final float INV_LOG_2 = (float) (1.0 / Math.log(2.0));
    private static final int PALETTE_SIZE = 512;
    private static final float[] COSINE = new float[PALETTE_SIZE];

    static {
        for (int i = 0; i < COSINE.length; i++) {
            COSINE[i] = (float) Math.cos(2 * Math.PI * i / COSINE.length);
        }
    }

    private final int[] pixels = new int[WIDTH * HEIGHT];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();

    Bitmap draw(FractalView.Frame frame) {
        if (frame.scene() >= 2) return drawFractal(frame);

        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(0xff020604);
        canvas.translate(WIDTH * .5f, HEIGHT * .5f);
        canvas.scale(HEIGHT, -HEIGHT);
        if (frame.asteroids() != null) drawAsteroids(canvas, frame.asteroids(), frame.levels());
        if (frame.tennis() != null) drawTennis(canvas, frame.tennis(), frame.levels());
        return bitmap;
    }

    private void drawAsteroids(Canvas canvas, AsteroidField.Frame field, AudioEnergy.Levels levels) {
        if (field.thrust() >= 0) {
            canvas.save();
            canvas.translate(field.x(), field.y());
            canvas.rotate((float) Math.toDegrees(field.heading()) - 90);
            outline.reset();
            outline.moveTo(0, .034f);
            outline.lineTo(-.023f, -.023f);
            outline.lineTo(0, -.013f);
            outline.lineTo(.023f, -.023f);
            outline.close();
            glowPath(canvas, 0xffbfffdc);
            float flame = .025f + .039f * field.thrust() + .023f * levels.bass();
            outline.reset();
            outline.moveTo(-.012f, -.020f);
            outline.lineTo(0, -flame);
            outline.lineTo(.012f, -.020f);
            glowPath(canvas, 0xff72dca1);
            canvas.restore();
        }

        float[] rocks = field.rocks();
        for (int offset = 0; offset < rocks.length; offset += 4) {
            float size = rocks[offset + 2];
            if (size <= 0) continue;
            outline.reset();
            for (int vertex = 0; vertex < 8; vertex++) {
                double angle = vertex * Math.PI / 4 - Math.PI;
                double heading = angle + rocks[offset + 3];
                float radius = size * (.81f + .17f * (float) Math.sin(angle * 3 + 1.7 * (offset / 4)));
                float x = rocks[offset] + radius * (float) Math.cos(heading);
                float y = rocks[offset + 1] + radius * (float) Math.sin(heading);
                if (vertex == 0) outline.moveTo(x, y);
                else outline.lineTo(x, y);
            }
            outline.close();
            glowPath(canvas, 0xff85bfaa);
        }

        float[] shots = field.shots();
        outline.reset();
        for (int offset = 0; offset < shots.length; offset += 4) {
            float dx = shots[offset + 2], dy = shots[offset + 3];
            if (dx * dx + dy * dy <= .5f) continue;
            outline.moveTo(shots[offset], shots[offset + 1]);
            outline.lineTo(shots[offset] - .022f * dx, shots[offset + 1] - .022f * dy);
        }
        glowPath(canvas, 0xffd9ffb0);
    }

    private void drawTennis(Canvas canvas, TennisRally.Frame rally, AudioEnergy.Levels levels) {
        outline.reset();
        outline.moveTo(-.65f, -.19f);
        outline.lineTo(.65f, -.19f);
        outline.moveTo(0, -.19f);
        outline.lineTo(0, -.09f);
        glowPath(canvas, 0xff48aa72);

        outline.reset();
        outline.moveTo(rally.previousX(), rally.previousY());
        outline.lineTo(rally.x(), rally.y());
        glowPath(canvas, 0xff52d78a);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x504cff98);
        canvas.drawCircle(rally.x(), rally.y(), .017f + .006f * levels.middle(), paint);
        paint.setColor(0xffc4ffdc);
        canvas.drawCircle(rally.x(), rally.y(), .006f, paint);
        if (rally.impact() > 0) {
            paint.setColor(0xffaeff76);
            paint.setAlpha((int) (160 * rally.impact()));
            canvas.drawCircle(rally.impactX(), rally.impactY(), .026f, paint);
        }
    }

    private void glowPath(Canvas canvas, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        paint.setAlpha(46);
        paint.setStrokeWidth(.021f);
        canvas.drawPath(outline, paint);
        paint.setAlpha(104);
        paint.setStrokeWidth(.010f);
        canvas.drawPath(outline, paint);
        paint.setAlpha(255);
        paint.setStrokeWidth(.004f);
        canvas.drawPath(outline, paint);
    }

    private Bitmap drawFractal(FractalView.Frame frame) {
        AudioEnergy.Levels spectrum = frame.levels();
        int scene = frame.scene() - 2;
        float time = frame.time();
        float angle = .16f * (float) Math.sin(time * .13f) + (scene == 2 ? time * .045f : 0);
        float cosine = (float) Math.cos(angle), sine = (float) Math.sin(angle);
        float juliaX = scene == 2 ? -.17f + .10f * (float) Math.sin(time * .15f)
                : -.74f + .11f * (float) Math.sin(time * .18f);
        float juliaY = scene == 2 ? .61f + .055f * (float) Math.cos(time * .23f)
                : .16f + .12f * (float) Math.cos(time * .21f) + .06f * spectrum.middle();
        float scale = scene == 2 ? 2.8f - .45f * spectrum.bass() : 2.75f - .42f * spectrum.bass();
        float zoom = 2.25f * (float) Math.exp(-2.75 * (.5 - .5 * Math.cos(time * .13f)))
                / (1 + .32f * spectrum.bass());
        float hue = time * .021f + spectrum.middle() * .09f + scene * .15f;
        float baseGlow = .23f + .18f * spectrum.treble() + .12f * spectrum.bass();
        float insideGlow = .34f + .14f * spectrum.treble();

        for (int row = 0; row < HEIGHT; row++) {
            float y = .5f - (row + .5f) / HEIGHT;
            for (int column = 0; column < WIDTH; column++) {
                float u = (column + .5f) / WIDTH - .5f;
                float x = u * WIDTH / HEIGHT;
                float px = cosine * x + sine * y, py = -sine * x + cosine * y;
                if (scene == 2) py = Math.abs(py);
                float cx = scene == 1 ? -.745f + zoom * px : juliaX;
                float cy = scene == 1 ? .112f + zoom * py : juliaY;
                float zx = scene == 1 ? 0 : scale * px;
                float zy = scene == 1 ? 0 : scale * py;
                float radius = 0, trap = 5;
                int steps = 0;
                for (; steps < 42; steps++) {
                    float nextX = zx * zx - zy * zy + cx;
                    zy = 2 * zx * zy + cy;
                    zx = nextX;
                    radius = zx * zx + zy * zy;
                    trap = Math.min(trap, Math.abs(zx * zy) + .14f * Math.abs(zx + zy));
                    if (radius > 64) break;
                }
                float edge = (float) Math.exp(-17 * trap);
                float band = steps == 42 ? 41.5f : steps + 1
                        - (float) Math.log(.5f * INV_LOG_2 * Math.log(radius)) * INV_LOG_2;
                float phase = band * .047f + edge * .16f + hue;
                float glow = steps == 42 ? .055f + edge * insideGlow : Math.min(.88f, baseGlow + .42f * edge);
                float vignette = 1 - .33f * Math.min(1, 2 * (u * u + y * y));
                pixels[row * WIDTH + column] = 0xff000000
                        | channel(.012f, .48f, phase, glow, vignette) << 16
                        | channel(.019f, .46f, phase + .22f, glow, vignette) << 8
                        | channel(.036f, .48f, phase + .47f, glow, vignette);
            }
        }
        return Bitmap.createBitmap(pixels, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    }

    private static int channel(float background, float amplitude, float phase, float glow, float vignette) {
        int index = (int) (phase * PALETTE_SIZE) & (PALETTE_SIZE - 1);
        return Math.min(255, Math.round(255 * (background + (.5f + amplitude * COSINE[index]) * glow) * vignette));
    }
}
