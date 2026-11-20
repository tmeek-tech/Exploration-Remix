package com.explorationremix.creature;

import com.explorationremix.block.Neon;
import com.explorationremix.world.terrain.GardensField;
import com.explorationremix.world.terrain.Terrain;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the Deep Gardens' stilt mantis herds live (0.20.0; Taran's cards: small herds of 2 to 4, a herd in most big
 * groves, each the colour of the glow mats it grazes, lime ones only within about 30 blocks of a lime vent). Worked
 * out from the vent field's own plan ({@link GardensField#packed}), never from the level, so every chunk agrees where
 * a herd's home is, whichever is made first.
 *
 * <p>The layer is cut into cells {@link #CELL} blocks across (inside one of the field's block tiles, so planning a cell
 * makes no tile the chunks under it would not make anyway). In each cell, the mat floors with room over them are
 * looked at on a grid; the one with the most mat floors round it is the heart of the cell's biggest grove, and a grove
 * with at least {@link #BIG_GROVE} of them round its heart is a big one. Most big groves hold a herd ({@link #HELD}); a
 * second grove of the cell, well away from the first (another storey, or the cell's far side), may hold another. A
 * herd takes the colour most of its grove's mats are, or lime when a live deep vent stands near.
 *
 * <p>The live deep vents (the lime ones, the ways down) are few, two or so to a square kilometre, and the rock round
 * them hot, its mats in small patches (the best within reach of the seed's first two held 8 and 9 floors round their
 * hearts, never a big grove). So the cell a lime vent stands in takes a smaller grove within {@link #LIME_REACH} of it,
 * {@link #LIME_GROVE} floors round its heart, before its big groves: most lime vents keep a lime herd, and lime herds are
 * found only there (Taran: "green ones only close to green vents").
 */
public final class MantisHerds {
    /** Herds are planned in cells this many blocks across (a block tile of the field holds four). */
    public static final int CELL = 64;
    /** The grid the cell's columns are read on (blocks), and how near (across, and up or down) a mat floor is "round" another. */
    static final int STEP = 3, NEAR = 9, NEAR_UP = 4;
    /** A grove with at least this many mat floors (grid points) round its heart is a big one (about 20 blocks across). */
    public static final int BIG_GROVE = 16;
    /** The share of big groves that hold a herd ("a herd in most big groves"). */
    static final double HELD = 0.8;
    /** A cell's second herd must lie this far from its first (across), or this many rows above or below it. */
    static final int APART = 30, APART_UP = 18;
    /** Rows of open air a mat floor needs over it for a mantis to stand there (its box is under three blocks tall). */
    static final int HEADROOM = 4;
    /** Lime herds only within this many blocks of a live deep vent (Taran: "green ones only close to green vents"). */
    public static final double LIME_REACH = 30;
    /** A grove within reach of a lime vent standing in the cell holds a herd with this many mat floors round its heart. */
    public static final int LIME_GROVE = 5;

    private MantisHerds() {}

    /** A herd's home: where it stands (a block on the mat it grazes, world coordinates), its colour and its size. */
    public record Home(long id, int x, int y, int z, Neon colour, int size, int mats) {}

    private static final Map<Long, List<Home>> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, List<Home>> e) { return size() > 512; }
    };
    private static long cacheSeed;

    /** The cell holding block column (x, z). */
    public static int cell(int v) { return Math.floorDiv(v, CELL); }

    /** The herd homes of cell (cx, cz): none, one or two. */
    public static List<Home> homes(Terrain t, int cx, int cz) {
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        synchronized (CACHE) {
            if (cacheSeed != t.seed) { CACHE.clear(); cacheSeed = t.seed; }
            List<Home> h = CACHE.get(key);
            if (h != null) return h;
        }
        List<Home> h = plan(t, cx, cz);
        synchronized (CACHE) { CACHE.put(key, h); }
        return h;
    }

    private static final java.util.Set<Long> PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The herd homes of cell (cx, cz) if the cell is planned already; else null, and its plan is made off the server
     * thread (for the refill, which runs on it: a fresh tile of the vent field takes seconds to make).
     */
    @Nullable
    public static List<Home> homesIfPlanned(Terrain t, int cx, int cz) {
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        synchronized (CACHE) {
            if (cacheSeed == t.seed) {
                List<Home> h = CACHE.get(key);
                if (h != null) return h;
            }
        }
        if (PENDING.add(key)) {
            net.minecraft.Util.backgroundExecutor().execute(() -> {
                try { homes(t, cx, cz); } finally { PENDING.remove(key); }
            });
        }
        return null;
    }

    /** The home of a herd by its id, if its cell still plans it. */
    @Nullable
    public static Home byId(Terrain t, long id) {
        int cx = (int) (id >> 33), cz = (int) ((id >> 1) & 0xFFFFFFFFL);
        for (Home h : homes(t, cx, cz)) if (h.id == id) return h;
        return null;
    }

    private static long id(int cx, int cz, int k) {
        return ((long) cx << 33) | ((cz & 0xFFFFFFFFL) << 1) | k;
    }

    /** One mat floor seen on the grid: its column, its row (the mat's own), its colour code. */
    private record Floor(int x, int row, int z, int colour) {}

    static List<Home> plan(Terrain t, int cx, int cz) {
        GardensField g = t.gardens;
        List<Home> out = new ArrayList<>(2);
        if (g == null) return out;
        int bottom = t.shape.bottom(Terrain.GARDENS), rows = t.shape.height(Terrain.GARDENS);
        int x0 = cx * CELL, z0 = cz * CELL;
        List<Floor> floors = new ArrayList<>();
        for (int i = STEP / 2; i < CELL; i += STEP)
            for (int j = STEP / 2; j < CELL; j += STEP) {
                int x = x0 + i, z = z0 + j;
                int open = 0;
                // down the column from the top: a run of open rows over a mat is a floor with room
                for (int row = rows - 1; row >= 1; row--) {
                    int p = g.packed(x, row, z), c = p & 31;
                    if (c == GardensField.C_AIR) { open++; continue; }
                    if ((c == GardensField.C_MAT || c == GardensField.C_MATE) && open >= HEADROOM) floors.add(new Floor(x, row, z, p >> 5));
                    open = 0;
                }
            }
        if (floors.isEmpty()) return out;
        int n = floors.size();
        int[] round = new int[n];
        for (int a = 0; a < n; a++) {
            Floor fa = floors.get(a);
            for (int b = a + 1; b < n; b++) {
                Floor fb = floors.get(b);
                if (Math.abs(fa.row - fb.row) > NEAR_UP) continue;
                int dx = fa.x - fb.x, dz = fa.z - fb.z;
                if (dx * dx + dz * dz > NEAR * NEAR) continue;
                round[a]++;
                round[b]++;
            }
        }
        // the lime vents near: a floor in reach of one is lime (2: of one standing in this cell, where a smaller grove does)
        List<double[]> limes = g.limeVents(x0 - LIME_REACH, z0 - LIME_REACH, x0 + CELL + LIME_REACH, z0 + CELL + LIME_REACH);
        int[] lime = new int[n];
        if (!limes.isEmpty())
            for (int a = 0; a < n; a++) {
                Floor fa = floors.get(a);
                for (double[] v : limes) {
                    if (!GardensField.inReach(v, fa.x + 0.5, fa.row + 1, fa.z + 0.5, LIME_REACH)) continue;
                    boolean mine = cell((int) Math.floor(v[0])) == cx && cell((int) Math.floor(v[1])) == cz;
                    lime[a] = Math.max(lime[a], mine ? 2 : 1);
                }
            }
        for (int k = 0; k < 2; k++) {
            int best = -1;
            for (int a = 0; a < n; a++) {
                if (round[a] + 1 < (lime[a] == 2 ? LIME_GROVE : BIG_GROVE)) continue;
                Floor fa = floors.get(a);
                boolean apart = true;
                for (Home h : out) {
                    int dx = fa.x - h.x, dz = fa.z - h.z, dy = fa.row + bottom + 1 - h.y;
                    if (dx * dx + dz * dz < APART * APART && Math.abs(dy) < APART_UP) apart = false;
                }
                if (!apart) continue;
                if (best < 0 || better(t.seed, fa, round[a], lime[a] == 2, floors.get(best), round[best], lime[best] == 2)) best = a;
            }
            if (best < 0) break;
            Floor heart = floors.get(best);
            long id = id(cx, cz, k);
            double roll = unit(t.seed, id, 1);
            if (roll >= HELD) {
                // an empty grove: it still keeps the place, so a second herd is never set right beside it
                out.add(new Home(id, heart.x, bottom + heart.row + 1, heart.z, null, 0, round[best] + 1));
                continue;
            }
            // its colour: what most of its grove's mats are, or lime by a live deep vent
            int[] votes = new int[4];
            for (Floor f : floors) {
                if (Math.abs(f.row - heart.row) > NEAR_UP) continue;
                int dx = f.x - heart.x, dz = f.z - heart.z;
                if (dx * dx + dz * dz <= NEAR * NEAR) votes[Math.max(0, Math.min(3, f.colour))]++;
            }
            int colour = 0;
            for (int c = 1; c < 4; c++) if (votes[c] > votes[colour]) colour = c;
            if (lime[best] > 0) colour = Neon.LIME.ordinal();
            int size = 2 + (int) Math.floor(unit(t.seed, id, 2) * 3.0);
            out.add(new Home(id, heart.x, bottom + heart.row + 1, heart.z, Neon.ofCode(colour), Math.min(4, size), round[best] + 1));
        }
        // empty groves only held places
        out.removeIf(h -> h.size == 0);
        return out;
    }

    /** Whether grove a goes before grove b: a lime vent's own grove first, then the bigger, then by the seed. */
    private static boolean better(long seed, Floor a, int roundA, boolean limeA, Floor b, int roundB, boolean limeB) {
        if (limeA != limeB) return limeA;
        if (roundA != roundB) return roundA > roundB;
        return tie(seed, a) < tie(seed, b);
    }

    private static double tie(long seed, Floor f) {
        return unit(seed, ((long) f.x << 40) ^ ((long) f.z << 12) ^ f.row, 3);
    }

    /** A number from 0 to 1 fixed by the world's seed, a key and a use. */
    static double unit(long seed, long key, int use) {
        long h = seed * 0x9E3779B97F4A7C15L + key * 0xC2B2AE3D27D4EB4FL + use * 0x165667B19E3779F9L;
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }
}
