package com.explorationremix.world.terrain;

/**
 * The shape of everything below the pack's old floor, as plain functions of position. Positive density is rock,
 * negative is open space; values are in blocks (a signed distance, roughly) until {@link #density} scales them for
 * Minecraft's interpolation.
 *
 * <p>The bands between the layers are solid but for the gate passages ({@link Plans.Gate}): band 1 has every den gate
 * of a cell ({@link Plans#denGates}), the deeper bands one gate each. Every block inside the Blue Reaches and the Deep
 * Gardens is the layer's cave network's ({@link Network}): chambers joined by tunnels and climbs, planned per cell, with
 * the Deep Blue Rift and the deep dark in the Blue Reaches. Since 0.5.1 the Wasteland and the Basalt Galleries are noise
 * fields with nothing planned ({@link WastelandField}, {@link GalleriesField} with the {@link TubeField}), and the
 * Wasteland opens into the Galleries with no band between them ({@link Join}, where the {@link Breakthrough} noise says).
 * Above the pack's old floor, this carves only the den gates' upper landings and ramps and the cell's upper set piece
 * (the Silver Fern Rift or the Ivory Root Cavern).
 */
public final class Terrain {
    /**
     * The deep layers, top down. Since 0.5 the old bottom layer is two: the Wasteland (the old Basalt Galleries' look,
     * winding tunnels and medium caves) and, since 0.5.1 with no band between them, the Basalt Galleries under it (a
     * wide bright cavern over a lava sea that rises and falls).
     */
    public static final int BLUE = 0, GARDENS = 1, WASTELAND = 2, GALLERIES = 3;
    /**
     * How far above its lava table the Basalt Galleries' lava rises at the height of its cycle (Taran, 2026-11-01: "the
     * low halls; high ground stays dry"). The low halls and paths flood; the ledges, arches, the Obsidian Tubes and both
     * landings' ways up stay above it.
     */
    public static final int FLOOD_RISE = 40;

    /** Layers with the basalt look (columns in the walls, basalt rock, lava below): the Wasteland and the Galleries. */
    public static boolean basaltLook(int li) { return li == WASTELAND || li == GALLERIES; }

    /**
     * Layers whose caves come from the network's plans, with ways, climbs and gates the features keep clear (Walkway,
     * OpenWayFeature, ClimbVinesFeature): the Blue Reaches and the Deep Gardens. Since 0.5.1 the Wasteland and the
     * Galleries are noise fields with no planned ways, so nothing guards a way there (players can always dig).
     */
    public static boolean planned(int li) { return li < WASTELAND; }

    /** How far the layer's lava rises over its table at the height of its cycle: only the Galleries' does. */
    public static int surge(int li) { return li == GALLERIES ? FLOOD_RISE : 0; }
    public static final int AIR = 0, WATER = 1, LAVA = 2;
    static final double SOLID = 64;
    /** Blocks of layer rock kept between any cave and the band above or the bedrock below. */
    static final double SEAL = 4;
    /** Half width of the guaranteed walking space along the Deep Blue Rift's ledge path (0.2.0's route core). */
    static final double CORE = 3.3;

    public final TerrainSettings settings;
    public final Shape shape;
    public final long seed;
    public final Plans plans;
    public final Palette palette;
    /**
     * What the two bottom layers' noise fields tell the rock dressing and the Galleries' glow: column cells, hanging
     * clusters, tubes, cones ({@link LookQueries}), answered by the Galleries' field, and the Wasteland's lava rule.
     */
    public final LookQueries looks;
    /**
     * The planned layers' cave networks: every block inside the Blue Reaches and the Deep Gardens is theirs ({@link
     * #planned}). Nothing asks it about the Wasteland or the Galleries.
     */
    public final Network network;
    /** Where the Wasteland opens into the Galleries; null when the layout keeps a band between them ({@link Join#on}). */
    public final Breakthrough breakthrough;
    /** The Galleries' Obsidian Tubes; null when the layout has no Galleries. */
    public final TubeField tubes;
    /** The Basalt Galleries (layer 3) as noise; null when the layout has no Galleries. */
    public final GalleriesField galleries;
    /** The Wasteland (layer 2) as noise; null when the layout has no Wasteland. */
    public final WastelandField wasteland;
    /** The rows where the Wasteland and the Galleries meet, both fields combined; null without either of them. */
    public final Join join;
    /** Asked before an upper set piece is placed, so none is carved under an ocean or river. Null in tests. */
    public volatile Upper.SurfaceProbe surfaceProbe;

    /** The wobble of the gate passages and landings (0.2.0's noise field 18, kept so the gates do not move). */
    private final Perlin detail;
    private final ThreadLocal<Column[]> cache;
    /**
     * Tests only: band 1 and the den gates' carve try every den gate of the cell in every column, not only those whose
     * bounding box reaches it ({@link #densOf}). The answers must not change.
     */
    boolean everyDenGate;

    public Terrain(TerrainSettings settings, int packFloor, long seed) {
        this.settings = settings;
        this.cache = ThreadLocal.withInitial(() -> {
            Column[] a = new Column[1024];
            for (int i = 0; i < a.length; i++) a[i] = new Column();
            return a;
        });
        this.shape = new Shape(settings, packFloor);
        this.seed = seed;
        long s = seed ^ 0x6A09E667F3BCC909L;
        detail = new Perlin(Hashing.mix(s, 18, 0, 0));
        this.plans = new Plans(this, seed);
        // The noise layers, each built from what it reads (no cycles): the breakthrough both read, the tubes, the
        // Galleries (which see the breakthrough as their join), the Wasteland (which reads the Galleries' density under
        // its openings) and the join of the two.
        this.breakthrough = Join.on(shape) ? new Breakthrough(seed, shape) : null;
        this.tubes = shape.layerCount > GALLERIES ? new TubeField(seed, shape.waterY(GALLERIES)) : null;
        this.galleries = tubes != null ? new GalleriesField(seed, shape.bottom(GALLERIES), shape.top(GALLERIES), shape.waterY(GALLERIES), tubes, breakthrough) : null;
        Join.Lower lower = galleries != null ? Join.Lower.of(galleries) : null;
        this.wasteland = shape.layerCount > WASTELAND ? new WastelandField(this, breakthrough, lower) : null;
        this.join = wasteland != null && lower != null ? new Join(shape, wasteland, lower, breakthrough) : null;
        this.looks = LookQueries.of(galleries, wasteland);
        this.palette = new Palette(this, seed, looks);
        this.network = new Network(this);
    }

    // ================================================================================================ columns

    /** What a column (x, z) above the pack's old floor needs that does not depend on y, computed on first use. */
    public static final class Column {
        int x, z, cellX, cellZ;
        boolean valid;
        boolean upDone;                     // the upper set piece over this column (if any): computed on first use
        Upper.Piece up;
        double upD, upS;                    // distance from the rift's centre line or the cavern's axis; arc length
        final double[] upT = new double[3];
        // The den gates of the column's cell whose landing or passage can reach it, and the ramps that can: computed on
        // first use, so a block looks at the one or two near it and not at every den gate of the cell.
        boolean densDone, rampsDone;
        Plans.Gate[] dens = new Plans.Gate[0];
        int denN;
        Route.Link[] ramps = new Route.Link[0];
        int rampN;
    }

    public Column column(int x, int z) {
        Column[] a = cache.get();
        Column c = a[((x & 31) << 5) | (z & 31)];
        if (c.valid && c.x == x && c.z == z) return c;
        c.x = x;
        c.z = z;
        c.cellX = plans.cellOf(x);
        c.cellZ = plans.cellOf(z);
        c.valid = true;
        c.upDone = false;
        c.densDone = false;
        c.rampsDone = false;
        return c;
    }

    private Upper.Piece upperOf(Column c) {
        if (!c.upDone) {
            Upper.Piece p = plans.upper(c.cellX, c.cellZ);
            c.up = null;
            if (p != null && p.near(c.x, c.z)) {
                c.up = p;
                if (p instanceof Upper.Rift r) {
                    r.nearest(c.x, c.z, c.upT);
                    c.upD = c.upT[0];
                    c.upS = c.upT[1];
                } else {
                    Upper.Cavern cv = (Upper.Cavern) p;
                    c.upD = Math.hypot(c.x - cv.cx, c.z - cv.cz);
                    c.upS = 0;
                }
            }
            c.upDone = true;
        }
        return c.up;
    }

    /**
     * How far past a den gate's bounding box ({@link Plans.Gate#overlaps}, which adds the landing's radius) its upper
     * landing or passage can carve anything: the landing reaches 6 past its radius, a passage 8 past its widest.
     */
    private static final double DEN_REACH = 8;
    /** How far past the box round a ramp's ends it is carved (its radius and wobble) and kept dry, as it always was. */
    private static final double RAMP_REACH = 12;

    /** The den gates of the column's cell whose upper landing or passage can reach it: {@code c.dens[0..denN)}. */
    private void densOf(Column c) {
        if (c.densDone) return;
        java.util.List<Plans.Gate> l = plans.denGates(c.cellX, c.cellZ);
        if (c.dens.length < l.size()) c.dens = new Plans.Gate[l.size()];
        int n = 0;
        for (Plans.Gate g : l) if (everyDenGate || g.overlaps(c.x, c.z, c.x, c.z, DEN_REACH)) c.dens[n++] = g;
        c.denN = n;
        c.densDone = true;
    }

    /** The ramps of the column's cell's den gates that can reach it: {@code c.ramps[0..rampN)}. */
    private void rampsOf(Column c) {
        if (c.rampsDone) return;
        java.util.List<Plans.Gate> l = plans.denGates(c.cellX, c.cellZ);
        if (c.ramps.length < l.size()) c.ramps = new Route.Link[l.size()];
        int n = 0;
        for (Plans.Gate g : l) {
            Route.Link r = plans.ramp(g);
            if (rampNear(r, c.x, c.z, RAMP_REACH)) c.ramps[n++] = r;
        }
        c.rampN = n;
        c.rampsDone = true;
    }

    /** Whether (x, z) is within {@code pad} of the box round a ramp's two ends (strictly, as the first gate's was). */
    public static boolean rampNear(Route.Link r, double x, double z, double pad) {
        return Math.abs(x - (r.x1() + r.x2()) * 0.5) < Math.abs(r.x2() - r.x1()) * 0.5 + pad
                && Math.abs(z - (r.z1() + r.z2()) * 0.5) < Math.abs(r.z2() - r.z1()) * 0.5 + pad;
    }

    // ================================================================================================ helpers

    static double smooth(double t) {
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    /** Floor of a rift at distance v from its centre line: a stream bed, then stepped ledges up to the rim. */
    static double riftFloor(double v, double bedY, double topY, double halfTop, double streamHalf, double stepH) {
        if (v <= streamHalf) return bedY;
        double depth = topY - bedY;
        double t = (v - streamHalf) / Math.max(1, halfTop - streamHalf);
        if (t >= 1) return topY + 2 + (t - 1) * 40;
        double g = depth * Math.pow(t, 0.85);
        double k = Math.floor(g / stepH), frac = g / stepH - k;
        double gq = stepH * (k + smooth((frac - 0.55) / 0.45));
        return bedY + Math.min(gq, depth + 2);
    }

    /** Steps of the given height that stay flat for {@code flat} of each step and then rise sharply. */
    static double stairs(double v, double step, double flat) {
        double k = Math.floor(v / step), frac = v / step - k;
        return step * (k + smooth((frac - flat) / (1 - flat)));
    }

    static double terraced(double v, double step) {
        double k = Math.floor(v / step), frac = v / step - k;
        return step * (k + smooth((frac - 0.6) / 0.4));
    }

    // ================================================================================================ density

    /** Minecraft-scaled density at a block: positive is rock. */
    public double density(int x, int y, int z) {
        double d = rawDensity(x, y, z);
        d /= 8.0;
        return d < -1 ? -1 : (d > 1 ? 1 : d);
    }

    /** Density in blocks: positive is rock. */
    public double rawDensity(int x, int y, int z) {
        if (y < shape.floorY + 1) return SOLID;
        if (y >= shape.packFloor) {
            // Only the den gates' passages continue upward; the pack's own terrain is used above its old floor.
            if (y < shape.packFloor + 16) return denPassages(column(x, z), x, y, z);
            return SOLID;
        }
        int li = shape.layerAt(y);
        if (li < 0) {
            int band = shape.bandAt(y);
            if (band == 0) return SOLID;
            Column c = column(x, z);
            // Band 1 has every den gate of the cell; each deeper band has one gate.
            if (band == 1) return denPassages(c, x, y, z);
            return passage(plans.gate(band, c.cellX, c.cellZ), x, y, z);
        }
        if (join != null && join.inBand(y)) return join.density(x, y, z);
        if (li == WASTELAND) return wasteland.density(x, y, z);
        if (li == GALLERIES) return galleries.density(x, y, z);
        return network.layerDensity(li, x, y, z);
    }

    /** The den gates' passages through band 1 at a block: the nearest of those that can reach the column. */
    private double denPassages(Column c, int x, int y, int z) {
        densOf(c);
        double d = SOLID;
        for (int i = 0; i < c.denN; i++) d = Math.min(d, passage(c.dens[i], x, y, z));
        return d;
    }

    private double passage(Plans.Gate g, int x, int y, int z) {
        double t = g.tAt(y);
        if (Double.isNaN(t)) return SOLID;
        double dx = x - g.px(t), dz = z - g.pz(t);
        double d2 = dx * dx + dz * dz;
        double r = g.radiusAt(t) + 2;
        if (d2 > (r + 6) * (r + 6)) return SOLID;
        return Math.sqrt(d2) - g.radiusAt(t) + 1.1 * detail.noise3(x / 7.0, y / 6.0, z / 7.0);
    }

    /** A rift geode's open space: a sphere with a flattened floor. Negative inside. */
    static double geodeSdf(Route.Geode g, double dxz2, double y) {
        double dy = y - g.y();
        double d = Math.sqrt(dxz2 + dy * dy) - g.r();
        double floor = g.y() - g.r() * 0.5;
        return Math.max(d, floor - y);
    }

    /**
     * Minecraft only samples the density every 8 blocks up and every 4 across, and interpolates in between, which
     * rounds off anything thinner than that. The network puts the thin pieces back block by block ({@link
     * Network#fine}): rock that has to be there (walking floors, bridges, landing floors, pool rims) and space that
     * has to stay open (the walking cores, the climbs, the landings and the way down). The noise layers have their own
     * per-block passes ({@link WastelandField#fine}, {@link GalleriesField#fine}, {@link Join#fine} where they meet).
     * {@code coarse} is the interpolated density on Minecraft's scale; so is the result.
     */
    public double fine(int x, int y, int z, double coarse) {
        int li = shape.layerAt(y);
        if (li < 0) return coarse;
        if (join != null && join.inBand(y)) return join.fine(x, y, z, coarse);
        if (li == WASTELAND) return wasteland.fine(x, y, z, coarse);
        if (li == GALLERIES) return galleries.fine(x, y, z, coarse);
        return network.fine(li, x, y, z, coarse);
    }

    private double landing(double dxz, double rad, double cy, double half, double floor, int x, int y, int z) {
        double ex = dxz / rad, ey = (y - cy) / half;
        double ell = (Math.sqrt(ex * ex + ey * ey) - 1) * half + 1.8 * detail.noise3(x / 9.0, y / 7.0, z / 9.0);
        double fl = floor + 0.9 * detail.noise2(x / 6.0, z / 6.0) - y;
        return Math.max(ell, fl);
    }

    /**
     * Everything carved out of the pack's own terrain above its old floor: the den gates' upper landings and ramps, and
     * the Silver Fern Rift or Ivory Root Cavern of this cell. Negative inside.
     */
    public double upperCarve(int x, int y, int z) {
        return Math.min(denGateCarve(x, y, z), pieceDist(x, y, z));
    }

    /** Signed distance to the open space of this column's upper set piece (negative inside), or SOLID when none is near. */
    public double pieceDist(int x, int y, int z) {
        if (y < shape.packFloor) return SOLID;
        Column c = column(x, z);
        Upper.Piece p = upperOf(c);
        if (p == null || y > p.topY + 2) return SOLID;
        return p.dist(c.upD, c.upS, x, y, z);
    }

    /**
     * What fills an open block above the pack's old floor near the upper set piece or a den gate's landing or ramp:
     * {@link #AIR} or {@link #WATER}, or -1 to leave it to the pack. Never lava, which the pack puts in its lowest caves.
     */
    public int upperFluid(int x, int y, int z) {
        if (y < shape.packFloor || y > shape.packFloor + 90) return -1;
        Column c = column(x, z);
        Upper.Piece p = upperOf(c);
        if (p != null && y <= p.topY + 2 && pieceDist(x, y, z) < 10) {
            if (y >= p.waterY) return AIR;
            if (p instanceof Upper.Cavern cv && c.upD > cv.rxz + 1) return AIR;
            return WATER;
        }
        if (y < shape.packFloor + 48 && denGateCarve(x, y, z) < 14.0) return AIR;
        return -1;
    }

    /** The upper set piece's biome at a block, or null. */
    public DeepBiome upperBiome(int x, int y, int z) {
        if (y < shape.packFloor) return null;
        Column c = column(x, z);
        Upper.Piece p = upperOf(c);
        if (p == null || y > p.topY + 4) return null;
        return pieceDist(x, y, z) < 6 ? p.biome() : null;
    }

    /** The surface of the lake (or stream) of the upper set piece over a column. */
    public double upperLakeY(int x, int z) {
        Upper.Piece p = upperOf(column(x, z));
        return p != null ? p.waterY : shape.packFloor + 14;
    }

    /** The upper set piece over a column, if any. */
    public Upper.Piece upperPiece(int x, int z) { return upperOf(column(x, z)); }

    /**
     * Every den gate's upper landing, the top of its passage and its ramp up into the pack's caves ({@link
     * Plans#denGates}): carved out of the pack's own terrain just above its old floor. Negative inside. Each is carved
     * as the first gate always was, so where no other den gate is near, the first gate's carve is what it was.
     */
    public double denGateCarve(int x, int y, int z) {
        if (y < shape.packFloor - 8 || y > shape.packFloor + 56) return SOLID;
        Column c = column(x, z);
        densOf(c);
        rampsOf(c);
        double d = SOLID;
        for (int i = 0; i < c.denN; i++) {
            Plans.Gate g = c.dens[i];
            double dxz = Math.hypot(x - g.ux, z - g.uz);
            if (dxz < g.landR + 6) d = Math.min(d, landing(dxz, g.landR, g.uCy, g.landHalf, g.uFloor, x, y, z));
            d = Math.min(d, passage(g, x, y, z));
        }
        if (c.rampN > 0) {
            // one wobble for every ramp here, the one the first gate's ramp always had
            double n = 1.5 * detail.noise3(x / 8.0, y / 6.0, z / 8.0);
            for (int i = 0; i < c.rampN; i++) d = Math.min(d, c.ramps[i].dist(x, y, z) + n);
        }
        return d;
    }

    // ================================================================================================ fluids

    /** What fills an open block: {@link #AIR}, {@link #WATER} or {@link #LAVA}. */
    public int fluidAt(int x, int y, int z) {
        int li = shape.layerAt(y);
        if (li < 0) return AIR;
        if (li == WASTELAND) return wasteland.fluid(x, y, z);
        if (li == GALLERIES) return galleries.fluid(x, y, z);
        return network.fluidAt(li, x, y, z);
    }

    // ================================================================================================ biomes

    /** The biome the deep world places at a block, or null above the pack's old floor. */
    public DeepBiome biomeAt(int x, int y, int z) {
        if (y >= shape.packFloor) return null;
        int li = shape.layerAt(y);
        if (li < 0) {
            int band = shape.bandAt(y);
            li = band > 0 ? band - 1 : shape.layerCount - 1;
            return routeBiome(li);
        }
        if (li == WASTELAND) return wasteland.biome(x, y, z);
        if (li == GALLERIES) return galleries.biome(x, y, z);
        return network.biomeAt(li, x, y, z);
    }

    /** A layer's main biome (0.2.0's route biome): the bands take their layer's, and the network falls back on it. */
    public static DeepBiome routeBiome(int li) {
        return switch (li) {
            case GARDENS -> DeepBiome.LANTERN_LAKE;
            case BLUE -> DeepBiome.DEEP_BLUE_RIFT;
            case WASTELAND -> DeepBiome.WASTELAND;
            default -> DeepBiome.BASALT_GALLERIES;
        };
    }

    // ================================================================================================ queries for materials and features

    /** Depth into the rock around the nearest refuge geode (0 at its inner surface), or a large value when none is near. */
    public double geodeShellDepth(int x, int y, int z) {
        int li = shape.layerAt(y);
        return li < 0 || !planned(li) ? 1e9 : network.geodeShellDepth(li, x, y, z);
    }

    /**
     * Depth into the rock around the nearest obsidian tube (0 at its inner surface), or a large value when none is near.
     * Only the Galleries have tubes, and they answer through the Galleries' field, which ignores a tube country it does
     * not let stand.
     */
    public double tubeShellDepth(int x, int y, int z) {
        return shape.layerAt(y) == GALLERIES ? galleries.tubeShellDepth(x, y, z) : 1e9;
    }

    // ------------------------------------------------------------------------------- the network's queries (12.1)
    /** No way lines: what a layer that is not planned has. A per-thread result, as the network's. */
    private static final ThreadLocal<Network.WayLines> NO_LINES = ThreadLocal.withInitial(Network.WayLines::new);

    private static Network.WayLines noLines() {
        Network.WayLines w = NO_LINES.get();
        w.n = 0;
        return w;
    }

    /**
     * Every walking line of layer li within {@code reach} of column (x, z), nearest first: tunnels, inner walks, ledge
     * passages, stubs and the rift's ledge path ({@link Network#wayLinesAt}). A per-thread result. None in a layer
     * that is not {@link #planned}.
     */
    public Network.WayLines wayLinesAt(int li, int x, int z, double reach) {
        return planned(li) ? network.wayLinesAt(li, x, z, reach) : noLines();
    }

    /** The lines of {@link #wayLinesAt} that the terrain carved in column (x, z): its own cell's plan only. */
    public Network.WayLines carvedLinesAt(int li, int x, int z, double reach) {
        return planned(li) ? network.carvedLinesAt(li, x, z, reach) : noLines();
    }

    /** The climbs and climb stages of layer li reaching into the box minX..maxX, minZ..maxZ (inclusive); none unplanned. */
    public java.util.List<Net.Shaft> shaftsIn(int minX, int minZ, int maxX, int maxZ, int li) {
        return planned(li) ? network.shaftsIn(li, minX, minZ, maxX, maxZ) : java.util.List.of();
    }

    /**
     * The plan of layer li in cell (cx, cz). Only the {@link #planned} layers have one: asking for another's is a mistake
     * (it would plan caves nobody sees), so it throws.
     */
    public Net network(int li, int cx, int cz) {
        if (!planned(li)) throw new IllegalArgumentException("layer " + li + " is a noise field: it has no plan");
        return network.network(li, cx, cz);
    }

    /**
     * The Basalt Galleries' Caldera vents within {@code r} of (x, z): where eruptions break out (the craters of the
     * Caldera's cones, {@link GalleriesField#ventsNear}). Each has a centre, a radius and the top of the rock or lava
     * there. None when the layout has no Galleries.
     */
    public java.util.List<Net.Vent> ventsNear(int x, int z, double r) {
        return galleries != null ? galleries.ventsNear(x, z, r) : java.util.List.of();
    }

    // ------------------------------------------------------------------------------- dev tools
    /**
     * For /remix info: what makes the caves at a block of a deep layer, in words (the network's plan in the planned
     * layers, the noise field's values in the others). Empty outside the layers.
     */
    public java.util.List<String> describe(int x, int y, int z) {
        int li = shape.layerAt(y);
        if (li < 0) return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>();
        if (li == WASTELAND) out.addAll(wasteland.describe(x, y, z));
        else if (li == GALLERIES) out.addAll(galleries.describe(x, y, z));
        else out.addAll(network.describe(li, x, y, z));
        if (join != null && join.inBand(y)) out.add(join.describe(x, y, z));
        return out;
    }

    /**
     * The places of a deep biome near (x, z), nearest first (set pieces first in the planned layers), with spots to
     * stand on: for /remix tp biome and /remix view. Each layer's own: the network's chambers in the planned layers,
     * the noise fields' sampled spots in the Wasteland and the Galleries.
     */
    public java.util.List<Network.Place> places(DeepBiome b, double x, double z) {
        int li = Network.layerOf(b);
        if (li < 0 || li >= shape.layerCount) return java.util.List.of();
        if (li == WASTELAND) return wasteland.places(b, x, z);
        if (li == GALLERIES) return galleries.places(b, x, z);
        return network.places(b, x, z);
    }

    /**
     * True when a structure starting at (x, y, z) is a planned ancient city: a CITY chamber's centre within 24 blocks
     * of (x, z), y within 30 of its floor (section 11.1).
     */
    public boolean citySite(int x, int y, int z) { return network.citySite(x, y, z); }
}
