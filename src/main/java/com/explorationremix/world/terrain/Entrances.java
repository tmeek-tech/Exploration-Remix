package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The entrances into the Upper Caves: small cave systems just under the ground that open onto the surface and lead down
 * into the pack's own caves. What covers the ground decides which ({@link Overhead.Cover}):
 * <ul>
 *     <li>under forests, the Root Vaults: a cluster of two to five chambers of mixed sizes (root halls, teal grottos and
 *     ravines), 15 to 40 blocks apart, their ceilings 4 to 6 blocks under the roots of the trees, joined by passages,
 *     with one or two mouths up to the surface and tunnels (descents) down into the pack's caves. Under snowy forests the
 *     same shapes, frozen round their openings ({@link Area#FROZEN_ROOTVAULTS});</li>
 *     <li>under plains, sinkholes: a daylit pit over a pool, undercut below its rim, with a talus slope down one side;</li>
 *     <li>under deserts and badlands, oasis hollows: a sandstone hollow round a spring, with a mouth up to the dunes and a
 *     skylight over the water.</li>
 * </ul>
 * Every chamber is planned with at least two ways out (passages, mouths, descents, a sinkhole's talus), links join
 * neighbouring sites, and the descents end in open parts of the pack's caves, so no entrance ends blind.
 *
 * <p>One candidate site per {@value #CELL}-block cell. Everything is a pure function of the seed and the surface
 * ({@link Overhead}): safe to call from any thread, and the same on every call.
 */
public final class Entrances {
    /** Chamber looks. */
    public static final int HALL = 0, TEAL = 1, RAVINE = 2, SINKHOLE = 3, HOLLOW = 4;
    public static final int LOOKS = 5;
    /** Chamber sizes. */
    public static final int SMALL = 0, MEDIUM = 1, LARGE = 2;
    public static final int AIR = 0, WATER = 1;
    /** Site grid: one candidate site per cell this wide. */
    public static final int CELL = 64;
    /** Signed distance returned where nothing is near. */
    public static final double SOLID = 64;
    /** Chance that a cell under each cover holds a site (before the ground has its say). */
    static final double FOREST_CHANCE = 0.85, PLAINS_CHANCE = 0.35, DESERT_CHANCE = 0.35;
    /** Water stays at least this far above the pack's old floor: clear of the set pieces and the gate ramp. */
    static final int FLOOR_CLEARANCE = 64;
    /** A chamber's ceiling stays this far under the lowest ground over it (oasis hollows: {@link #OASIS_ROOF}). */
    static final double ROOF_MIN = 4, ROOF_MAX = 6;
    static final double OASIS_ROOF_MIN = 7, OASIS_ROOF_MAX = 10;
    /** No column's ceiling comes closer to its own ground than this (oasis hollows: 7, so sand rests on sandstone). */
    static final int COLUMN_ROOF = 4, OASIS_COLUMN_ROOF = 7;
    /** The frozen part of a cold Root Vault cluster: within this of a mouth's upper half, or of a skylight. */
    static final double FROZEN_REACH = 10, SKYLIGHT_REACH = 8;

    /** The kind of site, from the cover over it. */
    public enum Kind { VAULTS, SINKHOLE, OASIS }

    /** The biome an entrance gives the blocks round it. */
    public enum Area { ROOTVAULTS, FROZEN_ROOTVAULTS, SINKHOLE, OASIS_HOLLOW }

    /** What a tunnel is for. */
    public enum Way { PASSAGE, MOUTH, DESCENT, LINK }

    public final long seed;
    public final int packFloor;
    public final Overhead overhead;
    private final int minWater;
    private final Perlin wobN, floorN, ceilN, roughN, meanderN;
    private final PlantCaves plants;
    private final ConcurrentHashMap<Long, Object> sites = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Tunnel[]> tunnels = new ConcurrentHashMap<>();
    private final ThreadLocal<Col[]> cols = ThreadLocal.withInitial(() -> {
        Col[] a = new Col[1024];
        for (int i = 0; i < a.length; i++) a[i] = new Col();
        return a;
    });
    private static final Object NONE = new Object();

    public Entrances(long seed, int packFloor, Overhead overhead) {
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
        plants = new PlantCaves(seed);
    }

    // ================================================================================================ the plan

    /** One site: a cluster of chambers, a sinkhole or an oasis, inside its own cell. */
    public static final class Site {
        public final int cellX, cellZ;
        public final Kind kind;
        /** The trees over its centre (Root Vaults), {@link RootKind#NONE} otherwise. */
        public final RootKind trees;
        /** Snow falls over its centre. */
        public final boolean cold;
        /** An oasis in the badlands' red rock. */
        public final boolean red;
        public final List<Chamber> chambers;
        /** The middle of its chambers. */
        public final double cx, cz;

        Site(int cellX, int cellZ, Kind kind, RootKind trees, boolean cold, boolean red, List<Chamber> chambers) {
            this.cellX = cellX;
            this.cellZ = cellZ;
            this.kind = kind;
            this.trees = trees;
            this.cold = cold;
            this.red = red;
            this.chambers = List.copyOf(chambers);
            double x = 0, z = 0;
            for (Chamber c : chambers) {
                x += c.cx;
                z += c.cz;
            }
            this.cx = x / chambers.size();
            this.cz = z / chambers.size();
            for (int i = 0; i < chambers.size(); i++) {
                chambers.get(i).site = this;
                chambers.get(i).index = i;
            }
        }

        public String describe() {
            return switch (kind) {
                case VAULTS -> (cold ? "frozen " : "") + "Root Vaults (" + chambers.size() + " chambers under " + trees.id + " trees)";
                case SINKHOLE -> (cold ? "frozen " : "") + "sinkhole" + (chambers.size() > 1 ? " with a side grotto" : "");
                case OASIS -> (red ? "red " : "") + "oasis hollow" + (chambers.size() > 1 ? " with a second hollow" : "");
            };
        }
    }

    /**
     * One chamber. Its footprint is a wobbly ellipse turned by an angle (a ravine: a slot; a sinkhole: a round pit with a
     * talus slope along its angle).
     */
    public static final class Chamber {
        Site site;
        int index;
        public final int look, size;
        public final double cx, cz;
        final double cos, sin;
        /** Half length and half width (a ravine: half its length, and half its width where it meets the sky). */
        public final double a, b;
        /** Open blocks below this are water: its stream, pool and puddles. */
        public final double water;
        /** Hall, teal grotto and hollow: the top of the dome above the water. Ravine: the roof over its closed ends. */
        public final double height;
        /** No ceiling reaches above this (a ravine's and a sinkhole's sky aside). */
        public final double roofCap;
        /** The lowest and highest ground over its footprint. */
        public final int groundMin, groundMax;
        double rise, channel, poolU, poolV, poolR = 1, poolD, stream, step = 4;
        /** Sinkhole: rim radius, undercut radius, the lip where the undercut starts, shore width, talus half width and slope. */
        double rim, under, lip, shore, talusHalf, talusSlope, talusEnd;
        final double ox, oz;
        /** Planned skylights: {x, z}. */
        public double[][] skylights = new double[0][];
        double minX, maxX, minZ, maxZ;
        /** Walkable ways out, counted while its site's tunnels are planned (for checks). */
        int ways;

        Chamber(int look, int size, double cx, double cz, double angle, double a, double b, double water, double height,
                double roofCap, int groundMin, int groundMax, double ox, double oz) {
            this.look = look;
            this.size = size;
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
            this.ox = ox;
            this.oz = oz;
            double reach = reach(look, a, b);
            this.minX = cx - reach;
            this.maxX = cx + reach;
            this.minZ = cz - reach;
            this.maxZ = cz + reach;
        }

        public Site site() { return site; }

        /** Widens its bounding box to {@code reach} blocks round its centre (a sinkhole's talus runs out past its pit). */
        void reachTo(double reach) {
            minX = Math.min(minX, cx - reach);
            maxX = Math.max(maxX, cx + reach);
            minZ = Math.min(minZ, cz - reach);
            maxZ = Math.max(maxZ, cz + reach);
        }

        public boolean covers(double x, double z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }

        /** Along its long axis (a sinkhole: along its talus) and across it, from its centre. */
        public double along(double x, double z) { return (x - cx) * cos + (z - cz) * sin; }

        public double across(double x, double z) { return -(x - cx) * sin + (z - cz) * cos; }

        double worldX(double u, double v) { return cx + u * cos - v * sin; }

        double worldZ(double u, double v) { return cz + u * sin + v * cos; }

        /** The highest a ceiling can be over it (a ravine's or a sinkhole's sky included). */
        public double top() { return look == RAVINE || look == SINKHOLE ? groundMax + 8 : roofCap; }

        /** Its lowest open block: the bottom of its stream or pool. */
        public double bottom() { return water - (look == TEAL || look == HOLLOW || look == SINKHOLE ? poolD : 2.5); }

        /** The height of a tunnel's centre line of radius r leaving it from a bank, its floor a block over the water. */
        double bank(double r) { return water + 1 + 0.55 * r; }

        /** How far its open footprint reaches from its centre towards an angle (its wobble included). */
        double radiusAlong(double phi) { return Entrances.radiusAlong(look, a, b, Math.atan2(sin, cos), phi, under); }

        public String lookName() { return Entrances.lookName(look); }

        /** Walkable ways out of it, as planned. */
        public int ways() { return ways; }
    }

    public static String lookName(int look) {
        return switch (look) {
            case HALL -> "root hall";
            case TEAL -> "teal grotto";
            case RAVINE -> "ravine";
            case SINKHOLE -> "sinkhole";
            default -> "oasis hollow";
        };
    }

    /** How far a chamber's open space and its dressed shell can reach from its centre, wobble included. */
    static double reach(int look, double a, double b) {
        return switch (look) {
            case RAVINE -> a * 1.05 + 6;
            case SINKHOLE -> a * 1.12 + 6;
            default -> Math.max(a, b) * 1.3 + 2;
        };
    }

    /** How far an open footprint reaches from its centre towards phi, for a shape turned by alpha. */
    static double radiusAlong(int look, double a, double b, double alpha, double phi, double under) {
        double c = Math.cos(phi - alpha), s = Math.sin(phi - alpha);
        return switch (look) {
            case RAVINE -> Math.min(a * 1.05 / Math.max(1e-6, Math.abs(c)), b * 1.1 / Math.max(1e-6, Math.abs(s)));
            case SINKHOLE -> under * 1.12;
            default -> 1.14 / Math.sqrt(sq(c / a) + sq(s / b));
        };
    }

    /** A tunnel: straight runs through its points, round with a flat floor. Negative inside. */
    public static final class Tunnel {
        final double[] px, py, pz;
        final double r;
        public final Way way;
        final Site owner;
        /** The chambers it leaves and reaches (null for a mouth's or a descent's far end, which open elsewhere). */
        final Chamber from, to;
        /** A mouth's upper half, where cold air reaches: its points, or null. */
        final double[] zx, zy, zz;
        final double minX, maxX, minZ, maxZ, minY, maxY;

        Tunnel(List<double[]> pts, double r, Way way, Site owner, Chamber from, Chamber to) {
            int n = pts.size();
            px = new double[n];
            py = new double[n];
            pz = new double[n];
            for (int i = 0; i < n; i++) {
                px[i] = pts.get(i)[0];
                py[i] = pts.get(i)[1];
                pz[i] = pts.get(i)[2];
            }
            this.r = r;
            this.way = way;
            this.owner = owner;
            this.from = from;
            this.to = to;
            double x0 = 1e18, x1 = -1e18, z0 = 1e18, z1 = -1e18, y0 = 1e18, y1 = -1e18;
            for (int i = 0; i < n; i++) {
                x0 = Math.min(x0, px[i]); x1 = Math.max(x1, px[i]);
                z0 = Math.min(z0, pz[i]); z1 = Math.max(z1, pz[i]);
                y0 = Math.min(y0, py[i]); y1 = Math.max(y1, py[i]);
            }
            double m = r + (way == Way.MOUTH ? FROZEN_REACH + 2 : 6);
            minX = x0 - m; maxX = x1 + m; minZ = z0 - m; maxZ = z1 + m; minY = y0 - m; maxY = y1 + m;
            if (way == Way.MOUTH) {
                // The upper half by length along it.
                double[] cum = new double[n];
                for (int i = 1; i < n; i++) cum[i] = cum[i - 1] + Math.sqrt(sq(px[i] - px[i - 1]) + sq(py[i] - py[i - 1]) + sq(pz[i] - pz[i - 1]));
                double half = cum[n - 1] * 0.5;
                List<double[]> up = new ArrayList<>();
                for (int i = 1; i < n; i++) {
                    if (cum[i] < half) continue;
                    if (up.isEmpty()) {
                        double f = (half - cum[i - 1]) / Math.max(1e-9, cum[i] - cum[i - 1]);
                        up.add(new double[]{px[i - 1] + (px[i] - px[i - 1]) * f, py[i - 1] + (py[i] - py[i - 1]) * f, pz[i - 1] + (pz[i] - pz[i - 1]) * f});
                    }
                    up.add(new double[]{px[i], py[i], pz[i]});
                }
                zx = new double[up.size()];
                zy = new double[up.size()];
                zz = new double[up.size()];
                for (int i = 0; i < up.size(); i++) {
                    zx[i] = up.get(i)[0];
                    zy[i] = up.get(i)[1];
                    zz[i] = up.get(i)[2];
                }
            } else {
                zx = zy = zz = null;
            }
        }

        boolean covers(double x, double z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }

        /** Where it ends: {x, y, z}. */
        public double[] end() { int n = px.length - 1; return new double[]{px[n], py[n], pz[n]}; }

        /** Its path's points, from where it leaves its chamber. */
        public int points() { return px.length; }

        public double[] point(int i) { return new double[]{px[i], py[i], pz[i]}; }

        public double radius() { return r; }

        public Site owner() { return owner; }

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

        /** Distance to a mouth's upper half (infinite for other tunnels). */
        double zoneDist(double x, double y, double z) {
            if (zx == null) return 1e18;
            double best = 1e18;
            for (int i = 0; i + 1 < zx.length; i++) best = Math.min(best, segDist2(x, y, z, zx[i], zy[i], zz[i], zx[i + 1], zy[i + 1], zz[i + 1]));
            return Math.sqrt(best);
        }

        /** Points every couple of blocks along it, for checks. */
        List<double[]> samples(double spacing) {
            List<double[]> out = new ArrayList<>();
            for (int i = 0; i + 1 < px.length; i++) {
                double dx = px[i + 1] - px[i], dy = py[i + 1] - py[i], dz = pz[i + 1] - pz[i];
                int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / spacing));
                for (int s = 0; s < steps; s++) {
                    double f = s / (double) steps;
                    out.add(new double[]{px[i] + dx * f, py[i] + dy * f, pz[i] + dz * f});
                }
            }
            out.add(end());
            return out;
        }
    }

    static double segDist2(double x, double y, double z, double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double l2 = dx * dx + dy * dy + dz * dz;
        double t = l2 < 1e-9 ? 0 : ((x - ax) * dx + (y - ay) * dy + (z - az) * dz) / l2;
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return sq(x - (ax + dx * t)) + sq(y - (ay + dy * t)) + sq(z - (az + dz * t));
    }

    private static long key(int a, int b) { return ((long) a << 32) ^ (b & 0xffffffffL); }

    /** The site of a cell, or null. */
    public Site site(int cellX, int cellZ) {
        long k = key(cellX, cellZ);
        Object o = sites.get(k);
        if (o == null) {
            Site s = plan(cellX, cellZ);
            o = s != null ? s : NONE;
            Object prev = sites.putIfAbsent(k, o);
            if (prev != null) o = prev;
        }
        return o == NONE ? null : (Site) o;
    }

    /** The sites whose cells lie within {@code cells} cells of (x, z), nearest first. */
    public List<Site> near(int x, int z, int cells) {
        int ci = Math.floorDiv(x, CELL), cj = Math.floorDiv(z, CELL);
        List<Site> out = new ArrayList<>();
        for (int i = -cells; i <= cells; i++) for (int j = -cells; j <= cells; j++) {
            Site s = site(ci + i, cj + j);
            if (s != null) out.add(s);
        }
        out.sort(Comparator.comparingDouble(s -> (s.cx - x) * (s.cx - x) + (s.cz - z) * (s.cz - z)));
        return out;
    }

    /** The tunnels a site's cell owns: its passages, mouths, descents and links to the sites east and south. */
    public List<Tunnel> tunnelsOf(Site s) { return List.of(tunnels(s.cellX, s.cellZ)); }

    private Overhead.Surface surf(double x, double z) { return overhead.at((int) Math.floor(x), (int) Math.floor(z)); }

    /**
     * Why a cell has the site it has, for /remix entrances: its centre's cover, then "wet", "mixed" (too little of the
     * ground round it shares that cover), "chance" (the roll said no), "refused" (the planner found no room) or "built".
     */
    public String[] why(int ci, int cj) {
        long h = Hashing.mix(seed, 7101, ci, cj);
        double cx = ci * (double) CELL + Hashing.range(Hashing.mix(h, 1, 0, 0), 18, CELL - 18);
        double cz = cj * (double) CELL + Hashing.range(Hashing.mix(h, 2, 0, 0), 18, CELL - 18);
        Overhead.Surface s0 = surf(cx, cz);
        String cover = s0.cover().name().toLowerCase(java.util.Locale.ROOT) + (s0.cold() ? " (cold)" : "");
        if (s0.wet()) return new String[]{cover, "wet"};
        if (s0.cover() == Overhead.Cover.OTHER) return new String[]{cover, "other"};
        int same = 1;
        for (int k = 0; k < 8; k++) {
            double t = k * Math.PI / 4;
            Overhead.Surface s = surf(cx + 20 * Math.cos(t), cz + 20 * Math.sin(t));
            if (s.wet()) return new String[]{cover, "wet"};
            if (s.cover() == s0.cover()) same++;
        }
        double roll = Hashing.unit(Hashing.mix(h, 3, 0, 0));
        int need = 6;
        double chance = switch (s0.cover()) {
            case FOREST -> FOREST_CHANCE;
            case PLAINS -> PLAINS_CHANCE;
            default -> DESERT_CHANCE;
        };
        if (same < need) return new String[]{cover, "mixed"};
        if (roll >= chance) return new String[]{cover, "chance"};
        return new String[]{cover, site(ci, cj) == null ? "refused" : "built"};
    }

    private Site plan(int ci, int cj) {
        long h = Hashing.mix(seed, 7101, ci, cj);
        double cx = ci * (double) CELL + Hashing.range(Hashing.mix(h, 1, 0, 0), 18, CELL - 18);
        double cz = cj * (double) CELL + Hashing.range(Hashing.mix(h, 2, 0, 0), 18, CELL - 18);
        Overhead.Surface s0 = surf(cx, cz);
        if (s0.wet() || s0.cover() == Overhead.Cover.OTHER) return null;
        // What covers the ground round the centre: most of it must match.
        int same = 1;
        for (int k = 0; k < 8; k++) {
            double t = k * Math.PI / 4;
            Overhead.Surface s = surf(cx + 20 * Math.cos(t), cz + 20 * Math.sin(t));
            if (s.wet()) return null;
            if (s.cover() == s0.cover()) same++;
        }
        double roll = Hashing.unit(Hashing.mix(h, 3, 0, 0));
        return switch (s0.cover()) {
            case FOREST -> same >= 6 && roll < FOREST_CHANCE ? planVaults(ci, cj, h, cx, cz, s0) : null;
            case PLAINS -> same >= 6 && roll < PLAINS_CHANCE ? planSinkhole(ci, cj, h, cx, cz, s0) : null;
            case DESERT, BADLANDS -> same >= 6 && roll < DESERT_CHANCE ? planOasis(ci, cj, h, cx, cz, s0) : null;
            default -> null;
        };
    }

    // ------------------------------------------------------------------------------------------------ Root Vaults

    private Site planVaults(int ci, int cj, long h, double cx, double cz, Overhead.Surface s0) {
        double nr = Hashing.unit(Hashing.mix(h, 10, 0, 0));
        int n = nr < 0.20 ? 2 : nr < 0.55 ? 3 : nr < 0.85 ? 4 : 5;
        List<Chamber> chs = new ArrayList<>();
        for (int attempt = 0; attempt < 6 && chs.isEmpty(); attempt++) {
            long hk = Hashing.mix(h, 11, attempt, 0);
            int size = Hashing.unit(Hashing.mix(hk, 1, 0, 0)) < 0.55 ? MEDIUM : LARGE;
            if (attempt >= 3) size = MEDIUM;
            int look = vaultLook(hk);
            double angle = Hashing.range(Hashing.mix(hk, 2, 0, 0), 0, Math.PI);
            double[] ab = size(look, size, hk);
            double m = Math.max(ab[0], ab[1]) * 1.14 + 6;
            double x = clamp(cx, ci * (double) CELL + m, (ci + 1) * (double) CELL - m);
            double z = clamp(cz, cj * (double) CELL + m, (cj + 1) * (double) CELL - m);
            Chamber c = vaultChamber(look, size, hk, x, z, angle, ab);
            if (c != null) chs.add(c);
        }
        if (chs.isEmpty()) return null;
        for (int k = 1; k < n; k++) {
            for (int attempt = 0; attempt < 12; attempt++) {
                long hk = Hashing.mix(h, 20 + k, attempt, 0);
                Chamber parent = chs.get((int) (Hashing.unit(Hashing.mix(hk, 1, 0, 0)) * chs.size()));
                double sr = Hashing.unit(Hashing.mix(hk, 2, 0, 0));
                int size = sr < 0.4 ? SMALL : sr < 0.8 ? MEDIUM : LARGE;
                int look = vaultLook(hk);
                double angle = Hashing.range(Hashing.mix(hk, 3, 0, 0), 0, Math.PI);
                double phi = Hashing.range(Hashing.mix(hk, 4, 0, 0), 0, Math.PI * 2);
                double gap = Hashing.range(Hashing.mix(hk, 5, 0, 0), 3, 9);
                Chamber c = beside(parent, chs, look, size, hk, angle, phi, gap, ci, cj, true);
                if (c != null) {
                    chs.add(c);
                    break;
                }
            }
        }
        return new Site(ci, cj, Kind.VAULTS, s0.trees(), s0.cold(), false, chs);
    }

    private static int vaultLook(long hk) {
        double lr = Hashing.unit(Hashing.mix(hk, 9, 0, 0));
        return lr < 0.45 ? HALL : lr < 0.80 ? TEAL : RAVINE;
    }

    /**
     * A chamber beside a parent, {@code gap} blocks from wall to wall towards phi, inside the cell less 4 blocks and at
     * least 3 blocks from every other chamber; or null.
     */
    private Chamber beside(Chamber parent, List<Chamber> others, int look, int size, long hk, double angle, double phi, double gap,
                           int ci, int cj, boolean vault) {
        if (look == RAVINE && size == SMALL) look = HALL;
        double[] ab = size(look, size, hk);
        double rNew = radiusAlong(look, ab[0], ab[1], angle, phi + Math.PI, 0);
        double dist = parent.radiusAlong(phi) + rNew + gap;
        double x = parent.cx + Math.cos(phi) * dist, z = parent.cz + Math.sin(phi) * dist;
        double m = (look == RAVINE ? ab[0] * 1.05 : Math.max(ab[0], ab[1]) * 1.14) + 4;
        if (x - m < ci * (double) CELL || x + m > (ci + 1) * (double) CELL || z - m < cj * (double) CELL || z + m > (cj + 1) * (double) CELL)
            return null;
        Chamber c = vault ? vaultChamber(look, size, hk, x, z, angle, ab) : hollowChamber(size, hk, x, z, angle, ab, false);
        if (c == null) return null;
        for (Chamber o : others) if (wallGap(c, o) < 3) return null;
        return c;
    }

    /** The rock between two chambers along the line through their centres. */
    static double wallGap(Chamber p, Chamber q) {
        double dx = q.cx - p.cx, dz = q.cz - p.cz, d = Math.hypot(dx, dz);
        double phi = Math.atan2(dz, dx);
        return d - p.radiusAlong(phi) - q.radiusAlong(phi + Math.PI);
    }

    /** Half length and half width by look and size. */
    private static double[] size(int look, int size, long h) {
        double a, b;
        long ha = Hashing.mix(h, 3, 1, 0), hb = Hashing.mix(h, 4, 1, 0);
        switch (size) {
            case SMALL -> { a = Hashing.range(ha, 4.5, 6.5); b = Hashing.range(hb, 3.5, Math.min(5, a)); }
            case MEDIUM -> { a = Hashing.range(ha, 7, 11); b = Hashing.range(hb, 5, Math.max(5.2, Math.min(8, a * 0.85))); }
            default -> { a = Hashing.range(ha, 11.5, 16); b = Hashing.range(hb, 8, Math.max(8.2, Math.min(11.5, a * 0.85))); }
        }
        if (look == TEAL || look == HOLLOW) b = Math.min(a, b * 1.15);
        if (look == RAVINE) {
            a = Math.min(22, a * 1.4);
            b = clamp(b * 0.8, 4.5, 9);
        }
        return new double[]{a, b};
    }

    /** The ground over a footprint: {lowest, highest, 1 if any of it is wet, 1 if a ravine may open over all of it}. */
    private int[] groundOver(double cx, double cz, double a, double b, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        Overhead.Surface s0 = surf(cx, cz);
        int lo = s0.y(), hi = s0.y(), wet = s0.wet() ? 1 : 0, ravine = s0.ravineOk() ? 1 : 0;
        for (int k = 0; k < 12; k++) {
            double t = k * Math.PI / 6;
            for (double f : new double[]{0.55, 1.0}) {
                double u = (a + 2) * f * Math.cos(t), v = (b + 2) * f * Math.sin(t);
                Overhead.Surface s = surf(cx + u * cos - v * sin, cz + u * sin + v * cos);
                lo = Math.min(lo, s.y());
                hi = Math.max(hi, s.y());
                if (s.wet()) wet = 1;
                if (!s.ravineOk()) ravine = 0;
            }
        }
        return new int[]{lo, hi, wet, ravine};
    }

    private Chamber vaultChamber(int look, int size, long h, double cx, double cz, double angle, double[] ab) {
        double a = ab[0], b = ab[1];
        int[] g = groundOver(cx, cz, a, b, angle);
        if (g[2] != 0) return null;
        if (look == RAVINE && (g[3] == 0 || g[1] - g[0] > 8)) {
            // No sky to open to here: a root hall instead, over the same ground.
            look = HALL;
            ab = size(HALL, size, Hashing.mix(h, 77, 0, 0));
            a = ab[0];
            b = ab[1];
            g = groundOver(cx, cz, a, b, angle);
            if (g[2] != 0) return null;
        }
        int gMin = g[0], gMax = g[1];
        if (gMax - gMin > 10) return null;
        double water, height, roofCap;
        if (look == RAVINE) {
            water = Math.floor(gMin - Hashing.range(Hashing.mix(h, 9, 0, 0), 13, 19));
            height = Math.min(Hashing.range(Hashing.mix(h, 10, 0, 0), 8, 12), gMin - 5 - water);
            roofCap = gMax + 8;
        } else {
            double[] hr = size == SMALL ? new double[]{5, 7} : size == MEDIUM ? new double[]{6, 10} : new double[]{8, 13};
            height = Hashing.range(Hashing.mix(h, 9, 0, 0), hr[0], hr[1]);
            if (look == TEAL) height *= 0.9;
            roofCap = gMin - Hashing.range(Hashing.mix(h, 10, 0, 0), ROOF_MIN, ROOF_MAX);
            water = Math.floor(roofCap - 2 - height - Hashing.range(Hashing.mix(h, 12, 0, 0), 0, 2));
        }
        if (water < minWater) return null;
        double ox = Hashing.range(Hashing.mix(h, 40, 0, 0), 0, 4096), oz = Hashing.range(Hashing.mix(h, 41, 0, 0), 0, 4096);
        Chamber c = new Chamber(look, size, cx, cz, angle, a, b, water, height, roofCap, gMin, gMax, ox, oz);
        double sr = Hashing.unit(Hashing.mix(h, 30, 0, 0));
        int sky = 0;
        switch (look) {
            case HALL -> {
                c.rise = size == SMALL ? Hashing.range(Hashing.mix(h, 11, 0, 0), 1.5, 3) : Hashing.range(Hashing.mix(h, 11, 0, 0), 2.5, size == LARGE ? 6 : 4.5);
                c.channel = Math.min(Hashing.range(Hashing.mix(h, 13, 0, 0), 1.4, 2.4), b * 0.3);
                sky = sr < 0.25 ? 0 : (sr < 0.75 || size == SMALL ? 1 : 2);
            }
            case TEAL -> {
                c.rise = Hashing.range(Hashing.mix(h, 11, 0, 0), size == SMALL ? 1.5 : 2, size == SMALL ? 3 : 4);
                c.poolU = (Hashing.unit(Hashing.mix(h, 14, 0, 0)) < 0.5 ? -1 : 1) * Hashing.range(Hashing.mix(h, 15, 0, 0), 0.15, 0.4) * a;
                c.poolV = Hashing.range(Hashing.mix(h, 16, 0, 0), -0.2, 0.2) * b;
                c.poolR = Hashing.range(Hashing.mix(h, 17, 0, 0), 0.5, 0.65) * b;
                c.poolD = Hashing.range(Hashing.mix(h, 18, 0, 0), 2, 3);
                sky = sr < 0.6 ? 0 : 1;
            }
            default -> {
                c.stream = Hashing.range(Hashing.mix(h, 19, 0, 0), 1.2, 2.0);
                c.step = Hashing.range(Hashing.mix(h, 20, 0, 0), 3.5, 5.0);
            }
        }
        c.skylights = skylights(c, h, sky);
        return c;
    }

    private static double[][] skylights(Chamber c, long h, int n) {
        double[][] out = new double[n][];
        for (int k = 0; k < n; k++) {
            double rr = Hashing.range(Hashing.mix(h, 31 + k, 1, 0), 0.1, 0.5), th = Hashing.range(Hashing.mix(h, 31 + k, 2, 0), 0, Math.PI * 2);
            double u = c.a * rr * Math.cos(th), v = c.b * rr * Math.sin(th);
            out[k] = new double[]{c.worldX(u, v), c.worldZ(u, v)};
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ sinkholes

    /** How far a sinkhole talus's line may run past the edge of its pit's cell. */
    static final double TALUS_OVER = 8;

    /**
     * How far a sinkhole's talus runs from the pit's centre along phi until its floor, rising from the shore at its
     * slope, meets the ground, plus two blocks; -1 when it crosses water or ground under sea level, or would not meet
     * the ground before running {@link #TALUS_OVER} blocks past the pit's cell.
     */
    private double talusLength(int ci, int cj, double cx, double cz, double phi, double water, double shoreOut, double slope) {
        double cos = Math.cos(phi), sin = Math.sin(phi);
        for (double u = shoreOut; ; u += 1) {
            double x = cx + cos * u, z = cz + sin * u;
            if (x < ci * CELL - TALUS_OVER || x > (ci + 1) * CELL + TALUS_OVER || z < cj * CELL - TALUS_OVER || z > (cj + 1) * CELL + TALUS_OVER)
                return -1;
            Overhead.Surface su = surf(x, z);
            if (su.wet() || su.y() < overhead.seaLevel()) return -1;
            if (water + 1 + (u - shoreOut) * slope >= su.y() + 0.5) return u + 2;
        }
    }

    private Site planSinkhole(int ci, int cj, long h, double cx, double cz, Overhead.Surface s0) {
        double rim = Hashing.range(Hashing.mix(h, 10, 0, 0), 5, 8.5);
        double under = rim * Hashing.range(Hashing.mix(h, 11, 0, 0), 1.15, 1.45);
        double angle = Hashing.range(Hashing.mix(h, 12, 0, 0), 0, Math.PI * 2);
        double m = under * 1.12 + 6;
        cx = clamp(cx, ci * (double) CELL + m, (ci + 1) * (double) CELL - m);
        cz = clamp(cz, cj * (double) CELL + m, (cj + 1) * (double) CELL - m);
        int[] g = groundOver(cx, cz, under, under, 0);
        if (g[2] != 0 || g[1] - g[0] > 8 || g[0] < overhead.seaLevel()) return null; // a pit under sea level floods
        double depth = Hashing.range(Hashing.mix(h, 13, 0, 0), 12, 20);
        double water = Math.floor(g[0] - depth);
        if (water < minWater) return null;
        double poolR = under * 0.75, shore = Hashing.range(Hashing.mix(h, 14, 0, 0), 1.0, 2.0);
        double talusHalf = Hashing.range(Hashing.mix(h, 16, 0, 0), 3.0, 4.5);
        double talusSlope = Hashing.range(Hashing.mix(h, 17, 0, 0), 0.55, 0.68);
        // The talus runs out from the shore until its floor meets the ground, walked along its line so that rising
        // ground is met where it rises (a trench that ends in a wall cannot be climbed out of); over no water, and not
        // far into the next cell, whose chambers know nothing of it. The planned direction first, then those round it.
        double talusLength = -1;
        for (int k = 0; k < 8 && talusLength < 0; k++) {
            double phi = angle + (k % 2 == 0 ? 1 : -1) * ((k + 1) / 2) * Math.PI / 4;
            talusLength = talusLength(ci, cj, cx, cz, phi, water, poolR + shore, talusSlope);
            if (talusLength >= 0) angle = phi;
        }
        if (talusLength < 0) return null;
        double ox = Hashing.range(Hashing.mix(h, 40, 0, 0), 0, 4096), oz = Hashing.range(Hashing.mix(h, 41, 0, 0), 0, 4096);
        Chamber pit = new Chamber(SINKHOLE, LARGE, cx, cz, angle, under, under, water, depth, g[1] + 8, g[0], g[1], ox, oz);
        pit.rim = rim;
        pit.under = under;
        pit.lip = g[0] - depth / 3;
        pit.poolR = poolR;
        pit.shore = shore;
        pit.poolD = Hashing.range(Hashing.mix(h, 15, 0, 0), 2, 4);
        pit.talusHalf = talusHalf;
        pit.talusSlope = talusSlope;
        pit.talusEnd = talusLength;
        pit.reachTo(talusLength + pit.talusHalf * 1.2 + 2);
        List<Chamber> chs = new ArrayList<>();
        chs.add(pit);
        // Now and then a small grotto off the shore, away from the talus, its pool a little above the sinkhole's.
        if (Hashing.unit(Hashing.mix(h, 18, 0, 0)) < 0.5) {
            for (int attempt = 0; attempt < 6; attempt++) {
                long hk = Hashing.mix(h, 19, attempt, 0);
                double phi = angle + Math.PI + Hashing.range(Hashing.mix(hk, 1, 0, 0), -1.9, 1.9);
                double side = Hashing.range(Hashing.mix(hk, 2, 0, 0), 0, Math.PI);
                double[] ab = size(TEAL, SMALL, hk);
                double rNew = radiusAlong(TEAL, ab[0], ab[1], side, phi + Math.PI, 0);
                double dist = pit.radiusAlong(phi) + rNew + Hashing.range(Hashing.mix(hk, 3, 0, 0), 3, 5);
                double x = cx + Math.cos(phi) * dist, z = cz + Math.sin(phi) * dist;
                double mm = Math.max(ab[0], ab[1]) * 1.14 + 4;
                if (x - mm < ci * (double) CELL || x + mm > (ci + 1) * (double) CELL || z - mm < cj * (double) CELL || z + mm > (cj + 1) * (double) CELL)
                    continue;
                int[] gs = groundOver(x, z, ab[0], ab[1], side);
                if (gs[2] != 0) continue;
                double w = water + 1, roofCap = Math.min(gs[0] - ROOF_MIN, w + 2 + 7);
                double height = roofCap - 2 - w;
                if (height < 4) continue;
                Chamber c = new Chamber(TEAL, SMALL, x, z, side, ab[0], ab[1], w, height, roofCap, gs[0], gs[1],
                        Hashing.range(Hashing.mix(hk, 40, 0, 0), 0, 4096), Hashing.range(Hashing.mix(hk, 41, 0, 0), 0, 4096));
                c.rise = Hashing.range(Hashing.mix(hk, 11, 0, 0), 1.5, 3);
                c.poolU = Hashing.range(Hashing.mix(hk, 15, 0, 0), -0.3, 0.3) * c.a;
                c.poolR = Hashing.range(Hashing.mix(hk, 17, 0, 0), 0.45, 0.6) * c.b;
                c.poolD = Hashing.range(Hashing.mix(hk, 18, 0, 0), 1.5, 2.5);
                if (wallGap(c, pit) < 3) continue;
                chs.add(c);
                break;
            }
        }
        return new Site(ci, cj, Kind.SINKHOLE, RootKind.NONE, s0.cold(), false, chs);
    }

    // ------------------------------------------------------------------------------------------------ oasis hollows

    private Site planOasis(int ci, int cj, long h, double cx, double cz, Overhead.Surface s0) {
        boolean red = s0.cover() == Overhead.Cover.BADLANDS;
        List<Chamber> chs = new ArrayList<>();
        for (int attempt = 0; attempt < 4 && chs.isEmpty(); attempt++) {
            long hk = Hashing.mix(h, 11, attempt, 0);
            double a = Hashing.range(Hashing.mix(hk, 1, 0, 0), attempt < 2 ? 11 : 9, attempt < 2 ? 18 : 13);
            double b = Hashing.range(Hashing.mix(hk, 2, 0, 0), 8, Math.max(8.2, Math.min(13, a * 0.85)));
            double angle = Hashing.range(Hashing.mix(hk, 3, 0, 0), 0, Math.PI);
            double m = Math.max(a, b) * 1.14 + 6;
            double x = clamp(cx, ci * (double) CELL + m, (ci + 1) * (double) CELL - m);
            double z = clamp(cz, cj * (double) CELL + m, (cj + 1) * (double) CELL - m);
            Chamber c = hollowChamber(LARGE, hk, x, z, angle, new double[]{a, b}, true);
            if (c != null) chs.add(c);
        }
        if (chs.isEmpty()) return null;
        if (Hashing.unit(Hashing.mix(h, 12, 0, 0)) < 0.6) {
            Chamber main = chs.get(0);
            for (int attempt = 0; attempt < 10; attempt++) {
                long hk = Hashing.mix(h, 13, attempt, 0);
                int size = Hashing.unit(Hashing.mix(hk, 1, 0, 0)) < 0.5 ? SMALL : MEDIUM;
                double angle = Hashing.range(Hashing.mix(hk, 3, 0, 0), 0, Math.PI);
                double phi = Hashing.range(Hashing.mix(hk, 4, 0, 0), 0, Math.PI * 2);
                double gap = Hashing.range(Hashing.mix(hk, 5, 0, 0), 3, 7);
                Chamber c = beside(main, chs, HOLLOW, size, hk, angle, phi, gap, ci, cj, false);
                if (c != null) {
                    chs.add(c);
                    break;
                }
            }
        }
        return new Site(ci, cj, Kind.OASIS, RootKind.NONE, s0.cold(), red, chs);
    }

    /** A sandstone hollow round a spring pool; the main one gets a skylight over its pool. */
    private Chamber hollowChamber(int size, long h, double cx, double cz, double angle, double[] ab, boolean main) {
        double a = ab[0], b = ab[1];
        int[] g = groundOver(cx, cz, a, b, angle);
        if (g[2] != 0 || g[1] - g[0] > 12) return null;
        double height = main ? Hashing.range(Hashing.mix(h, 9, 0, 0), 8, 12) : Hashing.range(Hashing.mix(h, 9, 0, 0), 6, 9);
        double roofCap = g[0] - Hashing.range(Hashing.mix(h, 10, 0, 0), OASIS_ROOF_MIN, OASIS_ROOF_MAX);
        double water = Math.floor(roofCap - 2 - height);
        if (water < minWater) return null;
        Chamber c = new Chamber(HOLLOW, size, cx, cz, angle, a, b, water, height, roofCap, g[0], g[1],
                Hashing.range(Hashing.mix(h, 40, 0, 0), 0, 4096), Hashing.range(Hashing.mix(h, 41, 0, 0), 0, 4096));
        c.rise = Hashing.range(Hashing.mix(h, 11, 0, 0), 2, 4);
        c.poolU = (Hashing.unit(Hashing.mix(h, 14, 0, 0)) < 0.5 ? -1 : 1) * Hashing.range(Hashing.mix(h, 15, 0, 0), 0.15, 0.35) * a;
        c.poolV = Hashing.range(Hashing.mix(h, 16, 0, 0), -0.2, 0.2) * b;
        c.poolR = Hashing.range(Hashing.mix(h, 17, 0, 0), 0.35, 0.5) * b;
        c.poolD = Hashing.range(Hashing.mix(h, 18, 0, 0), 2, 3);
        if (main) c.skylights = new double[][]{{c.worldX(c.poolU, c.poolV), c.worldZ(c.poolU, c.poolV)}};
        return c;
    }

    // ------------------------------------------------------------------------------------------------ tunnels

    /**
     * The tunnels a cell owns. A site's chambers are joined by passages (a spanning tree, then the shortest other pairs
     * until each chamber has two ways out); one or two mouths lead up to the surface (none from a sinkhole, whose talus is
     * its way out); one or two descents lead down into the pack's caves; links join the site to the sites east and south
     * of it. A chamber still short of two ways out gets another descent, or another passage.
     */
    private Tunnel[] tunnels(int ci, int cj) {
        long k = key(ci, cj);
        Tunnel[] t = tunnels.get(k);
        if (t != null) return t;
        List<Tunnel> out = new ArrayList<>(8);
        Site s = site(ci, cj);
        if (s != null) planTunnels(s, out);
        t = out.toArray(new Tunnel[0]);
        Tunnel[] prev = tunnels.putIfAbsent(k, t);
        return prev != null ? prev : t;
    }

    private void planTunnels(Site s, List<Tunnel> out) {
        long h = Hashing.mix(seed, 7102, s.cellX, s.cellZ);
        List<Chamber> chs = s.chambers;
        int n = chs.size();
        int[] ways = new int[n];
        boolean[][] joined = new boolean[n][n];
        double[] usedPhi = new double[n];
        java.util.Arrays.fill(usedPhi, Double.NaN);

        // Passages: a spanning tree over the walls' gaps, then the leaves' nearest other neighbours.
        List<int[]> pairs = new ArrayList<>();
        boolean[] in = new boolean[n];
        in[0] = true;
        for (int added = 1; added < n; added++) {
            int bi = -1, bj = -1;
            double best = 1e18;
            for (int i = 0; i < n; i++) {
                if (!in[i]) continue;
                for (int j = 0; j < n; j++) {
                    if (in[j]) continue;
                    double d = wallGap(chs.get(i), chs.get(j));
                    if (d < best) { best = d; bi = i; bj = j; }
                }
            }
            in[bj] = true;
            pairs.add(new int[]{bi, bj});
        }
        int[] degree = new int[n];
        for (int[] p : pairs) { degree[p[0]]++; degree[p[1]]++; }
        if (n >= 3) {
            for (int i = 0; i < n; i++) {
                if (degree[i] != 1) continue;
                int bj = -1;
                double best = 1e18;
                for (int j = 0; j < n; j++) {
                    if (j == i || linked(pairs, i, j)) continue;
                    double d = wallGap(chs.get(i), chs.get(j));
                    if (d < best) { best = d; bj = j; }
                }
                if (bj >= 0 && best < 24) {
                    pairs.add(new int[]{i, bj});
                    degree[i]++;
                    degree[bj]++;
                }
            }
        }
        int pi = 0;
        for (int[] p : pairs) {
            Tunnel tn = passage(chs.get(p[0]), chs.get(p[1]), Hashing.mix(h, 100, pi++, 0), 2, 0.6, Way.PASSAGE);
            if (tn != null && clear(tn, chs.get(p[0]), chs.get(p[1]))) {
                out.add(tn);
                ways[p[0]]++;
                ways[p[1]]++;
                joined[p[0]][p[1]] = joined[p[1]][p[0]] = true;
            }
        }

        // Mouths up to the surface, from the chambers farthest out first.
        if (s.kind == Kind.SINKHOLE) {
            ways[0]++; // its talus
        } else {
            int mouths = n >= 4 || Hashing.unit(Hashing.mix(h, 200, 0, 0)) < 0.3 ? 2 : 1;
            Integer[] order = byDistanceFromMiddle(s, false);
            double firstPhi = Double.NaN;
            int firstFrom = -1;
            for (int m = 0, tried = 0; m < mouths && tried < order.length * 2; tried++) {
                int ci = order[tried % order.length];
                if (m == 1 && ci == firstFrom && n > 1) continue;
                Tunnel tn = mouth(s, chs.get(ci), Hashing.mix(h, 210, tried, 0), firstPhi);
                if (tn == null || !clear(tn, chs.get(ci), null)) continue;
                out.add(tn);
                ways[ci]++;
                usedPhi[ci] = Math.atan2(tn.pz[1] - chs.get(ci).cz, tn.px[1] - chs.get(ci).cx);
                if (m == 0) {
                    firstPhi = Math.atan2(tn.pz[tn.pz.length - 1] - s.cz, tn.px[tn.px.length - 1] - s.cx);
                    firstFrom = ci;
                }
                m++;
            }
        }

        // Descents into the pack's caves, from the lowest chambers.
        int descents = s.kind == Kind.SINKHOLE ? (Hashing.unit(Hashing.mix(h, 300, 0, 0)) < 0.5 ? 2 : 1)
                : s.kind == Kind.OASIS ? (Hashing.unit(Hashing.mix(h, 300, 0, 0)) < 0.5 ? 2 : 1)
                : n >= 3 ? 2 : 1;
        Integer[] low = new Integer[n];
        for (int i = 0; i < n; i++) low[i] = i;
        java.util.Arrays.sort(low, Comparator.comparingDouble(i -> chs.get(i).water));
        for (int d = 0, tried = 0; d < descents && tried < n + 2; tried++) {
            int ci = low[tried % n];
            Tunnel tn = descent(chs.get(ci), Hashing.mix(h, 310, tried, 0), usedPhi[ci]);
            if (tn == null) continue;
            out.add(tn);
            ways[ci]++;
            usedPhi[ci] = Math.atan2(tn.pz[1] - chs.get(ci).cz, tn.px[1] - chs.get(ci).cx);
            d++;
        }

        // Links to the sites east and south (this cell owns them), and those from the west and north (counted only).
        Site e = site(s.cellX + 1, s.cellZ), so = site(s.cellX, s.cellZ + 1), w = site(s.cellX - 1, s.cellZ), nn = site(s.cellX, s.cellZ - 1);
        Tunnel le = e != null ? link(s, e, 1) : null, ls = so != null ? link(s, so, 2) : null;
        if (le != null) { out.add(le); ways[le.from.index]++; }
        if (ls != null) { out.add(ls); ways[ls.from.index]++; }
        Tunnel lw = w != null ? link(w, s, 1) : null, ln = nn != null ? link(nn, s, 2) : null;
        if (lw != null) ways[lw.to.index]++;
        if (ln != null) ways[ln.to.index]++;

        // Any chamber still short of two ways out: another descent, else a passage to a chamber it is not joined to.
        for (int i = 0; i < n; i++) {
            for (int attempt = 0; ways[i] < 2 && attempt < 3; attempt++) {
                Tunnel tn = descent(chs.get(i), Hashing.mix(h, 400 + i, attempt, 0), usedPhi[i]);
                if (tn == null) continue;
                out.add(tn);
                ways[i]++;
                usedPhi[i] = Math.atan2(tn.pz[1] - chs.get(i).cz, tn.px[1] - chs.get(i).cx);
            }
            if (ways[i] >= 2) continue;
            Integer[] others = new Integer[n];
            for (int j = 0; j < n; j++) others[j] = j;
            final int fi = i;
            java.util.Arrays.sort(others, Comparator.comparingDouble(j -> j == fi ? 1e18 : wallGap(chs.get(fi), chs.get(j))));
            for (int j : others) {
                if (j == i || joined[i][j] || ways[i] >= 2) continue;
                Tunnel tn = passage(chs.get(i), chs.get(j), Hashing.mix(h, 500 + i, j, 0), 2, 0.7, Way.PASSAGE);
                if (tn == null || !clear(tn, chs.get(i), chs.get(j))) continue;
                out.add(tn);
                ways[i]++;
                ways[j]++;
                joined[i][j] = joined[j][i] = true;
            }
        }
        for (int i = 0; i < n; i++) chs.get(i).ways = ways[i];
    }

    private static boolean linked(List<int[]> pairs, int i, int j) {
        for (int[] p : pairs) if ((p[0] == i && p[1] == j) || (p[0] == j && p[1] == i)) return true;
        return false;
    }

    /** The chamber indices of a site, farthest from (or nearest to) its middle first. */
    private static Integer[] byDistanceFromMiddle(Site s, boolean nearest) {
        int n = s.chambers.size();
        Integer[] o = new Integer[n];
        for (int i = 0; i < n; i++) o[i] = i;
        java.util.Arrays.sort(o, Comparator.comparingDouble(i -> {
            Chamber c = s.chambers.get(i);
            double d = Math.hypot(c.cx - s.cx, c.cz - s.cz) + c.radiusAlong(Math.atan2(c.cz - s.cz, c.cx - s.cx));
            return nearest ? d : -d;
        }));
        return o;
    }

    /**
     * A tunnel from bank to bank between two chambers, or null where it would be too steep. It leaves each chamber
     * level, its floor a block above that chamber's water, until it is clear of the chamber's wall, and climbs or drops
     * only in between, bent sideways when the drop is steep, so neither drains.
     */
    private Tunnel passage(Chamber a, Chamber b, long h, double cover, double maxSlope, Way way) {
        double r = Hashing.range(Hashing.mix(h, 1, 0, 0), 1.8, 2.5);
        double ya = a.bank(r), yb = b.bank(r);
        double phi = Math.atan2(b.cz - a.cz, b.cx - a.cx);
        double ux = Math.cos(phi), uz = Math.sin(phi);
        double ra = a.radiusAlong(phi), rb = b.radiusAlong(phi + Math.PI);
        // Level out of each chamber until clear of its wall, but no farther than halfway across the rock between them.
        double gap = Math.max(0, Math.hypot(b.cx - a.cx, b.cz - a.cz) - ra - rb), out = Math.min(r + 1.5, gap * 0.5);
        double sax = a.cx + ux * ra * 0.55, saz = a.cz + uz * ra * 0.55;
        double eax = a.cx + ux * (ra + out), eaz = a.cz + uz * (ra + out);
        double sbx = b.cx - ux * rb * 0.55, sbz = b.cz - uz * rb * 0.55;
        double ebx = b.cx - ux * (rb + out), ebz = b.cz - uz * (rb + out);
        double between = Math.max(0, gap - 2 * out);
        double drop = Math.abs(yb - ya);
        double side = Hashing.range(Hashing.mix(h, 2, 0, 0), -4, 4);
        if (drop > maxSlope * Math.max(between, 1)) {
            double need = drop / maxSlope;
            double s = 0.5 * Math.sqrt(Math.max(0, need * need - between * between));
            if (s > 12) return null;
            side = Math.signum(side == 0 ? 1 : side) * Math.max(Math.abs(side), s);
        }
        double mx = (eax + ebx) * 0.5 - uz * side, mz = (eaz + ebz) * 0.5 + ux * side, my = (ya + yb) * 0.5;
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{sax, ya, saz});
        pts.add(new double[]{eax, ya, eaz});
        if (between > 2 || Math.abs(side) > 1) pts.add(new double[]{mx, my, mz});
        pts.add(new double[]{ebx, yb, ebz});
        pts.add(new double[]{sbx, yb, sbz});
        if (!deepEnough(pts, r, cover, 1, 1)) return null;
        return new Tunnel(pts, r, way, a.site, a, b);
    }

    /**
     * A mouth: from a bank of a chamber, level until clear of its wall, then up at a steady slope towards the lowest
     * ground round it until its floor comes out over the ground: sideways out of a hillside, or as a trench on the flat.
     */
    private Tunnel mouth(Site s, Chamber c, long h, double avoidPhi) {
        double r = Hashing.range(Hashing.mix(h, 1, 0, 0), 2.0, 2.6);
        double slope = Hashing.range(Hashing.mix(h, 2, 0, 0), 0.45, 0.6);
        double ox = c.cx - s.cx, oz = c.cz - s.cz, ol = Math.hypot(ox, oz);
        double rot = Hashing.range(Hashing.mix(h, 3, 0, 0), 0, Math.PI / 4);
        double[] score = new double[8];
        Integer[] order = new Integer[8];
        for (int k = 0; k < 8; k++) {
            order[k] = k;
            double phi = rot + k * Math.PI / 4, dx = Math.cos(phi), dz = Math.sin(phi);
            score[k] = 1e18;
            if (ol > 1 && (dx * ox + dz * oz) / ol < -0.2) continue; // not back across the cluster
            if (!Double.isNaN(avoidPhi) && angleBetween(phi, avoidPhi) < Math.PI / 2) continue;
            double rho = c.radiusAlong(phi), sum = 0;
            boolean wet = false;
            for (double d : new double[]{12, 20, 28}) {
                Overhead.Surface su = surf(c.cx + dx * (rho + d), c.cz + dz * (rho + d));
                sum += su.y();
                wet |= su.wet() || su.y() < overhead.seaLevel();
            }
            if (!wet) score[k] = sum / 3 + 4 * Hashing.unit(Hashing.mix(h, 10 + k, 0, 0));
        }
        java.util.Arrays.sort(order, Comparator.comparingDouble(k -> score[k]));
        for (int oi = 0; oi < 3; oi++) {
            int k = order[oi];
            if (score[k] >= 1e17) break;
            double phi = rot + k * Math.PI / 4;
            Tunnel t = mouthTowards(s, c, phi, r, slope, Hashing.mix(h, 20 + k, 0, 0));
            if (t != null) return t;
        }
        return null;
    }

    private Tunnel mouthTowards(Site s, Chamber c, double phi, double r, double slope, long h) {
        double rho = c.radiusAlong(phi), bank = c.bank(r);
        double ux = Math.cos(phi), uz = Math.sin(phi);
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{c.cx + ux * rho * 0.55, bank, c.cz + uz * rho * 0.55});
        double px = c.cx + ux * (rho + r + 1), pz = c.cz + uz * (rho + r + 1), y = bank;
        pts.add(new double[]{px, y, pz});
        double ang = phi, turn = Hashing.range(Hashing.mix(h, 1, 0, 0), -0.5, 0.5) / 30;
        for (int step = 1; step <= 60; step++) {
            ang += turn;
            px += Math.cos(ang);
            pz += Math.sin(ang);
            y += slope;
            Overhead.Surface su = surf(px, pz);
            if (su.wet()) return null;
            // Where it breaks the ground it opens to the sky: never below sea level, where a swamp's water stands.
            if (y + r >= su.y() - 1 && su.y() < overhead.seaLevel()) return null;
            if (y - 0.55 * r >= su.y() + 0.5) {
                // Out: carry on a few blocks over the ground so the opening is clean.
                pts.add(new double[]{px + Math.cos(ang) * 3, y + 1, pz + Math.sin(ang) * 3});
                return new Tunnel(pts, r, Way.MOUTH, s, c, null);
            }
            if (step % 10 == 0) pts.add(new double[]{px, y, pz});
        }
        return null;
    }

    /**
     * A descent: from a bank of a chamber, level until clear of its wall, then down into an open part of the pack's
     * caves found by its density: tried in eight directions, 12 to 42 blocks out and 14 to 50 under the bank, then
     * farther; scored for how open the cave is, how steep the way, and a plant cave's bonus. Null when nothing is open.
     */
    private Tunnel descent(Chamber c, long h, double avoidPhi) {
        double r = Hashing.range(Hashing.mix(h, 1, 0, 0), 1.8, 2.4);
        double bank = c.bank(r);
        double rot = Hashing.range(Hashing.mix(h, 2, 0, 0), 0, Math.PI / 4);
        double bestScore = -1e18, bx = 0, by = 0, bz = 0, bphi = 0;
        for (int pass = 0; pass < 2 && bestScore <= -1e17; pass++) {
            double[] dists = pass == 0 ? new double[]{12, 20, 30, 42} : new double[]{50, 56};
            for (int k = 0; k < 8; k++) {
                double phi = rot + k * Math.PI / 4;
                if (!Double.isNaN(avoidPhi) && angleBetween(phi, avoidPhi) < Math.PI / 3) continue;
                // Not under a sinkhole's talus, which is its way up.
                if (c.look == SINKHOLE && angleBetween(phi, Math.atan2(c.sin, c.cos)) < Math.PI / 3) continue;
                double rho = c.radiusAlong(phi);
                double ex = c.cx + Math.cos(phi) * (rho + r + 2), ez = c.cz + Math.sin(phi) * (rho + r + 2);
                for (double d : dists) {
                    double tx = ex + Math.cos(phi) * d, tz = ez + Math.sin(phi) * d;
                    int gx = (int) Math.round(tx / 4) * 4, gz = (int) Math.round(tz / 4) * 4;
                    Overhead.Surface su = overhead.at(gx, gz);
                    if (su.wet()) continue;
                    for (int dep = 14; dep <= 50 + 10 * pass; dep += 6) {
                        if (dep > 1.1 * d) break;
                        int gy = (int) Math.round((bank - dep) / 8) * 8;
                        if (gy < packFloor + 12 || gy > su.y() - 18) continue;
                        double dens = overhead.packDensity(gx, gy, gz);
                        if (dens >= -0.08) continue;
                        // A cave, not a pocket: at least two of the six corners round it are open too.
                        int open = 0;
                        if (overhead.packDensity(gx + 4, gy, gz) < 0) open++;
                        if (overhead.packDensity(gx - 4, gy, gz) < 0) open++;
                        if (overhead.packDensity(gx, gy, gz + 4) < 0) open++;
                        if (overhead.packDensity(gx, gy, gz - 4) < 0) open++;
                        if (open < 2 && overhead.packDensity(gx, gy + 8, gz) < 0) open++;
                        if (open < 2 && overhead.packDensity(gx, gy - 8, gz) < 0) open++;
                        if (open < 2) continue;
                        double sl = (bank - gy) / d;
                        double score = Math.min(0.6, -dens) * 3 + open * 0.2 - Math.abs(sl - 0.75) - d / 60
                                + (plants.growth(gx, gy, gz, su.y() - gy) > 0 ? 0.4 : 0) + 0.2 * Hashing.unit(Hashing.mix(h, k, (long) d, dep));
                        if (score > bestScore) {
                            bestScore = score;
                            bx = gx;
                            by = gy;
                            bz = gz;
                            bphi = phi;
                        }
                    }
                }
            }
        }
        if (bestScore <= -1e17) return null;
        double rho = c.radiusAlong(bphi);
        double ux = Math.cos(bphi), uz = Math.sin(bphi);
        double ex = c.cx + ux * (rho + r + 2), ez = c.cz + uz * (rho + r + 2);
        double side = Hashing.range(Hashing.mix(h, 3, 0, 0), -6, 6);
        double mx = (ex + bx) * 0.5 - uz * side, mz = (ez + bz) * 0.5 + ux * side;
        double my = (bank + by) * 0.5 + Hashing.range(Hashing.mix(h, 4, 0, 0), -2, 2);
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{c.cx + ux * rho * 0.55, bank, c.cz + uz * rho * 0.55});
        pts.add(new double[]{ex, bank, ez});
        pts.add(new double[]{mx, my, mz});
        pts.add(new double[]{bx, by, bz});
        if (slope(pts, 1) > 1.25 || slope(pts, 2) > 1.25) return null;
        if (!deepEnough(pts, r, 3, 1, 0)) return null;
        Tunnel t = new Tunnel(pts, r, Way.DESCENT, c.site, c, null);
        return clear(t, c, null) ? t : null;
    }

    /** The slope of segment i (from point i to i + 1). */
    private static double slope(List<double[]> pts, int i) {
        double[] p = pts.get(i), q = pts.get(i + 1);
        return Math.abs(q[1] - p[1]) / Math.max(1e-6, Math.hypot(q[0] - p[0], q[2] - p[2]));
    }

    /** A link from the nearest chamber of one site to the nearest of its neighbour east (dir 1) or south (dir 2). */
    private Tunnel link(Site a, Site b, int dir) {
        long h = Hashing.mix(seed, 7103 + dir, a.cellX, a.cellZ);
        if (Hashing.unit(h) >= 0.55) return null;
        Chamber ba = null, bb = null;
        double best = 1e18;
        for (Chamber p : a.chambers) for (Chamber q : b.chambers) {
            double d = wallGap(p, q);
            if (d < best) { best = d; ba = p; bb = q; }
        }
        if (ba == null || best > 60) return null;
        Tunnel t = passage(ba, bb, Hashing.mix(h, 1, 0, 0), 4, 0.7, Way.LINK);
        return t != null && clear(t, ba, bb) ? t : null;
    }

    /**
     * True when no probe of a tunnel of radius r comes within {@code cover} blocks of the ground or lies under water;
     * the first {@code skip} and last {@code skipEnd} runs (inside its chambers) are not checked.
     */
    private boolean deepEnough(List<double[]> pts, double r, double cover, int skip, int skipEnd) {
        for (int i = skip; i + 1 < pts.size() - skipEnd; i++) {
            double[] p = pts.get(i), q = pts.get(i + 1);
            double l = Math.sqrt(sq(q[0] - p[0]) + sq(q[1] - p[1]) + sq(q[2] - p[2]));
            int steps = Math.max(1, (int) Math.ceil(l / 4));
            for (int s = 0; s <= steps; s++) {
                double f = s / (double) steps;
                double x = p[0] + (q[0] - p[0]) * f, y = p[1] + (q[1] - p[1]) * f, z = p[2] + (q[2] - p[2]) * f;
                Overhead.Surface su = surf(x, z);
                if (su.wet() || y + r + 1 > su.y() - cover) return false;
            }
        }
        return true;
    }

    /**
     * True when a tunnel keeps clear of the water of every chamber but the ones it joins: nowhere within a few blocks of
     * another chamber's footprint between 4 blocks under its lowest open block and a block over its water (above that,
     * running into it does no harm).
     */
    private boolean clear(Tunnel t, Chamber a, Chamber b) {
        Site own = t.owner;
        List<double[]> pts = t.samples(2);
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            Site s = site(own.cellX + di, own.cellZ + dj);
            if (s == null) continue;
            for (Chamber o : s.chambers) {
                if (o == a || o == b) continue;
                double lo = o.bottom() - 4, hi = o.water + 1;
                for (double[] p : pts) {
                    if (p[1] + t.r < lo || p[1] - 0.55 * t.r > hi) continue;
                    double d = Math.hypot(p[0] - o.cx, p[2] - o.cz);
                    if (d > reach(o.look, o.a, o.b) + t.r + 3) continue;
                    if (d < o.radiusAlong(Math.atan2(p[2] - o.cz, p[0] - o.cx)) + t.r + 2) return false;
                }
            }
        }
        return true;
    }

    // ================================================================================================ columns

    /** Everything about one column that does not depend on y. */
    static final class Col {
        int x, z;
        boolean valid;
        int n, tn;
        final Chamber[] g = new Chamber[8];
        final double[] f = new double[8], c = new double[8], q = new double[8];
        /** How far outside the chamber's open footprint the column lies, in blocks (negative inside). */
        final double[] e = new double[8];
        /** The same for the footprint whose water is sealed in: a sinkhole's pit, without its talus. */
        final double[] s = new double[8];
        final Tunnel[] t = new Tunnel[48];
        double lo, hi;
        int ground, groundLow;
        /** A sinkhole's talus crosses the column: its rock is roughened less, so the slope stays a block a step. */
        boolean talus;
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
        c.groundLow = Integer.MIN_VALUE;
        c.talus = false;
        c.lo = 1e9;
        c.hi = -1e9;
        int ci = Math.floorDiv(x, CELL), cj = Math.floorDiv(z, CELL);
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            Site s = site(ci + di, cj + dj);
            if (s == null) continue;
            for (Chamber g : s.chambers) {
                if (c.n < c.g.length && g.covers(x, z) && shape(g, x, z, c, c.n)) {
                    c.lo = Math.min(c.lo, c.f[c.n]);
                    c.hi = Math.max(c.hi, Math.max(c.c[c.n], c.f[c.n]));
                    c.g[c.n++] = g;
                }
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

    /** The lowest ground round a column (over the 3 x 3 biome columns about it), which a ceiling may not come near. */
    private int groundLow(Col c) {
        if (c.groundLow == Integer.MIN_VALUE) {
            int lo = Integer.MAX_VALUE;
            for (int dx = -4; dx <= 4; dx += 4) for (int dz = -4; dz <= 4; dz += 4) lo = Math.min(lo, overhead.at(c.x + dx, c.z + dz).y());
            c.groundLow = lo;
        }
        return c.groundLow;
    }

    /**
     * The floor and ceiling of a chamber over a column, into slot i of the column: open where floor &lt; y &lt; ceiling.
     * False when the column lies well outside it.
     */
    private boolean shape(Chamber g, int x, int z, Col col, int i) {
        double u = g.along(x, z), v = g.across(x, z);
        double nx = x + g.ox, nz = z + g.oz;
        double f, ce, q, e, seal = Double.NaN;
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
                ce = Math.min(ce, Math.min(g.roofCap, groundLow(col) - COLUMN_ROOF));
                e = (q - 1) * Math.min(g.a, g.b);
            }
            case TEAL, HOLLOW -> {
                boolean hollow = g.look == HOLLOW;
                double wob = 1 + (hollow ? 0.10 : 0.12) * wobN.noise2(nx / 11.0, nz / 11.0);
                q = Math.sqrt(sq(u / g.a) + sq(v / g.b)) / wob;
                if (q >= 1.25) return false;
                // Terraces around a round pool: mossy in a teal grotto; a flat sandy floor, then ledges, in a hollow.
                double t = Math.max(0, (q - (hollow ? 0.6 : 0.45)) / (hollow ? 0.4 : 0.55));
                f = g.water + 1.0 + (hollow ? 0.35 : 0.7) * floorN.noise2(nx / 9.0, nz / 9.0) + Terrain.stairs(t * g.rise, 2.0, 0.5);
                double dp = Math.hypot(u - g.poolU, v - g.poolV) / (g.poolR * (1 + 0.15 * wobN.noise2(nx / 7.0, nz / 7.0 + 40)));
                if (dp < 1) f = Math.min(f, g.water + 0.3 - g.poolD * (1 - dp * dp));
                ce = g.water + g.height * Math.sqrt(Math.max(0, 1 - Math.pow(q, hollow ? 2.2 : 2.5))) + (hollow ? 1.4 : 2.2) * ceilN.noise2(nx / 5.0, nz / 5.0);
                ce = Math.min(ce, Math.min(g.roofCap, groundLow(col) - (hollow ? OASIS_COLUMN_ROOF : COLUMN_ROOF)));
                e = (q - 1) * Math.min(g.a, g.b);
            }
            case RAVINE -> {
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
                ce = open * (ground + 8) + (1 - open) * Math.min(roof, groundLow(col) - COLUMN_ROOF);
                if (q >= 1.05) ce = f - 10;
                e = Math.max(vv - half, (q - 1) * g.a);
                // Ranked for its look and site by how far along and across: beside its long side a neighbouring
                // chamber keeps its own.
                q = Math.max(q, vv / g.b);
            }
            default -> {
                // A sinkhole: a round pit open to the sky, undercut below its lip down to a shore round its pool, and a
                // talus slope down one side (along its angle) from the ground to the shore.
                double dx = x - g.cx, dz = z - g.cz, r = Math.hypot(dx, dz);
                double th = Math.atan2(dz, dx);
                double wob = 1 + 0.12 * wobN.noise2(Math.cos(th) * 1.6 + g.ox, Math.sin(th) * 1.6 + g.oz);
                double rr = r / wob;
                int ground = ground(col);
                double pr = g.poolR * (1 + 0.1 * wobN.noise2(nx / 6.0, nz / 6.0 + 30)), shoreOut = pr + g.shore;
                double half = g.talusHalf * (1 + 0.15 * wobN.noise2(u / 7.0 + g.ox, g.oz + 9));
                boolean band = u > 0 && Math.abs(v) < half;
                // The talus: up from the shore at a steady slope, small steps in it, its sides rising to the walls.
                double talus = g.water + 1 + Math.max(0, u - shoreOut) * g.talusSlope + 0.2 * floorN.noise2(u / 5.0 + g.ox, v / 5.0)
                        + 1.4 * Math.max(0, Math.abs(v) - half * 0.45);
                // (It is open to the sky from the pit on: through the lip, where the undercut would roof it.)
                boolean onTalus = band && u <= g.talusEnd && (u >= shoreOut - 1 || rr >= g.rim - 1) && talus < ground + 0.5;
                boolean inUnder = rr < g.under * 1.05;
                if (!inUnder && !onTalus && rr >= g.under * 1.05 + 4) return false;
                // The pit's floor: the pool's basin, the shore round it, and an apron rising to the walls under the lip.
                if (rr < pr) f = g.water + 0.3 - g.poolD * (1 - sq(rr / pr));
                else if (rr < shoreOut) f = g.water + 0.3 + 0.45 * floorN.noise2(nx / 5.0, nz / 5.0);
                else f = g.water + 1 + (rr - shoreOut) * 0.55 + 0.8 * floorN.noise2(nx / 5.0, nz / 5.0);
                if (rr < g.rim) {
                    ce = ground + 8;
                } else if (inUnder) {
                    double k = (rr - g.rim) / Math.max(1, g.under - g.rim);
                    ce = g.lip - k * (g.lip - (g.water + 1.5)) + 1.0 * ceilN.noise2(nx / 6.0, nz / 6.0);
                    ce = Math.min(ce, groundLow(col) - COLUMN_ROOF);
                } else {
                    ce = f - 10; // rock round the undercut, listed for the seal
                }
                e = rr - g.under;
                seal = e; // the pool's seal stays round the pit: the talus holds no water
                if (onTalus) {
                    f = u >= shoreOut ? talus : Math.min(f, talus);
                    ce = ground + 8;
                    e = Math.min(e, Math.abs(v) - half);
                    col.talus = true;
                }
                q = rr / Math.max(1, g.under);
            }
        }
        col.f[i] = f;
        col.c[i] = ce;
        col.q[i] = q;
        col.e[i] = e;
        col.s[i] = Double.isNaN(seal) ? e : seal;
        return true;
    }

    // ================================================================================================ queries

    /** Signed distance (in blocks, roughly) to the entrances' open space: negative inside, {@link #SOLID} where nothing is near. */
    public double carve(int x, int y, int z) {
        Col c = column(x, z);
        if (y < c.lo || y > c.hi) return SOLID;
        double d = SOLID;
        for (int i = 0; i < c.n; i++) d = Math.min(d, Math.max(c.f[i] - y, y - c.c[i]));
        for (int i = 0; i < c.tn; i++) d = Math.min(d, c.t[i].dist(x, y, z));
        if (d < 4 && d > -4) d += (c.talus ? 0.35 : 0.9) * roughN.noise3(x / 5.0, y / 4.0, z / 5.0);
        return d;
    }

    /** Signed distance to one chamber's own open space, without its tunnels or the roughness (for checks). */
    double shapeDist(Chamber g, int x, int y, int z) {
        Col c = new Col();
        c.x = x;
        c.z = z;
        c.ground = Integer.MIN_VALUE;
        c.groundLow = Integer.MIN_VALUE;
        if (!g.covers(x, z) || !shape(g, x, z, c, 0)) return SOLID;
        return Math.max(c.f[0] - y, y - c.c[0]);
    }

    /**
     * How deep in the rock a block lies, for dressing the entrances' shells: {@link #carve}, which measures up and down
     * its column, or one more than its nearest side neighbour's, so a wall face under a ledge counts as the surface it is.
     */
    public double shell(int x, int y, int z) {
        double d = carve(x, y, z);
        if (d <= 3) return d;
        double side = Math.min(Math.min(carve(x + 1, y, z), carve(x - 1, y, z)), Math.min(carve(x, y, z + 1), carve(x, y, z - 1)));
        return Math.min(d, side + 1);
    }

    /**
     * A ravine's or sinkhole's wall up to two blocks under the ground: dressed as the entrance even where the surface's
     * own biome holds it (the entrances' biomes stay 6 blocks under the ground), so the soil does not band every ledge.
     */
    public boolean openWall(int x, int y, int z) {
        Col c = column(x, z);
        for (int i = 0; i < c.n; i++) {
            Chamber g = c.g[i];
            if ((g.look == RAVINE || g.look == SINKHOLE) && c.e[i] < 6 && y >= g.bottom() - 4 && y <= ground(c) - 2) return true;
        }
        return false;
    }

    /** True for any column the entrances may open (a quick test before the per-block work). */
    public boolean anyNear(int x, int z) {
        Col c = column(x, z);
        return c.n > 0 || c.tn > 0;
    }

    /**
     * Where the pack's own caves must stay shut: under and beside the water of a chamber, from 4 blocks below its lowest
     * open block to a block over its water level, within 4 blocks of its open footprint. The chamber's own open space is
     * carved again afterwards, so this only keeps the pack's caves from draining its water.
     */
    public boolean seals(int x, int y, int z) {
        Col c = column(x, z);
        if (c.n == 0 || y > c.hi) return false; // not c.lo: under a wall the floor is the ground
        for (int i = 0; i < c.n; i++) {
            Chamber g = c.g[i];
            if (c.s[i] < 4 && y <= g.water + 1 && y >= g.bottom() - 4) return true;
        }
        return false;
    }

    /**
     * The biome the entrances give a block, or null to leave it to the pack: within 5 blocks of a chamber's open space or
     * 3 of a tunnel (a descent only in its upper part), or one biome cell (4 blocks) to the side of such a place, so the
     * walls get their own rock instead of the soil the pack puts on every ledge; and never within 6 blocks of the ground.
     * A cold Root Vault cluster is frozen near its mouths' upper halves, its ravines and its skylights.
     */
    public Area areaAt(int x, int y, int z) {
        Site s = inArea(x, y, z);
        if (s == null) {
            s = inArea(x - 4, y, z);
            if (s == null) s = inArea(x + 4, y, z);
            if (s == null) s = inArea(x, y, z - 4);
            if (s == null) s = inArea(x, y, z + 4);
            if (s == null || y > ground(column(x, z)) - 6) return null;
        }
        return switch (s.kind) {
            case VAULTS -> s.cold && frozen(x, y, z) ? Area.FROZEN_ROOTVAULTS : Area.ROOTVAULTS;
            case SINKHOLE -> Area.SINKHOLE;
            case OASIS -> Area.OASIS_HOLLOW;
        };
    }

    private Site inArea(int x, int y, int z) {
        Col c = column(x, z);
        if (c.n == 0 && c.tn == 0) return null;
        if (y < c.lo - 6 || y > c.hi + 6) return null;
        int ground = ground(c);
        if (y > ground - 6) return null;
        for (int i = 0; i < c.n; i++) if (Math.max(c.f[i] - y, y - c.c[i]) < 5) return c.g[i].site;
        for (int i = 0; i < c.tn; i++) {
            Tunnel t = c.t[i];
            if (t.way == Way.DESCENT && y < t.from.water - 20) continue;
            if (t.dist(x, y, z) < 3) return t.owner;
        }
        return null;
    }

    /** True near the openings of a cold Root Vault cluster: its mouths' upper halves, its ravines and its skylights. */
    public boolean frozen(int x, int y, int z) {
        Col c = column(x, z);
        for (int i = 0; i < c.tn; i++) {
            Tunnel t = c.t[i];
            if (t.way == Way.MOUTH && t.owner.cold && t.zoneDist(x, y, z) < FROZEN_REACH) return true;
        }
        for (int i = 0; i < c.n; i++) {
            Chamber g = c.g[i];
            if (!g.site.cold) continue;
            if (g.look == RAVINE && c.e[i] < 4) return true;
            for (double[] s : g.skylights)
                if (Math.hypot(s[0] - x, s[1] - z) < SKYLIGHT_REACH && y > g.water - 3) return true;
        }
        return false;
    }

    /**
     * What fills an open block: {@link #WATER} below the water level of the chamber it is in, {@link #AIR} above it and in
     * the tunnels, or -1 to leave it to the pack (away from the entrances).
     */
    public int fluid(int x, int y, int z) {
        Col c = column(x, z);
        if (c.n == 0 && c.tn == 0) return -1;
        if (y < c.lo - 4 || y > c.hi + 4) return -1;
        double best = 1e9;
        Chamber bg = null;
        for (int i = 0; i < c.n; i++) {
            double d = Math.max(c.f[i] - y, y - c.c[i]);
            if (d < best) { best = d; bg = c.g[i]; }
        }
        if (bg != null && best < 2.5) return y < bg.water ? WATER : AIR;
        for (int i = 0; i < c.tn; i++) if (c.t[i].dist(x, y, z) < 2) return AIR;
        return -1;
    }

    /** The look of the entrance over a column (see {@link #HALL} and the others), or -1 where there is none. */
    public int look(int x, int z) {
        Col c = column(x, z);
        int best = -1;
        double bq = 1e9;
        for (int i = 0; i < c.n; i++) if (c.q[i] < bq) { bq = c.q[i]; best = c.g[i].look; }
        if (best >= 0) return best;
        Tunnel t = nearestTunnel(c);
        return t != null ? t.from.look : -1;
    }

    /** The site whose entrance runs under a column (its nearest chamber's, else a tunnel's), or null. */
    public Site siteAt(int x, int z) {
        Col c = column(x, z);
        Site best = null;
        double bq = 1e9;
        for (int i = 0; i < c.n; i++) if (c.q[i] < bq) { bq = c.q[i]; best = c.g[i].site; }
        if (best != null) return best;
        Tunnel t = nearestTunnel(c);
        return t != null ? t.owner : null;
    }

    /** The tunnel listed for a column that runs nearest it, seen from above, or null. */
    private static Tunnel nearestTunnel(Col c) {
        Tunnel best = null;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < c.tn; i++) {
            Tunnel t = c.t[i];
            for (int k = 0; k + 1 < t.px.length; k++) {
                double d = segDist2(c.x, t.py[k], c.z, t.px[k], t.py[k], t.pz[k], t.px[k + 1], t.py[k], t.pz[k + 1]);
                if (d < bd) { bd = d; best = t; }
            }
        }
        return best;
    }

    /** The chamber whose footprint covers a column, or null. */
    public Chamber chamberAt(int x, int z) {
        Col c = column(x, z);
        Chamber best = null;
        double bq = 1e9;
        for (int i = 0; i < c.n; i++) if (c.q[i] < bq && c.q[i] < 1.05) { bq = c.q[i]; best = c.g[i]; }
        return best;
    }

    /** True where a Root Vault chamber or mouth lies right under the ground of a column, so the trees' roots reach it. */
    public boolean underRoots(int x, int z) {
        Col c = column(x, z);
        for (int i = 0; i < c.n; i++) if (c.g[i].site.kind == Kind.VAULTS && c.e[i] < 0) return true;
        for (int i = 0; i < c.tn; i++) {
            Tunnel t = c.t[i];
            if (t.way != Way.MOUTH || t.owner.kind != Kind.VAULTS) continue;
            for (int k = 0; k + 1 < t.px.length; k++)
                if (segDist2(x, t.py[k], z, t.px[k], t.py[k], t.pz[k], t.px[k + 1], t.py[k], t.pz[k + 1]) < sq(t.r)) return true;
        }
        return false;
    }

    /**
     * The heights a feature should try over a column to land in a chamber: {lowest, highest}, or null where no chamber
     * lies under it. Kept 4 blocks under the ground (2 in a sinkhole, which opens higher).
     */
    public int[] band(int x, int z) {
        Col c = column(x, z);
        if (c.n == 0) return null;
        double lo = 1e9, hi = -1e9;
        boolean open = false;
        for (int i = 0; i < c.n; i++) {
            if (c.c[i] <= c.f[i] + 1) continue;
            lo = Math.min(lo, c.f[i] - 1);
            hi = Math.max(hi, c.c[i] + 1);
            open |= c.g[i].look == SINKHOLE;
        }
        if (lo > hi) return null;
        hi = Math.min(hi, ground(c) - (open ? 2 : 4));
        if (lo > hi) return null;
        return new int[]{(int) Math.floor(lo), (int) Math.ceil(hi)};
    }

    /** The planned skylights whose column lies in a chunk: {x, y, z}, y inside the chamber under it. */
    public List<int[]> skylights(int chunkX, int chunkZ) {
        List<int[]> out = new ArrayList<>();
        for (Site s : sitesTouching(chunkX, chunkZ)) {
            for (Chamber g : s.chambers) {
                for (double[] sk : g.skylights) {
                    int x = (int) Math.floor(sk[0]), z = (int) Math.floor(sk[1]);
                    if (x >> 4 != chunkX || z >> 4 != chunkZ) continue;
                    out.add(new int[]{x, (int) Math.floor(g.water + g.height * 0.5), z});
                }
            }
        }
        return out;
    }

    /** Where the Root Vaults' descents end in the pack's caves, for those ends in a chunk: {x, y, z}. */
    public List<int[]> junctions(int chunkX, int chunkZ) {
        List<int[]> out = new ArrayList<>();
        int ci = Math.floorDiv(chunkX << 4, CELL), cj = Math.floorDiv(chunkZ << 4, CELL);
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            for (Tunnel t : tunnels(ci + di, cj + dj)) {
                if (t.way != Way.DESCENT || t.owner.kind != Kind.VAULTS) continue;
                double[] e = t.end();
                int x = (int) Math.floor(e[0]), z = (int) Math.floor(e[2]);
                if (x >> 4 == chunkX && z >> 4 == chunkZ) out.add(new int[]{x, (int) Math.floor(e[1]), z});
            }
        }
        return out;
    }

    private List<Site> sitesTouching(int chunkX, int chunkZ) {
        int x0 = chunkX << 4, z0 = chunkZ << 4;
        int ci0 = Math.floorDiv(x0, CELL), cj0 = Math.floorDiv(z0, CELL), ci1 = Math.floorDiv(x0 + 15, CELL), cj1 = Math.floorDiv(z0 + 15, CELL);
        List<Site> out = new ArrayList<>();
        for (int ci = ci0; ci <= ci1; ci++) for (int cj = cj0; cj <= cj1; cj++) {
            Site s = site(ci, cj);
            if (s != null) out.add(s);
        }
        return out;
    }

    // ================================================================================================ shape helpers

    static double sq(double v) { return v * v; }

    static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    static double angleBetween(double a, double b) {
        double d = Math.abs(a - b) % (Math.PI * 2);
        return d > Math.PI ? Math.PI * 2 - d : d;
    }
}
