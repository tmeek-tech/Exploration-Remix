package com.explorationremix.rune.engine;

/**
 * One piece of gear's numbers: its kind, how much energy it holds, the floor its energy settles back to after a fight,
 * how its energy fades, how hard its runes hit, and its bonus slots.
 *
 * @param maxEnergy  the most energy it holds; a cast dearer than this is never cast (shown red)
 * @param floor      what its energy settles back to once the fight is over (fresh gear starts with this)
 * @param power      rune damage multiplier (a netherite sword's runes hit harder than a stone sword's)
 * @param bonusSlots extra slots (diamond and netherite gear: 1)
 * @param fade       how its energy goes back to the floor
 */
public record HostSpec(Host host, float maxEnergy, float floor, float power, int bonusSlots, Fade fade) {
    /**
     * How energy goes back to the floor: nothing for {@code delayTicks} after the gear last gained energy or cast, then
     * down by {@code downPerTick} while above the floor, or up by {@code upPerTick} while below it.
     */
    public record Fade(int delayTicks, float downPerTick, float upPerTick) {
        /** Four seconds, then 7 a second down, or 3 a second up. */
        public static final Fade DEFAULT = new Fade(80, 0.35F, 0.15F);
    }

    /** The share of its most energy a piece of gear settles back to, unless the server says otherwise. */
    public static final float FLOOR_SHARE = 0.3F;

    public int slotCount() { return host.maxSlots + bonusSlots; }

    public int awakeSlots(int depth) { return host.awakeSlots(depth, bonusSlots); }

    /** Plain numbers for each kind of gear, for tests and gear the game knows nothing special about. */
    public static HostSpec of(Host host) {
        return switch (host) {
            case SWORD -> new HostSpec(host, 70, 21, 1.0F, 0, Fade.DEFAULT);
            case BOW, CROSSBOW -> new HostSpec(host, 60, 18, 1.0F, 0, Fade.DEFAULT);
        };
    }

    public HostSpec withMax(float max) { return new HostSpec(host, max, Math.min(floor, max), power, bonusSlots, fade); }

    public HostSpec withBonus(int bonus) { return new HostSpec(host, maxEnergy, floor, power, bonus, fade); }

    public HostSpec withFloor(float f) { return new HostSpec(host, maxEnergy, Math.max(0.0F, Math.min(f, maxEnergy)), power, bonusSlots, fade); }

    public HostSpec withFade(Fade f) { return new HostSpec(host, maxEnergy, floor, power, bonusSlots, f); }
}
