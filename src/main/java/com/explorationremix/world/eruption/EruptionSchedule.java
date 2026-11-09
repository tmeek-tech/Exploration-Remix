package com.explorationremix.world.eruption;

import java.util.Random;

/**
 * When the Caldera erupts (0.9.0, spec 5.2): each Caldera keeps its own clock ({@link Clock}), set by the lava's phase,
 * drawn again at every server start and never saved. Pure (no game types, seeded), so it is tested offline;
 * {@link Eruptions} asks it for every Caldera a player is near.
 *
 * <ul>
 *     <li>While the lava is calm, warns or drains, eruptions come {@code minMinutes} to {@code maxMinutes} apart; while it
 *     rises and stands full, {@code risingMinMinutes} to {@code risingMaxMinutes} apart. A minimum of 0 means none in
 *     those phases. Each gap is drawn from the start of the eruption before it.</li>
 *     <li>When the lava starts to rise, the next eruption comes within {@code wakeSeconds} unless one is due sooner: the
 *     Caldera wakes as the lava rises.</li>
 *     <li>Entering a phase with none, the clock stops ({@link #NEVER}); entering one with some from a stopped clock, the
 *     next comes after 0.3 to 1 of a gap. A Caldera first seen gets its first after 0.3 to 1 of a gap too, so a player
 *     arriving sees one within a few minutes, not at once.</li>
 *     <li>With the lava cycle off the phase is read as calm (the caller passes {@link #CALM}).</li>
 *     <li>The vent that erupts is drawn among the Caldera's vents near the players, a vent {@code ventNearBias} blocks
 *     from the nearest player half as often as one beside them ({@link #weight}; 0: all alike).</li>
 *     <li>An eruption that starts while the lava rises or stands full shows {@code risingScale} times as big
 *     ({@link #scale}).</li>
 * </ul>
 * In each pair of bounds the larger counts as at least the smaller.
 */
public final class EruptionSchedule {
    /** The lava cycle's phases, in {@code LavaCycle.Phase}'s order. */
    public static final int CALM = 0, WARNING = 1, RISE = 2, FULL = 3, DRAIN = 4;
    /** A stopped clock: no eruption is due. */
    public static final long NEVER = Long.MAX_VALUE;
    /** A Caldera first seen, or woken from a stopped clock, erupts after this share of a gap and up to a whole one. */
    public static final double FIRST_SHARE = 0.3;

    /** The config's numbers, read each tick (the config is read while the server runs). */
    public record Settings(double minMinutes, double maxMinutes, double risingMinMinutes, double risingMaxMinutes, double wakeSeconds,
                           double warnSeconds, double burstSeconds, double ventNearBias, double risingScale) {}

    /** One Caldera's clock: when its next eruption is due, and the phase it last saw. */
    public static final class Clock {
        long nextAt = NEVER;
        int phase;

        /** The tick its next eruption is due at, or {@link #NEVER}. */
        public long nextAt() { return nextAt; }
    }

    private final Random random;

    public EruptionSchedule(long seed) { this.random = new Random(seed); }

    /** True for the phases with the rising lava's gaps and scale: the rise and the full lava. */
    public static boolean rising(int phase) { return phase == RISE || phase == FULL; }

    /** Whether eruptions come at all in {@code phase}. */
    public static boolean allows(int phase, Settings s) { return rising(phase) ? s.risingMinMinutes() > 0 : s.minMinutes() > 0; }

    /** A gap between two eruptions in {@code phase}, in ticks, drawn; {@link #NEVER} when the phase has none. */
    public long gap(int phase, Settings s) {
        if (!allows(phase, s)) return NEVER;
        double lo = rising(phase) ? s.risingMinMinutes() : s.minMinutes();
        double hi = Math.max(lo, rising(phase) ? s.risingMaxMinutes() : s.maxMinutes());
        return Math.max(1, Math.round((lo + random.nextDouble() * (hi - lo)) * 1200));
    }

    /** The clock of a Caldera first seen at {@code now} in {@code phase}: its first eruption after 0.3 to 1 of a gap. */
    public Clock firstSeen(long now, int phase, Settings s) {
        Clock c = new Clock();
        c.phase = phase;
        c.nextAt = fromStopped(now, phase, s);
        return c;
    }

    /**
     * The lava is in {@code phase} at {@code now}: on a change, the clock stops in a phase with none, starts again from a
     * stopped one, and on entering the rise the next eruption comes within {@code wakeSeconds}. Called every tick, before
     * {@link #due}; nothing happens while the phase stays.
     */
    public void phase(Clock c, int phase, long now, Settings s) {
        if (phase == c.phase) return;
        c.phase = phase;
        if (!allows(phase, s)) {
            c.nextAt = NEVER;
            return;
        }
        if (c.nextAt == NEVER) c.nextAt = fromStopped(now, phase, s);
        if (phase == RISE) c.nextAt = Math.min(c.nextAt, now + Math.round(random.nextDouble() * s.wakeSeconds() * 20));
    }

    /** True when the Caldera's eruption is due at {@code now}. */
    public static boolean due(Clock c, long now) { return c.nextAt != NEVER && now >= c.nextAt; }

    /** An eruption started at {@code start} (by the clock or a command): the next is drawn from it, by the phase now. */
    public void started(Clock c, long start, Settings s) {
        long g = gap(c.phase, s);
        c.nextAt = g == NEVER ? NEVER : start + g;
    }

    /** An eruption that was due could not start (no vent of the Caldera could be read): it is tried again {@code ticks} on. */
    public static void later(Clock c, long now, int ticks) {
        if (c.nextAt != NEVER) c.nextAt = now + ticks;
    }

    private long fromStopped(long now, int phase, Settings s) {
        long g = gap(phase, s);
        return g == NEVER ? NEVER : now + Math.max(1, Math.round((FIRST_SHARE + random.nextDouble() * (1 - FIRST_SHARE)) * g));
    }

    /**
     * A vent's weight in the draw of the vent that erupts, {@code d} blocks (across) from the nearest player: 1 beside
     * them, a half at {@code bias} blocks, falling as the square of the distance beyond; all alike with {@code bias} 0.
     */
    public static double weight(double d, double bias) {
        if (bias <= 0) return 1;
        double q = d / bias;
        return 1 / (1 + q * q);
    }

    /** Draws one of the vents {@code d[i]} blocks from their nearest players by {@link #weight}; -1 when there are none. */
    public int pick(double[] d, double bias) {
        if (d.length == 0) return -1;
        double sum = 0;
        for (double v : d) sum += weight(v, bias);
        double u = random.nextDouble() * sum;
        for (int i = 0; i < d.length; i++) {
            u -= weight(d[i], bias);
            if (u < 0) return i;
        }
        return d.length - 1;
    }

    /** How much bigger an eruption starting in {@code phase} shows: {@code risingScale} in the rise and the full lava, else 1. */
    public static float scale(int phase, double risingScale) { return rising(phase) ? (float) risingScale : 1F; }

    /** An eruption's strength, 0.8 to 1.2. */
    public float drawStrength() { return 0.8F + random.nextFloat() * 0.4F; }

    /** An eruption's warning, in ticks: {@code warnSeconds}. */
    public static int pre(Settings s) { return (int) Math.round(Math.max(0, s.warnSeconds()) * 20); }

    /** An eruption's burst, in ticks: {@code burstSeconds} a quarter more or less. */
    public int drawBurst(Settings s) { return (int) Math.max(1, Math.round(s.burstSeconds() * 20 * (0.75 + random.nextDouble() * 0.5))); }

    /** A seed for an eruption's own draws (its bombs and its client's particles). */
    public long drawSeed() { return random.nextLong(); }
}
