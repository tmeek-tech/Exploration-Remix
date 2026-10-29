package com.explorationremix.mixin;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.DeepMaterials;
import com.explorationremix.world.terrain.Rootvaults;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Below the pack's old floor, the deep world decides every block while the noise is filled: each layer's flat water
 * (or lava) table instead of vanilla's aquifers, band stone, bedrock, and the deep rock of each layer. Rock near open
 * space is left as stone for the surface pass, which dresses it per biome. Above the old floor, in the upper caves,
 * vanilla's aquifers stay but never hold lava: the lava lakes it would put at the bottom of the pack's caves are water.
 */
@Mixin(Aquifer.NoiseBasedAquifer.class)
public abstract class NoiseBasedAquiferMixin {
    @Shadow protected boolean shouldScheduleFluidUpdate;
    @Shadow @Final protected int minGridY;

    @Inject(method = "computeSubstance", at = @At("HEAD"), cancellable = true)
    private void er$deepSubstance(DensityFunction.FunctionContext ctx, double density, CallbackInfoReturnable<BlockState> cir) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) return;
        // Only the deepened overworld: its aquifer grid starts at the deep floor.
        if (this.minGridY != Math.floorDiv(t.shape.floorY, 12) - 1) return;
        int y = ctx.blockY();
        if (y >= t.shape.packFloor) {
            // The first gate's landing and ramp, the Silver Fern Rift and the Ivory Root Cavern are carved out of the
            // pack's caves, where vanilla floods everything near the old floor with lava. Around them the deep world
            // decides: their own stream or lake, and air above it, so no lava pools sit where water belongs. The
            // Rootvaults' grottos likewise hold their own streams and pools, and vanilla never floods one that happens
            // to lie below sea level.
            if (density <= 0.0) {
                int f = t.upperFluid(ctx.blockX(), y, ctx.blockZ());
                if (f < 0) {
                    Rootvaults r = DeepWorld.roots();
                    if (r != null) {
                        int rf = r.fluid(ctx.blockX(), y, ctx.blockZ());
                        if (rf >= 0) f = rf == Rootvaults.WATER ? Terrain.WATER : Terrain.AIR;
                    }
                }
                if (f >= 0) {
                    this.shouldScheduleFluidUpdate = false;
                    cir.setReturnValue(f == Terrain.WATER ? DeepMaterials.WATER : DeepMaterials.AIR);
                }
            }
            return;
        }
        this.shouldScheduleFluidUpdate = false;
        if (density > 0.0) {
            cir.setReturnValue(DeepMaterials.solid(t, ctx.blockX(), y, ctx.blockZ(), density));
            return;
        }
        int f = t.fluidAt(ctx.blockX(), y, ctx.blockZ());
        cir.setReturnValue(f == Terrain.WATER ? DeepMaterials.WATER : f == Terrain.LAVA ? DeepMaterials.LAVA : DeepMaterials.AIR);
    }

    /** The upper caves are safe: wherever vanilla's aquifer would leave lava above the old floor, it leaves water. */
    @Inject(method = "computeSubstance", at = @At("RETURN"), cancellable = true)
    private void er$noUpperLava(DensityFunction.FunctionContext ctx, double density, CallbackInfoReturnable<BlockState> cir) {
        BlockState s = cir.getReturnValue();
        if (s == null || !s.is(Blocks.LAVA)) return;
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed() || this.minGridY != Math.floorDiv(t.shape.floorY, 12) - 1) return;
        if (ctx.blockY() >= t.shape.packFloor) cir.setReturnValue(DeepMaterials.WATER);
    }
}
