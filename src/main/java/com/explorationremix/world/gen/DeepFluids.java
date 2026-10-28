package com.explorationremix.world.gen;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.Layer;
import com.explorationremix.world.plan.CellPlan;
import com.explorationremix.world.plan.LayerPlans;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/** Water tables and lava pockets for the deep layers. */
public final class DeepFluids {
    private DeepFluids() {}

    public record Result(@Nullable BlockState state, boolean scheduleUpdate) {}

    private static final Result AIR = new Result(Blocks.AIR.defaultBlockState(), false);

    /** Fluid level for a layer at a column: the layer's table, bent a little by noise. */
    public static int tableLevel(Layer layer, LayerPlans plans, int x, int z) {
        int base = layer.bottom() + layer.fluidLevel();
        double n = plans.noise().fbm2(x + layer.index() * 311.0, z, 160.0);
        return base + (int) Math.round(n * 5.0);
    }

    /** True inside one of a layer's lava pockets. */
    public static boolean inLavaPocket(Layer layer, LayerPlans plans, int x, int y, int z) {
        if (!layer.lavaPockets()) return false;
        return plans.noise().fbm3(x, y * 1.7, z, 44.0) > 0.52;
    }

    public static Result substance(int x, int y, int z) {
        LayerPlans plans = DeepWorld.plans();
        if (plans == null) return AIR;
        if (DeepWorld.bandAt(y) != 0) return AIR;
        Layer layer = DeepWorld.layerAt(y);
        if (layer == null) return AIR;
        CellPlan plan = plans.cellAt(layer.index(), x, z);
        if (plan.insideRefuge(x, y, z) || plan.insideLanding(x, y, z)) return AIR;
        boolean pocket = inLavaPocket(layer, plans, x, y, z);
        int level = tableLevel(layer, plans, x, z);
        if (pocket) level += 4;
        if (y >= level) return AIR;
        BlockState fluid;
        if (pocket) fluid = Blocks.LAVA.defaultBlockState();
        else fluid = switch (layer.fluid()) {
            case WATER -> Blocks.WATER.defaultBlockState();
            case LAVA -> Blocks.LAVA.defaultBlockState();
            case NONE -> Blocks.AIR.defaultBlockState();
        };
        return new Result(fluid, y == level - 1);
    }
}
