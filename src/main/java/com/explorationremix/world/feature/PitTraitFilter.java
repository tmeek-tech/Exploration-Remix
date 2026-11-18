package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.UpperShapes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

import java.util.Optional;

/**
 * Keeps a feature to the sinkholes (and oasis hollows) whose trait lies between {@code min} and {@code max} (0.18.6):
 * each pit draws its traits once, from its middle ({@link UpperShapes#pitTrait}), so a pit hung thick with ivy has it
 * all over and the next has none. Traits: {@code shelves}, {@code fall}, {@code slope}, {@code ivy}. Nothing passes
 * where no pit is, or without the noise shapes.
 */
public final class PitTraitFilter extends PlacementFilter {
    public static final Codec<PitTraitFilter> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.comapFlatMap(PitTraitFilter::parse, PitTraitFilter::name).fieldOf("trait").forGetter(f -> f.trait),
            Codec.DOUBLE.optionalFieldOf("min").forGetter(f -> f.min),
            Codec.DOUBLE.optionalFieldOf("max").forGetter(f -> f.max)
    ).apply(i, PitTraitFilter::new));

    private static final String[] NAMES = {"shelves", "fall", "slope", null, "ivy"};

    private final int trait;
    private final Optional<Double> min, max;

    private PitTraitFilter(int trait, Optional<Double> min, Optional<Double> max) {
        this.trait = trait;
        this.min = min;
        this.max = max;
    }

    private static DataResult<Integer> parse(String name) {
        for (int k = 0; k < NAMES.length; k++) if (name.equals(NAMES[k])) return DataResult.success(k);
        return DataResult.error(() -> "pit_trait: no trait " + name + " (shelves, fall, slope or ivy)");
    }

    private static String name(int trait) { return NAMES[trait]; }

    @Override
    protected boolean shouldPlace(PlacementContext ctx, RandomSource random, BlockPos pos) {
        UpperShapes sh = DeepWorld.shapes();
        if (sh == null) return false;
        double t = sh.pitTrait(pos.getX(), pos.getZ(), trait);
        if (t < 0) return false;
        return (min.isEmpty() || t >= min.get()) && (max.isEmpty() || t <= max.get());
    }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.PIT_TRAIT.get(); }
}
