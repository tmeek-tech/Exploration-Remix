package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seeded, lazily built plans: one gate per band per cell, and per layer per cell the route from the entry landing to
 * the exit landing (with its halls, bridges and refuge geodes). Everything a plan draws stays inside its own cell
 * with a wide margin, so a block only ever needs its own cell's plan and no seam can form at a cell edge.
 */
public final class Plans {
    /** Planned geometry keeps this far from a cell edge (blocks). */
    static final double MARGIN = 72;

    private final Terrain terrain;
    private final Shape shape;
    private final long seed;
    final int cellSize;
    private final double passageRadius;
    private final double landingRadius;
    private final Map<Long, Gate> gates = new ConcurrentHashMap<>();
    private final Map<Long, Route> routes = new ConcurrentHashMap<>();

    Plans(Terrain terrain, long seed) {
        this.terrain = terrain;
        this.shape = terrain.shape;
        this.seed = seed;
        this.cellSize = Math.max(320, terrain.settings.gateSpacing());
        this.passageRadius = terrain.settings.passageRadius();
        this.landingRadius = terrain.settings.landingRadius();
    }

    public int cellOf(double coord) { return (int) Math.floor(coord / cellSize); }

    private static long key(int kind, int cx, int cz) {
        return ((long) (kind & 0xFF) << 56) | ((cx & 0xFFFFFFFL) << 28) | (cz & 0xFFFFFFFL);
    }

    private double clampCell(double v, int cell, double margin) {
        double lo = cell * (double) cellSize + margin, hi = (cell + 1) * (double) cellSize - margin;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ------------------------------------------------------------------------------------------------ gates

    /**
     * A gate: a twisting natural passage down through one band. It leaves the floor of the upper landing (in the layer
     * above, on the shore of its water table) and winds down to the lower landing just under the band.
     */
    public static final class Gate {
        public final int band, cellX, cellZ;
        public final double ux, uz, uFloor, uCy;     // upper landing: centre x/z, floor y, centre y
        public final double lx, lz, lFloor, lCy;     // lower landing
        public final double landR, landHalf;          // landing radius (xz) and half height
        final double c1x, c1z, c2x, c2z;
        public final double y0, y1;                   // passage centre-line y at t = 0 and t = 1
        public final double radius;
        final double phase, wob;

        Gate(int band, int cellX, int cellZ, double ux, double uz, double uFloor, double lx, double lz, double lFloor,
             double c1x, double c1z, double c2x, double c2z, double radius, double landR, double landHalf, double phase, double wob) {
            this.band = band; this.cellX = cellX; this.cellZ = cellZ;
            this.ux = ux; this.uz = uz; this.uFloor = uFloor;
            this.lx = lx; this.lz = lz; this.lFloor = lFloor;
            this.c1x = c1x; this.c1z = c1z; this.c2x = c2x; this.c2z = c2z;
            this.radius = radius; this.landR = landR; this.landHalf = landHalf;
            this.uCy = uFloor + landHalf * 0.55;
            this.lCy = lFloor + landHalf * 0.55;
            this.y0 = uFloor + 3;
            this.y1 = lFloor + 3;
            this.phase = phase; this.wob = wob;
        }

        static double bez(double p0, double p1, double p2, double p3, double t) {
            double u = 1 - t;
            return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3;
        }

        /** Passage centre x at parameter t, with a slow sideways wobble so it never runs straight. */
        public double px(double t) {
            double dx = lx - ux, dz = lz - uz, len = Math.max(1, Math.hypot(dx, dz));
            return bez(ux, c1x, c2x, lx, t) - dz / len * wob * Math.sin(t * Math.PI * 2.0 + phase) * Math.sin(t * Math.PI);
        }

        public double pz(double t) {
            double dx = lx - ux, dz = lz - uz, len = Math.max(1, Math.hypot(dx, dz));
            return bez(uz, c1z, c2z, lz, t) + dx / len * wob * Math.sin(t * Math.PI * 2.0 + phase) * Math.sin(t * Math.PI);
        }

        public double py(double t) { return y0 + (y1 - y0) * t; }

        /** Passage parameter for a height, or NaN outside the passage. */
        public double tAt(double y) {
            if (y > y0 + 2 || y < y1 - 2) return Double.NaN;
            double t = (y - y0) / (y1 - y0);
            return t < 0 ? 0 : (t > 1 ? 1 : t);
        }

        /** Passage radius along its length: it swells and narrows like a natural cave. */
        public double radiusAt(double t) {
            return radius * (1.0 + 0.22 * Math.sin(t * Math.PI * 3.0 + phase * 1.7) + 0.12 * Math.sin(t * Math.PI * 7.0 + phase));
        }

        /** Horizontal distance from (x, z) to the passage centre at height y, or MAX outside its span. */
        public double passageXZ(double x, double y, double z) {
            double t = tAt(y);
            if (Double.isNaN(t)) return Double.MAX_VALUE;
            double dx = x - px(t), dz = z - pz(t);
            return Math.sqrt(dx * dx + dz * dz);
        }

        public double lengthEstimate() {
            double len = 0, px = px(0), pz = pz(0), py = py(0);
            for (int i = 1; i <= 32; i++) {
                double t = i / 32.0, x = px(t), z = pz(t), y = py(t);
                len += Math.sqrt((x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz));
                px = x; pz = z; py = y;
            }
            return len;
        }

        /** Whether the passage or either landing could reach into the given box (padded). */
        public boolean overlaps(double minX, double minZ, double maxX, double maxZ, double pad) {
            double p = pad + Math.max(radius * 1.4, landR);
            return maxX >= minX() - p && minX <= maxX() + p && maxZ >= minZ() - p && minZ <= maxZ() + p;
        }

        public double minX() { return Math.min(Math.min(ux, lx), Math.min(c1x, c2x)) - wob; }
        public double maxX() { return Math.max(Math.max(ux, lx), Math.max(c1x, c2x)) + wob; }
        public double minZ() { return Math.min(Math.min(uz, lz), Math.min(c1z, c2z)) - wob; }
        public double maxZ() { return Math.max(Math.max(uz, lz), Math.max(c1z, c2z)) + wob; }
    }

    /** The gate through band b (1-based) in a cell. */
    public Gate gate(int band, int cx, int cz) {
        Gate g = gates.get(key(band, cx, cz));
        if (g != null) return g;
        g = buildGate(band, cx, cz);
        Gate prev = gates.putIfAbsent(key(band, cx, cz), g);
        return prev != null ? prev : g;
    }

    /** The point a layer's route starts from must be far from where it ends, so every route is a real journey. */
    private double minRouteDistance() { return Math.min(260, cellSize * 0.42); }

    private Gate buildGate(int band, int cx, int cz) {
        long h = Hashing.mix(seed, 101 + band, cx, cz);
        double half = landingRadius / 1.6;
        double ux, uz;
        if (band == 1) {
            ux = clampCell(cx * (double) cellSize + Hashing.range(Hashing.mix(h, 1, 0, 0), 0.2, 0.8) * cellSize, cx, MARGIN + 20);
            uz = clampCell(cz * (double) cellSize + Hashing.range(Hashing.mix(h, 2, 0, 0), 0.2, 0.8) * cellSize, cz, MARGIN + 20);
        } else {
            Gate above = gate(band - 1, cx, cz);
            double[] p = farPoint(h, above.lx, above.lz, cx, cz);
            ux = p[0]; uz = p[1];
        }
        // Upper landing floor: the pack's old floor for band 1, otherwise just above the water table of the layer above.
        double uFloor = band == 1 ? shape.packFloor : shape.waterY(band - 2) + 2;
        int layerBelow = band - 1;
        double lCy = shape.top(layerBelow) - 3 - half;
        double lFloor = lCy - half * 0.55;
        double angle = Hashing.range(Hashing.mix(h, 3, 0, 0), 0, Math.PI * 2);
        double dist = Hashing.range(Hashing.mix(h, 4, 0, 0), 46, 64);
        double lx = clampCell(ux + Math.cos(angle) * dist, cx, MARGIN + 20), lz = clampCell(uz + Math.sin(angle) * dist, cz, MARGIN + 20);
        double dx = lx - ux, dz = lz - uz, len = Math.max(1, Math.hypot(dx, dz));
        double perp = Hashing.range(Hashing.mix(h, 5, 0, 0), 14, 22) * (Hashing.unit(Hashing.mix(h, 6, 0, 0)) < 0.5 ? -1 : 1);
        double nx = -dz / len, nz = dx / len;
        double c1x = clampCell(ux + dx * 0.33 + nx * perp, cx, MARGIN), c1z = clampCell(uz + dz * 0.33 + nz * perp, cz, MARGIN);
        double c2x = clampCell(ux + dx * 0.66 - nx * perp, cx, MARGIN), c2z = clampCell(uz + dz * 0.66 - nz * perp, cz, MARGIN);
        double phase = Hashing.range(Hashing.mix(h, 7, 0, 0), 0, Math.PI * 2);
        double wob = Hashing.range(Hashing.mix(h, 8, 0, 0), 2.0, 4.0);
        return new Gate(band, cx, cz, ux, uz, uFloor, lx, lz, lFloor, c1x, c1z, c2x, c2z, passageRadius, landingRadius, half, phase, wob);
    }

    /** A point in the cell at least {@link #minRouteDistance()} from (fx, fz), chosen from a few seeded candidates. */
    private double[] farPoint(long h, double fx, double fz, int cx, int cz) {
        double want = minRouteDistance();
        double bestX = 0, bestZ = 0, bestScore = -1e9;
        for (int i = 0; i < 12; i++) {
            double x = cx * (double) cellSize + Hashing.range(Hashing.mix(h, 40 + i, 1, 0), 0.0, 1.0) * cellSize;
            double z = cz * (double) cellSize + Hashing.range(Hashing.mix(h, 40 + i, 2, 0), 0.0, 1.0) * cellSize;
            x = clampCell(x, cx, MARGIN + 20);
            z = clampCell(z, cz, MARGIN + 20);
            double d = Math.hypot(x - fx, z - fz);
            double score = d >= want ? 1000 - Math.abs(d - want * 1.25) : d - want;
            if (score > bestScore) { bestScore = score; bestX = x; bestZ = z; }
        }
        return new double[]{bestX, bestZ};
    }

    // ------------------------------------------------------------------------------------------------ upper caves

    private final Map<Long, Upper.Piece> uppers = new ConcurrentHashMap<>();
    private static final Upper.Piece NO_PIECE = new Upper.Cavern(0, 0, 1, 1, -1e9, -1e9, -1e9, 0);

    /** The Silver Fern Rift or Ivory Root Cavern carved into the pack's caves in a cell, or null. */
    public Upper.Piece upper(int cx, int cz) {
        long k = key(1, cx, cz);
        Upper.Piece p = uppers.get(k);
        if (p == null) {
            Gate g = gate(1, cx, cz);
            Route.Link r = ramp(g);
            double[][] avoid = {{g.ux, g.uz, g.landR + 40}, {r.x2(), r.z2(), 30}, {(g.ux + r.x2()) * 0.5, (g.uz + r.z2()) * 0.5, 30}};
            p = Upper.plan(seed, cx, cz, cellSize, 8, shape.packFloor, avoid, terrain.surfaceProbe);
            if (p == null) p = NO_PIECE;
            Upper.Piece prev = uppers.putIfAbsent(k, p);
            if (prev != null) p = prev;
        }
        return p == NO_PIECE ? null : p;
    }

    private final Map<Long, Route.Link> ramps = new ConcurrentHashMap<>();

    /** The first gate's upper landing sits on the pack's old floor; this ramp climbs from it into the pack's caves. */
    public Route.Link ramp(Gate g) {
        return ramps.computeIfAbsent(key(0, g.cellX, g.cellZ), k -> {
            long h = Hashing.mix(seed, 777, g.cellX, g.cellZ);
            double angle = Hashing.range(Hashing.mix(h, 1, 0, 0), 0, Math.PI * 2);
            double run = 70, rise = 38;
            double ex = clampCell(g.ux + Math.cos(angle) * run, g.cellX, MARGIN), ez = clampCell(g.uz + Math.sin(angle) * run, g.cellZ, MARGIN);
            return new Route.Link(g.ux, g.uFloor + 3, g.uz, ex, g.uFloor + rise, ez, 4.0);
        });
    }

    // ------------------------------------------------------------------------------------------------ routes

    public Route route(int layer, int cx, int cz) {
        long k = key(16 + layer, cx, cz);
        Route r = routes.get(k);
        if (r != null) return r;
        r = buildRoute(layer, cx, cz);
        Route prev = routes.putIfAbsent(k, r);
        return prev != null ? prev : r;
    }

    private Route buildRoute(int layer, int cx, int cz) {
        long h = Hashing.mix(seed, 500 + layer, cx, cz);
        Gate entry = gate(layer + 1, cx, cz);
        boolean last = layer == shape.layerCount - 1;
        Gate exit = last ? null : gate(layer + 2, cx, cz);
        double W = shape.waterY(layer);
        double sx = entry.lx, sz = entry.lz, sFloor = entry.lFloor;
        double ex, ez, eFloor;
        double endR, endHalf;
        if (exit != null) {
            ex = exit.ux; ez = exit.uz; eFloor = exit.uFloor;
            endR = exit.landR; endHalf = exit.landHalf;
        } else {
            double[] p = farPoint(Hashing.mix(h, 9, 9, 9), sx, sz, cx, cz);
            ex = p[0]; ez = p[1]; eFloor = W + 2;
            endR = landingRadius + 5; endHalf = (landingRadius + 5) / 1.5;
        }

        // Waypoints: a meandering line from the entry landing to the exit landing. Deeper layers have further to
        // descend, so the line meanders more (and gains waypoints) until it is long enough for gentle ramps.
        double drop = Math.max(0, sFloor - eFloor);
        double want = layer == Terrain.GARDENS ? 40 + drop / 0.48 + 170 : (10 + drop / 0.48) / 0.92;
        List<double[]> pts = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            double amp = 1.0 + 0.25 * attempt;
            List<double[]> cand = meander(h, layer, cx, cz, sx, sz, ex, ez, amp, attempt / 2);
            if (pts == null || polyLength(cand) > polyLength(pts)) pts = cand;
            if (polyLength(pts) >= want) break;
        }
        return new Route(terrain, layer, cx, cz, h, pts, sFloor, eFloor, entry, exit, ex, ez, endR, endHalf, W);
    }

    /** A smoothed meandering polyline from (sx, sz) to (ex, ez), one point every ~3 blocks, kept inside the cell. */
    private List<double[]> meander(long h, int layer, int cx, int cz, double sx, double sz, double ex, double ez, double amp, int extra) {
        List<double[]> way = new ArrayList<>();
        way.add(new double[]{sx, sz});
        double dx = ex - sx, dz = ez - sz, dist = Math.max(1, Math.hypot(dx, dz));
        double nx = -dz / dist, nz = dx / dist;
        int n = 3 + (int) (Hashing.unit(Hashing.mix(h, 20, 0, 0)) * 2.0) + extra;
        double sign = Hashing.unit(Hashing.mix(h, 21, 0, 0)) < 0.5 ? -1 : 1;
        for (int i = 0; i < n; i++) {
            long hi = Hashing.mix(h, 30 + i, 0, 0);
            double t = (i + 1.0) / (n + 1) + Hashing.range(Hashing.mix(hi, 1, 0, 0), -0.05, 0.05);
            // A rift bends gently: its path runs on a ledge far from the centre line, which tight bends would fold.
            double off = sign * amp * (layer == Terrain.BLUE ? Hashing.range(Hashing.mix(hi, 2, 0, 0), 0.08, 0.22)
                    : Hashing.range(Hashing.mix(hi, 2, 0, 0), 0.12, 0.34)) * Math.max(dist, 220);
            sign = -sign;
            double wx = clampCell(sx + dx * t + nx * off, cx, MARGIN + 10);
            double wz = clampCell(sz + dz * t + nz * off, cz, MARGIN + 10);
            way.add(new double[]{wx, wz});
        }
        way.add(new double[]{ex, ez});
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i + 1 < way.size(); i++) {
            double[] p0 = way.get(Math.max(0, i - 1)), p1 = way.get(i), p2 = way.get(i + 1), p3 = way.get(Math.min(way.size() - 1, i + 2));
            double segLen = Math.hypot(p2[0] - p1[0], p2[1] - p1[1]);
            int steps = Math.max(2, (int) Math.ceil(segLen / 3.0));
            for (int k = 0; k < steps; k++) {
                double t = k / (double) steps;
                double x = catmull(p0[0], p1[0], p2[0], p3[0], t), z = catmull(p0[1], p1[1], p2[1], p3[1], t);
                pts.add(new double[]{clampCell(x, cx, MARGIN), clampCell(z, cz, MARGIN)});
            }
        }
        pts.add(new double[]{ex, ez});
        return pts;
    }

    private static double polyLength(List<double[]> pts) {
        double l = 0;
        for (int i = 1; i < pts.size(); i++) l += Math.hypot(pts.get(i)[0] - pts.get(i - 1)[0], pts.get(i)[1] - pts.get(i - 1)[1]);
        return l;
    }

    static double catmull(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }
}
