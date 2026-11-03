package com.explorationremix.client;

import java.util.function.DoubleSupplier;

/**
 * One puff of the volcanic smoke ({@link CaveParticles.VolcanicSmoke}), kept apart from Minecraft so a test can follow
 * it: how it rises off the lava, fast at first and slowing as it cools, drifts on a slow draught, swells and fades,
 * spreads sideways when it meets rock overhead (a vault, a hanging mass of columns, the underside of a ledge) and lingers
 * there, rising on if it slides out from under; and how brightly the lava under it lights it.
 *
 * <p>Picture 1 ("Epic Basalt Galleries in Volcanic Smoke") has smoke at every height: billows off the lava river, around
 * the hanging columns and gathered under the vault. A plume puffs one of these every second from one spot
 * ({@link SmokeColumns}), all with about the same rise, so they follow one another up as a column from the lava to 40 to
 * 100 blocks over it, or to the rock above, where they gather.
 */
final class SmokePuff {
    /** Rock at a point (the particle asks the level, a test its own cave). Fluids and air are not rock. */
    interface Rock {
        boolean at(double x, double y, double z);
    }

    /** How far over its middle a puff meets the rock that stops it: its swollen top presses against the rock. */
    static final double HEAD = 1.5;
    /** How quickly the rise follows its speed for the puff's age, a share a tick (the lag is under two seconds). */
    static final double RESPONSE = 0.03;
    /** The sideways push a puff gets each time it meets rock overhead, blocks a tick: it spreads about 4 blocks. */
    static final double SPREAD = 0.04;
    /** The share of the sideways speed kept each tick, so a push dies away over a few seconds. */
    static final double DRAG = 0.99;
    /** The rise a plume's puffs make over their lives, blocks. */
    static final double REACH_MIN = 40, REACH_MAX = 100;

    final double base, reach;
    final int life;
    /** The quad's half-width at birth and at the end: 2 to 2.6 blocks across, then 7 to 9 (the puff in its sprite is about
     * four fifths of the quad). */
    final float size0, size1;
    final float peak;
    private final double v0, draughtX, draughtZ;
    double x, y, z, vx, vy, vz;
    int age;
    /** Rock overhead last tick; how many times the puff has met rock overhead. */
    boolean capped;
    int spreads;

    /**
     * A puff at (x, y, z) over lava whose surface is at {@code base}, rising about {@code reach} blocks over its life
     * (600 ticks for 40, 1200 for 100, longer for the tall plumes that rise out of the Galleries through an opening).
     */
    SmokePuff(double x, double y, double z, double base, double reach, DoubleSupplier r) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.base = base;
        this.reach = reach;
        this.life = lifeFor(reach) + (int) (r.getAsDouble() * 60);
        // v(u) = v0 (1 - u)^0.6 over u = age / life sums to v0 life / 1.6 = reach
        this.v0 = 1.6 * reach / life;
        // it leaves the lava slowly and gathers speed, so a plume's puffs crowd together over the lava
        this.vy = 0.15 * v0;
        this.size0 = 1.0F + 0.3F * (float) r.getAsDouble();
        this.size1 = 3.4F + 1.0F * (float) r.getAsDouble();
        this.peak = 0.4F + 0.08F * (float) r.getAsDouble();
        double a = r.getAsDouble() * 2 * Math.PI, d = 0.002 + 0.006 * r.getAsDouble();
        this.draughtX = d * Math.cos(a);
        this.draughtZ = d * Math.sin(a);
        this.vx = draughtX;
        this.vz = draughtZ;
    }

    /** A puff's life for its rise: 600 ticks for a rise of 40 blocks, 1200 for 100, and on at that rate. */
    static int lifeFor(double reach) { return (int) Math.round(600 + 10 * Math.max(0, reach - REACH_MIN)); }

    /** The rise speed a puff tends to at {@code age}, blocks a tick: quickest low over the lava, slowing as it cools. */
    double lift(int age) { return v0 * Math.pow(1 - Math.min(1.0, age / (double) life), 0.6); }

    /**
     * One tick: rise toward this age's speed, drift, and meet the rock. Under rock it rises no further and is pushed
     * sideways (once each time it meets it), so it spreads along the underside and lingers there until it fades or
     * slides out from under, when it rises on. It never moves sideways into rock: it turns back.
     */
    void tick(Rock rock, DoubleSupplier r) {
        age++;
        vy += (lift(age) - vy) * RESPONSE;
        vx = vx * DRAG + draughtX * (1 - DRAG) + (r.getAsDouble() - 0.5) * 0.002;
        vz = vz * DRAG + draughtZ * (1 - DRAG) + (r.getAsDouble() - 0.5) * 0.002;
        boolean over = rock.at(x, y + HEAD, z);
        if (over) {
            if (!capped) {
                spreads++;
                double a = r.getAsDouble() * 2 * Math.PI;
                vx += SPREAD * Math.cos(a);
                vz += SPREAD * Math.sin(a);
            }
            vy = Math.min(vy, 0) * 0.5;
        }
        capped = over;
        if (rock.at(x + vx, y, z)) vx *= -0.5;
        else x += vx;
        if (rock.at(x, y, z + vz)) vz *= -0.5;
        else z += vz;
        y += vy;
    }

    /** The quad's half-width now: it swells fast off the lava, then more slowly. */
    float size() {
        double u = Math.min(1.0, age / (double) life);
        return (float) (size0 + (size1 - size0) * (1 - (1 - u) * (1 - u)));
    }

    /** The puff's opacity now: it thickens over its first twentieth and thins away over its last two fifths. */
    float alpha() {
        double u = Math.min(1.0, age / (double) life);
        return (float) (peak * Math.min(1, u / 0.05) * Math.min(1, (1 - u) / 0.4));
    }

    /**
     * The block light the lava under a puff gives it, {@code up} blocks over the lava's surface: 15 at the lava, 9 a
     * hundred blocks up and over. Smoke is lit from below; at its own position a puff in mid-air or under a vault often
     * has little light, and without this it would draw near black (under the shader, block light is warm, so the smoke
     * reads orange as in picture 1).
     */
    static int lightFromBelow(double up) {
        return (int) Math.round(15 - 6 * Math.max(0, Math.min(1, up / 100.0)));
    }
}
