package com.explorationremix.world.lava;

import java.util.Arrays;

/**
 * The Basalt Galleries' lava cycle as a pure function of time: which phase a tick of the cycle falls in and how high
 * the lava stands then. No Minecraft types, so the server, the client and the tests share it.
 *
 * <p>Taran's cycle (2026-11-01, "like Rain World's rain": the layer's own clock, read through warning signs): about 15
 * calm minutes to explore, rumbling warnings, a 2-minute rise, a minute at full while the land resets, and a 2-minute
 * drain. The lava climbs {@link #rise()} blocks over its table during the rise, one block at a time at an even pace,
 * holds there through the full phase and goes down the same way during the drain. Its height is a whole number of
 * blocks: height h means the lava fills every open block below the table's Y + h.
 */
public final class LavaCycle {
    /** The phases in order. A cycle starts calm and ends with the drain. */
    public enum Phase {
        CALM, WARNING, RISE, FULL, DRAIN;

        /** The lower-case name the commands use. */
        public String id() { return name().toLowerCase(java.util.Locale.ROOT); }

        /** The phase with that name, or null. */
        public static Phase byId(String id) {
            for (Phase p : values()) if (p.id().equals(id)) return p;
            return null;
        }
    }

    private static final Phase[] PHASES = Phase.values();

    /** Game ticks in a minute. */
    public static final int MINUTE = 1200;
    /** Taran's cycle: 20 minutes. */
    public static final int DEFAULT_LENGTH = 20 * MINUTE;
    /** The phases' shares of the default cycle, in minutes: 13.5 calm, 1.5 warning, 2 rise, 1 full, 2 drain. */
    public static final double[] DEFAULT_SHARES = {13.5, 1.5, 2.0, 1.0, 2.0};

    private final int[] lengths;
    private final int[] starts;
    private final int total;
    private final int rise;

    /**
     * A cycle with these phase lengths in ticks (one per {@link Phase}, any may be 0 but not all) in which the lava
     * climbs {@code rise} blocks.
     */
    public LavaCycle(int[] lengths, int rise) {
        if (lengths.length != PHASES.length) throw new IllegalArgumentException("one length per phase: " + Arrays.toString(lengths));
        if (rise < 0) throw new IllegalArgumentException("rise " + rise);
        this.lengths = lengths.clone();
        this.starts = new int[PHASES.length];
        long sum = 0;
        for (int i = 0; i < PHASES.length; i++) {
            if (lengths[i] < 0) throw new IllegalArgumentException("negative phase " + Arrays.toString(lengths));
            starts[i] = (int) sum;
            sum += lengths[i];
        }
        if (sum <= 0 || sum > Integer.MAX_VALUE / 2) throw new IllegalArgumentException("cycle length " + sum);
        this.total = (int) sum;
        this.rise = rise;
    }

    /** The default cycle: Taran's 20 minutes, with the given rise. */
    public static LavaCycle standard(int rise) { return new LavaCycle(scale(DEFAULT_LENGTH, DEFAULT_SHARES), rise); }

    /**
     * Phase lengths for a cycle of {@code total} ticks shared out in proportion to {@code shares} (one per phase,
     * not negative), rounded so they add up to exactly {@code total} (largest remainders first). Shares that are all
     * zero, or the wrong number of them, give the default shares.
     */
    public static int[] scale(int total, double[] shares) {
        if (total <= 0) throw new IllegalArgumentException("cycle length " + total);
        double sum = 0;
        boolean ok = shares != null && shares.length == PHASES.length;
        if (ok) for (double s : shares) {
            if (!(s >= 0) || Double.isInfinite(s)) ok = false;
            else sum += s;
        }
        if (!ok || sum <= 0) {
            shares = DEFAULT_SHARES;
            sum = 0;
            for (double s : shares) sum += s;
        }
        int[] out = new int[PHASES.length];
        double[] rest = new double[PHASES.length];
        int used = 0;
        for (int i = 0; i < out.length; i++) {
            double exact = total * shares[i] / sum;
            out[i] = (int) Math.floor(exact);
            rest[i] = exact - out[i];
            used += out[i];
        }
        while (used < total) {
            int best = 0;
            for (int i = 1; i < out.length; i++) if (rest[i] > rest[best]) best = i;
            out[best]++;
            rest[best] = -1;
            used++;
        }
        return out;
    }

    /** Ticks in one whole cycle. */
    public int length() { return total; }
    /** Ticks in a phase. */
    public int length(Phase p) { return lengths[p.ordinal()]; }
    /** The cycle tick a phase starts at. */
    public int start(Phase p) { return starts[p.ordinal()]; }
    /** How many blocks the lava climbs over its table. */
    public int rise() { return rise; }
    /** A copy of the phase lengths, in phase order. */
    public int[] lengths() { return lengths.clone(); }

    /** A cycle tick (any long: wrapped round the cycle) as a tick in 0 .. length - 1. */
    public int wrap(long t) { return (int) Math.floorMod(t, (long) total); }

    /**
     * Where the cycle is at tick t: the phase, how far into it, how long it is, and the lava's height then. Phases of
     * no length are never reported.
     */
    public State at(long t) {
        int w = wrap(t);
        int i = PHASES.length - 1;
        while (i > 0 && (lengths[i] == 0 || w < starts[i])) i--;
        // a tick before every non-empty phase cannot happen: w >= 0 = starts[0], and empty phases are skipped above;
        // if the first phases are empty, w lands in the first non-empty one
        while (lengths[i] == 0) i++;
        Phase p = PHASES[i];
        int into = w - starts[i];
        return new State(p, into, lengths[i], level(p, into, lengths[i]), w);
    }

    /** The lava's height over its table at cycle tick t, 0 to {@link #rise()}. */
    public int level(long t) { return at(t).level(); }

    /** The lava's height {@code into} ticks into phase p of length {@code len}. */
    private int level(Phase p, int into, int len) {
        return switch (p) {
            case CALM, WARNING -> 0;
            // one block every len / rise ticks: the first block after one step, the last just before full
            case RISE -> len == 0 ? rise : (int) ((long) rise * into / len);
            case FULL -> rise;
            case DRAIN -> len == 0 ? 0 : rise - (int) ((long) rise * into / len);
        };
    }

    /** The cycle tick {@code into} ticks into phase p (into is held inside the phase). */
    public int tickOf(Phase p, int into) {
        int len = lengths[p.ordinal()];
        int k = len == 0 ? 0 : Math.max(0, Math.min(len - 1, into));
        return starts[p.ordinal()] + k;
    }

    /**
     * The first cycle tick at which the lava stands {@code h} blocks over its table: the start of the calm for 0, the
     * start of the full phase for the whole rise, and the step of the rise in between (or the nearest one to it, when
     * the rise is too short to take every height).
     */
    public int tickForLevel(int h) {
        if (h <= 0) return tickOf(Phase.CALM, 0);
        int len = length(Phase.RISE);
        if (h >= rise || len == 0) return tickOf(Phase.FULL, 0);
        int into = (int) Math.min(len - 1L, ((long) h * len + rise - 1) / rise);
        // with fewer ticks than blocks the rise skips heights: step back to the nearest tick at or under h
        while (into > 0 && level(Phase.RISE, into, len) > h) into--;
        return tickOf(Phase.RISE, into);
    }

    /**
     * Where the cycle stands: its phase, ticks into it, the phase's length, the lava's height over its table and the
     * cycle tick (0 .. length - 1).
     */
    public record State(Phase phase, int into, int phaseLength, int level, int tick) {
        /** Ticks until the next phase. */
        public int left() { return phaseLength - into; }
        /** How far through its phase the cycle is, 0 to just under 1. */
        public double progress() { return phaseLength == 0 ? 0 : into / (double) phaseLength; }
    }
}
