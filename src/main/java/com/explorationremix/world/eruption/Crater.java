package com.explorationremix.world.eruption;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * What a lava bomb's blast does to the rock and to whoever stands near (0.9.0, spec 5.4.4 to 5.4.6), as plain rules over
 * cells and numbers: no game types, so it is tested offline on voxel boxes; {@link Blast} reads the world into it and
 * writes its answers back.
 *
 * <ul>
 *     <li><b>The partition</b> ({@link #part}): of the blocks the blast's rays reach, only with {@code breaks} (the reset on
 *     and no way down near) the natural ones in the Galleries, and the held ones of the Galleries' own rock (landed
 *     debris, a player's basalt), go to the crater, which the mod removes itself; a player's other blocks are left to
 *     the game's blast, which drops them as a creeper's would; nothing outside the layer, and no ore or obsidian, is
 *     touched. Without {@code breaks} nothing goes to the crater.</li>
 *     <li><b>The removal</b> ({@link #removable}): a crater block with a fluid in any of its six neighbours stays (a wall
 *     holding back the lake, a pool, a fall's seal), so no lava is ever let out.</li>
 *     <li><b>Magma</b> ({@link #magma}): the lowest removed block of the crater's middle column, if the block under it is
 *     solid and no fluid touches it.</li>
 *     <li><b>Ejecta</b> ({@link #ejecta}): drawn only from the removed blocks that may fall as debris (no glow, fire or
 *     magma, nothing waterlogged), those nearest the surface the bomb struck; each is thrown from the open cell one step
 *     out along that surface's normal ({@link #ejectaVelocity}).</li>
 *     <li><b>The strike</b> ({@link #exposure}, {@link #damage}): the game's own numbers for a blast of power {@code P},
 *     scaled by the config's shares.</li>
 * </ul>
 */
public final class Crater {
    private Crater() {}

    /** What a cell is, as bits: what the partition, the removal, the magma and the ejecta ask of it. */
    public static final int AIR = 1, IN_LAYER = 2, HELD = 4, ROCK = 8, DEBRIS = 16, FLUID = 32, KEEP = 64, GLOW = 128, SOLID = 256;

    /** Where a block the rays reach goes: to the crater, left to the game's blast, or out of the list (never touched). */
    public static final int CRATER = 0, VANILLA = 1, NOT = 2;

    /** The cells round a blast: the bits of the cell at (x, y, z). */
    @FunctionalInterface
    public interface Cells {
        int at(int x, int y, int z);
    }

    /**
     * Where a block the rays reached goes, its cell's bits {@code c}: without {@code breaks} every block is left to the
     * game (which, with the blast's {@code NONE}, then breaks nothing); air is left to it too (it skips air). Outside the
     * layer, and an ore, gilded blackstone or obsidian ({@link #KEEP}), out of the list; in the layer a natural block, or
     * a held one of the Galleries' rock, to the crater; any other held block (a player's) to the game.
     */
    public static int part(int c, boolean breaks) {
        if (!breaks || (c & AIR) != 0) return VANILLA;
        if ((c & IN_LAYER) == 0 || (c & KEEP) != 0) return NOT;
        if ((c & HELD) == 0 || (c & ROCK) != 0) return CRATER;
        return VANILLA;
    }

    /** True when a crater block at (x, y, z) may be removed: in the layer, and no fluid in any of its six neighbours. */
    public static boolean removable(Cells cells, int x, int y, int z) {
        if ((cells.at(x, y, z) & IN_LAYER) == 0) return false;
        return (cells.at(x + 1, y, z) & FLUID) == 0 && (cells.at(x - 1, y, z) & FLUID) == 0 && (cells.at(x, y + 1, z) & FLUID) == 0
                && (cells.at(x, y - 1, z) & FLUID) == 0 && (cells.at(x, y, z + 1) & FLUID) == 0 && (cells.at(x, y, z - 1) & FLUID) == 0;
    }

    /**
     * The crater's blocks (the partition's {@link #CRATER} ones, {x, y, z} each) that are removed: those {@link #removable}
     * with the cells as they are before any is removed (taking one never brings a fluid next to another).
     */
    public static List<int[]> removed(List<int[]> crater, Cells cells) {
        List<int[]> out = new ArrayList<>();
        for (int[] p : crater) if (removable(cells, p[0], p[1], p[2])) out.add(p);
        return out;
    }

    /**
     * The index in {@code removed} of the block that becomes magma: the lowest removed one in the column (mx, mz), the
     * crater's middle, if the block under it is solid and not removed, and no fluid touches it; -1 when there is none.
     */
    public static int magma(List<int[]> removed, int mx, int mz, Cells cells) {
        int best = -1;
        for (int i = 0; i < removed.size(); i++) {
            int[] p = removed.get(i);
            if (p[0] != mx || p[2] != mz) continue;
            if (best < 0 || p[1] < removed.get(best)[1]) best = i;
        }
        if (best < 0) return -1;
        int[] p = removed.get(best);
        int under = cells.at(p[0], p[1] - 1, p[2]);
        if ((under & SOLID) == 0 || (under & FLUID) != 0) return -1;
        return removable(cells, p[0], p[1], p[2]) ? best : -1;
    }

    /**
     * The indices in {@code removed} of the ejecta, at most {@code n}: of the blocks that may fall as debris
     * ({@link #DEBRIS}; never the one that becomes magma, {@code skip}), those nearest the surface struck, which faces
     * along (nx, ny, nz) through the centre (cx, cy, cz): the least deep along the normal first, then the nearest the
     * centre.
     */
    public static int[] ejecta(List<int[]> removed, Cells cells, int skip, double cx, double cy, double cz, double nx, double ny, double nz, int n) {
        List<Integer> can = new ArrayList<>();
        for (int i = 0; i < removed.size(); i++) {
            int[] p = removed.get(i);
            if (i != skip && (cells.at(p[0], p[1], p[2]) & DEBRIS) != 0) can.add(i);
        }
        can.sort(Comparator.<Integer>comparingDouble(i -> -depthOut(removed.get(i), cx, cy, cz, nx, ny, nz))
                .thenComparingDouble(i -> dist2(removed.get(i), cx, cy, cz)));
        int[] out = new int[Math.min(Math.max(0, n), can.size())];
        for (int i = 0; i < out.length; i++) out[i] = can.get(i);
        return out;
    }

    /** How far out along the normal a block's middle lies from the centre (less deep is more). */
    private static double depthOut(int[] p, double cx, double cy, double cz, double nx, double ny, double nz) {
        return (p[0] + 0.5 - cx) * nx + (p[1] + 0.5 - cy) * ny + (p[2] + 0.5 - cz) * nz;
    }

    private static double dist2(int[] p, double cx, double cy, double cz) {
        double dx = p[0] + 0.5 - cx, dy = p[1] + 0.5 - cy, dz = p[2] + 0.5 - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * An ejected block's motion off a surface facing along the unit normal (nx, ny, nz): 0.3 to 0.5 along it, 0.2 to 0.4
     * across it in a random direction, and at least 0.25 upward (a wall crater's block would otherwise sit on the rock
     * under it and fall back into the crater on its first tick). {vx, vy, vz}.
     */
    public static double[] ejectaVelocity(double nx, double ny, double nz, Random r) {
        double out = 0.3 + 0.2 * r.nextDouble(), side = 0.2 + 0.2 * r.nextDouble();
        // a unit vector across the normal: from a helper axis not along it, turned by a random angle about the normal
        double ax = Math.abs(ny) < 0.9 ? 0 : 1, ay = Math.abs(ny) < 0.9 ? 1 : 0;
        double ux = ay * nz, uy = -ax * nz, uz = ax * ny - ay * nx;
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul;
        uy /= ul;
        uz /= ul;
        double wx = ny * uz - nz * uy, wy = nz * ux - nx * uz, wz = nx * uy - ny * ux;
        double a = r.nextDouble() * Math.PI * 2, c = Math.cos(a), s = Math.sin(a);
        double vx = nx * out + (ux * c + wx * s) * side, vy = ny * out + (uy * c + wy * s) * side, vz = nz * out + (uz * c + wz * s) * side;
        return new double[]{vx, Math.max(vy, 0.25), vz};
    }

    /** The open cell the ejecta are thrown from: one step out of the block struck at (x, y, z) along the face's normal. */
    public static int[] ejectaCell(int x, int y, int z, int nx, int ny, int nz) { return new int[]{x + nx, y + ny, z + nz}; }

    // ================================================================================================ the strike

    /**
     * The game's exposure of an entity to a blast of power {@code p} ({@code Explosion.explode}'s entity loop): its feet
     * {@code dist} blocks from the centre, {@code seen} the share of its box the centre sees; -1 beyond twice the power,
     * where it is not struck at all (within it even an exposure of 0 takes the damage's 1).
     */
    public static double exposure(double dist, double p, double seen) {
        double d = dist / (2 * p);
        return d > 1 ? -1 : (1 - d) * seen;
    }

    /** The game's damage at exposure {@code q} for a blast of power {@code p}, before the config's share. */
    public static int damage(double q, double p) {
        return (int) ((q * q + q) / 2.0 * 7.0 * (2 * p) + 1.0);
    }
}
