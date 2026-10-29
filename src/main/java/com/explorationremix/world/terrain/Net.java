package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * One deep layer's cave network inside one cell: chambers (nodes) joined by tunnels and climbs (edges). Built once by
 * {@link NetPlanner}, read-only afterwards, so any number of worldgen threads can read it.
 */
public final class Net {
    public enum Kind { ENTRY, EXIT, BEDROCK, PORTAL, GROTTO, LAKE, TERRACE, SPRING, WELL, GALLERY, GREAT, CHIMNEY, TUBEHUB, CAVERN, CITY, POCKET, CANYON, GEODE, LAVA }

    /**
     * Edge kinds. DARK_DOOR joins a Blue Reaches portal vestibule standing on a deep dark cavern's floor to that
     * cavern's node, with no tunnel: the vestibule's stub opens straight onto the cavern floor.
     */
    public enum EK { WALK, CLIMB, LEDGE, STUB, TUBE, PORTAL, DARK_DOOR }

    /** A chamber: a footprint (warped superellipse) with a floor and a ceiling per column. */
    public static final class Chamber {
        public int id;
        public Kind kind;
        public DeepBiome biome;
        long hash;
        public double x, z, rx = 10, rz = 10, rot, cos = 1, sin = 0, sq = 2, warp = 0.15;
        public double f0, h, rise, terr, pend;
        double rampX, rampZ, rampDrop;
        public double lakeY = Double.NaN, lakeE;
        /** Wells and chimneys: the top of the shaft. */
        public double top;
        /** The spiral ledge walk of a well or chimney. */
        double helixR, helixA0, helixDir = 1, helixSlope = 0.42, helixTop;
        public boolean refuge, hunting, required, set, keep = true, low, court;
        int deg, parent = -1, strand = -1;
        /** Pillars as x, z, r triples (tube hubs: x and z relative to the centre). */
        double[] pillars = new double[0];
        /** Canyon nodes (Blue Reaches): the canyon and the arc length along its walk. */
        int canyon = -1;
        /** Blue Reaches: the deep dark region ({@link NetDark}) this node stands for, or -1. */
        public int dark = -1;
        double canyonS, doorDX, doorDZ;

        public double R() { return Math.max(rx, rz) * (1 + warp) + 2; }
        public boolean helix() { return kind == Kind.WELL || kind == Kind.CHIMNEY; }
        public boolean landing() { return kind == Kind.ENTRY || kind == Kind.EXIT || kind == Kind.BEDROCK; }
        double yLo() { return f0 - 3; }
        double yHi() { return helix() ? top : f0 + h + rise + rampDrop + 2; }

        void setRot(double r) { rot = r; cos = Math.cos(r); sin = Math.sin(r); }
    }

    /** A walking way: a tunnel between two doors, a chamber's inner walk, a blind stub or a portal stub. */
    public static final class Way {
        public int id;
        public EK kind;
        public DeepBiome biome;
        public boolean trunk, round, inner, keyhole;
        /** Chambers at the ends (-1 for none). */
        public int a = -1, b = -1;
        public int n;
        public double[] x, z, f, w, hgt, slab;
        public double minX, maxX, minZ, maxZ, minY, maxY, length;
        /** A blind passage that pinches out. */
        public boolean taper;
        /** A ledge passage between two stages of a climb (it joins no room). */
        public boolean ledge;
        /**
         * Where the walking line ends, as arc length from the way's first point: a blind tube ending in a lava pool
         * stops 3 blocks short of it ({@link Pool}). The way is carved to its end all the same.
         */
        public double walkTo = Double.POSITIVE_INFINITY;

        void bounds() {
            minX = minZ = minY = 1e18;
            maxX = maxZ = maxY = -1e18;
            length = 0;
            for (int i = 0; i < n; i++) {
                minX = Math.min(minX, x[i] - w[i]); maxX = Math.max(maxX, x[i] + w[i]);
                minZ = Math.min(minZ, z[i] - w[i]); maxZ = Math.max(maxZ, z[i] + w[i]);
                minY = Math.min(minY, f[i] - slab[i]); maxY = Math.max(maxY, f[i] + hgt[i]);
                if (i > 0) length += Math.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
            }
        }
    }

    /**
     * A climb, or one stage of a staged climb: a vertical shaft between a lower and an upper floor. Vines go up one
     * column of its wall ({@code vineA}, the angle from the axis); a rim of rock stands round its mouth except over
     * the vines.
     */
    public static final class Shaft {
        public int id;
        public double x, z, r, y0, y1;
        /** The floor at the stage's foot and the floor it climbs to (its mouth opens in that floor). */
        public double foot, top;
        public DeepBiome biome;
        /** This stage's number (from 1, at the bottom) and the climb's stage count (1 for a single shaft). */
        public int stage = 1, stages = 1;
        /** Angle of the vine column round the axis (radians), towards the way out at the top. */
        public double vineA;
        /**
         * The vine column, and the block beside it towards the vine angle that a climber steps off onto at the top
         * (kept open over a floor block, {@link Network#fixVines}). Set once {@code vineA} is.
         */
        public int vineX, vineZ, stepX = Integer.MIN_VALUE, stepZ = Integer.MIN_VALUE;
    }

    public static final class Edge {
        public int a, b;
        public EK kind;
        public double cost, len, dy;
        public boolean tree, loop, trunk, gate, stub, forced;
        int strand = -1;
        /** A climb: w1 runs from the lower door to the first stage's foot, w2 from the last stage's top on. */
        public Way w1, w2;
        /** A climb's first stage. */
        public Shaft sh;
        /** A climb's stages from the bottom up, and the ledge passages between them (one fewer). */
        public Shaft[] stages = NO_SHAFTS;
        public Way[] ledges = NO_WAYS;

        public int other(int n) { return n == a ? b : a; }
    }

    static final Shaft[] NO_SHAFTS = new Shaft[0];
    static final Way[] NO_WAYS = new Way[0];

    /**
     * A Basalt arch (section 5): a rock span across a hall's short axis, its feet sunk in the walls. {@code ux, uz} is
     * the hall's long axis (the arch's thickness runs along it), {@code vx, vz} the span's direction.
     */
    public static final class Arch {
        public double x, z, ux, uz, vx, vz;
        /** Half the span (to the feet, in the walls), half the crown's thickness along the hall, the crown's top. */
        public double span, halfAlong, deck;
        public int chamber;
    }

    /**
     * A lava pool cut 1 deep into a floor (the lead's list of 2026-10-29): the blocks whose centres lie within
     * {@code half} of the segment a..b (square ends), at level {@code y}, the floor's top block there; rock under them
     * and round them at that level ({@link #bank} wide), open over them. A Basalt great hall's perched channel along
     * its lowest terrace ({@code channel}: its banks are kept level with the lava, open over them), and the end of a
     * blind tube of an Obsidian Tubes hub (lava only under the tube's open space at the feet: the corners of its
     * pinched end stay rock).
     */
    public static final class Pool {
        public double ax, az, bx, bz, half;
        public int y;
        public boolean channel;
        /** The chamber (a great hall, or a tube's hub) and the way (a blind tube; -1 for a channel). */
        public int chamber = -1, way = -1;
        double ux, uz, len;

        Pool(double ax, double az, double bx, double bz, double half, int y) {
            this.ax = ax; this.az = az; this.bx = bx; this.bz = bz; this.half = half; this.y = y;
            len = Math.hypot(bx - ax, bz - az);
            ux = len < 1e-9 ? 1 : (bx - ax) / len;
            uz = len < 1e-9 ? 0 : (bz - az) / len;
        }

        /** The width of rock kept round the lava at its level and under it (blocks past its edge). */
        public double bank() { return channel ? 1.0 : 1.5; }

        /** Distance from (x, z) to the pool's footprint: negative or 0 inside. */
        public double dist(double x, double z) {
            double dx = x - ax, dz = z - az;
            double along = dx * ux + dz * uz, across = Math.abs(-dx * uz + dz * ux);
            double da = Math.max(-along, along - len), dc = across - half;
            if (da <= 0 && dc <= 0) return Math.max(da, dc);
            return Math.hypot(Math.max(0, da), Math.max(0, dc));
        }
    }

    public final int li, cx, cz;
    public final List<Chamber> ch = new ArrayList<>();
    public final List<Edge> edges = new ArrayList<>();
    public final List<Way> ways = new ArrayList<>();
    public final List<Shaft> shafts = new ArrayList<>();
    public final List<Arch> arches = new ArrayList<>();
    /** The Basalt Galleries' lava pools: the great halls' channels and the blind tubes' ends. */
    public final List<Pool> pools = new ArrayList<>();
    /** Blind tube ends whose pool could not be held by rock: {x, floor block y, z}; their floor is magma instead. */
    public final List<double[]> magmaEnds = new ArrayList<>();
    final List<Route> canyons = new ArrayList<>();
    public int entry = -1, exit = -1;
    int surgeNiches;
    /** The portal vestibules planned for this cell (dropped ones included; see {@link #droppedPortals}). */
    public final List<Integer> portals = new ArrayList<>();
    /** Portal vestibules no tunnel could reach: removed with their stub, a blind end carved in its place. */
    public final List<Integer> droppedPortals = new ArrayList<>();
    /** Plan statistics. */
    int attached, tries, embedFail, forced, droppedChambers, loopsAdded, stubsAdded, gateAug, cutRepairs, strandBreaks, walkRepairs, tubeStubs, chokeRepairs;
    final List<Integer> courtsE = new ArrayList<>(), courtsX = new ArrayList<>();
    /** The strands: braided main ways between the landings, as chamber id lists (courts at both ends). */
    final List<List<Integer>> strands = new ArrayList<>();
    final List<List<double[]>> strandLines = new ArrayList<>();
    double planMs;
    /** Of which the deep dark's view breaker (Blue Reaches). */
    double sightMs;
    /** Blue Reaches: the rasterised deep dark of this cell (null elsewhere). */
    NetDark.Plan dark;

    // ---- spatial index for the per-column lookups: 16-block buckets over the cell and a 48-block apron
    static final int BK = 16, APRON = 48;
    double ix0, iz0;
    int gn;
    int[][] bCh, bWay, bWaySeg, bSh;

    Net(int li, int cx, int cz) { this.li = li; this.cx = cx; this.cz = cz; }

    Chamber add(Chamber c) {
        c.id = ch.size();
        ch.add(c);
        return c;
    }

    List<Edge> adj(int n) {
        List<Edge> out = new ArrayList<>();
        for (Edge e : edges) if (e.a == n || e.b == n) out.add(e);
        return out;
    }
}
