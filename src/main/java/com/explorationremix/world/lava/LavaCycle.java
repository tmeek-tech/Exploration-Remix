package com.explorationremix.world.lava;

import java.util.Arrays;

/**
 * The Basalt Galleries' lava cycle as a pure function of time: which phase a tick of the cycle falls in and how high
 * the lava stands then. No Minecraft types, so the server, the client and the tests share it.
 *
 * <p>Taran's cycle (2026-11-01, "like Rain World's rain": the layer's own clock, read through warning signs): about 15
 * calm minutes to explore, rumbling warnings, a 2-minute rise, a minute at full while the land resets, then the drain.
 *
 * <p><b>0.21.6</b> (Taran, 2026-11-24: "a smoothly rising plane instead of real blocks ... rise in stages with breaks
 * in-between, and fall all at once"): the lava is a plane ({@link LavaPlane}) whose height over its table,
 * {@link #height}, is a smooth number of blocks, not a whole one. The rise is {@link #stages()} climbs of an equal share
 * of the {@link #rise()} blocks, each easing in and out, with breaks between them that take {@link #breakShare()} of
 * the rise; the lava holds through the full phase and then falls back to its table in one smooth drop over the drain,
 * which is short (15 seconds of the 20 minutes). {@link State#level()} is still a whole number of blocks: the rows of
 * the flood band whose middle is under the plane's surface, which the quakes and the terrain reset read as "under the
 * sea". Before 0.21.6 the lava was real blocks that climbed one row at a time at an even pace over the whole rise.
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
    /**
     * The phases' shares of the default cycle, in minutes: 15.25 calm, 1.5 warning, 2 rise (four climbs and three
     * breaks), 1 full, a 15-second drain (0.21.6; until then 13.5 calm and a 2-minute drain).
     */
    public static final double[] DEFAULT_SHARES = {15.25, 1.5, 2.0, 1.0, 0.25};
    /** Taran's pick (2026-11-24): four climbs of 10 blocks, about 15 seconds each, with 20-second breaks between. */
    public static final int DEFAULT_STAGES = 4;
    /** The share of the rise spent in the breaks between its climbs: 60 of the default rise's 120 seconds. */
    public static final double DEFAULT_BREAK_SHARE = 0.5;
    /** The most climbs a rise may have, and the largest share of it the breaks may take (a climb always has some time). */
    public static final int MAX_STAGES = 20;
    public static final double MAX_BREAK_SHARE = 0.9;
    /**
     * How far under the top of its block the surface of a lava source with nothing over it sits (vanilla draws it at
     * 8/9 of the block): the plane's surface is the lava table's Y less this, plus its height, so it starts level with
     * the table's own lava and at full stands where the top of the old block sea did.
     */
    public static final double SURFACE_DROP = 1.0 / 9.0;

    private final int[] lengths;
    private final int[] starts;
    private final int total;
    private final int rise;
    private final int stages;
    private final double breakShare;

    /**
     * A cycle with these phase lengths in ticks (one per {@link Phase}, any may be 0 but not all) in which the lava
     * climbs {@code rise} blocks in Taran's four climbs.
     */
    public LavaCycle(int[] lengths, int rise) { this(lengths, rise, DEFAULT_STAGES, DEFAULT_BREAK_SHARE); }

    /**
     * A cycle with these phase lengths in ticks in which the lava climbs {@code rise} blocks in {@code stages} climbs
     * (held between 1 and {@link #MAX_STAGES}) with breaks between them taking {@code breakShare} of the rise (held
     * between 0 and {@link #MAX_BREAK_SHARE}; ignored with one climb, which takes the whole rise).
     */
    public LavaCycle(int[] lengths, int rise, int stages, double breakShare) {
        if (lengths.length != PHASES.length) throw new IllegalArgumentException("one length per phase: " + Arrays.toString(lengths));
        if (rise < 0) throw new IllegalArgumentException("rise " + rise);
        this.stages = Math.max(1, Math.min(MAX_STAGES, stages));
        this.breakShare = this.stages == 1 || !(breakShare > 0) ? 0 : Math.min(MAX_BREAK_SHARE, breakShare);
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

    /** The default cycle: Taran's 20 minutes and four climbs, with the given rise. */
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
    /** How many climbs the rise is made of. */
    public int stages() { return stages; }
    /** The share of the rise spent in the breaks between its climbs (0 with one climb). */
    public double breakShare() { return breakShare; }
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
        int i = phaseIndex(w);
        Phase p = PHASES[i];
        int into = w - starts[i];
        double h = height(p, into, lengths[i]);
        return new State(p, into, lengths[i], rows(h), w, h);
    }

    /** The phase (its index) a wrapped tick falls in, never one of no length. */
    private int phaseIndex(int w) {
        int i = PHASES.length - 1;
        while (i > 0 && (lengths[i] == 0 || w < starts[i])) i--;
        // a tick before every non-empty phase cannot happen: w >= 0 = starts[0], and empty phases are skipped above;
        // if the first phases are empty, w lands in the first non-empty one
        while (lengths[i] == 0) i++;
        return i;
    }

    /** The rows of the flood band under the lava at cycle tick t, 0 to {@link #rise()} (see {@link State#level()}). */
    public int level(long t) { return at(t).level(); }

    /**
     * The lava's height over its table, in blocks, at cycle tick {@code t} (a fraction of a tick between two ticks, as
     * a client draws it): 0 to {@link #rise()}, smooth.
     */
    public double height(double t) {
        double w = t - Math.floor(t / total) * total;
        if (!(w >= 0 && w < total)) w = 0;     // a t too large for a double's fraction, or NaN
        int i = phaseIndex((int) Math.floor(w));
        return height(PHASES[i], w - starts[i], lengths[i]);
    }

    /** The lava's height {@code into} ticks into phase p of length {@code len}. */
    private double height(Phase p, double into, int len) {
        return switch (p) {
            case CALM, WARNING -> 0;
            case RISE -> riseHeight(into, len);
            case FULL -> rise;
            // one smooth drop, easing in and out, back to the table as the calm begins
            case DRAIN -> len == 0 ? 0 : rise * (1 - ease(into / len));
        };
    }

    /** The lava's height {@code into} ticks into a rise of {@code len} ticks: climb k of n takes it from k / n to (k + 1) / n of the rise. */
    private double riseHeight(double into, int len) {
        if (len <= 0) return rise;
        Stage st = stage(into, len);
        double done = st.climbing() ? ease(st.into() / st.length()) : 1;
        return rise * (st.index() + done) / stages;
    }

    /** Smoothstep: 0 at 0, 1 at 1, still at both ends. */
    static double ease(double u) {
        if (!(u > 0)) return 0;
        if (u >= 1) return 1;
        return u * u * (3 - 2 * u);
    }

    /**
     * The rows of the flood band (the table's Y plus 0 to rise - 1) whose middle is under the surface of lava standing
     * {@code h} blocks over its table: row i is under when table + i + 0.5 is under table - {@link #SURFACE_DROP} + h.
     * 0 at the table, the whole rise at full.
     */
    public int rows(double h) {
        double x = h - 0.5 - SURFACE_DROP;
        if (!(x > 0)) return 0;
        return (int) Math.min(rise, Math.ceil(x));
    }

    /** The climbs' length and the breaks' in a rise of {@code len} ticks: {climb, break}. */
    private double[] parts(int len) {
        double climbs = len * (1 - breakShare);
        double c = climbs / stages;
        double b = stages > 1 ? (len - climbs) / (stages - 1) : 0;
        return new double[]{c, b};
    }

    /**
     * Where a rise of {@code len} ticks stands {@code into} ticks in: which climb (0 to {@link #stages()} - 1), whether
     * the lava is climbing or in the break after that climb, and how far into that part and how long it is. The last
     * climb has no break after it: the full phase follows.
     */
    public Stage stage(double into, int len) {
        double[] cb = parts(Math.max(1, len));
        double c = cb[0], b = cb[1], span = c + b;
        int k = (int) Math.max(0, Math.min(stages - 1, Math.floor(into / span)));
        double local = Math.max(0, into - k * span);
        if (local < c || k == stages - 1) return new Stage(k, true, Math.min(local, c), c);
        return new Stage(k, false, local - c, b);
    }

    /** Where the rise stands at {@code s} (in the rise), or null in any other phase. */
    public Stage stage(State s) {
        return s.phase() == Phase.RISE ? stage(s.into(), s.phaseLength()) : null;
    }

    /**
     * A part of the rise: climb {@code index} (0 first) while {@code climbing}, else the break after it; {@code into}
     * ticks into that part of {@code length} ticks.
     */
    public record Stage(int index, boolean climbing, double into, double length) {
        /** Ticks until this part ends. */
        public double left() { return Math.max(0, length - into); }
    }

    /** The cycle tick {@code into} ticks into phase p (into is held inside the phase). */
    public int tickOf(Phase p, int into) {
        int len = lengths[p.ordinal()];
        int k = len == 0 ? 0 : Math.max(0, Math.min(len - 1, into));
        return starts[p.ordinal()] + k;
    }

    /**
     * The first cycle tick at which {@code h} rows of the flood band are under the lava ({@link State#level()}): the
     * start of the calm for 0, the start of the full phase for the whole rise, and the moment in the rise's climbs in
     * between. When the rise is empty or too short to take every height, the drain's first tick at or under h is used if
     * it comes nearer; when neither stands at h, the nearest height under it.
     */
    public int tickForLevel(int h) {
        if (h <= 0) return tickOf(Phase.CALM, 0);
        if (h >= rise) return tickOf(Phase.FULL, 0);
        int rl = length(Phase.RISE), dl = length(Phase.DRAIN);
        int best = -1, bestLevel = -1;
        if (rl > 0) {
            // the rise never goes down: the first tick with at least h rows under, or its last tick
            int lo = 0, hi = rl - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (rows(height(Phase.RISE, mid, rl)) >= h) hi = mid;
                else lo = mid + 1;
            }
            int into = lo;
            // a rise with fewer ticks than rows skips heights: step back to the nearest tick at or under h
            while (into > 0 && rows(height(Phase.RISE, into, rl)) > h) into--;
            best = tickOf(Phase.RISE, into);
            bestLevel = rows(height(Phase.RISE, into, rl));
            if (bestLevel == h) return best;
        }
        if (dl > 0) {
            // the drain never goes up: its first tick at or under h
            int lo = 0, hi = dl - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (rows(height(Phase.DRAIN, mid, dl)) <= h) hi = mid;
                else lo = mid + 1;
            }
            int got = rows(height(Phase.DRAIN, lo, dl));
            if (got <= h && got > bestLevel) {
                best = tickOf(Phase.DRAIN, lo);
                bestLevel = got;
            }
        }
        return best >= 0 ? best : tickOf(Phase.CALM, 0);
    }

    /**
     * Where the cycle stands: its phase, ticks into it, the phase's length, the rows of the flood band under the lava
     * ({@code level}: whole blocks, see {@link #rows}), the cycle tick (0 .. length - 1) and the lava's smooth height
     * over its table in blocks ({@code height}).
     */
    public record State(Phase phase, int into, int phaseLength, int level, int tick, double height) {
        /** Ticks until the next phase. */
        public int left() { return phaseLength - into; }
        /** How far through its phase the cycle is, 0 to just under 1. */
        public double progress() { return phaseLength == 0 ? 0 : into / (double) phaseLength; }
    }
}
