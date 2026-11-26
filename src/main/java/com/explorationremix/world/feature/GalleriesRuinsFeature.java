package com.explorationremix.world.feature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.rune.stone.RuneStoneHost;
import com.explorationremix.rune.stone.RuneStoneKind;
import com.explorationremix.rune.stone.RuneStones;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.GalleriesRuins;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The lower Basalt Galleries' ruins (0.22.4; Taran: "Can you make some scattered ruins in the lower Basalt Galleries?
 * ... Ruins should come in clusters with bigger structures in the middle and smaller around the edges"): sets each
 * chunk's own blocks of the clusters {@link GalleriesRuins} plans, once per chunk, last of the Galleries' passes. The plan
 * is the field's alone, so every chunk a cluster spans agrees on it whichever is made first.
 *
 * <p>Its stone is the config's ({@link Palette}: dark deepslate, Taran's pick on the stone card, and an unknown name
 * reads as it; Create's blue asurine and calcite, whose blocks each fall back to a vanilla one where Create is absent,
 * and stone are the others). A block of the plan goes in only where it leaves no
 * fluid loose: open air is never set beside a fluid (nor over one), a fluid is only ever replaced by a full block, and
 * the plan's lava goes in last, where its block holds it in the world as built (solid under it and on its four sides,
 * or lava; open over it), else it is a chiseled block. The chests take their loot tables: the rune's ({@link
 * #RUNE_CHEST}: a rune for certain and some loot) and the others' ({@link #LOOT_CHEST}). Two or three of the walls'
 * blocks are rune stones (0.23.1, {@link GalleriesRuins#runes}).
 */
public final class GalleriesRuinsFeature extends Feature<GalleriesRuinsFeature.Config> {
    /** The rune's chest's table and the loot chests'. */
    public static final ResourceLocation RUNE_CHEST = new ResourceLocation(ExplorationRemix.MOD_ID, "chests/galleries_ruin_rune"),
            LOOT_CHEST = new ResourceLocation(ExplorationRemix.MOD_ID, "chests/galleries_ruin");

    /** The stone: a {@link Palette} name. */
    public record Config(String stone) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("stone").forGetter(Config::stone)).apply(i, Config::new));
    }

    public GalleriesRuinsFeature(Codec<Config> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed() || t.galleries == null) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        List<GalleriesRuins.Plan> plans = GalleriesRuins.of(t).near(x0, z0, x0 + 15, z0 + 15);
        if (plans.isEmpty()) return false;
        Palette pal = Palette.of(ctx.config().stone());
        boolean any = false;
        for (GalleriesRuins.Plan p : plans) any |= write(level, p, x0, z0, pal, ctx);
        return any;
    }

    /** Sets plan p's blocks in the chunk from (x0, z0): rock and air, then the chests, then the lava. */
    private static boolean write(WorldGenLevel level, GalleriesRuins.Plan p, int x0, int z0, Palette pal, FeaturePlaceContext<Config> ctx) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), nb = new BlockPos.MutableBlockPos();
        int ax = Math.max(x0, p.x0), bx = Math.min(x0 + 15, p.x0 + GalleriesRuins.SX - 1);
        int az = Math.max(z0, p.z0), bz = Math.min(z0 + 15, p.z0 + GalleriesRuins.SX - 1);
        if (ax > bx || az > bz) return false;
        boolean any = false;
        // the rock and the air, bottom up
        for (int x = ax; x <= bx; x++)
            for (int z = az; z <= bz; z++)
                for (int y = p.yMin(); y <= p.yMax(); y++) {
                    byte c = p.at(x, y, z);
                    if (c == GalleriesRuins.NONE || c == GalleriesRuins.LAVA || c >= GalleriesRuins.CHEST) continue;
                    pos.set(x, y, z);
                    BlockState cur = level.getBlockState(pos);
                    if (c == GalleriesRuins.AIR) {
                        if (cur.isAir() || !cur.getFluidState().isEmpty() || nearFluid(level, pos, nb)) continue;
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        any = true;
                        continue;
                    }
                    if (!cur.getFluidState().isEmpty() && !GalleriesRuins.full(c)) continue;
                    level.setBlock(pos, pal.state(c), 2);
                    any = true;
                }
        // 0.23.1: the rune stones hidden in its walls, where its stone is one they hide in (the deepslate the ruins are
        // built of unless the config chose another)
        for (int[] r : p.runes) {
            int x = r[0], y = r[1], z = r[2];
            if (x < ax || x > bx || z < az || z > bz) continue;
            BlockState stone = pal.state((byte) r[3]);
            RuneStoneHost host = RuneStoneHost.of(stone);
            if (host == null || !RuneStoneKind.GALLERIES.takes(host)) continue;
            pos.set(x, y, z);
            if (level.getBlockState(pos) != stone) continue;
            level.setBlock(pos, RuneStoneKind.GALLERIES.state(host), 2);
            RuneStones.placed(RuneStoneKind.GALLERIES);
            any = true;
        }
        // the walls' and the stairs' shapes, from what stands round them now
        for (int x = ax; x <= bx; x++)
            for (int z = az; z <= bz; z++)
                for (int y = p.yMin(); y <= p.yMax(); y++) {
                    byte c = p.at(x, y, z);
                    if (c != GalleriesRuins.WALL && (c < GalleriesRuins.STAIRS || c >= GalleriesRuins.STAIRS + 8)) continue;
                    pos.set(x, y, z);
                    BlockState s = level.getBlockState(pos);
                    BlockState shaped = Block.updateFromNeighbourShapes(s, level, pos);
                    if (shaped != s && !shaped.isAir()) level.setBlock(pos, shaped, 2);
                }
        // the chests, with their tables
        for (int[] ch : p.chests) {
            int x = ch[0], y = ch[1], z = ch[2];
            if (x < ax || x > bx || z < az || z > bz) continue;
            byte c = p.at(x, y, z);
            if (c < GalleriesRuins.CHEST) continue;
            pos.set(x, y, z);
            if (!level.getBlockState(pos).getFluidState().isEmpty()) continue;
            int d = (c - GalleriesRuins.CHEST) & 3;
            level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, side(d)), 2);
            RandomizableContainerBlockEntity.setLootTable(level, ctx.random(), pos, ch[3] == 1 ? RUNE_CHEST : LOOT_CHEST);
            any = true;
        }
        // the lava, where its block holds it as built: a side or the block under it holds it where the world's block
        // there does (rock, or a lava source) or where the plan lays lava there too, which this pass sets in turn (or
        // the chunk beside, as it is made; the plan holds every lava block with its neighbours laid, GalleriesRuins.held).
        // The rig's first look (0.22.4) found a hall's channel of 16 chiseled all but its last block: each was looked
        // at while the next one along was still air.
        for (int x = ax; x <= bx; x++)
            for (int z = az; z <= bz; z++)
                for (int y = p.yMin(); y <= p.yMax(); y++) {
                    if (p.at(x, y, z) != GalleriesRuins.LAVA) continue;
                    pos.set(x, y, z);
                    BlockState cur = level.getBlockState(pos);
                    if (!cur.getFluidState().isEmpty()) continue;
                    boolean held = holdsLava(level, p, nb.setWithOffset(pos, Direction.DOWN), true)
                            && level.getBlockState(nb.setWithOffset(pos, Direction.UP)).isAir();
                    for (Direction dir : Direction.Plane.HORIZONTAL)
                        held &= holdsLava(level, p, nb.setWithOffset(pos, dir), false);
                    level.setBlock(pos, held ? Blocks.LAVA.defaultBlockState() : pal.state(GalleriesRuins.CHISELED), 2);
                    any = true;
                }
        return any;
    }

    /**
     * Whether the block at q keeps a lava block beside it (or over it, {@code under}) still: rock (solid, for the floor)
     * or a lava source, as the world stands; or a block the plan lays lava in that holds no other fluid now.
     */
    private static boolean holdsLava(WorldGenLevel level, GalleriesRuins.Plan p, BlockPos q, boolean under) {
        BlockState s = level.getBlockState(q);
        if (under ? FloorLavaFeature.solid(s) || FloorLavaFeature.holds(s) : FloorLavaFeature.holds(s)) return true;
        return p.at(q.getX(), q.getY(), q.getZ()) == GalleriesRuins.LAVA && s.getFluidState().isEmpty();
    }

    /** Whether a fluid lies over block pos or on one of its four sides (air set there would loose it). */
    private static boolean nearFluid(WorldGenLevel level, BlockPos pos, BlockPos.MutableBlockPos nb) {
        if (!level.getBlockState(nb.setWithOffset(pos, Direction.UP)).getFluidState().isEmpty()) return true;
        for (Direction d : Direction.Plane.HORIZONTAL)
            if (!level.getBlockState(nb.setWithOffset(pos, d)).getFluidState().isEmpty()) return true;
        return false;
    }

    /** A plan's side (0 east, 1 south, 2 west, 3 north) as a direction. */
    static Direction side(int d) {
        return switch (d & 3) {
            case 0 -> Direction.EAST;
            case 1 -> Direction.SOUTH;
            case 2 -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    // =================================================================================================== the stone
    /**
     * A ruin stone: for each part (bricks, cracked bricks, tiles, cracked tiles, polished, chiseled, pillar, rubble,
     * brick slab, tile slab, wall, stairs) a list of block ids, the first one the game has taken. Create's stones fall back
     * to deepslate's (asurine) or quartz's (calcite) where Create is absent.
     */
    static final class Palette {
        static final int BRICKS = 0, CRACKED = 1, TILES = 2, CRACKED_TILES = 3, POLISHED = 4, CHISELED = 5, PILLAR = 6, RUBBLE = 7,
                SLAB = 8, TILE_SLAB = 9, WALL = 10, STAIRS = 11;
        static final Map<String, String[][]> STONES = Map.of(
                "asurine", new String[][]{
                        {"create:cut_asurine_bricks", "minecraft:deepslate_bricks"},
                        {"create:small_asurine_bricks", "minecraft:cracked_deepslate_bricks"},
                        {"create:cut_asurine", "minecraft:deepslate_tiles"},
                        {"create:small_asurine_bricks", "minecraft:cracked_deepslate_tiles"},
                        {"create:polished_cut_asurine", "minecraft:polished_deepslate"},
                        {"create:layered_asurine", "minecraft:chiseled_deepslate"},
                        {"create:asurine_pillar", "minecraft:polished_basalt"},
                        {"create:asurine", "minecraft:cobbled_deepslate"},
                        {"create:cut_asurine_brick_slab", "minecraft:deepslate_brick_slab"},
                        {"create:cut_asurine_slab", "minecraft:deepslate_tile_slab"},
                        {"create:cut_asurine_brick_wall", "minecraft:deepslate_brick_wall"},
                        {"create:cut_asurine_brick_stairs", "minecraft:deepslate_brick_stairs"}},
                "deepslate", new String[][]{
                        {"minecraft:deepslate_bricks"}, {"minecraft:cracked_deepslate_bricks"}, {"minecraft:deepslate_tiles"},
                        {"minecraft:cracked_deepslate_tiles"}, {"minecraft:polished_deepslate"}, {"minecraft:chiseled_deepslate"},
                        {"minecraft:polished_basalt"}, {"minecraft:cobbled_deepslate"}, {"minecraft:deepslate_brick_slab"},
                        {"minecraft:deepslate_tile_slab"}, {"minecraft:deepslate_brick_wall"}, {"minecraft:deepslate_brick_stairs"}},
                "calcite", new String[][]{
                        {"create:cut_calcite_bricks", "minecraft:quartz_bricks"},
                        {"create:small_calcite_bricks", "minecraft:quartz_bricks"},
                        {"create:cut_calcite", "minecraft:smooth_quartz"},
                        {"create:small_calcite_bricks", "minecraft:calcite"},
                        {"create:polished_cut_calcite", "minecraft:quartz_block"},
                        {"create:layered_calcite", "minecraft:chiseled_quartz_block"},
                        {"create:calcite_pillar", "minecraft:quartz_pillar"},
                        {"minecraft:calcite"},
                        {"create:cut_calcite_brick_slab", "minecraft:quartz_slab"},
                        {"create:cut_calcite_slab", "minecraft:smooth_quartz_slab"},
                        {"create:cut_calcite_brick_wall", "minecraft:diorite_wall"},
                        {"create:cut_calcite_brick_stairs", "minecraft:quartz_stairs"}},
                "stone", new String[][]{
                        {"minecraft:stone_bricks"}, {"minecraft:cracked_stone_bricks"}, {"minecraft:polished_andesite"},
                        {"minecraft:andesite"}, {"minecraft:smooth_stone"}, {"minecraft:chiseled_stone_bricks"},
                        {"minecraft:polished_basalt"}, {"minecraft:cobblestone"}, {"minecraft:stone_brick_slab"},
                        {"minecraft:smooth_stone_slab"}, {"minecraft:stone_brick_wall"}, {"minecraft:stone_brick_stairs"}});
        private static final Map<String, Palette> RESOLVED = new ConcurrentHashMap<>();

        final BlockState[] part = new BlockState[12];

        /** Stone {@code name}'s palette (blue asurine for a name it does not know). */
        static Palette of(String name) {
            return RESOLVED.computeIfAbsent(STONES.containsKey(name) ? name : "deepslate", n -> new Palette(STONES.get(n)));
        }

        private Palette(String[][] ids) {
            for (int k = 0; k < part.length; k++) part[k] = first(ids[k]);
        }

        private static BlockState first(String[] ids) {
            for (String id : ids) {
                ResourceLocation rl = ResourceLocation.tryParse(id);
                if (rl == null || !ForgeRegistries.BLOCKS.containsKey(rl)) continue;
                Block b = ForgeRegistries.BLOCKS.getValue(rl);
                if (b != null && b != Blocks.AIR) return b.defaultBlockState();
            }
            return Blocks.DEEPSLATE_BRICKS.defaultBlockState();
        }

        /** The block for code c of a plan. */
        BlockState state(byte c) {
            switch (c) {
                case GalleriesRuins.BRICKS: return part[BRICKS];
                case GalleriesRuins.CRACKED_BRICKS: return part[CRACKED];
                case GalleriesRuins.TILES: return part[TILES];
                case GalleriesRuins.CRACKED_TILES: return part[CRACKED_TILES];
                case GalleriesRuins.POLISHED: return part[POLISHED];
                case GalleriesRuins.CHISELED: return part[CHISELED];
                case GalleriesRuins.PILLAR: return axis(part[PILLAR], Direction.Axis.Y);
                case GalleriesRuins.PILLAR_X: return axis(part[PILLAR], Direction.Axis.X);
                case GalleriesRuins.PILLAR_Z: return axis(part[PILLAR], Direction.Axis.Z);
                case GalleriesRuins.COBBLED: return part[RUBBLE];
                case GalleriesRuins.SLAB: return slab(part[SLAB], SlabType.BOTTOM);
                case GalleriesRuins.SLAB_TOP: return slab(part[SLAB], SlabType.TOP);
                case GalleriesRuins.TILE_SLAB: return slab(part[TILE_SLAB], SlabType.BOTTOM);
                case GalleriesRuins.WALL: return part[WALL];
                case GalleriesRuins.CHAIN: return Blocks.CHAIN.defaultBlockState();
                default:
                    if (c >= GalleriesRuins.STAIRS && c < GalleriesRuins.STAIRS + 8) {
                        BlockState s = part[STAIRS];
                        if (!s.hasProperty(StairBlock.FACING)) return part[BRICKS];
                        int k = c - GalleriesRuins.STAIRS;
                        return s.setValue(StairBlock.FACING, side(k)).setValue(StairBlock.HALF, k >= 4 ? Half.TOP : Half.BOTTOM);
                    }
                    return part[BRICKS];
            }
        }

        private static BlockState axis(BlockState s, Direction.Axis a) {
            return s.hasProperty(BlockStateProperties.AXIS) ? s.setValue(BlockStateProperties.AXIS, a) : s;
        }

        private static BlockState slab(BlockState s, SlabType t) {
            return s.hasProperty(SlabBlock.TYPE) ? s.setValue(SlabBlock.TYPE, t) : s;
        }
    }
}
