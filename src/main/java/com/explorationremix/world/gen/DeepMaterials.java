package com.explorationremix.world.gen;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Rock;
import com.explorationremix.world.terrain.Shape;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Turns the terrain's {@link Rock} choices into block states. Create's stones are looked up by id when the server
 * starts, with a vanilla stand-in (and a log line) when Create is missing.
 */
public final class DeepMaterials {
    private static final EnumMap<Rock, BlockState> STATES = new EnumMap<>(Rock.class);
    private static final BlockState[] BY_ORDINAL = new BlockState[Rock.values().length];
    public static final BlockState AIR = Blocks.AIR.defaultBlockState();
    public static final BlockState CAVE_AIR = Blocks.CAVE_AIR.defaultBlockState();
    public static final BlockState WATER = Blocks.WATER.defaultBlockState();
    public static final BlockState LAVA = Blocks.LAVA.defaultBlockState();

    private DeepMaterials() {}

    public static void resolve() {
        List<String> missing = new ArrayList<>();
        for (Rock r : Rock.values()) {
            BlockState s = switch (r) {
                case STONE -> Blocks.STONE.defaultBlockState();
                case DEEPSLATE -> Blocks.DEEPSLATE.defaultBlockState();
                case COBBLED_DEEPSLATE -> Blocks.COBBLED_DEEPSLATE.defaultBlockState();
                case TUFF -> Blocks.TUFF.defaultBlockState();
                case CALCITE -> Blocks.CALCITE.defaultBlockState();
                case LIMESTONE -> create("limestone", Blocks.CALCITE, missing);
                case OCHRUM -> create("ochrum", Blocks.YELLOW_TERRACOTTA, missing);
                case ASURINE -> create("asurine", Blocks.DEEPSLATE_TILES, missing);
                case CRIMSITE -> create("crimsite", Blocks.GRANITE, missing);
                case VERIDIUM -> create("veridium", Blocks.WARPED_WART_BLOCK, missing);
                case SCORIA -> create("scoria", Blocks.BROWN_TERRACOTTA, missing);
                case SCORCHIA -> create("scorchia", Blocks.POLISHED_BLACKSTONE, missing);
                case BLACKSTONE -> Blocks.BLACKSTONE.defaultBlockState();
                case BASALT -> Blocks.BASALT.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
                case SMOOTH_BASALT -> Blocks.SMOOTH_BASALT.defaultBlockState();
                case GRANITE -> Blocks.GRANITE.defaultBlockState();
                case DRIPSTONE -> Blocks.DRIPSTONE_BLOCK.defaultBlockState();
                case BEDROCK -> Blocks.BEDROCK.defaultBlockState();
                case BAND_STONE -> ERBlocks.BAND_STONE.get().defaultBlockState();
                case MOSS -> Blocks.MOSS_BLOCK.defaultBlockState();
                case GRASS -> Blocks.GRASS_BLOCK.defaultBlockState();
                case DIRT -> Blocks.DIRT.defaultBlockState();
                case ROOTED_DIRT -> Blocks.ROOTED_DIRT.defaultBlockState();
                case CLAY -> Blocks.CLAY.defaultBlockState();
                case SAND -> Blocks.SAND.defaultBlockState();
                case GRAVEL -> Blocks.GRAVEL.defaultBlockState();
                case MAGMA -> Blocks.MAGMA_BLOCK.defaultBlockState();
                case GILDED_BLACKSTONE -> Blocks.GILDED_BLACKSTONE.defaultBlockState();
                case GOLD_ORE -> Blocks.GOLD_ORE.defaultBlockState();
                case DEEPSLATE_GOLD_ORE -> Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState();
                case DEEPSLATE_DIAMOND_ORE -> Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState();
                case DEEPSLATE_COPPER_ORE -> Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState();
                case DEEPSLATE_IRON_ORE -> Blocks.DEEPSLATE_IRON_ORE.defaultBlockState();
                case DEEPSLATE_REDSTONE_ORE -> Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState();
                case IRON_ORE -> Blocks.IRON_ORE.defaultBlockState();
                case OBSIDIAN -> Blocks.OBSIDIAN.defaultBlockState();
                case CRYING_OBSIDIAN -> Blocks.CRYING_OBSIDIAN.defaultBlockState();
            };
            STATES.put(r, s);
            BY_ORDINAL[r.ordinal()] = s;
        }
        if (!missing.isEmpty()) {
            ExplorationRemix.LOGGER.warn("Create is not installed: its stones ({}) are replaced by vanilla blocks in the deep layers", String.join(", ", missing));
        }
    }

    private static BlockState create(String id, Block fallback, List<String> missing) {
        Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("create", id));
        if (b == null || b == Blocks.AIR) {
            missing.add(id);
            return fallback.defaultBlockState();
        }
        return b.defaultBlockState();
    }

    @Nullable
    public static BlockState state(@Nullable Rock r) {
        return r == null ? null : BY_ORDINAL[r.ordinal()];
    }

    /**
     * Rock below the pack's old floor, as decided while the noise is filled: bedrock at the very bottom, band stone in
     * the bands, the bedrock landing's bowl, and the layer's deep rock far from any open space. Returns null for rock
     * near open space, which is left as plain stone for the surface pass to dress per biome.
     */
    @Nullable
    public static BlockState solid(Terrain t, int x, int y, int z, double density) {
        Shape s = t.shape;
        if (y <= s.floorY + 4) {
            if (y == s.floorY) return BY_ORDINAL[Rock.BEDROCK.ordinal()];
            double chance = (s.floorY + 5 - y) / 5.0;
            if (Hashing.unit(Hashing.mix(t.seed ^ 0x51ED270B27F1A3C9L, x, y, z)) < chance) return BY_ORDINAL[Rock.BEDROCK.ordinal()];
        }
        int li = s.layerAt(y);
        if (li < 0) return BY_ORDINAL[Rock.BAND_STONE.ordinal()];
        if (li == s.layerCount - 1 && y < s.waterY(li) + 4 && t.bedrockLanding(x, y, z)) return BY_ORDINAL[Rock.BEDROCK.ordinal()];
        if (density > 0.75) return BY_ORDINAL[t.palette.interior(li, x, y, z).ordinal()];
        return null;
    }
}
