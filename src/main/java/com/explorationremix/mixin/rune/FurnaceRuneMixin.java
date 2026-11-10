package com.explorationremix.mixin.rune;

import com.explorationremix.rune.RuneGear;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

/**
 * A furnace (or blast furnace, or smoker) that smelts runed gear down (an iron sword into a nugget) pops its runes out
 * on top of itself, as items, rather than burning them with it. Hooked just before the input is used up.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class FurnaceRuneMixin {
    @Inject(method = "burn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;shrink(I)V"))
    private void explorationremix$runesOut(RegistryAccess access, @Nullable Recipe<?> recipe, NonNullList<ItemStack> items, int max,
                                           CallbackInfoReturnable<Boolean> cir) {
        ItemStack in = items.get(0);
        AbstractFurnaceBlockEntity self = (AbstractFurnaceBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide || !RuneGear.hasRunes(in)) return;
        BlockPos above = self.getBlockPos().above();
        for (ItemStack r : RuneGear.runeItems(in)) Containers.dropItemStack(level, above.getX() + 0.5, above.getY() + 0.1, above.getZ() + 0.5, r);
        RuneGear.clearRunes(in);
    }
}
