package com.explorationremix.world.lava;

/**
 * The lava cycle's clock: which tick of the cycle the world is at, as a function of the overworld's game time. Kept
 * in the world's saved data ({@link LavaCycleData}), so it survives restarts and every player sees the same cycle; no
 * Minecraft types, so it is tested offline.
 *
 * <p>The cycle tick is the game time less the game time the current cycle began at ({@link #cycleStart}). Game time
 * counts ticks the server has run, so the clock pauses while the server is off and {@code /time set} leaves it alone.
 * Each cycle keeps the phase lengths it began with: a changed config takes effect when the next cycle begins, while
 * {@link #rescale} (the length command) changes the running one at once, keeping its place in the phase. Frozen, the
 * clock holds its tick however much time passes (for tests and pictures). {@link #cycles} counts the cycles that have
 * ended: the epoch the next build's terrain reset will key its per-chunk records to.
 */
public final class LavaClock {
    /** The game time the current cycle began at (the cycle tick is game time minus this). */
    long cycleStart;
    /** This cycle's phase lengths in ticks, one per phase. */
    int[] lengths;
    /** True while the clock is held. */
    boolean frozen;
    /** The cycle tick the clock is held at while frozen. */
    int frozenAt;
    /** How many cycles have ended in this world. */
    long cycles;
    /** A cycle length set by command, in ticks, or 0 for the config's. */
    int lengthOverride;

    private transient LavaCycle cycle;
    private transient int cycleRise = -1;

    /** A clock whose first cycle begins at game time {@code now}, calm. */
    public LavaClock(long now, int[] lengths) {
        this.cycleStart = now;
        this.lengths = check(lengths);
    }

    /** A clock read back from saved data; bad lengths fall back to {@code fallback}. */
    LavaClock(long cycleStart, int[] lengths, boolean frozen, int frozenAt, long cycles, int lengthOverride, int[] fallback) {
        this.cycleStart = cycleStart;
        int[] ok;
        try {
            ok = check(lengths);
        } catch (RuntimeException e) {
            ok = check(fallback);
        }
        this.lengths = ok;
        this.frozen = frozen;
        this.cycles = Math.max(0, cycles);
        this.lengthOverride = Math.max(0, lengthOverride);
        this.frozenAt = Math.floorMod(frozenAt, total(ok));
    }

    private static int[] check(int[] lengths) {
        new LavaCycle(lengths, 0);     // throws on a bad set
        return lengths.clone();
    }

    private static int total(int[] lengths) {
        int t = 0;
        for (int l : lengths) t += l;
        return t;
    }

    /** The current cycle with the lava climbing {@code rise} blocks (cached until the lengths change). */
    public LavaCycle cycle(int rise) {
        if (cycle == null || cycleRise != rise) {
            cycle = new LavaCycle(lengths, rise);
            cycleRise = rise;
        }
        return cycle;
    }

    private void setLengths(int[] l) {
        lengths = check(l);
        cycle = null;
    }

    public boolean frozen() { return frozen; }
    public long cycles() { return cycles; }
    public int lengthOverride() { return lengthOverride; }
    public int[] lengths() { return lengths.clone(); }

    /**
     * Moves the clock up to game time {@code now}: when the current cycle has run out, the next begins where it ended,
     * with the phase lengths {@code next} (the config's, or the length command's), and so on if more than one cycle
     * went by. Returns true when something changed that must be saved.
     */
    public boolean advance(long now, int[] next) {
        if (frozen) return false;
        long t = now - cycleStart;
        if (t < 0) {
            // the game time went backwards (an old backup of level.dat with newer saved data): start the cycle over
            cycleStart = now;
            return true;
        }
        int total = total(lengths);
        if (t < total) return false;
        cycleStart += total;
        cycles++;
        setLengths(next);
        int nt = total(lengths);
        long more = (now - cycleStart) / nt;
        if (more > 0) {
            cycleStart += more * nt;
            cycles += more;
        }
        return true;
    }

    /** The cycle tick at game time {@code now} (after {@link #advance}), 0 .. length - 1. */
    public int tick(long now) {
        if (frozen) return frozenAt;
        return (int) Math.floorMod(now - cycleStart, (long) total(lengths));
    }

    /** Puts the clock at cycle tick {@code t} (held there if frozen). */
    public void set(long now, int t) {
        int w = Math.floorMod(t, total(lengths));
        if (frozen) frozenAt = w;
        else cycleStart = now - w;
    }

    /** Holds the clock where it is. */
    public void freeze(long now) {
        if (frozen) return;
        frozenAt = tick(now);
        frozen = true;
    }

    /** Lets the clock run on from where it was held. */
    public void unfreeze(long now) {
        if (!frozen) return;
        cycleStart = now - frozenAt;
        frozen = false;
    }

    /**
     * Gives the running cycle new phase lengths at once, keeping its phase and the share of the phase that has gone
     * by. {@code override} is remembered for the cycles after (0: back to the config's length).
     */
    public void rescale(long now, int[] newLengths, int override) {
        int rise = Math.max(0, cycleRise);
        LavaCycle.State s = cycle(rise).at(tick(now));
        LavaCycle n = new LavaCycle(newLengths, rise);
        int len = n.length(s.phase());
        int into = (int) Math.round(s.progress() * len);
        int t = n.tickOf(s.phase(), into);
        setLengths(newLengths);
        lengthOverride = Math.max(0, override);
        set(now, t);
    }
}
