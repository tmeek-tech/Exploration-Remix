package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seeded, lazily built plans: one gate per band per cell, the first gate's ramp up into the pack's caves, and the
 * upper set piece of a cell. Everything a plan draws stays inside its own cell with a wide margin, so a block only
 * ever needs its own cell's plan and no seam can form at a cell edge. The layers' caves are the network's
 * ({@link Network}).
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
        /** Which of its cell's den gates this is ({@link #denGates}); 0 for the cell's first gate and every deeper gate. */
        public final int den;
        public final double ux, uz, uFloor, uCy;     // upper landing: centre x/z, floor y, centre y
        public final double lx, lz, lFloor, lCy;     // lower landing
        public final double landR, landHalf;          // landing radius (xz) and half height
        final double c1x, c1z, c2x, c2z;
        public final double y0, y1;                   // passage centre-line y at t = 0 and t = 1
        public final double radius;
        final double phase, wob;

        Gate(int band, int cellX, int cellZ, double ux, double uz, double uFloor, double lx, double lz, double lFloor,
             double c1x, double c1z, double c2x, double c2z, double radius, double landR, double landHalf, double phase, double wob) {
            this(band, cellX, cellZ, 0, ux, uz, uFloor, lx, lz, lFloor, c1x, c1z, c2x, c2z, radius, landR, landHalf, phase, wob);
        }

        Gate(int band, int cellX, int cellZ, int den, double ux, double uz, double uFloor, double lx, double lz, double lFloor,
             double c1x, double c1z, double c2x, double c2z, double radius, double landR, double landHalf, double phase, double wob) {
            this.band = band; this.cellX = cellX; this.cellZ = cellZ; this.den = den;
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

    /**
     * A layer's way down must be far from where its way in lands, so crossing a layer is a real journey (0.2.0's
     * rule, kept so the gates do not move).
     */
    private double minGateDistance() { return Math.min(260, cellSize * 0.42); }

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

    /** A point in the cell at least {@link #minGateDistance()} from (fx, fz), chosen from a few seeded candidates. */
    private double[] farPoint(long h, double fx, double fz, int cx, int cz) {
        double want = minGateDistance();
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
        if (g.band == 1 && g.den > 0) {
            // not computeIfAbsent: building one asks for the cell's den gates and set piece, which use this map's sibling
            long k = key(g.den, g.cellX, g.cellZ);
            Route.Link l = denRamps.get(k);
            if (l != null) return l;
            l = denRamp(g);
            Route.Link prev = denRamps.putIfAbsent(k, l);
            return prev != null ? prev : l;
        }
        return ramps.computeIfAbsent(key(0, g.cellX, g.cellZ), k -> {
            long h = Hashing.mix(seed, 777, g.cellX, g.cellZ);
            double angle = Hashing.range(Hashing.mix(h, 1, 0, 0), 0, Math.PI * 2);
            double run = 70, rise = 38;
            double ex = clampCell(g.ux + Math.cos(angle) * run, g.cellX, MARGIN), ez = clampCell(g.uz + Math.sin(angle) * run, g.cellZ, MARGIN);
            return new Route.Link(g.ux, g.uFloor + 3, g.uz, ex, g.uFloor + rise, ez, 4.0);
        });
    }

    // ------------------------------------------------------------------------------------------------ den gates

    /** Den gates in one cell keep at least this far apart (blocks): with the cell margins, about one every 250 blocks. */
    public static final double DEN_SPACING = 170;
    /** Upper landings keep at least this far from a cell edge, as the first gate's do. */
    static final double DEN_EDGE = MARGIN + 20;
    /** Their lower landings in the Blue Reaches keep at least this far apart (blocks). */
    static final double DEN_LOWER_SPACING = 90;
    /** A den gate's passage runs at least this far across from its upper landing to its lower one (blocks). */
    static final double DEN_MIN_RUN = 46;
    private static final int DEN_TRIES = 48, DEN_MAX = 9;
    private final Map<Long, List<Gate>> denGateLists = new ConcurrentHashMap<>();
    private final Map<Long, Route.Link> denRamps = new ConcurrentHashMap<>();

    /**
     * Every way down from the Upper Caves to the Blue Reaches in a cell, each with a Mossback den on its upper landing:
     * the cell's first gate ({@code gate(1, cx, cz)}, index 0, left where it always was so the deeper gates keep their
     * places) and the extra den gates after it (index = {@link Gate#den}). A pure function of the seed and the cell,
     * cached; the extras keep {@link #DEN_SPACING} from each other and the first gate, {@link #DEN_EDGE} from the cell's
     * edges, and clear of the first gate's ramp and the cell's upper set piece.
     */
    public List<Gate> denGates(int cx, int cz) {
        long k = key(1, cx, cz);
        List<Gate> l = denGateLists.get(k);
        if (l != null) return l;
        l = List.copyOf(buildDenGates(cx, cz));
        List<Gate> prev = denGateLists.putIfAbsent(k, l);
        return prev != null ? prev : l;
    }

    /** The den gate whose upper landing is nearest (x, z), looking in the 3 x 3 cells round it. */
    public Gate nearestDenGate(double x, double z) {
        int cx = cellOf(x), cz = cellOf(z);
        Gate best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (Gate g : denGates(cx + dx, cz + dz)) {
                    double d = (g.ux - x) * (g.ux - x) + (g.uz - z) * (g.uz - z);
                    if (d < bestD) { bestD = d; best = g; }
                }
        return best;
    }

    private List<Gate> buildDenGates(int cx, int cz) {
        Gate first = gate(1, cx, cz);
        List<Gate> out = new ArrayList<>();
        out.add(first);
        Route.Link r = ramp(first);
        Upper.Piece piece = upper(cx, cz);
        long h = Hashing.mix(seed, 4101, cx, cz);
        double span = cellSize - 2 * DEN_EDGE;
        for (int i = 0; i < DEN_TRIES && out.size() < DEN_MAX; i++) {
            double x = cx * (double) cellSize + DEN_EDGE + Hashing.unit(Hashing.mix(h, i, 1, 0)) * span;
            double z = cz * (double) cellSize + DEN_EDGE + Hashing.unit(Hashing.mix(h, i, 2, 0)) * span;
            boolean ok = true;
            for (Gate g : out) if (Math.hypot(x - g.ux, z - g.uz) < DEN_SPACING) { ok = false; break; }
            if (!ok) continue;
            // clear of the first gate's ramp (its middle and top), and of the set piece by as much as the first gate is
            if (Math.hypot(x - r.x2(), z - r.z2()) < landingRadius + 50) continue;
            if (Math.hypot(x - (r.x1() + r.x2()) * 0.5, z - (r.z1() + r.z2()) * 0.5) < landingRadius + 40) continue;
            if (piece != null && piece.distXZ(x, z) < landingRadius + 40) continue;
            Gate g = buildDenGate(Hashing.mix(h, 100 + i, 0, 0), out.size(), cx, cz, x, z);
            // near a cell edge the clamp can pull the lower landing back under the upper one, and the passage would
            // be a straight shaft: keep only those that run as far across as the first gates do
            if (Math.hypot(g.lx - g.ux, g.lz - g.uz) < DEN_MIN_RUN) continue;
            // the lower landings, each with its courts in the top of the Blue Reaches, keep room between them too
            for (Gate o : out) if (Math.hypot(g.lx - o.lx, g.lz - o.lz) < DEN_LOWER_SPACING) { ok = false; break; }
            if (ok) out.add(g);
        }
        return out;
    }

    /** A den gate from its upper landing: the same passage and landings as {@link #buildGate} makes for band 1. */
    private Gate buildDenGate(long h, int index, int cx, int cz, double ux, double uz) {
        double half = landingRadius / 1.6;
        double uFloor = shape.packFloor;
        double lCy = shape.top(0) - 3 - half;
        double lFloor = lCy - half * 0.55;
        double angle = Hashing.range(Hashing.mix(h, 3, 0, 0), 0, Math.PI * 2);
        double dist = Hashing.range(Hashing.mix(h, 4, 0, 0), 46, 64);
        double lx = clampCell(ux + Math.cos(angle) * dist, cx, DEN_EDGE), lz = clampCell(uz + Math.sin(angle) * dist, cz, DEN_EDGE);
        double dx = lx - ux, dz = lz - uz, len = Math.max(1, Math.hypot(dx, dz));
        double perp = Hashing.range(Hashing.mix(h, 5, 0, 0), 14, 22) * (Hashing.unit(Hashing.mix(h, 6, 0, 0)) < 0.5 ? -1 : 1);
        double nx = -dz / len, nz = dx / len;
        double c1x = clampCell(ux + dx * 0.33 + nx * perp, cx, MARGIN), c1z = clampCell(uz + dz * 0.33 + nz * perp, cz, MARGIN);
        double c2x = clampCell(ux + dx * 0.66 - nx * perp, cx, MARGIN), c2z = clampCell(uz + dz * 0.66 - nz * perp, cz, MARGIN);
        double phase = Hashing.range(Hashing.mix(h, 7, 0, 0), 0, Math.PI * 2);
        double wob = Hashing.range(Hashing.mix(h, 8, 0, 0), 2.0, 4.0);
        return new Gate(1, cx, cz, index, ux, uz, uFloor, lx, lz, lFloor, c1x, c1z, c2x, c2z, passageRadius, landingRadius, half, phase, wob);
    }

    /**
     * A den gate's ramp up into the pack's caves (the first gate keeps {@link #ramp}'s seeded one). Tried in eight
     * directions, three lengths and three rises; it ends where the pack's own density finds open cave round about, as
     * the entrances' descents do, keeping clear of the other den gates' landings and the set piece. With no open cave
     * in reach it falls back to the first gate's kind of ramp.
     */
    private Route.Link denRamp(Gate g) {
        long h = Hashing.mix(seed, 4177, g.cellX * 8 + g.den, g.cellZ);
        Upper.SurfaceProbe probe = terrain.surfaceProbe;
        List<Gate> others = denGates(g.cellX, g.cellZ);
        Upper.Piece piece = upper(g.cellX, g.cellZ);
        double rot = Hashing.range(Hashing.mix(h, 1, 0, 0), 0, Math.PI / 4);
        double best = -1e18, bx = 0, by = 0, bz = 0;
        if (probe != null) {
            for (int k = 0; k < 8; k++) {
                double phi = rot + k * Math.PI / 4;
                for (double run : new double[]{44, 57, 70}) {
                    double ex = clampCell(g.ux + Math.cos(phi) * run, g.cellX, MARGIN), ez = clampCell(g.uz + Math.sin(phi) * run, g.cellZ, MARGIN);
                    if (!rampClear(g, others, piece, ex, ez)) continue;
                    for (double rise : new double[]{24, 31, 38}) {
                        int x = (int) Math.round(ex), y = (int) Math.round(g.uFloor + rise), z = (int) Math.round(ez);
                        double dens = probe.packDensity(x, y, z);
                        if (dens >= -0.08) continue;
                        int open = 0;
                        if (probe.packDensity(x + 4, y, z) < 0) open++;
                        if (probe.packDensity(x - 4, y, z) < 0) open++;
                        if (probe.packDensity(x, y, z + 4) < 0) open++;
                        if (probe.packDensity(x, y, z - 4) < 0) open++;
                        if (open < 2) continue;
                        double score = Math.min(0.6, -dens) * 3 + open * 0.2 - Math.abs(rise / run - 0.5) - run / 140
                                + 0.2 * Hashing.unit(Hashing.mix(h, k, (long) run, (long) rise));
                        if (score > best) { best = score; bx = ex; by = g.uFloor + rise; bz = ez; }
                    }
                }
            }
        }
        if (best <= -1e17) {
            double angle = Hashing.range(Hashing.mix(h, 2, 0, 0), 0, Math.PI * 2);
            for (int k = 0; k < 8; k++) {
                double phi = angle + k * Math.PI / 4;
                bx = clampCell(g.ux + Math.cos(phi) * 70, g.cellX, MARGIN);
                bz = clampCell(g.uz + Math.sin(phi) * 70, g.cellZ, MARGIN);
                if (rampClear(g, others, piece, bx, bz)) break;
            }
            by = g.uFloor + 38;
        }
        return new Route.Link(g.ux, g.uFloor + 3, g.uz, bx, by, bz, 4.0);
    }

    private boolean rampClear(Gate g, List<Gate> others, Upper.Piece piece, double ex, double ez) {
        double mx = (g.ux + ex) * 0.5, mz = (g.uz + ez) * 0.5;
        for (Gate o : others) {
            if (o == g) continue;
            if (Math.hypot(ex - o.ux, ez - o.uz) < o.landR + 30 || Math.hypot(mx - o.ux, mz - o.uz) < o.landR + 30) return false;
        }
        return piece == null || (piece.distXZ(ex, ez) > 30 && piece.distXZ(mx, mz) > 30);
    }
}
