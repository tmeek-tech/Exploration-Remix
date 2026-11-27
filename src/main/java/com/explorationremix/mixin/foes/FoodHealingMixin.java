package com.explorationremix.mixin.foes;

import com.explorationremix.combat.recovery.Recovery;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Food's slow healing goes while health comes back after a while unhurt instead (0.23.6, combat.recovery): food asks
 * the natural regeneration rule as if it were off. Hunger and starving are as they were.
 */
@Mixin(FoodData.class)
public abstract class FoodHealingMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z"))
    private boolean er$foodHeals(GameRules rules, GameRules.Key<GameRules.BooleanValue> key) {
        return rules.getBoolean(key) && !Recovery.replacesFoodHealing();
    }
}
