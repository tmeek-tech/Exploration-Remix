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
    /** How far round a box the clusters whose members could land in it are looked for. */
    static final double CAND_PAD = 60;

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

    /** An exit: the row the throat levels out at and its level run's length (blocks along the helix). */
    record Exit(int g, double levelLen) {}

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
        List<GardensPlan.DeepSite> deeps = plan.deepSitesNear(x0 - 40, z0 - 40, x1 + 40, z1 + 40, 0);
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
        List<GardensPlan.DeepSite> deeps = plan.deepSitesNear(x0 - 40, z0 - 40, x1 + 40, z1 + 40, 0);
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
                    if (s.x >= x0 - 90 && s.x < x0 + TS + 90 && s.z >= z0 - 90 && s.z < z0 + TS + 90) all.add(s);
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
        int s0 = Math.floorDiv((int) Math.floor(minX - 48), TS), s1 = Math.floorDiv((int) Math.floor(maxX + 48), TS);
        int t0 = Math.floorDiv((int) Math.floor(minZ - 48), TS), t1 = Math.floorDiv((int) Math.floor(maxZ + 48), TS);
        for (int a = s0; a <= s1; a++)
            for (int b = t0; b <= t1; b++)
                for (GardensSites.Site s : decided(a, b))
                    if (s.x >= minX - 48 && s.x < maxX + 48 && s.z >= minZ - 48 && s.z < maxZ + 48) out.add(s);
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
        // the vent decisions first, outside the limit on heavy work (they make the lighter tiles they need)
        int cx0 = bx * TB, cz0 = bz * TB, x0 = cx0 - BM, z0 = cz0 - BM, n = TB + 2 * BM;
        List<GardensSites.Site> sites = ventsNear(x0, z0, x0 + n, z0 + n);
        heavy.acquireUninterruptibly();
        try {
            long t0 = System.nanoTime();
            Tile tile = new Tile(cx0, cz0);
            GardensStoreys st = new GardensStoreys(plan, x0, z0, n, n, passB.use, passB);
            GardensRock rock = new GardensRock(plan, storeysOf(st), gates, x0, z0, n, n);
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
            GardensVentField vf = new GardensVentField(plan, gv, bands, cl, gates, x0, z0, n, n, rock.solid, rock.talus);
            List<GardensSites.Site> copies = new ArrayList<>(sites.size());
            for (GardensSites.Site s : sites) copies.add(s.copy());
            vf.stampRound(copies);
            GardensDress.Fields fd = new GardensDress.Fields();
            int n2 = n * n;
            fd.hot = st.hot;
            fd.mound = st.mound;
            float[][] wk = new float[K][n2];
            int[][] nearV = new int[K][n2];
            for (int[] row : nearV) java.util.Arrays.fill(row, -1);
            gv.ventWeights(vf.vents, x0, z0, n, n, wk, nearV, new boolean[n2]);
            float[][] pools = st.pools();
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
                fd.pools[k] = pools[k];
                fd.foot[k] = st.foot[k];
            }
            GardensDress dr = new GardensDress(plan, gv, vf.grid, vf.vents, vf.doorOk, fd, gates);
            dr.run();
            byte[] mat = vf.grid.mat, col = dr.colour;
            for (int i = 0; i < TB; i++)
                for (int j = 0; j < TB; j++) {
                    int src = ((i + BM) * n + (j + BM)) * SY, dst = (i * TB + j) * SY;
                    for (int y = 0; y < SY; y++) tile.blocks[dst + y] = (byte) (mat[src + y] | col[src + y] << 5);
                    int q = (i + BM) * n + (j + BM);
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
