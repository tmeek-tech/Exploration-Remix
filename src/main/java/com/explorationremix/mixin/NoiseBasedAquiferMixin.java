package com.explorationremix.mixin;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.DeepFluids;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Below the pack's floor, fluids follow each layer's water table instead of vanilla's aquifers. */
@Mixin(Aquifer.NoiseBasedAquifer.class)
public abstract class NoiseBasedAquiferMixin {
    @Shadow protected boolean shouldScheduleFluidUpdate;

    @Inject(method = "computeSubstance", at = @At("HEAD"), cancellable = true)
    private void er$deepSubstance(DensityFunction.FunctionContext ctx, double density, CallbackInfoReturnable<BlockState> cir) {
        int y = ctx.blockY();
        if (!DeepWorld.installed()) return;
        if (y >= DeepWorld.packFloor()) {
            // The first gate's landing and ramp are carved out of the pack's caves, where vanilla floods everything
            // below y -54 with lava. Keep a margin around them dry so the gate mouth is never a lava pool.
            if (y < DeepWorld.packFloor() + 48 && density <= 0.0 && DeepWorld.plans() != null
                    && DeepWorld.plans().gateOneCarveDistance(ctx.blockX(), y, ctx.blockZ()) < 14.0) {
                this.shouldScheduleFluidUpdate = false;
                cir.setReturnValue(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
            return;
        }
        if (density > 0.0) {
            this.shouldScheduleFluidUpdate = false;
            cir.setReturnValue(null);
            return;
        }
        DeepFluids.Result r = DeepFluids.substance(ctx.blockX(), y, ctx.blockZ());
        this.shouldScheduleFluidUpdate = r.scheduleUpdate();
        cir.setReturnValue(r.state());
    }
}
