package ru.oopssre.gremlinamp;

final class TennisRally {
    // Coordinates match the court in fractal.frag.
    private static final float BASELINE = .59f, COURT_Y = -.19f;
    private static final float BOUNCE = .72f;

    record Frame(float x, float y, float previousX, float previousY,
                 float impactX, float impactY, float impact) {}

    private int side = -1;
    private float progress, shot, bounceX = .33f, arc = .18f;
    private float fromHeight = .085f, toHeight = .085f;
    private Frame frame = new Frame(-BASELINE, COURT_Y + fromHeight,
            -BASELINE, COURT_Y + fromHeight, 0, 0, 0);

    Frame frame() { return frame; }

    void advance(float seconds, AudioEnergy.Levels levels) {
        float impact = Math.max(0, frame.impact() - seconds * 5);
        float impactX = frame.impactX(), impactY = frame.impactY();
        float speed = .94f + .58f * levels.bass() + .17f * levels.middle();
        float next = progress + seconds * speed;
        if (progress < BOUNCE && next >= BOUNCE) {
            impactX = -side * bounceX;
            impactY = COURT_Y;
            impact = .65f + .35f * levels.treble();
        }
        if (next >= 1) {
            side = -side;
            next -= 1;
            impactX = side * BASELINE;
            fromHeight = toHeight;
            impactY = COURT_Y + fromHeight;
            impact = 1;
            shot += 1.7f;
            float variation = (float) Math.sin(shot);
            bounceX = .33f + .043f * variation - .035f * levels.bass();
            arc = .175f + .025f * variation + .14f * levels.bass();
            toHeight = .085f + .025f * (float) Math.cos(shot);
        }
        progress = next;

        float x, y;
        if (progress < BOUNCE) {
            float u = progress / BOUNCE;
            x = side * (BASELINE - (BASELINE + bounceX) * u);
            y = COURT_Y + (1 - u) * fromHeight + 4 * arc * u * (1 - u);
        } else {
            float u = (progress - BOUNCE) / (1 - BOUNCE);
            x = -side * (bounceX + (BASELINE - bounceX) * u);
            y = COURT_Y + u * toHeight + .23f * u * (1 - u);
        }
        frame = new Frame(x, y, frame.x(), frame.y(), impactX, impactY, impact);
    }
}
