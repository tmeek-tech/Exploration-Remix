package com.explorationremix.combat;

import net.minecraft.world.entity.LivingEntity;

/**
 * A creature whose strikes have a wind-up you can read (the Mossback's rear, the Skein's coil). A player's strong
 * attack landing on it mid wind-up spoils the strike and staggers it (Taran's pick, 2026-11-11); light swings don't.
 */
public interface WindUp {
    /** Winding up a strike now. */
    boolean windingUp();

    /** A strong attack by {@code by} landed in the wind-up: the strike is dropped (the caller staggers it). */
    void interrupt(LivingEntity by);
}
