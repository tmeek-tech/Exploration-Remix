package com.explorationremix.world.quake;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * When the Basalt Galleries' quakes come (0.9.0, spec 4.1): big quakes every few minutes and light tremors between
 * them, server-wide (a quake is the layer's, not a player's), drawn again at every server start and never saved. Pure
 * (no game types, seeded), so it is tested offline; {@link Quakes} asks it every overworld tick. The third kind, the
 * ground answering a climb, is scheduled by the placements that call for it, not here.
 *
 * <ul>
 *     <li>A big quake is due when its time comes; it lasts {@code seconds} a quarter more or less, at a strength of 0.5
 *     to 1, and the next one is drawn from its end ({@code minMinutes} to {@code maxMinutes}).</li>
 *     <li>A tremor (only with {@code tremors} on) is due every {@code tremorMinSeconds} to {@code tremorMaxSeconds},
 *     lasts 30 to 50 ticks at a strength of 0.15 to 0.35, and never comes within {@link #AROUND_BIG} ticks of a big
 *     quake: one due then is put off to that long after the big quake's end.</li>
 *     <li>The lava's warning is the layer's only clock, so it is kept quiet ({@link #quietLeft}): a big quake due while
 *     the lava warns or in the first {@link #QUIET_RISE} ticks of its rise is put off to that window's end and 200 to
 *     600 ticks more; a tremor due then runs silent (its blocks still topple, but no client hears or feels it).</li>
 *     <li>A quake due with no player there to take part ({@code actors}) is skipped and the next one drawn. Nothing starts
 *     while the lava clock or a quake is held; the clock moves on, and a quake that fell due starts at the release.</li>
 * </ul>
 * In each pair of bounds the larger counts as at least the smaller.
 */
public final class QuakeSchedule {
    /** The kinds of quake (the same numbers as {@code LavaNet.Quake}'s). */
    public static final int TREMOR = 0, BIG = 1, CLIMB = 2;
    /** No tremor comes this many ticks before a big quake, during one or this many ticks after it. */
    public static final int AROUND_BIG = 400;
    /** The first ticks of the lava's rise that are kept quiet with its warning. */
    public static final int QUIET_RISE = 600;
    /** A big quake put off by the quiet window comes this many ticks after its end, and up to {@link #QUIET_SPREAD} more. */
    public static final int QUIET_AFTER = 200, QUIET_SPREAD = 400;

    /** The config's numbers, read each tick (the config is read while the server runs). */
    public record Settings(boolean tremors, double tremorMinSeconds, double tremorMaxSeconds, double minMinutes, double maxMinutes, double seconds) {}

    /** A quake that starts now: its kind, length in ticks, strength (0 to 1), the seed its client effects are drawn from, and whether it is silent. */
    public record Start(int kind, int duration, float strength, int seed, boolean silent) {}

    private final Random random;
    private boolean begun;
    private long nextTremor, nextBig;
    /** The next big quake's length and strength, drawn with its time, so a tremor can keep clear of its end. */
    private int bigDuration;
    private float bigStrength;
    /** The tick the last big quake ends at (MIN_VALUE before any). */
    private long lastBigEnd = Long.MIN_VALUE / 2;

    public QuakeSchedule(long seed) { this.random = new Random(seed); }

    /**
     * Ticks left of the quiet window at cycle phase {@code phase} ({@code into} ticks into it, {@code left} to go), or 0
     * outside it: the whole warning and the first {@link #QUIET_RISE} ticks of the rise. With the lava cycle off there is
     * no window. (A rise shorter than {@link #QUIET_RISE} is quiet throughout, and the window counted from the warning
     * then runs a little past it: a big quake is put off a little longer, nothing else.)
     */
    public static int quietLeft(boolean cycleOn, boolean warning, boolean rising, int into, int left) {
        if (!cycleOn) return 0;
        if (warning) return left + QUIET_RISE;
        if (rising && into < QUIET_RISE) return QUIET_RISE - into;
        return 0;
    }

    /** True once the first quakes have been drawn. */
    public boolean begun() { return begun; }
    /** The tick the next tremor is due at (it may be put off when it comes). */
    public long nextTremor() { return nextTremor; }
    /** The tick the next big quake is due at. */
    public long nextBig() { return nextBig; }

    /**
     * What starts at tick {@code now}, or null. {@code quiet} is what {@link #quietLeft} answers now, {@code held} whether
     * the lava clock or a quake is held, {@code actors} whether any player is there to take part.
     */
    @Nullable
    public Start tick(long now, Settings s, int quiet, boolean held, boolean actors) {
        if (!begun) {
            begun = true;
            nextTremor = now + tremorGap(s);
            drawBig(now, s);
        }
        if (held) return null;
        if (now >= nextBig) {
            if (quiet > 0) {
                nextBig = now + quiet + QUIET_AFTER + random.nextInt(QUIET_SPREAD + 1);
            } else if (!actors) {
                drawBig(now, s);
            } else {
                Start big = new Start(BIG, bigDuration, bigStrength, random.nextInt(), false);
                bigStarted(now, bigDuration, s);
                return big;
            }
        }
        if (s.tremors() && now >= nextTremor) {
            if (now < lastBigEnd + AROUND_BIG) {
                nextTremor = lastBigEnd + AROUND_BIG;
                return null;
            }
            if (nextBig - now <= AROUND_BIG) {
                nextTremor = nextBig + bigDuration + AROUND_BIG;
                return null;
            }
            nextTremor = now + tremorGap(s);
            if (!actors) return null;
            int duration = 30 + random.nextInt(21);
            float strength = 0.15F + random.nextFloat() * 0.2F;
            return new Start(TREMOR, duration, strength, random.nextInt(), quiet > 0);
        }
        return null;
    }

    /**
     * A big quake started at {@code start} and lasts {@code duration} ticks (by the schedule, or by a command): the next
     * one is drawn from its end, and the next tremor keeps {@link #AROUND_BIG} ticks clear of it. Called again with the
     * moved start when a big quake held by command is released, as it then ends later than it was drawn to.
     */
    public void bigStarted(long start, int duration, Settings s) {
        if (!begun) {
            begun = true;
            nextTremor = start + tremorGap(s);
        }
        lastBigEnd = start + duration;
        drawBig(lastBigEnd, s);
        if (nextTremor < lastBigEnd + AROUND_BIG) nextTremor = lastBigEnd + AROUND_BIG;
    }

    /** The length and strength a big quake would have now, drawn as the schedule draws them (for a command that gives none). */
    public int drawBigDuration(Settings s) { return (int) Math.round(Math.max(1, s.seconds() * 20 * (0.75 + random.nextDouble() * 0.5))); }
    public float drawBigStrength() { return 0.5F + random.nextFloat() * 0.5F; }
    /** A seed for a quake a command starts. */
    public int drawSeed() { return random.nextInt(); }
    /** A number from 0 (inclusive) to 1, from the schedule's own draws (for a command's defaults). */
    public float drawFloat() { return random.nextFloat(); }

    private void drawBig(long from, Settings s) {
        double lo = s.minMinutes(), hi = Math.max(lo, s.maxMinutes());
        nextBig = from + Math.round((lo + random.nextDouble() * (hi - lo)) * 1200);
        bigDuration = drawBigDuration(s);
        bigStrength = drawBigStrength();
    }

    private long tremorGap(Settings s) {
        double lo = s.tremorMinSeconds(), hi = Math.max(lo, s.tremorMaxSeconds());
        return Math.round((lo + random.nextDouble() * (hi - lo)) * 20);
    }
}
