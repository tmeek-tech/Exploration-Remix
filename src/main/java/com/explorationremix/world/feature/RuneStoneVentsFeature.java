package com.explorationremix.world.feature;

import com.explorationremix.rune.stone.RuneStoneHost;
import com.explorationremix.rune.stone.RuneStoneKind;
import com.explorationremix.rune.stone.RuneStones;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.GardensField;
import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Deep Gardens' rune stones (0.23.1; Taran: "the Gardens runes in vents"; card picks "Inside the hollow chamber" and
 * "One in eight"): one vent in eight that has a way into its sunken chamber (an open crack) hides one in the chamber's
 * wall, at eye level over the chamber's floor where it can (else at the feet, else just over the head), in the vent's
 * blackstone or the deepslate round it, facing into the chamber. Which vents, by each vent's own key, so every chunk
 * agrees; the chunk holding a vent's base sets its stone, read from the blocks as they stand, and only the chunks round
 * this one are read or written.
 *
 * <p>Runs last in the vent field's chunks (top layer), once the chunk's blocks are all in.
 */
public final class RuneStoneVentsFeature extends Feature<NoneFeatureConfiguration> {
    /** The share of the vents with a way in that hide a stone. */
    public static final double SHARE = 1.0 / 8.0;
    /** How far under the sill the chamber's floor is looked for (rows), and how far from the axis its wall (blocks). */
    static final int FLOOR_DOWN = 12, REACH = 9;
    /** The ways looked along from the axis for the wall. */
    static final int WAYS = 16;
    private static final long SALT = 0x52554E45L;

    /** For /remix runestones: vents met that hide a stone, and stones set in them. */
    public static final AtomicLong VENTS = new AtomicLong(), SET = new AtomicLong();

    public RuneStoneVentsFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed() || t.gardens == null) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        int bottom = t.shape.bottom(Terrain.GARDENS);
        boolean any = false;
        for (GardensField.VentChamber v : t.gardens.ventChambers(cp.getMinBlockX(), cp.getMinBlockZ(), cp.getMinBlockX() + 16, cp.getMinBlockZ() + 16)) {
            if (!hides(v)) continue;
            VENTS.incrementAndGet();
            BlockPos at = spot(level, v, bottom, cp);
            if (at == null) continue;
            RuneStoneHost host = RuneStoneHost.of(level.getBlockState(at));
            if (host == null) continue;
            level.setBlock(at, RuneStoneKind.DEEP_GARDENS.state(host), 2);
            SET.incrementAndGet();
            RuneStones.placed(RuneStoneKind.DEEP_GARDENS);
            any = true;
        }
        return any;
    }

    /** Whether vent v hides a stone: one with a way into its chamber, one in eight of those, by its key. */
    public static boolean hides(GardensField.VentChamber v) {
        return v.cracks().length > 0 && Hashing.unit(Hashing.mix(v.key(), SALT, 7, 0)) < SHARE;
    }

    /**
     * The block of vent v's chamber wall that takes its stone, or null where the chamber gives none: the floor under its
     * axis (the first solid block down from just over the sill), then along each of {@link #WAYS} ways out from the axis
     * at eye height over that floor (then the feet's, then over the head) the first block the way meets, if it is a stone
     * the Gardens' rune stones hide in, a full block with open air on its face towards the axis. The ways start at an
     * angle of the vent's own, so which wall it is differs from vent to vent.
     */
    @Nullable
    static BlockPos spot(WorldGenLevel level, GardensField.VentChamber v, int bottom, ChunkPos cp) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int ax = Mth.floor(v.x()), az = Mth.floor(v.z());
        int sill = bottom + v.sillRow();
        int floor = Integer.MIN_VALUE;
        boolean above = false;
        for (int y = sill + 3; y >= sill - FLOOR_DOWN; y--) {
            if (open(level, p.set(ax, y, az))) {
                above = true;
                continue;
            }
            if (above) floor = y;
            break;
        }
        if (floor == Integer.MIN_VALUE) return null;
        double a0 = Hashing.unit(Hashing.mix(v.key(), SALT, 8, 0)) * Math.PI * 2;
        for (int up : new int[]{2, 1, 3}) {
            int y = floor + up;
            for (int k = 0; k < WAYS; k++) {
                double a = a0 + k * Math.PI * 2 / WAYS, dx = Math.cos(a), dz = Math.sin(a);
                int lastX = ax, lastZ = az;
                for (double d = 0.5; d <= REACH; d += 0.5) {
                    int x = Mth.floor(v.x() + dx * d), z = Mth.floor(v.z() + dz * d);
                    if (x == lastX && z == lastZ) continue;
                    if (Math.abs((x >> 4) - cp.x) > 1 || Math.abs((z >> 4) - cp.z) > 1) break;
                    if (open(level, p.set(x, y, z))) {
                        lastX = x;
                        lastZ = z;
                        continue;
                    }
                    BlockState s = level.getBlockState(p);
                    RuneStoneHost host = RuneStoneHost.of(s);
                    if (host != null && RuneStoneKind.DEEP_GARDENS.takes(host) && faceInward(level, x, y, z, v.x(), v.z())) return new BlockPos(x, y, z);
                    break;
                }
            }
        }
        return null;
    }

    /** Whether block (x, y, z) has open air on a face that looks back towards the axis at (cx, cz). */
    private static boolean faceInward(WorldGenLevel level, int x, int y, int z, double cx, double cz) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        double here = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
        int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : nb) {
            int nx = x + d[0], nz = z + d[1];
            if (Math.hypot(nx + 0.5 - cx, nz + 0.5 - cz) >= here) continue;
            if (open(level, p.set(nx, y, nz))) return true;
        }
        return false;
    }

    /** Whether a block is walked through: air, or a plant or anything else with nothing to stand on and no fluid. */
    static boolean open(WorldGenLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.isAir() || (s.getFluidState().isEmpty() && s.getCollisionShape(level, p).isEmpty());
    }
}
