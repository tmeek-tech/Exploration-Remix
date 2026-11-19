package com.explorationremix.world.gen;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.block.Neon;
import com.explorationremix.block.NeonBlock;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.world.terrain.GardensField;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
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
 */
public final class GardensBlocks {
    private static final BlockState[][] STATES = new BlockState[32][4];
    /** Pointed black dripstone by direction (0 hanging, 1 standing) and thickness. */
    private static final BlockState[][] POINTED = new BlockState[2][DripstoneThickness.values().length];
    private static BlockState deepslate, oil;

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
        neon(C_FISSURE, ERBlocks.DEN_FISSURE.get());
        neon(C_VGLOW, ERBlocks.VENT_GLOW.get());
        neon(C_TVEIN, ERBlocks.THROAT_VEIN.get());
        neon(C_TIP, ERBlocks.VENT_BEADS.get());
        neon(C_STREAK, ERBlocks.VENT_BEADS.get());
        neon(C_MATE, ERBlocks.GLOW_MAT.get());
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

    /** The block at (x, y, z) of the Deep Gardens. */
    public static BlockState state(Terrain t, int x, int y, int z) {
        GardensField g = t.gardens;
        int row = y - t.shape.bottom(Terrain.GARDENS);
        int p = g.packed(x, row, z), c = p & 31;
        switch (c) {
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
