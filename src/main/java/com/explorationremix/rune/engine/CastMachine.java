package com.explorationremix.rune.engine;

/**
 * The rules of paying and timing, on a {@link CastState}:
 * <ul>
 *   <li>A cast is paid in full before it fires, or not at all: short of energy the gear sputters, spends nothing and
 *   stays on that cast.</li>
 *   <li>A cast dearer than the gear can ever hold is skipped (red); the cursor only visits casts that can fire.</li>
 *   <li>Each cast adds its delay before the next one. After the last cast the gear waits the longer of that delay and
 *   the row's recovery, then starts again from the first.</li>
 *   <li>After {@link Limits#idleTicks()} without casting, once it is ready, the gear goes back to its first cast.</li>
 *   <li>Energy regained while held is counted from {@code regenSince}, so the gear is written only when something
 *   happens to it.</li>
 * </ul>
 */
public final class CastMachine {
    private CastMachine() {}

    public enum Kind {
        /** It fired: {@code cast} and {@code cost} say which and what it paid. */
        CAST,
        /** Still in a delay or recovering: {@code wait} ticks to go. */
        NOT_READY,
        /** Short of energy: needs {@code cost}, has {@code have}. Nothing spent; the cursor stays. */
        SPUTTER,
        /** No cast in the row does anything. */
        NOTHING,
        /** Every cast costs more than the gear can hold. */
        ALL_RED
    }

    public record Outcome(Kind kind, int cast, int cost, float have, int waitTicks) {
        public boolean fired() { return kind == Kind.CAST; }
    }

    /** Energy at {@code now}, counting what it regained while held. */
    public static float energyAt(CastState s, HostSpec h, long now) {
        float e = s.energy;
        if (h.regenPerTick() > 0 && s.regenSince >= 0) {
            long from = Math.max(s.energyTick, s.regenSince);
            if (now > from) e += h.regenPerTick() * (now - from);
        }
        return Math.max(0.0F, Math.min(e, h.maxEnergy()));
    }

    /** Writes the energy as it stands at {@code now} into the state. */
    public static void settle(CastState s, HostSpec h, long now) {
        s.energy = energyAt(s, h, now);
        s.energyTick = now;
    }

    /** The gear starts or stops regaining energy (taken in hand, put away). */
    public static void setHeld(CastState s, HostSpec h, long now, boolean held) {
        settle(s, h, now);
        s.regenSince = held ? now : -1;
    }

    /** Adds energy (a sword hit, a blocked hit, a Siphon mote), up to what the gear holds. */
    public static float add(CastState s, HostSpec h, long now, float amount) {
        settle(s, h, now);
        float before = s.energy;
        s.energy = Math.max(0.0F, Math.min(h.maxEnergy(), s.energy + amount));
        return s.energy - before;
    }

    /** The cast the gear would fire next at {@code now} (after an idle reset), or -1 if none can fire. */
    public static int next(CastState s, Reading r, long now, Limits lim) {
        int n = r.casts().size();
        if (n == 0) return -1;
        int cursor = s.cursor;
        if (cursor < 0 || cursor >= n) cursor = 0;
        if (cursor != 0 && now >= s.readyAt && now - s.lastCast >= lim.idleTicks()) cursor = 0;
        for (int k = 0; k < n; k++) {
            int c = (cursor + k) % n;
            if (!r.casts().get(c).red()) return c;
        }
        return -1;
    }

    /**
     * Tries to cast. On {@link Kind#CAST} the state has paid and moved on; otherwise it is unchanged (apart from going
     * back to the first cast after an idle spell).
     *
     * @param free the cast costs nothing (commands and tests)
     */
    public static Outcome attempt(CastState s, Reading r, long now, boolean free, Limits lim) {
        HostSpec h = r.spec();
        int n = r.casts().size();
        if (n == 0) return new Outcome(Kind.NOTHING, -1, 0, energyAt(s, h, now), 0);
        if (now < s.readyAt) return new Outcome(Kind.NOT_READY, -1, 0, energyAt(s, h, now), (int) Math.min(Integer.MAX_VALUE, s.readyAt - now));
        int c = next(s, r, now, lim);
        if (c < 0) return new Outcome(Kind.ALL_RED, -1, 0, energyAt(s, h, now), 0);
        s.cursor = c;
        CastReading cast = r.casts().get(c);
        int cost = free ? 0 : cast.cost();
        float have = energyAt(s, h, now);
        if (have + 1.0E-4F < cost) return new Outcome(Kind.SPUTTER, c, cost, have, 0);
        s.energy = have - cost;
        s.energyTick = now;
        s.lastCast = now;
        int following = -1;
        for (int k = c + 1; k < n; k++) {
            if (!r.casts().get(k).red()) { following = k; break; }
        }
        if (following < 0) {
            s.cursor = 0;
            s.readyAt = now + Math.max(cast.delay(), r.recovery());
        } else {
            s.cursor = following;
            s.readyAt = now + cast.delay();
        }
        return new Outcome(Kind.CAST, c, cost, s.energy, 0);
    }

    /** Fraction of the wait done, for the recovery sweep (1 when ready). */
    public static float readiness(CastState s, long now, int waitTotal) {
        if (now >= s.readyAt || waitTotal <= 0) return 1.0F;
        return 1.0F - (float) (s.readyAt - now) / waitTotal;
    }
}
