package com.explorationremix.world.terrain;

import com.explorationremix.world.terrain.Net.Chamber;
import com.explorationremix.world.terrain.Net.EK;
import com.explorationremix.world.terrain.Net.Kind;
import com.explorationremix.world.terrain.Net.Arch;
import com.explorationremix.world.terrain.Net.Shaft;
import com.explorationremix.world.terrain.Net.Way;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.LongAdder;

/**
 * The deep layers as connected cave networks: chambers joined by tunnels and climbs, planned per cell and layer by
 * {@link NetPlanner}. This is the density at Minecraft's interpolation corners ({@link #layerDensity}), the per-block
 * correction after interpolation ({@link #fine}), the fluids and the biomes. The bands, the gates and their landings
 * and the upper caves stay with {@link Terrain}, which owns one of these and hands it every block inside a layer.
 *
 * <p>Plans are made on first use and cached (single-flight: concurrent requests for one cell-layer wait for one
 * planner run). Everything else is a pure function of the seed and position, so any number of worldgen threads can
 * call in.
 */
public final class Network {
    static final double SOLID = 64;
    /** Plans kept (about 2 MB each); one cell-layer is replanned if it falls out and is asked for again. */
    static final int PLAN_CACHE = 96;
    /** Cell edges whose portals are kept. */
    static final int PORTAL_CACHE = 4096;
    /** The deep dark's floors keep this share of the roughness, so they stay walkable. */
    static final double DARK_FLOOR_ROUGH = 0.35;

    final Terrain base;
    final Shape shape;
    final long seed;
    /** The cell size (blocks): one plan per layer per cell. */
    final double cell;
    final Perlin warpA, warpB, floorN, ceilN, pendN, islandN, rough1, rough2, detail, ledgeN;
    final NetDark dark;
    private final LoadingCache<Long, Net> nets;
    private final Cache<Long, List<Portal>> portalCache;
    private final ThreadLocal<Col[]> cache;
    /** Statistics: plans made, and how often a column's lists had to grow. */
    final LongAdder plansMade = new LongAdder(), columnGrowths = new LongAdder();
    /** Tests only: the per-block pass computes everything everywhere instead of only inside a column's span. */
    boolean fullFine;

    public Network(Terrain base) {
        this.base = base;
        this.shape = base.shape;
        this.seed = base.seed;
        this.cell = base.plans.cellSize;
        long s = seed ^ 0x3C6EF372FE94F82BL;
        warpA = new Perlin(Hashing.mix(s, 1, 0, 0));
        warpB = new Perlin(Hashing.mix(s, 2, 0, 0));
        floorN = new Perlin(Hashing.mix(s, 3, 0, 0));
        ceilN = new Perlin(Hashing.mix(s, 4, 0, 0));
        pendN = new Perlin(Hashing.mix(s, 5, 0, 0));
        islandN = new Perlin(Hashing.mix(s, 6, 0, 0));
        rough1 = new Perlin(Hashing.mix(s, 7, 0, 0));
        rough2 = new Perlin(Hashing.mix(s, 8, 0, 0));
        detail = new Perlin(Hashing.mix(s, 9, 0, 0));
        ledgeN = new Perlin(Hashing.mix(s, 10, 0, 0));
        dark = new NetDark(seed, shape, cell);
        int layers = shape.layerCount;
        cache = ThreadLocal.withInitial(() -> {
            Col[] a = new Col[1024];
            for (int i = 0; i < a.length; i++) a[i] = new Col(layers);
            return a;
        });
        nets = CacheBuilder.newBuilder().maximumSize(PLAN_CACHE).build(CacheLoader.from(k -> {
            plansMade.increment();
            return NetPlanner.plan(this, (int) (k >>> 56), unpack(k >>> 28), unpack(k));
        }));
        portalCache = CacheBuilder.newBuilder().maximumSize(PORTAL_CACHE).build();
    }

    static long key(int li, int cx, int cz) { return ((long) (li & 0xFF) << 56) | ((cx & 0xFFFFFFFL) << 28) | (cz & 0xFFFFFFFL); }

    /** The low 28 bits of v as a signed number. */
    private static int unpack(long v) { return (int) (v << 36 >> 36); }

    /** The plan of layer li in cell (cx, cz), made on first use. */
    Net net(int li, int cx, int cz) {
        try {
            return nets.getUnchecked(key(li, cx, cz));
        } catch (UncheckedExecutionException e) {
            Throwable t = e.getCause();
            if (t instanceof RuntimeException r) throw r;
            if (t instanceof Error r) throw r;
            throw e;
        }
    }

    int cellOf(double v) { return (int) Math.floor(v / cell); }

    // ================================================================================================ portals
    /** A portal joining two cells: a level stub tunnel across their shared edge between two vestibule chambers. */
    static final class Portal {
        boolean xEdge;
        double line, along, floor, vestR, vestH;
        int acx, acz, bcx, bcz;
        double ax, az, bx, bz;
        Way stub;

        boolean cellA(int cx, int cz) { return cx == acx && cz == acz; }
        /** Where the stub crosses the edge (the same for both cells). */
        double edgeX(boolean a) { return xEdge ? line : along; }
        double edgeZ(boolean a) { return xEdge ? along : line; }
    }

    /**
     * The portals across one cell edge: the west edge of cell (ex, ez) when {@code xEdge}, else its north edge. A pure
     * function of the seed, the layer and the edge, so the cells on both sides plan the same ones.
     */
    List<Portal> portalsOfEdge(int li, boolean xEdge, int ex, int ez) {
        long k = Hashing.mix(seed, 7400 + li * 2L + (xEdge ? 1 : 0), ex, ez);
        try {
            return portalCache.get(k, () -> makePortals(li, xEdge, ex, ez, k));
        } catch (ExecutionException | UncheckedExecutionException e) {
            Throwable t = e.getCause();
            if (t instanceof RuntimeException r) throw r;
            if (t instanceof Error r) throw r;
            throw new IllegalStateException(e);
        }
    }

    private List<Portal> makePortals(int li, boolean xEdge, int ex, int ez, long k) {
        List<Portal> out = new ArrayList<>();
        double W = shape.waterY(li), T = shape.top(li);
        int count = li == Terrain.BLUE ? 3 : 2 + (Hashing.unit(Hashing.mix(k, 1, 0, 0)) < 0.4 ? 1 : 0);
        double base0 = (xEdge ? ez : ex) * cell;
        double lineV = (xEdge ? ex : ez) * cell;
        // portals keep a sixth of the edge from each corner, so no vestibule crowds a corner where four cells meet
        double corner = cell / 6;
        double yLo = W + (li == Terrain.BLUE ? 14 : li == Terrain.BASALT ? Terrain.SURGE_RISE + 3 : 6), yHi = T - 52;
        List<double[]> chosen = new ArrayList<>();
        for (int i = 0; i < 80 && chosen.size() < count; i++) {
            double a = base0 + corner + Hashing.unit(Hashing.mix(k, 10 + i, 1, 0)) * (cell - 2 * corner);
            double y = yLo + Hashing.unit(Hashing.mix(k, 10 + i, 2, 0)) * (yHi - yLo);
            boolean ok = true;
            for (double[] c : chosen) if (Math.abs(c[0] - a) < 110 && Math.abs(c[1] - y) < 40) ok = false;
            for (double[] c : chosen) if (Math.abs(c[0] - a) < 40) ok = false;
            if (ok && li == Terrain.BLUE && darkHit(xEdge, lineV, a, Math.floor(y), 9.5 + 6, 13 + 4)) {
                // a Blue Reaches portal that would cut a deep dark cavern stands on its floor instead, or is not made
                double sn = snapFloor(xEdge, lineV, a);
                if (Double.isNaN(sn)) ok = false; else y = sn;
            }
            if (ok) chosen.add(new double[]{a, Math.floor(y)});
        }
        for (int i = 0; i < chosen.size(); i++) {
            Portal p = new Portal();
            p.xEdge = xEdge;
            p.line = lineV;
            p.along = chosen.get(i)[0];
            p.floor = chosen.get(i)[1];
            p.vestR = 7.5 + 2 * Hashing.unit(Hashing.mix(k, 50 + i, 0, 0));
            p.vestH = 10 + 3 * Hashing.unit(Hashing.mix(k, 60 + i, 0, 0));
            if (xEdge) {
                p.acx = ex - 1; p.acz = ez; p.bcx = ex; p.bcz = ez;
                p.ax = lineV - 30; p.az = p.along; p.bx = lineV + 30; p.bz = p.along;
            } else {
                p.acx = ex; p.acz = ez - 1; p.bcx = ex; p.bcz = ez;
                p.ax = p.along; p.az = lineV - 30; p.bx = p.along; p.bz = lineV + 30;
            }
            Way wy = new Way();
            wy.kind = EK.PORTAL;
            int m = 25;
            wy.n = m;
            wy.x = new double[m]; wy.z = new double[m]; wy.f = new double[m]; wy.w = new double[m]; wy.hgt = new double[m]; wy.slab = new double[m];
            double sx = xEdge ? p.ax + p.vestR * 0.5 : p.ax, sz = xEdge ? p.az : p.az + p.vestR * 0.5;
            double tx = xEdge ? p.bx - p.vestR * 0.5 : p.bx, tz = xEdge ? p.bz : p.bz - p.vestR * 0.5;
            for (int j = 0; j < m; j++) {
                double t = j / (double) (m - 1);
                // a gentle S-bend so the stub is not a straight pipe; zero at the edge so both halves agree
                double bend = 3.0 * Math.sin(t * Math.PI * 2) * (Hashing.unit(Hashing.mix(k, 70 + i, 0, 0)) < 0.5 ? 1 : -1);
                wy.x[j] = sx + (tx - sx) * t + (xEdge ? 0 : bend);
                wy.z[j] = sz + (tz - sz) * t + (xEdge ? bend : 0);
                wy.f[j] = p.floor;
                wy.w[j] = 3.8 + 0.6 * Math.sin(t * 5 + i);
                wy.hgt[j] = 8.5 + Math.sin(t * 4 + i);
                wy.slab[j] = 4;
            }
            wy.bounds();
            p.stub = wy;
            out.add(p);
        }
        return out;
    }

    /** True when 0.2.0's deep dark tiers open near the two vestibules or along the stub, within the portal's height. */
    boolean darkHit(boolean xEdge, double lineV, double along, double y, double rad, double hgt) {
        double[] t = new double[2];
        for (double off = -30; off <= 30; off += 6) {
            double r = Math.abs(off) >= 24 ? rad : 8;
            for (int s = 0; s < 9; s++) {
                double ang = s * Math.PI / 4, rr = s == 8 ? 0 : r;
                double x = xEdge ? lineV + off + rr * Math.cos(ang) : along + rr * Math.cos(ang);
                double z = xEdge ? along + rr * Math.sin(ang) : lineV + off + rr * Math.sin(ang);
                for (int k = 0; k < 2; k++) {
                    double o = dark.open(k, x, z);
                    if (o <= 0) continue;
                    dark.tier(o, dark.floor0(k, x, z), dark.ceil0(k, x, z), t);
                    if (t[1] <= t[0]) continue;
                    if (t[1] + 6 >= y - 4 && t[0] - 6 <= y + hgt) return true;
                }
            }
        }
        return false;
    }

    /**
     * The floor for a portal standing on a deep dark cavern: both vestibule centres on the open core of one tier with
     * floors within 3 blocks, or NaN when there is no such tier.
     */
    double snapFloor(boolean xEdge, double lineV, double along) {
        double[] t = new double[2];
        for (int k = 0; k < 2; k++) {
            double[] f = new double[2];
            boolean ok = true;
            for (int side = 0; side < 2; side++) {
                double off = side == 0 ? -30 : 30;
                double x = xEdge ? lineV + off : along, z = xEdge ? along : lineV + off;
                double o = dark.open(k, x, z);
                if (o < 0.8) { ok = false; break; }
                dark.tier(o, dark.floor0(k, x, z), dark.ceil0(k, x, z), t);
                if (t[1] - t[0] < 18) { ok = false; break; }
                f[side] = t[0];
            }
            if (ok && Math.abs(f[0] - f[1]) <= 3) return Math.floor(Math.max(f[0], f[1])) + 1;
        }
        return Double.NaN;
    }

    List<Portal> portalsOfCell(int li, int cx, int cz) {
        List<Portal> out = new ArrayList<>();
        out.addAll(portalsOfEdge(li, true, cx, cz));
        out.addAll(portalsOfEdge(li, true, cx + 1, cz));
        out.addAll(portalsOfEdge(li, false, cx, cz));
        out.addAll(portalsOfEdge(li, false, cx, cz + 1));
        return out;
    }

    static Way copy(Way o) {
        Way w = new Way();
        w.kind = o.kind; w.n = o.n;
        w.x = o.x.clone(); w.z = o.z.clone(); w.f = o.f.clone(); w.w = o.w.clone(); w.hgt = o.hgt.clone(); w.slab = o.slab.clone();
        w.bounds();
        return w;
    }

    // ================================================================================================ Blue Reaches
    /** A rift canyon built with 0.2.0's Route (ledge path, bridges, refuge geodes) between two points of a cell. */
    Route canyon(int li, int cx, int cz, long h, double sx, double sz, double ex, double ez, double sFloor, double eFloor) {
        double drop = Math.max(0, sFloor - eFloor);
        double want = (10 + drop / 0.48) / 0.92;
        List<double[]> pts = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            double amp = 1.0 + 0.3 * attempt;
            List<double[]> cand = meander(h, cx, cz, sx, sz, ex, ez, amp, attempt / 2);
            if (pts == null || NetPlanner.polyLen(cand) > NetPlanner.polyLen(pts)) pts = cand;
            if (NetPlanner.polyLen(pts) >= want) break;
        }
        Plans.Gate en = base.plans.gate(li + 1, cx, cz), exG = base.plans.gate(li + 2, cx, cz);
        Route rt = new Route(base, li, cx, cz, h, pts, sFloor, eFloor, en, exG, ex, ez, 14, 9, shape.waterY(li));
        fixGeodes(rt, cx, cz);
        levelGeodes(rt, h);
        return rt;
    }

    /**
     * A geode cluster planned by 0.2.0's Route can land inside another stretch of the rift (where the rift bends back),
     * which leaves it without a floor. Such a cluster is pushed away from the rift along its tunnel, or dropped.
     */
    void fixGeodes(Route rt, int cx, int cz) {
        List<Route.Geode> g = rt.geodes;
        List<Route.Link> links = rt.links;
        double x0 = cx * cell, z0 = cz * cell;
        for (int li = 0; li < links.size(); li++) {
            Route.Link l = links.get(li);
            int head = -1;
            for (int i = 0; i < g.size(); i++) if (Math.abs(l.x2() - g.get(i).x()) < 0.01 && Math.abs(l.z2() - g.get(i).z()) < 0.01) head = i;
            if (head < 0) continue;
            int end = head + 1;
            while (end < g.size()) {
                boolean isHead = false;
                for (Route.Link o : links) if (Math.abs(o.x2() - g.get(end).x()) < 0.01 && Math.abs(o.z2() - g.get(end).z()) < 0.01) isHead = true;
                if (isHead) break;
                end++;
            }
            double ux = l.x2() - l.x1(), uz = l.z2() - l.z1(), ul = Math.max(1e-6, Math.hypot(ux, uz));
            ux /= ul; uz /= ul;
            double best = -1;
            for (int k = 0; k <= 6; k++) {
                double sh = k * 12;
                boolean hit = false, out = false;
                for (int i = head; i < end; i++) {
                    Route.Geode ge = g.get(i);
                    double x = ge.x() + ux * sh, z = ge.z() + uz * sh;
                    double m = Plans.MARGIN - 20 + ge.r();
                    if (x < x0 + m || x > x0 + cell - m || z < z0 + m || z > z0 + cell - m) out = true;
                    double[] o = riftOpen(rt, x, z, ge.r() + 4);
                    if (o != null && o[0] < ge.y() + ge.r() + 3 && o[1] > ge.y() - ge.r() * 0.5 - 3) hit = true;
                }
                if (!hit && !out) { best = sh; break; }
            }
            if (best < 0) {
                // nowhere to go: the cluster and its tunnel go
                for (int i = end - 1; i >= head; i--) g.remove(i);
                links.remove(li);
                li--;
                continue;
            }
            if (best == 0) continue;
            for (int i = head; i < end; i++) {
                Route.Geode ge = g.get(i);
                g.set(i, new Route.Geode(ge.x() + ux * best, ge.y(), ge.z() + uz * best, ge.r()));
            }
            links.set(li, new Route.Link(l.x1(), l.y1(), l.z1(), l.x2() + ux * best, l.y2(), l.z2() + uz * best, l.r()));
        }
    }

    /** Asurine Geodes: every geode of a cluster gets its floor within a block of the first one's, so a cluster is one walkable floor. */
    static void levelGeodes(Route rt, long h) {
        List<Route.Geode> g = rt.geodes;
        double headFloor = Double.NaN;
        for (int i = 0; i < g.size(); i++) {
            Route.Geode ge = g.get(i);
            boolean head = false;
            for (Route.Link l : rt.links) if (Math.abs(l.x2() - ge.x()) < 0.01 && Math.abs(l.z2() - ge.z()) < 0.01) head = true;
            if (head || Double.isNaN(headFloor)) { headFloor = ge.y() - ge.r() * 0.5; continue; }
            double f = headFloor + (Hashing.unit(Hashing.mix(h, 9900 + i, 0, 0)) < 0.5 ? 0 : 1);
            g.set(i, new Route.Geode(ge.x(), f + ge.r() * 0.5, ge.z(), ge.r()));
        }
    }

    private List<double[]> meander(long h, int cx, int cz, double sx, double sz, double ex, double ez, double amp, int extra) {
        List<double[]> way = new ArrayList<>();
        way.add(new double[]{sx, sz});
        double dx = ex - sx, dz = ez - sz, dist = Math.max(1, Math.hypot(dx, dz));
        double nx = -dz / dist, nz = dx / dist;
        int n = 3 + (int) (Hashing.unit(Hashing.mix(h, 20, 0, 0)) * 2.0) + extra;
        double sign = Hashing.unit(Hashing.mix(h, 21, 0, 0)) < 0.5 ? -1 : 1;
        double m = Plans.MARGIN + 10;
        double x0 = cx * cell, z0 = cz * cell;
        for (int i = 0; i < n; i++) {
            long hi = Hashing.mix(h, 30 + i, 0, 0);
            double t = (i + 1.0) / (n + 1) + Hashing.range(Hashing.mix(hi, 1, 0, 0), -0.05, 0.05);
            double off = sign * amp * Hashing.range(Hashing.mix(hi, 2, 0, 0), 0.08, 0.22) * Math.max(dist, 220);
            sign = -sign;
            double wx = Math.max(x0 + m, Math.min(x0 + cell - m, sx + dx * t + nx * off));
            double wz = Math.max(z0 + m, Math.min(z0 + cell - m, sz + dz * t + nz * off));
            way.add(new double[]{wx, wz});
        }
        way.add(new double[]{ex, ez});
        List<double[]> pts = NetPlanner.catmull(way, 3.0);
        for (double[] p : pts) {
            p[0] = Math.max(x0 + Plans.MARGIN, Math.min(x0 + cell - Plans.MARGIN, p[0]));
            p[1] = Math.max(z0 + Plans.MARGIN, Math.min(z0 + cell - Plans.MARGIN, p[1]));
        }
        return pts;
    }

    /** Where vanilla would start an ancient city (random spread 24/8, salt 20083232) well inside this cell. */
    List<int[]> citySites(int cx, int cz) {
        List<int[]> out = new ArrayList<>();
        int bx0 = (int) Math.round(cx * cell), bz0 = (int) Math.round(cz * cell), size = (int) Math.round(cell);
        int c0x = Math.floorDiv(bx0, 16), c1x = Math.floorDiv(bx0 + size - 1, 16);
        int c0z = Math.floorDiv(bz0, 16), c1z = Math.floorDiv(bz0 + size - 1, 16);
        for (int rx = Math.floorDiv(c0x, 24); rx <= Math.floorDiv(c1x, 24); rx++) {
            for (int rz = Math.floorDiv(c0z, 24); rz <= Math.floorDiv(c1z, 24); rz++) {
                Random r = new Random(rx * 341873128712L + rz * 132897987541L + seed + 20083232L);
                int chx = rx * 24 + r.nextInt(16), chz = rz * 24 + r.nextInt(16);
                int bx = chx * 16, bz = chz * 16;
                if (bx >= bx0 + 110 && bx < bx0 + size - 110 && bz >= bz0 + 110 && bz < bz0 + size - 110) out.add(new int[]{bx, bz});
            }
        }
        return out;
    }

    // ================================================================================================ chamber shape
    /** Footprint coordinate: 0 at the centre, 1 at the wall line, warped so no chamber is a clean ellipse. */
    double e(Chamber c, double x, double z) {
        double dx = x - c.x, dz = z - c.z;
        double u = dx * c.cos + dz * c.sin, v = -dx * c.sin + dz * c.cos;
        if (c.warp > 0) {
            double R = Math.max(c.rx, c.rz), sc = R * 0.75 + 6, off = (c.hash & 255) * 0.731;
            u += c.warp * R * warpA.noise2(x / sc + off, z / sc);
            v += c.warp * R * warpB.noise2(x / sc, z / sc + off);
        }
        double a = Math.abs(u) / c.rx, b = Math.abs(v) / c.rz;
        if (c.sq == 2) return Math.sqrt(a * a + b * b);
        return Math.pow(Math.pow(a, c.sq) + Math.pow(b, c.sq), 1 / c.sq);
    }

    double floorAt(Chamber c, double e, double x, double z) {
        switch (c.kind) {
            case LAKE -> {
                double le = c.lakeE + 0.05 * ledgeN.noise2(x / 21.0, z / 21.0);
                if (e < le) {
                    double q = e / le;
                    double bed = c.f0 + (c.lakeY - 1.5 - c.f0) * q * q;
                    double isl = islandN.fbm2(x / 20.0 + (c.hash & 63), z / 20.0, 2) - 0.25;
                    if (isl > 0 && q < 0.85) bed = Math.max(bed, Math.min(c.lakeY + 1.5, c.lakeY - 4 + isl * 40));
                    return bed;
                }
                double t = (e - le) / Math.max(0.05, 1 - le);
                // the beach's first tread is level with the water's top, so a swimmer steps straight out
                return c.lakeY + 0.05 + Terrain.stairs(t * c.rise + 0.8 * ledgeN.noise2(x / 9.0, z / 9.0), 1.0, 0.7);
            }
            case TERRACE, SPRING -> {
                double R = Math.max(c.rx, c.rz);
                double t = ((x - c.x) * c.rampX + (z - c.z) * c.rampZ) / R;
                t = t < -1 ? -1 : (t > 1 ? 1 : t);
                double v = c.rampDrop * 0.5 * (1 - t) + c.rise * e * e + 1.1 * floorN.noise2(x / 11.0, z / 11.0) + 1.1;
                return c.f0 + Terrain.terraced(Math.max(0, v), c.terr);
            }
            case WELL, CHIMNEY -> {
                double f = c.f0 + c.rise * e * e + 0.6 * floorN.noise2(x / 7.0, z / 7.0);
                if (pool(c) && e < c.lakeE + 0.04) {
                    // the pool at the well's foot: a flat bottom 3.5 under the water, steep sides
                    double t = Math.max(0, Math.min(1, (c.lakeE + 0.04 - e) / 0.12));
                    f += (c.lakeY - 3.5 - f) * t;
                }
                return f;
            }
            case LAVA -> {
                // a bowl whose rim stays under the lava table, so the lava reaches its walls
                return c.f0 + c.rise * e * e;
            }
            default -> {
                double v = c.rise * e * e + 1.0 * floorN.noise2(x / 9.0, z / 9.0) + 1.0;
                if (c.rampDrop > 0) {
                    // a strand chamber steps down along the way through it
                    double R = Math.max(c.rx, c.rz);
                    double t = ((x - c.x) * c.rampX + (z - c.z) * c.rampZ) / R;
                    t = t < -1 ? -1 : (t > 1 ? 1 : t);
                    v += c.rampDrop * 0.5 * (1 - t);
                }
                return c.f0 + (c.terr > 0 ? Terrain.terraced(Math.max(0, v), Math.max(1, c.terr)) : v);
            }
        }
    }

    double ceilAt(Chamber c, double e, double x, double z) {
        if (e >= 1) return c.f0 - 30;
        double p;
        switch (c.kind) {
            case GALLERY, GREAT, CITY -> p = 4;
            case TERRACE, SPRING, CAVERN, LAVA, GROTTO -> p = 3;
            case WELL, CHIMNEY -> {
                return c.top - 5 * e * e + 2 * ceilN.noise2(x / 9.0, z / 9.0);
            }
            default -> p = 2;
        }
        double ep = p == 2 ? e * e : (p == 4 ? e * e * e * e : e * e * e);
        double C = c.f0 + c.h * Math.sqrt(Math.max(0, 1 - ep));
        if (c.rampDrop > 0 && c.kind != Kind.LAKE) {
            double R = Math.max(c.rx, c.rz);
            double t = ((x - c.x) * c.rampX + (z - c.z) * c.rampZ) / R;
            t = t < -1 ? -1 : (t > 1 ? 1 : t);
            C += c.rampDrop * 0.5 * (1 - t);
        }
        if (c.pend > 0) {
            double n = pendN.noise2(x / 4.5, z / 4.5);
            if (n > 0.25) C -= c.pend * Math.pow((n - 0.25) / 0.75, 1.3) * 2.2 * Math.min(1, (1 - e) / 0.25);
        }
        return C + 1.5 * ceilN.noise2(x / 13.0, z / 13.0);
    }

    /** Wall term: everything closes where the footprint ends. */
    static double wallTerm(Chamber c, double e) {
        return (e - 1) * Math.min(c.rx, c.rz);
    }

    DeepBiome biomeOf(int li, Chamber c) {
        if (c.kind == Kind.POCKET && c.parent >= 0) return null; // a pocket takes its parent's biome
        switch (li) {
            case Terrain.GARDENS:
                if (c.kind == Kind.SPRING) return DeepBiome.RUST_SPRINGS;
                if (c.hunting || c.kind == Kind.TERRACE) return DeepBiome.EMBER_GARDENS;
                return DeepBiome.LANTERN_LAKE;
            case Terrain.BLUE:
                if (c.kind == Kind.CAVERN || c.kind == Kind.CITY) return DeepBiome.DEEP_DARK;
                if (c.kind == Kind.GEODE) return DeepBiome.ASURINE_GEODES;
                return DeepBiome.DEEP_BLUE_RIFT;
            default:
                if (c.kind == Kind.TUBEHUB) return DeepBiome.OBSIDIAN_TUBES;
                return DeepBiome.BASALT_GALLERIES;
        }
    }

    // ================================================================================================ columns
    /** Everything about one (x, z) column that does not depend on y, per layer, computed on first use. */
    static final class Col {
        int x, z;
        boolean valid;
        final LCol[] l;

        Col(int layers) {
            l = new LCol[layers];
            for (int i = 0; i < layers; i++) l[i] = new LCol();
        }
    }

    /**
     * One layer of a column: the chambers, ways and shafts that reach it (with their floor, ceiling and distance
     * here), the rift canyon and the deep dark tiers (Blue Reaches), the landings, and the span of heights the
     * per-block pass has to look at. The lists grow when a column needs more room; nothing is ever left out.
     */
    static final class LCol {
        boolean done;
        Net net;
        int nc;
        Chamber[] c = new Chamber[16];
        // per-column values are floats: 1,024 columns x 3 layers per worldgen thread (section 2.1)
        float[] e = new float[16], F = new float[16], C = new float[16], pd = new float[16], pr = new float[16];
        int nw;
        Way[] w = new Way[48];
        float[] wd = new float[48], wf = new float[48], ww = new float[48], wh = new float[48], wsl = new float[48];
        float[] wcore = new float[48], wslabHalf = new float[48];
        int ns;
        Shaft[] s = new Shaft[8];
        float[] sd = new float[8];
        // canyon (Blue)
        Route r;
        boolean rOn, wOn, w2On;
        double rD, rS, rV, rTop, rHalfTop, rrF, rrC;
        double wD, rF, rW, rH, rSlab, wD2, rF2, rW2, rH2, rSlab2, cap, cap2;
        Route.Bridge bridge;
        int geoN;
        Route.Geode[] geo = new Route.Geode[12];
        float[] geoD2 = new float[12];
        final double[] tmp = new double[6];
        // deep dark tiers (Blue)
        boolean darkOn;
        double aF, aC, bF, bC, aPd, aPr, bPd, bPr;
        // landings; whether the gates' passages can reach the column (outside, their term is rock)
        Plans.Gate en, ex;
        boolean enNear, exNear;
        /** An obsidian tube hub's footprint covers the column. */
        boolean hub;
        /**
         * How much of the roughness and the basalt column shift the column takes: none in a tube hub or within w + 2
         * of a round tube's line, all of it from 4 blocks further out (section 5).
         */
        float roughK;
        /** The Basalt arch over the column, with the column's place under it (q = across / span, along). */
        Arch arch;
        float archQ, archAlong;
        double entryD, exitD, endD;
        double lavaE;
        Chamber bed;
        /** The heights the per-block pass must look at; outside them the interpolated density stands. */
        double fLo, fHi;
        double colShift;

        void growC() {
            int n = c.length * 2;
            c = Arrays.copyOf(c, n); e = Arrays.copyOf(e, n); F = Arrays.copyOf(F, n); C = Arrays.copyOf(C, n);
            pd = Arrays.copyOf(pd, n); pr = Arrays.copyOf(pr, n);
        }

        void growW() {
            int n = w.length * 2;
            w = Arrays.copyOf(w, n); wd = Arrays.copyOf(wd, n); wf = Arrays.copyOf(wf, n); ww = Arrays.copyOf(ww, n);
            wh = Arrays.copyOf(wh, n); wsl = Arrays.copyOf(wsl, n); wcore = Arrays.copyOf(wcore, n); wslabHalf = Arrays.copyOf(wslabHalf, n);
        }

        void growS() {
            int n = s.length * 2;
            s = Arrays.copyOf(s, n); sd = Arrays.copyOf(sd, n);
        }

        void growG() {
            int n = geo.length * 2;
            geo = Arrays.copyOf(geo, n); geoD2 = Arrays.copyOf(geoD2, n);
        }
    }

    Col column(int x, int z) {
        Col[] a = cache.get();
        Col c = a[((x & 31) << 5) | (z & 31)];
        if (c.valid && c.x == x && c.z == z) return c;
        c.x = x;
        c.z = z;
        c.valid = true;
        for (LCol l : c.l) l.done = false;
        return c;
    }

    LCol layer(Col c, int li) {
        LCol lc = c.l[li];
        if (!lc.done) compute(c, li, lc);
        return lc;
    }

    private void compute(Col col, int li, LCol lc) {
        double x = col.x, z = col.z;
        int cx = cellOf(x), cz = cellOf(z);
        Net n = net(li, cx, cz);
        lc.net = n;
        lc.nc = 0;
        lc.nw = 0;
        lc.ns = 0;
        lc.geoN = 0;
        lc.fLo = 1e9;
        lc.lavaE = 9;
        lc.fHi = -1e9;
        int b = NetIndex.bucket(n, x, z);
        if (b >= 0) {
            for (int id : n.bCh[b]) {
                Chamber c = n.ch.get(id);
                double e = e(c, x, z);
                if (e > 1.5) continue;
                if (lc.nc == lc.c.length) { lc.growC(); columnGrowths.increment(); }
                int k = lc.nc++;
                lc.c[k] = c;
                lc.e[k] = (float) e;
                lc.F[k] = (float) floorAt(c, e, x, z);
                lc.C[k] = (float) ceilAt(c, e, x, z);
                lc.pd[k] = 1e9f;
                lc.pr[k] = 0;
                for (int p = 0; p + 2 < c.pillars.length; p += 3) {
                    double px = c.kind == Kind.TUBEHUB ? c.x + c.pillars[p] : c.pillars[p];
                    double pz = c.kind == Kind.TUBEHUB ? c.z + c.pillars[p + 1] : c.pillars[p + 1];
                    double d = Math.hypot(x - px, z - pz);
                    if (d - c.pillars[p + 2] < lc.pd[k] - lc.pr[k]) { lc.pd[k] = (float) d; lc.pr[k] = (float) c.pillars[p + 2]; }
                }
                if (c.kind == Kind.LAVA) lc.lavaE = Math.min(lc.lavaE, e);
                if (pool(c)) {
                    // the rim and the floor under the water reach 10 below the floor line
                    lc.fLo = Math.min(lc.fLo, Math.min(c.f0, c.lakeY - 3.5) - 11);
                    lc.fHi = Math.max(lc.fHi, c.lakeY + 1);
                }
                if (e < 1.3 && (c.kind == Kind.TUBEHUB || protectedRoom(c))) {
                    lc.fLo = Math.min(lc.fLo, lc.F[k] - 4);
                    lc.fHi = Math.max(lc.fHi, lc.C[k] + 4);
                }
            }
            // ways: every local minimum of the distance along each run of consecutive segments of one way, so a way
            // that passes over the column more than once (a spiral ledge, a hairpin) is seen at every pass
            int[] ids = n.bWay[b], segs = n.bWaySeg[b];
            int curWay = -1, lastSeg = -10;
            double bestD = 1e9, bestFi = 0, prevD = 1e9;
            boolean tracking = true;
            for (int k = 0; k <= ids.length; k++) {
                boolean flush = k == ids.length || ids[k] != curWay || segs[k] > lastSeg + 3;
                if (flush && curWay >= 0) {
                    if (tracking && bestD < 1e8) pushWay(lc, n.ways.get(curWay), bestD, bestFi);
                    bestD = 1e9;
                    prevD = 1e9;
                    tracking = true;
                }
                if (k == ids.length) break;
                curWay = ids[k];
                lastSeg = segs[k];
                Way wy = n.ways.get(curWay);
                int i = segs[k];
                double ax = wy.x[i], az = wy.z[i], dx = wy.x[i + 1] - ax, dz = wy.z[i + 1] - az;
                double len2 = dx * dx + dz * dz;
                double t = len2 < 1e-9 ? 0 : ((x - ax) * dx + (z - az) * dz) / len2;
                t = t < 0 ? 0 : (t > 1 ? 1 : t);
                double ex = x - (ax + dx * t), ez = z - (az + dz * t);
                double d = Math.sqrt(ex * ex + ez * ez);
                if (tracking) {
                    if (d < bestD) { bestD = d; bestFi = i + t; }
                    else if (d > bestD + 4) {
                        // left this pass: keep its nearest point and wait for the way to come back
                        pushWay(lc, wy, bestD, bestFi);
                        tracking = false;
                        bestD = 1e9;
                    }
                } else if (d < prevD - 0.25) {
                    tracking = true;
                    bestD = d;
                    bestFi = i + t;
                }
                prevD = d;
            }
            for (int id : n.bSh[b]) {
                Shaft s = n.shafts.get(id);
                double d = Math.hypot(x - s.x, z - s.z);
                if (d > s.r + 8) continue;
                if (lc.ns == lc.s.length) { lc.growS(); columnGrowths.increment(); }
                lc.s[lc.ns] = s;
                lc.sd[lc.ns++] = (float) d;
                lc.fLo = Math.min(lc.fLo, s.y0 - 2);
                lc.fHi = Math.max(lc.fHi, s.y1 + 2);
            }
        }
        // canyon
        lc.r = null;
        lc.rOn = lc.wOn = lc.w2On = false;
        lc.bridge = null;
        lc.rrF = SOLID;
        lc.rrC = -SOLID;
        if (!n.canyons.isEmpty()) canyonCol(lc, n.canyons.get(0), x, z);
        lc.darkOn = false;
        if (n.dark != null) darkCol(lc, n.dark, x, z);
        // landings
        lc.en = base.plans.gate(li + 1, cx, cz);
        lc.ex = li + 2 <= shape.layerCount ? base.plans.gate(li + 2, cx, cz) : null;
        lc.entryD = Math.hypot(x - lc.en.lx, z - lc.en.lz);
        lc.exitD = lc.ex != null ? Math.hypot(x - lc.ex.ux, z - lc.ex.uz) : 1e9;
        // passage() is rock beyond 1.34 radii + 8 of the centre line, which keeps inside the gate's box
        lc.enNear = lc.en.overlaps(x, z, x, z, 8);
        lc.exNear = lc.ex != null && lc.ex.overlaps(x, z, x, z, 8);
        lc.bed = lc.ex == null ? n.ch.get(n.exit) : null;
        lc.endD = lc.bed != null ? Math.hypot(x - lc.bed.x, z - lc.bed.z) : 1e9;
        if (lc.entryD < lc.en.landR + 6) { lc.fLo = Math.min(lc.fLo, lc.en.lFloor - 9); lc.fHi = Math.max(lc.fHi, lc.en.lCy + lc.en.landHalf + 4); }
        if (lc.ex != null && lc.exitD < lc.ex.landR + 6) { lc.fLo = Math.min(lc.fLo, lc.ex.uFloor - 9); lc.fHi = Math.max(lc.fHi, lc.ex.uCy + lc.ex.landHalf + 4); }
        if (lc.bed != null && lc.endD < lc.bed.rx + 6) { lc.fLo = Math.min(lc.fLo, lc.bed.f0 - 9); lc.fHi = Math.max(lc.fHi, lc.bed.f0 + 20); }
        // basalt columns: a per-column offset of the walls, constant over a small Voronoi cell
        lc.colShift = li == Terrain.BASALT ? 1.7 * voronoiValue(x, z, 3.3) : 0;
        lc.hub = inHub(lc);
        lc.roughK = lc.hub ? 0 : 1;
        for (int k = 0; k < lc.nw; k++) {
            if (lc.w[k].round) lc.roughK = Math.min(lc.roughK, (float) Math.max(0, Math.min(1, (lc.wd[k] - lc.ww[k] - 2) / 4)));
        }
        lc.arch = null;
        for (Arch a : n.arches) {
            double dx = x - a.x, dz = z - a.z;
            double q = Math.abs(dx * a.vx + dz * a.vz) / a.span, along = dx * a.ux + dz * a.uz;
            if (q >= 1 || Math.abs(along) > a.halfAlong * (1 + 1.2 * q * q) + 1.3) continue;
            lc.arch = a;
            lc.archQ = (float) q;
            lc.archAlong = (float) along;
            // the arch is a per-block term: the span covers it, wobble included (section 4.4)
            double deck = a.deck - 4 * q * q, thick = 3 + 9 * q * q * q;
            lc.fLo = Math.min(lc.fLo, deck - thick - 2.5);
            lc.fHi = Math.max(lc.fHi, deck + 2.5);
            break;
        }
        lc.done = true;
    }

    /**
     * Small rooms (under 14 tall, not a landing, a helix shaft, a lake, a deep dark region, a canyon node or a city)
     * are re-cut block by block after interpolation, 2 blocks inside their walls, so interpolation cannot close them.
     */
    static boolean protectedRoom(Chamber c) {
        return c.h < 14 && !c.helix() && !c.landing() && c.kind != Kind.LAKE && c.dark < 0 && c.kind != Kind.CANYON && c.kind != Kind.CITY;
    }

    private void pushWay(LCol lc, Way wy, double d, double fi) {
        double reach = (wy.inner ? 6.5 : Math.max(wy.w[0], wy.w[wy.n - 1]) * 1.3 + NetIndex.REACH);
        if (d > Math.max(reach, 9)) return;
        if (lc.nw == lc.w.length) { lc.growW(); columnGrowths.increment(); }
        int k = lc.nw++;
        int i = (int) Math.floor(fi);
        double t = fi - i;
        if (i >= wy.n - 1) { i = wy.n - 2; t = 1; }
        lc.w[k] = wy;
        lc.wd[k] = (float) d;
        lc.wf[k] = (float) (wy.f[i] + (wy.f[i + 1] - wy.f[i]) * t);
        lc.ww[k] = (float) (wy.w[i] + (wy.w[i + 1] - wy.w[i]) * t);
        lc.wh[k] = (float) (wy.hgt[i] + (wy.hgt[i + 1] - wy.hgt[i]) * t);
        lc.wsl[k] = (float) (wy.slab[i] + (wy.slab[i + 1] - wy.slab[i]) * t);
        boolean helix = wy.inner && wy.slab[0] >= 3.4;
        lc.wcore[k] = wy.inner ? 1.9f : 2.4f;
        lc.wslabHalf[k] = helix ? 3.4f : (wy.inner ? 3.6f : (float) Math.min(5.0, lc.ww[k] * 0.9 + 1.2));
        if (d < lc.wslabHalf[k] + 1 || d < lc.wcore[k] + 1.5 || (!wy.inner && d < lc.ww[k] * 1.25)) {
            // the walking core stays open up to 6.24 over the floor line, whatever the way's height; a round tube's
            // circle reaches 1.85 w + 0.5 over it
            lc.fLo = Math.min(lc.fLo, lc.wf[k] - lc.wsl[k] - 2);
            lc.fHi = Math.max(lc.fHi, lc.wf[k] + Math.max(Math.max(lc.wh[k] + 1, 7), wy.round ? 1.85 * lc.ww[k] + 1.5 : 0));
        }
    }

    /**
     * The deep dark of a column: both tiers where the cell's plan keeps them. Within 16 blocks of a seam the raw 0.2.0
     * field opens whatever the plan decided, so both cells carve the same caverns there; from 16 to 32 a cavern the plan
     * closes tapers shut instead of ending in a wall (section 6.3).
     */
    private void darkCol(LCol lc, NetDark.Plan p, double x, double z) {
        lc.aF = lc.bF = SOLID;
        lc.aC = lc.bC = -SOLID;
        lc.aPr = lc.bPr = 0;
        lc.aPd = lc.bPd = 1e9;
        double[] t = lc.tmp;
        double fade = -1;
        double band = NetDark.band(NetDark.seamDist(x + 0.5, z + 0.5, cell));
        for (int k = 0; k < 2; k++) {
            double o = dark.open(k, x, z);
            if (o <= 0) continue;
            if (fade < 0) fade = NetDark.fade(p.geodes, x, z);
            o *= fade * Math.max(band, p.keepAt(k, x, z));
            if (o <= 0) continue;
            dark.tier(o, dark.floor0(k, x, z), dark.ceil0(k, x, z), t);
            if (t[1] <= t[0]) continue;
            double F = t[0], C = t[1];
            dark.pillar(k, x, z, t);
            if (k == 0) { lc.aF = F; lc.aC = C; lc.aPd = t[0]; lc.aPr = t[1]; }
            else { lc.bF = F; lc.bC = C; lc.bPd = t[0]; lc.bPr = t[1]; }
            lc.darkOn = true;
        }
    }

    private void canyonCol(LCol lc, Route r, double x, double z) {
        lc.r = r;
        double[] tmp = lc.tmp;
        lc.rOn = r.nearest(x, z, tmp);
        if (lc.rOn) {
            lc.rD = tmp[0];
            lc.rS = tmp[1];
            lc.rV = tmp[2];
            lc.rTop = r.at(r.riftTop, lc.rS);
            lc.rHalfTop = r.at(r.riftHalf, lc.rS);
            double v = Math.abs(lc.rV) + 2.0 * ledgeN.noise2(x / 16.0, z / 16.0);
            double step = 11 + 3 * ledgeN.noise2(lc.rS / 90.0, 4.4);
            double fl = Terrain.riftFloor(Math.max(0, v), r.W - 2, lc.rTop, lc.rHalfTop, r.streamHalf, step);
            double ce = lc.rTop + 7 * Math.max(0, 1 - (v / lc.rHalfTop) * (v / lc.rHalfTop));
            double endFade = Terrain.smooth((Math.min(lc.rS, r.length - lc.rS) - 10) / 60.0);
            ce = fl + (ce - fl) * endFade;
            if (ce > fl) { lc.rrF = fl; lc.rrC = ce; }
            for (Route.Bridge bb : r.bridges) if (Math.abs(lc.rS - bb.s()) < bb.halfAlong() + 6) { lc.bridge = bb; break; }
        }
        lc.wOn = r.nearestWalk(x, z, tmp);
        lc.wD = tmp[0];
        double fi = tmp[1];
        lc.w2On = false;
        if (lc.wOn) {
            lc.rF = r.atIndex(r.floor, fi);
            lc.rW = r.atIndex(r.half, fi);
            lc.rH = r.atIndex(r.height, fi);
            lc.rSlab = r.atIndex(r.slab, fi);
            double fi2 = tmp[5];
            lc.wD2 = tmp[4];
            if (lc.wD2 < 40) {
                lc.w2On = true;
                lc.rF2 = r.atIndex(r.floor, fi2);
                lc.rW2 = r.atIndex(r.half, fi2);
                lc.rH2 = r.atIndex(r.height, fi2);
                lc.rSlab2 = r.atIndex(r.slab, fi2);
            }
            if (lc.wD < Terrain.CORE + 4) { lc.fLo = Math.min(lc.fLo, lc.rF - lc.rSlab - 3); lc.fHi = Math.max(lc.fHi, lc.rF + 9); }
            if (lc.w2On && lc.wD2 < Terrain.CORE + 4) { lc.fLo = Math.min(lc.fLo, lc.rF2 - lc.rSlab2 - 3); lc.fHi = Math.max(lc.fHi, lc.rF2 + 9); }
        }
        lc.cap = lc.cap2 = 1e9;
        if (lc.w2On && Math.abs(lc.rF - lc.rF2) >= 4.5 && Math.abs(lc.rF - lc.rF2) < 16) {
            double gap = Math.abs(lc.rF - lc.rF2);
            if (lc.rF > lc.rF2) { lc.rSlab = Math.max(1.5, Math.min(lc.rSlab, gap - 3)); lc.cap2 = lc.rF - lc.rSlab; }
            else { lc.rSlab2 = Math.max(1.5, Math.min(lc.rSlab2, gap - 3)); lc.cap = lc.rF2 - lc.rSlab2; }
        }
        if (lc.bridge != null) { lc.fLo = Math.min(lc.fLo, lc.bridge.y() - 15); lc.fHi = Math.max(lc.fHi, lc.bridge.y() + 4); }
        for (Route.Geode g : r.geodes) {
            double d2 = (x - g.x()) * (x - g.x()) + (z - g.z()) * (z - g.z());
            if (Math.sqrt(d2) >= g.r() + 12) continue;
            if (lc.geoN == lc.geo.length) { lc.growG(); columnGrowths.increment(); }
            lc.geo[lc.geoN] = g;
            lc.geoD2[lc.geoN++] = (float) d2;
        }
    }

    /**
     * The rift's open space at a column as {floor, ceiling}, as if the column were {@code shrink} blocks nearer the
     * centre line (so anything within that distance counts), or null when the rift does not reach it.
     */
    double[] riftOpen(Route r, double x, double z, double shrink) {
        double[] tmp = new double[6];
        if (!r.nearest(x, z, tmp)) return null;
        double s = tmp[1];
        double top = r.at(r.riftTop, s), halfTop = r.at(r.riftHalf, s);
        double v = Math.max(0, Math.abs(tmp[2]) - shrink);
        if (v > halfTop + 4) return null;
        double fl = Terrain.riftFloor(v, r.W - 2, top, halfTop, r.streamHalf, 11);
        double ce = top + 7 * Math.max(0, 1 - (v / halfTop) * (v / halfTop));
        double endFade = Terrain.smooth((Math.min(s, r.length - s) - 10) / 60.0);
        ce = fl + (ce - fl) * endFade;
        if (ce <= fl) return null;
        return new double[]{fl, ce};
    }

    double voronoiValue(double x, double z, double size) {
        int gx = (int) Math.floor(x / size), gz = (int) Math.floor(z / size);
        double best = 1e9;
        long bh = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long hh = Hashing.mix(seed, 8800, gx + dx, gz + dz);
            double px = (gx + dx + 0.15 + 0.7 * Hashing.unit(hh)) * size, pz = (gz + dz + 0.15 + 0.7 * Hashing.unit(Hashing.mix(hh, 1, 0, 0))) * size;
            double d = (x - px) * (x - px) + (z - pz) * (z - pz);
            if (d < best) { best = d; bh = hh; }
        }
        return Hashing.unit(Hashing.mix(bh, 2, 0, 0)) * 2 - 1;
    }

    // ================================================================================================ density
    static double smin(double a, double b, double k) {
        double hh = Math.max(k - Math.abs(a - b), 0) / k;
        return Math.min(a, b) - hh * hh * k * 0.25;
    }

    double rough(int li, int x, int y, int z) {
        double a, s;
        switch (li) {
            case Terrain.GARDENS -> { a = 2.6; s = 12; }
            case Terrain.BLUE -> { a = 4.0; s = 20; }
            default -> { a = 2.4; s = 14; }
        }
        return a * rough1.noise3(x / s, y / (s * 0.75), z / s) + a * 0.35 * rough2.noise3(x / (s * 0.4), y / (s * 0.4), z / (s * 0.4));
    }

    static double vault(double r, double y, double f, double w, double h) {
        double q = r / w;
        if (q >= 1.25) return SOLID;
        double fl = f + (q > 0.35 ? (q - 0.35) * (q - 0.35) * w * 0.9 : 0);
        double ce = f + h * Math.sqrt(Math.max(0, 1 - q * q));
        return Math.max(fl - y, y - ce);
    }

    /** A tunnel's cross-section at a column: the vault, or a round tube's circle (section 5). Negative inside. */
    static double wayDist(LCol lc, int k, int y) {
        return lc.w[k].round ? roundDist(lc.wd[k], y, lc.wf[k], lc.ww[k]) : vault(lc.wd[k], y, lc.wf[k], lc.ww[k], lc.wh[k]);
    }

    /** A perched pool of water held in a chamber: a lake over the water table, or the pool at a well's foot. */
    static boolean pool(Chamber c) {
        return (c.kind == Kind.LAKE || c.kind == Kind.WELL) && !Double.isNaN(c.lakeY);
    }

    double chamberDist(LCol lc, int k, int y) {
        Chamber c = lc.c[k];
        double d = Math.max(lc.F[k] - y, y - lc.C[k]);
        return Math.max(d, wallTerm(c, lc.e[k]));
    }

    /** Density in blocks (positive is rock) of layer li at a block, as sampled at the interpolation corners. */
    double layerDensity(int li, int x, int y, int z) {
        LCol lc = layer(column(x, z), li);
        int top = shape.top(li), bot = shape.bottom(li);
        double W = shape.waterY(li);
        double d = SOLID;
        for (int k = 0; k < lc.nc; k++) d = smin(d, chamberDist(lc, k, y), 4);
        for (int k = 0; k < lc.nw; k++) {
            Way wy = lc.w[k];
            if (wy.inner) continue;
            d = smin(d, wayDist(lc, k, y), 3);
        }
        for (int k = 0; k < lc.ns; k++) {
            Shaft s = lc.s[k];
            if (y < s.y0 - 2 || y > s.y1 + 2) continue;
            d = Math.min(d, Math.max(lc.sd[k] - s.r, Math.max(s.y0 - y, y - s.y1)));
        }
        if (lc.r != null) {
            if (lc.rOn && lc.rrC > lc.rrF) d = Math.min(d, Math.max(lc.rrF - y, y - lc.rrC));
            if (lc.wOn) d = Math.min(d, vault(lc.wD, y, lc.rF, lc.rW, lc.rH));
            if (lc.w2On) d = Math.min(d, vault(lc.wD2, y, lc.rF2, lc.rW2, lc.rH2));
            for (Route.Link l : lc.r.links) {
                if (Math.abs(x - (l.x1() + l.x2()) * 0.5) > Math.abs(l.x2() - l.x1()) * 0.5 + 10) continue;
                if (Math.abs(z - (l.z1() + l.z2()) * 0.5) > Math.abs(l.z2() - l.z1()) * 0.5 + 10) continue;
                d = Math.min(d, l.dist(x, y, z));
            }
        }
        if (lc.darkOn) {
            if (lc.aC > lc.aF) d = Math.min(d, Math.max(lc.aF - y, y - lc.aC));
            if (lc.bC > lc.bF) d = Math.min(d, Math.max(lc.bF - y, y - lc.bC));
        }
        boolean hub = lc.hub;
        if (d > -12 && d < 12 && lc.roughK > 0) {
            // the deep dark's floors stay walkable: a third of the roughness within a few blocks of a tier's floor
            double rs = lc.roughK;
            if (lc.darkOn && ((lc.aC > lc.aF && y > lc.aF - 3 && y < lc.aF + 4) || (lc.bC > lc.bF && y > lc.bF - 3 && y < lc.bF + 4))) rs *= DARK_FLOOR_ROUGH;
            if (lc.ns > 0) rs *= mouthFade(lc, y);
            if (rs > 0) d += rs * rough(li, x, y, z);
        }
        if (lc.geoN > 0) d = Math.min(d, geodeDist(lc, x, y, z));
        if (lc.darkOn) {
            if (lc.aC > lc.aF && lc.aPd < lc.aPr * 2.5) d = Math.max(d, pillarSolid(lc.aPd, lc.aPr, true, y, lc.aF, lc.aC));
            if (lc.bC > lc.bF && lc.bPd < lc.bPr * 2.5) d = Math.max(d, pillarSolid(lc.bPd, lc.bPr, true, y, lc.bF, lc.bC));
        }
        // pillars
        for (int k = 0; k < lc.nc; k++) {
            if (lc.pr[k] <= 0 || lc.pd[k] > lc.pr[k] * 2.5) continue;
            d = Math.max(d, pillarSolid(lc.pd[k], lc.pr[k], lc.c[k].kind != Kind.TUBEHUB, y, lc.F[k], lc.C[k]));
        }
        d = Math.max(d, mustStay(lc, x, y, z));
        if (top - y < Terrain.SEAL) d = Math.max(d, Terrain.SEAL - (top - y));
        if (y - bot < Terrain.SEAL) d = Math.max(d, Terrain.SEAL - (y - bot));
        Plans.Gate en = lc.en, ex = lc.ex;
        if (ex != null && y < W + 1) {
            // the exit passage stays dry: a shell of rock round it under the water table
            double pxz = ex.passageXZ(x, y, z);
            if (pxz < ex.radius * 2.4 + 4) d = Math.max(d, ex.radius * 1.45 + 3.5 - pxz);
        }
        d = Math.min(d, mustOpen(lc, x, y, z));
        if (lc.enNear && y > en.y1 - 3) d = Math.min(d, passage(en, x, y, z));
        if (lc.exNear && y < ex.y0 + 3) d = Math.min(d, passage(ex, x, y, z));
        if (hub) d = Math.max(d, hubPillar(lc, y));
        return d;
    }

    /**
     * Where the lava basin cuts into the bedrock landing's plate, the plate's edge steps down to the lava in 1-block
     * treads (0.75 down per block out), so the shore can be walked to and climbed out of.
     */
    double lavaShore(LCol lc, int y) {
        double out = lc.endD - lc.bed.rx;
        if (out < -1 || out > 5) return -SOLID;
        double top = lc.bed.f0 - 0.75 * Math.max(0, out);
        if (top < shape.waterY(lc.net.li) - 1.6) return -SOLID;
        return Math.min(Math.min(top - y, y - (lc.bed.f0 - 7)), 5 - out);
    }

    /**
     * How much of the roughness (and of the basalt column shift) a block takes round a climb stage's mouth: none within
     * r + 1.5 of the axis from 2 below the stage's top floor to 6 over it, all of it 3 blocks further out. No rough
     * ledge then stands over the hole, where a fall would be longer than the stage, and the rim stays a clean lip
     * (section 10).
     */
    static double mouthFade(LCol lc, int y) {
        double f = 1;
        for (int k = 0; k < lc.ns; k++) {
            Shaft s = lc.s[k];
            double out = Math.max((lc.sd[k] - s.r - 1.5) / 3, Math.max(s.top - 2 - y, y - (s.top + 6)) / 3);
            if (out < f) f = Math.max(0, out);
        }
        return f;
    }

    /** The column is inside an obsidian tube hub (or just round it). */
    static boolean inHub(LCol lc) {
        for (int k = 0; k < lc.nc; k++) if (lc.c[k].kind == Kind.TUBEHUB && lc.e[k] < 1.3) return true;
        return false;
    }

    /** The hub's centre pillar, applied last so no walking core cuts it. */
    static double hubPillar(LCol lc, int y) {
        double d = -SOLID;
        for (int k = 0; k < lc.nc; k++) {
            if (lc.c[k].kind != Kind.TUBEHUB || lc.pr[k] <= 0) continue;
            d = Math.max(d, pillarSolid(lc.pd[k], lc.pr[k], false, y, lc.F[k], lc.C[k]));
        }
        return d;
    }

    double geodeDist(LCol lc, int x, int y, int z) {
        double best = 1e9;
        for (int k = 0; k < lc.geoN; k++) best = Math.min(best, Terrain.geodeSdf(lc.geo[k], lc.geoD2[k], y));
        if (best > 12) return best;
        return best + 0.7 * detail.noise3(x / 6.0, y / 6.0, z / 6.0);
    }

    static double pillarSolid(double pd, double pr, boolean hour, double y, double f, double c) {
        if (pr <= 0 || c <= f) return -SOLID;
        double r = pr;
        if (hour) {
            double mid = (f + c) * 0.5, half = Math.max(4, (c - f) * 0.5);
            double q = (y - mid) / half;
            r = pr * (0.75 + 0.9 * q * q);
        }
        return Math.min(r - pd, Math.min(y - (f - 3), (c + 3) - y));
    }

    double passage(Plans.Gate g, int x, int y, int z) {
        double t = g.tAt(y);
        if (Double.isNaN(t)) return SOLID;
        double dx = x - g.px(t), dz = z - g.pz(t);
        double d2 = dx * dx + dz * dz;
        double r = g.radiusAt(t) + 2;
        if (d2 > (r + 6) * (r + 6)) return SOLID;
        return Math.sqrt(d2) - g.radiusAt(t) + 1.1 * detail.noise3(x / 7.0, y / 6.0, z / 7.0);
    }

    static double pathSlab(double pathD, double floor, double slab, int y, double half) {
        if (pathD >= half + 1 || y >= floor || y <= floor - slab - 2) return -SOLID;
        // the block under the feet (the last below the floor line) is always rock on the line itself
        if (y == Math.ceil(floor - 1e-9) - 1 && pathD < 1.5) return Math.max(0.6, Math.min(floor - y, half - pathD));
        return Math.min(Math.min(floor - y, y - (floor - slab)), half - pathD);
    }

    static double pathCore(double pathD, double floor, int y, double core) {
        if (pathD >= core + 1.5 || y < floor - 0.01 || y >= floor + 8) return SOLID;
        double dy = (y - (floor + 3.6)) / 1.1;
        // the first block at or above the floor line and the one over it are always open on the line itself, so the
        // feet sit at ceil(floor) everywhere and two neighbours never differ by more than one block
        double feet = Math.ceil(floor - 1e-9);
        if (y >= feet && y <= feet + 1 && pathD < 0.75) return -0.6;
        return Math.max(Math.sqrt(pathD * pathD + dy * dy) - core, floor - y);
    }

    /** Rock that must stay, as a signed distance (positive inside): walking floors, bridges, landing plates, lake rims. */
    double mustStay(LCol lc, int x, int y, int z) {
        double d = -SOLID;
        for (int k = 0; k < lc.nw; k++) {
            if (lc.wd[k] > lc.wslabHalf[k] + 1) continue;
            d = Math.max(d, pathSlab(lc.wd[k], lc.wf[k], lc.wsl[k], y, lc.wslabHalf[k]));
        }
        if (lc.r != null) {
            if (lc.wOn) d = Math.max(d, pathSlab(lc.wD, lc.rF, lc.rSlab, y, Terrain.CORE + 2.2));
            if (lc.w2On) d = Math.max(d, pathSlab(lc.wD2, lc.rF2, lc.rSlab2, y, Terrain.CORE + 2.2));
            if (lc.bridge != null) {
                Route.Bridge b = lc.bridge;
                double across = Math.abs(lc.rV), along = Math.abs(lc.rS - b.s());
                if (across < b.span() && y <= b.y() + 3 && y >= b.y() - 14) {
                    double q = across / b.span();
                    double thick = 3.0 + 9.0 * q * q * q;
                    double deck = b.y() + 1.5 * q * q;
                    double vert = Math.min(deck - y, y - (deck - thick));
                    double side = b.halfAlong() * (1 + 0.7 * q * q) - along;
                    d = Math.max(d, Math.min(vert, side) + 0.8 * detail.noise3(x / 5.0, y / 5.0, z / 5.0));
                }
            }
        }
        Plans.Gate en = lc.en, ex = lc.ex;
        if (lc.entryD < en.landR) d = Math.max(d, Math.min(Math.min(en.lFloor - y, y - (en.lFloor - 7)), en.landR - lc.entryD));
        if (ex != null && lc.exitD < ex.landR) d = Math.max(d, Math.min(Math.min(ex.uFloor - y, y - (ex.uFloor - 7)), ex.landR - lc.exitD));
        if (lc.bed != null && lc.endD < lc.bed.rx) d = Math.max(d, Math.min(Math.min(lc.bed.f0 - y, y - (lc.bed.f0 - 7)), lc.bed.rx - lc.endD));
        if (lc.bed != null && lc.lavaE < 1.1) d = Math.max(d, lavaShore(lc, y));
        // perched lakes and the wells' pools: a rim of rock round the water and a floor under it
        for (int k = 0; k < lc.nc; k++) {
            Chamber c = lc.c[k];
            if (!pool(c) || c.lakeY <= shape.waterY(lc.net.li) + 0.5) continue;
            if (y >= c.lakeY || y < c.f0 - 10) continue;
            double R = Math.min(c.rx, c.rz);
            double ring = (lc.e[k] - (c.lakeE + 0.06)) * R;
            if (ring > -0.5 && ring < 4) d = Math.max(d, Math.min(ring + 0.5, 4 - ring) + 0.5);
            if (lc.e[k] < c.lakeE + 0.1 && y < lc.F[k] - 1.5 && y >= lc.F[k] - 6) d = Math.max(d, Math.min(lc.F[k] - 1.5 - y, y - (lc.F[k] - 6)) + 0.5);
        }
        // climbs: a 1-block rim round each stage's mouth, open over the vines (section 10), and the floor of the step
        // off the top of the vines
        for (int k = 0; k < lc.ns; k++) {
            Shaft s = lc.s[k];
            if (x == s.stepX && z == s.stepZ && y == vineTop(s) - 1) d = Math.max(d, 1);
            double sd = lc.sd[k];
            if (sd <= s.r + 0.5 || sd >= s.r + 1.5 || y != (int) Math.ceil(s.top - 1e-9)) continue;
            double off = Math.abs(angleDiff(Math.atan2(z - s.z, x - s.x), s.vineA));
            if (off < RIM_GAP) continue;
            d = Math.max(d, Math.min(Math.min(sd - (s.r + 0.5), s.r + 1.5 - sd), (off - RIM_GAP) * sd) + 0.25);
        }
        // the helices: a parapet on the spiral ledge's open edge, 3 blocks of wall to 2 of gap (section 10)
        for (int k = 0; k < lc.nw; k++) {
            Way wy = lc.w[k];
            if (!wy.inner || wy.a < 0 || lc.wd[k] < 2.4 || lc.wd[k] > 3.4 || y != (int) Math.ceil(lc.wf[k] - 1e-9)) continue;
            Chamber c = lc.net.ch.get(wy.a);
            if (!c.helix()) continue;
            double dx = x - c.x, dz = z - c.z;
            if (dx * dx + dz * dz > c.helixR * c.helixR) continue;
            if (Math.floorMod((long) Math.floor(Math.atan2(dz, dx) * c.helixR), 5L) >= 3) continue;
            d = Math.max(d, Math.min(lc.wd[k] - 2.4, 3.4 - lc.wd[k]) + 0.25);
        }
        // the Basalt arches (section 5)
        if (lc.arch != null) {
            Arch a = lc.arch;
            double q = lc.archQ, deck = a.deck - 4 * q * q, thick = 3 + 9 * q * q * q;
            if (y > deck - thick - 1.3 && y < deck + 1.3) {
                double vert = Math.min(deck - y, y - (deck - thick));
                double side = a.halfAlong * (1 + 1.2 * q * q) - Math.abs(lc.archAlong);
                d = Math.max(d, Math.min(vert, side) + 1.2 * detail.noise3(x / 6.0, y / 6.0, z / 6.0));
            }
        }
        return d;
    }

    /**
     * The block column a climb stage's vines hang in (section 10): inside the shaft's open core (which the per-block
     * pass keeps open), as far towards the vine angle as that goes, so a climber steps off its top onto the floor the
     * stage climbs to. The vines hang from the first rock over that floor ({@link #vineTop}) down to the stage's foot
     * ({@link #vineFoot}); {@code ClimbVinesFeature} and the voxel harness both follow this rule.
     */
    public static int[] vineColumn(Shaft s) {
        double ca = Math.cos(s.vineA), sa = Math.sin(s.vineA), lim = s.r - 0.65;
        int[] out = {(int) Math.floor(s.x), (int) Math.floor(s.z)};
        double best = -1e9;
        for (int x = (int) Math.floor(s.x - lim); x <= (int) Math.ceil(s.x + lim); x++) {
            for (int z = (int) Math.floor(s.z - lim); z <= (int) Math.ceil(s.z + lim); z++) {
                double dx = x - s.x, dz = z - s.z;
                if (dx * dx + dz * dz >= lim * lim) continue;
                double score = dx * ca + dz * sa - 0.25 * Math.abs(dz * ca - dx * sa);
                if (score > best) { best = score; out = new int[]{x, z}; }
            }
        }
        return out;
    }

    /**
     * Fixes a stage's vine column ({@link #vineColumn}) and its step: the next block from it towards the vine angle
     * (along x or z, whichever is nearer that angle). The column stands at the edge of the hole's open core, so its
     * neighbours towards the way out can lie where the hole's wall and the narrow ledge passage meet, and the
     * interpolation may leave them rock at the top floor's level; the step is kept open there over a block of rock
     * ({@link #mustOpen}, {@link #mustStay}), so a climber always steps straight off the top of the vines.
     */
    static void fixVines(Shaft s) {
        int[] c = vineColumn(s);
        s.vineX = c[0];
        s.vineZ = c[1];
        double ca = Math.cos(s.vineA), sa = Math.sin(s.vineA);
        if (Math.abs(ca) >= Math.abs(sa)) {
            s.stepX = c[0] + (ca > 0 ? 1 : -1);
            s.stepZ = c[1];
        } else {
            s.stepX = c[0];
            s.stepZ = c[1] + (sa > 0 ? 1 : -1);
        }
    }

    /** The lowest block of a stage's vine column: the first over the floor at its foot. */
    public static int vineFoot(Shaft s) { return (int) Math.ceil(s.foot - 1e-9); }

    /** The feet level on the floor a stage climbs to; the vines reach past it to the rock over it. */
    public static int vineTop(Shaft s) { return (int) Math.ceil(s.top - 1e-9); }

    /** Half the opening in a stage's rim over its vines. */
    static final double RIM_GAP = Math.toRadians(40);

    static double angleDiff(double a, double b) {
        return Math.atan2(Math.sin(a - b), Math.cos(a - b));
    }

    /** Space that must stay open, as a signed distance (negative inside): walking cores, shafts and the landings. */
    double mustOpen(LCol lc, int x, int y, int z) {
        double d = SOLID;
        for (int k = 0; k < lc.nw; k++) {
            if (lc.wd[k] > lc.wcore[k] + 1.5) continue;
            d = Math.min(d, pathCore(lc.wd[k], lc.wf[k], y, lc.wcore[k]));
        }
        for (int k = 0; k < lc.ns; k++) {
            Shaft s = lc.s[k];
            if (x == s.stepX && z == s.stepZ) {
                // the step off the top of the vines: open for the feet and the head, its floor block left alone
                int top = vineTop(s);
                if (y >= top && y <= top + 1) d = Math.min(d, -1);
                if (y == top - 1) continue;
            }
            if (lc.sd[k] > s.r || y < s.y0 || y > s.y1) continue;
            d = Math.min(d, Math.max(lc.sd[k] - (s.r - 0.6), Math.max(s.y0 + 0.5 - y, y - s.y1)));
        }
        if (lc.r != null) {
            if (lc.wOn && y < lc.cap) d = Math.min(d, pathCore(lc.wD, lc.rF, y, Terrain.CORE));
            if (lc.w2On && y < lc.cap2) d = Math.min(d, pathCore(lc.wD2, lc.rF2, y, Terrain.CORE));
        }
        Plans.Gate en = lc.en, ex = lc.ex;
        if (lc.entryD < en.landR + 4 && y > en.lFloor - 2 && y < en.lCy + en.landHalf + 4)
            d = Math.min(d, landing(lc.entryD, en.landR, en.lCy, en.landHalf, en.lFloor, x, y, z));
        if (ex != null && lc.exitD < ex.landR + 4 && y > ex.uFloor - 2 && y < ex.uCy + ex.landHalf + 4)
            d = Math.min(d, landing(lc.exitD, ex.landR, ex.uCy, ex.landHalf, ex.uFloor, x, y, z));
        if (lc.bed != null && lc.endD < lc.bed.rx + 4 && y > lc.bed.f0 - 2 && y < lc.bed.f0 + 20)
            d = Math.min(d, landing(lc.endD, lc.bed.rx, lc.bed.f0 + 7, 12, lc.bed.f0, x, y, z));
        return d;
    }

    double landing(double dxz, double rad, double cy, double half, double floor, int x, int y, int z) {
        double ex = dxz / rad, ey = (y - cy) / half;
        double ell = (Math.sqrt(ex * ex + ey * ey) - 1) * half + 1.8 * detail.noise3(x / 9.0, y / 7.0, z / 9.0);
        double fl = floor + 0.9 * detail.noise2(x / 6.0, z / 6.0) - y;
        return Math.max(ell, fl);
    }

    /**
     * The per-block pass after interpolation in layer li ({@code coarse} on Minecraft's scale, as is the result). It
     * puts back what interpolation rounds off: the walking floors and cores, the tunnels' exact shapes, the small
     * rooms, the landings and passages. It only looks between the column's fLo and fHi (and at the gate passages);
     * elsewhere the interpolated density stands.
     */
    double fine(int li, int x, int y, int z, double coarse) {
        LCol lc = layer(column(x, z), li);
        double c8 = coarse * 8.0;
        boolean hub = lc.hub;
        if (lc.colShift != 0 && c8 > -3 && c8 < 3) c8 += lc.colShift * lc.roughK * (lc.ns > 0 ? mouthFade(lc, y) : 1);
        Plans.Gate en = lc.en, ex = lc.ex;
        boolean enPass = lc.enNear && y > en.y1 - 3, exPass = lc.exNear && y < ex.y0 + 3;
        boolean pass = enPass || exPass;
        if ((y < lc.fLo || y > lc.fHi) && !pass && !fullFine) {
            double d = c8 / 8.0;
            return d < -1 ? -1 : (d > 1 ? 1 : d);
        }
        double keep = mustStay(lc, x, y, z), open = mustOpen(lc, x, y, z);
        for (int k = 0; k < lc.nw; k++) {
            if (lc.w[k].inner) continue;
            if (lc.wd[k] > lc.ww[k] * 1.25) continue;
            open = Math.min(open, wayDist(lc, k, y) + 1.5);
        }
        for (int k = 0; k < lc.nc; k++) {
            if (!protectedRoom(lc.c[k]) || lc.e[k] > 1.0) continue;
            open = Math.min(open, chamberDist(lc, k, y) + 2.0);
        }
        if (enPass) open = Math.min(open, passage(en, x, y, z));
        if (exPass) open = Math.min(open, passage(ex, x, y, z));
        // Only what changes a block counts: rock that must stay where the interpolation left air, space that must be
        // open where it left rock, the hub's pillar. A term that cannot turn the block leaves the interpolated value
        // as it is, so outside the fine span (where none of them can act) skipping the pass gives the same value.
        if (keep <= 0) keep = -SOLID;
        if (open > 0) open = SOLID;
        double d = Math.min(Math.max(c8, keep), open);
        if (hub) {
            double p = hubPillar(lc, y);
            if (p > 0) d = Math.max(d, p);
        }
        d /= 8.0;
        return d < -1 ? -1 : (d > 1 ? 1 : d);
    }

    // ================================================================================================ fluids and biomes
    /** What fills an open block of layer li: {@link Terrain#AIR}, {@link Terrain#WATER} or {@link Terrain#LAVA}. */
    int fluidAt(int li, int x, int y, int z) {
        LCol lc = layer(column(x, z), li);
        if (y >= shape.waterY(li)) {
            for (int k = 0; k < lc.nc; k++) {
                Chamber c = lc.c[k];
                if (!pool(c)) continue;
                if (y < c.lakeY && lc.e[k] < c.lakeE + 0.1 && y >= lc.F[k] - 2) return Terrain.WATER;
            }
            return Terrain.AIR;
        }
        Plans.Gate ex = lc.ex;
        if (ex != null) {
            double pxz = ex.passageXZ(x, y, z);
            if (pxz < ex.radius * 1.45 + 3.0) return Terrain.AIR;
        }
        return shape.lava(li) ? Terrain.LAVA : Terrain.WATER;
    }

    /** The biome at a block of layer li: the nearest chamber's or way's, a refuge first, then the deep dark and the rift. */
    DeepBiome biomeAt(int li, int x, int y, int z) {
        LCol lc = layer(column(x, z), li);
        DeepBiome best = null;
        double bestD = 7;
        for (int k = 0; k < lc.nc; k++) {
            double d = Math.max(lc.F[k] - y, y - lc.C[k]);
            d = Math.max(d, wallTerm(lc.c[k], lc.e[k]));
            // a hub's biome (and so its obsidian) reaches round its dome past the wall line, as 0.2.0's tubes did
            if (lc.c[k].kind == Kind.TUBEHUB) d = hubDepth(lc, k, y);
            DeepBiome b = lc.c[k].biome;
            if (b == null && lc.c[k].parent >= 0) b = lc.net.ch.get(lc.c[k].parent).biome;
            if (b == null) continue;
            if (b.role == DeepBiome.Role.REFUGE && d < 6) return b;
            if (d < bestD) { bestD = d; best = b; }
        }
        for (int k = 0; k < lc.nw; k++) {
            double d = lc.w[k].round ? roundShell(lc, k, y) : wayDist(lc, k, y);
            DeepBiome b = lc.w[k].biome;
            if (b == null) continue;
            if (b.role == DeepBiome.Role.REFUGE && d < 4) return b;
            if (d < bestD - 1) { bestD = d; best = b; }
        }
        if (lc.geoN > 0 && geodeDist(lc, x, y, z) < 6) return DeepBiome.ASURINE_GEODES;
        if (lc.darkOn) {
            double dd = 1e9;
            if (lc.aC > lc.aF) dd = Math.min(dd, Math.max(lc.aF - y, y - lc.aC));
            if (lc.bC > lc.bF) dd = Math.min(dd, Math.max(lc.bF - y, y - lc.bC));
            if (dd < 2 || (dd < 6 && dd < bestD)) return DeepBiome.DEEP_DARK;
        }
        if (lc.r != null) {
            for (Route.Link l : lc.r.links) if (l.dist(x, y, z) < 4) return DeepBiome.ASURINE_GEODES;
            if (lc.rOn && Math.abs(lc.rV) < lc.rHalfTop + 6 && y > lc.r.W - 6 && y < lc.rTop + 10 && bestD > 0) return DeepBiome.DEEP_BLUE_RIFT;
        }
        return best != null ? best : Terrain.routeBiome(li);
    }

    // ================================================================================================ queries for materials
    /** Depth into the rock round the nearest refuge geode (0 at its inner surface), or 1e9 when none is near. */
    double geodeShellDepth(int li, int x, int y, int z) {
        if (li != Terrain.BLUE) return 1e9;
        LCol lc = layer(column(x, z), li);
        return lc.geoN == 0 ? 1e9 : geodeDist(lc, x, y, z);
    }

    /**
     * Depth into the rock round the nearest obsidian tube (0 at its inner surface): a tube hub or a round way (the
     * hubs' tunnels and blind tubes), or 1e9 when none is near.
     */
    double tubeShellDepth(int li, int x, int y, int z) {
        if (li != Terrain.BASALT) return 1e9;
        LCol lc = layer(column(x, z), li);
        double best = 1e9;
        for (int k = 0; k < lc.nc; k++) if (lc.c[k].kind == Kind.TUBEHUB) best = Math.min(best, hubDepth(lc, k, y));
        for (int k = 0; k < lc.nw; k++) if (lc.w[k].round) best = Math.min(best, roundShell(lc, k, y));
        return best;
    }

    /**
     * The space a round tube leaves open at a column: its circle and its walking core, which stands out of a thin tube
     * (the blind tubes' pinched ends). Negative inside.
     */
    static double roundShell(LCol lc, int k, int y) {
        return Math.min(roundDist(lc.wd[k], y, lc.wf[k], lc.ww[k]), pathCore(lc.wd[k], lc.wf[k], y, lc.wcore[k]));
    }

    /**
     * Depth into the rock round a tube hub (negative inside it): below its floor as built, and out from its dome, taken
     * as the ellipsoid its ceiling follows, so the depth holds past the wall line too, where the chamber's own ceiling
     * term gives up (it drops the ceiling 30 under the floor there).
     */
    static double hubDepth(LCol lc, int k, int y) {
        Chamber c = lc.c[k];
        double e = lc.e[k], R = Math.min(c.rx, c.rz);
        double v = Math.max(0, y - c.f0) / c.h, q = Math.sqrt(e * e + v * v);
        // first order: (q - 1) over the gradient of q, exact on the axes
        double g = Math.sqrt(e * e / (R * R) + v * v / (c.h * c.h));
        double dome = g < 1e-9 ? (q - 1) * Math.min(R, c.h) : (q - 1) * q / g;
        if (e < 1) dome = Math.min(dome, y - lc.C[k]);
        return Math.max(lc.F[k] - y, dome);
    }

    /**
     * A round way's cross-section (section 5, after 0.2.0's tubes): a circle of radius w + 0.5 round f + 0.85 w, cut
     * by the flat floor at f. Signed: negative inside.
     */
    static double roundDist(double wd, int y, double wf, double ww) {
        double dy = y - (wf + 0.85 * ww);
        return Math.max(Math.sqrt(wd * wd + dy * dy) - (ww + 0.5), wf - y);
    }

    /** True in the bowl of the bedrock landing at the bottom of the last layer. */
    boolean bedrockLanding(int li, int x, int y, int z) {
        if (li != shape.layerCount - 1) return false;
        LCol lc = layer(column(x, z), li);
        return lc.bed != null && lc.endD < lc.bed.rx + 3 && y <= lc.bed.f0 + 2 && y > lc.bed.f0 - 6;
    }
}
