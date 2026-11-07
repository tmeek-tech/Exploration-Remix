package com.explorationremix.mixin.accessor;

import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether a projectile has flown a tick yet: a new shot joins without it, a portal's or a teleport's copy with it. */
@Mixin(Projectile.class)
public interface ProjectileAccessor {
    @Accessor("hasBeenShot") boolean er$hasBeenShot();
}
