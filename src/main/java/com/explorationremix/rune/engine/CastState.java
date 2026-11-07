package com.explorationremix.rune.engine;

/**
 * A piece of gear's casting state, kept with the gear (game ticks are the server's game time):
 * <ul>
 *   <li>{@code energy} as it stood at {@code energyTick};</li>
 *   <li>{@code regenSince}: the tick it started regaining energy (held), or -1 while it doesn't;</li>
 *   <li>{@code cursor}: the cast it fires next;</li>
 *   <li>{@code readyAt}: the first tick it may cast again (after a cast's delay, or the recovery after the last);</li>
 *   <li>{@code lastCast}: the tick it last cast, for going back to the first cast after an idle spell.</li>
 * </ul>
 * Energy is worked out when asked, so the gear needs writing only when something happens to it.
 */
public final class CastState {
    public float energy;
    public long energyTick;
    public long regenSince = -1;
    public int cursor;
    public long readyAt;
    public long lastCast = Long.MIN_VALUE / 4;

    public CastState() {}

    public CastState(float energy, long energyTick, long regenSince, int cursor, long readyAt, long lastCast) {
        this.energy = energy;
        this.energyTick = energyTick;
        this.regenSince = regenSince;
        this.cursor = cursor;
        this.readyAt = readyAt;
        this.lastCast = lastCast;
    }

    /** A fresh piece of gear's state: its starting energy, nothing cast. */
    public static CastState fresh(HostSpec spec, long now) {
        return new CastState(spec.startEnergy(), now, -1, 0, 0, Long.MIN_VALUE / 4);
    }

    public CastState copy() { return new CastState(energy, energyTick, regenSince, cursor, readyAt, lastCast); }

    @Override
    public boolean equals(Object o) {
        return o instanceof CastState s && s.energy == energy && s.energyTick == energyTick && s.regenSince == regenSince && s.cursor == cursor
                && s.readyAt == readyAt && s.lastCast == lastCast;
    }

    @Override
    public int hashCode() { return Float.hashCode(energy) * 31 + Long.hashCode(energyTick) * 17 + cursor; }

    @Override
    public String toString() {
        return "CastState{e=" + energy + "@" + energyTick + ", regen=" + regenSince + ", cursor=" + cursor + ", ready=" + readyAt + ", last=" + lastCast + "}";
    }
}
