package com.explorationremix.world.feature;

import com.explorationremix.block.RuneBlock;
import com.explorationremix.block.entity.RuneBlockEntity;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.Layer;
import com.explorationremix.world.plan.Chamber;
import com.explorationremix.world.plan.Hashing;
import com.explorationremix.world.plan.LayerPlans;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * Builds the small, half-collapsed ruins the layer plan asked for in this chunk: a brick floor with gaps, broken
 * pillars, a little light, and a rune block on a pedestal in the middle.
 */
public class RuinFeature extends Feature<NoneFeatureConfiguration> {
    public RuinFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    private record Palette(BlockState brick, BlockState cracked, BlockState tile, BlockState slab, BlockState light, BlockState rubble) {}

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        if (!DeepWorld.installed()) return false;
        LayerPlans plans = DeepWorld.plans();
        if (plans == null) return false;
        WorldGenLevel level = ctx.level();
        BlockPos origin = ctx.origin();
        int chunkX = origin.getX() >> 4, chunkZ = origin.getZ() >> 4;
        boolean any = false;
        for (Layer layer : DeepWorld.layers()) {
            for (Chamber chamber : plans.ruinsInChunk(layer, chunkX, chunkZ)) {
                build(level, layer, chamber, plans.seed());
                any = true;
            }
        }
        return any;
    }

    private static BlockState lookup(String id, Block fallback) {
        Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
        return (b == null || b == Blocks.AIR ? fallback : b).defaultBlockState();
    }

    private static Palette palette(Layer layer) {
        return switch (layer.index()) {
            case 0 -> new Palette(lookup("create:cut_ochrum_bricks", Blocks.DEEPSLATE_BRICKS), Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState(),
                    lookup("create:cut_limestone_bricks", Blocks.DEEPSLATE_TILES), lookup("create:cut_ochrum_brick_slab", Blocks.DEEPSLATE_BRICK_SLAB),
                    ERBlocks.OCHRE_CRYSTAL.get().defaultBlockState(), Blocks.COBBLED_DEEPSLATE.defaultBlockState());
            case 1 -> new Palette(lookup("create:cut_asurine_bricks", Blocks.DEEPSLATE_BRICKS), Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState(),
                    Blocks.DEEPSLATE_TILES.defaultBlockState(), lookup("create:cut_asurine_brick_slab", Blocks.DEEPSLATE_BRICK_SLAB),
                    ERBlocks.ASURINE_CRYSTAL.get().defaultBlockState(), Blocks.TUFF.defaultBlockState());
            case 2 -> new Palette(lookup("create:cut_crimsite_bricks", Blocks.DEEPSLATE_BRICKS), Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState(),
                    Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(), lookup("create:cut_crimsite_brick_slab", Blocks.DEEPSLATE_BRICK_SLAB),
                    ERBlocks.HEMATITE_CRYSTAL.get().defaultBlockState(), Blocks.BLACKSTONE.defaultBlockState());
            default -> new Palette(lookup("create:cut_scoria_bricks", Blocks.POLISHED_BLACKSTONE_BRICKS), Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultBlockState(),
                    lookup("create:cut_scorchia_bricks", Blocks.POLISHED_BASALT), lookup("create:cut_scoria_brick_slab", Blocks.POLISHED_BLACKSTONE_BRICK_SLAB),
                    Blocks.SHROOMLIGHT.defaultBlockState(), Blocks.BASALT.defaultBlockState());
        };
    }

    private static void build(WorldGenLevel level, Layer layer, Chamber chamber, long seed) {
        long h = Hashing.mix(seed, 9001, (long) Math.floor(chamber.x()), (long) Math.floor(chamber.z()));
        RandomSource random = RandomSource.create(h);
        Palette p = palette(layer);
        int cx = (int) Math.floor(chamber.x()), cz = (int) Math.floor(chamber.z());
        int floorY = chamber.floorY();
        // Find the real floor under the centre: the ellipsoid floor can be a block or two off after interpolation.
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(cx, floorY + 3, cz);
        int tries = 0;
        while (tries++ < 12 && !level.getBlockState(probe).isAir()) probe.move(Direction.UP);
        tries = 0;
        while (tries++ < 16 && probe.getY() > layer.bottom() + 2 && level.getBlockState(probe.below()).isAir()) probe.move(Direction.DOWN);
        int y = probe.getY();
        if (y <= layer.bottom() + 2 || y >= layer.top() - 4) return;   // no floor found inside the layer
        int radius = chamber.kind() == Chamber.Kind.REFUGE ? 4 : (chamber.kind() == Chamber.Kind.BEDROCK_LANDING ? 5 : 3);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > radius + 0.5) continue;
                pos.set(cx + dx, y - 1, cz + dz);
                float r = random.nextFloat();
                boolean edge = d > radius - 1.2;
                if (edge && r < 0.45) continue;                       // collapsed rim
                BlockState floor = r < 0.15 ? p.cracked() : (r < 0.3 ? p.tile() : p.brick());
                level.setBlock(pos, floor, 2);
                // clear the space above the floor
                for (int dy = 0; dy < 4; dy++) {
                    pos.set(cx + dx, y + dy, cz + dz);
                    if (!level.getBlockState(pos).isAir() && level.getBlockState(pos).getFluidState().isEmpty()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                }
                if (edge && random.nextFloat() < 0.2) level.setBlock(pos.set(cx + dx, y, cz + dz), p.rubble(), 2);
            }
        }
        // Corner pillars of uneven height, some fallen.
        int pr = radius - 1;
        int[][] corners = {{pr, pr}, {-pr, pr}, {pr, -pr}, {-pr, -pr}};
        for (int[] c : corners) {
            int height = 1 + random.nextInt(4);
            if (random.nextFloat() < 0.25) height = 0;
            for (int dy = 0; dy < height; dy++) {
                pos.set(cx + c[0], y + dy, cz + c[1]);
                level.setBlock(pos, random.nextFloat() < 0.3 ? p.cracked() : p.brick(), 2);
            }
            if (height >= 3 && random.nextFloat() < 0.6) level.setBlock(pos.set(cx + c[0], y + height, cz + c[1]), p.light(), 2);
        }
        // Pedestal and the rune block.
        level.setBlock(pos.set(cx, y, cz), slab(p.slab(), SlabType.BOTTOM), 2);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (random.nextFloat() < 0.7) level.setBlock(pos.set(cx + dir.getStepX(), y, cz + dir.getStepZ()), slab(p.slab(), SlabType.BOTTOM), 2);
        }
        BlockPos runePos = new BlockPos(cx, y + 1, cz);
        level.setBlock(runePos, ERBlocks.RUNE_BLOCK.get().defaultBlockState(), 2);
        BlockEntity be = level.getBlockEntity(runePos);
        if (be instanceof RuneBlockEntity rune) {
            int tier = layer.index() + 1;   // rune tiers start at 1: the first layer's refuges offer tier-1 runes
            if (chamber.kind() == Chamber.Kind.HUNTING_RUIN) tier += 1;
            if (chamber.kind() == Chamber.Kind.BEDROCK_LANDING) tier += 2;
            rune.setup(tier, chamber.kind().name().toLowerCase(), layer.id());
        }
        // Hunting-ground ruins are where explorers died: two sets of remains, each holding an attunement book.
        if (chamber.kind() == Chamber.Kind.HUNTING_RUIN) {
            int placed = 0;
            for (int i = 0; i < 24 && placed < 2; i++) {
                int dx = random.nextInt(radius * 2 + 7) - radius - 3, dz = random.nextInt(radius * 2 + 7) - radius - 3;
                if (Math.abs(dx) < 2 && Math.abs(dz) < 2) continue;
                pos.set(cx + dx, y, cz + dz);
                for (int dy = 2; dy >= -2 && !(level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)); dy--) pos.set(cx + dx, y + dy, cz + dz);
                if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) continue;
                level.setBlock(pos, ERBlocks.REMAINS.get().defaultBlockState(), 2);
                placed++;
            }
        }
        // A few scattered light blocks and rubble around the ruin.
        for (int i = 0; i < 6; i++) {
            int dx = random.nextInt(radius * 2 + 5) - radius - 2, dz = random.nextInt(radius * 2 + 5) - radius - 2;
            pos.set(cx + dx, y, cz + dz);
            if (level.getBlockState(pos).isAir() && !level.getBlockState(pos.below()).isAir()) {
                level.setBlock(pos, random.nextFloat() < 0.3 ? p.light() : p.rubble(), 2);
            }
        }
    }

    private static BlockState slab(BlockState slabState, SlabType type) {
        return slabState.hasProperty(SlabBlock.TYPE) ? slabState.setValue(SlabBlock.TYPE, type) : slabState;
    }

    @SuppressWarnings("unused")
    private static final List<String> NOTES = List.of("Ruins are deterministic per chamber: the same seed gives the same ruin.");
}
