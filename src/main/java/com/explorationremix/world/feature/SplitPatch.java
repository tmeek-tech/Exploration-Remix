package com.explorationremix.world.feature;

import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Perlin;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shape of a {@link SplitPatchFeature} patch and the split of its ground between its two covers: where a patch round a
 * middle covers the floor (an ellipse whose outline a fine field pushes in and out, so no patch shows a straight or
 * square edge), and which blocks take the bare cover (where a second field, over the world's x and z, lies in its
 * lowest {@code bareShare}). Both are pure functions of the world's seed and the block's x and z, so patches that overlap
 * agree on every block, and the same seed gives the same floors.
 *
 * <p>0.24.6 (Taran, 2026-11-28: "Can you replace about half of the moss in Ivory Root Caverns with limestone?"): the halls'
 * moss patches are as many and as big as before, and half of each, in rounded pieces a few blocks across, is limestone.
 */
public final class SplitPatch {
    /** How far a patch's outline wanders in or out, as a share of its radius. */
    static final double EDGE = 0.45;
    /** How many blocks across the outline's wobble is (its slow part; a fine part half as wide rides on it). */
    static final double EDGE_SCALE = 3.2;
    /** The split field's fine part, as a share of its slow part's weight and of its width. */
    static final double SPLIT_FINE = 0.3, SPLIT_FINE_SCALE = 0.35;

    private static final Map<Key, SplitPatch> CACHE = new ConcurrentHashMap<>();

    private record Key(long seed, double bareShare, double splitScale) {}

    private final Perlin edgeA, edgeB, splitA, splitB;
    private final double splitScale;
    /** The split field's value under which a block is bare: its {@code bareShare} quantile. */
    private final double threshold;

    public SplitPatch(long seed, double bareShare, double splitScale) {
        long s = Hashing.mix(seed, 0x53706C74L, 0x4D6F7373L, 0);
        edgeA = new Perlin(Hashing.mix(s, 1, 0, 0));
        edgeB = new Perlin(Hashing.mix(s, 2, 0, 0));
        splitA = new Perlin(Hashing.mix(s, 3, 0, 0));
        splitB = new Perlin(Hashing.mix(s, 4, 0, 0));
        this.splitScale = Math.max(0.5, splitScale);
        threshold = quantile(bareShare);
    }

    /** The patch shape for a world's seed, made once for each seed and split. */
    public static SplitPatch of(long seed, double bareShare, double splitScale) {
        if (CACHE.size() > 32) CACHE.clear();
        return CACHE.computeIfAbsent(new Key(seed, bareShare, splitScale), k -> new SplitPatch(k.seed, k.bareShare, k.splitScale));
    }

    /** The outline's push at a column, about -1 to 1. */
    public double edge(int x, int z) {
        double v = 1.25 * (edgeA.noise2(x / EDGE_SCALE, z / EDGE_SCALE) + 0.5 * edgeB.noise2(x / (0.5 * EDGE_SCALE), z / (0.5 * EDGE_SCALE)));
        return Math.max(-1, Math.min(1, v));
    }

    /**
     * Whether the column {@code dx}, {@code dz} from a patch's middle (world column {@code x}, {@code z}) lies inside the
     * patch, whose half widths across x and z are {@code ax} and {@code az}.
     */
    public boolean inside(int x, int z, double dx, double dz, double ax, double az) {
        double q = Math.sqrt(dx * dx / (ax * ax) + dz * dz / (az * az));
        if (q >= 1 + EDGE) return false;
        return q < 1 + EDGE * edge(x, z);
    }

    /** How far from a patch's middle its outline can reach, in blocks, for half widths up to {@code a}. */
    public static int reach(double a) { return (int) Math.ceil(a * (1 + EDGE)); }

    /** The split field at a column (its slow part, with a fine part riding on it), about -1 to 1. */
    public double split(int x, int z) {
        double f = SPLIT_FINE_SCALE * splitScale;
        return splitA.noise2(x / splitScale, z / splitScale) + SPLIT_FINE * splitB.noise2(x / f + 17.3, z / f - 5.1);
    }

    /** Whether a column of a patch takes the bare cover. */
    public boolean bare(int x, int z) { return split(x, z) < threshold; }

    /** The split field's value with {@code share} of the world's columns under it, sampled once over a wide square. */
    private double quantile(double share) {
        if (share <= 0) return Double.NEGATIVE_INFINITY;
        if (share >= 1) return Double.POSITIVE_INFINITY;
        int n = 8192;
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            long h = Hashing.mix(0x51756E74L, i, 7, 11);
            int x = (int) (Hashing.unit(h) * 40000) - 20000, z = (int) (Hashing.unit(Hashing.mix(h, 1, 2, 3)) * 40000) - 20000;
            v[i] = split(x, z);
        }
        Arrays.sort(v);
        return v[Math.min(n - 1, (int) Math.round(share * n))];
    }
}
