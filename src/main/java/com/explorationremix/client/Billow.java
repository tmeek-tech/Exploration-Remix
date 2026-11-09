package com.explorationremix.client;

/**
 * The numbers of the clouds a blast or a crash throws up (0.9.0 eruption effects, round 2), kept apart from Minecraft so
 * a test can follow them, as {@link Fountain} is: how many small soft puffs make each cloud, how fast they are thrown
 * out and up, how the drag stops them, how big they start and swell, how long they live and how they thin away
 * ({@link CaveParticles.Dust} draws them). The first pass drew a crash's dust as a dozen puffs up to five blocks across
 * each that drifted three blocks and hung ten seconds: a hall-filling wall of panes. Now:
 * <ul>
 *     <li>{@link #ROCK_DUST}, a ceiling block's crash: a low billow rolling out along the floor, about 3 to 5 blocks
 *     across, that settles and is gone within 2 to 3 seconds, so several blocks of one big quake never fill a hall nor
 *     hide its lava pools;</li>
 *     <li>{@link #BLAST_ASH}, a lava bomb's blast: a dark cloud about 4 to 6 blocks across thrown up over the crater
 *     and rising 1 to 2.5 blocks out of it (the first try stayed low in the pit, thin and lit by the fire), gone within
 *     3 to 4 seconds.</li>
 * </ul>
 * Time is in particle ticks; speeds in blocks a tick. The particle's own order is the game's ({@code Particle.tick}):
 * its upward speed loses {@code 0.04 * gravity}, it moves, then it keeps {@link Kind#drag} of its speed.
 */
final class Billow {
    private Billow() {}

    /**
     * One kind of cloud: {@code puffs} of them, set out in a ring {@code ring} blocks round the spot, thrown out
     * {@code out0} to {@code out1} and up {@code up0} to {@code up1}, kept {@code drag} of their speed each tick, under
     * {@code gravity} of the particles' gravity (below 0 they rise); half-width {@code size0} to {@code size1} at birth,
     * swelling to {@code swell} times it; alive {@code life0} to {@code life1} ticks, at most {@code peak} opaque, thinning
     * away over the last {@code fade} share of their lives; tinted {@code grey}, never drawn darker than block light
     * {@code light}.
     */
    record Kind(int puffs, double ring, double out0, double out1, double up0, double up1, double drag, float gravity,
                float size0, float size1, float swell, int life0, int life1, float peak, float fade, float grey, int light) {}

    static final Kind ROCK_DUST = new Kind(26, 0.4, 0.06, 0.16, 0.01, 0.04, 0.85, 0.02F,
            0.35F, 0.55F, 1.6F, 40, 60, 0.75F, 0.6F, 0.62F, 10);
    static final Kind BLAST_ASH = new Kind(34, 0.7, 0.06, 0.13, 0.1, 0.22, 0.88, -0.03F,
            0.5F, 0.72F, 1.6F, 60, 80, 0.95F, 0.5F, 0.12F, 6);

    /** How far a puff thrown at {@code v} drifts before the drag {@code drag} stops it: {@code v / (1 - drag)} (it moves, then slows). */
    static double drift(double v, double drag) { return v / (1 - drag); }

    /**
     * The cloud's width across at its widest, blocks: two times the ring, the farthest drift and the largest puff's
     * swollen half-width (the half-width is the quad's; the puff in its sprite is a little smaller).
     */
    static double width(Kind k) { return 2 * (k.ring() + drift(k.out1(), k.drag()) + k.size1() * k.swell()); }

    /** The narrowest cloud: the ring, the shortest drift and the smallest puff, swollen. */
    static double narrowest(Kind k) { return 2 * (k.ring() + drift(k.out0(), k.drag()) + k.size0() * k.swell()); }

    /** A puff's half-width {@code u} (0 to 1) through its life from {@code s0}: swelling quickly at first, to {@link Kind#swell} times it. */
    static float size(Kind k, float s0, double u) {
        double v = Math.min(1, Math.max(0, u));
        return (float) (s0 * (1 + (k.swell() - 1) * (1 - (1 - v) * (1 - v))));
    }

    /** A puff's opacity at {@code age} of {@code life} ticks: up to {@link Kind#peak} over 2 ticks, thinning to none over the last {@link Kind#fade} of its life. */
    static float alpha(Kind k, int age, int life) {
        double u = Math.min(1, age / (double) Math.max(1, life));
        return (float) (k.peak() * Math.min(1, age / 2.0) * Math.min(1, (1 - u) / k.fade()));
    }

    /**
     * How far a puff starting still settles (gravity over 0) or rises (under 0) under its drag in {@code ticks}, blocks
     * (down negative), in the particle's order.
     */
    static double settle(Kind k, double vy, int ticks) {
        double y = 0, v = vy;
        for (int t = 0; t < ticks; t++) {
            v -= 0.04 * k.gravity();
            y += v;
            v *= k.drag();
        }
        return y;
    }
}
