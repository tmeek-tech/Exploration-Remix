package com.explorationremix.creature.firefly;

import java.util.List;

/**
 * Where a landed swarm's fireflies sit: on the real block faces round the swarm's landing anchor, spread out over the
 * patch instead of heaped on one spot. The client lists the faces near the anchor (floor, wall or ceiling, any that
 * face open air), {@link #face} turns each into a few points on it, and {@link #pick} chooses one point per firefly:
 * the one nearest the anchor first, then each next one as far from those already taken as it can be (up to
 * {@link #SPREAD}), while staying near the anchor, so the swarm settles as a loose patch a couple of blocks across.
 *
 * <p>Plain numbers, no game classes, so it can be tested on its own.
 */
public final class Perches {
    /** No firefly settles further than this from the anchor. */
    public static final double REACH = 2.5;
    /** How far a firefly sits off its face, into the air (its glow then never sinks into the rock). */
    public static final double LIFT = 0.03;
    /** Once fireflies are this far apart, spreading them further counts for nothing: nearness to the anchor wins. */
    static final double SPREAD = 0.55;
    /** How much each block of distance from the anchor counts against a point. */
    static final double PULL = 0.18;
    /** A little seeded noise in the choice, so the patch never looks like a grid. */
    static final double JITTER = 0.06;
    /** The points on a face: a two by two grid this far in from its edges, each nudged up to {@link #NUDGE}. */
    static final double INSET = 0.27, NUDGE = 0.08;

    private Perches() {}

    /** A point a firefly can sit on, and the way its face looks (out of the rock, into the air). */
    public record Spot(double x, double y, double z, double nx, double ny, double nz) {
        double dist2(double ox, double oy, double oz) {
            double dx = x - ox, dy = y - oy, dz = z - oz;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /**
     * Adds to {@code out} the points on the face between the open block (bx, by, bz) and its neighbour in direction
     * (dx, dy, dz), the rock: four points spread over the face, each lifted {@link #LIFT} off it into the open block.
     */
    public static void face(int bx, int by, int bz, int dx, int dy, int dz, long seed, List<Spot> out) {
        // the face's plane, half a block from the open block's middle towards the rock
        double cx = bx + 0.5 + dx * (0.5 - LIFT), cy = by + 0.5 + dy * (0.5 - LIFT), cz = bz + 0.5 + dz * (0.5 - LIFT);
        long key = seed ^ (bx * 73856093L) ^ (by * 19349663L) ^ (bz * 83492791L) ^ ((dx + 2) * 3L + (dy + 2) * 7L + (dz + 2) * 11L);
        int k = 0;
        for (double a : new double[]{INSET, 1.0 - INSET})
            for (double b : new double[]{INSET, 1.0 - INSET}) {
                double ja = a + (Pulse.unit(key, k, 7) * 2.0 - 1.0) * NUDGE - 0.5;
                double jb = b + (Pulse.unit(key, k, 8) * 2.0 - 1.0) * NUDGE - 0.5;
                k++;
                // a and b run along the two axes that lie in the face
                double x = cx, y = cy, z = cz;
                if (dx != 0) { y += ja; z += jb; }
                else if (dy != 0) { x += ja; z += jb; }
                else { x += ja; y += jb; }
                out.add(new Spot(x, y, z, -dx, -dy, -dz));
            }
    }

    /**
     * Chooses {@code n} of the candidates for n fireflies, spread out round the anchor (ax, ay, az); returns their
     * indices in the order chosen. Candidates further than {@link #REACH} from the anchor are never chosen. With fewer
     * usable candidates than fireflies the choice goes round again (two fireflies then share a point); with none it is
     * empty.
     */
    public static int[] pick(List<Spot> candidates, double ax, double ay, double az, int n, long seed) {
        int m = candidates.size();
        double[] anchor = new double[m];
        double[] nearest = new double[m];         // distance to the nearest point already taken
        boolean[] taken = new boolean[m];
        int usable = 0;
        for (int i = 0; i < m; i++) {
            anchor[i] = Math.sqrt(candidates.get(i).dist2(ax, ay, az));
            nearest[i] = Double.MAX_VALUE;
            if (anchor[i] <= REACH) usable++;
            else taken[i] = true;                 // out of reach: never offered
        }
        if (usable == 0 || n <= 0) return new int[0];
        int[] out = new int[n];
        int got = 0;
        while (got < Math.min(n, usable)) {
            int best = -1;
            double bestScore = -Double.MAX_VALUE;
            for (int i = 0; i < m; i++) {
                if (taken[i]) continue;
                double spread = got == 0 ? 0.0 : Math.min(nearest[i], SPREAD);
                double score = spread - PULL * anchor[i] + JITTER * Pulse.unit(seed, i, 9);
                if (score > bestScore) { bestScore = score; best = i; }
            }
            taken[best] = true;
            out[got++] = best;
            Spot s = candidates.get(best);
            for (int i = 0; i < m; i++)
                if (!taken[i]) nearest[i] = Math.min(nearest[i], Math.sqrt(candidates.get(i).dist2(s.x, s.y, s.z)));
        }
        for (int i = got; i < n; i++) out[i] = out[i % got];
        return out;
    }
}
