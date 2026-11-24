package com.explorationremix.creature;

import com.explorationremix.block.Neon;
import com.explorationremix.creature.body.LevelTerrain;
import com.explorationremix.creature.body.LurkerBody;
import com.explorationremix.creature.body.LurkerCrawl;
import com.explorationremix.creature.body.LurkerRig;
import com.explorationremix.creature.body.LurkerSpec;
import com.explorationremix.creature.body.Terrain;
import com.explorationremix.creature.body.TerrainPatch;
import com.explorationremix.world.terrain.GardensField;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

import javax.annotation.Nullable;

/**
 * Where the vent urchins live (0.21.3; the 0.21.0 chimney lurkers' vents): about half the Deep Gardens' live vents
 * (Taran's card: "About half of them"), one to a vent, sat in its mouth (Taran's 0.21.3 card: "Half sunk into the
 * mouth": its lower half down in the flue, its spines spread over the rim all round like a trap). Which vents hold one
 * is decided by each vent's own key, so every chunk agrees; its seat, and the side it goes down and comes back up by,
 * are read from the vent's blocks.
 *
 * <p>Its seat is in its mouth over the axis or a little off it, its test's middle just over the rim's top (the
 * middling height of the rim round the mouth), with room round its test down in the flue and most of its spines finding
 * the rim. Its way down is
 * on one side of the vent with no crack under it (a crack leads into the den, and it is too big to follow anyone through
 * one): from over the rim's inner edge there, out over the rim, down the outside to the floor, and back.
 */
public final class LurkerVents {
    private LurkerVents() {}

    /**
     * The share of the live vents with a flue it might fit that hold one, should their mouths have a seat for it (about
     * half of all live vents on the test seed: most of the rest have mouths too wide or too lopsided for its spines).
     */
    static final double HELD = 0.92;
    /**
     * A flue narrower than the first (its mean radius a little under the top, blocks) is too tight for its test; one
     * wider than the second has a mouth its spines cannot span. Between, its mouth's own blocks decide ({@link #mouth}).
     */
    public static final double MIN_FLUE = 1.0, MAX_FLUE = 5.0;
    /** Its way down keeps this far round the axis from any of its vent's open cracks (radians). */
    static final double CRACK_CLEAR = Math.toRadians(42);
    /** The sides of its mouth it reads (and tries for its way down). */
    static final int SIDES = 16;
    /** Its spines must find the rim with at least this many of its sixteen at its seat. */
    static final int ON_RIM = 12;
    /** Over the rim's inner edge on a side, its middle this far out past the edge (blocks): where its way down starts. */
    static final double CREST_OUT = 0.75;

    /**
     * An urchin's home: its vent (key, axis at the top), the rim's top (the middling height round the mouth: the first
     * open row over it), the side its way down lies on (radians from +x toward +z), its seat (its test's middle, sat in
     * the mouth), the mouth's mean radius at the rim, its vent wall's outside (across, from the axis) and its colour,
     * the vent's own.
     */
    public record Home(long key, double x, double z, double top, double azimuth, double waitX, double waitY, double waitZ, double mouth,
                       double wall, Neon colour) {
        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("Key", key);
            t.putDouble("X", x);
            t.putDouble("Z", z);
            t.putDouble("Top", top);
            t.putDouble("Azimuth", azimuth);
            t.putDouble("WaitX", waitX);
            t.putDouble("WaitY", waitY);
            t.putDouble("WaitZ", waitZ);
            t.putDouble("Mouth", mouth);
            t.putDouble("Wall", wall);
            t.putByte("Colour", (byte) colour.ordinal());
            t.putInt("Seat", 1);
            return t;
        }

        public static Home load(CompoundTag t) {
            int c = t.getByte("Colour");
            double wx = t.getDouble("WaitX"), wz = t.getDouble("WaitZ"), x = t.getDouble("X"), z = t.getDouble("Z");
            double wall = t.contains("Wall") ? t.getDouble("Wall") : Math.hypot(wx - x, wz - z) + 3.5;
            // a 0.21.0 home waited on its flue's inner wall: its mouth from there (see old)
            double mouth = t.contains("Mouth") ? t.getDouble("Mouth") : Math.hypot(wx - x, wz - z) + 1.25;
            return new Home(t.getLong("Key"), x, z, t.getDouble("Top"), t.getDouble("Azimuth"), wx, t.getDouble("WaitY"), wz, mouth, wall,
                    Neon.ofCode(c < 0 || c > 3 ? 1 : c));
        }

        /** Whether a saved home is a 0.21.0 lurker's (a spot on its flue's inner wall), from before the urchins sat in their mouths. */
        public static boolean old(CompoundTag t) { return !t.contains("Seat"); }

        /** Its seat as a box position (the box's feet under its test's middle). */
        public double boxY() { return waitY - LurkerRig.shared().spec.ride; }

        /** Where it looks out from while it waits: its crown, over the rim. */
        public Vector3d peek(Vector3d out) {
            return out.set(waitX, waitY + LurkerRig.shared().spec.halfHeight + 0.15, waitZ);
        }

        /** How far (across) a point is from the vent's axis. */
        public double across(double px, double pz) { return Math.hypot(px - x, pz - z); }
    }

    /** Whether a live vent holds an urchin: one whose mouth it fits, by its key alone. */
    public static boolean held(GardensField.LiveVent v) {
        return fits(v) && unit(v.key()) < HELD;
    }

    /** Whether a live vent's mouth is one it fits (from the vent's own numbers, before its blocks are read). */
    public static boolean fits(GardensField.LiveVent v) {
        return v.flue() >= MIN_FLUE && v.flue() <= MAX_FLUE;
    }

    /** Which blocks may be read (a world-gen region's own). */
    public interface Readable {
        boolean at(int x, int z);
    }

    /** Whether the block at (x, y, z) is one it stands on (has a collision box). */
    public interface Solid {
        boolean at(int x, int y, int z);
    }

    /**
     * Its home at vent {@code v} read from the blocks, or null if the vent will not do. {@code bottom} is the Deep
     * Gardens layer's lowest world y (the vent's rows count from it). Only columns {@code readable} allows are read.
     */
    @Nullable
    public static Home find(LevelReader level, GardensField.LiveVent v, int bottom, Readable readable) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        LevelTerrain lt = new LevelTerrain(level);
        return find((x, y, z) -> solid(level, p.set(x, y, z)), (x, y, z, sink) -> { if (readable.at(x, z)) lt.boxes(x, y, z, sink); }, v, bottom, readable, null);
    }

    /** For /remix lurker sides: its seat at vent {@code v} and each side of the mouth, and why each will or will not do for its way down. */
    public static String sides(LevelReader level, GardensField.LiveVent v, int bottom, Readable readable) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        LevelTerrain lt = new LevelTerrain(level);
        Solid solid = (x, y, z) -> solid(level, p.set(x, y, z));
        Terrain rock = (x, y, z, sink) -> { if (readable.at(x, z)) lt.boxes(x, y, z, sink); };
        StringBuilder b = new StringBuilder();
        find(solid, rock, v, bottom, readable, b);
        return b.toString();
    }

    /** For /remix lurker trial: its way down and back from its home's side, run as trials over the level's loaded rock, and how they went. */
    public static String trial(LevelReader level, Home h) {
        Terrain rock = loaded(level);
        Readable readable = (x, z) -> level.hasChunk(x >> 4, z >> 4);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        Solid solid = (x, y, z) -> solid(level, p.set(x, y, z));
        Vector3d crest = crest(solid, readable, h, h.azimuth());
        if (crest == null) return "no rim found on its side";
        StringBuilder why = new StringBuilder();
        Trial out = trialOut(rock, h, crest, readable, why);
        if (out.verdict() != Verdict.FLOOR) return out.verdict() == Verdict.ON ? "out: off the loaded ground on its way down outside" : "out: " + why;
        String o = String.format(java.util.Locale.ROOT, "out: from %.1f %.1f %.1f to the floor at %.1f %.1f %.1f (%.1f off its axis, %.1f under its rim)",
                crest.x, crest.y, crest.z, out.at().x, out.at().y, out.at().z, h.across(out.at().x, out.at().z), h.top() - out.at().y);
        Trial home = trialHome(rock, h, crest, out.at(), readable, why);
        return o + "; home: " + (home.verdict() == Verdict.HOME ? String.format(java.util.Locale.ROOT, "back over its rim at %.1f %.1f %.1f",
                home.at().x, home.at().y, home.at().z) : home.verdict() == Verdict.ON ? "off the loaded ground" : why.toString());
    }

    // ------------------------------------------------------------------------------------------------ the mouth

    /** The mouth of a vent read from its blocks: each side's rim (its inner edge out from the axis, its top), and its seat. */
    static final class Mouth {
        final double[] edge = new double[SIDES], top = new double[SIDES];
        /** The rim's middling top (the first open row over it), its mean inner radius, and its widest. */
        double refTop, mean, widest;
        /** Its seat: its test's middle. */
        final Vector3d seat = new Vector3d();

        double side(int k) { return k * (2.0 * Math.PI / SIDES); }
    }

    /**
     * The rim of vent (ax, az) on the side {@code th}: the highest row (from a few over the vent's mean top, {@code mean},
     * down a few under) with rock within {@code reach} of the axis on that side, its inner edge's distance from the axis
     * ({@code out[0]}) and the first open row over it ({@code out[1]}). False with none, or unreadable blocks.
     */
    static boolean rimAt(Solid solid, Readable readable, double ax, double az, double th, int mean, double reach, double[] out) {
        double c = Math.cos(th), s = Math.sin(th);
        for (int y = mean + 4; y >= mean - 4; y--) {
            double r = firstRock(solid, readable, ax, az, c, s, y, reach);
            if (r == UNREAD) return false;
            if (Double.isNaN(r)) continue;
            out[0] = r;
            out[1] = y + 1;
            return true;
        }
        return false;
    }

    private static final double UNREAD = -1.0;

    /** How far out from the axis the first rock lies on row {@code y} along (c, s) (blocks; NaN if none within reach; UNREAD past the readable ground). */
    private static double firstRock(Solid solid, Readable readable, double ax, double az, double c, double s, int y, double reach) {
        for (double r = 0.3; r <= reach; r += 0.2) {
            int bx = (int) Math.floor(ax + c * r), bz = (int) Math.floor(az + s * r);
            if (!readable.at(bx, bz)) return UNREAD;
            if (solid.at(bx, y, bz)) return r;
        }
        return Double.NaN;
    }

    /**
     * Vent {@code v}'s mouth and its urchin's seat in it, or null (with why, if asked): every side's rim read, its seat
     * with its test's middle {@link LurkerSpec#seat} over the rim's middling top (raised a little should its test fit no
     * lower), over the axis or a little off it, and, with {@code rock} to lay its body on, where the most of its spines
     * find the rim: at least {@link #ON_RIM} of them.
     */
    @Nullable
    static Mouth mouth(Solid solid, @Nullable Terrain rock, GardensField.LiveVent v, int bottom, Readable readable, @Nullable StringBuilder why) {
        LurkerSpec spec = LurkerRig.shared().spec;
        Mouth m = new Mouth();
        int mean = (int) Math.floor(v.topRow() + bottom);
        double reach = v.flue() + 3.0, sum = 0;
        double[] r = new double[2];
        double[] tops = new double[SIDES];
        for (int k = 0; k < SIDES; k++) {
            double th = m.side(k);
            if (!rimAt(solid, readable, v.x(), v.z(), th, mean, reach, r)) return no(why, String.format(java.util.Locale.ROOT, "no rim read at %.0f deg", Math.toDegrees(th)));
            if (r[0] < 0.7) return no(why, String.format(java.util.Locale.ROOT, "its mouth shut at %.0f deg (rock %.1f from its axis)", Math.toDegrees(th), r[0]));
            m.edge[k] = r[0];
            m.top[k] = r[1];
            tops[k] = r[1];
            sum += r[0];
            m.widest = Math.max(m.widest, r[0]);
        }
        java.util.Arrays.sort(tops);
        m.refTop = 0.5 * (tops[SIDES / 2 - 1] + tops[SIDES / 2]);
        m.mean = sum / SIDES;
        if (m.mean < MIN_FLUE * 0.8) return no(why, String.format(java.util.Locale.ROOT, "its mouth only %.1f from its axis at its rim", m.mean));
        // its seat: over the axis or a little off it (toward a narrower side of a lopsided mouth), where its test fits
        // and the most of its spines find the rim (its top, or over its edge); raised a little if its test fits no lower
        boolean fitted = false;
        int bestOn = -1;
        for (int lift = 0; lift <= 3 && bestOn < ON_RIM; lift++) {
            double y = m.refTop + spec.seat + 0.2 * lift * spec.scale;
            for (double[] o : SEAT_OFFSETS) {
                double x = v.x() + o[0] * spec.scale, z = v.z() + o[1] * spec.scale;
                if (!fits(solid, readable, x, y, z, 1.0)) continue;
                if (!fitted || rock == null) m.seat.set(x, y, z);
                fitted = true;
                if (rock == null) return m;
                int on = onRim(rock, new Vector3d(x, y, z), m.refTop);
                if (on > bestOn) { bestOn = on; m.seat.set(x, y, z); }
                if (on == SIDES) break;
            }
        }
        if (!fitted) return no(why, "its test will not fit in its mouth");
        if (bestOn < ON_RIM) return no(why, String.format(java.util.Locale.ROOT, "only %d of its spines find the rim (its mouth %.1f from its axis, at most %.1f)", bestOn, m.mean, m.widest));
        return m;
    }

    /** The places its seat is tried at, nearest its mouth's axis first (blocks across, at scale 1): out to 0.8 off it. */
    private static final double[][] SEAT_OFFSETS = seatOffsets();

    private static double[][] seatOffsets() {
        java.util.List<double[]> o = new java.util.ArrayList<>();
        for (int gx = -2; gx <= 2; gx++) for (int gz = -2; gz <= 2; gz++) o.add(new double[]{gx * 0.4, gz * 0.4});
        o.sort(java.util.Comparator.comparingDouble(a -> a[0] * a[0] + a[1] * a[1]));
        return o.toArray(new double[0][]);
    }

    /** How many of its spines, laid out sat at {@code seat}, stand on the rim (no lower than a block and a half under its top). */
    static int onRim(Terrain rock, Vector3d seat, double top) {
        LurkerSpec spec = LurkerRig.shared().spec;
        LurkerBody body = new LurkerBody(spec);
        LurkerBody.Drive d = new LurkerBody.Drive();
        d.x = seat.x; d.y = seat.y; d.z = seat.z;
        d.upright = true;
        d.mode = LurkerBody.Mode.SURFACE;
        int[] b = new int[6];
        body.bounds(d, b);
        TerrainPatch patch = new TerrainPatch();
        patch.build(rock, b[0], b[1], b[2], b[3], b[4], b[5]);
        body.tick(patch, d);
        int on = 0;
        for (LurkerBody.Foot ft : body.feet) if (ft.planted && ft.at.y > top - 1.5) on++;
        return on;
    }

    /**
     * Whether its test fits with its middle at (x, y, z): open at its middle and all round its surface ({@code scale}
     * of its size: its half width round, its half height up and down).
     */
    static boolean fits(Solid solid, Readable readable, double x, double y, double z, double scale) {
        LurkerSpec spec = LurkerRig.shared().spec;
        double w = spec.halfWidth * scale * 0.95, h = spec.halfHeight * scale * 0.95;
        if (!open(solid, readable, x, y, z)) return false;
        for (int el = -1; el <= 1; el++) {
            double ce = el == 0 ? 1.0 : Math.cos(Math.toRadians(50)), se = el == 0 ? 0.0 : el * Math.sin(Math.toRadians(50));
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4;
                if (!open(solid, readable, x + Math.cos(a) * w * ce, y + se * h, z + Math.sin(a) * w * ce)) return false;
            }
        }
        return open(solid, readable, x, y + h, z) && open(solid, readable, x, y - h, z);
    }

    /**
     * Where its middle goes over the rim on the side {@code th} (radians from +x toward +z): {@link #CREST_OUT} past the
     * rim's inner edge there, its ride over the rim's top; null with no rim found there (or unreadable blocks).
     */
    @Nullable
    public static Vector3d crest(Solid solid, Readable readable, Home h, double th) {
        double[] r = new double[2];
        int mean = (int) Math.floor(h.top()) - 1;
        if (!rimAt(solid, readable, h.x(), h.z(), th, mean, h.mouth() + 3.0, r)) return null;
        double out = r[0] + CREST_OUT * LurkerRig.shared().spec.scale;
        return new Vector3d(h.x() + Math.cos(th) * out, r[1] + LurkerRig.shared().spec.ride, h.z() + Math.sin(th) * out);
    }

    /** As {@link #crest(Solid, Readable, Home, double)}, read from the level's loaded blocks. */
    @Nullable
    public static Vector3d crest(LevelReader level, Home h, double th) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        return crest((x, y, z) -> solid(level, p.set(x, y, z)), (x, z) -> level.hasChunk(x >> 4, z >> 4), h, th);
    }

    // ------------------------------------------------------------------------------------------------ its home

    /**
     * As {@link #find(LevelReader, GardensField.LiveVent, int, Readable)}, its blocks read through {@code solid} and its
     * rock (to lay its body on, and for the trial runs of its way down and back) through {@code rock}: its seat in the
     * mouth ({@link #mouth}); then the sides of the vent in a turn of its own (from its key), each with no crack under
     * it: its way down from over the rim's inner edge there, out over the rim and down the outside to the floor, and its
     * way back from there up over the rim (trial runs of its crawl, judged as the creature judges them, {@link Out} and
     * {@link In}; one that leaves the readable ground on its way down outside is taken as good). With {@code report},
     * every side is tried and how each went written there.
     */
    @Nullable
    public static Home find(Solid solid, Terrain rock, GardensField.LiveVent v, int bottom, Readable readable, @Nullable StringBuilder report) {
        StringBuilder why = new StringBuilder();
        Mouth m = mouth(solid, rock, v, bottom, readable, why);
        if (m == null) {
            if (report != null) report.append("no seat: ").append(why);
            return null;
        }
        if (report != null) report.append(String.format(java.util.Locale.ROOT, "seat %.1f %.1f %.1f (rim's top %.1f, mouth %.1f from its axis, at most %.1f)",
                m.seat.x, m.seat.y, m.seat.z, m.refTop, m.mean, m.widest));
        double wall = Math.max(v.wall(), m.widest + 1.0);
        int first = (int) Math.floor(phase(v) / (2.0 * Math.PI) * SIDES) % SIDES;
        Home found = null;
        for (int i = 0; i < SIDES; i++) {
            int k = (first + i) % SIDES;
            double th = m.side(k);
            Home h = new Home(v.key(), v.x(), v.z(), m.refTop, th, m.seat.x, m.seat.y, m.seat.z, m.mean, wall, Neon.ofCode(v.colour()));
            why.setLength(0);
            boolean good = sideWorks(solid, rock, h, v.cracks(), readable, why);
            if (report != null) report.append(String.format(java.util.Locale.ROOT, "\n %4.0f deg: %s", Math.toDegrees(LurkerCrawlMath.wrap(th)), good ? "good" : why));
            if (good && found == null) {
                found = h;
                if (report == null) break;
            }
        }
        return found;
    }

    /** Whether its way down and back works on home {@code h}'s side, its vent's cracks {@code cracks} (with why not, if not). */
    static boolean sideWorks(Solid solid, Terrain rock, Home h, double[] cracks, Readable readable, StringBuilder why) {
        double th = h.azimuth();
        if (nearCrack(cracks, th)) { why.append("a crack under it"); return false; }
        Vector3d crest = crest(solid, readable, h, th);
        if (crest == null) { why.append("no rim read"); return false; }
        if (!fits(solid, readable, crest.x, crest.y, crest.z, 0.9)) { why.append("no room over its rim"); return false; }
        // its glide between its seat and over the rim there: room for its test all the way
        for (int s = 1; s < 6; s++) {
            double t = s / 6.0;
            double x = h.waitX() + (crest.x - h.waitX()) * t, z = h.waitZ() + (crest.z - h.waitZ()) * t;
            double y = h.waitY() + (crest.y - h.waitY()) * t + GLIDE_LIFT * Math.sin(Math.PI * t);
            if (!fits(solid, readable, x, y, z, 0.8)) { why.append("no room to glide out over its rim"); return false; }
        }
        Trial out = trialOut(rock, h, crest, readable, why);
        if (out.verdict() == Verdict.LOST) return false;
        if (out.verdict() == Verdict.FLOOR && trialHome(rock, h, crest, out.at(), readable, why).verdict() == Verdict.LOST) return false;
        return true;
    }

    /** How high its glide between its seat and over its rim rises over the straight way (blocks). */
    public static final double GLIDE_LIFT = 0.45;

    /** The first of the sides of vent {@code v} tried for its way down (radians): a turn of its own, from its key. */
    static double phase(GardensField.LiveVent v) {
        return unit(v.key() * 0x9E3779B97F4A7C15L + 0x5DEECE66DL) * 2.0 * Math.PI;
    }

    @Nullable
    private static Mouth no(@Nullable StringBuilder why, String reason) {
        if (why != null) why.append(reason);
        return null;
    }

    private static boolean open(Solid solid, Readable readable, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        return readable.at(bx, bz) && !solid.at(bx, (int) Math.floor(y), bz);
    }

    /** A trial run's end: FLOOR or HOME (and where), ON (off the readable ground on its way, taken as good) or LOST. */
    record Trial(Verdict verdict, @Nullable Vector3d at) {}

    /** Its way down from over its rim at {@code crest} run as a trial: FLOOR (out and down to the floor, and where), ON (off the readable ground, down outside), or LOST. */
    static Trial trialOut(Terrain rock, Home h, Vector3d crest, Readable readable, @Nullable StringBuilder why) {
        LurkerCrawl c = new LurkerCrawl(LurkerRig.shared().spec.ride);
        c.start(new Vector3d(crest), h.x(), h.z(), LurkerCrawl.Sense.OUT);
        Out judge = new Out(h, new Vector3d(crest));
        for (int i = 0; i < 400; i++) {
            boolean held = c.step(rock, ChimneyLurkerEntity.EMERGE_CRAWL);
            if (offEdge(readable, c)) return judge.down() ? new Trial(Verdict.ON, null) : lost(why, "out", "off the readable ground before its rim", c, h);
            if (!held) return lost(why, "out", "lost the rock", c, h);
            Verdict j = judge.judge(c);
            if (j == Verdict.LOST) return lost(why, "out", "lost its way (" + judge.why() + ")", c, h);
            if (j == Verdict.FLOOR) return new Trial(j, new Vector3d(c.pos));
        }
        return lost(why, "out", "too long", c, h);
    }

    /** Its way back from {@code foot} run as a trial: HOME (back over its rim near {@code crest}), ON (off the readable ground), or LOST. */
    static Trial trialHome(Terrain rock, Home h, Vector3d crest, Vector3d foot, Readable readable, @Nullable StringBuilder why) {
        LurkerCrawl c = new LurkerCrawl(LurkerRig.shared().spec.ride);
        c.start(new Vector3d(foot), h.x(), h.z(), LurkerCrawl.Sense.IN);
        In judge = new In(h);
        Vector3d from = new Vector3d(c.pos);
        for (int i = 0; i < 900; i++) {
            boolean held = c.step(rock, ChimneyLurkerEntity.HOME_CRAWL);
            if (offEdge(readable, c)) return new Trial(Verdict.ON, null);
            if (!held) return lost(why, "home", "lost the rock", c, h);
            Verdict j = judge.judge(c);
            if (j == Verdict.LOST) return lost(why, "home", "lost its way (" + judge.why() + ")", c, h);
            if (j == Verdict.HOME) {
                double off = Math.hypot(c.pos.x - crest.x, c.pos.z - crest.z);
                if (off > 2.5 || Math.abs(c.pos.y - crest.y) > 2.5) return lost(why, "home", String.format(java.util.Locale.ROOT, "came over %.1f from where it went", off), c, h);
                return new Trial(j, new Vector3d(c.pos));
            }
            if (i % 30 == 29) {
                if (from.distanceSquared(c.pos) < 0.25) return lost(why, "home", "stuck", c, h);
                from.set(c.pos);
            }
        }
        return lost(why, "home", "too long", c, h);
    }

    /** Whether the crawl's read round it reaches past the readable ground. */
    private static boolean offEdge(Readable readable, LurkerCrawl c) {
        int ax = (int) Math.floor(c.pos.x), az = (int) Math.floor(c.pos.z);
        for (int dx = -LurkerCrawlReach.AROUND; dx <= LurkerCrawlReach.AROUND; dx += LurkerCrawlReach.AROUND)
            for (int dz = -LurkerCrawlReach.AROUND; dz <= LurkerCrawlReach.AROUND; dz += LurkerCrawlReach.AROUND)
                if (!readable.at(ax + dx, az + dz)) return true;
        return false;
    }

    private static Trial lost(@Nullable StringBuilder why, String way, String reason, LurkerCrawl c, Home h) {
        if (why != null) why.append(String.format(java.util.Locale.ROOT, "its way %s %s at %.1f %.1f %.1f (%.1f off its axis, %.1f under its rim, tick %d)",
                way, reason, c.pos.x, c.pos.y, c.pos.z, h.across(c.pos.x, c.pos.z), h.top() - c.pos.y, c.ticks));
        return new Trial(Verdict.LOST, null);
    }

    /** How far round it the crawl reads the rock (as LurkerCrawl's own). */
    static final class LurkerCrawlReach {
        static final int AROUND = 3;
    }

    /**
     * Where its way down from over its rim on its home's side comes down to the floor (a dry run of its crawl down the
     * outside), or null if the crawl loses its way; read only in loaded chunks.
     */
    @Nullable
    public static Vector3d footOf(LevelReader level, Home h) {
        Vector3d crest = crest(level, h, h.azimuth());
        if (crest == null) return null;
        Trial out = trialOut(loaded(level), h, crest, (x, z) -> true, null);
        return out.verdict() == Verdict.FLOOR ? out.at() : null;
    }

    /** How its way over the rim goes, tick by tick. */
    public enum Verdict {
        /** On its way. */
        ON,
        /** Out and down: on the floor outside its vent. */
        FLOOR,
        /** In and up: over its rim's top, by its mouth. */
        HOME,
        /** Lost its way: down inside its vent going out, round its axis, up something over its rim, or too long. */
        LOST
    }

    /**
     * Judges its way down (from over its rim's inner edge, out over the rim, down the outside to the floor), tick by
     * tick: the emerging urchin's own, and the dry runs'. One that sets off outside, down its vent's outer wall (turned
     * back on its way home), is over its rim already.
     */
    public static final class Out {
        private final Home h;
        private final double startY, mouth, ride;
        private boolean crested;
        /** Its body's height as it went over its rim. */
        private double crestY;
        private int flat;
        private String why = "";

        public Out(Home h, Vector3d from) {
            this.h = h;
            this.startY = from.y;
            this.mouth = h.mouth();
            this.ride = LurkerRig.shared().spec.ride;
            crested = from.y > h.top() + 0.3 || h.across(from.x, from.z) > (mouth + h.wall()) * 0.5;
            crestY = from.y;
        }

        /** Whether it has been over its rim and is on its way down outside. */
        public boolean down() { return crested; }

        /** Why it lost its way (for tests and /remix lurker). */
        public String why() { return why; }

        public Verdict judge(LurkerCrawl c) {
            double across = h.across(c.pos.x, c.pos.z), y = c.pos.y;
            // round its axis: the crawl has gone across its vent
            if (across < 0.6) return lost("round its axis");
            if (!crested) {
                if (y > h.top() - 1.5 && c.facingUp() > 0.6) { crested = true; crestY = y; }
                else if (y < startY - 1.5) return lost("down its flue before its rim");
                else if (c.ticks > 120) return lost("never over its rim");
                return Verdict.ON;
            }
            if (y > crestY + 3.0) return lost("up something over its rim");
            // back down inside: in its flue, or on rock that faces its axis within its wall
            double rim = c.highest - ride;
            if (y < rim - 1.0 && (across < mouth - 0.3 || (c.facingOut() < -0.6 && across < h.wall()))) return lost("back down inside");
            boolean below = y < Math.min(rim - 2.5, startY - 1.0);
            flat = below && across > h.wall() + 0.3 && c.facingUp() > 0.75 ? flat + 1 : 0;
            if (flat >= ChimneyLurkerEntity.FLOOR_TICKS || (below && across > h.wall() + 6.0)) return Verdict.FLOOR;
            return c.ticks > 500 ? lost("too long") : Verdict.ON;
        }

        private Verdict lost(String reason) {
            why = reason;
            return Verdict.LOST;
        }
    }

    /**
     * Judges its way home (from outside, up its vent's outer wall and over its rim to its mouth's edge, where it glides
     * down into its seat), tick by tick: the climbing urchin's own, and the tests'.
     */
    public static final class In {
        private final Home h;
        private final double ride;
        private boolean crested;
        /** How far out from the axis it may go on its way to its vent (from where it set off). */
        private double far = Double.NaN;
        private String why = "";

        public In(Home h) {
            this.h = h;
            this.ride = LurkerRig.shared().spec.ride;
        }

        /** Why it lost its way (for tests and /remix lurker). */
        public String why() { return why; }

        public Verdict judge(LurkerCrawl c) {
            double across = h.across(c.pos.x, c.pos.z), y = c.pos.y;
            if (Double.isNaN(far)) far = Math.max(across, h.wall()) + 3.0;
            if (across < 0.6) return lost("round its axis");
            if (!crested) {
                // on a top near its rim's height, within its wall: over its rim (a terrace further down is not)
                if (c.facingUp() > 0.6 && y > h.top() - 2.5 && across < h.wall() + 2.0) crested = true;
                else if (y > h.top() + 5.0) return lost("up something over its rim");
                else if (across > far) return lost("away from its vent (along a roof?)");
                else if (c.ticks > 900) return lost("too long");
                if (!crested) return Verdict.ON;
            }
            // over the rim: home once it comes to the mouth's edge (or starts down inside)
            if (across < h.mouth() + CREST_OUT + 0.4 || c.facingOut() < -0.3) return Verdict.HOME;
            double rim = c.highest - ride;
            if (y < rim - 3.0) return lost("down its flue");
            if (across > h.wall() + 2.5) return lost("back out over its rim");
            return c.ticks > 900 ? lost("too long") : Verdict.ON;
        }

        private Verdict lost(String reason) {
            why = reason;
            return Verdict.LOST;
        }
    }

    /** The level's rock, read only where chunks are loaded (nothing elsewhere: the server never loads one for it). */
    public static Terrain loaded(LevelReader level) {
        LevelTerrain t = new LevelTerrain(level);
        return (x, y, z, sink) -> {
            if (level.hasChunk(x >> 4, z >> 4)) t.boxes(x, y, z, sink);
        };
    }

    static boolean nearCrack(double[] cracks, double az) {
        for (double c : cracks) {
            double d = Math.abs(LurkerCrawlMath.wrap(az - c));
            if (d < CRACK_CLEAR) return true;
        }
        return false;
    }

    static boolean solid(BlockGetter level, BlockPos.MutableBlockPos p) {
        BlockState s = level.getBlockState(p);
        return !s.getCollisionShape(level, p).isEmpty();
    }

    /** A number from 0 to 1 from a key (a hash). */
    static double unit(long k) {
        long h = k * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Small angle sums. */
    static final class LurkerCrawlMath {
        static double wrap(double a) {
            a %= 2.0 * Math.PI;
            if (a > Math.PI) a -= 2.0 * Math.PI;
            if (a < -Math.PI) a += 2.0 * Math.PI;
            return a;
        }
    }
}
