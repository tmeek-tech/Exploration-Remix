package com.explorationremix.mixin;

import com.explorationremix.world.lava.LavaPlane;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Basalt Galleries' risen lava is a plane with no blocks under it (0.21.6, {@link LavaPlane}), so the game's own
 * fluid test finds none: once it has measured the fluids an entity is in, the plane's depth over the entity's feet is
 * added as lava. Forge reads every lava test from that one number (being in lava, its height for swimming up and
 * slowing, the burn in {@code baseTick}), so whatever stands under the plane's surface is in lava exactly as in vanilla
 * lava: set alight and hurt, slowed, able to swim up, items and experience burning; and the fire-proof are safe in it.
 * Both sides: the server's plane for the server's entities, the client's for the client's (the local player's own
 * movement is worked out there).
 */
@Mixin(Entity.class)
public abstract class EntityLavaPlaneMixin {
    @Shadow(remap = false)
    protected Object2DoubleMap<FluidType> forgeFluidTypeHeight;

    @Inject(method = "updateInWaterStateAndDoFluidPushing", at = @At("RETURN"), cancellable = true)
    private void explorationremix$lavaPlane(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        Level level = self.level();
        LavaPlane plane = LavaPlane.of(level);
        if (plane == null) return;
        double depth = plane.depth(self.getBoundingBox(), level.getGameTime());
        if (depth <= 0) return;
        FluidType lava = ForgeMod.LAVA_TYPE.get();
        if (depth > forgeFluidTypeHeight.getDouble(lava)) forgeFluidTypeHeight.put(lava, depth);
        cir.setReturnValue(true);
    }
}
