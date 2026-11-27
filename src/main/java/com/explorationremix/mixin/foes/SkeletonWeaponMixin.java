package com.explorationremix.mixin.foes;

import com.explorationremix.combat.foes.Foes;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A skeleton weighs up its weapon (as it is made, loads, or is handed something) and vanilla gives it its bow goal or
 * its melee goal: a reworked one (0.23.6) gets ours again in their place.
 */
@Mixin(AbstractSkeleton.class)
public abstract class SkeletonWeaponMixin {
    @Inject(method = "reassessWeaponGoal", at = @At("TAIL"))
    private void er$foeGoal(CallbackInfo ci) {
        Foes.reassessed((AbstractSkeleton) (Object) this);
    }
}
