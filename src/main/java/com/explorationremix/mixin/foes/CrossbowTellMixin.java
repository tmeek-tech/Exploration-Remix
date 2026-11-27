package com.explorationremix.mixin.foes;

import com.explorationremix.combat.foes.FoeRules;
import com.explorationremix.combat.foes.Foes;
import net.minecraft.world.entity.ai.goal.RangedCrossbowAttackGoal;
import net.minecraft.world.entity.monster.Monster;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A pillager holding a loaded crossbow on its quarry (0.23.6): a tell {@link FoeRules#SHOT_TELL} ticks before the bolt
 * goes (the wait after loading counts down to it), as the skeletons' shots have.
 */
@Mixin(RangedCrossbowAttackGoal.class)
public abstract class CrossbowTellMixin {
    @Shadow private int attackDelay;
    /** Its crossbowman, kept from the constructor: the goal's own field is generic, which a shadow can't be mapped onto. */
    @Unique private Monster er$mob;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void er$keep(Monster mob, double speed, float radius, CallbackInfo ci) {
        er$mob = mob;
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void er$boltTell(CallbackInfo ci) {
        if (attackDelay == FoeRules.SHOT_TELL && er$mob != null) Foes.boltTell(er$mob);
    }
}
