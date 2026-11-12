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
 *
 * <p>Since 0.14 (Taran, 2026-11-12: no unbreakable band, the Fern Rifts are the way between the Upper Caves and the Blue
 * Reaches) each den gate's passage is a deep rift ({@link #denRifts}): a ferny crack from the den's ledge on the old floor
 * down through band 1, which is plain rock, into the Blue Reaches' landing. Its floor follows the passage's course down,
 * so it walks like the passage did; its walls rise about 36 blocks over the den's ledge.
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
    /** The Wasteland's tunnel sheets (warp, lattices, floors, opening sites), shared by both fields; null without a Wasteland. */
    final WastelandSheets sheets;
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
    /**
     * Whether the Silver Fern Rift and Ivory Root Cavern set pieces are carved (false where the Upper Caves grow from
     * noise, {@link UpperShapes}, whose rifts and halls take their looks). The den gates are carved either way.
     */
    public volatile boolean upperPieces = true;
    /**
     * Whether each den gate's passage is a deep rift through band 1, which is then plain rock, rather than a passage
     * through the band's unbreakable stone (0.14, {@link #denRift}). Set before any chunk is made, never changed after.
     */
    public volatile boolean denRifts = true;

    /** The wobble of the gate passages and landings (0.2.0's noise field 18, kept so the gates do not move). */
    private final Perlin detail;
    /** The den rifts' ledges and the roughness of their walls (0.14). */
    private final Perlin ledgeN, roughN;
    private final ThreadLocal<Column[]> cache;
    /**
     * Tests only: band 1 and the den gates' carve try every den gate of the cell in every column, not only those whose
     * bounding box reaches it ({@link #densOf}). The answers must not change.
     */
    boolean everyDenGate;

    public Terrain(TerrainSettings settings, int packFloor, long seed) {
        this(settings, packFloor, seed, 0, 0);
    }

    /**
     * As above, with the Galleries' ramp decks and disc caps moved {@code deckShift} and {@code capShift} rows against the
     * Wasteland's craters (JoinTest's one-row check only; the game keeps both at 0).
     */
    Terrain(TerrainSettings settings, int packFloor, long seed, int deckShift, int capShift) {
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
        ledgeN = new Perlin(Hashing.mix(s, 19, 0, 0));
        roughN = new Perlin(Hashing.mix(s, 20, 0, 0));
        this.plans = new Plans(this, seed);
        // The noise layers, each built from what it reads (no cycles): the Wasteland's sheets and the breakthrough both
        // read, the tubes (which first build the Galleries' landscape as it stands without them, GalleriesLandscape, and
        // take their floors from it), the Galleries (which reuse that landscape and see the breakthrough as their join),
        // the Wasteland (which reads the Galleries' density under its openings) and the join of the two.
        this.sheets = shape.layerCount > WASTELAND ? new WastelandSheets(seed, shape, settings, plans) : null;
        this.breakthrough = Join.on(shape) ? new Breakthrough(seed, shape, sheets) : null;
        this.tubes = shape.layerCount > GALLERIES ? new TubeField(seed, shape.waterY(GALLERIES)) : null;
        this.galleries = tubes != null ? new GalleriesField(seed, shape.bottom(GALLERIES), shape.top(GALLERIES), shape.waterY(GALLERIES), tubes, breakthrough, deckShift, capShift) : null;
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
        // Each of those den gates' rift over the column ({@link #denRifts}): how far across from its middle line, its
        // floor and top, and its width's scale (the gate's, narrowing towards the ends); the ledges' offset.
        double[] rv = new double[0], rFloor = new double[0], rTop = new double[0], rW = new double[0];
        double rStep;
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
            Upper.Piece p = upperPieces ? plans.upper(c.cellX, c.cellZ) : null;
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
     * landing, passage or rift can carve anything: the landing reaches 6 past its radius, a passage 8 past its widest, a
     * rift {@link #RIFT_REACH} past its middle line, which runs {@link Plans.Gate#LINE_BACK} past the box.
     */
    private static final double DEN_REACH = 12;
    /** A den rift is rock further than this across from its middle line (blocks): past its widest, ledges and roughness. */
    static final double RIFT_REACH = 12;
    /** A den rift's top lies about this far over the den's ledge (the old floor), give or take 7 (blocks): no higher than the ramps reach. */
    static final double RIFT_RISE = 34;
    /** A den rift's half width at its floor and top, and how much the middle of its height adds (blocks, at scale 1). */
    static final double RIFT_EDGE = 2.2, RIFT_BELLY = 3.4;
    /** How far past the box round a ramp's ends it is carved (its radius and wobble) and kept dry (its dry zone and shell). */
    private static final double RAMP_REACH = 16;
    /** A den rift is never wider than this from its middle line (blocks): its widest, ledges and roughness, at scale 1.2. */
    static final double RIFT_WIDEST = 9;
    /**
     * The den gates' dry zone above the old floor ({@link #denDryDist}): the pack's caves are kept dry this close to their
     * open space, and the pack's water is rock out to {@link #DEN_SHELL} (blocks).
     */
    public static final double DEN_DRY = 5, DEN_SHELL = 7;

    /** The den gates of the column's cell whose upper landing or passage can reach it: {@code c.dens[0..denN)}. */
    private void densOf(Column c) {
        if (c.densDone) return;
        java.util.List<Plans.Gate> l = plans.denGates(c.cellX, c.cellZ);
        if (c.dens.length < l.size()) c.dens = new Plans.Gate[l.size()];
        int n = 0;
        for (Plans.Gate g : l) if (everyDenGate || g.overlaps(c.x, c.z, c.x, c.z, DEN_REACH)) c.dens[n++] = g;
        c.denN = n;
        if (denRifts) riftsOf(c);
        c.densDone = true;
    }

    private static final ThreadLocal<double[]> LINE = ThreadLocal.withInitial(() -> new double[3]);

    /**
     * Each den gate's rift over a column: its middle line follows the passage's course across, carried 10 blocks on
     * behind the upper landing and 6 past the lower one ({@link Plans.Gate#lineNearest}); its floor follows the passage's
     * floor down from the den's ledge to a block over the lower landing's, so it walks as the passage did; its top lies
     * about {@link #RIFT_RISE} over the ledge and comes down to the floor at both ends, where the crack narrows too.
     */
    private void riftsOf(Column c) {
        if (c.rv.length < c.denN) {
            int m = Math.max(c.denN, 4);
            c.rv = new double[m];
            c.rFloor = new double[m];
            c.rTop = new double[m];
            c.rW = new double[m];
        }
        double[] line = LINE.get();
        double rise = RIFT_RISE + 7 * detail.noise2(c.x / 50.0 + 300, c.z / 50.0 + 300);
        for (int k = 0; k < c.denN; k++) {
            Plans.Gate g = c.dens[k];
            g.lineNearest(c.x + 0.5, c.z + 0.5, line);
            double end = Math.min(line[1] + Plans.Gate.LINE_BACK, g.lineEnd() - line[1]);
            double floor = g.riftFloorAt(line[1]);
            double scale = 0.95 + 0.25 * Hashing.unit(Hashing.mix(seed, 4301, g.cellX * 16L + g.den, g.cellZ));
            c.rv[k] = line[0];
            c.rFloor[k] = floor;
            c.rTop[k] = floor + (g.uFloor + rise - floor) * smooth(end / 12.0);
            c.rW[k] = scale * (0.45 + 0.55 * smooth(end / 10.0));
        }
        c.rStep = 3 * detail.noise2(c.x / 30.0 + 500, c.z / 30.0 + 500);
    }

    /**
     * Signed distance to den gate k's rift at a block of column c (negative inside): a crack whose walls step back in
     * ledges every six blocks, narrow at its floor and top and widest in the middle of its height (about 4 across at the
     * floor, 11 halfway up), and rock beyond {@link #RIFT_REACH}.
     */
    private double riftAt(Column c, int k, int x, int y, int z) {
        double v = c.rv[k];
        if (v > RIFT_REACH) return SOLID;
        double yy = y + 0.5, fl = c.rFloor[k], top = c.rTop[k];
        double dV = Math.max(fl - yy, yy - top);
        if (dV > 1) return Math.max(dV, v - RIFT_REACH);
        double tv = (yy - fl) / Math.max(1, top - fl);
        tv = tv < 0 ? 0 : (tv > 1 ? 1 : tv);
        double lens = Math.sqrt(Math.sin(Math.PI * tv));
        int step = (int) Math.floor((yy + c.rStep) / 6.0);
        double ledge = ledgeN.noise3(x / 22.0, step * 1.7, z / 22.0);
        double d = v - (RIFT_EDGE + RIFT_BELLY * lens + 1.3 * ledge) * c.rW[k];
        if (d < 4 && d > -4) d += 0.8 * roughN.noise3(x / 7.0 + 40, y / 7.0, z / 7.0 + 40);
        return Math.max(d, dV);
    }

    /**
     * Signed distance to a den gate's rift at a block (negative inside), or {@link #SOLID} where it cannot reach: for the
     * Blue Reaches' network, which carves the rift's foot round its lower landing as it carved the passage's.
     */
    double denRift(Plans.Gate g, int x, int y, int z) {
        Column c = column(x, z);
        densOf(c);
        for (int k = 0; k < c.denN; k++) if (c.dens[k] == g) return riftAt(c, k, x, y, z);
        return SOLID;
    }

    /** Whether a den rift's middle line passes within {@code pad} of (x, z) across (tests and tools). */
    public boolean nearDenRift(int x, int z, double pad) {
        if (!denRifts) return false;
        Column c = column(x, z);
        densOf(c);
        for (int k = 0; k < c.denN; k++) if (c.rv[k] < pad) return true;
        return false;
    }

    /**
     * The heights over a column where a den rift is open: {lowest floor, highest top}, or null where none is (the fern
     * rift's plants are aimed into it, see {@code LayerRangePlacement}).
     */
    public int[] denRiftBand(int x, int z) {
        if (!denRifts) return null;
        Column c = column(x, z);
        densOf(c);
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int k = 0; k < c.denN; k++) {
            if (c.rv[k] > (RIFT_EDGE + RIFT_BELLY + 1) * c.rW[k] || c.rTop[k] - c.rFloor[k] < 3) continue;
            lo = Math.min(lo, c.rFloor[k]);
            hi = Math.max(hi, c.rTop[k]);
        }
        return lo > hi ? null : new int[]{(int) Math.floor(lo), (int) Math.ceil(hi)};
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

    /**
     * The den gates' passages through band 1 at a block, or their rifts ({@link #denRifts}): the nearest of those that can
     * reach the column.
     */
    private double denPassages(Column c, int x, int y, int z) {
        densOf(c);
        double d = SOLID;
        if (denRifts) {
            for (int i = 0; i < c.denN; i++) d = Math.min(d, riftAt(c, i, x, y, z));
            return d;
        }
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
        if (li < 0) {
            // band 1's den rifts keep their ledges and floors: every block of a rift is open and every other block is
            // rock, whatever the interpolation (it would sink the floor a block or two here and there)
            if (denRifts && shape.bandAt(y) == 1) {
                double d = denPassages(column(x, z), x, y, z);
                return d <= 0 ? Math.min(coarse, Math.max(-1, d / 8.0)) : Math.max(coarse, Math.min(1, d / 8.0));
            }
            return coarse;
        }
        if (join != null && join.inBand(y)) return join.fine(x, y, z, coarse);
        if (li == WASTELAND) return wasteland.fine(x, y, z, coarse);
        if (li == GALLERIES) return galleries.fine(x, y, z, coarse);
        double d = network.fine(li, x, y, z, coarse);
        // a den rift that crosses over the Blue Reaches' caves keeps its floor, so the way down never has a hole no one
        // climbs back out of: the two blocks under the bottom of its crack always, out past the lower landing's rim (a
        // room or way under it is roofed there, a bridge on the rift's floor), and the shelf under that and under its
        // walls where the density has rock (the interpolation alone lets a cave's space up through rock thinner than its
        // 8-block step)
        if (d <= 0 && denRifts && li == BLUE) {
            int under = underDenRiftFloor(x, y, z);
            if (under == 1 || (under == 2 && rawDensity(x, y, z) > 0)) return 0.1;
        }
        return d;
    }

    /** How deep under a den rift's floor its shelf keeps the density's rock (blocks): the interpolation's vertical step. */
    private static final double RIFT_SHELF = 8;

    /**
     * Where a block lies under a den rift, out past its lower landing's rim: 1 in its bridge ({@link #RIFT_BRIDGE} under
     * its floor, where the rift is open within {@link #RIFT_LOW} over that floor: the bottom of its crack, whose walls
     * slope out), 2 elsewhere in its shelf ({@link #RIFT_SHELF} under its floor, as far across as the rift can open), or 0.
     * A bridge one block thick hovered over the lower landing's edge (pieces of 8 to 12 blocks that nothing held).
     */
    private int underDenRiftFloor(int x, int y, int z) {
        Column c = column(x, z);
        densOf(c);
        int under = 0;
        for (int k = 0; k < c.denN; k++) {
            if (c.rv[k] > RIFT_WIDEST) continue;
            double fl = c.rFloor[k], yy = y + 0.5;
            if (yy >= fl || yy < fl - RIFT_SHELF) continue;
            Plans.Gate g = c.dens[k];
            if (Math.hypot(x + 0.5 - g.lx, z + 0.5 - g.lz) <= g.landR - Plans.Gate.LAND_IN) continue;
            if (yy >= fl - RIFT_BRIDGE) {
                int f = (int) Math.floor(fl + 0.5);
                for (int j = 0; j <= RIFT_LOW; j++) if (riftAt(c, k, x, f + j, z) < 0) return 1;
            }
            under = 2;
        }
        return under;
    }

    /** How far over a den rift's floor its crack counts as the bottom that the floor under it holds up (blocks). */
    private static final int RIFT_LOW = 4;
    /** How thick the floor under the bottom of a den rift's crack always is (blocks): a bridge over any cave under it. */
    private static final double RIFT_BRIDGE = 2;

    /**
     * Whether the game builds rock at a block below the pack's old floor, as its noise pass does: {@link #density} at
     * Minecraft's 4 x 8 x 4 corners, interpolated in NoiseChunk's order (along y, then x, then z), then {@link #fine}.
     * Pure and thread safe like the fields. It costs eight corner densities and one fine(), so the rock dressing asks it
     * only of the few blocks whose look turns on a neighbour (Palette: lava beside a bank's top).
     */
    public boolean rockAt(int x, int y, int z) {
        int x0 = Math.floorDiv(x, 4) * 4, y0 = Math.floorDiv(y, 8) * 8, z0 = Math.floorDiv(z, 4) * 4;
        double fx = (x - x0) / 4.0, fy = (y - y0) / 8.0, fz = (z - z0) / 4.0;
        double x00 = lerp(fy, density(x0, y0, z0), density(x0, y0 + 8, z0));
        double x10 = lerp(fy, density(x0 + 4, y0, z0), density(x0 + 4, y0 + 8, z0));
        double x01 = lerp(fy, density(x0, y0, z0 + 4), density(x0, y0 + 8, z0 + 4));
        double x11 = lerp(fy, density(x0 + 4, y0, z0 + 4), density(x0 + 4, y0 + 8, z0 + 4));
        return fine(x, y, z, lerp(fz, lerp(fx, x00, x10), lerp(fx, x01, x11))) > 0;
    }

    private static double lerp(double t, double a, double b) { return a + t * (b - a); }

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
        if (y < shape.packFloor + 48 && denDryDist(x, y, z) < DEN_DRY) return AIR;
        return -1;
    }

    /**
     * How far a block lies from the den gates' open space above the old floor, for their dry zone ({@link #upperFluid})
     * and the rock shell round it, where the pack's water turns to stone (NoiseBasedAquiferMixin): the upper landing as a
     * cylinder from its floor to its top, the rift as a slab round its middle line as wide as it ever gets from its floor
     * to its top, and the ramps. Unlike {@link #denGateCarve}, whose pieces stop at their own reach, it runs on smoothly
     * past the shell, so the shell closes all round and the pack's lakes never stand as a wall of water beside the dry
     * zone's air.
     */
    public double denDryDist(int x, int y, int z) {
        if (y < shape.packFloor - 8 || y > shape.packFloor + 56) return SOLID;
        Column c = column(x, z);
        densOf(c);
        rampsOf(c);
        double d = SOLID, yy = y + 0.5;
        for (int i = 0; i < c.denN; i++) {
            Plans.Gate g = c.dens[i];
            double dxz = Math.hypot(x + 0.5 - g.ux, z + 0.5 - g.uz);
            d = Math.min(d, Math.max(dxz - g.landR, Math.max(g.uFloor - 1 - yy, yy - (g.uCy + g.landHalf))));
            if (denRifts) d = Math.min(d, Math.max(c.rv[i] - RIFT_WIDEST, Math.max(c.rFloor[i] - 1 - yy, yy - c.rTop[i])));
            else d = Math.min(d, passage(g, x, y, z));
        }
        for (int i = 0; i < c.rampN; i++) d = Math.min(d, c.ramps[i].dist(x, y, z));
        return d;
    }

    /** The upper set piece's biome at a block, or a den rift's (with its den's ledge), or null. */
    public DeepBiome upperBiome(int x, int y, int z) {
        if (y < shape.packFloor) return null;
        if (denRifts && y < shape.packFloor + 56 && denLedgeOrRift(x, y, z) < 6) return DeepBiome.SILVER_FERN_RIFT;
        Column c = column(x, z);
        Upper.Piece p = upperOf(c);
        if (p == null || y > p.topY + 4) return null;
        return pieceDist(x, y, z) < 6 ? p.biome() : null;
    }

    /** Signed distance to the den rifts and their dens' ledges (the upper landings) at a block: no ramps. */
    private double denLedgeOrRift(int x, int y, int z) {
        Column c = column(x, z);
        densOf(c);
        double d = SOLID;
        for (int i = 0; i < c.denN; i++) {
            Plans.Gate g = c.dens[i];
            double dxz = Math.hypot(x - g.ux, z - g.uz);
            if (dxz < g.landR + 6) d = Math.min(d, landing(dxz, g.landR, g.uCy, g.landHalf, g.uFloor, x, y, z));
            d = Math.min(d, riftAt(c, i, x, y, z));
        }
        return d;
    }

    /** The surface of the lake (or stream) of the upper set piece over a column. */
    public double upperLakeY(int x, int z) {
        Upper.Piece p = upperOf(column(x, z));
        return p != null ? p.waterY : shape.packFloor + 14;
    }

    /** The upper set piece over a column, if any. */
    public Upper.Piece upperPiece(int x, int z) { return upperOf(column(x, z)); }

    /**
     * Every den gate's upper landing, the top of its passage (or its rift, {@link #denRifts}) and its ramp up into the
     * pack's caves ({@link Plans#denGates}): carved out of the pack's own terrain just above its old floor. Negative
     * inside. Each is carved as the first gate always was, so where no other den gate is near, the first gate's carve is
     * what it was.
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
            d = Math.min(d, denRifts ? riftAt(c, i, x, y, z) : passage(g, x, y, z));
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
            // A den rift through band 1 is a Fern Rift, but for the band's bottom four rows: they keep the Deep Blue Rift
            // under every column, whose biome carries the ways' and the dens' features (open_way, den_lair) to every chunk.
            if (band == 1 && denRifts && y >= shape.bandBottom(1) + 4 && denPassages(column(x, z), x, y, z) < 6) return DeepBiome.SILVER_FERN_RIFT;
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
     * (it would plan caves nobody sees), so it is null in the game, which the dev tools refuse, and throws under the
     * tests ({@link Network#hasPlan}).
     */
    public Net network(int li, int cx, int cz) {
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

    /**
     * The Basalt Galleries' Caldera vent sites within {@code r} of (x, z), cheaply, for the eruptions (0.9.0,
     * {@link GalleriesField#ventSites}): {@link #ventsNear}'s vents and their stacks' sizes, without reading the terrain,
     * so their {@code y} is NaN (read the vent's rows from the world's blocks) and the rare closed stacks are listed too.
     * None when the layout has no Galleries. A query only.
     */
    public java.util.List<Net.Vent> ventSitesNear(int x, int z, double r) {
        return galleries != null ? galleries.ventSites(x, z, r) : java.util.List.of();
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
