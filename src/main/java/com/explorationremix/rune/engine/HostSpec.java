package com.explorationremix.rune.engine;

/**
 * One piece of gear's numbers: its kind, how much energy it holds and regains, how hard its runes hit, and its bonus
 * slots.
 *
 * @param maxEnergy   the most energy it holds; a cast dearer than this is never cast (shown red)
 * @param regenPerTick energy regained per tick while it regenerates (wand and bow: while held), 0 if never
 * @param startEnergy energy a fresh piece of gear starts with
 * @param power       rune damage multiplier (a netherite sword's runes hit harder than a stone sword's)
 * @param bonusSlots  extra slots (diamond and netherite gear: 1)
 */
public record HostSpec(Host host, float maxEnergy, float regenPerTick, float startEnergy, float power, int bonusSlots) {
    public int slotCount() { return host.maxSlots + bonusSlots; }

    public int awakeSlots(int depth) { return host.awakeSlots(depth, bonusSlots); }

    /** Plain numbers for each kind of gear, for tests and gear the game knows nothing special about. */
    public static HostSpec of(Host host) {
        return switch (host) {
            case SWORD -> new HostSpec(host, 70, 0, 30, 1.0F, 0);
            case BOW, CROSSBOW -> new HostSpec(host, 60, 0.2F, 60, 1.0F, 0);
            case SHIELD -> new HostSpec(host, 50, 0, 30, 1.0F, 0);
            case WAND -> new HostSpec(host, 100, 0.8F, 100, 1.0F, 0);
        };
    }

    public HostSpec withMax(float max) { return new HostSpec(host, max, regenPerTick, Math.min(startEnergy, max), power, bonusSlots); }

    public HostSpec withBonus(int bonus) { return new HostSpec(host, maxEnergy, regenPerTick, startEnergy, power, bonus); }
}
