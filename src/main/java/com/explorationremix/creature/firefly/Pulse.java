package com.explorationremix.creature.firefly;

/**
 * How bright one blue firefly is. In flight every firefly glows steadily at {@link #GLOW}. Settled on the rock each
 * one breathes: its light swells and fades over a part of the cycle (its own share, {@link #MIN_SWELL} to
 * {@link #MAX_SWELL}), then rests dim at {@link #LOW} until its next breath.
 *
 * <p>The fireflies of one swarm breathe at the swarm's own rate, one breath every {@link #MIN_PERIOD} to
 * {@link #MAX_PERIOD} ticks (two to four seconds; swarms differ), each at its own point of the cycle. Those points are
 * spread round the cycle by the golden ratio, so however many fireflies a swarm has, their breaths fall evenly apart
 * and stay that way: a settled swarm never flashes together, its light wanders through the patch. (Fireflies each
 * breathing at a rate of its own would drift into step now and then, and the whole patch would flare at once.)
 *
 * <p>Everything comes from the swarm's seed and the firefly's own number, so every client sees the same firefly
 * breathe the same way for as long as it lives. Plain numbers, no game classes, so it can be tested on its own.
 */
public final class Pulse {
    /** The shortest and longest breath, in ticks. */
    public static final double MIN_PERIOD = 40.0, MAX_PERIOD = 80.0;
    /** The steady glow in flight, and the dim rest between breaths (fractions of full brightness). */
    public static final double GLOW = 1.0, LOW = 0.12;
    /** The share of a cycle a firefly's light swells and fades for; the rest is its dim pause. */
    public static final double MIN_SWELL = 0.5, MAX_SWELL = 0.66;
    /** How far a firefly's point in the cycle may stray from its even share (a fraction of the cycle). */
    static final double PHASE_JITTER = 0.08;
    private static final double GOLDEN = 0.6180339887498949;

    private Pulse() {}

    /** The length of the swarm's breath, in ticks: in [{@link #MIN_PERIOD}, {@link #MAX_PERIOD}). */
    public static double period(long seed) {
        return MIN_PERIOD + (MAX_PERIOD - MIN_PERIOD) * unit(seed, -1, 1);
    }

    /** Where in the swarm's cycle this firefly's breath comes, in [0, 1). */
    public static double phase(long seed, int id) {
        double p = id * GOLDEN + PHASE_JITTER * (unit(seed, id, 2) - 0.5) + unit(seed, -1, 3);
        return p - Math.floor(p);
    }

    /** The share of the cycle this firefly's light swells for: in [{@link #MIN_SWELL}, {@link #MAX_SWELL}). */
    public static double swell(long seed, int id) {
        return MIN_SWELL + (MAX_SWELL - MIN_SWELL) * unit(seed, id, 4);
    }

    /** One breath: 0 at the start of the cycle, up to 1 and back to 0 by {@code swell}, then 0 until the cycle ends. */
    public static double breath(double u, double swell) {
        u -= Math.floor(u);
        if (u >= swell) return 0.0;
        double s = Math.sin(Math.PI * u / swell);
        return s * s;
    }

    /** A settled firefly's brightness at this time (ticks, fractional for the frames between ticks): {@link #LOW} to 1. */
    public static double settled(long seed, int id, double ticks) {
        return LOW + (1.0 - LOW) * breath(ticks / period(seed) + phase(seed, id), swell(seed, id));
    }

    /**
     * A firefly's brightness, {@code settledness} of the way from its steady flying glow (0) to its settled breathing
     * (1): it eases from one to the other as it lands or takes off, never snapping.
     */
    public static double brightness(long seed, int id, double ticks, double settledness) {
        double s = Math.max(0.0, Math.min(1.0, settledness));
        return GLOW + (settled(seed, id, ticks) - GLOW) * s;
    }

    /** A number in [0, 1) that depends only on the seed, the firefly and which number of it is wanted. */
    static double unit(long seed, int id, int salt) {
        long z = seed * 0x9E3779B97F4A7C15L + id * 0xBF58476D1CE4E5B9L + salt * 0x94D049BB133111EBL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }
}
