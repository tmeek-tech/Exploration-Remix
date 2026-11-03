package com.explorationremix.client;

/**
 * Where the volcanic smoke rises, kept apart from Minecraft so a test can walk it. The ground round the player is cut
 * into cells {@link #CELL} blocks across; each cell has {@link #SPOTS} fixed spots that it tries in its own order, and
 * puffs from the first that suits on its own beat, every puff with about the cell's own rise, so its puffs follow one
 * another up as a steady column standing over the lava (picture 1's plumes), not a scatter of puffs. With several spots
 * a cell finds a narrow river or a pool that one spot would mostly miss.
 *
 * <ul>
 *     <li>In the Galleries: half the cells out to {@link #NEAR} cells (about 60 blocks) are vents that puff every
 *     {@link #BEAT} ticks, and a quarter of those out to {@link #FAR} cells (about 110 blocks) every {@link #FAR_BEAT}:
 *     the shader greys anything past about a hundred blocks, so far plumes are fewer. A vent puffs from its first spot
 *     with lava under open air going down the spot's column from 40 over the player to 140 under (the lava sea, the
 *     terrace pools and the lips of the falls alike); a vent with no such spot has no plume.</li>
 *     <li>Through an opening, from either layer: any cell out to {@link #OPEN} cells with a spot open at the Galleries'
 *     top row and the row over it, with nothing but open air down to the lava, sends up from it a tall plume every
 *     {@link #BEAT} ticks that rises out through the hole, {@link #OUT} blocks over the top row, into the Wasteland, where
 *     it is still thick and gathers under the Wasteland's roof (the client has no seed, so it reads the blocks rather
 *     than the Wasteland's field). Its openings come first: such a cell has no plain plume.</li>
 *     <li>From the Wasteland only the openings' plumes rise; its own lava smokes near the player (CaveAtmosphere's
 *     sampler).</li>
 * </ul>
 * The cells go nearest first, so when the client's count of puffs is full ({@link SmokeBudget}) the farthest plumes go
 * short, on every side alike, and whole: a plume the count turns away rests a while, the far ones first.
 */
final class SmokeColumns {
    /** What the plumes need to know of the blocks. */
    interface Blocks {
        /** Air the smoke rises through. */
        boolean open(int x, int y, int z);

        boolean lava(int x, int y, int z);
    }

    /**
     * Where a puff starts: over lava whose surface is {@code above} blocks under it, to rise about {@code reach}; a puff
     * of the plume of cell {@code plume}, which is past the near cells when {@code far}.
     */
    interface Sink {
        void puff(double x, double y, double z, double reach, double above, long plume, boolean far);
    }

    static final int CELL = 12;
    /** The spots a cell tries for its plume, first to last. */
    static final int SPOTS = 4;
    /** Cells out from the player's: vents to NEAR and FAR (Galleries), openings to OPEN (either layer). */
    static final int NEAR = 5, FAR = 9, OPEN = 6;
    /** Ticks between a cell's puffs, near and far: at one a second a plume's puffs touch from the lava up. */
    static final int BEAT = 20, FAR_BEAT = 40;
    /** How far over and under the player a spot's column is searched for lava (Galleries). */
    static final int SCAN_UP = 40, SCAN_DOWN = 140;
    /**
     * How far over the Galleries' top an opening's plume aims to rise: out through the hole and up the Wasteland's caves,
     * so its puffs leave the hole at about their full thickness (a puff thins over the last fifth of its rise).
     */
    static final int OUT = 36;
    /** A vent's rise is its own, 45 to 90 blocks, and each puff's within a ninth of it: 40 to 100. */
    static final double RISE_MIN = 45, RISE_MAX = 90, RISE_JITTER = 0.11;
    /** Rows not known (no lava cycle sync from the server): no openings. */
    static final int UNKNOWN = Integer.MIN_VALUE;

    /** The cells' offsets out to FAR, nearest first. */
    static final int[][] RINGS = rings(FAR);

    private SmokeColumns() {}

    static int[][] rings(int r) {
        java.util.List<int[]> l = new java.util.ArrayList<>();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) if (dx * dx + dz * dz <= r * r) l.add(new int[]{dx, dz});
        l.sort(java.util.Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        return l.toArray(new int[0][]);
    }

    /** A cell's key, for its plume. */
    static long plume(int gx, int gz) { return (long) gx << 32 ^ gz & 0xFFFFFFFFL; }

    static long hash(int gx, int gz) {
        long h = gx * 0x9E3779B97F4A7C15L ^ gz * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ h >>> 32;
    }

    /** The hash of a cell's spot {@code c}: the cell's own for the first spot, a mix of it for the others. */
    static long spot(long h, int c) {
        if (c == 0) return h;
        long k = h + c * 0xD6E8FEB86659FD93L;
        k = (k ^ k >>> 32) * 0xD6E8FEB86659FD93L;
        return k ^ k >>> 32;
    }

    /** A spot along x from its hash (and, from the hash's other bits, z). */
    static int spotX(int gx, long h) { return gx * CELL + (int) Long.remainderUnsigned(h, CELL); }

    static int spotZ(int gz, long h) { return gz * CELL + (int) Long.remainderUnsigned(h >>> 20, CELL); }

    /** The beat a Galleries cell {@code d2} cells squared from the player's puffs its plain plume on, or 0 for none. */
    static int ventBeat(long h, int d2) {
        if (d2 <= NEAR * NEAR) return (h >>> 40 & 1) == 0 ? BEAT : 0;
        if (d2 <= FAR * FAR) return (h >>> 40 & 3) == 0 ? FAR_BEAT : 0;
        return 0;
    }

    /** Whether a cell puffs at {@code time} on {@code beat} (0: never); each cell has its own phase. */
    static boolean on(long h, long time, int beat) { return beat > 0 && Math.floorMod(time + (h >>> 44), beat) == 0; }

    /** A vent's own rise, from its hash. */
    static double rise(long h) { return RISE_MIN + (RISE_MAX - RISE_MIN) * ((h >>> 48 & 0xFFFF) / 65535.0); }

    /**
     * Sends the puffs due at {@code time} round the player at (px, py, pz) to {@code out}; returns how many.
     *
     * @param galleries the player is in the Galleries (else in the Wasteland or its refuges)
     * @param top       the Galleries' top row ({@link #UNKNOWN} when not known: no openings then)
     * @param table     the lava table (the lava fills the open space under it; known with the top row)
     */
    static int puff(Blocks w, long time, int px, int py, int pz, boolean galleries, int top, int table, Sink out) {
        int cx = Math.floorDiv(px, CELL), cz = Math.floorDiv(pz, CELL), n = 0;
        int r = galleries ? FAR : OPEN;
        for (int[] o : RINGS) {
            int dx = o[0], dz = o[1], d2 = dx * dx + dz * dz;
            if (d2 > r * r) break;
            int gx = cx + dx, gz = cz + dz;
            long h = hash(gx, gz);
            boolean opening = top != UNKNOWN && d2 <= OPEN * OPEN && on(h, time, BEAT);
            boolean plain = galleries && on(h, time, ventBeat(h, d2));
            if (!opening && !plain) continue;
            // a block of wander about the spot (inside its block), so the column is not a line of puffs one over the other
            long k = hash(gx ^ (int) time, gz ^ (int) (time >>> 32));
            double jx = (k & 0xFF) / 256.0 - 0.5, jz = (k >>> 8 & 0xFF) / 256.0 - 0.5, u = (k >>> 16 & 0xFFFF) / 65535.0;
            int x = 0, z = 0, lava = UNKNOWN;
            // an opening first: a spot open at the top row and the row over it, with only open air down to the lava
            for (int c = 0; c < SPOTS && top != UNKNOWN && lava == UNKNOWN; c++) {
                long s = spot(h, c);
                x = spotX(gx, s);
                z = spotZ(gz, s);
                if (w.open(x, top, z) && w.open(x, top + 1, z)) lava = clearDrop(w, x, z, top, table - 1);
            }
            if (lava != UNKNOWN) {
                // an opening's cell puffs only its tall plume, on the opening's beat
                if (opening) {
                    out.puff(x + 0.5 + jx, lava + 1.1, z + 0.5 + jz, top + OUT - (lava + 1), 0.1, plume(gx, gz), d2 > NEAR * NEAR);
                    n++;
                }
                continue;
            }
            if (!plain) continue;
            int yTop = py + SCAN_UP, yBottom = py - SCAN_DOWN;
            if (top != UNKNOWN) {
                yTop = Math.min(yTop, top);
                // under the lava table there is only lava and rock (and further down another layer's caves)
                yBottom = Math.max(yBottom, table - 2);
            }
            for (int c = 0; c < SPOTS && lava == UNKNOWN; c++) {
                long s = spot(h, c);
                x = spotX(gx, s);
                z = spotZ(gz, s);
                lava = lavaUnderAir(w, x, z, yTop, yBottom);
            }
            if (lava == UNKNOWN) continue;
            out.puff(x + 0.5 + jx, lava + 1.1, z + 0.5 + jz, rise(h) * (1 + RISE_JITTER * (2 * u - 1)), 0.1, plume(gx, gz), d2 > NEAR * NEAR);
            n++;
        }
        return n;
    }

    /** The first lava under open air going down column (x, z) from yTop to yBottom, or {@link #UNKNOWN}. */
    static int lavaUnderAir(Blocks w, int x, int z, int yTop, int yBottom) {
        boolean open = false;
        for (int y = yTop; y >= yBottom; y--) {
            if (w.open(x, y, z)) {
                open = true;
                continue;
            }
            if (open && w.lava(x, y, z)) return y;
            open = false;
        }
        return UNKNOWN;
    }

    /** The lava under nothing but open air going down column (x, z) from yTop, or {@link #UNKNOWN} if rock comes first. */
    static int clearDrop(Blocks w, int x, int z, int yTop, int yBottom) {
        for (int y = yTop; y >= yBottom; y--) {
            if (w.open(x, y, z)) continue;
            return w.lava(x, y, z) ? y : UNKNOWN;
        }
        return UNKNOWN;
    }
}
