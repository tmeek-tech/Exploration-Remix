package com.explorationremix.world.feature;

import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Plans;
import com.explorationremix.world.terrain.Route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a den gate's lair lies ({@link DenLairFeature}): the Mossback den on the upper landing's floor ring, the bones
 * and scraps strewn within {@link #REACH} blocks of it, and the bone blocks set into the landing floor. Everything here
 * is a pure function of the seed and the gate's plan, so every chunk that decorates part of a lair agrees on it; the
 * only thing left to the world is which heights hold a floor, and each of those questions is asked by one chunk only.
 * <p>
 * The den: hashed spots on the ring {@link #RING_MIN} to {@link #RING_MAX} blocks from the landing centre, at least
 * {@link #RAMP_CLEAR} away from the ramp's direction and with the lizard's 2 x 2 clear of the passage's mouth. They are
 * kept to the one chunk that holds most of them, so that chunk alone tries them against the floor (a chunk sees only
 * the chunks round it, and a lair's far edge is two or three chunks off). The first spot is the lair's centre, which
 * the scatter is laid round; the others follow nearest first, so the den lands as close to that centre as the floor
 * allows.
 */
public final class DenLair {
    /** The den lies this far from the landing centre (blocks, to the middle of its block): on the floor ring round the mouth. */
    public static final double RING_MIN = 7, RING_MAX = 10;
    /** And at least this far round from the ramp's direction (radians), so it is not in the way up. */
    public static final double RAMP_CLEAR = Math.toRadians(60);
    /** Bones and scraps lie within this of the lair's centre (blocks, across). */
    public static final double REACH = 30;
    public static final int SCRAPS_MIN = 18, SCRAPS_MAX = 26, BONES_MIN = 2, BONES_MAX = 4;
    /** The bone blocks lie this far from the landing centre: the ring of floor round the mouth. */
    public static final double BONE_RING_MIN = 4.5, BONE_RING_MAX = 10.5;
    /** The den's floor is looked for this far above and below the landing's planned floor (it is noisy by about a block). */
    public static final int DEN_UP = 3, DEN_DOWN = 3;
    /** A scrap's column is scanned from this far above the den's height down to this far below it. */
    public static final int SCAN_ABOVE = 40, SCAN_BELOW = 4;
    private static final int DEN_TRIES = 96, BONE_TRIES = 32;
    /** Keep the den this much clear of the mouth beyond the passage's radius (its edge wanders about a block). */
    private static final double DEN_MOUTH_PAD = 2.5;
    private static final double MOUTH_PAD = 1.5;
    /** Nothing is strewn closer than this to the lair's centre: the den's own 2 x 2. */
    private static final double SCRAP_NEAR = 1.5;
    /** How often each look turns up: ribs and vertebrae, a skull fragment, feathers and fur, shed scales and hide. */
    private static final double[] LOOK_WEIGHTS = {0.34, 0.16, 0.25, 0.25};
    // fresh salts (Plans uses 101-105, 777, 3001, 4101, 4177, 7101-7103, 7400, 7700, 8800, 8811, 8812, 9311)
    private static final long SALT_DEN = 4201, SALT_SCRAPS = 4202, SALT_BONES = 4203;

    /** A point strewn on the floor: its column, and for a scrap its look (0-3) and turn (0-3, south west north east). */
    public record Point(int x, int z, int look, int turn) {}

    /** Asks the world whether the den fits with its block at (x, y, z). */
    public interface Floor {
        boolean fits(int x, int y, int z);
    }

    public final Plans.Gate gate;
    /** The ramp's direction from the landing centre (radians, atan2 of z and x). */
    public final double rampAngle;
    /** The den's spots in the order they are tried: x and z of the den block, the low corner of the lizard's 2 x 2. */
    public final List<int[]> spots;
    /** The chunk holding every spot (meaningless when there are none). */
    public final int chunkX, chunkZ;
    /** The lair's centre: the middle of the first spot's 2 x 2. */
    public final double centreX, centreZ;
    /** The den's planned height: its block sits on the landing floor, here or a block either way. */
    public final int floorY;
    public final List<Point> scraps, bones;

    private DenLair(Plans.Gate gate, double rampAngle, List<int[]> spots, int chunkX, int chunkZ, double centreX, double centreZ,
                    int floorY, List<Point> scraps, List<Point> bones) {
        this.gate = gate; this.rampAngle = rampAngle; this.spots = spots; this.chunkX = chunkX; this.chunkZ = chunkZ;
        this.centreX = centreX; this.centreZ = centreZ; this.floorY = floorY; this.scraps = scraps; this.bones = bones;
    }

    /** Whether any part of a gate's lair could fall in the box (block coordinates, inclusive). */
    public static boolean mayReach(Plans.Gate g, int minX, int minZ, int maxX, int maxZ) {
        double r = RING_MAX + 2 + REACH + 1;
        return g.ux + r >= minX && g.ux - r <= maxX + 1 && g.uz + r >= minZ && g.uz - r <= maxZ + 1;
    }

    /** The lair of a den gate, from the world seed, the gate and its ramp ({@code Plans.ramp(g)}). */
    public static DenLair of(long seed, Plans.Gate g, Route.Link ramp) {
        double rampAngle = Math.atan2(ramp.z2() - ramp.z1(), ramp.x2() - ramp.x1());
        int floorY = (int) Math.round(g.uFloor);

        // the den's spots, hashed round the allowed part of the ring
        long hd = Hashing.mix(Hashing.mix(seed, SALT_DEN, g.cellX, g.cellZ), g.den, 0, 0);
        List<int[]> all = new ArrayList<>();
        for (int i = 0; i < DEN_TRIES; i++) {
            double a = rampAngle + RAMP_CLEAR + Hashing.unit(Hashing.mix(hd, i, 1, 0)) * (2 * Math.PI - 2 * RAMP_CLEAR);
            double d = Hashing.range(Hashing.mix(hd, i, 2, 0), RING_MIN, RING_MAX);
            int x = (int) Math.floor(g.ux + Math.cos(a) * d), z = (int) Math.floor(g.uz + Math.sin(a) * d);
            if (!onRing(g, rampAngle, x, z) || contains(all, x, z)) continue;
            if (mouth2x2(g, x, z, floorY)) continue;
            all.add(new int[]{x, z});
        }
        // kept to the chunk that holds most of them (the first such in the order tried)
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (int[] s : all) counts.merge(chunkKey(s[0] >> 4, s[1] >> 4), 1, Integer::sum);
        long best = 0;
        int bestN = 0;
        for (Map.Entry<Long, Integer> e : counts.entrySet()) if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
        List<int[]> spots = new ArrayList<>();
        for (int[] s : all) if (chunkKey(s[0] >> 4, s[1] >> 4) == best) spots.add(s);
        int chunkX = 0, chunkZ = 0;
        double cx, cz;
        if (spots.isEmpty()) {
            // never seen: the mouth and the ramp leave most of the ring free
            double a = rampAngle + Math.PI;
            cx = g.ux + Math.cos(a) * (RING_MIN + RING_MAX) * 0.5;
            cz = g.uz + Math.sin(a) * (RING_MIN + RING_MAX) * 0.5;
        } else {
            int[] first = spots.get(0);
            chunkX = first[0] >> 4;
            chunkZ = first[1] >> 4;
            cx = first[0] + 1;
            cz = first[1] + 1;
            final double fx = cx, fz = cz;
            // the rest nearest the first, so the den keeps close to the centre the scatter is laid round (a stable sort)
            List<int[]> rest = new ArrayList<>(spots.subList(1, spots.size()));
            rest.sort(Comparator.comparingDouble(s -> (s[0] + 1 - fx) * (s[0] + 1 - fx) + (s[1] + 1 - fz) * (s[1] + 1 - fz)));
            spots = new ArrayList<>(spots.size());
            spots.add(first);
            spots.addAll(rest);
        }

        // the scraps, thinning out from the centre: the distance is even, so they are packed closest near the den
        long hs = Hashing.mix(Hashing.mix(seed, SALT_SCRAPS, g.cellX, g.cellZ), g.den, 0, 0);
        int n = SCRAPS_MIN + (int) (Hashing.unit(Hashing.mix(hs, 0, 0, 1)) * (SCRAPS_MAX - SCRAPS_MIN + 1));
        List<Point> scraps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = Hashing.range(Hashing.mix(hs, i, 1, 0), 0, 2 * Math.PI);
            double d = Hashing.range(Hashing.mix(hs, i, 2, 0), SCRAP_NEAR, REACH);
            int x = (int) Math.floor(cx + Math.cos(a) * d), z = (int) Math.floor(cz + Math.sin(a) * d);
            if (containsPoint(scraps, x, z)) continue;
            scraps.add(new Point(x, z, look(Hashing.unit(Hashing.mix(hs, i, 3, 0))), (int) (Hashing.unit(Hashing.mix(hs, i, 4, 0)) * 4)));
        }

        // the bone blocks, on the floor ring round the mouth and a few blocks apart
        long hb = Hashing.mix(Hashing.mix(seed, SALT_BONES, g.cellX, g.cellZ), g.den, 0, 0);
        int k = BONES_MIN + (int) (Hashing.unit(Hashing.mix(hb, 0, 0, 1)) * (BONES_MAX - BONES_MIN + 1));
        List<Point> bones = new ArrayList<>();
        for (int i = 0; i < BONE_TRIES && bones.size() < k; i++) {
            double a = Hashing.range(Hashing.mix(hb, i, 1, 0), 0, 2 * Math.PI);
            double d = Hashing.range(Hashing.mix(hb, i, 2, 0), BONE_RING_MIN, BONE_RING_MAX);
            int x = (int) Math.floor(g.ux + Math.cos(a) * d), z = (int) Math.floor(g.uz + Math.sin(a) * d);
            if (inMouth(g, x + 0.5, z + 0.5, floorY - 1, floorY, MOUTH_PAD)) continue;
            if (Math.hypot(x + 0.5 - cx, z + 0.5 - cz) < 2.5) continue;           // not hidden under the den
            boolean near = false;
            for (Point b : bones) if (Math.abs(b.x() - x) < 3 && Math.abs(b.z() - z) < 3) { near = true; break; }
            if (!near) bones.add(new Point(x, z, 0, 0));
        }
        return new DenLair(g, rampAngle, List.copyOf(spots), chunkX, chunkZ, cx, cz, floorY, List.copyOf(scraps), List.copyOf(bones));
    }

    /**
     * The den's block: the first spot, in order, where the floor fits ({@code strict}: room for the lizard), each tried
     * from {@link #DEN_UP} above the planned floor down to {@link #DEN_DOWN} below it; failing that, the first where
     * {@code loose} fits (the den alone), so a gate is never left without its den while there is any floor. Null when
     * neither finds one.
     */
    public int[] den(Floor strict, Floor loose) {
        for (Floor f : new Floor[]{strict, loose}) {
            if (f == null) continue;
            for (int[] s : spots)
                for (int y = floorY + DEN_UP; y >= floorY - DEN_DOWN; y--)
                    if (f.fits(s[0], y, s[1])) return new int[]{s[0], y, s[1]};
        }
        return null;
    }

    /** Whether the den's spots lie in this chunk: only that chunk looks for the den's floor and places it. */
    public boolean denIn(int chunkX, int chunkZ) {
        return !spots.isEmpty() && this.chunkX == chunkX && this.chunkZ == chunkZ;
    }

    /**
     * Whether an open block just over a floor at (x, y, z) is in or beside the passage's mouth, where nothing is
     * strewn: it would only fall down the way. Above the landing floor (the ramp, the pack's caves) it never is.
     */
    public boolean mouth(int x, int y, int z) {
        if (y > floorY + 3) return false;
        return inMouth(gate, x + 0.5, z + 0.5, Math.min(y - 1, floorY - 1), Math.max(y, floorY), MOUTH_PAD);
    }

    /** Whether the den block at (x, z) is on the ring, clear of the ramp's direction. */
    static boolean onRing(Plans.Gate g, double rampAngle, int x, int z) {
        double dx = x + 0.5 - g.ux, dz = z + 0.5 - g.uz;
        double d = Math.hypot(dx, dz);
        return d >= RING_MIN && d <= RING_MAX && angleOff(Math.atan2(dz, dx), rampAngle) >= RAMP_CLEAR;
    }

    /** Whether the lizard's 2 x 2 with its low corner at (x, z) comes near the passage's mouth in the landing floor. */
    static boolean mouth2x2(Plans.Gate g, int x, int z, int floorY) {
        for (int dx = 0; dx < 2; dx++)
            for (int dz = 0; dz < 2; dz++)
                if (inMouth(g, x + dx + 0.5, z + dz + 0.5, floorY - 1, floorY + 1, DEN_MOUTH_PAD)) return true;
        return false;
    }

    /**
     * Whether (x, z) lies within the passage's radius plus {@code pad} of its centre line at any whole height from
     * {@code yLo} to {@code yHi}: the hole the passage cuts through the floor as it leaves the landing.
     */
    static boolean inMouth(Plans.Gate g, double x, double z, int yLo, int yHi, double pad) {
        for (int y = yLo; y <= yHi; y++) {
            double t = g.tAt(y);
            if (Double.isNaN(t)) continue;
            double dx = x - g.px(t), dz = z - g.pz(t), r = g.radiusAt(t) + pad;
            if (dx * dx + dz * dz < r * r) return true;
        }
        return false;
    }

    /** How far apart two directions are (radians, 0 to pi). */
    static double angleOff(double a, double b) {
        return Math.abs(Math.IEEEremainder(a - b, 2 * Math.PI));
    }

    private static int look(double u) {
        for (int i = 0; i < LOOK_WEIGHTS.length - 1; i++) {
            if (u < LOOK_WEIGHTS[i]) return i;
            u -= LOOK_WEIGHTS[i];
        }
        return LOOK_WEIGHTS.length - 1;
    }

    private static long chunkKey(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL); }

    private static boolean contains(List<int[]> l, int x, int z) {
        for (int[] s : l) if (s[0] == x && s[1] == z) return true;
        return false;
    }

    private static boolean containsPoint(List<Point> l, int x, int z) {
        for (Point p : l) if (p.x() == x && p.z() == z) return true;
        return false;
    }
}
