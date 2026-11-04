package com.explorationremix.creature;

import java.util.function.DoubleSupplier;

/**
 * How the Skein moves, when it moves at all: never at an even pace. It lies still for long stretches ({@link
 * Phase#REST}); now and then it goes out, and an outing is a run of bursts: it snaps to a new heading ({@link
 * Phase#PIVOT}, a tick to three), skitters at a sprint for a fraction of a second to a second and a half ({@link
 * Phase#BURST}), and freezes dead ({@link Phase#FREEZE}) for a moment before the next snap. The lengths are drawn so
 * they never settle into a rhythm: many bursts are twitches of a few ticks and a few run long; most freezes are brief,
 * some long enough that it seems to have settled; most snaps turn it 30 to 120 degrees, and now and then it doubles
 * back. An outing ends once it has covered its distance (8 to 30 blocks), and it rests again for tens of seconds to
 * minutes, so about three quarters of the time it lies still.
 *
 * <p>Its moods change the numbers: {@link Mood#ROAM} as above; {@link Mood#STALK}, creeping up on fireflies, in short
 * slow bursts with long freezes, its heading only jittered (the creature aims it); {@link Mood#BOLT}, getting away, in
 * fast long bursts with only the briefest freezes, over a short way; {@link Mood#JOURNEY}, heading somewhere far off,
 * in long runs broken by brief freezes and now and then a longer pause, its heading only jittered (the creature aims
 * it at where it is going), and a long rest once it gets there.
 *
 * <p>The creature says how far it moved each tick and when a burst got nowhere; the gait says what to do next. Plain
 * numbers and a source of uniform randoms in [0, 1), so it can be tested on its own.
 */
public final class SkeinGait {
    public enum Phase { REST, PIVOT, BURST, FREEZE }
    public enum Mood { ROAM, STALK, BOLT, JOURNEY }

    // ROAM: bursts of 3 to 30 ticks (a twitch, a dash, now and then a long run), at 0.95 to 1.2 times its speed
    static final int TWITCH_MAX = 6, DASH_MAX = 18, RUN_MAX = 30, BURST_MIN = 3;
    static final double TWITCHES = 0.3, DASHES = 0.5;
    // freezes of 4 to 40 ticks (0.2 to 2 s): mostly short, some long
    static final int FREEZE_MIN = 4, SHORT_FREEZE = 12, MID_FREEZE = 28, FREEZE_MAX = 40;
    static final double SHORT_FREEZES = 0.55, MID_FREEZES = 0.35;
    /** A snap turns it 30 to 120 degrees either way; this share of them double it back (150 to 200 degrees). */
    static final double TURN_MIN = 30, TURN_MAX = 120, BACK_MIN = 150, BACK_MAX = 200, DOUBLE_BACK = 0.18;
    /** An outing covers 8 to 30 blocks. */
    static final double OUTING_MIN = 8, OUTING_MAX = 30;
    /** Then it rests 12 to 102 seconds (most of them under a minute). */
    static final int REST_MIN = 240, REST_SPREAD = 1800;
    // STALK and BOLT
    static final int STALK_BURST_MAX = 12, STALK_FREEZE_MIN = 20, STALK_FREEZE_MAX = 60;
    static final double STALK_JITTER = 35;
    static final int BOLT_BURST_MIN = 10, BOLT_BURST_MAX = 30, BOLT_FREEZE_MIN = 3, BOLT_FREEZE_MAX = 10;
    static final double BOLT_JITTER = 50;
    // JOURNEY: runs of 24 to 50 ticks at 0.95 to 1.1 times its speed; freezes of 2 to 8 ticks, a fifth of them pauses of
    // 20 to 45; its heading jittered up to 12 degrees either way
    static final int JOURNEY_BURST_MIN = 24, JOURNEY_BURST_MAX = 50, JOURNEY_FREEZE_MIN = 2, JOURNEY_FREEZE_MAX = 8,
            JOURNEY_PAUSE_MIN = 20, JOURNEY_PAUSE_MAX = 45;
    static final double JOURNEY_PAUSES = 0.2, JOURNEY_JITTER = 12;
    /** After a bolt or a stalk it rests only 5 to 20 seconds. */
    static final int SHORT_REST_MIN = 100, SHORT_REST_MAX = 400;
    /** Bursts in a row that got nowhere before it gives up the outing. */
    static final int GIVE_UP = 4;

    private Phase phase = Phase.REST;
    private Mood mood = Mood.ROAM;
    private int left = 1;
    private double budget;
    private int burstTicks;
    private double speed = 1.0, turn;
    private int failed;
    private boolean gaveUp;

    public Phase phase() { return phase; }
    public Mood mood() { return mood; }
    /** Ticks left in this phase. */
    public int left() { return left; }
    /** Blocks left to go on this outing. */
    public double budget() { return budget; }
    /** The current (or, during a pivot, the coming) burst's length in ticks and speed (times its own). */
    public int burstTicks() { return burstTicks; }
    public double speed() { return speed; }
    /** The coming burst's turn from its heading (radians, either way): the snap. */
    public double turn() { return turn; }
    /** Motionless: resting or frozen. */
    public boolean still() { return phase == Phase.REST || phase == Phase.FREEZE; }
    public boolean outing() { return phase != Phase.REST; }
    /**
     * Whether it is resting because its last outing's bursts got nowhere ({@link #GIVE_UP} in a row) with way still to
     * go, not because it went its way or was told to rest: there was no way where it was going.
     */
    public boolean gaveUp() { return gaveUp; }

    /** Rests for a random while (long, after roaming or a journey; short after a bolt or a stalk). */
    public void rest(DoubleSupplier r) {
        rest(mood == Mood.ROAM || mood == Mood.JOURNEY ? REST_MIN + (int) (sq(r.getAsDouble()) * REST_SPREAD) : range(r, SHORT_REST_MIN, SHORT_REST_MAX));
    }

    public void rest(int ticks) {
        phase = Phase.REST;
        left = Math.max(1, ticks);
        budget = 0;
        failed = 0;
        gaveUp = false;
    }

    /** Sets out on an outing of the usual length (8 to 30 blocks). */
    public void startOuting(Mood m, DoubleSupplier r) {
        startOuting(m, OUTING_MIN + r.getAsDouble() * (OUTING_MAX - OUTING_MIN), r);
    }

    /** Sets out on an outing of {@code blocks}: it starts with a snap to its first heading. */
    public void startOuting(Mood m, double blocks, DoubleSupplier r) {
        mood = m;
        budget = blocks;
        failed = 0;
        gaveUp = false;
        pivot(r);
    }

    /**
     * A tick passes, in which it moved {@code moved} blocks. Returns true when the phase changed (the creature then
     * aims a new burst, or stops dead).
     */
    public boolean tick(double moved, DoubleSupplier r) {
        if (phase == Phase.BURST) {
            budget -= moved;
            if (budget <= 0) { freeze(r); return true; }
        }
        if (--left > 0) return false;
        switch (phase) {
            case REST -> startOuting(Mood.ROAM, r);
            case PIVOT -> burst();
            case BURST -> freeze(r);
            case FREEZE -> {
                if (budget <= 0 || failed >= GIVE_UP) {
                    boolean nowhere = budget > 0;
                    rest(r);
                    gaveUp = nowhere;
                } else pivot(r);
            }
        }
        return true;
    }

    /** The burst got where it was going, or nowhere at all ({@code nowhere}): it freezes now. */
    public void endBurst(boolean nowhere, DoubleSupplier r) {
        if (phase != Phase.BURST && phase != Phase.PIVOT) return;
        failed = nowhere ? failed + 1 : 0;
        freeze(r);
    }

    /** No way at all for this burst's heading: a new snap, the same pivot (the creature asks again). */
    public void reroll(DoubleSupplier r) {
        turn = drawTurn(r);
    }

    private void pivot(DoubleSupplier r) {
        phase = Phase.PIVOT;
        left = 1 + (int) (r.getAsDouble() * 3);
        turn = drawTurn(r);
        burstTicks = drawBurst(r);
        speed = drawSpeed(r);
    }

    private void burst() {
        phase = Phase.BURST;
        left = burstTicks;
    }

    private void freeze(DoubleSupplier r) {
        phase = Phase.FREEZE;
        left = drawFreeze(r);
    }

    private double drawTurn(DoubleSupplier r) {
        double side = r.getAsDouble() < 0.5 ? -1.0 : 1.0;
        double deg = switch (mood) {
            case ROAM -> r.getAsDouble() < DOUBLE_BACK ? BACK_MIN + r.getAsDouble() * (BACK_MAX - BACK_MIN)
                    : TURN_MIN + r.getAsDouble() * (TURN_MAX - TURN_MIN);
            case STALK -> r.getAsDouble() * STALK_JITTER;
            case BOLT -> r.getAsDouble() * BOLT_JITTER;
            case JOURNEY -> r.getAsDouble() * JOURNEY_JITTER;
        };
        return side * Math.toRadians(deg);
    }

    private int drawBurst(DoubleSupplier r) {
        return switch (mood) {
            case ROAM -> {
                double k = r.getAsDouble();
                if (k < TWITCHES) yield range(r, BURST_MIN, TWITCH_MAX);
                if (k < TWITCHES + DASHES) yield range(r, TWITCH_MAX + 1, DASH_MAX);
                yield range(r, DASH_MAX + 1, RUN_MAX);
            }
            case STALK -> range(r, BURST_MIN, STALK_BURST_MAX);
            case BOLT -> range(r, BOLT_BURST_MIN, BOLT_BURST_MAX);
            case JOURNEY -> range(r, JOURNEY_BURST_MIN, JOURNEY_BURST_MAX);
        };
    }

    private double drawSpeed(DoubleSupplier r) {
        double k = r.getAsDouble();
        return switch (mood) {
            case ROAM -> 0.95 + 0.25 * k;
            case STALK -> 0.55 + 0.2 * k;
            case BOLT -> 1.2 + 0.15 * k;
            case JOURNEY -> 0.95 + 0.15 * k;
        };
    }

    private int drawFreeze(DoubleSupplier r) {
        return switch (mood) {
            case ROAM -> {
                double k = r.getAsDouble();
                if (k < SHORT_FREEZES) yield range(r, FREEZE_MIN, SHORT_FREEZE);
                if (k < SHORT_FREEZES + MID_FREEZES) yield range(r, SHORT_FREEZE + 1, MID_FREEZE);
                yield range(r, MID_FREEZE + 1, FREEZE_MAX);
            }
            case STALK -> range(r, STALK_FREEZE_MIN, STALK_FREEZE_MAX);
            case BOLT -> range(r, BOLT_FREEZE_MIN, BOLT_FREEZE_MAX);
            case JOURNEY -> r.getAsDouble() < JOURNEY_PAUSES ? range(r, JOURNEY_PAUSE_MIN, JOURNEY_PAUSE_MAX)
                    : range(r, JOURNEY_FREEZE_MIN, JOURNEY_FREEZE_MAX);
        };
    }

    /** A whole number from lo to hi, both included. */
    private static int range(DoubleSupplier r, int lo, int hi) {
        return lo + Math.min(hi - lo, (int) (r.getAsDouble() * (hi - lo + 1)));
    }

    private static double sq(double v) { return v * v; }

    /** For the dev listing: what it is doing, and for how long yet. */
    public String describe() {
        String what = switch (phase) {
            case REST -> "resting";
            case PIVOT -> "turning";
            case BURST -> "running";
            case FREEZE -> "frozen";
        };
        return String.format(java.util.Locale.ROOT, "%s %.1fs (%s%s)", what, left / 20.0, mood.name().toLowerCase(java.util.Locale.ROOT),
                outing() ? String.format(java.util.Locale.ROOT, ", %.0f blocks to go", Math.max(0, budget)) : "");
    }
}
