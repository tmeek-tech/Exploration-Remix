package com.explorationremix.mixin.accessor;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Vanilla's attack charge: swords and axes no longer charge (every swing that lands is at full strength), so the timed
 * swings set it full just before they hit, and a player's own game keeps it full so the charge bar never shows.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("attackStrengthTicker") int er$attackStrengthTicker();

    @Accessor("attackStrengthTicker") void er$setAttackStrengthTicker(int ticks);
}
