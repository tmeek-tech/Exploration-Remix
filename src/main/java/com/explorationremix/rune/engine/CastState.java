package com.explorationremix.rune.engine;

/**
 * A piece of gear's casting state, kept with the gear (game ticks are the server's game time):
 * <ul>
 *   <li>{@code energy} as it stood at {@code energyTick};</li>
 *   <li>{@code lastGain}: the tick it last gained energy (a hit's motes landing in it), or -1 if never: with
 *   {@code lastCast}, when the fight it was in went quiet, and its energy began to go back to the floor;</li>
 *   <li>{@code cursor}: the cast it fires next;</li>
 *   <li>{@code readyAt}: the first tick it may cast again (after a cast's delay, or the recovery after the last);</li>
 *   <li>{@code lastCast}: the tick it last cast, for going back to the first cast after an idle spell.</li>
 * </ul>
 * Energy is worked out when asked, so the gear needs writing only when something happens to it.
 */
public final class CastState {
    public float energy;
    public long energyTick;
    public long lastGain = -1;
    public int cursor;
    public long readyAt;
    public long lastCast = Long.MIN_VALUE / 4;

    public CastState() {}

    public CastState(float energy, long energyTick, long lastGain, int cursor, long readyAt, long lastCast) {
        this.energy = energy;
        this.energyTick = energyTick;
        this.lastGain = lastGain;
        this.cursor = cursor;
        this.readyAt = readyAt;
        this.lastCast = lastCast;
    }

    /** A fresh piece of gear's state: at its floor, nothing cast. */
    public static CastState fresh(HostSpec spec, long now) {
        return new CastState(spec.floor(), now, -1, 0, 0, Long.MIN_VALUE / 4);
    }

    public CastState copy() { return new CastState(energy, energyTick, lastGain, cursor, readyAt, lastCast); }

    @Override
    public boolean equals(Object o) {
        return o instanceof CastState s && s.energy == energy && s.energyTick == energyTick && s.lastGain == lastGain && s.cursor == cursor
                && s.readyAt == readyAt && s.lastCast == lastCast;
    }

    @Override
    public int hashCode() { return Float.hashCode(energy) * 31 + Long.hashCode(energyTick) * 17 + cursor; }

    @Override
    public String toString() {
        return "CastState{e=" + energy + "@" + energyTick + ", gain=" + lastGain + ", cursor=" + cursor + ", ready=" + readyAt + ", last=" + lastCast + "}";
    }
}
