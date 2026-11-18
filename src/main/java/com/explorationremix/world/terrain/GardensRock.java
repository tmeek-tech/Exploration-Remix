package com.explorationremix.world.terrain;

import static com.explorationremix.world.terrain.GardensPlan.K;
import static com.explorationremix.world.terrain.GardensPlan.SEAL;
import static com.explorationremix.world.terrain.GardensPlan.SY;

/**
 * The Deep Gardens' rock over a window (the previews' gen3.carve, layer.seal and carve_landings, gen3.talus_passes):
 * every storey opened from its floor to its ceiling with rough walls, the seal rows, the gate landings, then rubble
 * slopes under every drop. Rubble spreads one column a round from a drop, at most as far as the drop is high (40), so
 * a window is exact a margin of that in from its edges.
 *
 * <p>Blocks are indexed {@code (i * nz + j) * SY + y}: a column's rows lie together.
 */
final class GardensRock {
    /** What the gates carve and keep: their landings and the stubs of their passages. */
    interface Gates {
        /** Whether a block is open space of a gate (landing or passage). */
        boolean open(int wx, int y, int wz);

        /** Whether a block is kept from rubble (round a landing). */
        boolean keep(int wx, int y, int wz);

        /** Whether any gate's room can reach this column (a quick out). */
        boolean near(int wx, int wz);
    }

    /** The 2D fields the carve reads, by world column. */
    interface Storeys {
        float floor(int k, int wx, int wz);

        float ceil(int k, int wx, int wz);

        boolean foot(int k, int wx, int wz);

        /** The roughness of storey k's walls at a column. */
        float rough(int k, int wx, int wz);

        /** The low border (rubble pass 2 works under it plus 6). */
        float lowBorder(int wx, int wz);
    }

    final int x0, z0, nx, nz;
    final boolean[] solid, talus;

    GardensRock(GardensPlan plan, Storeys st, Gates gates, int x0, int z0, int nx, int nz) {
        this.x0 = x0;
        this.z0 = z0;
        this.nx = nx;
        this.nz = nz;
        int n = nx * nz;
        solid = new boolean[n * SY];
        java.util.Arrays.fill(solid, true);
        int[] p1 = plan.perm(plan.seed + 900), p2 = plan.perm(plan.seed + 901);
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                int wx = x0 + i, wz = z0 + j, base = (i * nz + j) * SY;
                for (int k = 0; k < K; k++) {
                    float f = st.floor(k, wx, wz), c = st.ceil(k, wx, wz);
                    if (c - f < 1f) continue;
                    double rough = st.rough(k, wx, wz);
                    int ya = Math.max(4, (int) f - 3), yb = Math.min(SY - 4, (int) c + 6);
                    for (int y = ya; y < yb; y++) {
                        if (!solid[base + y]) continue;
                        double r = rough * GardensNoise.n3(p1, wx / 10.0, y / 8.0, wz / 10.0);
                        double r2 = 0.5 * rough * GardensNoise.n3(p2, wx / 4.5, y / 4.5, wz / 4.5);
                        if (y > f + 0.2 * r2 && y < c + r + r2) solid[base + y] = false;
                    }
                }
                for (int y = 0; y < SEAL; y++) solid[base + y] = true;
                for (int y = SY - SEAL; y < SY; y++) solid[base + y] = true;
                if (gates.near(wx, wz))
                    for (int y = 0; y < SY; y++) if (gates.open(wx, y, wz)) solid[base + y] = false;
            }
        // rubble: kmax 12 everywhere, then 40 in the dome band and round the holes where storeys merge
        boolean[] keep = new boolean[n * SY];
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                int wx = x0 + i, wz = z0 + j;
                if (!gates.near(wx, wz)) continue;
                int base = (i * nz + j) * SY;
                for (int y = 0; y < SY; y++) keep[base + y] = gates.keep(wx, y, wz);
            }
        boolean[] before = solid.clone();
        talusRounds(solid, keep, null, 12, 3, 80);
        boolean[] hole = new boolean[n];
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                int wx = x0 + i, wz = z0 + j;
                for (int k = 0; k < K - 1 && !hole[i * nz + j]; k++)
                    hole[i * nz + j] = st.foot(k, wx, wz) && st.ceil(k, wx, wz) >= st.floor(k + 1, wx, wz) - 1f;
            }
        hole = GardensMath.dilateCross(hole, nx, nz, 4);
        boolean[] keep2 = keep.clone();
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                int q = i * nz + j, base = q * SY;
                if (hole[q]) continue;
                float lb6 = st.lowBorder(x0 + i, z0 + j) + 6f;
                for (int y = 0; y < SY; y++) if (!((float) y < lb6)) keep2[base + y] = true;
            }
        talusRounds(solid, keep2, null, 40, 3, 80);
        talus = new boolean[n * SY];
        for (int b = 0; b < talus.length; b++) talus[b] = solid[b] && !before[b];
    }

    /**
     * common.talus: rubble settles at the foot of every drop, round by round. An open block on a floor gets rubble when
     * a neighbouring column has a floor 2..kmax higher that a player could walk off into this column (open all the way
     * up to it), and {@code head} blocks of air stay over the rubble. Blocks in {@code keep} never take rubble; floors in
     * {@code nosrc} shed none. Each round reads the rock as the last left it; only columns next to a change are looked
     * at again, which gives the same rounds.
     */
    void talusRounds(boolean[] solid, boolean[] keep, boolean[] nosrc, int kmax, int head, int rounds) {
        talusRounds(solid, keep, nosrc, kmax, head, rounds, nx, nz);
    }

    /** {@link #talusRounds(boolean[], boolean[], boolean[], int, int, int)} over any window of nx by nz columns. */
    static void talusRounds(boolean[] solid, boolean[] keep, boolean[] nosrc, int kmax, int head, int rounds, int nx, int nz) {
        int n = nx * nz;
        boolean[] dirty = new boolean[n], next = new boolean[n];
        java.util.Arrays.fill(dirty, true);
        int[] add = new int[1024];
        for (int r = 0; r < rounds; r++) {
            int na = 0;
            for (int i = 1; i < nx - 1; i++)
                for (int j = 1; j < nz - 1; j++) {
                    int q = i * nz + j;
                    if (!dirty[q]) continue;
                    int base = q * SY;
                    for (int y = SEAL + 1; y < SY - SEAL - head - 1; y++) {
                        int b = base + y;
                        if (solid[b] || !solid[b - 1]) continue;
                        boolean ok = true;
                        for (int jj = 1; jj <= head; jj++)
                            if (solid[b + jj]) {
                                ok = false;
                                break;
                            }
                        if (!ok) continue;
                        boolean fill = false;
                        for (int d = 0; d < 4 && !fill; d++) {
                            int ni = i + (d == 0 ? 1 : (d == 1 ? -1 : 0)), nj = j + (d == 2 ? 1 : (d == 3 ? -1 : 0));
                            int nb = (ni * nz + nj) * SY;
                            for (int jj = 2; jj <= kmax; jj++) {
                                int yy = y + jj;
                                if (yy + 1 >= SY || solid[base + yy] || solid[base + yy + 1]) break;
                                if (solid[nb + yy - 1] && !solid[nb + yy] && !solid[nb + yy + 1]) {
                                    if (nosrc == null || !nosrc[nb + yy - 1]) fill = true;
                                    break;
                                }
                            }
                        }
                        if (fill && !keep[b]) {
                            if (na == add.length) add = java.util.Arrays.copyOf(add, na * 2);
                            add[na++] = b;
                        }
                    }
                }
            if (na == 0) break;
            java.util.Arrays.fill(next, false);
            for (int a = 0; a < na; a++) {
                int b = add[a];
                solid[b] = true;
                int q = b / SY, i = q / nz, j = q - i * nz;
                next[q] = true;
                if (i > 0) next[q - nz] = true;
                if (i < nx - 1) next[q + nz] = true;
                if (j > 0) next[q - 1] = true;
                if (j < nz - 1) next[q + 1] = true;
            }
            boolean[] t = dirty;
            dirty = next;
            next = t;
        }
    }

    boolean solidAt(int wx, int y, int wz) { return solid[((wx - x0) * nz + (wz - z0)) * SY + y]; }

    /** The preview's gate rooms: a plain ellipsoid landing and straight passage stubs (layer.carve_landings). */
    static Gates previewGates(java.util.List<GardensGates.Gate> gs, double landR, double landHalf) {
        return new Gates() {
            @Override
            public boolean open(int wx, int y, int wz) {
                float X = wx, Y = y, Z = wz;
                for (GardensGates.Gate g : gs) {
                    float cx = (float) g.x(), cz = (float) g.z(), cy = (float) (g.floor() + landHalf * 0.55);
                    float dx = X - cx, dz = Z - cz, dy = (Y - cy) / (float) landHalf;
                    float d = (dx * dx + dz * dz) / (float) (landR * landR) + dy * dy;
                    if (d < 1f && Y >= (float) g.floor()) return true;
                    float r2 = dx * dx + dz * dz;
                    if (g.entry()) {
                        if (r2 < 4.5f * 4.5f && Y > cy) return true;
                    } else {
                        float bx = X - cx - 6f;
                        float r2b = bx * bx + dz * dz;
                        if (r2b < 3.5f * 3.5f && Y < (float) (g.floor() + 3)) return true;
                    }
                }
                return false;
            }

            @Override
            public boolean keep(int wx, int y, int wz) {
                float X = wx, Y = y, Z = wz;
                for (GardensGates.Gate g : gs) {
                    float cx = (float) g.x(), cz = (float) g.z(), cy = (float) (g.floor() + landHalf * 0.55);
                    float dx = X - cx, dz = Z - cz, dy = (Y - cy) / (float) (landHalf + 2);
                    float d = (dx * dx + dz * dz) / (float) ((landR + 2) * (landR + 2)) + dy * dy;
                    if (d < 1f) return true;
                }
                return false;
            }

            @Override
            public boolean near(int wx, int wz) {
                for (GardensGates.Gate g : gs) if (Math.abs(wx - g.x()) < landR + 12 && Math.abs(wz - g.z()) < landR + 12) return true;
                return false;
            }
        };
    }
}
