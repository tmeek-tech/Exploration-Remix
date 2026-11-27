package com.explorationremix.creature;

import com.explorationremix.creature.body.EmberWalk;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Where an ember urchin may be (0.23.7): its own ground (the Wasteland's tunnels and caves, never the Ash Hollows, the
 * cards' "Keeps out of the Ash Hollows"), the rock it reads as it walks (a loaded level's, a chunk being made's), and
 * the spots one can be set down on: its middle at its standing height over a tunnel's floor, its ball clear of the rock
 * ({@link EmberWalk#standAt}, {@link EmberWalk#clear}). Used by the urchin itself, the world's making
 * (world.feature.EmberUrchinsFeature) and the top-up near players ({@link EmberRefill}).
 */
public final class EmberSpots {
    private EmberSpots() {}

    /** Where one can stand: its middle. */
    public record Spot(double x, double y, double z) {}

    /** Whether a middle at (x, y, z) lies in the Wasteland, out of the Ash Hollows (anywhere, with no deep world). */
    public static boolean inWasteland(double x, double y, double z) {
        Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        if (t == null || t.wasteland == null) return true;
        int by = Mth.floor(y);
        if (t.shape.layerAt(by) != Terrain.WASTELAND) return false;
        return t.wasteland.biome(Mth.floor(x), by, Mth.floor(z)) != DeepBiome.ASH_HOLLOWS;
    }

    /** Whether a middle at (x, y, z) lies in the Wasteland's layer at all (Ash Hollows or not). */
    public static boolean inLayer(double y) {
        Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        return t != null && t.shape.layerAt(Mth.floor(y)) == Terrain.WASTELAND;
    }

    /** The Wasteland's rows (bottom, top), or null with no deep world. */
    @Nullable
    public static int[] rows() {
        Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        if (t == null || t.wasteland == null) return null;
        return new int[]{t.shape.bottom(Terrain.WASTELAND), t.shape.top(Terrain.WASTELAND)};
    }

    /**
     * The spots over column (x, z) from row {@code top} down to row {@code bottom}: one over each floor there with room
     * for the ball over it, its middle where {@link EmberWalk#standAt} puts it, clear of the rock and on {@code g}'s ground.
     */
    public static List<Spot> floors(EmberWalk.Ground g, int x, int z, int top, int bottom) {
        List<Spot> out = new ArrayList<>(2);
        double cx = x + 0.5, cz = z + 0.5;
        boolean wasOpen = false;
        int runTop = top;
        for (int y = top; y >= bottom; y--) {
            boolean open = !g.solid(x, y, z);
            if (open && !wasOpen) runTop = y;
            if (!open && wasOpen) {
                // the floor is this block's top (y + 1); the room over it runs up to runTop
                int room = runTop - y;
                if (room >= 3) {
                    double my = EmberWalk.standAt(g, cx, y + 1 + Math.min(room * 0.5, EmberWalk.STAND), cz);
                    if (!Double.isNaN(my) && EmberWalk.clear(g, cx, my, cz, EmberWalk.CLEAR) && g.allowed(cx, my, cz))
                        out.add(new Spot(cx, my, cz));
                }
            }
            wasOpen = open;
        }
        return out;
    }

    /** A spot it is set down on has a way through it: some way round it it could walk this far, and as far back across. */
    static final double WAY_ON = 3.0;

    /**
     * Whether it could walk on from a spot, not only pace round a pocket (a cramped stretch of a climb between storeys,
     * say): of eight ways round it, one runs on at least {@link #WAY_ON} ({@link EmberWalk#probe}), and so does one
     * roughly across from it.
     */
    public static boolean wayThrough(EmberWalk.Ground g, Spot s) {
        EmberWalk walk = new EmberWalk();
        Vector3d at = new Vector3d(s.x(), s.y(), s.z()), way = new Vector3d();
        boolean[] on = new boolean[8];
        for (int k = 0; k < 8; k++) {
            double a = Math.PI * 0.25 * k;
            on[k] = walk.probe(g, at, way.set(Math.cos(a), 0, Math.sin(a))) >= WAY_ON;
        }
        for (int k = 0; k < 8; k++) if (on[k] && (on[(k + 3) % 8] || on[(k + 4) % 8] || on[(k + 5) % 8])) return true;
        return false;
    }

    /** A spot it is set down on lets it roam: the ground it could walk runs on at least ROAM blocks from it (or holds ROAM_CELLS columns). */
    static final int ROAM = 14, ROAM_CELLS = 400;
    static final double ROAM_SPARE = 0.25;
    private static final int[] RX = {1, -1, 0, 0, 1, 1, -1, -1}, RZ = {0, 0, 1, -1, 1, -1, 1, -1};

    /**
     * Whether it could roam from a spot, not only pace up and down a short stretch shut in at both ends (a landing
     * between two flights of a climb between storeys, too narrow for it): the columns it could walk to from the spot, a
     * block at a time as it steps ({@link EmberWalk#step}), reach {@link #ROAM} from it, or are many (a cave).
     */
    public static boolean roams(EmberWalk.Ground g, Spot s) {
        HashSet<Long> seen = new HashSet<>();
        ArrayDeque<double[]> open = new ArrayDeque<>();
        open.add(new double[]{s.x(), s.y(), s.z()});
        seen.add(BlockPos.asLong(Mth.floor(s.x()), Mth.floor(s.y()), Mth.floor(s.z())));
        int cells = 0;
        while (!open.isEmpty()) {
            double[] c = open.poll();
            if (++cells >= ROAM_CELLS) return true;
            for (int k = 0; k < 8; k++) {
                double x = c[0] + RX[k], z = c[2] + RZ[k];
                // each step as steep as one of its own (a probe's) whatever its length, and with room to spare, so that it
                // reaches no more than it walks (block by block a squeeze it only just fits looks wider than it walks)
                double y = EmberWalk.step(g, c[1], x, z, EmberWalk.PROBE_STEP, EmberWalk.CLEAR + ROAM_SPARE);
                if (Double.isNaN(y) || !seen.add(BlockPos.asLong(Mth.floor(x), Mth.floor(y), Mth.floor(z)))) continue;
                double dx = x - s.x(), dz = z - s.z();
                if (dx * dx + dz * dz >= ROAM * ROAM) return true;
                open.add(new double[]{x, y, z});
            }
        }
        return false;
    }

    /** How wide the way is at a spot: the narrower of its free spans along x and along z (blocks, at most 16). */
    public static double width(EmberWalk.Ground g, double x, double y, double z) {
        double sx = span(g, x, y, z, 1, 0) + span(g, x, y, z, -1, 0);
        double sz = span(g, x, y, z, 0, 1) + span(g, x, y, z, 0, -1);
        return Math.max(1.0, Math.min(16.0, Math.min(sx, sz)));
    }

    private static double span(EmberWalk.Ground g, double x, double y, double z, int dx, int dz) {
        for (double d = 0.5; d <= 8.0; d += 0.5)
            if (g.solid(Mth.floor(x + dx * d), Mth.floor(y), Mth.floor(z + dz * d))) return d;
        return 8.0;
    }

    // ------------------------------------------------------------------------------------------------ grounds

    /**
     * A loaded level's rock: any block with a collision box (lava, light and plants are open), a block in a chunk not
     * loaded is rock (so nothing walks or is set down into one), and no chunk is ever loaded to read one. {@link #fresh}
     * once a tick, as a chunk may have come or gone.
     */
    public static final class LevelGround implements EmberWalk.Ground {
        private final ServerLevel level;
        private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        private int cx = Integer.MIN_VALUE, cz;
        @Nullable private LevelChunk chunk;
        /** Keeps to the Wasteland and out of the Ash Hollows (else anywhere: one set down by an egg elsewhere walks there). */
        public boolean keepToWasteland = true;
        /** Started in the Ash Hollows (an egg there): anywhere in the layer, so it can walk out. */
        public boolean inHollows;

        public LevelGround(ServerLevel level) { this.level = level; }

        public void fresh() { cx = Integer.MIN_VALUE; chunk = null; }

        @Override
        public boolean solid(int x, int y, int z) {
            int kx = x >> 4, kz = z >> 4;
            if (kx != cx || kz != cz) {
                cx = kx;
                cz = kz;
                chunk = level.getChunkSource().getChunkNow(kx, kz);
            }
            if (chunk == null) return true;
            at.set(x, y, z);
            BlockState s = chunk.getBlockState(at);
            if (s.isAir()) return false;
            return !s.getCollisionShape(level, at).isEmpty();
        }

        @Override
        public boolean allowed(double x, double y, double z) {
            if (!keepToWasteland) return true;
            return inHollows ? inLayer(y) : inWasteland(x, y, z);
        }
    }

    /**
     * A chunk being made's rock, read only in it and the chunks round it (beyond them, rock): for setting urchins down
     * with the world.
     */
    public static final class RegionGround implements EmberWalk.Ground {
        private final BlockGetter level;
        private final ChunkPos centre;
        private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();

        public RegionGround(WorldGenLevel level, ChunkPos centre) {
            this.level = level;
            this.centre = centre;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            if (Math.abs((x >> 4) - centre.x) > 1 || Math.abs((z >> 4) - centre.z) > 1) return true;
            at.set(x, y, z);
            BlockState s = level.getBlockState(at);
            if (s.isAir()) return false;
            return !s.getCollisionShape(level, at).isEmpty();
        }

        @Override
        public boolean allowed(double x, double y, double z) { return inWasteland(x, y, z); }
    }
}
