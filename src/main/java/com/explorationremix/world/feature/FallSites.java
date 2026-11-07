package com.explorationremix.world.feature;

import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * Where the Basalt Galleries' lava falls may pour: sites spaced over the layer, worked out from the seed and the climate
 * alone, so a chunk finds its own sites without its neighbours' blocks and in any order (the 0.8.0 pack run counted
 * 1557 falls in 2400 chunks, side by side in curtains at the Caldera and the Causeway: tries per chunk, three to twelve,
 * each placing where it found a sealed spot).
 *
 * <p>Each {@code grid} x {@code grid} cell of a lattice holds one candidate at a hashed spot, drawn with
 * {@code chance}. In the Caldera only the candidates on its lake's rim count (the climate's distance from the patch's
 * middle, in the patch's radii, from {@code ring_min} to {@code ring_max}: the lake reaches 0.5 and its lava a little
 * past it over the shore's first blocks); everywhere else every candidate counts. The counted candidates are kept in
 * the order of their priorities (hashed), each where no kept one stands within {@code spacing} (random sequential
 * adsorption, Matérn's third thinning): a candidate is kept where every counted candidate of a higher priority within
 * the spacing is not, which reads only the candidates round it, out along chains of rising priority. So no two sites
 * stand closer than the spacing, they lie at random, not on a grid, and they pack about twice as close as when a
 * candidate gave way to every higher one in reach, kept or not.
 *
 * <p>A fall finds its own spot near its site, on a wall within a few blocks or a sealed patch of the vault, so the sites'
 * spacing alone would not keep the falls apart. So each of a fall's blocks must lie {@code keep} blocks or more nearer
 * its own site than any other kept site ({@link #clear}): such points of two sites lie on either side of the two
 * branches of a hyperbola whose foci are the sites, which nowhere come nearer each other than {@code keep}, so no two
 * falls do either.
 *
 * <p>With an {@code anchor} (fix round 2 after the 0.8.0 pack runs: spaced evenly through the halls' middles, the falls
 * stood in colonnades down the Causeway and the Scoria Canyons), a kept candidate outside the Caldera holds a fall only
 * by a wall or under a hanging mass ({@link #site}), so the falls pour down the halls' sides and off their masses.
 *
 * <p>With an {@code aim} as well (fix round 3: every fall poured from the high vault as a thin line, none from a hanging
 * mass, though a mass stood by a fifth of the sites), a site with no mass over it but one within the anchor's reach
 * pours from under that mass ({@link #aim}: GalleriesField.massNear, the deepest cluster whose underside stands
 * {@code aim} rows or more over the floor), and its try comes in at that column ({@link #aimsInChunk}), in the chunk
 * that holds it. The site stays where it is for the keep ({@link #clear}, {@link #siteOf}).
 */
public final class FallSites {
    /**
     * A set of sites: its salt, lattice, spacing, draw, the falls' keep, the Caldera's rim, the halls' anchor, the rows of
     * open air under a hanging mass a site's fall moves to (0: none) and whether only the candidates on a Caldera's rim
     * count (fix round 3).
     */
    public record Spec(int salt, int grid, double spacing, double chance, double keep, double ringMin, double ringMax, double anchor, int aim,
                       boolean rimOnly) {
        public Spec(int salt, int grid, double spacing, double chance, double keep, double ringMin, double ringMax) {
            this(salt, grid, spacing, chance, keep, ringMin, ringMax, 0, 0, false);
        }

        public Spec(int salt, int grid, double spacing, double chance, double keep, double ringMin, double ringMax, double anchor) {
            this(salt, grid, spacing, chance, keep, ringMin, ringMax, anchor, 0, false);
        }

        public static final Codec<Spec> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("salt").forGetter(Spec::salt),
                Codec.intRange(4, 64).optionalFieldOf("grid", 16).forGetter(Spec::grid),
                Codec.doubleRange(1, 128).fieldOf("spacing").forGetter(Spec::spacing),
                Codec.doubleRange(0, 1).optionalFieldOf("chance", 1.0).forGetter(Spec::chance),
                Codec.doubleRange(0, 64).optionalFieldOf("keep", 0.0).forGetter(Spec::keep),
                Codec.doubleRange(0, 9).optionalFieldOf("ring_min", 0.0).forGetter(Spec::ringMin),
                Codec.doubleRange(0, 9).optionalFieldOf("ring_max", 9.0).forGetter(Spec::ringMax),
                Codec.doubleRange(0, 32).optionalFieldOf("anchor", 0.0).forGetter(Spec::anchor),
                Codec.intRange(0, 128).optionalFieldOf("aim", 0).forGetter(Spec::aim),
                Codec.BOOL.optionalFieldOf("rim_only", false).forGetter(Spec::rimOnly)
        ).apply(i, Spec::new));
    }

    private FallSites() {}

    private static long hash(long seed, Spec s, int i, int j) { return Hashing.mix(seed, 610_000L + s.salt(), i, j); }

    /** Candidate (i, j)'s column, x and z. */
    public static int x(long seed, Spec s, int i, int j) { return i * s.grid() + (int) (Hashing.unit(Hashing.mix(hash(seed, s, i, j), 1, 0, 0)) * s.grid()); }

    public static int z(long seed, Spec s, int i, int j) { return j * s.grid() + (int) (Hashing.unit(Hashing.mix(hash(seed, s, i, j), 2, 0, 0)) * s.grid()); }

    private static double priority(long seed, Spec s, int i, int j) { return Hashing.unit(Hashing.mix(hash(seed, s, i, j), 3, 0, 0)); }

    /** Candidate (i, j)'s share, uniform in [0, 1): features that split a set of sites take their own range of it. */
    public static double share(long seed, Spec s, int i, int j) { return Hashing.unit(Hashing.mix(hash(seed, s, i, j), 4, 0, 0)); }

    /**
     * Whether candidate (i, j) is drawn and, in the Caldera, lies on its lake's rim; with {@code rim_only} (the Caldera's
     * own set, fix round 3), only a candidate on a rim counts, so the set's packing reads none of the halls' candidates,
     * which its falls never stand at: kept candidates there were never sites of its feature, yet a chain of rising
     * priorities ran through them, the longer the wider the spacing (a chunk's sites took 44 ms to find at 56, 1.3 at 40,
     * on a cold cache).
     */
    public static boolean counted(Terrain t, Spec s, int i, int j) {
        if (Hashing.unit(Hashing.mix(hash(t.seed, s, i, j), 5, 0, 0)) >= s.chance()) return false;
        if (t.galleries == null) return !s.rimOnly();
        double dn = t.galleries.calderaDn(x(t.seed, s, i, j) + 0.5, z(t.seed, s, i, j) + 0.5);
        return (dn > 8 && !s.rimOnly()) || (dn >= s.ringMin() && dn <= s.ringMax());
    }

    /**
     * Whether candidate (i, j) holds a fall: kept and, with an {@code anchor}, outside the Caldera by a wall or under a
     * hanging mass ({@code GalleriesField.fallAnchor}, within the anchor's blocks; fix round 2 after the 0.8.0 pack runs,
     * when the falls spaced through the halls' middles stood in colonnades). The anchor is weighed after the packing, on
     * the kept candidates only: so a hall's middle is left without a fall rather than filled from its walls, and the
     * packing reads no more than the climate, as before (the anchor reads the field's cells, which a candidate's chain of
     * priorities would otherwise read far round every chunk).
     */
    public static boolean site(Terrain t, Spec s, int i, int j) {
        if (!kept(t, s, i, j)) return false;
        if (s.anchor() <= 0 || t.galleries == null) return true;
        int x = x(t.seed, s, i, j), z = z(t.seed, s, i, j);
        return t.galleries.calderaDn(x + 0.5, z + 0.5) <= 8 || t.galleries.fallAnchor(x, z, s.anchor());
    }

    /**
     * Whether candidate (i, j) is a site: counted, with every counted candidate of a higher priority within the spacing
     * not a site.
     */
    public static boolean kept(Terrain t, Spec s, int i, int j) {
        Cache c = CACHE.get();
        int slot = c.slot(t.seed, s, i, j);
        if (c.state[slot] >= KEPT_NO) return c.state[slot] == KEPT_YES;
        boolean k = counted(c, t, s, i, j, slot);
        if (k) {
            long seed = t.seed;
            int x = x(seed, s, i, j), z = z(seed, s, i, j), r = (int) Math.ceil(s.spacing() / s.grid()) + 1;
            double p = priority(seed, s, i, j);
            for (int di = -r; di <= r && k; di++) for (int dj = -r; dj <= r && k; dj++) {
                if (di == 0 && dj == 0) continue;
                int ni = i + di, nj = j + dj;
                if (Math.hypot(x(seed, s, ni, nj) - x, z(seed, s, ni, nj) - z) >= s.spacing()) continue;
                double q = priority(seed, s, ni, nj);
                if (q < p || (q == p && (ni < i || (ni == i && nj < j)))) continue;
                if (kept(t, s, ni, nj)) k = false;   // rising priority: the chain ends
            }
        }
        slot = c.slot(t.seed, s, i, j);   // (the recursion may have taken the slot)
        c.state[slot] = k ? KEPT_YES : KEPT_NO;
        return k;
    }

    private static boolean counted(Cache c, Terrain t, Spec s, int i, int j, int slot) {
        if (c.state[slot] == UNREAD) c.state[slot] = counted(t, s, i, j) ? COUNTED : NOT_COUNTED;
        return c.state[slot] != NOT_COUNTED;
    }

    /**
     * The column site (i, j)'s fall pours from (fix round 3): with an {@code aim}, outside the Caldera, the column under
     * the hanging mass by it (GalleriesField.massNear within the anchor's reach, the site's own column when a mass hangs
     * over it), else, or with none, the site's own column. For a site ({@link #site}) only.
     */
    public static int[] aim(Terrain t, Spec s, int i, int j) {
        int x = x(t.seed, s, i, j), z = z(t.seed, s, i, j);
        if (s.aim() <= 0 || s.anchor() <= 0 || t.galleries == null || t.galleries.calderaDn(x + 0.5, z + 0.5) <= 8) return new int[]{x, z};
        int[] m = t.galleries.massNear(x, z, s.anchor(), s.aim());
        return m == null ? new int[]{x, z} : m;
    }

    /**
     * The columns the falls of the sites whose share lies in [shareMin, shareMax) pour from ({@link #aim}) that lie in
     * the 16 x 16 columns from (x0, z0), as {x, z} pairs: a site's try comes in where its fall pours, in that column's
     * chunk, which keeps every block of the fall ({@code FluidFallFeature}). Without an aim, {@link #inChunk}.
     */
    public static void aimsInChunk(Terrain t, Spec s, int x0, int z0, double shareMin, double shareMax, List<int[]> out) {
        if (s.aim() <= 0 || s.anchor() <= 0) {
            inChunk(t, s, x0, z0, shareMin, shareMax, out);
            return;
        }
        int g = s.grid(), r = (int) Math.ceil(s.anchor()) + 1;
        for (int i = Math.floorDiv(x0 - r, g); i <= Math.floorDiv(x0 + 15 + r, g); i++)
            for (int j = Math.floorDiv(z0 - r, g); j <= Math.floorDiv(z0 + 15 + r, g); j++) {
                int x = x(t.seed, s, i, j), z = z(t.seed, s, i, j);
                if (x < x0 - r || x >= x0 + 16 + r || z < z0 - r || z >= z0 + 16 + r) continue;
                double sh = share(t.seed, s, i, j);
                if (sh < shareMin || sh >= shareMax || !site(t, s, i, j)) continue;
                int[] a = aim(t, s, i, j);
                if (a[0] >= x0 && a[0] < x0 + 16 && a[1] >= z0 && a[1] < z0 + 16) out.add(a);
            }
    }

    /**
     * The site whose fall pours from column (x, z) ({@link #aim}), as {x, z}: the column itself without an aim, or where
     * no site's fall pours from it.
     */
    public static int[] siteOf(Terrain t, Spec s, int x, int z) {
        if (s.aim() <= 0 || s.anchor() <= 0) return new int[]{x, z};
        int g = s.grid(), r = (int) Math.ceil(s.anchor()) + 1;
        for (int i = Math.floorDiv(x - r, g); i <= Math.floorDiv(x + r, g); i++)
            for (int j = Math.floorDiv(z - r, g); j <= Math.floorDiv(z + r, g); j++) {
                int sx = x(t.seed, s, i, j), sz = z(t.seed, s, i, j);
                if (Math.abs(sx - x) > r || Math.abs(sz - z) > r || !site(t, s, i, j)) continue;
                int[] a = aim(t, s, i, j);
                if (a[0] == x && a[1] == z) return new int[]{sx, sz};
            }
        return new int[]{x, z};
    }

    /** The sites ({@link #site}) in the 16 x 16 columns from (x0, z0) whose share lies in [shareMin, shareMax), as {x, z} pairs. */
    public static void inChunk(Terrain t, Spec s, int x0, int z0, double shareMin, double shareMax, List<int[]> out) {
        int g = s.grid();
        for (int i = Math.floorDiv(x0, g); i <= Math.floorDiv(x0 + 15, g); i++)
            for (int j = Math.floorDiv(z0, g); j <= Math.floorDiv(z0 + 15, g); j++) {
                int x = x(t.seed, s, i, j), z = z(t.seed, s, i, j);
                if (x < x0 || x >= x0 + 16 || z < z0 || z >= z0 + 16) continue;
                double sh = share(t.seed, s, i, j);
                if (sh < shareMin || sh >= shareMax || !site(t, s, i, j)) continue;
                out.add(new int[]{x, z});
            }
    }

    /**
     * Whether a fall's block in column (x, z) keeps to the share of the site at (siteX, siteZ): {@code keep} blocks or
     * more nearer that site than any other site of the set (true where the keep is 0).
     */
    public static boolean clear(Terrain t, Spec s, int siteX, int siteZ, int x, int z) {
        if (s.keep() <= 0) return true;
        double px = x + 0.5, pz = z + 0.5, own = Math.hypot(px - siteX - 0.5, pz - siteZ - 0.5), reach = own + s.keep() + s.grid();
        int g = s.grid();
        for (int i = (int) Math.floor((px - reach) / g); i <= (int) Math.floor((px + reach) / g); i++)
            for (int j = (int) Math.floor((pz - reach) / g); j <= (int) Math.floor((pz + reach) / g); j++) {
                int sx = x(t.seed, s, i, j), sz = z(t.seed, s, i, j);
                if (sx == siteX && sz == siteZ) continue;
                double d = Math.hypot(px - sx - 0.5, pz - sz - 0.5);
                if (d - own >= s.keep() || !kept(t, s, i, j)) continue;
                return false;
            }
        return true;
    }

    private static final byte UNREAD = 0, NOT_COUNTED = 1, COUNTED = 2, KEPT_NO = 3, KEPT_YES = 4;

    /** The candidates a thread has read of late (64 x 64 slots of the lattice): unread, counted or not, kept or not. */
    private static final class Cache {
        final byte[] state = new byte[4096];
        final int[] i = new int[4096], j = new int[4096];
        final long[] seed = new long[4096];
        final Spec[] spec = new Spec[4096];

        /** Candidate (i, j)'s slot, emptied if it held another. */
        int slot(long sd, Spec s, int ci, int cj) {
            int k = (ci & 63) << 6 | (cj & 63);
            if (state[k] != UNREAD && (i[k] != ci || j[k] != cj || seed[k] != sd || !s.equals(spec[k]))) state[k] = UNREAD;
            if (state[k] == UNREAD) {
                i[k] = ci;
                j[k] = cj;
                seed[k] = sd;
                spec[k] = s;
            }
            return k;
        }
    }

    private static final ThreadLocal<Cache> CACHE = ThreadLocal.withInitial(Cache::new);
}
