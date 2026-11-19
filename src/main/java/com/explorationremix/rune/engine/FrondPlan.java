package com.explorationremix.rune.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where a struck silver fern's fronds go (RunePlants, 0.18.7). A fern hanging on a wall unrolls along the rock: up from
 * it to the lip of the wall, so the top of the curtain is level with the ledge and you can step off onto it, and down
 * from it to the floor, hanging on free where the rock falls away beneath it; and in the columns either side of it,
 * shorter runs where the rock goes on, so it opens as a curtain rather than a rope. A hanging frond unrolls straight down
 * to the floor. Pure: it asks a probe what is where (in the fern's own frame), so it is worked out and tested without
 * a world.
 */
public final class FrondPlan {
    /**
     * What a plan asks of the world, in the fern's frame: {@code side} along the wall (0 the fern's own column, -1 and
     * 1 either side of it), {@code dy} blocks up from the fern.
     */
    public interface Probe {
        /** Whether fronds can go there: it is open air. */
        boolean open(int side, int dy);

        /** Whether the rock behind that block (the wall the fern hangs on) is there to lie on. */
        boolean rock(int side, int dy);
    }

    /**
     * One block of fronds: where it is, whether it lies on the rock (or hangs free), when it unrolls (its {@code order},
     * in steps from the strike; blocks with the same order unroll together) and whether it is the lowest of its column
     * (drawn with the fronds' tip).
     */
    public record Step(int side, int dy, boolean onRock, int order, boolean tip) {}

    /** How far the fronds run up to the lip, and down to the floor, in the fern's column. */
    public static final int UP = 8, DOWN = 20;
    /** The columns either side reach this share of the fern's column, chosen per fern between these. */
    static final double SIDE_MIN = 0.35, SIDE_MAX = 0.7;
    /** They start unrolling this many steps after the middle one. */
    static final int SIDE_LAG = 2;

    private FrondPlan() {}

    /**
     * A hanging fern's curtain. {@code seed} (the fern's position) chooses how far the side columns reach, so a fern
     * unrolls the same way each time. Empty when there is nowhere for fronds to go.
     */
    public static List<Step> fern(Probe p, long seed) {
        List<Step> out = new ArrayList<>();
        // the fern's own column: up to the lip, then down to the floor
        int up = 0;
        for (int dy = 1; dy <= UP && p.open(0, dy) && p.rock(0, dy); dy++) {
            out.add(new Step(0, dy, true, dy - 1, false));
            up = dy;
        }
        int down = 0;
        for (int dy = -1; dy >= -DOWN && p.open(0, dy); dy--) {
            out.add(new Step(0, dy, p.rock(0, dy), -dy - 1, false));
            down = -dy;
        }
        markTip(out, 0);
        // hemmed in above and below: it has nowhere to unroll
        if (out.isEmpty()) return out;
        // either side: shorter, and only along the rock (they never hang free)
        java.util.Random r = new java.util.Random(seed * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L);
        for (int side : new int[]{-1, 1}) {
            double share = SIDE_MIN + r.nextDouble() * (SIDE_MAX - SIDE_MIN);
            int sideDown = (int) Math.round(down * share), sideUp = (int) Math.round(up * share * 0.8);
            if (!p.open(side, 0) || !p.rock(side, 0)) continue;
            out.add(new Step(side, 0, true, SIDE_LAG, false));
            for (int dy = 1; dy <= sideUp && p.open(side, dy) && p.rock(side, dy); dy++) out.add(new Step(side, dy, true, SIDE_LAG + dy, false));
            for (int dy = -1; dy >= -sideDown && p.open(side, dy) && p.rock(side, dy); dy--) out.add(new Step(side, dy, true, SIDE_LAG - dy, false));
            markTip(out, side);
        }
        out.sort(Comparator.comparingInt(Step::order).thenComparingInt(s -> Math.abs(s.side())));
        return out;
    }

    /** A hanging frond's run: straight down from below its tip ({@code dy} 0) to the floor, hanging free. */
    public static List<Step> frond(Probe p) {
        List<Step> out = new ArrayList<>();
        for (int dy = -1; dy >= -DOWN && p.open(0, dy); dy--) out.add(new Step(0, dy, false, -dy - 1, false));
        markTip(out, 0);
        return out;
    }

    /** The lowest step of column {@code side} is its tip. */
    private static void markTip(List<Step> steps, int side) {
        int low = -1;
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (s.side() == side && (low < 0 || s.dy() < steps.get(low).dy())) low = i;
        }
        if (low >= 0 && steps.get(low).dy() < 0) {
            Step s = steps.get(low);
            steps.set(low, new Step(s.side(), s.dy(), s.onRock(), s.order(), true));
        }
    }

    /** How many steps the whole curtain takes to unroll: one more than its last step's order. */
    public static int length(List<Step> steps) {
        int n = 0;
        for (Step s : steps) n = Math.max(n, s.order() + 1);
        return n;
    }
}
