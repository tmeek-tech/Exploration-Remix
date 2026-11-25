package com.explorationremix.mixin.rune;

import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mobs spawn with plain gear (0.22.6, rune.loot.Plain): a skeleton's bow, a zombie's sword or armor and a drowned's
 * trident are never enchanted, since the enchanting table sets runes now. Gear a mob picks up keeps what it had.
 */
@Mixin(Mob.class)
public abstract class PlainMobGearMixin {
    @Inject(method = "populateDefaultEquipmentEnchantments", at = @At("HEAD"), cancellable = true)
    private void explorationremix$plainGear(RandomSource random, DifficultyInstance difficulty, CallbackInfo ci) {
        ci.cancel();
    }
}
