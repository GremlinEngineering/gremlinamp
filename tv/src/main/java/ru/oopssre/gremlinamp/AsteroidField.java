package ru.oopssre.gremlinamp;

import java.util.Random;

final class AsteroidField {
    // Keep these capacities in sync with the fragment shader's uniform arrays.
    private static final int ROCK_COUNT = 5, SHOT_COUNT = 4;
    private static final float EDGE_X = .89f, EDGE_Y = .50f, SHOT_SPEED = 1.25f;
    private final Random random = new Random();
    private final Rock[] rocks = new Rock[ROCK_COUNT];
    private final Shot[] shots = new Shot[SHOT_COUNT];
    private float x, y, vx, vy, heading, reload, respawn, shield;
    private Frame frame;

    record Frame(float x, float y, float heading, float thrust, float[] rocks, float[] shots) {}

    AsteroidField() {
        for (int i = 0; i < 3; i++) spawn(i);
        publish(0);
    }

    Frame frame() { return frame; }

    void advance(float seconds, AudioEnergy.Levels levels) {
        // Keep a fast shot from stepping over a small fragment after a slow UI frame.
        int steps = (int) Math.ceil(seconds / .02f);
        float thrust = 0;
        for (int i = 0; i < steps; i++) thrust = step(seconds / steps, levels);
        publish(shield > 0 && (int) (shield * 12) % 2 == 0 ? -1 : thrust);
    }

    private float step(float seconds, AudioEnergy.Levels levels) {
        Rock target = null;
        float nearest = Float.MAX_VALUE;
        for (Rock rock : rocks) {
            if (rock == null) continue;
            rock.x = wrap(rock.x + rock.vx * seconds, EDGE_X);
            rock.y = wrap(rock.y + rock.vy * seconds, EDGE_Y);
            rock.angle += rock.spin * seconds;
            float distance = (float) Math.hypot(rock.x - x, rock.y - y);
            if (distance < nearest) {
                target = rock;
                nearest = distance;
            }
        }

        float turn = 0;
        boolean dodging = target != null && nearest < target.radius + .10f;
        if (target != null) {
            float lead = nearest / SHOT_SPEED;
            float aim = (float) Math.atan2(target.y - y + target.vy * lead, target.x - x + target.vx * lead);
            if (dodging) aim += 2.1f;
            turn = wrap(aim - heading, (float) Math.PI);
            float limit = 5f * seconds;
            heading = wrap(heading + Math.max(-limit, Math.min(limit, turn)), (float) Math.PI);
        }
        float thrust = dodging || Math.abs(turn) < .65f ? .75f : .27f;
        thrust *= .65f + .35f * levels.bass();
        float forwardX = (float) Math.cos(heading), forwardY = (float) Math.sin(heading);
        vx += (forwardX * thrust - vx * 2.1f) * seconds;
        vy += (forwardY * thrust - vy * 2.1f) * seconds;
        x = wrap(x + vx * seconds, EDGE_X);
        y = wrap(y + vy * seconds, EDGE_Y);

        shield = Math.max(0, shield - seconds);
        if (shield == 0 && target != null && nearest < target.radius + .018f) {
            x = y = vx = vy = 0;
            shield = 1.1f;
        }

        reload -= seconds;
        if (target != null && !dodging && Math.abs(turn) < .24f && reload <= 0) {
            for (int i = 0; i < shots.length; i++) {
                if (shots[i] != null) continue;
                shots[i] = new Shot(x + forwardX * .037f, y + forwardY * .037f, forwardX, forwardY);
                reload = .30f - .16f * levels.bass() - .07f * levels.treble();
                break;
            }
        }
        for (int i = 0; i < shots.length; i++) {
            Shot shot = shots[i];
            if (shot == null) continue;
            shot.x = wrap(shot.x + shot.dx * SHOT_SPEED * seconds, EDGE_X);
            shot.y = wrap(shot.y + shot.dy * SHOT_SPEED * seconds, EDGE_Y);
            shot.life -= seconds;
            if (shot.life <= 0 || hitRock(shot)) shots[i] = null;
        }

        respawn -= seconds;
        if (respawn <= 0) {
            int count = 0, free = -1;
            for (int i = 0; i < rocks.length; i++) {
                if (rocks[i] == null) free = i;
                else count++;
            }
            if (count < 3) {
                spawn(free);
                respawn = 1.2f;
            }
        }
        return thrust;
    }

    private boolean hitRock(Shot shot) {
        for (int i = 0; i < rocks.length; i++) {
            Rock rock = rocks[i];
            if (rock == null || Math.hypot(rock.x - shot.x, rock.y - shot.y) >= rock.radius) continue;
            rocks[i] = null;
            if (rock.radius > .05f) {
                float size = rock.radius * .62f;
                rocks[i] = new Rock(rock.x, rock.y, rock.vx - .13f * shot.dy, rock.vy + .13f * shot.dx, size, -1.5f);
                for (int j = 0; j < rocks.length; j++) {
                    if (rocks[j] != null) continue;
                    rocks[j] = new Rock(rock.x, rock.y, rock.vx + .13f * shot.dy, rock.vy - .13f * shot.dx, size, 1.8f);
                    break;
                }
            }
            return true;
        }
        return false;
    }

    private void spawn(int index) {
        int side = x < 0 ? 1 : -1;
        float edge = side * (.64f + .24f * random.nextFloat());
        rocks[index] = new Rock(edge, .84f * (random.nextFloat() - .5f),
                -side * (.095f + .09f * random.nextFloat()), .20f * (random.nextFloat() - .5f),
                .078f + .026f * random.nextFloat(), 1.5f * (random.nextFloat() - .5f));
    }

    private void publish(float thrust) {
        float[] rockData = new float[ROCK_COUNT * 4], shotData = new float[SHOT_COUNT * 4];
        for (int i = 0; i < rocks.length; i++) {
            Rock rock = rocks[i];
            if (rock == null) continue;
            int offset = i * 4;
            rockData[offset] = rock.x;
            rockData[offset + 1] = rock.y;
            rockData[offset + 2] = rock.radius;
            rockData[offset + 3] = rock.angle;
        }
        for (int i = 0; i < shots.length; i++) {
            Shot shot = shots[i];
            if (shot == null) continue;
            int offset = i * 4;
            shotData[offset] = shot.x;
            shotData[offset + 1] = shot.y;
            shotData[offset + 2] = shot.dx;
            shotData[offset + 3] = shot.dy;
        }
        frame = new Frame(x, y, heading, thrust, rockData, shotData);
    }

    private static float wrap(float value, float edge) {
        if (value > edge) return value - 2 * edge;
        if (value < -edge) return value + 2 * edge;
        return value;
    }

    private static final class Rock {
        float x, y, angle;
        final float vx, vy, radius, spin;

        Rock(float x, float y, float vx, float vy, float radius, float spin) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.radius = radius;
            this.spin = spin;
        }
    }

    private static final class Shot {
        float x, y, life = .73f;
        final float dx, dy;

        Shot(float x, float y, float dx, float dy) {
            this.x = x;
            this.y = y;
            this.dx = dx;
            this.dy = dy;
        }
    }
}
