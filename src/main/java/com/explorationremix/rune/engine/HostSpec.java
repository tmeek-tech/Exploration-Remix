package com.explorationremix.rune.engine;

/**
 * One weapon's numbers: its kind, how much energy it holds, the floor its energy settles back to after a fight, how its
 * energy fades, and how hard its runes hit.
 *
 * @param maxEnergy the most energy it holds; a cast dearer than this is never cast (shown red)
 * @param floor     what its energy settles back to once the fight is over (a fresh weapon starts with this)
 * @param power     rune damage multiplier (a netherite sword's runes hit harder than a stone sword's)
 * @param fade      how its energy goes back to the floor
 */
public record HostSpec(Host host, float maxEnergy, float floor, float power, Fade fade) {
    /**
     * How energy goes back to the floor: nothing for {@code delayTicks} after the weapon last gained energy or cast, then
     * down by {@code downPerTick} while above the floor, or up by {@code upPerTick} while below it.
     */
    public record Fade(int delayTicks, float downPerTick, float upPerTick) {
        /** Four seconds, then 7 a second down, or 3 a second up. */
        public static final Fade DEFAULT = new Fade(80, 0.35F, 0.15F);
    }

    /** The share of its most energy a weapon settles back to, unless the server says otherwise. */
    public static final float FLOOR_SHARE = 0.3F;

    /** Plain numbers for each kind of weapon, for tests and weapons the game knows nothing special about. */
    public static HostSpec of(Host host) {
        return switch (host) {
            case SWORD -> new HostSpec(host, 70, 21, 1.0F, Fade.DEFAULT);
            case BOW, CROSSBOW -> new HostSpec(host, 60, 18, 1.0F, Fade.DEFAULT);
        };
    }

    public HostSpec withMax(float max) { return new HostSpec(host, max, Math.min(floor, max), power, fade); }

    public HostSpec withFloor(float f) { return new HostSpec(host, maxEnergy, Math.max(0.0F, Math.min(f, maxEnergy)), power, fade); }

    public HostSpec withFade(Fade f) { return new HostSpec(host, maxEnergy, floor, power, f); }
}
