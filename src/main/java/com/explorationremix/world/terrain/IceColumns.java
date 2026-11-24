package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Frost Fern Grotto's ice columns (0.20.7, Taran: "Make ice columns procedurally generated instead of structures").
 * Until then each column was placed on its own: the same pinched hourglass stamped at spaced spots. Now the ice grows from
 * fields over world coordinates, as the caves themselves do, and the cave it grows in shapes it:
 * <ul>
 *     <li><b>Where.</b> A jittered grid keeps at most one formation's middle in each {@link #CELL}-block square (the
 *     first of a few spots in it with room for one, {@link #TRIES}), as often as a slow grove field and the grotto's
 *     iciness say ({@link #chance}): groves of formations in some caves and few in others, so a big hall keeps long views
 *     between its groves, and more and thicker the deeper the grotto lies (it grows icier with depth,
 *     {@link Palette#grottoIce}).</li>
 *     <li><b>Footprint.</b> How far a column lies from a formation's middle is measured through a warped, lumpy field
 *     and stretched one way, so no two formations are round alike and two that stand close fuse into one mass.</li>
 *     <li><b>Profile.</b> In each column of open space a formation covers, its ice hangs from that column's ceiling and
 *     stands on its floor as far as the formation's profile says at that distance from its middle. A joined formation
 *     fills its core floor to ceiling and flares out over both, thinnest at a waist; a broken one tapers to a point from
 *     the ceiling (a giant icicle) over a stump that never meets it. The lengths follow each column's own ceiling and
 *     floor, so the ice drapes over the cave's shape, and ice only ever hangs on from a ceiling or stands on from a
 *     floor: nothing floats.</li>
 *     <li><b>One chunk each.</b> What a formation is depends on its middle's column alone: the room there (how tall it
 *     is, and how open at walking height, so that none stands in a tunnel and only hanging ice grows in narrow places),
 *     its biome and how icy it is. The chunk its cell centres in decides it and grows it whole, into the chunks round it
 *     too ({@link #chunk}), so no two chunks can judge it differently, even where something later turns rock to ice
 *     (the grotto's ice crusts) between their turns. Ice reads as open space ({@link World#open}), so formations that
 *     overlap add up to the same ice whichever grows first.</li>
 *     <li><b>Ice.</b> Packed ice, banded with uneven rings of blue ice that come wider the deeper ({@link #blue}), and
 *     an icicle at the point of each broken formation's spike and stump.</li>
 * </ul>
 * A pure function of the seed and of what the world answers: safe on any thread.
 */
public final class IceColumns {
    /** What the ice asks of the world. */
    public interface World {
        /**
         * Open to the ice: air, ice (packed or blue, so a formation already grown reads as the room it stands in), an
         * icicle, a snow layer, a plant, a half slab; not water, nor rock.
         */
        boolean open(int x, int y, int z);

        /** Something a stump may stand on: sturdy and dry. */
        boolean standable(int x, int y, int z);

        /** The Frost Fern Grotto's biome at a block. */
        boolean grotto(int x, int y, int z);

        /** How icy the grotto is at a block ({@link Palette#grottoIce}), 0 near its top to 1 deep down. */
        double iciness(int x, int y, int z);

        /** A way's walking corridor near a column (the den gates' ramps; {@code Walkway.near}). */
        boolean way(int x, int z, int y0, int y1, double margin);

        /**
         * The lowest and highest block of a column read for its rooms (from the caves' floor to under the ground): the
         * same whenever it is asked, so every chunk reads a formation's middle alike.
         */
        int low(int x, int z);

        int high(int x, int z);
    }

    /** Where the ice goes. */
    public interface Sink {
        /** Ice at a block: blue ice, or packed ice. */
        void ice(int x, int y, int z, boolean blue);

        /**
         * An icicle block at a block, pointing down (hanging) or up (standing), {@code index} blocks from its base, of
         * {@code length}: only into air, never over ice (another formation's), which keeps overlapping formations alike
         * whichever grows first (ice in turn grows over an icicle).
         */
        void icicle(int x, int y, int z, boolean down, int index, int length);
    }

    /** At most one formation's middle in each square this many blocks across. */
    static final int CELL = 7;
    /**
     * How many spots of a cell are tried for its middle, in turn: the first whose column has room for a formation is it.
     * In a wide hall the first spot nearly always has room, so the halls keep the density the chance gives; the grotto's
     * smaller, twisting caves catch a formation where one of the spots finds room.
     */
    static final int TRIES = 6;
    /** A formation's radius before the depth and the room scale it: {@link #R_LO} plus up to {@link #R_SPAN}, small ones commonest. */
    static final double R_LO = 1.6, R_SPAN = 3.0, R_POW = 1.6;
    /** How far the warp moves a column, over what distance it turns, and how much the lumps add or take off a radius. */
    static final double WARP = 1.3, WARP_SCALE = 11, LUMP = 0.18, LUMP_SCALE = 3.1;
    /** How much longer one way a footprint may be. */
    static final double STRETCH = 0.35;
    /** No block of a formation lies further than this across the ground from its middle. */
    static final int REACH = 10;
    /** The grove field's scale, and a cell's chance of a formation in a grove's heart and away from any grove, deep down. */
    static final double GROVE_SCALE = 72, P_GROVE = 0.7, P_OUT = 0.1;
    /** Share of the chance left at the grotto's top (iciness 0): the rest comes with depth. */
    static final double P_TOP = 0.5;
    /** Rooms lower than this get no formation; joined ones only up to {@link #JOIN_MAX} tall, broken ones from {@link #BROKEN_MIN}. */
    static final int MIN_ROOM = 5, JOIN_MAX = 26, BROKEN_MIN = 7;
    /** A joined column is at least this many times less wide (its radius) than its room is tall. */
    static final double SLENDER = 8;
    /** A broken formation's base radius, at least (before narrow places take a quarter off). */
    static final double BROKEN_R = 2.2;
    /**
     * How many of the eight ways out of a formation's middle must be open at walking height: for all of it, and for
     * hanging ice only (a tunnel's two ways: a spike keeps the headroom under it, {@link #decide}).
     */
    static final int OPEN_ALL = 4, OPEN_HANG = 2;

    private final long seed;
    private final Perlin groveN, warpX, warpZ, lumpN, dripN, bandN, bandM;

    public IceColumns(long seed) {
        this.seed = seed;
        long s = Hashing.mix(seed, 0x1CE, 0xC01, 0x7);
        groveN = new Perlin(Hashing.mix(s, 1, 0, 0));
        warpX = new Perlin(Hashing.mix(s, 2, 0, 0));
        warpZ = new Perlin(Hashing.mix(s, 3, 0, 0));
        lumpN = new Perlin(Hashing.mix(s, 4, 0, 0));
        dripN = new Perlin(Hashing.mix(s, 5, 0, 0));
        bandN = new Perlin(Hashing.mix(s, 6, 0, 0));
        bandM = new Perlin(Hashing.mix(s, 7, 0, 0));
    }

    public long seed() { return seed; }

    /**
     * Grows the formations of the cells that centre in the 16 by 16 columns from (x0, z0), one chunk: each whole, its
     * ice up to {@link #REACH} blocks from its middle, which lies at most {@code CELL / 2} from its cell's centre, so up
     * to 13 blocks into the chunks round this one.
     */
    public void chunk(int x0, int z0, World w, Sink out) {
        Cache cache = new Cache();
        for (Seed s : owned(x0, z0)) {
            int t = spot(s, w, cache);
            if (t >= 0) grow(s, t, w, out, cache);
        }
    }

    /** The cells (with a draw) whose centres lie in the 16 by 16 columns from (x0, z0). */
    List<Seed> owned(int x0, int z0) {
        List<Seed> out = new ArrayList<>();
        int i0 = Math.floorDiv(x0 - CELL / 2 + CELL - 1, CELL), i1 = Math.floorDiv(x0 + 15 - CELL / 2, CELL);
        int j0 = Math.floorDiv(z0 - CELL / 2 + CELL - 1, CELL), j1 = Math.floorDiv(z0 + 15 - CELL / 2, CELL);
        for (int i = i0; i <= i1; i++) for (int j = j0; j <= j1; j++) {
            Seed s = seed(i, j);
            if (s != null) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ the middles

    /** A cell: the spots tried for its formation's middle, and its draws (none of which depend on the room). */
    public static final class Seed {
        /** The cell, and its key in the caches. */
        final int i, j;
        final long key;
        /** The spots tried for the middle, in turn, and their warped positions. */
        final int[] ax = new int[TRIES], az = new int[TRIES];
        final double[] wx = new double[TRIES], wz = new double[TRIES];
        /** The draw that decides whether the formation is there ({@link #chance}); the grove field at the cell. */
        final double roll, grove;
        final double r0, cos, sin, stretch;
        final double joinRoll, waist, split, drip, mound, hang, stand, stumpRoll;

        Seed(int i, int j, long h, double grove) {
            this.i = i;
            this.j = j;
            this.key = ((long) i << 32) ^ (j & 0xFFFFFFFFL);
            this.grove = grove;
            roll = Hashing.unit(Hashing.mix(h, 2, 0, 0));
            r0 = R_LO + R_SPAN * Math.pow(Hashing.unit(Hashing.mix(h, 3, 0, 0)), R_POW);
            double a = 2 * Math.PI * Hashing.unit(Hashing.mix(h, 4, 0, 0));
            cos = Math.cos(a);
            sin = Math.sin(a);
            stretch = 1 + STRETCH * Hashing.unit(Hashing.mix(h, 5, 0, 0));
            joinRoll = Hashing.unit(Hashing.mix(h, 6, 0, 0));
            waist = 0.25 + 0.2 * Hashing.unit(Hashing.mix(h, 7, 0, 0));
            split = 0.4 + 0.22 * Hashing.unit(Hashing.mix(h, 8, 0, 0));
            // the hanging ice tapers to a slender drip, the standing ice swells like a mound of frozen splashes
            drip = 0.9 + 0.6 * Hashing.unit(Hashing.mix(h, 9, 0, 0));
            mound = 0.6 + 0.4 * Hashing.unit(Hashing.mix(h, 10, 0, 0));
            hang = 0.3 + 0.35 * Hashing.unit(Hashing.mix(h, 11, 0, 0));
            stand = 0.1 + 0.2 * Hashing.unit(Hashing.mix(h, 12, 0, 0));
            stumpRoll = Hashing.unit(Hashing.mix(h, 13, 0, 0));
        }
    }

    /** The cell's spots and draws, or null where no depth would ever keep a formation there. */
    Seed seed(int i, int j) {
        long h = Hashing.mix(seed ^ 0x1CEC01L, i, j, 0);
        double g = grove(i * CELL + CELL / 2, j * CELL + CELL / 2);
        if (Hashing.unit(Hashing.mix(h, 2, 0, 0)) >= chance(g, 1)) return null;
        Seed s = new Seed(i, j, h, g);
        for (int t = 0; t < TRIES; t++) {
            int ax = i * CELL + 1 + (int) (Hashing.unit(Hashing.mix(h, 20 + t, 0, 0)) * (CELL - 2));
            int az = j * CELL + 1 + (int) (Hashing.unit(Hashing.mix(h, 30 + t, 0, 0)) * (CELL - 2));
            s.ax[t] = ax;
            s.az[t] = az;
            s.wx[t] = ax + 0.5 + WARP * warpX.noise2(ax / WARP_SCALE, az / WARP_SCALE);
            s.wz[t] = az + 0.5 + WARP * warpZ.noise2(ax / WARP_SCALE, az / WARP_SCALE);
        }
        return s;
    }

    /** The grove field at a column: 1 in a grove's heart, 0 away from any. */
    double grove(int x, int z) {
        double g = (1.38 * groveN.noise2(x / GROVE_SCALE, z / GROVE_SCALE) + 0.05) / 0.45;
        g = g <= 0 ? 0 : g >= 1 ? 1 : g;
        return g * g * (3 - 2 * g);
    }

    /** A cell's chance of a formation, by the grove field and how icy the grotto is at its middle. */
    static double chance(double grove, double ice) {
        return (P_OUT + (P_GROVE - P_OUT) * grove) * (P_TOP + (1 - P_TOP) * ice);
    }

    /** The middles of every cell whose formation could reach the columns x0..x1, z0..z1. */
    public List<Seed> seeds(int x0, int z0, int x1, int z1) {
        List<Seed> out = new ArrayList<>();
        int i0 = Math.floorDiv(x0 - REACH, CELL), i1 = Math.floorDiv(x1 + REACH, CELL);
        int j0 = Math.floorDiv(z0 - REACH, CELL), j1 = Math.floorDiv(z1 + REACH, CELL);
        for (int i = i0; i <= i1; i++) for (int j = j0; j <= j1; j++) {
            Seed s = seed(i, j);
            if (s != null) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ a formation

    /** What a formation is in one room: decided at its middle's column alone. */
    static final class Form {
        /** Its middle (which of the cell's spots), and the room there: its lowest and highest open block. */
        final int spot, floor, ceil;
        final double r;
        final boolean joined;
        /** Joined: the core's share of the radius, filled floor to ceiling. */
        final double waist;
        /** How far the ice hangs from the ceiling and stands on the floor at the middle (blocks). */
        final double lc, lf;
        /** The profile's exponents: the hanging ice's and the standing ice's (a lower one swells, a higher one tapers). */
        final double drip, mound;

        Form(int spot, int floor, int ceil, double r, boolean joined, double waist, double lc, double lf, double drip, double mound) {
            this.spot = spot;
            this.floor = floor;
            this.ceil = ceil;
            this.r = r;
            this.joined = joined;
            this.waist = waist;
            this.lc = lc;
            this.lf = lf;
            this.drip = drip;
            this.mound = mound;
        }
    }

    /** Per decoration pass: what was read of the middles' columns and decided there. */
    public static final class Cache {
        final Map<Long, int[]> runs = new HashMap<>();
        final Map<Long, Form> forms = new HashMap<>();
        final Map<Long, Integer> spots = new HashMap<>();
    }

    private static final Form NONE = new Form(-1, 0, 0, 0, false, 0, 0, 0, 1, 1);

    /**
     * The formation of a seed in the room of its middle's column that shares most of {@code lo..hi} (a column's open
     * run), or null where it has none there.
     */
    Form form(Seed s, int lo, int hi, World w, Cache cache) {
        int t = spot(s, w, cache);
        if (t < 0) return null;
        int[] runs = runs(s.ax[t], s.az[t], w, cache);
        int best = -1, overlap = 0;
        for (int k = 0; k < runs.length; k += 2) {
            int o = Math.min(hi, runs[k + 1]) - Math.max(lo, runs[k]) + 1;
            if (o > overlap) {
                overlap = o;
                best = k;
            }
        }
        if (best < 0) return null;
        return formAt(s, t, runs[best], runs[best + 1], w, cache);
    }

    /** The formation with its middle at spot t in the room a..b there, or null; decided once a pass. */
    private Form formAt(Seed s, int t, int a, int b, World w, Cache cache) {
        long key = (((s.key * 31 + t) * 31 + a) * 1_000_003L) + b;
        Form f = cache.forms.get(key);
        if (f == null) {
            f = decide(s, t, a, b, w);
            cache.forms.put(key, f == null ? NONE : f);
        }
        return f == NONE ? null : f;
    }

    /**
     * Which of a cell's spots is its formations' middle: the first whose column has room for a formation in any of its
     * rooms, or -1 for none.
     */
    int spot(Seed s, World w, Cache cache) {
        Integer known = cache.spots.get(s.key);
        if (known != null) return known;
        int pick = -1;
        for (int t = 0; t < TRIES && pick < 0; t++) {
            int[] runs = runs(s.ax[t], s.az[t], w, cache);
            for (int k = 0; k < runs.length && pick < 0; k += 2)
                if (formAt(s, t, runs[k], runs[k + 1], w, cache) != null) pick = t;
        }
        cache.spots.put(s.key, pick);
        return pick;
    }

    /**
     * The open runs of a column within its scan ({@link World#low} to {@link World#high}), as pairs of lowest and highest
     * open block, top first; each bounded both ways, so open sky never counts as a room.
     */
    int[] runs(int x, int z, World w, Cache cache) {
        long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        int[] r = cache.runs.get(key);
        if (r != null) return r;
        int lo = w.low(x, z), hi = w.high(x, z);
        List<Integer> out = new ArrayList<>();
        int top = Integer.MIN_VALUE;
        for (int y = hi; y >= lo; y--) {
            boolean open = w.open(x, y, z);
            if (open && top == Integer.MIN_VALUE) top = y;
            if (!open && top != Integer.MIN_VALUE) {
                // open from y + 1 up to top, with rock (or water) at y and over top (unless top is the scan's own end)
                if (top < hi) {
                    out.add(y + 1);
                    out.add(top);
                }
                top = Integer.MIN_VALUE;
            }
        }
        r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        cache.runs.put(key, r);
        return r;
    }

    /** What a seed's formation is with its middle at spot t, in the room a..b there, or null for none. */
    Form decide(Seed s, int t, int a, int b, World w) {
        int room = b - a + 1;
        if (room < MIN_ROOM) return null;
        int ax = s.ax[t], az = s.az[t];
        // its biome and how icy, judged near the floor (which no later crust shifts)
        int yRef = Math.min(b, a + 2);
        if (!w.grotto(ax, yRef, az)) return null;
        double ice = w.iciness(ax, yRef, az);
        if (s.roll >= chance(s.grove, ice)) return null;
        double r = s.r0 * (0.8 + 0.3 * ice);
        r = Math.min(r, 0.8 + 0.2 * room);
        if (w.way(ax, az, a, b, r + 1)) return null;
        int open = openWays(ax, a, az, (int) Math.ceil(r + 1.5), w);
        if (open < OPEN_HANG) return null;
        boolean wide = open >= OPEN_ALL;
        boolean stands = wide && w.standable(ax, a - 1, az);
        // joined only where the column would not be a pole: at least a quarter as thick as the room is tall
        if (stands && room <= JOIN_MAX && r >= room / SLENDER && s.joinRoll < 0.55 + 0.3 * ice) {
            double waist = Math.min(0.75, Math.max(s.waist, 0.85 / r));
            return new Form(t, a, b, r, true, waist, s.split * room, (1 - s.split) * room, s.drip, s.mound);
        }
        if (room < BROKEN_MIN) return null;
        // a spike as thin as a pole reads as one: broken ones are at least BROKEN_R across their base
        r = Math.max(r, BROKEN_R);
        if (!wide) r *= 0.75;
        double lc = Math.min(s.hang * room, (wide ? 1.5 : 1.0) + (wide ? 3.0 : 2.2) * r);
        double lf = stands && s.stumpRoll < 0.7 ? Math.min(s.stand * room, 1 + 2.2 * r) : 0;
        // a gap a player can walk through between the point and the stump
        double gap = Math.max(3, 0.15 * room);
        if (lc + lf > room - gap) {
            double k = (room - gap) / (lc + lf);
            lc *= k;
            lf *= k;
        }
        if (lc < 2) return null;
        // a broken spike tapers steadily like a carrot to the icicle at its point, never a needle under a plate
        double cone = 0.62 + 0.25 * (s.drip - 0.9) / 0.6;
        return new Form(t, a, b, r, false, 0, lc, lf, cone, s.mound);
    }

    private static final int[][] WAYS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    /** How many of the eight ways out of a column are open for {@code reach} blocks at the two heights over its floor. */
    static int openWays(int x, int floor, int z, int reach, World w) {
        int n = 0;
        for (int[] d : WAYS) {
            int steps = d[0] != 0 && d[1] != 0 ? (int) Math.ceil(reach / 1.41) : reach;
            boolean ok = true;
            for (int k = 1; k <= steps && ok; k++) {
                int px = x + d[0] * k, pz = z + d[1] * k;
                ok = w.open(px, floor, pz) && w.open(px, floor + 1, pz);
            }
            if (ok) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------ a column

    /** How far a column lies from a seed's middle (spot t) through the warped, lumpy field, before the radius divides it. */
    static double distance(Seed s, int t, double wx, double wz) {
        double dx = wx - s.wx[t], dz = wz - s.wz[t];
        double a = dx * s.cos + dz * s.sin, b = -dx * s.sin + dz * s.cos;
        return Math.sqrt(a * a / (s.stretch * s.stretch) + b * b);
    }

    /**
     * Grows a cell's formations, with their middle at spot t (one in each room of that column with one), over every
     * column they cover: in each room there that the middle's room shares, the ice hangs from the room's ceiling and
     * stands on its floor as far as the profile says at the column's distance from the middle; where the two meet, or in
     * a joined formation's core, it fills the room.
     */
    void grow(Seed s, int t, World w, Sink out, Cache cache) {
        int mx = s.ax[t], mz = s.az[t];
        // the widest the formation can be: a joined one at its iciest, or a broken one's base
        double widest = Math.max(s.r0 * 1.1, BROKEN_R);
        for (int x = mx - REACH; x <= mx + REACH; x++) for (int z = mz - REACH; z <= mz + REACH; z++) {
            double wx = x + 0.5 + WARP * warpX.noise2(x / WARP_SCALE, z / WARP_SCALE);
            double wz = z + 0.5 + WARP * warpZ.noise2(x / WARP_SCALE, z / WARP_SCALE);
            double lump = 1 + LUMP * 1.38 * lumpN.noise2(x / LUMP_SCALE, z / LUMP_SCALE);
            double d = distance(s, t, wx, wz);
            if (d >= widest * lump) continue;
            int[] runs = runs(x, z, w, cache);
            // drips: the flares' and points' lengths wander a little from column to column
            double drip = 1 + 0.16 * 1.38 * dripN.noise2(x / 1.7, z / 1.7);
            for (int k = 0; k < runs.length; k += 2) {
                int a = runs[k], b = runs[k + 1], room = b - a + 1;
                if (room < 3) continue;
                Form f = form(s, a, b, w, cache);
                if (f == null) continue;
                double u = d / (f.r * lump);
                if (u >= 1) continue;
                // how far out of the middle's run (0 at the core's edge or the middle, 1 at the footprint's edge)
                boolean fill = f.joined && u < f.waist;
                double v = f.joined ? (u - f.waist) / (1 - f.waist) : u;
                double hang = fill ? 0 : f.lc * (1 - Math.pow(v, 1 / f.drip)) * drip;
                double stand = fill ? 0 : f.lf * (1 - Math.pow(v, 1 / f.mound)) * (0.5 + 0.5 * drip);
                if (!w.standable(x, a - 1, z)) stand = 0;
                int nh = (int) Math.floor(hang + 0.5), ns = (int) Math.floor(stand + 0.5);
                if (fill || nh + ns >= room) {
                    nh = room;
                    ns = 0;
                }
                if (nh == 0 && ns == 0) continue;
                double ice = w.iciness(x, (a + b) >> 1, z);
                for (int y = b; y > b - nh; y--) out.ice(x, y, z, blue(x, y, z, ice));
                for (int y = a; y < a + ns; y++) out.ice(x, y, z, blue(x, y, z, ice));
                if (!f.joined && x == mx && z == mz && nh < room) tips(x, z, a, b, nh, ns, out);
            }
        }
    }

    /** An icicle under a broken formation's spike and on its stump, where the gap between them leaves room. */
    private static void tips(int x, int z, int a, int b, int nh, int ns, Sink out) {
        int gap = (b - nh) - (a + ns) + 1;
        if (nh >= 2) {
            int len = Math.min(nh >= 7 ? 2 : 1, gap - 2);
            for (int k = 0; k < len; k++) out.icicle(x, b - nh - k, z, true, k, len);
            gap -= Math.max(0, len);
        }
        if (ns >= 2) {
            int len = Math.min(ns >= 5 ? 2 : 1, gap - 2);
            for (int k = 0; k < len; k++) out.icicle(x, a + ns + k, z, false, k, len);
        }
    }

    /**
     * Whether a block of a formation is blue ice: thin wavy rings through the packed ice, mostly level like the layers
     * of the water that froze, wider the icier the grotto.
     */
    boolean blue(int x, int y, int z, double ice) {
        double b = bandN.noise3(x / 16.0, y / 4.5, z / 16.0) + 0.5 * bandM.noise3(x / 40.0, y / 14.0, z / 40.0);
        return Math.abs(b) < 0.03 + 0.05 * ice;
    }
}
