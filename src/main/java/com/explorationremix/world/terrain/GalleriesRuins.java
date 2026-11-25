package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The lower Basalt Galleries' ruins (0.22.3; Taran: "Can you make some scattered ruins in the lower Basalt Galleries?
 * ... Ruins should come in clusters with bigger structures in the middle and smaller around the edges"). Taran's cards: a
 * cluster about every 400 blocks, on the high ground (the tablelands' tops, over the lava's highest line), half fallen,
 * a rune and some loot in each, and lava channels glowing in them (the Galleries' only light is lava you can see).
 *
 * <p>The layer is cut into cells of {@link #G} blocks; most hold one cluster ({@link #HELD}). A cluster's middle is the
 * roomiest of the spots on a grid over its cell ({@link #SCAN}), kept {@link #EDGE} blocks off the cell's sides (so no
 * two clusters ever meet), where the tops stand flat and clear as far round as its big structure reaches ({@link #CENTRE_R}): a
 * tableland's top, no way down, deck, bridge or band near, open {@link #HEAD_C} rows over the floor. There stands its big structure, one of four: a broken tower, a pillared hall, a
 * stepped shrine or a great gate, 15 to 25 blocks across, with the rune's chest in it. Round it, out to
 * {@link #R_OUT}, 6 to 12 small ruins where their own ground is flat: wall pieces, column stubs and fallen columns,
 * huts' footings (two or three of them with a loot chest), small arches, lava braziers and wells, stair stubs.
 *
 * <p>A plan is the blocks of one cluster in a box round its middle, as codes ({@link #AIR}, {@link #BRICKS}, ...),
 * worked out from the field alone (its floors and cells, never the level), so every chunk the cluster spans agrees on
 * it, whichever is made first; the feature (GalleriesRuinsFeature) sets each chunk's own blocks from it. Each piece
 * stands on its own paving: the ground under it is levelled to one row (rock filled under it where the floor dips, the
 * floor's bumps cut off over it), so nothing of it hangs and no step stands in it. Half fallen ({@link #DECAY}): every
 * wall, tower and column is broken off at a jagged height, about half what it stood to, the fallen blocks lying as
 * rubble at its foot, and a third of the standing blocks cracked. Lava lies only where rock holds it on every side and
 * under it ({@link #held}), flush in a floor or a basin, open to the air over it.
 */
public final class GalleriesRuins {
    /** The clusters' lattice (blocks), and the share of its cells that hold one. */
    public static final int G = 400;
    static final double HELD = 0.85;
    /**
     * A cluster's middle is looked for on a grid of SCAN blocks over its cell, kept EDGE blocks off the cell's sides,
     * where the tops stand at least half its big structure's reach from a chasm's edge, a wall's face and a massif's foot;
     * the LOOKS flattest are tried in turn ({@link #fit}). Not within TUBE_KEEP of a tube country's rim, nor within
     * WAY_KEEP of a way down's deck or foot past its reach, nor within HEAD_WAY of a way's head or its hole's throat.
     */
    static final int SCAN = 12, EDGE = 64, LOOKS = 8, TUBE_KEEP = 8, WAY_KEEP = 4, HEAD_WAY = 4;
    /**
     * The big structure's ground (CENTRE_R by kind: the tower's court and heap, the hall's platform, the shrine's stair,
     * the gate's walls): paved at one row, rock filled under it where the floor lies up to FLAT_LO rows lower (its
     * foundations show there, a terrace's wall) and the floor's rock cut away where it stands up to FLAT_HI rows higher.
     * The site's ground is read every FIT_STEP blocks over that reach: at least FIT_MIN of it must be such ground for the
     * paving row that suits most of it, and once the structure is laid at least KEEP_MIN of its blocks must stand on
     * such ground ({@link #prune}: the rest broke off at a chasm's edge, a ramp or a cone).
     */
    static final int[] CENTRE_R = {12, 16, 17, 16};
    static final int FLAT_LO = 4, FLAT_HI = 2, FIT_STEP = 3;
    static final double FIT_MIN = 0.8, KEEP_MIN = 0.88;
    /** Open rows over the floor the big structure needs at its middle and over its ground; a small one over its own. */
    static final int HEAD_C = 30, HEAD_G = 12, HEAD_S = 9;
    /** The small ruins' middles lie from the big structure's reach plus this, out to R_OUT blocks from the middle. */
    static final int R_GAP = 3, R_OUT = 34;
    /** A small piece's ground: its tops within SMALL_LO + SMALL_HI rows (see {@link #smallGround}); the spots tried for each. */
    static final int SMALL_LO = 4, SMALL_HI = 2, SMALL_TRIES = 16;
    /**
     * The room for them round a site ({@link #outer}): read every OUTER_STEP blocks, plain ground within OUTER_LO rows
     * under and OUTER_HI over the paving; it counts OUTER_W against the big structure's own ground in the site's score.
     */
    static final int OUTER_STEP = 6, OUTER_LO = 8, OUTER_HI = 5;
    static final double OUTER_W = 0.6;
    /** The plan's box: this many blocks either side of the middle, and rows from Y_UNDER under its paving up. */
    public static final int BOX = 46, SY = 60, Y_UNDER = 16;
    public static final int SX = 2 * BOX + 1;
    /** A cluster's ground stands over the lava's highest line by at least this (the tops: W + 44 or more). */
    static final int DRY = 41;
    /** Half fallen: walls stand to between DECAY_LO and 1 of their height, the mean about a half. */
    static final double DECAY_LO = 0.25, CRACK = 0.33;

    // ------------------------------------------------------------------------------------------------ block codes
    /** No block of the plan here: the level keeps its own. */
    public static final byte NONE = 0;
    public static final byte AIR = 1, BRICKS = 2, CRACKED_BRICKS = 3, TILES = 4, CRACKED_TILES = 5, POLISHED = 6, CHISELED = 7,
            PILLAR = 8, PILLAR_X = 9, PILLAR_Z = 10, COBBLED = 11, SLAB = 12, SLAB_TOP = 13, TILE_SLAB = 14, WALL = 15,
            LAVA = 16, CHAIN = 17;
    /** Brick stairs: STAIRS + the side they rise toward (0 east, 1 south, 2 west, 3 north), + 4 upside down. */
    public static final byte STAIRS = 32;
    /** A loot chest (CHEST) or the rune's (RUNE_CHEST), + the side it faces (0 east, 1 south, 2 west, 3 north). */
    public static final byte CHEST = 40, RUNE_CHEST = 44;

    /** Whether code b is a full block of rock (holds lava beside or under it). */
    public static boolean full(byte b) {
        return b == BRICKS || b == CRACKED_BRICKS || b == TILES || b == CRACKED_TILES || b == POLISHED || b == CHISELED
                || b == PILLAR || b == PILLAR_X || b == PILLAR_Z || b == COBBLED;
    }

    /** The big structures. */
    public static final int TOWER = 0, HALL = 1, SHRINE = 2, GATE = 3;
    static final String[] KINDS = {"tower", "hall", "shrine", "gate"};

    // ------------------------------------------------------------------------------------------------------ the plan
    /** One cluster's blocks. */
    public static final class Plan {
        /** The lattice cell, the middle column, its paving row (the top block) and the big structure's kind. */
        public final int i, j, cx, cz, gy, kind;
        /** The box: its lowest corner (blocks). */
        public final int x0, y0, z0;
        final byte[] b = new byte[SX * SX * SY];
        /** The ground per column (its top block): the field's floor less one, then each piece's paving. */
        final int[] ground = new int[SX * SX];
        /** The terrain's own top block per column (the field's), before the plan; Integer.MIN_VALUE: not read yet. */
        final int[] terrain = new int[SX * SX];
        /** The chests: x, y, z, and 0 for a loot chest or 1 for the rune's. */
        public final List<int[]> chests = new ArrayList<>();
        /** The small ruins, as kind names, for the tests and the logs. */
        public final List<String> smalls = new ArrayList<>();
        final long h;
        /** Open rows over the middle's floor. */
        int head;
        /** Per column of the box, the lowest and highest rows the plan sets (air too; lo > hi: none), once laid. */
        final short[] lo = new short[SX * SX], hi = new short[SX * SX];

        Plan(int i, int j, int cx, int cz, int gy, int kind, long h) {
            this.i = i; this.j = j; this.cx = cx; this.cz = cz; this.gy = gy; this.kind = kind; this.h = h;
            x0 = cx - BOX; z0 = cz - BOX; y0 = gy - Y_UNDER;
            java.util.Arrays.fill(terrain, Integer.MIN_VALUE);
        }

        /** The code at block (x, y, z) (world coordinates); NONE outside the box. */
        public byte at(int x, int y, int z) {
            int dx = x - x0, dy = y - y0, dz = z - z0;
            if (dx < 0 || dz < 0 || dy < 0 || dx >= SX || dz >= SX || dy >= SY) return NONE;
            return b[(dx * SX + dz) * SY + dy];
        }

        void set(int x, int y, int z, byte c) {
            int dx = x - x0, dy = y - y0, dz = z - z0;
            if (dx < 0 || dz < 0 || dy < 0 || dx >= SX || dz >= SX || dy >= SY) return;
            b[(dx * SX + dz) * SY + dy] = c;
        }

        boolean inBox(int x, int z) { int dx = x - x0, dz = z - z0; return dx >= 0 && dz >= 0 && dx < SX && dz < SX; }

        /** The lowest and highest rows of the box. */
        public int yMin() { return y0; }
        public int yMax() { return y0 + SY - 1; }

        /** Whether the box reaches the square of columns [minX, maxX] x [minZ, maxZ]. */
        public boolean touches(int minX, int minZ, int maxX, int maxZ) {
            return maxX >= x0 && minX < x0 + SX && maxZ >= z0 && minZ < z0 + SX;
        }

        /** How many blocks of the plan are set (codes other than NONE and AIR). */
        public int blocks() {
            int n = 0;
            for (byte c : b) if (c != NONE && c != AIR) n++;
            return n;
        }

        public String kindName() { return KINDS[kind]; }
    }

    // -------------------------------------------------------------------------------------------------- the planner
    private final GalleriesField g;
    private final long seed;
    private final int W;
    private final Map<Long, Plan> cache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Plan> e) { return size() > 24; }
    };
    /** Cells planned and found empty. */
    private final Map<Long, Boolean> none = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> e) { return size() > 4096; }
    };

    private static final Map<Long, GalleriesRuins> OF = new LinkedHashMap<>();

    /** The ruins of terrain t's Galleries (one planner per seed). */
    public static GalleriesRuins of(Terrain t) {
        synchronized (OF) {
            GalleriesRuins r = OF.get(t.seed);
            if (r == null || r.g != t.galleries) {
                if (OF.size() > 4) OF.clear();
                r = new GalleriesRuins(t.galleries, t.seed);
                OF.put(t.seed, r);
            }
            return r;
        }
    }

    GalleriesRuins(GalleriesField g, long seed) {
        this.g = g;
        this.seed = seed;
        this.W = g.W;
    }

    /** The cell holding block coordinate v. */
    public static int cell(int v) { return Math.floorDiv(v, G); }

    /** Cell (i, j)'s cluster, or null where it holds none. */
    public Plan plan(int i, int j) {
        long key = ((long) i << 32) ^ (j & 0xFFFFFFFFL);
        synchronized (cache) {
            Plan p = cache.get(key);
            if (p != null) return p;
            if (none.containsKey(key)) return null;
        }
        Plan p = build(i, j);
        synchronized (cache) {
            if (p != null) cache.put(key, p);
            else none.put(key, Boolean.TRUE);
        }
        return p;
    }

    /** The clusters whose boxes reach the columns [minX, maxX] x [minZ, maxZ] (a chunk: at most four cells round it). */
    public List<Plan> near(int minX, int minZ, int maxX, int maxZ) {
        List<Plan> out = new ArrayList<>(2);
        for (int i = cell(minX - BOX); i <= cell(maxX + BOX); i++)
            for (int j = cell(minZ - BOX); j <= cell(maxZ + BOX); j++) {
                // a cell's middle lies EDGE or more inside it, so its box reaches only so far past its sides
                int lo = i * G + EDGE - BOX, hi = (i + 1) * G - EDGE + BOX;
                int loZ = j * G + EDGE - BOX, hiZ = (j + 1) * G - EDGE + BOX;
                if (maxX < lo || minX > hi || maxZ < loZ || minZ > hiZ) continue;
                Plan p = plan(i, j);
                if (p != null && p.touches(minX, minZ, maxX, maxZ)) out.add(p);
            }
        return out;
    }

    // ------------------------------------------------------------------------------------------------- the sites
    /** The terrain's top block at column (x, z) (the field's floor less one), kept in the plan. */
    int terrain(Plan p, int x, int z) {
        if (!p.inBox(x, z)) return g.floorAt(x, z) - 1;
        int k = (x - p.x0) * SX + (z - p.z0);
        int t = p.terrain[k];
        if (t == Integer.MIN_VALUE) {
            t = g.floorAt(x, z) - 1;
            p.terrain[k] = t;
            p.ground[k] = t;
        }
        return t;
    }

    int ground(Plan p, int x, int z) {
        terrain(p, x, z);
        return p.ground[(x - p.x0) * SX + (z - p.z0)];
    }

    void setGround(Plan p, int x, int z, int y) {
        terrain(p, x, z);
        p.ground[(x - p.x0) * SX + (z - p.z0)] = y;
    }

    /**
     * Whether column (x, z) is plain ground a piece may stand on at paving row py: an open cell of the tablelands' tops
     * with no deck, band, bridge, ramp's plane or cone in it, its floor within [py - lo, py + hi], open head rows over it.
     */
    boolean plain(int x, int z, int py, int lo, int hi, int head) {
        GalleriesField.Cell c = g.column(x, z);
        if (!plainCell(c)) return false;
        int f = g.floorAt(x, z);
        if (f - 1 < py - lo || f - 1 > py + hi) return false;
        return c.c - f >= head;
    }

    private Plan build(int i, int j) {
        if (g == null) return null;
        long h = Hashing.mix(seed, 93001, i, j);
        if (Hashing.unit(h) >= HELD) return null;
        int kind = (int) (Hashing.unit(Hashing.mix(h, 2, 0, 0)) * 4), R = CENTRE_R[kind];
        GalleriesField.Climate cl = new GalleriesField.Climate();
        GalleriesField.Shape0 s = new GalleriesField.Shape0();
        // the candidates: a jittered grid over the cell's inner square, on the tops, each scored by how much of the
        // ground round it suits one paving row (the flattest first)
        List<double[]> cand = new ArrayList<>();
        int n = (G - 2 * EDGE) / SCAN;
        int[] fl = new int[(2 * R / FIT_STEP + 2) * (2 * R / FIT_STEP + 2)], row = new int[1];
        for (int a = 0; a < n; a++)
            for (int b = 0; b < n; b++) {
                long hk = Hashing.mix(h, 1, a, b);
                int x = i * G + EDGE + a * SCAN + (int) (Hashing.unit(hk) * SCAN);
                int z = j * G + EDGE + b * SCAN + (int) (Hashing.unit(Hashing.mix(hk, 1, 0, 0)) * SCAN);
                if (g.tubesAt(x + 0.5, z + 0.5) && g.tubes.rimAt(x + 0.5, z + 0.5) > -TUBE_KEEP) continue;
                g.climate(x + 0.5, z + 0.5, cl);
                if (cl.wMain() < 0.95) continue;
                GalleriesField.Cell c = g.column(x, z);
                if (c.closed() || c.deckMode != 0 || c.bl != c.bh || c.pier || c.rowed()) continue;
                int gy = g.floorAt(x, z) - 1;
                if (gy < W + DRY || c.c - (gy + 1) < HEAD_C) continue;
                g.shape(x + 0.5, z + 0.5, cl, s, 0);
                if (s.tW < 0.95 || s.massif) continue;
                if (Math.min(s.tEdge, Math.min(s.hall[0], s.dm)) < 0.5 * R) continue;
                double fit = fit(x, z, R, fl, row);
                if (fit < FIT_MIN) continue;
                // and the room round it for the small ruins
                double out = outer(x, z, row[0]);
                cand.add(new double[]{fit + OUTER_W * out + 0.03 * Hashing.unit(Hashing.mix(hk, 2, 0, 0)), x, z, row[0]});
            }
        cand.sort((u, v) -> Double.compare(v[0], u[0]));
        int tries = 0;
        for (int k = 0; k < cand.size() && tries < LOOKS; k++) {
            double[] q = cand.get(k);
            int x = (int) q[1], z = (int) q[2], gy = (int) q[3];
            if (gy < W + DRY) continue;
            // apart from the ones tried already (a near one fails the same way)
            boolean near = false;
            for (int m = 0; m < k && !near; m++) {
                double[] o = cand.get(m);
                if (o[0] < 0) near = Math.abs(o[1] - x) + Math.abs(o[2] - z) < 2 * SCAN;
            }
            if (near) continue;
            q[0] = -1;   // tried
            tries++;
            if (g.wayNear(x, z, gy - 6, gy + HEAD_C, R + WAY_KEEP, HEAD_WAY)) continue;
            GalleriesField.Cell c = g.column(x, z);
            Plan p = new Plan(i, j, x, z, gy, kind, Hashing.mix(h, 3, k, 0));
            p.head = c.c - (gy + 1);
            if (p.head < HEAD_C - FLAT_HI) continue;
            if (lay(p)) return p;
        }
        return null;
    }

    /**
     * The share of the ground within r of (x, z), read every FIT_STEP blocks, that suits one paving row: plain ground
     * ({@link #plainCell}) with HEAD_G open rows over it, over the lava's reach, whose top lies within FLAT_LO under and
     * FLAT_HI over that row. The row that suits most of it (and of those, the one nearest their middle) into row[0].
     */
    double fit(int x, int z, int r, int[] fl, int[] row) {
        int tot = 0, m = 0;
        for (int dx = -r; dx <= r; dx += FIT_STEP)
            for (int dz = -r; dz <= r; dz += FIT_STEP) {
                if (dx * dx + dz * dz > r * r + r) continue;
                tot++;
                GalleriesField.Cell c = g.column(x + dx, z + dz);
                if (!plainCell(c)) continue;
                int f = g.floorAt(x + dx, z + dz) - 1;
                if (c.c - (f + 1) < HEAD_G || f < W + DRY - FLAT_LO) continue;
                fl[m++] = f;
            }
        row[0] = Integer.MIN_VALUE;
        if (m == 0) return 0;
        java.util.Arrays.sort(fl, 0, m);
        int best = 0, ba = 0, bb = 0;
        for (int a = 0, b = 0; b < m; b++) {
            while (fl[b] - fl[a] > FLAT_LO + FLAT_HI) a++;
            if (b - a + 1 > best) { best = b - a + 1; ba = a; bb = b; }
        }
        // the paving row: the window's middle top, kept so its lowest is no more than FLAT_LO under, its highest no
        // more than FLAT_HI over
        int mid = fl[(ba + bb) / 2];
        row[0] = Math.max(fl[bb] - FLAT_HI, Math.min(fl[ba] + FLAT_LO, mid));
        return (double) best / tot;
    }

    /**
     * The share of the ground out to R_OUT round (x, z), read every OUTER_STEP blocks, that is plain with HEAD_S open rows
     * over it and its top within OUTER_LO under or OUTER_HI over paving row py: the room for the small ruins.
     */
    double outer(int x, int z, int py) {
        int tot = 0, ok = 0;
        for (int dx = -R_OUT; dx <= R_OUT; dx += OUTER_STEP)
            for (int dz = -R_OUT; dz <= R_OUT; dz += OUTER_STEP) {
                if (dx * dx + dz * dz > R_OUT * R_OUT) continue;
                tot++;
                GalleriesField.Cell c = g.column(x + dx, z + dz);
                if (!plainCell(c)) continue;
                int f = g.floorAt(x + dx, z + dz) - 1;
                if (c.c - (f + 1) >= HEAD_S && f >= py - OUTER_LO && f <= py + OUTER_HI) ok++;
            }
        return tot == 0 ? 0 : (double) ok / tot;
    }

    /** Whether cell c is plain ground a piece may stand on: open, with no deck, band, bridge's pier, ramp's plane or cone, pillar or stray in it. */
    static boolean plainCell(GalleriesField.Cell c) {
        return !(c.closed() || c.deckMode != 0 || c.bl != c.bh || c.pier || c.rowed() || c.pillar || c.stray);
    }

    // ------------------------------------------------------------------------------------------------ the layout
    /** The local frame of a piece: its middle, its turn (0..3 quarter turns) and mirror. */
    static final class Frame {
        final int ox, oz, rot;
        final boolean mirror;
        Frame(int ox, int oz, int rot, boolean mirror) { this.ox = ox; this.oz = oz; this.rot = rot & 3; this.mirror = mirror; }
        int x(int u, int v) {
            if (mirror) u = -u;
            switch (rot) {
                case 1: return ox - v;
                case 2: return ox - u;
                case 3: return ox + v;
                default: return ox + u;
            }
        }
        int z(int u, int v) {
            if (mirror) u = -u;
            switch (rot) {
                case 1: return oz + u;
                case 2: return oz - v;
                case 3: return oz - u;
                default: return oz + v;
            }
        }
        /** A local side (0 +u, 1 +v, 2 -u, 3 -v) as a world side (0 east, 1 south, 2 west, 3 north). */
        int side(int d) {
            if (mirror && (d & 1) == 0) d = (d + 2) & 3;
            return (d + rot) & 3;
        }
    }

    /**
     * Lays plan p's cluster: its big structure, which must keep KEEP_MIN of its blocks and the rune's chest once what
     * stands on no plain ground is dropped ({@link #prune}); then the small ruins round it, the lava's hold last. False
     * where the big structure does not stand.
     */
    private boolean lay(Plan p) {
        long h = p.h;
        Frame f = new Frame(p.cx, p.cz, (int) (Hashing.unit(Hashing.mix(h, 4, 0, 0)) * 4), Hashing.unit(Hashing.mix(h, 5, 0, 0)) < 0.5);
        int reach;
        switch (p.kind) {
            case TOWER: reach = tower(p, f, Hashing.mix(h, 10, 0, 0)); break;
            case HALL: reach = hall(p, f, Hashing.mix(h, 11, 0, 0)); break;
            case SHRINE: reach = shrine(p, f, Hashing.mix(h, 12, 0, 0)); break;
            default: reach = gate(p, f, Hashing.mix(h, 13, 0, 0)); break;
        }
        int laid = p.blocks();
        prune(p);
        if (p.blocks() < KEEP_MIN * laid) return false;
        boolean rune = false;
        for (int[] c : p.chests) rune |= c[3] == 1;
        if (!rune) return false;
        // the small ruins round it, the chests in two or three of them (huts: every third, and the last ones while a
        // chest is left over)
        int n = 6 + (int) (Hashing.unit(Hashing.mix(h, 6, 0, 0)) * 7);
        int chests = 2 + (Hashing.unit(Hashing.mix(h, 7, 0, 0)) < 0.5 ? 1 : 0);
        double a0 = Hashing.unit(Hashing.mix(h, 8, 0, 0)) * 2 * Math.PI;
        List<int[]> placed = new ArrayList<>();
        for (int q = 0; q < n; q++) {
            long hq = Hashing.mix(h, 20, q, 0);
            boolean done = false;
            for (int t = 0; t < SMALL_TRIES && !done; t++) {
                long ht = Hashing.mix(hq, t, 0, 0);
                // its own share of the ring first, then anywhere round it
                double a = t < SMALL_TRIES / 3 ? a0 + 2 * Math.PI * (q + 0.6 * (Hashing.unit(ht) - 0.5)) / n : 2 * Math.PI * Hashing.unit(ht);
                double r = reach + R_GAP + Hashing.unit(Hashing.mix(ht, 1, 0, 0)) * (R_OUT - reach - R_GAP);
                int x = p.cx + (int) Math.round(r * Math.cos(a)), z = p.cz + (int) Math.round(r * Math.sin(a));
                int type = (int) (Hashing.unit(Hashing.mix(ht, 2, 0, 0)) * SMALL_TYPES);
                if (chests > 0 && (q % 3 == 0 || q >= n - chests)) type = HUT;
                int rad = SMALL_R[type];
                boolean far = true;
                for (int[] o : placed) if (Math.hypot(o[0] - x, o[1] - z) < rad + o[2] + 3) { far = false; break; }
                if (!far) continue;
                int py = smallGround(p, x, z, rad);
                if (py == Integer.MIN_VALUE) continue;
                Frame fs = new Frame(x, z, (int) (Hashing.unit(Hashing.mix(ht, 3, 0, 0)) * 4), Hashing.unit(Hashing.mix(ht, 4, 0, 0)) < 0.5);
                boolean chest = small(p, fs, py, type, Hashing.mix(ht, 5, 0, 0), chests > 0);
                if (chest) chests--;
                placed.add(new int[]{x, z, rad});
                p.smalls.add(SMALL_NAMES[type]);
                done = true;
            }
        }
        prune(p);
        settleLava(p);
        seal(p);
        return true;
    }

    /** Notes each column's lowest and highest rows the plan sets ({@link #keepOff}). */
    static void seal(Plan p) {
        for (int k = 0; k < SX * SX; k++) {
            int a = SY, b = -1;
            for (int dy = 0; dy < SY; dy++)
                if (p.b[k * SY + dy] != NONE) { a = Math.min(a, dy); b = dy; }
            p.lo[k] = (short) a;
            p.hi[k] = (short) b;
        }
    }

    /** Rows a feature keeps off the ruins over their highest blocks (head room), and the margin it keeps at least. */
    static final int KEEP_HEAD = 3;

    /**
     * Whether a structure in column (x, z) spanning rows lo..hi would come within {@code margin} blocks (sideways) of a
     * cluster's blocks, at their rows (from the lowest the plan sets to KEEP_HEAD over its highest): the Galleries'
     * other passes ask it through {@code Walkway.nearWays}, so no pool, fall or hanging column is laid in the ruins, and
     * none is cut by them as they are set after it. Planning the cells round (x, z) if they are not yet.
     */
    public boolean keepOff(int x, int z, int lo, int hi, double margin) {
        if (g == null || hi < g.B || lo > g.T + 8) return false;
        int m = (int) Math.ceil(Math.max(0, margin));
        for (Plan p : near(x - m, z - m, x + m, z + m)) {
            if (hi < p.y0 || lo > p.y0 + SY + KEEP_HEAD) continue;
            for (int dx = -m; dx <= m; dx++)
                for (int dz = -m; dz <= m; dz++) {
                    if (dx * dx + dz * dz > margin * margin + 0.5) continue;
                    int cx = x + dx - p.x0, cz = z + dz - p.z0;
                    if (cx < 0 || cz < 0 || cx >= SX || cz >= SX) continue;
                    int k = cx * SX + cz;
                    if (p.lo[k] > p.hi[k]) continue;
                    if (hi >= p.y0 + p.lo[k] && lo <= p.y0 + p.hi[k] + KEEP_HEAD) return true;
                }
        }
        return false;
    }

    /**
     * Drops what of plan p stands on no plain ground: every code of a column whose cell is not plain ({@link
     * #plainCell}), whose own top lies more than FLAT_LO under or FLAT_HI over the plan's ground there, or lies in the
     * lava's reach; and in every column, the plan's blocks within 2 rows of the ceiling and any code in the ceiling's
     * rock. Then every block no longer joined to the ground through others ({@link #support}).
     */
    void prune(Plan p) {
        for (int dx = 0; dx < SX; dx++)
            for (int dz = 0; dz < SX; dz++) {
                int k0 = (dx * SX + dz) * SY;
                boolean any = false;
                for (int dy = 0; dy < SY && !any; dy++) any = p.b[k0 + dy] != NONE;
                if (!any) continue;
                int x = p.x0 + dx, z = p.z0 + dz;
                GalleriesField.Cell c = g.column(x, z);
                int t = terrain(p, x, z), gr = p.ground[dx * SX + dz];
                if (!plainCell(c) || t < gr - FLAT_LO || t > gr + FLAT_HI || t < W + DRY - FLAT_LO) {
                    for (int dy = 0; dy < SY; dy++) p.b[k0 + dy] = NONE;
                    p.ground[dx * SX + dz] = t;
                    continue;
                }
                for (int dy = Math.max(0, c.c - 2 - p.y0); dy < SY; dy++) {
                    byte b = p.b[k0 + dy];
                    if (b != NONE && (b != AIR || p.y0 + dy >= c.c)) p.b[k0 + dy] = NONE;
                }
            }
        support(p);
    }

    /**
     * Drops every block of plan p (a code other than NONE, AIR and LAVA) not joined to the ground: to a block on the
     * terrain's own rock (NONE under it, at or under the terrain's top there), through blocks touching face to face. The
     * chests dropped leave its list. Lava is left to {@link #settleLava}.
     */
    void support(Plan p) {
        boolean[] on = new boolean[p.b.length];
        int[] queue = new int[p.b.length];
        int qh = 0, qt = 0;
        for (int dx = 0; dx < SX; dx++)
            for (int dz = 0; dz < SX; dz++) {
                int k0 = (dx * SX + dz) * SY, t = p.terrain[dx * SX + dz];
                if (t == Integer.MIN_VALUE) t = terrain(p, p.x0 + dx, p.z0 + dz);
                for (int dy = 1; dy < SY; dy++) {
                    byte b = p.b[k0 + dy];
                    if (b == NONE || b == AIR || b == LAVA) continue;
                    if (p.b[k0 + dy - 1] == NONE && p.y0 + dy - 1 <= t) { on[k0 + dy] = true; queue[qt++] = k0 + dy; }
                }
            }
        while (qh < qt) {
            int k = queue[qh++], dy = k % SY, col = k / SY, dz = col % SX, dx = col / SX;
            for (int d = 0; d < 6; d++) {
                int nx = dx + (d == 0 ? 1 : d == 1 ? -1 : 0), nz = dz + (d == 2 ? 1 : d == 3 ? -1 : 0), ny = dy + (d == 4 ? 1 : d == 5 ? -1 : 0);
                if (nx < 0 || nz < 0 || ny < 0 || nx >= SX || nz >= SX || ny >= SY) continue;
                int nk = (nx * SX + nz) * SY + ny;
                if (on[nk]) continue;
                byte b = p.b[nk];
                if (b == NONE || b == AIR || b == LAVA) continue;
                on[nk] = true;
                queue[qt++] = nk;
            }
        }
        for (int k = 0; k < p.b.length; k++) {
            byte b = p.b[k];
            if (b != NONE && b != AIR && b != LAVA && !on[k]) p.b[k] = NONE;
        }
        p.chests.removeIf(c -> {
            byte b = p.at(c[0], c[1], c[2]);
            return b < CHEST || b >= RUNE_CHEST + 4;
        });
    }

    /**
     * A small piece's paving row at (x, z), or Integer.MIN_VALUE where it cannot stand: every column within rad + 1 plain
     * ground ({@link #plainCell}) with HEAD_S open rows over it and none of the plan's blocks yet, their tops within
     * SMALL_LO + SMALL_HI rows of each other (the row between them: rock filled up to SMALL_LO rows under it, cut down
     * SMALL_HI over it), over the lava's reach, and no way down near.
     */
    private int smallGround(Plan p, int x, int z, int rad) {
        int r = rad + 1, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        if (!p.inBox(x - r, z - r) || !p.inBox(x + r, z + r)) return Integer.MIN_VALUE;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r + 1) continue;
                int xx = x + dx, zz = z + dz;
                GalleriesField.Cell c = g.column(xx, zz);
                if (!plainCell(c)) return Integer.MIN_VALUE;
                int t = terrain(p, xx, zz);
                if (c.c - (t + 1) < HEAD_S) return Integer.MIN_VALUE;
                if (ground(p, xx, zz) != t) return Integer.MIN_VALUE;
                // keep off the big structure's and the other pieces' blocks (their rubble too)
                for (int y = t - 2; y <= t + 3; y++) if (p.at(xx, y, zz) != NONE) return Integer.MIN_VALUE;
                lo = Math.min(lo, t);
                hi = Math.max(hi, t);
                if (hi - lo > SMALL_LO + SMALL_HI) return Integer.MIN_VALUE;
            }
        int py = Math.max(hi - SMALL_HI, Math.min(lo + SMALL_LO, (lo + hi + 1) / 2));
        if (py < W + DRY) return Integer.MIN_VALUE;
        if (g.wayNear(x, z, py - 4, py + HEAD_S, r + WAY_KEEP, HEAD_WAY)) return Integer.MIN_VALUE;
        return py;
    }

    // ------------------------------------------------------------------------------------------------ building
    /** Levels column (x, z) to paving row py with code pave: rock filled under it, the floor's bumps cut off over it. */
    void level(Plan p, int x, int z, int py, byte pave) {
        if (!p.inBox(x, z)) return;
        int t = terrain(p, x, z);
        // (a closed column's or a chasm's top lies far off: only the box's rows are walked; prune drops such a column)
        for (int y = Math.max(p.y0, Math.min(t + 1, py)); y < py; y++) p.set(x, y, z, BRICKS);
        p.set(x, py, z, pave);
        for (int y = py + 1, top = Math.min(p.yMax(), Math.max(t, py + 2)); y <= top; y++) if (p.at(x, y, z) == NONE) p.set(x, y, z, AIR);
        setGround(p, x, z, py);
    }

    /** A deterministic share in [0, 1) for block (x, y, z) of the plan and salt s. */
    static double rnd(Plan p, int x, int y, int z, int s) { return Hashing.unit(Hashing.mix(p.h ^ (s * 0x9E3779B97F4A7C15L), x, y, z)); }

    /** Bricks, a third of them cracked. */
    static byte brick(Plan p, int x, int y, int z) { return rnd(p, x, y, z, 1) < CRACK ? CRACKED_BRICKS : BRICKS; }

    /** Tiles, a third of them cracked. */
    static byte tile(Plan p, int x, int y, int z) { return rnd(p, x, y, z, 2) < CRACK ? CRACKED_TILES : TILES; }

    /** A smooth value noise of period 1 along t (hash h), in [0, 1]. */
    static double vnoise(long h, double t) {
        int i = (int) Math.floor(t);
        double f = t - i, a = Hashing.unit(Hashing.mix(h, i, 0, 0)), b = Hashing.unit(Hashing.mix(h, i + 1, 0, 0));
        f = f * f * (3 - 2 * f);
        return a + (b - a) * f;
    }

    /** A wall's broken top along it: the rows it stands to at place t (blocks) of a run first height H, half fallen. */
    static int broken(long h, double t, int H) {
        double n = 0.6 * vnoise(h, t / 5.0) + 0.4 * vnoise(Hashing.mix(h, 1, 0, 0), t / 1.7);
        return (int) Math.round(H * (DECAY_LO + (1 - DECAY_LO) * n * n * 1.25));
    }

    /** Sets a block where the plan has nothing there or open air (a piece's block over another's air). */
    static void put(Plan p, int x, int y, int z, byte c) {
        byte o = p.at(x, y, z);
        if (o == NONE || o == AIR) p.set(x, y, z, c);
    }

    /** Rubble: up to n blocks heaped on column (x, z)'s ground, where that lies within 2 rows of py. */
    void rubble(Plan p, int x, int z, int n, int py) {
        if (!p.inBox(x, z) || n <= 0) return;
        int gr = ground(p, x, z);
        if (Math.abs(gr - py) > 2) return;
        // on the open ground only: not into a piece's blocks, not where a cell's rock stands
        GalleriesField.Cell c = g.column(x, z);
        if (c.closed() || c.deckMode != 0 || c.bl != c.bh || c.pier) return;
        int y = gr + 1;
        while (p.at(x, y, z) != NONE && p.at(x, y, z) != AIR) {
            // over loose rubble it heaps; over a piece's own blocks it does not lie
            byte o = p.at(x, y, z);
            if (o != COBBLED && o != CRACKED_BRICKS && o != BRICKS || y > gr + 3) return;
            y++;
        }
        for (int k = 0; k < n && y < c.c - 2; k++, y++) {
            double r = rnd(p, x, y, z, 7);
            byte b = k == n - 1 && r < 0.45 ? SLAB : (r < 0.45 ? COBBLED : (r < 0.75 ? CRACKED_BRICKS : BRICKS));
            if (p.at(x, y, z) != NONE && p.at(x, y, z) != AIR) break;
            p.set(x, y, z, b);
            if (b == SLAB) break;
        }
        // the column's ground is the rubble's top for later rubble
    }

    /** A fallen pillar lying from (x, z) along world side d for len blocks on the ground near py, in pieces. */
    void fallen(Plan p, int x, int z, int d, int len, int py) {
        int dx = d == 0 ? 1 : (d == 2 ? -1 : 0), dz = d == 1 ? 1 : (d == 3 ? -1 : 0);
        byte b = dx != 0 ? PILLAR_X : PILLAR_Z;
        for (int k = 0; k < len; k++) {
            int xx = x + dx * k, zz = z + dz * k;
            if (!p.inBox(xx, zz)) return;
            if (rnd(p, xx, py, zz, 9) < 0.12 && k > 1 && k < len - 1) continue;   // a break between drums
            int gr = ground(p, xx, zz);
            if (Math.abs(gr - py) > 2) return;
            GalleriesField.Cell c = g.column(xx, zz);
            if (c.closed() || c.bl != c.bh || c.deckMode != 0) return;
            byte o = p.at(xx, gr + 1, zz);
            if (o != NONE && o != AIR) continue;
            p.set(xx, gr + 1, zz, b);
        }
    }

    // -------------------------------------------------------------------------------------------- the big structures
    /**
     * The broken tower: round, 11 blocks across with walls 2 thick, first 22 to 28 rows high with a chiseled band every
     * 6 rows and windows; broken off at a jagged height all round and down to a few rows over a breach on one side, its
     * fallen stones in a heap out from the breach. A stair winds up inside along its wall to where the wall stops, a
     * lava well glows in its floor, the rune's chest stands across from the breach. A paved court 19 across round it
     * with a lava brazier at each corner and a low broken wall round the court. Returns its reach (blocks).
     */
    private int tower(Plan p, Frame f, long h) {
        int py = p.gy;
        int H = Math.min(22 + (int) (Hashing.unit(h) * 7), p.head - 5);
        double ro = 5.5, ri = 3.5;
        double breach = Hashing.unit(Hashing.mix(h, 1, 0, 0)) * 2 * Math.PI;
        // the court
        for (int u = -9; u <= 9; u++)
            for (int v = -9; v <= 9; v++) {
                int x = f.x(u, v), z = f.z(u, v);
                boolean ring = Math.max(Math.abs(u), Math.abs(v)) >= 8;
                byte pave = ring ? brick(p, x, py, z) : ((u + v & 1) == 0 ? tile(p, x, py, z) : POLISHED);
                if (rnd(p, x, py, z, 3) < 0.08 && !ring) pave = COBBLED;
                level(p, x, z, py, pave);
            }
        // the court's low wall, mostly fallen, its gap at the front (-v)
        for (int k = -10; k <= 10; k++)
            for (int side = 0; side < 4; side++) {
                int u = side == 0 ? 10 : (side == 2 ? -10 : k), v = side == 1 ? 10 : (side == 3 ? -10 : k);
                if (side == 3 && Math.abs(u) <= 2) continue;
                int x = f.x(u, v), z = f.z(u, v);
                level(p, x, z, py, brick(p, x, py, z));
                int hb = broken(Hashing.mix(h, 30 + side, 0, 0), k + 10, 3) - 1;
                for (int y = 1; y <= hb; y++) put(p, x, py + y, z, brick(p, x, py + y, z));
            }
        // the tower's walls, broken
        for (int u = -6; u <= 6; u++)
            for (int v = -6; v <= 6; v++) {
                double r = Math.hypot(u, v);
                if (r > ro + 0.3 || r < ri - 0.3) continue;
                int x = f.x(u, v), z = f.z(u, v);
                double a = Math.atan2(v, u), da = Math.abs(Math.atan2(Math.sin(a - breach), Math.cos(a - breach)));
                int top = broken(Hashing.mix(h, 2, 0, 0), (a + Math.PI) * 6, H);
                if (da < 0.65) top = Math.min(top, 1 + (int) (4 * da / 0.65));          // the breach
                else if (da < 1.1) top = Math.min(top, (int) (H * (0.2 + 0.6 * (da - 0.65) / 0.45)));
                for (int y = 1; y <= top; y++) {
                    boolean window = (y % 6 == 2 || y % 6 == 3) && y > 6 && windowAt(a, h);
                    if (window) continue;
                    byte b = y % 6 == 0 ? CHISELED : brick(p, x, py + y, z);
                    p.set(x, py + y, z, b);
                }
            }
        // inside: the stair up along the wall, from the breach round; a lava well in the middle; the rune's chest
        double sa = breach + 0.9;
        for (int k = 0; k < 40; k++) {
            int y = 1 + k / 2;
            double a = sa + k * 0.2;
            int u = (int) Math.round(2.6 * Math.cos(a)), v = (int) Math.round(2.6 * Math.sin(a));
            int x = f.x(u, v), z = f.z(u, v);
            // stands only under the wall's own broken top there, and gaps where steps fell
            int wallTop = broken(Hashing.mix(h, 2, 0, 0), (Math.atan2(v, u) + Math.PI) * 6, H);
            if (y >= wallTop - 1 || rnd(p, x, y, z, 11) < 0.12) continue;
            byte o = p.at(x, py + y, z);
            if (o != NONE && o != AIR) continue;
            // the step: a stair rising along the turn, set into the wall beside it (a turn over another clears it by 15 rows)
            p.set(x, py + y, z, (byte) (STAIRS + stepSide(a + Math.PI / 2, f, u, v)));
        }
        int wx = f.x(0, 0), wz = f.z(0, 0);
        p.set(wx, py, wz, LAVA);
        for (int[] d : NB4) put(p, wx + d[0], py, wz + d[1], CHISELED);
        for (int[] d : NB4) {
            int x = wx + d[0], z = wz + d[1];
            p.set(x, py, z, CHISELED);
            p.set(x, py + 1, z, SLAB);
        }
        // the rune's chest by the far wall, across from the breach, facing the well
        double ca = breach + Math.PI;
        int cu = (int) Math.round(2.3 * Math.cos(ca)), cv = (int) Math.round(2.3 * Math.sin(ca));
        chest(p, f.x(cu, cv), py + 1, f.z(cu, cv), faceToward(f.x(cu, cv), f.z(cu, cv), wx, wz), true);
        // the corner braziers
        for (int su = -1; su <= 1; su += 2)
            for (int sv = -1; sv <= 1; sv += 2) brazier(p, f.x(6 * su, 6 * sv), f.z(6 * su, 6 * sv), py);
        // the fallen stones: a heap out from the breach, thinning outward, and a few drums of the top lying further
        for (int u = -12; u <= 12; u++)
            for (int v = -12; v <= 12; v++) {
                double r = Math.hypot(u, v);
                if (r < ro + 0.5 || r > 12) continue;
                double a = Math.atan2(v, u), da = Math.abs(Math.atan2(Math.sin(a - breach), Math.cos(a - breach)));
                if (da > 0.9) continue;
                int x = f.x(u, v), z = f.z(u, v);
                double depth = (1 - da / 0.9) * (1 - (r - ro) / (12 - ro));
                int n = (int) Math.floor(depth * 4 + rnd(p, x, 0, z, 12));
                rubble(p, x, z, n, py);
            }
        return 10;
    }

    /** Whether the tower's windows stand at angle a (four of them, a quarter turn apart). */
    private static boolean windowAt(double a, long h) {
        double o = Hashing.unit(Hashing.mix(h, 3, 0, 0)) * Math.PI / 2;
        double m = ((a - o) % (Math.PI / 2) + Math.PI / 2) % (Math.PI / 2);
        return m < 0.22 || m > Math.PI / 2 - 0.22;
    }

    /** The world side a stair rises toward for local heading a at local (u, v) (its nearest of the four). */
    private static int stepSide(double a, Frame f, int u, int v) {
        double cu = Math.cos(a), cv = Math.sin(a);
        int d = Math.abs(cu) > Math.abs(cv) ? (cu > 0 ? 0 : 2) : (cv > 0 ? 1 : 3);
        return f.side(d);
    }

    /** The side (0 east, 1 south, 2 west, 3 north) that faces from (x, z) toward (tx, tz). */
    static int faceToward(int x, int z, int tx, int tz) {
        int dx = tx - x, dz = tz - z;
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? 0 : 2;
        return dz >= 0 ? 1 : 3;
    }

    static final int[][] NB4 = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    /** A chest at (x, y, z) facing world side d: the rune's or a loot chest, on a solid block. */
    void chest(Plan p, int x, int y, int z, int d, boolean rune) {
        byte under = p.at(x, y - 1, z);
        if (under == NONE || under == AIR || under == LAVA) p.set(x, y - 1, z, POLISHED);
        p.set(x, y, z, (byte) ((rune ? RUNE_CHEST : CHEST) + d));
        p.set(x, y + 1, z, AIR);
        p.chests.add(new int[]{x, y, z, rune ? 1 : 0});
    }

    /** A lava brazier at column (x, z) on ground py: a block of polished stone with lava sunk in its top, rimmed. */
    void brazier(Plan p, int x, int z, int py) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                int xx = x + dx, zz = z + dz;
                if (ground(p, xx, zz) != py) level(p, xx, zz, py, POLISHED);
                p.set(xx, py + 1, zz, dx == 0 && dz == 0 ? LAVA : (dx == 0 || dz == 0 ? POLISHED : WALL));
            }
        p.set(x, py, z, CHISELED);
    }

    /**
     * The pillared hall: a platform 25 by 15 a step up from the ground, stairs along its front; two rows of six columns
     * of polished basalt on bases and under chiseled capitals, 11 rows high, half of them broken off and some lying where
     * they fell, lintels left over the pairs still standing; a lava channel down its middle; a broken back wall with the
     * rune's chest on an altar before it. Returns its reach.
     */
    private int hall(Plan p, Frame f, long h) {
        int py = p.gy, top = py + 1;
        int HU = 12, HV = 7;
        for (int u = -HU - 1; u <= HU + 1; u++)
            for (int v = -HV - 2; v <= HV + 1; v++) {
                int x = f.x(u, v), z = f.z(u, v);
                level(p, x, z, py, brick(p, x, py, z));
                if (Math.abs(u) <= HU && v >= -HV && v <= HV) {
                    boolean edge = Math.abs(u) == HU || v == -HV || v == HV;
                    byte b = edge ? brick(p, x, top, z) : ((Math.abs(u) + Math.abs(v)) % 4 == 0 ? POLISHED : tile(p, x, top, z));
                    p.set(x, top, z, b);
                    setGround(p, x, z, top);
                } else if (v == -HV - 1 && Math.abs(u) <= HU) {
                    // the front steps
                    if (rnd(p, x, top, z, 4) > 0.15) p.set(x, top, z, (byte) (STAIRS + f.side(1)));
                }
            }
        // the lava channel down the middle, with chiseled ends
        for (int u = -HU + 3; u <= HU - 3; u++) {
            int x = f.x(u, 0), z = f.z(u, 0);
            if (Math.abs(u) == HU - 3) { p.set(x, top, z, CHISELED); continue; }
            p.set(x, top, z, LAVA);
            p.set(x, py, z, POLISHED);
        }
        // the columns
        int CH = 11;
        boolean[][] whole = new boolean[2][6];
        for (int side = 0; side < 2; side++)
            for (int k = 0; k < 6; k++) {
                int u = -10 + 4 * k, v = side == 0 ? -5 : 5;
                int x = f.x(u, v), z = f.z(u, v);
                double r = Hashing.unit(Hashing.mix(h, 40 + side, k, 0));
                int stand = r < 0.3 ? CH : (r < 0.75 ? 1 + (int) (Hashing.unit(Hashing.mix(h, 42, side, k)) * 7) : 1);
                whole[side][k] = stand == CH;
                p.set(x, top + 1, z, POLISHED);
                for (int y = 2; y <= stand; y++) p.set(x, top + y, z, y == CH ? CHISELED : PILLAR);
                if (r >= 0.75) {
                    // fallen: it lies out from its base, away from the hall's middle or along it
                    int d = Hashing.unit(Hashing.mix(h, 43, side, k)) < 0.5 ? f.side(side == 0 ? 3 : 1) : f.side(Hashing.unit(Hashing.mix(h, 44, side, k)) < 0.5 ? 0 : 2);
                    int[] o = NB4[d];
                    fallen(p, x + o[0] * 2, z + o[1] * 2, d, 5 + (int) (Hashing.unit(Hashing.mix(h, 45, side, k)) * 4), top);
                } else if (stand < CH) {
                    // its drums lie round its foot
                    for (int q = 0; q < 2; q++) {
                        int[] o = NB4[(int) (Hashing.unit(Hashing.mix(h, 46 + q, side, k)) * 4)];
                        rubble(p, x + o[0] * (1 + q), z + o[1] * (1 + q), 1, top);
                    }
                }
            }
        // lintels over the neighbouring pairs still whole
        for (int side = 0; side < 2; side++)
            for (int k = 0; k < 5; k++) {
                if (!whole[side][k] || !whole[side][k + 1]) continue;
                int v = side == 0 ? -5 : 5;
                for (int u = -10 + 4 * k; u <= -6 + 4 * k; u++) {
                    int x = f.x(u, v), z = f.z(u, v);
                    p.set(x, top + CH + 1, z, brick(p, x, top + CH + 1, z));
                }
            }
        // the back wall, broken, and the rune's altar before it
        long hw = Hashing.mix(h, 50, 0, 0);
        for (int u = -HU; u <= HU; u++) {
            int x = f.x(u, HV + 1), z = f.z(u, HV + 1);
            level(p, x, z, py, brick(p, x, py, z));
            p.set(x, top, z, brick(p, x, top, z));
            setGround(p, x, z, top);
            int hb = Math.abs(u) <= 1 ? 2 : broken(hw, u + HU, 10);
            for (int y = 1; y <= hb; y++) p.set(x, top + y, z, y == 5 ? CHISELED : brick(p, x, top + y, z));
        }
        int ax = f.x(0, HV - 1), az = f.z(0, HV - 1);
        p.set(ax, top, az, CHISELED);
        chest(p, ax, top + 1, az, f.side(3), true);
        for (int s = -1; s <= 1; s += 2) {
            int x = f.x(s, HV - 1), z = f.z(s, HV - 1);
            p.set(x, top + 1, z, (byte) (STAIRS + f.side(s > 0 ? 2 : 0)));
        }
        // fallen stones along the back wall's foot and round the platform
        for (int u = -HU; u <= HU; u++) {
            int x = f.x(u, HV + 2), z = f.z(u, HV + 2);
            rubble(p, x, z, (int) (rnd(p, x, 1, z, 13) * 3), py);
            int x2 = f.x(u, HV), z2 = f.z(u, HV);
            if (rnd(p, x2, 2, z2, 14) < 0.35) rubble(p, x2, z2, 1, top);
        }
        return 15;
    }

    /**
     * The stepped shrine: four tiers of bricks 19, 15, 11 and 7 blocks across, three rows each, a stair up from the ground
     * in front to the top, where four broken posts stand round a lava basin and the rune's chest behind it; corners and edges
     * crumbled, one flank slumped into a heap; two lava wells at the stair's foot. Returns its reach.
     */
    private int shrine(Plan p, Frame f, long h) {
        int py = p.gy;
        int[] half = {9, 7, 5, 3};
        int slump = (int) (Hashing.unit(h) * 4);   // the flank that slumped (local side)
        for (int u = -10; u <= 10; u++)
            for (int v = -17; v <= 10; v++) {
                int x = f.x(u, v), z = f.z(u, v);
                level(p, x, z, py, v < -10 ? tile(p, x, py, z) : brick(p, x, py, z));
            }
        for (int t = 0; t < 4; t++) {
            int a = half[t], y0 = py + 1 + 3 * t;
            for (int u = -a; u <= a; u++)
                for (int v = -a; v <= a; v++) {
                    int x = f.x(u, v), z = f.z(u, v);
                    boolean edge = Math.abs(u) == a || Math.abs(v) == a, corner = Math.abs(u) == a && Math.abs(v) == a;
                    int side = Math.abs(u) >= Math.abs(v) ? (u > 0 ? 0 : 2) : (v > 0 ? 1 : 3);
                    for (int y = y0; y < y0 + 3; y++) {
                        double r = rnd(p, x, y, z, 15);
                        if (corner && r < 0.55) continue;
                        if (edge && y == y0 + 2 && r < 0.3) continue;
                        // the slumped flank: its outer blocks gone, more toward the top
                        if (side == slump && Math.max(Math.abs(u), Math.abs(v)) >= a - 1 && r < 0.25 + 0.15 * t) continue;
                        p.set(x, y, z, edge && y == y0 + 2 ? (r < 0.6 ? CHISELED : brick(p, x, y, z)) : brick(p, x, y, z));
                    }
                }
        }
        // the stair up the front (-v), three wide, a row a block from the ground in front to the top's edge, cut into
        // the tiers it meets; some steps fallen
        for (int k = 0; k < 12; k++) {
            int v = -15 + k, y = py + 1 + k;
            for (int u = -1; u <= 1; u++) {
                int x = f.x(u, v), z = f.z(u, v);
                for (int yy = py + 1; yy < y; yy++) if (p.at(x, yy, z) == NONE || p.at(x, yy, z) == AIR) p.set(x, yy, z, brick(p, x, yy, z));
                p.set(x, y, z, rnd(p, x, y, z, 16) < 0.1 ? COBBLED : (byte) (STAIRS + f.side(1)));
                for (int yy = y + 1; yy <= y + 3; yy++) p.set(x, yy, z, AIR);
            }
        }
        // the top: a platform 7 across at py + 12, posts at its corners broken, the basin, the chest
        int ty = py + 12;
        for (int u = -3; u <= 3; u++)
            for (int v = -3; v <= 3; v++) {
                int x = f.x(u, v), z = f.z(u, v);
                p.set(x, ty, z, (u + v & 1) == 0 ? POLISHED : tile(p, x, ty, z));
                for (int y = ty + 1; y <= ty + 4; y++) if (p.at(x, y, z) == NONE) p.set(x, y, z, AIR);
            }
        for (int su = -1; su <= 1; su += 2)
            for (int sv = -1; sv <= 1; sv += 2) {
                int x = f.x(3 * su, 3 * sv), z = f.z(3 * su, 3 * sv);
                int hb = 1 + (int) (Hashing.unit(Hashing.mix(h, 60 + su, sv, 0)) * 4);
                for (int y = 1; y <= hb; y++) p.set(x, ty + y, z, y == hb && hb == 4 ? CHISELED : PILLAR);
            }
        int bx = f.x(0, -1), bz = f.z(0, -1);
        p.set(bx, ty, bz, LAVA);
        p.set(bx, ty - 1, bz, POLISHED);
        for (int[] d : NB4) {
            p.set(bx + d[0], ty, bz + d[1], CHISELED);
            p.set(bx + d[0], ty + 1, bz + d[1], SLAB);
        }
        int cx = f.x(0, 2), cz = f.z(0, 2);
        chest(p, cx, ty + 1, cz, f.side(3), true);
        // two wells at the stair's foot
        for (int s = -1; s <= 1; s += 2) well(p, f.x(4 * s, -15), f.z(4 * s, -15), py);
        // the slumped flank's heap
        for (int u = -14; u <= 14; u++)
            for (int v = -14; v <= 14; v++) {
                int lu = u, lv = v;
                int side = Math.abs(lu) >= Math.abs(lv) ? (lu > 0 ? 0 : 2) : (lv > 0 ? 1 : 3);
                if (side != slump || slump == 3) continue;
                int d = Math.max(Math.abs(u), Math.abs(v));
                if (d < 10 || d > 14) continue;
                int x = f.x(u, v), z = f.z(u, v);
                rubble(p, x, z, (int) Math.floor((14 - d) * 0.6 + rnd(p, x, 3, z, 17)), py);
            }
        return 12;
    }

    /** A lava well at column (x, z) on ground py: lava flush in the paving, a ring of bricks round it a row up. */
    void well(Plan p, int x, int z, int py) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                int xx = x + dx, zz = z + dz;
                if (ground(p, xx, zz) != py) level(p, xx, zz, py, BRICKS);
                if (dx == 0 && dz == 0) {
                    p.set(xx, py, zz, LAVA);
                    p.set(xx, py - 1, zz, POLISHED);
                } else {
                    p.set(xx, py, zz, POLISHED);
                    p.set(xx, py + 1, zz, (dx == 0 || dz == 0) ? SLAB : brick(p, xx, py + 1, zz));
                }
            }
    }

    /**
     * The great gate: two pylons 5 blocks square, 17 to 20 rows high, hollow, either side of a gap 5 wide, the arch over
     * the gap half fallen onto the road; broken walls running out from them; a paved road through it between lava
     * channels; the rune's chest inside one pylon, through a doorway on the road. Returns its reach.
     */
    private int gate(Plan p, Frame f, long h) {
        int py = p.gy;
        int H = Math.min(17 + (int) (Hashing.unit(h) * 4), p.head - 6);
        int fallSide = Hashing.unit(Hashing.mix(h, 1, 0, 0)) < 0.5 ? -1 : 1;
        int chestSide = Hashing.unit(Hashing.mix(h, 2, 0, 0)) < 0.5 ? -1 : 1;
        // the road and the ground round the pylons
        for (int u = -9; u <= 9; u++)
            for (int v = -14; v <= 14; v++) {
                int x = f.x(u, v), z = f.z(u, v);
                boolean road = Math.abs(u) <= 2;
                level(p, x, z, py, road ? ((v & 1) == 0 ? tile(p, x, py, z) : POLISHED) : brick(p, x, py, z));
            }
        // the lava channels either side of the road, out from the pylons
        for (int v = -13; v <= 13; v++) {
            if (Math.abs(v) <= 3) continue;
            for (int s = -1; s <= 1; s += 2) {
                int x = f.x(4 * s, v), z = f.z(4 * s, v);
                boolean end = Math.abs(v) == 4 || Math.abs(v) == 13;
                p.set(x, py, z, end ? CHISELED : LAVA);
                if (!end) p.set(x, py - 1, z, POLISHED);
            }
        }
        // the pylons
        for (int s = -1; s <= 1; s += 2) {
            long hs = Hashing.mix(h, 3, s, 0);
            int pTop = H - (s == fallSide ? 3 + (int) (Hashing.unit(hs) * 5) : (int) (Hashing.unit(hs) * 2));
            for (int u = 3; u <= 7; u++)
                for (int v = -2; v <= 2; v++) {
                    int uu = s * u, x = f.x(uu, v), z = f.z(uu, v);
                    boolean shell = u == 3 || u == 7 || Math.abs(v) == 2;
                    int top = pTop - (shell ? 0 : 0);
                    // its top crumbles: the corners and the shell's upper rows lose blocks
                    int crown = broken(Hashing.mix(hs, u, v, 0), (u + v) * 1.3, 4);
                    for (int y = 1; y <= top + crown - 2; y++) {
                        if (!shell && y < top - 2) { p.set(x, py + y, z, AIR); continue; }
                        if (y > top - 2 && !shell) continue;
                        byte b = (y % 5 == 0) ? CHISELED : brick(p, x, py + y, z);
                        if (y > top - 3 && Math.abs(v) == 2 && (u == 3 || u == 7) && rnd(p, x, py + y, z, 18) < 0.5) continue;
                        p.set(x, py + y, z, b);
                    }
                }
            // a doorway into the chest's pylon, from the road
            if (s == chestSide) {
                for (int y = 1; y <= 3; y++) {
                    p.set(f.x(3 * s, 0), py + y, f.z(3 * s, 0), AIR);
                }
                p.set(f.x(3 * s, 0), py + 4, f.z(3 * s, 0), (byte) (STAIRS + 4 + f.side(s > 0 ? 2 : 0)));
                int cx = f.x(6 * s, 0), cz = f.z(6 * s, 0);
                chest(p, cx, py + 1, cz, f.side(s > 0 ? 2 : 0), true);
            }
        }
        // the arch over the gap: whole on one side, fallen on the other (its stones on the road under it)
        for (int u = -2; u <= 2; u++) {
            int x = f.x(u, 0), z = f.z(u, 0);
            boolean gone = u * fallSide > 0 || (u == 0 && Hashing.unit(Hashing.mix(h, 5, 0, 0)) < 0.5);
            for (int v = -2; v <= 2; v++) {
                int xv = f.x(u, v), zv = f.z(u, v);
                if (gone) {
                    if (rnd(p, xv, 0, zv, 19) < 0.5) rubble(p, xv, zv, 1 + (int) (rnd(p, xv, 1, zv, 20) * 2), py);
                    continue;
                }
                for (int y = 11; y <= 13; y++) p.set(xv, py + y, zv, y == 11 && Math.abs(u) == 2 ? (byte) (STAIRS + 4 + f.side(u > 0 ? 0 : 2)) : brick(p, xv, py + y, zv));
            }
            if (!gone && u == 0) {
                for (int y = 8; y <= 10; y++) p.set(x, py + y, z, CHAIN);
            }
        }
        // the walls out from the pylons, broken low
        for (int s = -1; s <= 1; s += 2) {
            long hw = Hashing.mix(h, 7, s, 0);
            for (int u = 8; u <= 15; u++) {
                int uu = s * u;
                for (int v = -1; v <= 0; v++) {
                    int x = f.x(uu, v), z = f.z(uu, v);
                    if (!plain(x, z, py, 2, 2, HEAD_S)) continue;
                    level(p, x, z, py, brick(p, x, py, z));
                    int hb = broken(hw, u - 8 + 0.3 * v, 8) - (u - 8) / 2;
                    for (int y = 1; y <= hb; y++) p.set(x, py + y, z, brick(p, x, py + y, z));
                    if (hb < 3) rubble(p, f.x(uu, v - 2), f.z(uu, v - 2), 1 + (int) (rnd(p, x, 5, z, 21) * 2), py);
                }
            }
        }
        return 15;
    }

    // ----------------------------------------------------------------------------------------- the small ruins
    static final int WALL_PIECE = 0, COLUMNS = 1, HUT = 2, ARCH = 3, BRAZIER = 4, FALLEN = 5, STAIR = 6, WELL = 7;
    static final int SMALL_TYPES = 8;
    static final int[] SMALL_R = {5, 4, 4, 3, 2, 4, 3, 2};
    static final String[] SMALL_NAMES = {"wall", "columns", "hut", "arch", "brazier", "fallen column", "stair", "well"};

    /**
     * A hut's frame turned so that its doorway (local side -v, b blocks out) opens onto the ground that lies nearest its
     * floor (paving row py) just outside it, lower ground before higher (steps can be laid down to it, {@link
     * #doorSteps}, but not cut up into a bank). A hut's paving may stand up to SMALL_LO rows over the ground round it,
     * and on the first plans one hut of the two test seeds' 74 chests stood on such a plinth with its doorway over the
     * drop and a bank two rows over its floor on the far side: its loot chest could be dropped into but not walked out of.
     */
    private Frame doorFrame(Plan p, Frame f, int b, int py) {
        Frame best = f;
        int bestCost = Integer.MAX_VALUE;
        for (int k = 0; k < 4; k++) {
            Frame fk = k == 0 ? f : new Frame(f.ox, f.oz, f.rot + k, f.mirror);
            int x = fk.x(0, -b - 1), z = fk.z(0, -b - 1);
            if (!p.inBox(x, z) || !plainCell(g.column(x, z))) continue;
            int t = terrain(p, x, z), cost = t > py ? 3 * (t - py) : py - t;
            if (cost < bestCost) { bestCost = cost; best = fk; }
        }
        return best;
    }

    /**
     * Steps down from a hut's doorway (frame f, its wall b blocks out along -v, paving row py) to the ground outside,
     * where that lies more than a row under the floor: a brick stair a row lower at each block out, rising toward the
     * door, over rock filled to it, until the ground is a step from the last stair (at most SMALL_LO of them). Stops at
     * a column that is not plain ground or that holds another piece's blocks; loose rubble on a stair's column goes.
     */
    private void doorSteps(Plan p, Frame f, int b, int py) {
        int top = py;
        for (int k = 1; k <= SMALL_LO; k++) {
            int x = f.x(0, -b - k), z = f.z(0, -b - k);
            if (!p.inBox(x, z) || !plainCell(g.column(x, z))) return;
            int t = terrain(p, x, z), s = top - 1;
            if (t >= s) return;
            for (int y = t + 1; y <= s + 3; y++) {
                byte o = p.at(x, y, z);
                if (o != NONE && o != AIR && o != COBBLED && o != CRACKED_BRICKS && o != BRICKS && o != SLAB) return;
            }
            for (int y = Math.max(p.y0, t + 1); y < s; y++) p.set(x, y, z, BRICKS);
            p.set(x, s, z, (byte) (STAIRS + f.side(1)));
            for (int y = s + 1; y <= s + 3 && y <= p.yMax(); y++) if (p.at(x, y, z) != AIR) p.set(x, y, z, NONE);
            setGround(p, x, z, s);
            top = s;
        }
    }

    /** Small ruin of the given type at frame f on paving row py; true where it took a chest (given one). */
    private boolean small(Plan p, Frame f, int py, int type, long h, boolean chest) {
        switch (type) {
            case WALL_PIECE: {
                int L = 5 + (int) (Hashing.unit(h) * 5), H = 3 + (int) (Hashing.unit(Hashing.mix(h, 1, 0, 0)) * 4);
                int turn = Hashing.unit(Hashing.mix(h, 2, 0, 0)) < 0.5 ? 2 + (int) (Hashing.unit(Hashing.mix(h, 3, 0, 0)) * 3) : 0;
                for (int k = -L / 2; k <= L - L / 2; k++) wallCol(p, f, k, 0, py, broken(h, k + 10, H), true);
                for (int k = 1; k <= turn; k++) wallCol(p, f, L - L / 2, k, py, broken(Hashing.mix(h, 4, 0, 0), k, H - 1), true);
                return false;
            }
            case COLUMNS: {
                int n = 2 + (Hashing.unit(h) < 0.5 ? 1 : 0);
                for (int k = 0; k < n; k++) {
                    int u = (k - (n - 1) / 2) * 3;
                    int x = f.x(u, 0), z = f.z(u, 0);
                    for (int du = -1; du <= 1; du++) for (int dv = -1; dv <= 1; dv++) level(p, f.x(u + du, dv), f.z(u + du, dv), py, tile(p, f.x(u + du, dv), py, f.z(u + du, dv)));
                    double r = Hashing.unit(Hashing.mix(h, 1, k, 0));
                    int stand = 1 + (int) (r * r * 7);
                    p.set(x, py + 1, z, POLISHED);
                    for (int y = 2; y <= stand; y++) p.set(x, py + y, z, PILLAR);
                    if (stand <= 2 && k == n - 1) fallen(p, f.x(u, 2), f.z(u, 2), f.side(1), 4, py);
                }
                return false;
            }
            case HUT: {
                int a = 2 + (Hashing.unit(h) < 0.5 ? 1 : 0), b = 2 + (Hashing.unit(Hashing.mix(h, 1, 0, 0)) < 0.6 ? 0 : 1);
                int H = 3 + (Hashing.unit(Hashing.mix(h, 2, 0, 0)) < 0.5 ? 1 : 0);
                f = doorFrame(p, f, b, py);
                for (int u = -a; u <= a; u++)
                    for (int v = -b; v <= b; v++) {
                        int x = f.x(u, v), z = f.z(u, v);
                        boolean wall = Math.abs(u) == a || Math.abs(v) == b;
                        level(p, x, z, py, wall ? brick(p, x, py, z) : tile(p, x, py, z));
                        if (!wall) continue;
                        if (v == -b && u == 0) continue;   // the doorway
                        int t = (u + a) + (v + b) * 3;
                        wallCol(p, f, u, v, py, broken(Hashing.mix(h, 3, 0, 0), t * 0.8, H), false);
                    }
                // the fallen stones inside and out
                for (int q = 0; q < 4; q++) {
                    int u = (int) Math.round((Hashing.unit(Hashing.mix(h, 5, q, 0)) * 2 - 1) * (a + 2));
                    int v = (int) Math.round((Hashing.unit(Hashing.mix(h, 6, q, 0)) * 2 - 1) * (b + 2));
                    if (Math.abs(u) == a || Math.abs(v) == b) continue;
                    rubble(p, f.x(u, v), f.z(u, v), 1, py);
                }
                doorSteps(p, f, b, py);
                if (chest) {
                    int u = a - 1, v = b - 1;
                    chest(p, f.x(u, v), py + 1, f.z(u, v), f.side(3), false);
                    return true;
                }
                return false;
            }
            case ARCH: {
                boolean broke = Hashing.unit(h) < 0.6;
                for (int u = -3; u <= 3; u++)
                    for (int v = -1; v <= 1; v++) level(p, f.x(u, v), f.z(u, v), py, Math.abs(u) <= 1 ? tile(p, f.x(u, v), py, f.z(u, v)) : brick(p, f.x(u, v), py, f.z(u, v)));
                for (int s = -1; s <= 1; s += 2) {
                    int ph = s > 0 && broke ? 2 + (int) (Hashing.unit(Hashing.mix(h, 1, 0, 0)) * 2) : 5;
                    for (int u = 2; u <= 3; u++)
                        for (int y = 1; y <= ph; y++) p.set(f.x(s * u, 0), py + y, f.z(s * u, 0), y == 5 ? CHISELED : brick(p, f.x(s * u, 0), py + y, f.z(s * u, 0)));
                }
                // the lintel: whole, or only its first block on the standing side, the rest fallen at its foot
                for (int u = -3; u <= 3; u++) {
                    int x = f.x(u, 0), z = f.z(u, 0);
                    if (broke && u > -1) {
                        if (u <= 1) rubble(p, f.x(u, 1), f.z(u, 1), 1, py);
                        continue;
                    }
                    p.set(x, py + 6, z, brick(p, x, py + 6, z));
                    if (u == -1) p.set(x, py + 5, z, (byte) (STAIRS + 4 + f.side(2)));
                    if (u == 1 && !broke) p.set(x, py + 5, z, (byte) (STAIRS + 4 + f.side(0)));
                }
                return false;
            }
            case BRAZIER: {
                for (int u = -2; u <= 2; u++)
                    for (int v = -2; v <= 2; v++) level(p, f.x(u, v), f.z(u, v), py, (Math.abs(u) == 2 || Math.abs(v) == 2) ? brick(p, f.x(u, v), py, f.z(u, v)) : tile(p, f.x(u, v), py, f.z(u, v)));
                brazier(p, f.x(0, 0), f.z(0, 0), py);
                for (int s = 0; s < 2; s++) {
                    int u = s == 0 ? -2 : 2, v = s == 0 ? 2 : -2;
                    int hb = 1 + (int) (Hashing.unit(Hashing.mix(h, 1, s, 0)) * 3);
                    for (int y = 1; y <= hb; y++) p.set(f.x(u, v), py + y, f.z(u, v), PILLAR);
                }
                return false;
            }
            case FALLEN: {
                int len = 5 + (int) (Hashing.unit(h) * 4);
                for (int u = -1; u <= 1; u++) for (int v = -1; v <= 1; v++) level(p, f.x(u, v), f.z(u, v), py, POLISHED);
                p.set(f.x(0, 0), py + 1, f.z(0, 0), POLISHED);
                int stub = 1 + (Hashing.unit(Hashing.mix(h, 1, 0, 0)) < 0.5 ? 1 : 0);
                for (int y = 2; y <= stub + 1; y++) p.set(f.x(0, 0), py + y, f.z(0, 0), PILLAR);
                fallen(p, f.x(0, 2), f.z(0, 2), f.side(1), len, py);
                int ex = f.x(0, 2 + len), ez = f.z(0, 2 + len);
                if (p.inBox(ex, ez) && Math.abs(ground(p, ex, ez) - py) <= 1) put(p, ex, ground(p, ex, ez) + 1, ez, CHISELED);
                return false;
            }
            case STAIR: {
                for (int u = -2; u <= 2; u++)
                    for (int v = -3; v <= 2; v++) level(p, f.x(u, v), f.z(u, v), py, brick(p, f.x(u, v), py, f.z(u, v)));
                int land = 3 + (Hashing.unit(h) < 0.5 ? 1 : 0);
                for (int u = -1; u <= 1; u++)
                    for (int v = -1; v <= 1; v++)
                        for (int y = 1; y <= land; y++) {
                            int x = f.x(u, v + 1), z = f.z(u, v + 1);
                            if (y == land && rnd(p, x, py + y, z, 22) < 0.3) continue;
                            p.set(x, py + y, z, y == land ? tile(p, x, py + y, z) : brick(p, x, py + y, z));
                        }
                for (int k = 0; k < land; k++)
                    for (int u = -1; u <= 1; u++) {
                        int v = -1 - land + k + 1, x = f.x(u, v - 1 + 1), z = f.z(u, v - 1 + 1);
                        if (rnd(p, x, py + k + 1, z, 23) < 0.15) continue;
                        for (int y = 1; y <= k; y++) put(p, x, py + y, z, brick(p, x, py + y, z));
                        put(p, x, py + k + 1, z, (byte) (STAIRS + f.side(1)));
                    }
                return false;
            }
            default: {
                for (int u = -2; u <= 2; u++) for (int v = -2; v <= 2; v++) level(p, f.x(u, v), f.z(u, v), py, tile(p, f.x(u, v), py, f.z(u, v)));
                well(p, f.x(0, 0), f.z(0, 0), py);
                return false;
            }
        }
    }

    /** A column of a wall at local (u, v) of frame f, standing hb rows over paving py (levelled there when lev). */
    private void wallCol(Plan p, Frame f, int u, int v, int py, int hb, boolean lev) {
        int x = f.x(u, v), z = f.z(u, v);
        if (lev) {
            if (!plain(x, z, py, 2, 2, HEAD_S)) return;
            level(p, x, z, py, brick(p, x, py, z));
        }
        for (int y = 1; y <= hb; y++) p.set(x, py + y, z, brick(p, x, py + y, z));
        // what fell off its top lies at its foot, either side
        if (hb < 4) {
            int[] o = NB4[f.side(rnd(p, x, 0, z, 24) < 0.5 ? 1 : 3)];
            rubble(p, x + o[0], z + o[1], rnd(p, x, 1, z, 25) < 0.5 ? 1 : 0, py);
        }
    }

    // --------------------------------------------------------------------------------------------- the lava's hold
    /**
     * Whether lava at (x, y, z) of plan p stays put: a full block (of the plan, or the terrain's own under its top) under
     * it and on its four sides, or lava; and open over it. A side in another chunk must be the terrain's own rock (the
     * plan's blocks there are set when that chunk is made, maybe after this one), so whether a block of lava stands
     * goes by the plan and the field alone.
     */
    public boolean held(Plan p, int x, int y, int z) {
        if (!holds(p, x, y - 1, z, x, z)) return false;
        for (int[] d : NB4) if (!holds(p, x + d[0], y, z + d[1], x, z)) return false;
        byte up = p.at(x, y + 1, z);
        return up == AIR || (up == NONE && y + 1 > terrain(p, x, z));
    }

    private boolean holds(Plan p, int x, int y, int z, int ox, int oz) {
        byte c = p.at(x, y, z);
        boolean other = (x >> 4) != (ox >> 4) || (z >> 4) != (oz >> 4);
        if (c == LAVA) return true;
        if (full(c)) return !other || y <= terrain(p, x, z);
        if (c == NONE) return y <= terrain(p, x, z);
        return false;
    }

    /** Every lava block of the plan that is not held becomes a chiseled block (run once a plan is laid). */
    void settleLava(Plan p) {
        for (int dx = 0; dx < SX; dx++)
            for (int dz = 0; dz < SX; dz++)
                for (int dy = 0; dy < SY; dy++) {
                    int k = (dx * SX + dz) * SY + dy;
                    if (p.b[k] != LAVA) continue;
                    int x = p.x0 + dx, y = p.y0 + dy, z = p.z0 + dz;
                    if (!held(p, x, y, z)) p.b[k] = CHISELED;
                }
    }
}
