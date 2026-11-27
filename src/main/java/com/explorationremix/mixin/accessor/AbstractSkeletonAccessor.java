package com.explorationremix.mixin.accessor;

import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * A skeleton's own arrow (a stray's slows): the reworked skeletons (0.23.6, combat.foes) loose it themselves, aimed
 * ahead of a moving player.
 */
@Mixin(AbstractSkeleton.class)
public interface AbstractSkeletonAccessor {
    @Invoker("getArrow") AbstractArrow er$getArrow(ItemStack arrow, float power);
}
