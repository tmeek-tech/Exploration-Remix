package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Shape;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

import java.util.stream.Stream;

/**
 * Aims a try at the tall open spaces of a deep layer. A try that starts at a random height and scans for a floor or a
 * ceiling ({@code minecraft:environment_scan}, at most 32 steps) misses most of the time in the Basalt Galleries'
 * halls, which are 60 to 100 tall, and never reaches their vaults from the floor. So this reads the column at the try's
 * x and z instead: the runs of open air inside the named layer (from {@code inset} blocks over its bottom, or over its
 * lava table with {@code above_water}, up to {@code inset} blocks under its top) that are at least {@code min_span}
 * tall, of which it picks one at random. The try goes to the run's top, the block under the vault ({@code ceiling}),
 * its bottom, the block on the floor ({@code floor}, only where the floor is rock, not lava), or any block of it
 * ({@code any}). A column with no such run gives no try. Only blocks are read, so it follows whatever shape the
 * layer's network carves, features placed before it included.
 */
public final class OpenSpanPlacement extends PlacementModifier {
    public static final Codec<OpenSpanPlacement> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("layer").forGetter(OpenSpanPlacement::layer),
            Codec.STRING.optionalFieldOf("anchor", "any").forGetter(OpenSpanPlacement::anchor),
            Codec.intRange(1, 384).optionalFieldOf("min_span", 16).forGetter(OpenSpanPlacement::minSpan),
            Codec.INT.optionalFieldOf("inset", 2).forGetter(OpenSpanPlacement::inset),
            Codec.BOOL.optionalFieldOf("above_water", false).forGetter(OpenSpanPlacement::aboveWater)
    ).apply(i, OpenSpanPlacement::new));

    private final String layer, anchor;
    private final int minSpan, inset;
    private final boolean aboveWater;

    public OpenSpanPlacement(String layer, String anchor, int minSpan, int inset, boolean aboveWater) {
        this.layer = layer;
        this.anchor = anchor;
        this.minSpan = minSpan;
        this.inset = inset;
        this.aboveWater = aboveWater;
    }

    public String layer() { return layer; }
    public String anchor() { return anchor; }
    public int minSpan() { return minSpan; }
    public int inset() { return inset; }
    public boolean aboveWater() { return aboveWater; }

    @Override
    public Stream<BlockPos> getPositions(PlacementContext ctx, RandomSource random, BlockPos pos) {
        Shape s = DeepWorld.shape();
        if (s == null || !DeepWorld.installed()) return Stream.empty();
        int li = s.layerIndex(layer);
        if (li < 0) return Stream.empty();
        int lo = (aboveWater ? s.waterY(li) : s.bottom(li)) + inset, hi = s.top(li) - inset;
        if (hi - lo + 1 < minSpan) return Stream.empty();
        boolean floor = anchor.equals("floor"), ceiling = anchor.equals("ceiling");
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos(pos.getX(), hi, pos.getZ());
        // Runs of air from the top down; the k-th run that qualifies replaces the pick with chance 1/k, so every
        // qualifying run is as likely as any other.
        int seen = 0, pickTop = 0, pickBottom = 0;
        int top = Integer.MIN_VALUE;
        for (int y = hi; y >= lo - 1; y--) {
            boolean air = y >= lo && ctx.getBlockState(at.setY(y)).isAir();
            if (air) {
                if (top == Integer.MIN_VALUE) top = y;
                continue;
            }
            if (top != Integer.MIN_VALUE) {
                int bottom = y + 1;
                if (top - bottom + 1 >= minSpan && (!floor || rockUnder(ctx, at, bottom)) && random.nextInt(++seen) == 0) {
                    pickTop = top;
                    pickBottom = bottom;
                }
                top = Integer.MIN_VALUE;
            }
        }
        if (seen == 0) return Stream.empty();
        int y = ceiling ? pickTop : floor ? pickBottom : pickBottom + random.nextInt(pickTop - pickBottom + 1);
        return Stream.of(new BlockPos(pos.getX(), y, pos.getZ()));
    }

    /** Whether the block under a run's bottom is rock or something else solid that is no fluid. */
    private static boolean rockUnder(PlacementContext ctx, BlockPos.MutableBlockPos at, int bottom) {
        BlockState s = ctx.getBlockState(at.setY(bottom - 1));
        return s.getFluidState().isEmpty() && !s.isAir();
    }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.OPEN_SPAN.get(); }
}
