package com.explorationremix.creature;

import com.explorationremix.block.Neon;
import com.explorationremix.creature.body.LevelTerrain;
import com.explorationremix.creature.body.LurkerCrawl;
import com.explorationremix.creature.body.LurkerRig;
import com.explorationremix.creature.body.Terrain;
import com.explorationremix.world.terrain.GardensField;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

import javax.annotation.Nullable;

/**
 * Where the chimney lurkers live (0.21.0): about half the Deep Gardens' live vents (Taran's card: "About half of
 * them"), one to a vent, waiting down in its mouth (card: "Down in the chimney's mouth"). Which vents hold one is
 * decided by each vent's own key, so every chunk agrees; where in the mouth it waits is read from the vent's blocks.
 *
 * <p>Its spot is on the flue's inner wall, head up, {@link ChimneyLurkerEntity#WAIT_DEPTH} under the rim, on a side of
 * the vent with no crack under it (a crack leads into the den, and it is too big to follow anyone through one: card,
 * "Legs spanning about 6 blocks"), with open air over the rim for its front feet to hook over.
 */
public final class LurkerVents {
    private LurkerVents() {}

    /** The share of the live vents with room in their flue that hold a lurker (about half of all live vents). */
    static final double HELD = 0.76;
    /** A flue narrower than this (its mean radius under the top, blocks) is too tight for one to wait in. */
    public static final double MIN_FLUE = 2.0;
    /** Its spot keeps this far round the axis from any of its vent's open cracks (radians). */
    static final double CRACK_CLEAR = Math.toRadians(42);

    /**
     * A lurker's home: its vent (key, axis at the top), the rim's top over its spot, the way round the axis its spot
     * lies (radians from +x toward +z), its spot (its body's middle), its vent wall's outside (across, from the axis)
     * and its colour, the vent's own.
     */
    public record Home(long key, double x, double z, double top, double azimuth, double waitX, double waitY, double waitZ, double wall,
                       Neon colour) {
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
            t.putDouble("Wall", wall);
            t.putByte("Colour", (byte) colour.ordinal());
            return t;
        }

        public static Home load(CompoundTag t) {
            int c = t.getByte("Colour");
            double wx = t.getDouble("WaitX"), wz = t.getDouble("WaitZ"), x = t.getDouble("X"), z = t.getDouble("Z");
            double wall = t.contains("Wall") ? t.getDouble("Wall") : Math.hypot(wx - x, wz - z) + 3.5;
            return new Home(t.getLong("Key"), x, z, t.getDouble("Top"), t.getDouble("Azimuth"), wx, t.getDouble("WaitY"), wz, wall,
                    Neon.ofCode(c < 0 || c > 3 ? 1 : c));
        }

        /** Its flue's inner wall under its spot (across, from the axis): where its spot's rock is. */
        public double flue() { return across(waitX, waitZ) + LurkerRig.shared().spec.ride; }

        /** Its spot as a box position (the box's feet under its body's middle). */
        public double boxY() { return waitY - LurkerRig.shared().spec.ride; }

        /** Where it looks out from while it waits: just over the rim's inner edge above its spot. */
        public Vector3d peek(Vector3d out) {
            double c = Math.cos(azimuth), s = Math.sin(azimuth);
            double r = Math.hypot(waitX - x, waitZ - z) + 0.9;
            return out.set(x + c * r, top + 0.7, z + s * r);
        }

        /** How far (across) a point is from the vent's axis. */
        public double across(double px, double pz) { return Math.hypot(px - x, pz - z); }
    }

    /** Whether a live vent holds a lurker: one with room in its flue, by its key alone. */
    public static boolean held(GardensField.LiveVent v) {
        return v.flue() >= MIN_FLUE && unit(v.key()) < HELD;
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
     * Its home at vent {@code v} read from the blocks, or null if no side of it will do. {@code bottom} is the Deep
     * Gardens layer's lowest world y (the vent's rows count from it). Only columns {@code readable} allows are read.
     */
    @Nullable
    public static Home find(LevelReader level, GardensField.LiveVent v, int bottom, Readable readable) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        LevelTerrain lt = new LevelTerrain(level);
        return find((x, y, z) -> solid(level, p.set(x, y, z)), (x, y, z, sink) -> { if (readable.at(x, z)) lt.boxes(x, y, z, sink); }, v, bottom, readable);
    }

    /** For /remix lurker sides: each of vent {@code v}'s twelve sides and why it will or will not do, read from the level. */
    public static String sides(LevelReader level, GardensField.LiveVent v, int bottom, Readable readable) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        LevelTerrain lt = new LevelTerrain(level);
        Solid solid = (x, y, z) -> solid(level, p.set(x, y, z));
        Terrain rock = (x, y, z, sink) -> { if (readable.at(x, z)) lt.boxes(x, y, z, sink); };
        StringBuilder b = new StringBuilder();
        double phase = phase(v);
        for (int i = 0; i < 12; i++) {
            double az = phase + i * (2.0 * Math.PI / 12.0);
            StringBuilder why = new StringBuilder();
            Home h = spotAt(solid, rock, v, bottom, readable, az, why);
            b.append(String.format(java.util.Locale.ROOT, "\n %4.0f deg: %s", Math.toDegrees(LurkerCrawlMath.wrap(az)),
                    h != null ? String.format(java.util.Locale.ROOT, "good, spot %.1f %.1f %.1f under rim %.0f", h.waitX(), h.waitY(), h.waitZ(), h.top()) : why));
        }
        return b.toString();
    }

    /** For /remix lurker trial: its ways out and home from its spot run as trials over the level's loaded rock, and how they went. */
    public static String trial(LevelReader level, Home h) {
        Terrain rock = loaded(level);
        Readable readable = (x, z) -> level.hasChunk(x >> 4, z >> 4);
        StringBuilder why = new StringBuilder();
        Trial out = trialOut(rock, h, readable, why);
        if (out.verdict() != Verdict.FLOOR) return out.verdict() == Verdict.ON ? "out: off the loaded ground on its way down outside" : "out: " + why;
        String o = String.format(java.util.Locale.ROOT, "out: to the floor at %.1f %.1f %.1f (%.1f off its axis, %.1f under its rim)",
                out.at().x, out.at().y, out.at().z, h.across(out.at().x, out.at().z), h.top() - out.at().y);
        Trial home = trialHome(rock, h, out.at(), readable, why);
        return o + "; home: " + (home.verdict() == Verdict.HOME ? String.format(java.util.Locale.ROOT, "back at %.1f %.1f %.1f", home.at().x, home.at().y, home.at().z)
                : home.verdict() == Verdict.ON ? "off the loaded ground" : why.toString());
    }

    /**
     * As {@link #find(LevelReader, GardensField.LiveVent, int, Readable)}, its blocks read through {@code solid} and its
     * rock (for the trial runs of its ways out and home) through {@code rock}. The sides of the vent are tried in a turn
     * of its own (from its key): on each, its spot on the flue's inner wall {@link ChimneyLurkerEntity#WAIT_DEPTH} under
     * the rim's top there, with room round it and up through the mouth; then its way out from there over the rim and down
     * the outside to the floor, and its way home from there back to its spot (trial runs of its crawl, judged as the
     * creature judges them, {@link Out} and {@link In}; one that leaves the readable ground on its way down outside is
     * taken as good).
     */
    @Nullable
    public static Home find(Solid solid, Terrain rock, GardensField.LiveVent v, int bottom, Readable readable) {
        double phase = phase(v);
        for (int i = 0; i < 12; i++) {
            Home h = spotAt(solid, rock, v, bottom, readable, phase + i * (2.0 * Math.PI / 12.0), null);
            if (h != null) return h;
        }
        return null;
    }

    /** The first of the twelve sides of vent {@code v} tried for its spot (radians): a turn of its own, from its key. */
    static double phase(GardensField.LiveVent v) {
        return unit(v.key() * 0x9E3779B97F4A7C15L + 0x5DEECE66DL) * 2.0 * Math.PI;
    }

    /** The narrowest its flue may be between its spot and its rim (from the axis to the wall, blocks): its body's way up. */
    static final double MOUTH = 1.75;

    /**
     * Its home on the side {@code az} (radians from +x toward +z) of vent {@code v}, or null (with why, if asked) if that
     * side will not do: a crack under it, no room in the flue there or up through its mouth, no open air over the rim,
     * or (with {@code rock} to run them over) no way out from it or home to it.
     */
    @Nullable
    static Home spotAt(Solid solid, @Nullable Terrain rock, GardensField.LiveVent v, int bottom, Readable readable, double az, @Nullable StringBuilder why) {
        double ride = LurkerRig.shared().spec.ride;
        if (nearCrack(v.cracks(), az)) return no(why, "a crack under it");
        double c = Math.cos(az), s = Math.sin(az);
        int mean = (int) Math.floor(v.topRow() + bottom);
        double reach = v.flue() + 4.0;
        // the flue's inner wall on this side, row by row from well under the rim's mean top up to where the wall ends
        int y0 = mean - 3;
        double[] wall = new double[12];
        wall[0] = wallAt(solid, readable, v, c, s, y0, reach);
        if (Double.isNaN(wall[0])) return no(why, "no inner wall at row " + y0);
        int ty = Integer.MIN_VALUE;
        for (int k = 1; k < wall.length; k++) {
            wall[k] = wallAt(solid, readable, v, c, s, y0 + k, wall[k - 1] + 1.5);
            if (Double.isNaN(wall[k])) { ty = y0 + k; break; }
        }
        if (ty == Integer.MIN_VALUE) return no(why, "its wall runs on up past its rim (into the roof?)");
        // ty: the first open row over the wall's top. Its spot's row, two under: room for it, and up through its mouth
        int ys = ty - (int) Math.round(ChimneyLurkerEntity.WAIT_DEPTH);
        double rs = ys >= y0 ? wall[ys - y0] : wallAt(solid, readable, v, c, s, ys, reach);
        if (Double.isNaN(rs)) return no(why, "no inner wall at its spot's row " + ys);
        if (rs < MIN_FLUE) return no(why, String.format(java.util.Locale.ROOT, "the flue only %.1f across at its spot", rs));
        for (int y = Math.max(ys + 1, y0); y < ty; y++)
            if (wall[y - y0] < MOUTH) return no(why, String.format(java.util.Locale.ROOT, "its mouth narrows to %.1f at row %d", wall[y - y0], y));
        // across the flue from its spot: room for its body and legs (no narrow slot)
        if (!open(solid, readable, v.x() - c * 0.5, ys, v.z() - s * 0.5) || !open(solid, readable, v.x() - c * 1.5, ys, v.z() - s * 1.5))
            return no(why, "rock across the flue");
        // over the rim's inner edge: open air for its front feet, and headroom to go over
        double rt = wall[ty - 1 - y0];
        int bx = (int) Math.floor(v.x() + c * (rt + 0.45)), bz = (int) Math.floor(v.z() + s * (rt + 0.45));
        if (!readable.at(bx, bz)) return no(why, "its rim unread");
        if (solid.at(bx, ty, bz) || solid.at(bx, ty + 1, bz)) return no(why, "no air over its rim at " + ty);
        // its spot on the inner wall under the rim, in the open (in from a lip over it, if it must be), with room toward the axis over it
        double wy = ty - ChimneyLurkerEntity.WAIT_DEPTH, r = rs - ride;
        while (r >= 1.0 && !(open(solid, readable, v.x() + c * r, wy, v.z() + s * r) && open(solid, readable, v.x() + c * r, wy + 1.0, v.z() + s * r))) r -= 0.25;
        if (r < 1.0) return no(why, "its spot in rock");
        double wx = v.x() + c * r, wz = v.z() + s * r;
        if (!open(solid, readable, wx - c, wy, wz - s) || !open(solid, readable, wx - c, wy + 1.0, wz - s)) return no(why, "no room by its spot");
        Home h = new Home(v.key(), v.x(), v.z(), ty, az, wx, wy, wz, Math.max(v.wall(), rt + 1.0), Neon.ofCode(v.colour()));
        if (rock == null) return h;
        Trial out = trialOut(rock, h, readable, why);
        if (out.verdict() == Verdict.LOST) return null;
        if (out.verdict() == Verdict.FLOOR && trialHome(rock, h, out.at(), readable, why).verdict() == Verdict.LOST) return null;
        return h;
    }

    /** How far out from the axis the flue's wall is on row {@code y} (blocks; NaN if none within {@code reach}). */
    private static double wallAt(Solid solid, Readable readable, GardensField.LiveVent v, double c, double s, int y, double reach) {
        for (double r = 0.6; r <= reach; r += 0.2) {
            int bx = (int) Math.floor(v.x() + c * r), bz = (int) Math.floor(v.z() + s * r);
            if (!readable.at(bx, bz)) return Double.NaN;
            if (solid.at(bx, y, bz)) return r;
        }
        return Double.NaN;
    }

    @Nullable
    private static Home no(@Nullable StringBuilder why, String reason) {
        if (why != null) why.append(reason);
        return null;
    }

    private static boolean open(Solid solid, Readable readable, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        return readable.at(bx, bz) && !solid.at(bx, (int) Math.floor(y), bz);
    }

    /** A trial run's end: FLOOR or HOME (and where), ON (off the readable ground on its way, taken as good) or LOST. */
    record Trial(Verdict verdict, @Nullable Vector3d at) {}

    /** Its way out from its spot run as a trial: FLOOR (out and down to the floor, and where), ON (off the readable ground, down outside), or LOST. */
    static Trial trialOut(Terrain rock, Home h, Readable readable, @Nullable StringBuilder why) {
        LurkerCrawl c = new LurkerCrawl(LurkerRig.shared().spec.ride);
        c.start(new Vector3d(h.waitX(), h.waitY(), h.waitZ()), h.x(), h.z(), LurkerCrawl.Sense.OUT);
        Out judge = new Out(h, new Vector3d(h.waitX(), h.waitY(), h.waitZ()));
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

    /** Its way home from {@code foot} run as a trial: HOME (back in its mouth near its spot), ON (off the readable ground), or LOST. */
    static Trial trialHome(Terrain rock, Home h, Vector3d foot, Readable readable, @Nullable StringBuilder why) {
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
                double off = Math.hypot(c.pos.x - h.waitX(), c.pos.z - h.waitZ());
                if (off > 2.5 || Math.abs(c.pos.y - h.waitY()) > 2.5) return lost(why, "home", String.format(java.util.Locale.ROOT, "came in %.1f from its spot", off), c, h);
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
     * Where its way out from its spot comes down to the floor (a dry run of its crawl over the rim and down the
     * outside), or null if the crawl loses its way; read only in loaded chunks.
     */
    @Nullable
    public static Vector3d footOf(LevelReader level, Home h) {
        return footOf(loaded(level), h);
    }

    /** As {@link #footOf(LevelReader, Home)}, over the rock {@code t}. */
    @Nullable
    public static Vector3d footOf(Terrain t, Home h) {
        Trial out = trialOut(t, h, (x, z) -> true, null);
        return out.verdict() == Verdict.FLOOR ? out.at() : null;
    }

    /** How its way over the rim goes, tick by tick. */
    public enum Verdict {
        /** On its way. */
        ON,
        /** Out and down: on the floor outside its vent. */
        FLOOR,
        /** In and down: at its spot in its mouth. */
        HOME,
        /** Lost its way: down inside its vent going out, round its axis, up something over its rim, or too long. */
        LOST
    }

    /**
     * Judges its way out (up its flue's inner wall, over its rim, down the outside to the floor), tick by tick: the
     * emerging lurker's own, and the dry runs'. One that sets off outside its flue (on its rim's top or its outer wall,
     * turned back on its way home) is over already.
     */
    public static final class Out {
        private final Home h;
        private final double startY, flue, ride;
        private boolean crested;
        /** Its body's height as it went over its rim. */
        private double crestY;
        private int flat;
        private String why = "";

        public Out(Home h, Vector3d from) {
            this.h = h;
            this.startY = from.y;
            this.flue = h.flue();
            this.ride = LurkerRig.shared().spec.ride;
            crested = from.y > h.top() + 0.3 || h.across(from.x, from.z) > (flue + h.wall()) * 0.5;
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
            if (y < rim - 1.0 && (across < flue - 0.3 || (c.facingOut() < -0.6 && across < h.wall()))) return lost("back down inside");
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
     * Judges its way home (from outside, up its vent's outer wall, over its rim and down its flue's inner wall to its
     * spot's depth), tick by tick: the climbing lurker's own, and the tests'.
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

        /** The rim's top where it went over (its body's middle rode over it). */
        public double rim(LurkerCrawl c) { return c.highest - ride; }

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
                return Verdict.ON;
            }
            double rim = rim(c);
            if (c.facingOut() < -0.6 && across < h.wall() && y <= rim - ChimneyLurkerEntity.WAIT_DEPTH) return Verdict.HOME;
            if (y < rim - ChimneyLurkerEntity.WAIT_DEPTH - 2.0) return lost("down its flue past its spot");
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
