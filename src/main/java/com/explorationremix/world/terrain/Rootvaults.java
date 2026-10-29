package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Rootvaults: grottos carved 10 to 40 blocks under forests, jungles, taigas, swamps and cherry groves, where the
 * trees above send their roots down through the ceiling. The pack's own caves hardly reach that close to the ground
 * (Tectonic fades them out in the top couple of dozen blocks), so the grottos are carved here, like the set pieces.
 *
 * <p>One candidate site per {@value #CELL}-block cell. What grows over it ({@link Overhead}) decides whether a grotto is
 * carved: trees over (nearly) all of its footprint, no ocean, river or beach, and ground level enough that the grotto
 * can sit between 10 and 40 blocks under all of it. Each grotto has one of three looks: a root hall (a domed hall with
 * a stream along it and terraces up its walls), a teal grotto (rounder and lower, around a pool) or a ravine (a slot
 * with a stream and stepped ledges, open to the sky over its middle, only where the sky is reachable). Tails lead from
 * each grotto down into the pack's caves, and tunnels join neighbouring grottos, so they form a network with branches,
 * loops and dead ends. Halls and teal grottos are planned with skylights, which a feature cuts through the real world.
 *
 * <p>Everything is a pure function of position: safe to call from any thread, and the same on every call.
 */
public final class Rootvaults {
    public static final int HALL = 0, TEAL = 1, RAVINE = 2;
    public static final int AIR = 0, WATER = 1;
    /** Site grid: one candidate grotto per cell this wide. */
    public static final int CELL = 64;
    /** Signed distance returned where nothing is near. */
    public static final double SOLID = 64;
    /** Share of cells that try to hold a grotto (before what grows above has its say). */
    static final double CHANCE = 0.55;
    /** Grottos keep their water at least this far above the pack's old floor: clear of the set pieces and the gate ramp. */
    static final int FLOOR_CLEARANCE = 64;

    public final long seed;
    public final int packFloor;
    public final Overhead overhead;
    private final int minWater;
    private final Perlin wobN, floorN, ceilN, roughN, meanderN;
    private final ConcurrentHashMap<Long, Object> grottos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Tunnel[]> tunnels = new ConcurrentHashMap<>();
    private final ThreadLocal<Col[]> cols = ThreadLocal.withInitial(() -> {
        Col[] a = new Col[1024];
        for (int i = 0; i < a.length; i++) a[i] = new Col();
        return a;
    });
    private static final Object NONE = new Object();

    public Rootvaults(long seed, int packFloor, Overhead overhead) {
        this.seed = seed;
        this.packFloor = packFloor;
        this.overhead = overhead;
        this.minWater = packFloor + FLOOR_CLEARANCE;
        long s = seed ^ 0x5851F42D4C957F2DL;
        wobN = new Perlin(Hashing.mix(s, 1, 0, 0));
        floorN = new Perlin(Hashing.mix(s, 2, 0, 0));
        ceilN = new Perlin(Hashing.mix(s, 3, 0, 0));
        roughN = new Perlin(Hashing.mix(s, 4, 0, 0));
        meanderN = new Perlin(Hashing.mix(s, 5, 0, 0));
    }

    // ================================================================================================ the plan

    /** One grotto. Its footprint is a wobbly ellipse (a ravine: a slot) turned by an angle, inside its own cell. */
    public static final class Grotto {
        public final int cellX, cellZ, look;
        /** The trees over its centre. */
        public final RootKind kind;
        public final double cx, cz;
        final double cos, sin;
        /** Half length and half width (a ravine: half its length, and half its width where it meets the sky). */
        public final double a, b;
        /** Open blocks below this are water: its stream, pool and puddles. */
        public final double water;
        /** Hall and teal grotto: the top of the dome above the water. Ravine: the roof over its two closed ends. */
        public final double height;
        /**
         * No ceiling reaches above this (10 under the lowest ground sampled over a hall or teal grotto; each column's
         * ceiling also stays 10 under its own ground).
         */
        public final double roofCap;
        /** The lowest and highest ground over its footprint. */
        public final int groundMin, groundMax;
        final double rise, channel, poolU, poolV, poolR, poolD, stream, step, ox, oz;
        /** Planned skylights: {x, z}. */
        public final double[][] skylights;
        final double minX, maxX, minZ, maxZ;

        Grotto(int cellX, int cellZ, int look, RootKind kind, double cx, double cz, double angle, double a, double b,
               double water, double height, double roofCap, int groundMin, int groundMax, double rise, double channel,
               double poolU, double poolV, double poolR, double poolD, double stream, double step, double ox, double oz,
               double[][] skylights) {
            this.cellX = cellX;
            this.cellZ = cellZ;
            this.look = look;
            this.kind = kind;
            this.cx = cx;
            this.cz = cz;
            this.cos = Math.cos(angle);
            this.sin = Math.sin(angle);
            this.a = a;
            this.b = b;
            this.water = water;
            this.height = height;
            this.roofCap = roofCap;
            this.groundMin = groundMin;
            this.groundMax = groundMax;
            this.rise = rise;
            this.channel = channel;
            this.poolU = poolU;
            this.poolV = poolV;
            this.poolR = poolR;
            this.poolD = poolD;
            this.stream = stream;
            this.step = step;
            this.ox = ox;
            this.oz = oz;
            this.skylights = skylights;
            double reach = reach(look, a, b);
            this.minX = cx - reach;
            this.maxX = cx + reach;
            this.minZ = cz - reach;
            this.maxZ = cz + reach;
        }

        public boolean covers(double x, double z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }

        /** Along its long axis and across it, from its centre. */
        public double along(double x, double z) { return (x - cx) * cos + (z - cz) * sin; }

        public double across(double x, double z) { return -(x - cx) * sin + (z - cz) * cos; }

        double worldX(double u, double v) { return cx + u * cos - v * sin; }

        double worldZ(double u, double v) { return cz + u * sin + v * cos; }

        /** The highest a ceiling can be over it (a ravine's sky included). */
        public double top() { return look == RAVINE ? groundMax + 8 : roofCap; }

        /** Its lowest open block: the bottom of its stream or pool. */
        public double bottom() { return water - (look == TEAL ? poolD : 2.5); }

        public String lookName() { return Rootvaults.lookName(look); }
    }

    public static String lookName(int look) {
        return switch (look) {
            case HALL -> "root hall";
            case TEAL -> "teal grotto";
            default -> "ravine";
        };
    }

    /** How far its open space can reach from its centre, wobble included. */
    static double reach(int look, double a, double b) {
        return look == RAVINE ? a * 1.05 + 6 : Math.max(a, b) * 1.3 + 2;
    }

    /** A tunnel: two straight runs through three points, round with a flat floor. Negative inside. */
    static final class Tunnel {
        final double[] px, py, pz;
        final double r;
        /** A link between two grottos (a tail leads down into the pack's caves instead). */
        final boolean link;
        final Grotto owner;
        final double minX, maxX, minZ, maxZ, minY, maxY;

        Tunnel(double[] px, double[] py, double[] pz, double r, boolean link, Grotto owner) {
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.r = r;
            this.link = link;
            this.owner = owner;
            double x0 = 1e18, x1 = -1e18, z0 = 1e18, z1 = -1e18, y0 = 1e18, y1 = -1e18;
            for (int i = 0; i < px.length; i++) {
                x0 = Math.min(x0, px[i]); x1 = Math.max(x1, px[i]);
                z0 = Math.min(z0, pz[i]); z1 = Math.max(z1, pz[i]);
                y0 = Math.min(y0, py[i]); y1 = Math.max(y1, py[i]);
            }
            double m = r + 6;
            minX = x0 - m; maxX = x1 + m; minZ = z0 - m; maxZ = z1 + m; minY = y0 - m; maxY = y1 + m;
        }

        boolean covers(double x, double z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }

        double dist(double x, double y, double z) {
            double best = 1e18, by = py[0];
            for (int i = 0; i + 1 < px.length; i++) {
                double ax = px[i], ay = py[i], az = pz[i];
                double dx = px[i + 1] - ax, dy = py[i + 1] - ay, dz = pz[i + 1] - az;
                double l2 = dx * dx + dy * dy + dz * dz;
                double t = l2 < 1e-9 ? 0 : ((x - ax) * dx + (y - ay) * dy + (z - az) * dz) / l2;
                t = t < 0 ? 0 : (t > 1 ? 1 : t);
                double qx = x - (ax + dx * t), qy = y - (ay + dy * t), qz = z - (az + dz * t);
                double d2 = qx * qx + qy * qy + qz * qz;
                if (d2 < best) {
                    best = d2;
                    by = ay + dy * t;
                }
            }
            // Round, with a flat floor a little over halfway down so it can be walked.
            return Math.max(Math.sqrt(best) - r, (by - 0.55 * r) - y);
        }
    }

    private static long key(int a, int b) { return ((long) a << 32) ^ (b & 0xffffffffL); }

    /** The grotto of a cell, or null. */
    public Grotto grotto(int cellX, int cellZ) {
        long k = key(cellX, cellZ);
        Object o = grottos.get(k);
        if (o == null) {
            Grotto g = plan(cellX, cellZ);
            o = g != null ? g : NONE;
            Object prev = grottos.putIfAbsent(k, o);
            if (prev != null) o = prev;
        }
        return o == NONE ? null : (Grotto) o;
    }

    /** The grottos whose cells lie within {@code cells} cells of (x, z), nearest first. */
    public List<Grotto> near(int x, int z, int cells) {
        int ci = Math.floorDiv(x, CELL), cj = Math.floorDiv(z, CELL);
        List<Grotto> out = new ArrayList<>();
        for (int i = -cells; i <= cells; i++) for (int j = -cells; j <= cells; j++) {
            Grotto g = grotto(ci + i, cj + j);
            if (g != null) out.add(g);
        }
        out.sort(Comparator.comparingDouble(g -> (g.cx - x) * (g.cx - x) + (g.cz - z) * (g.cz - z)));
        return out;
    }

    private Grotto plan(int ci, int cj) {
        long h = Hashing.mix(seed, 7001, ci, cj);
        if (Hashing.unit(h) >= CHANCE) return null;
        double lr = Hashing.unit(Hashing.mix(h, 1, 0, 0));
        int look = lr < 0.45 ? HALL : (lr < 0.80 ? TEAL : RAVINE);
        double angle = Hashing.range(Hashing.mix(h, 2, 0, 0), 0, Math.PI);
        double[] ab = size(look, h);
        double a = ab[0], b = ab[1];
        double margin = Math.ceil(reach(look, a, b)) + 3;
        double cx = ci * (double) CELL + Hashing.range(Hashing.mix(h, 5, 0, 0), margin, CELL - margin);
        double cz = cj * (double) CELL + Hashing.range(Hashing.mix(h, 6, 0, 0), margin, CELL - margin);

        // What grows over the centre, then over a ring just outside the footprint.
        Overhead.Surface s0 = overhead.at((int) Math.floor(cx), (int) Math.floor(cz));
        if (!s0.trees().trees() || s0.wet()) return null;
        double cos = Math.cos(angle), sin = Math.sin(angle);
        int trees = 1, gMin = s0.y(), gMax = s0.y();
        boolean ravineOk = s0.ravineOk();
        for (int k = 0; k < 8; k++) {
            double t = k * Math.PI / 4;
            double u = (a + 3) * Math.cos(t), v = (b + 3) * Math.sin(t);
            Overhead.Surface s = overhead.at((int) Math.floor(cx + u * cos - v * sin), (int) Math.floor(cz + u * sin + v * cos));
            if (s.wet()) return null;
            if (s.trees().trees()) trees++;
            gMin = Math.min(gMin, s.y());
            gMax = Math.max(gMax, s.y());
            ravineOk &= s.ravineOk();
        }
        if (trees < 7 || gMax - gMin > 18) return null;
        if (look == RAVINE && (!ravineOk || gMax - gMin > 10)) {
            // No sky to open to here: a root hall instead, inside the footprint already checked.
            look = HALL;
            ab = size(HALL, Hashing.mix(h, 77, 0, 0));
            a = ab[0];
            b = ab[1];
        }

        // How deep: halls and teal grottos keep 10 blocks of rock over them; everything stays within 40 of the ground.
        double water, height, roofCap;
        if (look == RAVINE) {
            water = Math.floor(gMin - Hashing.range(Hashing.mix(h, 9, 0, 0), 22, 28));
            height = Math.min(Hashing.range(Hashing.mix(h, 10, 0, 0), 11, 15), gMin - 7 - water);
            roofCap = gMax + 8;
        } else {
            height = look == HALL ? Hashing.range(Hashing.mix(h, 9, 0, 0), 10, 16) : Hashing.range(Hashing.mix(h, 9, 0, 0), 8, 13);
            roofCap = gMin - 10;
            water = Math.floor(roofCap - 2 - height - Hashing.range(Hashing.mix(h, 10, 0, 0), 0, 4));
            double lowest = Math.ceil(gMax - 37.5);
            if (water < lowest) {
                water = lowest;
                height = roofCap - 2 - water;
                if (height < 8) return null;
            }
        }
        if (water < minWater) return null;

        double rise = 0, channel = 0, poolU = 0, poolV = 0, poolR = 1, poolD = 0, stream = 0, step = 4;
        int sky = 0;
        double sr = Hashing.unit(Hashing.mix(h, 30, 0, 0));
        switch (look) {
            case HALL -> {
                rise = Hashing.range(Hashing.mix(h, 11, 0, 0), 3, 6);
                channel = Hashing.range(Hashing.mix(h, 12, 0, 0), 1.4, 2.4);
                sky = sr < 0.25 ? 0 : (sr < 0.75 ? 1 : 2);
            }
            case TEAL -> {
                rise = Hashing.range(Hashing.mix(h, 11, 0, 0), 2, 4);
                poolU = (Hashing.unit(Hashing.mix(h, 13, 0, 0)) < 0.5 ? -1 : 1) * Hashing.range(Hashing.mix(h, 14, 0, 0), 0.15, 0.4) * a;
                poolV = Hashing.range(Hashing.mix(h, 15, 0, 0), -0.2, 0.2) * b;
                poolR = Hashing.range(Hashing.mix(h, 16, 0, 0), 0.5, 0.65) * b;
                poolD = Hashing.range(Hashing.mix(h, 17, 0, 0), 2, 3);
                sky = sr < 0.6 ? 0 : 1;
            }
            default -> {
                stream = Hashing.range(Hashing.mix(h, 18, 0, 0), 1.2, 2.0);
                step = Hashing.range(Hashing.mix(h, 19, 0, 0), 3.5, 5.0);
            }
        }
        double[][] skylights = new double[sky][];
        for (int k = 0; k < sky; k++) {
            double rr = Hashing.range(Hashing.mix(h, 31 + k, 1, 0), 0.1, 0.5), th = Hashing.range(Hashing.mix(h, 31 + k, 2, 0), 0, Math.PI * 2);
            double u = a * rr * Math.cos(th), v = b * rr * Math.sin(th);
            skylights[k] = new double[]{cx + u * cos - v * sin, cz + u * sin + v * cos};
        }
        double ox = Hashing.range(Hashing.mix(h, 40, 0, 0), 0, 4096), oz = Hashing.range(Hashing.mix(h, 41, 0, 0), 0, 4096);
        return new Grotto(ci, cj, look, s0.trees(), cx, cz, angle, a, b, water, height, roofCap, gMin, gMax, rise, channel,
                poolU, poolV, poolR, poolD, stream, step, ox, oz, skylights);
    }

    private static double[] size(int look, long h) {
        double a, b;
        if (look == RAVINE) {
            a = Hashing.range(Hashing.mix(h, 3, 0, 0), 15, 22);
            b = Hashing.range(Hashing.mix(h, 4, 0, 0), 6, 9);
        } else if (look == HALL) {
            a = Hashing.range(Hashing.mix(h, 3, 0, 0), 10, 15);
            b = Hashing.range(Hashing.mix(h, 4, 0, 0), 7.5, Math.min(11, a * 0.85));
        } else {
            a = Hashing.range(Hashing.mix(h, 3, 0, 0), 8, 12);
            b = Hashing.range(Hashing.mix(h, 4, 0, 0), 7, Math.min(10, a));
        }
        return new double[]{a, b};
    }

    private Tunnel tail(Grotto g, long h, int k) {
        double r = Hashing.range(Hashing.mix(h, 1, 0, 0), 2.3, 3.0);
        double u, v;
        if (g.look == RAVINE) {
            // From a bank of the stream towards one end.
            u = (k == 0 ? 1 : -1) * g.a * Hashing.range(Hashing.mix(h, 2, 0, 0), 0.45, 0.7);
            v = (Hashing.unit(Hashing.mix(h, 3, 0, 0)) < 0.5 ? -1 : 1) * (g.stream + 2.5);
        } else {
            double phi = Hashing.range(Hashing.mix(h, 2, 0, 0), 0, Math.PI * 2);
            u = 0.72 * g.a * Math.cos(phi);
            v = 0.72 * g.b * Math.sin(phi);
        }
        double sx = g.worldX(u, v), sz = g.worldZ(u, v);
        double sy = g.water + 1 + 0.55 * r;
        double dx = sx - g.cx, dz = sz - g.cz, dl = Math.hypot(dx, dz);
        if (dl < 1e-6) { dx = 1; dz = 0; dl = 1; }
        double turn = Hashing.range(Hashing.mix(h, 4, 0, 0), -0.45, 0.45);
        double ct = Math.cos(turn), st = Math.sin(turn);
        double ux = (dx * ct - dz * st) / dl, uz = (dx * st + dz * ct) / dl;
        // Level, above the water, until clear of the grotto: a tunnel that dipped below its water level beside it
        // would drain it. Then down.
        double out = Math.max(3, reach(g.look, g.a, g.b) + 3 - dl);
        double qx = sx + ux * out, qz = sz + uz * out;
        double run = Hashing.range(Hashing.mix(h, 5, 0, 0), 28, 44), drop = Hashing.range(Hashing.mix(h, 6, 0, 0), 16, 28);
        double ex = qx + ux * run, ez = qz + uz * run, ey = sy - drop;
        if (ey < minWater - 30) return null;
        double side = Hashing.range(Hashing.mix(h, 7, 0, 0), -8, 8);
        double mx = (qx + ex) * 0.5 - uz * side, mz = (qz + ez) * 0.5 + ux * side, my = (sy + ey) * 0.5 + Hashing.range(Hashing.mix(h, 8, 0, 0), -2, 2);
        // Never under water, and 10 blocks of rock over it all the way (the ground may fall away faster than it does).
        if (!deepEnough(new double[][]{{qx, sy, qz}, {(qx + mx) * 0.5, (sy + my) * 0.5, (qz + mz) * 0.5}, {mx, my, mz},
                {(mx + ex) * 0.5, (my + ey) * 0.5, (mz + ez) * 0.5}, {ex, ey, ez}}, r)) return null;
        return new Tunnel(new double[]{sx, qx, mx, ex}, new double[]{sy, sy, my, ey}, new double[]{sz, qz, mz, ez}, r, false, g);
    }

    /**
     * The tunnels a cell owns: its grotto's tails, and links from it to the grottos east and south of it.
     *
     * <p>Tails lead down from a bank near the wall into the pack's caves, which are at full strength from about 26
     * under the ground: one always, a second now and then, each trying a few directions until it finds one that keeps
     * clear of the other grottos (a tunnel passing beside a grotto below its water level would drain it).
     */
    private Tunnel[] tunnels(int ci, int cj) {
        long k = key(ci, cj);
        Tunnel[] t = tunnels.get(k);
        if (t != null) return t;
        List<Tunnel> out = new ArrayList<>(4);
        Grotto g = grotto(ci, cj);
        if (g != null) {
            long h = Hashing.mix(seed, 7001, ci, cj);
            int n = Hashing.unit(Hashing.mix(h, 50, 0, 0)) < 0.35 ? 2 : 1;
            for (int i = 0; i < n; i++) {
                for (int attempt = 0; attempt < 4; attempt++) {
                    Tunnel tail = tail(g, Hashing.mix(h, 51 + i, attempt, 0), i);
                    if (tail != null && clear(tail, g, null)) {
                        out.add(tail);
                        break;
                    }
                }
            }
            Grotto e = grotto(ci + 1, cj), s = grotto(ci, cj + 1);
            if (e != null) { Tunnel l = link(g, e, 1); if (l != null && clear(l, g, e)) out.add(l); }
            if (s != null) { Tunnel l = link(g, s, 2); if (l != null && clear(l, g, s)) out.add(l); }
        }
        t = out.toArray(new Tunnel[0]);
        Tunnel[] prev = tunnels.putIfAbsent(k, t);
        return prev != null ? prev : t;
    }

    /**
     * True when a tunnel keeps clear of every grotto but the ones it joins: never within 8 blocks of another grotto's
     * reach unless well above or below it.
     */
    private boolean clear(Tunnel t, Grotto a, Grotto b) {
        int ci = Math.floorDiv((int) Math.floor(a.cx), CELL), cj = Math.floorDiv((int) Math.floor(a.cz), CELL);
        for (int di = -2; di <= 2; di++) for (int dj = -2; dj <= 2; dj++) {
            Grotto o = grotto(ci + di, cj + dj);
            if (o == null || o == a || o == b) continue;
            double near = reach(o.look, o.a, o.b) + 8, lo = o.bottom() - 6, hi = o.top() + 6;
            for (int i = 0; i + 1 < t.px.length; i++) {
                double dx = t.px[i + 1] - t.px[i], dy = t.py[i + 1] - t.py[i], dz = t.pz[i + 1] - t.pz[i];
                int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / 3));
                for (int s = 0; s <= steps; s++) {
                    double f = s / (double) steps;
                    double x = t.px[i] + dx * f, y = t.py[i] + dy * f, z = t.pz[i] + dz * f;
                    if (y + t.r < lo || y - t.r > hi) continue;
                    if (Math.hypot(x - o.cx, z - o.cz) < near) return false;
                }
            }
        }
        return true;
    }

    /**
     * A tunnel from bank to bank between two neighbouring grottos, or null when it would be too steep or too shallow.
     * It leaves each grotto level, above its water, and climbs or drops only in between, so neither drains.
     */
    private Tunnel link(Grotto a, Grotto b, int dir) {
        long h = Hashing.mix(seed, 7002 + dir, a.cellX, a.cellZ);
        if (Hashing.unit(h) >= 0.55) return null;
        double r = Hashing.range(Hashing.mix(h, 1, 0, 0), 2.3, 3.0);
        double y0 = a.water + 1 + 0.55 * r, y1 = b.water + 1 + 0.55 * r;
        double dx = b.cx - a.cx, dz = b.cz - a.cz, dl = Math.hypot(dx, dz);
        if (dl < 1) return null;
        double ux = dx / dl, uz = dz / dl;
        double ra = reach(a.look, a.a, a.b) + 3, rb = reach(b.look, b.a, b.b) + 3, between = dl - ra - rb;
        if (between < 4 || Math.abs(y1 - y0) > 0.7 * between) return null;
        double ax = a.cx + ux * ra, az = a.cz + uz * ra, bx = b.cx - ux * rb, bz = b.cz - uz * rb;
        double side = Hashing.range(Hashing.mix(h, 2, 0, 0), -10, 10);
        double mx = (ax + bx) * 0.5 - uz * side, mz = (az + bz) * 0.5 + ux * side, my = (y0 + y1) * 0.5;
        if (!deepEnough(new double[][]{{ax, y0, az}, {(ax + mx) * 0.5, (y0 + my) * 0.5, (az + mz) * 0.5}, {mx, my, mz},
                {(mx + bx) * 0.5, (my + y1) * 0.5, (mz + bz) * 0.5}, {bx, y1, bz}}, r)) return null;
        return new Tunnel(new double[]{a.cx, ax, mx, bx, b.cx}, new double[]{y0, y0, my, y1, y1}, new double[]{a.cz, az, mz, bz, b.cz}, r, true, a);
    }

    /** True when no probe {x, y, z} of a tunnel of radius r lies under water or within 10 blocks of the ground. */
    private boolean deepEnough(double[][] probes, double r) {
        for (double[] p : probes) {
            Overhead.Surface s = overhead.at((int) Math.floor(p[0]), (int) Math.floor(p[2]));
            if (s.wet() || p[1] + r + 1 > s.y() - 10) return false;
        }
        return true;
    }

    // ================================================================================================ columns

    /** Everything about one column that does not depend on y. */
    static final class Col {
        int x, z;
        boolean valid;
        int n, tn;
        final Grotto[] g = new Grotto[4];
        final double[] f = new double[4], c = new double[4], q = new double[4];
        /** How far outside the grotto's open footprint the column lies, in blocks (negative inside). */
        final double[] e = new double[4];
        final Tunnel[] t = new Tunnel[32];
        double lo, hi;
        int ground;
    }

    private Col column(int x, int z) {
        Col[] arr = cols.get();
        Col c = arr[((x & 31) << 5) | (z & 31)];
        if (c.valid && c.x == x && c.z == z) return c;
        c.x = x;
        c.z = z;
        c.valid = true;
        c.n = 0;
        c.tn = 0;
        c.ground = Integer.MIN_VALUE;
        c.lo = 1e9;
        c.hi = -1e9;
        int ci = Math.floorDiv(x, CELL), cj = Math.floorDiv(z, CELL);
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            Grotto g = grotto(ci + di, cj + dj);
            if (g != null && c.n < c.g.length && g.covers(x, z) && shape(g, x, z, c, c.n)) {
                c.lo = Math.min(c.lo, c.f[c.n]);
                c.hi = Math.max(c.hi, Math.max(c.c[c.n], c.f[c.n]));
                c.g[c.n++] = g;
            }
            for (Tunnel t : tunnels(ci + di, cj + dj)) {
                if (c.tn < c.t.length && t.covers(x, z)) {
                    c.t[c.tn++] = t;
                    c.lo = Math.min(c.lo, t.minY);
                    c.hi = Math.max(c.hi, t.maxY);
                }
            }
        }
        c.lo -= 6;
        c.hi += 6;
        return c;
    }

    private int ground(Col c) {
        if (c.ground == Integer.MIN_VALUE) c.ground = overhead.at(c.x, c.z).y();
        return c.ground;
    }

    /**
     * The floor and ceiling of a grotto over a column, into slot i of the column: open where floor &lt; y &lt; ceiling.
     * False when the column lies well outside it.
     */
    private boolean shape(Grotto g, int x, int z, Col col, int i) {
        double u = g.along(x, z), v = g.across(x, z);
        double nx = x + g.ox, nz = z + g.oz;
        double f, ce, q, e;
        switch (g.look) {
            case HALL -> {
                double wob = 1 + 0.14 * wobN.noise2(nx / 13.0, nz / 13.0);
                q = Math.sqrt(sq(u / g.a) + sq(v / g.b)) / wob;
                if (q >= 1.25) return false;
                // Low banks around a stream along the hall, then terraces up the walls.
                double t = Math.max(0, (q - 0.5) / 0.5);
                f = g.water + 0.3 + 0.7 * floorN.noise2(nx / 9.0, nz / 9.0) + Terrain.stairs(t * g.rise, 2.0, 0.55);
                double vm = v - 0.22 * g.b * meanderN.noise2(u / 16.0 + g.ox, g.oz);
                if (Math.abs(vm) < g.channel) f -= 2.6 * (1 - sq(vm / g.channel)) * (1 - Terrain.smooth((q - 0.72) / 0.25));
                ce = g.water + g.height * Math.sqrt(Math.max(0, 1 - q * q * q)) + 1.6 * ceilN.noise2(nx / 6.0, nz / 6.0);
                ce = Math.min(ce, Math.min(g.roofCap, ground(col) - 10));
                e = (q - 1) * Math.min(g.a, g.b);
            }
            case TEAL -> {
                double wob = 1 + 0.12 * wobN.noise2(nx / 11.0, nz / 11.0);
                q = Math.sqrt(sq(u / g.a) + sq(v / g.b)) / wob;
                if (q >= 1.25) return false;
                // Mossy terraces around a round pool.
                double t = Math.max(0, (q - 0.45) / 0.55);
                f = g.water + 1.0 + 0.7 * floorN.noise2(nx / 9.0, nz / 9.0) + Terrain.stairs(t * g.rise, 2.0, 0.5);
                double dp = Math.hypot(u - g.poolU, v - g.poolV) / (g.poolR * (1 + 0.15 * wobN.noise2(nx / 7.0, nz / 7.0 + 40)));
                if (dp < 1) f = Math.min(f, g.water + 0.3 - g.poolD * (1 - dp * dp));
                ce = g.water + g.height * Math.sqrt(Math.max(0, 1 - Math.pow(q, 2.5))) + 2.2 * ceilN.noise2(nx / 5.0, nz / 5.0);
                ce = Math.min(ce, Math.min(g.roofCap, ground(col) - 10));
                e = (q - 1) * Math.min(g.a, g.b);
            }
            default -> {
                // A slot with a stream at the bottom and stepped ledges up both walls; open to the sky over its middle,
                // roofed at its ends, which close in.
                q = Math.abs(u) / g.a;
                // Rock past its ends is listed too (closed), so the seal around its water reaches beyond them.
                if (Math.abs(u) >= g.a * 1.05 + 5) return false;
                double endFade = Terrain.smooth((1 - q) / 0.3);
                double half = g.b * (0.35 + 0.65 * endFade);
                double vv = Math.max(0, Math.abs(v) + 1.2 * wobN.noise2(nx / 8.0, nz / 8.0));
                int ground = ground(col);
                double bed = g.water - 1.5;
                f = Terrain.riftFloor(vv, bed, ground + 3, half, g.stream, g.step);
                double open = Terrain.smooth((0.6 - q) / 0.12);
                double roof = bed + (g.height + 1.5) * Terrain.smooth((1 - q) / 0.2) + 1.2 * ceilN.noise2(nx / 6.0, nz / 6.0);
                ce = open * (ground + 8) + (1 - open) * roof;
                if (q >= 1.05) ce = f - 10;
                e = Math.max(vv - half, (q - 1) * g.a);
            }
        }
        col.f[i] = f;
        col.c[i] = ce;
        col.q[i] = q;
        col.e[i] = e;
        return true;
    }

    // ================================================================================================ queries

    /**
     * Signed distance (in blocks, roughly) to the open space of the Rootvaults: negative inside, {@link #SOLID} where
     * nothing is near.
     */
    public double carve(int x, int y, int z) {
        Col c = column(x, z);
        if (y < c.lo || y > c.hi) return SOLID;
        double d = SOLID;
        for (int i = 0; i < c.n; i++) d = Math.min(d, Math.max(c.f[i] - y, y - c.c[i]));
        for (int i = 0; i < c.tn; i++) d = Math.min(d, c.t[i].dist(x, y, z));
        if (d < 4 && d > -4) d += 0.9 * roughN.noise3(x / 5.0, y / 4.0, z / 5.0);
        return d;
    }

    /** Signed distance to one grotto's own open space, without its tunnels or the roughness (for checks). */
    double shapeDist(Grotto g, int x, int y, int z) {
        Col c = new Col();
        c.x = x;
        c.z = z;
        c.ground = Integer.MIN_VALUE;
        if (!g.covers(x, z) || !shape(g, x, z, c, 0)) return SOLID;
        return Math.max(c.f[0] - y, y - c.c[0]);
    }

    /**
     * How deep in the rock a block lies, for dressing the grottos' shells: {@link #carve}, which measures up and down
     * its column, or one more than its nearest side neighbour's, so a wall face under a ledge counts as the surface it
     * is.
     */
    public double shell(int x, int y, int z) {
        double d = carve(x, y, z);
        if (d <= 3) return d;
        double side = Math.min(Math.min(carve(x + 1, y, z), carve(x - 1, y, z)), Math.min(carve(x, y, z + 1), carve(x, y, z - 1)));
        return Math.min(d, side + 1);
    }

    /**
     * A ravine's wall up to two blocks under the ground: dressed as the ravine even where the surface's own biome
     * holds it (the Rootvaults biome stays 6 blocks under the ground), so the forest's soil does not band every ledge
     * near the top.
     */
    public boolean ravineWall(int x, int y, int z) {
        Col c = column(x, z);
        for (int i = 0; i < c.n; i++) {
            Grotto g = c.g[i];
            if (g.look == RAVINE && c.e[i] < 6 && y >= g.bottom() - 4 && y <= ground(c) - 2) return true;
        }
        return false;
    }

    /** True for any column the Rootvaults may open (a quick test before the per-block work). */
    public boolean anyNear(int x, int z) {
        Col c = column(x, z);
        return c.n > 0 || c.tn > 0;
    }

    /**
     * Where the pack's own caves must stay shut: under and beside the water of a grotto, from 4 blocks below its lowest
     * open block to a block over its water level, within 4 blocks of its open footprint (a ravine: of its slot). The
     * grotto's own open space is carved again afterwards, so this only keeps the pack's caves from draining its water.
     */
    public boolean seals(int x, int y, int z) {
        Col c = column(x, z);
        if (c.n == 0 || y > c.hi) return false; // not c.lo: under a wall the floor is the ground
        for (int i = 0; i < c.n; i++) {
            Grotto g = c.g[i];
            if (c.e[i] < 4 && y <= g.water + 1 && y >= g.bottom() - 4) return true;
        }
        return false;
    }

    /**
     * Where the Rootvaults biome goes: within 5 blocks of a grotto's open space or 3 of its tunnels (tails only in the
     * top 46 blocks), or one biome cell (4 blocks) to the side of such a place, so the grottos' walls get their own
     * rock instead of the soil the pack puts on every ledge; and never within 6 blocks of the surface over the column.
     */
    public boolean biomeAt(int x, int y, int z) {
        if (inBiome(x, y, z)) return true;
        if (!inBiome(x - 4, y, z) && !inBiome(x + 4, y, z) && !inBiome(x, y, z - 4) && !inBiome(x, y, z + 4)) return false;
        return y <= ground(column(x, z)) - 6;
    }

    private boolean inBiome(int x, int y, int z) {
        Col c = column(x, z);
        if (c.n == 0 && c.tn == 0) return false;
        if (y < c.lo - 6 || y > c.hi + 6) return false;
        int ground = ground(c);
        if (y > ground - 6) return false;
        for (int i = 0; i < c.n; i++) if (Math.max(c.f[i] - y, y - c.c[i]) < 5) return true;
        for (int i = 0; i < c.tn; i++) {
            Tunnel t = c.t[i];
            if ((t.link || y >= ground - 46) && t.dist(x, y, z) < 3) return true;
        }
        return false;
    }

    /**
     * What fills an open block: {@link #WATER} below the water level of the grotto it is in, {@link #AIR} above it and
     * in the tunnels, or -1 to leave it to the pack (away from the Rootvaults).
     */
    public int fluid(int x, int y, int z) {
        Col c = column(x, z);
        if (c.n == 0 && c.tn == 0) return -1;
        if (y < c.lo - 4 || y > c.hi + 4) return -1;
        double best = 1e9;
        Grotto bg = null;
        for (int i = 0; i < c.n; i++) {
            double d = Math.max(c.f[i] - y, y - c.c[i]);
            if (d < best) { best = d; bg = c.g[i]; }
        }
        if (bg != null && best < 2.5) return y < bg.water ? WATER : AIR;
        for (int i = 0; i < c.tn; i++) if (c.t[i].dist(x, y, z) < 2) return AIR;
        return -1;
    }

    /** The look of the Rootvaults over a column (0 root hall, 1 teal grotto, 2 ravine), or -1 where there are none. */
    public int look(int x, int z) {
        Col c = column(x, z);
        int best = -1;
        double bq = 1e9;
        for (int i = 0; i < c.n; i++) if (c.q[i] < bq) { bq = c.q[i]; best = c.g[i].look; }
        if (best >= 0) return best;
        return c.tn > 0 ? c.t[0].owner.look : -1;
    }

    /** The grotto whose footprint covers a column, or null. */
    public Grotto grottoAt(int x, int z) {
        Col c = column(x, z);
        Grotto best = null;
        double bq = 1e9;
        for (int i = 0; i < c.n; i++) if (c.q[i] < bq && c.q[i] < 1.05) { bq = c.q[i]; best = c.g[i]; }
        return best;
    }

    /**
     * The heights a feature should try over a column to land in a grotto: {lowest, highest}, or null where no grotto
     * lies under it. Kept 4 blocks under the ground (a ravine opens higher, but its top is the surface's biome).
     */
    public int[] band(int x, int z) {
        Col c = column(x, z);
        if (c.n == 0) return null;
        double lo = 1e9, hi = -1e9;
        for (int i = 0; i < c.n; i++) {
            if (c.c[i] <= c.f[i] + 1) continue;
            lo = Math.min(lo, c.f[i] - 1);
            hi = Math.max(hi, c.c[i] + 1);
        }
        if (lo > hi) return null;
        hi = Math.min(hi, ground(c) - 4);
        if (lo > hi) return null;
        return new int[]{(int) Math.floor(lo), (int) Math.ceil(hi)};
    }

    /** The planned skylights whose column lies in a chunk: {x, y, z}, y inside the grotto under it. */
    public List<int[]> skylights(int chunkX, int chunkZ) {
        int x0 = chunkX << 4, z0 = chunkZ << 4;
        int ci0 = Math.floorDiv(x0, CELL), cj0 = Math.floorDiv(z0, CELL), ci1 = Math.floorDiv(x0 + 15, CELL), cj1 = Math.floorDiv(z0 + 15, CELL);
        List<int[]> out = new ArrayList<>();
        for (int ci = ci0; ci <= ci1; ci++) for (int cj = cj0; cj <= cj1; cj++) {
            Grotto g = grotto(ci, cj);
            if (g == null) continue;
            for (double[] s : g.skylights) {
                int x = (int) Math.floor(s[0]), z = (int) Math.floor(s[1]);
                if (x >> 4 != chunkX || z >> 4 != chunkZ) continue;
                out.add(new int[]{x, (int) Math.floor(g.water + g.height * 0.5), z});
            }
        }
        return out;
    }

    // ================================================================================================ shape helpers

    static double sq(double v) { return v * v; }
}
