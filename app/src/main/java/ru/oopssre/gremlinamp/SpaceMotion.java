package ru.oopssre.gremlinamp;

/** Estimates walking cadence from acceleration with a gravity baseline and hysteresis. */
final class SpaceMotion {
    private float gravity, activity, tilt;
    private long lastStep;
    private float cadence;
    private int intervals;
    private boolean armed;

    SpaceTracker.Motion sample(long time, float x, float y, float z) {
        float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
        if (!Float.isFinite(magnitude)) return SpaceTracker.Motion.STILL;
        if (gravity == 0) gravity = magnitude;
        gravity += (magnitude - gravity) * .052f;
        tilt += (Math.max(-1, Math.min(1, x / 8f)) - tilt) * .055f;
        float impulse = magnitude - gravity;
        activity += (Math.min(1, Math.abs(impulse) / 3.4f) - activity) * .075f;
        if (impulse < .18f) armed = true;
        if (armed && impulse > .72f) {
            armed = false;
            long interval = time - lastStep;
            if (lastStep == 0 || interval >= 260) {
                if (lastStep != 0 && interval <= 1000) {
                    float measured = 60_000f / interval;
                    cadence = intervals == 0 ? measured : cadence * .76f + measured * .24f;
                    intervals++;
                } else intervals = 0;
                lastStep = time;
            }
        }
        if (time - lastStep > 2400) intervals = 0;
        return new SpaceTracker.Motion(intervals >= 2 ? cadence : 0, Math.max(0, activity - .035f), tilt);
    }
}
