package com.explorationremix.world.terrain;

import com.explorationremix.world.terrain.Net.Chamber;
import com.explorationremix.world.terrain.Net.EK;
import com.explorationremix.world.terrain.Net.Kind;
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
 * The planned deep layers (the Blue Reaches and the Deep Gardens, {@link Terrain#planned}) as connected cave networks:
 * chambers joined by tunnels and climbs, planned per cell and layer by {@link NetPlanner}. This is the density at
 * Minecraft's interpolation corners ({@link #layerDensity}), the per-block correction after interpolation ({@link
 * #fine}), the fluids and the biomes. The bands, the gates and their landings, the upper caves and the noise layers
 * (the Wasteland and the Basalt Galleries) stay with {@link Terrain}, which owns one of these and hands it every block
 * inside a planned layer. Asked about a noise layer, its queries answer empty in the game and throw under the tests
 * ({@link #hasPlan}).
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
        // the planned layers only: a column keeps nothing for the noise layers
        int layers = Math.min(shape.layerCount, Terrain.WASTELAND);
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

    /**
     * Whether a question about a layer that is not {@link Terrain#planned} throws (true under the tests, which set the
     * system property {@code explorationremix.strictPlans}) or gets an empty answer (false, the game). The Wasteland and
     * the Basalt Galleries are noise fields with no plan, so nothing here answers for them: a caller that still asks is a
     * mistake, which the tests catch at once and world generation survives.
     */
    static volatile boolean strict = Boolean.getBoolean("explorationremix.strictPlans");

    /**
     * True for a planned layer. For another it throws when {@link #strict}, and is false otherwise: the query answers
     * empty (no way lines, no climbs, no portals, no plan, no geode).
     */
    static boolean hasPlan(int li) {
        if (Terrain.planned(li)) return true;
        if (strict) throw new IllegalArgumentException("layer " + li + " is a noise field: it has no plan");
        return false;
    }

    /** Throws for a layer that is not planned, strict or not: for the plans' own workings, which only planned layers reach. */
    static void requirePlanned(int li) {
        if (!Terrain.planned(li)) throw new IllegalArgumentException("layer " + li + " is a noise field: it has no plan");
    }

    /** The plan of layer li in cell (cx, cz), made on first use. Only the {@link Terrain#planned} layers have one. */
    Net net(int li, int cx, int cz) {
        requirePlanned(li);
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

    // ================================================================================================ queries (12.1)
    /**
     * What {@link #wayLinesAt} found, nearest first: per line the distance from the column to it, the walking floor
     * there (the feet stand at {@code ceil(floor)}) and the line's half-width. A per-thread buffer, valid until the
     * thread's next call; it grows as needed, so no line is ever left out.
     */
    public static final class WayLines {
        public int n;
        public double[] d = new double[16], floor = new double[16], half = new double[16];

        void add(double dd, double f, double h) {
            if (n == d.length) {
                d = Arrays.copyOf(d, n * 2);
                floor = Arrays.copyOf(floor, n * 2);
                half = Arrays.copyOf(half, n * 2);
            }
            int i = n++;
            while (i > 0 && d[i - 1] > dd) {
                d[i] = d[i - 1];
                floor[i] = floor[i - 1];
                half[i] = half[i - 1];
                i--;
            }
            d[i] = dd;
            floor[i] = f;
            half[i] = h;
        }
    }

    private static final ThreadLocal<WayLines> LINES = ThreadLocal.withInitial(WayLines::new);
    private static final ThreadLocal<double[]> LINE_TMP = ThreadLocal.withInitial(() -> new double[6]);

    /**
     * Every walking line of layer li that passes within {@code reach} of (x, z): the tunnels, inner walks, ledge
     * passages and stubs of the plans (the neighbouring cells' too) and the rift's ledge path. A way that passes the
     * column more than once (a helix's turns, a hairpin) is listed once per pass. No cap (section 12.1).
     */
    public WayLines wayLinesAt(int li, double x, double z, double reach) {
        WayLines out = LINES.get();
        out.n = 0;
        if (!hasPlan(li)) return out;
        int cx = cellOf(x), cz = cellOf(z);
        for (int i = -1; i <= 1; i++) for (int k = -1; k <= 1; k++) {
            double ex = Math.max(0, Math.max((cx + i) * cell - x, x - (cx + i + 1) * cell));
            double ez = Math.max(0, Math.max((cz + k) * cell - z, z - (cz + k + 1) * cell));
            if (Math.hypot(ex, ez) > reach + Net.APRON) continue;
            linesOf(net(li, cx + i, cz + k), x, z, reach, out);
        }
        return out;
    }

    /**
     * The lines of {@link #wayLinesAt} from the plan of the column's own cell only: the ways the terrain carved in this
     * column (the density reads that plan alone; a portal stub is in both cells' plans). For the pass that re-opens
     * them, which must never cut a corridor through rock that no way carved.
     */
    public WayLines carvedLinesAt(int li, double x, double z, double reach) {
        WayLines out = LINES.get();
        out.n = 0;
        if (!hasPlan(li)) return out;
        linesOf(net(li, cellOf(x), cellOf(z)), x, z, reach, out);
        return out;
    }

    private void linesOf(Net n, double x, double z, double reach, WayLines out) {
        for (Way wy : n.ways) {
            if (x < wy.minX - reach || x > wy.maxX + reach || z < wy.minZ - reach || z > wy.maxZ + reach) continue;
            // every local minimum of the distance along the way, as the column lookup finds them (compute)
            double bestD = 1e9, bestFi = 0, prevD = 1e9;
            boolean tracking = true;
            for (int i = 0; i + 1 < wy.n; i++) {
                double ax = wy.x[i], az = wy.z[i], dx = wy.x[i + 1] - ax, dz = wy.z[i + 1] - az;
                double len2 = dx * dx + dz * dz;
                double t = len2 < 1e-9 ? 0 : ((x - ax) * dx + (z - az) * dz) / len2;
                t = t < 0 ? 0 : (t > 1 ? 1 : t);
                double d = Math.hypot(x - (ax + dx * t), z - (az + dz * t));
                if (tracking) {
                    if (d < bestD) { bestD = d; bestFi = i + t; }
                    else if (d > bestD + 4) {
                        if (bestD <= reach) addLine(out, wy, bestD, bestFi);
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
            if (tracking && bestD <= reach) addLine(out, wy, bestD, bestFi);
        }
        double[] tmp = LINE_TMP.get();
        for (Route r : n.canyons) {
            if (!r.nearestWalk(x, z, tmp)) continue;
            double fi = tmp[1], d2 = tmp[4], fi2 = tmp[5];
            if (tmp[0] <= reach) out.add(tmp[0], r.atIndex(r.floor, fi), r.atIndex(r.half, fi));
            if (d2 <= reach) out.add(d2, r.atIndex(r.floor, fi2), r.atIndex(r.half, fi2));
        }
    }

    private static void addLine(WayLines out, Way wy, double d, double fi) {
        int i = (int) Math.floor(fi);
        double t = fi - i;
        if (i >= wy.n - 1) { i = wy.n - 2; t = 1; }
        out.add(d, wy.f[i] + (wy.f[i + 1] - wy.f[i]) * t, wy.w[i] + (wy.w[i + 1] - wy.w[i]) * t);
    }

    /**
     * The climbs and climb stages of layer li whose shafts (with their rims) reach into the box minX..maxX, minZ..maxZ
     * (inclusive): from the plans of every cell the box touches, each shaft carved in its own cell.
     */
    public List<Shaft> shaftsIn(int li, int minX, int minZ, int maxX, int maxZ) {
        List<Shaft> out = new ArrayList<>();
        if (!hasPlan(li)) return out;
        // a climb's radius is at most 3.4 (NetPlanner.climb), 5.4 with its rim and step: 8 covers every cell
        for (int cx = cellOf(minX - 8); cx <= cellOf(maxX + 8); cx++) for (int cz = cellOf(minZ - 8); cz <= cellOf(maxZ + 8); cz++) {
            for (Shaft s : net(li, cx, cz).shafts) {
                if (cellOf(s.x) != cx || cellOf(s.z) != cz) continue;
                double r = s.r + 2;
                if (s.x + r < minX || s.x - r > maxX + 1 || s.z + r < minZ || s.z - r > maxZ + 1) continue;
                out.add(s);
            }
        }
        return out;
    }

    /** The plan of layer li in cell (cx, cz), for the dev tools; null for a layer with no plan ({@link #hasPlan}). */
    public Net network(int li, int cx, int cz) { return hasPlan(li) ? net(li, cx, cz) : null; }

    // ================================================================================================ dev tools (12.5)
    /** A place showing a deep biome, for the dev tools: a chamber, the rift or a refuge geode, with spots to stand on. */
    public static final class Place {
        public final double x, z, reach;
        public final boolean setPiece;
        public final String what;
        /** Spots to stand on, best first: {x, feet y, z}. The world's blocks decide which is open. */
        public final List<double[]> stands = new ArrayList<>();

        Place(double x, double z, double reach, boolean setPiece, String what) {
            this.x = x; this.z = z; this.reach = reach; this.setPiece = setPiece; this.what = what;
        }
    }

    /** The deep layer a biome belongs to, or -1 (the Upper Caves' biomes). */
    public static int layerOf(DeepBiome b) {
        return switch (b) {
            case DEEP_BLUE_RIFT, DEEP_DARK, ASURINE_GEODES -> Terrain.BLUE;
            case LANTERN_LAKE, EMBER_GARDENS, RUST_SPRINGS -> Terrain.GARDENS;
            case WASTELAND, ASH_HOLLOWS -> Terrain.WASTELAND;
            case BASALT_GALLERIES, OBSIDIAN_TUBES, CALDERA, CAUSEWAY, SCORIA_CANYONS -> Terrain.GALLERIES;
            default -> -1;
        };
    }

    /**
     * The places of a deep biome in the plans of the 3 x 3 cells round (x, z), set pieces first, then nearest first
     * (/remix tp biome, /remix view): every kept chamber of the biome (spots at its centre and part way out to its
     * walls, on its walking floor), and in the Blue Reaches the rift (spots along its ledge path) and the refuge geodes
     * (their floors).
     */
    public List<Place> places(DeepBiome b, double x, double z) {
        List<Place> out = new ArrayList<>();
        int li = layerOf(b);
        if (li < 0 || li >= shape.layerCount) return out;
        int pcx = cellOf(x), pcz = cellOf(z);
        for (int cx = pcx - 1; cx <= pcx + 1; cx++) for (int cz = pcz - 1; cz <= pcz + 1; cz++) {
            Net n = net(li, cx, cz);
            for (Chamber c : n.ch) {
                if (!c.keep || c.kind == Kind.CANYON || cellOf(c.x) != cx || cellOf(c.z) != cz) continue;
                DeepBiome cb = c.biome != null ? c.biome : (c.parent >= 0 ? n.ch.get(c.parent).biome : null);
                if (cb != b) continue;
                boolean set = c.set || c.kind == Kind.CITY || c.kind == Kind.LAKE || c.kind == Kind.SPRING;
                Place p = new Place(c.x, c.z, c.R(), set, c.kind.name().toLowerCase(java.util.Locale.ROOT) + " #" + c.id + " of cell " + cx + " " + cz);
                for (int ring = 0; ring < 3; ring++) {
                    int k = ring == 0 ? 1 : 6;
                    double f = ring == 0 ? 0 : ring == 1 ? 0.45 : 0.75;
                    for (int j = 0; j < k; j++) {
                        double a = (j + (ring == 2 ? 0.5 : 0)) * Math.PI * 2 / k, u = Math.cos(a) * f * c.rx, v = Math.sin(a) * f * c.rz;
                        double px = c.x + u * c.cos - v * c.sin, pz = c.z + u * c.sin + v * c.cos;
                        double fl;
                        if (c.dark >= 0 || c.kind == Kind.CITY || c.helix()) fl = c.f0;
                        else {
                            double e = e(c, px, pz);
                            if (e >= 0.95) continue;
                            fl = floorAt(c, e, px, pz);
                            if (c.kind == Kind.LAKE && fl < c.lakeY) continue; // under the water
                        }
                        p.stands.add(new double[]{px, Math.ceil(fl - 1e-9), pz});
                    }
                }
                if (!p.stands.isEmpty()) out.add(p);
            }
            for (Route r : n.canyons) {
                if (b == DeepBiome.DEEP_BLUE_RIFT) {
                    double[] mid = r.pathAt(r.walkLength * 0.5);
                    Place p = new Place(mid[0], mid[1], 28, true, "the rift of cell " + cx + " " + cz);
                    for (double f : new double[]{0.5, 0.35, 0.65, 0.2, 0.8}) {
                        double[] q = r.pathAt(r.walkLength * f);
                        p.stands.add(new double[]{q[0], Math.ceil(q[2] - 1e-9), q[1]});
                    }
                    out.add(p);
                }
                if (b == DeepBiome.ASURINE_GEODES) {
                    for (Route.Geode g : r.geodes) {
                        Place p = new Place(g.x(), g.z(), g.r(), true, "a refuge geode of cell " + cx + " " + cz);
                        p.stands.add(new double[]{g.x(), Math.floor(g.y() - g.r() * 0.4), g.z()});
                        for (int j = 0; j < 4; j++) {
                            double a = j * Math.PI / 2;
                            p.stands.add(new double[]{g.x() + Math.cos(a) * g.r() * 0.3, Math.floor(g.y() - g.r() * 0.45), g.z() + Math.sin(a) * g.r() * 0.3});
                        }
                        out.add(p);
                    }
                }
            }
        }
        out.sort((p, q) -> p.setPiece != q.setPiece ? (p.setPiece ? -1 : 1) : Double.compare(Math.hypot(p.x - x, p.z - z), Math.hypot(q.x - x, q.z - z)));
        return out;
    }

    /** For /remix info: the plan's node, way, climb and rift at a block of layer li, in words. */
    public List<String> describe(int li, int x, int y, int z) {
        List<String> out = new ArrayList<>();
        if (!hasPlan(li)) return out;
        int cx = cellOf(x), cz = cellOf(z);
        Net n = net(li, cx, cz);
        int kept = 0;
        for (Chamber c : n.ch) if (c.keep) kept++;
        out.add(String.format(java.util.Locale.ROOT, "network: cell %d %d of layer %d, %d chambers, %d ways, %d climb stages, %d portals%s",
                cx, cz, li + 1, kept, n.ways.size(), n.shafts.size(), n.portals.size() - n.droppedPortals.size(), n.canyons.isEmpty() ? "" : ", the rift"));
        Chamber in = null, near = null;
        double bestIn = 1e9, bestNear = 1e9;
        for (Chamber c : n.ch) {
            if (!c.keep || c.kind == Kind.CANYON) continue;
            double e = e(c, x, z), d = Math.hypot(x - c.x, z - c.z) - c.R();
            if (e < 1 && y >= c.yLo() && y <= c.yHi() && e < bestIn) { bestIn = e; in = c; }
            if (d < bestNear) { bestNear = d; near = c; }
        }
        Chamber c = in != null ? in : near;
        if (c != null) {
            out.add(String.format(java.util.Locale.ROOT, "  %s %s #%d (%s%s): centre %.0f %.0f, floor y %.0f, %.0f by %.0f, %.0f tall%s",
                    in != null ? "in node" : "nearest node", c.kind.name().toLowerCase(java.util.Locale.ROOT), c.id,
                    c.biome == null ? "-" : c.biome.id, c.set ? ", set piece" : "", c.x, c.z, c.f0, 2 * c.rx, 2 * c.rz, c.helix() ? c.top - c.f0 : c.h,
                    in != null ? "" : String.format(java.util.Locale.ROOT, ", %.0f blocks past its wall", Math.max(0, bestNear))));
        }
        Way bw = null;
        double bd = 1e9, bDist = 0, bFloor = 0;
        for (Way wy : n.ways) {
            if (x < wy.minX - 48 || x > wy.maxX + 48 || z < wy.minZ - 48 || z > wy.maxZ + 48) continue;
            for (int i = 0; i + 1 < wy.n; i++) {
                double ax = wy.x[i], az = wy.z[i], dx = wy.x[i + 1] - ax, dz = wy.z[i + 1] - az;
                double len2 = dx * dx + dz * dz;
                double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / len2));
                double d = Math.hypot(x - (ax + dx * t), z - (az + dz * t)), f = wy.f[i] + (wy.f[i + 1] - wy.f[i]) * t;
                double score = d + 0.5 * Math.abs(y - Math.ceil(f));
                if (score < bd) { bd = score; bw = wy; bDist = d; bFloor = f; }
            }
        }
        if (bw != null) {
            String ends = (bw.a >= 0 ? n.ch.get(bw.a).kind.name().toLowerCase(java.util.Locale.ROOT) + " #" + bw.a : "-") + " to "
                    + (bw.b >= 0 ? n.ch.get(bw.b).kind.name().toLowerCase(java.util.Locale.ROOT) + " #" + bw.b : "-");
            out.add(String.format(java.util.Locale.ROOT, "  nearest way: #%d, %s%s, %s, %.0f blocks off its line, its floor y %.0f",
                    bw.id, bw.kind.name().toLowerCase(java.util.Locale.ROOT), bw.inner ? " (inner walk)" : "", ends, bDist, bFloor));
        }
        for (Shaft s : n.shafts) {
            if (Math.hypot(x - s.x, z - s.z) > s.r + 6 || y < s.y0 - 4 || y > s.y1 + 4) continue;
            out.add(String.format(java.util.Locale.ROOT, "  climb stage %d of %d: foot y %.0f, top y %.0f, vines at %d %d", s.stage, s.stages, s.foot, s.top, s.vineX, s.vineZ));
        }
        double[] tmp = new double[6];
        for (Route r : n.canyons) {
            if (r.nearestWalk(x, z, tmp))
                out.add(String.format(java.util.Locale.ROOT, "  the rift's ledge path: %.0f blocks away, its floor y %.0f", tmp[0], r.atIndex(r.floor, tmp[1])));
        }
        return out;
    }

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
        if (!hasPlan(li)) return List.of();
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
        double yLo = W + (li == Terrain.BLUE ? 14 : 6), yHi = T - 52;
        List<double[]> chosen = new ArrayList<>();
        // Blue Reaches: the eligible city sites of the two cells, whose reserves no vestibule or stub may enter
        List<double[]> sites = new ArrayList<>();
        if (li == Terrain.BLUE) {
            sites.addAll(eligibleCities(xEdge ? ex - 1 : ex, xEdge ? ez : ez - 1));
            sites.addAll(eligibleCities(ex, ez));
        }
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
            if (ok && inReserve(sites, xEdge, lineV, a, Math.floor(y))) ok = false;
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

    /**
     * True when a portal on this edge at {@code along}, floor y, would put a vestibule (up to 9.5 across and 15 tall
     * with its rise) or its stub (an S-bend 3 blocks off the line, on a 4-block slab) into a city reserve (section 11.1).
     */
    boolean inReserve(List<double[]> sites, boolean xEdge, double lineV, double along, double y) {
        if (sites.isEmpty()) return false;
        double W = shape.waterY(Terrain.BLUE);
        if (y + 16 < W + CITY_RESERVE_LO || y - 5 > W + CITY_RESERVE_HI) return false;
        for (double[] s : sites) {
            // from the site to the segment between the two vestibules' centres (30 blocks either side of the edge)
            double u = xEdge ? s[0] : s[1], v = xEdge ? s[1] : s[0];
            if (Math.hypot(Math.max(0, Math.abs(u - lineV) - 30), v - along) < CITY_RESERVE + 9.5 + 3) return true;
        }
        return false;
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
        Route rt = new Route(base, li, cx, cz, h, pts, sFloor, eFloor, shape.waterY(li));
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

    // ================================================================================================ ancient cities
    /** The city reserve (section 11.1): its radius round the site, and its heights over the Blue Reaches' water table. */
    public static final double CITY_RESERVE = 128, CITY_RESERVE_LO = 8, CITY_RESERVE_HI = 80;
    /** How far inside its cell, and from both of the cell's landings, a city site must lie to be used. */
    static final double CITY_INSIDE = 150, CITY_LANDING = 140;

    /**
     * Where vanilla would start an ancient city (random spread 24/8, salt 20083232) in cell (cx, cz): the centre of the
     * start chunk, {x, z}.
     */
    List<double[]> citySites(int cx, int cz) {
        List<double[]> out = new ArrayList<>();
        int bx0 = (int) Math.round(cx * cell), bz0 = (int) Math.round(cz * cell), size = (int) Math.round(cell);
        int c0x = Math.floorDiv(bx0, 16), c1x = Math.floorDiv(bx0 + size - 1, 16);
        int c0z = Math.floorDiv(bz0, 16), c1z = Math.floorDiv(bz0 + size - 1, 16);
        for (int rx = Math.floorDiv(c0x, 24); rx <= Math.floorDiv(c1x, 24); rx++) {
            for (int rz = Math.floorDiv(c0z, 24); rz <= Math.floorDiv(c1z, 24); rz++) {
                Random r = new Random(rx * 341873128712L + rz * 132897987541L + seed + 20083232L);
                int chx = rx * 24 + r.nextInt(16), chz = rz * 24 + r.nextInt(16);
                double x = chx * 16 + 8, z = chz * 16 + 8;
                if (x >= bx0 && x < bx0 + size && z >= bz0 && z < bz0 + size) out.add(new double[]{x, z});
            }
        }
        return out;
    }

    /**
     * The city sites of cell (cx, cz) that a city may use (section 11.1): at least {@link #CITY_INSIDE} inside the cell
     * and {@link #CITY_LANDING} from both of the Blue Reaches' landings, so the reserve stays 22 blocks inside the cell
     * and both landings 12 outside it. A pure function of the seed and the cell (the sites and the gates are pure), so
     * the portals of the cell's edges read it too. Empty when the cells are too small for a city (under 400).
     */
    List<double[]> eligibleCities(int cx, int cz) {
        List<double[]> out = new ArrayList<>();
        if (cell < 400) return out;
        double x0 = cx * cell, z0 = cz * cell;
        Plans.Gate in = base.plans.gate(Terrain.BLUE + 1, cx, cz), down = base.plans.gate(Terrain.BLUE + 2, cx, cz);
        for (double[] s : citySites(cx, cz)) {
            if (s[0] < x0 + CITY_INSIDE || s[0] > x0 + cell - CITY_INSIDE || s[1] < z0 + CITY_INSIDE || s[1] > z0 + cell - CITY_INSIDE) continue;
            if (Math.hypot(s[0] - in.lx, s[1] - in.lz) < CITY_LANDING || Math.hypot(s[0] - down.ux, s[1] - down.uz) < CITY_LANDING) continue;
            out.add(s);
        }
        return out;
    }

    /**
     * True when a structure starting at (x, y, z) is a planned ancient city (section 11.1): a kept CITY chamber's centre
     * is within 24 blocks of (x, z) and y is within 30 of its floor. Replaces 0.2.0's {@code clearOfRoute}.
     */
    public boolean citySite(int x, int y, int z) {
        // a city stands at least 150 inside its cell, so only the start's own cell can hold it
        for (Chamber c : net(Terrain.BLUE, cellOf(x), cellOf(z)).ch) {
            if (!c.keep || c.kind != Kind.CITY) continue;
            if (Math.hypot(c.x - x, c.z - z) <= 24 && Math.abs(y - c.f0) <= 30) return true;
        }
        return false;
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
                double t = rampT(c, x, z);
                double v = c.rampDrop * 0.5 * (1 - t) + c.rise * e * e + 1.1 * floorN.noise2(x / 11.0, z / 11.0) + 1.1;
                return c.f0 + Terrain.terraced(Math.max(0, v), c.terr);
            }
            case WELL -> {
                double f = c.f0 + c.rise * e * e + 0.6 * floorN.noise2(x / 7.0, z / 7.0);
                if (pool(c) && e < c.lakeE + 0.04) {
                    // the pool at the well's foot: a flat bottom 3.5 under the water, steep sides
                    double t = Math.max(0, Math.min(1, (c.lakeE + 0.04 - e) / 0.12));
                    f += (c.lakeY - 3.5 - f) * t;
                }
                return f;
            }
            default -> {
                double v = c.rise * e * e + 1.0 * floorN.noise2(x / 9.0, z / 9.0) + 1.0;
                if (c.rampDrop > 0) {
                    // a strand chamber steps down along the way through it
                    v += c.rampDrop * 0.5 * (1 - rampT(c, x, z));
                }
                return c.f0 + (c.terr > 0 ? Terrain.terraced(Math.max(0, v), Math.max(1, c.terr)) : v);
            }
        }
    }

    double ceilAt(Chamber c, double e, double x, double z) {
        if (e >= 1) return c.f0 - 30;
        double p;
        switch (c.kind) {
            case CITY -> p = 4;
            case TERRACE, SPRING, CAVERN, GROTTO -> p = 3;
            case WELL -> {
                return c.top - 5 * e * e + 2 * ceilN.noise2(x / 9.0, z / 9.0);
            }
            default -> p = 2;
        }
        double ep = p == 2 ? e * e : (p == 4 ? e * e * e * e : e * e * e);
        double C = c.f0 + c.h * Math.sqrt(Math.max(0, 1 - ep));
        if (c.rampDrop > 0 && c.kind != Kind.LAKE) C += c.rampDrop * 0.5 * (1 - rampT(c, x, z));
        if (c.pend > 0) {
            double n = pendN.noise2(x / 4.5, z / 4.5);
            if (n > 0.25) C -= c.pend * Math.pow((n - 0.25) / 0.75, 1.3) * 2.2 * Math.min(1, (1 - e) / 0.25);
        }
        return C + 1.5 * ceilN.noise2(x / 13.0, z / 13.0);
    }

    /** Where a block stands along a chamber's ramp: 1 at the low end, -1 at the high end, clamped. */
    static double rampT(Chamber c, double x, double z) {
        double R = Math.max(c.rx, c.rz);
        double t = ((x - c.x) * c.rampX + (z - c.z) * c.rampZ) / R;
        return t < -1 ? -1 : (t > 1 ? 1 : t);
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
                throw new IllegalArgumentException("no biomes for layer " + li);
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
        // per-column values are floats: 1,024 columns x 2 layers per worldgen thread (section 2.1)
        float[] e = new float[16], F = new float[16], C = new float[16];
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
        /** The tiers' ceilings as the plan keeps them, before a way close over one brings it down ({@link #DARK_ROOF}). */
        double aC0, bC0;
        // landings; whether the gates' passages can reach the column (outside, their term is rock)
        Plans.Gate en, ex;
        boolean enNear, exNear;
        /**
         * The Blue Reaches: the cell's other den gates ({@link Plans#denGates} past the first) whose passage or lower
         * landing can reach the column (as {@link #enNear} tells of the entry's), and the column's distance to each
         * one's lower landing (as {@link #entryD}).
         */
        int nd;
        Plans.Gate[] dg = new Plans.Gate[4];
        double[] dgD = new double[4];
        double entryD, exitD;
        /** The heights the per-block pass must look at; outside them the interpolated density stands. */
        double fLo, fHi;

        void growC() {
            int n = c.length * 2;
            c = Arrays.copyOf(c, n); e = Arrays.copyOf(e, n); F = Arrays.copyOf(F, n); C = Arrays.copyOf(C, n);
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
        requirePlanned(li);
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
                if (pool(c)) {
                    // the rim and the floor under the water reach 10 below the floor line
                    lc.fLo = Math.min(lc.fLo, Math.min(c.f0, c.lakeY - 3.5) - 11);
                    lc.fHi = Math.max(lc.fHi, c.lakeY + 1);
                }
                if (e < 1.3 && protectedRoom(c)) {
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
        if (n.dark != null) {
            darkCol(lc, n.dark, x, z);
            // not within 16 of a seam, where both cells carve the raw field alike (section 6.3)
            if (lc.darkOn && NetDark.seamDist(x + 0.5, z + 0.5, cell) > 16) roofUnderWays(lc);
        }
        // landings: a planned layer has the gate above it and the gate below it
        lc.en = base.plans.gate(li + 1, cx, cz);
        lc.ex = base.plans.gate(li + 2, cx, cz);
        lc.entryD = Math.hypot(x - lc.en.lx, z - lc.en.lz);
        lc.exitD = Math.hypot(x - lc.ex.ux, z - lc.ex.uz);
        // passage() is rock beyond 1.34 radii + 8 of the centre line, which keeps inside the gate's box
        lc.enNear = lc.en.overlaps(x, z, x, z, 8);
        lc.exNear = lc.ex.overlaps(x, z, x, z, 8);
        if (lc.entryD < lc.en.landR + 6) { lc.fLo = Math.min(lc.fLo, lc.en.lFloor - 9); lc.fHi = Math.max(lc.fHi, lc.en.lCy + lc.en.landHalf + 4); }
        if (lc.exitD < lc.ex.landR + 6) { lc.fLo = Math.min(lc.fLo, lc.ex.uFloor - 9); lc.fHi = Math.max(lc.fHi, lc.ex.uCy + lc.ex.landHalf + 4); }
        // the other den gates' landings and passages, carved as the entry's are, whatever the plan (the planner joins
        // each landing to the network). The box test covers the landing too: its pad is past the landing's radius.
        lc.nd = 0;
        if (li == Terrain.BLUE) {
            List<Plans.Gate> dens = base.plans.denGates(cx, cz);
            for (int i = 1; i < dens.size(); i++) {
                Plans.Gate g = dens.get(i);
                if (!g.overlaps(x, z, x, z, 8)) continue;
                if (lc.nd == lc.dg.length) {
                    lc.dg = Arrays.copyOf(lc.dg, lc.nd * 2);
                    lc.dgD = Arrays.copyOf(lc.dgD, lc.nd * 2);
                    columnGrowths.increment();
                }
                double d = Math.hypot(x - g.lx, z - g.lz);
                lc.dg[lc.nd] = g;
                lc.dgD[lc.nd++] = d;
                if (d < g.landR + 6) { lc.fLo = Math.min(lc.fLo, g.lFloor - 9); lc.fHi = Math.max(lc.fHi, g.lCy + g.landHalf + 4); }
            }
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
        lc.wcore[k] = wy.inner ? 1.9f : 2.4f;
        lc.wslabHalf[k] = (float) slabHalf(wy, lc.ww[k]);
        if (d < lc.wslabHalf[k] + 1 || d < lc.wcore[k] + 1.5 || (!wy.inner && d < lc.ww[k] * 1.25)) {
            // the walking core stays open up to 6.24 over the floor line, whatever the way's height (the sum is a
            // double's: the floats' own sum would round differently)
            lc.fLo = Math.min(lc.fLo, lc.wf[k] - lc.wsl[k] - 2);
            lc.fHi = Math.max(lc.fHi, lc.wf[k] + (double) Math.max(lc.wh[k] + 1, 7));
        }
    }

    /** Half the width of a way's floor slab where the way is w wide (a helix's spiral ledge, an inner walk, a tunnel). */
    static double slabHalf(Way wy, double w) {
        boolean helix = wy.inner && wy.slab[0] >= 3.4;
        return helix ? 3.4 : (wy.inner ? 3.6 : Math.min(5.0, w * 0.9 + 1.2));
    }

    /**
     * The deep dark of a column: both tiers where the cell's plan keeps them. Within 16 blocks of a seam the raw 0.2.0
     * field opens whatever the plan decided, so both cells carve the same caverns there; from 16 to 32 a cavern the plan
     * closes tapers shut instead of ending in a wall (section 6.3).
     */
    private void darkCol(LCol lc, NetDark.Plan p, double x, double z) {
        lc.aF = lc.bF = SOLID;
        lc.aC = lc.bC = lc.aC0 = lc.bC0 = -SOLID;
        lc.aPr = lc.bPr = 0;
        lc.aPd = lc.bPd = 1e9;
        double[] t = lc.tmp;
        double fade = -1;
        double band = NetDark.band(NetDark.seamDist(x + 0.5, z + 0.5, cell));
        for (int k = 0; k < 2; k++) {
            double o = dark.open(k, x, z);
            if (o <= 0) continue;
            if (fade < 0) fade = NetDark.fade(p.geodes, x, z);
            if (!dark.tierAt(p, k, x, z, o, band, fade, t)) continue;
            double F = t[0], C = t[1];
            dark.pillar(k, x, z, p, t);
            if (k == 0) { lc.aF = F; lc.aC = lc.aC0 = C; lc.aPd = t[0]; lc.aPr = t[1]; }
            else { lc.bF = F; lc.bC = lc.bC0 = C; lc.bPd = t[0]; lc.bPr = t[1]; }
            lc.darkOn = true;
        }
    }

    /**
     * The rock a deep dark cavern's roof keeps under a walking way's floor slab (blocks): its ceiling comes down to
     * that far under the slab beneath the way and {@link #ROOF_SIDE} beyond its sides, sloping back up further out
     * (but within 16 blocks of a seam, where both cells carve the raw tiers alike).
     * The planner lays ways over the caverns' roofs, their own included, and the roughness of the roof (up to 5.4
     * blocks) and of the tunnel holed a thinner one: walking tunnels whose slabs lay 3 or 4 blocks over a cavern's roof
     * dropped 27 to 36 blocks into it (0.6.2, seeds 8004 cell 0,0 and 575400745 cell 1,-2). Keeping the ways off such
     * roofs in the planner instead laid all 99 plans of the sweep's cells out anew (9,169 ways to 8,618) and left
     * caverns and exits unjoined (seed 2 cell -1,0 lost its exit and all 25 of its caverns); this leaves every plan as
     * it was, and only lowers a roof a few blocks along the ways that run close over it.
     */
    static final double DARK_ROOF = 8;
    /** How far beyond a way's half width a cavern's roof keeps {@link #DARK_ROOF} under it (blocks); then 1.5 a block less. */
    static final double ROOF_SIDE = 2;

    /** Lowers the tiers' ceilings of a column to {@link #DARK_ROOF} under the slab of a way close over them. */
    private static void roofUnderWays(LCol lc) {
        for (int k = 0; k < lc.nw; k++) {
            if (lc.w[k].inner) continue;
            double side = lc.wd[k] - lc.ww[k] - ROOF_SIDE;
            double bottom = lc.wf[k] - lc.wsl[k], cap = bottom - DARK_ROOF + Math.max(0, side) * 1.5;
            if (cap >= bottom) continue;
            // only a roof under the slab (a way that bridges a cavern's air, or passes in its wall, leaves it as it is)
            if (lc.aC > lc.aF && bottom >= lc.aC - 1 && cap < lc.aC) lc.aC = Math.max(lc.aF, cap);
            if (lc.bC > lc.bF && bottom >= lc.bC - 1 && cap < lc.bC) lc.bC = Math.max(lc.bF, cap);
        }
    }

    /** How near (blocks) an open span may come to a pillar's foot or top and still lay it bare: roughness and interpolation. */
    static final double PILLAR_END_MARGIN = 2;

    /**
     * What share of an end's columns an open span must lay bare for the pillar to go, unless one of them lies within
     * the pillar's radius of its centre. Fewer, all out on the flare of its foot or top, leave a lip over the void on an
     * end the rock still holds: a pillar the rift only grazes stands. (For a straight edge of the void the share and
     * the radius agree: a fifth of the end's disc reaches about one radius in from its rim.)
     */
    static final double PILLAR_BARED_SHARE = 0.2;

    /**
     * The deep dark pillars a Blue Reaches cell does not stand ({@link NetDark.Plan#drops}): those the rift, or the
     * city's hall, would leave in mid-air. A pillar's rock is clipped to its tier's floor - 3 and ceiling + 3 (0.2.0),
     * and the rift opens far below and above both, so a pillar inside it floats, or hangs or stands with a sawn-off end
     * (until 0.5.1 the view breaker's spires tied the worst of them to the rift's floor and ceiling; Taran had the
     * spires go, 2026-11-02, for the long views and the natural walls). The rift's own shape stays as it is: carving
     * the pillars with it would leave slivers and half columns at its edge instead. A pillar goes when the rift's open
     * span (as canyonCol carves it, with a cavern it opens into, {@link #PLATE}) or the hall's lays one of its ends
     * bare ({@link #pillarBared}): holds the block under its foot or over its top, within {@link #PILLAR_END_MARGIN},
     * at a column of that end within the pillar's radius of its centre, or at {@link #PILLAR_BARED_SHARE} of the end's
     * columns (those its foot's or top's disc covers, within 2.5 radii, where its tier opens, whichever pillar is
     * nearest there). So every pillar left stands on, and hangs from, rock over most of both ends. A pillar the rift
     * only passes beside, or grazes, stands as before, and so does every pillar away from the rift and the hall. No
     * pillar is needed to hold up a way's floor: the ways keep off the rift's void and the caverns' air (NetPlanner's
     * clear and darkDoor). A pillar a way's walking core cuts into goes too ({@link #wayCuts}, 0.6.2).
     *
     * <p>A cell only drops pillars whose centre lies at least 2.5 of the tier's largest radii inside it: no column of
     * another cell can see such a pillar (neither its rock nor its being nearer than one whose rock would reach the
     * column), so both sides of a seam build the same pillars, and every column needs only its own cell's plan. The
     * pillars nearer an edge reach at most 30 blocks in (tier B 32.5). The hall keeps 88 blocks inside its cell. The
     * rift's centre line keeps 72, and the rift opens at most its half width (42 at most with the default layers) and
     * 2 more from it, so it may come as near as 28 blocks to the edge in theory; measured over 240 cells it came no
     * nearer than 36, and none of the 7,477 pillars those cells kept by their edges had an end it laid bare. The ways
     * keep {@code EDGE_MARGIN} inside their cell, so a pillar they cut is in it. Runs as the plan's last step, when the
     * rift, the hall, the ways and the regions kept are final.
     */
    void dropExposedPillars(Net n) {
        NetDark.Plan p = n.dark;
        if (p == null) return;
        Route rift = n.canyons.isEmpty() ? null : n.canyons.get(0);
        List<Chamber> halls = new ArrayList<>();
        for (Chamber c : n.ch) if (c.keep && c.kind == Kind.CITY) halls.add(c);
        List<Way> ways = new ArrayList<>();
        for (Way wy : n.ways) if (!wy.inner && wy.n > 0) ways.add(wy);
        if (rift == null && halls.isEmpty() && ways.isEmpty()) return;
        // each way's box {x lo, x hi, z lo, z hi}, for wayCuts to pass over the ways far from a pillar
        double[][] box = new double[ways.size()][];
        for (int i = 0; i < box.length; i++) {
            Way wy = ways.get(i);
            double[] b = {1e18, -1e18, 1e18, -1e18};
            for (int j = 0; j < wy.n; j++) {
                b[0] = Math.min(b[0], wy.x[j]); b[1] = Math.max(b[1], wy.x[j]);
                b[2] = Math.min(b[2], wy.z[j]); b[3] = Math.max(b[3], wy.z[j]);
            }
            box[i] = b;
        }
        double riftHalf = 0;
        if (rift != null) for (double h : rift.riftHalf) riftHalf = Math.max(riftHalf, h);
        double x0 = n.cx * cell, z0 = n.cz * cell;
        double[] pc = new double[3], tc = new double[2], span = new double[2], tmp = new double[6];
        long[] out = new long[16];
        int no = 0;
        for (int t = 0; t < 2; t++) {
            double grid = NetDark.PILLAR_GRID[t], inset = NetDark.PILLAR_REACH * NetDark.PILLAR_R_MAX[t];
            for (int gx = (int) Math.floor((x0 + inset) / grid) - 1; gx * grid <= x0 + cell - inset; gx++)
                for (int gz = (int) Math.floor((z0 + inset) / grid) - 1; gz * grid <= z0 + cell - inset; gz++) {
                    if (!dark.gridPillar(t, gx, gz, pc)) continue;
                    if (pc[0] < x0 + inset || pc[0] > x0 + cell - inset || pc[1] < z0 + inset || pc[1] > z0 + cell - inset) continue;
                    if (!pillarBared(p, t, pc, rift, riftHalf, halls, tc, span, tmp) && !wayCuts(p, t, pc, ways, box, tc)) continue;
                    if (no == out.length) out = Arrays.copyOf(out, no * 2);
                    out[no++] = NetDark.pillarKey(t, gx, gz);
                }
        }
        long[] d = Arrays.copyOf(out, no);
        Arrays.sort(d);
        p.drops = d;
    }

    /**
     * Whether the rift or a hall lays the foot or the top of pillar pc = {x, z, radius} of tier t bare enough for it to
     * go ({@link #dropExposedPillars}): at a column of that end within the pillar's radius of its centre, or at
     * {@link #PILLAR_BARED_SHARE} of the end's columns.
     */
    private boolean pillarBared(NetDark.Plan p, int t, double[] pc, Route rift, double riftHalf, List<Chamber> halls,
                                double[] tc, double[] span, double[] tmp) {
        double cx = pc[0], cz = pc[1], pr = pc[2], reach = NetDark.PILLAR_REACH * pr;
        // the rift opens only within its half width + 2 of the centre line (ledge noise), the hall inside its R()
        boolean nearRift = rift != null && rift.nearest(cx, cz, tmp) && tmp[0] - reach < riftHalf + 4;
        boolean nearHall = false;
        for (Chamber c : halls) if (Math.hypot(cx - c.x, cz - c.z) < c.R() + reach + 4) nearHall = true;
        if (!nearRift && !nearHall) return false;
        // per end, foot then top: the columns it covers, how many of them are bared, and whether one within pr is
        double[] ts = new double[4], tc2 = new double[2];
        int footN = 0, footBared = 0, topN = 0, topBared = 0;
        boolean footCore = false, topCore = false;
        for (int x = (int) Math.floor(cx - reach); x <= (int) Math.ceil(cx + reach); x++) {
            for (int z = (int) Math.floor(cz - reach); z <= (int) Math.ceil(cz + reach); z++) {
                double pd = Math.hypot(x - cx, z - cz);
                if (pd >= reach) continue;
                double o = dark.open(t, x, z);
                if (o <= 0) continue;
                double band = NetDark.band(NetDark.seamDist(x + 0.5, z + 0.5, cell));
                if (!dark.tierAt(p, t, x, z, o, band, NetDark.fade(p.geodes, x, z), tc)) continue;
                double F = tc[0], C = tc[1];
                // the pillar is widest at its ends: a column its ends miss holds none of it
                boolean foot = pillarR(pr, F - 3, F, C) > pd, top = pillarR(pr, C + 3, F, C) > pd;
                if (!foot && !top) continue;
                boolean footBare = false, topBare = false;
                if (nearRift && rift.nearest(x, z, tmp)) {
                    double s = tmp[1];
                    if (riftCut(rift, x, z, s, tmp[2], rift.at(rift.riftTop, s), rift.at(rift.riftHalf, s), span)) {
                        // the rift with the cavern it opens into where a tier's floor lies within PLATE over its vault
                        tierSpans(p, x, z, ts, tc2);
                        double hi = joinedTop(span[1], ts);
                        footBare = endBared(span[0], hi, F, C, foot, false);
                        topBare = endBared(span[0], hi, F, C, false, top);
                    }
                }
                for (Chamber c : halls) {
                    double e = e(c, x, z);
                    if (e >= 1) continue;
                    double lo = floorAt(c, e, x, z), hi = ceilAt(c, e, x, z);
                    footBare |= endBared(lo, hi, F, C, foot, false);
                    topBare |= endBared(lo, hi, F, C, false, top);
                }
                if (foot) { footN++; if (footBare) { footBared++; footCore |= pd < pr; } }
                if (top) { topN++; if (topBare) { topBared++; topCore |= pd < pr; } }
            }
        }
        return endLaidBare(footN, footBared, footCore) || endLaidBare(topN, topBared, topCore);
    }

    /**
     * Whether a way's walking core cuts into pillar pc = {x, z, radius} of tier t ({@link #dropExposedPillars}): passes
     * within the pillar's waist (0.75 radii, the column every height of it keeps) and {@link #WAY_CORE}, its tunnel
     * reaching into the pillar's height (its floor - 3 to its ceiling + 3). The pillar's rock is laid over the ways'
     * tunnels and only the core opened through it again ({@link #mustOpen}), so such a pillar stood with a squeeze hole
     * through its foot, and where the corners' interpolation thinned its waist away a piece of it hung from nothing
     * (0.6.2: a 134-block foot a way on the cavern's floor cut loose, a 12-block sliver over a way that ran just under
     * its cavern's floor; over 36 cells 5.6% of the pillars standing in the open had a way through them so). The ways
     * meet pillars in their own caverns, which dark.hit leaves them, and in those a last walking tunnel may cross.
     */
    private boolean wayCuts(NetDark.Plan p, int t, double[] pc, List<Way> ways, double[][] box, double[] tc) {
        double cx = pc[0], cz = pc[1], pr = pc[2], reach = 0.75 * pr + WAY_CORE;
        double F = Double.NaN, C = Double.NaN;
        for (int i = 0; i < box.length; i++) {
            double[] b = box[i];
            if (cx < b[0] - reach || cx > b[1] + reach || cz < b[2] - reach || cz > b[3] + reach) continue;
            Way wy = ways.get(i);
            for (int j = 0; j < wy.n; j++) {
                int a = Math.max(0, j - 1);
                // the way's line nearest the pillar's axis, on segment a..j
                double ax = wy.x[a], az = wy.z[a], dx = wy.x[j] - ax, dz = wy.z[j] - az, len2 = dx * dx + dz * dz;
                double u = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((cx - ax) * dx + (cz - az) * dz) / len2));
                if (Math.hypot(cx - (ax + dx * u), cz - (az + dz * u)) >= reach) continue;
                if (Double.isNaN(F)) {
                    // the pillar's height span at its axis (its rock is clipped to each column's tier floor - 3 and
                    // ceiling + 3; dropExposedPillars only asks of pillars whose tier opens round them)
                    double o = dark.open(t, cx, cz);
                    if (o <= 0 || !dark.tierAt(p, t, cx, cz, o, NetDark.band(NetDark.seamDist(cx + 0.5, cz + 0.5, cell)), NetDark.fade(p.geodes, cx, cz), tc)) return false;
                    F = tc[0];
                    C = tc[1];
                }
                double f = wy.f[a] + (wy.f[j] - wy.f[a]) * u, h = wy.hgt[a] + (wy.hgt[j] - wy.hgt[a]) * u;
                if (f < C + 3 && f + h > F - 3) return true;
            }
        }
        return false;
    }

    /** How far a way's walking core reaches from its line (blocks): its half width, 2.4 ({@link #pathCore}). */
    static final double WAY_CORE = 2.4;

    /**
     * How thick (blocks) the shelf between the rift's vault and a deep dark tier's floor over it must be to stay: a
     * thinner one goes, and the rift and the cavern open into one ({@link #layerDensity}). Roughness ate such shelves
     * into plates nothing held (0.6.2: four pieces of 80 to 273 blocks over 100 cells, where the shelf was 10 blocks
     * thick or less), and taking them adds no rock: the view from the cavern down the rift opens.
     */
    static final double PLATE = 10;

    /**
     * The top of the rift's open span at a column, whose ceiling is ce, once a tier whose floor lies within
     * {@link #PLATE} over it joins it ({@link #tierSpans}' {aF, aC, bF, bC}).
     */
    static double joinedTop(double ce, double[] ts) {
        return Math.max(joinedTop(ce, ts[0], ts[1]), joinedTop(ce, ts[2], ts[3]));
    }

    static double joinedTop(double ce, double F, double C) {
        return C > F && C > ce && F - ce < PLATE ? C : ce;
    }

    /** The deep dark tiers' open spans at a column as plan p keeps them: out = {aF, aC, bF, bC}, SOLID / -SOLID where shut. */
    void tierSpans(NetDark.Plan p, int x, int z, double[] out, double[] tc) {
        out[0] = out[2] = SOLID;
        out[1] = out[3] = -SOLID;
        if (p == null) return;
        double band = NetDark.band(NetDark.seamDist(x + 0.5, z + 0.5, cell)), fade = -1;
        for (int k = 0; k < 2; k++) {
            double o = dark.open(k, x, z);
            if (o <= 0) continue;
            if (fade < 0) fade = NetDark.fade(p.geodes, x, z);
            if (!dark.tierAt(p, k, x, z, o, band, fade, tc)) continue;
            out[2 * k] = tc[0];
            out[2 * k + 1] = tc[1];
        }
    }

    /** The share of a natural bridge's deck columns that must reach rock for it to stand ({@link #dropLooseBridges}). */
    static final double BRIDGE_HELD_SHARE = 0.05;

    /**
     * How thick (blocks) the run of rock round a block of a bridge's deck must be for the block to hold the bridge
     * ({@link #bridgeHeld}): from the nearest span the rift or a tier opens under it to the nearest over it. Roughness
     * (up to 5.4 blocks off either span) and the 8-block steps of the interpolation thin a thinner run to nothing: a
     * run of 12 between the rift's floor and a cavern's roof under it held a bridge that hung as 507 blocks (0.6.2).
     */
    static final double BRIDGE_HOLD = 20;

    /**
     * Blue Reaches: the rift's natural bridges that nothing would hold (0.6.2). A natural bridge is drawn at a height
     * between the water + 18 and the rift's top - 16, and its deck reaches 4 blocks past the rift's half width so that
     * its ends sit in the walls. But where a deep dark tier opens the walls at that height, or where the bridge lies at
     * a bend of the centre line (its deck is the columns whose nearest point on the line is the bridge's, and at a bend
     * those can stop short of a wall), it was rock nothing held: over 100 cells nine of 105 natural bridges, each with
     * under 3% of its deck's columns reaching rock. A natural bridge with under {@link #BRIDGE_HELD_SHARE} of its
     * deck's columns reaching rock that neither the rift (with the caverns it opens into, {@link #PLATE}) nor a tier
     * opens, in a run of {@link #BRIDGE_HOLD}, goes: over 95 cells 20 of 103, of which 9 had hung as 87 to 2,404 blocks
     * and most of the rest lay in a cavern's air. It is not drawn again at another height: there it crossed what
     * these spans leave out (a way, a room, a ledge path) and hung cut off from the end that held it (1,191 blocks). The
     * bridges the ledge path crosses on are its slab's and stay. The rift keeps 72 blocks inside its cell, so a
     * bridge's columns are its own cell's. Runs at the plan's end, when the rift and the regions kept are final.
     */
    void dropLooseBridges(Net n) {
        if (n.canyons.isEmpty()) return;
        Route r = n.canyons.get(0);
        NetDark.Plan p = n.dark;
        double[] tmp = new double[6], span = new double[2], ts = new double[4], tc = new double[2];
        List<Route.Bridge> stand = new ArrayList<>();
        for (Route.Bridge b : r.bridges) {
            if (r.crossings.contains(b.s())) { stand.add(b); continue; }
            // the deck's columns, as canyonCol and mustStay make them: {q, rift floor, joined top, aF, aC, bF, bC}
            double[] c = r.pointAt(b.s());
            int reach = (int) Math.ceil(b.span() + 2 * b.halfAlong() + 2);
            List<double[]> cols = new ArrayList<>();
            for (int x = (int) Math.floor(c[0]) - reach; x <= (int) Math.ceil(c[0]) + reach; x++)
                for (int z = (int) Math.floor(c[1]) - reach; z <= (int) Math.ceil(c[1]) + reach; z++) {
                    if (!r.nearest(x, z, tmp)) continue;
                    double s = tmp[1], v = tmp[2], along = Math.abs(s - b.s()), across = Math.abs(v);
                    if (along >= b.halfAlong() + 6 || across >= b.span()) continue;
                    double q = across / b.span();
                    if (b.halfAlong() * (1 + 0.7 * q * q) - along <= 0) continue;
                    double rf = SOLID * 9, rc = -SOLID * 9;
                    tierSpans(p, x, z, ts, tc);
                    if (riftCut(r, x, z, s, v, r.at(r.riftTop, s), r.at(r.riftHalf, s), span)) { rf = span[0]; rc = joinedTop(span[1], ts); }
                    cols.add(new double[]{q, rf, rc, ts[0], ts[1], ts[2], ts[3]});
                }
            if (bridgeHeld(cols, b.y())) stand.add(b);
        }
        r.bridges.clear();
        r.bridges.addAll(stand);
    }

    /**
     * Whether a bridge's deck at height y, over its columns ({@link #dropLooseBridges}), reaches rock at its held share:
     * a block of the deck that neither the rift nor a tier opens, in a run of rock at least {@link #BRIDGE_HOLD} thick.
     * A thinner run is a shelf between two open spans (a terrace of the rift's floor over a cavern's roof), which
     * roughness holes as it holed the plates.
     */
    static boolean bridgeHeld(List<double[]> cols, double y) {
        int held = 0;
        for (double[] c : cols) {
            double q = c[0], deck = y + 1.5 * q * q, thick = 3.0 + 9.0 * q * q * q;
            for (double yy = Math.ceil(deck - thick + 1); yy <= deck - 1; yy++) {
                double below = -1e9, above = 1e9;
                boolean open = false;
                for (int k = 1; k < 7; k += 2) {
                    double lo = c[k], hi = c[k + 1];
                    if (hi <= lo) continue;
                    if (yy > lo && yy < hi) open = true;
                    else if (hi <= yy) below = Math.max(below, hi);
                    else above = Math.min(above, lo);
                }
                if (!open && above - below >= BRIDGE_HOLD) { held++; break; }
            }
        }
        return held >= BRIDGE_HELD_SHARE * cols.size();
    }

    /** True when an end's bared columns (of cols it covers) reach within the pillar's radius or make up its bared share. */
    static boolean endLaidBare(int cols, int bared, boolean core) {
        return bared > 0 && (core || bared >= PILLAR_BARED_SHARE * cols);
    }

    /** True when an open span lo..hi reaches the block under a pillar's foot (F - 3) or over its top (C + 3). */
    static boolean endBared(double lo, double hi, double F, double C, boolean foot, boolean top) {
        double m = PILLAR_END_MARGIN;
        return (foot && lo < F - 3 + m && hi > F - 3 - m) || (top && hi > C + 3 - m && lo < C + 3 + m);
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
            if (riftCut(r, x, z, lc.rS, lc.rV, lc.rTop, lc.rHalfTop, tmp)) { lc.rrF = tmp[0]; lc.rrC = tmp[1]; }
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
     * The rift's open span at a column as canyonCol carves it, from the column's nearest point on the centre line (arc
     * length s, signed distance sv, from {@link Route#nearest}) and the rift's top and half width there: out = {floor,
     * ceiling}; false where the rift is shut.
     */
    boolean riftCut(Route r, double x, double z, double s, double sv, double top, double halfTop, double[] out) {
        double v = Math.abs(sv) + 2.0 * ledgeN.noise2(x / 16.0, z / 16.0);
        double step = 11 + 3 * ledgeN.noise2(s / 90.0, 4.4);
        double fl = Terrain.riftFloor(Math.max(0, v), r.W - 2, top, halfTop, r.streamHalf, step);
        double ce = top + 7 * Math.max(0, 1 - (v / halfTop) * (v / halfTop));
        double endFade = Terrain.smooth((Math.min(s, r.length - s) - 10) / 60.0);
        ce = fl + (ce - fl) * endFade;
        if (ce <= fl) return false;
        out[0] = fl;
        out[1] = ce;
        return true;
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
            default -> throw new IllegalArgumentException("layer " + li + " is a noise field: it has no plan");
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

    /** A tunnel's cross-section at a column: its vault (section 5). Negative inside. */
    static double wayDist(LCol lc, int k, int y) {
        return vault(lc.wd[k], y, lc.wf[k], lc.ww[k], lc.wh[k]);
    }

    /** A perched pool of water held in a chamber: a lake over the water table, or the pool at a well's foot. */
    static boolean pool(Chamber c) {
        return (c.kind == Kind.LAKE || c.kind == Kind.WELL) && !Double.isNaN(c.lakeY);
    }

    double chamberDist(LCol lc, int k, int y) {
        double d = Math.max(lc.F[k] - y, y - lc.C[k]);
        return Math.max(d, wallTerm(lc.c[k], lc.e[k]));
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
            // a tier whose floor lies within PLATE over the rift's vault opens into the rift: one span from the rift's
            // floor to the cavern's ceiling, deep enough that no roughness stands in it where the shelf was
            if (lc.r != null && lc.rOn && lc.rrC > lc.rrF) {
                double jt = Math.max(joinedTop(lc.rrC, lc.aF, lc.aC), joinedTop(lc.rrC, lc.bF, lc.bC));
                if (jt > lc.rrC) d = Math.min(d, Math.max(lc.rrF - y, y - jt));
            }
        }
        if (d > -12 && d < 12) {
            // the deep dark's floors stay walkable: a third of the roughness within a few blocks of a tier's floor
            double rs = 1;
            if (lc.darkOn && ((lc.aC > lc.aF && y > lc.aF - 3 && y < lc.aF + 4) || (lc.bC > lc.bF && y > lc.bF - 3 && y < lc.bF + 4))) rs *= DARK_FLOOR_ROUGH;
            if (lc.ns > 0) rs *= mouthFade(lc, y);
            if (rs > 0) d += rs * rough(li, x, y, z);
        }
        if (lc.geoN > 0) d = Math.min(d, geodeDist(lc, x, y, z));
        if (lc.darkOn) {
            if (lc.aC > lc.aF && lc.aPd < lc.aPr * NetDark.PILLAR_REACH) d = Math.max(d, pillarSolid(lc.aPd, lc.aPr, y, lc.aF, lc.aC));
            if (lc.bC > lc.bF && lc.bPd < lc.bPr * NetDark.PILLAR_REACH) d = Math.max(d, pillarSolid(lc.bPd, lc.bPr, y, lc.bF, lc.bC));
        }
        d = Math.max(d, mustStay(lc, x, y, z));
        if (top - y < Terrain.SEAL) d = Math.max(d, Terrain.SEAL - (top - y));
        if (y - bot < Terrain.SEAL) d = Math.max(d, Terrain.SEAL - (y - bot));
        Plans.Gate en = lc.en, ex = lc.ex;
        if (y < W + 1) {
            // the exit passage stays dry: a shell of rock round it under the water table
            double pxz = ex.passageXZ(x, y, z);
            if (pxz < ex.radius * 2.4 + 4) d = Math.max(d, ex.radius * 1.45 + 3.5 - pxz);
        }
        d = Math.min(d, mustOpen(lc, x, y, z));
        if (lc.enNear && y > en.y1 - 3) d = Math.min(d, passage(en, x, y, z));
        if (lc.exNear && y < ex.y0 + 3) d = Math.min(d, passage(ex, x, y, z));
        for (int k = 0; k < lc.nd; k++) if (y > lc.dg[k].y1 - 3) d = Math.min(d, passage(lc.dg[k], x, y, z));
        return d;
    }

    /**
     * How much of the roughness a block takes round a climb stage's mouth: none within r + 1.5 of the axis from 2 below
     * the stage's top floor to 6 over it, all of it 3 blocks further out. No rough ledge then stands over the hole, where
     * a fall would be longer than the stage, and the rim stays a clean lip (section 10).
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

    double geodeDist(LCol lc, int x, int y, int z) {
        double best = 1e9;
        for (int k = 0; k < lc.geoN; k++) best = Math.min(best, Terrain.geodeSdf(lc.geo[k], lc.geoD2[k], y));
        if (best > 12) return best;
        return best + 0.7 * detail.noise3(x / 6.0, y / 6.0, z / 6.0);
    }

    /** One of 0.2.0's hourglass pillars of a deep dark tier: 0.75 pr across at its waist, flaring to its foot and top. */
    static double pillarSolid(double pd, double pr, double y, double f, double c) {
        if (pr <= 0 || c <= f) return -SOLID;
        double r = pillarR(pr, y, f, c);
        return Math.min(r - pd, Math.min(y - (f - 3), (c + 3) - y));
    }

    /** The radius of a pillar (radius pr at its centre) at height y, where its tier spans f to c: an hourglass. */
    static double pillarR(double pr, double y, double f, double c) {
        double mid = (f + c) * 0.5, half = Math.max(4, (c - f) * 0.5);
        double q = (y - mid) / half;
        return pr * (0.75 + 0.9 * q * q);
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
        for (int k = 0; k < lc.nd; k++) {
            Plans.Gate g = lc.dg[k];
            if (lc.dgD[k] < g.landR) d = Math.max(d, Math.min(Math.min(g.lFloor - y, y - (g.lFloor - 7)), g.landR - lc.dgD[k]));
        }
        if (lc.exitD < ex.landR) d = Math.max(d, Math.min(Math.min(ex.uFloor - y, y - (ex.uFloor - 7)), ex.landR - lc.exitD));
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
    public static final double RIM_GAP = Math.toRadians(40);

    // ---- shelf vines (section 10): ShelfVinesFeature and the voxel harness follow these rules
    /**
     * The vine lines' spacing: a lip that runs on through columns side by side meets one at least every 10 blocks
     * (section 10 asks for one every 6 to 10). Measured on the Blue 2 x 2 cells of seed 8675309: 8 hangs 75,100 vines,
     * 10 61,680 and 16 38,490, and the main component holds 96.3%, 95.7% and 94.5% of the places standing on rock
     * (90.0% with none).
     */
    public static final int SHELF_PERIOD = 10;
    /** A lip's drop: from 2 to this many blocks between the feet on the lip and the floor (or water) below. */
    public static final int SHELF_MAX_DROP = 48;
    /**
     * How far over the feet on the lip the rock a shelf vine hangs from may be. The game's hanging vines need rock over
     * them, so a shelf vine hangs from the ceiling over the lip, past the lip, down to the floor below; the deep dark's
     * tiers are up to about 40 tall, and the rift's open terraces under its far vault get none (its ledge path joins them).
     */
    public static final int SHELF_MAX_UP = 40;

    /**
     * True for the columns a shelf vine may hang in: those on the lines x = 10 i + ox and z = 10 k + oz (ox and oz from
     * the seed).
     */
    public boolean shelfVineColumn(int x, int z) {
        long h = Hashing.mix(seed, 8811, 0, 0);
        int ox = (int) Math.floorMod(h, (long) SHELF_PERIOD), oz = (int) Math.floorMod(h >>> 20, (long) SHELF_PERIOD);
        return Math.floorMod(x - ox, SHELF_PERIOD) == 0 || Math.floorMod(z - oz, SHELF_PERIOD) == 0;
    }

    public static double angleDiff(double a, double b) {
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
        for (int k = 0; k < lc.nd; k++) {
            Plans.Gate g = lc.dg[k];
            if (lc.dgD[k] < g.landR + 4 && y > g.lFloor - 2 && y < g.lCy + g.landHalf + 4)
                d = Math.min(d, landing(lc.dgD[k], g.landR, g.lCy, g.landHalf, g.lFloor, x, y, z));
        }
        if (lc.exitD < ex.landR + 4 && y > ex.uFloor - 2 && y < ex.uCy + ex.landHalf + 4)
            d = Math.min(d, landing(lc.exitD, ex.landR, ex.uCy, ex.landHalf, ex.uFloor, x, y, z));
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
        Plans.Gate en = lc.en, ex = lc.ex;
        boolean enPass = lc.enNear && y > en.y1 - 3, exPass = lc.exNear && y < ex.y0 + 3;
        boolean pass = enPass || exPass;
        for (int k = 0; k < lc.nd && !pass; k++) pass = y > lc.dg[k].y1 - 3;
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
        for (int k = 0; k < lc.nd; k++) if (y > lc.dg[k].y1 - 3) open = Math.min(open, passage(lc.dg[k], x, y, z));
        // Only what changes a block counts: rock that must stay where the interpolation left air, and space that must be
        // open where it left rock. A term that cannot turn the block leaves the interpolated value as it is, so outside
        // the fine span (where none of them can act) skipping the pass gives the same value.
        if (keep <= 0) keep = -SOLID;
        if (open > 0) open = SOLID;
        double d = Math.min(Math.max(c8, keep), open);
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
        if (ex.passageXZ(x, y, z) < ex.radius * 1.45 + 3.0) return Terrain.AIR;
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
            DeepBiome b = lc.c[k].biome;
            if (b == null && lc.c[k].parent >= 0) b = lc.net.ch.get(lc.c[k].parent).biome;
            if (b == null) continue;
            if (b.role == DeepBiome.Role.REFUGE && d < 6) return b;
            if (d < bestD) { bestD = d; best = b; }
        }
        for (int k = 0; k < lc.nw; k++) {
            double d = wayDist(lc, k, y);
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
        if (!hasPlan(li) || li != Terrain.BLUE) return 1e9;
        LCol lc = layer(column(x, z), li);
        return lc.geoN == 0 ? 1e9 : geodeDist(lc, x, y, z);
    }
}
