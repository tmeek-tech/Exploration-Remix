package com.explorationremix.mixin.rune;

import com.explorationremix.rune.cast.ArrowRides;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A rune arrow's own search for what it hits leaves out those it passes by (ArrowRides.passesBy): a Fan copy, or a shot
 * flying on after a hit, never meets someone runes mustn't touch, so it finds the foe or the wall behind them in the
 * same step. Server side only (the client's arrow has the same id, and the rides live on the server); the arrow is
 * sent to the watching players that tick, so a client that bounced its own picture of it off a villager is put right
 * at once, as for a vanilla piercing arrow.
 */
@Mixin(AbstractArrow.class)
public abstract class ArrowPassByMixin {
    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
    private void explorationremix$passBy(Entity e, CallbackInfoReturnable<Boolean> cir) {
        AbstractArrow self = (AbstractArrow) (Object) this;
        if (!self.level().isClientSide() && ArrowRides.passesBy(self, e)) {
            self.hasImpulse = true;
            cir.setReturnValue(false);
        }
    }
}
