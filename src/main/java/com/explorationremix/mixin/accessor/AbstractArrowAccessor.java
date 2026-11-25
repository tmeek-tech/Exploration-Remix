package com.explorationremix.mixin.accessor;

import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Recall's view of an arrow: whether it is stuck in a block, and the item it is picked up as. */
@Mixin(AbstractArrow.class)
public interface AbstractArrowAccessor {
    @Accessor("inGround") boolean er$inGround();

    @Invoker("getPickupItem") ItemStack er$pickupItem();
}
