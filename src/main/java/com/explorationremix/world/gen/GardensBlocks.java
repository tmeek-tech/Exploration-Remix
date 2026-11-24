package com.explorationremix.world.gen;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.block.Neon;
import com.explorationremix.block.NeonBlock;
import com.explorationremix.block.NeonMushroomsBlock;
import com.explorationremix.block.TubeWormsBlock;
import com.explorationremix.block.WallTubeWormsBlock;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.world.terrain.GardensField;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraftforge.registries.ForgeRegistries;

import static com.explorationremix.world.terrain.GardensField.*;

/**
 * The Deep Gardens' blocks (round 2, 0.18.10): the vent field's codes ({@link GardensField}) as block states, decided
 * while the noise is filled, so the surface pass leaves them alone (it only dresses the default stone).
 *
 * <p>Taran's picks: the cave walls are plain deepslate (no tuff, 2026-11-19) with black dripstone over much of their
 * surface, the rubble cobbled deepslate; the vents and spires vanilla blackstone ("Vents should be blackstone",
 * 2026-11-19), dithered into the deepslate round each vent's foot; the neon blocks glow in the vent's colour. The seep pools
 * hold mcremix's crude oil ("mcremix has oil now. Can you use that?"), one block deep ("Knee-deep, one block": rock
 * fills each pool to a block under its surface), as still source blocks that only flow once a block beside them
 * changes; without mcremix the dips stay dry.
 *
 * <p>0.20.2's plant life (GardensDress.plants) by colour, each piece read from its own column: filament tufts or, with
 * the plant bit, the grass; tall grass's upper half over its lower; a glowworm thread's tip at the bottom of its run;
 * a seep reed one block or the two halves of a tall one; one or two small mushrooms, or three with the bit.
 *
 * <p>0.20.6's tube worms: a clump's thick middle with the bit, its thin edge without; standing on the block under them
 * if that holds them, else on a wall beside them (the first of the four sides that holds them, from a side picked
 * per block), leaning out from it.
 */
public final class GardensBlocks {
    private static final BlockState[][] STATES = new BlockState[32][4];
    /** Pointed black dripstone by direction (0 hanging, 1 standing) and thickness. */
    private static final BlockState[][] POINTED = new BlockState[2][DripstoneThickness.values().length];
    private static BlockState deepslate, oil;
    /** 0.20.2's plants: by colour (and the mushrooms by count); the threads and reeds are light blue alone. */
    private static final BlockState[] TUFT = new BlockState[4], GRASS = new BlockState[4], TALL_LOWER = new BlockState[4],
            TALL_UPPER = new BlockState[4];
    /** 0.20.6's tube worms: standing, by thickness (0 a clump's middle, 1 its edge) and colour; on a wall, by the way they lean too. */
    private static final BlockState[][] WORMS = new BlockState[2][4];
    private static final BlockState[][][] WALL_WORMS = new BlockState[4][2][4];
    private static final BlockState[][] SHROOMS = new BlockState[4][3];
    private static BlockState threadTip, threadBody, reed, reedLower, reedUpper;

    private GardensBlocks() {}

    /** Looks the blocks up once the registries are ready (with {@link DeepMaterials#resolve}). */
    public static void resolve() {
        deepslate = Blocks.DEEPSLATE.defaultBlockState();
        for (BlockState[] row : STATES) java.util.Arrays.fill(row, Blocks.CAVE_AIR.defaultBlockState());
        plain(C_ROCK, deepslate);
        plain(C_SEALROCK, deepslate);
        plain(C_TALUS, Blocks.COBBLED_DEEPSLATE.defaultBlockState());
        BlockState blackstone = Blocks.BLACKSTONE.defaultBlockState();
        plain(C_DEAD, blackstone);
        plain(C_VDEAD, blackstone);
        plain(C_LIVE, blackstone);
        plain(C_VLIVE, blackstone);
        plain(C_VBASE, blackstone);
        plain(C_DRIP, ERBlocks.BLACK_DRIPSTONE.get().defaultBlockState());
        plain(C_SKIN, ERBlocks.BLACK_DRIPSTONE.get().defaultBlockState());
        for (int d = 0; d < 2; d++)
            for (DripstoneThickness th : DripstoneThickness.values())
                POINTED[d][th.ordinal()] = ERBlocks.POINTED_BLACK_DRIPSTONE.get().defaultBlockState()
                        .setValue(PointedDripstoneBlock.TIP_DIRECTION, d == 0 ? Direction.DOWN : Direction.UP)
                        .setValue(PointedDripstoneBlock.THICKNESS, th);
        plain(C_STEM, ERBlocks.PALE_STEM.get().defaultBlockState());
        plain(C_SEEP, ERBlocks.SEEP_CRUST.get().defaultBlockState());
        neon(C_MAT, ERBlocks.GLOW_MAT.get());
        neon(C_CAP, ERBlocks.GLOW_CAP.get());
        neon(C_CROWN, ERBlocks.VENT_CROWN.get());
        // round 4: den fissure, vent glow and throat vein left the game; the dressing no longer writes their codes,
        // which would place vent beads
        neon(C_FISSURE, ERBlocks.VENT_BEADS.get());
        neon(C_VGLOW, ERBlocks.VENT_BEADS.get());
        neon(C_TVEIN, ERBlocks.VENT_BEADS.get());
        neon(C_TIP, ERBlocks.VENT_BEADS.get());
        neon(C_STREAK, ERBlocks.VENT_BEADS.get());
        neon(C_MATE, ERBlocks.THIN_GLOW_MAT.get());
        for (Neon n : Neon.values()) {
            int o = n.ordinal();
            TUFT[o] = ERBlocks.FILAMENT_TUFT.get().defaultBlockState().setValue(NeonBlock.NEON, n);
            GRASS[o] = ERBlocks.FILAMENT_GRASS.get().defaultBlockState().setValue(NeonBlock.NEON, n);
            BlockState tall = ERBlocks.TALL_FILAMENT_GRASS.get().defaultBlockState().setValue(NeonBlock.NEON, n);
            TALL_LOWER[o] = tall.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER);
            TALL_UPPER[o] = tall.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER);
            for (int th = 0; th < 2; th++) {
                WORMS[th][o] = ERBlocks.TUBE_WORMS.get().defaultBlockState().setValue(NeonBlock.NEON, n).setValue(TubeWormsBlock.OUT, true)
                        .setValue(TubeWormsBlock.SPARSE, th == 1);
                for (int f = 0; f < 4; f++)
                    WALL_WORMS[f][th][o] = ERBlocks.WALL_TUBE_WORMS.get().defaultBlockState().setValue(NeonBlock.NEON, n)
                            .setValue(TubeWormsBlock.OUT, true).setValue(TubeWormsBlock.SPARSE, th == 1)
                            .setValue(WallTubeWormsBlock.FACING, Direction.from2DDataValue(f));
            }
            for (int k = 0; k < 3; k++)
                SHROOMS[o][k] = ERBlocks.NEON_MUSHROOMS.get().defaultBlockState().setValue(NeonBlock.NEON, n).setValue(NeonMushroomsBlock.MUSHROOMS, k + 1);
        }
        threadTip = ERBlocks.GLOWWORM_THREADS.get().defaultBlockState();
        threadBody = ERBlocks.GLOWWORM_THREADS_PLANT.get().defaultBlockState();
        reed = ERBlocks.SEEP_REEDS.get().defaultBlockState();
        reedLower = ERBlocks.TALL_SEEP_REEDS.get().defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER);
        reedUpper = ERBlocks.TALL_SEEP_REEDS.get().defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER);
        Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("mcremix", "crude_oil"));
        oil = b instanceof LiquidBlock ? b.defaultBlockState() : null;
        if (oil == null) ExplorationRemix.LOGGER.info("mcremix's crude oil is not installed: the Deep Gardens' seep pools stay dry");
    }

    private static void plain(int code, BlockState s) { java.util.Arrays.fill(STATES[code], s); }

    private static void neon(int code, Block b) {
        for (Neon n : Neon.values()) STATES[code][n.ordinal()] = b.defaultBlockState().setValue(NeonBlock.NEON, n);
    }

    /**
     * Pointed black dripstone's state from its run in the column, by vanilla's rules: a lone block is a tip, two are a
     * frustum over a tip, longer runs a base, middles, a frustum and a tip; hanging for C_PDOWN, standing for C_PUP.
     * The dressing never lets a hanging tip meet a standing one, so there is no tip_merge.
     */
    private static BlockState pointed(GardensField g, int x, int row, int z, int c, int rows) {
        int step = c == C_PDOWN ? -1 : 1;                       // towards the tip
        int toTip = 0;
        for (int r = row + step; r >= 0 && r < rows && toTip < 2 && (g.packed(x, r, z) & 31) == c; r += step) toTip++;
        int back = row - step;
        boolean more = back >= 0 && back < rows && (g.packed(x, back, z) & 31) == c;
        DripstoneThickness th = toTip == 0 ? DripstoneThickness.TIP
                : (toTip == 1 ? DripstoneThickness.FRUSTUM : (more ? DripstoneThickness.MIDDLE : DripstoneThickness.BASE));
        return POINTED[c == C_PDOWN ? 0 : 1][th.ordinal()];
    }

    /** Tube worms at row {@code row} of column (x, z): on the floor if the block under holds them, else on a wall beside them. */
    private static BlockState worms(GardensField g, int x, int row, int z, int colour, boolean middle) {
        int th = middle ? 0 : 1;
        if (row == 0 || holds(g.packed(x, row - 1, z) & 31)) return WORMS[th][colour];
        int first = (int) ((Mth.getSeed(x, row, z) >>> 24) & 3);
        for (int k = 0; k < 4; k++) {
            Direction side = Direction.from2DDataValue((first + k) & 3);
            if (holds(g.packed(x + side.getStepX(), row, z + side.getStepZ()) & 31))
                return WALL_WORMS[side.getOpposite().get2DDataValue()][th][colour];
        }
        return WORMS[th][colour];       // nothing beside them holds them (the dressing leaves none so)
    }

    /** Whether the block at row r of the column is code c (false outside the layer). */
    private static boolean is(GardensField g, int x, int r, int z, int rows, int c) {
        return r >= 0 && r < rows && (g.packed(x, r, z) & 31) == c;
    }

    /** The block at (x, y, z) of the Deep Gardens. */
    public static BlockState state(Terrain t, int x, int y, int z) {
        GardensField g = t.gardens;
        int row = y - t.shape.bottom(Terrain.GARDENS), rows = t.shape.height(Terrain.GARDENS);
        int p = g.packed(x, row, z), c = p & 31, colour = (p >> 5) & 3;
        boolean bit = (p & PLANT_BIT) != 0;
        switch (c) {
            case C_GRASS:
                return (bit ? GRASS : TUFT)[colour];
            case C_TALLG:
                return (is(g, x, row - 1, z, rows, C_TALLG) ? TALL_UPPER : TALL_LOWER)[colour];
            case C_WORM:
                return worms(g, x, row, z, colour, bit);
            case C_THREAD:
                return is(g, x, row - 1, z, rows, C_THREAD) ? threadBody : threadTip;
            case C_REED:
                if (is(g, x, row - 1, z, rows, C_REED)) return reedUpper;
                return is(g, x, row + 1, z, rows, C_REED) ? reedLower : reed;
            case C_SHROOM:
                // crowded, three; else one or two by the spot
                return SHROOMS[colour][bit ? 2 : (int) ((Mth.getSeed(x, row, z) >>> 20) & 1L)];
            case C_ROCK:
                return deepslate;
            case C_OIL:
                // knee-deep: only a pool's top block holds oil, rock fills it under that
                if (row + 1 < t.shape.height(Terrain.GARDENS) && (g.packed(x, row + 1, z) & 31) == C_OIL) return deepslate;
                return oil != null ? oil : DeepMaterials.CAVE_AIR;
            case C_PDOWN:
            case C_PUP:
                return pointed(g, x, row, z, c, t.shape.height(Terrain.GARDENS));
            default:
                return STATES[c][(p >> 5) & 3];
        }
    }
}
