package com.explorationremix.world.quake;

/**
 * How a falling block falls (0.9.0, spec 4.4.2): vanilla's own order for a block let go at rest, each tick taking 0.04
 * off its speed, moving by it, then keeping 98 % of it. A block falls 10 blocks in 24 ticks, 50 in 60, 100 in 92 and
 * 130 in 109; lava does not slow it (a falling block's tick never feels a fluid). Pure: the client
 * ({@code client.QuakeEffects}) times a ceiling block's dust, splash and landing by it, and the tests check it.
 */
public final class Fall {
    private Fall() {}

    /** Speed lost to gravity each tick, in blocks a tick. */
    public static final double GRAVITY = 0.04;
    /** Share of its speed a falling block keeps each tick. */
    public static final double DRAG = 0.98;

    /** How far a block let go at rest has fallen after {@code t} ticks, in blocks. */
    public static double y(int t) {
        double v = 0, y = 0;
        for (int i = 0; i < t; i++) {
            v -= GRAVITY;
            y -= v;
            v *= DRAG;
        }
        return y;
    }

    /** The tick a block let go at rest lands after falling {@code h} blocks: the first whose move takes it that far. */
    public static int ticks(double h) {
        if (h <= 0) return 0;
        double v = 0, y = 0;
        int t = 0;
        while (y < h) {
            v -= GRAVITY;
            y -= v;
            v *= DRAG;
            t++;
        }
        return t;
    }
}
