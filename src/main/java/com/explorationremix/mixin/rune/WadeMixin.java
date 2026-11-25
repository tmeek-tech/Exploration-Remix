package com.explorationremix.mixin.rune;

import com.explorationremix.rune.armor.WornRunes;
import com.explorationremix.rune.engine.RuneId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stride (leggings): cobwebs, powder snow and berry bushes don't hold its player back. Both sides, as the player's own
 * game moves them and reads the same worn runes.
 */
@Mixin(Entity.class)
public abstract class WadeMixin {
    @Inject(method = "makeStuckInBlock", at = @At("HEAD"), cancellable = true)
    private void explorationremix$wade(BlockState state, Vec3 slow, CallbackInfo ci) {
        if ((Object) this instanceof Player p && WornRunes.of(p).has(RuneId.STRIDE)) ci.cancel();
    }
}
