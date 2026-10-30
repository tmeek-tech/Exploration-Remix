package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.SurfaceOracle;
import com.explorationremix.world.terrain.Entrances;
import com.explorationremix.world.terrain.Shape;
import com.explorationremix.world.terrain.Terrain;
import com.explorationremix.world.terrain.Upper;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

import java.util.stream.Stream;

/**
 * Picks a random Y inside a named layer (by its config id), so datapack features follow the configured heights
 * instead of hard-coded numbers. {@code inset} keeps the position away from the band edges; {@code above_water}
 * keeps it above the layer's water or lava table. The layer {@code upper} is the pack's own caves: where an entrance's
 * chamber lies under the column, mostly the heights of that chamber (a few blocks under the ground); otherwise from
 * the old floor up to 128 blocks above it, where the Silver Fern Rift and the Ivory Root Cavern are. Its
 * {@code focus} aims the tries: {@code pieces} only into the open space of a set piece over the column (none
 * elsewhere), {@code grottos} as just described, {@code caves} anywhere from the old floor up to 6 blocks under the
 * pack's ground (for the plant caves, which follow the ground), {@code auto} (the default) into a set piece three times
 * in four where there is one, as described otherwise.
 */
public final class LayerRangePlacement extends PlacementModifier {
    public static final Codec<LayerRangePlacement> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("layer").forGetter(LayerRangePlacement::layer),
            Codec.INT.optionalFieldOf("inset", 2).forGetter(LayerRangePlacement::inset),
            Codec.BOOL.optionalFieldOf("above_water", false).forGetter(LayerRangePlacement::aboveWater),
            Codec.STRING.optionalFieldOf("focus", "auto").forGetter(LayerRangePlacement::focus)
    ).apply(i, LayerRangePlacement::new));

    private final String layer;
    private final int inset;
    private final boolean aboveWater;
    private final String focus;

    public LayerRangePlacement(String layer, int inset, boolean aboveWater, String focus) {
        this.layer = layer;
        this.inset = inset;
        this.aboveWater = aboveWater;
        this.focus = focus;
    }

    public String layer() { return layer; }
    public int inset() { return inset; }
    public boolean aboveWater() { return aboveWater; }
    public String focus() { return focus; }

    @Override
    public Stream<BlockPos> getPositions(PlacementContext ctx, RandomSource random, BlockPos pos) {
        Shape s = DeepWorld.shape();
        if (s == null || !DeepWorld.installed()) return Stream.empty();
        if (layer.equals("upper")) {
            int x = pos.getX(), z = pos.getZ();
            if (focus.equals("caves")) {
                SurfaceOracle o = DeepWorld.oracle();
                int lo = s.packFloor + 6 + inset, hi = o != null ? o.at(x, z).y() - 6 : lo + 128;
                if (hi <= lo) return Stream.empty();
                return Stream.of(new BlockPos(x, lo + random.nextInt(hi - lo + 1), z));
            }
            if (!focus.equals("grottos")) {
                // A set piece is a small target in a 126-block range, and often under a grotto: aim at it.
                Terrain t = DeepWorld.terrain();
                Upper.Piece p = t != null ? t.upperPiece(x, z) : null;
                if (p != null && p.distXZ(x, z) < 4) {
                    if (focus.equals("pieces") || random.nextFloat() < 0.75F) {
                        // Start in the open: the floor and ceiling scans that follow find nothing from rock or water,
                        // and most of a set piece's columns are rock or stream.
                        int lo = (int) Math.floor(p.waterY) - 3, hi = (int) Math.ceil(p.topY);
                        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos(x, lo, z);
                        int open = 0, pick = lo;
                        for (int y = lo; y <= hi; y++) {
                            if (ctx.getBlockState(at.setY(y)).isAir() && random.nextInt(++open) == 0) pick = y;
                        }
                        return open > 0 ? Stream.of(new BlockPos(x, pick, z)) : Stream.empty();
                    }
                } else if (focus.equals("pieces")) {
                    return Stream.empty();
                }
            }
            Entrances r = DeepWorld.entrances();
            int[] band = r != null ? r.band(pos.getX(), pos.getZ()) : null;
            if (band != null && random.nextFloat() < 0.75F)
                return Stream.of(new BlockPos(pos.getX(), band[0] + random.nextInt(band[1] - band[0] + 1), pos.getZ()));
            int lo = s.packFloor + 6 + inset;
            return Stream.of(new BlockPos(pos.getX(), lo + random.nextInt(128 - inset), pos.getZ()));
        }
        int li = s.layerIndex(layer);
        if (li < 0) return Stream.empty();
        int min = (aboveWater ? s.waterY(li) : s.bottom(li)) + inset, max = s.top(li) - inset;
        if (max < min) return Stream.empty();
        return Stream.of(new BlockPos(pos.getX(), min + random.nextInt(max - min + 1), pos.getZ()));
    }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.LAYER_RANGE.get(); }
}
