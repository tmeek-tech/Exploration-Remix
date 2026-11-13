package com.explorationremix.mixin;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.DeepMaterials;
import com.explorationremix.world.terrain.Entrances;
import com.explorationremix.world.terrain.UpperShapes;
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
            // The den gates' landings and ramps, the Silver Fern Rift and the Ivory Root Cavern are carved out of the
            // pack's caves, where vanilla floods everything near the old floor with lava. Around them the deep world
            // decides: their own stream or lake, and air above it, so no lava pools sit where water belongs. The
            // entrances' chambers likewise hold their own streams and pools, and vanilla never floods one that happens
            // to lie below sea level.
            if (density <= 0.0) {
                int f = t.upperFluid(ctx.blockX(), y, ctx.blockZ());
                boolean flow = false;
                if (f < 0) {
                    Entrances e = DeepWorld.entrances();
                    if (e != null) {
                        int ef = e.fluid(ctx.blockX(), y, ctx.blockZ());
                        if (ef >= 0) f = ef == Entrances.WATER ? Terrain.WATER : Terrain.AIR;
                    }
                    UpperShapes s = DeepWorld.shapes();
                    if (s != null) {
                        // The noise shapes' pools, streams and lakes lie still in rock that holds them; only a
                        // spring's water flows as the chunk finishes, down the rift's wall.
                        int sf = s.fluid(ctx.blockX(), y, ctx.blockZ());
                        if (sf >= 0) {
                            f = sf == UpperShapes.AIR ? Terrain.AIR : Terrain.WATER;
                            flow = sf == UpperShapes.SPRING;
                        }
                    }
                }
                if (f >= 0) {
                    this.shouldScheduleFluidUpdate = flow;
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

    /**
     * The upper caves are safe: wherever vanilla's aquifer would leave lava above the old floor, it leaves water. Where
     * the Upper Caves grow from noise, the pack's water beside their open space or over it stays rock instead, so the
     * pack's lakes end at a wall of stone rather than standing as a wall of water (or hanging over a tunnel); so does
     * the pack's water at the edge of the den gates' dry zone.
     */
    @Inject(method = "computeSubstance", at = @At("RETURN"), cancellable = true)
    private void er$noUpperLava(DensityFunction.FunctionContext ctx, double density, CallbackInfoReturnable<BlockState> cir) {
        BlockState s = cir.getReturnValue();
        if (s == null) return;
        boolean lava = s.is(Blocks.LAVA);
        if (!lava && !s.is(Blocks.WATER)) return;
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed() || this.minGridY != Math.floorDiv(t.shape.floorY, 12) - 1) return;
        if (ctx.blockY() < t.shape.packFloor) return;
        UpperShapes shapes = DeepWorld.shapes();
        if (shapes != null && shapes.besideOpen(ctx.blockX(), ctx.blockY(), ctx.blockZ())) {
            this.shouldScheduleFluidUpdate = false;
            cir.setReturnValue(null);
            return;
        }
        // The den gates keep the pack's caves dry close to their landings, ramps and rifts (the head of this class):
        // the pack's water just outside that, or over it where the dry zone stops, is rock, so its lakes end at a wall
        // of stone rather than a wall of water.
        int y = ctx.blockY();
        if (y <= t.shape.packFloor + 56 && t.denDryDist(ctx.blockX(), y, ctx.blockZ()) < Terrain.DEN_SHELL) {
            this.shouldScheduleFluidUpdate = false;
            cir.setReturnValue(null);
            return;
        }
        if (lava) cir.setReturnValue(DeepMaterials.WATER);
    }
}
