package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.LongFunction;
import java.util.function.Predicate;

import static com.explorationremix.world.terrain.GardensPlan.K;
import static com.explorationremix.world.terrain.GardensPlan.SY;

/**
 * The Deep Gardens in the game (round 2): the noise vent field the previews worked out over a patch, made seamless.
 *
 * <p>Every decision is taken once for the whole world and never depends on which chunk asks: whether a vent hall
 * lifts its storey (its stack's fit on the first pass), which vents stand (each cluster's fit and the rule between
 * clusters, which only reads candidates within a few blocks), which cracks open (each vent's door test on the carve).
 * They read the storeys at single columns, whose distance fields come from fixed tiles worked out with a margin
 * ({@link Edt}), so a column's storeys are the same whichever window or lookup asks for them. The blocks are then
 * made per 128-block tile from a window 40 blocks wider each side (the rubble, the dressing and the oil need that
 * much round them), and only the tile's own columns are kept.
 *
 * <p>Thread safe: the game's worker threads share the caches; at most two block tiles are made at once.
 */
public final class GardensField {
    /** Tiles: the footprint inputs, the distance fields (and their margin), the vent decisions, the blocks (and margin). */
    static final int T0 = 64, TE = 128, EM = 46, TS = 128, TB = 128, BM = 40;
    /** The distance fields are kept to 1/1024 of a block (as chars: they never pass 40). */
    static final float EDT_Q = 1024f;
    /**
     * The margins the decisions read round a tile, from the vents' sizes (GardensSites.GIRTH and TALL). The largest
     * bound t33 comes to about 59 at most, a deep vent's (at R 17.25 a foot of 28.4, its hood's 8, an apron of 20.4
     * off a drop of 12, and 2); a plain deep vent's to 56 and an ordinary vent's to 49.4 (a beehive of R 13.5, its
     * porches over a drop of 12). Over the 1024 x 1024 round the origin at seed 8675309 the largest are 52.4 (a deep
     * vent), 49.5 (a plain one) and 47.0 (a beehive), where they were 36.6 at most before the vents grew. So:
     * <ul>
     * <li>VENT_REACH, round a block window for the standing vents: every vent whose bound could reach it, so at
     * least the largest bound (it was 48 for bounds up to about 37); the dressing's vent weights reach Rf + 7 +
     * 1.35 t46 (56 at most), under the window's margin and this;
     * <li>CAND_PAD, round a site tile for the clusters: the rule between clusters reads every rival within
     * 1.15 (Rv + Rq) + 4 of a vent (35 for two of R 13.5), and a member lies up to about 24 from its cluster's
     * anchor, which lies up to 0.45 r from its dome's centre (a dome is found when its disc of 1.2 r reaches the
     * box) or 3 from its vent hall's, so at least 35 + 24 + 3 and some (it was 60 when that came to about 45);
     * <li>DOOR_PAD, round a site tile for the door tests: a vent's door boxes reach t32 + 10 from its centre (36 for a
     * deep vent) and another vent's walls its routMax (t47, at most 42), so at least 78, and the vents the test lists
     * are those whose bound comes within t32 + 16 (101 at most); under the 128 of the site tiles round it;
     * <li>DEEP_PAD, round a box for the deep sites whose clearing (GardensSites.DEEP_CLEAR, 54) could reach it.
     * </ul>
     * Too small a margin makes tiles disagree at their seams; GardensFieldTest builds a tile from two windows.
     */
    static final double VENT_REACH = 64, CAND_PAD = 80, DOOR_PAD = 112, DEEP_PAD = GardensSites.DEEP_CLEAR + 4;

    final GardensPlan plan;
    final GardensVents gv;
    final GardensRock.Gates gates;
    /** The game's gates, the landings the storeys' floors are pulled to. */
    final GardensGates gateList;
    private final int[] p1, p2;
    /** The first pass (every vent hall lifts) and the second (only those whose stack stands). */
    final Pass passA, passB;
    /** The throat exits' search, or null: then every deep site is a plain live vent. */
    volatile Exits exits;

    private final Lru<Tier0> tier0 = new Lru<>(Integer.getInteger("gardens.tier0Cap", 1024));
    private final Map<Long, Boolean> held = new ConcurrentHashMap<>();
    private final Lru<List<GardensSites.Site>> siteTiles = new Lru<>(256);
    private final Lru<List<GardensSites.Site>> decidedTiles = new Lru<>(256);
    private final Lru<Tile> blockTiles = new Lru<>(16);
    private final Semaphore heavy = new Semaphore(2);
    /** Counts and times (ns) of each kind of work, for /remix info and the checks. */
    final java.util.concurrent.atomic.AtomicLongArray stats = new java.util.concurrent.atomic.AtomicLongArray(16);
    static final int S_TIER0 = 0, S_EDT = 2, S_COL = 4, S_HELD = 6, S_SITES = 8, S_DECIDED = 10, S_TILE = 12, S_EDTB = 14;
    static final String[] STAT_NAMES = {"tier0", "edt", "column", "held", "sites", "doors", "tile", "edtB"};

    private void stat(int s, long t0) {
        stats.incrementAndGet(s);
        stats.addAndGet(s + 1, System.nanoTime() - t0);
    }

    /** The work done so far, one line. */
    String statLine() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < STAT_NAMES.length; i++) {
            long n = stats.get(2 * i), ns = stats.get(2 * i + 1);
            b.append(i == 0 ? "" : ", ").append(STAT_NAMES[i]).append(' ').append(n).append(String.format(java.util.Locale.ROOT, " (%.1f s)", ns / 1e9));
        }
        return b.toString();
    }

    /** Where the throats come out: per deep site, its level run into the Wasteland's tunnels, or null for none. */
    interface Exits {
        /** For the deep vent of site d (its table row built with a stand-in throat), the exit found, or null. */
        Exit find(GardensPlan.DeepSite d, GardensVents.Vent v);
    }

    /**
     * An exit: the row the throat levels out at, its level run's length (blocks along the helix), and where an outlet
     * leaves the level run and how long it runs (NaN for an exit off the ring itself).
     */
    record Exit(int g, double levelLen, double outletDep, double outletLen) {}

    GardensField(GardensPlan plan, GardensRock.Gates gates) {
        this.plan = plan;
        this.gv = new GardensVents(plan);
        this.gates = gates;
        this.gateList = plan.gates;
        p1 = plan.perm(plan.seed + 900);
        p2 = plan.perm(plan.seed + 901);
        passA = new Pass(null, false);
        passB = new Pass(this::held, true);
    }

    // ================================================================================================ caches

    /** A small LRU whose values are made once: callers asking for a key being made wait for it. */
    static final class Lru<V> {
        private final int cap;
        private final LinkedHashMap<Long, CompletableFuture<V>> map = new LinkedHashMap<>(64, 0.75f, true);

        Lru(int cap) { this.cap = cap; }

        V get(long key, LongFunction<V> make) {
            CompletableFuture<V> f;
            boolean mine = false;
            synchronized (map) {
                f = map.get(key);
                if (f == null) {
                    f = new CompletableFuture<>();
                    map.put(key, f);
                    mine = true;
                    if (map.size() > cap) {
                        var it = map.entrySet().iterator();
                        while (map.size() > cap && it.hasNext()) {
                            var e = it.next();
                            if (e.getValue().isDone()) it.remove();
                            else if (map.size() > 2 * cap) it.remove();
                        }
                    }
                }
            }
            if (mine) {
                try {
                    V v = make.apply(key);
                    f.complete(v);
                    return v;
                } catch (Throwable t) {
                    synchronized (map) {
                        map.remove(key, f);
                    }
                    f.completeExceptionally(t);
                    throw t;
                }
            }
            return f.join();
        }

        void clear() {
            synchronized (map) {
                map.clear();
            }
        }
    }

    /** A tile's or column's key: packed, then mixed so hash tables spread neighbours evenly. */
    static long key(int a, int b) { return GardensPlan.mix64(((long) a << 32) | (b & 0xFFFFFFFFL)); }

    // ================================================================================================ tier 0

    /**
     * The footprint's inputs over a 64-block tile, kept compact: per column, which storeys' room noise is above zero,
     * which have a tunnel and which a bed sheet (before the gates' plumbing); and a storey's exact room noise only where
     * a vent hall's lift could reach the tile, the one place its value and not just its sign decides a hall.
     */
    static final class Tier0 {
        /** Bit k: storey k's room noise above zero; bit 8 + k: a tunnel; bit 16 + k: a bed sheet. */
        final int[] bits = new int[T0 * T0];
        /** Storey k's room noise, or null where no vent hall of storey k reaches the tile. */
        final float[][] m0 = new float[K][];
    }

    private Tier0 tier0At(int x, int z) {
        int tx = Math.floorDiv(x, T0), tz = Math.floorDiv(z, T0);
        return tier0.get(key(tx, tz), k -> makeTier0(tx, tz));
    }

    private Tier0 makeTier0(int tx, int tz) {
        long ts = System.nanoTime();
        Tier0 t = new Tier0();
        int x0 = tx * T0, z0 = tz * T0;
        for (int k = 0; k < K; k++)
            if (!plan.vhallsNear(k, x0, z0, x0 + T0 - 1, z0 + T0 - 1, GardensPlan.RV0 + 7).isEmpty()) t.m0[k] = new float[T0 * T0];
        GardensPlan.Col c = new GardensPlan.Col();
        int all = (1 << K) - 1;
        for (int i = 0; i < T0; i++)
            for (int j = 0; j < T0; j++) {
                int q = i * T0 + j;
                plan.column(x0 + i, z0 + j, c, all, true);
                int b = 0;
                for (int k = 0; k < K; k++) {
                    if (c.m0[k] > 0f) b |= 1 << k;
                    if (c.td[k] < c.tw[k]) b |= 1 << (8 + k);
                    if (c.mb[k] > 0f && (c.pil[k] < 0.42f || c.col[k] > 0.35f)) b |= 1 << (16 + k);
                    if (t.m0[k] != null) t.m0[k][q] = c.m0[k];
                }
                t.bits[q] = b;
            }
        stat(S_TIER0, ts);
        return t;
    }

    private static int t0q(int x, int z) { return Math.floorMod(x, T0) * T0 + Math.floorMod(z, T0); }

    /**
     * Whether storey k is a hall at column q of tier-0 tile t, with the lift's bump there: the room noise plus 0.35 of
     * the bump above zero (gen3's footprint). Where there is no bump only the noise's sign matters.
     */
    private boolean hallAt(Tier0 t, int q, int k, float bump, int x, int z) {
        if (bump > 0f) {
            float[] m = t.m0[k];
            float m0 = m != null ? m[q] : pointCol(x, z, k).m0[k];
            return m0 + 0.35f * bump > 0f;
        }
        return (t.bits[q] >> k & 1) != 0;
    }

    /** One column's noise for storey k alone (the others in the returned record are stale). */
    private GardensPlan.Col pointCol(int x, int z, int k) {
        GardensPlan.Col c = new GardensPlan.Col();
        plan.column(x, z, c, 1 << k, true);
        return c;
    }

    boolean tunBit(int k, int x, int z) { return (tier0At(x, z).bits[t0q(x, z)] >> (8 + k) & 1) != 0; }

    boolean sheetBit(int k, int x, int z) { return (tier0At(x, z).bits[t0q(x, z)] >> (16 + k) & 1) != 0; }

    // ================================================================================================ the two passes

    /**
     * One pass of the storeys: which vent halls lift, its distance-field tiles, its gate plumbing, its single columns.
     * The distance fields are capped at 40 and blurred as a window's own would be, over each tile with a margin of 46, so
     * they never depend on who asks.
     */
    final class Pass implements GardensStoreys.Edt {
        final Predicate<GardensPlan.VHall> use;
        final boolean second;
        private final Lru<char[][]> edt = new Lru<>(Integer.getInteger("gardens.edtCap", 160));
        private final Lru<java.util.concurrent.atomic.AtomicReferenceArray<PCol>> cols =
                new Lru<>(Integer.getInteger("gardens.colCap", 1024));
        private final Map<GardensStoreys.GateWays, java.util.Set<Long>> plugs = new ConcurrentHashMap<>();

        Pass(Predicate<GardensPlan.VHall> use, boolean second) {
            this.use = use;
            this.second = second;
        }

        List<GardensPlan.VHall> vhalls(int k, double minX, double minZ, double maxX, double maxZ, double pad) {
            List<GardensPlan.VHall> out = new ArrayList<>();
            for (GardensPlan.VHall v : plan.vhallsNear(k, minX, minZ, maxX, maxZ, pad)) if (use == null || use.test(v)) out.add(v);
            return out;
        }

        /** The lift's bump of storey k at a column (the halls' footprint grows round a lifting vent hall). */
        float bump(int k, int x, int z) {
            float b = 0f;
            for (GardensPlan.VHall v : vhalls(k, x, z, x, z, GardensPlan.RV0 + 7)) {
                float rvF = (float) (GardensPlan.RV0 + 6 * v.u());
                float bb = GardensMath.rampF(1f - plan.dist(x, z, v.x(), v.z()) / rvF, 0, 1);
                if (bb > b) b = bb;
            }
            return b;
        }

        /** Whether the gates' plumbing plugs a column of storey k. */
        boolean plugged(int k, int x, int z) {
            if (k != 0 && k != K - 1) return false;
            for (GardensGates.Gate g : plan.gates.near(x, z, GardensStoreys.GW_PLUG_OUT + 2)) {
                if (g.entry() != (k == K - 1)) continue;
                GardensStoreys.GateWays gw = plan.gateWays(g, k, use);
                if (gw == null || gw.plugged.length == 0) continue;
                java.util.Set<Long> s = plugs.computeIfAbsent(gw, w -> {
                    java.util.Set<Long> h = new java.util.HashSet<>();
                    for (long c : w.plugged) h.add(c);
                    return h;
                });
                if (s.contains(w(x, z))) return true;
            }
            return false;
        }

        /** The gate plumbing's own column key (GateWays.plugged packs columns this way, unmixed). */
        private long w(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }

        char[][] tile(int x, int z) {
            int tx = Math.floorDiv(x, TE), tz = Math.floorDiv(z, TE);
            return edt.get(key(tx, tz), k -> makeEdt(tx, tz));
        }

        private char[][] makeEdt(int tx, int tz) {
            long ts = System.nanoTime();
            int n = TE + 2 * EM, x0 = tx * TE - EM, z0 = tz * TE - EM;
            char[][] out = new char[2 * K][];
            boolean[] fh = new boolean[n * n], sh = new boolean[n * n];
            float[] bump = new float[n * n];
            // the tier-0 tiles under the window, looked up once
            int ta = Math.floorDiv(x0, T0), tb = Math.floorDiv(z0, T0);
            int na = Math.floorDiv(x0 + n - 1, T0) - ta + 1, nb = Math.floorDiv(z0 + n - 1, T0) - tb + 1;
            Tier0[] t0s = new Tier0[na * nb];
            for (int a = 0; a < na; a++)
                for (int b = 0; b < nb; b++) {
                    int qa = ta + a, qb = tb + b;
                    t0s[a * nb + b] = tier0.get(key(qa, qb), kk -> makeTier0(qa, qb));
                }
            int[] ti = new int[n], tj = new int[n], ii = new int[n], jj = new int[n];
            for (int i = 0; i < n; i++) {
                ti[i] = Math.floorDiv(x0 + i, T0) - ta;
                ii[i] = Math.floorMod(x0 + i, T0) * T0;
                tj[i] = Math.floorDiv(z0 + i, T0) - tb;
                jj[i] = Math.floorMod(z0 + i, T0);
            }
            for (int k = 0; k < K; k++) {
                java.util.Arrays.fill(bump, 0f);
                for (GardensPlan.VHall v : vhalls(k, x0, z0, x0 + n - 1, z0 + n - 1, GardensPlan.RV0 + 7)) {
                    double rv = GardensPlan.RV0 + 6 * v.u();
                    float rvF = (float) rv;
                    int ia = Math.max(0, (int) Math.floor(v.x() - rv - 1) - x0), ib = Math.min(n - 1, (int) Math.ceil(v.x() + rv + 1) - x0);
                    int ja = Math.max(0, (int) Math.floor(v.z() - rv - 1) - z0), jb = Math.min(n - 1, (int) Math.ceil(v.z() + rv + 1) - z0);
                    for (int i = ia; i <= ib; i++)
                        for (int j = ja; j <= jb; j++) {
                            float b = GardensMath.rampF(1f - plan.dist(x0 + i, z0 + j, v.x(), v.z()) / rvF, 0, 1);
                            if (b > bump[i * n + j]) bump[i * n + j] = b;
                        }
                }
                boolean anyH = false, anyS = false;
                for (int i = 0; i < n; i++)
                    for (int j = 0; j < n; j++) {
                        int q = i * n + j;
                        Tier0 t = t0s[ti[i] * nb + tj[j]];
                        int tq = ii[i] + jj[j];
                        boolean hall = hallAt(t, tq, k, bump[q], x0 + i, z0 + j);
                        anyH |= hall;
                        fh[q] = hall || (t.bits[tq] >> (8 + k) & 1) != 0;
                        sh[q] = (t.bits[tq] >> (16 + k) & 1) != 0;
                        anyS |= sh[q];
                    }
                if (anyH) out[k] = crop(GardensMath.gaussian(GardensMath.edt(fh, n, n, 40f), n, n, 1.5), n);
                if (anyS) out[K + k] = crop(GardensMath.gaussian(GardensMath.edt(sh, n, n, 40f), n, n, 1.2), n);
            }
            stat(second ? S_EDTB : S_EDT, ts);
            return out;
        }

        /** The tile's own part of a window's field, kept to 1/1024 of a block. */
        private char[] crop(float[] a, int n) {
            char[] o = new char[TE * TE];
            for (int i = 0; i < TE; i++)
                for (int j = 0; j < TE; j++) {
                    float v = a[(i + EM) * n + EM + j];
                    o[i * TE + j] = (char) Math.round(Math.max(0f, Math.min(63f, v)) * EDT_Q);
                }
            return o;
        }

        @Override
        public float hall(int k, int x, int z) {
            char[] a = tile(x, z)[k];
            return a == null ? 0f : a[Math.floorMod(x, TE) * TE + Math.floorMod(z, TE)] / EDT_Q;
        }

        @Override
        public float sheet(int k, int x, int z) {
            char[] a = tile(x, z)[K + k];
            return a == null ? 0f : a[Math.floorMod(x, TE) * TE + Math.floorMod(z, TE)] / EDT_Q;
        }

        /** This pass's storeys at one column, each worked out once and kept (in 16 by 16 chunks, filled as asked). */
        PCol col(int x, int z) {
            int cx = x >> 4, cz = z >> 4;
            java.util.concurrent.atomic.AtomicReferenceArray<PCol> ch =
                    cols.get(key(cx, cz), k -> new java.util.concurrent.atomic.AtomicReferenceArray<>(256));
            int i = (x & 15) << 4 | (z & 15);
            PCol c = ch.get(i);
            if (c == null) {
                c = makeCol(this, x, z);
                if (!ch.compareAndSet(i, null, c)) c = ch.get(i);
            }
            return c;
        }
    }

    // ================================================================================================ single columns

    /** One column's storeys (a pass's), as the sites, the door test and the bands read them. */
    static final class PCol {
        final float[] floor = new float[K], ceil = new float[K], wh = new float[K], wb = new float[K], dh = new float[K],
                bump = new float[K], rough = new float[K];
        /** Bit k: storey k's footprint; bit 8 + k: its rooms. */
        int bits;
        final byte[] dom = new byte[K];
        float hot;

        boolean foot(int k) { return (bits >> k & 1) != 0; }

        boolean rooms(int k) { return (bits >> (8 + k) & 1) != 0; }

        /** The band at a row: the dominant style of the highest storey whose floor is at or under row + 1. */
        int bandAt(int y) {
            int kk = 0;
            for (int k = 0; k < K; k++) if (floor[k] <= y + 1) kk = k;
            return dom[kk];
        }
    }

    /** A job's own lookups: the carve of single columns, and single storeys' noise (each worked out once). */
    final class Memo {
        final Pass pass;
        private final Map<Long, boolean[]> carve = new HashMap<>();
        private final Map<Long, GardensPlan.Col> noise = new HashMap<>();

        Memo(Pass pass) { this.pass = pass; }

        PCol col(int x, int z) { return pass.col(x, z); }

        /** The carve of a column (this pass's storeys, the gates' rooms): solid per row. */
        boolean[] carve(int x, int z) {
            long k = key(x, z);
            boolean[] s = carve.get(k);
            if (s == null) {
                PCol c = col(x, z);
                s = new boolean[SY];
                java.util.Arrays.fill(s, true);
                GardensRock.carveColumn(p1, p2, c.floor, c.ceil, c.rough, x, z, gates, s, 0);
                carve.put(k, s);
            }
            return s;
        }

        /** Storey k's room noise at a column: kept by the tier-0 tile near a vent hall, else worked out here. */
        float m0(int k, int x, int z) {
            float[] m = tier0At(x, z).m0[k];
            if (m != null) return m[t0q(x, z)];
            return noise(k, x, z).m0[k];
        }

        /** Storey k's bed noise at a column. */
        float mb(int k, int x, int z) { return noise(k, x, z).mb[k]; }

        private GardensPlan.Col noise(int k, int x, int z) {
            long key = key(x, z) ^ (long) k * 0x9E3779B97F4A7C15L;
            GardensPlan.Col c = noise.get(key);
            if (c == null) {
                c = pointCol(x, z, k);
                noise.put(key, c);
            }
            return c;
        }

        GardensVents.CeilBelow ceilBelow() {
            return (k, x, z) -> {
                if (k == 0) return -999f;
                PCol c = col(x, z);
                return c.foot(k - 1) ? c.ceil[k - 1] : -999f;
            };
        }
    }

    private PCol makeCol(Pass pass, int x, int z) {
        long ts = System.nanoTime();
        GardensStoreys s = new GardensStoreys(plan, new int[]{x}, new int[]{z}, pass.use, pass);
        PCol c = new PCol();
        int bits = 0;
        for (int k = 0; k < K; k++) {
            c.floor[k] = s.floor[k][0];
            c.ceil[k] = s.ceil[k][0];
            c.wh[k] = s.wh[k][0];
            c.wb[k] = s.wb[k][0];
            c.dh[k] = s.dh[k][0];
            c.bump[k] = s.bump[k][0];
            if (s.foot[k][0]) bits |= 1 << k;
            if (s.rooms[k][0]) bits |= 1 << (8 + k);
            c.dom[k] = (byte) s.dominant(k, 0);
            c.rough[k] = s.rough(k, 0);
        }
        c.bits = bits;
        c.hot = s.hot[0];
        stat(S_COL, ts);
        return c;
    }

    /** The fields the sites read, at single columns of one pass. */
    GardensSites.Fields fields(Memo m) {
        Pass p = m.pass;
        return new GardensSites.Fields() {
            public float floor(int k, int x, int z) { return m.col(x, z).floor[k]; }

            public float ceil(int k, int x, int z) { return m.col(x, z).ceil[k]; }

            public boolean foot(int k, int x, int z) { return m.col(x, z).foot(k); }

            public boolean rooms(int k, int x, int z) { return m.col(x, z).rooms(k); }

            public float wh(int k, int x, int z) { return m.col(x, z).wh[k]; }

            public float wb(int k, int x, int z) { return m.col(x, z).wb[k]; }

            public float m0(int k, int x, int z) { return m.m0(k, x, z); }

            public boolean hall(int k, int x, int z) {
                return hallAt(tier0At(x, z), t0q(x, z), k, p.bump(k, x, z), x, z) && !p.plugged(k, x, z);
            }

            public float mb(int k, int x, int z) { return m.mb(k, x, z); }

            public boolean sheet(int k, int x, int z) { return sheetBit(k, x, z) && !p.plugged(k, x, z); }

            public float domeH(int k, int x, int z) { return m.col(x, z).dh[k]; }

            public float bump(int k, int x, int z) { return m.col(x, z).bump[k]; }

            public float hot(int x, int z) { return m.col(x, z).hot; }

            public int dom(int k, int x, int z) { return m.col(x, z).dom[k]; }

            public List<GardensPlan.VHall> vhalls(int k, double minX, double minZ, double maxX, double maxZ) {
                List<GardensPlan.VHall> out = new ArrayList<>();
                for (GardensPlan.VHall v : p.vhalls(k, minX, minZ, maxX, maxZ, 0))
                    if (v.x() >= minX && v.x() < maxX && v.z() >= minZ && v.z() < maxZ) out.add(v);
                return out;
            }

            public List<GardensPlan.Dome> domes(int k, double minX, double minZ, double maxX, double maxZ) {
                List<GardensPlan.Dome> out = new ArrayList<>();
                for (GardensPlan.Dome d : plan.domesNear(k, minX, minZ, maxX, maxZ, 0)) if (!d.deep()) out.add(d);
                return out;
            }
        };
    }

    // ================================================================================================ decisions

    /** Whether vent hall v lifts its storey: some member of its stack stands on the first pass (gen3's held halls). */
    boolean held(GardensPlan.VHall v) {
        Boolean b = held.get(v.key());
        if (b != null) return b;
        long ts = System.nanoTime();
        Memo m = new Memo(passA);
        GardensSites sa = new GardensSites(plan, fields(m));
        double x0 = v.x() - 32, z0 = v.z() - 32, x1 = v.x() + 32, z1 = v.z() + 32;
        List<GardensSites.Site> cands = sa.candidates(x0 - CAND_PAD, z0 - CAND_PAD, x1 + CAND_PAD, z1 + CAND_PAD, v.k());
        List<GardensPlan.DeepSite> deeps = plan.deepSitesNear(x0 - DEEP_PAD, z0 - DEEP_PAD, x1 + DEEP_PAD, z1 + DEEP_PAD, 0);
        boolean h = !sa.resolve(cands, deeps, s -> s.family == 1 && s.k == v.k() && s.i == v.i() && s.kk == v.kk()).isEmpty();
        stat(S_HELD, ts);
        Boolean prev = held.putIfAbsent(v.key(), h);
        return prev != null ? prev : h;
    }

    /** How many vent halls were asked about, and how many lift (for the checks). */
    int[] heldCount() {
        int t = 0, n = 0;
        for (Boolean b : held.values()) {
            n++;
            if (b) t++;
        }
        return new int[]{t, n};
    }

    /** The sites standing in a 128-block tile (the second pass, the rule between clusters), with their bands. */
    List<GardensSites.Site> sites(int sx, int sz) {
        return siteTiles.get(key(sx, sz), k -> makeSites(sx, sz));
    }

    private List<GardensSites.Site> makeSites(int sx, int sz) {
        long ts = System.nanoTime();
        double x0 = sx * (double) TS, z0 = sz * (double) TS, x1 = x0 + TS, z1 = z0 + TS;
        Memo m = new Memo(passB);
        GardensSites sb = new GardensSites(plan, fields(m));
        List<GardensSites.Site> cands = sb.candidates(x0 - CAND_PAD, z0 - CAND_PAD, x1 + CAND_PAD, z1 + CAND_PAD, -1);
        List<GardensPlan.DeepSite> deeps = plan.deepSitesNear(x0 - DEEP_PAD, z0 - DEEP_PAD, x1 + DEEP_PAD, z1 + DEEP_PAD, 0);
        List<GardensSites.Site> out = sb.resolve(cands, deeps, s -> s.x >= x0 && s.x < x1 && s.z >= z0 && s.z < z1);
        for (GardensPlan.DeepSite d : deeps) {
            if (d.x() < x0 || d.x() >= x1 || d.z() < z0 || d.z() >= z1) continue;
            GardensSites.Site v = sb.deepVent(d);
            if (v.form == 3) {
                Exits ex = exits;
                Exit e = ex == null ? null : ex.find(d, gv.table(List.of(v), (x, y, z) -> 0).get(0));
                if (e == null) v = sb.deepVent(d, true);
                else {
                    v.gStand = e.g();
                    v.throatLevel = e.levelLen();
                    v.outletDep = e.outletDep();
                    v.outletLen = e.outletLen();
                }
            }
            out.add(v);
        }
        for (GardensSites.Site s : out)
            if (s.form != 3) s.band = m.col((int) Math.floor(s.x), (int) Math.floor(s.z)).bandAt(s.ys);
        stat(S_SITES, ts);
        return out;
    }

    /** The vents standing in a tile once each has had its door test: cracks that open onto nothing shut, or the vent goes. */
    List<GardensSites.Site> decided(int sx, int sz) {
        return decidedTiles.get(key(sx, sz), k -> makeDecided(sx, sz));
    }

    private List<GardensSites.Site> makeDecided(int sx, int sz) {
        List<GardensSites.Site> mine = sites(sx, sz);
        if (mine.isEmpty()) return List.of();
        long ts = System.nanoTime();
        double x0 = sx * (double) TS, z0 = sz * (double) TS;
        List<GardensSites.Site> all = new ArrayList<>();
        for (int a = -1; a <= 1; a++)
            for (int b = -1; b <= 1; b++)
                for (GardensSites.Site s : sites(sx + a, sz + b))
                    if (s.x >= x0 - DOOR_PAD && s.x < x0 + TS + DOOR_PAD && s.z >= z0 - DOOR_PAD && s.z < z0 + TS + DOOR_PAD) all.add(s);
        all.sort(GardensSites.Site::compareTo);
        List<GardensVents.Vent> vt = gv.table(all, (x, y, z) -> 0);
        Memo m = new Memo(passB);
        GardensVents.CeilBelow cl = m.ceilBelow();
        GardensVents.Solid solid = (x, y, z) -> y < 0 || y >= SY || m.carve(x, z)[y];
        List<GardensSites.Site> out = new ArrayList<>();
        for (GardensVents.Vent v : vt) {
            if (!mine.contains(v.site)) continue;
            int[] res = gv.doorTestOne(v, vt, solid, cl, GardensVents.DOOR_OPEN);
            if (res[0] != 1 && res[1] != 1 && res[2] != 1) continue;
            GardensSites.Site s = v.site.copy();
            for (int j = 0; j < s.ncr; j++) s.cracks[j] = j < 3 && res[j] == 1 ? s.cracks[j] : Double.NaN;
            out.add(s);
        }
        stat(S_DECIDED, ts);
        return out;
    }

    /** Every standing vent whose bound could reach the box, in the canonical order. */
    List<GardensSites.Site> ventsNear(double minX, double minZ, double maxX, double maxZ) {
        List<GardensSites.Site> out = new ArrayList<>();
        double r = VENT_REACH;
        int s0 = Math.floorDiv((int) Math.floor(minX - r), TS), s1 = Math.floorDiv((int) Math.floor(maxX + r), TS);
        int t0 = Math.floorDiv((int) Math.floor(minZ - r), TS), t1 = Math.floorDiv((int) Math.floor(maxZ + r), TS);
        for (int a = s0; a <= s1; a++)
            for (int b = t0; b <= t1; b++)
                for (GardensSites.Site s : decided(a, b))
                    if (s.x >= minX - r && s.x < maxX + r && s.z >= minZ - r && s.z < maxZ + r) out.add(s);
        out.sort(GardensSites.Site::compareTo);
        return out;
    }

    // ================================================================================================ blocks

    /** A block tile: each block's code and colour (code | colour << 5), and each column's storey floors and bands. */
    static final class Tile {
        final int x0, z0;
        final byte[] blocks = new byte[TB * TB * SY];
        final float[] floors = new float[K * TB * TB];
        final byte[] dom = new byte[K * TB * TB];
        /** What the making took (ms) and how many vents stood in the window. */
        long ms;
        int vents;

        Tile(int x0, int z0) {
            this.x0 = x0;
            this.z0 = z0;
        }

        int code(int x, int y, int z) { return blocks[((x - x0) * TB + (z - z0)) * SY + y] & 0xFF; }

        int bandAt(int x, int y, int z) {
            int q = (x - x0) * TB + (z - z0), kk = 0;
            for (int k = 0; k < K; k++) if (floors[k * TB * TB + q] <= y + 1) kk = k;
            return dom[kk * TB * TB + q];
        }
    }

    private final ThreadLocal<Tile> last = new ThreadLocal<>();

    /** The block tile holding column (x, z). */
    Tile tileAt(int x, int z) {
        Tile t = last.get();
        if (t != null && x >= t.x0 && x < t.x0 + TB && z >= t.z0 && z < t.z0 + TB) return t;
        int bx = Math.floorDiv(x, TB), bz = Math.floorDiv(z, TB);
        t = blockTiles.get(key(bx, bz), k -> makeTile(bx, bz));
        last.set(t);
        return t;
    }

    /** A block's code | colour << 5 at layer row y (0 = the layer's bottom row). */
    public int packed(int x, int y, int z) { return tileAt(x, z).code(x, y, z); }

    /** The band at a block of the layer: 0 Vent Domes (bottom), 1 Chimney Halls (middle), 2 Seep Beds (top). */
    public int band(int x, int y, int z) { return tileAt(x, z).bandAt(x, y, z); }

    private Tile makeTile(int bx, int bz) {
        int cx0 = bx * TB, cz0 = bz * TB;
        return makeTile(cx0, cz0, cx0 - BM, cz0 - BM, TB + 2 * BM, TB + 2 * BM);
    }

    /**
     * The block tile at (cx0, cz0) made over the window of nx by nz columns at (x0, z0), which must hold the tile with
     * a margin of at least BM round it (the game's window is just that; the checks make others to see that the tile's
     * blocks never depend on the window).
     */
    Tile makeTile(int cx0, int cz0, int x0, int z0, int nx, int nz) {
        if (cx0 - x0 < BM || cz0 - z0 < BM || x0 + nx - (cx0 + TB) < BM || z0 + nz - (cz0 + TB) < BM)
            throw new IllegalArgumentException("a block window without its margin");
        // the vent decisions first, outside the limit on heavy work (they make the lighter tiles they need)
        List<GardensSites.Site> sites = ventsNear(x0, z0, x0 + nx, z0 + nz);
        heavy.acquireUninterruptibly();
        try {
            long t0 = System.nanoTime();
            Tile tile = new Tile(cx0, cz0);
            GardensStoreys st = new GardensStoreys(plan, x0, z0, nx, nz, passB.use, passB);
            GardensRock rock = new GardensRock(plan, storeysOf(st), gates, x0, z0, nx, nz);
            GardensVents.CeilBelow cl = (k, x, z) -> {
                if (k == 0) return -999f;
                int q = st.idx(x, z);
                return st.foot[k - 1][q] ? st.ceil[k - 1][q] : -999f;
            };
            GardensVents.Bands bands = (x, y, z) -> {
                if (!st.contains(x, z)) return 0;
                int q = st.idx(x, z), kk = 0;
                for (int k = 0; k < K; k++) if (st.floor[k][q] <= y + 1) kk = k;
                return st.dominant(kk, q);
            };
            GardensVentField vf = new GardensVentField(plan, gv, bands, cl, gates, x0, z0, nx, nz, rock.solid, rock.talus);
            List<GardensSites.Site> copies = new ArrayList<>(sites.size());
            for (GardensSites.Site s : sites) copies.add(s.copy());
            vf.stampRound(copies);
            GardensDress.Fields fd = new GardensDress.Fields();
            int n2 = nx * nz;
            fd.hot = st.hot;
            fd.mound = st.mound;
            float[][] wk = new float[K][n2];
            int[][] nearV = new int[K][n2];
            for (int[] row : nearV) java.util.Arrays.fill(row, -1);
            gv.ventWeights(vf.vents, x0, z0, nx, nz, wk, nearV, new boolean[n2]);
            boolean[][] oilFoot = st.oilFoot();
            for (int k = 0; k < K; k++) {
                fd.floor[k] = st.floor[k];
                fd.wd[k] = st.wd[k];
                float[] mst = new float[n2];
                int[] dom = new int[n2];
                for (int q = 0; q < n2; q++) {
                    mst[q] = Math.max(0f, Math.min(1f, st.oc[k][q]));
                    dom[q] = st.dominant(k, q);
                }
                fd.mst[k] = mst;
                fd.dom[k] = dom;
                fd.vw[k] = wk[k];
                fd.vnear[k] = nearV[k];
                fd.oil[k] = oilFoot[k];
                fd.foot[k] = st.foot[k];
            }
            GardensDress dr = new GardensDress(plan, gv, vf.grid, vf.vents, vf.doorOk, fd, gates);
            dr.run();
            byte[] mat = vf.grid.mat, col = dr.colour;
            int di = cx0 - x0, dj = cz0 - z0;
            for (int i = 0; i < TB; i++)
                for (int j = 0; j < TB; j++) {
                    int q = (i + di) * nz + (j + dj), src = q * SY, dst = (i * TB + j) * SY;
                    for (int y = 0; y < SY; y++) tile.blocks[dst + y] = (byte) (mat[src + y] | col[src + y] << 5);
                    for (int k = 0; k < K; k++) {
                        tile.floors[k * TB * TB + i * TB + j] = st.floor[k][q];
                        tile.dom[k * TB * TB + i * TB + j] = (byte) st.dominant(k, q);
                    }
                }
            tile.vents = vf.vents.size();
            tile.ms = (System.nanoTime() - t0) / 1_000_000;
            stat(S_TILE, t0);
            return tile;
        } finally {
            heavy.release();
        }
    }

    // ================================================================================================ the game's queries

    /** The game's field for a world: its plan from the world's seed, the gates where {@link Plans} puts them. */
    static GardensField game(long worldSeed, Plans plans, Shape shape, GardensRock.Gates gates, GardensExits.Waste waste) {
        // the block tiles hold SY rows: a layer of any other height would read past them (the world config fixes it at SY)
        if (shape.height(Terrain.GARDENS) != SY)
            throw new IllegalArgumentException("The Deep Gardens' vent field is built for " + SY + " rows, not " + shape.height(Terrain.GARDENS));
        GardensPlan plan = new GardensPlan(GardensPlan.foldSeed(worldSeed), GardensGates.of(plans, shape));
        GardensField f = new GardensField(plan, gates);
        if (waste != null) f.exits = new GardensExits(f.gv, waste);
        return f;
    }

    /** The vent field's block codes (GardensVents, GardensDress), for the game's block states (world.gen.GardensBlocks). */
    public static final int C_AIR = 0, C_ROCK = 1, C_TALUS = 2, C_DEAD = 3, C_LIVE = 4, C_THROAT = 5, C_MAT = 6, C_STEM = 7,
            C_CAP = 8, C_OIL = 9, C_SEALROCK = 10, C_DRIP = 11, C_VDEAD = 12, C_VLIVE = 13, C_CROWN = 14, C_FISSURE = 15,
            C_VGLOW = 16, C_TVEIN = 17, C_TIP = 18, C_SEEP = 19, C_SKIN = 20, C_PDOWN = 21, C_PUP = 22, C_STREAK = 23,
            C_VBASE = 24, C_MATE = 25,
            // 0.20.2, the plant life (GardensDress.plants), each walked through like air: filament grass (a tuft, or
            // with PLANT_BIT the taller grass), tall filament grass (both halves), tube worms, glowworm threads (a run
            // hanging from a ceiling), seep reeds (one block or two) and small neon mushrooms (with PLANT_BIT crowded)
            C_GRASS = 26, C_TALLG = 27, C_WORM = 28, C_THREAD = 29, C_REED = 30, C_SHROOM = 31;
    /** A plant's extra bit in a packed block (over its colour, which is then {@code (p >> 5) & 3}). */
    public static final int PLANT_BIT = 128;

    /** Whether a block code is a plant's (0.20.2). */
    public static boolean plant(int c) { return c >= C_GRASS && c <= C_SHROOM; }

    /** Whether a block code is walked through: air or a plant. */
    public static boolean airy(int c) { return c == C_AIR || plant(c); }

    /**
     * Whether a block of the layer (row 0 = its bottom) is open: air, a plant (0.20.2: what was air before the plants,
     * so nothing that asks changes), or the crude oil of a seep pool.
     */
    public boolean open(int x, int y, int z) {
        int c = packed(x, y, z) & 31;
        return c == GardensVents.AIR || c == GardensVents.OIL || plant(c);
    }

    /**
     * The band at a block of the layer, from its column's storeys alone (cheap: the biomes are asked for before the
     * blocks are made): 0 Vent Domes (bottom), 1 Chimney Halls (middle), 2 Seep Beds (top).
     */
    public int bandOf(int x, int y, int z) { return passB.col(x, z).bandAt(y); }

    /** For /remix info: the band, the block's code and colour, and the standing vents near. */
    public List<String> describe(int x, int y, int z) {
        List<String> out = new ArrayList<>();
        int p = packed(x, y, z);
        PCol c = passB.col(x, z);
        StringBuilder fl = new StringBuilder();
        for (int k = 0; k < K; k++) if (c.foot(k)) fl.append(fl.length() > 0 ? ", " : "").append(k).append(String.format(java.util.Locale.ROOT, ": %.1f..%.1f", c.floor[k], c.ceil[k]));
        out.add(String.format(java.util.Locale.ROOT, "Deep Gardens row %d: band %d, code %d colour %d%s; storeys %s", y, bandOf(x, y, z), p & 31, (p >> 5) & 3,
                (p & PLANT_BIT) != 0 ? " (plant bit)" : "", fl));
        for (GardensSites.Site s : ventsNear(x - 40, z - 40, x + 40, z + 40)) {
            double d = Math.hypot(s.x + gv.ox - x, s.z + gv.oz - z);
            if (d > 40) continue;
            out.add(String.format(java.util.Locale.ROOT, "vent form %d at (%.0f, %.0f), %.0f away, top row %d, %s%s", s.form, s.x + gv.ox, s.z + gv.oz, d, s.ys,
                    s.live ? "live" : "dead", s.form == 3 ? String.format(java.util.Locale.ROOT, ", throat to row %d%s", s.gStand, Double.isNaN(s.outletDep) ? "" : " by an outlet") : ""));
        }
        out.add(statLine());
        return out;
    }

    /**
     * Whether a live deep vent (one of the ways down: lime, the only lime vents) stands within {@code reach} blocks of
     * the point (x, row, z) of the layer (row 0 its bottom), measured to the nearest point of the vent's column from its
     * base row to its top. For the stilt mantises (0.20.0, Taran's ask: green ones only close to green vents).
     */
    public boolean limeVentNear(double x, double row, double z, double reach) {
        for (double[] v : limeVents(x - reach, z - reach, x + reach, z + reach))
            if (inReach(v, x, row, z, reach)) return true;
        return false;
    }

    /**
     * The live deep vents (the lime ones, the ways down) whose columns stand in the box: each its column's x and z, the
     * lowest row of its body and its height in rows.
     */
    public List<double[]> limeVents(double minX, double minZ, double maxX, double maxZ) {
        List<double[]> out = new ArrayList<>();
        for (GardensSites.Site s : ventsNear(minX, minZ, maxX, maxZ)) {
            if (s.form != 3 || !s.live) continue;
            double x = s.x + gv.ox, z = s.z + gv.oz;
            if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
            out.add(new double[]{x, z, s.ys, s.H});
        }
        return out;
    }

    /**
     * A live vent, for the chimney lurkers that wait in their mouths (0.21.0): its key (the same whichever chunk asks),
     * the axis at its top (world x, z), its base row and its top row (layer rows, 0 the layer's bottom; the top is the
     * rim's mean height round it), its flue's radius and its wall's outside a couple of rows under the top (means round
     * it), its form (3: a deep vent) and colour (a {@code Neon} code: its beads' own, the glow mats' at its centre, lime
     * for a deep vent), and the ways its open cracks face (radians from +x toward +z).
     */
    public record LiveVent(long key, double x, double z, int baseRow, double topRow, double flue, double wall, int form, int colour,
                           double[] cracks) {}

    /** How far a live vent's top may stand from its base (across, blocks: its lean, at most 0.05 a row). */
    static final double LEAN_PAD = 8.0;

    /** The live vents whose tops stand in the box (see {@link LiveVent}), in the canonical order. */
    public List<LiveVent> liveVents(double minX, double minZ, double maxX, double maxZ) {
        List<LiveVent> out = new ArrayList<>();
        int[] pcol = null;
        GardensVents.Geom g = new GardensVents.Geom();
        for (GardensSites.Site s : ventsNear(minX, minZ, maxX, maxZ)) {
            if (!s.live) continue;
            // a vent leans at most a twentieth of a block a row: its top stands within a few blocks of its base
            double bx = s.x + gv.ox, bz = s.z + gv.oz;
            if (bx < minX - LEAN_PAD || bx >= maxX + LEAN_PAD || bz < minZ - LEAN_PAD || bz >= maxZ + LEAN_PAD) continue;
            GardensVents.Vent v = gv.table(List.of(s), (x, y, z) -> 0).get(0);
            float[] T = v.t;
            double htop = T[26];
            double ax = v.cx + T[14] * htop + gv.ox, az = v.cz + T[15] * htop + gv.oz;
            if (ax < minX || ax >= maxX || az < minZ || az >= maxZ) continue;
            if (pcol == null) pcol = plan.perm(plan.seed + 1000 + 8);
            double top = 0, rin = 0, rout = 0;
            int row = (int) Math.floor(T[6] + htop) - 2;
            for (int a = 0; a < 8; a++) {
                double th = a * Math.PI / 4;
                top += gv.top(v, th);
                gv.geom(v, (int) Math.floor(ax + Math.cos(th) * 2.0), row, (int) Math.floor(az + Math.sin(th) * 2.0), row, g);
                rin += g.rin;
                rout += g.rout;
            }
            int nc = 0;
            for (double c : s.cracks) if (!Double.isNaN(c)) nc++;
            double[] cracks = new double[nc];
            nc = 0;
            for (double c : s.cracks) if (!Double.isNaN(c)) cracks[nc++] = c;
            long key = ((long) Math.floor(s.x + gv.ox) & 0x3FFFFFFL) << 38 | ((long) Math.floor(s.z + gv.oz) & 0x3FFFFFFL) << 12 | (s.ys & 0xFFFL);
            out.add(new LiveVent(key, ax, az, s.ys, top / 8.0, rin / 8.0, rout / 8.0, s.form, GardensDress.ventColour(v, pcol, gv.ox, gv.oz), cracks));
        }
        return out;
    }

    /** Whether a point (x, row, z) lies within {@code reach} of a vent from {@link #limeVents} (its body's column). */
    public static boolean inReach(double[] vent, double x, double row, double z, double reach) {
        double dx = vent[0] - x, dz = vent[1] - z;
        double dy = Math.max(0.0, Math.max(vent[2] - row, row - (vent[2] + vent[3])));
        return dx * dx + dz * dz + dy * dy <= reach * reach;
    }

    /**
     * Places of a band near (x, z), nearest first, for /remix tp biome and /remix view: storey floors on a grid, each
     * with a spot to stand on ({@code bottom} is the layer's bottom row as a world y).
     */
    public List<Network.Place> places(int band, String what, double x, double z, int bottom, double reach, int max) {
        List<Network.Place> out = new ArrayList<>();
        int step = 8, R = (int) Math.ceil(reach / step);
        for (int r = 0; r <= R && out.size() < max; r++)
            for (int i = -r; i <= r && out.size() < max; i++)
                for (int j = -r; j <= r && out.size() < max; j++) {
                    if (Math.max(Math.abs(i), Math.abs(j)) != r) continue;
                    int px = (int) Math.floor(x) + i * step, pz = (int) Math.floor(z) + j * step;
                    PCol c = passB.col(px, pz);
                    for (int k = 0; k < K; k++) {
                        if (!c.foot(k) || c.ceil[k] - c.floor[k] < 5f) continue;
                        int feet = (int) Math.floor(c.floor[k]) + 1;
                        if (c.bandAt(feet) != band) continue;
                        Network.Place p = new Network.Place(px + 0.5, pz + 0.5, 8, false, what + " storey " + k);
                        p.stands.add(new double[]{px + 0.5, bottom + feet, pz + 0.5});
                        out.add(p);
                        break;
                    }
                }
        return out;
    }

    // ================================================================================================ under the layer

    /** The rows under the layer a throat can open: from the Wasteland slab's bottom (row -72, Y -552) to row -1. */
    static final int UNDER_LO = -72;
    /** How far from its site's centre a throat can reach under the layer: its ring with drift, and an outlet. */
    static final double UNDER_REACH = 84;

    /** A chunk's throat blocks under the layer: per column two words of row bits (row - UNDER_LO). */
    private static final class Under {
        int cx, cz;
        boolean valid, any;
        final long[] bits = new long[512];
    }

    private final ThreadLocal<Under> under = ThreadLocal.withInitial(Under::new);

    /**
     * Whether a deep vent's throat opens a block under the layer (rows -72..-1: band 3, and the Wasteland's top, where
     * it winds down to its exit and comes out in a tunnel). Worked out per chunk, once per thread.
     */
    public boolean throatUnder(int x, int y, int z) {
        if (y < UNDER_LO || y > -1) return false;
        Under u = under.get();
        int cx = x >> 4, cz = z >> 4;
        if (!u.valid || u.cx != cx || u.cz != cz) fillUnder(u, cx, cz);
        if (!u.any) return false;
        int e = y - UNDER_LO, q = ((((x & 15) << 4) | (z & 15)) << 1) + (e >> 6);
        return (u.bits[q] >>> (e & 63) & 1) != 0;
    }

    /**
     * A standing deep vent with a throat: its centre, the row of its floor, the row its throat comes out at, and the
     * middle of the throat's mouth on the vent's floor (where the helix starts, on the ring round the vent's axis).
     */
    public record Throat(double x, double z, int floorRow, int exitRow, double mouthX, double mouthZ) {}

    /**
     * The standing deep vents whose throats wind down into the Wasteland, nearest first, of the deep sites within
     * {@code reach} of (x, z), at most {@code max} (for /remix tp deepvent: a site whose decisions are not made yet costs
     * seconds, so call it off the server thread).
     */
    public List<Throat> throatsNear(double x, double z, double reach, int max) {
        List<GardensPlan.DeepSite> ds = new ArrayList<>(plan.deepSitesNear(x - reach, z - reach, x + reach, z + reach, 0));
        ds.sort(java.util.Comparator.comparingDouble(d -> Math.hypot(d.x() - x, d.z() - z)));
        List<Throat> out = new ArrayList<>();
        for (GardensPlan.DeepSite d : ds) {
            if (out.size() >= max || Math.hypot(d.x() - x, d.z() - z) > reach) break;
            if (exits == null || !GardensSites.mayThroat(d)) continue;
            GardensSites.Site s = deepVentOf(d);
            if (s == null || Double.isNaN(s.throatLevel)) continue;
            // the helix starts level at the vent's floor, on the ring of radius Rh at angle phiP (no drift there yet)
            GardensVents.Vent v = gv.table(List.of(s), (qx, qy, qz) -> 0).get(0);
            double phi = v.t[35], rh = v.t[36];
            out.add(new Throat(s.x + gv.ox, s.z + gv.oz, s.ys, s.gStand, v.cx + rh * Math.cos(phi) + gv.ox, v.cz + rh * Math.sin(phi) + gv.oz));
        }
        return out;
    }

    /** The standing deep vent of a deep site, with its exit, or null (no throat, or the vent did not stand). */
    GardensSites.Site deepVentOf(GardensPlan.DeepSite d) {
        for (GardensSites.Site s : decided(Math.floorDiv((int) Math.floor(d.x()), TS), Math.floorDiv((int) Math.floor(d.z()), TS)))
            if (s.form == 3 && s.deep != null && s.deep.i() == d.i() && s.deep.kk() == d.kk()) return s;
        return null;
    }

    private void fillUnder(Under u, int cx, int cz) {
        u.valid = false;
        u.any = false;
        java.util.Arrays.fill(u.bits, 0L);
        int x0 = cx << 4, z0 = cz << 4;
        double[] h0 = new double[1];
        // no exit search, no throat (every deep site is then a plain live vent)
        List<GardensPlan.DeepSite> near = exits == null ? List.of() : plan.deepSitesNear(x0, z0, x0 + 15, z0 + 15, UNDER_REACH);
        for (GardensPlan.DeepSite d : near) {
            // a site whose gap is too short for a deep vent never has a throat: no need for the decisions round it
            if (!GardensSites.mayThroat(d)) continue;
            GardensSites.Site s = deepVentOf(d);
            if (s == null || Double.isNaN(s.throatLevel)) continue;
            GardensVents.Vent v = gv.table(List.of(s), (x, y, z) -> 0).get(0);
            double[][] tab = GardensVents.throatTable(gv.throatPerm(s), v.t[6], s.gStand, v.t[36], GardensVents.DS, s.throatLevel, v.t[35], v.t[34]);
            double[][] cur = Double.isNaN(s.outletDep) ? null
                    : GardensExits.outletCurve(GardensExits.Site.of(gv, v), tab, s.outletDep, s.outletLen);
            for (int i = 0; i < 16; i++)
                for (int j = 0; j < 16; j++) {
                    int x = x0 + i, z = z0 + j, q = (i << 4 | j) << 1;
                    for (int y = UNDER_LO; y <= -1; y++)
                        if (gv.tubeHit(tab, v.cx, v.cz, v.t[35], v.t[34], v.t[36], x, y, z) >= 0) {
                            int e = y - UNDER_LO;
                            u.bits[q + (e >> 6)] |= 1L << (e & 63);
                            u.any = true;
                        }
                    if (cur != null && GardensExits.outletColumn(cur, gv.ptube, x - gv.ox, z - gv.oz, x, z, h0) >= 0)
                        for (int y = s.gStand; y <= Math.min(-1, s.gStand + 10); y++)
                            if (GardensExits.outletRow(gv.ptube, h0[0], s.gStand, x, y, z)) {
                                int e = y - UNDER_LO;
                                u.bits[q + (e >> 6)] |= 1L << (e & 63);
                                u.any = true;
                            }
                }
        }
        u.cx = cx;
        u.cz = cz;
        u.valid = true;
    }

    static GardensRock.Storeys storeysOf(GardensStoreys b) {
        return new GardensRock.Storeys() {
            public float floor(int k, int wx, int wz) { return b.floor[k][b.idx(wx, wz)]; }

            public float ceil(int k, int wx, int wz) { return b.ceil[k][b.idx(wx, wz)]; }

            public boolean foot(int k, int wx, int wz) { return b.foot[k][b.idx(wx, wz)]; }

            public float rough(int k, int wx, int wz) { return b.rough(k, b.idx(wx, wz)); }

            public float lowBorder(int wx, int wz) { return b.lb[b.idx(wx, wz)]; }
        };
    }
}
