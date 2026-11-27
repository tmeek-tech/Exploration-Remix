package com.explorationremix.mixin.foes;

import com.explorationremix.combat.foes.FoeSlimes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.monster.Slime;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A slime squashing down for its slam, and in the slam's leap (0.23.8): its own hops and steering are held. */
@Mixin(targets = "net.minecraft.world.entity.monster.Slime$SlimeMoveControl")
public abstract class SlimeHoldMixin extends MoveControl {
    @Shadow @Final private Slime slime;

    private SlimeHoldMixin(Mob mob) { super(mob); }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void er$heldForSlam(CallbackInfo ci) {
        if (FoeSlimes.holding(slime)) {
            slime.setSpeed(0.0F);
            slime.xxa = 0.0F;
            ci.cancel();
        }
    }
}
