package com.explorationremix.world.feature;

import com.explorationremix.world.terrain.GardensField;
import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Network;
import net.minecraft.util.Mth;

/**
 * Which of the deep world's vents and ponds hide a rune stone (0.23.1; card picks "One in eight" vents with a way into
 * their chamber, "One in three" ponds): each by its own key or middle, so every chunk, the features and the commands
 * agree. Pure functions of the world's plan ({@link RuneStoneVentsFeature}, {@link RuneStonePondsFeature} set them).
 */
public final class RuneStoneSites {
    private RuneStoneSites() {}

    /** The share of the vents with a way in that hide a stone, and of the ponds. */
    public static final double VENT_SHARE = 1.0 / 8.0, POND_SHARE = 1.0 / 3.0;
    static final long VENT_SALT = 0x52554E45L, POND_SALT = 0x504F4E44L;

    /** Whether vent v hides a stone: one with a way into its chamber (an open crack), one in eight of those, by its key. */
    public static boolean ventHides(GardensField.VentChamber v) {
        return v.cracks().length > 0 && Hashing.unit(Hashing.mix(v.key(), VENT_SALT, 7, 0)) < VENT_SHARE;
    }

    /** Whether a pond hides a stone: one in three, by its middle. */
    public static boolean pondHides(long seed, Network.PondInfo pond) {
        return Hashing.unit(Hashing.mix(seed, POND_SALT, Mth.floor(pond.x() * 4), Mth.floor(pond.z() * 4))) < POND_SHARE;
    }
}
