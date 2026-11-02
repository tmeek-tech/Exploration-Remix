package com.explorationremix.world.feature;

import com.explorationremix.block.BoneScrapsBlock;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Plans;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * The Mossback lairs at the den gates, the ways down from the Upper Caves to the Blue Reaches: on each gate's upper
 * landing a den ({@code creature.MossbackDen}) on the floor ring round the passage's mouth, bones and scraps strewn over
 * the floors within 30 blocks of it (the landing, the ramp up and any of the pack's caves in reach), and a few bone
 * blocks set into the landing floor. They are the only signs of a den; nothing marks the way to one further out.
 * <p>
 * Runs in every chunk after the ways are opened (open_way), as band 1 is the Deep Blue Rift under every column. Where
 * everything goes is planned by {@link DenLair} from the seed and the gate, so every chunk agrees on it, and each chunk
 * writes only inside itself: the den from the one chunk that holds its spots, and each scrap or bone block from the
 * chunk holding its column.
 */
public final class DenLairFeature extends Feature<NoneFeatureConfiguration> {
    /**
     * Bone blocks look this far round their planned column (inside the chunk) for floor they can be set into: out to
     * three blocks, nearest first, since much of a landing's floor is the bands' stone, which they never replace.
     */
    private static final int[][] AROUND = around(3);

    private static int[][] around(int r) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) out.add(new int[]{dx, dz});
        // nearest first (ring by ring, then the straight-across ones before the diagonals); a stable sort keeps it fixed
        out.sort(java.util.Comparator.comparingInt((int[] o) -> Math.max(Math.abs(o[0]), Math.abs(o[1])))
                .thenComparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        return out.toArray(new int[0][]);
    }

    public DenLairFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        int minX = cp.getMinBlockX(), minZ = cp.getMinBlockZ(), maxX = minX + 15, maxZ = minZ + 15;
        Plans plans = t.plans;
        int cellX = plans.cellOf(minX + 8), cellZ = plans.cellOf(minZ + 8);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int dcx = -1; dcx <= 1; dcx++)
            for (int dcz = -1; dcz <= 1; dcz++)
                for (Plans.Gate g : plans.denGates(cellX + dcx, cellZ + dcz)) {
                    if (!DenLair.mayReach(g, minX, minZ, maxX, maxZ)) continue;
                    DenLair lair = DenLair.of(t.seed, g, plans.ramp(g));
                    if (lair.denIn(cp.x, cp.z)) {
                        int[] den = lair.den((x, y, z) -> fits(level, x, y, z), (x, y, z) -> fitsAlone(level, x, y, z));
                        if (den != null) {
                            level.setBlock(pos.set(den[0], den[1], den[2]), ERBlocks.MOSSBACK_DEN.get().defaultBlockState(), 2);
                            any = true;
                        }
                    }
                    for (DenLair.Point b : lair.bones)
                        if (inside(b.x(), b.z(), minX, minZ, maxX, maxZ)) any |= sinkBone(level, lair, b, minX, minZ, maxX, maxZ, pos);
                    for (DenLair.Point s : lair.scraps)
                        if (inside(s.x(), s.z(), minX, minZ, maxX, maxZ)) any |= strew(level, lair, s, pos);
                }
        return any;
    }

    private static boolean inside(int x, int z, int minX, int minZ, int maxX, int maxZ) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /**
     * A scrap on the lowest floor of its column, from {@link DenLair#SCAN_BELOW} under the den's height up to no more
     * than {@link DenLair#REACH} from the lair's centre (and at most {@link DenLair#SCAN_ABOVE} up): open air over a
     * block with a sturdy top. The lowest, so a ledge high over the landing never takes the scrap from the landing's
     * own floor. Only into air, so it never takes a plant's place, and never in the passage's mouth.
     */
    private static boolean strew(WorldGenLevel level, DenLair lair, DenLair.Point s, BlockPos.MutableBlockPos pos) {
        double dx = s.x() + 0.5 - lair.centreX, dz = s.z() + 0.5 - lair.centreZ;
        double up = Math.sqrt(Math.max(0.0, DenLair.REACH * DenLair.REACH - dx * dx - dz * dz));
        int top = Math.min(level.getMaxBuildHeight() - 1, lair.floorY + Math.min(DenLair.SCAN_ABOVE, (int) Math.floor(up)));
        int bottom = Math.max(level.getMinBuildHeight() + 1, lair.floorY - DenLair.SCAN_BELOW);
        for (int y = bottom; y <= top; y++) {
            pos.set(s.x(), y, s.z());
            BlockState here = level.getBlockState(pos);
            if (!here.getCollisionShape(level, pos).isEmpty()) continue;
            pos.setY(y - 1);
            BlockState below = level.getBlockState(pos);
            if (!below.isFaceSturdy(level, pos, Direction.UP) || !below.getFluidState().isEmpty()) continue;
            // the lowest floor up the column: strewn here or not at all
            if (!here.isAir() || !here.getFluidState().isEmpty() || lair.mouth(s.x(), y, s.z())) return false;
            BlockState scrap = ERBlocks.BONE_SCRAPS.get().defaultBlockState()
                    .setValue(BoneScrapsBlock.LOOK, s.look()).setValue(BoneScrapsBlock.FACING, Direction.from2DDataValue(s.turn()));
            level.setBlock(pos.set(s.x(), y, s.z()), scrap, 2);
            return true;
        }
        return false;
    }

    /**
     * A bone block set into the landing floor, so nothing sticks up: the floor block itself is replaced, at the planned
     * column or the first of its neighbours inside this chunk whose floor lies near the landing's and may be replaced
     * (never the bands' stone, which nothing breaks, nor anything in the passage's mouth).
     */
    private static boolean sinkBone(WorldGenLevel level, DenLair lair, DenLair.Point b, int minX, int minZ, int maxX, int maxZ,
                                    BlockPos.MutableBlockPos pos) {
        for (int[] o : AROUND) {
            int x = b.x() + o[0], z = b.z() + o[1];
            if (!inside(x, z, minX, minZ, maxX, maxZ)) continue;
            for (int y = lair.floorY + DenLair.DEN_UP; y >= lair.floorY - DenLair.DEN_DOWN; y--) {
                pos.set(x, y, z);
                if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) continue;
                pos.setY(y - 1);
                BlockState floor = level.getBlockState(pos);
                if (!floor.isFaceSturdy(level, pos, Direction.UP)) continue;
                // the floor of this column: set the bone into it, or try the next column
                // (a column another bone took is passed over, so each planned bone is its own block)
                if (floor.isCollisionShapeFullBlock(level, pos) && !floor.is(Blocks.BONE_BLOCK) && !floor.hasBlockEntity() && floor.getFluidState().isEmpty()
                        && !floor.is(BlockTags.FEATURES_CANNOT_REPLACE) && !floor.is(Blocks.BEDROCK) && !lair.mouth(x, y, z)) {
                    level.setBlock(pos, Blocks.BONE_BLOCK.defaultBlockState(), 2);
                    return true;
                }
                break;
            }
        }
        return false;
    }

    /**
     * Open floor for a lizard (0.6.0's dens' test): solid ground under 2 x 2 cells, which hold at most a carpet or small
     * plant, and two blocks open above them. The den block goes at the low corner.
     */
    private static boolean fits(WorldGenLevel level, int x0, int y0, int z0) {
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 2; dx++)
            for (int dz = 0; dz < 2; dz++)
                if (!cellFits(level, q, x0 + dx, y0, z0 + dz, dx == 0 && dz == 0)) return false;
        return true;
    }

    /** The den block's own cell alone, for a landing with no 2 x 2 of open floor on its ring. */
    private static boolean fitsAlone(WorldGenLevel level, int x, int y, int z) {
        return cellFits(level, new BlockPos.MutableBlockPos(), x, y, z, true);
    }

    private static boolean cellFits(WorldGenLevel level, BlockPos.MutableBlockPos q, int x, int y, int z, boolean denCell) {
        q.set(x, y - 1, z);
        if (!level.getBlockState(q).isFaceSturdy(level, q, Direction.UP)) return false;
        q.set(x, y, z);
        BlockState s = level.getBlockState(q);
        if (!(s.isAir() || s.canBeReplaced()) || !s.getFluidState().isEmpty()) return false;
        // nothing the lizard's box would catch on (thick snow), and no tall plant whose top would be left floating
        if (s.getCollisionShape(level, q).max(Direction.Axis.Y) > 0.1) return false;
        if (denCell && s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return false;
        for (int dy = 1; dy <= 2; dy++) {
            q.set(x, y + dy, z);
            BlockState above = level.getBlockState(q);
            if (!above.getCollisionShape(level, q).isEmpty() || !above.getFluidState().isEmpty()) return false;
        }
        return true;
    }
}
