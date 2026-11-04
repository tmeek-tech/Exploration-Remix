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
    /** The deep dark's seam fins per cell edge (Blue Reaches), per tier; see {@link #seamPillars}. */
    private final Cache<Long, NetSight.Shapes[]> seamCache;
    /** What the seams' view breakers found, per tier, over every edge planned so far (for the probes). */
    final NetSight.Stats[] seamStats = {new NetSight.Stats(), new NetSight.Stats()};
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
        seamCache = CacheBuilder.newBuilder().maximumSize(PORTAL_CACHE).build();
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
     * passages and stubs of the plans (the neighbouring cells' too), the rift's ledge path, and the Basalt Galleries'
     * Causeway column paths ({@link #causewayPaths}). A way that passes the column more than once (a helix's turns, a
     * hairpin) is listed once per pass. No cap (section 12.1).
     */
    public WayLines wayLinesAt(int li, double x, double z, double reach) {
        WayLines out = LINES.get();
        out.n = 0;
        int cx = cellOf(x), cz = cellOf(z);
        for (int i = -1; i <= 1; i++) for (int k = -1; k <= 1; k++) {
            double ex = Math.max(0, Math.max((cx + i) * cell - x, x - (cx + i + 1) * cell));
            double ez = Math.max(0, Math.max((cz + k) * cell - z, z - (cz + k + 1) * cell));
            if (Math.hypot(ex, ez) > reach + Net.APRON) continue;
            Net n = net(li, cx + i, cz + k);
            linesOf(n, x, z, reach, out);
            if (li == Terrain.GALLERIES) causewayPaths(n, x, z, reach, out);
        }
        return out;
    }

    /**
     * The Causeway's column path as a walking line, for the features that keep off the ways ({@code Walkway}) and for
     * nothing else. No way is laid on the path ({@code NetPlanner.hallWalks}: a way's slab would fill the gaps between
     * its columns), so without this the lava pools, lava falls and column clusters that keep off every way would land on
     * its column tops. The line runs down the path's middle (sv = pathV, in the hall's warped coordinates, as
     * {@link #hexTop} lays the columns out) between the end ramps' feet. Its floor is one block under the path's top
     * block, so the corridor (four blocks up from there) holds the top blocks themselves (a pool or a fall's sump in
     * them), the one-block steps either way from one column top to the next, and the room over them; 0.6 of its
     * half-width covers the path's two rows of columns. It is not in {@link #carvedLinesAt}: nothing may cut a corridor
     * along it.
     */
    private void causewayPaths(Net n, double x, double z, double reach, WayLines out) {
        for (Chamber c : n.ch) {
            if (c.kind != Kind.CAUSEWAY || !c.keep) continue;
            double r = c.R() + reach;
            if (Math.abs(x - c.x) > r || Math.abs(z - c.z) > r) continue;
            double[] uv = hallUV(c, x, z);
            double endU = c.rx - c.endW, u = uv[0], sv = uv[1] * c.side;
            double d = Math.hypot(Math.max(0, Math.abs(u) - endU), sv - c.pathV);
            if (d > reach) continue;
            out.add(d, Math.round(pathHeight(c, Math.max(-endU, Math.min(endU, u)))) - 1, 1.75 * c.hexS / SQ3 / 0.6);
        }
    }

    /**
     * The lines of {@link #wayLinesAt} from the plan of the column's own cell only: the ways the terrain carved in this
     * column (the density reads that plan alone; a portal stub is in both cells' plans). For the pass that re-opens
     * them, which must never cut a corridor through rock that no way carved.
     */
    public WayLines carvedLinesAt(int li, double x, double z, double reach) {
        WayLines out = LINES.get();
        out.n = 0;
        linesOf(net(li, cellOf(x), cellOf(z)), x, z, reach, out);
        return out;
    }

    private void linesOf(Net n, double x, double z, double reach, WayLines out) {
        for (Way wy : n.ways) {
            if (x < wy.minX - reach || x > wy.maxX + reach || z < wy.minZ - reach || z > wy.maxZ + reach) continue;
            // every local minimum of the distance along the way, as the column lookup finds them (compute)
            double bestD = 1e9, bestFi = 0, prevD = 1e9, acc = 0;
            boolean tracking = true;
            for (int i = 0; i + 1 < wy.n; i++) {
                double ax = wy.x[i], az = wy.z[i], dx = wy.x[i + 1] - ax, dz = wy.z[i + 1] - az;
                double len2 = dx * dx + dz * dz, seg = Math.sqrt(len2);
                // the walking line ends at walkTo (a blind tube ending in lava stops 3 blocks short of it)
                if (acc >= wy.walkTo) break;
                double tMax = acc + seg > wy.walkTo ? (wy.walkTo - acc) / seg : 1;
                acc += seg;
                double t = len2 < 1e-9 ? 0 : ((x - ax) * dx + (z - az) * dz) / len2;
                t = t < 0 ? 0 : (t > tMax ? tMax : t);
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

    /**
     * The Caldera vents of the Basalt Galleries within {@code r} of (x, z) (their edge within reach), from the plans of
     * every cell the circle touches: where the runtime's eruptions break out. Empty for the other layers. A read-only
     * query; it plans the cells it needs, so keep {@code r} under a cell.
     */
    public List<Net.Vent> ventsNear(int li, double x, double z, double r) {
        List<Net.Vent> out = new ArrayList<>();
        if (li != Terrain.GALLERIES) return out;
        for (int cx = cellOf(x - r); cx <= cellOf(x + r); cx++) for (int cz = cellOf(z - r); cz <= cellOf(z + r); cz++)
            for (Net.Vent v : net(li, cx, cz).vents) if (Math.hypot(v.x - x, v.z - z) <= r + v.r) out.add(v);
        return out;
    }

    /**
     * The hall of the Basalt Galleries over column (x, z) (a column hall, the Caldera, the Causeway or a rift): {floor,
     * ceiling, the bed of its lava} there, the floor being the ledge's or terrace's top (or the lava's bed), or null when
     * the column is not inside one. For the decoration, which aims its hanging columns and lava falls at the halls'
     * vaults and walls.
     */
    public double[] hallSpanAt(int li, int x, int z) {
        if (li != Terrain.GALLERIES) return null;
        LCol lc = layer(column(x, z), li);
        for (int k = 0; k < lc.nc; k++) if (lc.c[k].hall() && lc.e[k] < 1) return new double[]{lc.F[k], lc.C[k], lc.c[k].base};
        return null;
    }

    /**
     * The Deep Blue Rifts that may pass within {@code reach} of (x, z) (sideways): those of the Blue Reaches cells
     * that reach that near, for the creatures that travel them. Their plans are made if they have not been.
     */
    public List<Route> riftsNear(double x, double z, double reach) {
        List<Route> out = new ArrayList<>();
        if (Terrain.BLUE >= shape.layerCount) return out;
        for (int cx = cellOf(x - reach); cx <= cellOf(x + reach); cx++)
            for (int cz = cellOf(z - reach); cz <= cellOf(z + reach); cz++)
                for (Route r : net(Terrain.BLUE, cx, cz).canyons) if (r.rift) out.add(r);
        return out;
    }

    /** The plan of layer li in cell (cx, cz), for the dev tools. */
    public Net network(int li, int cx, int cz) { return net(li, cx, cz); }

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
                boolean set = c.set || c.kind == Kind.CITY || c.kind == Kind.LAKE || c.kind == Kind.TUBEHUB || c.kind == Kind.SPRING;
                Place p = new Place(c.x, c.z, c.R(), set, c.kind.name().toLowerCase(java.util.Locale.ROOT) + " #" + c.id + " of cell " + cx + " " + cz);
                // a hall's walking floors are its ledges and terraces, out by the walls: a fourth ring there
                for (int ring = 0; ring < (c.hall() ? 4 : 3); ring++) {
                    int k = ring == 0 ? 1 : ring == 3 ? 8 : 6;
                    double f = ring == 0 ? 0 : ring == 1 ? 0.45 : ring == 2 ? 0.75 : 0.88;
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
                            if (c.hall() && fl < shape.waterY(li) + 1) continue; // in the lava
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
        int cx = cellOf(x), cz = cellOf(z);
        Net n = net(li, cx, cz);
        int kept = 0;
        for (Chamber c : n.ch) if (c.keep) kept++;
        out.add(String.format(java.util.Locale.ROOT, "network: cell %d %d of layer %d, %d chambers, %d ways, %d climb stages, %d portals%s",
                cx, cz, li + 1, kept, n.ways.size(), n.shafts.size(), n.portals.size() - n.droppedPortals.size(), n.canyons.isEmpty() ? "" : ", the rift"));
        if (!n.pools.isEmpty() || !n.magmaEnds.isEmpty()) {
            Net.Pool ch = null;
            for (Net.Pool p : n.pools) if (p.channel) ch = p;
            out.add(String.format(java.util.Locale.ROOT, "  lava: %d blind tubes end in lava, %d on magma%s", n.pools.size() - (ch == null ? 0 : 1), n.magmaEnds.size(),
                    ch == null ? ", no channel in the great hall" : String.format(java.util.Locale.ROOT, ", a channel %.0f long in the great hall from %.0f %d %.0f to %.0f %.0f",
                            ch.len, ch.ax, ch.y, ch.az, ch.bx, ch.bz)));
        }
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
            out.add(String.format(java.util.Locale.ROOT, "  nearest way: #%d, %s%s%s, %s, %.0f blocks off its line, its floor y %.0f",
                    bw.id, bw.kind.name().toLowerCase(java.util.Locale.ROOT), bw.inner ? " (inner walk)" : "", bw.round ? " (round tube)" : "", ends, bDist, bFloor));
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
        double yLo = W + (li == Terrain.BLUE ? 14 : li == Terrain.GALLERIES ? Terrain.surge(li) + 3 : 6), yHi = T - 52;
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

    // ================================================================================================ seam pillars
    /** Half the width of the strip a seam's own fins are planned in, and how far past its ends its lines reach. */
    static final double SEAM_STRIP = 24, SEAM_EXT = 150;
    /** Lines within this many blocks of a seam are the seam's to cut. */
    static final double SEAM_FOCUS = 13.5;

    /**
     * The deep dark's fins on one cell edge (Blue Reaches): the west edge of cell (ex, ez) when {@code xEdge}, else its
     * north edge. A cell's own view breaker ({@link NetPlanner#sightBreak}) keeps its shapes and their reach inside the
     * cell, at least 12 blocks from its edges, so lines that run along a seam are cut here instead: lines within 14
     * degrees of the seam that pass within {@link #SEAM_FOCUS} of it, over 0.2.0's raw tiers (which both cells carve
     * within 16 blocks of the seam), get fins standing across the seam, clear of the edge's portals. A pure function of
     * the seed and the edge, so both cells carve the same fins.
     */
    NetSight.Shapes[] seamPillars(boolean xEdge, int ex, int ez) {
        long k = Hashing.mix(seed, 7700 + (xEdge ? 1 : 0), ex, ez);
        try {
            return seamCache.get(k, () -> makeSeamPillars(xEdge, ex, ez, k));
        } catch (ExecutionException | UncheckedExecutionException e) {
            Throwable t = e.getCause();
            if (t instanceof RuntimeException r) throw r;
            if (t instanceof Error r) throw r;
            throw new IllegalStateException(e);
        }
    }

    /** A seam fin's half length (blocks) and radius. */
    static final double SEAM_FIN_HALF = 12, SEAM_FIN_R = 4;

    private NetSight.Shapes[] makeSeamPillars(boolean xEdge, int ex, int ez, long key) {
        double line = (xEdge ? ex : ez) * cell, u0 = (xEdge ? ez : ex) * cell;
        double g = 2;
        int nAcross = (int) Math.round(2 * SEAM_STRIP / g), nAlong = (int) Math.round((cell + 2 * SEAM_EXT) / g);
        double rx0 = xEdge ? line - SEAM_STRIP : u0 - SEAM_EXT, rz0 = xEdge ? u0 - SEAM_EXT : line - SEAM_STRIP;
        int nx = xEdge ? nAcross : nAlong, nz = xEdge ? nAlong : nAcross;
        List<Portal> portals = portalsOfEdge(Terrain.BLUE, xEdge, ex, ez);
        NetSight.Shapes[] out = new NetSight.Shapes[2];
        double[] t2 = new double[2];
        double foot = 1.65 * SEAM_FIN_R;
        for (int t = 0; t < 2; t++) {
            NetSight.Raster ras = new NetSight.Raster(rx0, rz0, g, nx, nz);
            float[] oe = new float[nx * nz], hgt = new float[nx * nz];
            for (int i = 0; i < nx; i++) for (int kk = 0; kk < nz; kk++) {
                double x = ras.px(i), z = ras.pz(kk);
                double o = dark.open(t, x, z);
                if (o <= 0) continue;
                dark.tier(o, dark.floor0(t, x, z), dark.ceil0(t, x, z), t2);
                oe[i * nz + kk] = (float) o;
                hgt[i * nz + kk] = (float) (t2[1] - t2[0]);
                if (t2[1] - t2[0] >= 3) ras.see[i * nz + kk] = 1;
            }
            List<double[]> grid = new ArrayList<>();
            dark.gridPillars(t, rx0, rz0, rx0 + nx * g, rz0 + nz * g, grid);
            NetSight.Shapes blk = new NetSight.Shapes();
            for (double[] pl : grid) {
                int i = (int) Math.floor((pl[0] - rx0) / g), kk = (int) Math.floor((pl[1] - rz0) / g);
                if (i >= 0 && kk >= 0 && i < nx && kk < nz && ras.see[i * nz + kk] != 0) blk.add(pl[0], pl[1], pl[0], pl[1], pl[2]);
            }
            // candidates: fins across the seam on the edge's own stretch, standing on a cavern's core with room round them
            NetSight.Shapes cand = new NetSight.Shapes();
            for (double d = -2; d <= 2; d += 2) for (double u = u0 + 2; u < u0 + cell; u += 4) {
                boolean ok = true;
                for (double v = -SEAM_FIN_HALF; v <= SEAM_FIN_HALF && ok; v += 4) {
                    double x = xEdge ? line + d + v : u, z = xEdge ? u : line + d + v;
                    int i = (int) Math.floor((x - rx0) / g), kk = (int) Math.floor((z - rz0) / g);
                    if (oe[i * nz + kk] < NetDark.CORE || hgt[i * nz + kk] < 12) ok = false;
                    else if (clearance(ras, i, kk, foot + 5) < foot + 4) ok = false;
                }
                if (!ok) continue;
                for (Portal pt : portals) if (Math.abs(u - pt.along) < pt.vestR + foot + 12) ok = false;
                double ax = xEdge ? line + d - SEAM_FIN_HALF : u, az = xEdge ? u : line + d - SEAM_FIN_HALF;
                double bx = xEdge ? line + d + SEAM_FIN_HALF : u, bz = xEdge ? u : line + d + SEAM_FIN_HALF;
                for (int j = 0; j < blk.n && ok; j++)
                    if (NetSight.segSeg(ax, az, bx, bz, blk.ax[j], blk.az[j], blk.bx[j], blk.bz[j]) < 0.9 * 1.65 * (SEAM_FIN_R + blk.r[j])) ok = false;
                if (ok) cand.add(ax, az, bx, bz, SEAM_FIN_R);
            }
            double[] ang = new double[57];
            double base = xEdge ? Math.PI / 2 : 0;
            for (int a = 0; a < ang.length; a++) ang[a] = base + Math.toRadians(-14 + 0.5 * a);
            double cxr = xEdge ? line : u0 + cell / 2, czr = xEdge ? u0 + cell / 2 : line;
            double[] focus = xEdge ? new double[]{line - SEAM_FOCUS, u0, line + SEAM_FOCUS, u0 + cell}
                    : new double[]{u0, line - SEAM_FOCUS, u0 + cell, line + SEAM_FOCUS};
            NetSight.Stats st = new NetSight.Stats();
            int[] ch = NetSight.choose(ras, ang, cxr, czr, 1, focus, blk, cand, NetSight.LMAX, 1.25, 0.9, st);
            synchronized (seamStats) { seamStats[t].add(st); }
            NetSight.Shapes res = new NetSight.Shapes();
            for (int j : ch) res.add(cand.get(j));
            out[t] = res;
        }
        return out;
    }

    /** Blocks from raster cell (i, k) to the nearest closed cell of the raster, up to {@code max}. */
    static double clearance(NetSight.Raster ras, int i, int k, double max) {
        int m = (int) Math.ceil(max / ras.g);
        double best2 = max * max / (ras.g * ras.g);
        for (int di = -m; di <= m; di++) for (int dk = -m; dk <= m; dk++) {
            double d2 = di * di + dk * dk;
            if (d2 >= best2) continue;
            int a = i + di, b = k + dk;
            if (a < 0 || b < 0 || a >= ras.nx || b >= ras.nz) continue;
            if (ras.see[a * ras.nz + b] == 0) best2 = d2;
        }
        return Math.sqrt(best2) * ras.g;
    }

    /** The seam fin of tier t nearest (x, z), when nearer (by distance from its segment less its radius) than out = {d, r}; then out[2] = 1. */
    void seamPillar(int t, double x, double z, double[] out) {
        int sx = (int) Math.round(x / cell), sz = (int) Math.round(z / cell);
        double reach = SEAM_FIN_HALF + 2 + SEAM_FIN_R * 2.5 + 1;
        if (Math.abs(x - sx * cell) < reach) {
            int ez = cellOf(z);
            for (int e = ez - 1; e <= ez + 1; e++) {
                if (z < e * cell - reach || z > (e + 1) * cell + reach) continue;
                nearestOf(seamPillars(true, sx, e)[t], x, z, out);
            }
        }
        if (Math.abs(z - sz * cell) < reach) {
            int ex = cellOf(x);
            for (int e = ex - 1; e <= ex + 1; e++) {
                if (x < e * cell - reach || x > (e + 1) * cell + reach) continue;
                nearestOf(seamPillars(false, e, sz)[t], x, z, out);
            }
        }
    }

    private static void nearestOf(NetSight.Shapes p, double x, double z, double[] out) {
        for (int j = 0; j < p.n; j++) {
            if (Math.abs(x - p.ax[j]) > 30 || Math.abs(z - p.az[j]) > 30) continue;
            double d = p.dist(j, x, z);
            if (d - p.r[j] < out[0] - out[1]) { out[0] = d; out[1] = p.r[j]; out[2] = 1; }
        }
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
            case HALL -> {
                // the lava lake inside the lip, the ledge from the lip to the wall, rising a terrace or two to the wall
                if (e < c.lakeEdge) return c.base;
                double t = (e - c.lakeEdge) / Math.max(0.05, 1 - c.lakeEdge);
                return c.f0 + Terrain.terraced(Math.max(0, c.rise * t * t + 0.7 * floorN.noise2(x / 9.0, z / 9.0) + 0.7), 1);
            }
            case CALDERA -> {
                return calderaFloor(c, e, x, z);
            }
            case CAUSEWAY -> {
                double[] uv = hallUV(c, x, z);
                return causewayFloor(c, uv[0], uv[1] * c.side, x, z);
            }
            case RIFT -> {
                double[] uv = hallUV(c, x, z);
                return riftFloor(c, uv[0], uv[1], x, z);
            }
            default -> {
                // a flight court's stair has no noise: each step along it climbs at most one block
                double v = c.flight ? c.rise * e * e + 1.0 : c.rise * e * e + 1.0 * floorN.noise2(x / 9.0, z / 9.0) + 1.0;
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
            case GALLERY, GREAT, CITY -> p = 4;
            case TERRACE, SPRING, CAVERN, LAVA -> p = 3;
            // a Wasteland refuge (an Ash Hollow) is a dome; refuges elsewhere are other kinds
            case GROTTO -> p = c.refuge ? 2 : 3;
            case WELL, CHIMNEY -> {
                return c.top - 5 * e * e + 2 * ceilN.noise2(x / 9.0, z / 9.0);
            }
            // the Galleries' halls: flat vaults that keep their height out over the ledges by the walls
            case HALL -> p = 8;
            case CAUSEWAY, RIFT -> p = 6;
            default -> p = 2;
        }
        double ep;
        if (p <= 4) ep = p == 2 ? e * e : (p == 4 ? e * e * e * e : e * e * e);
        else {
            double e2 = e * e, e4 = e2 * e2;
            ep = p == 6 ? e4 * e2 : e4 * e4;
        }
        double C = c.f0 + c.h * Math.sqrt(Math.max(0, 1 - ep));
        if (c.rampDrop > 0 && c.kind != Kind.LAKE) C += c.rampDrop * 0.5 * (1 - rampT(c, x, z));
        if (c.pend > 0) {
            double n = pendN.noise2(x / 4.5, z / 4.5);
            if (n > 0.25) C -= c.pend * Math.pow((n - 0.25) / 0.75, 1.3) * 2.2 * Math.min(1, (1 - e) / 0.25);
        }
        return C + 1.5 * ceilN.noise2(x / 13.0, z / 13.0);
    }

    /** A flight court's level foot: the stair starts this many blocks in from the end by the landing. */
    static final double FLIGHT_FOOT = 4;

    /**
     * Where a block stands along a chamber's ramp: 1 at the low end, -1 at the high end, clamped. A flight court's
     * stair starts {@link #FLIGHT_FOOT} blocks in from its low end, so the spoke from the landing ends on a level floor.
     */
    static double rampT(Chamber c, double x, double z) {
        double R = Math.max(c.rx, c.rz);
        double t = ((x - c.x) * c.rampX + (z - c.z) * c.rampZ) / R;
        if (c.flight) t = (t + 1) * R / (R - FLIGHT_FOOT / 2) - 1;
        return t < -1 ? -1 : (t > 1 ? 1 : t);
    }

    /** Wall term: everything closes where the footprint ends. */
    static double wallTerm(Chamber c, double e) {
        return (e - 1) * Math.min(c.rx, c.rz);
    }

    /**
     * The wall term at a column: a hall's scales (e - 1) by its footprint's gradient there ({@link #wallScale}), every
     * other chamber's by its short radius ({@link #wallTerm}).
     */
    static double wallTerm(LCol lc, int k) {
        return lc.c[k].hall() ? (lc.e[k] - 1) * lc.ws[k] : wallTerm(lc.c[k], lc.e[k]);
    }

    /**
     * A hall's wall scale at its local (u, v), footprint coordinate e: the inverse of the footprint's gradient, between
     * the short and the long radius, so (e - 1) times it is about the distance to the wall line at the ends of a long
     * hall as at its sides. With the short radius, the far ends of the Causeway and the rift were barely open even
     * on their centre line, and their lava and ledges came out as blobs of rock.
     */
    static double wallScale(Chamber c, double u, double v, double e) {
        double lo = Math.min(c.rx, c.rz), hi = Math.max(c.rx, c.rz);
        if (e < 0.05) return lo;
        double p = c.sq, a = Math.abs(u) / c.rx, b = Math.abs(v) / c.rz;
        double g = Math.pow(e, 1 - p) * Math.hypot(Math.pow(a, p - 1) / c.rx, Math.pow(b, p - 1) / c.rz);
        return g * hi <= 1 ? hi : g * lo >= 1 ? lo : 1 / g;
    }

    // ------------------------------------------------------------------------------------- the Basalt Galleries' halls
    /** A hall's dry floor, per block: rock this far under it, open this far over it ({@link #hallFloor}). */
    static final double HALL_FLOOR_DEPTH = 8, HALL_FLOOR_ROOM = 6;

    /**
     * True where a hall's floor is kept exactly as planned, per block: its dry floors (over the lava's table) inside
     * 0.96 of the footprint, and a flight court's stair. The interpolation would
     * round every lip and terrace edge and the roughness and the columns' offsets would step the floors by two or
     * three blocks: on floors this wide, each step leaves standing places no one can reach, and on a stair it is a
     * step no one can climb. Rock stays {@link #HALL_FLOOR_DEPTH} under the floor and the air {@link #HALL_FLOOR_ROOM}
     * over it, whatever tunnel ends there (the end of a tunnel's carve reaches a width past its last point, and on a
     * stair it dug a pit); the walls, vaults and the lava's beds keep the interpolated shape. So does the bedrock
     * landing's lava basin over a table shallower than the default (config, W its height), whose bed lies within a
     * few blocks of the seal over the bedrock: interpolated between the seal and the open basin, the bed comes out a
     * block or two high and rough, and stands out of the lava it holds ({@link #fluidAt}) in islands a flood would
     * catch far from any stair.
     */
    static boolean hallFloor(Chamber c, double e, double F, int li, double W) {
        if (c.flight) return e < 0.96 && li == Terrain.GALLERIES;
        if (c.kind == Kind.LAVA) return e < 0.96 && li == Terrain.GALLERIES && c.f0 + HALL_LAVA > W;
        if (!c.hall() || e >= 0.96 || li != Terrain.GALLERIES) return false;
        return F >= c.base + 5;
    }

    /** How steeply the Scoria Canyons' ramps climb from a low ledge to the upper one (blocks up per block along). */
    static final double RIFT_RAMP = 0.72;
    /** Causeway columns: no column at a block (the lava, or a floor of the hall's own there). */
    static final int NO_COLUMN = Integer.MIN_VALUE;
    /** Causeway columns: a column from the lava's bed into the vault. */
    static final int TO_VAULT = Integer.MAX_VALUE;
    /**
     * The roughness's share inside the Basalt Galleries' halls. Their walls stand 40 to 100 tall: the full roughness
     * steps them into ledges of a block or two, and each is a standing place no one can reach (the columns' offsets
     * keep the basalt look).
     */
    static final float HALL_ROUGH = 0.35f;
    private static final double SQ3 = Math.sqrt(3);

    /**
     * A hall's local coordinates at (x, z), warped as {@link #e} warps them: along its long axis and across it. The
     * Causeway's and the rift's zones (ledges, ramps, the river, the column field) are laid out in these, so they
     * follow the walls the footprint draws.
     */
    double[] hallUV(Chamber c, double x, double z) {
        double dx = x - c.x, dz = z - c.z;
        double u = dx * c.cos + dz * c.sin, v = -dx * c.sin + dz * c.cos;
        if (c.warp > 0) {
            double R = Math.max(c.rx, c.rz), sc = R * 0.75 + 6, off = (c.hash & 255) * 0.731;
            u += c.warp * R * warpA.noise2(x / sc + off, z / sc);
            v += c.warp * R * warpB.noise2(x / sc, z / sc + off);
        }
        return new double[]{u, v};
    }

    /**
     * The Caldera's floor: the crater's lava lake inside, its inner wall sheer from the lava's bed to the crest of the
     * rim (W + 15 to W + 28) and undercut under the lip ({@link #shelfUnder}), a shallow moat behind the crest, then
     * 1-block terraces up to the outer ring at the dry level, where the doors are. The terraces climb at most about
     * 0.85 a block ({@code NetPlanner} sizes the crater for that), so every one of them can be walked up from the crest.
     */
    double calderaFloor(Chamber c, double e, double x, double z) {
        double eW = 0.86;
        if (e < c.crestE) return c.base;
        double n = 0.6 * floorN.noise2(x / 8.0, z / 8.0);
        if (e < eW) {
            double t = (e - c.crestE) / (eW - c.crestE);
            double moat = t < 0.3 ? 2.2 * Math.sin(Math.PI * t / 0.3) : 0;
            return c.crest + Terrain.terraced((c.f0 - c.crest) * t - moat + n + 3, 1) - 3;
        }
        return c.f0 + Terrain.terraced(Math.max(0, c.rise * (e - eW) / (1 - eW) + n + 0.6), 1);
    }

    /**
     * The Causeway's floor at local (u, sv), sv across with the wall ledge on its positive side: the ledge along that
     * wall at the dry level; at both ends a ramp of 1-block terraces across the hall from the column path's level on
     * the far side up to the ledge; elsewhere the lava, the columns standing in it ({@link #hexTop}).
     */
    double causewayFloor(Chamber c, double u, double sv, double x, double z) {
        double ledgeV = c.rz - c.ledgeW, endU = c.rx - c.endW;
        double n = 0.5 * floorN.noise2(x / 8.0, z / 8.0);
        if (Math.abs(u) >= endU) {
            double t = (sv - c.pathV) / Math.max(1, ledgeV - c.pathV);
            t = t < 0 ? 0 : (t > 1 ? 1 : t);
            return c.pathEnd + Terrain.terraced(Math.max(0, (c.f0 - c.pathEnd) * t + n * t), 1);
        }
        if (sv >= ledgeV) {
            double t = (sv - ledgeV) / Math.max(1, c.ledgeW);
            return c.f0 + Terrain.terraced(Math.max(0, c.rise * t * t + n + 0.5), 1);
        }
        return c.base;
    }

    /**
     * The Scoria Canyons' floor at local (u, v): the lava river down the middle; beside it on each side a low ledge
     * (12 under the upper one, so it floods) reached by a ramp of 1-block terraces down from the upper ledge along the
     * canyon, and the river again past the low ledge's ends; the upper ledges from there to the walls at the dry level,
     * as far along as they are {@link #RIFT_LEDGE_MIN} wide ({@link #riftLedgeEnd}): past that the river reaches the
     * walls where they close in at the canyon's ends.
     */
    double riftFloor(Chamber c, double u, double v, double x, double z) {
        double av = Math.abs(v);
        if (av < c.riverW || Math.abs(u) > riftLedgeEnd(c)) return c.base;
        double n = 0.5 * floorN.noise2(x / 8.0, z / 8.0);
        if (av < c.riverW + c.lowW) {
            int s = v >= 0 ? 0 : 1;
            double d = (c.rampU[s] - u) * c.rampDir[s], ramp = (c.f0 - c.crest) / RIFT_RAMP;
            if (d < 0 || d > ramp + c.lowLen[s]) return c.base;
            if (d < ramp) return c.crest + Terrain.terraced((c.f0 - c.crest) * (1 - d / ramp), 1);
            return c.crest + Terrain.terraced(Math.max(0, n + 0.5), 1);
        }
        double t = (av - c.riverW - c.lowW) / Math.max(1, c.rz - c.riverW - c.lowW);
        return c.f0 + Terrain.terraced(Math.max(0, c.rise * t * t + n + 0.5), 1);
    }

    /** How far in under a lip over the lava an undercut reaches (blocks): past one interpolation cell. */
    static final double UNDER_REACH = 6;

    /**
     * Under a floor that stands over the lava: the top of the open space between its underside and the lava, or NaN
     * where nothing overhangs. Under a column hall's ledge and the Causeway's wall ledge, from the lip to the wall;
     * under every other lip over the lava, {@link #UNDER_REACH} blocks in: the Causeway's end ramps, the Caldera's
     * crest, the rift's ledges and ramps beside its river. The underside is {@code shelf} under the floor at the lip
     * ({@link #RIFT_LOW_SHELF} under the rift's low ledges) and curves down to the lava, so the floor is thick enough
     * for the 4 x 8 x 4 interpolation and a fall from its lip lands in the lava. Without it, the interpolated cliff
     * leans out at its foot: a floor's height is no distance across a cliff, so the lowest blocks of the face stand
     * three or four out from the lip, a slope of rock over the lava that every fall from the lip lands on.
     */
    double shelfUnder(Chamber c, double e, double x, double z) {
        if (e >= 1.02) return Double.NaN;
        double t, top = c.f0, shelf = c.shelf;
        switch (c.kind) {
            case HALL -> {
                if (e < c.lakeEdge) return Double.NaN;
                t = (e - c.lakeEdge) / (1 - c.lakeEdge);
            }
            case CAUSEWAY -> {
                double[] uv = hallUV(c, x, z);
                double ledgeV = c.rz - c.ledgeW, sv = uv[1] * c.side, au = Math.abs(uv[0]), endU = c.rx - c.endW;
                if (au < endU) {
                    if (sv < ledgeV) return Double.NaN;
                    t = (sv - ledgeV) / Math.max(1, c.ledgeW);
                } else {
                    // an end ramp's face over the column field
                    if (au >= endU + UNDER_REACH) return Double.NaN;
                    t = (au - endU) / UNDER_REACH;
                    top = causewayFloor(c, uv[0], sv, x, z);
                }
            }
            case CALDERA -> {
                double reach = UNDER_REACH / Math.min(c.rx, c.rz);
                if (e < c.crestE || e >= c.crestE + reach) return Double.NaN;
                t = (e - c.crestE) / reach;
                top = c.crest;
            }
            case RIFT -> {
                double[] uv = hallUV(c, x, z);
                double d = riverDist(c, uv[0], uv[1]);
                if (d <= 0 || d >= UNDER_REACH) return Double.NaN;
                t = d / UNDER_REACH;
                top = riftFloor(c, uv[0], uv[1], x, z);
                if (top < c.f0 - 6) shelf = RIFT_LOW_SHELF;
            }
            default -> {
                return Double.NaN;
            }
        }
        t = Math.min(1, t);
        double lip = top - shelf;
        return lip - (lip - (c.base + 2)) * t * t;
    }

    /**
     * The Scoria Canyons: the shelf under the low ledges and the lower half of their ramps (blocks), where it meets the
     * undercut at their lips. Their floors stand {@link #HALL_FLOOR_DEPTH} thick block by block all the same ({@link
     * #hallFloor}); with the hall's shelf of 10, the rock between the undercut and the floor bulges out of a low
     * ledge's end at the interpolation corners, 4 under its floor and right under the upper ledge's lip, and a fall
     * from the lip lands on it 16 or 17 down.
     */
    static final double RIFT_LOW_SHELF = 3;

    /**
     * The Scoria Canyons: how far local (u, v) lies from the lava river as {@link #riftFloor} lays it out (0 on it):
     * the river down the middle, and the low ledges' band past the ramp's top and the low ledge's end.
     */
    static double riverDist(Chamber c, double u, double v) {
        double a = Math.abs(v), r1 = c.riverW, r2 = c.riverW + c.lowW, end = riftLedgeEnd(c) - Math.abs(u);
        if (a < r1 || end < 0) return 0;
        int s = v >= 0 ? 0 : 1;
        double d = (c.rampU[s] - u) * c.rampDir[s], D = (c.f0 - c.crest) / RIFT_RAMP + c.lowLen[s], da = Math.max(0, a - r2);
        return Math.min(Math.min(a - r1, end), Math.min(Math.hypot(da, Math.max(0, d)), Math.hypot(da, Math.max(0, D - d))));
    }

    /** The Scoria Canyons: the narrowest an upper ledge runs (blocks). */
    static final double RIFT_LEDGE_MIN = 5;

    /**
     * The Scoria Canyons: how far from the middle along the canyon (in u) the upper ledges run, to where the walls
     * close in on them to {@link #RIFT_LEDGE_MIN}. A narrower ledge has no room under its lip for the undercut, and
     * the wall's own term leaves too little open there for the interpolation: every fall from it landed on rock.
     */
    static double riftLedgeEnd(Chamber c) {
        double q = Math.pow((c.riverW + c.lowW + RIFT_LEDGE_MIN) / c.rz, c.sq);
        return c.rx * Math.pow(Math.max(0, 1 - q), 1 / c.sq);
    }

    /**
     * The top block of the Causeway's column at (x, z), or {@link #NO_COLUMN}. The columns are hexagonal prisms on a
     * lattice in the hall's local coordinates, {@code hexS} apart (radius 1.5 to 2.3): the column path's two rows touch
     * edge to edge, their tops following a gentle swell from the middle to the end ramps that never steps more than a
     * block from one column to the next; beside the path about every other place holds a column a step off it; one in
     * five of the rest, away from the path, the ledge's lip and the end ramps, stands from the lava into the vault
     * ({@link #TO_VAULT}, out of the flood). Nothing stands alone with a top of its own: such a top would be a standing
     * place no one can walk to. Behind the path, from the middle of its far row to the far wall, the rock stands a
     * block under the path's tops ({@link #backTop}): the path keeps 2 to 5 blocks from that wall, and the slot between
     * them, too narrow for the interpolation, came out a crack with a rough floor at the lava's top, where a step off
     * the path's far side fell 16 to 20 onto rock.
     */
    int hexTop(Chamber c, double x, double z) {
        double[] uv = hallUV(c, x, z);
        double u = uv[0], sv = uv[1] * c.side;
        double ledgeV = c.rz - c.ledgeW, endU = c.rx - c.endW;
        if (Math.abs(u) > endU + 1 || sv > ledgeV - 1) return NO_COLUMN;
        // pointy-top hexagons of circumradius s / sqrt 3: rows along u, s sqrt 3 / 2 apart across
        double s = c.hexS, R = s / SQ3;
        double qf = (SQ3 / 3 * u - sv / 3) / R, rf = (2.0 / 3 * sv) / R, yf = -qf - rf;
        long qi = Math.round(qf), ri = Math.round(rf), yi = Math.round(yf);
        double dq = Math.abs(qi - qf), dr = Math.abs(ri - rf), dy = Math.abs(yi - yf);
        if (dq > dr && dq > dy) qi = -ri - yi;
        else if (dr > dy) ri = -qi - yi;
        double hu = R * (SQ3 * qi + SQ3 / 2 * ri), hv = R * 1.5 * ri;
        double px = u - hu, py = sv - hv;
        double hd = Math.max(Math.abs(px), Math.max(Math.abs(px * 0.5 + py * SQ3 / 2), Math.abs(px * 0.5 - py * SQ3 / 2)));
        long hh = Hashing.mix(c.hash, 8800, qi, ri);
        double a = Hashing.unit(hh), b = Hashing.unit(Hashing.mix(hh, 1, 0, 0));
        double ap = Math.abs(hv - c.pathV), half = s / 2;
        int pathTop = (int) Math.round(pathHeight(c, hu)), top;
        // past the path's ends the end ramp has its own floor
        if (Math.abs(hu) > endU - 0.5 * s && ap >= 0.5 * s) top = NO_COLUMN;
        else {
            if (ap < 0.5 * s) top = pathTop;
            else if (ap < 1.4 * s) top = a < 0.5 ? pathTop + (b < 0.35 ? -1 : b < 0.7 ? 0 : 1) : NO_COLUMN;
            else {
                // a column into the vault where it leaves room round it, never by the ledge's lip or the end ramps
                boolean buffer = hv > ledgeV - 6 || Math.abs(hu) > endU - 6 || ap < 2.2 * s;
                top = buffer || a < 0.8 ? NO_COLUMN : TO_VAULT;
                half *= 0.72 + 0.26 * b;
            }
            if (hd > half + 1e-9) top = NO_COLUMN;
        }
        return Math.max(top, backTop(c, u, sv));
    }

    /**
     * The rock behind the Causeway's column path at (u, sv) on its axes ({@link #hallUV}, sv on the path's side
     * positive): from the middle of the path's far row (SQ3 / 4 of a column's width off its line) to the far wall, a
     * block under the path's tops; elsewhere {@link #NO_COLUMN}. It reaches to e 1.15, past the columns' 1.02: there
     * the hall's interpolated wall left a slit a block wide between the rock and the wall, 16 deep.
     */
    static int backTop(Chamber c, double u, double sv) {
        if (sv >= c.pathV - SQ3 / 4 * c.hexS || Math.abs(u) > c.rx - c.endW) return NO_COLUMN;
        return (int) Math.round(pathHeight(c, u)) - 1;
    }

    /** The column path's height along the Causeway: lowest in the middle, rising to the end ramps' foot. */
    static double pathHeight(Chamber c, double u) {
        double endU = c.rx - c.endW, t = Math.min(1, Math.abs(u) / endU);
        return c.pathMid + (c.pathEnd - c.pathMid) * (1 - Math.cos(Math.PI * t)) * 0.5;
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
            case Terrain.WASTELAND:
                if (c.refuge) return DeepBiome.ASH_HOLLOWS;
                return DeepBiome.WASTELAND;
            case Terrain.GALLERIES:
                switch (c.kind) {
                    case TUBEHUB: return DeepBiome.OBSIDIAN_TUBES;
                    case CALDERA: return DeepBiome.CALDERA;
                    case CAUSEWAY: return DeepBiome.CAUSEWAY;
                    case RIFT: return DeepBiome.SCORIA_CANYONS;
                    default: return DeepBiome.BASALT_GALLERIES;
                }
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
        // per-column values are floats: 1,024 columns x 3 layers per worldgen thread (section 2.1)
        float[] e = new float[16], F = new float[16], C = new float[16], pd = new float[16], pr = new float[16];
        /** Under a hall's ledge: the top of the open space over the lava ({@link #shelfUnder}), else NaN. */
        float[] U = new float[16];
        /** A hall's wall scale at the column ({@link #wallScale}); unused for every other chamber. */
        float[] ws = new float[16];
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
        /** The nearest pillar of tier A (B) is a view breaker's column ({@link #columnSolid}), not one of 0.2.0's hourglasses. */
        boolean aCol, bCol;
        /** The nearest of the rift's spires (Blue): distance from its segment and its radius (0 for none). */
        double rPd, rPr;
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
        /** An obsidian tube hub's footprint covers the column. */
        boolean hub;
        /**
         * How much of the roughness and the basalt column shift the column takes: none in a tube hub or within w + 2
         * of a round tube's line, all of it from 4 blocks further out (section 5).
         */
        float roughK;
        /** The roughness's share in the Basalt Galleries' halls ({@link #HALL_ROUGH}), 1 elsewhere. */
        float hallK = 1;
        /** The Basalt arch over the column, with the column's place under it (q = across / span, along). */
        Arch arch;
        float archQ, archAlong;
        /**
         * The lava pool at the column (Basalt), the distance from the column's centre to its footprint (0 or less:
         * inside), and whether the column holds its lava (inside the footprint, where the floor is the pool's).
         */
        Net.Pool pool;
        float poolD;
        boolean poolLava;
        double entryD, exitD, endD;
        double lavaE;
        Chamber bed;
        /**
         * The Basalt Galleries: the column lies in the shore notch from the bedrock landing to its lava basin ({@link
         * #shoreNotch}): how far across the notch's line it is, and the tread's top there.
         */
        boolean notch;
        float notchAcross, notchTop;
        /** A Causeway column at the column: its top block and the lowest block it stands on ({@link #hexTop}). */
        int colTop = NO_COLUMN, colLo;
        /** The heights the per-block pass must look at; outside them the interpolated density stands. */
        double fLo, fHi;
        double colShift;

        void growC() {
            int n = c.length * 2;
            c = Arrays.copyOf(c, n); e = Arrays.copyOf(e, n); F = Arrays.copyOf(F, n); C = Arrays.copyOf(C, n);
            pd = Arrays.copyOf(pd, n); pr = Arrays.copyOf(pr, n); U = Arrays.copyOf(U, n); ws = Arrays.copyOf(ws, n);
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
        lc.colTop = NO_COLUMN;
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
                lc.U[k] = c.hall() ? (float) shelfUnder(c, e, x, z) : Float.NaN;
                if (c.hall()) {
                    double[] uv = hallUV(c, x, z);
                    lc.ws[k] = (float) wallScale(c, uv[0], uv[1], e);
                }
                if (c.kind == Kind.CAUSEWAY && e < 1.15) {
                    // the column is a per-block term: the span reaches from the lava's bed to its top
                    double[] uv = hallUV(c, x, z);
                    int top = e < 1.02 ? hexTop(c, x, z) : backTop(c, uv[0], uv[1] * c.side);
                    if (top == TO_VAULT) top = (int) Math.ceil(lc.C[k]) + 3;
                    if (top != NO_COLUMN && top > lc.colTop) {
                        lc.colTop = top;
                        lc.colLo = (int) Math.floor(c.base) - 1;
                        lc.fLo = Math.min(lc.fLo, lc.colLo - 1);
                        lc.fHi = Math.max(lc.fHi, top + 1);
                    }
                }
                lc.pd[k] = 1e9f;
                lc.pr[k] = 0;
                for (int p = 0; p + 2 < c.pillars.length; p += 3) {
                    double px = c.kind == Kind.TUBEHUB ? c.x + c.pillars[p] : c.pillars[p];
                    double pz = c.kind == Kind.TUBEHUB ? c.z + c.pillars[p + 1] : c.pillars[p + 1];
                    double d = Math.hypot(x - px, z - pz);
                    if (d - c.pillars[p + 2] < lc.pd[k] - lc.pr[k]) { lc.pd[k] = (float) d; lc.pr[k] = (float) c.pillars[p + 2]; }
                }
                if (hallFloor(c, e, lc.F[k], li, shape.waterY(li))) {
                    // a hall's dry floor is a per-block term (hallFloor): the span covers its band
                    lc.fLo = Math.min(lc.fLo, lc.F[k] - HALL_FLOOR_DEPTH - 1);
                    lc.fHi = Math.max(lc.fHi, lc.F[k] + HALL_FLOOR_ROOM + 1);
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
            // the Basalt Galleries: a flight's own tunnels end at the stair's floor a few blocks in from their doors, and
            // their carve runs on a width past the end. At the stair's foot (a bedrock flight's spoke from the landing,
            // an entry flight's doors on its level foot) it ran on into the stair as it climbs and cut a pit with a
            // 3-block step at its edge: inside the flight those tunnels keep to the stair's floor. At the stair's top
            // (a bedrock flight's other doors, an entry flight's spoke from the landing) the tunnel has to cut through
            // the stair's top end down to its floor there
            for (int k = 0; k < lc.nw; k++) {
                Way wy = lc.w[k];
                if (wy.inner) continue;
                for (int j = 0; j < lc.nc; j++) {
                    Chamber c = lc.c[j];
                    if (!c.flight || lc.e[j] >= 1 || (wy.a != c.id && wy.b != c.id)) continue;
                    int end = wy.a == c.id ? 0 : wy.n - 1;
                    if (rampT(c, wy.x[end], wy.z[end]) < 0) continue;
                    if (lc.F[j] > lc.wf[k] && lc.F[j] < lc.wf[k] + 12) {
                        lc.wf[k] = lc.F[j];
                        lc.fHi = Math.max(lc.fHi, lc.wf[k] + Math.max(lc.wh[k] + 1, 7));
                    }
                }
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
        lc.rPd = 1e9;
        lc.rPr = 0;
        if (n.dark != null && lc.rOn && lc.rrC > lc.rrF) {
            double[] t = lc.tmp;
            t[0] = 1e9;
            t[1] = 0;
            n.dark.sightPillar(NetDark.Plan.RIFT, x, z, t);
            lc.rPd = t[0];
            lc.rPr = t[1];
        }
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
        shoreNotch(lc, n, x, z);
        // basalt columns: a per-column offset of the walls, constant over a small Voronoi cell
        lc.colShift = Terrain.basaltLook(li) ? 1.7 * voronoiValue(x, z, 3.3) : 0;
        lc.hub = inHub(lc);
        lc.roughK = lc.hub ? 0 : 1;
        lc.hallK = 1;
        for (int k = 0; k < lc.nc; k++) if (lc.c[k].hall() && lc.e[k] < 1.15) lc.hallK = HALL_ROUGH;
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
            double deck = a.deck - a.drop * q * q, thick = 3 + 9 * q * q * q;
            lc.fLo = Math.min(lc.fLo, deck - thick - 2.5);
            lc.fHi = Math.max(lc.fHi, deck + 2.5);
            break;
        }
        lc.pool = null;
        for (Net.Pool p : n.pools) {
            double d = p.dist(x + 0.5, z + 0.5);
            if (d > p.bank() + 0.5 || (lc.pool != null && d >= lc.poolD)) continue;
            lc.pool = p;
            lc.poolD = (float) d;
        }
        lc.poolLava = false;
        if (lc.pool != null) {
            Net.Pool p = lc.pool;
            // lava only where the floor is the pool's: all of a channel (it lies on the tread's flat), and in a blind
            // tube only under its open space at the feet, so the corners of its pinched end stay rock (bank)
            if (lc.poolD <= 0) {
                boolean in = p.channel;
                for (int k = 0; k < lc.nw && !in; k++) if (lc.w[k].id == p.way) in = roundShell(lc, k, p.y + 1) < 0;
                lc.poolLava = in;
            }
            // the pool is a per-block term: its bed, its banks and the air over them
            lc.fLo = Math.min(lc.fLo, p.y - 3);
            lc.fHi = Math.max(lc.fHi, p.y + 4);
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
            // and the columns that cut long views: on the seams, and the plan's own (t[2] = 1 when one of them is nearer)
            t[2] = 0;
            seamPillar(k, x, z, t);
            p.sightPillar(k, x, z, t);
            if (k == 0) { lc.aF = F; lc.aC = C; lc.aPd = t[0]; lc.aPr = t[1]; lc.aCol = t[2] > 0; }
            else { lc.bF = F; lc.bC = C; lc.bPd = t[0]; lc.bPr = t[1]; lc.bCol = t[2] > 0; }
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
            default -> { a = 2.4; s = 14; } // the Wasteland and the Galleries: the basalt look
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
        float u = lc.U[k];
        // under a hall's ledge: open from the lava's bed to the ledge's underside as well
        if (u == u) d = Math.min(d, Math.max(c.base - y, y - u));
        double wall = wallTerm(lc, k);
        if (c.kind == Kind.RIFT && y < c.f0 - 6) wall -= Math.min(RIFT_LEAN, (c.f0 - 6 - y) * 0.5);
        return Math.max(d, wall);
    }

    /**
     * The Scoria Canyons: how far their walls lean back under the upper ledges (blocks), from nothing 6 under the
     * ledges' floor, 1 in 2, to this 16 under it, where a fall from a ledge starts to hurt too much. Under the ledges
     * the rock is their own; past their ends the river reaches the walls, and a fall from a ledge's last block by an
     * upright wall runs down its rough face onto a knob 16 under the ledge, a pocket with no way out. Leaning back,
     * the walls let such a fall go on into the river.
     */
    static final double RIFT_LEAN = 5;

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
            double rs = lc.roughK * lc.hallK;
            if (lc.darkOn && ((lc.aC > lc.aF && y > lc.aF - 3 && y < lc.aF + 4) || (lc.bC > lc.bF && y > lc.bF - 3 && y < lc.bF + 4))) rs *= DARK_FLOOR_ROUGH;
            if (lc.ns > 0) rs *= mouthFade(lc, y);
            if (rs > 0) d += rs * rough(li, x, y, z);
        }
        if (lc.geoN > 0) d = Math.min(d, geodeDist(lc, x, y, z));
        if (lc.darkOn) {
            if (lc.aC > lc.aF && lc.aPd < lc.aPr * 2.5)
                d = Math.max(d, lc.aCol ? columnSolid(lc.aPd, lc.aPr, y, lc.aF, lc.aC) : pillarSolid(lc.aPd, lc.aPr, true, y, lc.aF, lc.aC));
            if (lc.bC > lc.bF && lc.bPd < lc.bPr * 2.5)
                d = Math.max(d, lc.bCol ? columnSolid(lc.bPd, lc.bPr, y, lc.bF, lc.bC) : pillarSolid(lc.bPd, lc.bPr, true, y, lc.bF, lc.bC));
        }
        // the rift's spires, from its floor to its vault, which cut its long views (NetPlanner.sightBreak)
        if (lc.rPr > 0 && lc.rPd < lc.rPr * 2.5) d = Math.max(d, columnSolid(lc.rPd, lc.rPr, y, lc.rrF, lc.rrC));
        // pillars
        for (int k = 0; k < lc.nc; k++) {
            if (lc.pr[k] <= 0 || lc.pd[k] > lc.pr[k] * 2.5) continue;
            // a column hall's columns are straight (a waist's flanks step out into ledges no one can reach)
            d = Math.max(d, pillarSolid(lc.pd[k], lc.pr[k], lc.c[k].kind != Kind.TUBEHUB && lc.c[k].kind != Kind.HALL, y, lc.F[k], lc.C[k]));
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
        for (int k = 0; k < lc.nd; k++) if (y > lc.dg[k].y1 - 3) d = Math.min(d, passage(lc.dg[k], x, y, z));
        if (hub) d = Math.max(d, hubPillar(lc, y));
        return d;
    }

    /**
     * The Basalt Galleries' shore notch: their bedrock landing is small (its flights up are close from all of it) and
     * stands clear of its lava basin, so a notch {@link #SHORE_HALF} blocks either side of the line between their
     * centres runs from 3 blocks inside the plate's edge to 3 past the basin's near wall, open 5 blocks over treads
     * that step down from the landing's floor to the lava (0.75 down per block out, as {@link #lavaShore} steps the
     * plate's edge inside the basin): the shore can be walked to from the landing and climbed out of. The basin is the
     * plan's last chamber.
     */
    void shoreNotch(LCol lc, Net n, double x, double z) {
        lc.notch = false;
        if (lc.bed == null || n.li != Terrain.GALLERIES || lc.endD > lc.bed.rx + 12) return;
        Chamber b = n.ch.get(n.ch.size() - 1);
        if (b.kind != Kind.LAVA) return;
        double ux = b.x - lc.bed.x, uz = b.z - lc.bed.z, ul = Math.hypot(ux, uz);
        ux /= ul;
        uz /= ul;
        double along = (x - lc.bed.x) * ux + (z - lc.bed.z) * uz;
        double across = Math.abs((z - lc.bed.z) * ux - (x - lc.bed.x) * uz);
        if (across > SHORE_HALF + 1 || along < lc.bed.rx - 3 || along > ul - b.rx + 3) return;
        double W = shape.waterY(n.li);
        lc.notch = true;
        lc.notchAcross = (float) across;
        lc.notchTop = (float) Math.max(W - 1, lc.bed.f0 - 0.75 * Math.max(0, along - lc.bed.rx));
        lc.fLo = Math.min(lc.fLo, lc.notchTop - 7);
        lc.fHi = Math.max(lc.fHi, lc.notchTop + 8);
    }

    /** Half the width of the Basalt Galleries' shore notch (blocks, {@link #shoreNotch}). */
    static final double SHORE_HALF = 3;

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

    /**
     * How much of the basalt column shift a block of the Wasteland takes near a floor: {@link #FLOOR_FADE}, from 2
     * under to 2 over the floor of a room (inside its footprint) or of a way (within its half-width and 2) in its
     * column; all of it elsewhere and in the other layers. The columns still show in the floors, with half their steps:
     * the Wasteland has many more rooms than the Basalt Galleries had, and at the full shift so many patches of their
     * floors were cut off by two-block steps that the walkable cave held under 96% of the standing places (95.6 and
     * 96.1% in the two cells of wastelandIsOneCave; 96.5 and 96.7% with this).
     */
    static double floorFade(int li, LCol lc, int y) {
        if (li != Terrain.WASTELAND) return 1;
        for (int k = 0; k < lc.nc; k++) if (lc.e[k] < 1 && Math.abs(y - lc.F[k]) < 2.5) return FLOOR_FADE;
        for (int k = 0; k < lc.nw; k++) if (lc.wd[k] < lc.ww[k] + 2 && Math.abs(y - lc.wf[k]) < 2.5) return FLOOR_FADE;
        return 1;
    }

    /** The share of the basalt column shift the Wasteland's floors take ({@link #floorFade}). */
    static final double FLOOR_FADE = 0.5;

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

    /**
     * A view breaker's column (NetPlanner.sightBreak): upright at radius pr from its foot to the middle of its tier, then
     * flaring into the ceiling (1.65 pr at it). An hourglass's lower flare turns into rings of ledges no one can climb;
     * an upright foot holds none, and its narrowest is still wider than the 0.75 pr the breaker counts on.
     */
    static double columnSolid(double pd, double pr, double y, double f, double c) {
        if (pr <= 0 || c <= f) return -SOLID;
        double mid = (f + c) * 0.5, half = Math.max(4, (c - f) * 0.5);
        double q = Math.max(0, (y - mid) / half);
        double r = pr * (1 + 0.65 * q * q);
        return Math.min(r - pd, Math.min(y - (f - 3), (c + 3) - y));
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
        for (int k = 0; k < lc.nd; k++) {
            Plans.Gate g = lc.dg[k];
            if (lc.dgD[k] < g.landR) d = Math.max(d, Math.min(Math.min(g.lFloor - y, y - (g.lFloor - 7)), g.landR - lc.dgD[k]));
        }
        if (ex != null && lc.exitD < ex.landR) d = Math.max(d, Math.min(Math.min(ex.uFloor - y, y - (ex.uFloor - 7)), ex.landR - lc.exitD));
        if (lc.bed != null && lc.endD < lc.bed.rx) d = Math.max(d, Math.min(Math.min(lc.bed.f0 - y, y - (lc.bed.f0 - 7)), lc.bed.rx - lc.endD));
        if (lc.bed != null && lc.lavaE < 1.1) d = Math.max(d, lavaShore(lc, y));
        if (lc.notch && lc.notchTop > shape.waterY(lc.net.li) - 0.5) d = Math.max(d, Math.min(Math.min(lc.notchTop - y, y - (lc.notchTop - 6)), SHORE_HALF + 1 - lc.notchAcross));
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
            double q = lc.archQ, deck = a.deck - a.drop * q * q, thick = 3 + 9 * q * q * q;
            if (y > deck - thick - 1.3 && y < deck + 1.3) {
                double vert = Math.min(deck - y, y - (deck - thick));
                double side = a.halfAlong * (1 + 1.2 * q * q) - Math.abs(lc.archAlong);
                d = Math.max(d, Math.min(vert, side) + 1.2 * detail.noise3(x / 6.0, y / 6.0, z / 6.0));
            }
        }
        // a lava pool: rock under the lava, and round it at its own level (and under that)
        if (lc.pool != null) {
            int py = lc.pool.y;
            if (lc.poolLava ? y == py - 1 : (lc.poolD <= lc.pool.bank() && (y == py || y == py - 1))) d = Math.max(d, 1);
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
        if (ex != null && lc.exitD < ex.landR + 4 && y > ex.uFloor - 2 && y < ex.uCy + ex.landHalf + 4)
            d = Math.min(d, landing(lc.exitD, ex.landR, ex.uCy, ex.landHalf, ex.uFloor, x, y, z));
        if (lc.bed != null && lc.endD < lc.bed.rx + 4 && y > lc.bed.f0 - 2 && y < lc.bed.f0 + 20)
            d = Math.min(d, landing(lc.endD, lc.bed.rx, lc.bed.f0 + 7, 12, lc.bed.f0, x, y, z));
        if (lc.notch) d = Math.min(d, Math.max(lc.notchAcross - SHORE_HALF, Math.max(lc.notchTop - 0.01 - y, y - (lc.notchTop + 5))));
        // a lava pool: its own block cut out of the floor and the air over it (in a tube, only the feet's block, which
        // the tube leaves open there anyway); a channel's banks open two over them too, so the tread runs level to the
        // lava's edge with no rim
        if (lc.pool != null) {
            int py = lc.pool.y;
            boolean ch = lc.pool.channel;
            if (lc.poolLava ? (y >= py && y <= py + (ch ? 2 : 1)) : (ch && lc.poolD <= lc.pool.bank() && y >= py + 1 && y <= py + 2)) d = Math.min(d, -1);
        }
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
        if (lc.colShift != 0 && c8 > -3 && c8 < 3) c8 += lc.colShift * lc.roughK * (lc.ns > 0 ? mouthFade(lc, y) : 1) * floorFade(li, lc, y);
        Plans.Gate en = lc.en, ex = lc.ex;
        boolean enPass = lc.enNear && y > en.y1 - 3, exPass = lc.exNear && y < ex.y0 + 3;
        boolean pass = enPass || exPass;
        for (int k = 0; k < lc.nd && !pass; k++) pass = y > lc.dg[k].y1 - 3;
        if ((y < lc.fLo || y > lc.fHi) && !pass && !fullFine) {
            double d = c8 / 8.0;
            return d < -1 ? -1 : (d > 1 ? 1 : d);
        }
        double keep = mustStay(lc, x, y, z), open = mustOpen(lc, x, y, z);
        // a Causeway column, from the lava's bed to its top block: per block only (at the corners, 4 apart, its rock
        // would spread into a plate over the lava)
        if (lc.colTop != NO_COLUMN && y <= lc.colTop && y >= lc.colLo) keep = Math.max(keep, 1);
        for (int k = 0; k < lc.nc; k++) {
            if (!hallFloor(lc.c[k], lc.e[k], lc.F[k], li, shape.waterY(li))) continue;
            double F = lc.F[k];
            keep = Math.max(keep, Math.min(F - y, y - (F - HALL_FLOOR_DEPTH)));
            open = Math.min(open, Math.max(F - y, y - (F + HALL_FLOOR_ROOM)));
        }
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
        if (lc.poolLava && y == lc.pool.y) return Terrain.LAVA;
        if (y >= shape.waterY(li)) {
            for (int k = 0; k < lc.nc; k++) {
                Chamber c = lc.c[k];
                if (c.hall() && y < c.base + HALL_LAVA && lc.e[k] < 1.1) return Terrain.LAVA;
                if (c.kind == Kind.LAVA && lc.bed != null && y < Math.min(c.f0 + HALL_LAVA, lc.bed.f0) && lc.e[k] < 1.1) return Terrain.LAVA;
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

    /**
     * How high a hall's lava stands over its bed (blocks): to the lava's table at the default depth of 10, and as high
     * where the Basalt Galleries' lava is shallower (config). There the bed cannot go deeper (the seal over the bedrock
     * is under it), and the table would leave the lake a block or two deep, dry in patches where the rough bed stands
     * up, a floor 40 or 50 under the ledges. The bedrock landing's basin holds its lava as high, but never over the
     * landing's floor (2 over the table), which it would spill onto: a block or two over the table, over a bed kept
     * flat ({@link #hallFloor}).
     */
    static final double HALL_LAVA = 5;

    /** The biome at a block of layer li: the nearest chamber's or way's, a refuge first, then the deep dark and the rift. */
    DeepBiome biomeAt(int li, int x, int y, int z) {
        LCol lc = layer(column(x, z), li);
        DeepBiome best = null;
        double bestD = 7;
        for (int k = 0; k < lc.nc; k++) {
            double d = Math.max(lc.F[k] - y, y - lc.C[k]);
            d = Math.max(d, wallTerm(lc, k));
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
     * hubs' tunnels and blind tubes), or 1e9 when none is near. A round way's depth joins the rest smoothly, as the
     * density joins a way to its room ({@link #smin}, 3): where a tube meets its hub's dome at a slant, the rock the
     * blend rounds off in the crease between them stands up to 2 from both surfaces, and the lining would break there.
     */
    double tubeShellDepth(int li, int x, int y, int z) {
        if (!Terrain.basaltLook(li)) return 1e9;
        LCol lc = layer(column(x, z), li);
        double best = 1e9;
        for (int k = 0; k < lc.nc; k++) if (lc.c[k].kind == Kind.TUBEHUB) best = Math.min(best, hubDepth(lc, k, y));
        for (int k = 0; k < lc.nw; k++) if (lc.w[k].round) best = smin(best, roundShell(lc, k, y), 3);
        return best;
    }

    /**
     * True within 2 blocks of the end of a blind tube whose lava pool could not be held by rock (Basalt), at its floor's
     * top block or the one under it: the palette lays magma there instead.
     */
    boolean tubeStubEnd(int li, int x, int y, int z) {
        if (!Terrain.basaltLook(li)) return false;
        LCol lc = layer(column(x, z), li);
        for (double[] m : lc.net.magmaEnds)
            if (Math.hypot(x + 0.5 - m[0], z + 0.5 - m[2]) <= 2 && y <= m[1] && y >= m[1] - 1) return true;
        return false;
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
