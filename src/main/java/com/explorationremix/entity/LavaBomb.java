package com.explorationremix.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;

/**
 * A lava bomb thrown by an eruption of the Caldera ({@code world.eruption.Eruptions}). The skeleton of 0.5.1: the
 * eruption build fills it in (flight, burst, trail).
 */
public class LavaBomb extends ThrowableProjectile {
    public LavaBomb(EntityType<? extends LavaBomb> type, Level level) { super(type, level); }

    @Override
    protected void defineSynchedData() {}
}
