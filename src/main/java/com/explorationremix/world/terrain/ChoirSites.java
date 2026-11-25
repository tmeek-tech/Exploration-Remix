package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the Hollow Choir's arena stands (0.21.9): one sunken amphitheatre in about one Blue Reaches cell in three,
 * cut into the floor of one of that cell's own deep dark caverns ({@link NetDark}) and open to the cavern's ceiling
 * above it, so you look down into the bowl from the cavern floor and can see clean across it.
 *
 * <p>A site is a pure function of the seed, the cell and the cell's finished plan, so the chunks that build the arena
 * all agree on it. It is picked at the end of planning ({@link NetPlanner}), once the regions kept, the rift, the
 * geodes, the city and every way are final, and before the pillars are settled, so the planner can leave the pillars
 * inside the bowl unstood ({@link Network#dropExposedPillars}) instead of hanging them from the ceiling.
 *
 * <p>Every site lies at least {@link #INSIDE} inside its cell, which puts the bowl's edge 72 blocks in: a chunk that
 * touches an arena always has its middle in that arena's cell, so one cell's plan is the only one a chunk has to ask.
 */
public final class ChoirSites {
    /** The fighting floor (blocks, radius): 32 across, room to be driven back without losing sight of the middle. */
    public static final double FLOOR_R = 16;
    /** The foot of the seats, where the floor ends. */
    public static final double SEAT_R0 = FLOOR_R + 1;
    /** The outer edge of the rim walk round the top of the seats. */
    public static final double RIM_R = 24;
    /** The seats: this many treads from the floor to the rim, each one tread deep and this many blocks high. */
    public static final int STEPS = 3;
    public static final double STEP_RISE = 2;
    /** The bowl's depth below the cavern's floor: the seats' whole rise. */
    public static final double DEPTH = STEPS * STEP_RISE;
    /** Open rock wanted over the cavern floor at the bowl, so the crown has air over it and the view stays long. */
    public static final double HEAD = 18;
    /** How much cavern the bowl wants round its edge, so it stands in open floor and not against a wall. */
    public static final double CLEAR = 6;
    /**
     * How far the bowl's edge keeps off the other things the cell plans. These are small on purpose: what matters is
     * that nothing is cut in half, not that the arena stands alone in the dark. A chamber is a room with a floor of its
     * own, so it keeps a little more; a city keeps its whole 128-block reserve, since the arena must never be part of
     * the city a key came from.
     */
    static final double PAD_CHAMBER = 4, PAD_WAY = 3, PAD_SHAFT = 6, PAD_GATE = 6, PAD_RIFT = 6, PAD_GEODE = 3;
    /** A site stands at least this far inside its cell (the bowl's edge 72 blocks in). */
    public static final double INSIDE = RIM_R + 72;
    /** The floor of the cavern may only wander this much over the bowl's footprint, so the rim sits level in it. */
    static final double LEVEL = 7;
    /**
     * The share of cells the hash lets through at all. The deep dark itself turns away about half of them anyway (no
     * level, roomy, empty enough piece of cavern floor that far inside the cell), so this is only the trim on top:
     * turning away one cell in four lands at about one Blue Reaches cell in three with an arena, which is Taran's
     * pick. It is a mild gate on purpose. A cell it turns away is one the terrain would have allowed, and that is
     * what keeps an arena from standing at every good spot in the layer, where knowing the terrain would be knowing
     * where every arena is.
     */
    private static final double HASH_KEEP = 0.75;
    /** The raster step the cell is searched on (blocks), and how many sites are tested in full before giving up. */
    private static final double SCAN = 8;
    private static final int TRIES = 48;
    // fresh salts (Plans uses 101-105, 777, 3001, 4101, 4177, 7101-7103, 7400, 7700, 8800, 8811, 8812, 9311; DenLair 4201-4203)
    private static final long SALT_CELL = 5101, SALT_PICK = 5102, SALT_TURN = 5103;

    private ChoirSites() {}

    /**
     * One arena: the middle of the bowl, the cavern floor and ceiling there, which deep dark tier it is cut into, and
     * the way the first socket faces (the others follow at a third of a turn).
     */
    public record Site(int cellX, int cellZ, double x, double z, double floor, double ceil, int tier, double turn, long hash) {
        /** The top of the rim: the cavern's floor, which the bowl is sunk below. */
        public double rimY() { return floor; }

        /** The bowl's own floor. */
        public double floorY() { return floor - DEPTH; }

        /** True when any part of the arena could fall in the block box (inclusive). */
        public boolean mayReach(int minX, int minZ, int maxX, int maxZ) {
            double r = RIM_R + 3;
            return x + r >= minX && x - r <= maxX + 1 && z + r >= minZ && z - r <= maxZ + 1;
        }
    }

    /**
     * The arena of cell (cx, cz) of the Blue Reaches, or null where the cell has none: one cell in six is turned away
     * by the hash, and most of the rest by the cavern floor, which holds no level, roomy, empty enough piece that far
     * inside the cell. About one cell in three ends with an arena.
     *
     * <p>The search is a funnel, cheapest test first, because it runs for every Blue Reaches cell that is planned:
     * what the cell plans elsewhere ({@link #clear}, plain distances and the same for both tiers), then the raster of
     * the deep dark ({@link #oneRegion}), and only then the noise the floor and ceiling are read from
     * ({@link #levelFloor}), on the few candidates the shuffle puts first.
     */
    static Site plan(Network w, Net n) {
        if (n.li != Terrain.BLUE || n.dark == null || n.failed) return null;
        long h = Hashing.mix(w.seed, SALT_CELL, n.cx, n.cz);
        if (Hashing.unit(h) >= HASH_KEEP) return null;

        List<double[]> cand = coarse(w, n);
        if (cand.isEmpty()) return null;
        // a fixed shuffle, so the site does not drift to one corner of every cell: hashed keys, then first that passes
        for (double[] c : cand) c[3] = Hashing.unit(Hashing.mix(h, SALT_PICK, (int) c[0], (int) c[1]));
        cand.sort((a, b) -> Double.compare(a[3], b[3]));
        int tried = 0;
        for (double[] c : cand) {
            if (++tried > TRIES) break;
            int tier = (int) c[2];
            double[] fc = levelFloor(n.dark, tier, c[0], c[1]);
            if (fc == null) continue;
            double turn = Hashing.range(Hashing.mix(h, SALT_TURN, 0, 0), 0, Math.PI * 2);
            return new Site(n.cx, n.cz, c[0], c[1], fc[0], fc[1], tier, turn, h);
        }
        return null;
    }

    /**
     * The cell's candidate middles: at least {@link #INSIDE} inside the cell, clear of everything else the cell
     * plans, and on one kept region's core of one tier with room to spare round the footprint. Each is
     * {x, z, tier, key}. Whether the floor there is level enough is left to {@link #levelFloor}, since that is the
     * one test that has to read the noise.
     */
    private static List<double[]> coarse(Network w, Net n) {
        NetDark.Plan p = n.dark;
        List<double[]> out = new ArrayList<>();
        double x0 = n.cx * w.cell, z0 = n.cz * w.cell;
        if (w.cell < 2 * INSIDE + SCAN) return out;
        for (double x = x0 + INSIDE; x <= x0 + w.cell - INSIDE; x += SCAN)
            for (double z = z0 + INSIDE; z <= z0 + w.cell - INSIDE; z += SCAN) {
                if (!clear(w, n, x, z)) continue;
                for (int t = 0; t < 2; t++) {
                    NetDark.Region g = p.regionAt(t, x, z);
                    // the bowl's own cavern: one deep dark cavern holds the whole footprint with room to spare round
                    // it. Every cavern the planner keeps has a vestibule door onto it, which is how it is reached and
                    // how the arena is: you come in through the door and the bowl is across the open floor, far enough
                    // off that the arena is nobody's room but its own (clear() keeps it off the vestibule and the city).
                    if (g == null) continue;
                    if (!oneRegion(p, g, t, x, z)) continue;
                    out.add(new double[]{x, z, t, 0});
                }
            }
        return out;
    }

    /** True when every ring sample of the footprint lies on the same kept region's core, well inside it. */
    private static boolean oneRegion(NetDark.Plan p, NetDark.Region g, int t, double x, double z) {
        for (double r : new double[]{FLOOR_R, RIM_R, RIM_R + CLEAR})
            for (int i = 0; i < 12; i++) {
                double a = i * Math.PI / 6;
                double sx = x + r * Math.cos(a), sz = z + r * Math.sin(a);
                // the rim and the floor must be the same cavern; the apron ring need only be cavern at all, since
                // nothing of the arena is built on it
                if (r <= RIM_R && p.regionAt(t, sx, sz) != g) return false;
                if (p.keepAt(t, sx, sz) < (r <= RIM_R ? 0.9 : 0.5)) return false;
            }
        return true;
    }

    /**
     * The cavern's floor and ceiling over the footprint when it is level and roomy enough to take the bowl, else null:
     * out = {floor, ceiling}, the floor the highest sample (so the rim stands clear of the rock) and the ceiling the
     * lowest.
     */
    private static double[] levelFloor(NetDark.Plan p, int t, double x, double z) {
        double lo = 1e9, hi = -1e9, ceil = 1e9;
        for (int i = 0; i <= 24; i++) {
            double sx = x, sz = z;
            if (i > 0) {
                double r = i <= 12 ? FLOOR_R : RIM_R;
                double a = (i % 12) * Math.PI / 6;
                sx = x + r * Math.cos(a);
                sz = z + r * Math.sin(a);
            }
            double f = p.floorAt(t, sx, sz), c = p.ceilAt(t, sx, sz);
            if (c <= f) return null;
            lo = Math.min(lo, f);
            hi = Math.max(hi, f);
            ceil = Math.min(ceil, c);
        }
        if (hi - lo > LEVEL) return null;
        double floor = Math.round(hi);
        if (ceil - floor < HEAD) return null;
        return new double[]{floor, ceil};
    }

    /**
     * True when the bowl's footprint, with {@link #CLEAR} round it, meets nothing else the cell plans: no chamber (the
     * landings, the portal vestibules and the city's hall among them), no way, no climb, no den gate's landing, not
     * the rift, not a geode, and nothing of a city's 128-block reserve.
     */
    private static boolean clear(Network w, Net n, double x, double z) {
        return why(w, n, x, z) == null;
    }

    /** What the bowl's footprint at (x, z) would meet, or null when it meets nothing (the dev tools name it). */
    static String why(Network w, Net n, double x, double z) {
        for (Net.Chamber c : n.ch) {
            if (!c.keep) continue;
            // a city's whole reserve: the arena is never part of a city, and no city piece is ever cut by a bowl
            double room = c.kind == Net.Kind.CITY ? Network.CITY_RESERVE : c.R() + PAD_CHAMBER;
            if (Math.hypot(x - c.x, z - c.z) < room + RIM_R) return "chamber " + c.id + " (" + c.kind + ")";
        }
        for (Net.Way wy : n.ways)
            for (int i = 0; i < wy.n; i++)
                if (Math.hypot(x - wy.x[i], z - wy.z[i]) < wy.w[i] + PAD_WAY + RIM_R) return "way " + wy.id;
        for (Net.Shaft s : n.shafts) if (Math.hypot(x - s.x, z - s.z) < s.r + PAD_SHAFT + RIM_R) return "climb " + s.id;
        double[] tmp = new double[6];
        for (Route rt : n.canyons) {
            double half = 0;
            for (double hh : rt.riftHalf) half = Math.max(half, hh);
            if (rt.nearest(x, z, tmp) && tmp[0] < half + PAD_RIFT + RIM_R) return "the rift";
        }
        for (Route.Geode g : n.dark.geodes) if (Math.hypot(x - g.x(), z - g.z()) < g.r() + PAD_GEODE + RIM_R) return "a geode";
        // the ways down: this cell's gates into and out of the layer, and its den gates, landings and all
        for (Plans.Gate g : gates(w, n)) {
            if (Math.hypot(x - g.lx, z - g.lz) < g.landR + PAD_GATE + RIM_R) return "gate band " + g.band + " lower landing";
            if (Math.hypot(x - g.ux, z - g.uz) < g.landR + PAD_GATE + RIM_R) return "gate band " + g.band + " upper landing";
            double keep = RIM_R + PAD_GATE;
            if (g.overlaps(x - keep, z - keep, x + keep, z + keep, 0)) return "gate band " + g.band + " passage";
        }
        return null;
    }

    private static List<Plans.Gate> gates(Network w, Net n) {
        List<Plans.Gate> out = new ArrayList<>(w.base.plans.denGates(n.cx, n.cz));
        out.add(w.base.plans.gate(Terrain.BLUE + 1, n.cx, n.cz));
        out.add(w.base.plans.gate(Terrain.BLUE + 2, n.cx, n.cz));
        return out;
    }

    /** True when (x, z) lies under the bowl's rim, so the planner leaves no pillar standing through the arena. */
    static boolean inBowl(Site s, double x, double z, double pad) {
        return s != null && Math.hypot(x - s.x(), z - s.z()) < RIM_R + pad;
    }
}
