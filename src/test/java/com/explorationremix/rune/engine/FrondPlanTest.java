package com.explorationremix.rune.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where a struck silver fern's fronds unroll: up to the lip, down to the floor, free below an undercut, the sides. */
class FrondPlanTest {
    /**
     * A wall in the fern's frame, drawn top row first: three columns (side -1, 0, 1), the fern's row marked F in the
     * middle. '.' is open air with rock behind it, ' ' open air with nothing behind (the rock falls away), '#' solid.
     * Rows above and below the drawing are solid.
     */
    static FrondPlan.Probe wall(String... rows) {
        int fern = -1;
        for (int i = 0; i < rows.length; i++) if (rows[i].charAt(1) == 'F') fern = i;
        int f = fern;
        return new FrondPlan.Probe() {
            char at(int side, int dy) {
                int row = f - dy;
                return row < 0 || row >= rows.length ? '#' : rows[row].charAt(side + 1);
            }

            @Override
            public boolean open(int side, int dy) { return at(side, dy) == '.' || at(side, dy) == ' '; }

            @Override
            public boolean rock(int side, int dy) { return at(side, dy) == '.'; }
        };
    }

    static List<FrondPlan.Step> column(List<FrondPlan.Step> steps, int side) {
        return steps.stream().filter(s -> s.side() == side).sorted((a, b) -> b.dy() - a.dy()).collect(Collectors.toList());
    }

    @Test
    void onAPlainWallTheMiddleRunsUpToTheLipAndDownToTheFloor() {
        FrondPlan.Probe p = wall(
                "   ",   // above the lip: open, nothing behind
                "...",
                "...",
                ".F.",
                "...",
                "...",
                "...",
                "###");  // the floor
        List<FrondPlan.Step> steps = FrondPlan.fern(p, 42L);
        List<FrondPlan.Step> mid = column(steps, 0);
        assertEquals(List.of(2, 1, -1, -2, -3), mid.stream().map(FrondPlan.Step::dy).toList());
        assertTrue(mid.stream().allMatch(FrondPlan.Step::onRock), "all of it lies on the rock");
        // up and down together, a block a step from the fern
        assertEquals(List.of(1, 0, 0, 1, 2), mid.stream().map(FrondPlan.Step::order).toList());
        assertEquals(List.of(false, false, false, false, true), mid.stream().map(FrondPlan.Step::tip).toList(), "only the lowest is the tip");
    }

    @Test
    void belowAnUndercutTheMiddleHangsFreeAndTheSidesStopWhereTheRockDoes() {
        FrondPlan.Probe p = wall(
                "...",
                ".F.",
                "...",
                "   ",
                "   ",
                "   ",
                "###");
        List<FrondPlan.Step> steps = FrondPlan.fern(p, 7L);
        List<FrondPlan.Step> mid = column(steps, 0);
        assertEquals(List.of(1, -1, -2, -3, -4), mid.stream().map(FrondPlan.Step::dy).toList());
        assertEquals(List.of(true, true, false, false, false), mid.stream().map(FrondPlan.Step::onRock).toList());
        for (int side : new int[]{-1, 1}) {
            for (FrondPlan.Step s : column(steps, side)) {
                assertTrue(s.onRock(), "a side column never hangs free");
                assertTrue(s.dy() >= -1, "it stops where the rock does");
            }
        }
    }

    @Test
    void underAnOverhangNothingGoesUp() {
        FrondPlan.Probe p = wall(
                "###",
                ".F.",
                "...",
                "...",
                "###");
        List<FrondPlan.Step> steps = FrondPlan.fern(p, 1L);
        assertTrue(steps.stream().noneMatch(s -> s.dy() > 0));
        assertEquals(List.of(-1, -2), column(steps, 0).stream().map(FrondPlan.Step::dy).toList());
    }

    @Test
    void hemmedInAboveAndBelowItHasNowhereToGo() {
        assertTrue(FrondPlan.fern(wall("###", ".F.", "###"), 3L).isEmpty());
    }

    @Test
    void aTallWallIsCutAtTheReach() {
        String[] rows = new String[1 + FrondPlan.UP + 4 + FrondPlan.DOWN + 4];
        java.util.Arrays.fill(rows, "...");
        rows[FrondPlan.UP + 4] = ".F.";
        List<FrondPlan.Step> mid = column(FrondPlan.fern(wall(rows), 5L), 0);
        assertEquals(FrondPlan.UP, mid.get(0).dy());
        assertEquals(-FrondPlan.DOWN, mid.get(mid.size() - 1).dy());
    }

    @Test
    void theSidesAreShorterStartLaterAndAreTheSameEachTime() {
        String[] rows = new String[12];
        java.util.Arrays.fill(rows, "...");
        rows[3] = ".F.";
        rows[11] = "###";
        FrondPlan.Probe p = wall(rows);
        List<FrondPlan.Step> a = FrondPlan.fern(p, 99L), b = FrondPlan.fern(p, 99L);
        assertEquals(a, b, "a fern unrolls the same way each time");
        int midDown = (int) column(a, 0).stream().filter(s -> s.dy() < 0).count();
        for (int side : new int[]{-1, 1}) {
            List<FrondPlan.Step> col = column(a, side);
            assertFalse(col.isEmpty());
            long down = col.stream().filter(s -> s.dy() < 0).count();
            assertTrue(down >= Math.round(midDown * FrondPlan.SIDE_MIN) && down <= Math.round(midDown * FrondPlan.SIDE_MAX), "share " + down);
            assertTrue(col.stream().allMatch(s -> s.order() >= FrondPlan.SIDE_LAG));
            assertTrue(col.get(col.size() - 1).tip());
        }
        // sorted by when they unroll
        for (int i = 1; i < a.size(); i++) assertTrue(a.get(i - 1).order() <= a.get(i).order());
        assertEquals(a.stream().mapToInt(FrondPlan.Step::order).max().getAsInt() + 1, FrondPlan.length(a));
    }

    @Test
    void aHangingFrondUnrollsStraightDownToTheFloor() {
        FrondPlan.Probe p = wall(
                "#F#",
                " . ",
                "   ",
                "   ",
                "###");
        List<FrondPlan.Step> steps = FrondPlan.frond(p);
        assertEquals(List.of(-1, -2, -3), steps.stream().map(FrondPlan.Step::dy).toList());
        assertTrue(steps.stream().noneMatch(FrondPlan.Step::onRock));
        assertTrue(steps.get(2).tip());
        assertEquals(3, FrondPlan.length(steps));
    }
}
